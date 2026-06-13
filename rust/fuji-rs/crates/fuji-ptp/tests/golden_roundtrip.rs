//! Golden fixture roundtrip tests for PTP-IP packet codec.
//!
//! For each `.hex` fixture under `tests/golden/ptp/`:
//!   1. Parse hex text to bytes.
//!   2. Decode bytes → `PtpIpPacket`.
//!   3. Re-encode → bytes.
//!   4. Assert encoded bytes == fixture bytes (canonical form).
//!   5. Decode the re-encoded bytes and assert structural equality with step-2 result.
//!
//! The fixtures are hand-authored from clean-room constants; see README.md in the
//! same directory for byte-by-byte provenance.

use fuji_ptp::{decode_packet, encode_packet};

// ── Hex parser ────────────────────────────────────────────────────────────────

/// Parses a whitespace-separated hex string (e.g. `"08 00 00 00 04 00 00 00"`)
/// into a `Vec<u8>`. Ignores newlines and multiple spaces.
fn parse_hex_fixture(text: &str) -> Vec<u8> {
    text.split_ascii_whitespace()
        .map(|tok| {
            u8::from_str_radix(tok, 16)
                .unwrap_or_else(|_| panic!("invalid hex token in fixture: {tok:?}"))
        })
        .collect()
}

// ── Core golden assertion ─────────────────────────────────────────────────────

/// Asserts the full golden roundtrip contract for one fixture:
/// - `decode(bytes)` succeeds.
/// - `encode(decode(bytes))` produces bytes identical to the fixture.
/// - `decode(encode(decode(bytes)))` equals `decode(bytes)` structurally.
fn assert_golden_roundtrip(fixture_name: &str, bytes: &[u8]) {
    let packet = decode_packet(bytes).unwrap_or_else(|e| {
        panic!("golden fixture {fixture_name}: decode failed: {e:?}");
    });

    let re_encoded = encode_packet(&packet);

    assert_eq!(
        re_encoded, bytes,
        "golden fixture {fixture_name}: encode(decode(bytes)) != fixture bytes"
    );

    let re_decoded = decode_packet(&re_encoded).unwrap_or_else(|e| {
        panic!("golden fixture {fixture_name}: re-decode after encode failed: {e:?}");
    });

    assert_eq!(
        re_decoded, packet,
        "golden fixture {fixture_name}: decode(encode(decode(bytes))) != decode(bytes)"
    );
}

// ── Individual golden tests ───────────────────────────────────────────────────

#[test]
fn golden_init_command_req() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/init_command_req.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 82, "init_command_req.hex must be 82 bytes");
    assert_golden_roundtrip("init_command_req.hex", &bytes);
}

#[test]
fn golden_init_command_ack() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/init_command_ack.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 68, "init_command_ack.hex must be 68 bytes");
    assert_golden_roundtrip("init_command_ack.hex", &bytes);
}

#[test]
fn golden_init_event_req() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/init_event_req.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 12, "init_event_req.hex must be 12 bytes");
    assert_golden_roundtrip("init_event_req.hex", &bytes);
}

#[test]
fn golden_init_event_ack() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/init_event_ack.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 8, "init_event_ack.hex must be 8 bytes");
    assert_golden_roundtrip("init_event_ack.hex", &bytes);
}

#[test]
fn golden_operation_request() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/operation_request.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 22, "operation_request.hex must be 22 bytes");
    assert_golden_roundtrip("operation_request.hex", &bytes);
}

#[test]
fn golden_operation_response() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/operation_response.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 14, "operation_response.hex must be 14 bytes");
    assert_golden_roundtrip("operation_response.hex", &bytes);
}

#[test]
fn golden_event() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/event.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 14, "event.hex must be 14 bytes");
    // Verify the packet_type byte is 0x08, not 0x0C.
    assert_eq!(bytes[4], 0x08, "event.hex packet_type[0] must be 0x08");
    assert_eq!(bytes[5], 0x00, "event.hex packet_type[1] must be 0x00");
    assert_golden_roundtrip("event.hex", &bytes);
}

#[test]
fn golden_start_data() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/start_data.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 20, "start_data.hex must be 20 bytes");
    assert_golden_roundtrip("start_data.hex", &bytes);
}

#[test]
fn golden_data_packet() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/data_packet.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 16, "data_packet.hex must be 16 bytes");
    assert_golden_roundtrip("data_packet.hex", &bytes);
}

#[test]
fn golden_end_data() {
    let raw = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/golden/ptp/end_data.hex"
    ));
    let bytes = parse_hex_fixture(raw);
    assert_eq!(bytes.len(), 16, "end_data.hex must be 16 bytes");
    // Verify the packet_type byte is 0x0C (DataPacketEnd), not 0x08 (Event).
    assert_eq!(bytes[4], 0x0C, "end_data.hex packet_type[0] must be 0x0C");
    assert_golden_roundtrip("end_data.hex", &bytes);
}

// ── Omnibus golden test (name contains "golden" per spec requirement) ─────────

/// Runs all golden fixtures in sequence. The function name contains "golden"
/// as required by the task specification. Individual per-fixture tests above
/// provide granular failure messages; this omnibus verifies all pass together.
#[test]
fn golden_all_fixtures_roundtrip() {
    struct Fixture {
        name: &'static str,
        content: &'static str,
        expected_len: usize,
    }

    let fixtures = [
        Fixture {
            name: "init_command_req.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/init_command_req.hex"
            )),
            expected_len: 82,
        },
        Fixture {
            name: "init_command_ack.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/init_command_ack.hex"
            )),
            expected_len: 68,
        },
        Fixture {
            name: "init_event_req.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/init_event_req.hex"
            )),
            expected_len: 12,
        },
        Fixture {
            name: "init_event_ack.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/init_event_ack.hex"
            )),
            expected_len: 8,
        },
        Fixture {
            name: "operation_request.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/operation_request.hex"
            )),
            expected_len: 22,
        },
        Fixture {
            name: "operation_response.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/operation_response.hex"
            )),
            expected_len: 14,
        },
        Fixture {
            name: "event.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/event.hex"
            )),
            expected_len: 14,
        },
        Fixture {
            name: "start_data.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/start_data.hex"
            )),
            expected_len: 20,
        },
        Fixture {
            name: "data_packet.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/data_packet.hex"
            )),
            expected_len: 16,
        },
        Fixture {
            name: "end_data.hex",
            content: include_str!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/tests/golden/ptp/end_data.hex"
            )),
            expected_len: 16,
        },
    ];

    for f in &fixtures {
        let bytes = parse_hex_fixture(f.content);
        assert_eq!(
            bytes.len(),
            f.expected_len,
            "fixture {} has wrong byte count",
            f.name
        );
        assert_golden_roundtrip(f.name, &bytes);
    }
}
