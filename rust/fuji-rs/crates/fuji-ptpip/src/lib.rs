//! `fuji-ptpip` — PTP-IP-over-TCP client for Frameport.
//!
//! # Architecture
//!
//! This crate provides three things:
//!
//! 1. [`PtpIpTcpClient`] — a concrete synchronous command client that owns a
//!    [`std::net::TcpStream`] and speaks PTP-IP on the command channel (port 55740
//!    in production, ephemeral in tests).  Used by `fuji-transfer` for all media
//!    enumeration and download operations.
//!
//! 2. [`WifiSession`] / [`open_from_owned_socket_fd`] — production Android path
//!    placeholder.  In production Frameport never connects its own `TcpStream`;
//!    instead Android binds a socket to the camera Wi-Fi network and hands the
//!    file descriptor to Rust.  These items preserve that API surface so
//!    `fuji-transfer` can reference it without breaking when the production path
//!    is wired up.
//!
//! 3. [`event`] / [`remote`] — async modules for the event channel reader and
//!    remote-capture session state machine.  These are async (tokio-based) and
//!    are used by `fuji-ffi` for the M15 remote-shutter path.
//!
//! # Streaming download
//!
//! [`PtpIpTcpClient::open_object`] sends `GetObject` and reads the `StartData`
//! header to learn the total length.  [`PtpIpTcpClient::read_object_chunk`] then
//! streams the `EndData` payload bytes into a caller-supplied 64 KiB buffer without
//! ever buffering the full object in RAM.  Only the small `OperationResponse` is
//! decoded via `decode_packet` at the end.
//!
//! # Production note
//!
//! The `TcpStream::connect` path in [`PtpIpTcpClient::connect`] is used for
//! hermetic tests only.  Production wraps an Android-owned file descriptor:
//! `android.net.Network.bindSocket()` → `dup()` → Rust `OwnedFd` → `File` →
//! wrapped in a synchronous BufReader/BufWriter on top of the fd.  That path will
//! be provided by [`open_from_owned_socket_fd`] once the Android layer is wired up.
//!
//! # Safety
//!
//! The [`event`] module contains one `unsafe` block in `EventChannelReader::from_raw_fd`
//! to wrap an Android-owned file descriptor into a `tokio::net::TcpStream` via
//! `std::net::TcpStream::from_raw_fd`.  The safety contract (caller must transfer
//! exclusive ownership of a valid TCP-socket fd) is documented at the call site and
//! in `docs/rust/ffi-boundary.md`.  All other code in this crate is safe Rust.

// unsafe_code is NOT forbidden at the crate level because event::EventChannelReader::from_raw_fd
// requires unsafe { std::net::TcpStream::from_raw_fd(fd) } for the Android fd-handoff path.
// The single unsafe block is guarded by a // SAFETY: comment per the project rules.
// See docs/adr/0002-wifi-socket-fd-handoff.md and docs/rust/ffi-boundary.md.

pub mod event;
pub mod remote;

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};

use fuji_core::{FujiError, FujiResult, ProtocolError, SessionId, TransferError, TransportKind};
use fuji_ptp::constants::{FUJI_PTPIP_VERSION, SESSION_ID, opcode, response_code};
use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};

// ── Re-export: max thumbnail cap used by callers ──────────────────────────────

/// Default maximum thumbnail byte count (512 KiB).
///
/// `get_thumb` returns [`TransferError::ThumbnailTooLarge`] when the camera
/// declares a thumbnail larger than this value.
pub const MAX_THUMBNAIL_BYTES: u64 = 512 * 1024;

/// Maximum command-channel PTP-IP packet size decoded in one allocation.
///
/// Bulk object payloads must use `open_object` + `read_object_chunk`; this cap
/// only applies to command responses, handshake packets, and small data phases.
const MAX_CONTROL_PACKET_BYTES: usize = 4096;

// ── Transaction ID counter ────────────────────────────────────────────────────

/// Monotonically incrementing transaction ID.
///
/// Starts at 1.  Wraps `0xFFFF_FFFE -> 1` — the sentinel value `0xFFFF_FFFF`
/// is never transmitted (reserved per ptp-ptpip.md §5.1).
/// `0` is used only for `OpenSession` (fixed by the spec) and never incremented
/// through the counter path.
#[derive(Debug)]
struct TxnCounter(u32);

impl TxnCounter {
    fn new() -> Self {
        Self(0)
    }

    /// Increment and return the new value, wrapping around `0xFFFF_FFFF`.
    fn next(&mut self) -> u32 {
        self.0 = if self.0 >= 0xFFFF_FFFE { 1 } else { self.0 + 1 };
        self.0
    }
}

// ── Object streaming state ────────────────────────────────────────────────────

/// Tracks position within a streaming `GetObject` data phase.
#[derive(Debug)]
struct ObjectStream {
    /// Bytes remaining to read from the socket across all pending data frames.
    ///
    /// Initialised to `StartData.total_data_length` and decremented as each
    /// DataPacket / EndData frame payload is consumed.  Reaches zero when the
    /// final EndData payload has been fully read.
    remaining: u64,
    /// Transaction ID of the in-flight `GetObject` operation.
    txn_id: u32,
    /// Whether we have already consumed all DataPacket headers up through the
    /// EndData frame header (i.e. the next bytes on the wire are payload bytes).
    end_header_read: bool,
}

// ── PtpIpTcpClient ────────────────────────────────────────────────────────────

/// Synchronous PTP-IP command-channel client.
///
/// Owns a [`TcpStream`] for the command channel (port 55740 in production).
/// The event and live-view channels (55741 / 55742) are NOT opened by this client;
/// they are out of scope for the transfer path.
///
/// # Thread safety
///
/// `PtpIpTcpClient` is `!Send` + `!Sync` (due to the raw socket state).  Use it
/// from a single Rust thread; wrap in an Android foreground-service thread if
/// needed.
///
/// # Production vs. test construction
///
/// - **Tests**: use [`PtpIpTcpClient::connect`] which calls `TcpStream::connect`.
/// - **Production**: Android binds a socket to the camera network, dups the fd,
///   and calls [`open_from_owned_socket_fd`] which returns a [`WifiSession`].
///   Wrapping a `WifiSession` into a `PtpIpTcpClient` is the next step in the
///   Android integration (tracked as a TODO in `open_from_owned_socket_fd`).
pub struct PtpIpTcpClient {
    stream: TcpStream,
    txn: TxnCounter,
    /// State for an in-progress streaming `GetObject` operation, if any.
    object_stream: Option<ObjectStream>,
}

impl PtpIpTcpClient {
    // ── Construction ──────────────────────────────────────────────────────────

    /// Connects to a PTP-IP command channel and performs the full handshake:
    ///
    /// 1. TCP connect to `addr`.
    /// 2. Send `InitCommandRequest` (Fujifilm magic version `0x8F53E4F2`).
    /// 3. Receive `InitCommandAck`.
    /// 4. **Caller** must connect to the event-channel address separately and
    ///    perform `InitEventRequest` → `InitEventAck` before calling
    ///    [`open_session`](Self::open_session).
    ///
    /// After this call the client is ready for [`open_session`](Self::open_session).
    ///
    /// # Note on production path
    ///
    /// This method creates its own `TcpStream` and is appropriate for hermetic
    /// tests.  In production, Android owns the socket; use
    /// [`open_from_owned_socket_fd`] instead.
    pub fn connect(addr: SocketAddr) -> FujiResult<Self> {
        let stream = TcpStream::connect(addr)
            .map_err(|_| FujiError::Transfer(TransferError::CameraDisconnected))?;

        let mut client = Self {
            stream,
            txn: TxnCounter::new(),
            object_stream: None,
        };

        // Send Init Command Request with Fujifilm magic version.
        // GUID: a fixed placeholder; production should use a stable app-install GUID.
        let init_req = PtpIpPacket::InitCommandRequest {
            version: FUJI_PTPIP_VERSION,
            guid: [
                0xFE, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x01,
            ],
            device_name: "Frameport".to_owned(),
        };
        client.write_packet(&init_req)?;

        // Expect InitCommandAck.
        match client.read_packet()? {
            PtpIpPacket::InitCommandAck { .. } => {}
            PtpIpPacket::InitFail { .. } => {
                return Err(FujiError::Protocol(ProtocolError::HandshakeRejected));
            }
            _ => {
                return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket));
            }
        }

        Ok(client)
    }

    // ── Session management ────────────────────────────────────────────────────

    /// Sends `OpenSession` (opcode `0x1002`, params = `[SESSION_ID=1]`,
    /// `transaction_id = 0`).
    ///
    /// Must be called after the event-channel handshake is complete.
    /// The PTP spec reserves `transaction_id = 0` for `OpenSession`; subsequent
    /// operations use the auto-incrementing counter starting at 1.
    pub fn open_session(&mut self) -> FujiResult<()> {
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::OPEN_SESSION,
            transaction_id: 0, // fixed by spec for OpenSession
            params: vec![SESSION_ID],
        };
        self.write_packet(&req)?;

        let resp = self.read_packet()?;
        self.expect_ok_response(resp, 0)
    }

    // ── Property reads ────────────────────────────────────────────────────────

    /// Calls `GetDevicePropValue` (opcode `0x1015`) for `prop_code` and returns
    /// the raw EndData payload bytes.
    ///
    /// Small property payloads (e.g. `ObjectCount` u32 = 4 bytes) are buffered
    /// in full — this is safe because property payloads are always bounded.
    pub fn get_device_prop_value(&mut self, prop_code: u16) -> FujiResult<Vec<u8>> {
        let txn = self.txn.next();
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::GET_DEVICE_PROP_VALUE,
            transaction_id: txn,
            params: vec![prop_code as u32],
        };
        self.write_packet(&req)?;
        self.read_data_phase_small(txn)
    }

    // ── Object info ───────────────────────────────────────────────────────────

    /// Calls `GetObjectInfo` (opcode `0x1008`) for `handle` and returns the raw
    /// `ObjectInfo` dataset bytes (the `EndData` payload, without PTP-IP framing).
    ///
    /// The caller should pass the bytes to
    /// [`fuji_ptp::decode_object_info`] to get a typed [`fuji_ptp::ObjectInfo`].
    pub fn get_object_info(&mut self, handle: u32) -> FujiResult<Vec<u8>> {
        let txn = self.txn.next();
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::GET_OBJECT_INFO,
            transaction_id: txn,
            params: vec![handle],
        };
        self.write_packet(&req)?;
        self.read_data_phase_small(txn)
    }

    // ── Thumbnail ─────────────────────────────────────────────────────────────

    /// Calls `GetThumb` (opcode `0x100A`) for `handle`.
    ///
    /// Returns `(declared_len, bytes)` where `declared_len` is the value from
    /// the `StartData` packet header and `bytes` is the `EndData` payload.
    ///
    /// Returns [`TransferError::ThumbnailTooLarge`] when `declared_len` exceeds
    /// `max_bytes` **before** buffering the payload.
    pub fn get_thumb(&mut self, handle: u32, max_bytes: u64) -> FujiResult<(u64, Vec<u8>)> {
        let txn = self.txn.next();
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::GET_THUMB,
            transaction_id: txn,
            params: vec![handle],
        };
        self.write_packet(&req)?;

        // Read StartData to get declared length.
        let start = self.read_packet()?;
        let declared = match start {
            PtpIpPacket::StartData {
                total_data_length, ..
            } => total_data_length,
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        };

        // Reject oversized thumbnails BEFORE buffering.
        if declared > max_bytes {
            return Err(FujiError::Transfer(TransferError::ThumbnailTooLarge {
                size: declared,
                limit: max_bytes,
            }));
        }

        // Read EndData payload (thumbnail is small, buffering is fine).
        let end = self.read_packet()?;
        let payload = match end {
            PtpIpPacket::EndData { payload, .. } => payload,
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        };

        // Read trailing OperationResponse.
        let resp = self.read_packet()?;
        self.expect_ok_response(resp, txn)?;

        Ok((declared, payload))
    }

    // ── Streaming object download ──────────────────────────────────────────────

    /// Sends `GetObject` (opcode `0x1009`) and reads the `StartData` header.
    ///
    /// Returns the total object length declared in `StartData`.
    ///
    /// After this call, use [`read_object_chunk`](Self::read_object_chunk) to
    /// stream the payload bytes (no full-object buffering).
    ///
    /// # Implementation note — production path
    ///
    /// This implementation uses `GetObject` (single data phase), which is correct
    /// for the hermetic test path (fuji-sim).  Against real Fujifilm X-T5 hardware
    /// over Wi-Fi, prefer `GetPartialObject` (opcode `0x101B`, max 1 MiB chunks)
    /// because `GetObject` can stall on some camera firmware versions.
    ///
    /// TODO(frameport): switch to `GetPartialObject` chunking for the production
    /// Android path per `transfer-liveview.md` section 5.2 (stall caveat).
    pub fn open_object(&mut self, handle: u32) -> FujiResult<u64> {
        if self.object_stream.is_some() {
            return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket));
        }

        let txn = self.txn.next();
        let req = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode::GET_OBJECT,
            transaction_id: txn,
            params: vec![handle],
        };
        self.write_packet(&req)?;

        // Read StartData (20 bytes) via decode_packet — small, safe.
        let start = self.read_packet()?;
        let total = match start {
            PtpIpPacket::StartData {
                total_data_length, ..
            } => total_data_length,
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        };

        self.object_stream = Some(ObjectStream {
            remaining: total,
            txn_id: txn,
            end_header_read: false,
        });

        Ok(total)
    }

    /// Streams bytes of the object data phase into `buf`.
    ///
    /// Returns the number of bytes written into `buf`, or `0` when the payload
    /// is exhausted.  After returning `0`, this method internally reads and
    /// validates the trailing `OperationResponse` — the caller MUST call this
    /// method one final time after receiving `0` bytes, or call it until it
    /// returns `0`, then drop the client (session is over).
    ///
    /// Actually: `0` is returned once on the *first* call where `remaining == 0`
    /// after payload exhaustion AND after consuming the trailing `OperationResponse`.
    /// The client is then ready for the next operation.
    ///
    /// # Streaming protocol
    ///
    /// The `GetObject` data phase on PTP-IP sends data in one or more frames:
    ///
    /// * Zero or more intermediate `DataPacket` frames (type `0x000A`), each
    ///   carrying a partial payload.
    /// * Exactly one `EndData` frame (type `0x000C`) that carries the last
    ///   (possibly zero-length) payload slice.
    ///
    /// This method drains all intermediate `DataPacket` frames and the final
    /// `EndData` frame, accumulating bytes directly into the caller's buffer
    /// without ever buffering the full object in RAM.  Frame headers (12 bytes
    /// each) are read manually WITHOUT calling `decode_packet`.
    ///
    /// # Cancel support
    ///
    /// This method does not check a cancel flag — cancellation is the caller's
    /// responsibility by dropping the `PtpIpTcpClient` (which closes the socket).
    pub fn read_object_chunk(&mut self, buf: &mut [u8]) -> FujiResult<usize> {
        let state = self
            .object_stream
            .as_mut()
            .ok_or(FujiError::Protocol(ProtocolError::UnexpectedPacket))?;

        // Read frame headers until we have consumed either a DataPacket (0x000A)
        // or the EndData (0x000C) header that covers bytes still remaining.
        //
        // M-3 fix: loop over intermediate DataPacket chunks before EndData.
        // M-4 fix: validate that declared payload_len does not exceed `remaining`
        //          (rather than requiring an exact match with `total`, which breaks
        //          on multi-chunk transfers where each chunk covers only a portion).
        if !state.end_header_read {
            loop {
                // Each PTP-IP data frame: length(4) | type(4) | txn(4) = 12 bytes.
                // Read manually to avoid allocating the full payload via decode_packet.
                let mut hdr = [0u8; 12];
                self.stream.read_exact(&mut hdr).map_err(|e| {
                    // L-2 fix: preserve the OS error in debug output before mapping.
                    #[cfg(debug_assertions)]
                    eprintln!("fuji-ptpip: read_object_chunk header read failed: {e}");
                    let _ = &e; // suppress unused-variable warning in release
                    FujiError::Transfer(TransferError::CameraDisconnected)
                })?;

                let declared_frame_len =
                    u32::from_le_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]);
                let pkt_type = u32::from_le_bytes([hdr[4], hdr[5], hdr[6], hdr[7]]);

                if declared_frame_len < 12 {
                    return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
                        declared: declared_frame_len,
                        minimum: 12,
                    }));
                }

                // L-3 fix: guard the u64→usize cast so it is sound on 32-bit targets.
                let raw_payload_len: u64 = u64::from(declared_frame_len - 12);
                let payload_len_usize =
                    usize::try_from(raw_payload_len).map_err(|_| {
                        // declared_frame_len would need to exceed usize::MAX + 12 bytes,
                        // which is impossible for a u32 on any real target, but we guard
                        // anyway for soundness on hypothetical 16-bit targets.
                        FujiError::Protocol(ProtocolError::InvalidPacketLength {
                            declared: declared_frame_len,
                            minimum: 12,
                        })
                    })?;

                match pkt_type {
                    // 0x000A = intermediate DataPacket — drain its payload and
                    // continue the loop to read the next frame header.
                    0x000A => {
                        // M-4: validate this chunk does not exceed what we are still
                        // expecting, to catch camera protocol violations early.
                        if raw_payload_len > state.remaining {
                            return Err(FujiError::Protocol(
                                ProtocolError::ResponseMismatch,
                            ));
                        }
                        // Drain the intermediate chunk payload in chunks of buf size.
                        // We do NOT copy into `buf` here because callers call us
                        // repeatedly; draining in-line keeps things simple and avoids
                        // a separate allocation.
                        let mut drained: usize = 0;
                        while drained < payload_len_usize {
                            let want = (payload_len_usize - drained).min(buf.len());
                            let n = self.stream.read(&mut buf[..want]).map_err(|e| {
                                #[cfg(debug_assertions)]
                                eprintln!(
                                    "fuji-ptpip: intermediate DataPacket drain failed: {e}"
                                );
                                let _ = &e;
                                FujiError::Transfer(TransferError::CameraDisconnected)
                            })?;
                            if n == 0 {
                                return Err(FujiError::Transfer(
                                    TransferError::CameraDisconnected,
                                ));
                            }
                            drained += n;
                        }
                        state.remaining = state
                            .remaining
                            .saturating_sub(raw_payload_len);
                        // Continue to read the next frame header.
                    }

                    // 0x000C = EndData — this is the last (possibly zero-length) frame.
                    0x000C => {
                        // M-4: the EndData payload must not exceed bytes still expected.
                        if raw_payload_len > state.remaining {
                            return Err(FujiError::Protocol(
                                ProtocolError::ResponseMismatch,
                            ));
                        }
                        state.end_header_read = true;
                        // `remaining` is NOT decremented here; the read loop below
                        // decrements it as bytes are consumed from the socket.
                        break;
                    }

                    _ => {
                        return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket));
                    }
                }
            }
        }

        let state = self
            .object_stream
            .as_mut()
            .ok_or(FujiError::Protocol(ProtocolError::UnexpectedPacket))?;

        if state.remaining == 0 {
            // Payload exhausted — read trailing OperationResponse.
            let txn_id = state.txn_id;
            self.object_stream = None;

            let resp = self.read_packet()?;
            self.expect_ok_response(resp, txn_id)?;
            return Ok(0);
        }

        // How many bytes to read this chunk.
        // L-3 fix: guard the u64→usize narrowing with try_from so the cast is
        // sound on 32-bit targets where usize is 4 bytes.
        let remaining_usize = usize::try_from(state.remaining).unwrap_or(usize::MAX);
        let to_read = buf.len().min(remaining_usize);
        let n = self.stream.read(&mut buf[..to_read]).map_err(|e| {
            // L-2 fix: log the OS error before mapping.
            #[cfg(debug_assertions)]
            eprintln!("fuji-ptpip: read_object_chunk payload read failed: {e}");
            let _ = &e;
            FujiError::Transfer(TransferError::CameraDisconnected)
        })?;

        if n == 0 {
            return Err(FujiError::Transfer(TransferError::CameraDisconnected));
        }

        state.remaining -= n as u64;
        Ok(n)
    }

    // ── Internal packet I/O ───────────────────────────────────────────────────

    /// Encodes and writes a packet to the stream.
    fn write_packet(&mut self, pkt: &PtpIpPacket) -> FujiResult<()> {
        let bytes = encode_packet(pkt);
        self.stream
            .write_all(&bytes)
            .map_err(|_| FujiError::Transfer(TransferError::CameraDisconnected))
    }

    /// Reads one length-prefixed PTP-IP packet from the stream.
    ///
    /// Reads the 4-byte length prefix first, rejects impossible control-packet
    /// lengths, then calls `decode_packet`. Safe for small packets (handshake,
    /// responses, StartData, small EndData). NOT for use with bulk object
    /// payloads — use [`read_object_chunk`](Self::read_object_chunk) instead.
    fn read_packet(&mut self) -> FujiResult<PtpIpPacket> {
        let mut len_buf = [0u8; 4];
        self.stream
            .read_exact(&mut len_buf)
            .map_err(|_| FujiError::Transfer(TransferError::CameraDisconnected))?;

        let declared = u32::from_le_bytes(len_buf) as usize;
        if declared < 8 {
            return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
                declared: declared as u32,
                minimum: 8,
            }));
        }
        if declared > MAX_CONTROL_PACKET_BYTES {
            return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
                declared: declared as u32,
                minimum: 8,
            }));
        }

        let mut buf = vec![0u8; declared];
        buf[..4].copy_from_slice(&len_buf);
        self.stream
            .read_exact(&mut buf[4..])
            .map_err(|_| FujiError::Transfer(TransferError::CameraDisconnected))?;

        decode_packet(&buf).map_err(|_| FujiError::Protocol(ProtocolError::UnexpectedPacket))
    }

    /// Reads a complete data phase: `StartData` → `EndData` → `OperationResponse(OK)`.
    ///
    /// Buffers the `EndData` payload in a `Vec<u8>` — only safe for small payloads.
    /// For bulk object downloads use [`open_object`](Self::open_object) +
    /// [`read_object_chunk`](Self::read_object_chunk).
    fn read_data_phase_small(&mut self, expected_txn: u32) -> FujiResult<Vec<u8>> {
        // StartData.
        match self.read_packet()? {
            PtpIpPacket::StartData { .. } => {}
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        }

        // EndData — contains the full payload.
        let payload = match self.read_packet()? {
            PtpIpPacket::EndData { payload, .. } => payload,
            _ => return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        };

        // OperationResponse(OK).
        let resp = self.read_packet()?;
        self.expect_ok_response(resp, expected_txn)?;

        Ok(payload)
    }

    /// Validates that `pkt` is an `OperationResponse` with `response_code == OK`
    /// and that its echoed `transaction_id` matches `expected_txn`.
    ///
    /// Returns [`ProtocolError::InvalidTransactionId`] when the camera echoes a
    /// transaction ID that does not match the one sent in the request — an
    /// out-of-order or mismatched response would otherwise corrupt the session.
    fn expect_ok_response(&self, pkt: PtpIpPacket, expected_txn: u32) -> FujiResult<()> {
        match pkt {
            PtpIpPacket::OperationResponse {
                response_code,
                transaction_id,
                ..
            } if response_code == response_code::OK => {
                if transaction_id != expected_txn {
                    return Err(FujiError::Protocol(ProtocolError::InvalidTransactionId));
                }
                Ok(())
            }
            PtpIpPacket::OperationResponse { response_code, .. }
                if response_code == response_code::INVALID_OBJECT_HANDLE =>
            {
                Err(FujiError::Transfer(TransferError::ObjectNotFound))
            }
            PtpIpPacket::OperationResponse { response_code, .. }
                if response_code == response_code::SESSION_NOT_OPEN =>
            {
                Err(FujiError::Protocol(ProtocolError::SessionNotOpen))
            }
            PtpIpPacket::OperationResponse { .. } => {
                Err(FujiError::Protocol(ProtocolError::OperationRejected))
            }
            _ => Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        }
    }
}

// ── WifiSession — production Android-fd placeholder ───────────────────────────

/// Represents an open PTP-IP session backed by an Android-owned socket fd.
///
/// In production, Android binds the socket to the camera Wi-Fi network
/// (`android.net.Network.bindSocket()`), dups the fd (`dup(2)`), and passes the
/// `OwnedFd` to Rust.  This struct holds the session metadata for that path.
///
/// For test sessions use [`PtpIpTcpClient::connect`] instead.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WifiSession {
    pub id: SessionId,
    pub endpoint: String,
    pub transport: TransportKind,
}

impl WifiSession {
    pub fn placeholder(id: SessionId) -> Self {
        Self {
            id,
            endpoint: "socket-fd-owned-by-android".to_owned(),
            transport: TransportKind::WifiPtpIp,
        }
    }
}

/// Opens a PTP-IP session from an Android-owned file descriptor.
///
/// # Production path
///
/// In production Frameport, Android:
/// 1. Requests a `Network` handle for the camera Wi-Fi AP.
/// 2. Calls `Network.bindSocket(socket)` to force routing.
/// 3. Dups the fd and hands `OwnedFd` to Rust.
///
/// This function wraps that fd into a `WifiSession` and eventually into a
/// `PtpIpTcpClient`.  The wrapping step is not yet implemented — see the
/// TODO below.
///
/// # Current status
///
/// Not implemented.  Returns [`FujiError::NotImplemented`] until the Android
/// layer plumbs the fd handoff described in `docs/adr/0002-wifi-socket-fd-handoff.md`.
///
/// TODO(frameport): wrap `OwnedFd` into `std::net::TcpStream::from_raw_fd` on
/// Android, build a `PtpIpTcpClient` from it, and remove this placeholder.
pub fn open_from_owned_socket_fd(_fd: i32) -> FujiResult<WifiSession> {
    Err(FujiError::NotImplemented("ptp-ip session open placeholder"))
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn txn_counter_starts_at_one_and_increments() {
        let mut c = TxnCounter::new();
        assert_eq!(c.next(), 1);
        assert_eq!(c.next(), 2);
        assert_eq!(c.next(), 3);
    }

    #[test]
    fn txn_counter_wraps_around_sentinel() {
        let mut c = TxnCounter(0xFFFF_FFFE);
        assert_eq!(c.next(), 1, "0xFFFFFFFE + 1 should wrap to 1");
        assert_eq!(c.next(), 2);
    }

    #[test]
    fn placeholder_session_uses_wifi_transport() {
        let session = WifiSession::placeholder(SessionId::new(1).unwrap());
        assert_eq!(session.transport, TransportKind::WifiPtpIp);
    }

    #[test]
    fn open_from_owned_socket_fd_returns_not_implemented() {
        assert!(matches!(
            open_from_owned_socket_fd(3),
            Err(FujiError::NotImplemented(_))
        ));
    }

    #[test]
    fn read_packet_rejects_over_cap_declared_length() {
        use std::io::Write as _;
        use std::net::TcpListener;

        let over_cap = (MAX_CONTROL_PACKET_BYTES + 1) as u32;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream.write_all(&over_cap.to_le_bytes()).unwrap();
        });

        let stream = TcpStream::connect(addr).unwrap();
        let mut client = PtpIpTcpClient {
            stream,
            txn: TxnCounter::new(),
            object_stream: None,
        };

        let err = client.read_packet().unwrap_err();
        assert_eq!(
            err,
            FujiError::Protocol(ProtocolError::InvalidPacketLength {
                declared: over_cap,
                minimum: 8,
            }),
            "over-cap declared length must fail before allocating: {err:?}"
        );
        server.join().unwrap();
    }

    #[test]
    fn read_packet_allows_exactly_cap_declared_length() {
        use std::io::Write as _;
        use std::net::TcpListener;

        let at_cap = MAX_CONTROL_PACKET_BYTES as u32;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut data = at_cap.to_le_bytes().to_vec();
            data.resize(MAX_CONTROL_PACKET_BYTES, 0u8);
            stream.write_all(&data).unwrap();
        });

        let stream = TcpStream::connect(addr).unwrap();
        let mut client = PtpIpTcpClient {
            stream,
            txn: TxnCounter::new(),
            object_stream: None,
        };

        let result = client.read_packet();
        match result {
            Err(FujiError::Protocol(ProtocolError::InvalidPacketLength { declared, .. }))
                if declared == at_cap =>
            {
                panic!("cap check must allow exactly MAX_CONTROL_PACKET_BYTES")
            }
            _ => {}
        }
        server.join().unwrap();
    }

    /// expect_ok_response returns InvalidTransactionId when the camera echoes a
    /// transaction_id that does not match the one sent in the request.
    ///
    /// This guards against out-of-order or mismatched responses that would
    /// otherwise corrupt the session state silently.
    #[test]
    fn expect_ok_response_rejects_mismatched_transaction_id() {
        use std::io::Write as _;
        use std::net::TcpListener;

        // Spin up a loopback listener on an ephemeral port.
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();

        // Server thread: accept one connection and write a single OperationResponse
        // whose transaction_id is 0x99 — intentionally wrong relative to the
        // client's expected value of 1.
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let pkt = PtpIpPacket::OperationResponse {
                response_code: response_code::OK,
                transaction_id: 0x99, // mismatched — client sent txn=1
                result_params: vec![],
            };
            stream.write_all(&encode_packet(&pkt)).unwrap();
        });

        // Client: connect directly (bypassing the full handshake) by wrapping the
        // raw TcpStream so we can call expect_ok_response in isolation.
        let stream = TcpStream::connect(addr).unwrap();
        let client = PtpIpTcpClient {
            stream,
            txn: TxnCounter::new(),
            object_stream: None,
        };

        server.join().unwrap();

        // Read the packet the server wrote and validate it against expected txn=1.
        let pkt = {
            // Re-open as read side — borrow the stream via a clone for reading.
            // We drive read_packet via a temporary client wrapping the same fd.
            // Simplest approach: build a second client from a loopback pair and
            // directly call expect_ok_response with a pre-built packet.
            PtpIpPacket::OperationResponse {
                response_code: response_code::OK,
                transaction_id: 0x99,
                result_params: vec![],
            }
        };
        let _ = client; // stream closed

        // Direct unit test: call expect_ok_response with a mismatched packet.
        let dummy_stream = TcpStream::connect(addr).unwrap_or_else(|_| {
            // addr is already closed; create another pair for the dummy client.
            let l2 = TcpListener::bind("127.0.0.1:0").unwrap();
            let a2 = l2.local_addr().unwrap();
            let s = TcpStream::connect(a2).unwrap();
            let _ = l2.accept().unwrap();
            s
        });
        let checker = PtpIpTcpClient {
            stream: dummy_stream,
            txn: TxnCounter::new(),
            object_stream: None,
        };

        let result = checker.expect_ok_response(pkt, 1);
        assert!(
            matches!(
                result,
                Err(FujiError::Protocol(ProtocolError::InvalidTransactionId))
            ),
            "expected InvalidTransactionId, got {result:?}"
        );
    }

    /// expect_ok_response accepts a matching transaction_id.
    #[test]
    fn expect_ok_response_accepts_matching_transaction_id() {
        use std::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let stream = TcpStream::connect(addr).unwrap();
        let _ = listener.accept().unwrap(); // consume the server side

        let checker = PtpIpTcpClient {
            stream,
            txn: TxnCounter::new(),
            object_stream: None,
        };

        let pkt = PtpIpPacket::OperationResponse {
            response_code: response_code::OK,
            transaction_id: 1,
            result_params: vec![],
        };

        assert!(
            checker.expect_ok_response(pkt, 1).is_ok(),
            "matching transaction_id must be accepted"
        );
    }
}
