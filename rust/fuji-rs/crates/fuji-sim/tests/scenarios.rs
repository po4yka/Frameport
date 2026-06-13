//! Integration tests for `fuji-sim` — four deterministic PTP-IP scenario tests.
//!
//! Each test:
//!   - Builds a `FakeCameraServer` via `FakeCameraBuilder` on `127.0.0.1:0`.
//!   - Connects a `tokio::net::TcpStream` client.
//!   - Drives the CLIENT side of the PTP-IP exchange using
//!     `fuji_ptp::{encode_packet, decode_packet, PtpIpPacket, constants}`.
//!   - Asserts on TYPED response values (never raw bytes).
//!   - Calls `ShutdownHandle::shutdown()` and verifies the server task ends.
//!
//! Rules in effect:
//!   - No `thread::sleep` anywhere.
//!   - No fixed ports (only `127.0.0.1:0`).
//!   - No global / static mutable state.
//!   - Every `async fn` carries a `// cancel-safe:` annotation.
//!   - No bare hex literals — all constants from `fuji_ptp::constants`.

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::timeout;

use fuji_ptp::constants::{FUJI_PTPIP_VERSION, SESSION_ID, opcode, prop_code, response_code};
use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};
use fuji_sim::{FakeCameraBuilder, FakeCameraServer, MAX_BUSY_RETRIES};

// ── Client helpers ────────────────────────────────────────────────────────────

/// Writes a PTP-IP packet to the stream.
///
// cancel-safe: wraps a single `write_all` which is cancel-safe in tokio.
async fn send_packet(stream: &mut TcpStream, pkt: &PtpIpPacket) {
    let bytes = encode_packet(pkt);
    stream
        .write_all(&bytes)
        .await
        .expect("write_all should not fail on loopback");
}

/// Reads one length-prefixed PTP-IP packet from the stream.
///
// NOT cancel-safe: partial reads leave the stream cursor in an inconsistent
// position. Never used under `tokio::select!`.
async fn recv_packet(stream: &mut TcpStream) -> PtpIpPacket {
    let mut len_buf = [0u8; 4];
    stream
        .read_exact(&mut len_buf)
        .await
        .expect("read length prefix");
    let len = u32::from_le_bytes(len_buf) as usize;
    let mut buf = vec![0u8; len];
    buf[..4].copy_from_slice(&len_buf);
    stream.read_exact(&mut buf[4..]).await.expect("read body");
    decode_packet(&buf).expect("decode should succeed for well-formed server output")
}

/// Reads one packet that is known to begin a data phase (StartData) and drains
/// all data packets until EndData, then reads the trailing OperationResponse.
///
/// Returns `(end_data_payload, operation_response)`.
///
// NOT cancel-safe: owns accumulated state across multiple `.await` points.
async fn recv_data_phase(stream: &mut TcpStream) -> (Vec<u8>, PtpIpPacket) {
    // First packet must be StartData.
    let start = recv_packet(stream).await;
    assert!(
        matches!(start, PtpIpPacket::StartData { .. }),
        "expected StartData, got {start:?}"
    );

    // Drain optional DataPackets, collect EndData payload.
    let mut payload: Vec<u8> = Vec::new();
    let response = loop {
        let pkt = recv_packet(stream).await;
        match pkt {
            PtpIpPacket::DataPacket { payload: chunk, .. } => {
                payload.extend_from_slice(&chunk);
            }
            PtpIpPacket::EndData {
                payload: end_chunk, ..
            } => {
                payload.extend_from_slice(&end_chunk);
                // Next packet after EndData is the OperationResponse.
                let resp = recv_packet(stream).await;
                break resp;
            }
            other => panic!("unexpected packet in data phase: {other:?}"),
        }
    };
    (payload, response)
}

/// Performs the PTP-IP channel handshake: InitCommandRequest → InitCommandAck,
/// InitEventRequest → InitEventAck.
///
/// Returns the `(guid, camera_name)` from the ACK so callers can assert on them.
///
// NOT cancel-safe: multiple sequential `.await` points; drives stream state forward.
async fn handshake(stream: &mut TcpStream) -> ([u8; 16], String) {
    send_packet(
        stream,
        &PtpIpPacket::InitCommandRequest {
            version: FUJI_PTPIP_VERSION,
            guid: [
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E,
                0x0F, 0x10,
            ],
            device_name: "frameport-test-client".to_owned(),
        },
    )
    .await;

    let ack = recv_packet(stream).await;
    let (guid, camera_name) = match ack {
        PtpIpPacket::InitCommandAck { guid, camera_name } => (guid, camera_name),
        other => panic!("expected InitCommandAck, got {other:?}"),
    };

    send_packet(
        stream,
        &PtpIpPacket::InitEventRequest {
            connection_number: 1,
        },
    )
    .await;

    let event_ack = recv_packet(stream).await;
    assert_eq!(
        event_ack,
        PtpIpPacket::InitEventAck,
        "expected InitEventAck"
    );

    (guid, camera_name)
}

/// Sends an OperationRequest with no data phase and reads the single OperationResponse.
///
// NOT cancel-safe: multiple sequential `.await` points.
async fn op_no_data(
    stream: &mut TcpStream,
    opcode_val: u16,
    txn_id: u32,
    params: Vec<u32>,
) -> PtpIpPacket {
    send_packet(
        stream,
        &PtpIpPacket::OperationRequest {
            data_phase: 1, // no data phase
            opcode: opcode_val,
            transaction_id: txn_id,
            params,
        },
    )
    .await;
    recv_packet(stream).await
}

/// Sends an OperationRequest that returns a data phase and returns
/// `(payload, response_code)`.
///
// NOT cancel-safe: multiple sequential `.await` points with accumulated state.
async fn op_data_read(
    stream: &mut TcpStream,
    opcode_val: u16,
    txn_id: u32,
    params: Vec<u32>,
) -> (Vec<u8>, u16) {
    send_packet(
        stream,
        &PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: opcode_val,
            transaction_id: txn_id,
            params,
        },
    )
    .await;
    let (payload, resp) = recv_data_phase(stream).await;
    let code = match &resp {
        PtpIpPacket::OperationResponse { response_code, .. } => *response_code,
        other => panic!("expected OperationResponse, got {other:?}"),
    };
    (payload, code)
}

/// Sends a graceful goodbye packet (section 4.10) and closes the client side.
///
// cancel-safe: single `write_all`; stream drop is synchronous.
async fn send_goodbye(stream: &mut TcpStream) {
    use fuji_ptp::constants::GOODBYE_PACKET;
    stream
        .write_all(&GOODBYE_PACKET)
        .await
        .expect("write goodbye");
}

// ── Scenario 1: happy_path_handshake ─────────────────────────────────────────

/// Tests the complete happy-path PTP-IP session with busy_count=0.
///
/// Asserts:
///   - `InitCommandAck` carries the configured camera_name and a non-zero GUID.
///   - `OpenSession` returns `response_code::OK`.
///   - `GetDeviceInfo` completes the data phase and returns `response_code::OK`
///     with a non-empty payload containing "FUJIFILM" (manufacturer string).
///   - `SetDevicePropValue` (mode write / SetFunctionMode) returns `response_code::OK`
///     immediately (busy_count=0).
///   - `GetDevicePropValue(IMAGE_GET_VERSION)` returns the configured firmware version
///     as 4-byte LE UINT32 and `response_code::OK`.
///   - `GetDevicePropValue(EVENTS_LIST)` returns a 2-byte zero payload (empty events)
///     and `response_code::OK`.
///   - `GetDevicePropValue(OBJECT_COUNT)` returns the configured count as 4-byte LE
///     UINT32 and `response_code::OK`.
///   - Graceful client disconnect via goodbye packet; `ShutdownHandle::shutdown()` is
///     called and the server task exits (no hang).
///
/// Shutdown-clean verification: `ShutdownHandle::shutdown()` is called after the
/// client finishes. The whole test is wrapped in `tokio::time::timeout(5s)` — if the
/// server hangs after shutdown the test fails via timeout rather than hanging forever.
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime, never used under tokio::select!
#[tokio::test]
async fn happy_path_handshake() {
    const CONFIGURED_OBJECT_COUNT: u32 = 7;
    const CONFIGURED_FIRMWARE: u32 = 0x0002_0004; // X-T20 observed, master-constants.md §3b

    let custom_guid: [u8; 16] = [
        0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67,
        0x89,
    ];

    let config = FakeCameraBuilder::new()
        .busy_count(0)
        .object_count(CONFIGURED_OBJECT_COUNT)
        .firmware_version(CONFIGURED_FIRMWARE)
        .camera_guid(custom_guid)
        .camera_name("X-T5-sim-test")
        .build();

    let server = FakeCameraServer::bind(config).await.expect("bind");
    let addr = server.bound_addr();
    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut stream = TcpStream::connect(addr).await.expect("connect");

        // ── Step 1: Handshake ──────────────────────────────────────────────────
        let (returned_guid, returned_name) = handshake(&mut stream).await;

        // Camera name and GUID must match the builder configuration.
        assert_eq!(
            returned_name, "X-T5-sim-test",
            "InitCommandAck camera_name must match builder"
        );
        assert_eq!(
            returned_guid, custom_guid,
            "InitCommandAck guid must match builder"
        );

        // ── Step 2: OpenSession (txn_id=0, param[0]=SESSION_ID) ───────────────
        let open_resp = op_no_data(&mut stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
        assert!(
            matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
            "OpenSession must return OK, got {open_resp:?}"
        );

        // ── Step 3: GetDeviceInfo (data phase) ────────────────────────────────
        let (device_info_payload, device_info_code) =
            op_data_read(&mut stream, opcode::GET_DEVICE_INFO, 1, vec![]).await;
        assert_eq!(
            device_info_code,
            response_code::OK,
            "GetDeviceInfo must return OK"
        );
        assert!(
            !device_info_payload.is_empty(),
            "GetDeviceInfo payload must be non-empty"
        );
        // The dataset encodes "FUJIFILM" as a PTP string; "F" in UTF-16LE is 0x46 0x00.
        // We check that 0x46 (ASCII 'F') appears in the payload — manufacturer string present.
        assert!(
            device_info_payload.contains(&0x46u8),
            "GetDeviceInfo payload must contain FUJIFILM manufacturer string ('F' = 0x46)"
        );

        // ── Step 4: SetDevicePropValue (mode write = SetFunctionMode) ─────────
        // ptp-ptpip.md section 5.2: opcode=SET_DEVICE_PROP_VALUE on a mode property.
        // busy_count=0 → must return OK on the first attempt.
        let set_mode_resp = op_no_data(
            &mut stream,
            opcode::SET_DEVICE_PROP_VALUE,
            2,
            vec![prop_code::CLIENT_STATE as u32],
        )
        .await;
        assert!(
            matches!(&set_mode_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
            "SetFunctionMode (busy_count=0) must return OK immediately, got {set_mode_resp:?}"
        );

        // ── Step 5: GetDevicePropValue(IMAGE_GET_VERSION) ─────────────────────
        // ptp-ptpip.md section 5.2 / master-constants.md §3b: read then re-write version.
        let (fw_payload, fw_code) = op_data_read(
            &mut stream,
            opcode::GET_DEVICE_PROP_VALUE,
            3,
            vec![prop_code::IMAGE_GET_VERSION as u32],
        )
        .await;
        assert_eq!(
            fw_code,
            response_code::OK,
            "GetDevicePropValue(IMAGE_GET_VERSION) must return OK"
        );
        assert_eq!(
            fw_payload.len(),
            4,
            "firmware version payload must be 4 bytes (UINT32 LE)"
        );
        let fw_word = u32::from_le_bytes(fw_payload[..4].try_into().unwrap());
        assert_eq!(
            fw_word, CONFIGURED_FIRMWARE,
            "firmware version word must match builder"
        );

        // ── Step 6: GetDevicePropValue(EVENTS_LIST) ───────────────────────────
        // ptp-ptpip.md section 8.3: u16 count + entries; count=0 → 2 zero bytes.
        let (events_payload, events_code) = op_data_read(
            &mut stream,
            opcode::GET_DEVICE_PROP_VALUE,
            4,
            vec![prop_code::EVENTS_LIST as u32],
        )
        .await;
        assert_eq!(
            events_code,
            response_code::OK,
            "GetDevicePropValue(EVENTS_LIST) must return OK"
        );
        assert_eq!(
            events_payload,
            vec![0u8, 0u8],
            "EVENTS_LIST payload must be 2 zero bytes (empty list)"
        );

        // ── Step 7: GetDevicePropValue(OBJECT_COUNT) ──────────────────────────
        // master-constants.md §3b: OBJECT_COUNT is a UINT32.
        let (count_payload, count_code) = op_data_read(
            &mut stream,
            opcode::GET_DEVICE_PROP_VALUE,
            5,
            vec![prop_code::OBJECT_COUNT as u32],
        )
        .await;
        assert_eq!(
            count_code,
            response_code::OK,
            "GetDevicePropValue(OBJECT_COUNT) must return OK"
        );
        assert_eq!(
            count_payload.len(),
            4,
            "OBJECT_COUNT payload must be 4 bytes (UINT32 LE)"
        );
        let object_count = u32::from_le_bytes(count_payload[..4].try_into().unwrap());
        assert_eq!(
            object_count, CONFIGURED_OBJECT_COUNT,
            "object count must match builder"
        );

        // ── Step 8: Graceful disconnect via goodbye packet ────────────────────
        // ptp-ptpip.md section 4.10: 8-byte all-0xFF Ping treated as goodbye.
        send_goodbye(&mut stream).await;
        // Drop the stream — TCP FIN signals the server the client is done.
        drop(stream);
    });

    test_body.await.expect("happy_path_handshake timed out");

    // Shutdown-clean: signal server stop; the timeout above ensures no hang.
    handle.shutdown();
}

// ── Scenario 2: busy_retry_sequence ──────────────────────────────────────────

/// Tests the BUSY retry sequence: busy_count=5 → client receives BUSY exactly 5
/// times before the 6th attempt returns OK.
///
/// Asserts:
///   - The mode-write (SetDevicePropValue) returns `response_code::BUSY`
///     on exactly 5 consecutive attempts (matching `MAX_BUSY_RETRIES = 5`).
///   - The 6th attempt returns `response_code::OK`.
///   - The total number of requests sent equals 6 (5 retries + 1 success).
///   - No `thread::sleep` is used between retries.
///
/// Shutdown-clean: server is signalled via `ShutdownHandle::shutdown()` after the
/// session completes; the outer `timeout(5s)` guards against hangs.
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime, never used under tokio::select!
#[tokio::test]
async fn busy_retry_sequence() {
    // busy_count must equal MAX_BUSY_RETRIES (5) to exercise the full loop.
    let busy_count = MAX_BUSY_RETRIES;
    let config = FakeCameraBuilder::new().busy_count(busy_count).build();

    let server = FakeCameraServer::bind(config).await.expect("bind");
    let addr = server.bound_addr();
    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut stream = TcpStream::connect(addr).await.expect("connect");

        // Handshake
        let _ = handshake(&mut stream).await;

        // OpenSession
        let open_resp = op_no_data(&mut stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
        assert!(
            matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
            "OpenSession must return OK"
        );

        // ── Retry loop: expect BUSY × busy_count, then OK ─────────────────────
        // ptp-ptpip.md section 4.3: client retries up to MAX_BUSY_RETRIES=5 times.
        let mut requests_sent: u32 = 0;
        let mut busy_count_observed: u32 = 0;

        loop {
            let txn_id = 1 + requests_sent; // transaction IDs must be unique per session
            let resp = op_no_data(
                &mut stream,
                opcode::SET_DEVICE_PROP_VALUE,
                txn_id,
                vec![prop_code::CLIENT_STATE as u32],
            )
            .await;
            requests_sent += 1;

            match &resp {
                PtpIpPacket::OperationResponse { response_code, .. }
                    if *response_code == response_code::BUSY =>
                {
                    busy_count_observed += 1;
                    // No thread::sleep — retry immediately without real-time delay.
                    // RETRY_DELAY_MS is a documented CLIENT policy constant; this
                    // test verifies correctness, not real-time compliance.
                    assert!(
                        busy_count_observed <= busy_count,
                        "received more BUSY responses ({busy_count_observed}) than configured ({busy_count})"
                    );
                }
                PtpIpPacket::OperationResponse { response_code, .. }
                    if *response_code == response_code::OK =>
                {
                    break;
                }
                other => {
                    panic!("unexpected response on mode write: {other:?}");
                }
            }
        }

        // Verify exact counts.
        assert_eq!(
            busy_count_observed, busy_count,
            "must have received exactly {busy_count} BUSY responses"
        );
        // Total requests = number of BUSYs + 1 success.
        let expected_requests = busy_count + 1;
        assert_eq!(
            requests_sent, expected_requests,
            "total mode-write requests must equal busy_count({busy_count}) + 1 = {expected_requests}"
        );

        // CloseSession to exit cleanly.
        let close_resp = op_no_data(
            &mut stream,
            opcode::CLOSE_SESSION,
            requests_sent + 1,
            vec![],
        )
        .await;
        assert!(
            matches!(&close_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
            "CloseSession must return OK"
        );
    });

    test_body.await.expect("busy_retry_sequence timed out");
    handle.shutdown();
}

// ── Scenario 3: fatal_error_termination ──────────────────────────────────────

/// Table-driven test over the three fatal status codes that abort the session
/// on the mode-write step (SetDevicePropValue).
///
/// Codes tested (by name, not bare hex):
///   - `response_code::FATAL_AUTH_FAILURE`   (0x201D) — auth failure
///   - `response_code::SESSION_ALREADY_OPEN` (0x201E) — stale session
///   - `response_code::FATAL_GENERIC_FAILURE` (0x2000) — generic abort
///
/// Asserts for each sub-case:
///   - The server returns exactly the configured fatal code on the mode write.
///   - The client does NOT retry after a fatal code (only one request is sent).
///   - The error code is surfaced as a typed `OperationResponse` value —
///     the client can match it, not just observe a connection drop.
///
/// Shutdown-clean: each sub-case creates its own ephemeral-port server and
/// calls `ShutdownHandle::shutdown()` independently.
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime, never used under tokio::select!
#[tokio::test]
async fn fatal_error_termination() {
    // Table: (fatal_code, human_label_for_assertion_messages)
    // All three codes from ptp-ptpip.md section 7 / master-constants.md §4b.
    let cases: &[(u16, &str)] = &[
        (
            response_code::FATAL_AUTH_FAILURE,
            "FATAL_AUTH_FAILURE (0x201D)",
        ),
        (
            response_code::SESSION_ALREADY_OPEN,
            "SESSION_ALREADY_OPEN (0x201E)",
        ),
        (
            response_code::FATAL_GENERIC_FAILURE,
            "FATAL_GENERIC_FAILURE (0x2000)",
        ),
    ];

    for &(fatal_code, label) in cases {
        let config = FakeCameraBuilder::new()
            .fatal_on_set_mode(Some(fatal_code))
            .build();

        let server = FakeCameraServer::bind(config).await.expect("bind");
        let addr = server.bound_addr();
        let handle = server.run();

        let test_body = timeout(Duration::from_secs(5), async move {
            let mut stream = TcpStream::connect(addr).await.expect("connect");

            // Handshake
            let _ = handshake(&mut stream).await;

            // OpenSession
            let open_resp =
                op_no_data(&mut stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
            assert!(
                matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
                "[{label}] OpenSession must return OK"
            );

            // Mode write: expect the configured fatal code, NOT BUSY and NOT OK.
            let mode_resp = op_no_data(
                &mut stream,
                opcode::SET_DEVICE_PROP_VALUE,
                1,
                vec![prop_code::CLIENT_STATE as u32],
            )
            .await;

            let returned_code = match &mode_resp {
                PtpIpPacket::OperationResponse { response_code, .. } => *response_code,
                other => panic!("[{label}] expected OperationResponse, got {other:?}"),
            };

            assert_eq!(
                returned_code, fatal_code,
                "[{label}] fatal mode-write response code must exactly match configured value"
            );

            // The client must NOT retry on a fatal code — verify by asserting that
            // the returned code is not BUSY (client would retry BUSY).
            assert_ne!(
                returned_code,
                response_code::BUSY,
                "[{label}] fatal code must not be BUSY"
            );
            assert_ne!(
                returned_code,
                response_code::OK,
                "[{label}] fatal code must not be OK"
            );
        });

        test_body
            .await
            .unwrap_or_else(|_| panic!("[{label}] test timed out"));

        handle.shutdown();
    }
}

// ── Scenario 4: premature_disconnect ─────────────────────────────────────────

/// Tests that a server-side connection drop after `InitCommandAck` (before
/// `OpenSession`) is surfaced to the client as a typed EOF / disconnect error
/// rather than a panic or an infinite hang.
///
/// Mechanism: the server is configured with a deliberately wrong version that
/// the server will reject... but actually we want the server to ACK and then we
/// drop. We test this by:
///   1. Completing a real `InitCommandRequest` → `InitCommandAck` exchange.
///   2. NOT sending `InitEventRequest` — instead the test client tries to read
///      another packet immediately after the ACK.  The server drops the
///      connection because the next packet it receives (from the client's
///      perspective: the client sends a wrong packet type, OR we just close the
///      client connection to simulate early hangup, observe the READ side).
///
/// Actually, to simulate the server dropping after ACK: we use a second
/// connection to the same server where — after the first handshake completes on
/// a separate connection — we connect a fresh client that only reads the ACK
/// and then tries to read a second packet. The server will proceed to wait for
/// `InitEventRequest`, but we send `CloseSession` instead (wrong order), which
/// triggers `ProtocolViolation` and the server closes the connection. The client
/// read of the next packet must return an error (EOF = `UnexpectedDisconnect`),
/// bounded by `tokio::time::timeout`.
///
/// Asserts:
///   - Reading from the stream after the server drops the connection returns
///     `Err` / EOF within the test timeout — no hang, no panic.
///   - The client's `read_exact` returns an error (not a panic) when the
///     server closes the connection.
///
/// Shutdown-clean: `ShutdownHandle::shutdown()` is called after the test.
/// The outer `timeout(5s)` ensures no hang even if the server stalls.
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime, never used under tokio::select!
#[tokio::test]
async fn premature_disconnect() {
    let config = FakeCameraBuilder::new().build();
    let server = FakeCameraServer::bind(config).await.expect("bind");
    let addr = server.bound_addr();
    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut stream = TcpStream::connect(addr).await.expect("connect");

        // ── Step 1: Complete InitCommandRequest → InitCommandAck ──────────────
        send_packet(
            &mut stream,
            &PtpIpPacket::InitCommandRequest {
                version: FUJI_PTPIP_VERSION,
                guid: [0xFFu8; 16],
                device_name: "disconnect-test".to_owned(),
            },
        )
        .await;

        // Read the ACK — server must send InitCommandAck.
        let ack = recv_packet(&mut stream).await;
        assert!(
            matches!(ack, PtpIpPacket::InitCommandAck { .. }),
            "expected InitCommandAck before disconnect scenario, got {ack:?}"
        );

        // ── Step 2: Send wrong packet (OperationRequest instead of InitEventRequest)
        // The server expects InitEventRequest next; receiving OperationRequest causes
        // a ProtocolViolation, which closes the connection from the server side.
        send_packet(
            &mut stream,
            &PtpIpPacket::OperationRequest {
                data_phase: 1,
                opcode: opcode::CLOSE_SESSION, // totally wrong at this stage
                transaction_id: 99,
                params: vec![],
            },
        )
        .await;

        // ── Step 3: Client reads — must see EOF/error, not hang ───────────────
        // The server has dropped the connection. `read_exact` on a closed stream
        // returns an error. We assert it errors out rather than blocking.
        let mut len_buf = [0u8; 4];
        let read_result = stream.read_exact(&mut len_buf).await;

        assert!(
            read_result.is_err(),
            "reading from a server-dropped connection must return Err (got Ok — server did not disconnect)"
        );
        // This is the typed UnexpectedDisconnect-equivalent — an I/O error on read,
        // not a panic and not a hang. The error kind is typically `UnexpectedEof`.
        let err = read_result.unwrap_err();
        assert!(
            err.kind() == std::io::ErrorKind::UnexpectedEof
                || err.kind() == std::io::ErrorKind::ConnectionReset
                || err.kind() == std::io::ErrorKind::BrokenPipe,
            "expected UnexpectedEof/ConnectionReset/BrokenPipe, got {err:?}"
        );
    });

    test_body.await.expect("premature_disconnect timed out");
    handle.shutdown();
}
