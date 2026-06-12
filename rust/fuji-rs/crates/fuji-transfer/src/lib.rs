use fuji_core::{FujiError, FujiResult, MediaObjectId, SessionId};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TransferRequest {
    pub session_id: SessionId,
    pub media_object_id: MediaObjectId,
    pub output_fd_owned_by_rust: bool,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TransferProgress {
    pub bytes_written: u64,
    pub total_bytes: Option<u64>,
}

pub fn download_to_owned_fd(_request: TransferRequest, _fd: i32) -> FujiResult<TransferProgress> {
    Err(FujiError::NotImplemented("media transfer placeholder"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn progress_can_represent_unknown_total() {
        let progress = TransferProgress {
            bytes_written: 0,
            total_bytes: None,
        };
        assert_eq!(progress.total_bytes, None);
    }
}
