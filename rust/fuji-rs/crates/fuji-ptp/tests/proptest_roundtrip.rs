//! Property-based roundtrip tests for the PTP-IP packet codec.
//!
//! For every packet type: generate arbitrary valid field values, encode, decode,
//! assert structural equality. Also asserts `decode_packet` never panics on
//! arbitrary byte vectors.
//!
//! Uses proptest 1.x. Run with: `cargo test -p fuji-ptp --locked`.

use fuji_ptp::{PtpIpPacket, decode_packet, encode_packet};
use proptest::prelude::*;

// ── Strategies ────────────────────────────────────────────────────────────────

/// Strategy for a 16-byte GUID array.
fn arb_guid() -> impl Strategy<Value = [u8; 16]> {
    prop::array::uniform16(any::<u8>())
}

/// Strategy for a short ASCII device name (avoids encoding edge-cases irrelevant to codec).
fn arb_device_name() -> impl Strategy<Value = String> {
    // Up to 26 visible ASCII chars (the max the 54-byte field can hold).
    "[A-Za-z0-9 ]{0,26}".prop_map(|s| s)
}

/// Strategy for a short camera name (up to 21 chars, the encode_camera_name limit).
fn arb_camera_name() -> impl Strategy<Value = String> {
    "[A-Za-z0-9 \\-]{0,21}".prop_map(|s| s)
}

/// Strategy for up to 5 u32 params.
fn arb_params(max: usize) -> impl Strategy<Value = Vec<u32>> {
    prop::collection::vec(any::<u32>(), 0..=max)
}

// ── InitCommandRequest ────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_init_command_request_roundtrip(
        version in any::<u32>(),
        guid in arb_guid(),
        device_name in arb_device_name(),
    ) {
        let pkt = PtpIpPacket::InitCommandRequest { version, guid, device_name };
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 82);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── InitCommandAck ────────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_init_command_ack_roundtrip(
        guid in arb_guid(),
        camera_name in arb_camera_name(),
    ) {
        let pkt = PtpIpPacket::InitCommandAck { guid, camera_name };
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 68);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── InitEventRequest ──────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_init_event_request_roundtrip(connection_number in any::<u32>()) {
        let pkt = PtpIpPacket::InitEventRequest { connection_number };
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 12);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── InitEventAck ─────────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_init_event_ack_roundtrip(_dummy in any::<u8>()) {
        let pkt = PtpIpPacket::InitEventAck;
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 8);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── InitFail ──────────────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_init_fail_roundtrip(raw_payload in prop::collection::vec(any::<u8>(), 0..64)) {
        let pkt = PtpIpPacket::InitFail { raw_payload };
        let encoded = encode_packet(&pkt);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── OperationRequest ──────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_operation_request_roundtrip(
        data_phase in any::<u32>(),
        opcode in any::<u16>(),
        transaction_id in any::<u32>(),
        params in arb_params(5),
    ) {
        let n = params.len().min(5);
        let params_truncated = params[..n].to_vec();
        let pkt = PtpIpPacket::OperationRequest {
            data_phase,
            opcode,
            transaction_id,
            params: params_truncated.clone(),
        };
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 18 + n * 4);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── OperationResponse ─────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_operation_response_roundtrip(
        response_code in any::<u16>(),
        transaction_id in any::<u32>(),
        result_params in arb_params(5),
    ) {
        let pkt = PtpIpPacket::OperationResponse {
            response_code,
            transaction_id,
            result_params: result_params.clone(),
        };
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 14 + result_params.len() * 4);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── Event ─────────────────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_event_roundtrip(
        event_code in any::<u16>(),
        transaction_id in any::<u32>(),
        params in arb_params(5),
    ) {
        let pkt = PtpIpPacket::Event { event_code, transaction_id, params: params.clone() };
        let encoded = encode_packet(&pkt);
        // Verify packet_type byte is 0x08 (not 0x0C).
        prop_assert_eq!(encoded[4], 0x08u8);
        prop_assert_eq!(encoded.len(), 14 + params.len() * 4);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── StartData ─────────────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_start_data_roundtrip(
        transaction_id in any::<u32>(),
        total_data_length in any::<u64>(),
    ) {
        let pkt = PtpIpPacket::StartData { transaction_id, total_data_length };
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 20);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── DataPacket ────────────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_data_packet_roundtrip(
        transaction_id in any::<u32>(),
        payload in prop::collection::vec(any::<u8>(), 0..1024),
    ) {
        let pkt = PtpIpPacket::DataPacket { transaction_id, payload: payload.clone() };
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 12 + payload.len());
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── CancelTransaction ─────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_cancel_transaction_roundtrip(
        raw_payload in prop::collection::vec(any::<u8>(), 0..64),
    ) {
        let pkt = PtpIpPacket::CancelTransaction { raw_payload };
        let encoded = encode_packet(&pkt);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── EndData ───────────────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_end_data_roundtrip(
        transaction_id in any::<u32>(),
        payload in prop::collection::vec(any::<u8>(), 0..1024),
    ) {
        let pkt = PtpIpPacket::EndData { transaction_id, payload: payload.clone() };
        let encoded = encode_packet(&pkt);
        prop_assert_eq!(encoded.len(), 12 + payload.len());
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── Ping / Pong ───────────────────────────────────────────────────────────────

proptest! {
    #[test]
    fn prop_ping_roundtrip(raw_payload in prop::collection::vec(any::<u8>(), 0..32)) {
        let pkt = PtpIpPacket::Ping { raw_payload };
        let encoded = encode_packet(&pkt);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

proptest! {
    #[test]
    fn prop_pong_roundtrip(raw_payload in prop::collection::vec(any::<u8>(), 0..32)) {
        let pkt = PtpIpPacket::Pong { raw_payload };
        let encoded = encode_packet(&pkt);
        let decoded = decode_packet(&encoded).unwrap();
        prop_assert_eq!(decoded, pkt);
    }
}

// ── decode_packet never panics on arbitrary bytes ─────────────────────────────

proptest! {
    #[test]
    fn prop_decode_never_panics(data in prop::collection::vec(any::<u8>(), 0..256)) {
        // Must not panic — Ok or Err are both acceptable.
        let _ = decode_packet(&data);
    }
}

proptest! {
    #[test]
    fn prop_decode_never_panics_exact_8_bytes(data in prop::array::uniform8(any::<u8>())) {
        let _ = decode_packet(&data);
    }
}

proptest! {
    #[test]
    fn prop_decode_never_panics_large_inputs(
        data in prop::collection::vec(any::<u8>(), 0..4096),
    ) {
        let _ = decode_packet(&data);
    }
}
