use fuji_core::{FujiError, FujiResult, SessionId, TransportKind};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WifiSession {
    pub id: SessionId,
    pub endpoint: String,
    pub transport: TransportKind,
}

impl WifiSession {
    pub fn placeholder(id: SessionId) -> Self {
        Self {
            id,
            endpoint: "socket-fd-owned-by-android".to_owned(),
            transport: TransportKind::WifiPtpIp,
        }
    }
}

pub fn open_from_owned_socket_fd(_fd: i32) -> FujiResult<WifiSession> {
    Err(FujiError::NotImplemented("ptp-ip session open placeholder"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_session_uses_wifi_transport() {
        let session = WifiSession::placeholder(SessionId::new(1).unwrap());
        assert_eq!(session.transport, TransportKind::WifiPtpIp);
    }
}
