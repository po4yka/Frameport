//! BLE advertisement parsing for Fujifilm cameras.
//!
//! Parses manufacturer-specific advertisement data to identify Fujifilm cameras
//! and extract pairing tokens when in pairing mode.
//!
//! # Source references
//! - `docs/reference/ble-wifi-discovery.md` §"Advertisement and Discovery"
//! - `docs/reference/master-constants.md` §5a
//!
//! # Privacy invariants
//! - Raw advertisement bytes are NEVER surfaced in error messages or returned
//!   in any form to logging infrastructure.
//! - Pairing tokens are opaque `[u8; 4]` — the caller must not log them.

use std::fmt;

use fuji_core::{BleProtocolError, FujiError, FujiResult};

use crate::constants::{MANUFACTURER_COMPANY_ID, PAIRING_MODE_TYPE_BYTE, PAIRING_TOKEN_SIZE};

// ─── Types ───────────────────────────────────────────────────────────────────

/// A pairing token extracted from a Fujifilm camera advertisement.
///
/// 4 raw bytes that must be written verbatim to `CHR_PAIRING_KEY`.
/// The token is ephemeral — it changes per pairing session and must
/// not be cached or reused across sessions.
///
/// Source: ble-wifi-discovery.md §"Pairing Key" + §"Pairing Mode Indicator". [H] LFJ
#[derive(Clone, Eq, PartialEq)]
pub struct PairingToken(pub [u8; PAIRING_TOKEN_SIZE]);

impl PairingToken {
    /// Return the raw 4-byte token suitable for writing to `CHR_PAIRING_KEY`.
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
}

impl fmt::Debug for PairingToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("PairingToken(<redacted>)")
    }
}

/// Typed result of parsing a Fujifilm BLE advertisement.
///
/// A device advertises with manufacturer company ID `0x04D8`. Whether or not
/// it is in pairing mode is indicated by the type byte in the manufacturer
/// payload.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CameraAdvertisement {
    /// `true` when the camera is in BLE pairing mode (type byte == `0x02`).
    /// When `false`, the pairing token field is absent.
    ///
    /// Source: ble-wifi-discovery.md §"Pairing Mode Indicator". [H] LFJ
    pub is_in_pairing_mode: bool,

    /// Present only when `is_in_pairing_mode == true`. Must not be logged.
    pub pairing_token: Option<PairingToken>,
}

// ─── Manufacturer-payload parser ─────────────────────────────────────────────

/// Parse the raw manufacturer-specific payload bytes delivered by the Android
/// BLE scan callback.
///
/// The Android BLE stack strips the 2-byte company ID prefix from the
/// manufacturer-specific data before delivering it to `ScanResult.getScanRecord().
/// getManufacturerSpecificData(MANUFACTURER_COMPANY_ID)`. This function
/// therefore receives the payload **after** the company ID, i.e.:
///
/// ```text
/// payload[0] = type byte (0x02 = pairing mode)
/// payload[1..5] = pairing token (only valid when type == 0x02)
/// ```
///
/// If the company ID did not match `0x04D8` the Android scan filter would have
/// excluded the advertisement; the caller must ensure the correct map key was
/// used to retrieve this payload.
///
/// # Errors
/// Returns `BleProtocolError::MalformedPayload` when:
/// - `payload` is empty (no manufacturer data).
/// - `payload` indicates pairing mode but is too short to contain the 4-byte
///   token (expected at least 5 bytes: 1 type byte + 4 token bytes).
pub fn parse_manufacturer_payload(payload: &[u8]) -> FujiResult<CameraAdvertisement> {
    // Guard: need at least the type byte.
    let type_byte = payload
        .first()
        .copied()
        .ok_or(FujiError::BleProtocol(BleProtocolError::MalformedPayload))?;

    if type_byte != PAIRING_MODE_TYPE_BYTE {
        // Camera is present but not in pairing mode — valid advertisement.
        return Ok(CameraAdvertisement {
            is_in_pairing_mode: false,
            pairing_token: None,
        });
    }

    // Pairing mode: need type byte (index 0) + 4 token bytes (indices 1..=4).
    // Minimum payload length = 1 + PAIRING_TOKEN_SIZE = 5.
    let min_len = 1 + PAIRING_TOKEN_SIZE;
    if payload.len() < min_len {
        return Err(FujiError::BleProtocol(BleProtocolError::MalformedPayload));
    }

    // SAFETY (bounds): checked payload.len() >= 5 above; index 1..5 is valid.
    let token_bytes: [u8; PAIRING_TOKEN_SIZE] = payload[1..1 + PAIRING_TOKEN_SIZE]
        .try_into()
        .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))?;

    Ok(CameraAdvertisement {
        is_in_pairing_mode: true,
        pairing_token: Some(PairingToken(token_bytes)),
    })
}

/// Parse the raw advertisement bytes as provided by a raw BLE packet capture
/// or a test fixture that includes the full manufacturer-specific data AD
/// structure (including the 2-byte company ID at the front).
///
/// Layout expected:
/// ```text
/// bytes[0..2]  = company ID in little-endian (must equal 0x04D8)
/// bytes[2]     = type byte
/// bytes[3..7]  = pairing token (when type == 0x02)
/// ```
///
/// # Errors
/// - `BleProtocolError::MalformedPayload` if `bytes` is too short (< 3),
///   company ID does not match, or pairing-mode payload is truncated.
pub fn parse_raw_manufacturer_data(bytes: &[u8]) -> FujiResult<CameraAdvertisement> {
    // Need at least 2-byte company ID + 1 type byte.
    if bytes.len() < 3 {
        return Err(FujiError::BleProtocol(BleProtocolError::MalformedPayload));
    }

    // Validate company ID (little-endian u16).
    // Use .get() to be explicit about bounds — they are already checked above
    // but this makes the indexing intent clear.
    let company_id_lo = bytes
        .first()
        .copied()
        .ok_or(FujiError::BleProtocol(BleProtocolError::MalformedPayload))?;
    let company_id_hi = bytes
        .get(1)
        .copied()
        .ok_or(FujiError::BleProtocol(BleProtocolError::MalformedPayload))?;
    let company_id = u16::from_le_bytes([company_id_lo, company_id_hi]);

    if company_id != MANUFACTURER_COMPANY_ID {
        return Err(FujiError::BleProtocol(BleProtocolError::MalformedPayload));
    }

    // The remainder after the 2-byte company ID is the payload.
    let payload = bytes
        .get(2..)
        .ok_or(FujiError::BleProtocol(BleProtocolError::MalformedPayload))?;

    parse_manufacturer_payload(payload)
}

// ─── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── parse_manufacturer_payload ───────────────────────────────────────────

    #[test]
    fn non_pairing_mode_type_byte_returns_not_in_pairing_mode() {
        // type byte = 0x01 (not pairing)
        let payload = [0x01u8, 0x00, 0x00, 0x00, 0x00];
        let adv = parse_manufacturer_payload(&payload).unwrap();
        assert!(!adv.is_in_pairing_mode);
        assert!(adv.pairing_token.is_none());
    }

    #[test]
    fn pairing_mode_extracts_token_correctly() {
        // type=0x02, token=[0xAA, 0xBB, 0xCC, 0xDD]
        let payload = [0x02u8, 0xAA, 0xBB, 0xCC, 0xDD];
        let adv = parse_manufacturer_payload(&payload).unwrap();
        assert!(adv.is_in_pairing_mode);
        let token = adv.pairing_token.unwrap();
        assert_eq!(token.0, [0xAA, 0xBB, 0xCC, 0xDD]);
    }

    #[test]
    fn debug_output_redacts_pairing_token_bytes() {
        let payload = [0x02u8, 0xAA, 0xBB, 0xCC, 0xDD];
        let adv = parse_manufacturer_payload(&payload).unwrap();

        let debug = format!("{adv:?}");

        assert!(debug.contains("<redacted>"));
        assert!(!debug.contains("AA"));
        assert!(!debug.contains("BB"));
        assert!(!debug.contains("CC"));
        assert!(!debug.contains("DD"));
        assert!(!debug.contains("170"));
        assert!(!debug.contains("187"));
        assert!(!debug.contains("204"));
        assert!(!debug.contains("221"));
    }

    #[test]
    fn empty_payload_returns_malformed_error() {
        let err = parse_manufacturer_payload(&[]).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload)
        );
    }

    #[test]
    fn pairing_mode_truncated_payload_returns_malformed_error() {
        // type=0x02 but only 3 bytes total (need 5)
        let payload = [0x02u8, 0xAA, 0xBB];
        let err = parse_manufacturer_payload(&payload).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload)
        );
    }

    // ── parse_raw_manufacturer_data ──────────────────────────────────────────

    #[test]
    fn raw_data_with_correct_company_id_and_pairing_mode() {
        // company ID 0x04D8 LE = [0xD8, 0x04], type=0x02, token=4 bytes
        let raw = [0xD8u8, 0x04, 0x02, 0x11, 0x22, 0x33, 0x44];
        let adv = parse_raw_manufacturer_data(&raw).unwrap();
        assert!(adv.is_in_pairing_mode);
        assert_eq!(adv.pairing_token.unwrap().0, [0x11, 0x22, 0x33, 0x44]);
    }

    #[test]
    fn raw_data_wrong_company_id_returns_malformed_error() {
        // company ID 0x0000 != 0x04D8
        let raw = [0x00u8, 0x00, 0x02, 0x11, 0x22, 0x33, 0x44];
        let err = parse_raw_manufacturer_data(&raw).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload)
        );
    }

    #[test]
    fn raw_data_too_short_returns_malformed_error() {
        // Only 2 bytes — not enough for company ID + type byte.
        let raw = [0xD8u8, 0x04];
        let err = parse_raw_manufacturer_data(&raw).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload)
        );
    }

    #[test]
    fn raw_data_non_pairing_type_byte() {
        let raw = [0xD8u8, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00];
        let adv = parse_raw_manufacturer_data(&raw).unwrap();
        assert!(!adv.is_in_pairing_mode);
        assert!(adv.pairing_token.is_none());
    }
}
