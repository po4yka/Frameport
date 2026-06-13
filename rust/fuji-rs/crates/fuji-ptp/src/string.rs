//! PTP string and device-name encoding/decoding helpers.
//!
//! Two distinct string formats appear in PTP-IP:
//!
//! 1. **PTP string** (section 4.11): `u8 num_chars` (count of UCS-2LE units including the
//!    null terminator), followed by `num_chars * 2` bytes of UCS-2LE. An empty string is a
//!    single `0x00` byte.
//!
//! 2. **Fixed 54-byte device-name field** (sections 4.2/4.3): UTF-16LE, null-padded to
//!    exactly 54 bytes, **without** a leading length byte. Used in Init Command Request
//!    and Init Command Ack packets.

use crate::error::PtpCodecError;

// ── PTP string (variable length, length-prefixed) ────────────────────────────

/// Decodes a PTP string from `buf` starting at `offset`.
///
/// Returns the decoded [`String`] and the number of bytes consumed from `buf`
/// (including the length byte and all UTF-16LE code units).
///
/// An empty string (length byte = 0) returns `("".to_owned(), 1)`.
///
/// # Errors
/// - [`PtpCodecError::PacketTooShort`] if the buffer does not hold the full string.
/// - [`PtpCodecError::InvalidUtf16`] if the UTF-16LE code units are not valid Unicode.
pub fn decode_ptp_string(buf: &[u8], offset: usize) -> Result<(String, usize), PtpCodecError> {
    // Need at least 1 byte for the count.
    if offset >= buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: offset + 1,
            got: buf.len(),
        });
    }

    let num_chars = buf[offset] as usize;
    // Consumed = 1 (length byte) + num_chars * 2 (UTF-16LE code units)
    let byte_count = num_chars
        .checked_mul(2)
        .ok_or(PtpCodecError::LengthOverflow)?;
    let total_consumed = byte_count
        .checked_add(1)
        .ok_or(PtpCodecError::LengthOverflow)?;

    if num_chars == 0 {
        return Ok((String::new(), 1));
    }

    let end = offset
        .checked_add(1)
        .and_then(|s| s.checked_add(byte_count))
        .ok_or(PtpCodecError::LengthOverflow)?;

    if end > buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }

    let units = decode_utf16le_units(&buf[offset + 1..end])?;

    // The last unit is the null terminator; strip it.
    let without_null = if units.last() == Some(&0u16) {
        &units[..units.len() - 1]
    } else {
        &units[..]
    };

    let s = String::from_utf16(without_null).map_err(|_| PtpCodecError::InvalidUtf16)?;
    Ok((s, total_consumed))
}

/// Encodes `s` as a PTP string (length-prefixed UCS-2LE with null terminator).
///
/// Returns the encoded bytes, including the leading `u8` length byte.
/// An empty string encodes as `[0x00]`.
///
/// # Panics
/// Does not panic. Strings longer than 254 visible characters are truncated to 254
/// characters (plus null = 255, the maximum `u8` count).
pub fn encode_ptp_string(s: &str) -> Vec<u8> {
    if s.is_empty() {
        return vec![0x00];
    }

    // Collect UTF-16 code units. Truncate to 254 visible chars to fit a u8 length
    // (254 chars + 1 null = 255 = u8::MAX).
    let units: Vec<u16> = s
        .encode_utf16()
        .take(254)
        .chain(std::iter::once(0u16))
        .collect();

    let num_chars = units.len(); // <= 255
    let mut out = Vec::with_capacity(1 + num_chars * 2);
    out.push(num_chars as u8);
    for unit in &units {
        out.extend_from_slice(&unit.to_le_bytes());
    }
    out
}

// ── Fixed-width 54-byte device-name field ────────────────────────────────────

/// The fixed byte width of the device-name field in Init Command Request / Ack.
/// Section 4.2: UTF-16LE, max 26 visible chars, null-padded to 54 bytes.
pub const DEVICE_NAME_FIELD_BYTES: usize = 54;

/// Decodes a 54-byte, null-padded UTF-16LE device-name field.
///
/// Reads exactly [`DEVICE_NAME_FIELD_BYTES`] bytes from `buf[offset..]`.
///
/// # Errors
/// - [`PtpCodecError::PacketTooShort`] if the buffer is too short.
/// - [`PtpCodecError::InvalidUtf16`] if the data is not valid UTF-16.
pub fn decode_device_name(buf: &[u8], offset: usize) -> Result<String, PtpCodecError> {
    let end = offset
        .checked_add(DEVICE_NAME_FIELD_BYTES)
        .ok_or(PtpCodecError::LengthOverflow)?;
    if end > buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }
    let field = &buf[offset..end];
    let units = decode_utf16le_units(field)?;
    // Strip null terminators from the end.
    let without_null: Vec<u16> = units.into_iter().take_while(|&c| c != 0u16).collect();
    String::from_utf16(&without_null).map_err(|_| PtpCodecError::InvalidUtf16)
}

/// Encodes `s` into exactly [`DEVICE_NAME_FIELD_BYTES`] bytes of null-padded UTF-16LE.
///
/// Truncates to 26 visible characters (26 × 2 = 52 bytes + 1 null unit = 54 bytes).
/// Strings shorter than 26 characters are zero-padded to 54 bytes.
pub fn encode_device_name(s: &str) -> [u8; DEVICE_NAME_FIELD_BYTES] {
    let mut out = [0u8; DEVICE_NAME_FIELD_BYTES];
    // Up to 26 visible characters: 26 × 2 = 52 bytes; remaining 2 bytes stay null.
    let units: Vec<u16> = s.encode_utf16().take(26).collect();
    for (i, unit) in units.iter().enumerate() {
        let byte_pos = i * 2;
        let le = unit.to_le_bytes();
        out[byte_pos] = le[0];
        out[byte_pos + 1] = le[1];
    }
    // Remaining bytes are already zero-initialized.
    out
}

// ── Internal helpers ──────────────────────────────────────────────────────────

/// Reads `buf` as a sequence of little-endian `u16` code units.
///
/// Requires `buf.len()` to be even; if odd the last byte is ignored (this should
/// not happen on well-formed PTP packets).
fn decode_utf16le_units(buf: &[u8]) -> Result<Vec<u16>, PtpCodecError> {
    let pair_count = buf.len() / 2;
    let mut units = Vec::with_capacity(pair_count);
    for i in 0..pair_count {
        let lo = buf[i * 2];
        let hi = buf[i * 2 + 1];
        units.push(u16::from_le_bytes([lo, hi]));
    }
    Ok(units)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ptp_string_roundtrip_ascii() {
        let encoded = encode_ptp_string("hello");
        let (decoded, consumed) = decode_ptp_string(&encoded, 0).unwrap();
        assert_eq!(decoded, "hello");
        assert_eq!(consumed, encoded.len());
    }

    #[test]
    fn ptp_string_empty_encodes_as_single_zero() {
        let encoded = encode_ptp_string("");
        assert_eq!(encoded, vec![0x00]);
        let (decoded, consumed) = decode_ptp_string(&encoded, 0).unwrap();
        assert_eq!(decoded, "");
        assert_eq!(consumed, 1);
    }

    #[test]
    fn ptp_string_too_short_returns_error() {
        // Length byte claims 3 chars but buffer only has 1 char worth of data.
        let buf = vec![0x03u8, 0x41, 0x00]; // num_chars=3 but only 1 unit follows
        assert!(matches!(
            decode_ptp_string(&buf, 0),
            Err(PtpCodecError::PacketTooShort { .. })
        ));
    }

    #[test]
    fn device_name_roundtrip() {
        let encoded = encode_device_name("Frameport");
        assert_eq!(encoded.len(), DEVICE_NAME_FIELD_BYTES);
        let decoded = decode_device_name(&encoded, 0).unwrap();
        assert_eq!(decoded, "Frameport");
    }

    #[test]
    fn device_name_truncates_at_26_chars() {
        let long = "A".repeat(40);
        let encoded = encode_device_name(&long);
        let decoded = decode_device_name(&encoded, 0).unwrap();
        assert_eq!(decoded.len(), 26);
    }

    #[test]
    fn device_name_short_is_null_padded() {
        let encoded = encode_device_name("Hi");
        // First 4 bytes: 'H'=0x48,0x00, 'i'=0x69,0x00
        assert_eq!(encoded[0], 0x48);
        assert_eq!(encoded[1], 0x00);
        assert_eq!(encoded[2], 0x69);
        assert_eq!(encoded[3], 0x00);
        // Rest should be zero.
        assert!(encoded[4..].iter().all(|&b| b == 0));
    }
}
