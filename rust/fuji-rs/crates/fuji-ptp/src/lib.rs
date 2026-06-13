//! `fuji-ptp` — PTP-IP container codec for Frameport.
//!
//! This crate is **pure protocol**: it encodes and decodes PTP-IP wire packets and
//! Fujifilm transport frames. It has no knowledge of sockets, transports, Android
//! platform APIs, or JNI.
//!
//! # Module layout
//!
//! | Module | Responsibility |
//! |--------|----------------|
//! | [`error`] | [`PtpCodecError`] — wire-format error type |
//! | [`header`] | 8-byte common PTP-IP header encode/decode |
//! | [`packet`] | [`PtpIpPacketType`] enum (all 14 codes) and [`PtpIpPacket`] enum |
//! | [`string`] | PTP string and fixed 54-byte device-name helpers |
//! | [`array`] | PTP u32 array encode/decode |
//! | [`framing`] | Fuji length-prefix frame (section 2) and Control Message (section 3) |
//! | [`constants`] | Protocol constants: opcodes, response codes, prop codes, ports, magic |
//!
//! # Primary entry points
//!
//! ```rust
//! use fuji_ptp::{decode_packet, encode_packet, PtpIpPacket, PtpCodecError};
//!
//! // Decode
//! let buf: &[u8] = &[0x08, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00];
//! let pkt = decode_packet(buf).unwrap();
//! assert_eq!(pkt, PtpIpPacket::InitEventAck);
//!
//! // Encode
//! let encoded = encode_packet(&PtpIpPacket::InitEventAck);
//! assert_eq!(encoded, buf);
//! ```
//!
//! # Safety
//!
//! This crate contains no `unsafe` code.

pub mod array;
pub mod constants;
pub mod error;
pub mod framing;
pub mod header;
pub mod packet;
pub mod string;

// ── Public re-exports ─────────────────────────────────────────────────────────

pub use array::{decode_u32_array, encode_u32_array};
pub use error::PtpCodecError;
pub use framing::{
    FujiControlMessage, FujiFrame, decode_control_message, decode_fuji_frame,
    encode_control_message, encode_fuji_frame,
};
pub use packet::{PtpIpPacket, PtpIpPacketType, decode_packet, encode_packet};
pub use string::{
    DEVICE_NAME_FIELD_BYTES, decode_device_name, decode_ptp_string, encode_device_name,
    encode_ptp_string,
};

#[cfg(test)]
mod integration_tests {
    use super::*;

    /// A full Init Command Request encode/decode cycle using the Fujifilm magic version.
    #[test]
    fn init_command_request_with_fuji_magic_version() {
        let pkt = PtpIpPacket::InitCommandRequest {
            version: constants::FUJI_PTPIP_VERSION,
            guid: [
                0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB,
                0xCD, 0xEF,
            ],
            device_name: "Frameport".to_owned(),
        };
        let encoded = encode_packet(&pkt);
        // Section 4.2: always 82 bytes.
        assert_eq!(encoded.len(), 82);
        // First 4 bytes = length = 82 LE.
        assert_eq!(
            u32::from_le_bytes([encoded[0], encoded[1], encoded[2], encoded[3]]),
            82
        );
        // Bytes 4..8 = packet type = 0x0001 LE.
        assert_eq!(
            u32::from_le_bytes([encoded[4], encoded[5], encoded[6], encoded[7]]),
            0x0001
        );
        // Bytes 8..12 = version = FUJI_PTPIP_VERSION LE.
        assert_eq!(
            u32::from_le_bytes([encoded[8], encoded[9], encoded[10], encoded[11]]),
            constants::FUJI_PTPIP_VERSION
        );
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    /// An OpenSession operation request (txn_id = 0, opcode = 0x1002, param = 1).
    #[test]
    fn open_session_request_encode() {
        let pkt = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: constants::opcode::OPEN_SESSION,
            transaction_id: 0,
            params: vec![constants::SESSION_ID],
        };
        let encoded = encode_packet(&pkt);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    /// Goodbye packet bytes match the spec (section 4.10).
    #[test]
    fn goodbye_packet_bytes() {
        assert_eq!(
            constants::GOODBYE_PACKET,
            [0x08, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF]
        );
    }

    /// decode_packet never panics on adversarial inputs.
    #[test]
    fn decode_never_panics_on_adversarial_inputs() {
        let inputs: &[&[u8]] = &[
            &[],                                               // empty
            &[0xFF],                                           // 1 byte
            &[0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF], // all-0xFF (unknown type)
            &[0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00], // type 1 but only header
            &[0x04, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00], // declared length < 8
            &[0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00], // declared length = 0
            // Oversized declared length that would overflow if not checked:
            &[0xFF, 0xFF, 0xFF, 0x7F, 0x04, 0x00, 0x00, 0x00],
        ];
        for input in inputs {
            // Must not panic — errors are fine.
            let _ = decode_packet(input);
        }
    }

    /// Event packet type code is 0x0008, NOT 0x000C.
    #[test]
    fn event_packet_type_code_is_0x0008_not_0x000c() {
        let pkt = PtpIpPacket::Event {
            event_code: 0x4006,
            transaction_id: 1,
            params: vec![],
        };
        assert_eq!(
            PtpIpPacketType::Event.to_code(),
            0x0008,
            "Event packet type must be 0x0008 per ptp-ptpip.md section 4.1"
        );
        let encoded = encode_packet(&pkt);
        let type_code = u32::from_le_bytes([encoded[4], encoded[5], encoded[6], encoded[7]]);
        assert_eq!(type_code, 0x0008);
        assert_ne!(type_code, 0x000C, "0x000C is DataPacketEnd, not Event");
    }

    /// DataPacketEnd type code is 0x000C.
    #[test]
    fn data_packet_end_type_code_is_0x000c() {
        assert_eq!(PtpIpPacketType::DataPacketEnd.to_code(), 0x000C);
    }

    /// All 14 packet type codes from ptp-ptpip.md section 4.1 are present.
    #[test]
    fn all_14_packet_type_codes_present() {
        let expected_codes: &[u32] = &[
            0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x0008, 0x0009, 0x000A, 0x000B,
            0x000C, 0x000D, 0x000E,
        ];
        for &code in expected_codes {
            assert!(
                PtpIpPacketType::from_code(code).is_some(),
                "packet type code 0x{code:04X} not found"
            );
        }
    }

    /// Framing + control message full pipeline roundtrip.
    #[test]
    fn framing_pipeline_roundtrip() {
        let msg = FujiControlMessage {
            index: 1,
            message_type: 0x1002,
            transaction_id: 1,
            payload: vec![0x01, 0x00, 0x00, 0x00],
        };
        let body = encode_control_message(&msg);
        let frame = encode_fuji_frame(&body);
        let decoded_frame = decode_fuji_frame(&frame).unwrap();
        let decoded_msg = decode_control_message(&decoded_frame.body).unwrap();
        assert_eq!(decoded_msg, msg);
    }
}
