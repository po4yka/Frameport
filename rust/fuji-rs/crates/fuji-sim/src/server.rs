//! [`FakeCameraServer`] — in-process PTP-IP fake camera server for tests.
//!
//! The server binds TWO `TcpListener`s on `127.0.0.1:0` (OS-assigned ports):
//!
//! * **Command channel** — accepts `InitCommandRequest` → replies `InitCommandAck`,
//!   then drives the PTP operation dispatch loop.
//! * **Event channel** — accepts `InitEventRequest` → replies `InitEventAck`.
//!   This is a separate TCP connection, matching the production PTP-IP two-socket
//!   design described in ptp-ptpip.md section 5.1 steps 5-7.
//!
//! # Lifecycle
//!
//! ```text
//! let server = FakeCameraServer::bind(config).await?;
//! let cmd_addr   = server.bound_addr();   // pass to the test command client
//! let event_addr = server.event_addr();   // pass to the test event client
//! let handle = server.run();
//! // ... test client opens cmd_addr, does handshake, opens event_addr, etc. ...
//! handle.shutdown();                      // signal the accept loop to stop
//! ```
//!
//! # Protocol coverage
//!
//! 1. Command channel: InitCommandRequest → InitCommandAck
//! 2. Event channel (separate TCP connection): InitEventRequest → InitEventAck
//!    The event channel is handled **concurrently** with the dispatch loop — a
//!    background task accepts and services it opportunistically.  This matches the
//!    production protocol where the event channel is lazy (opened only after
//!    InitiateOpenCapture, never required before the transfer handshake).
//! 3. Command channel: PTP operations dispatched by [`crate::dispatch`]
//! 4. Graceful shutdown on CloseSession or goodbye packet
//!
//! # Safety
//!
//! This module is `#![forbid(unsafe_code)]` (enforced at the crate root).

use std::net::SocketAddr;
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;

use fuji_ptp::constants::{FUJI_PTPIP_VERSION, opcode};
use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};

use crate::builder::ServerConfig;
use crate::dispatch::{DispatchResult, DispatchState, dispatch_operation};
use crate::error::SimError;

// PTP-IP packet type codes for the raw streaming frame headers.
// type=0x09 → StartData, type=0x0C → EndData.
// Source: ptp-ptpip.md section 4.1 / packet type code table. [H]
const PTPIP_TYPE_START_DATA: u32 = 0x0000_0009;
const PTPIP_TYPE_END_DATA: u32 = 0x0000_000C;
const PTPIP_TYPE_OPERATION_RESPONSE: u32 = 0x0000_0007;

// response_code::OK = 0x2001 — used in the raw OperationResponse frame after streaming.
// fuji_ptp::constants::response_code::OK
const RESPONSE_CODE_OK: u16 = 0x2001;

// ── ShutdownHandle ────────────────────────────────────────────────────────────

/// A handle that lets a test stop the server's accept loop cleanly.
///
/// Call [`ShutdownHandle::shutdown`] once the test has finished.
/// The server task will exit after the current connection completes or the
/// next accept wakes up.
pub struct ShutdownHandle {
    tx: Option<oneshot::Sender<()>>,
}

impl ShutdownHandle {
    /// Sends the shutdown signal to the server's accept loop.
    ///
    /// Idempotent — subsequent calls are no-ops.
    pub fn shutdown(mut self) {
        if let Some(tx) = self.tx.take() {
            // Ignore the error: if the receiver is already gone the server has
            // already stopped, which is fine.
            let _ = tx.send(());
        }
    }
}

// ── FakeCameraServer ──────────────────────────────────────────────────────────

/// In-process PTP-IP fake camera server with two separate TCP listeners.
///
/// * `cmd_listener`   — command channel (use [`bound_addr`](Self::bound_addr))
/// * `event_listener` — event channel   (use [`event_addr`](Self::event_addr))
///
/// Create via [`FakeCameraBuilder`](crate::FakeCameraBuilder) and start with
/// [`FakeCameraServer::run`].
pub struct FakeCameraServer {
    cmd_listener: TcpListener,
    event_listener: TcpListener,
    config: ServerConfig,
}

impl FakeCameraServer {
    /// Binds two new `TcpListener`s on `127.0.0.1:0` (OS-assigned ephemeral ports):
    /// one for the command channel and one for the event channel.
    ///
    /// Never uses fixed ports — each test gets its own isolated server pair.
    ///
    // cancel-safe: async binds complete without holding resources across .await.
    pub async fn bind(config: ServerConfig) -> Result<Self, SimError> {
        let cmd_listener = TcpListener::bind("127.0.0.1:0")
            .await
            .map_err(SimError::BindFailed)?;
        let event_listener = TcpListener::bind("127.0.0.1:0")
            .await
            .map_err(SimError::BindFailed)?;
        Ok(Self {
            cmd_listener,
            event_listener,
            config,
        })
    }

    /// Returns the local address of the **command channel** listener.
    ///
    /// Panics if the listener was closed before this call (cannot happen in normal
    /// usage since `bind()` holds the listener until `run()` moves it).
    pub fn bound_addr(&self) -> SocketAddr {
        self.cmd_listener
            .local_addr()
            .expect("cmd_listener has local_addr")
    }

    /// Returns the local address of the **event channel** listener.
    ///
    /// The client must connect a separate TCP socket to this address and perform
    /// the `InitEventRequest` → `InitEventAck` handshake (ptp-ptpip.md section 5.1
    /// steps 5-7).
    ///
    /// Panics if the listener was closed before this call (cannot happen in normal usage).
    pub fn event_addr(&self) -> SocketAddr {
        self.event_listener
            .local_addr()
            .expect("event_listener has local_addr")
    }

    /// Starts the accept-and-dispatch loop in a background tokio task.
    ///
    /// Returns a [`ShutdownHandle`] that the caller uses to stop both listeners.
    /// The server accepts one command connection per session and services it fully,
    /// then waits for the next connection or a shutdown signal.
    ///
    /// The event channel listener is wrapped in an `Arc<TcpListener>` so that per-session
    /// background tasks can accept event connections concurrently with the command dispatch
    /// loop, without blocking it.
    ///
    // cancel-safe: spawns a task; the task itself selects between accept and
    // the shutdown signal using tokio::select!. Each branch is cancel-safe
    // (accept is cancel-safe per tokio docs; oneshot recv is cancel-safe).
    pub fn run(self) -> ShutdownHandle {
        let (tx, rx) = oneshot::channel::<()>();
        let config = self.config;
        let cmd_listener = self.cmd_listener;
        // Wrap event_listener in Arc so background per-session tasks can share it.
        let event_listener = Arc::new(self.event_listener);

        tokio::spawn(async move {
            server_loop(cmd_listener, event_listener, config, rx).await;
        });

        ShutdownHandle { tx: Some(tx) }
    }
}

// ── Server loop ───────────────────────────────────────────────────────────────

// The handle_session future is NOT cancel-safe (it owns TCP streams and holds
// partial read state), but it is never dropped mid-flight in this loop —
// once accepted we drive it to completion before looping.
// cancel-safe: the outer select! arms are both cancel-safe.
async fn server_loop(
    cmd_listener: TcpListener,
    event_listener: Arc<TcpListener>,
    config: ServerConfig,
    mut shutdown_rx: oneshot::Receiver<()>,
) {
    loop {
        // Accept the command connection only. The event connection is accepted
        // concurrently by a background task spawned inside handle_session, so
        // the dispatch loop never blocks waiting for the event channel.
        let cmd_stream = tokio::select! {
            biased;

            // Shutdown signal wins if both are ready simultaneously.
            _ = &mut shutdown_rx => {
                break;
            }

            accept_result = cmd_listener.accept() => {
                match accept_result {
                    Ok((stream, _)) => stream,
                    Err(_) => break,
                }
            }
        };

        // Drive the full session. handle_session spawns a background task for
        // the event channel and proceeds directly to the dispatch loop.
        // The shutdown signal is not checked between command accept and session
        // start — the session drives to completion (or protocol error) before
        // the next loop iteration re-checks shutdown.
        let _ = handle_session(cmd_stream, Arc::clone(&event_listener), &config).await;
    }
}

// ── Per-session handler ───────────────────────────────────────────────────────

// Sends InitCommandAck on the command channel, then immediately spawns a
// background task to accept and service the event channel concurrently.
// The dispatch loop starts without waiting for the event channel — this is
// correct per docs/reference/transfer-liveview.md section 1: the event channel
// is lazy and is never required before the transfer handshake.
//
// The background event task accepts ONE event connection, performs
// InitEventRequest → InitEventAck, and then exits.  It simply pends forever if
// no event client connects; it is dropped when the session ends.
//
// Never driven via tokio::select! — always run to completion.
// NOT cancel-safe: owns TcpStreams and holds accumulated read state.
async fn handle_session(
    mut cmd_stream: TcpStream,
    event_listener: Arc<TcpListener>,
    config: &ServerConfig,
) -> Result<(), SimError> {
    let mut dispatch_state = DispatchState::new(config);

    // ── Command channel handshake (section 5.1 steps 2-4) ────────────────────

    // Step 1: Init Command Request.
    let pkt = read_packet(&mut cmd_stream).await?;
    match pkt {
        PtpIpPacket::InitCommandRequest { version, .. } => {
            if version != FUJI_PTPIP_VERSION {
                return Err(SimError::ProtocolViolation(format!(
                    "InitCommandRequest: expected version=0x{FUJI_PTPIP_VERSION:08X}, got 0x{version:08X}"
                )));
            }
            // Reply: InitCommandAck (GUID + camera_name only).
            // ptp-ptpip.md section 4.3. [H]
            let ack = PtpIpPacket::InitCommandAck {
                guid: config.camera_guid,
                camera_name: config.camera_name.clone(),
            };
            write_packet(&mut cmd_stream, &ack).await?;
        }
        other => {
            return Err(SimError::ProtocolViolation(format!(
                "expected InitCommandRequest, got {other:?}"
            )));
        }
    }

    // ── Event channel: spawn concurrent background task (section 5.1 steps 5-7) ──
    // After sending InitCommandAck the client MAY connect the event channel.
    // We accept it opportunistically in a background task so the dispatch loop
    // below is NEVER blocked waiting for it.  If no client connects the task
    // simply pends until it is dropped at the end of this session.
    //
    // cancel-safe annotation for the spawned task: the task owns its own
    // TcpStream and drives it to completion; it is never cancelled mid-frame.
    tokio::spawn(async move {
        if let Ok((mut event_stream, _)) = event_listener.accept().await {
            // Read InitEventRequest and reply InitEventAck.
            // Ignore errors — the background task is best-effort.
            if let Ok(pkt) = read_packet(&mut event_stream).await
                && matches!(pkt, PtpIpPacket::InitEventRequest { .. })
            {
                let _ = write_packet(&mut event_stream, &PtpIpPacket::InitEventAck).await;
            }
        }
        // event_stream is dropped here, closing the event TCP connection.
    });

    // ── Operation dispatch loop (section 5.1 steps 8-13) ─────────────────────
    loop {
        let pkt = read_packet(&mut cmd_stream).await?;

        match pkt {
            PtpIpPacket::OperationRequest {
                opcode: op,
                transaction_id,
                params,
                data_phase,
                ..
            } => {
                // For write operations (data_phase == 2), consume the data packets
                // before dispatching — the server does not use the data for any
                // operation currently, but must drain them to keep the framing aligned.
                let data_payload: Option<Vec<u8>> = if data_phase == 2 {
                    let payload = read_data_phase(&mut cmd_stream).await?;
                    Some(payload)
                } else {
                    None
                };

                let dispatch_result = dispatch_operation(
                    op,
                    transaction_id,
                    &params,
                    data_payload.as_deref(),
                    &mut dispatch_state,
                    config,
                )?;

                let is_close = op == opcode::CLOSE_SESSION;

                match dispatch_result {
                    DispatchResult::Packets(pkts) => {
                        for pkt in &pkts {
                            write_packet(&mut cmd_stream, pkt).await?;
                        }
                    }
                    DispatchResult::StreamObject {
                        transaction_id: txn_id,
                        total_data_length,
                        object_bytes,
                        reset_after_bytes,
                    } => {
                        // Write StartData frame (20 bytes):
                        //   length  u32 LE = 20
                        //   type    u32 LE = 0x09
                        //   txn_id  u32 LE
                        //   total_data_length u64 LE
                        // ptp-ptpip.md section 4.9 / wire-format ground truth. [H]
                        let mut start_data = [0u8; 20];
                        start_data[0..4].copy_from_slice(&20u32.to_le_bytes());
                        start_data[4..8].copy_from_slice(&PTPIP_TYPE_START_DATA.to_le_bytes());
                        start_data[8..12].copy_from_slice(&txn_id.to_le_bytes());
                        start_data[12..20].copy_from_slice(&total_data_length.to_le_bytes());
                        cmd_stream
                            .write_all(&start_data)
                            .await
                            .map_err(SimError::Io)?;

                        // Write EndData frame header:
                        //   length  u32 LE = 12 + object_bytes.len()
                        //   type    u32 LE = 0x0C
                        //   txn_id  u32 LE
                        // Then stream the body in 64 KiB chunks (no per-iteration heap alloc —
                        // one [u8; 65536] stack buffer is reused per the streaming requirement).
                        let body_len = object_bytes.len();
                        let end_data_header_len: u32 = 12 + body_len as u32;
                        let mut end_data_header = [0u8; 12];
                        end_data_header[0..4].copy_from_slice(&end_data_header_len.to_le_bytes());
                        end_data_header[4..8].copy_from_slice(&PTPIP_TYPE_END_DATA.to_le_bytes());
                        end_data_header[8..12].copy_from_slice(&txn_id.to_le_bytes());
                        cmd_stream
                            .write_all(&end_data_header)
                            .await
                            .map_err(SimError::Io)?;

                        // Stream body bytes, honouring the connection-reset fault.
                        // One stack buffer reused across all iterations (no per-chunk alloc).
                        const CHUNK: usize = 65536;
                        let mut buf = [0u8; CHUNK];
                        let mut written: usize = 0;
                        loop {
                            if written >= body_len {
                                break;
                            }
                            let remaining = body_len - written;
                            let to_write = remaining.min(CHUNK);

                            // Connection-reset fault: drop after `k` bytes of body.
                            if let Some(k) = reset_after_bytes {
                                if written >= k {
                                    // Drop the connection mid-stream.
                                    return Ok(());
                                }
                                // Clamp the chunk so we stop exactly at k.
                                let clamped = to_write.min(k.saturating_sub(written));
                                buf[..clamped]
                                    .copy_from_slice(&object_bytes[written..written + clamped]);
                                cmd_stream
                                    .write_all(&buf[..clamped])
                                    .await
                                    .map_err(SimError::Io)?;
                                written += clamped;
                                continue;
                            }

                            buf[..to_write]
                                .copy_from_slice(&object_bytes[written..written + to_write]);
                            cmd_stream
                                .write_all(&buf[..to_write])
                                .await
                                .map_err(SimError::Io)?;
                            written += to_write;
                        }

                        // Write OperationResponse(OK) after the stream.
                        // Wire format (ptp-ptpip.md section 4.7):
                        //   length        u32 LE = 18 (no result_params)
                        //   type          u32 LE = 0x07
                        //   response_code u16 LE = 0x2001 (OK)
                        //   transaction_id u32 LE
                        // (result_params are absent when empty — length stays 18)
                        let mut op_resp = [0u8; 18];
                        op_resp[0..4].copy_from_slice(&18u32.to_le_bytes());
                        op_resp[4..8].copy_from_slice(&PTPIP_TYPE_OPERATION_RESPONSE.to_le_bytes());
                        op_resp[8..10].copy_from_slice(&RESPONSE_CODE_OK.to_le_bytes());
                        op_resp[10..14].copy_from_slice(&txn_id.to_le_bytes());
                        // bytes 14..18 = 4 padding bytes (zero) — standard empty result_params
                        cmd_stream.write_all(&op_resp).await.map_err(SimError::Io)?;
                    }
                }

                if is_close {
                    // Session ended cleanly.
                    return Ok(());
                }
            }

            // Goodbye packet (section 4.10): length=8, type=0xFFFFFFFF.
            // fuji_ptp decodes it as an unknown packet type; the raw bytes match
            // GOODBYE_PACKET. We handle the framing-level goodbye here:
            // a 4-byte or 8-byte marker with 0xFF bytes signals graceful disconnect.
            PtpIpPacket::Ping { raw_payload } if raw_payload.iter().all(|b| *b == 0xFF) => {
                // Treat as goodbye — close cleanly.
                return Ok(());
            }

            other => {
                return Err(SimError::ProtocolViolation(format!(
                    "unexpected packet in operation loop: {other:?}"
                )));
            }
        }
    }
}

// ── Data-phase drain (section 4.9 write sequence) ────────────────────────────

// NOT cancel-safe: owns partial read state across .await points.
async fn read_data_phase(stream: &mut TcpStream) -> Result<Vec<u8>, SimError> {
    // Expect: StartData → (optional DataPacket …) → EndData
    // For the fake server we only care about EndData payload.
    let mut accumulated: Vec<u8> = Vec::new();

    loop {
        let pkt = read_packet(stream).await?;
        match pkt {
            PtpIpPacket::StartData { .. } => {
                // Noted; nothing to extract.
            }
            PtpIpPacket::DataPacket { payload, .. } => {
                accumulated.extend_from_slice(&payload);
            }
            PtpIpPacket::EndData { payload, .. } => {
                accumulated.extend_from_slice(&payload);
                return Ok(accumulated);
            }
            other => {
                return Err(SimError::ProtocolViolation(format!(
                    "expected data-phase packet, got {other:?}"
                )));
            }
        }
    }
}

// ── Framing I/O ───────────────────────────────────────────────────────────────

/// Reads one length-prefixed PTP-IP packet from `stream`.
///
/// Wire framing (section 2 / section 4.1):
///   bytes 0..4  — u32 LE total packet length (including these 4 bytes)
///   bytes 4..N  — remainder of the packet
///
/// Validates the declared length before reading to prevent allocation exhaustion
/// on malformed or adversarial inputs.
///
// NOT cancel-safe: partial reads leave the stream in an unrecoverable state.
// Never used under tokio::select! — always driven to completion.
async fn read_packet(stream: &mut TcpStream) -> Result<PtpIpPacket, SimError> {
    // Read the 4-byte length prefix.
    let mut len_buf = [0u8; 4];
    stream
        .read_exact(&mut len_buf)
        .await
        .map_err(|_| SimError::UnexpectedDisconnect)?;

    let declared_len = u32::from_le_bytes(len_buf) as usize;

    // Sanity-check: must be at least 8 bytes (common PTP-IP header).
    if declared_len < 8 {
        return Err(SimError::ProtocolViolation(format!(
            "declared packet length {declared_len} is less than minimum 8 bytes"
        )));
    }

    // Sanity-check: cap at 64 MiB to prevent allocation exhaustion on bad input.
    const MAX_PACKET_BYTES: usize = 64 * 1024 * 1024;
    if declared_len > MAX_PACKET_BYTES {
        return Err(SimError::ProtocolViolation(format!(
            "declared packet length {declared_len} exceeds cap of {MAX_PACKET_BYTES}"
        )));
    }

    // Allocate and read the rest of the packet.
    let mut buf = vec![0u8; declared_len];
    buf[0] = len_buf[0];
    buf[1] = len_buf[1];
    buf[2] = len_buf[2];
    buf[3] = len_buf[3];

    stream
        .read_exact(&mut buf[4..])
        .await
        .map_err(|_| SimError::UnexpectedDisconnect)?;

    decode_packet(&buf)
        .map_err(|e| SimError::ProtocolViolation(format!("PTP-IP decode error: {e}")))
}

/// Encodes `packet` and writes it to `stream` atomically.
///
/// Never used under `tokio::select!`.
// NOT cancel-safe: a partial write leaves the stream in an invalid state.
async fn write_packet(stream: &mut TcpStream, packet: &PtpIpPacket) -> Result<(), SimError> {
    let bytes = encode_packet(packet);
    stream.write_all(&bytes).await.map_err(SimError::Io)
}
