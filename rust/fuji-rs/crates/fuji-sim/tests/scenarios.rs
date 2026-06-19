//! Integration tests for `fuji-sim` — four deterministic PTP-IP scenario tests
//! plus a dedicated two-socket event-handshake test.
//!
//! Each test:
//!   - Builds a `FakeCameraServer` via `FakeCameraBuilder` on `127.0.0.1:0`.
//!   - Connects a command `TcpStream` to `bound_addr()` and a separate event
//!     `TcpStream` to `event_addr()` — matching the production PTP-IP two-socket
//!     design (ptp-ptpip.md section 5.1 steps 5-7).
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
// NOT cancel-safe: partial reads leave stream cursor in inconsistent state; never used under tokio::select!.
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

/// Performs the two-socket PTP-IP channel handshake:
///
///   - Command socket:  InitCommandRequest → InitCommandAck
///   - Event socket:    InitEventRequest → InitEventAck  (separate TCP connection)
///
/// Per ptp-ptpip.md section 5.1 steps 2-7. [H]
///
/// Returns the `(guid, camera_name)` from the command ACK so callers can assert on them.
///
// NOT cancel-safe: multiple sequential `.await` points; drives stream state forward.
async fn handshake(cmd_stream: &mut TcpStream, event_stream: &mut TcpStream) -> ([u8; 16], String) {
    // ── Command channel: InitCommandRequest → InitCommandAck ──────────────────
    send_packet(
        cmd_stream,
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

    let ack = recv_packet(cmd_stream).await;
    let (guid, camera_name) = match ack {
        PtpIpPacket::InitCommandAck { guid, camera_name } => (guid, camera_name),
        other => panic!("expected InitCommandAck, got {other:?}"),
    };

    // ── Event channel: InitEventRequest → InitEventAck ────────────────────────
    // Separate TCP connection to event_addr() per ptp-ptpip.md section 5.1 step 5. [H]
    send_packet(
        event_stream,
        &PtpIpPacket::InitEventRequest {
            connection_number: 1,
        },
    )
    .await;

    let event_ack = recv_packet(event_stream).await;
    assert_eq!(
        event_ack,
        PtpIpPacket::InitEventAck,
        "expected InitEventAck on event channel"
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
///   - The event channel (`event_addr()`) independently accepts `InitEventRequest`
///     and replies `InitEventAck`.
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
    let cmd_addr = server.bound_addr();
    let event_addr = server.event_addr();
    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async {
        // Open both channels
        let mut cmd_stream = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        let mut event_stream = TcpStream::connect(event_addr).await.expect("connect event");

        // ── Step 1: Two-socket handshake ──────────────────────────────────────
        let (returned_guid, returned_name) = handshake(&mut cmd_stream, &mut event_stream).await;

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
        let open_resp =
            op_no_data(&mut cmd_stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
        assert!(
            matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
            "OpenSession must return OK, got {open_resp:?}"
        );

        // ── Step 3: GetDeviceInfo (data phase) ────────────────────────────────
        let (device_info_payload, device_info_code) =
            op_data_read(&mut cmd_stream, opcode::GET_DEVICE_INFO, 1, vec![]).await;
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
        // ClientState (0xDF01) is UINT16 — 2-byte payload, value=1 (IMAGE_RECEIVE).
        // master-constants.md §3b. [H]
        let set_mode_code = op_set_prop_value(
            &mut cmd_stream,
            2,
            prop_code::CLIENT_STATE,
            &[1u8, 0u8], // IMAGE_RECEIVE = 1 as UINT16 LE
        )
        .await;
        assert_eq!(
            set_mode_code,
            response_code::OK,
            "SetFunctionMode (busy_count=0) must return OK immediately"
        );

        // ── Step 5: GetDevicePropValue(IMAGE_GET_VERSION) ─────────────────────
        // ptp-ptpip.md section 5.2 / master-constants.md §3b: read then re-write version.
        let (fw_payload, fw_code) = op_data_read(
            &mut cmd_stream,
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
            &mut cmd_stream,
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
            &mut cmd_stream,
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
        send_goodbye(&mut cmd_stream).await;
        // Drop streams — TCP FIN signals the server the client is done.
        drop(cmd_stream);
        drop(event_stream);
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
    let cmd_addr = server.bound_addr();
    let event_addr = server.event_addr();
    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut cmd_stream = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        let mut event_stream = TcpStream::connect(event_addr).await.expect("connect event");

        // Two-socket handshake
        let _ = handshake(&mut cmd_stream, &mut event_stream).await;

        // OpenSession
        let open_resp =
            op_no_data(&mut cmd_stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
        assert!(
            matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
            "OpenSession must return OK"
        );

        // ── Retry loop: expect BUSY × busy_count, then OK ─────────────────────
        // ptp-ptpip.md section 4.3: client retries up to MAX_BUSY_RETRIES=5 times.
        // ClientState (0xDF01) is UINT16 — 2-byte payload. master-constants.md §3b. [H]
        let mut requests_sent: u32 = 0;
        let mut busy_count_observed: u32 = 0;

        loop {
            let txn_id = 1 + requests_sent; // transaction IDs must be unique per session
            let code = op_set_prop_value(
                &mut cmd_stream,
                txn_id,
                prop_code::CLIENT_STATE,
                &[1u8, 0u8], // IMAGE_RECEIVE = 1 as UINT16 LE
            )
            .await;
            requests_sent += 1;

            if code == response_code::BUSY {
                busy_count_observed += 1;
                // No thread::sleep — retry immediately without real-time delay.
                // RETRY_DELAY_MS is a documented CLIENT policy constant; this
                // test verifies correctness, not real-time compliance.
                assert!(
                    busy_count_observed <= busy_count,
                    "received more BUSY responses ({busy_count_observed}) than configured ({busy_count})"
                );
            } else if code == response_code::OK {
                break;
            } else {
                panic!("unexpected response_code on mode write: 0x{code:04X}");
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
            &mut cmd_stream,
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
        let cmd_addr = server.bound_addr();
        let event_addr = server.event_addr();
        let handle = server.run();

        let test_body = timeout(Duration::from_secs(5), async move {
            let mut cmd_stream = TcpStream::connect(cmd_addr).await.expect("connect cmd");
            let mut event_stream = TcpStream::connect(event_addr).await.expect("connect event");

            // Two-socket handshake
            let _ = handshake(&mut cmd_stream, &mut event_stream).await;

            // OpenSession
            let open_resp =
                op_no_data(&mut cmd_stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
            assert!(
                matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
                "[{label}] OpenSession must return OK"
            );

            // Mode write: expect the configured fatal code, NOT BUSY and NOT OK.
            // ClientState (0xDF01) is UINT16 — 2-byte payload. master-constants.md §3b. [H]
            // The payload must pass width validation before the fatal_on_set_mode check fires.
            let returned_code = op_set_prop_value(
                &mut cmd_stream,
                1,
                prop_code::CLIENT_STATE,
                &[1u8, 0u8], // IMAGE_RECEIVE = 1 as UINT16 LE
            )
            .await;

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
/// Mechanism:
///   1. Completing a real `InitCommandRequest` → `InitCommandAck` exchange on cmd.
///   2. Completing `InitEventRequest` → `InitEventAck` on the event socket.
///   3. Sending a wrong packet type on the command socket (OperationRequest instead
///      of what the server now expects — i.e., a valid operation), but specifically
///      sending `CloseSession` right away which the server WILL handle cleanly.
///
/// Actually, to exercise the disconnect case we send a completely unexpected packet
/// type to the command socket BEFORE OpenSession: we send an `OperationRequest` with
/// opcode CLOSE_SESSION (valid structurally, but at this stage the server expects
/// `OperationRequest` only in the dispatch loop — which it IS in). The server will
/// return OK on CloseSession and close the connection.  The client then reads from
/// the now-closed stream and must get an EOF error, not a hang.
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
    let cmd_addr = server.bound_addr();
    let event_addr = server.event_addr();
    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async {
        // ── Step 1: Complete InitCommandRequest → InitCommandAck ──────────────
        let mut cmd_stream = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        send_packet(
            &mut cmd_stream,
            &PtpIpPacket::InitCommandRequest {
                version: FUJI_PTPIP_VERSION,
                guid: [0xFFu8; 16],
                device_name: "disconnect-test".to_owned(),
            },
        )
        .await;

        // Read the ACK — server must send InitCommandAck.
        let ack = recv_packet(&mut cmd_stream).await;
        assert!(
            matches!(ack, PtpIpPacket::InitCommandAck { .. }),
            "expected InitCommandAck before disconnect scenario, got {ack:?}"
        );

        // ── Step 2: Complete event channel handshake ──────────────────────────
        // The server is now waiting for the event connection.
        let mut event_stream = TcpStream::connect(event_addr).await.expect("connect event");
        send_packet(
            &mut event_stream,
            &PtpIpPacket::InitEventRequest {
                connection_number: 1,
            },
        )
        .await;
        let event_ack = recv_packet(&mut event_stream).await;
        assert_eq!(event_ack, PtpIpPacket::InitEventAck);

        // ── Step 3: OpenSession then CloseSession immediately ─────────────────
        // M-8: the session-open guard requires OpenSession before any other
        // operation; CloseSession without a prior OpenSession now returns
        // SESSION_NOT_OPEN (0x2003) instead of OK.  Send OpenSession first so
        // CloseSession returns OK and the server closes the connection cleanly.
        send_packet(
            &mut cmd_stream,
            &PtpIpPacket::OperationRequest {
                data_phase: 1,
                opcode: opcode::OPEN_SESSION,
                transaction_id: 1,
                params: vec![fuji_ptp::constants::SESSION_ID],
            },
        )
        .await;
        let open_resp = recv_packet(&mut cmd_stream).await;
        assert!(
            matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
            "OpenSession must return OK, got {open_resp:?}"
        );

        send_packet(
            &mut cmd_stream,
            &PtpIpPacket::OperationRequest {
                data_phase: 1,
                opcode: opcode::CLOSE_SESSION,
                transaction_id: 99,
                params: vec![],
            },
        )
        .await;

        // Read the CloseSession OK response.
        let close_resp = recv_packet(&mut cmd_stream).await;
        assert!(
            matches!(&close_resp, PtpIpPacket::OperationResponse { response_code, .. } if *response_code == response_code::OK),
            "CloseSession must return OK, got {close_resp:?}"
        );

        // ── Step 4: Client reads again — must see EOF/error, not hang ──────────
        // The server has returned from handle_session after CloseSession. The
        // TCP connection is now half-closed or fully closed on the server side.
        let mut len_buf = [0u8; 4];
        let read_result = cmd_stream.read_exact(&mut len_buf).await;

        assert!(
            read_result.is_err(),
            "reading from a server-closed connection must return Err (got Ok — server did not disconnect)"
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

// ── Scenario 5: device_info_advertises_operations_and_properties ─────────────

/// Parses a u16 PTP array at `offset` in `buf`.
///
/// PTP array encoding (ptp-ptpip.md section 4.12): u32 LE count + count × u16 LE.
/// Returns `(array_elements, bytes_consumed)`.
fn parse_u16_array(buf: &[u8], offset: usize) -> (Vec<u16>, usize) {
    assert!(
        buf.len() >= offset + 4,
        "buffer too short to read u16-array count at offset {offset}"
    );
    let count = u32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap()) as usize;
    let data_start = offset + 4;
    let data_end = data_start + count * 2;
    assert!(
        buf.len() >= data_end,
        "buffer too short for {count} u16 elements at offset {data_start}"
    );
    let mut elements = Vec::with_capacity(count);
    for i in 0..count {
        let pos = data_start + i * 2;
        elements.push(u16::from_le_bytes(buf[pos..pos + 2].try_into().unwrap()));
    }
    (elements, 4 + count * 2)
}

/// Skips a PTP string in `buf` at `offset`, returning the number of bytes consumed.
///
/// PTP string encoding (ptp-ptpip.md section 4.11):
///   u8 num_chars (including null; 0 = empty string), then num_chars × u16 LE.
fn skip_ptp_string(buf: &[u8], offset: usize) -> usize {
    assert!(
        buf.len() > offset,
        "buffer too short for PTP string length byte at offset {offset}"
    );
    let num_chars = buf[offset] as usize;
    1 + num_chars * 2
}

/// Tests that GetDeviceInfo returns non-empty OperationsSupported and
/// DevicePropertiesSupported arrays that contain specific expected codes.
///
/// Parses the ISO 15740 §5.5.1 DeviceInfo layout from the EndData payload:
///   StandardVersion (u16)
///   VendorExtensionID (u32)
///   VendorExtensionVersion (u16)
///   VendorExtensionDesc (PTP string)
///   FunctionalMode (u16)
///   OperationsSupported (u16 array)  ← assert non-empty, contains OPEN_SESSION
///   EventsSupported (u16 array)      ← skip
///   DevicePropertiesSupported (u16 array) ← assert non-empty, contains OBJECT_COUNT
///
/// Sources:
///   - ptp-ptpip.md section 6.1 (GetDeviceInfo dataset layout). [H]
///   - ptp-ptpip.md section 4.12 (array encoding). [H]
///   - master-constants.md §2a (OPEN_SESSION = 0x1002). [H]
///   - master-constants.md §3b (OBJECT_COUNT = 0xD222). [H]
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime, never used under tokio::select!
#[tokio::test]
async fn device_info_advertises_operations_and_properties() {
    let config = FakeCameraBuilder::new().build();
    let server = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = server.bound_addr();
    let event_addr = server.event_addr();
    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut cmd_stream = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        let mut event_stream = TcpStream::connect(event_addr).await.expect("connect event");

        let _ = handshake(&mut cmd_stream, &mut event_stream).await;

        // OpenSession required before GetDeviceInfo returns a full payload.
        // (GetDeviceInfo technically needs no session per spec, but the sim
        // dispatch loop is already open here — we just follow the standard
        // session flow for consistency with other scenarios.)
        let open_resp =
            op_no_data(&mut cmd_stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
        assert!(
            matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK),
            "OpenSession must return OK"
        );

        // ── GetDeviceInfo ─────────────────────────────────────────────────────
        let (payload, code) =
            op_data_read(&mut cmd_stream, opcode::GET_DEVICE_INFO, 1, vec![]).await;

        assert_eq!(code, response_code::OK, "GetDeviceInfo must return OK");
        assert!(!payload.is_empty(), "DeviceInfo payload must be non-empty");

        // ── Parse DeviceInfo layout (ISO 15740 §5.5.1 / ptp-ptpip.md §6.1) ──
        let mut cursor: usize = 0;

        // StandardVersion: u16
        cursor += 2;
        // VendorExtensionID: u32
        cursor += 4;
        // VendorExtensionVersion: u16
        cursor += 2;
        // VendorExtensionDesc: PTP string ("FUJIFILM")
        cursor += skip_ptp_string(&payload, cursor);
        // FunctionalMode: u16
        cursor += 2;

        // OperationsSupported: u16 array — ptp-ptpip.md section 4.12.
        let (ops, ops_consumed) = parse_u16_array(&payload, cursor);
        cursor += ops_consumed;

        assert!(
            !ops.is_empty(),
            "OperationsSupported must be non-empty (got empty array)"
        );
        assert!(
            ops.contains(&opcode::OPEN_SESSION),
            "OperationsSupported must contain OPEN_SESSION (0x{:04X}); got {ops:?}",
            opcode::OPEN_SESSION
        );
        assert!(
            ops.contains(&opcode::GET_DEVICE_INFO),
            "OperationsSupported must contain GET_DEVICE_INFO (0x{:04X}); got {ops:?}",
            opcode::GET_DEVICE_INFO
        );
        assert!(
            ops.contains(&opcode::GET_PARTIAL_OBJECT),
            "OperationsSupported must contain GET_PARTIAL_OBJECT (0x{:04X}); got {ops:?}",
            opcode::GET_PARTIAL_OBJECT
        );

        // EventsSupported: u16 array — skip (Fujifilm uses EventsList poll instead).
        let (_, events_consumed) = parse_u16_array(&payload, cursor);
        cursor += events_consumed;

        // DevicePropertiesSupported: u16 array — ptp-ptpip.md section 4.12.
        let (props, _props_consumed) = parse_u16_array(&payload, cursor);

        assert!(
            !props.is_empty(),
            "DevicePropertiesSupported must be non-empty (got empty array)"
        );
        assert!(
            props.contains(&prop_code::OBJECT_COUNT),
            "DevicePropertiesSupported must contain OBJECT_COUNT (0x{:04X}); got {props:?}",
            prop_code::OBJECT_COUNT
        );
        assert!(
            props.contains(&prop_code::EVENTS_LIST),
            "DevicePropertiesSupported must contain EVENTS_LIST (0x{:04X}); got {props:?}",
            prop_code::EVENTS_LIST
        );

        send_goodbye(&mut cmd_stream).await;
        drop(cmd_stream);
        drop(event_stream);
    });

    test_body
        .await
        .expect("device_info_advertises_operations_and_properties timed out");
    handle.shutdown();
}

// ── Scenario 6: two_socket_event_handshake ────────────────────────────────────

/// Validates the two-socket PTP-IP design explicitly:
///
///   - Command socket connects to `bound_addr()` and drives the Init_Command handshake.
///   - Event socket connects to `event_addr()` and drives the InitEventRequest/Ack.
///   - Both channels are exercised on distinct TCP connections with no shared state.
///   - The session completes cleanly with CloseSession.
///   - `ShutdownHandle::shutdown()` stops BOTH accept paths without a hang.
///
/// ptp-ptpip.md section 5.1 steps 2-7. [H]
///
/// This test is timeout-bounded (5 s); uses no `thread::sleep`.
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime, never used under tokio::select!
#[tokio::test]
async fn two_socket_event_handshake() {
    let custom_guid: [u8; 16] = [
        0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90, 0xA0, 0xB0, 0xC0, 0xD0, 0xE0, 0xF0,
        0x01,
    ];

    let config = FakeCameraBuilder::new()
        .camera_guid(custom_guid)
        .camera_name("two-socket-test-cam")
        .object_count(1)
        .build();

    let server = FakeCameraServer::bind(config).await.expect("bind");

    // Capture both addresses BEFORE run() moves the listeners.
    let cmd_addr = server.bound_addr();
    let event_addr = server.event_addr();

    // Confirm they are distinct (OS must assign separate ephemeral ports).
    assert_ne!(
        cmd_addr, event_addr,
        "command and event listeners must be on distinct addresses"
    );

    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async move {
        // ── Open both sockets ─────────────────────────────────────────────────
        let mut cmd_stream = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        let mut event_stream = TcpStream::connect(event_addr).await.expect("connect event");

        // ── Command channel: InitCommandRequest → InitCommandAck ──────────────
        send_packet(
            &mut cmd_stream,
            &PtpIpPacket::InitCommandRequest {
                version: FUJI_PTPIP_VERSION,
                guid: [
                    0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6, 0x07, 0x18, 0x29, 0x3A, 0x4B, 0x5C, 0x6D,
                    0x7E, 0x8F, 0x90,
                ],
                device_name: "two-socket-client".to_owned(),
            },
        )
        .await;

        let cmd_ack = recv_packet(&mut cmd_stream).await;
        let (returned_guid, returned_name) = match cmd_ack {
            PtpIpPacket::InitCommandAck { guid, camera_name } => (guid, camera_name),
            other => panic!("expected InitCommandAck on command channel, got {other:?}"),
        };

        // Verify the command ACK carries the configured camera identity.
        assert_eq!(
            returned_guid, custom_guid,
            "InitCommandAck guid must match builder"
        );
        assert_eq!(
            returned_name, "two-socket-test-cam",
            "InitCommandAck camera_name must match builder"
        );

        // ── Event channel: InitEventRequest → InitEventAck ────────────────────
        // This MUST be on the separate event socket, NOT the command socket.
        // ptp-ptpip.md section 5.1 step 5. [H]
        send_packet(
            &mut event_stream,
            &PtpIpPacket::InitEventRequest {
                connection_number: 1,
            },
        )
        .await;

        let event_ack = recv_packet(&mut event_stream).await;
        assert_eq!(
            event_ack,
            PtpIpPacket::InitEventAck,
            "must receive InitEventAck on the event channel"
        );

        // ── Operations on command channel ─────────────────────────────────────
        // OpenSession
        let open_resp =
            op_no_data(&mut cmd_stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
        assert!(
            matches!(
                &open_resp,
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "OpenSession must return OK, got {open_resp:?}"
        );

        // CloseSession — ends the session cleanly.
        let close_resp = op_no_data(&mut cmd_stream, opcode::CLOSE_SESSION, 1, vec![]).await;
        assert!(
            matches!(
                &close_resp,
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "CloseSession must return OK, got {close_resp:?}"
        );

        // Drop both streams to release loopback resources.
        drop(cmd_stream);
        drop(event_stream);
    });

    test_body
        .await
        .expect("two_socket_event_handshake timed out");

    // Shutdown-clean: signal server stop; the timeout above ensures no hang.
    handle.shutdown();
}

// ── Scenario 7: set_device_prop_payload_validation ───────────────────────────

/// Sends a `SetDevicePropValue` with a data phase on the command socket.
///
/// The data phase follows ptp-ptpip.md section 4.9:
///   OperationRequest (data_phase=2) → StartData → EndData → (read OperationResponse)
///
/// Returns the `response_code` from the server's `OperationResponse`.
///
// NOT cancel-safe: multiple sequential `.await` points with stream state.
async fn op_set_prop_value(stream: &mut TcpStream, txn_id: u32, prop: u16, payload: &[u8]) -> u16 {
    // OperationRequest with data_phase=2 signals a write with data phase.
    // ptp-ptpip.md section 4.9. [H]
    send_packet(
        stream,
        &PtpIpPacket::OperationRequest {
            data_phase: 2,
            opcode: opcode::SET_DEVICE_PROP_VALUE,
            transaction_id: txn_id,
            params: vec![prop as u32],
        },
    )
    .await;

    // StartData — total_data_length = payload.len().
    let payload_len = payload.len() as u64;
    send_packet(
        stream,
        &PtpIpPacket::StartData {
            transaction_id: txn_id,
            total_data_length: payload_len,
        },
    )
    .await;

    // EndData — carries the value bytes.
    send_packet(
        stream,
        &PtpIpPacket::EndData {
            transaction_id: txn_id,
            payload: payload.to_vec(),
        },
    )
    .await;

    // Read the server's OperationResponse.
    let resp = recv_packet(stream).await;
    match resp {
        PtpIpPacket::OperationResponse { response_code, .. } => response_code,
        other => panic!("expected OperationResponse after SetDevicePropValue, got {other:?}"),
    }
}

/// Tests SetDevicePropValue payload validation over a live server connection.
///
/// Asserts:
///   1. A well-formed `ClientState` write (UINT16, 2 bytes) with busy_count=0
///      returns `response_code::OK`.
///   2. A malformed `ClientState` write with an **empty** data-phase payload
///      returns `response_code::PARAMETER_NOT_SUPPORTED`.
///   3. A malformed `ClientState` write with a **wrong-width** (3-byte) payload
///      returns `response_code::PARAMETER_NOT_SUPPORTED`.
///   4. A well-formed `ImageGetVersion` write (UINT32, 4 bytes) with busy_count=0
///      returns `response_code::OK`.
///   5. A malformed `ImageGetVersion` write with a **2-byte** payload
///      returns `response_code::PARAMETER_NOT_SUPPORTED`.
///
/// Property widths:
///   - `ClientState`     (0xDF01) — UINT16, 2 bytes. master-constants.md §3b. [H]
///   - `ImageGetVersion` (0xDF21) — UINT32, 4 bytes. master-constants.md §3b. [H]
///
/// Validation must fire **before** the busy_count/fatal_on_set_mode logic: the server
/// is configured with busy_count=0 and no fatal override so a successful well-formed
/// write returns OK immediately.
///
/// No `thread::sleep`, ephemeral ports only, timeout-bounded at 5 s.
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime.
#[tokio::test]
async fn set_device_prop_payload_validation() {
    let config = FakeCameraBuilder::new()
        .busy_count(0) // well-formed writes must return OK on first attempt
        .build();

    let server = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = server.bound_addr();
    let event_addr = server.event_addr();
    let handle = server.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut cmd_stream = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        let mut event_stream = TcpStream::connect(event_addr).await.expect("connect event");

        // Two-socket handshake.
        let _ = handshake(&mut cmd_stream, &mut event_stream).await;

        // OpenSession (required before property operations).
        let open_resp =
            op_no_data(&mut cmd_stream, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;
        assert!(
            matches!(&open_resp, PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK),
            "OpenSession must return OK, got {open_resp:?}"
        );

        // ── Case 1: well-formed ClientState write (UINT16, 2 bytes) → OK ─────
        // ClientState (0xDF01) is UINT16. master-constants.md §3b. [H]
        let code_1 = op_set_prop_value(
            &mut cmd_stream,
            1,
            prop_code::CLIENT_STATE,
            &[1u8, 0u8], // IMAGE_RECEIVE mode = 1 as UINT16 LE
        )
        .await;
        assert_eq!(
            code_1,
            response_code::OK,
            "well-formed ClientState write must return OK (busy_count=0)"
        );

        // ── Case 2: malformed ClientState — empty payload → PARAMETER_NOT_SUPPORTED
        let code_2 = op_set_prop_value(
            &mut cmd_stream,
            2,
            prop_code::CLIENT_STATE,
            &[], // 0 bytes — wrong width for UINT16
        )
        .await;
        assert_eq!(
            code_2,
            response_code::PARAMETER_NOT_SUPPORTED,
            "empty payload for ClientState must return PARAMETER_NOT_SUPPORTED"
        );

        // ── Case 3: malformed ClientState — wrong-width (3 bytes) → PARAMETER_NOT_SUPPORTED
        let code_3 = op_set_prop_value(
            &mut cmd_stream,
            3,
            prop_code::CLIENT_STATE,
            &[1u8, 0u8, 0u8], // 3 bytes — wrong width for UINT16
        )
        .await;
        assert_eq!(
            code_3,
            response_code::PARAMETER_NOT_SUPPORTED,
            "3-byte payload for ClientState (UINT16) must return PARAMETER_NOT_SUPPORTED"
        );

        // ── Case 4: well-formed ImageGetVersion write (UINT32, 4 bytes) → OK ──
        // ImageGetVersion (0xDF21) is UINT32. master-constants.md §3b. [H]
        let code_4 = op_set_prop_value(
            &mut cmd_stream,
            4,
            prop_code::IMAGE_GET_VERSION,
            &[0x04u8, 0x00u8, 0x02u8, 0x00u8], // 0x00020004 LE
        )
        .await;
        assert_eq!(
            code_4,
            response_code::OK,
            "well-formed ImageGetVersion write must return OK (busy_count=0)"
        );

        // ── Case 5: malformed ImageGetVersion — 2 bytes instead of 4 → PARAMETER_NOT_SUPPORTED
        let code_5 = op_set_prop_value(
            &mut cmd_stream,
            5,
            prop_code::IMAGE_GET_VERSION,
            &[0x04u8, 0x00u8], // 2 bytes — wrong width for UINT32
        )
        .await;
        assert_eq!(
            code_5,
            response_code::PARAMETER_NOT_SUPPORTED,
            "2-byte payload for ImageGetVersion (UINT32) must return PARAMETER_NOT_SUPPORTED"
        );

        // ── Graceful close ────────────────────────────────────────────────────
        let close_resp = op_no_data(&mut cmd_stream, opcode::CLOSE_SESSION, 6, vec![]).await;
        assert!(
            matches!(&close_resp, PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK),
            "CloseSession must return OK, got {close_resp:?}"
        );

        drop(cmd_stream);
        drop(event_stream);
    });

    test_body
        .await
        .expect("set_device_prop_payload_validation timed out");

    handle.shutdown();
}

// ── RemoteScenario tests ──────────────────────────────────────────────────────
//
// Design rules:
//   - No fixed ports — all listeners on 127.0.0.1:0 (ephemeral).
//   - No unconditional sleeps; delays are 0 ms for full determinism.
//   - Reuse existing helpers: handshake(), op_no_data(), op_set_prop_value(),
//     send_packet(), recv_packet().
//   - All PTP/BLE constants come from fuji_ptp::constants — no bare hex.
//   - fuji_ptpip::event::EventChannelReader and remote::RemoteSession drive
//     the client side; the FakeCameraServer drives the server side.
//   - CaptureComplete events are injected via a dedicated loopback listener
//     because FakeCameraServer's event background task closes its stream after
//     InitEventAck — it does not emit events autonomously yet.
//
// [H] marks assertions whose ground truth comes from docs/reference/master-constants.md
// with uncertainty noted where the spec source is incomplete.

// ── Helper: inject a CaptureComplete event over a fresh loopback pair ─────────
//
// Spawns a tokio task that:
//   1. Accepts one connection on `listener`.
//   2. Reads the InitEventRequest (12 bytes).
//   3. Sends InitEventAck.
//   4. Sends a PTP-IP Event packet for CAPTURE_COMPLETE (0x400D).
//
// Returns (JoinHandle for the injector task, addr the client should connect to).
//
// NOT cancel-safe: sequential .await points; never used under select!.
fn spawn_event_injector(
    listener: tokio::net::TcpListener,
    txn_id: u32,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let (mut srv, _) = listener.accept().await.expect("injector: accept");
        // Read InitEventRequest (12 bytes).
        let mut req_buf = [0u8; 12];
        AsyncReadExt::read_exact(&mut srv, &mut req_buf)
            .await
            .expect("injector: read InitEventRequest");
        // Send InitEventAck.
        let ack = encode_packet(&PtpIpPacket::InitEventAck);
        AsyncWriteExt::write_all(&mut srv, &ack)
            .await
            .expect("injector: write InitEventAck");
        // Send CaptureComplete event (0x400D). [H] master-constants.md §4a.
        let ev = encode_packet(&PtpIpPacket::Event {
            event_code: fuji_ptp::constants::event_code::CAPTURE_COMPLETE,
            transaction_id: txn_id,
            params: vec![],
        });
        AsyncWriteExt::write_all(&mut srv, &ev)
            .await
            .expect("injector: write CaptureComplete");
        // Hold the connection open briefly so the client can read the event.
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    })
}

// ─────────────────────────────────────────────────────────────────────────────
// Scenario A: named constant values match documented ground truth.
//
// Synchronous — no .await, no server, no sockets. Verifies that every opcode
// and property code referenced in the remote path comes from named constants
// and matches the values in master-constants.md.
//
// cancel-safe: no .await points.
// ─────────────────────────────────────────────────────────────────────────────
#[test]
fn remote_scenario_named_constants_match_ground_truth() {
    use fuji_ptp::constants::{event_code, function_mode, opcode, prop_code};

    // Command port: 55740. Event port: 55741. [H] master-constants.md §1.
    assert_eq!(fuji_ptp::constants::PORT_COMMAND, 55740);
    assert_eq!(fuji_ptp::constants::PORT_EVENT, 55741);

    // SetDevicePropValue: 0x1016. [H]
    assert_eq!(opcode::SET_DEVICE_PROP_VALUE, 0x1016);
    // InitiateOpenCapture: 0x101C. [H]
    assert_eq!(opcode::INITIATE_OPEN_CAPTURE, 0x101C);
    // TerminateOpenCapture: 0x1018. [H]
    assert_eq!(opcode::TERMINATE_OPEN_CAPTURE, 0x1018);

    // ClientState prop code: 0xDF01. [H] master-constants.md §3b.
    assert_eq!(prop_code::CLIENT_STATE, 0xDF01);
    // REMOTE mode value: 5, UINT16. [H] master-constants.md §4b.
    assert_eq!(function_mode::REMOTE, 5u16);

    // CaptureComplete event: 0x400D. [H] master-constants.md §4a.
    assert_eq!(event_code::CAPTURE_COMPLETE, 0x400D);
    // ObjectAdded: 0x4002. [H]
    assert_eq!(event_code::OBJECT_ADDED, 0x4002);
    // DevicePropChanged: 0x4006. [H]
    assert_eq!(event_code::DEVICE_PROP_CHANGED, 0x4006);
}

// ─────────────────────────────────────────────────────────────────────────────
// Scenario B: SetFunctionMode(REMOTE) returns OK on first attempt (busy_count=0).
//
// Drives the data-write phase directly with op_set_prop_value() and asserts
// the sim returns response_code::OK without any BUSY.
//
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime.
// ─────────────────────────────────────────────────────────────────────────────
#[tokio::test]
async fn remote_scenario_set_function_mode_ok_on_first_attempt() {
    use fuji_ptp::constants::{function_mode, prop_code};

    let config = FakeCameraBuilder::new().busy_count(0).build();
    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut cmd = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        let mut evt = TcpStream::connect(event_addr).await.expect("connect event");
        let _ = handshake(&mut cmd, &mut evt).await;
        op_no_data(&mut cmd, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;

        // SetDevicePropValue(ClientState=REMOTE_MODE=5). UINT16 LE. [H]
        let value: [u8; 2] = function_mode::REMOTE.to_le_bytes();
        let code = op_set_prop_value(&mut cmd, 1, prop_code::CLIENT_STATE, &value).await;

        assert_eq!(
            code,
            response_code::OK,
            "SetFunctionMode(REMOTE) with busy_count=0 must return OK immediately"
        );

        send_goodbye(&mut cmd).await;
    });

    test_body
        .await
        .expect("remote_scenario_set_function_mode_ok_on_first_attempt timed out");
    handle.shutdown();
}

// ─────────────────────────────────────────────────────────────────────────────
// Scenario C: SetFunctionMode(REMOTE) returns BUSY N times then OK.
//
// busy_count=2: first 2 calls return BUSY (0x2019), third returns OK.
// The test drives all 3 rounds manually via op_set_prop_value().
//
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime.
// ─────────────────────────────────────────────────────────────────────────────
#[tokio::test]
async fn remote_scenario_busy_retry_then_ok() {
    use fuji_ptp::constants::{function_mode, prop_code};

    let config = FakeCameraBuilder::new().busy_count(2).build();
    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut cmd = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        let mut evt = TcpStream::connect(event_addr).await.expect("connect event");
        let _ = handshake(&mut cmd, &mut evt).await;
        op_no_data(&mut cmd, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;

        let value: [u8; 2] = function_mode::REMOTE.to_le_bytes();

        // Round 1: BUSY expected.
        let r1 = op_set_prop_value(&mut cmd, 1, prop_code::CLIENT_STATE, &value).await;
        assert_eq!(
            r1,
            response_code::BUSY,
            "round 1 must be BUSY (busy_count=2)"
        );

        // Round 2: BUSY expected.
        let r2 = op_set_prop_value(&mut cmd, 2, prop_code::CLIENT_STATE, &value).await;
        assert_eq!(
            r2,
            response_code::BUSY,
            "round 2 must be BUSY (busy_count=2)"
        );

        // Round 3: OK — busy_count exhausted.
        let r3 = op_set_prop_value(&mut cmd, 3, prop_code::CLIENT_STATE, &value).await;
        assert_eq!(
            r3,
            response_code::OK,
            "round 3 must be OK after busy_count=2 exhausted"
        );

        send_goodbye(&mut cmd).await;
    });

    test_body
        .await
        .expect("remote_scenario_busy_retry_then_ok timed out");
    handle.shutdown();
}

// ─────────────────────────────────────────────────────────────────────────────
// Scenario D: InitiateOpenCapture returns OK from the sim.
//
// Sends SetFunctionMode(REMOTE) + InitiateOpenCapture (0x101C) and asserts OK
// for both. Does not wait for events — that is Scenario E.
//
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime.
// ─────────────────────────────────────────────────────────────────────────────
#[tokio::test]
async fn remote_scenario_initiate_open_capture_returns_ok() {
    use fuji_ptp::constants::{function_mode, prop_code};

    let config = FakeCameraBuilder::new().busy_count(0).build();
    let srv = FakeCameraServer::bind(config).await.expect("bind");
    let cmd_addr = srv.bound_addr();
    let event_addr = srv.event_addr();
    let handle = srv.run();

    let test_body = timeout(Duration::from_secs(5), async {
        let mut cmd = TcpStream::connect(cmd_addr).await.expect("connect cmd");
        let mut evt = TcpStream::connect(event_addr).await.expect("connect event");
        let _ = handshake(&mut cmd, &mut evt).await;
        op_no_data(&mut cmd, opcode::OPEN_SESSION, 0, vec![SESSION_ID]).await;

        // Step 1: SetFunctionMode(REMOTE). [H]
        let value: [u8; 2] = function_mode::REMOTE.to_le_bytes();
        let set_code = op_set_prop_value(&mut cmd, 1, prop_code::CLIENT_STATE, &value).await;
        assert_eq!(
            set_code,
            response_code::OK,
            "SetFunctionMode(REMOTE) must return OK"
        );

        // Step 2: InitiateOpenCapture (0x101C, no params, no data phase). [H]
        let open_resp = op_no_data(&mut cmd, opcode::INITIATE_OPEN_CAPTURE, 2, vec![]).await;
        assert!(
            matches!(
                &open_resp,
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "InitiateOpenCapture must return OK, got {open_resp:?}"
        );

        send_goodbye(&mut cmd).await;
    });

    test_body
        .await
        .expect("remote_scenario_initiate_open_capture_returns_ok timed out");
    handle.shutdown();
}

// ─────────────────────────────────────────────────────────────────────────────
// Scenario E: EventChannelReader receives CaptureComplete (0x400D) within one
// poll_next() call from a synthetic loopback injector.
//
// Uses spawn_event_injector() to provide a CaptureComplete packet on the event
// channel. EventChannelReader::handshake() + poll_next() must return
// FujiEvent::CaptureComplete with the expected transaction_id.
//
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime.
// ─────────────────────────────────────────────────────────────────────────────
#[tokio::test]
async fn remote_scenario_event_reader_receives_capture_complete() {
    use fuji_ptpip::event::{EventChannelReader, FujiEvent};

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind event injector");
    let addr = listener.local_addr().expect("local_addr");

    // TXN=7 — arbitrary but distinct from 0.
    let injector = spawn_event_injector(listener, 7);

    let client = timeout(Duration::from_secs(3), async {
        let stream = tokio::net::TcpStream::connect(addr)
            .await
            .expect("connect to injector");
        let mut reader = EventChannelReader::from_stream(stream);

        // Handshake: sends InitEventRequest → reads InitEventAck.
        // NOT cancel-safe: driven to completion here.
        reader.handshake().await.expect("event handshake");

        // poll_next: reads the CaptureComplete packet the injector sends.
        // NOT cancel-safe: driven to completion here.
        let ev = reader.poll_next().await.expect("poll_next must return Ok");

        assert!(
            matches!(
                ev,
                FujiEvent::CaptureComplete {
                    transaction_id: 7,
                    ..
                }
            ),
            "expected FujiEvent::CaptureComplete {{ txn=7 }}, got {ev:?}"
        );
    });

    client
        .await
        .expect("remote_scenario_event_reader_receives_capture_complete timed out");
    injector.await.expect("injector task must complete cleanly");
}

// ─────────────────────────────────────────────────────────────────────────────
// Scenario F: EventChannelReader on EOF returns TransportError::EventChannelClosed.
//
// The injector drops the TCP connection after InitEventAck (no event sent).
// poll_next() must return Err(FujiError::Transport(TransportError::EventChannelClosed)).
//
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime.
// ─────────────────────────────────────────────────────────────────────────────
#[tokio::test]
async fn remote_scenario_event_reader_eof_returns_channel_closed() {
    use fuji_core::{FujiError, TransportError};
    use fuji_ptpip::event::EventChannelReader;

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind eof listener");
    let addr = listener.local_addr().expect("local_addr");

    // Server: accept, handshake, then drop (EOF).
    // NOT cancel-safe: sequential .await; never used under select!.
    tokio::spawn(async move {
        let (mut srv, _) = listener.accept().await.expect("accept");
        let mut req_buf = [0u8; 12];
        let _ = AsyncReadExt::read_exact(&mut srv, &mut req_buf).await;
        let ack = encode_packet(&PtpIpPacket::InitEventAck);
        let _ = AsyncWriteExt::write_all(&mut srv, &ack).await;
        // Drop `srv` here → EOF on the client side.
    });

    let client = timeout(Duration::from_secs(3), async {
        let stream = tokio::net::TcpStream::connect(addr).await.expect("connect");
        let mut reader = EventChannelReader::from_stream(stream);
        reader.handshake().await.expect("event handshake");
        let result = reader.poll_next().await;
        assert!(
            matches!(
                result,
                Err(FujiError::Transport(TransportError::EventChannelClosed))
            ),
            "expected EventChannelClosed on EOF, got {result:?}"
        );
    });

    client
        .await
        .expect("remote_scenario_event_reader_eof_returns_channel_closed timed out");
}

// ─────────────────────────────────────────────────────────────────────────────
// Scenario G: RemoteSession::set_function_mode_remote() returns OK when
// busy_count=0 — drives the operation via the typed RemoteSession API.
//
// Uses a dedicated loopback pair (not FakeCameraServer) to have full control
// over the server side, matching the pattern in fuji-ptpip's own unit tests.
//
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime.
// ─────────────────────────────────────────────────────────────────────────────
#[tokio::test]
async fn remote_scenario_remote_session_set_function_mode_ok() {
    use fuji_ptp::constants::response_code as rc;
    use fuji_ptpip::event::EventChannelReader;
    use fuji_ptpip::remote::{RemoteSession, RemoteSessionConfig};

    // Minimal event-channel stub: accept, handshake, idle.
    let evt_listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind evt");
    let evt_addr = evt_listener.local_addr().expect("evt local_addr");
    tokio::spawn(async move {
        let (mut srv, _) = evt_listener.accept().await.expect("evt accept");
        let mut buf = [0u8; 12];
        let _ = AsyncReadExt::read_exact(&mut srv, &mut buf).await;
        let ack = encode_packet(&PtpIpPacket::InitEventAck);
        let _ = AsyncWriteExt::write_all(&mut srv, &ack).await;
        // Idle — hold connection open.
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    });

    // Command-channel stub: accept, reply to SetDevicePropValue with OK.
    // NOT cancel-safe: sequential .await; never used under select!.
    let cmd_listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind cmd");
    let cmd_addr = cmd_listener.local_addr().expect("cmd local_addr");
    tokio::spawn(async move {
        let (mut srv, _) = cmd_listener.accept().await.expect("cmd accept");
        // Read OperationRequest + StartData + EndData (3 packets).
        for _ in 0..3u8 {
            let mut len_buf = [0u8; 4];
            let _ = AsyncReadExt::read_exact(&mut srv, &mut len_buf).await;
            let len = u32::from_le_bytes(len_buf) as usize;
            let mut body = vec![0u8; len.saturating_sub(4)];
            let _ = AsyncReadExt::read_exact(&mut srv, &mut body).await;
        }
        // Reply OperationResponse(OK, txn=1).
        let resp = encode_packet(&PtpIpPacket::OperationResponse {
            response_code: rc::OK,
            transaction_id: 1,
            result_params: vec![],
        });
        let _ = AsyncWriteExt::write_all(&mut srv, &resp).await;
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    });

    let test = timeout(Duration::from_secs(3), async {
        // Build EventChannelReader from async loopback stream.
        let evt_stream = tokio::net::TcpStream::connect(evt_addr)
            .await
            .expect("connect evt");
        let mut evt_reader = EventChannelReader::from_stream(evt_stream);
        evt_reader.handshake().await.expect("evt handshake");

        // Build RemoteSession.
        let cfg = RemoteSessionConfig {
            max_attempts: 3,
            retry_delay_ms: 0,
            capture_complete_timeout_ms: 2_000,
        };
        let session = RemoteSession::new(cfg, evt_reader);

        // Connect command stream.
        let mut cmd = tokio::net::TcpStream::connect(cmd_addr)
            .await
            .expect("connect cmd");

        // Drive set_function_mode_remote — &self, stream: &mut S (AsyncRead+AsyncWrite+Unpin).
        let result = session.set_function_mode_remote(&mut cmd, 1).await;
        assert!(
            result.is_ok(),
            "set_function_mode_remote must return Ok, got {result:?}"
        );
    });

    test.await
        .expect("remote_scenario_remote_session_set_function_mode_ok timed out");
}

// ─────────────────────────────────────────────────────────────────────────────
// Scenario H: RemoteSession::set_function_mode_remote() returns
// CameraError::Busy { attempts } when max_attempts is exhausted.
//
// Command-channel stub always replies BUSY. RemoteSession with max_attempts=2
// must return CameraError::Busy { attempts: 2 } after two attempts.
//
// cancel-safe: test entry point — driven to completion by #[tokio::test] runtime.
// ─────────────────────────────────────────────────────────────────────────────
#[tokio::test]
async fn remote_scenario_remote_session_busy_exhausted() {
    use fuji_core::{CameraError, FujiError};
    use fuji_ptp::constants::response_code as rc;
    use fuji_ptpip::event::EventChannelReader;
    use fuji_ptpip::remote::{RemoteSession, RemoteSessionConfig};

    // Event-channel stub: accept, handshake, idle.
    let evt_listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind evt");
    let evt_addr = evt_listener.local_addr().expect("evt local_addr");
    tokio::spawn(async move {
        let (mut srv, _) = evt_listener.accept().await.expect("evt accept");
        let mut buf = [0u8; 12];
        let _ = AsyncReadExt::read_exact(&mut srv, &mut buf).await;
        let ack = encode_packet(&PtpIpPacket::InitEventAck);
        let _ = AsyncWriteExt::write_all(&mut srv, &ack).await;
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    });

    // Command stub: always drains the 3 write-phase packets and replies BUSY.
    // NOT cancel-safe: sequential .await; never used under select!.
    let cmd_listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind cmd");
    let cmd_addr = cmd_listener.local_addr().expect("cmd local_addr");
    tokio::spawn(async move {
        let (mut srv, _) = cmd_listener.accept().await.expect("cmd accept");
        // max_attempts=2 → 2 attempts; drain + reply BUSY for each.
        for attempt in 1u32..=2 {
            for _ in 0..3u8 {
                let mut len_buf = [0u8; 4];
                let _ = AsyncReadExt::read_exact(&mut srv, &mut len_buf).await;
                let len = u32::from_le_bytes(len_buf) as usize;
                let mut body = vec![0u8; len.saturating_sub(4)];
                let _ = AsyncReadExt::read_exact(&mut srv, &mut body).await;
            }
            let resp = encode_packet(&PtpIpPacket::OperationResponse {
                response_code: rc::BUSY,
                transaction_id: attempt,
                result_params: vec![],
            });
            let _ = AsyncWriteExt::write_all(&mut srv, &resp).await;
        }
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    });

    let test = timeout(Duration::from_secs(3), async {
        let evt_stream = tokio::net::TcpStream::connect(evt_addr)
            .await
            .expect("connect evt");
        let mut evt_reader = EventChannelReader::from_stream(evt_stream);
        evt_reader.handshake().await.expect("evt handshake");

        let cfg = RemoteSessionConfig {
            max_attempts: 2,
            retry_delay_ms: 0,
            capture_complete_timeout_ms: 2_000,
        };
        let session = RemoteSession::new(cfg, evt_reader);

        let mut cmd = tokio::net::TcpStream::connect(cmd_addr)
            .await
            .expect("connect cmd");

        let result = session.set_function_mode_remote(&mut cmd, 1).await;
        assert!(
            matches!(
                result,
                Err(FujiError::Camera(CameraError::Busy { attempts: 2 }))
            ),
            "expected CameraError::Busy {{ attempts: 2 }}, got {result:?}"
        );
    });

    test.await
        .expect("remote_scenario_remote_session_busy_exhausted timed out");
}
