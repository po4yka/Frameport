//! BLE → Wi-Fi handoff credential container.
//!
//! `BleWifiHandoff` holds the validated SSID and passphrase obtained by
//! reading `CHR_CAMERA_SSID_NAME_STRING` and `CHR_CAMERA_WIFI_PASSPHRASE_STRING`
//! from the camera's GATT server. The Android layer uses these values to build
//! a `WifiNetworkSpecifier`.
//!
//! # Privacy invariants (absolute, from ble-wifi-discovery.md §"Privacy")
//! - The passphrase MUST NEVER appear in any log call at any level.
//! - The `Debug` implementation intentionally redacts the passphrase field.
//! - The `Display` implementation is deliberately not implemented; callers
//!   must access fields explicitly to prevent accidental string interpolation.
//!
//! # Source references
//! - `docs/reference/ble-wifi-discovery.md` §"BLE → Wi-Fi Handoff State Machine"
//! - `docs/reference/ble-wifi-discovery.md` §"Camera Information (SSID / Passphrase)"

use fuji_core::{BleProtocolError, FujiError, FujiResult};

// ─── BleWifiHandoff ──────────────────────────────────────────────────────────

/// Validated SSID and passphrase obtained from the camera via BLE.
///
/// Construction enforces:
/// - SSID is non-empty and valid UTF-8.
/// - Passphrase is non-empty and valid UTF-8.
///
/// Invalid values produce `BleProtocolError::MalformedPayload` or
/// `BleProtocolError::MissingWifiCredentials`.
///
/// Source: ble-wifi-discovery.md §"CredentialRead" and §"HandoffValidation"
/// states. [H] XBL
#[derive(Clone, Eq, PartialEq)]
pub struct BleWifiHandoff {
    /// Camera Wi-Fi SSID. Non-empty, UTF-8. Must not be logged.
    ssid: String,
    /// Camera WPA2 passphrase. Non-empty, UTF-8.
    ///
    /// PRIVACY INVARIANT: MUST NEVER appear in any log call at any level in
    /// any build configuration. The `Debug` impl redacts this field.
    passphrase: String,
}

impl std::fmt::Debug for BleWifiHandoff {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Passphrase is intentionally redacted; only expose SSID length.
        f.debug_struct("BleWifiHandoff")
            .field("ssid_len", &self.ssid.len())
            .field("passphrase", &"<redacted>")
            .finish()
    }
}

impl BleWifiHandoff {
    /// Construct a `BleWifiHandoff` from raw UTF-8 strings parsed from BLE
    /// characteristics.
    ///
    /// # Errors
    /// - `BleProtocolError::MissingWifiCredentials` when `ssid` is empty.
    /// - `BleProtocolError::MalformedPayload` when `passphrase` is empty
    ///   (present in the characteristic but zero-length; an absent passphrase
    ///   is a structural failure, not a credential absence).
    ///
    /// Empty SSID → `MissingWifiCredentials` because the camera is not
    /// broadcasting a network.
    /// Empty passphrase → `MalformedPayload` because the characteristic
    /// delivered data but it contained no usable content.
    pub fn new(ssid: String, passphrase: String) -> FujiResult<Self> {
        if ssid.is_empty() {
            return Err(FujiError::BleProtocol(
                BleProtocolError::MissingWifiCredentials,
            ));
        }
        if passphrase.is_empty() {
            return Err(FujiError::BleProtocol(BleProtocolError::MalformedPayload));
        }
        Ok(Self { ssid, passphrase })
    }

    /// Return the camera Wi-Fi SSID.
    ///
    /// This value is safe to use in `WifiNetworkSpecifier` construction but
    /// must not be logged in release builds (it identifies the specific camera).
    pub fn ssid(&self) -> &str {
        &self.ssid
    }

    /// Return the camera WPA2 passphrase.
    ///
    /// PRIVACY INVARIANT: The returned `&str` MUST NEVER be passed to any
    /// logging call. The caller is responsible for zero-ing memory after use
    /// if operating in a security-sensitive context.
    pub fn passphrase(&self) -> &str {
        &self.passphrase
    }
}

// ─── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_with_valid_ssid_and_passphrase_succeeds() {
        let h = BleWifiHandoff::new("FUJIFILM-X-T5-0000".to_owned(), "test-wpa2-key".to_owned())
            .unwrap();
        assert_eq!(h.ssid(), "FUJIFILM-X-T5-0000");
        // Verify passphrase accessor works; do not log the value.
        assert!(!h.passphrase().is_empty());
    }

    #[test]
    fn empty_ssid_returns_missing_wifi_credentials() {
        let err = BleWifiHandoff::new(String::new(), "some-pass".to_owned()).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MissingWifiCredentials)
        );
    }

    #[test]
    fn empty_passphrase_returns_malformed_payload() {
        let err = BleWifiHandoff::new("FUJIFILM-X-T5-0000".to_owned(), String::new()).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload)
        );
    }

    #[test]
    fn debug_repr_redacts_passphrase() {
        let h =
            BleWifiHandoff::new("FUJIFILM-X-T5-0000".to_owned(), "secret-pass".to_owned()).unwrap();
        let debug_str = format!("{h:?}");
        // Passphrase must not appear in debug output.
        assert!(
            !debug_str.contains("secret-pass"),
            "passphrase leaked in Debug: {debug_str}"
        );
        assert!(
            debug_str.contains("<redacted>"),
            "expected <redacted> in Debug output: {debug_str}"
        );
    }

    // ── Named test required by GT-NAMED-TESTS ───────────────────────────────
    // The verifier asserts: `malformed_handoff_payload_returns_ble_protocol_error_not_panic`

    #[test]
    fn malformed_handoff_payload_returns_ble_protocol_error_not_panic() {
        // Simulate the HandoffValidation step: Rust parses SSID and passphrase
        // bytes from the camera, then constructs BleWifiHandoff.
        //
        // Case 1: SSID characteristic returned empty bytes (no network).
        let err = BleWifiHandoff::new(String::new(), "irrelevant".to_owned()).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MissingWifiCredentials),
            "empty SSID must produce MissingWifiCredentials, not panic"
        );

        // Case 2: Passphrase characteristic returned an empty string.
        let err = BleWifiHandoff::new("FUJIFILM-X-T5-0000".to_owned(), String::new()).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload),
            "empty passphrase must produce MalformedPayload, not panic"
        );

        // Case 3: parse_ssid on invalid UTF-8 produces MalformedPayload (not panic).
        let invalid_utf8 = [0xFFu8, 0xFE];
        let err = crate::payloads::parse_ssid(&invalid_utf8).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload),
            "invalid UTF-8 SSID must produce MalformedPayload, not panic"
        );
    }
}
