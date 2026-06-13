//! Common 8-byte PTP-IP packet header.
//!
//! Every PTP-IP packet begins with two 32-bit little-endian fields (section 4.1):
//!
//! | Offset | Width | Field |
//! |--------|-------|-------|
//! | 0      | u32   | `length` — total byte count of the packet, including this header |
//! | 4      | u32   | `packet_type` — see [`PtpIpPacketType`] |
//!
//! The minimum valid packet is the 8-byte header alone (e.g., Init Event Ack, Ping, Pong).

use crate::error::PtpCodecError;
use crate::packet::PtpIpPacketType;

/// The fixed size of the common PTP-IP header in bytes.
pub const HEADER_SIZE: usize = 8;

/// Decoded common PTP-IP header.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PtpIpHeader {
    /// Total packet length in bytes, including this header.
    pub length: u32,
    /// Packet type code.
    pub packet_type: u32,
}

impl PtpIpHeader {
    /// Decodes the 8-byte header from the start of `buf`.
    ///
    /// # Errors
    /// Returns [`PtpCodecError::PacketTooShort`] if `buf.len() < 8`.
    pub fn decode(buf: &[u8]) -> Result<Self, PtpCodecError> {
        if buf.len() < HEADER_SIZE {
            return Err(PtpCodecError::PacketTooShort {
                expected: HEADER_SIZE,
                got: buf.len(),
            });
        }
        let length = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
        let packet_type = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);
        Ok(Self {
            length,
            packet_type,
        })
    }

    /// Encodes this header into 8 bytes appended to `out`.
    pub fn encode_into(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.length.to_le_bytes());
        out.extend_from_slice(&self.packet_type.to_le_bytes());
    }

    /// Constructs a header for the given `packet_type` and `total_length`.
    pub fn new(packet_type: PtpIpPacketType, total_length: u32) -> Self {
        Self {
            length: total_length,
            packet_type: packet_type.to_code(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_valid_header() {
        // length = 0x0C (12), type = 0x0003 (InitEventRequest)
        let buf = [0x0C, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00];
        let hdr = PtpIpHeader::decode(&buf).unwrap();
        assert_eq!(hdr.length, 12);
        assert_eq!(hdr.packet_type, 0x0003);
    }

    #[test]
    fn decode_too_short_returns_error() {
        let buf = [0x0C, 0x00, 0x00];
        assert!(matches!(
            PtpIpHeader::decode(&buf),
            Err(PtpCodecError::PacketTooShort {
                expected: 8,
                got: 3
            })
        ));
    }

    #[test]
    fn encode_decode_roundtrip() {
        let hdr = PtpIpHeader {
            length: 20,
            packet_type: 0x0009,
        };
        let mut buf = Vec::new();
        hdr.encode_into(&mut buf);
        let decoded = PtpIpHeader::decode(&buf).unwrap();
        assert_eq!(decoded, hdr);
    }
}
