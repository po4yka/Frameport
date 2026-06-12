use fuji_core::{FujiError, FujiResult};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PtpOperation {
    GetDeviceInfo,
    OpenSession,
    CloseSession,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct PtpPacket {
    pub operation: PtpOperation,
    pub payload_len: usize,
}

impl PtpPacket {
    pub fn placeholder(operation: PtpOperation) -> Self {
        Self {
            operation,
            payload_len: 0,
        }
    }
}

pub fn decode_packet(_bytes: &[u8]) -> FujiResult<PtpPacket> {
    Err(FujiError::NotImplemented("ptp packet decoding placeholder"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_packet_has_no_payload() {
        assert_eq!(
            PtpPacket::placeholder(PtpOperation::GetDeviceInfo).payload_len,
            0,
        );
    }
}
