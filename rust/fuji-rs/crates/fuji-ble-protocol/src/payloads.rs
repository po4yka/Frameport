//! Characteristic payload parsers and builders for Fujifilm BLE characteristics.
//!
//! Each function either parses bytes read from a characteristic into a typed
//! domain value, or builds the bytes to write to a characteristic.
//!
//! All multi-byte integers are little-endian per the Fujifilm BLE spec.
//!
//! # Source references
//! - `docs/reference/ble-wifi-discovery.md` §"BLE Command Payload Layouts"
//! - `docs/reference/master-constants.md` §5c, §5d
//!
//! # Privacy invariants
//! - SSID and passphrase payloads are returned as `String`; the caller
//!   MUST NOT pass them to any log call at any level.
//! - This module never logs; it only parses and builds.

use fuji_core::{BleProtocolError, FujiError, FujiResult};

use crate::constants::{
    APP_INFO_PAYLOAD_LEN, LOCATION_AND_SPEED_PAYLOAD_LEN, SHOOTING_REQUEST_PAYLOAD_LEN,
};

// ─── ShootingRequest ─────────────────────────────────────────────────────────

/// The three valid shutter states for the remote shooting request.
///
/// Characteristic: `CHR_SHOOTING_REQUEST` in `SERVICE_CAMERA_CONTROL`.
/// Payload: 2-byte LE u16.
///
/// Source: ble-wifi-discovery.md §"ShootingRequest". [H] LFJ, XPN, XBL
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ShootingRequest {
    /// S0 — release / reset / cancel autofocus. Wire value: `0x0000`. [H] LFJ, XPN, XBL
    S0Release = 0x0000,
    /// S1 — half-press (autofocus trigger). Wire value: `0x0001`. [H] LFJ, XPN, XBL
    S1HalfPress = 0x0001,
    /// S2 — full-press (capture). Wire value: `0x0002`. [H] LFJ, XPN, XBL
    S2FullPress = 0x0002,
}

/// Build the 2-byte LE payload for a `CHR_SHOOTING_REQUEST` write.
///
/// Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
pub fn build_shooting_request(state: ShootingRequest) -> [u8; 2] {
    (state as u16).to_le_bytes()
}

// ─── ShootingResponse ────────────────────────────────────────────────────────

/// The camera's acknowledgement of a `CHR_SHOOTING_REQUEST` write.
///
/// # Source and uncertainty
/// `CHR_SHOOTING_REQUEST` is a **Write-only** characteristic (see
/// ble-wifi-discovery.md §"Camera Control" and master-constants.md §5c).
/// The camera's acknowledgement is delivered as the GATT write-callback
/// status (`onCharacteristicWrite`) — not as a separate notification
/// characteristic.  When the Android GATT stack exposes this status as raw
/// bytes for parsing (e.g. in a higher-level framing layer), those bytes are
/// a 2-byte LE u16:
///
/// | Value    | Meaning                              | Source confidence |
/// |----------|--------------------------------------|-------------------|
/// | `0x0000` | Accepted — camera acknowledged the   | [H] LFJ, XPN, XBL |
/// |          | shutter command successfully.        |                   |
/// | other    | Rejected — GATT error or camera busy.| Inferred from     |
/// |          | The raw status code is preserved.    | BLE spec + XPN    |
///
/// **Uncertainty:** No source provides a comprehensive table of
/// camera-returned status codes for this specific characteristic beyond the
/// success/failure distinction.  Treat any non-zero value as `Rejected` and
/// surface the raw code to callers for diagnostics.  Verify on X-T5 hardware.
///
/// # Payload layout
/// - Bytes 0–1: u16 LE status code.  Exactly 2 bytes.
///
/// # Privacy
/// This parser never logs raw byte values.  No device identifiers appear
/// in any field.
///
/// Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ShootingResponse {
    /// Camera accepted the shutter command.  Wire value `0x0000`.
    Accepted,
    /// Camera rejected the command or returned a GATT error.
    /// The raw u16 status code is preserved for diagnostics.
    Rejected {
        /// The raw u16 LE status code returned by the camera.
        raw_status: u16,
    },
}

/// Parse a 2-byte LE `CHR_SHOOTING_REQUEST` write-callback acknowledgement
/// payload into a typed [`ShootingResponse`].
///
/// Exactly 2 bytes are required.  `0x0000` maps to [`ShootingResponse::Accepted`];
/// any other value maps to [`ShootingResponse::Rejected`] with the raw code
/// preserved.
///
/// # Errors
/// Returns `BleProtocolError::InvalidPayloadLength` when `bytes.len() != 2`.
/// Returns `BleProtocolError::MalformedPayload` on an internal slice error
/// (should never fire given the length check, but is correct for defence in depth).
///
/// Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
pub fn parse_shooting_response(bytes: &[u8]) -> FujiResult<ShootingResponse> {
    if bytes.len() != SHOOTING_REQUEST_PAYLOAD_LEN {
        return Err(FujiError::BleProtocol(
            BleProtocolError::InvalidPayloadLength,
        ));
    }
    // bounds: checked len == 2 above; [0..2] is always in range
    let status = u16::from_le_bytes(
        bytes[0..2]
            .try_into()
            .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))?,
    );
    if status == 0x0000 {
        Ok(ShootingResponse::Accepted)
    } else {
        Ok(ShootingResponse::Rejected { raw_status: status })
    }
}

// ─── ApplicationInformation ──────────────────────────────────────────────────

/// Application information written to `CHR_CONNECTED_APPLICATION_INFORMATION`.
///
/// Payload: 3 bytes — `[app_id: u8, version_lo: u8, version_hi: u8]` (LE u16 version).
///
/// Source: ble-wifi-discovery.md §"Application Information". [H] XBL
/// Note: XApp uses app_id=0x80, version=0x0101. Frameport uses its own values.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct AppInfo {
    /// Frameport's application identifier. Must NOT be 0x80 (XApp's value).
    pub app_id: u8,
    /// Frameport's BLE connected version (LE u16).
    pub version: u16,
}

/// Build the 3-byte payload for a `CHR_CONNECTED_APPLICATION_INFORMATION` write.
///
/// Source: ble-wifi-discovery.md §"Application Information". [H] XBL
pub fn build_app_info(info: AppInfo) -> [u8; APP_INFO_PAYLOAD_LEN] {
    let version_bytes = info.version.to_le_bytes();
    [info.app_id, version_bytes[0], version_bytes[1]]
}

/// Parse a 3-byte `CHR_CONNECTED_APPLICATION_INFORMATION` payload.
///
/// # Errors
/// `BleProtocolError::InvalidPayloadLength` when `bytes.len() != 3`.
pub fn parse_app_info(bytes: &[u8]) -> FujiResult<AppInfo> {
    if bytes.len() != APP_INFO_PAYLOAD_LEN {
        return Err(FujiError::BleProtocol(
            BleProtocolError::InvalidPayloadLength,
        ));
    }
    // bounds: checked len == 3 above
    let app_id = bytes[0];
    let version = u16::from_le_bytes([bytes[1], bytes[2]]);
    Ok(AppInfo { app_id, version })
}

// ─── LocationAndSpeed ─────────────────────────────────────────────────────────

/// The 23-byte GPS location-and-speed payload written to `CHR_LOCATION_AND_SPEED`.
///
/// All fields are little-endian. Lat/lon/altitude/speed are scaled integers.
///
/// Source: ble-wifi-discovery.md §"LocationAndSpeed (GPS Geotagging)";
/// master-constants.md §5d. [H] LFJ, XBL
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LocationAndSpeed {
    /// Latitude in degrees × 10,000,000 (i32 LE).
    /// Example: 35.54308° → 355_430_800.
    pub latitude_scaled: i32,
    /// Longitude in degrees × 10,000,000 (i32 LE).
    pub longitude_scaled: i32,
    /// Altitude in metres, rounded to nearest integer (i32 LE).
    pub altitude_m: i32,
    /// Speed in metres/second × 100, rounded (i32 LE).
    pub speed_cm_per_s: i32,
    /// UTC year (u16 LE).
    pub year: u16,
    /// UTC month, 1-based (January = 1).
    pub month: u8,
    /// UTC day, 1-based.
    pub day: u8,
    /// UTC hours.
    pub hours: u8,
    /// UTC minutes.
    pub minutes: u8,
    /// UTC seconds.
    pub seconds: u8,
}

/// Build the 23-byte `CHR_LOCATION_AND_SPEED` payload from typed fields.
///
/// Source: ble-wifi-discovery.md §"LocationAndSpeed". [H] LFJ, XBL
pub fn build_location_and_speed(loc: &LocationAndSpeed) -> [u8; LOCATION_AND_SPEED_PAYLOAD_LEN] {
    let mut buf = [0u8; LOCATION_AND_SPEED_PAYLOAD_LEN];
    buf[0..4].copy_from_slice(&loc.latitude_scaled.to_le_bytes());
    buf[4..8].copy_from_slice(&loc.longitude_scaled.to_le_bytes());
    buf[8..12].copy_from_slice(&loc.altitude_m.to_le_bytes());
    buf[12..16].copy_from_slice(&loc.speed_cm_per_s.to_le_bytes());
    buf[16..18].copy_from_slice(&loc.year.to_le_bytes());
    buf[18] = loc.month;
    buf[19] = loc.day;
    buf[20] = loc.hours;
    buf[21] = loc.minutes;
    buf[22] = loc.seconds;
    buf
}

/// Parse a 23-byte `CHR_LOCATION_AND_SPEED` payload.
///
/// # Errors
/// `BleProtocolError::InvalidPayloadLength` when `bytes.len() != 23`.
///
/// Note: lat/lon are not validated — privacy-local-first.md forbids logging
/// them. Validation of value ranges (e.g. |lat| <= 90°) is the caller's
/// responsibility; this parser only checks structural validity.
pub fn parse_location_and_speed(bytes: &[u8]) -> FujiResult<LocationAndSpeed> {
    if bytes.len() != LOCATION_AND_SPEED_PAYLOAD_LEN {
        return Err(FujiError::BleProtocol(
            BleProtocolError::InvalidPayloadLength,
        ));
    }
    // bounds: each slice is within [0, 23) after length check
    let latitude_scaled = i32::from_le_bytes(
        bytes[0..4]
            .try_into()
            .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))?,
    );
    let longitude_scaled = i32::from_le_bytes(
        bytes[4..8]
            .try_into()
            .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))?,
    );
    let altitude_m = i32::from_le_bytes(
        bytes[8..12]
            .try_into()
            .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))?,
    );
    let speed_cm_per_s = i32::from_le_bytes(
        bytes[12..16]
            .try_into()
            .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))?,
    );
    let year = u16::from_le_bytes(
        bytes[16..18]
            .try_into()
            .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))?,
    );
    // bounds: indices 18-22 are within [0, 23)
    let month = bytes[18];
    let day = bytes[19];
    let hours = bytes[20];
    let minutes = bytes[21];
    let seconds = bytes[22];

    Ok(LocationAndSpeed {
        latitude_scaled,
        longitude_scaled,
        altitude_m,
        speed_cm_per_s,
        year,
        month,
        day,
        hours,
        minutes,
        seconds,
    })
}

// ─── SSID / Passphrase ───────────────────────────────────────────────────────

/// Parse the UTF-8 SSID string from `CHR_CAMERA_SSID_NAME_STRING`.
///
/// The characteristic delivers a raw UTF-8 byte sequence. Empty is permitted
/// by the protocol (an empty SSID means the camera is not advertising a
/// network), but the `BleWifiHandoff` constructor enforces non-empty.
///
/// Source: ble-wifi-discovery.md §"Camera Information (SSID / Passphrase / Identity)". [H] XPN, XBL
///
/// # Errors
/// `BleProtocolError::MalformedPayload` when `bytes` is not valid UTF-8.
pub fn parse_ssid(bytes: &[u8]) -> FujiResult<String> {
    std::str::from_utf8(bytes)
        .map(|s| s.to_owned())
        .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))
}

/// Parse the UTF-8 passphrase from `CHR_CAMERA_WIFI_PASSPHRASE_STRING`.
///
/// PRIVACY INVARIANT: the returned `String` MUST NEVER be passed to any
/// logging call (Timber, tracing, log, println) at any level in any build.
///
/// Source: ble-wifi-discovery.md §"Camera Information (SSID / Passphrase / Identity)". [H] XPN, XBL
///
/// # Errors
/// `BleProtocolError::MalformedPayload` when `bytes` is not valid UTF-8.
pub fn parse_passphrase(bytes: &[u8]) -> FujiResult<String> {
    std::str::from_utf8(bytes)
        .map(|s| s.to_owned())
        .map_err(|_| FujiError::BleProtocol(BleProtocolError::MalformedPayload))
}

// ─── DeviceName builder ───────────────────────────────────────────────────────

/// Build the ASCII device-name byte payload for `CHR_CONNECTED_DEVICE_NAME_STRING`.
///
/// Returns `Err` if `name` contains non-ASCII characters (the characteristic
/// expects ASCII).
///
/// Source: ble-wifi-discovery.md §"Device Name". [H] LFJ, XBL
///
/// # Errors
/// `BleProtocolError::MalformedPayload` when `name` is not pure ASCII.
pub fn build_device_name(name: &str) -> FujiResult<Vec<u8>> {
    if !name.is_ascii() {
        return Err(FujiError::BleProtocol(BleProtocolError::MalformedPayload));
    }
    Ok(name.as_bytes().to_vec())
}

// ─── LocationSyncCycle builder ────────────────────────────────────────────────

/// Build the 2-byte LE payload for `CHR_LOCATION_SYNC_CYCLE`.
///
/// `interval_secs` is the desired GPS update interval in seconds.
///
/// Source: ble-wifi-discovery.md §"Geolocation"; master-constants.md §5c. [H] XBL
pub fn build_location_sync_cycle(interval_secs: u16) -> [u8; 2] {
    interval_secs.to_le_bytes()
}

// ─── DisconnectReason builder ─────────────────────────────────────────────────

/// Build the 2-byte LE payload for `CHR_CONNECTED_DEVICE_DISCONNECTED_REASON`.
///
/// Write this before an intentional disconnect to inform the camera why the
/// client is disconnecting.
///
/// Source: ble-wifi-discovery.md §"Connected Device Information". [H] XBL
pub fn build_disconnect_reason(reason: u16) -> [u8; 2] {
    reason.to_le_bytes()
}

// ─── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── ShootingResponse ─────────────────────────────────────────────────────

    #[test]
    fn shooting_response_accepted_parses_from_zero_status() {
        // 0x0000 LE = camera accepted the shutter command.
        // Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
        let bytes = [0x00u8, 0x00];
        let resp = parse_shooting_response(&bytes).unwrap();
        assert_eq!(resp, ShootingResponse::Accepted);
    }

    #[test]
    fn shooting_response_rejected_preserves_nonzero_status() {
        // Any non-zero status is Rejected with the raw code preserved.
        // Synthetic fixture — status 0x0005 is not a documented Fujifilm code;
        // used here purely to exercise the Rejected path.
        let bytes = [0x05u8, 0x00]; // 0x0005 LE
        let resp = parse_shooting_response(&bytes).unwrap();
        assert_eq!(resp, ShootingResponse::Rejected { raw_status: 0x0005 });
    }

    #[test]
    fn shooting_response_rejected_high_byte_status() {
        // Status 0x0100 LE = [0x00, 0x01] — exercises big-endian confusion guard.
        let bytes = [0x00u8, 0x01]; // 0x0100 LE
        let resp = parse_shooting_response(&bytes).unwrap();
        assert_eq!(resp, ShootingResponse::Rejected { raw_status: 0x0100 });
    }

    #[test]
    fn shooting_response_wrong_length_returns_invalid_payload_length() {
        // Zero bytes — too short.
        let err = parse_shooting_response(&[]).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::InvalidPayloadLength)
        );
    }

    #[test]
    fn shooting_response_one_byte_returns_invalid_payload_length() {
        let err = parse_shooting_response(&[0x00]).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::InvalidPayloadLength)
        );
    }

    #[test]
    fn shooting_response_three_bytes_returns_invalid_payload_length() {
        let err = parse_shooting_response(&[0x00, 0x00, 0x00]).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::InvalidPayloadLength)
        );
    }

    // ── ShootingRequest ──────────────────────────────────────────────────────

    #[test]
    fn shooting_request_s0_encodes_to_zero() {
        assert_eq!(
            build_shooting_request(ShootingRequest::S0Release),
            [0x00, 0x00]
        );
    }

    #[test]
    fn shooting_request_s1_encodes_to_one() {
        assert_eq!(
            build_shooting_request(ShootingRequest::S1HalfPress),
            [0x01, 0x00]
        );
    }

    #[test]
    fn shooting_request_s2_encodes_to_two() {
        assert_eq!(
            build_shooting_request(ShootingRequest::S2FullPress),
            [0x02, 0x00]
        );
    }

    // ── ApplicationInformation ───────────────────────────────────────────────

    #[test]
    fn build_app_info_encodes_correctly() {
        let info = AppInfo {
            app_id: 0x01,
            version: 0x0102,
        };
        let bytes = build_app_info(info);
        assert_eq!(bytes, [0x01, 0x02, 0x01]);
    }

    #[test]
    fn parse_app_info_round_trips() {
        let info = AppInfo {
            app_id: 0x42,
            version: 0xABCD,
        };
        let bytes = build_app_info(info);
        let parsed = parse_app_info(&bytes).unwrap();
        assert_eq!(parsed, info);
    }

    #[test]
    fn parse_app_info_wrong_length_returns_error() {
        let err = parse_app_info(&[0x01, 0x02]).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::InvalidPayloadLength)
        );
    }

    // ── LocationAndSpeed ────────────────────────────────────────────────────

    #[test]
    fn location_and_speed_round_trips() {
        let loc = LocationAndSpeed {
            latitude_scaled: 355_430_800,
            longitude_scaled: 1_397_680_000,
            altitude_m: 42,
            speed_cm_per_s: 150,
            year: 2025,
            month: 6,
            day: 14,
            hours: 10,
            minutes: 30,
            seconds: 0,
        };
        let bytes = build_location_and_speed(&loc);
        assert_eq!(bytes.len(), LOCATION_AND_SPEED_PAYLOAD_LEN);
        let parsed = parse_location_and_speed(&bytes).unwrap();
        assert_eq!(parsed, loc);
    }

    #[test]
    fn parse_location_and_speed_wrong_length_returns_error() {
        let short = [0u8; 22];
        let err = parse_location_and_speed(&short).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::InvalidPayloadLength)
        );
    }

    #[test]
    fn parse_location_and_speed_too_long_returns_error() {
        let long = [0u8; 24];
        let err = parse_location_and_speed(&long).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::InvalidPayloadLength)
        );
    }

    // ── SSID / Passphrase ────────────────────────────────────────────────────

    #[test]
    fn parse_ssid_valid_utf8() {
        let bytes = b"FUJIFILM-X-T5-1234";
        let ssid = parse_ssid(bytes).unwrap();
        assert_eq!(ssid, "FUJIFILM-X-T5-1234");
    }

    #[test]
    fn parse_ssid_invalid_utf8_returns_malformed() {
        let invalid = [0xFF, 0xFE, 0x00];
        let err = parse_ssid(&invalid).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload)
        );
    }

    #[test]
    fn parse_passphrase_valid_utf8() {
        // Do not log the passphrase in real code — this is a test fixture only.
        let bytes = b"test-passphrase-fixture";
        let result = parse_passphrase(bytes).unwrap();
        // Only assert length to avoid any passphrase substring appearing in
        // assertion failure messages.
        assert_eq!(result.len(), bytes.len());
    }

    #[test]
    fn parse_passphrase_invalid_utf8_returns_malformed() {
        let invalid = [0x80u8, 0x81];
        let err = parse_passphrase(&invalid).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload)
        );
    }

    // ── DeviceName ──────────────────────────────────────────────────────────

    #[test]
    fn build_device_name_ascii_succeeds() {
        let bytes = build_device_name("Frameport-Test-0001").unwrap();
        assert_eq!(bytes, b"Frameport-Test-0001");
    }

    #[test]
    fn build_device_name_non_ascii_returns_malformed() {
        let err = build_device_name("Frameport-é").unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::MalformedPayload)
        );
    }

    // ── LocationSyncCycle ────────────────────────────────────────────────────

    #[test]
    fn build_location_sync_cycle_encodes_le() {
        // interval = 30s = 0x001E → LE bytes [0x1E, 0x00]
        assert_eq!(build_location_sync_cycle(30), [0x1E, 0x00]);
    }

    // ── DisconnectReason ─────────────────────────────────────────────────────

    #[test]
    fn build_disconnect_reason_encodes_le() {
        assert_eq!(build_disconnect_reason(0x0001), [0x01, 0x00]);
        assert_eq!(build_disconnect_reason(0x0000), [0x00, 0x00]);
    }
}
