use fuji_core::{FujiError, FujiResult};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BlePayload {
    pub bytes_len: usize,
}

pub fn parse_payload(_bytes: &[u8]) -> FujiResult<BlePayload> {
    Err(FujiError::NotImplemented("ble payload parser placeholder"))
}

pub fn build_placeholder_command() -> Vec<u8> {
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_command_is_empty() {
        assert!(build_placeholder_command().is_empty());
    }
}
