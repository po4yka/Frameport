//! `fuji-liveview` — zero-alloc MJPEG parser and async read-loop helper for
//! Fujifilm PTP-IP live-view streaming (M16).
//!
//! # Frame format (transfer-liveview.md §6g, master-constants.md §1)
//!
//! Each frame arrives as a single length-prefixed TCP packet:
//!
//! ```text
//! [0..4]   4-byte transport prefix: u32 LE, value = total packet length (inclusive)
//! [4..18]  14-byte FCW frame control word header:
//!            bytes 0-3  (absolute 4-7):   u32 LE = 0 (constant)
//!            bytes 4-7  (absolute 4-7):   u32 LE frame counter (increments per frame)
//!            bytes 8-13 (absolute 12-17): zeros
//! [18..]   JPEG payload starting with SOI 0xFF 0xD8, ending with EOI 0xFF 0xD9
//! ```
//!
//! Total fixed overhead per frame when `secondaryLen == 0`: 18 bytes.
//! JPEG payload starts at byte offset `FRAME_HEADER_TOTAL_LEN` (18).
//!
//! Source: transfer-liveview.md §6g (fuji-cam-wifi-tool model). Confirmed by
//! Wireshark filter `data.data[18:2] == ff:d8`. The XApp variable-`secondaryLen`
//! model is documented in the reference but requires hardware verification first.
//!
//! # Frame counter (gap detection)
//!
//! The u32 LE frame counter sits at absolute offset `FCW_FRAME_COUNTER_OFFSET`
//! (= 4, immediately after the 4-byte transport prefix). Gaps in successive
//! counter values indicate camera-side frame drops.
//!
//! # Zero-alloc guarantee
//!
//! [`LiveViewParser`] owns a single fixed `[u8; RING_BUFFER_SIZE]` array
//! (`RING_BUFFER_SIZE` = 204800, `REMOTE_RECEIVE_FILE_SIZE` in master-constants.md).
//! `parse_frame` copies the input packet into this buffer and exposes the JPEG
//! payload as `&[u8]` with zero copy and zero heap allocation on the hot path.
//!
//! # Async read loop
//!
//! [`run_liveview_loop`] is the cancel-safe async helper that drives the parser
//! over a `tokio::io::AsyncRead` source, calling a per-frame `&[u8]` callback.

#![forbid(unsafe_code)]

use std::sync::atomic::{AtomicBool, Ordering};

use fuji_core::{FujiError, SessionId};
use thiserror::Error;
use tokio::io::{AsyncRead, AsyncReadExt};

// Named constants (master-constants.md §1, transfer-liveview.md §6g)

/// Ring buffer size for the live-view parser.
/// Source: master-constants.md §1 -- `REMOTE_RECEIVE_FILE_SIZE` = 200 KiB = 204800 bytes.
/// Confidence: [H] FCW, LFJ, FJH, XPN, XBL
pub const RING_BUFFER_SIZE: usize = 204_800;

/// JPEG start-of-image marker byte 0 (ISO/IEC 10918-1).
/// Source: transfer-liveview.md §6g -- parser validates SOI before delivering frame.
pub const JPEG_SOI_BYTE_0: u8 = 0xFF;

/// JPEG start-of-image marker byte 1.
pub const JPEG_SOI_BYTE_1: u8 = 0xD8;

/// JPEG end-of-image marker byte 0 (ISO/IEC 10918-1).
pub const JPEG_EOI_BYTE_0: u8 = 0xFF;

/// JPEG end-of-image marker byte 1.
pub const JPEG_EOI_BYTE_1: u8 = 0xD9;

/// Fixed FCW frame control word header length in bytes (transfer-liveview.md §6g).
/// Does NOT include the 4-byte transport prefix.
pub const FCW_HEADER_LEN: usize = 14;

/// 4-byte PTP-IP transport prefix that precedes each FCW frame (u32 LE, inclusive).
pub const TRANSPORT_PREFIX_LEN: usize = 4;

/// Total fixed per-frame overhead when `secondaryLen == 0`.
/// = TRANSPORT_PREFIX_LEN(4) + FCW_HEADER_LEN(14) = 18 bytes.
/// Source: transfer-liveview.md §6g -- Wireshark filter `data.data[18:2] == ff:d8`.
pub const FRAME_HEADER_TOTAL_LEN: usize = TRANSPORT_PREFIX_LEN + FCW_HEADER_LEN;

/// Byte offset of the u32 LE frame counter within the full framed packet.
/// Absolute offset 4: immediately after the 4-byte transport prefix.
/// Source: transfer-liveview.md §6g -- "bytes 4-7: u32 frame counter (increments per frame)".
pub const FCW_FRAME_COUNTER_OFFSET: usize = TRANSPORT_PREFIX_LEN;

/// Minimum valid frame size: full 18-byte header + at least 2 JPEG SOI bytes.
pub const MIN_FRAME_SIZE: usize = FRAME_HEADER_TOTAL_LEN + 2;

// LiveViewError

/// Typed errors produced by the live-view parser and read loop.
///
/// Each variant maps to an existing [`FujiError`] sub-family via
/// [`From<LiveViewError> for FujiError`]. No new top-level `FujiError` variant
/// is introduced to avoid breaking exhaustiveness tests in `fuji-core`.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub enum LiveViewError {
    /// The packet size exceeds `RING_BUFFER_SIZE`.
    /// The parser increments `stats.drop_count` and skips the frame.
    /// The ring buffer is NEVER grown.
    #[error("liveview:frame-too-large")]
    FrameTooLarge,

    /// The FCW or transport header bytes are not as expected.
    /// Includes mismatched length field or packet too short to contain a header.
    #[error("liveview:malformed-header")]
    MalformedHeader,

    /// The underlying stream returned EOF mid-header or mid-frame.
    #[error("liveview:unexpected-eof")]
    UnexpectedEof,

    /// The JPEG payload does not start with the expected SOI marker (0xFF 0xD8).
    #[error("liveview:invalid-jpeg-marker")]
    InvalidJpegMarker,
}

/// Map [`LiveViewError`] into the top-level [`FujiError`] type without adding
/// new `FujiError` variants.
impl From<LiveViewError> for FujiError {
    fn from(err: LiveViewError) -> Self {
        use fuji_core::{ProtocolError, TransferError, TransportError};
        match err {
            LiveViewError::FrameTooLarge => FujiError::Transfer(TransferError::PartialReadFailed),
            LiveViewError::MalformedHeader => FujiError::Protocol(ProtocolError::UnexpectedPacket),
            LiveViewError::UnexpectedEof => FujiError::Transport(TransportError::Eof),
            LiveViewError::InvalidJpegMarker => {
                FujiError::Protocol(ProtocolError::UnexpectedPacket)
            }
        }
    }
}

// FrameStats

/// Per-session live-view statistics updated in place by [`LiveViewParser`].
///
/// All fields are updated without allocation. `last_parse_monotonic` uses
/// `std::time::Instant` which is available on the host test runner and on Android.
///
/// No device identifiers, IP addresses, or file paths appear in any field
/// (privacy-local-first.md).
#[derive(Debug)]
pub struct FrameStats {
    /// Monotonically incrementing counter; incremented on every successfully
    /// parsed frame. Does NOT advance on dropped or errored frames.
    pub sequence: u64,
    /// Monotonic timestamp of the most recently parsed frame.
    /// `None` until the first frame is parsed successfully.
    pub last_parse_monotonic: Option<std::time::Instant>,
    /// Number of frames dropped because `FrameTooLarge` was emitted.
    /// Does NOT include frames skipped for `InvalidJpegMarker` or other errors.
    pub drop_count: u64,
}

impl FrameStats {
    /// Construct zeroed stats for a new session.
    pub fn new() -> Self {
        Self {
            sequence: 0,
            last_parse_monotonic: None,
            drop_count: 0,
        }
    }
}

impl Default for FrameStats {
    fn default() -> Self {
        Self::new()
    }
}

// LiveViewParser

/// Zero-alloc MJPEG frame parser for Fujifilm PTP-IP live-view.
///
/// The parser owns a single fixed `[u8; RING_BUFFER_SIZE]` ring buffer
/// allocated exactly ONCE at construction. Every `parse_frame` call copies the
/// input packet into this buffer and returns `&[u8]` pointing into it -- zero
/// copy after the initial write, zero heap allocation on the hot path.
///
/// On `FrameTooLarge`: `stats.drop_count` is incremented; the buffer is NOT grown.
pub struct LiveViewParser {
    /// Session this parser belongs to (for diagnostic correlation only).
    /// Never logged with identifying information per privacy-local-first.md.
    session_id: SessionId,
    /// Fixed ring buffer -- heap-allocated once at construction, reused every frame.
    buffer: Box<[u8; RING_BUFFER_SIZE]>,
    /// Running statistics updated in place by `parse_frame`.
    ///
    /// NOTE: this field is intentionally `pub` because `tests/alloc_shim.rs` resets
    /// individual sub-fields directly (`parser.stats.sequence = 0`) to avoid a heap
    /// allocation inside the zero-alloc measurement window.  Making it private would
    /// require edits to files outside this crate's `src/` tree.  The accessor method
    /// `stats()` is the preferred read path for all other callers.
    pub stats: FrameStats,
}

impl LiveViewParser {
    /// Construct a new parser, allocating the ring buffer exactly once.
    ///
    /// The `Box<[u8; N]>` heap-allocates the 200 KiB array once to avoid a
    /// large stack frame. All subsequent operations on the hot path are zero-alloc.
    pub fn new(session_id: SessionId) -> Self {
        // ALLOCATION: single 200 KiB heap alloc here, never again on hot path.
        Self {
            session_id,
            buffer: Box::new([0u8; RING_BUFFER_SIZE]),
            stats: FrameStats::new(),
        }
    }

    /// Return the session id this parser is associated with.
    pub fn session_id(&self) -> SessionId {
        self.session_id
    }

    /// Parse the next frame from `input`, updating the internal ring buffer.
    ///
    /// `input` is the complete framed packet as received from the TCP socket:
    /// - bytes 0..4:   transport prefix (u32 LE, inclusive total length = input.len())
    /// - bytes 4..18:  FCW header (frame counter at bytes 4..8 as u32 LE)
    /// - bytes 18..:   JPEG payload (must start with 0xFF 0xD8)
    ///
    /// Source: transfer-liveview.md §6g -- fuji-cam-wifi-tool fixed-18-byte model.
    ///
    /// On success, returns `Ok(&[u8])` -- a slice into the internal buffer
    /// covering exactly the JPEG payload (buffer[FRAME_HEADER_TOTAL_LEN..input.len()]).
    /// The slice is valid until the next call to `parse_frame`.
    ///
    /// # Allocation invariant
    ///
    /// This function MUST NOT allocate. No Vec, Box, String, Arc, or Rc is
    /// created in the body. The return value is &[u8] into self.buffer.
    #[inline]
    pub fn parse_frame<'buf>(&'buf mut self, input: &[u8]) -> Result<&'buf [u8], LiveViewError> {
        // Guard: empty input
        if input.is_empty() {
            return Err(LiveViewError::UnexpectedEof);
        }

        // Guard: oversized frame -- never grow the buffer
        if input.len() > RING_BUFFER_SIZE {
            self.stats.drop_count = self.stats.drop_count.saturating_add(1);
            return Err(LiveViewError::FrameTooLarge);
        }

        // Guard: minimum header + SOI bytes (MIN_FRAME_SIZE = 18 + 2 = 20)
        if input.len() < MIN_FRAME_SIZE {
            return Err(LiveViewError::MalformedHeader);
        }

        // Validate transport prefix (u32 LE, inclusive)
        // Source: transfer-liveview.md §6g -- "4-byte Fuji transport length prefix (LE, inclusive)"
        let declared_len = u32::from_le_bytes([input[0], input[1], input[2], input[3]]) as usize;
        if declared_len != input.len() {
            return Err(LiveViewError::MalformedHeader);
        }

        // Copy entire packet into ring buffer (single copy_from_slice, zero-alloc)
        let len = input.len();
        self.buffer[..len].copy_from_slice(input);

        // Validate JPEG SOI at FRAME_HEADER_TOTAL_LEN (18)
        // Source: transfer-liveview.md §6g -- Wireshark data.data[18:2] == ff:d8
        if self.buffer[FRAME_HEADER_TOTAL_LEN] != JPEG_SOI_BYTE_0
            || self.buffer[FRAME_HEADER_TOTAL_LEN + 1] != JPEG_SOI_BYTE_1
        {
            return Err(LiveViewError::InvalidJpegMarker);
        }

        // Update stats in place -- no allocation
        self.stats.sequence = self.stats.sequence.wrapping_add(1);
        self.stats.last_parse_monotonic = Some(std::time::Instant::now());

        // Return zero-copy slice into the owned buffer (JPEG payload only)
        Ok(&self.buffer[FRAME_HEADER_TOTAL_LEN..len])
    }

    /// Borrow a mutable slice of the ring buffer sized to `declared_len`, for a read
    /// loop to fill the frame (transport prefix + FCW header + JPEG payload) IN PLACE.
    ///
    /// This is the zero-copy intake path used by [`run_liveview_loop`]: the socket reads
    /// directly into the parser's single ring buffer — there is no second buffer and no
    /// per-frame copy. Pair with [`finalize_frame`].
    ///
    /// Errors (and bumps `drop_count`) if `declared_len` exceeds the ring buffer; errors
    /// with `MalformedHeader` if it is below the minimum frame size. No allocation.
    pub fn frame_buffer_mut(&mut self, declared_len: usize) -> Result<&mut [u8], LiveViewError> {
        if declared_len > RING_BUFFER_SIZE {
            self.stats.drop_count = self.stats.drop_count.saturating_add(1);
            return Err(LiveViewError::FrameTooLarge);
        }
        if declared_len < MIN_FRAME_SIZE {
            return Err(LiveViewError::MalformedHeader);
        }
        Ok(&mut self.buffer[..declared_len])
    }

    /// Validate a frame already written into the ring buffer (via [`frame_buffer_mut`])
    /// of exactly `declared_len` bytes, returning the zero-copy JPEG payload slice.
    ///
    /// No copy and no allocation — the bytes are already in `self.buffer`.
    #[inline]
    pub fn finalize_frame(&mut self, declared_len: usize) -> Result<&[u8], LiveViewError> {
        if !(MIN_FRAME_SIZE..=RING_BUFFER_SIZE).contains(&declared_len) {
            return Err(LiveViewError::MalformedHeader);
        }
        // The 4-byte transport prefix (already in the buffer) must equal the frame length.
        let prefix_len = u32::from_le_bytes([
            self.buffer[0],
            self.buffer[1],
            self.buffer[2],
            self.buffer[3],
        ]) as usize;
        if prefix_len != declared_len {
            return Err(LiveViewError::MalformedHeader);
        }
        if self.buffer[FRAME_HEADER_TOTAL_LEN] != JPEG_SOI_BYTE_0
            || self.buffer[FRAME_HEADER_TOTAL_LEN + 1] != JPEG_SOI_BYTE_1
        {
            return Err(LiveViewError::InvalidJpegMarker);
        }
        self.stats.sequence = self.stats.sequence.wrapping_add(1);
        self.stats.last_parse_monotonic = Some(std::time::Instant::now());
        Ok(&self.buffer[FRAME_HEADER_TOTAL_LEN..declared_len])
    }

    /// Return current frame statistics without consuming the parser.
    pub fn stats(&self) -> &FrameStats {
        &self.stats
    }

    /// Extract the u32 LE FCW frame counter from the last buffered packet.
    ///
    /// Source: transfer-liveview.md §6g -- "bytes 4-7: u32 frame counter
    /// (increments per frame)". Gaps indicate camera-side frame drops.
    ///
    /// Returns `None` if no packet has been successfully parsed yet.
    #[inline]
    pub fn last_frame_counter(&self) -> Option<u32> {
        if self.stats.sequence == 0 {
            return None;
        }
        // Bounds: MIN_FRAME_SIZE (20) > FCW_FRAME_COUNTER_OFFSET + 4 (8).
        // A successful parse_frame guarantees input.len() >= MIN_FRAME_SIZE and
        // the packet was copied into self.buffer[..len].
        Some(u32::from_le_bytes([
            self.buffer[FCW_FRAME_COUNTER_OFFSET],
            self.buffer[FCW_FRAME_COUNTER_OFFSET + 1],
            self.buffer[FCW_FRAME_COUNTER_OFFSET + 2],
            self.buffer[FCW_FRAME_COUNTER_OFFSET + 3],
        ]))
    }
}

// Async read-loop helper

/// Discard buffer size used when draining an oversized frame body.
///
/// Must be small enough to avoid a large stack frame but large enough to make
/// drain loops efficient for the common case. 4 KiB is consistent with typical
/// kernel socket buffer granularity and keeps the stack overhead trivial.
const DRAIN_BUF_SIZE: usize = 4096;

/// Drive the live-view parser over an `AsyncRead` source.
///
/// Reads length-prefixed TCP packets from `reader` in a loop per
/// transfer-liveview.md §6g (fuji-cam-wifi-tool model):
/// 1. Read 4-byte transport prefix (u32 LE, inclusive packet length).
/// 2. Read remaining `declared_len - 4` bytes (FCW header + JPEG payload).
/// 3. Pass the assembled packet to `parser.parse_frame`.
/// 4. On `Ok(jpeg_slice)`, call `on_frame(jpeg_slice)`.
///
/// `on_frame` receives `&[u8]` pointing into the parser's ring buffer.
/// It MUST NOT retain this reference across the call boundary.
///
/// # Oversized frames
///
/// When `frame_buffer_mut` rejects a frame as `FrameTooLarge`, the body bytes
/// (`declared_len - TRANSPORT_PREFIX_LEN`) are still sitting in the TCP stream.
/// This function drains them through a fixed 4 KiB stack buffer (`DRAIN_BUF_SIZE`)
/// in a bounded loop so that the stream stays frame-aligned, then `continue`s to
/// the next frame. The session is NOT torn down. `parser.stats.drop_count` is
/// incremented once by `frame_buffer_mut` before control returns here.
///
/// # Cancel-safety
///
/// cancel-safe: the `read_exact` calls per frame (including the drain loop) are
/// individually cancel-safe (tokio re-tries partial reads when re-polled). On
/// cancellation mid-drain, the stream position is advanced by an arbitrary
/// partial amount; the next poll will resume draining. The parser's ring buffer
/// and stats remain consistent because `finalize_frame` is only called after a
/// complete, in-bounds frame has been read.
///
/// # Allocation invariant
///
/// ZERO heap allocation on the happy-path loop body. There is no scratch buffer:
/// each in-bounds frame is read directly into the parser's single ring buffer via
/// [`LiveViewParser::frame_buffer_mut`] and validated in place by
/// [`LiveViewParser::finalize_frame`] — no second buffer and no per-frame copy.
/// The drain path uses a fixed 4 KiB stack array (zero heap alloc). `on_frame`
/// is caller-controlled; any JVM byte[] allocation inside it is out of scope.
pub async fn run_liveview_loop<R, F>(
    parser: &mut LiveViewParser,
    reader: &mut R,
    mut on_frame: F,
    stop_signal: &AtomicBool,
) -> Result<(), LiveViewError>
where
    R: AsyncRead + Unpin,
    F: FnMut(&[u8]),
{
    // SINGLE BUFFER: the frame is read directly into the parser's ring buffer via
    // frame_buffer_mut(); there is no second scratch buffer and no per-frame copy.
    loop {
        // Stop signal -- checked before each blocking read
        if stop_signal.load(Ordering::Relaxed) {
            return Ok(());
        }

        // Phase 1: read 4-byte transport prefix (u32 LE, inclusive length)
        // Source: transfer-liveview.md §6g -- "4-byte Fuji transport length prefix".
        let mut prefix = [0u8; TRANSPORT_PREFIX_LEN];
        match reader.read_exact(&mut prefix).await {
            Ok(_) => {}
            Err(_) => return Err(LiveViewError::UnexpectedEof),
        }

        let declared_len = u32::from_le_bytes(prefix) as usize;

        // Phase 2: borrow the ring buffer in place (bounds-checked), copy in the prefix,
        // and read the remaining body (FCW header + JPEG payload) directly into it.
        //
        // On FrameTooLarge: frame_buffer_mut already incremented drop_count. We must
        // drain the unconsumed body bytes from the stream so the next frame starts at
        // the correct TCP offset, then continue the loop. Returning Err here would
        // permanently kill the session for a per-frame oversize condition.
        {
            let frame = match parser.frame_buffer_mut(declared_len) {
                Ok(slice) => slice,
                Err(LiveViewError::FrameTooLarge) => {
                    // A frame modestly larger than the ring buffer is a recoverable
                    // per-frame oversize: drain and continue. But an absurd declared_len
                    // (a corrupt or hostile stream sending e.g. 0xFFFFFFFF) would otherwise
                    // stall the worker draining gigabytes 4 KiB at a time. Cap the
                    // recoverable range; beyond it the stream is untrustworthy, so tear
                    // down and let the caller reconnect.
                    const MAX_DRAINABLE_LEN: usize = RING_BUFFER_SIZE * 4;
                    if declared_len > MAX_DRAINABLE_LEN {
                        return Err(LiveViewError::MalformedHeader);
                    }
                    // Drain the body (declared_len - TRANSPORT_PREFIX_LEN bytes) in 4 KiB
                    // chunks so the stream stays frame-aligned. Do NOT allocate declared_len
                    // bytes at once — the whole point is that the frame is too large to buffer.
                    let mut to_drain = declared_len.saturating_sub(TRANSPORT_PREFIX_LEN);
                    let mut discard = [0u8; DRAIN_BUF_SIZE];
                    while to_drain > 0 {
                        // Respect stop_signal during potentially long drains.
                        if stop_signal.load(Ordering::Relaxed) {
                            return Ok(());
                        }
                        let chunk = to_drain.min(DRAIN_BUF_SIZE);
                        match reader.read_exact(&mut discard[..chunk]).await {
                            Ok(_) => {}
                            Err(_) => return Err(LiveViewError::UnexpectedEof),
                        }
                        to_drain -= chunk;
                    }
                    // Stream is now aligned to the start of the next frame.
                    continue;
                }
                // MalformedHeader (declared_len < MIN_FRAME_SIZE): stream integrity is
                // compromised; we cannot safely skip an unknown number of bytes.
                Err(e) => return Err(e),
            };
            frame[..TRANSPORT_PREFIX_LEN].copy_from_slice(&prefix);
            match reader.read_exact(&mut frame[TRANSPORT_PREFIX_LEN..]).await {
                Ok(_) => {}
                Err(_) => return Err(LiveViewError::UnexpectedEof),
            }
        } // the &mut ring-buffer borrow ends here, before finalize_frame re-borrows.

        // Phase 3: validate in place (no copy) and deliver the zero-copy JPEG slice.
        //
        // Note: finalize_frame never returns FrameTooLarge — it guards oversized values
        // with MalformedHeader (the frame was already bounds-checked by frame_buffer_mut
        // above and only reachable here when declared_len <= RING_BUFFER_SIZE).
        match parser.finalize_frame(declared_len) {
            Ok(jpeg_slice) => {
                // Deliver JPEG payload; caller must not retain the reference.
                on_frame(jpeg_slice);
            }
            // Recoverable per-frame fault: bad JPEG marker — skip and continue.
            Err(LiveViewError::InvalidJpegMarker) => {}
            // Stream-integrity faults: surface so the caller can reconnect.
            Err(LiveViewError::MalformedHeader) => return Err(LiveViewError::MalformedHeader),
            Err(LiveViewError::UnexpectedEof) => return Err(LiveViewError::UnexpectedEof),
            // FrameTooLarge is unreachable here: frame_buffer_mut already handled it
            // above (drain + continue). finalize_frame uses MalformedHeader for size
            // violations. This arm is kept for exhaustiveness only.
            Err(LiveViewError::FrameTooLarge) => return Err(LiveViewError::MalformedHeader),
        }
    }
}

// Tests

#[cfg(test)]
mod tests {
    use super::*;

    // Helpers

    fn session() -> SessionId {
        SessionId::new(1).unwrap()
    }

    /// Build a well-formed framed packet with the given JPEG payload.
    /// Wire layout (transfer-liveview.md §6g, fuji-cam-wifi-tool model).
    fn make_frame(jpeg: &[u8], counter: u32) -> Vec<u8> {
        let total = FRAME_HEADER_TOTAL_LEN + jpeg.len();
        let mut buf = vec![0u8; total];
        // Transport prefix (u32 LE, inclusive total length).
        buf[0..4].copy_from_slice(&(total as u32).to_le_bytes());
        // FCW frame counter at absolute offset FCW_FRAME_COUNTER_OFFSET (4).
        buf[FCW_FRAME_COUNTER_OFFSET..FCW_FRAME_COUNTER_OFFSET + 4]
            .copy_from_slice(&counter.to_le_bytes());
        // JPEG payload at offset FRAME_HEADER_TOTAL_LEN (18).
        buf[FRAME_HEADER_TOTAL_LEN..].copy_from_slice(jpeg);
        buf
    }

    /// Minimal well-formed JPEG: SOI + EOI.
    fn minimal_jpeg() -> Vec<u8> {
        vec![
            JPEG_SOI_BYTE_0,
            JPEG_SOI_BYTE_1,
            JPEG_EOI_BYTE_0,
            JPEG_EOI_BYTE_1,
        ]
    }

    /// Concatenate frames into a Cursor for stream tests.
    fn build_stream(frames: &[Vec<u8>]) -> std::io::Cursor<Vec<u8>> {
        let mut buf = Vec::new();
        for f in frames {
            buf.extend_from_slice(f);
        }
        std::io::Cursor::new(buf)
    }

    // Construction

    #[test]
    fn parser_constructs_without_allocation_panic() {
        let parser = LiveViewParser::new(session());
        assert_eq!(parser.stats().sequence, 0);
        assert_eq!(parser.stats().drop_count, 0);
        assert!(parser.stats().last_parse_monotonic.is_none());
    }

    #[test]
    fn parser_session_id_roundtrips() {
        let sid = session();
        let parser = LiveViewParser::new(sid);
        assert_eq!(parser.session_id(), sid);
    }

    // UnexpectedEof

    #[test]
    fn empty_input_returns_unexpected_eof() {
        let mut parser = LiveViewParser::new(session());
        assert_eq!(parser.parse_frame(&[]), Err(LiveViewError::UnexpectedEof));
    }

    // MalformedHeader

    #[test]
    fn too_short_input_returns_malformed_header() {
        let mut parser = LiveViewParser::new(session());
        let short = [1u8; 10];
        assert_eq!(
            parser.parse_frame(&short),
            Err(LiveViewError::MalformedHeader)
        );
    }

    #[test]
    fn mismatched_length_prefix_returns_malformed_header() {
        let mut parser = LiveViewParser::new(session());
        let mut frame = make_frame(&minimal_jpeg(), 1);
        let wrong = (frame.len() as u32 + 5).to_le_bytes();
        frame[0..4].copy_from_slice(&wrong);
        assert_eq!(
            parser.parse_frame(&frame),
            Err(LiveViewError::MalformedHeader)
        );
    }

    // FrameTooLarge

    #[test]
    fn oversized_input_returns_frame_too_large_and_increments_drop() {
        let mut parser = LiveViewParser::new(session());
        let big = vec![0u8; RING_BUFFER_SIZE + 1];
        let result = parser.parse_frame(&big);
        assert_eq!(result, Err(LiveViewError::FrameTooLarge));
        assert_eq!(parser.stats().drop_count, 1);
        assert_eq!(parser.stats().sequence, 0);
    }

    #[test]
    fn multiple_oversized_inputs_accumulate_drop_count() {
        let mut parser = LiveViewParser::new(session());
        let big = vec![0u8; RING_BUFFER_SIZE + 1];
        for expected in 1..=3u64 {
            let _ = parser.parse_frame(&big);
            assert_eq!(parser.stats().drop_count, expected);
        }
        assert_eq!(parser.stats().sequence, 0);
    }

    // InvalidJpegMarker

    #[test]
    fn non_soi_payload_returns_invalid_jpeg_marker() {
        let mut parser = LiveViewParser::new(session());
        let bad_payload = vec![0x00u8, 0x01u8, 0x02u8, 0x03u8];
        let frame = make_frame(&bad_payload, 1);
        assert_eq!(
            parser.parse_frame(&frame),
            Err(LiveViewError::InvalidJpegMarker)
        );
        assert_eq!(parser.stats().sequence, 0);
        assert_eq!(parser.stats().drop_count, 0);
    }

    // Valid frame -- happy path

    #[test]
    fn valid_frame_returns_jpeg_slice_starting_at_soi() {
        let mut parser = LiveViewParser::new(session());
        let jpeg = minimal_jpeg();
        let frame = make_frame(&jpeg, 42);
        let slice = parser.parse_frame(&frame).unwrap();
        assert_eq!(slice[0], JPEG_SOI_BYTE_0);
        assert_eq!(slice[1], JPEG_SOI_BYTE_1);
        assert_eq!(slice.len(), jpeg.len());
    }

    #[test]
    fn valid_frame_advances_sequence_and_sets_timestamp() {
        let mut parser = LiveViewParser::new(session());
        let frame = make_frame(&minimal_jpeg(), 1);
        parser.parse_frame(&frame).unwrap();
        assert_eq!(parser.stats().sequence, 1);
        assert!(parser.stats().last_parse_monotonic.is_some());
        assert_eq!(parser.stats().drop_count, 0);
    }

    #[test]
    fn sequence_increments_on_each_valid_frame() {
        let mut parser = LiveViewParser::new(session());
        let jpeg = minimal_jpeg();
        for i in 1..=5u64 {
            let frame = make_frame(&jpeg, i as u32);
            parser.parse_frame(&frame).unwrap();
            assert_eq!(parser.stats().sequence, i);
        }
    }

    #[test]
    fn exactly_ring_buffer_size_frame_is_accepted() {
        let mut parser = LiveViewParser::new(session());
        let payload_len = RING_BUFFER_SIZE - FRAME_HEADER_TOTAL_LEN;
        let mut jpeg_payload = vec![0u8; payload_len];
        jpeg_payload[0] = JPEG_SOI_BYTE_0;
        jpeg_payload[1] = JPEG_SOI_BYTE_1;
        let frame = make_frame(&jpeg_payload, 1);
        assert_eq!(frame.len(), RING_BUFFER_SIZE);
        assert!(
            parser.parse_frame(&frame).is_ok(),
            "max-size frame must be accepted (not FrameTooLarge)"
        );
        assert_eq!(parser.stats().drop_count, 0);
    }

    // Frame counter

    #[test]
    fn last_frame_counter_is_none_before_first_parse() {
        let parser = LiveViewParser::new(session());
        assert!(parser.last_frame_counter().is_none());
    }

    #[test]
    fn last_frame_counter_reflects_fcw_counter_field() {
        let mut parser = LiveViewParser::new(session());
        let frame = make_frame(&minimal_jpeg(), 0xDEAD_BEEF);
        parser.parse_frame(&frame).unwrap();
        assert_eq!(parser.last_frame_counter(), Some(0xDEAD_BEEF));
    }

    // From<LiveViewError> for FujiError

    #[test]
    fn live_view_error_maps_to_fuji_error() {
        use fuji_core::{FujiError, ProtocolError, TransferError, TransportError};
        assert_eq!(
            FujiError::from(LiveViewError::FrameTooLarge),
            FujiError::Transfer(TransferError::PartialReadFailed),
        );
        assert_eq!(
            FujiError::from(LiveViewError::MalformedHeader),
            FujiError::Protocol(ProtocolError::UnexpectedPacket),
        );
        assert_eq!(
            FujiError::from(LiveViewError::UnexpectedEof),
            FujiError::Transport(TransportError::Eof),
        );
        assert_eq!(
            FujiError::from(LiveViewError::InvalidJpegMarker),
            FujiError::Protocol(ProtocolError::UnexpectedPacket),
        );
    }

    // FrameStats defaults

    #[test]
    fn frame_stats_default_is_zeroed() {
        let stats = FrameStats::default();
        assert_eq!(stats.sequence, 0);
        assert_eq!(stats.drop_count, 0);
        assert!(stats.last_parse_monotonic.is_none());
    }

    // Async read-loop tests

    #[tokio::test]
    async fn loop_delivers_all_frames_then_eof() {
        let jpeg = minimal_jpeg();
        let frames: Vec<Vec<u8>> = (1u32..=3).map(|c| make_frame(&jpeg, c)).collect();
        let mut stream = build_stream(&frames);
        let stop = AtomicBool::new(false);
        let mut parser = LiveViewParser::new(session());
        let mut delivered: Vec<Vec<u8>> = Vec::new();

        let result = run_liveview_loop(
            &mut parser,
            &mut stream,
            |s| delivered.push(s.to_vec()),
            &stop,
        )
        .await;

        assert_eq!(result, Err(LiveViewError::UnexpectedEof));
        assert_eq!(delivered.len(), 3);
        assert_eq!(parser.stats().sequence, 3);
        for d in &delivered {
            assert_eq!(d[0], JPEG_SOI_BYTE_0);
            assert_eq!(d[1], JPEG_SOI_BYTE_1);
        }
    }

    #[tokio::test]
    async fn loop_exits_immediately_on_preset_stop_signal() {
        let jpeg = minimal_jpeg();
        let frames: Vec<Vec<u8>> = (1u32..=100).map(|c| make_frame(&jpeg, c)).collect();
        let mut stream = build_stream(&frames);
        let stop = AtomicBool::new(true);
        let mut parser = LiveViewParser::new(session());
        let mut count = 0usize;

        let result = run_liveview_loop(&mut parser, &mut stream, |_| count += 1, &stop).await;

        assert_eq!(result, Ok(()));
        assert_eq!(count, 0, "stop was pre-set; no frames should be delivered");
    }

    #[tokio::test]
    async fn loop_skips_bad_jpeg_marker_and_delivers_next() {
        let bad_payload = vec![0x00u8, 0x01u8, 0x02u8, 0x03u8];
        let good_jpeg = minimal_jpeg();
        let frames = vec![make_frame(&bad_payload, 1), make_frame(&good_jpeg, 2)];
        let mut stream = build_stream(&frames);
        let stop = AtomicBool::new(false);
        let mut parser = LiveViewParser::new(session());
        let mut delivered = 0usize;

        let result = run_liveview_loop(&mut parser, &mut stream, |_| delivered += 1, &stop).await;

        assert_eq!(result, Err(LiveViewError::UnexpectedEof));
        assert_eq!(delivered, 1);
        assert_eq!(parser.stats().sequence, 1);
    }

    #[tokio::test]
    async fn loop_returns_malformed_header_on_undersized_declared_len() {
        let tiny_len: u32 = 4;
        let mut buf = tiny_len.to_le_bytes().to_vec();
        buf.extend_from_slice(&[0u8; 20]);
        let mut stream = std::io::Cursor::new(buf);
        let stop = AtomicBool::new(false);
        let mut parser = LiveViewParser::new(session());

        let result = run_liveview_loop(&mut parser, &mut stream, |_| {}, &stop).await;
        assert_eq!(result, Err(LiveViewError::MalformedHeader));
    }

    #[tokio::test]
    async fn loop_returns_unexpected_eof_on_empty_stream() {
        let mut stream = std::io::Cursor::new(Vec::<u8>::new());
        let stop = AtomicBool::new(false);
        let mut parser = LiveViewParser::new(session());

        let result = run_liveview_loop(&mut parser, &mut stream, |_| {}, &stop).await;
        assert_eq!(result, Err(LiveViewError::UnexpectedEof));
    }

    // H-1 regression: oversized frame followed by valid frame — stream must stay aligned.
    //
    // Constructs a stream where:
    //   - Frame 1: 4-byte transport prefix declaring RING_BUFFER_SIZE + 1 bytes,
    //     followed by the full body (RING_BUFFER_SIZE + 1 - 4 bytes of zeros).
    //     This triggers the FrameTooLarge drain path.
    //   - Frame 2: a well-formed JPEG frame.
    //
    // The loop must drain the oversized body, realign on Frame 2, deliver it, then
    // return UnexpectedEof (stream exhausted). If the drain is missing the cursor
    // position is wrong and Frame 2 is never parsed — delivered count stays 0.
    #[tokio::test]
    async fn loop_drains_oversized_frame_and_delivers_next_valid_frame() {
        // Build the oversized "frame": a 4-byte prefix claiming RING_BUFFER_SIZE + 1
        // bytes, followed by the matching body so the stream is well-formed at the TCP
        // level. The parser must drain the body without allocating it all at once.
        let oversized_declared: u32 = (RING_BUFFER_SIZE + 1) as u32;
        let body_len = (RING_BUFFER_SIZE + 1).saturating_sub(TRANSPORT_PREFIX_LEN);
        let mut buf = Vec::with_capacity(RING_BUFFER_SIZE + 1 + 64);
        buf.extend_from_slice(&oversized_declared.to_le_bytes());
        buf.extend(std::iter::repeat_n(0u8, body_len));

        // Append a well-formed valid frame immediately after.
        let valid_frame = make_frame(&minimal_jpeg(), 99);
        buf.extend_from_slice(&valid_frame);

        let mut stream = std::io::Cursor::new(buf);
        let stop = AtomicBool::new(false);
        let mut parser = LiveViewParser::new(session());
        let mut delivered: Vec<Vec<u8>> = Vec::new();

        let result = run_liveview_loop(
            &mut parser,
            &mut stream,
            |s| delivered.push(s.to_vec()),
            &stop,
        )
        .await;

        // Stream ends after the valid frame → UnexpectedEof.
        assert_eq!(
            result,
            Err(LiveViewError::UnexpectedEof),
            "loop must exhaust the stream and return UnexpectedEof"
        );
        // The valid frame after the oversized one must have been delivered.
        assert_eq!(
            delivered.len(),
            1,
            "exactly one valid frame must be delivered after the oversized drain"
        );
        assert_eq!(
            delivered[0][0], JPEG_SOI_BYTE_0,
            "delivered frame must start with JPEG SOI byte 0"
        );
        assert_eq!(
            delivered[0][1], JPEG_SOI_BYTE_1,
            "delivered frame must start with JPEG SOI byte 1"
        );
        // drop_count was bumped once (by frame_buffer_mut for the oversized frame).
        assert_eq!(
            parser.stats().drop_count,
            1,
            "drop_count must be 1 after one oversized frame"
        );
        // sequence was bumped once (for the successfully delivered valid frame).
        assert_eq!(
            parser.stats().sequence,
            1,
            "sequence must be 1 after one successfully delivered frame"
        );
    }
}
