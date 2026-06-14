//! `fuji-core` — domain primitives and typed error hierarchy for the Frameport native SDK.
//!
//! This crate is pure synchronous Rust. No Android types, no JNI, no async, no sockets.

#![forbid(unsafe_code)]

use std::fmt::{Display, Formatter};

use thiserror::Error;

// ─── SDK version ───────────────────────────────────────────────────────────────

pub const SDK_VERSION: &str = env!("CARGO_PKG_VERSION");

// ─── Identity primitives ────────────────────────────────────────────────────────

#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct CameraId(String);

impl CameraId {
    pub fn new(value: impl Into<String>) -> FujiResult<Self> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(FujiError::InvalidInput("camera id cannot be empty"));
        }
        Ok(Self(value))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct CameraModel(String);

impl CameraModel {
    pub fn placeholder() -> Self {
        Self("Placeholder camera model".to_owned())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct FirmwareVersion(String);

impl FirmwareVersion {
    pub fn new(value: impl Into<String>) -> FujiResult<Self> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(FujiError::InvalidInput("firmware version cannot be empty"));
        }
        Ok(Self(value))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub struct SessionId(i64);

impl SessionId {
    pub fn new(value: i64) -> FujiResult<Self> {
        if value <= 0 {
            return Err(FujiError::InvalidInput("session id must be positive"));
        }
        Ok(Self(value))
    }

    pub fn get(self) -> i64 {
        self.0
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum TransportKind {
    WifiPtpIp,
    BluetoothLe,
    UsbPtp,
    Noop,
}

impl Display for TransportKind {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            TransportKind::WifiPtpIp => formatter.write_str("wifi-ptp-ip"),
            TransportKind::BluetoothLe => formatter.write_str("bluetooth-le"),
            TransportKind::UsbPtp => formatter.write_str("usb-ptp"),
            TransportKind::Noop => formatter.write_str("noop"),
        }
    }
}

// ─── Media object primitives (legacy) ──────────────────────────────────────────

#[derive(Clone, Debug, Eq, PartialEq, Hash)]
pub struct MediaObjectId(String);

impl MediaObjectId {
    pub fn new(value: impl Into<String>) -> FujiResult<Self> {
        let value = value.into();
        if value.trim().is_empty() {
            return Err(FujiError::InvalidInput("media object id cannot be empty"));
        }
        Ok(Self(value))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Legacy media format enum (used by existing callers such as `fuji-transfer`).
/// For new code prefer [`CameraMediaFormat`] which carries wire codes.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum MediaFormat {
    Jpeg,
    Raw,
    Heif,
    Movie,
    Unknown,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct MediaObject {
    pub id: MediaObjectId,
    pub file_name: Option<String>,
    pub format: MediaFormat,
    pub size_bytes: Option<u64>,
    pub captured_at_epoch_millis: Option<i64>,
}

// ─── Camera object handle (PTP u32) ────────────────────────────────────────────

/// A PTP object handle — the camera's u32 identifier for a media object.
///
/// Distinct from the string-keyed [`MediaObjectId`] used by the legacy layer.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct CameraObjectId(u32);

impl CameraObjectId {
    pub fn new(value: u32) -> Self {
        Self(value)
    }

    pub fn get(self) -> u32 {
        self.0
    }
}

// ─── CameraMediaFormat — canonical wire-code enum ──────────────────────────────

/// Fujifilm PTP object format codes from master-constants.md §2b.
///
/// | Variant | Wire code | Source |
/// |---------|-----------|--------|
/// | Jpeg    | 0x3801 (14337) | [H] FJH, XPN, XBL |
/// | Raf     | 0xB103 (45315) | [H] FJH, XPN, XBL |
/// | Heif    | 0xB982 (47490) | [H] XPN, XBL |
/// | Mov     | 0x300D (12301) | [H] XPN, XBL |
/// | Unknown | raw_code       | fallback for unrecognised codes |
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub enum CameraMediaFormat {
    /// Standard JPEG — wire code 0x3801 (14337). [H] FJH, XPN, XBL
    Jpeg,
    /// Fujifilm RAW — wire code 0xB103 (45315). [H] FJH, XPN, XBL
    Raf,
    /// HEIF — wire code 0xB982 (47490). [H] XPN, XBL
    Heif,
    /// MOV video — wire code 0x300D (12301). [H] XPN, XBL
    Mov,
    /// Unknown or unrecognised format code; stores the raw value when available.
    Unknown { raw_code: Option<u32> },
}

impl CameraMediaFormat {
    /// Map a PTP wire code to the canonical [`CameraMediaFormat`].
    ///
    /// Unknown codes are preserved in [`CameraMediaFormat::Unknown::raw_code`]
    /// so the caller can log or inspect them without data loss.
    pub fn from_wire_code(code: u32) -> Self {
        match code {
            // 0x3801 — standard JPEG (master-constants.md §2b, §4)
            0x3801 => Self::Jpeg,
            // 0x3808 — JPEG/rotated variant (master-constants.md §4, section 4).
            // Observed on some Fujifilm bodies; treated as JPEG for import purposes.
            0x3808 => Self::Jpeg,
            0xB103 => Self::Raf,
            0xB982 => Self::Heif,
            0x300D => Self::Mov,
            // NOTE: No confirmed MP4 wire code exists in master-constants.md as of M05.
            // MP4 and any other unrecognised codes map to Unknown { raw_code }.
            // Do NOT invent an Mp4 variant without a verified wire-code source.
            other => Self::Unknown {
                raw_code: Some(other),
            },
        }
    }

    /// Return the PTP wire code for known formats.
    ///
    /// For [`CameraMediaFormat::Unknown`], returns the stored `raw_code` if
    /// present, otherwise `None`.
    pub fn to_wire_code(self) -> Option<u32> {
        match self {
            Self::Jpeg => Some(0x3801),
            Self::Raf => Some(0xB103),
            Self::Heif => Some(0xB982),
            Self::Mov => Some(0x300D),
            Self::Unknown { raw_code } => raw_code,
        }
    }
}

// ─── CameraMediaObject ─────────────────────────────────────────────────────────

/// A media object on the camera as seen through PTP enumeration.
///
/// `filename_opaque_hash` stores a redacted, non-reversible representation of
/// the file name per `privacy-local-first.md` — the raw filename is never
/// persisted in domain structs.
#[derive(Clone, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct CameraMediaObject {
    pub object_handle: CameraObjectId,
    pub format: CameraMediaFormat,
    pub size_bytes: Option<u64>,
    /// UTC capture timestamp as seconds since Unix epoch, when available.
    pub capture_date_utc: Option<i64>,
    /// SHA-256 (or similar) hash of the original filename for dedup, not the
    /// raw filename itself.  See `privacy-local-first.md`.
    pub filename_opaque_hash: Option<String>,
}

// ─── TransferProgress ──────────────────────────────────────────────────────────

/// Progress snapshot for an in-flight object transfer.
///
/// The `fraction()` method clamps to `[0.0, 1.0]` and is safe against
/// division-by-zero when `total_bytes == 0`.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct TransferProgress {
    pub bytes_transferred: u64,
    pub total_bytes: u64,
    pub object_handle: CameraObjectId,
    pub transfer_id: u64,
}

impl TransferProgress {
    /// Returns the transfer fraction in `[0.0, 1.0]`.
    ///
    /// - Returns `0.0` when `total_bytes == 0` (no division-by-zero).
    /// - Clamps to `1.0` if `bytes_transferred > total_bytes`.
    pub fn fraction(&self) -> f64 {
        if self.total_bytes == 0 {
            return 0.0;
        }
        let f = self.bytes_transferred as f64 / self.total_bytes as f64;
        f.min(1.0)
    }
}

// ─── ImportState ───────────────────────────────────────────────────────────────

/// State machine for a single media import operation.
///
/// Transitions:
/// ```text
/// Pending → Connecting → Transferring { progress } → Finalizing → Complete
///        ↘ (any) → Failed { error }
/// ```
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ImportState {
    Pending,
    Connecting,
    Transferring { progress: TransferProgress },
    Finalizing,
    Complete,
    Failed { error: FujiError },
}

// ─── FunctionMode ──────────────────────────────────────────────────────────────

/// SDK function mode codes passed to `SetFunctionMode` after `OpenSession`.
///
/// Source: master-constants.md §4b "SDK function mode codes (XApp)". [H] XPN, XBL
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
#[repr(u16)]
pub enum FunctionMode {
    /// IMAGE_RECEIVE — master-constants.md §4b code 1. [H] XPN, XBL
    ImageReceive = 1,
    /// REMOTE — master-constants.md §4b code 5. [H] XPN, XBL
    RemoteCapture = 5,
    /// NEUTRAL20 — master-constants.md §4b code 6. [H] XPN, XBL
    Neutral20 = 6,
    /// FW_DATA_TRANSFER — master-constants.md §4b code 19. [H] XPN, XBL
    FwDataTransfer = 19,
    /// IMAGE_VIEW_V2 — master-constants.md §4b code 20. [H] XPN, XBL
    ImageViewV2 = 20,
    /// RESERVED_PHOTO_RECEIVED20 — master-constants.md §4b code 21. [H] XPN, XBL
    ReservedPhotoReceived20 = 21,
    /// IMAGE_LIVE_VIEW — master-constants.md §4b code 22. [H] XPN, XBL
    Liveview = 22,
}

// ─── CameraState ───────────────────────────────────────────────────────────────

/// Domain-level camera operational state.
///
/// The wire source is PTP property 0xDF00 (`CameraState`):
/// 0=WaitForAccess, 1=MultipleTransfer, 2=FullAccess, 3=PcAutoSave, 6=RemoteAccess.
/// This enum stays platform-neutral; wire decoding belongs in `fuji-ptpip`.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum CameraState {
    Ready,
    Busy,
    Sleeping,
    PoweredOff,
    Disconnected,
    RemoteMode,
}

// ─── DiagnosticCategory ────────────────────────────────────────────────────────

/// Corresponds to `ErrorLayer` in `docs/protocol/error-model.md`.
///
/// NOTE: The doc defines `NativeBoundary`; this enum spells it `Native` so that
/// `fuji-diagnostics` (which references `DiagnosticCategory::Native`) keeps
/// compiling without change.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum DiagnosticCategory {
    UserAction,
    Permission,
    Bluetooth,
    Wifi,
    Socket,
    Usb,
    /// The native/JNI boundary layer (`NativeBoundary` in error-model.md).
    Native,
    Transport,
    Protocol,
    CameraState,
    MediaTransfer,
    Storage,
    Location,
    Diagnostics,
}

// ─── DiagnosticEvent ───────────────────────────────────────────────────────────

/// A single structured diagnostic event.
///
/// `category` IS the layer field — the method `layer()` is an alias accessor so
/// callers can use either name. `timestamp_epoch_micros == 0` in placeholder
/// events (real timestamps are filled in at runtime).
///
/// The sequence number is assigned by [`DiagnosticTimeline`] on insertion; it is
/// not part of the event itself to keep events self-contained.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DiagnosticEvent {
    pub category: DiagnosticCategory,
    pub message: String,
    /// Microseconds since Unix epoch. `0` indicates "unknown / placeholder".
    pub timestamp_epoch_micros: i64,
}

impl DiagnosticEvent {
    /// Construct a placeholder event with `timestamp_epoch_micros = 0`.
    ///
    /// Signature is intentionally `(DiagnosticCategory, impl Into<String>)` with
    /// exactly two positional arguments to remain compatible with `fuji-diagnostics`.
    pub fn placeholder(category: DiagnosticCategory, message: impl Into<String>) -> Self {
        Self {
            category,
            message: message.into(),
            timestamp_epoch_micros: 0,
        }
    }

    /// Accessor alias for `category`, satisfying the "layer" vocabulary in
    /// `error-model.md` without adding a redundant field.
    pub fn layer(&self) -> DiagnosticCategory {
        self.category
    }
}

// ─── DiagnosticTimeline ────────────────────────────────────────────────────────

/// An ordered, Vec-backed collection of [`DiagnosticEvent`]s with insertion-order
/// preservation and monotonically increasing per-event sequence numbers.
///
/// Sequence numbers are assigned by the timeline on `push()` (1-based, never
/// reset). They are stored alongside the event as `(sequence_number, event)`
/// pairs. This keeps events themselves simple while allowing callers to detect
/// gaps or reorder if needed.
#[derive(Clone, Debug, Default)]
pub struct DiagnosticTimeline {
    events: Vec<(u64, DiagnosticEvent)>,
    next_seq: u64,
}

impl DiagnosticTimeline {
    pub fn new() -> Self {
        Self::default()
    }

    /// Append an event and assign it the next sequence number.
    pub fn push(&mut self, event: DiagnosticEvent) {
        self.next_seq += 1;
        self.events.push((self.next_seq, event));
    }

    /// Return a slice of `(sequence_number, event)` pairs in insertion order.
    pub fn events(&self) -> &[(u64, DiagnosticEvent)] {
        &self.events
    }

    /// Iterate over `(sequence_number, event)` pairs.
    pub fn iter(&self) -> impl Iterator<Item = &(u64, DiagnosticEvent)> {
        self.events.iter()
    }

    pub fn len(&self) -> usize {
        self.events.len()
    }

    pub fn is_empty(&self) -> bool {
        self.events.is_empty()
    }
}

// ─── Sub-error enums (Rust-owned typed families) ───────────────────────────────

/// Transport-layer errors (fd-backed stream / TCP).
///
/// Source: error-model.md `Transport.*` family.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq, Hash)]
pub enum TransportError {
    #[error("transport:read-failed")]
    ReadFailed,
    #[error("transport:write-failed")]
    WriteFailed,
    #[error("transport:timeout")]
    Timeout,
    #[error("transport:connection-reset")]
    ConnectionReset,
    #[error("transport:eof")]
    Eof,
    #[error("transport:session-closed")]
    SessionClosed,
    #[error("transport:backpressure-limit-reached")]
    BackpressureLimitReached,
    #[error("transport:unexpected-channel-close")]
    UnexpectedChannelClose,
    #[error("transport:channel-not-open")]
    ChannelNotOpen,
    /// The event channel (PTP-IP port 55741) was closed by the peer or timed out.
    ///
    /// Returned by `EventChannelReader` when the TCP stream reaches EOF or the
    /// remote closes the connection after `InitiateOpenCapture`.
    #[error("transport:event-channel-closed")]
    EventChannelClosed,
}

/// PTP/PTP-IP protocol errors.
///
/// Source: error-model.md `Protocol.*` family.
/// PTP response code reference (master-constants.md §4b):
/// OK=0x2001, GeneralError=0x2002, SessionNotOpen=0x2003,
/// InvalidTransactionID=0x2004, OperationNotSupported=0x2005,
/// SessionAlreadyOpen=0x201E, BUSY=0x2019.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq, Hash)]
pub enum ProtocolError {
    #[error("protocol:handshake-rejected")]
    HandshakeRejected,
    #[error("protocol:open-session-failed")]
    OpenSessionFailed,
    #[error("protocol:close-session-failed")]
    CloseSessionFailed,
    #[error("protocol:unexpected-packet")]
    UnexpectedPacket,
    #[error("protocol:invalid-packet-length")]
    InvalidPacketLength { declared: u32, minimum: u32 },
    #[error("protocol:invalid-transaction-id")]
    InvalidTransactionId,
    #[error("protocol:response-mismatch")]
    ResponseMismatch,
    #[error("protocol:unsupported-operation")]
    UnsupportedOperation,
    #[error("protocol:unsupported-function-mode")]
    UnsupportedFunctionMode,
    #[error("protocol:operation-timeout")]
    OperationTimeout,
    #[error("protocol:operation-rejected")]
    OperationRejected,
    #[error("protocol:session-not-open")]
    SessionNotOpen,
    #[error("protocol:session-already-open")]
    SessionAlreadyOpen,
    #[error("protocol:event-channel-unavailable")]
    EventChannelUnavailable,
    #[error("protocol:liveview-channel-unavailable")]
    LiveViewChannelUnavailable,
}

/// Camera-side operational state errors.
///
/// Source: error-model.md `Camera.*` family.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq, Hash)]
pub enum CameraError {
    /// Camera responded BUSY. `attempts` is the number of tries made before giving up.
    #[error("camera:busy after {attempts} attempts")]
    Busy { attempts: u32 },
    #[error("camera:disconnected")]
    Disconnected,
    #[error("camera:sleeping")]
    Sleeping,
    #[error("camera:powered-off")]
    PoweredOff,
    #[error("camera:mode-unsupported")]
    ModeUnsupported,
    #[error("camera:card-missing")]
    CardMissing,
    #[error("camera:card-busy")]
    CardBusy,
    #[error("camera:card-full")]
    CardFull,
    #[error("camera:storage-unavailable")]
    StorageUnavailable,
    #[error("camera:battery-low")]
    BatteryLow,
    #[error("camera:firmware-unsupported")]
    FirmwareUnsupported,
    #[error("camera:firmware-untested")]
    FirmwareUntested,
    #[error("camera:user-action-required")]
    UserActionRequiredOnCamera,
    #[error("camera:operation-rejected")]
    OperationRejected,
    #[error("camera:remote-mode-unavailable")]
    RemoteModeUnavailable,
    #[error("camera:location-sync-unsupported")]
    LocationSyncUnsupported,
}

/// Media object and thumbnail errors.
///
/// Source: error-model.md `Media.*` and `Thumbnail.*` families.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq, Hash)]
pub enum MediaError {
    #[error("media:object-count-unavailable")]
    ObjectCountUnavailable,
    #[error("media:object-handles-unavailable")]
    ObjectHandlesUnavailable,
    #[error("media:object-info-unavailable")]
    ObjectInfoUnavailable,
    #[error("media:unsupported-object-format")]
    UnsupportedObjectFormat,
    #[error("media:unknown-object-format")]
    UnknownObjectFormat,
    #[error("media:stale-object-list")]
    StaleObjectList,
    #[error("media:invalid-filename")]
    InvalidFilename,
    #[error("media:metadata-unavailable")]
    MetadataUnavailable,
    #[error("thumbnail:not-available")]
    ThumbnailNotAvailable,
    #[error("thumbnail:unsupported-format")]
    ThumbnailUnsupportedFormat,
    #[error("thumbnail:timeout")]
    ThumbnailTimeout,
    #[error("thumbnail:decode-failed")]
    ThumbnailDecodeFailed,
}

/// Object transfer errors.
///
/// Source: error-model.md `Transfer.*` family.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq, Hash)]
pub enum TransferError {
    #[error("transfer:object-not-found")]
    ObjectNotFound,
    #[error("transfer:partial-read-failed")]
    PartialReadFailed,
    #[error("transfer:size-mismatch")]
    SizeMismatch { expected: u64, actual: u64 },
    #[error("transfer:thumbnail-too-large")]
    ThumbnailTooLarge { size: u64, limit: u64 },
    #[error("transfer:camera-disconnected")]
    CameraDisconnected,
    #[error("transfer:timeout")]
    Timeout,
    #[error("transfer:cancelled")]
    Cancelled,
    #[error("transfer:unsupported-format")]
    UnsupportedFormat,
    #[error("transfer:output-write-failed")]
    OutputWriteFailed,
}

/// Storage errors observable from Rust (fd-streaming subset).
///
/// MediaStore-create/finalize/permission variants are Android-owned and are
/// NOT modelled here per the Android/Rust boundary in CLAUDE.md.
/// Source: error-model.md `Storage.*` family (Rust-observable subset).
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq, Hash)]
pub enum StorageError {
    #[error("storage:output-fd-invalid")]
    OutputFdInvalid,
    #[error("storage:output-write-failed")]
    OutputWriteFailed,
    #[error("storage:insufficient-space")]
    InsufficientSpace,
    #[error("storage:destination-unavailable")]
    DestinationUnavailable,
}

/// JNI/native-boundary errors.
///
/// Source: error-model.md `Native.*` family.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq, Hash)]
pub enum NativeError {
    #[error("native:library-load-failed")]
    LibraryLoadFailed,
    #[error("native:symbol-missing")]
    SymbolMissing,
    #[error("native:session-handle-invalid")]
    SessionHandleInvalid,
    #[error("native:panic-prevented")]
    PanicPrevented,
    #[error("native:callback-failed")]
    CallbackFailed,
    #[error("native:callback-dropped")]
    CallbackDropped,
    #[error("native:invalid-argument")]
    InvalidArgument,
    #[error("native:result-decode-failed")]
    ResultDecodeFailed,
    #[error("native:thread-attach-failed")]
    ThreadAttachFailed,
    #[error("native:unexpected-null")]
    UnexpectedNull,
    #[error("native:unsupported-abi")]
    UnsupportedAbi,
}

/// Fujifilm BLE protocol payload errors.
///
/// Source: error-model.md `BleProtocol.*` family.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq, Hash)]
pub enum BleProtocolError {
    #[error("ble-protocol:malformed-payload")]
    MalformedPayload,
    #[error("ble-protocol:invalid-payload-length")]
    InvalidPayloadLength,
    #[error("ble-protocol:unsupported-camera-state")]
    UnsupportedCameraState,
    #[error("ble-protocol:pairing-required")]
    PairingRequired,
    #[error("ble-protocol:pairing-rejected")]
    PairingRejected,
    #[error("ble-protocol:pairing-stale")]
    PairingStale,
    #[error("ble-protocol:missing-wifi-credentials")]
    MissingWifiCredentials,
    #[error("ble-protocol:unsupported-characteristic-payload")]
    UnsupportedCharacteristicPayload,
    #[error("ble-protocol:location-payload-unsupported")]
    LocationPayloadUnsupported,
}

// ─── Top-level FujiError ───────────────────────────────────────────────────────

/// The top-level error type for the Frameport native SDK.
///
/// The 6 original variants are kept **unchanged** for non-regression (used by
/// `fuji-sim`, `fuji-ptpip`, `fuji-liveview`, `fuji-transfer`, `fuji-ble-protocol`,
/// `fuji-usb-ptp`).  The new typed sub-error variants are added alongside them.
///
/// Must stay `Clone + Debug + Error + Eq + PartialEq` — `io::Error` is
/// intentionally excluded; fuji-core is pure (see CLAUDE.md).
#[derive(Clone, Debug, Error, Eq, PartialEq)]
pub enum FujiError {
    // ── Original 6 variants (non-regression) ──────────────────────────────────
    #[error("invalid input: {0}")]
    InvalidInput(&'static str),
    #[error("native sdk is not initialized")]
    NotInitialized,
    #[error("operation is not implemented: {0}")]
    NotImplemented(&'static str),
    #[error("session was not found: {0}")]
    SessionNotFound(i64),
    #[error("protocol is unavailable: {0}")]
    ProtocolUnavailable(&'static str),
    #[error("transport is unavailable: {0:?}")]
    TransportUnavailable(TransportKind),

    // ── New typed sub-error wrappers ──────────────────────────────────────────
    #[error(transparent)]
    Transport(#[from] TransportError),
    #[error(transparent)]
    Protocol(#[from] ProtocolError),
    #[error(transparent)]
    Camera(#[from] CameraError),
    #[error(transparent)]
    Media(#[from] MediaError),
    #[error(transparent)]
    Transfer(#[from] TransferError),
    #[error(transparent)]
    Storage(#[from] StorageError),
    #[error(transparent)]
    Native(#[from] NativeError),
    #[error(transparent)]
    BleProtocol(#[from] BleProtocolError),
}

pub type FujiResult<T> = Result<T, FujiError>;

// ─── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── Non-regression (original tests) ───────────────────────────────────────

    #[test]
    fn rejects_empty_camera_id() {
        assert_eq!(
            CameraId::new(" ").unwrap_err(),
            FujiError::InvalidInput("camera id cannot be empty"),
        );
    }

    #[test]
    fn accepts_positive_session_id() {
        assert_eq!(SessionId::new(7).unwrap().get(), 7);
    }

    // ── Helper: compile-time trait bound check ────────────────────────────────
    // Called at compile time; the body is empty.
    fn assert_traits<T: Clone + std::fmt::Debug + PartialEq>() {}

    #[test]
    fn trait_bounds_camera_media_object() {
        assert_traits::<CameraMediaObject>();
    }

    #[test]
    fn trait_bounds_transfer_progress() {
        assert_traits::<TransferProgress>();
    }

    #[test]
    fn trait_bounds_import_state() {
        assert_traits::<ImportState>();
    }

    // ── Redaction deny-list ────────────────────────────────────────────────────
    // Verify that no Display message for any error variant contains:
    //   - a "/" character (path leak)
    //   - a MAC-address-shaped substring (XX:XX pattern)
    //   - the literal substrings "password", "passphrase", "ssid", "serial"

    fn contains_mac_pattern(s: &str) -> bool {
        // Hand-rolled: look for two hex chars followed by ':' followed by two hex chars.
        let bytes = s.as_bytes();
        if bytes.len() < 5 {
            return false;
        }
        for i in 0..bytes.len().saturating_sub(4) {
            let is_hex = |b: u8| b.is_ascii_hexdigit();
            if is_hex(bytes[i])
                && is_hex(bytes[i + 1])
                && bytes[i + 2] == b':'
                && is_hex(bytes[i + 3])
                && is_hex(bytes[i + 4])
            {
                return true;
            }
        }
        false
    }

    fn check_display(label: &str, msg: &str) {
        assert!(!msg.is_empty(), "{label}: Display must not be empty");
        assert!(
            !msg.contains('/'),
            "{label}: Display must not contain '/' (path leak): {msg:?}"
        );
        assert!(
            !contains_mac_pattern(msg),
            "{label}: Display must not contain MAC-like pattern: {msg:?}"
        );
        let lower = msg.to_lowercase();
        for forbidden in &["password", "passphrase", "ssid", "serial"] {
            assert!(
                !lower.contains(forbidden),
                "{label}: Display must not contain {forbidden:?}: {msg:?}"
            );
        }
    }

    macro_rules! check_variant {
        ($variant:expr, $label:expr) => {
            check_display($label, &format!("{}", $variant));
        };
    }

    // TransportError — all 9 variants
    #[test]
    fn redaction_transport_error() {
        check_variant!(TransportError::ReadFailed, "TransportError::ReadFailed");
        check_variant!(TransportError::WriteFailed, "TransportError::WriteFailed");
        check_variant!(TransportError::Timeout, "TransportError::Timeout");
        check_variant!(
            TransportError::ConnectionReset,
            "TransportError::ConnectionReset"
        );
        check_variant!(TransportError::Eof, "TransportError::Eof");
        check_variant!(
            TransportError::SessionClosed,
            "TransportError::SessionClosed"
        );
        check_variant!(
            TransportError::BackpressureLimitReached,
            "TransportError::BackpressureLimitReached"
        );
        check_variant!(
            TransportError::UnexpectedChannelClose,
            "TransportError::UnexpectedChannelClose"
        );
        check_variant!(
            TransportError::ChannelNotOpen,
            "TransportError::ChannelNotOpen"
        );
        check_variant!(
            TransportError::EventChannelClosed,
            "TransportError::EventChannelClosed"
        );
    }

    // ProtocolError — all 15 variants
    #[test]
    fn redaction_protocol_error() {
        check_variant!(
            ProtocolError::HandshakeRejected,
            "ProtocolError::HandshakeRejected"
        );
        check_variant!(
            ProtocolError::OpenSessionFailed,
            "ProtocolError::OpenSessionFailed"
        );
        check_variant!(
            ProtocolError::CloseSessionFailed,
            "ProtocolError::CloseSessionFailed"
        );
        check_variant!(
            ProtocolError::UnexpectedPacket,
            "ProtocolError::UnexpectedPacket"
        );
        check_variant!(
            ProtocolError::InvalidPacketLength {
                declared: 4,
                minimum: 12
            },
            "ProtocolError::InvalidPacketLength"
        );
        check_variant!(
            ProtocolError::InvalidTransactionId,
            "ProtocolError::InvalidTransactionId"
        );
        check_variant!(
            ProtocolError::ResponseMismatch,
            "ProtocolError::ResponseMismatch"
        );
        check_variant!(
            ProtocolError::UnsupportedOperation,
            "ProtocolError::UnsupportedOperation"
        );
        check_variant!(
            ProtocolError::UnsupportedFunctionMode,
            "ProtocolError::UnsupportedFunctionMode"
        );
        check_variant!(
            ProtocolError::OperationTimeout,
            "ProtocolError::OperationTimeout"
        );
        check_variant!(
            ProtocolError::OperationRejected,
            "ProtocolError::OperationRejected"
        );
        check_variant!(
            ProtocolError::SessionNotOpen,
            "ProtocolError::SessionNotOpen"
        );
        check_variant!(
            ProtocolError::SessionAlreadyOpen,
            "ProtocolError::SessionAlreadyOpen"
        );
        check_variant!(
            ProtocolError::EventChannelUnavailable,
            "ProtocolError::EventChannelUnavailable"
        );
        check_variant!(
            ProtocolError::LiveViewChannelUnavailable,
            "ProtocolError::LiveViewChannelUnavailable"
        );
    }

    // CameraError — all 16 variants
    #[test]
    fn redaction_camera_error() {
        check_variant!(CameraError::Busy { attempts: 0 }, "CameraError::Busy");
        check_variant!(CameraError::Disconnected, "CameraError::Disconnected");
        check_variant!(CameraError::Sleeping, "CameraError::Sleeping");
        check_variant!(CameraError::PoweredOff, "CameraError::PoweredOff");
        check_variant!(CameraError::ModeUnsupported, "CameraError::ModeUnsupported");
        check_variant!(CameraError::CardMissing, "CameraError::CardMissing");
        check_variant!(CameraError::CardBusy, "CameraError::CardBusy");
        check_variant!(CameraError::CardFull, "CameraError::CardFull");
        check_variant!(
            CameraError::StorageUnavailable,
            "CameraError::StorageUnavailable"
        );
        check_variant!(CameraError::BatteryLow, "CameraError::BatteryLow");
        check_variant!(
            CameraError::FirmwareUnsupported,
            "CameraError::FirmwareUnsupported"
        );
        check_variant!(
            CameraError::FirmwareUntested,
            "CameraError::FirmwareUntested"
        );
        check_variant!(
            CameraError::UserActionRequiredOnCamera,
            "CameraError::UserActionRequiredOnCamera"
        );
        check_variant!(
            CameraError::OperationRejected,
            "CameraError::OperationRejected"
        );
        check_variant!(
            CameraError::RemoteModeUnavailable,
            "CameraError::RemoteModeUnavailable"
        );
        check_variant!(
            CameraError::LocationSyncUnsupported,
            "CameraError::LocationSyncUnsupported"
        );
    }

    // MediaError — all 12 variants
    #[test]
    fn redaction_media_error() {
        check_variant!(
            MediaError::ObjectCountUnavailable,
            "MediaError::ObjectCountUnavailable"
        );
        check_variant!(
            MediaError::ObjectHandlesUnavailable,
            "MediaError::ObjectHandlesUnavailable"
        );
        check_variant!(
            MediaError::ObjectInfoUnavailable,
            "MediaError::ObjectInfoUnavailable"
        );
        check_variant!(
            MediaError::UnsupportedObjectFormat,
            "MediaError::UnsupportedObjectFormat"
        );
        check_variant!(
            MediaError::UnknownObjectFormat,
            "MediaError::UnknownObjectFormat"
        );
        check_variant!(MediaError::StaleObjectList, "MediaError::StaleObjectList");
        check_variant!(MediaError::InvalidFilename, "MediaError::InvalidFilename");
        check_variant!(
            MediaError::MetadataUnavailable,
            "MediaError::MetadataUnavailable"
        );
        check_variant!(
            MediaError::ThumbnailNotAvailable,
            "MediaError::ThumbnailNotAvailable"
        );
        check_variant!(
            MediaError::ThumbnailUnsupportedFormat,
            "MediaError::ThumbnailUnsupportedFormat"
        );
        check_variant!(MediaError::ThumbnailTimeout, "MediaError::ThumbnailTimeout");
        check_variant!(
            MediaError::ThumbnailDecodeFailed,
            "MediaError::ThumbnailDecodeFailed"
        );
    }

    // TransferError — all 9 variants (SizeMismatch and ThumbnailTooLarge are struct variants)
    #[test]
    fn redaction_transfer_error() {
        check_variant!(
            TransferError::ObjectNotFound,
            "TransferError::ObjectNotFound"
        );
        check_variant!(
            TransferError::PartialReadFailed,
            "TransferError::PartialReadFailed"
        );
        check_variant!(
            TransferError::SizeMismatch {
                expected: 1024,
                actual: 512
            },
            "TransferError::SizeMismatch"
        );
        check_variant!(
            TransferError::ThumbnailTooLarge {
                size: 600_000,
                limit: 524_288
            },
            "TransferError::ThumbnailTooLarge"
        );
        check_variant!(
            TransferError::CameraDisconnected,
            "TransferError::CameraDisconnected"
        );
        check_variant!(TransferError::Timeout, "TransferError::Timeout");
        check_variant!(TransferError::Cancelled, "TransferError::Cancelled");
        check_variant!(
            TransferError::UnsupportedFormat,
            "TransferError::UnsupportedFormat"
        );
        check_variant!(
            TransferError::OutputWriteFailed,
            "TransferError::OutputWriteFailed"
        );
    }

    // StorageError — all 4 Rust-visible variants
    #[test]
    fn redaction_storage_error() {
        check_variant!(
            StorageError::OutputFdInvalid,
            "StorageError::OutputFdInvalid"
        );
        check_variant!(
            StorageError::OutputWriteFailed,
            "StorageError::OutputWriteFailed"
        );
        check_variant!(
            StorageError::InsufficientSpace,
            "StorageError::InsufficientSpace"
        );
        check_variant!(
            StorageError::DestinationUnavailable,
            "StorageError::DestinationUnavailable"
        );
    }

    // NativeError — all 11 variants
    #[test]
    fn redaction_native_error() {
        check_variant!(
            NativeError::LibraryLoadFailed,
            "NativeError::LibraryLoadFailed"
        );
        check_variant!(NativeError::SymbolMissing, "NativeError::SymbolMissing");
        check_variant!(
            NativeError::SessionHandleInvalid,
            "NativeError::SessionHandleInvalid"
        );
        check_variant!(NativeError::PanicPrevented, "NativeError::PanicPrevented");
        check_variant!(NativeError::CallbackFailed, "NativeError::CallbackFailed");
        check_variant!(NativeError::CallbackDropped, "NativeError::CallbackDropped");
        check_variant!(NativeError::InvalidArgument, "NativeError::InvalidArgument");
        check_variant!(
            NativeError::ResultDecodeFailed,
            "NativeError::ResultDecodeFailed"
        );
        check_variant!(
            NativeError::ThreadAttachFailed,
            "NativeError::ThreadAttachFailed"
        );
        check_variant!(NativeError::UnexpectedNull, "NativeError::UnexpectedNull");
        check_variant!(NativeError::UnsupportedAbi, "NativeError::UnsupportedAbi");
    }

    // BleProtocolError — all 9 variants
    #[test]
    fn redaction_ble_protocol_error() {
        check_variant!(
            BleProtocolError::MalformedPayload,
            "BleProtocolError::MalformedPayload"
        );
        check_variant!(
            BleProtocolError::InvalidPayloadLength,
            "BleProtocolError::InvalidPayloadLength"
        );
        check_variant!(
            BleProtocolError::UnsupportedCameraState,
            "BleProtocolError::UnsupportedCameraState"
        );
        check_variant!(
            BleProtocolError::PairingRequired,
            "BleProtocolError::PairingRequired"
        );
        check_variant!(
            BleProtocolError::PairingRejected,
            "BleProtocolError::PairingRejected"
        );
        check_variant!(
            BleProtocolError::PairingStale,
            "BleProtocolError::PairingStale"
        );
        check_variant!(
            BleProtocolError::MissingWifiCredentials,
            "BleProtocolError::MissingWifiCredentials"
        );
        check_variant!(
            BleProtocolError::UnsupportedCharacteristicPayload,
            "BleProtocolError::UnsupportedCharacteristicPayload"
        );
        check_variant!(
            BleProtocolError::LocationPayloadUnsupported,
            "BleProtocolError::LocationPayloadUnsupported"
        );
    }

    // FujiError top-level — representative value per variant
    #[test]
    fn redaction_fuji_error_top_level() {
        check_variant!(
            FujiError::InvalidInput("test-key"),
            "FujiError::InvalidInput"
        );
        check_variant!(FujiError::NotInitialized, "FujiError::NotInitialized");
        check_variant!(FujiError::NotImplemented("op"), "FujiError::NotImplemented");
        check_variant!(FujiError::SessionNotFound(42), "FujiError::SessionNotFound");
        check_variant!(
            FujiError::ProtocolUnavailable("ptp-ip"),
            "FujiError::ProtocolUnavailable"
        );
        check_variant!(
            FujiError::TransportUnavailable(TransportKind::WifiPtpIp),
            "FujiError::TransportUnavailable"
        );
        check_variant!(
            FujiError::Transport(TransportError::Timeout),
            "FujiError::Transport"
        );
        check_variant!(
            FujiError::Protocol(ProtocolError::HandshakeRejected),
            "FujiError::Protocol"
        );
        check_variant!(
            FujiError::Camera(CameraError::Busy { attempts: 0 }),
            "FujiError::Camera"
        );
        check_variant!(
            FujiError::Media(MediaError::ThumbnailTimeout),
            "FujiError::Media"
        );
        check_variant!(
            FujiError::Transfer(TransferError::ObjectNotFound),
            "FujiError::Transfer"
        );
        check_variant!(
            FujiError::Storage(StorageError::OutputFdInvalid),
            "FujiError::Storage"
        );
        check_variant!(
            FujiError::Native(NativeError::PanicPrevented),
            "FujiError::Native"
        );
        check_variant!(
            FujiError::BleProtocol(BleProtocolError::MalformedPayload),
            "FujiError::BleProtocol"
        );
    }

    // ── FunctionMode discriminants ─────────────────────────────────────────────

    #[test]
    fn function_mode_discriminants() {
        assert_eq!(FunctionMode::ImageReceive as u16, 1);
        assert_eq!(FunctionMode::RemoteCapture as u16, 5);
        assert_eq!(FunctionMode::Neutral20 as u16, 6);
        assert_eq!(FunctionMode::FwDataTransfer as u16, 19);
        assert_eq!(FunctionMode::ImageViewV2 as u16, 20);
        assert_eq!(FunctionMode::ReservedPhotoReceived20 as u16, 21);
        assert_eq!(FunctionMode::Liveview as u16, 22);
    }

    // ── ImportState transitions ────────────────────────────────────────────────

    #[test]
    fn import_state_transitions_to_complete() {
        let state = ImportState::Pending;
        assert_eq!(state, ImportState::Pending);

        let handle = CameraObjectId::new(1);
        let progress = TransferProgress {
            bytes_transferred: 500,
            total_bytes: 1000,
            object_handle: handle,
            transfer_id: 7,
        };
        let state = ImportState::Transferring { progress };
        assert_eq!(
            state,
            ImportState::Transferring {
                progress: TransferProgress {
                    bytes_transferred: 500,
                    total_bytes: 1000,
                    object_handle: handle,
                    transfer_id: 7,
                }
            }
        );

        let state = ImportState::Complete;
        assert_eq!(state, ImportState::Complete);
    }

    #[test]
    fn import_state_pending_to_failed() {
        let _pending = ImportState::Pending;
        let failed = ImportState::Failed {
            error: FujiError::NotInitialized,
        };
        assert_eq!(
            failed,
            ImportState::Failed {
                error: FujiError::NotInitialized
            }
        );
    }

    // ── TransferProgress::fraction ────────────────────────────────────────────

    #[test]
    fn transfer_progress_fraction_zero_transferred() {
        let p = TransferProgress {
            bytes_transferred: 0,
            total_bytes: 1000,
            object_handle: CameraObjectId::new(1),
            transfer_id: 1,
        };
        assert_eq!(p.fraction(), 0.0);
    }

    #[test]
    fn transfer_progress_fraction_half() {
        let p = TransferProgress {
            bytes_transferred: 500,
            total_bytes: 1000,
            object_handle: CameraObjectId::new(1),
            transfer_id: 1,
        };
        let diff = (p.fraction() - 0.5_f64).abs();
        assert!(diff < 1e-9, "expected ~0.5, got {}", p.fraction());
    }

    #[test]
    fn transfer_progress_fraction_zero_total_no_panic() {
        let p = TransferProgress {
            bytes_transferred: 999,
            total_bytes: 0,
            object_handle: CameraObjectId::new(1),
            transfer_id: 1,
        };
        assert_eq!(p.fraction(), 0.0);
    }

    #[test]
    fn transfer_progress_fraction_clamped_to_one() {
        let p = TransferProgress {
            bytes_transferred: 2000,
            total_bytes: 1000,
            object_handle: CameraObjectId::new(1),
            transfer_id: 1,
        };
        assert_eq!(p.fraction(), 1.0);
    }

    // ── DiagnosticTimeline insertion order ────────────────────────────────────

    #[test]
    fn diagnostic_timeline_preserves_insertion_order() {
        let mut tl = DiagnosticTimeline::new();

        let e1 = DiagnosticEvent::placeholder(DiagnosticCategory::Native, "first");
        let e2 = DiagnosticEvent::placeholder(DiagnosticCategory::Transport, "second");
        let e3 = DiagnosticEvent::placeholder(DiagnosticCategory::Protocol, "third");

        tl.push(e1.clone());
        tl.push(e2.clone());
        tl.push(e3.clone());

        let events = tl.events();
        assert_eq!(events.len(), 3);

        // Insertion order
        assert_eq!(events[0].1.category, DiagnosticCategory::Native);
        assert_eq!(events[1].1.category, DiagnosticCategory::Transport);
        assert_eq!(events[2].1.category, DiagnosticCategory::Protocol);

        // Monotonically increasing sequence numbers
        assert_eq!(events[0].0, 1);
        assert_eq!(events[1].0, 2);
        assert_eq!(events[2].0, 3);

        // Message content preserved
        assert_eq!(events[0].1.message, "first");
        assert_eq!(events[1].1.message, "second");
        assert_eq!(events[2].1.message, "third");
    }

    // ── CameraMediaFormat round-trip ──────────────────────────────────────────

    #[test]
    fn camera_media_format_from_wire_code() {
        assert_eq!(
            CameraMediaFormat::from_wire_code(0x3801),
            CameraMediaFormat::Jpeg
        );
        assert_eq!(
            CameraMediaFormat::from_wire_code(0xB103),
            CameraMediaFormat::Raf
        );
        assert_eq!(
            CameraMediaFormat::from_wire_code(0xB982),
            CameraMediaFormat::Heif
        );
        assert_eq!(
            CameraMediaFormat::from_wire_code(0x300D),
            CameraMediaFormat::Mov
        );
        assert_eq!(
            CameraMediaFormat::from_wire_code(0x9999),
            CameraMediaFormat::Unknown {
                raw_code: Some(0x9999)
            }
        );
    }

    #[test]
    fn camera_media_format_to_wire_code() {
        assert_eq!(CameraMediaFormat::Jpeg.to_wire_code(), Some(0x3801));
        assert_eq!(CameraMediaFormat::Raf.to_wire_code(), Some(0xB103));
        assert_eq!(CameraMediaFormat::Heif.to_wire_code(), Some(0xB982));
        assert_eq!(CameraMediaFormat::Mov.to_wire_code(), Some(0x300D));
        assert_eq!(
            CameraMediaFormat::Unknown {
                raw_code: Some(0x9999)
            }
            .to_wire_code(),
            Some(0x9999)
        );
        assert_eq!(
            CameraMediaFormat::Unknown { raw_code: None }.to_wire_code(),
            None
        );
    }

    // ── DiagnosticEvent::placeholder / layer() ────────────────────────────────

    #[test]
    fn diagnostic_event_placeholder_two_args() {
        let event = DiagnosticEvent::placeholder(DiagnosticCategory::Native, "x");
        assert_eq!(event.layer(), DiagnosticCategory::Native);
        assert_eq!(event.message, "x");
        assert_eq!(event.timestamp_epoch_micros, 0);
    }
}
