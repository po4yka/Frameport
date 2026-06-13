//! `fuji-sim` — deterministic, hermetic in-process PTP-IP fake camera server.
//!
//! This crate provides a [`FakeCameraServer`] that binds on `127.0.0.1:0`
//! (OS-assigned ephemeral port) and speaks the PTP-IP command-channel protocol
//! over a real TCP loopback socket. It is the primary test harness used by all
//! later Rust crates in the workspace.
//!
//! # Design principles
//!
//! - **No fixed ports**: every test gets its own ephemeral port — no interference
//!   between parallel test runners.
//! - **No thread::sleep**: timing is controlled by tokio's async executor.
//! - **No global/static mutable state**: all server state is per-instance.
//! - **Configurable behaviour**: BUSY count, fatal status codes, object count, and
//!   firmware version are all set via [`FakeCameraBuilder`].
//!
//! # Quick start
//!
//! ```rust,no_run
//! use fuji_sim::{FakeCameraBuilder, FakeCameraServer};
//!
//! #[tokio::test]
//! async fn example_session() {
//!     let config = FakeCameraBuilder::new().object_count(3).build();
//!     let server = FakeCameraServer::bind(config).await.unwrap();
//!     let addr = server.bound_addr();
//!     let handle = server.run();
//!     // ... connect a test PTP-IP client to `addr` and exercise the session ...
//!     handle.shutdown();
//! }
//! ```
//!
//! # Safety
//!
//! This crate contains no unsafe code.

#![forbid(unsafe_code)]

pub mod builder;
pub mod dispatch;
pub mod error;
pub mod server;

// ── Public re-exports ─────────────────────────────────────────────────────────

pub use builder::{FakeCameraBuilder, ServerConfig};
pub use dispatch::{MAX_BUSY_RETRIES, RETRY_DELAY_MS};
pub use error::SimError;
pub use server::{FakeCameraServer, ShutdownHandle};

// ── Legacy placeholder — kept for backwards compat with existing tests ─────────

use fuji_core::{CameraId, CameraModel, FirmwareVersion, FujiResult, TransportKind};

/// Placeholder camera struct, preserved from the original stub.
///
/// Use [`FakeCameraServer`] for real test sessions.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FakeCamera {
    pub id: CameraId,
    pub model: CameraModel,
    pub firmware_version: FirmwareVersion,
    pub transport: TransportKind,
}

/// Returns a no-op placeholder [`FakeCamera`] for backward compatibility.
///
/// Prefer [`FakeCameraBuilder`] + [`FakeCameraServer`] for new tests.
pub fn placeholder_camera() -> FujiResult<FakeCamera> {
    Ok(FakeCamera {
        id: CameraId::new("fake-camera-placeholder")?,
        model: CameraModel::placeholder(),
        firmware_version: FirmwareVersion::new("0.0-placeholder")?,
        transport: TransportKind::Noop,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Legacy placeholder test ───────────────────────────────────────────────

    #[test]
    fn fake_camera_is_noop_transport() {
        assert_eq!(placeholder_camera().unwrap().transport, TransportKind::Noop);
    }

    // ── Smoke test: server binds, client connects, full handshake succeeds ────

    /// Runs a minimal PTP-IP session:
    ///   InitCommandRequest → InitCommandAck
    ///   InitEventRequest → InitEventAck
    ///   OpenSession → OK
    ///   CloseSession → OK
    ///   (server shuts down)
    ///
    /// Uses no fixed ports, no thread::sleep, no global state.
    // cancel-safe: test driver — never used under tokio::select!; driven to completion by #[tokio::test] runtime.
    #[tokio::test]
    async fn smoke_session_open_close() {
        use std::time::Duration;
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        use tokio::net::TcpStream;
        use tokio::time::timeout;

        use fuji_ptp::constants::{FUJI_PTPIP_VERSION, SESSION_ID, opcode, response_code};
        use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};

        let config = FakeCameraBuilder::new().object_count(2).build();
        let srv = FakeCameraServer::bind(config).await.expect("bind");
        let addr = srv.bound_addr();
        let handle = srv.run();

        // Helper: write a packet, read the next packet.
        // NOT cancel-safe: holds partial stream read state across .await; never used under tokio::select!.
        async fn exchange(stream: &mut TcpStream, send: &PtpIpPacket) -> PtpIpPacket {
            let bytes = encode_packet(send);
            stream.write_all(&bytes).await.unwrap();
            // Read 4-byte length prefix.
            let mut len_buf = [0u8; 4];
            stream.read_exact(&mut len_buf).await.unwrap();
            let len = u32::from_le_bytes(len_buf) as usize;
            let mut buf = vec![0u8; len];
            buf[..4].copy_from_slice(&len_buf);
            stream.read_exact(&mut buf[4..]).await.unwrap();
            decode_packet(&buf).unwrap()
        }

        let session = timeout(Duration::from_secs(5), async {
            let mut stream = TcpStream::connect(addr).await.expect("connect");

            // Step 1: Init Command Request
            let ack = exchange(
                &mut stream,
                &PtpIpPacket::InitCommandRequest {
                    version: FUJI_PTPIP_VERSION,
                    guid: [0u8; 16],
                    device_name: "test-client".to_owned(),
                },
            )
            .await;
            assert!(
                matches!(ack, PtpIpPacket::InitCommandAck { .. }),
                "expected InitCommandAck, got {ack:?}"
            );

            // Step 2: Init Event Request
            let event_ack = exchange(
                &mut stream,
                &PtpIpPacket::InitEventRequest {
                    connection_number: 1,
                },
            )
            .await;
            assert_eq!(event_ack, PtpIpPacket::InitEventAck);

            // Step 3: OpenSession (txn_id=0, param[0]=SESSION_ID=1)
            let open_resp = exchange(
                &mut stream,
                &PtpIpPacket::OperationRequest {
                    data_phase: 1,
                    opcode: opcode::OPEN_SESSION,
                    transaction_id: 0,
                    params: vec![SESSION_ID],
                },
            )
            .await;
            assert!(
                matches!(
                    &open_resp,
                    PtpIpPacket::OperationResponse { response_code, .. }
                    if *response_code == response_code::OK
                ),
                "expected OK, got {open_resp:?}"
            );

            // Step 4: CloseSession
            let close_resp = exchange(
                &mut stream,
                &PtpIpPacket::OperationRequest {
                    data_phase: 1,
                    opcode: opcode::CLOSE_SESSION,
                    transaction_id: 1,
                    params: vec![],
                },
            )
            .await;
            assert!(
                matches!(
                    &close_resp,
                    PtpIpPacket::OperationResponse { response_code, .. }
                    if *response_code == response_code::OK
                ),
                "expected OK on close, got {close_resp:?}"
            );
        })
        .await;

        session.expect("session timed out");
        handle.shutdown();
    }

    // ── BUSY → OK on mode write ───────────────────────────────────────────────

    // cancel-safe: test driver — never used under tokio::select!; driven to completion by #[tokio::test] runtime.
    #[tokio::test]
    async fn set_mode_busy_then_ok() {
        use std::time::Duration;
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        use tokio::net::TcpStream;
        use tokio::time::timeout;

        use fuji_ptp::constants::{
            FUJI_PTPIP_VERSION, SESSION_ID, opcode, prop_code, response_code,
        };
        use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};

        let config = FakeCameraBuilder::new().busy_count(1).build();
        let srv = FakeCameraServer::bind(config).await.expect("bind");
        let addr = srv.bound_addr();
        let handle = srv.run();

        // NOT cancel-safe: holds partial stream read state across .await; never used under tokio::select!.
        async fn exchange(stream: &mut TcpStream, send: &PtpIpPacket) -> PtpIpPacket {
            let bytes = encode_packet(send);
            stream.write_all(&bytes).await.unwrap();
            let mut len_buf = [0u8; 4];
            stream.read_exact(&mut len_buf).await.unwrap();
            let len = u32::from_le_bytes(len_buf) as usize;
            let mut buf = vec![0u8; len];
            buf[..4].copy_from_slice(&len_buf);
            stream.read_exact(&mut buf[4..]).await.unwrap();
            decode_packet(&buf).unwrap()
        }

        let result = timeout(Duration::from_secs(5), async {
            let mut stream = TcpStream::connect(addr).await.unwrap();

            // Handshake
            let _ = exchange(
                &mut stream,
                &PtpIpPacket::InitCommandRequest {
                    version: FUJI_PTPIP_VERSION,
                    guid: [1u8; 16],
                    device_name: "t".to_owned(),
                },
            )
            .await;
            let _ = exchange(
                &mut stream,
                &PtpIpPacket::InitEventRequest {
                    connection_number: 1,
                },
            )
            .await;
            let _ = exchange(
                &mut stream,
                &PtpIpPacket::OperationRequest {
                    data_phase: 1,
                    opcode: opcode::OPEN_SESSION,
                    transaction_id: 0,
                    params: vec![SESSION_ID],
                },
            )
            .await;

            // First SetDevicePropValue → BUSY (busy_count=1)
            let r1 = exchange(
                &mut stream,
                &PtpIpPacket::OperationRequest {
                    data_phase: 1,
                    opcode: opcode::SET_DEVICE_PROP_VALUE,
                    transaction_id: 1,
                    params: vec![prop_code::IMAGE_GET_VERSION as u32],
                },
            )
            .await;
            assert!(
                matches!(
                    &r1,
                    PtpIpPacket::OperationResponse { response_code, .. }
                    if *response_code == response_code::BUSY
                ),
                "expected BUSY first"
            );

            // Second SetDevicePropValue → OK
            let r2 = exchange(
                &mut stream,
                &PtpIpPacket::OperationRequest {
                    data_phase: 1,
                    opcode: opcode::SET_DEVICE_PROP_VALUE,
                    transaction_id: 2,
                    params: vec![prop_code::IMAGE_GET_VERSION as u32],
                },
            )
            .await;
            assert!(
                matches!(
                    &r2,
                    PtpIpPacket::OperationResponse { response_code, .. }
                    if *response_code == response_code::OK
                ),
                "expected OK second"
            );

            // CloseSession
            let _ = exchange(
                &mut stream,
                &PtpIpPacket::OperationRequest {
                    data_phase: 1,
                    opcode: opcode::CLOSE_SESSION,
                    transaction_id: 3,
                    params: vec![],
                },
            )
            .await;
        })
        .await;

        result.expect("test timed out");
        handle.shutdown();
    }
}
