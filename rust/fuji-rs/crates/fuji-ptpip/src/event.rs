//! Async PTP-IP event-channel reader for the remote-capture path.
//!
//! # Architecture
//!
//! The event channel (port 55741 / `PORT_EVENT`) is a SEPARATE TCP connection from the
//! command channel (port 55740 / `PORT_COMMAND`). Per ptp-ptpip.md section 5.1 steps 5-7,
//! the client opens this channel after `InitCommandAck` but the camera only begins sending
//! events AFTER `InitiateOpenCapture` (opcode `0x101C`) is sent on the command channel.
//!
//! # Fd-ownership contract
//!
//! `EventChannelReader::from_raw_fd` takes ownership of a raw file descriptor that was
//! previously duplicated (via `dup(2)` on Android) by the Kotlin layer. After the call
//! returns, Rust owns the fd exclusively:
//!
//! 1. Android calls `ParcelFileDescriptor.fromSocket(socket).detachFd()`.
//! 2. Android passes the resulting raw fd to Rust over JNI (as `jint`).
//! 3. Rust calls `EventChannelReader::from_raw_fd(fd)` — Rust takes ownership.
//! 4. If Rust drops `EventChannelReader`, the fd is closed.
//! 5. Android MUST NOT close or use the original fd after the JNI call.
//!
//! This mirrors the command-channel fd-dup discipline in `open_from_owned_socket_fd`.
//!
//! # Event-packet framing uncertainty
//!
//! master-constants.md line 836 documents an IMPORTANT UNCERTAINTY: no source confirms
//! the exact inner payload layout of the Fujifilm event packets on port 55741. This
//! implementation parses the DOCUMENTED PTP-IP Event container framing (type=0x0008,
//! the 14-byte header layout identical to `OperationResponse`) and dispatches by event
//! code; the inner Fuji event body beyond the standard header is treated as opaque.
//!
//! Callers that need the inner body bytes will find them in `FujiEvent::raw_params`.
//! Do NOT add logic that assumes a specific field layout for the inner body until
//! hardware confirmation is available.
//!
//! # Cancel-safety note
//!
//! `EventChannelReader::poll_next` is NOT cancel-safe when awaited directly.  It holds
//! a partial TCP read across `.await` points; dropping the future mid-read loses buffered
//! bytes and leaves the stream misaligned.  Use it only as `reader.poll_next().await`
//! to completion, or wrap it inside a task that is driven to completion before cancellation.

use std::os::unix::io::FromRawFd;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use fuji_core::{FujiError, FujiResult, TransportError};
use fuji_ptp::constants::event_code;
use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};

// ── FujiEvent ─────────────────────────────────────────────────────────────────

/// A typed PTP-IP event received on the event channel (port 55741).
///
/// The `raw_params` field carries any parameters that follow the fixed 14-byte
/// PTP-IP Event header. Their interpretation is uncertain pending hardware
/// confirmation (master-constants.md line 836).
///
/// # Discriminants — from `fuji_ptp::constants::event_code`
///
/// | Variant                | Code   | Source |
/// |------------------------|--------|--------|
/// | `CaptureComplete`      | 0x400D | master-constants.md §4a |
/// | `ObjectAdded`          | 0x4002 | master-constants.md §4a |
/// | `CameraStatusChanged`  | 0x4006 | master-constants.md §4a |
/// | `Unknown`              | any    | unrecognised event code |
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum FujiEvent {
    /// `CaptureComplete` (0x400D) — camera finished taking the photo.
    ///
    /// This is the signal `RemoteSession` waits for after `InitiateOpenCapture`.
    ///
    /// # Uncertainty note
    ///
    /// master-constants.md line 836: the exact inner body layout for the
    /// CaptureComplete event on port 55741 is not confirmed from a hardware source.
    /// The transaction_id and raw_params are parsed from the standard PTP-IP Event
    /// container; their semantic meaning for Fujifilm is unconfirmed.
    CaptureComplete {
        /// Transaction ID from the PTP-IP Event header.
        transaction_id: u32,
        /// Raw parameter words (0–3) following the fixed header. Layout uncertain.
        raw_params: Vec<u32>,
    },
    /// `ObjectAdded` (0x4002) — a new media object is available on the camera.
    ObjectAdded {
        /// Transaction ID from the PTP-IP Event header.
        transaction_id: u32,
        /// Raw parameter words. Params[0] is typically the object handle, but
        /// this is not hardware-confirmed for the 55741 event channel.
        raw_params: Vec<u32>,
    },
    /// `DevicePropChanged` (0x4006) — a device property value changed.
    CameraStatusChanged {
        /// Transaction ID from the PTP-IP Event header.
        transaction_id: u32,
        /// Raw parameter words.
        raw_params: Vec<u32>,
    },
    /// An event code not listed above. Carry-forward for forward compatibility.
    Unknown {
        /// The raw PTP event code.
        event_code: u16,
        /// Transaction ID from the PTP-IP Event header.
        transaction_id: u32,
        /// Raw parameter words.
        raw_params: Vec<u32>,
    },
}

// ── EventChannelReader ────────────────────────────────────────────────────────

/// Async reader for PTP-IP events on the event channel (port 55741 / `PORT_EVENT`).
///
/// Owns a `tokio::net::TcpStream` wrapping either:
/// - an Android-owned fd (production path — via `from_raw_fd`), or
/// - a direct loopback `TcpStream` (hermetic tests — via `from_stream`).
///
/// # Handshake
///
/// Before events arrive the reader must complete the event-channel handshake:
///
/// 1. Send `InitEventRequest { connection_number: 1 }`.
/// 2. Receive `InitEventAck`.
///
/// Call `handshake()` exactly once after construction. The camera starts
/// sending events only after `InitiateOpenCapture` on the command channel.
///
/// # Packet framing
///
/// Each call to `poll_next` reads one complete PTP-IP Event packet using the
/// standard length-prefix framing (4-byte LE length, then the rest of the
/// packet). The minimum packet size is 14 bytes (8-byte header + 2-byte
/// event_code + 4-byte transaction_id). Parameters follow at 4 bytes each.
///
/// # Cancel-safety
///
/// NOT cancel-safe. See module-level doc.
pub struct EventChannelReader {
    stream: TcpStream,
}

impl EventChannelReader {
    // ── Construction ──────────────────────────────────────────────────────────

    /// Wraps an already-connected `tokio::net::TcpStream`.
    ///
    /// Used in hermetic tests where the test connects directly to a loopback
    /// `TcpListener`. In production use `from_raw_fd`.
    ///
    // cancel-safe: synchronous constructor, no .await points.
    pub fn from_stream(stream: TcpStream) -> Self {
        Self { stream }
    }

    /// Takes ownership of a raw file descriptor and wraps it in a tokio `TcpStream`.
    ///
    /// # Fd ownership
    ///
    /// After this call Rust owns `fd` exclusively. The Android side MUST NOT
    /// close or use the original fd after passing it here. See the module-level
    /// fd-ownership contract for the full dup-then-transfer sequence.
    ///
    /// # Errors
    ///
    /// Returns `TransportError::ReadFailed` (wrapped in `FujiError::Transport`) if
    /// `TcpStream::from_std` fails (e.g. the fd is not a valid TCP socket).
    ///
    // cancel-safe: synchronous conversion, no .await points.
    //
    // SAFETY: `fd` must be a valid, open file descriptor that refers to a TCP
    // socket. The caller (Android JNI layer) must have already duplicated the
    // original fd (via dup(2)) so that Rust holds exclusive ownership. After
    // this call the caller MUST NOT close or use the original fd — Rust's Drop
    // impl for TcpStream will close it. Violating this invariant causes a
    // double-close (use-after-free at the OS level).
    pub fn from_raw_fd(fd: i32) -> FujiResult<Self> {
        // SAFETY: We require the caller to pass an owned, valid TCP-socket fd.
        // See the SAFETY block above and the module-level fd-ownership contract.
        let std_stream = unsafe { std::net::TcpStream::from_raw_fd(fd) };
        std_stream
            .set_nonblocking(true)
            .map_err(|_| FujiError::Transport(TransportError::ReadFailed))?;
        let tokio_stream = TcpStream::from_std(std_stream)
            .map_err(|_| FujiError::Transport(TransportError::ReadFailed))?;
        Ok(Self {
            stream: tokio_stream,
        })
    }

    // ── Handshake ─────────────────────────────────────────────────────────────

    /// Sends `InitEventRequest` and waits for `InitEventAck`.
    ///
    /// Must be called exactly once after construction, before `poll_next`.
    ///
    /// # Cancel-safety
    ///
    /// NOT cancel-safe: holds partial TCP read/write state across `.await` points.
    /// Drive this future to completion without cancelling.
    pub async fn handshake(&mut self) -> FujiResult<()> {
        // Send InitEventRequest with connection_number = 1 (fixed by protocol).
        // ptp-ptpip.md section 5.1 steps 5-7: connection_number is always 1. [H]
        let req = PtpIpPacket::InitEventRequest {
            connection_number: 1,
        };
        self.write_packet(&req).await?;

        // Expect InitEventAck (8 bytes, header only).
        match self.read_raw_packet().await? {
            PtpIpPacket::InitEventAck => Ok(()),
            _ => Err(FujiError::Protocol(
                fuji_core::ProtocolError::HandshakeRejected,
            )),
        }
    }

    // ── Event polling ─────────────────────────────────────────────────────────

    /// Reads the next PTP-IP Event packet from the stream.
    ///
    /// Blocks (asynchronously) until one complete packet arrives or the connection
    /// is closed. On EOF or connection-reset, returns
    /// `FujiError::Transport(TransportError::EventChannelClosed)`.
    ///
    /// # Cancel-safety
    ///
    /// NOT cancel-safe: partial TCP reads leave the stream framing misaligned.
    /// Always drive this future to completion.
    pub async fn poll_next(&mut self) -> FujiResult<FujiEvent> {
        let pkt = self.read_raw_packet().await?;
        match pkt {
            PtpIpPacket::Event {
                event_code: code,
                transaction_id,
                params,
            } => Ok(dispatch_event(code, transaction_id, params)),
            // Any non-Event packet on the event channel is unexpected; treat as
            // a protocol error so the caller can react (close, reconnect, etc.).
            _ => Err(FujiError::Protocol(
                fuji_core::ProtocolError::UnexpectedPacket,
            )),
        }
    }

    // ── Internal framing I/O ──────────────────────────────────────────────────

    /// Reads one length-prefixed PTP-IP packet from the stream.
    ///
    /// Frame format: 4-byte LE length (including those 4 bytes), then the rest.
    /// Minimum length is 8 bytes (common header).
    ///
    // NOT cancel-safe: a partial read leaves `self.stream` in an irrecoverable
    // framing state. Never used under tokio::select!; always driven to completion.
    async fn read_raw_packet(&mut self) -> FujiResult<PtpIpPacket> {
        // Read the 4-byte length prefix.
        let mut len_buf = [0u8; 4];
        match self.stream.read_exact(&mut len_buf).await {
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                return Err(FujiError::Transport(TransportError::EventChannelClosed));
            }
            Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => {
                return Err(FujiError::Transport(TransportError::EventChannelClosed));
            }
            Err(_) => {
                return Err(FujiError::Transport(TransportError::EventChannelClosed));
            }
        }

        let declared = u32::from_le_bytes(len_buf) as usize;
        // Minimum valid PTP-IP packet is 8 bytes (4-byte length + 4-byte type).
        if declared < 8 {
            return Err(FujiError::Protocol(
                fuji_core::ProtocolError::InvalidPacketLength {
                    declared: declared as u32,
                    minimum: 8,
                },
            ));
        }
        // Cap to prevent allocation exhaustion on malformed packets. Same cap as fuji-sim.
        const MAX_EVENT_PACKET_BYTES: usize = 256;
        if declared > MAX_EVENT_PACKET_BYTES {
            return Err(FujiError::Protocol(
                fuji_core::ProtocolError::InvalidPacketLength {
                    declared: declared as u32,
                    minimum: 8,
                },
            ));
        }

        // Read the rest of the packet into a single allocation.
        let mut buf = vec![0u8; declared];
        buf[..4].copy_from_slice(&len_buf);
        match self.stream.read_exact(&mut buf[4..]).await {
            Ok(_) => {}
            Err(_) => {
                return Err(FujiError::Transport(TransportError::EventChannelClosed));
            }
        }

        decode_packet(&buf)
            .map_err(|_| FujiError::Protocol(fuji_core::ProtocolError::UnexpectedPacket))
    }

    /// Encodes `packet` and writes it to the stream.
    ///
    // NOT cancel-safe: a partial write leaves the stream in an invalid state.
    // Never used under tokio::select!; always driven to completion.
    async fn write_packet(&mut self, pkt: &PtpIpPacket) -> FujiResult<()> {
        let bytes = encode_packet(pkt);
        self.stream
            .write_all(&bytes)
            .await
            .map_err(|_| FujiError::Transport(TransportError::WriteFailed))
    }
}

// ── Event dispatch ────────────────────────────────────────────────────────────

/// Maps a raw PTP-IP event code + params to a typed `FujiEvent`.
///
/// All event codes reference `fuji_ptp::constants::event_code`; no bare
/// hex literals are used in match arms.
// cancel-safe: synchronous — no .await points.
fn dispatch_event(code: u16, transaction_id: u32, params: Vec<u32>) -> FujiEvent {
    if code == event_code::CAPTURE_COMPLETE {
        FujiEvent::CaptureComplete {
            transaction_id,
            raw_params: params,
        }
    } else if code == event_code::OBJECT_ADDED {
        FujiEvent::ObjectAdded {
            transaction_id,
            raw_params: params,
        }
    } else if code == event_code::DEVICE_PROP_CHANGED {
        FujiEvent::CameraStatusChanged {
            transaction_id,
            raw_params: params,
        }
    } else {
        FujiEvent::Unknown {
            event_code: code,
            transaction_id,
            raw_params: params,
        }
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use fuji_ptp::constants::event_code;

    #[test]
    fn dispatch_event_capture_complete() {
        let ev = dispatch_event(event_code::CAPTURE_COMPLETE, 7, vec![]);
        assert!(matches!(
            ev,
            FujiEvent::CaptureComplete {
                transaction_id: 7,
                ..
            }
        ));
    }

    #[test]
    fn dispatch_event_object_added() {
        let ev = dispatch_event(event_code::OBJECT_ADDED, 2, vec![0x0001_0001]);
        assert!(matches!(
            ev,
            FujiEvent::ObjectAdded {
                transaction_id: 2,
                ..
            }
        ));
    }

    #[test]
    fn dispatch_event_device_prop_changed() {
        let ev = dispatch_event(event_code::DEVICE_PROP_CHANGED, 1, vec![]);
        assert!(matches!(ev, FujiEvent::CameraStatusChanged { .. }));
    }

    #[test]
    fn dispatch_event_unknown() {
        let ev = dispatch_event(0xBEEF, 99, vec![1, 2]);
        assert!(matches!(
            ev,
            FujiEvent::Unknown {
                event_code: 0xBEEF,
                ..
            }
        ));
    }

    // ── Integration: handshake + poll_next over loopback TCP ─────────────────

    #[tokio::test]
    async fn event_reader_handshake_and_capture_complete() {
        use std::time::Duration;
        use tokio::io::AsyncWriteExt as _;
        use tokio::net::TcpListener;
        use tokio::time::timeout;

        use fuji_ptp::{PtpIpPacket, encode_packet};

        // Bind an ephemeral loopback listener (no fixed port).
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        // Spawn a fake server that:
        // 1. Accepts the connection.
        // 2. Reads InitEventRequest.
        // 3. Replies InitEventAck.
        // 4. Writes a CaptureComplete event packet.
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();

            // Read client's InitEventRequest (12 bytes).
            let mut buf = [0u8; 12];
            stream.read_exact(&mut buf).await.unwrap();

            // Send InitEventAck (8 bytes).
            let ack = encode_packet(&PtpIpPacket::InitEventAck);
            stream.write_all(&ack).await.unwrap();

            // Send CaptureComplete event (event_code=0x400D, txn=1, no params).
            // Uses event_code::CAPTURE_COMPLETE from fuji_ptp::constants.
            let ev = encode_packet(&PtpIpPacket::Event {
                event_code: event_code::CAPTURE_COMPLETE,
                transaction_id: 1,
                params: vec![],
            });
            stream.write_all(&ev).await.unwrap();
        });

        let client = timeout(Duration::from_secs(3), async {
            let stream = tokio::net::TcpStream::connect(addr).await.unwrap();
            let mut reader = EventChannelReader::from_stream(stream);
            reader.handshake().await.unwrap();
            let ev = reader.poll_next().await.unwrap();
            assert!(
                matches!(
                    ev,
                    FujiEvent::CaptureComplete {
                        transaction_id: 1,
                        ..
                    }
                ),
                "expected CaptureComplete with txn=1, got {ev:?}"
            );
        });

        client.await.expect("client timed out");
        server.await.unwrap();
    }

    #[tokio::test]
    async fn event_reader_eof_returns_event_channel_closed() {
        use tokio::net::TcpListener;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        // Server that immediately closes after the handshake.
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            // Read InitEventRequest.
            let mut buf = [0u8; 12];
            let _ = stream.read_exact(&mut buf).await;
            // Send InitEventAck.
            let ack = fuji_ptp::encode_packet(&PtpIpPacket::InitEventAck);
            let _ = stream.write_all(&ack).await;
            // Drop stream → EOF on client.
        });

        let stream = tokio::net::TcpStream::connect(addr).await.unwrap();
        let mut reader = EventChannelReader::from_stream(stream);
        reader.handshake().await.unwrap();
        let result = reader.poll_next().await;
        assert!(
            matches!(
                result,
                Err(FujiError::Transport(TransportError::EventChannelClosed))
            ),
            "expected EventChannelClosed on EOF, got {result:?}"
        );
    }
}
