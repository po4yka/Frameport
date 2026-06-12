use fuji_core::{FujiError, FujiResult, TransportKind};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UsbTransport {
    pub fd_owned_by_rust: bool,
    pub transport: TransportKind,
}

pub fn open_from_owned_fd(_fd: i32) -> FujiResult<UsbTransport> {
    Err(FujiError::NotImplemented("usb ptp transport placeholder"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_transport_kind_is_usb() {
        let transport = UsbTransport {
            fd_owned_by_rust: true,
            transport: TransportKind::UsbPtp,
        };
        assert_eq!(transport.transport, TransportKind::UsbPtp);
    }
}
