//! Integration tests for `fuji-ble-protocol`.
//!
//! All fixtures are synthetic — constructed from documented payload layouts.
//! No real device captures, MACs, SSIDs, passphrases, or serial numbers appear here.
//!
//! GT-NAMED-TESTS (verifier asserts these exact function names):
//! - `advertisement_parser_returns_typed_camera_advertisement_from_fixture`
//! - `malformed_handoff_payload_returns_ble_protocol_error_not_panic`
//!   (second named test lives in `handoff.rs` as a unit test per GT-RUST-CRATE;
//!   it is also re-verified here for the integration harness)
//!
//! Source: docs/reference/ble-wifi-discovery.md, master-constants.md §5

use fuji_ble_protocol::{
    PairingToken,
    advertisement::{parse_manufacturer_payload, parse_raw_manufacturer_data},
    handoff::BleWifiHandoff,
    pairing::{PairingKind, PairingSession, PairingState},
    payloads::{
        AppInfo, LocationAndSpeed, ShootingRequest, ShootingResponse, build_app_info,
        build_location_and_speed, build_shooting_request, parse_app_info, parse_location_and_speed,
        parse_shooting_response, parse_ssid,
    },
};
use fuji_core::{BleProtocolError, FujiError};

// ─── Synthetic fixture bytes ─────────────────────────────────────────────────
//
// Layout per ble-wifi-discovery.md / master-constants.md §5a:
//
//   Full raw manufacturer-specific data:
//   [0..2] = company ID 0x04D8 in LE = [0xD8, 0x04]
//   [2]    = type byte (0x02 = pairing mode; other = not pairing)
//   [3..7] = pairing token (only valid when type == 0x02)
//
// Token values below are synthetic counter-pattern bytes. They do NOT
// represent any real camera pairing token.

/// Synthetic manufacturer data: pairing mode, token = [0x01, 0x02, 0x03, 0x04].
const FIXTURE_PAIRING_MODE_RAW: &[u8] = &[
    0xD8, 0x04, // company ID 0x04D8 LE
    0x02, // type byte: pairing mode
    0x01, 0x02, 0x03, 0x04, // synthetic token
];

/// Synthetic manufacturer data: NOT in pairing mode (type byte = 0x01).
const FIXTURE_NON_PAIRING_MODE_RAW: &[u8] = &[
    0xD8, 0x04, // company ID 0x04D8 LE
    0x01, // type byte: not pairing mode
    0x00, 0x00, 0x00, 0x00,
];

/// Synthetic manufacturer data: wrong company ID (not 0x04D8).
const FIXTURE_WRONG_COMPANY_ID: &[u8] = &[
    0x00, 0x00, // company ID 0x0000 — not Fujifilm
    0x02, 0x01, 0x02, 0x03, 0x04,
];

/// Synthetic manufacturer payload (post-company-ID strip): pairing mode, token=[0xAA,0xBB,0xCC,0xDD].
const FIXTURE_PAYLOAD_PAIRING: &[u8] = &[0x02, 0xAA, 0xBB, 0xCC, 0xDD];

/// Synthetic manufacturer payload: not pairing mode.
const FIXTURE_PAYLOAD_NOT_PAIRING: &[u8] = &[0x00, 0x00, 0x00, 0x00, 0x00];

/// Truncated pairing payload — only type byte, no token (should be MalformedPayload).
const FIXTURE_PAYLOAD_PAIRING_TRUNCATED: &[u8] = &[0x02];

/// 23-byte synthetic LocationAndSpeed fixture.
/// Layout: lat(4) + lon(4) + alt(4) + speed(4) + year(2) + month(1) + day(1) + h(1) + m(1) + s(1)
/// All zeroed — exercising the boundary of parse_location_and_speed.
const FIXTURE_LOCATION_ZEROED: &[u8] = &[0u8; 23];

/// 23-byte synthetic LocationAndSpeed fixture with known field values.
/// lat=355430800 (LE), lon=0, alt=0, speed=0, year=2025, month=6, day=14, 10:30:00
fn fixture_location_known() -> [u8; 23] {
    let loc = LocationAndSpeed {
        latitude_scaled: 355_430_800,
        longitude_scaled: 0,
        altitude_m: 0,
        speed_cm_per_s: 0,
        year: 2025,
        month: 6,
        day: 14,
        hours: 10,
        minutes: 30,
        seconds: 0,
    };
    build_location_and_speed(&loc)
}

// ─── Named test 1 (GT-NAMED-TESTS) ──────────────────────────────────────────

/// GT-NAMED-TEST: `advertisement_parser_returns_typed_camera_advertisement_from_fixture`
///
/// Verifies that the advertisement parser:
/// 1. Correctly identifies a Fujifilm pairing-mode advertisement from synthetic bytes.
/// 2. Extracts the pairing token verbatim.
/// 3. Identifies a non-pairing advertisement without extracting a token.
/// 4. Rejects malformed/truncated payloads with MalformedPayload.
#[test]
fn advertisement_parser_returns_typed_camera_advertisement_from_fixture() {
    // ── Pairing mode: raw manufacturer data (includes company ID) ──────────
    let adv = parse_raw_manufacturer_data(FIXTURE_PAIRING_MODE_RAW).unwrap();
    assert!(
        adv.is_in_pairing_mode,
        "fixture pairing mode raw: expected is_in_pairing_mode=true"
    );
    let token = adv.pairing_token.expect("expected pairing token present");
    assert_eq!(
        token.0,
        [0x01, 0x02, 0x03, 0x04],
        "token bytes must match fixture"
    );

    // ── Non-pairing mode: raw manufacturer data ─────────────────────────────
    let adv = parse_raw_manufacturer_data(FIXTURE_NON_PAIRING_MODE_RAW).unwrap();
    assert!(
        !adv.is_in_pairing_mode,
        "fixture non-pairing mode: expected is_in_pairing_mode=false"
    );
    assert!(
        adv.pairing_token.is_none(),
        "non-pairing mode must have no token"
    );

    // ── Wrong company ID: must reject ─────────────────────────────────────
    let err = parse_raw_manufacturer_data(FIXTURE_WRONG_COMPANY_ID).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::MalformedPayload),
        "wrong company ID must produce MalformedPayload"
    );

    // ── Payload-only (post-company-ID strip) ─────────────────────────────
    let adv = parse_manufacturer_payload(FIXTURE_PAYLOAD_PAIRING).unwrap();
    assert!(adv.is_in_pairing_mode);
    let token = adv.pairing_token.unwrap();
    assert_eq!(token.0, [0xAA, 0xBB, 0xCC, 0xDD]);

    let adv = parse_manufacturer_payload(FIXTURE_PAYLOAD_NOT_PAIRING).unwrap();
    assert!(!adv.is_in_pairing_mode);
    assert!(adv.pairing_token.is_none());

    // ── Truncated pairing payload: must produce MalformedPayload ─────────
    let err = parse_manufacturer_payload(FIXTURE_PAYLOAD_PAIRING_TRUNCATED).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::MalformedPayload),
        "truncated pairing payload must produce MalformedPayload"
    );

    // ── Empty payload ─────────────────────────────────────────────────────
    let err = parse_manufacturer_payload(&[]).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::MalformedPayload),
        "empty payload must produce MalformedPayload"
    );
}

// ─── Named test 2 (GT-NAMED-TESTS) ──────────────────────────────────────────

/// GT-NAMED-TEST: `malformed_handoff_payload_returns_ble_protocol_error_not_panic`
///
/// Verifies that all malformed handoff paths produce typed errors, never panics.
/// This is the integration-test counterpart to the unit test in `handoff.rs`.
#[test]
fn malformed_handoff_payload_returns_ble_protocol_error_not_panic() {
    // Empty SSID → MissingWifiCredentials
    let err = BleWifiHandoff::new(String::new(), "pass".to_owned()).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::MissingWifiCredentials)
    );

    // Empty passphrase → MalformedPayload
    let err = BleWifiHandoff::new("FUJIFILM-X-T5-0000".to_owned(), String::new()).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::MalformedPayload)
    );

    // Invalid UTF-8 SSID bytes → MalformedPayload (no panic)
    let bad_ssid_bytes = [0xC3u8, 0x28]; // invalid 2-byte UTF-8 sequence
    let err = parse_ssid(&bad_ssid_bytes).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::MalformedPayload)
    );

    // Invalid UTF-8 passphrase bytes → MalformedPayload (no panic)
    let bad_pass_bytes = [0xFFu8, 0xFE];
    let err = fuji_ble_protocol::payloads::parse_passphrase(&bad_pass_bytes).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::MalformedPayload)
    );

    // Truncated raw manufacturer data for pairing mode → MalformedPayload (no panic)
    let truncated_raw = [0xD8u8, 0x04, 0x02, 0x01]; // only 1 of 4 token bytes
    let err = parse_raw_manufacturer_data(&truncated_raw).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::MalformedPayload)
    );
}

// ─── Advertisement fixture tests ─────────────────────────────────────────────

#[test]
fn fixture_pairing_token_as_bytes_matches_raw_fields() {
    let token = PairingToken([0x01, 0x02, 0x03, 0x04]);
    assert_eq!(token.as_bytes(), &[0x01, 0x02, 0x03, 0x04]);
}

// ─── LocationAndSpeed fixture tests ──────────────────────────────────────────

#[test]
fn fixture_location_zeroed_parses_to_all_zero_fields() {
    let loc = parse_location_and_speed(FIXTURE_LOCATION_ZEROED).unwrap();
    assert_eq!(loc.latitude_scaled, 0);
    assert_eq!(loc.longitude_scaled, 0);
    assert_eq!(loc.altitude_m, 0);
    assert_eq!(loc.speed_cm_per_s, 0);
    assert_eq!(loc.year, 0);
    assert_eq!(loc.month, 0);
    assert_eq!(loc.day, 0);
    assert_eq!(loc.hours, 0);
    assert_eq!(loc.minutes, 0);
    assert_eq!(loc.seconds, 0);
}

#[test]
fn fixture_location_known_round_trips_correctly() {
    let bytes = fixture_location_known();
    let loc = parse_location_and_speed(&bytes).unwrap();
    assert_eq!(loc.latitude_scaled, 355_430_800);
    assert_eq!(loc.year, 2025);
    assert_eq!(loc.month, 6);
    assert_eq!(loc.day, 14);
    assert_eq!(loc.hours, 10);
    assert_eq!(loc.minutes, 30);
    assert_eq!(loc.seconds, 0);
}

#[test]
fn location_22_byte_fixture_returns_invalid_payload_length() {
    let short = [0u8; 22];
    let err = parse_location_and_speed(&short).unwrap_err();
    assert_eq!(
        err,
        FujiError::BleProtocol(BleProtocolError::InvalidPayloadLength)
    );
}

// ─── ShootingRequest fixture tests ───────────────────────────────────────────

#[test]
fn shooting_request_fixture_s0_s1_s2_encode_as_le_u16() {
    // S0=0x0000, S1=0x0001, S2=0x0002 — all LE
    // Source: ble-wifi-discovery.md §"ShootingRequest". [H] LFJ, XPN, XBL
    assert_eq!(
        build_shooting_request(ShootingRequest::S0Release),
        [0x00, 0x00]
    );
    assert_eq!(
        build_shooting_request(ShootingRequest::S1HalfPress),
        [0x01, 0x00]
    );
    assert_eq!(
        build_shooting_request(ShootingRequest::S2FullPress),
        [0x02, 0x00]
    );
}

// ─── AppInfo fixture tests ───────────────────────────────────────────────────

#[test]
fn app_info_fixture_encodes_and_parses_correctly() {
    // Frameport app info: app_id=0x01, version=0x0100
    // XApp uses 0x80 / 0x0101 — we must use different values.
    // Source: ble-wifi-discovery.md §"Application Information". [H] XBL
    let info = AppInfo {
        app_id: 0x01,
        version: 0x0100,
    };
    let bytes = build_app_info(info);
    assert_eq!(bytes.len(), 3);
    assert_eq!(bytes[0], 0x01); // app_id
    assert_eq!(bytes[1], 0x00); // version lo (LE)
    assert_eq!(bytes[2], 0x01); // version hi (LE)

    let parsed = parse_app_info(&bytes).unwrap();
    assert_eq!(parsed.app_id, 0x01);
    assert_eq!(parsed.version, 0x0100);
}

// ─── Pairing state machine integration ───────────────────────────────────────

#[test]
fn pairing_state_machine_initial_pairing_happy_path() {
    let token = PairingToken([0x01, 0x02, 0x03, 0x04]);
    let handoff = BleWifiHandoff::new(
        "FUJIFILM-X-T5-0000".to_owned(),
        "fixture-wpa2-key".to_owned(),
    )
    .unwrap();

    let session = PairingSession::new(PairingKind::InitialPairing);
    assert_eq!(session.state, PairingState::ServicesDiscovered);

    let session = session.begin_initial_pairing(token).unwrap();
    assert!(matches!(
        session.state,
        PairingState::PairingInProgress { .. }
    ));

    let session = session.accept_pairing_key().unwrap();
    assert_eq!(session.state, PairingState::PairingKeyAccepted);

    let session = session.accept_device_name().unwrap();
    assert_eq!(session.state, PairingState::PairingAccepted);

    let session = session.credentials_validated(handoff.clone()).unwrap();
    assert!(session.is_terminal());
    let h = session.handoff().unwrap();
    assert_eq!(h.ssid(), "FUJIFILM-X-T5-0000");
}

#[test]
fn pairing_state_machine_reconnect_happy_path() {
    let handoff = BleWifiHandoff::new(
        "FUJIFILM-X-T5-0000".to_owned(),
        "fixture-wpa2-key".to_owned(),
    )
    .unwrap();

    let session = PairingSession::new(PairingKind::Reconnect);
    let session = session.begin_reconnect().unwrap();
    assert_eq!(session.state, PairingState::CredentialRead);

    let session = session.credentials_validated(handoff).unwrap();
    assert!(session.is_terminal());
    assert!(session.handoff().is_some());
}

#[test]
fn pairing_state_machine_fail_transitions_to_terminal() {
    let session = PairingSession::new(PairingKind::InitialPairing);
    let session = session.fail(BleProtocolError::PairingRejected);
    assert!(session.is_terminal());
    assert!(matches!(session.state, PairingState::Failed { .. }));
    assert!(session.handoff().is_none());
}

// ─── BleWifiHandoff integration tests ────────────────────────────────────────

#[test]
fn ble_wifi_handoff_debug_does_not_leak_passphrase() {
    let handoff = BleWifiHandoff::new(
        "FUJIFILM-X-T5-0000".to_owned(),
        "fixture-secret-key".to_owned(),
    )
    .unwrap();
    let debug = format!("{handoff:?}");
    assert!(
        !debug.contains("fixture-secret-key"),
        "passphrase leaked in Debug output: {debug}"
    );
    assert!(debug.contains("<redacted>"), "expected <redacted>: {debug}");
}

#[test]
fn ble_wifi_handoff_accessors_return_correct_values() {
    let handoff = BleWifiHandoff::new(
        "FUJIFILM-X-T5-TEST".to_owned(),
        "test-pass-fixture".to_owned(),
    )
    .unwrap();
    assert_eq!(handoff.ssid(), "FUJIFILM-X-T5-TEST");
    // Only assert length to avoid passphrase substring in failure messages.
    assert_eq!(handoff.passphrase().len(), "test-pass-fixture".len());
}

// ─── ShootingResponse golden fixture tests ────────────────────────────────────
//
// Each fixture is a 2-byte LE u16 synthetic payload constructed from the
// documented layout in ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)".
//
// Fixture files under tests/fixtures/:
//   shooting_response_accepted.bin         — [0x00, 0x00] → Accepted
//   shooting_response_rejected_0x0005.bin  — [0x05, 0x00] → Rejected { raw_status: 5 }
//   shooting_response_rejected_0x0100.bin  — [0x00, 0x01] → Rejected { raw_status: 256 }
//
// Governed by golden-bless-discipline.md — do not regenerate without human
// approval and a commit-message rationale.
//
// UNCERTAINTY NOTE (master-constants.md line 836): No source provides a
// comprehensive table of camera-returned status codes for CHR_SHOOTING_REQUEST
// beyond the success/failure distinction.  These fixtures exercise the
// structural parser only; the semantic mapping of non-zero codes is uncertain
// pending X-T5 hardware verification.
// Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL

/// Golden: `shooting_response_accepted.bin` → `ShootingResponse::Accepted`.
///
/// Fixture bytes: `[0x00, 0x00]` (u16 LE value 0, meaning camera accepted the
/// shutter command).
/// Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
#[test]
fn golden_shooting_response_accepted_fixture_parses_to_accepted() {
    let fixture = include_bytes!("fixtures/shooting_response_accepted.bin");
    assert_eq!(fixture.len(), 2, "fixture must be exactly 2 bytes (u16 LE)");
    // Verify raw bytes match documented layout.
    assert_eq!(fixture[0], 0x00, "byte 0 (status lo) must be 0x00");
    assert_eq!(fixture[1], 0x00, "byte 1 (status hi) must be 0x00");
    let resp = parse_shooting_response(fixture).unwrap();
    assert_eq!(
        resp,
        ShootingResponse::Accepted,
        "0x0000 LE must parse to ShootingResponse::Accepted"
    );
}

/// Golden: `shooting_response_rejected_0x0005.bin` → `ShootingResponse::Rejected { raw_status: 5 }`.
///
/// Fixture bytes: `[0x05, 0x00]` (u16 LE value 5, non-zero → Rejected).
/// The value 5 is synthetic — it is not a documented Fujifilm status code.
/// It exercises the Rejected path and the LE byte ordering.
/// Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
#[test]
fn golden_shooting_response_rejected_0x0005_fixture_parses_to_rejected() {
    let fixture = include_bytes!("fixtures/shooting_response_rejected_0x0005.bin");
    assert_eq!(fixture.len(), 2, "fixture must be exactly 2 bytes");
    assert_eq!(fixture[0], 0x05, "byte 0 (status lo) must be 0x05");
    assert_eq!(fixture[1], 0x00, "byte 1 (status hi) must be 0x00");
    let resp = parse_shooting_response(fixture).unwrap();
    assert_eq!(
        resp,
        ShootingResponse::Rejected { raw_status: 0x0005 },
        "0x0005 LE must parse to ShootingResponse::Rejected {{ raw_status: 5 }}"
    );
}

/// Golden: `shooting_response_rejected_0x0100.bin` → `ShootingResponse::Rejected { raw_status: 256 }`.
///
/// Fixture bytes: `[0x00, 0x01]` (u16 LE value 256 = 0x0100).  This fixture
/// guards against big-endian confusion: a big-endian parser would read these
/// bytes as 0x0001 (1), but the correct LE interpretation is 0x0100 (256).
/// Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
#[test]
fn golden_shooting_response_rejected_0x0100_fixture_guards_endian_confusion() {
    let fixture = include_bytes!("fixtures/shooting_response_rejected_0x0100.bin");
    assert_eq!(fixture.len(), 2, "fixture must be exactly 2 bytes");
    assert_eq!(fixture[0], 0x00, "byte 0 (status lo) must be 0x00");
    assert_eq!(fixture[1], 0x01, "byte 1 (status hi) must be 0x01");
    let resp = parse_shooting_response(fixture).unwrap();
    assert_eq!(
        resp,
        ShootingResponse::Rejected { raw_status: 0x0100 },
        "0x0100 LE must parse to ShootingResponse::Rejected {{ raw_status: 256 }}"
    );
    // Sanity-check: a big-endian misread would produce raw_status=1, not 256.
    assert_ne!(
        resp,
        ShootingResponse::Rejected { raw_status: 0x0001 },
        "must NOT be misread as big-endian (raw_status=1)"
    );
}

/// Coverage: half-press, full-press, and release request encoding is unchanged.
///
/// Confirms that `ShootingRequest` round-trips are unaffected by the addition
/// of `ShootingResponse`.
/// Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
#[test]
fn shooting_request_half_full_release_coverage_after_response_addition() {
    // S0 = release
    assert_eq!(
        build_shooting_request(ShootingRequest::S0Release),
        [0x00, 0x00],
        "S0Release must encode to [0x00, 0x00]"
    );
    // S1 = half-press / AF
    assert_eq!(
        build_shooting_request(ShootingRequest::S1HalfPress),
        [0x01, 0x00],
        "S1HalfPress must encode to [0x01, 0x00]"
    );
    // S2 = full-press / capture
    assert_eq!(
        build_shooting_request(ShootingRequest::S2FullPress),
        [0x02, 0x00],
        "S2FullPress must encode to [0x02, 0x00]"
    );
}

// ─── Proptest: advertisement parser panic-safety over arbitrary byte slices ───
//
// Required by llm-rust-prompts.md: BLE payload parsers that handle untrusted
// bytes MUST have a proptest (or fuzz target) that asserts they never panic and
// never read out of bounds for any input.
//
// Both public parser entry points are exercised:
//   - `parse_manufacturer_payload`: receives the post-company-ID byte slice as
//     delivered by the Android BLE stack.
//   - `parse_raw_manufacturer_data`: receives the full manufacturer-specific
//     data including the 2-byte company ID prefix.
//
// The invariant: for any &[u8] input the parser MUST return Ok(_) or a typed
// FujiError::BleProtocol(_). It must NEVER panic, never abort, and never index
// out of bounds.

#[cfg(test)]
mod proptest_advertisement {
    use fuji_ble_protocol::advertisement::{
        parse_manufacturer_payload, parse_raw_manufacturer_data,
    };
    use fuji_core::FujiError;
    use proptest::prelude::*;

    proptest! {
        /// Feed arbitrary byte slices to `parse_manufacturer_payload` and assert it
        /// never panics and always returns a typed Ok or FujiError::BleProtocol.
        #[test]
        fn manufacturer_payload_never_panics_on_arbitrary_input(
            bytes in proptest::collection::vec(any::<u8>(), 0..=64)
        ) {
            match parse_manufacturer_payload(&bytes) {
                Ok(_) => {}
                Err(FujiError::BleProtocol(_)) => {}
                Err(other) => {
                    panic!(
                        "parse_manufacturer_payload returned unexpected error variant: {other:?}"
                    );
                }
            }
        }

        /// Feed arbitrary byte slices to `parse_raw_manufacturer_data` and assert it
        /// never panics and always returns a typed Ok or FujiError::BleProtocol.
        #[test]
        fn raw_manufacturer_data_never_panics_on_arbitrary_input(
            bytes in proptest::collection::vec(any::<u8>(), 0..=64)
        ) {
            match parse_raw_manufacturer_data(&bytes) {
                Ok(_) => {}
                Err(FujiError::BleProtocol(_)) => {}
                Err(other) => {
                    panic!(
                        "parse_raw_manufacturer_data returned unexpected error variant: {other:?}"
                    );
                }
            }
        }

        /// Verify the boundary: exactly-minimum-length pairing-mode payloads parse
        /// successfully, one-byte-short payloads return MalformedPayload.
        #[test]
        fn manufacturer_payload_pairing_mode_boundary(token in any::<[u8; 4]>()) {
            // Exactly 5 bytes (1 type + 4 token) — must succeed.
            let mut exact = [0u8; 5];
            exact[0] = 0x02; // PAIRING_MODE_TYPE_BYTE
            exact[1..5].copy_from_slice(&token);
            assert!(
                parse_manufacturer_payload(&exact).is_ok(),
                "5-byte pairing payload must parse OK"
            );

            // 4 bytes (truncated by one) — must return MalformedPayload.
            let truncated = &exact[..4];
            assert!(
                parse_manufacturer_payload(truncated).is_err(),
                "4-byte pairing payload must return Err"
            );
        }

        /// Verify that a correct Fujifilm company ID prefix with arbitrary payload
        /// suffix never panics in `parse_raw_manufacturer_data`.
        #[test]
        fn raw_data_with_correct_company_id_never_panics(
            suffix in proptest::collection::vec(any::<u8>(), 0..=32)
        ) {
            // Prepend the valid Fujifilm company ID (0x04D8 LE = [0xD8, 0x04]).
            let mut bytes = Vec::with_capacity(2 + suffix.len());
            bytes.push(0xD8);
            bytes.push(0x04);
            bytes.extend_from_slice(&suffix);
            match parse_raw_manufacturer_data(&bytes) {
                Ok(_) | Err(FujiError::BleProtocol(_)) => {}
                Err(other) => {
                    panic!(
                        "parse_raw_manufacturer_data with valid company ID returned unexpected error: {other:?}"
                    );
                }
            }
        }
    }
}
