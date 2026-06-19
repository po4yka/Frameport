//! Fuji transport framing layers (ptp-ptpip.md sections 2 and 3).
//!
//! ## Layer 1 — Fuji length-prefix frame (section 2)
//!
//! Every control message on the Fuji command channel is wrapped in a 4-byte
//! length-prefix frame:
//!
//! | Offset | Width | Field |
//! |--------|-------|-------|
//! | 0      | u32 LE | Total frame length **including** this 4-byte field |
//! | 4      | body  | Message body (see section 3) |
//!
//! The special value `0xFFFFFFFF` at offset 0 signals "camera busy / error".
//!
//! ## Layer 2 — Fuji Control Message Container (section 3)
//!
//! After stripping the 4-byte length prefix, each message body has:
//!
//! | Offset | Width | Field |
//! |--------|-------|-------|
//! | 0      | u16 LE | `index` — 1=normal, 2=second part of two-part msg, 0=terminate |
//! | 2      | u16 LE | `message_type` — Fuji opcode |
//! | 4      | u32 LE | `transaction_id` |
//! | 8      | N     | `payload` — opcode-specific bytes |

use crate::error::PtpCodecError;

// ── Layer 1: Fuji length-prefix framing ──────────────────────────────────────

/// The sentinel value that signals "camera busy or error" (section 2).
pub const FUJI_FRAME_BUSY_SENTINEL: u32 = 0xFFFF_FFFF;

/// The minimum valid frame length (must be at least 4 — the length field itself).
const FUJI_FRAME_MIN_LENGTH: u32 = 4;

/// A decoded Fuji length-prefix frame.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FujiFrame {
    /// The body bytes (after stripping the 4-byte length prefix).
    pub body: Vec<u8>,
}

/// Encodes `body` into a Fuji length-prefix frame.
///
/// The returned buffer is `4 + body.len()` bytes: a `u32 LE` length field
/// (value = `4 + body.len()`) followed by `body`.
///
/// # Panics
/// Panics in debug builds if `4 + body.len()` exceeds `u32::MAX` (a frame
/// larger than ~4 GiB is a programming error; the Fuji protocol does not
/// support frames this large). In release builds the length field saturates
/// to `u32::MAX`, which the camera will reject — the caller is responsible
/// for not constructing oversized frames.
pub fn encode_fuji_frame(body: &[u8]) -> Vec<u8> {
    let total = 4_usize + body.len();
    // Fuji frames use a u32 LE length field; frames >4 GiB are not valid.
    debug_assert!(
        total <= u32::MAX as usize,
        "encode_fuji_frame: frame length {total} overflows u32"
    );
    let total_u32 = u32::try_from(total).unwrap_or(u32::MAX);
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(&total_u32.to_le_bytes());
    out.extend_from_slice(body);
    out
}

/// Decodes a Fuji length-prefix frame from `buf`.
///
/// Returns a [`FujiFrame`] containing the body bytes (not including the 4-byte
/// length prefix).
///
/// # Length validation
/// 1. `buf.len()` must be >= 4 to read the length field.
/// 2. The declared `length` must be >= 4 (the length field is included in the count).
/// 3. `buf.len()` must be >= `length` (the buffer must contain the complete frame).
///
/// Steps 2 and 3 guarantee that no slice indexing can panic.
///
/// # Errors
/// - [`PtpCodecError::PacketTooShort`] if `buf.len() < 4`.
/// - [`PtpCodecError::PacketTooShort`] if declared `length < 4`.
/// - [`PtpCodecError::PacketTooShort`] if `buf.len() < length`.
/// - [`PtpCodecError::LengthOverflow`] if `length` overflows `usize`.
pub fn decode_fuji_frame(buf: &[u8]) -> Result<FujiFrame, PtpCodecError> {
    // Step 1: need at least 4 bytes to read the length field.
    if buf.len() < 4 {
        return Err(PtpCodecError::PacketTooShort {
            expected: 4,
            got: buf.len(),
        });
    }

    let length = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);

    // Step 2: declared length must be >= 4 (it includes the 4-byte prefix itself).
    if length < FUJI_FRAME_MIN_LENGTH {
        return Err(PtpCodecError::PacketTooShort {
            expected: FUJI_FRAME_MIN_LENGTH as usize,
            got: length as usize,
        });
    }

    // Step 3: convert to usize safely.
    let frame_len = usize::try_from(length).map_err(|_| PtpCodecError::LengthOverflow)?;

    // Step 4: the buffer must contain the whole frame.
    if buf.len() < frame_len {
        return Err(PtpCodecError::PacketTooShort {
            expected: frame_len,
            got: buf.len(),
        });
    }

    // Safe: frame_len >= 4 and buf.len() >= frame_len.
    let body = buf[4..frame_len].to_vec();
    Ok(FujiFrame { body })
}

// ── Layer 2: Fuji Control Message Container ───────────────────────────────────

/// A decoded Fuji Control Message (after stripping the layer-1 length prefix).
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FujiControlMessage {
    /// Message index: `1` = normal single-part; `2` = second part of two-part message;
    /// `0` = terminate sentinel.
    pub index: u16,
    /// Fuji opcode / message type.
    pub message_type: u16,
    /// Transaction ID (monotonically incrementing; shared across two-part messages).
    pub transaction_id: u32,
    /// Opcode-specific payload bytes.
    pub payload: Vec<u8>,
}

/// The fixed header size of the Fuji Control Message body (without the outer frame prefix).
const CONTROL_HEADER_SIZE: usize = 8;

/// Decodes a [`FujiControlMessage`] from the body bytes of a Fuji frame.
///
/// `body` is the bytes following the 4-byte length prefix (i.e., `FujiFrame::body`).
///
/// # Errors
/// - [`PtpCodecError::PacketTooShort`] if `body.len() < 8`.
pub fn decode_control_message(body: &[u8]) -> Result<FujiControlMessage, PtpCodecError> {
    if body.len() < CONTROL_HEADER_SIZE {
        return Err(PtpCodecError::PacketTooShort {
            expected: CONTROL_HEADER_SIZE,
            got: body.len(),
        });
    }

    let index = u16::from_le_bytes([body[0], body[1]]);
    let message_type = u16::from_le_bytes([body[2], body[3]]);
    let transaction_id = u32::from_le_bytes([body[4], body[5], body[6], body[7]]);
    let payload = body[CONTROL_HEADER_SIZE..].to_vec();

    Ok(FujiControlMessage {
        index,
        message_type,
        transaction_id,
        payload,
    })
}

/// Encodes a [`FujiControlMessage`] into its wire bytes (the layer-2 body,
/// **not** including the outer layer-1 length prefix).
///
/// To obtain a complete framed message ready to send on the command channel,
/// pass the result to [`encode_fuji_frame`].
pub fn encode_control_message(msg: &FujiControlMessage) -> Vec<u8> {
    let total = CONTROL_HEADER_SIZE + msg.payload.len();
    let mut out = Vec::with_capacity(total);
    out.extend_from_slice(&msg.index.to_le_bytes());
    out.extend_from_slice(&msg.message_type.to_le_bytes());
    out.extend_from_slice(&msg.transaction_id.to_le_bytes());
    out.extend_from_slice(&msg.payload);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Layer 1: Fuji frame ────────────────────────────────────────────────

    #[test]
    fn encode_decode_fuji_frame_roundtrip() {
        let body = vec![0x01, 0x00, 0x01, 0x20, 0x03, 0x00, 0x00, 0x00];
        let frame = encode_fuji_frame(&body);
        // Total = 4 + 8 = 12
        assert_eq!(frame.len(), 12);
        let decoded = decode_fuji_frame(&frame).unwrap();
        assert_eq!(decoded.body, body);
    }

    #[test]
    fn encode_decode_empty_body_roundtrip() {
        let frame = encode_fuji_frame(&[]);
        assert_eq!(frame.len(), 4);
        let decoded = decode_fuji_frame(&frame).unwrap();
        assert_eq!(decoded.body, Vec::<u8>::new());
    }

    #[test]
    fn decode_fuji_frame_too_short_for_length_field() {
        assert!(matches!(
            decode_fuji_frame(&[0x0C, 0x00, 0x00]),
            Err(PtpCodecError::PacketTooShort {
                expected: 4,
                got: 3
            })
        ));
    }

    #[test]
    fn decode_fuji_frame_empty_buffer() {
        assert!(matches!(
            decode_fuji_frame(&[]),
            Err(PtpCodecError::PacketTooShort {
                expected: 4,
                got: 0
            })
        ));
    }

    #[test]
    fn decode_fuji_frame_declared_length_less_than_4() {
        // Length = 2 — less than FUJI_FRAME_MIN_LENGTH
        let buf = [0x02, 0x00, 0x00, 0x00, 0xFF, 0xFF];
        assert!(matches!(
            decode_fuji_frame(&buf),
            Err(PtpCodecError::PacketTooShort { .. })
        ));
    }

    #[test]
    fn decode_fuji_frame_declared_longer_than_buffer() {
        // Declare length = 100 but buffer only 10 bytes
        let mut buf = vec![0u8; 10];
        buf[0] = 100; // length = 100 LE
        assert!(matches!(
            decode_fuji_frame(&buf),
            Err(PtpCodecError::PacketTooShort {
                expected: 100,
                got: 10
            })
        ));
    }

    #[test]
    fn decode_fuji_frame_length_field_equals_4_gives_empty_body() {
        let buf = [0x04, 0x00, 0x00, 0x00]; // length = 4, no body
        let decoded = decode_fuji_frame(&buf).unwrap();
        assert_eq!(decoded.body, Vec::<u8>::new());
    }

    // ── Layer 2: Control message ───────────────────────────────────────────

    #[test]
    fn encode_decode_control_message_roundtrip() {
        let msg = FujiControlMessage {
            index: 1,
            message_type: 0x1002,
            transaction_id: 3,
            payload: vec![0x01, 0x00, 0x00, 0x00],
        };
        let encoded = encode_control_message(&msg);
        let decoded = decode_control_message(&encoded).unwrap();
        assert_eq!(decoded, msg);
    }

    #[test]
    fn control_message_empty_payload_roundtrip() {
        let msg = FujiControlMessage {
            index: 0,
            message_type: 0x1003,
            transaction_id: 7,
            payload: vec![],
        };
        let encoded = encode_control_message(&msg);
        assert_eq!(encoded.len(), CONTROL_HEADER_SIZE);
        let decoded = decode_control_message(&encoded).unwrap();
        assert_eq!(decoded, msg);
    }

    #[test]
    fn control_message_too_short() {
        let buf = [0x01, 0x00, 0x02, 0x10]; // only 4 bytes, need 8
        assert!(matches!(
            decode_control_message(&buf),
            Err(PtpCodecError::PacketTooShort {
                expected: 8,
                got: 4
            })
        ));
    }

    #[test]
    fn framed_control_message_full_roundtrip() {
        let msg = FujiControlMessage {
            index: 1,
            message_type: 0x1015,
            transaction_id: 0,
            payload: vec![0x12, 0xD2, 0x00, 0x00],
        };
        let body = encode_control_message(&msg);
        let frame = encode_fuji_frame(&body);
        let decoded_frame = decode_fuji_frame(&frame).unwrap();
        let decoded_msg = decode_control_message(&decoded_frame.body).unwrap();
        assert_eq!(decoded_msg, msg);
    }
}
