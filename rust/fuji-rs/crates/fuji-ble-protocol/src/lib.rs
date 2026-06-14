//! `fuji-ble-protocol` — Fujifilm BLE advertisement parsing, characteristic
//! payload encoding/decoding, pairing state machine, and Wi-Fi handoff
//! credential validation.
//!
//! # Scope
//! Pure synchronous Rust. No async, no JNI, no Android objects. This crate
//! stands alone and is tested independently.
//!
//! # Architecture boundary
//! Rust owns: advertisement parsing, characteristic payload encode/decode,
//! pairing state-machine logic, `BleWifiHandoff` validation.
//! Android owns: BluetoothGatt lifecycle, BLE scanning, GATT operations,
//! Wi-Fi network requests.
//!
//! # Privacy
//! - Passphrase bytes must never appear in any log call at any level.
//! - BLE MAC address must never appear in any log outside a
//!   `frameport-dev-logs`-feature-gated `tracing::trace!` (not in v1).
//! - Raw BLE payload bytes must never appear in Timber/log calls.
//!
//! Source: `docs/reference/ble-wifi-discovery.md`
//! Source: `docs/reference/master-constants.md`

#![forbid(unsafe_code)]

pub mod advertisement;
pub mod constants;
pub mod handoff;
pub mod pairing;
pub mod payloads;

// Re-export the most commonly used items at crate root for ergonomic access.
pub use advertisement::{
    CameraAdvertisement, PairingToken, parse_manufacturer_payload, parse_raw_manufacturer_data,
};
pub use handoff::BleWifiHandoff;
pub use pairing::{PairingKind, PairingSession, PairingState};
pub use payloads::{
    AppInfo, LocationAndSpeed, ShootingRequest, build_app_info, build_device_name,
    build_disconnect_reason, build_location_and_speed, build_location_sync_cycle,
    build_shooting_request, parse_app_info, parse_location_and_speed, parse_passphrase, parse_ssid,
};
