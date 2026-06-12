use fuji_core::{CameraId, CameraModel, FirmwareVersion, FujiResult, TransportKind};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct FakeCamera {
    pub id: CameraId,
    pub model: CameraModel,
    pub firmware_version: FirmwareVersion,
    pub transport: TransportKind,
}

pub fn placeholder_camera() -> FujiResult<FakeCamera> {
    Ok(FakeCamera {
        id: CameraId::new("fake-camera-placeholder")?,
        model: CameraModel::placeholder(),
        firmware_version: FirmwareVersion::new("0.0-placeholder")?,
        transport: TransportKind::Noop,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fake_camera_is_noop_transport() {
        assert_eq!(placeholder_camera().unwrap().transport, TransportKind::Noop);
    }
}
