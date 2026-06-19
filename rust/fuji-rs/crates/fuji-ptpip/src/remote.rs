//! Remote-capture session for PTP-IP over Wi-Fi.
//!
//! # Protocol sequence (master-constants.md §4a, ptp-ptpip.md §5)
//!
//! ```text
//! [cmd] OpenSession (already done by PtpIpTcpClient)
//! [cmd] SetDevicePropValue(ClientState / 0xDF01, UINT16 = REMOTE_MODE / 5)
//!          ↕  retried up to max_attempts with retry_delay_ms on BUSY (0x2019)
//! [cmd] InitiateOpenCapture (0x101C)
//!          → camera opens event (55741) and liveview (55742) channels
//! [event] wait for CaptureComplete (0x400D)
//! [cmd] TerminateOpenCapture (0x1018, param = txn_id of InitiateOpenCapture)
//! ```
//!
//! # Shutter sequence (triggered externally, e.g. by BLE)
//!
//! The PTP-IP shutter path sends `InitiateCapture` (0x100E) on the command
//! channel. The BLE path is handled outside this crate (in `fuji-ble-protocol`).
//!
//! # Configuration
//!
//! See [`RemoteSessionConfig`] for all tunable parameters with their defaults
//! derived from the M03 precedent (3 loops × 5 passes = max 15 total retries
//! at `retry_delay_ms` between passes, with `attempt_loops` outer loops).
//!
//! # Cancel-safety
//!
//! All `async fn` in this module carry explicit `// cancel-safe:` or
//! `// NOT cancel-safe:` annotations in their doc comments.

use std::time::Duration;

use tokio::time::sleep;

use fuji_core::{CameraError, FujiError, FujiResult, ProtocolError};
use fuji_ptp::constants::{function_mode, opcode, prop_code, response_code};
use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};

use crate::event::{EventChannelReader, FujiEvent};

// ── RemoteSessionConfig ───────────────────────────────────────────────────────

/// Configuration for a [`RemoteSession`].
///
/// All timing and retry counts are named fields, never hard-coded literals in
/// logic. Defaults are derived from the M03 session-init precedent:
/// 3 outer attempt loops, 5 passes per loop, 100 ms between passes.
///
/// The production values for the BUSY-retry policy come from ptp-ptpip.md
/// section 4.3: "retry up to 5 times with 500 ms delay". The `max_attempts`
/// default of 15 (3 × 5) is a conservative Frameport-specific extension of
/// that policy, configurable for test overrides.
#[derive(Clone, Debug)]
pub struct RemoteSessionConfig {
    /// Maximum number of BUSY (0x2019) retries before returning
    /// `CameraError::Busy { attempts }`. Must be ≥ 1.
    ///
    /// Default: 15 (3 loops × 5 passes — M03 precedent).
    pub max_attempts: u32,

    /// Delay between BUSY retry attempts, in milliseconds.
    ///
    /// Default: 100 ms (M03 precedent for mode-set retries; production shutter
    /// retries typically use 500 ms per ptp-ptpip.md §4.3 but 100 ms is used
    /// here because the remote-mode set is a handshake step, not a user action).
    pub retry_delay_ms: u64,

    /// Timeout for waiting for a `CaptureComplete` event after `InitiateOpenCapture`.
    ///
    /// Default: 30 000 ms (30 s). A real camera takes at most a few seconds;
    /// the long timeout accommodates slow or error-condition scenarios.
    pub capture_complete_timeout_ms: u64,
}

impl Default for RemoteSessionConfig {
    fn default() -> Self {
        // M03 precedent: 3 loops × 5 passes = 15 total retries at 100 ms/pass.
        // max_attempts field: not a literal in logic; referenced as self.config.max_attempts.
        const DEFAULT_MAX_ATTEMPTS: u32 = 15;
        // retry_delay_ms field: not a literal in logic; referenced as self.config.retry_delay_ms.
        const DEFAULT_RETRY_DELAY_MS: u64 = 100;
        // capture_complete_timeout_ms: 30 s default.
        const DEFAULT_CAPTURE_TIMEOUT_MS: u64 = 30_000;

        Self {
            max_attempts: DEFAULT_MAX_ATTEMPTS,
            retry_delay_ms: DEFAULT_RETRY_DELAY_MS,
            capture_complete_timeout_ms: DEFAULT_CAPTURE_TIMEOUT_MS,
        }
    }
}

// ── RemoteSession ─────────────────────────────────────────────────────────────

/// Drives the PTP-IP remote-capture protocol over two separate channels:
///
/// * **Command channel**: provided as a raw TCP read/write pair (byte-level
///   because `PtpIpTcpClient` is sync; `RemoteSession` drives it async using
///   `tokio::net::TcpStream`).
/// * **Event channel**: owned `EventChannelReader` already handed off from
///   Android over an fd or a test loopback stream.
///
/// # JNIEnv isolation
///
/// `RemoteSession` holds NO `JNIEnv` reference. It is pure Rust and may be
/// spawned into any tokio task without lifetime concerns.
///
/// # Ownership
///
/// `RemoteSession` takes ownership of the event reader. The command stream is
/// passed by mutable reference to each operation so the existing sync
/// `PtpIpTcpClient` in the same session can share the connection (bridging
/// async operations on the same fd is deferred to M16 JNI wiring).
pub struct RemoteSession {
    config: RemoteSessionConfig,
    event_reader: EventChannelReader,
}

impl RemoteSession {
    /// Creates a new `RemoteSession` with the given config and event reader.
    ///
    // cancel-safe: synchronous constructor, no .await points.
    pub fn new(config: RemoteSessionConfig, event_reader: EventChannelReader) -> Self {
        Self {
            config,
            event_reader,
        }
    }

    /// Creates a `RemoteSession` with default config.
    ///
    // cancel-safe: synchronous constructor, no .await points.
    pub fn with_defaults(event_reader: EventChannelReader) -> Self {
        Self::new(RemoteSessionConfig::default(), event_reader)
    }

    // ── SetFunctionMode(REMOTE) ───────────────────────────────────────────────

    /// Sends `SetDevicePropValue(ClientState, REMOTE_MODE)` on the provided
    /// async command stream, retrying on BUSY up to `config.max_attempts` times
    /// with `config.retry_delay_ms` between retries.
    ///
    /// # Protocol
    ///
    /// `SetDevicePropValue` (opcode `0x1016`) is a write operation:
    /// `OperationRequest(data_phase=2)` → `StartData` → `EndData(value_bytes)`
    /// → `OperationResponse`.
    ///
    /// `ClientState` (0xDF01) is UINT16 — 2 bytes LE.
    /// `REMOTE_MODE` = 5 (`fuji_ptp::constants::function_mode::REMOTE`).
    ///
    /// # Errors
    ///
    /// Returns `CameraError::Busy { attempts }` if all retries are exhausted.
    /// Returns `ProtocolError` variants for malformed responses.
    ///
    /// # Cancel-safety
    ///
    /// NOT cancel-safe: holds partial TCP write/read state across `.await` points.
    /// Drive to completion.
    pub async fn set_function_mode_remote<S>(&self, stream: &mut S, txn_id: u32) -> FujiResult<()>
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        // ClientState (0xDF01) value for REMOTE mode = 5 (function_mode::REMOTE).
        // UINT16 LE — 2 bytes. master-constants.md §3b / §4b. [H]
        let value_bytes: [u8; 2] = function_mode::REMOTE.to_le_bytes();

        let mut attempts: u32 = 0;

        loop {
            attempts += 1;

            // Build and send the data-phase write sequence.
            //
            // SetDevicePropValue write sequence (ptp-ptpip.md section 4.9):
            //   1. OperationRequest(data_phase=2, opcode=SET_DEVICE_PROP_VALUE, params=[ClientState])
            //   2. StartData(total_data_length = value_bytes.len())
            //   3. EndData(value_bytes)
            // Response is OperationResponse with response_code.
            write_packet_async(
                stream,
                &PtpIpPacket::OperationRequest {
                    data_phase: 2, // write — carries data phase
                    opcode: opcode::SET_DEVICE_PROP_VALUE,
                    transaction_id: txn_id,
                    params: vec![prop_code::CLIENT_STATE as u32],
                },
            )
            .await?;

            write_packet_async(
                stream,
                &PtpIpPacket::StartData {
                    transaction_id: txn_id,
                    total_data_length: value_bytes.len() as u64,
                },
            )
            .await?;

            write_packet_async(
                stream,
                &PtpIpPacket::EndData {
                    transaction_id: txn_id,
                    payload: value_bytes.to_vec(),
                },
            )
            .await?;

            let resp = read_packet_async(stream).await?;

            match resp {
                PtpIpPacket::OperationResponse {
                    response_code: rc, ..
                } if rc == response_code::OK => {
                    return Ok(());
                }
                PtpIpPacket::OperationResponse {
                    response_code: rc, ..
                } if rc == response_code::BUSY => {
                    // Check BEFORE sleeping so that if max_attempts == 1 we
                    // return immediately without an extra delay.
                    if attempts >= self.config.max_attempts {
                        return Err(FujiError::Camera(CameraError::Busy { attempts }));
                    }
                    sleep(Duration::from_millis(self.config.retry_delay_ms)).await;
                    // M-24: transaction_id is intentionally NOT incremented across
                    // BUSY retries for SetDevicePropValue(ClientState).
                    //
                    // Standard PTP-IP requires a monotonically increasing transaction_id
                    // per operation (ptp-ptpip.md §5.1). However, Fujifilm cameras that
                    // respond BUSY to this specific operation expect the retry to re-send
                    // the same transaction_id as the original attempt. This is confirmed
                    // by the M03 session-init precedent: the retry loop re-uses the same
                    // txn_id and the camera responds OK on a subsequent attempt.
                    //
                    // Reusing txn_id here is a Fujifilm-specific deviation from the
                    // PTP-IP standard. If hardware testing shows the camera correctly
                    // accepts an incremented txn_id on retry, this can be changed by
                    // passing a `&mut TxnCounter` reference to this method and calling
                    // `txn.next()` here.
                    //
                    // TODO(audit): M-24 — confirm or refute txn_id-reuse on BUSY via
                    // hardware capture against a real Fujifilm X-T5 before the M16
                    // JNI wiring milestone. If confirmed wrong, plumb TxnCounter here.
                }
                PtpIpPacket::OperationResponse { .. } => {
                    return Err(FujiError::Protocol(ProtocolError::OperationRejected));
                }
                _ => {
                    return Err(FujiError::Protocol(ProtocolError::UnexpectedPacket));
                }
            }
        }
    }

    // ── InitiateOpenCapture ───────────────────────────────────────────────────

    /// Sends `InitiateOpenCapture` (0x101C) on the command channel.
    ///
    /// This triggers the camera to open the event (55741) and liveview (55742)
    /// channels. Call AFTER `set_function_mode_remote` completes successfully.
    ///
    /// # Cancel-safety
    ///
    /// NOT cancel-safe: holds partial TCP write/read state across `.await` points.
    pub async fn initiate_open_capture<S>(&self, stream: &mut S, txn_id: u32) -> FujiResult<()>
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        // InitiateOpenCapture (0x101C) — no data phase (data_phase=1), no params.
        // ptp-ptpip.md opcode table; master-constants.md §2a. [H]
        write_packet_async(
            stream,
            &PtpIpPacket::OperationRequest {
                data_phase: 1,
                opcode: opcode::INITIATE_OPEN_CAPTURE,
                transaction_id: txn_id,
                params: vec![],
            },
        )
        .await?;

        let resp = read_packet_async(stream).await?;

        match resp {
            PtpIpPacket::OperationResponse {
                response_code: rc, ..
            } if rc == response_code::OK => Ok(()),
            PtpIpPacket::OperationResponse { .. } => {
                Err(FujiError::Protocol(ProtocolError::OperationRejected))
            }
            _ => Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        }
    }

    // ── Wait for CaptureComplete ──────────────────────────────────────────────

    /// Polls the event channel until `CaptureComplete` (0x400D) is received or
    /// the configured `capture_complete_timeout_ms` expires.
    ///
    /// Other events (ObjectAdded, CameraStatusChanged, Unknown) are silently
    /// consumed — the caller does not need to handle them.
    ///
    /// # Errors
    ///
    /// Returns `ProtocolError::OperationTimeout` if no `CaptureComplete` arrives
    /// before `capture_complete_timeout_ms`.
    /// Returns `TransportError::EventChannelClosed` if the event channel closes.
    ///
    /// # Cancel-safety
    ///
    /// NOT cancel-safe: `EventChannelReader::poll_next` is not cancel-safe; this
    /// method drives it inside a `tokio::time::timeout` that ensures the future
    /// is driven to completion or the timeout fires. Do NOT cancel the returned
    /// future mid-await.
    pub async fn wait_for_capture_complete(&mut self) -> FujiResult<()> {
        let timeout_duration = Duration::from_millis(self.config.capture_complete_timeout_ms);

        // Use tokio::time::timeout to bound the wait. If it expires, the inner
        // poll_next future is dropped — this leaves the event stream misaligned
        // (NOT cancel-safe), but the session would be torn down at that point anyway.
        let result = tokio::time::timeout(timeout_duration, async {
            loop {
                let ev = self.event_reader.poll_next().await?;
                match ev {
                    FujiEvent::CaptureComplete { .. } => return Ok(()),
                    // Consume non-CaptureComplete events and keep waiting.
                    FujiEvent::ObjectAdded { .. }
                    | FujiEvent::CameraStatusChanged { .. }
                    | FujiEvent::Unknown { .. } => {
                        // Continue polling.
                    }
                }
            }
        })
        .await;

        match result {
            Ok(inner) => inner,
            Err(_elapsed) => Err(FujiError::Protocol(ProtocolError::OperationTimeout)),
        }
    }

    // ── TerminateOpenCapture ──────────────────────────────────────────────────

    /// Sends `TerminateOpenCapture` (0x1018) to end the open-capture session.
    ///
    /// `open_capture_txn_id` is the transaction ID used in `initiate_open_capture`.
    /// Per ptp-ptpip.md opcode table, `params[0]` = that transaction ID. [H]
    ///
    /// # Cancel-safety
    ///
    /// NOT cancel-safe: holds partial TCP write/read state across `.await` points.
    pub async fn terminate_open_capture<S>(
        &self,
        stream: &mut S,
        txn_id: u32,
        open_capture_txn_id: u32,
    ) -> FujiResult<()>
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        // TerminateOpenCapture (0x1018): param[0] = txn_id of InitiateOpenCapture. [H]
        write_packet_async(
            stream,
            &PtpIpPacket::OperationRequest {
                data_phase: 1,
                opcode: opcode::TERMINATE_OPEN_CAPTURE,
                transaction_id: txn_id,
                params: vec![open_capture_txn_id],
            },
        )
        .await?;

        let resp = read_packet_async(stream).await?;

        match resp {
            PtpIpPacket::OperationResponse {
                response_code: rc, ..
            } if rc == response_code::OK => Ok(()),
            // Best-effort: if the camera rejects, log and continue cleanup.
            PtpIpPacket::OperationResponse { .. } => {
                Err(FujiError::Protocol(ProtocolError::OperationRejected))
            }
            _ => Err(FujiError::Protocol(ProtocolError::UnexpectedPacket)),
        }
    }

    // ── Full remote-shutter sequence ──────────────────────────────────────────

    /// Runs the full remote-shutter sequence over the provided async command stream:
    ///
    /// 1. `SetDevicePropValue(ClientState, REMOTE_MODE)` — with BUSY retry.
    /// 2. `InitiateOpenCapture`.
    /// 3. Wait for `CaptureComplete` on the event channel.
    /// 4. `TerminateOpenCapture`.
    ///
    /// Transaction IDs are assigned sequentially starting from `first_txn_id`.
    ///
    /// # Errors
    ///
    /// Any step may fail; the error is returned immediately. No partial-cleanup
    /// is attempted — the caller should tear down the session on error.
    ///
    /// # Cancel-safety
    ///
    /// NOT cancel-safe: drives multiple sequential NOT-cancel-safe operations.
    pub async fn run_shutter_sequence<S>(
        &mut self,
        stream: &mut S,
        first_txn_id: u32,
    ) -> FujiResult<()>
    where
        S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin,
    {
        let set_mode_txn = first_txn_id;
        let open_capture_txn = first_txn_id.saturating_add(1);
        let terminate_txn = first_txn_id.saturating_add(2);

        // Step 1: SetFunctionMode(REMOTE).
        self.set_function_mode_remote(stream, set_mode_txn).await?;

        // Step 2: InitiateOpenCapture.
        self.initiate_open_capture(stream, open_capture_txn).await?;

        // Step 3: Wait for CaptureComplete on the event channel.
        self.wait_for_capture_complete().await?;

        // Step 4: TerminateOpenCapture.
        self.terminate_open_capture(stream, terminate_txn, open_capture_txn)
            .await?;

        Ok(())
    }
}

// ── Async packet I/O helpers ──────────────────────────────────────────────────

/// Encodes `pkt` and writes it to an async writer.
///
// NOT cancel-safe: partial writes leave the stream in an invalid state.
// Never used under tokio::select!; always driven to completion.
async fn write_packet_async<W>(writer: &mut W, pkt: &PtpIpPacket) -> FujiResult<()>
where
    W: tokio::io::AsyncWrite + Unpin,
{
    use tokio::io::AsyncWriteExt;
    let bytes = encode_packet(pkt);
    writer
        .write_all(&bytes)
        .await
        .map_err(|_| FujiError::Transport(fuji_core::TransportError::WriteFailed))
}

/// Reads one length-prefixed PTP-IP packet from an async reader.
///
// NOT cancel-safe: partial reads leave the stream framing misaligned.
// Never used under tokio::select!; always driven to completion.
async fn read_packet_async<R>(reader: &mut R) -> FujiResult<PtpIpPacket>
where
    R: tokio::io::AsyncRead + Unpin,
{
    use tokio::io::AsyncReadExt;

    let mut len_buf = [0u8; 4];
    reader
        .read_exact(&mut len_buf)
        .await
        .map_err(|_| FujiError::Transport(fuji_core::TransportError::ReadFailed))?;

    let declared = u32::from_le_bytes(len_buf) as usize;
    if declared < 8 {
        return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
            declared: declared as u32,
            minimum: 8,
        }));
    }
    // Cap at 4 KiB for command-channel responses (no data-phase reads here).
    const MAX_RESPONSE_BYTES: usize = 4096;
    if declared > MAX_RESPONSE_BYTES {
        return Err(FujiError::Protocol(ProtocolError::InvalidPacketLength {
            declared: declared as u32,
            minimum: 8,
        }));
    }

    let mut buf = vec![0u8; declared];
    buf[..4].copy_from_slice(&len_buf);
    reader
        .read_exact(&mut buf[4..])
        .await
        .map_err(|_| FujiError::Transport(fuji_core::TransportError::ReadFailed))?;

    decode_packet(&buf).map_err(|_| FujiError::Protocol(ProtocolError::UnexpectedPacket))
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use fuji_ptp::constants::{opcode, prop_code, response_code};
    use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    // Helper: read one packet from a TcpStream.
    // NOT cancel-safe: partial read leaves stream misaligned.
    async fn server_read(stream: &mut tokio::net::TcpStream) -> PtpIpPacket {
        let mut len_buf = [0u8; 4];
        stream.read_exact(&mut len_buf).await.unwrap();
        let len = u32::from_le_bytes(len_buf) as usize;
        let mut buf = vec![0u8; len];
        buf[..4].copy_from_slice(&len_buf);
        stream.read_exact(&mut buf[4..]).await.unwrap();
        decode_packet(&buf).unwrap()
    }

    // Helper: write one packet to a TcpStream.
    // NOT cancel-safe: partial write leaves stream in invalid state.
    async fn server_write(stream: &mut tokio::net::TcpStream, pkt: &PtpIpPacket) {
        let bytes = encode_packet(pkt);
        stream.write_all(&bytes).await.unwrap();
    }

    // ── set_function_mode_remote tests ────────────────────────────────────────

    #[tokio::test]
    async fn set_function_mode_remote_ok_on_first_attempt() {
        // Server responds OK immediately.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut srv, _) = listener.accept().await.unwrap();
            // Consume OperationRequest + StartData + EndData (3 packets).
            server_read(&mut srv).await;
            server_read(&mut srv).await;
            server_read(&mut srv).await;
            // Reply OK.
            server_write(
                &mut srv,
                &PtpIpPacket::OperationResponse {
                    response_code: response_code::OK,
                    transaction_id: 1,
                    result_params: vec![],
                },
            )
            .await;
        });

        // Build a fake event reader (a disconnected loopback — not used in this test).
        let event_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let event_addr = event_listener.local_addr().unwrap();
        tokio::spawn(async move {
            let _ = event_listener.accept().await;
        });
        let event_stream = tokio::net::TcpStream::connect(event_addr).await.unwrap();
        let event_reader = EventChannelReader::from_stream(event_stream);

        let session = RemoteSession::new(RemoteSessionConfig::default(), event_reader);
        let mut cmd_stream = tokio::net::TcpStream::connect(addr).await.unwrap();
        session
            .set_function_mode_remote(&mut cmd_stream, 1)
            .await
            .expect("expected Ok on first attempt");
    }

    #[tokio::test]
    async fn set_function_mode_remote_busy_then_ok() {
        // Server responds BUSY once, then OK.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut srv, _) = listener.accept().await.unwrap();

            // First attempt: consume 3 packets, reply BUSY.
            server_read(&mut srv).await;
            server_read(&mut srv).await;
            server_read(&mut srv).await;
            server_write(
                &mut srv,
                &PtpIpPacket::OperationResponse {
                    response_code: response_code::BUSY,
                    transaction_id: 1,
                    result_params: vec![],
                },
            )
            .await;

            // Second attempt: consume 3 packets, reply OK.
            server_read(&mut srv).await;
            server_read(&mut srv).await;
            server_read(&mut srv).await;
            server_write(
                &mut srv,
                &PtpIpPacket::OperationResponse {
                    response_code: response_code::OK,
                    transaction_id: 1,
                    result_params: vec![],
                },
            )
            .await;
        });

        let event_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let event_addr = event_listener.local_addr().unwrap();
        tokio::spawn(async move {
            let _ = event_listener.accept().await;
        });
        let event_stream = tokio::net::TcpStream::connect(event_addr).await.unwrap();
        let event_reader = EventChannelReader::from_stream(event_stream);

        // Use a very short retry delay to keep the test fast.
        let config = RemoteSessionConfig {
            max_attempts: 5,
            retry_delay_ms: 0, // no delay in tests — deterministic
            capture_complete_timeout_ms: 5_000,
        };
        let session = RemoteSession::new(config, event_reader);
        let mut cmd_stream = tokio::net::TcpStream::connect(addr).await.unwrap();
        session
            .set_function_mode_remote(&mut cmd_stream, 1)
            .await
            .expect("expected Ok after BUSY→OK");
    }

    #[tokio::test]
    async fn set_function_mode_remote_busy_exhausted_returns_camera_busy() {
        // Server always replies BUSY — session should exhaust max_attempts.
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut srv, _) = listener.accept().await.unwrap();
            // max_attempts=2 → 2 attempts × 3 packets each = 6 packets total.
            for _ in 0..2 {
                server_read(&mut srv).await;
                server_read(&mut srv).await;
                server_read(&mut srv).await;
                server_write(
                    &mut srv,
                    &PtpIpPacket::OperationResponse {
                        response_code: response_code::BUSY,
                        transaction_id: 1,
                        result_params: vec![],
                    },
                )
                .await;
            }
        });

        let event_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let event_addr = event_listener.local_addr().unwrap();
        tokio::spawn(async move {
            let _ = event_listener.accept().await;
        });
        let event_stream = tokio::net::TcpStream::connect(event_addr).await.unwrap();
        let event_reader = EventChannelReader::from_stream(event_stream);

        let config = RemoteSessionConfig {
            max_attempts: 2,
            retry_delay_ms: 0,
            capture_complete_timeout_ms: 5_000,
        };
        let session = RemoteSession::new(config, event_reader);
        let mut cmd_stream = tokio::net::TcpStream::connect(addr).await.unwrap();
        let result = session.set_function_mode_remote(&mut cmd_stream, 1).await;

        assert!(
            matches!(
                result,
                Err(FujiError::Camera(CameraError::Busy { attempts: 2 }))
            ),
            "expected Busy {{ attempts: 2 }}, got {result:?}"
        );
    }

    // ── Verify opcodes reference named constants, not literals ────────────────

    #[test]
    fn set_mode_uses_named_opcode_and_prop_constants() {
        // Verify that SET_DEVICE_PROP_VALUE opcode and CLIENT_STATE prop code
        // match the named values from fuji_ptp::constants.
        assert_eq!(opcode::SET_DEVICE_PROP_VALUE, 0x1016);
        assert_eq!(prop_code::CLIENT_STATE, 0xDF01);
        assert_eq!(function_mode::REMOTE, 5u16);
        assert_eq!(opcode::INITIATE_OPEN_CAPTURE, 0x101C);
        assert_eq!(opcode::TERMINATE_OPEN_CAPTURE, 0x1018);
    }

    // ── Config defaults ───────────────────────────────────────────────────────

    #[test]
    fn remote_session_config_default_values() {
        let cfg = RemoteSessionConfig::default();
        assert_eq!(
            cfg.max_attempts, 15,
            "default max_attempts = 15 (3×5 M03 precedent)"
        );
        assert_eq!(cfg.retry_delay_ms, 100, "default retry_delay_ms = 100");
        assert_eq!(
            cfg.capture_complete_timeout_ms, 30_000,
            "default capture_complete_timeout_ms = 30 000"
        );
    }
}
