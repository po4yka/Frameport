//! Fujifilm BLE pairing state machine.
//!
//! Models the observable pairing and reconnection flows documented in:
//! - `docs/reference/ble-wifi-discovery.md` §"Pairing and Registration Flow"
//! - `docs/reference/ble-wifi-discovery.md` §"BLE → Wi-Fi Handoff State Machine"
//!
//! # Design constraints
//! - Pure sync; no async, no I/O, no Android objects.
//! - No global mutable state. Every `PairingSession` is an owned value.
//! - State transitions are explicit methods that consume `self` and return
//!   `Result<NextState, FujiError>`. Invalid transitions return
//!   `BleProtocolError::UnsupportedCameraState`.
//!
//! The Android `camera/bluetooth` layer is responsible for orchestrating the
//! actual GATT reads/writes in the correct order. This module only tracks
//! which state the session is in and validates transitions.

use fuji_core::{BleProtocolError, FujiError, FujiResult};

use crate::advertisement::PairingToken;
use crate::handoff::BleWifiHandoff;

// ─── PairingKind ─────────────────────────────────────────────────────────────

/// Whether this is an initial pairing or a reconnection to an already-paired
/// camera.
///
/// This determines which characteristic read/write sequence the Android layer
/// must execute.
///
/// Source: ble-wifi-discovery.md §"Initial Pairing Sequence" vs
/// §"Reconnect / Name-Update Sequence". [H] LFJ, XBL
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PairingKind {
    /// Camera is not yet paired. The pairing token from the advertisement must
    /// be written to `CHR_PAIRING_KEY`.
    InitialPairing,
    /// Camera is already paired. Skip writing the pairing key; only write
    /// `CHR_CONNECTED_DEVICE_NAME_STRING`.
    Reconnect,
}

// ─── PairingState ────────────────────────────────────────────────────────────

/// States in the BLE pairing / handoff state machine.
///
/// Transitions are enforced by [`PairingSession`] methods. The Android layer
/// drives the transitions by calling the appropriate method after each GATT
/// operation completes.
///
/// Source: ble-wifi-discovery.md §"BLE → Wi-Fi Handoff State Machine". [H] LFJ, XBL
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PairingState {
    /// Services have been discovered on the connected GATT server. Awaiting
    /// the decision of whether initial pairing or reconnect is needed.
    ServicesDiscovered,

    /// Initial pairing is in progress. The 4-byte token has been confirmed
    /// from the advertisement and must be written to `CHR_PAIRING_KEY`.
    ///
    /// Source: ble-wifi-discovery.md §"Initial Pairing Sequence". [H] LFJ, XBL
    PairingInProgress { token: PairingToken },

    /// Pairing key write succeeded. Android must now write
    /// `CHR_CONNECTED_DEVICE_NAME_STRING`.
    ///
    /// Source: ble-wifi-discovery.md §"Write sequence" step 2. [H] LFJ, XBL
    PairingKeyAccepted,

    /// Device name was written. Optionally write
    /// `CHR_CONNECTED_APPLICATION_INFORMATION`. Pairing is complete.
    ///
    /// Source: ble-wifi-discovery.md §"After the device name write...". [H] XBL
    PairingAccepted,

    /// Camera is already paired (reconnect path). Android must read
    /// `CHR_CAMERA_SSID_NAME_STRING` and `CHR_CAMERA_WIFI_PASSPHRASE_STRING`.
    ///
    /// Source: ble-wifi-discovery.md §"Reconnect / Name-Update Sequence". [H] LFJ, XBL
    CredentialRead,

    /// SSID and passphrase have been read and validated by Rust.
    /// Android can now build a `WifiNetworkSpecifier`.
    HandoffReady { handoff: BleWifiHandoff },

    /// Terminal error state. The session cannot proceed.
    Failed { reason: BleProtocolError },
}

// ─── PairingSession ──────────────────────────────────────────────────────────

/// An owned, non-global pairing session.
///
/// Created when Android completes GATT service discovery. The Android layer
/// calls transition methods in response to GATT operation callbacks. Each
/// method returns `FujiResult<PairingSession>` with the new state embedded.
///
/// # Error handling
/// Invalid transitions (e.g., calling `accept_pairing_key()` from
/// `CredentialRead`) return `BleProtocolError::UnsupportedCameraState`.
/// The session is consumed on error; create a new session to retry.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PairingSession {
    pub kind: PairingKind,
    pub state: PairingState,
}

impl PairingSession {
    /// Create a new pairing session in the `ServicesDiscovered` state.
    ///
    /// `kind` determines which sequence the Android layer will execute.
    pub fn new(kind: PairingKind) -> Self {
        Self {
            kind,
            state: PairingState::ServicesDiscovered,
        }
    }

    /// Transition: services discovered → pairing in progress.
    ///
    /// Called when the Android layer has determined the camera is not yet
    /// paired. The advertisement `token` must have been extracted by
    /// `parse_manufacturer_payload()` before calling this.
    ///
    /// Valid from: `ServicesDiscovered` with `kind == InitialPairing`.
    ///
    /// # Errors
    /// `BleProtocolError::UnsupportedCameraState` if called from the wrong
    /// state or with `kind == Reconnect`.
    pub fn begin_initial_pairing(self, token: PairingToken) -> FujiResult<Self> {
        match (&self.state, self.kind) {
            (PairingState::ServicesDiscovered, PairingKind::InitialPairing) => Ok(Self {
                kind: self.kind,
                state: PairingState::PairingInProgress { token },
            }),
            _ => Err(FujiError::BleProtocol(
                BleProtocolError::UnsupportedCameraState,
            )),
        }
    }

    /// Transition: services discovered → credential read (reconnect path).
    ///
    /// Called when the Android layer has determined the camera is already
    /// paired. Android will next read SSID and passphrase characteristics.
    ///
    /// Valid from: `ServicesDiscovered` with `kind == Reconnect`.
    ///
    /// # Errors
    /// `BleProtocolError::UnsupportedCameraState` if called from the wrong state
    /// or with `kind == InitialPairing`.
    pub fn begin_reconnect(self) -> FujiResult<Self> {
        match (&self.state, self.kind) {
            (PairingState::ServicesDiscovered, PairingKind::Reconnect) => Ok(Self {
                kind: self.kind,
                state: PairingState::CredentialRead,
            }),
            _ => Err(FujiError::BleProtocol(
                BleProtocolError::UnsupportedCameraState,
            )),
        }
    }

    /// Transition: pairing in progress → pairing key accepted.
    ///
    /// Called when Android successfully writes the 4-byte token to
    /// `CHR_PAIRING_KEY`. Android must next write `CHR_CONNECTED_DEVICE_NAME_STRING`.
    ///
    /// Valid from: `PairingInProgress`.
    ///
    /// # Errors
    /// `BleProtocolError::UnsupportedCameraState` from any other state.
    pub fn accept_pairing_key(self) -> FujiResult<Self> {
        match &self.state {
            PairingState::PairingInProgress { .. } => Ok(Self {
                kind: self.kind,
                state: PairingState::PairingKeyAccepted,
            }),
            _ => Err(FujiError::BleProtocol(
                BleProtocolError::UnsupportedCameraState,
            )),
        }
    }

    /// Transition: pairing key accepted → pairing accepted.
    ///
    /// Called when Android successfully writes `CHR_CONNECTED_DEVICE_NAME_STRING`.
    ///
    /// Valid from: `PairingKeyAccepted`.
    ///
    /// # Errors
    /// `BleProtocolError::UnsupportedCameraState` from any other state.
    pub fn accept_device_name(self) -> FujiResult<Self> {
        match &self.state {
            PairingState::PairingKeyAccepted => Ok(Self {
                kind: self.kind,
                state: PairingState::PairingAccepted,
            }),
            _ => Err(FujiError::BleProtocol(
                BleProtocolError::UnsupportedCameraState,
            )),
        }
    }

    /// Transition: pairing accepted OR credential read → handoff ready.
    ///
    /// Called when Rust has validated the SSID and passphrase read from the
    /// camera. The `handoff` value is produced by `BleWifiHandoff::new()`.
    ///
    /// Valid from: `PairingAccepted` (initial pairing path, post-credential-read)
    /// or `CredentialRead` (reconnect path).
    ///
    /// # Errors
    /// `BleProtocolError::UnsupportedCameraState` from any other state.
    pub fn credentials_validated(self, handoff: BleWifiHandoff) -> FujiResult<Self> {
        match &self.state {
            PairingState::PairingAccepted | PairingState::CredentialRead => Ok(Self {
                kind: self.kind,
                state: PairingState::HandoffReady { handoff },
            }),
            _ => Err(FujiError::BleProtocol(
                BleProtocolError::UnsupportedCameraState,
            )),
        }
    }

    /// Transition: any state → failed (terminal).
    ///
    /// Called when an unrecoverable error occurs during any phase. The session
    /// cannot be recovered; the Android layer must disconnect and create a new
    /// `PairingSession` to retry.
    pub fn fail(self, reason: BleProtocolError) -> Self {
        Self {
            kind: self.kind,
            state: PairingState::Failed { reason },
        }
    }

    /// Returns `true` if the session is in a terminal state (either
    /// `HandoffReady` or `Failed`).
    pub fn is_terminal(&self) -> bool {
        matches!(
            self.state,
            PairingState::HandoffReady { .. } | PairingState::Failed { .. }
        )
    }

    /// Extract the validated `BleWifiHandoff` if the session has reached
    /// `HandoffReady`.
    ///
    /// Returns `None` if the session is not yet in the `HandoffReady` state.
    pub fn handoff(&self) -> Option<&BleWifiHandoff> {
        match &self.state {
            PairingState::HandoffReady { handoff } => Some(handoff),
            _ => None,
        }
    }
}

// ─── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::handoff::BleWifiHandoff;

    fn dummy_token() -> PairingToken {
        PairingToken([0x11, 0x22, 0x33, 0x44])
    }

    fn dummy_handoff() -> BleWifiHandoff {
        BleWifiHandoff::new(
            "FUJIFILM-X-T5-TEST".to_owned(),
            "test-pass-fixture".to_owned(),
        )
        .unwrap()
    }

    // ── InitialPairing happy path ────────────────────────────────────────────

    #[test]
    fn initial_pairing_full_sequence_reaches_handoff_ready() {
        let session = PairingSession::new(PairingKind::InitialPairing);
        assert_eq!(session.state, PairingState::ServicesDiscovered);

        let session = session.begin_initial_pairing(dummy_token()).unwrap();
        assert!(matches!(
            session.state,
            PairingState::PairingInProgress { .. }
        ));

        let session = session.accept_pairing_key().unwrap();
        assert_eq!(session.state, PairingState::PairingKeyAccepted);

        let session = session.accept_device_name().unwrap();
        assert_eq!(session.state, PairingState::PairingAccepted);

        let session = session.credentials_validated(dummy_handoff()).unwrap();
        assert!(matches!(session.state, PairingState::HandoffReady { .. }));
        assert!(session.is_terminal());
        assert!(session.handoff().is_some());
    }

    // ── Reconnect happy path ─────────────────────────────────────────────────

    #[test]
    fn reconnect_full_sequence_reaches_handoff_ready() {
        let session = PairingSession::new(PairingKind::Reconnect);
        let session = session.begin_reconnect().unwrap();
        assert_eq!(session.state, PairingState::CredentialRead);

        let session = session.credentials_validated(dummy_handoff()).unwrap();
        assert!(matches!(session.state, PairingState::HandoffReady { .. }));
        assert!(session.is_terminal());
    }

    // ── Invalid transitions return UnsupportedCameraState ────────────────────

    #[test]
    fn begin_initial_pairing_on_reconnect_kind_fails() {
        let session = PairingSession::new(PairingKind::Reconnect);
        let err = session.begin_initial_pairing(dummy_token()).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::UnsupportedCameraState)
        );
    }

    #[test]
    fn begin_reconnect_on_initial_kind_fails() {
        let session = PairingSession::new(PairingKind::InitialPairing);
        let err = session.begin_reconnect().unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::UnsupportedCameraState)
        );
    }

    #[test]
    fn accept_pairing_key_from_wrong_state_fails() {
        let session = PairingSession::new(PairingKind::InitialPairing);
        // Still in ServicesDiscovered — not PairingInProgress
        let err = session.accept_pairing_key().unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::UnsupportedCameraState)
        );
    }

    #[test]
    fn accept_device_name_from_wrong_state_fails() {
        let session = PairingSession::new(PairingKind::InitialPairing);
        // Skip directly to accept_device_name — invalid
        let err = session.accept_device_name().unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::UnsupportedCameraState)
        );
    }

    #[test]
    fn credentials_validated_from_pairing_in_progress_fails() {
        let session = PairingSession::new(PairingKind::InitialPairing);
        let session = session.begin_initial_pairing(dummy_token()).unwrap();
        // Can't jump to HandoffReady before accept_pairing_key + accept_device_name
        let err = session.credentials_validated(dummy_handoff()).unwrap_err();
        assert_eq!(
            err,
            FujiError::BleProtocol(BleProtocolError::UnsupportedCameraState)
        );
    }

    // ── fail() transition ────────────────────────────────────────────────────

    #[test]
    fn fail_transitions_to_failed_state() {
        let session = PairingSession::new(PairingKind::InitialPairing);
        let session = session.fail(BleProtocolError::PairingRejected);
        assert!(matches!(session.state, PairingState::Failed { .. }));
        assert!(session.is_terminal());
        assert!(session.handoff().is_none());
    }

    // ── is_terminal ─────────────────────────────────────────────────────────

    #[test]
    fn services_discovered_is_not_terminal() {
        let session = PairingSession::new(PairingKind::InitialPairing);
        assert!(!session.is_terminal());
    }

    #[test]
    fn credential_read_is_not_terminal() {
        let session = PairingSession::new(PairingKind::Reconnect);
        let session = session.begin_reconnect().unwrap();
        assert!(!session.is_terminal());
    }
}
