use fuji_core::{FujiError, FujiResult, SessionId};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct LiveViewFrame {
    pub session_id: SessionId,
    pub sequence: u64,
    pub bytes: Vec<u8>,
}

impl LiveViewFrame {
    pub fn empty_placeholder(session_id: SessionId) -> Self {
        Self {
            session_id,
            sequence: 0,
            bytes: Vec::new(),
        }
    }
}

pub fn parse_frame(_bytes: &[u8], _session_id: SessionId) -> FujiResult<LiveViewFrame> {
    Err(FujiError::NotImplemented(
        "liveview frame parser placeholder",
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_frame_is_empty() {
        let frame = LiveViewFrame::empty_placeholder(SessionId::new(1).unwrap());
        assert!(frame.bytes.is_empty());
    }
}
