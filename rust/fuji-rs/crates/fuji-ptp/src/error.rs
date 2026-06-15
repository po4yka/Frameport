//! Codec-level errors for PTP-IP packet encoding and decoding.
//!
//! These errors describe wire-format problems (truncated buffer, unknown type
//! code, invalid UTF-16) rather than session or transport state. Domain-level
//! errors belong in `fuji-core::FujiError`.

use thiserror::Error;

/// Errors that can occur while encoding or decoding a PTP-IP packet.
#[derive(Clone, Debug, Eq, PartialEq, Error)]
pub enum PtpCodecError {
    /// Buffer is shorter than the minimum required for the operation.
    #[error("packet too short: expected {expected} bytes, got {got}")]
    PacketTooShort { expected: usize, got: usize },

    /// The `packet_type` field in the header does not correspond to any known type.
    #[error("unknown PTP-IP packet type: 0x{0:04X}")]
    UnknownPacketType(u32),

    /// A UTF-16LE device-name or PTP string field contained an invalid code unit sequence.
    #[error("invalid UTF-16 in string field")]
    InvalidUtf16,

    /// An arithmetic overflow occurred while computing a length (e.g., a declared length
    /// exceeds `usize::MAX`).
    #[error("length field overflows addressable range")]
    LengthOverflow,
}
