//! PTP array wire encoding (ptp-ptpip.md section 4.12).
//!
//! A PTP array is encoded as:
//! 1. `u32 LE` — element count.
//! 2. `count × element_width LE` — elements.
//!
//! This module provides helpers for the u32-element variant (object handles,
//! property lists, storage IDs, etc.).

use crate::error::PtpCodecError;

/// Decodes a PTP `u32` array from `buf` starting at `offset`.
///
/// Returns the decoded `Vec<u32>` and the total number of bytes consumed
/// (4 for the count field + 4 × element_count for the elements).
///
/// # Errors
/// - [`PtpCodecError::PacketTooShort`] if the buffer does not hold the full array.
/// - [`PtpCodecError::LengthOverflow`] if the element count overflows `usize`.
pub fn decode_u32_array(buf: &[u8], offset: usize) -> Result<(Vec<u32>, usize), PtpCodecError> {
    // Need at least 4 bytes for the count field.
    let count_end = offset.checked_add(4).ok_or(PtpCodecError::LengthOverflow)?;
    if buf.len() < count_end {
        return Err(PtpCodecError::PacketTooShort {
            expected: count_end,
            got: buf.len(),
        });
    }

    let count = u32::from_le_bytes([
        buf[offset],
        buf[offset + 1],
        buf[offset + 2],
        buf[offset + 3],
    ]) as usize;

    let byte_count = count.checked_mul(4).ok_or(PtpCodecError::LengthOverflow)?;
    let total_consumed = byte_count
        .checked_add(4)
        .ok_or(PtpCodecError::LengthOverflow)?;
    let end = offset
        .checked_add(total_consumed)
        .ok_or(PtpCodecError::LengthOverflow)?;

    if buf.len() < end {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }

    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let pos = offset + 4 + i * 4;
        let val = u32::from_le_bytes([buf[pos], buf[pos + 1], buf[pos + 2], buf[pos + 3]]);
        out.push(val);
    }

    Ok((out, total_consumed))
}

/// Encodes a `u32` slice as a PTP array (u32 count + elements).
///
/// Returns the encoded bytes.
pub fn encode_u32_array(elements: &[u32]) -> Vec<u8> {
    let count = elements.len() as u32;
    let mut out = Vec::with_capacity(4 + elements.len() * 4);
    out.extend_from_slice(&count.to_le_bytes());
    for elem in elements {
        out.extend_from_slice(&elem.to_le_bytes());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_array_roundtrip() {
        let encoded = encode_u32_array(&[]);
        assert_eq!(encoded, vec![0x00, 0x00, 0x00, 0x00]);
        let (decoded, consumed) = decode_u32_array(&encoded, 0).unwrap();
        assert_eq!(decoded, Vec::<u32>::new());
        assert_eq!(consumed, 4);
    }

    #[test]
    fn three_element_array_roundtrip() {
        let elements = vec![0x0001u32, 0x0002u32, 0x0003u32];
        let encoded = encode_u32_array(&elements);
        // 4 (count) + 3 × 4 = 16 bytes
        assert_eq!(encoded.len(), 16);
        let (decoded, consumed) = decode_u32_array(&encoded, 0).unwrap();
        assert_eq!(decoded, elements);
        assert_eq!(consumed, 16);
    }

    #[test]
    fn decode_with_nonzero_offset() {
        let prefix = vec![0xAA, 0xBB]; // 2 bytes of preceding data
        let mut buf = prefix.clone();
        buf.extend_from_slice(&encode_u32_array(&[0x1234_5678]));
        let (decoded, consumed) = decode_u32_array(&buf, 2).unwrap();
        assert_eq!(decoded, vec![0x1234_5678u32]);
        assert_eq!(consumed, 8); // 4 (count) + 4 (element)
    }

    #[test]
    fn decode_too_short_for_count_field() {
        let buf = [0x01, 0x00, 0x00]; // only 3 bytes, need 4 for count
        assert!(matches!(
            decode_u32_array(&buf, 0),
            Err(PtpCodecError::PacketTooShort { .. })
        ));
    }

    #[test]
    fn decode_too_short_for_elements() {
        // count = 3 but only 4 bytes for elements (need 12)
        let mut buf = vec![0x03, 0x00, 0x00, 0x00]; // count = 3
        buf.extend_from_slice(&[0x01, 0x00, 0x00, 0x00]); // only 1 element
        assert!(matches!(
            decode_u32_array(&buf, 0),
            Err(PtpCodecError::PacketTooShort { .. })
        ));
    }
}
