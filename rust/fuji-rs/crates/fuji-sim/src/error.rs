//! `SimError` — typed errors for the fake camera server.
//!
//! These errors represent failure modes during test-server operation. They map to
//! [`fuji_core::FujiError`] so callers can use the common error vocabulary.

use thiserror::Error;

use fuji_core::{FujiError, TransportKind};

/// Errors produced by the [`crate::FakeCameraServer`].
///
/// `io::Error` is not `Clone` or `Eq`, so this type intentionally derives only
/// `Debug` and `Error` — not `Clone`, `PartialEq`, or `Eq`.
#[derive(Debug, Error)]
pub enum SimError {
    /// The server could not bind its TCP listener to `127.0.0.1:0`.
    #[error("server bind failed: {0}")]
    BindFailed(std::io::Error),

    /// The server encountered an error while accepting a new TCP connection.
    #[error("accept failed: {0}")]
    AcceptFailed(std::io::Error),

    /// The client sent a packet that violates the PTP-IP protocol.
    ///
    /// The inner `String` describes which invariant was violated. Never contains
    /// raw device identifiers or packet bytes (privacy rule).
    #[error("protocol violation: {0}")]
    ProtocolViolation(String),

    /// The TCP connection was closed by the client before the session completed.
    #[error("unexpected client disconnect")]
    UnexpectedDisconnect,

    /// A socket I/O error occurred that is not covered by the variants above.
    #[error("socket I/O error: {0}")]
    Io(std::io::Error),
}

impl From<SimError> for FujiError {
    fn from(err: SimError) -> FujiError {
        match err {
            SimError::BindFailed(_) | SimError::AcceptFailed(_) => {
                // Bind / accept failure → transport is unavailable.
                FujiError::TransportUnavailable(TransportKind::WifiPtpIp)
            }
            SimError::ProtocolViolation(msg) => {
                // We cannot easily store a dynamic String in FujiError's &'static str
                // variants. Use ProtocolUnavailable with a static description; the
                // full diagnostic is already in the SimError message.
                let _ = msg;
                FujiError::ProtocolUnavailable("PTP-IP protocol violation in fake camera server")
            }
            SimError::UnexpectedDisconnect => {
                FujiError::ProtocolUnavailable("client disconnected unexpectedly")
            }
            SimError::Io(_) => FujiError::TransportUnavailable(TransportKind::WifiPtpIp),
        }
    }
}
