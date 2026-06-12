use std::fmt::{Display, Formatter};

use thiserror::Error;

pub const SDK_VERSION: &str = env!("CARGO_PKG_VERSION");

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

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DiagnosticEvent {
    pub category: DiagnosticCategory,
    pub message: String,
}

impl DiagnosticEvent {
    pub fn placeholder(category: DiagnosticCategory, message: impl Into<String>) -> Self {
        Self {
            category,
            message: message.into(),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub enum DiagnosticCategory {
    Native,
    Transport,
    Protocol,
    Transfer,
    Storage,
    Privacy,
}

#[derive(Clone, Debug, Error, Eq, PartialEq)]
pub enum FujiError {
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
}

pub type FujiResult<T> = Result<T, FujiError>;

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

#[cfg(test)]
mod tests {
    use super::*;

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
}
