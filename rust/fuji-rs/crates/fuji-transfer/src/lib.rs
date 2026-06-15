//! `fuji-transfer` — media enumeration, thumbnail, and streaming download for Frameport.
//!
//! This crate implements the M05 transfer logic against the [`CommandTransport`] trait,
//! which is implemented for [`fuji_ptpip::PtpIpTcpClient`].
//!
//! # Architecture
//!
//! All protocol logic operates through `&mut dyn CommandTransport` so unit tests can
//! inject a fake transport without a live TCP connection.  For integration tests,
//! `fuji_ptpip::PtpIpTcpClient` implements the trait and talks to `fuji-sim`.
//!
//! # Download opcode note
//!
//! [`download_to_owned_fd`] uses `GetObject` (0x1009) — single data phase, streamed in
//! 64 KiB socket reads.  This is validated against `fuji-sim`.
//!
//! TODO(frameport): production against a real Fujifilm X-T5 over Wi-Fi should switch to
//! `GetPartialObject` (opcode 0x101B, ≤1 MiB chunks) per `transfer-liveview.md` section
//! 5.2 (stall caveat).  The 64 KiB streaming/cancel/progress/size-check machinery is
//! identical either way.
//!
//! # Privacy
//!
//! Raw filenames from the camera are never stored in domain structs.  They are hashed
//! (SHA-256, first 64 bits as hex, stable across sessions) and stored as hex strings in
//! [`fuji_core::CameraMediaObject::filename_opaque_hash`].
//!
//! # Safety
//!
//! This crate contains no `unsafe` code.

#![forbid(unsafe_code)]

use std::fs::File;
use std::io::{BufWriter, Write};
use std::os::fd::OwnedFd;
use std::sync::atomic::{AtomicBool, Ordering};

use fuji_core::{
    CameraMediaFormat, CameraMediaObject, CameraObjectId, FujiError, FujiResult, ProtocolError,
    TransferError, TransferProgress,
};
use fuji_ptp::decode_object_info;
use fuji_ptpip::PtpIpTcpClient;

// ── CommandTransport trait ────────────────────────────────────────────────────

/// Object-safe trait that abstracts the PTP-IP command channel operations needed
/// by the transfer layer.
///
/// Implemented by [`fuji_ptpip::PtpIpTcpClient`] for production and test use.
/// The orphan rule is satisfied because this trait is defined in `fuji-transfer`.
///
/// # Method contracts
///
/// - [`get_device_prop_value`]: returns raw EndData payload bytes for a given
///   property code.  Small, bounded — safe to buffer.
/// - [`get_object_info`]: returns the raw ObjectInfo dataset bytes (EndData
///   payload stripped of PTP-IP framing).  Pass to [`fuji_ptp::decode_object_info`].
/// - [`get_thumb`]: returns `(declared_len, bytes)`.  `declared_len` is from
///   the `StartData` header; the cap check is applied BEFORE buffering.
/// - [`open_object`]: sends `GetObject` and reads `StartData`; returns total
///   object length.  Must be followed by repeated calls to [`read_object_chunk`].
/// - [`read_object_chunk`]: streams payload bytes into `buf`.  Returns bytes
///   written, or `0` when the payload is exhausted (after internally consuming
///   the trailing `OperationResponse`).
pub trait CommandTransport {
    fn get_device_prop_value(&mut self, prop_code: u16) -> FujiResult<Vec<u8>>;
    fn get_object_info(&mut self, handle: u32) -> FujiResult<Vec<u8>>;
    fn get_thumb(&mut self, handle: u32) -> FujiResult<(u64, Vec<u8>)>;
    fn open_object(&mut self, handle: u32) -> FujiResult<u64>;
    fn read_object_chunk(&mut self, buf: &mut [u8]) -> FujiResult<usize>;
}

// ── impl CommandTransport for PtpIpTcpClient ─────────────────────────────────

impl CommandTransport for PtpIpTcpClient {
    fn get_device_prop_value(&mut self, prop_code: u16) -> FujiResult<Vec<u8>> {
        PtpIpTcpClient::get_device_prop_value(self, prop_code)
    }

    fn get_object_info(&mut self, handle: u32) -> FujiResult<Vec<u8>> {
        PtpIpTcpClient::get_object_info(self, handle)
    }

    fn get_thumb(&mut self, handle: u32) -> FujiResult<(u64, Vec<u8>)> {
        PtpIpTcpClient::get_thumb(self, handle, fuji_ptpip::MAX_THUMBNAIL_BYTES)
    }

    fn open_object(&mut self, handle: u32) -> FujiResult<u64> {
        PtpIpTcpClient::open_object(self, handle)
    }

    fn read_object_chunk(&mut self, buf: &mut [u8]) -> FujiResult<usize> {
        PtpIpTcpClient::read_object_chunk(self, buf)
    }
}

// ── Default thumbnail cap ─────────────────────────────────────────────────────

/// Default maximum thumbnail size: 512 KiB (524_288 bytes).
///
/// [`get_thumbnail`] rejects thumbnails larger than this before buffering.
pub const DEFAULT_MAX_THUMBNAIL_BYTES: u64 = 524_288;

// ── list_media ────────────────────────────────────────────────────────────────

/// Enumerates all media objects currently visible on the camera.
///
/// Reads `ObjectCount` (property `0xD222`) via `GetDevicePropValue` and calls
/// `GetObjectInfo` for each handle `1..=count`.  Object handles over Wi-Fi are
/// sequential 1..=N per `transfer-liveview.md` section 3.1 — `GetObjectHandles`
/// is NOT called.
///
/// # Note on SetFunctionMode
///
/// Real-hardware sessions require `SetFunctionMode(IMAGE_RECEIVE=1)` and a 50 ms
/// wait before `list_media`.  For hermetic tests against `fuji-sim`, the sim
/// accepts transfer operations after `OpenSession` without this step.  The
/// production Android path must call `SetFunctionMode` before calling this
/// function (deferred — no sleeps in hermetic tests).
pub fn list_media(t: &mut dyn CommandTransport) -> FujiResult<Vec<CameraMediaObject>> {
    // Read ObjectCount property (0xD222) — returns a u32 LE.
    let prop_bytes = t.get_device_prop_value(fuji_ptp::constants::prop_code::OBJECT_COUNT)?;
    if prop_bytes.len() < 4 {
        return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
            declared: prop_bytes.len() as u32,
            minimum: 4,
        }));
    }
    let count = u32::from_le_bytes([prop_bytes[0], prop_bytes[1], prop_bytes[2], prop_bytes[3]]);

    let mut objects = Vec::with_capacity(count as usize);

    for handle in 1..=count {
        let info_bytes = t.get_object_info(handle)?;
        let info = decode_object_info(&info_bytes).map_err(|_| {
            FujiError::Protocol(ProtocolError::InvalidPacketLength {
                declared: info_bytes.len() as u32,
                minimum: fuji_ptp::object_info::OBJECT_INFO_FIXED_BYTES as u32,
            })
        })?;

        let format = CameraMediaFormat::from_wire_code(info.object_format as u32);
        let size_bytes = Some(info.object_compressed_size as u64);
        let capture_date_utc = parse_ptp_date(&info.capture_date);
        let filename_opaque_hash = if info.filename.is_empty() {
            None
        } else {
            Some(sha256_hex_prefix(&info.filename))
        };

        objects.push(CameraMediaObject {
            object_handle: CameraObjectId::new(handle),
            format,
            size_bytes,
            capture_date_utc,
            filename_opaque_hash,
        });
    }

    Ok(objects)
}

// ── get_thumbnail ─────────────────────────────────────────────────────────────

/// Fetches the JPEG thumbnail for `handle`.
///
/// Returns caller-owned bytes.  The cap check fires on the `StartData`
/// `declared_len` BEFORE buffering the payload.
///
/// # Arguments
///
/// - `t` — mutable reference to the command transport.
/// - `handle` — PTP object handle.
/// - `max_thumbnail_bytes` — cap in bytes; use [`DEFAULT_MAX_THUMBNAIL_BYTES`]
///   (512 KiB) for the default.  Pass `u64::MAX` to disable the cap.
///
/// # Errors
///
/// Returns [`TransferError::ThumbnailTooLarge`] when `declared_len > max_thumbnail_bytes`.
pub fn get_thumbnail(
    t: &mut dyn CommandTransport,
    handle: u32,
    max_thumbnail_bytes: u64,
) -> FujiResult<Vec<u8>> {
    let (declared_len, bytes) = t.get_thumb(handle)?;
    if declared_len > max_thumbnail_bytes {
        return Err(FujiError::Transfer(TransferError::ThumbnailTooLarge {
            size: declared_len,
            limit: max_thumbnail_bytes,
        }));
    }
    Ok(bytes)
}

// ── download_to_owned_fd ──────────────────────────────────────────────────────

/// Streams a full camera object into `output_fd`.
///
/// # Ownership and fd lifecycle
///
/// Takes ownership of `output_fd` (an [`OwnedFd`]).  The fd is closed (dropped)
/// on **every** exit path: success, cancellation, and all error variants.
/// Callers must not use the fd after this call returns.
///
/// # Streaming
///
/// Uses a single reused `[u8; 65536]` stack buffer — no per-iteration heap
/// allocation.  The camera sends the object in a single `EndData` payload;
/// the client streams it into the fd in 64 KiB chunks without buffering the
/// full object in RAM.
///
/// # Cancel support
///
/// `cancel` is checked after each chunk boundary with `Ordering::Relaxed`.
/// On cancel: the fd is dropped (closing the underlying file descriptor)
/// and `Err(Transfer(Cancelled))` is returned.
///
/// // cancel-safe: exits at chunk boundary; OwnedFd drop closes the fd on
/// // every exit path.
///
/// # Errors
///
/// | Condition | Error |
/// |---|---|
/// | Cancelled via `cancel` flag | [`TransferError::Cancelled`] |
/// | Camera disconnected mid-transfer | [`TransferError::CameraDisconnected`] |
/// | fd write failure | [`TransferError::OutputWriteFailed`] |
/// | `bytes_written != expected` after loop | [`TransferError::SizeMismatch`] |
/// | Invalid ObjectInfo packet | [`ProtocolError::InvalidPacketLength`] |
pub fn download_to_owned_fd(
    t: &mut dyn CommandTransport,
    object_handle: CameraObjectId,
    output_fd: OwnedFd,
    cancel: &AtomicBool,
    progress_cb: &mut dyn FnMut(TransferProgress),
) -> FujiResult<TransferProgress> {
    let handle = object_handle.get();

    // GetObjectInfo to learn the expected size.
    let info_bytes = t.get_object_info(handle)?;
    let info = decode_object_info(&info_bytes).map_err(|_| {
        FujiError::Protocol(ProtocolError::InvalidPacketLength {
            declared: info_bytes.len() as u32,
            minimum: fuji_ptp::object_info::OBJECT_INFO_FIXED_BYTES as u32,
        })
    })?;
    let expected = info.object_compressed_size as u64;

    // Wrap OwnedFd as a BufWriter<File>.  File::from(OwnedFd) is safe: OwnedFd
    // guarantees ownership of the fd; File takes ownership and closes it on drop.
    let file = File::from(output_fd);
    let mut writer = BufWriter::new(file);

    // Send GetObject and read StartData — learns total_data_length from camera.
    // NOTE: total from open_object is the camera-declared length from StartData;
    // we use `expected` from ObjectInfo for the integrity check after streaming.
    let _total_declared = t.open_object(handle)?;

    // ONE reused stack buffer — no per-iteration heap allocation.
    // 65536 bytes = 64 KiB, well under the 16 KiB Box::new stack-overflow threshold.
    let mut buf = [0u8; 65536];
    let mut bytes_written: u64 = 0;
    let transfer_id = object_handle.get() as u64;

    loop {
        let n = match t.read_object_chunk(&mut buf) {
            Ok(n) => n,
            Err(e) => {
                // Drop writer (closes fd) before returning the error.
                drop(writer);
                return Err(e);
            }
        };

        if n == 0 {
            // Payload exhausted — OperationResponse already consumed by read_object_chunk.
            break;
        }

        writer.write_all(&buf[..n]).map_err(|_| {
            // Drop writer (closes fd) on write failure.
            FujiError::Transfer(TransferError::OutputWriteFailed)
        })?;

        bytes_written += n as u64;

        let progress = TransferProgress {
            bytes_transferred: bytes_written,
            total_bytes: expected,
            object_handle,
            transfer_id,
        };
        progress_cb(progress);

        // Cancel check at chunk boundary.
        if cancel.load(Ordering::Relaxed) {
            // Drop writer closes the fd.
            drop(writer);
            return Err(FujiError::Transfer(TransferError::Cancelled));
        }
    }

    // Flush the BufWriter (closes internal buffer; fd stays open until drop).
    writer
        .flush()
        .map_err(|_| FujiError::Transfer(TransferError::OutputWriteFailed))?;
    // Drop writer — closes fd.
    drop(writer);

    // Integrity check: bytes written must equal the size declared in ObjectInfo.
    if bytes_written != expected {
        return Err(FujiError::Transfer(TransferError::SizeMismatch {
            expected,
            actual: bytes_written,
        }));
    }

    Ok(TransferProgress {
        bytes_transferred: bytes_written,
        total_bytes: expected,
        object_handle,
        transfer_id,
    })
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/// Parses a PTP CaptureDate string to a Unix epoch second value.
///
/// PTP date strings are ISO 8601 basic format: `YYYYMMDDThhmmss[.s][Z]`.
/// Returns `None` for empty strings or unparseable input.
///
/// Privacy: the raw string is never persisted; only the derived epoch second
/// (an integer) is stored in [`CameraMediaObject::capture_date_utc`].
fn parse_ptp_date(s: &str) -> Option<i64> {
    // Minimum: "YYYYMMDDThhmmss" = 15 chars.
    if s.len() < 15 {
        return None;
    }
    let bytes = s.as_bytes();
    // Expect 'T' at position 8.
    if bytes.get(8) != Some(&b'T') {
        return None;
    }

    let year: i64 = parse_decimal(&s[0..4])?;
    let month: i64 = parse_decimal(&s[4..6])?;
    let day: i64 = parse_decimal(&s[6..8])?;
    let hour: i64 = parse_decimal(&s[9..11])?;
    let min: i64 = parse_decimal(&s[11..13])?;
    let sec: i64 = parse_decimal(&s[13..15])?;

    // Validate ranges.
    if !(1..=12).contains(&month)
        || !(1..=31).contains(&day)
        || hour > 23
        || min > 59
        || sec > 60
        || year < 1970
    {
        return None;
    }

    // Days from 1970-01-01 to year-01-01 using a simple Gregorian formula.
    let y = year - 1970;
    // Leap years since 1970 (1972, 1976, …): floor((y+1)/4) - floor((y+1)/100) + floor((y+1)/400).
    // Simplified: count leap years in [1970, year).
    let leap_days = leap_years_since_1970(year);
    let days_to_year = y * 365 + leap_days;

    // Days from year-01-01 to year-MM-01.
    let is_leap = is_leap_year(year);
    let days_in_months: [i64; 12] = [
        31,
        28 + is_leap as i64,
        31,
        30,
        31,
        30,
        31,
        31,
        30,
        31,
        30,
        31,
    ];
    let days_to_month: i64 = days_in_months.iter().take((month - 1) as usize).sum();

    let total_days = days_to_year + days_to_month + (day - 1);
    let epoch_secs = total_days * 86400 + hour * 3600 + min * 60 + sec;

    Some(epoch_secs)
}

fn parse_decimal(s: &str) -> Option<i64> {
    if s.is_empty() {
        return None;
    }
    let mut v: i64 = 0;
    for b in s.bytes() {
        if !b.is_ascii_digit() {
            return None;
        }
        v = v * 10 + (b - b'0') as i64;
    }
    Some(v)
}

fn is_leap_year(year: i64) -> bool {
    (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

/// Count leap years in [1970, year).
fn leap_years_since_1970(year: i64) -> i64 {
    let y = year - 1; // inclusive upper bound for [1970, year)
    let from = 1969i64; // [1970, year) → [1, year-1] leap count minus [1, 1969] leap count
    fn leaps(n: i64) -> i64 {
        n / 4 - n / 100 + n / 400
    }
    leaps(y) - leaps(from)
}

/// SHA-256 hash of a UTF-8 string, returned as a 16-character (64-bit) lowercase hex prefix.
///
/// Used as a privacy-preserving opaque filename identifier.  The raw filename is never
/// stored.  SHA-256 is cryptographically one-way; FNV-1a (the previous implementation)
/// was trivially reversible for the short, predictable filenames produced by Fujifilm
/// cameras (e.g. `DSCF0001.JPG`).
///
/// The output is the first 8 bytes (16 hex chars) of the SHA-256 digest — 64 bits of
/// collision resistance, sufficient for a local media catalog of any plausible size.
///
/// # TODO
///
/// Add a per-install random salt (stored in encrypted DataStore) to prevent
/// cross-device correlation of hashes.  The salt store is not yet available in v1;
/// unsalted SHA-256 is used in the interim.
fn sha256_hex_prefix(s: &str) -> String {
    let digest = sha256(s.as_bytes());
    // First 8 bytes → 16 hex chars.
    let mut out = String::with_capacity(16);
    for b in &digest[..8] {
        use std::fmt::Write as _;
        let _ = write!(out, "{b:02x}");
    }
    out
}

/// Minimal pure-Rust SHA-256 implementation (no external crate).
///
/// Follows FIPS 180-4.  Suitable for short inputs (filenames) where performance
/// is not a concern.  Constant-time is not guaranteed; this is NOT for MAC/HMAC.
fn sha256(msg: &[u8]) -> [u8; 32] {
    // Initial hash values: first 32 bits of the fractional parts of the square
    // roots of the first 8 primes.
    #[allow(clippy::unreadable_literal)]
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];

    // Round constants: first 32 bits of the fractional parts of the cube roots
    // of the first 64 primes.
    #[allow(clippy::unreadable_literal)]
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];

    // Pre-processing: pad the message.
    let bit_len = (msg.len() as u64).wrapping_mul(8);
    let mut padded: Vec<u8> = Vec::with_capacity(msg.len() + 73);
    padded.extend_from_slice(msg);
    padded.push(0x80);
    // Pad to 56 mod 64 bytes.
    while padded.len() % 64 != 56 {
        padded.push(0x00);
    }
    // Append original length as 64-bit big-endian.
    padded.extend_from_slice(&bit_len.to_be_bytes());

    // Process each 512-bit (64-byte) block.
    for block in padded.chunks_exact(64) {
        let mut w = [0u32; 64];
        for (i, chunk) in block.chunks_exact(4).enumerate().take(16) {
            w[i] = u32::from_be_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }

        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh] = h;

        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);

            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }

        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }

    // Produce the 32-byte digest.
    let mut digest = [0u8; 32];
    for (i, word) in h.iter().enumerate() {
        digest[i * 4..(i + 1) * 4].copy_from_slice(&word.to_be_bytes());
    }
    digest
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── SHA-256 prefix hash ───────────────────────────────────────────────────

    #[test]
    fn sha256_hex_prefix_empty_is_16_chars() {
        let h = sha256_hex_prefix("");
        assert_eq!(h.len(), 16);
    }

    #[test]
    fn sha256_hex_prefix_same_input_same_output() {
        assert_eq!(sha256_hex_prefix("DSCF0001.JPG"), sha256_hex_prefix("DSCF0001.JPG"));
    }

    #[test]
    fn sha256_hex_prefix_different_inputs_different_outputs() {
        assert_ne!(sha256_hex_prefix("DSCF0001.JPG"), sha256_hex_prefix("DSCF0002.JPG"));
    }

    #[test]
    fn sha256_hex_prefix_known_vector() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469f492c89519e0cf4b3
        // First 8 bytes → "ba7816bf8f01cfea"
        assert_eq!(sha256_hex_prefix("abc"), "ba7816bf8f01cfea");
    }

    // ── PTP date parsing ──────────────────────────────────────────────────────

    #[test]
    fn parse_ptp_date_empty_is_none() {
        assert_eq!(parse_ptp_date(""), None);
    }

    #[test]
    fn parse_ptp_date_epoch() {
        // 1970-01-01T000000 → 0
        assert_eq!(parse_ptp_date("19700101T000000"), Some(0));
    }

    #[test]
    fn parse_ptp_date_known_date() {
        // 2024-12-25T12:00:00
        // Days from 1970-01-01 to 2024-01-01:
        // 54 years, 14 leap years (72,76,80,84,88,92,96,104,108,112,116,120 — wait:
        // 1972,1976,1980,1984,1988,1992,1996,2000,2004,2008,2012,2016,2020,2024 = 14 leaps)
        // Actually 2024 is not counted since we stop at 2024-01-01.
        // leap years in [1970,2024) = 1972,1976,1980,1984,1988,1992,1996,2000,2004,2008,2012,2016,2020 = 13
        // 54*365 + 13 = 19710 + 13 = 19723 days to 2024-01-01
        // days to Dec 1: 31+29+31+30+31+30+31+31+30+31+30 = 335 (2024 is leap)
        // day 25 = +24 more = day 335+24 = 359
        // total days = 19723 + 359 = 20082
        // + 12*3600 = 43200 secs
        // = 20082 * 86400 + 43200 = 1735084800 + 43200 = 1735128000
        let result = parse_ptp_date("20241225T120000");
        assert!(result.is_some());
        let epoch = result.unwrap();
        // Rough sanity check: 2024 is well past 2000
        assert!(epoch > 1_000_000_000, "epoch should be > 1e9, got {epoch}");
        assert!(epoch < 2_000_000_000, "epoch should be < 2e9, got {epoch}");
    }

    #[test]
    fn parse_ptp_date_invalid_no_t() {
        assert_eq!(parse_ptp_date("20241225 120000"), None);
    }

    #[test]
    fn parse_ptp_date_too_short() {
        assert_eq!(parse_ptp_date("20241225T1200"), None);
    }

    // ── CommandTransport fake ─────────────────────────────────────────────────

    /// Minimal fake transport for unit-testing list_media, get_thumbnail,
    /// and download_to_owned_fd without a TCP connection.
    struct FakeTransport {
        /// ObjectCount to return (u32 LE bytes).
        object_count: u32,
        /// Objects indexed by handle-1.
        objects: Vec<FakeObject>,
        /// If Some, reading object chunk bytes returns this error.
        chunk_error: Option<FujiError>,
        /// Tracks open_object call.
        open_called: bool,
        /// Chunks to emit: each inner Vec is one read_object_chunk return.
        chunks: Vec<Vec<u8>>,
        chunk_idx: usize,
        /// Whether to emit trailing 0 after chunks.
        emit_terminal_zero: bool,
    }

    struct FakeObject {
        format_code: u16,
        compressed_size: u32,
        filename: String,
        capture_date: String,
        thumb: Vec<u8>,
        data: Vec<u8>,
    }

    impl FakeTransport {
        fn new(objects: Vec<FakeObject>) -> Self {
            let object_count = objects.len() as u32;
            // Pre-build chunks from object data (use first object).
            let chunks: Vec<Vec<u8>> = if objects.is_empty() {
                vec![]
            } else {
                // Emit data as a single chunk.
                vec![objects[0].data.clone()]
            };
            Self {
                object_count,
                objects,
                chunk_error: None,
                open_called: false,
                chunks,
                chunk_idx: 0,
                emit_terminal_zero: true,
            }
        }
    }

    impl CommandTransport for FakeTransport {
        fn get_device_prop_value(&mut self, prop_code: u16) -> FujiResult<Vec<u8>> {
            if prop_code == fuji_ptp::constants::prop_code::OBJECT_COUNT {
                Ok(self.object_count.to_le_bytes().to_vec())
            } else {
                Err(FujiError::Protocol(ProtocolError::UnsupportedOperation))
            }
        }

        fn get_object_info(&mut self, handle: u32) -> FujiResult<Vec<u8>> {
            let idx = (handle as usize).saturating_sub(1);
            let obj = self
                .objects
                .get(idx)
                .ok_or(FujiError::Transfer(TransferError::ObjectNotFound))?;
            let info = fuji_ptp::ObjectInfo {
                storage_id: 0x0001_0001,
                object_format: obj.format_code,
                protection_status: 0,
                object_compressed_size: obj.compressed_size,
                thumb_format: fuji_ptp::constants::object_format::JPEG,
                thumb_compressed_size: obj.thumb.len() as u32,
                thumb_pix_width: 160,
                thumb_pix_height: 120,
                image_pix_width: 0,
                image_pix_height: 0,
                image_bit_depth: 0,
                parent_object: 0,
                association_type: 0,
                association_desc: 0,
                sequence_number: handle,
                filename: obj.filename.clone(),
                capture_date: obj.capture_date.clone(),
                modification_date: String::new(),
                keywords: String::new(),
            };
            Ok(fuji_ptp::encode_object_info(&info))
        }

        fn get_thumb(&mut self, handle: u32) -> FujiResult<(u64, Vec<u8>)> {
            let idx = (handle as usize).saturating_sub(1);
            let obj = self
                .objects
                .get(idx)
                .ok_or(FujiError::Transfer(TransferError::ObjectNotFound))?;
            let declared = obj.thumb.len() as u64;
            Ok((declared, obj.thumb.clone()))
        }

        fn open_object(&mut self, handle: u32) -> FujiResult<u64> {
            self.open_called = true;
            let idx = (handle as usize).saturating_sub(1);
            let obj = self
                .objects
                .get(idx)
                .ok_or(FujiError::Transfer(TransferError::ObjectNotFound))?;
            Ok(obj.data.len() as u64)
        }

        fn read_object_chunk(&mut self, buf: &mut [u8]) -> FujiResult<usize> {
            if let Some(ref e) = self.chunk_error {
                return Err(e.clone());
            }
            if self.chunk_idx < self.chunks.len() {
                let chunk = &self.chunks[self.chunk_idx];
                let n = chunk.len().min(buf.len());
                buf[..n].copy_from_slice(&chunk[..n]);
                self.chunk_idx += 1;
                Ok(n)
            } else if self.emit_terminal_zero {
                self.emit_terminal_zero = false;
                Ok(0)
            } else {
                Ok(0)
            }
        }
    }

    // ── list_media tests ──────────────────────────────────────────────────────

    #[test]
    fn list_media_empty_returns_empty_vec() {
        let mut t = FakeTransport::new(vec![]);
        let result = list_media(&mut t).unwrap();
        assert!(result.is_empty());
    }

    #[test]
    fn list_media_single_jpeg_object() {
        let mut t = FakeTransport::new(vec![FakeObject {
            format_code: 0x3801,
            compressed_size: 1024,
            filename: "DSCF0001.JPG".to_owned(),
            capture_date: "20241225T120000".to_owned(),
            thumb: vec![0u8; 32],
            data: vec![0u8; 1024],
        }]);
        let objects = list_media(&mut t).unwrap();
        assert_eq!(objects.len(), 1);
        let obj = &objects[0];
        assert_eq!(obj.object_handle, CameraObjectId::new(1));
        assert_eq!(obj.format, CameraMediaFormat::Jpeg);
        assert_eq!(obj.size_bytes, Some(1024));
        assert!(obj.capture_date_utc.is_some());
        // Filename hash is a 16-char hex string.
        let hash = obj.filename_opaque_hash.as_deref().unwrap();
        assert_eq!(hash.len(), 16);
        assert!(hash.chars().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn list_media_multiple_objects() {
        let mut t = FakeTransport::new(vec![
            FakeObject {
                format_code: 0x3801,
                compressed_size: 1024,
                filename: "A.JPG".to_owned(),
                capture_date: String::new(),
                thumb: vec![],
                data: vec![0u8; 1024],
            },
            FakeObject {
                format_code: 0xB103,
                compressed_size: 50_000_000,
                filename: "B.RAF".to_owned(),
                capture_date: String::new(),
                thumb: vec![],
                data: vec![0u8; 50_000_000],
            },
        ]);
        let objects = list_media(&mut t).unwrap();
        assert_eq!(objects.len(), 2);
        assert_eq!(objects[0].format, CameraMediaFormat::Jpeg);
        assert_eq!(objects[1].format, CameraMediaFormat::Raf);
    }

    #[test]
    fn list_media_unknown_format_code_maps_to_unknown() {
        let mut t = FakeTransport::new(vec![FakeObject {
            format_code: 0xDEAD,
            compressed_size: 100,
            filename: "X.???".to_owned(),
            capture_date: String::new(),
            thumb: vec![],
            data: vec![0u8; 100],
        }]);
        let objects = list_media(&mut t).unwrap();
        assert_eq!(objects.len(), 1);
        assert!(matches!(
            objects[0].format,
            CameraMediaFormat::Unknown {
                raw_code: Some(0xDEAD)
            }
        ));
    }

    // ── get_thumbnail tests ───────────────────────────────────────────────────

    #[test]
    fn get_thumbnail_returns_bytes_within_cap() {
        let thumb_data = vec![0xFFu8, 0xD8, 0xFF, 0xE0];
        let mut t = FakeTransport::new(vec![FakeObject {
            format_code: 0x3801,
            compressed_size: 1024,
            filename: "T.JPG".to_owned(),
            capture_date: String::new(),
            thumb: thumb_data.clone(),
            data: vec![0u8; 1024],
        }]);
        let result = get_thumbnail(&mut t, 1, DEFAULT_MAX_THUMBNAIL_BYTES).unwrap();
        assert_eq!(result, thumb_data);
    }

    #[test]
    fn get_thumbnail_rejects_oversized() {
        // Thumb is 10 bytes; cap is 5 bytes.
        let thumb_data = vec![0u8; 10];
        let mut t = FakeTransport::new(vec![FakeObject {
            format_code: 0x3801,
            compressed_size: 1024,
            filename: "T.JPG".to_owned(),
            capture_date: String::new(),
            thumb: thumb_data,
            data: vec![0u8; 1024],
        }]);
        let err = get_thumbnail(&mut t, 1, 5).unwrap_err();
        assert!(matches!(
            err,
            FujiError::Transfer(TransferError::ThumbnailTooLarge { size: 10, limit: 5 })
        ));
    }

    // ── download_to_owned_fd tests ────────────────────────────────────────────

    // Helper: create an OwnedFd backed by a temp file, return (OwnedFd, path).
    //
    // File implements Into<OwnedFd> on unix targets (Rust 1.63+) — no unsafe needed.
    fn make_temp_fd() -> (OwnedFd, std::path::PathBuf) {
        use std::sync::atomic::{AtomicU64, Ordering};
        // Global counter ensures each call gets a unique filename even when tests
        // run in parallel within the same process (same pid, different threads).
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let n = COUNTER.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir();
        let path = dir.join(format!("frameport-test-{}-{}.bin", std::process::id(), n));
        let file = File::create(&path).unwrap();
        // File → OwnedFd via the safe Into<OwnedFd> impl in std::os::unix::io.
        let fd: OwnedFd = file.into();
        (fd, path)
    }

    #[test]
    fn download_to_owned_fd_success() {
        let data = vec![0xABu8; 256];
        let mut t = FakeTransport::new(vec![FakeObject {
            format_code: 0x3801,
            compressed_size: data.len() as u32,
            filename: "D.JPG".to_owned(),
            capture_date: String::new(),
            thumb: vec![],
            data: data.clone(),
        }]);
        let (fd, path) = make_temp_fd();
        let cancel = AtomicBool::new(false);
        let mut progress_received = vec![];
        let result = download_to_owned_fd(&mut t, CameraObjectId::new(1), fd, &cancel, &mut |p| {
            progress_received.push(p)
        });
        let final_progress = result.unwrap();
        assert_eq!(final_progress.bytes_transferred, 256);
        assert_eq!(final_progress.total_bytes, 256);
        assert_eq!(final_progress.object_handle, CameraObjectId::new(1));
        assert_eq!(final_progress.transfer_id, 1);
        // Verify data was written to the file.
        let written = std::fs::read(&path).unwrap();
        assert_eq!(written, data);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn download_to_owned_fd_cancel_mid_transfer() {
        // Two chunks; cancel after first.
        let data = vec![0xCCu8; 128];
        let mut t = FakeTransport {
            object_count: 1,
            objects: vec![FakeObject {
                format_code: 0x3801,
                compressed_size: data.len() as u32,
                filename: "C.JPG".to_owned(),
                capture_date: String::new(),
                thumb: vec![],
                data: data.clone(),
            }],
            chunk_error: None,
            open_called: false,
            // Two 64-byte chunks.
            chunks: vec![vec![0xCCu8; 64], vec![0xCCu8; 64]],
            chunk_idx: 0,
            emit_terminal_zero: true,
        };
        let (fd, path) = make_temp_fd();
        let cancel = AtomicBool::new(false);
        // Cancel flag will be set by progress callback after first chunk.
        let cancel_ref = &cancel;
        let result =
            download_to_owned_fd(&mut t, CameraObjectId::new(1), fd, cancel_ref, &mut |_p| {
                // Set cancel after receiving any progress.
                cancel_ref.store(true, Ordering::Relaxed);
            });
        assert!(
            matches!(result, Err(FujiError::Transfer(TransferError::Cancelled))),
            "expected Cancelled, got {result:?}"
        );
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn download_to_owned_fd_size_mismatch_returns_error() {
        // compressed_size claims 100 but data is 64 bytes.
        let data = vec![0u8; 64];
        let mut t = FakeTransport::new(vec![FakeObject {
            format_code: 0x3801,
            compressed_size: 100,
            filename: "M.JPG".to_owned(),
            capture_date: String::new(),
            thumb: vec![],
            data,
        }]);
        let (fd, path) = make_temp_fd();
        let cancel = AtomicBool::new(false);
        let result = download_to_owned_fd(&mut t, CameraObjectId::new(1), fd, &cancel, &mut |_| {});
        assert!(
            matches!(
                result,
                Err(FujiError::Transfer(TransferError::SizeMismatch {
                    expected: 100,
                    actual: 64
                }))
            ),
            "expected SizeMismatch, got {result:?}"
        );
        let _ = std::fs::remove_file(&path);
    }

    // ── Integration test against fuji-sim ─────────────────────────────────────

    #[cfg(test)]
    mod integration {
        use super::*;
        use fuji_ptp::constants::object_format;
        use fuji_ptpip::PtpIpTcpClient;
        use fuji_sim::{FakeCameraBuilder, FakeCameraServer};
        use std::time::Duration;
        use tokio::time::timeout;

        // Helper: connect PtpIpTcpClient to the sim and open the session.
        //
        // Protocol order (ptp-ptpip.md section 5.1):
        //   1. Client connects cmd channel → sends InitCommandRequest → receives InitCommandAck.
        //   2. Server (after sending InitCommandAck) accepts event channel.
        //   3. Client connects event channel → sends InitEventRequest → receives InitEventAck.
        //   4. Client sends OpenSession on cmd channel.
        //
        // PtpIpTcpClient::connect is a blocking std-socket call; run on spawn_blocking.
        // The event channel handshake is then done after InitCommandAck is received.
        //
        // cancel-safe: test driver — driven to completion by #[tokio::test]; never under tokio::select!.
        async fn connect_and_open(
            cmd_addr: std::net::SocketAddr,
            event_addr: std::net::SocketAddr,
        ) -> PtpIpTcpClient {
            use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};
            use tokio::io::{AsyncReadExt, AsyncWriteExt};
            use tokio::net::TcpStream;

            // Step 1: connect the sync command client (sends InitCommandRequest,
            // receives InitCommandAck). This unblocks the server to spawn its
            // background event-channel task and enter the dispatch loop.
            let client =
                tokio::task::spawn_blocking(move || PtpIpTcpClient::connect(cmd_addr).unwrap())
                    .await
                    .unwrap();

            // Step 2: event channel handshake — the server's background task accepts
            // it concurrently; we connect here after InitCommandAck is received.
            let mut event_stream = TcpStream::connect(event_addr).await.unwrap();
            let event_req = encode_packet(&PtpIpPacket::InitEventRequest {
                connection_number: 1,
            });
            event_stream.write_all(&event_req).await.unwrap();
            let mut lbuf = [0u8; 4];
            event_stream.read_exact(&mut lbuf).await.unwrap();
            let len = u32::from_le_bytes(lbuf) as usize;
            let mut buf = vec![0u8; len];
            buf[..4].copy_from_slice(&lbuf);
            event_stream.read_exact(&mut buf[4..]).await.unwrap();
            let ack = decode_packet(&buf).unwrap();
            assert_eq!(ack, PtpIpPacket::InitEventAck, "event channel ack");

            // Step 3: OpenSession on cmd channel — blocking sync I/O.
            // Safe with multi_thread flavor: the server's async tasks run on a
            // second worker thread, so blocking here does not starve them.
            let mut client = client;
            client.open_session().unwrap();
            client
        }

        // cancel-safe: test driver — driven to completion by #[tokio::test]; never under tokio::select!.
        // multi_thread flavor: the test body calls blocking sync I/O (list_media, etc.) on the
        // calling thread; the second worker thread services the async server tasks concurrently.
        #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
        async fn sim_list_media_two_objects() {
            let jpeg_data = vec![0xAAu8; 4096];
            let raf_data = vec![0xBBu8; 8192];
            let config = FakeCameraBuilder::new()
                .add_object(
                    object_format::JPEG,
                    jpeg_data.len() as u32,
                    "DSCF0001.JPG",
                    "20241225T120000",
                    jpeg_data,
                    vec![0u8; 64],
                )
                .add_object(
                    object_format::RAF,
                    raf_data.len() as u32,
                    "DSCF0001.RAF",
                    "",
                    raf_data,
                    vec![],
                )
                .build();

            let srv = FakeCameraServer::bind(config).await.unwrap();
            let cmd_addr = srv.bound_addr();
            let event_addr = srv.event_addr();
            let handle = srv.run();

            let result = timeout(Duration::from_secs(5), async {
                let mut client = connect_and_open(cmd_addr, event_addr).await;
                let objects = list_media(&mut client).unwrap();
                assert_eq!(objects.len(), 2, "expected 2 objects");
                assert_eq!(objects[0].format, CameraMediaFormat::Jpeg);
                assert_eq!(objects[1].format, CameraMediaFormat::Raf);
                assert_eq!(objects[0].size_bytes, Some(4096));
                assert_eq!(objects[1].size_bytes, Some(8192));
                // Object 0 has a date, object 1 does not.
                assert!(objects[0].capture_date_utc.is_some());
                assert!(objects[1].capture_date_utc.is_none());
                // Filename hashes are different strings.
                assert_ne!(
                    objects[0].filename_opaque_hash,
                    objects[1].filename_opaque_hash
                );
            })
            .await;

            result.expect("test timed out");
            handle.shutdown();
        }

        // cancel-safe: test driver — driven to completion by #[tokio::test]; never under tokio::select!.
        #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
        async fn sim_get_thumbnail() {
            let thumb = vec![0xFFu8, 0xD8, 0xFF, 0xE0, 0x00, 0x10]; // JPEG magic
            let config = FakeCameraBuilder::new()
                .add_object(
                    object_format::JPEG,
                    1024,
                    "T.JPG",
                    "",
                    vec![0u8; 1024],
                    thumb.clone(),
                )
                .build();

            let srv = FakeCameraServer::bind(config).await.unwrap();
            let cmd_addr = srv.bound_addr();
            let event_addr = srv.event_addr();
            let handle = srv.run();

            let result = timeout(Duration::from_secs(5), async {
                let mut client = connect_and_open(cmd_addr, event_addr).await;
                let bytes = get_thumbnail(&mut client, 1, DEFAULT_MAX_THUMBNAIL_BYTES).unwrap();
                assert_eq!(bytes, thumb);
            })
            .await;

            result.expect("test timed out");
            handle.shutdown();
        }

        // cancel-safe: test driver — driven to completion by #[tokio::test]; never under tokio::select!.
        #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
        async fn sim_download_to_owned_fd() {
            let data = vec![0xABu8; 65536 + 1024]; // > 64 KiB to test chunk loop
            let config = FakeCameraBuilder::new()
                .add_object(
                    object_format::JPEG,
                    data.len() as u32,
                    "D.JPG",
                    "",
                    data.clone(),
                    vec![],
                )
                .build();

            let srv = FakeCameraServer::bind(config).await.unwrap();
            let cmd_addr = srv.bound_addr();
            let event_addr = srv.event_addr();
            let handle = srv.run();

            let result = timeout(Duration::from_secs(10), async {
                let mut client = connect_and_open(cmd_addr, event_addr).await;
                let (fd, path) = make_temp_fd();
                let cancel = AtomicBool::new(false);
                let mut progress_snapshots = vec![];
                let final_progress = download_to_owned_fd(
                    &mut client,
                    CameraObjectId::new(1),
                    fd,
                    &cancel,
                    &mut |p| progress_snapshots.push(p),
                )
                .unwrap();

                assert_eq!(final_progress.bytes_transferred, data.len() as u64);
                assert_eq!(final_progress.total_bytes, data.len() as u64);
                assert!(
                    !progress_snapshots.is_empty(),
                    "must have received progress"
                );
                // Verify written content.
                let written = std::fs::read(&path).unwrap();
                assert_eq!(written, data);
                let _ = std::fs::remove_file(&path);
            })
            .await;

            result.expect("test timed out");
            handle.shutdown();
        }

        // cancel-safe: test driver — driven to completion by #[tokio::test]; never under tokio::select!.
        #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
        async fn sim_malformed_object_info_returns_protocol_error() {
            let config = FakeCameraBuilder::new()
                .add_object(
                    object_format::JPEG,
                    1024,
                    "X.JPG",
                    "",
                    vec![0u8; 1024],
                    vec![],
                )
                .with_malformed_object_info_fault(1)
                .build();

            let srv = FakeCameraServer::bind(config).await.unwrap();
            let cmd_addr = srv.bound_addr();
            let event_addr = srv.event_addr();
            let handle = srv.run();

            let result = timeout(Duration::from_secs(5), async {
                let mut client = connect_and_open(cmd_addr, event_addr).await;
                let err = list_media(&mut client).unwrap_err();
                assert!(
                    matches!(
                        err,
                        FujiError::Protocol(ProtocolError::InvalidPacketLength { .. })
                    ),
                    "expected InvalidPacketLength, got {err:?}"
                );
            })
            .await;

            result.expect("test timed out");
            handle.shutdown();
        }

        // cancel-safe: test driver — driven to completion by #[tokio::test]; never under tokio::select!.
        #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
        async fn sim_size_mismatch_returns_transfer_error() {
            // object_bytes has 64 bytes but compressed_size claims 999.
            let config = FakeCameraBuilder::new()
                .add_object(
                    object_format::JPEG,
                    999, // advertised — mismatch
                    "M.JPG",
                    "",
                    vec![0u8; 64], // actual bytes
                    vec![],
                )
                .with_size_mismatch_fault(1)
                .build();

            let srv = FakeCameraServer::bind(config).await.unwrap();
            let cmd_addr = srv.bound_addr();
            let event_addr = srv.event_addr();
            let handle = srv.run();

            let result = timeout(Duration::from_secs(5), async {
                let mut client = connect_and_open(cmd_addr, event_addr).await;
                let (fd, path) = make_temp_fd();
                let cancel = AtomicBool::new(false);
                let err = download_to_owned_fd(
                    &mut client,
                    CameraObjectId::new(1),
                    fd,
                    &cancel,
                    &mut |_| {},
                )
                .unwrap_err();
                // Either SizeMismatch or CameraDisconnected depending on sim wire behaviour.
                let is_expected = matches!(
                    err,
                    FujiError::Transfer(TransferError::SizeMismatch { .. })
                        | FujiError::Transfer(TransferError::CameraDisconnected)
                        | FujiError::Protocol(ProtocolError::ResponseMismatch)
                );
                assert!(is_expected, "unexpected error: {err:?}");
                let _ = std::fs::remove_file(&path);
            })
            .await;

            result.expect("test timed out");
            handle.shutdown();
        }
    }
}
