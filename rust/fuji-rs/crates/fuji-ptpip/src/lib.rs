//! `fuji-ptpip` — synchronous PTP-IP-over-TCP command client for Frameport.
//!
//! # Architecture
//!
//! This crate provides two things:
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

#![forbid(unsafe_code)]

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
    /// Total payload bytes declared in `StartData.total_data_length`.
    total: u64,
    /// Bytes remaining to read from the socket.
    remaining: u64,
    /// Transaction ID of the in-flight `GetObject` operation.
    txn_id: u32,
    /// Whether we have already read the 12-byte `EndData` header.
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
        self.expect_ok_response(resp, "OpenSession")
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
        self.expect_ok_response(resp, "GetThumb")?;

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
            total,
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
    /// The `GetObject` data phase on PTP-IP (via `fuji-sim` and real cameras)
    /// sends all data in a single `EndData` packet (no intermediate `DataPacket`
    /// chunks for objects up to ~64 MiB).  The client reads the `EndData` frame
    /// header (12 bytes: length u32, type u32, txn u32) manually WITHOUT calling
    /// `decode_packet` (which would buffer the whole payload), then streams the
    /// payload bytes directly from the `TcpStream` in chunks of up to `buf.len()`.
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

        // Read the EndData frame header the first time we enter the payload.
        if !state.end_header_read {
            // EndData frame: length(4) | type(4) | txn(4) → 12 bytes minimum.
            // We read it manually so we DON'T pass the full large frame through
            // decode_packet (which would allocate the entire payload in RAM).
            let mut hdr = [0u8; 12];
            self.stream
                .read_exact(&mut hdr)
                .map_err(|_e| FujiError::Transfer(TransferError::CameraDisconnected))?;

            let declared_frame_len = u32::from_le_bytes([hdr[0], hdr[1], hdr[2], hdr[3]]);
            let pkt_type = u32::from_le_bytes([hdr[4], hdr[5], hdr[6], hdr[7]]);

            // Type 0x000C = DataPacketEnd.
            if pkt_type != 0x000C {
                return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket));
            }
            if declared_frame_len < 12 {
                return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
                    declared: declared_frame_len,
                    minimum: 12,
                }));
            }

            let payload_len = (declared_frame_len - 12) as u64;

            // Validate payload length matches total declared in StartData.
            // (Allow exact match only; deviation is a protocol error.)
            if payload_len != state.total {
                return Err(FujiError::Protocol(ProtocolError::ResponseMismatch));
            }

            // Re-borrow after stream access.
            let state = self
                .object_stream
                .as_mut()
                .ok_or(FujiError::Protocol(ProtocolError::UnexpectedPacket))?;
            state.end_header_read = true;
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
            self.expect_ok_response(resp, "GetObject")?;
            let _ = txn_id;
            return Ok(0);
        }

        // How many bytes to read this chunk.
        let to_read = buf.len().min(state.remaining as usize);
        let n = self
            .stream
            .read(&mut buf[..to_read])
            .map_err(|_| FujiError::Transfer(TransferError::CameraDisconnected))?;

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
    /// Reads the 4-byte length prefix first, allocates exactly that many bytes,
    /// then calls `decode_packet`.  Safe for small packets (handshake, responses,
    /// StartData, small EndData).  NOT for use with bulk object payloads — use
    /// [`read_object_chunk`](Self::read_object_chunk) instead.
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
        self.expect_ok_response(resp, "data-phase")?;
        let _ = expected_txn;

        Ok(payload)
    }

    /// Validates that `pkt` is an `OperationResponse` with `response_code == OK`.
    fn expect_ok_response(&self, pkt: PtpIpPacket, op: &str) -> FujiResult<()> {
        match pkt {
            PtpIpPacket::OperationResponse { response_code, .. }
                if response_code == response_code::OK =>
            {
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
            _ => {
                let _ = op;
                Err(FujiError::Protocol(ProtocolError::UnexpectedPacket))
            }
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
}
