//! PTP and Fujifilm protocol constants.
//!
//! All values are interoperability facts from ptp-ptpip.md and master-constants.md.
//! No proprietary Fujifilm assets or credentials are included.

// ── PTP operation opcodes (section 6.1 / master-constants.md §2a) ─────────────

/// Standard PTP opcodes.
pub mod opcode {
    /// `0x1001` GetDeviceInfo — no session required.
    pub const GET_DEVICE_INFO: u16 = 0x1001;
    /// `0x1002` OpenSession — param[0] = SessionID (always 1); txn_id must be 0.
    pub const OPEN_SESSION: u16 = 0x1002;
    /// `0x1003` CloseSession.
    pub const CLOSE_SESSION: u16 = 0x1003;
    /// `0x1004` GetStorageIDs.
    pub const GET_STORAGE_IDS: u16 = 0x1004;
    /// `0x1005` GetStorageInfo — param[0] = storage_id.
    pub const GET_STORAGE_INFO: u16 = 0x1005;
    /// `0x1006` GetNumObjects.
    pub const GET_NUM_OBJECTS: u16 = 0x1006;
    /// `0x1007` GetObjectHandles — do NOT call for Wi-Fi; use ObjectCount (0xD222) instead.
    pub const GET_OBJECT_HANDLES: u16 = 0x1007;
    /// `0x1008` GetObjectInfo — param[0] = object_handle.
    pub const GET_OBJECT_INFO: u16 = 0x1008;
    /// `0x1009` GetObject — param[0] = object_handle.
    pub const GET_OBJECT: u16 = 0x1009;
    /// `0x100A` GetThumb — param[0] = object_handle; returns JPEG thumbnail.
    pub const GET_THUMB: u16 = 0x100A;
    /// `0x100B` DeleteObject — param[0] = handle, param[1] = format_code.
    pub const DELETE_OBJECT: u16 = 0x100B;
    /// `0x100C` SendObjectInfo — param[0] = storage_id, param[1] = parent.
    pub const SEND_OBJECT_INFO: u16 = 0x100C;
    /// `0x100D` SendObject.
    pub const SEND_OBJECT: u16 = 0x100D;
    /// `0x100E` InitiateCapture — param[0] = storage_id, param[1] = format_code.
    pub const INITIATE_CAPTURE: u16 = 0x100E;
    /// `0x1014` GetDevicePropDesc — param[0] = property_code.
    pub const GET_DEVICE_PROP_DESC: u16 = 0x1014;
    /// `0x1015` GetDevicePropValue — param[0] = property_code.
    pub const GET_DEVICE_PROP_VALUE: u16 = 0x1015;
    /// `0x1016` SetDevicePropValue — param[0] = property_code; data phase carries new value.
    pub const SET_DEVICE_PROP_VALUE: u16 = 0x1016;
    /// `0x1018` TerminateOpenCapture — param[0] = txn_id of the matching InitiateOpenCapture.
    pub const TERMINATE_OPEN_CAPTURE: u16 = 0x1018;
    /// `0x101B` GetPartialObject — param[0] = handle, param[1] = offset, param[2] = max_bytes.
    /// Fujifilm max chunk: 0x100000 (1 MiB).
    pub const GET_PARTIAL_OBJECT: u16 = 0x101B;
    /// `0x101C` InitiateOpenCapture — triggers event+liveview channel open.
    pub const INITIATE_OPEN_CAPTURE: u16 = 0x101C;

    /// Fujifilm vendor opcodes.
    pub mod fuji {
        /// `0x9020` InitiateMovieCapture.
        pub const INITIATE_MOVIE_CAPTURE: u16 = 0x9020;
        /// `0x9021` TerminateMovieCapture.
        pub const TERMINATE_MOVIE_CAPTURE: u16 = 0x9021;
        /// `0x9022` GetCapturePreview / camera_last_image.
        pub const GET_CAPTURE_PREVIEW: u16 = 0x9022;
        /// `0x9026` LockS1Lock (focus point / half-shutter AF lock).
        pub const LOCK_S1_LOCK: u16 = 0x9026;
        /// `0x9027` UnlockS1Lock.
        pub const UNLOCK_S1_LOCK: u16 = 0x9027;
        /// `0x902B` GetDeviceInfo (Fuji capabilities) — returns TLV DevicePropDesc blocks.
        pub const GET_FUJI_DEVICE_INFO: u16 = 0x902B;
        /// `0x902C` StepShutterSpeed — param[0]=1 faster, 0 slower.
        pub const STEP_SHUTTER_SPEED: u16 = 0x902C;
        /// `0x902D` StepFNumber — param[0]=1 open, 0 close.
        pub const STEP_FNUMBER: u16 = 0x902D;
        /// `0x902E` StepExposureBias — param[0]=1 positive, 0 negative.
        pub const STEP_EXPOSURE_BIAS: u16 = 0x902E;
        /// `0x9040` FmSendObjectInfo.
        pub const FM_SEND_OBJECT_INFO: u16 = 0x9040;
        /// `0x9041` FmSendObject.
        pub const FM_SEND_OBJECT: u16 = 0x9041;
        /// `0x9042` FmSendPartialObject.
        pub const FM_SEND_PARTIAL_OBJECT: u16 = 0x9042;
        /// `0x900C` FujiSendObjectInfo — use ObjectFormat 0xF802 for RAF upload.
        pub const FUJI_SEND_OBJECT_INFO: u16 = 0x900C;
        /// `0x900D` FujiSendObject2.
        pub const FUJI_SEND_OBJECT2: u16 = 0x900D;
        /// `0x9060` SetCameraEvent — data_phase=2; 6-byte payload for non-string props.
        pub const SET_CAMERA_EVENT: u16 = 0x9060;
    }
}

// ── PTP response codes (section 7 / master-constants.md §4b) ─────────────────

/// Standard PTP response codes.
pub mod response_code {
    /// `0x2001` OK — success.
    pub const OK: u16 = 0x2001;
    /// `0x2002` GeneralError.
    pub const GENERAL_ERROR: u16 = 0x2002;
    /// `0x2003` SessionNotOpen.
    pub const SESSION_NOT_OPEN: u16 = 0x2003;
    /// `0x2004` InvalidTransactionID.
    pub const INVALID_TRANSACTION_ID: u16 = 0x2004;
    /// `0x2005` OperationNotSupported.
    pub const OPERATION_NOT_SUPPORTED: u16 = 0x2005;
    /// `0x2006` ParameterNotSupported.
    pub const PARAMETER_NOT_SUPPORTED: u16 = 0x2006;
    /// `0x2007` IncompleteTransfer.
    pub const INCOMPLETE_TRANSFER: u16 = 0x2007;
    /// `0x2008` InvalidStorageID.
    pub const INVALID_STORAGE_ID: u16 = 0x2008;
    /// `0x2009` InvalidObjectHandle.
    pub const INVALID_OBJECT_HANDLE: u16 = 0x2009;
    /// `0x200A` DevicePropNotSupported.
    pub const DEVICE_PROP_NOT_SUPPORTED: u16 = 0x200A;
    /// `0x2019` BUSY — retry: 500 ms × up to 5 attempts. [H]
    pub const BUSY: u16 = 0x2019;
    /// `0x201D` Fatal auth failure — abort connection.
    pub const FATAL_AUTH_FAILURE: u16 = 0x201D;
    /// `0x201E` SessionAlreadyOpen — triggers stale-session recovery.
    pub const SESSION_ALREADY_OPEN: u16 = 0x201E;
    /// `0x2000` Fatal generic failure — abort connection immediately.
    ///
    /// Cited in ptp-ptpip.md section 7 as one of the fatal status codes that
    /// abort the InitCommandAck handshake alongside `0x201D` and `0x201E`. [H]
    pub const FATAL_GENERIC_FAILURE: u16 = 0x2000;
}

// ── Standard PTP event codes (section 10.1 / master-constants.md §4a) ────────

/// PTP event codes.
pub mod event_code {
    /// `0x4002` ObjectAdded.
    pub const OBJECT_ADDED: u16 = 0x4002;
    /// `0x4003` ObjectRemoved.
    pub const OBJECT_REMOVED: u16 = 0x4003;
    /// `0x4006` DevicePropChanged.
    pub const DEVICE_PROP_CHANGED: u16 = 0x4006;
    /// `0x4009` RequestObjectTransfer.
    pub const REQUEST_OBJECT_TRANSFER: u16 = 0x4009;
    /// `0x400D` CaptureComplete.
    pub const CAPTURE_COMPLETE: u16 = 0x400D;

    /// Fujifilm vendor event codes (confidence: [M] — may be inaccurate per libfuji).
    pub mod fuji {
        /// `0xC001` PreviewAvailable — capture preview ready.
        pub const PREVIEW_AVAILABLE: u16 = 0xC001;
        /// `0xC004` ObjectAdded (Fuji variant).
        pub const OBJECT_ADDED: u16 = 0xC004;
    }
}

// ── Device property codes (section 8 / master-constants.md §3) ───────────────

/// Device property codes.
pub mod prop_code {
    // Standard PTP properties
    /// `0x5001` BatteryLevel — UINT8.
    pub const BATTERY_LEVEL: u16 = 0x5001;
    /// `0x5007` FNumber — UINT16; f-number × 100; 0x0000=Auto, 0xFFFF=N/A.
    pub const F_NUMBER: u16 = 0x5007;
    /// `0x500A` FocusMode — UINT16; 1=Manual, 0x8001=AF-S, 0x8002=AF-C.
    pub const FOCUS_MODE: u16 = 0x500A;
    /// `0x500D` ExposureTime — UINT32.
    pub const EXPOSURE_TIME: u16 = 0x500D;
    /// `0x500E` ExposureProgramMode — UINT16; 1=M, 2=P, 3=Av, 4=Tv, 6=Auto.
    pub const EXPOSURE_PROGRAM_MODE: u16 = 0x500E;
    /// `0x5010` ExposureBiasCompensation — INT16; milli-EV.
    pub const EXPOSURE_BIAS_COMPENSATION: u16 = 0x5010;

    // Fujifilm vendor properties
    /// `0xD001` FilmSimulation — UINT16.
    pub const FILM_SIMULATION: u16 = 0xD001;
    /// `0xD018` ImageFormat (Wi-Fi) — UINT16; 2=JPEG Fine, 3=Normal, 4=RAW+JPEG Fine.
    pub const IMAGE_FORMAT: u16 = 0xD018;
    /// `0xD02A` ISO — UINT32; bit 31=auto, bit 30=emulated, bits 0-23=numeric ISO.
    pub const ISO: u16 = 0xD02A;
    /// `0xD212` EventsList / CurrentState — poll for camera events and keep-alive.
    pub const EVENTS_LIST: u16 = 0xD212;
    /// `0xD222` ObjectCount — available Wi-Fi objects; handles are sequential 1..N.
    pub const OBJECT_COUNT: u16 = 0xD222;
    /// `0xD226` CompressSmall — set 1 for 400–800 KB preview; 0 for full size.
    pub const COMPRESS_SMALL: u16 = 0xD226;
    /// `0xD227` EnableCorrectFileSize — set 1 before GetObjectInfo/GetObject; reset after.
    pub const ENABLE_CORRECT_FILE_SIZE: u16 = 0xD227;
    /// `0xD240` ShutterSpeed (Wi-Fi vendor) — UINT32 bit-field.
    pub const SHUTTER_SPEED: u16 = 0xD240;
    /// `0xD242` BatteryLevel (Wi-Fi ext.) — UINT16.
    pub const BATTERY_LEVEL_WIFI: u16 = 0xD242;
    /// `0xDF00` CameraState — UINT16; 0=WAIT, 1=MULTIPLE_TRANSFER, 2=FULL_ACCESS, etc.
    pub const CAMERA_STATE: u16 = 0xDF00;
    /// `0xDF01` ClientState — write to request a mode; triggers camera UI dialog.
    pub const CLIENT_STATE: u16 = 0xDF01;
    /// `0xDF21` ImageGetVersion — read then re-write to confirm support.
    pub const IMAGE_GET_VERSION: u16 = 0xDF21;
    /// `0xDF22` GetObjectVersion — version negotiation gate.
    pub const GET_OBJECT_VERSION: u16 = 0xDF22;
    /// `0xDF24` RemoteVersion — `0x2000C` for Camera Connect app 2.11; −1 = no remote.
    pub const REMOTE_VERSION: u16 = 0xDF24;
}

// ── PTP data type codes (section 11) ─────────────────────────────────────────

/// PTP data type codes used in DevicePropDesc responses.
pub mod data_type {
    pub const INT8: u16 = 0x0001;
    pub const UINT8: u16 = 0x0002;
    pub const INT16: u16 = 0x0003;
    pub const UINT16: u16 = 0x0004;
    pub const INT32: u16 = 0x0005;
    pub const UINT32: u16 = 0x0006;
    pub const INT64: u16 = 0x0007;
    pub const UINT64: u16 = 0x0008;
    pub const UINT8_ARRAY: u16 = 0x4002;
    pub const UINT16_ARRAY: u16 = 0x4004;
    pub const UINT32_ARRAY: u16 = 0x4006;
    pub const STRING: u16 = 0xFFFF;
}

// ── Object format codes (section 14) ─────────────────────────────────────────

/// PTP object format codes.
pub mod object_format {
    /// `0x3801` JPEG (standard).
    pub const JPEG: u16 = 0x3801;
    /// `0xB103` RAF (Fujifilm RAW).
    pub const RAF: u16 = 0xB103;
    /// `0xB982` HEIF.
    pub const HEIF: u16 = 0xB982;
    /// `0x300D` MOV video.
    pub const MOV: u16 = 0x300D;
    /// `0xF802` Fujifilm RAW profile / object upload — use with opcode 0x900C.
    pub const FUJI_PROFILE: u16 = 0xF802;
}

// ── Session and connection constants ──────────────────────────────────────────

/// Fujifilm camera Wi-Fi AP address: `192.168.0.1`. [H]
pub const CAMERA_IP: [u8; 4] = [192, 168, 0, 1];

/// Command/control channel TCP port: 55740 (`0xD9BC`). [H]
pub const PORT_COMMAND: u16 = 55740;
/// Async event channel TCP port: 55741 (`0xD9BD`). Opened lazily. [H]
pub const PORT_EVENT: u16 = 55741;
/// Live-view / through-picture stream TCP port: 55742 (`0xD9BE`). Opened lazily. [H]
pub const PORT_LIVEVIEW: u16 = 55742;

/// Fujifilm PTP-IP version magic in Init Command Request. Cameras reject other values. [H]
/// From ptp-ptpip.md section 4.2: `0x8F53E4F2`.
pub const FUJI_PTPIP_VERSION: u32 = 0x8F53E4F2;

/// PTP-IP session ID — always 1, never negotiated. [H]
pub const SESSION_ID: u32 = 1;

/// The 8-byte goodbye packet sent on graceful disconnect (section 4.10).
/// `length = 8`, `packet_type = 0xFFFFFFFF` (Fujifilm non-standard extension). [H]
pub const GOODBYE_PACKET: [u8; 8] = [0x08, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF];

/// Fujifilm function mode codes — written to ClientState (0xDF01) after OpenSession.
pub mod function_mode {
    /// Image receive / Wi-Fi import mode.
    pub const IMAGE_RECEIVE: u16 = 1;
    /// Remote capture / shutter mode.
    pub const REMOTE: u16 = 5;
    /// Neutral mode (version 2.0).
    pub const NEUTRAL20: u16 = 6;
    /// Image view v2.
    pub const IMAGE_VIEW_V2: u16 = 20;
    /// Live-view streaming mode.
    pub const IMAGE_LIVE_VIEW: u16 = 22;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fuji_version_magic_matches_spec() {
        // 0x8F53E4F2 is the Fujifilm proprietary version field (ptp-ptpip.md §4.2).
        assert_eq!(FUJI_PTPIP_VERSION, 0x8F53E4F2);
    }

    #[test]
    fn port_values_match_spec() {
        assert_eq!(PORT_COMMAND, 55740);
        assert_eq!(PORT_EVENT, 55741);
        assert_eq!(PORT_LIVEVIEW, 55742);
    }

    #[test]
    fn goodbye_packet_matches_spec() {
        // Section 4.10: 08 00 00 00 FF FF FF FF
        assert_eq!(
            GOODBYE_PACKET,
            [0x08, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF]
        );
    }

    #[test]
    fn open_session_opcode_is_0x1002() {
        assert_eq!(opcode::OPEN_SESSION, 0x1002);
    }

    #[test]
    fn response_ok_is_0x2001() {
        assert_eq!(response_code::OK, 0x2001);
    }

    #[test]
    fn events_list_prop_code_is_0xd212() {
        assert_eq!(prop_code::EVENTS_LIST, 0xD212);
    }
}
