//! PTP ObjectInfo dataset codec.
//!
//! Shared encoder and decoder so the simulation server (`fuji-sim`) and the transfer
//! decoder (`fuji-transfer`) agree byte-for-byte on the wire layout.  Any drift between
//! the two is a compile-time / test failure here, not a silent protocol mismatch at
//! runtime.
//!
//! # Wire layout (PTP standard, all fields little-endian)
//!
//! | Offset | Size | Field |
//! |--------|------|-------|
//! | 0      | 4    | `storage_id` (u32) |
//! | 4      | 2    | `object_format` (u16) — see [`crate::constants::object_format`] |
//! | 6      | 2    | `protection_status` (u16) — 0=no protection, 1=read-only |
//! | 8      | 4    | `object_compressed_size` (u32) — uncompressed size or best estimate |
//! | 12     | 2    | `thumb_format` (u16) |
//! | 14     | 4    | `thumb_compressed_size` (u32) |
//! | 18     | 4    | `thumb_pix_width` (u32) |
//! | 22     | 4    | `thumb_pix_height` (u32) |
//! | 26     | 4    | `image_pix_width` (u32) |
//! | 30     | 4    | `image_pix_height` (u32) |
//! | 34     | 4    | `image_bit_depth` (u32) |
//! | 38     | 4    | `parent_object` (u32) — 0 = root |
//! | 42     | 2    | `association_type` (u16) |
//! | 44     | 4    | `association_desc` (u32) |
//! | 48     | 4    | `sequence_number` (u32) |
//! | 52     | var  | `filename` (PTP string) |
//! | 52+fL  | var  | `capture_date` (PTP string) |
//! | …      | var  | `modification_date` (PTP string) |
//! | …      | var  | `keywords` (PTP string) |
//!
//! The object handle is the *request* parameter — it is NOT a field inside ObjectInfo.
//!
//! PTP string encoding: `u8` count of UCS-2LE code units **including** the null terminator
//! (0 = empty string), followed by that many `u16` LE code units.
//! See [`crate::string::decode_ptp_string`] / [`crate::string::encode_ptp_string`].

use crate::{
    error::PtpCodecError,
    string::{decode_ptp_string, encode_ptp_string},
};

// ── Minimum sizes ─────────────────────────────────────────────────────────────

/// Size in bytes of the fixed-width portion of an ObjectInfo dataset (before the
/// variable-length PTP string fields).
///
/// Exported so callers (e.g. `fuji-transfer`) can map a short-buffer error to
/// `ProtocolError::InvalidPacketLength { minimum: OBJECT_INFO_FIXED_BYTES as u32 }`.
pub const OBJECT_INFO_FIXED_BYTES: usize = 52;

// ── Public type ───────────────────────────────────────────────────────────────

/// PTP ObjectInfo dataset as transferred over the wire.
///
/// The object handle is the *request* parameter sent with `GetObjectInfo`; it is not
/// stored inside the ObjectInfo payload itself.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ObjectInfo {
    /// Storage on the camera where the object resides (e.g. `0x00010001`).
    pub storage_id: u32,
    /// PTP object format code.  Use [`crate::constants::object_format`] constants.
    pub object_format: u16,
    /// Protection status: `0` = no protection, `1` = read-only.
    pub protection_status: u16,
    /// Compressed size of the full object in bytes.
    ///
    /// For objects larger than `u32::MAX` this field may be `0xFFFF_FFFF`; callers
    /// should cross-reference the actual download byte count for integrity checking.
    pub object_compressed_size: u32,
    /// Format code for the embedded thumbnail.
    pub thumb_format: u16,
    /// Compressed size of the thumbnail in bytes.
    pub thumb_compressed_size: u32,
    /// Width of the thumbnail in pixels.
    pub thumb_pix_width: u32,
    /// Height of the thumbnail in pixels.
    pub thumb_pix_height: u32,
    /// Width of the full image in pixels.
    pub image_pix_width: u32,
    /// Height of the full image in pixels.
    pub image_pix_height: u32,
    /// Bit depth of the full image.
    pub image_bit_depth: u32,
    /// Handle of the parent object (`0` = root / no parent).
    pub parent_object: u32,
    /// Association type (folder type etc.).
    pub association_type: u16,
    /// Association descriptor.
    pub association_desc: u32,
    /// Sequence number within the storage.
    pub sequence_number: u32,
    /// Original filename on the camera (UTF-8; may be empty).
    pub filename: String,
    /// Capture date/time as an ISO 8601 string (may be empty).
    pub capture_date: String,
    /// Modification date/time as an ISO 8601 string (may be empty).
    pub modification_date: String,
    /// Keyword list (may be empty).
    pub keywords: String,
}

// ── Encoder ───────────────────────────────────────────────────────────────────

/// Encodes an [`ObjectInfo`] into its PTP wire representation.
///
/// The returned `Vec<u8>` starts at offset 0 (i.e., it does NOT include any surrounding
/// PTP-IP packet framing — wrap it in an `EndData` payload when sending).
pub fn encode_object_info(info: &ObjectInfo) -> Vec<u8> {
    let mut buf = Vec::with_capacity(OBJECT_INFO_FIXED_BYTES + 32);

    // Fixed-width fields (all LE).
    buf.extend_from_slice(&info.storage_id.to_le_bytes());
    buf.extend_from_slice(&info.object_format.to_le_bytes());
    buf.extend_from_slice(&info.protection_status.to_le_bytes());
    buf.extend_from_slice(&info.object_compressed_size.to_le_bytes());
    buf.extend_from_slice(&info.thumb_format.to_le_bytes());
    buf.extend_from_slice(&info.thumb_compressed_size.to_le_bytes());
    buf.extend_from_slice(&info.thumb_pix_width.to_le_bytes());
    buf.extend_from_slice(&info.thumb_pix_height.to_le_bytes());
    buf.extend_from_slice(&info.image_pix_width.to_le_bytes());
    buf.extend_from_slice(&info.image_pix_height.to_le_bytes());
    buf.extend_from_slice(&info.image_bit_depth.to_le_bytes());
    buf.extend_from_slice(&info.parent_object.to_le_bytes());
    buf.extend_from_slice(&info.association_type.to_le_bytes());
    buf.extend_from_slice(&info.association_desc.to_le_bytes());
    buf.extend_from_slice(&info.sequence_number.to_le_bytes());

    // Verify fixed section is exactly OBJECT_INFO_FIXED_BYTES.
    debug_assert_eq!(
        buf.len(),
        OBJECT_INFO_FIXED_BYTES,
        "fixed section size mismatch — update OBJECT_INFO_FIXED_BYTES"
    );

    // Variable-length PTP string fields.
    buf.extend_from_slice(&encode_ptp_string(&info.filename));
    buf.extend_from_slice(&encode_ptp_string(&info.capture_date));
    buf.extend_from_slice(&encode_ptp_string(&info.modification_date));
    buf.extend_from_slice(&encode_ptp_string(&info.keywords));

    buf
}

// ── Decoder ───────────────────────────────────────────────────────────────────

/// Decodes an [`ObjectInfo`] from the raw PTP payload bytes.
///
/// `buf` must be the ObjectInfo dataset bytes only (i.e. the payload extracted from an
/// `EndData` packet — the PTP-IP framing must already be stripped).
///
/// # Errors
///
/// Returns [`PtpCodecError::PacketTooShort`] if the buffer is too short to hold any
/// required field, including every variable-length PTP string.  Returns
/// [`PtpCodecError::InvalidUtf16`] if a string field contains invalid UTF-16.
/// Returns [`PtpCodecError::LengthOverflow`] on arithmetic overflow (malformed input).
///
/// This function will **never** panic or access the buffer out-of-bounds.
pub fn decode_object_info(buf: &[u8]) -> Result<ObjectInfo, PtpCodecError> {
    // Validate fixed-size section up front.
    if buf.len() < OBJECT_INFO_FIXED_BYTES {
        return Err(PtpCodecError::PacketTooShort {
            expected: OBJECT_INFO_FIXED_BYTES,
            got: buf.len(),
        });
    }

    // Helper: read a u32 LE at a compile-time-known offset (bounds already checked above
    // for the fixed section).
    let read_u32 = |offset: usize| -> u32 {
        u32::from_le_bytes([
            buf[offset],
            buf[offset + 1],
            buf[offset + 2],
            buf[offset + 3],
        ])
    };
    let read_u16 = |offset: usize| -> u16 { u16::from_le_bytes([buf[offset], buf[offset + 1]]) };

    let storage_id = read_u32(0);
    let object_format = read_u16(4);
    let protection_status = read_u16(6);
    let object_compressed_size = read_u32(8);
    let thumb_format = read_u16(12);
    let thumb_compressed_size = read_u32(14);
    let thumb_pix_width = read_u32(18);
    let thumb_pix_height = read_u32(22);
    let image_pix_width = read_u32(26);
    let image_pix_height = read_u32(30);
    let image_bit_depth = read_u32(34);
    let parent_object = read_u32(38);
    let association_type = read_u16(42);
    let association_desc = read_u32(44);
    let sequence_number = read_u32(48);

    // Variable-length PTP strings — each call validates its own bounds.
    let mut cursor = OBJECT_INFO_FIXED_BYTES;

    let (filename, consumed) = decode_ptp_string(buf, cursor)?;
    cursor = cursor
        .checked_add(consumed)
        .ok_or(PtpCodecError::LengthOverflow)?;

    let (capture_date, consumed) = decode_ptp_string(buf, cursor)?;
    cursor = cursor
        .checked_add(consumed)
        .ok_or(PtpCodecError::LengthOverflow)?;

    let (modification_date, consumed) = decode_ptp_string(buf, cursor)?;
    cursor = cursor
        .checked_add(consumed)
        .ok_or(PtpCodecError::LengthOverflow)?;

    let (keywords, _consumed) = decode_ptp_string(buf, cursor)?;

    Ok(ObjectInfo {
        storage_id,
        object_format,
        protection_status,
        object_compressed_size,
        thumb_format,
        thumb_compressed_size,
        thumb_pix_width,
        thumb_pix_height,
        image_pix_width,
        image_pix_height,
        image_bit_depth,
        parent_object,
        association_type,
        association_desc,
        sequence_number,
        filename,
        capture_date,
        modification_date,
        keywords,
    })
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::constants::object_format;

    fn sample_object_info() -> ObjectInfo {
        ObjectInfo {
            storage_id: 0x0001_0001,
            object_format: object_format::JPEG,
            protection_status: 0,
            object_compressed_size: 4_096_000,
            thumb_format: object_format::JPEG,
            thumb_compressed_size: 12_345,
            thumb_pix_width: 160,
            thumb_pix_height: 120,
            image_pix_width: 6944,
            image_pix_height: 4624,
            image_bit_depth: 8,
            parent_object: 0,
            association_type: 0,
            association_desc: 0,
            sequence_number: 1,
            filename: "DSCF0001.JPG".to_owned(),
            capture_date: "20241225T120000".to_owned(),
            modification_date: "20241225T120000".to_owned(),
            keywords: String::new(),
        }
    }

    /// Full encode → decode roundtrip produces identical structs.
    #[test]
    fn roundtrip_jpeg() {
        let original = sample_object_info();
        let encoded = encode_object_info(&original);
        let decoded = decode_object_info(&encoded).expect("decode must succeed");
        assert_eq!(decoded, original);
    }

    /// Roundtrip with RAF format code and empty optional strings.
    #[test]
    fn roundtrip_raf_empty_strings() {
        let original = ObjectInfo {
            storage_id: 0x0001_0001,
            object_format: object_format::RAF,
            protection_status: 0,
            object_compressed_size: 52_428_800,
            thumb_format: object_format::JPEG,
            thumb_compressed_size: 98_304,
            thumb_pix_width: 320,
            thumb_pix_height: 214,
            image_pix_width: 6944,
            image_pix_height: 4624,
            image_bit_depth: 14,
            parent_object: 0,
            association_type: 0,
            association_desc: 0,
            sequence_number: 2,
            filename: "DSCF0002.RAF".to_owned(),
            capture_date: String::new(),
            modification_date: String::new(),
            keywords: String::new(),
        };
        let encoded = encode_object_info(&original);
        let decoded = decode_object_info(&encoded).expect("decode must succeed");
        assert_eq!(decoded, original);
    }

    /// Roundtrip with HEIF format code and non-ASCII filename.
    #[test]
    fn roundtrip_heif_unicode_filename() {
        let original = ObjectInfo {
            storage_id: 0x0001_0001,
            object_format: object_format::HEIF,
            protection_status: 1,
            object_compressed_size: 8_000_000,
            thumb_format: object_format::JPEG,
            thumb_compressed_size: 40_000,
            thumb_pix_width: 160,
            thumb_pix_height: 120,
            image_pix_width: 4096,
            image_pix_height: 2732,
            image_bit_depth: 10,
            parent_object: 0,
            association_type: 0,
            association_desc: 0,
            sequence_number: 3,
            filename: "Ünïcödé.HEIC".to_owned(),
            capture_date: "20250101T000000Z".to_owned(),
            modification_date: String::new(),
            keywords: "vacation travel".to_owned(),
        };
        let encoded = encode_object_info(&original);
        let decoded = decode_object_info(&encoded).expect("decode must succeed");
        assert_eq!(decoded, original);
    }

    /// Encoded byte count: fixed section must be exactly OBJECT_INFO_FIXED_BYTES before
    /// the string fields start.
    #[test]
    fn fixed_section_is_52_bytes() {
        // Encode an info where every string is empty (encodes as single 0x00 each).
        let info = ObjectInfo {
            storage_id: 1,
            object_format: object_format::JPEG,
            protection_status: 0,
            object_compressed_size: 0,
            thumb_format: 0,
            thumb_compressed_size: 0,
            thumb_pix_width: 0,
            thumb_pix_height: 0,
            image_pix_width: 0,
            image_pix_height: 0,
            image_bit_depth: 0,
            parent_object: 0,
            association_type: 0,
            association_desc: 0,
            sequence_number: 0,
            filename: String::new(),
            capture_date: String::new(),
            modification_date: String::new(),
            keywords: String::new(),
        };
        let encoded = encode_object_info(&info);
        // 52 fixed + 4 × 1-byte empty PTP strings = 56 bytes.
        assert_eq!(encoded.len(), OBJECT_INFO_FIXED_BYTES + 4);
        // First byte of string section: length byte of filename PTP string = 0 (empty).
        assert_eq!(encoded[OBJECT_INFO_FIXED_BYTES], 0x00);
    }

    /// Decoding a completely empty buffer returns PacketTooShort.
    #[test]
    fn decode_empty_buf_returns_error() {
        let result = decode_object_info(&[]);
        assert!(
            matches!(
                result,
                Err(PtpCodecError::PacketTooShort {
                    expected: 52,
                    got: 0
                })
            ),
            "expected PacketTooShort(52, 0), got {result:?}"
        );
    }

    /// Decoding a buffer that covers only the fixed section (no string fields) returns
    /// PacketTooShort.
    #[test]
    fn decode_truncated_at_fixed_section_returns_error() {
        // Exactly 52 bytes (fixed section only) — the filename PTP string is missing.
        let buf = vec![0u8; OBJECT_INFO_FIXED_BYTES];
        let result = decode_object_info(&buf);
        assert!(
            matches!(result, Err(PtpCodecError::PacketTooShort { .. })),
            "expected PacketTooShort, got {result:?}"
        );
    }

    /// Decoding a buffer truncated mid-string (length byte present but data truncated)
    /// returns PacketTooShort — no panic.
    #[test]
    fn decode_truncated_mid_string_returns_error_not_panic() {
        let good = encode_object_info(&sample_object_info());
        // Keep the fixed section + length byte of filename string, but drop the rest.
        let truncated = &good[..OBJECT_INFO_FIXED_BYTES + 1];
        let result = decode_object_info(truncated);
        assert!(
            matches!(result, Err(PtpCodecError::PacketTooShort { .. })),
            "expected PacketTooShort, got {result:?}"
        );
    }

    /// Decoding a buffer truncated after filename but before capture_date returns an
    /// error — no panic.
    #[test]
    fn decode_truncated_after_filename_returns_error_not_panic() {
        let good = encode_object_info(&sample_object_info());
        // Filename PTP string for "DSCF0001.JPG": 1 + 12*2 + 2 (null) = 27 bytes.
        let filename_len = encode_ptp_string("DSCF0001.JPG").len();
        let truncated = &good[..OBJECT_INFO_FIXED_BYTES + filename_len];
        // The capture_date string is absent — should error, not panic.
        let result = decode_object_info(truncated);
        assert!(
            matches!(result, Err(PtpCodecError::PacketTooShort { .. })),
            "expected PacketTooShort, got {result:?}"
        );
    }

    /// Adversarial: length byte in filename claims 255 chars but only 1 byte follows.
    #[test]
    fn decode_adversarial_length_byte_returns_error_not_panic() {
        let mut buf = vec![0u8; OBJECT_INFO_FIXED_BYTES + 3];
        // Set filename length byte to 255 (claims 510 bytes of UTF-16 follow).
        buf[OBJECT_INFO_FIXED_BYTES] = 255;
        // Only 2 extra bytes follow — massively truncated.
        buf[OBJECT_INFO_FIXED_BYTES + 1] = 0x41;
        buf[OBJECT_INFO_FIXED_BYTES + 2] = 0x00;
        let result = decode_object_info(&buf);
        assert!(
            matches!(result, Err(PtpCodecError::PacketTooShort { .. })),
            "expected PacketTooShort, got {result:?}"
        );
    }

    /// object_format constant for JPEG_ROTATED (0x3808) is available via constants module.
    /// This ensures the codec can handle the format code without treating it as unknown.
    #[test]
    fn jpeg_rotated_format_code_roundtrips() {
        use crate::constants::object_format::JPEG_ROTATED;
        let info = ObjectInfo {
            object_format: JPEG_ROTATED,
            ..sample_object_info()
        };
        let decoded = decode_object_info(&encode_object_info(&info)).unwrap();
        assert_eq!(decoded.object_format, JPEG_ROTATED);
    }

    /// MOV format code roundtrips through the codec.
    #[test]
    fn mov_format_code_roundtrips() {
        let info = ObjectInfo {
            object_format: object_format::MOV,
            ..sample_object_info()
        };
        let decoded = decode_object_info(&encode_object_info(&info)).unwrap();
        assert_eq!(decoded.object_format, object_format::MOV);
    }
}
