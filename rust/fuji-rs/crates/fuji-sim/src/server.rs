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
//! 3. Command channel: PTP operations dispatched by [`crate::dispatch`]
//! 4. Graceful shutdown on CloseSession or goodbye packet
//!
//! # Safety
//!
//! This module is `#![forbid(unsafe_code)]` (enforced at the crate root).

use std::net::SocketAddr;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::oneshot;

use fuji_ptp::constants::{FUJI_PTPIP_VERSION, opcode};
use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};

use crate::builder::ServerConfig;
use crate::dispatch::{DispatchState, dispatch_operation};
use crate::error::SimError;

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
    /// The server accepts one command connection and one event connection per session,
    /// services the command connection fully, then waits for the next pair or a
    /// shutdown signal.
    ///
    // cancel-safe: spawns a task; the task itself selects between accept and
    // the shutdown signal using tokio::select!. Each branch is cancel-safe
    // (accept is cancel-safe per tokio docs; oneshot recv is cancel-safe).
    pub fn run(self) -> ShutdownHandle {
        let (tx, rx) = oneshot::channel::<()>();
        let config = self.config;
        let cmd_listener = self.cmd_listener;
        let event_listener = self.event_listener;

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
    event_listener: TcpListener,
    config: ServerConfig,
    mut shutdown_rx: oneshot::Receiver<()>,
) {
    loop {
        // Accept the command connection. The event connection is accepted
        // INSIDE handle_session after InitCommandAck is sent — this matches
        // the production PTP-IP flow (ptp-ptpip.md section 5.1): the client
        // connects the event socket only after receiving InitCommandAck.
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

        // Drive the full session. handle_session accepts the event connection
        // from event_listener after sending InitCommandAck.
        // The shutdown signal is not checked between command accept and session
        // start — the session drives to completion (or protocol error) before
        // the next loop iteration re-checks shutdown.
        let _ = handle_session(cmd_stream, &event_listener, &config).await;
    }
}

// ── Per-session handler ───────────────────────────────────────────────────────

// Accepts the event-channel connection from `event_listener` AFTER sending
// InitCommandAck on the command channel. This matches the production PTP-IP
// flow (ptp-ptpip.md section 5.1): the client only opens the event socket after
// it has received InitCommandAck. Never driven via tokio::select! — always run
// to completion.
// NOT cancel-safe: owns TcpStreams and holds accumulated read state.
async fn handle_session(
    mut cmd_stream: TcpStream,
    event_listener: &TcpListener,
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

    // ── Event channel handshake (section 5.1 steps 5-7) ─────────────────────
    // After sending InitCommandAck, accept the client's event-channel connection.
    // The client connects to event_addr() and sends InitEventRequest there;
    // we reply with InitEventAck on that same connection.
    let (mut event_stream, _) = event_listener
        .accept()
        .await
        .map_err(SimError::AcceptFailed)?;

    let pkt = read_packet(&mut event_stream).await?;
    match pkt {
        PtpIpPacket::InitEventRequest { .. } => {
            write_packet(&mut event_stream, &PtpIpPacket::InitEventAck).await?;
        }
        other => {
            return Err(SimError::ProtocolViolation(format!(
                "expected InitEventRequest on event channel, got {other:?}"
            )));
        }
    }

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

                let response_pkts = dispatch_operation(
                    op,
                    transaction_id,
                    &params,
                    data_payload.as_deref(),
                    &mut dispatch_state,
                    config,
                )?;

                let is_close = op == opcode::CLOSE_SESSION;

                for pkt in &response_pkts {
                    write_packet(&mut cmd_stream, pkt).await?;
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
