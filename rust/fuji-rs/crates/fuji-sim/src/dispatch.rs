//! Dispatch table for PTP-IP operation requests.
//!
//! The server calls [`dispatch_operation`] once it has decoded an
//! [`OperationRequest`] packet from the client. The function returns the sequence
//! of [`PtpIpPacket`]s to write back, or [`SimError::ProtocolViolation`] if the
//! request is structurally invalid.
//!
//! Data-returning operations follow the section 4.9 sequence:
//!   StartData → EndData → OperationResponse(OK)
//! Non-data operations return a single OperationResponse.
//!
//! All named constants come from `fuji_ptp::constants`; no bare hex literals are
//! used in match arms or response builders.

use fuji_ptp::PtpIpPacket;
use fuji_ptp::constants::{opcode, prop_code, response_code};

use crate::builder::ServerConfig;
use crate::error::SimError;

// Client-side retry policy constants — documented here for tests that simulate
// the full retry loop.
//
// Source: ptp-ptpip.md section 4.3 BUSY retry policy. [H]
//
// The server never sleeps; these constants describe what the CLIENT should do.
// Defined as `pub` so test code can reference them directly.

/// Delay in milliseconds between BUSY retries on the client side.
/// ptp-ptpip.md section 4.3: "sleep 500 ms and retry". [H]
pub const RETRY_DELAY_MS: u64 = 500;

/// Maximum number of BUSY retry attempts on the client side.
/// ptp-ptpip.md section 4.3: "up to 5 attempts (2.5 s total)". [H]
pub const MAX_BUSY_RETRIES: u32 = 5;

/// Mutable dispatch state shared across multiple operation calls within a session.
///
/// The server creates one `DispatchState` per accepted connection and passes it
/// through each call to `dispatch_operation`.
#[derive(Debug)]
pub struct DispatchState {
    /// How many more BUSY responses to emit before returning OK on mode write.
    pub remaining_busy: u32,
}

impl DispatchState {
    // cancel-safe: this is a synchronous constructor, no .await points.
    pub fn new(config: &ServerConfig) -> Self {
        Self {
            remaining_busy: config.busy_count,
        }
    }
}

/// Dispatches a single PTP-IP OperationRequest and returns the response packet(s).
///
/// The caller must have already extracted `opcode`, `transaction_id`, and `params`
/// from the decoded [`PtpIpPacket::OperationRequest`].
///
/// Returns `Ok(Vec<PtpIpPacket>)` — the sequence to write back to the client.
/// Returns `Err(SimError::ProtocolViolation(...))` for structurally invalid requests.
///
// cancel-safe: synchronous — no .await points; no owned resources allocated.
pub fn dispatch_operation(
    opcode_val: u16,
    transaction_id: u32,
    params: &[u32],
    data_payload: Option<&[u8]>,
    state: &mut DispatchState,
    config: &ServerConfig,
) -> Result<Vec<PtpIpPacket>, SimError> {
    match opcode_val {
        // ── GetDeviceInfo (0x1001) ────────────────────────────────────────────
        // Returns a minimal DeviceInfo dataset in the section 4.9 data phase.
        // Cited: ptp-ptpip.md section 6.1, master-constants.md §2a.
        op if op == opcode::GET_DEVICE_INFO => {
            let payload = build_device_info_dataset();
            Ok(data_phase_response(transaction_id, payload))
        }

        // ── OpenSession (0x1002) ──────────────────────────────────────────────
        // Responds with OK. SessionID is always 1, never negotiated.
        // ptp-ptpip.md section 5.1 step 8: "txn_id=0, param[0]=1". [H]
        op if op == opcode::OPEN_SESSION => {
            if params.first().copied().unwrap_or(0) != fuji_ptp::constants::SESSION_ID {
                return Err(SimError::ProtocolViolation(format!(
                    "OpenSession: expected SessionID={}, got {:?}",
                    fuji_ptp::constants::SESSION_ID,
                    params.first()
                )));
            }
            Ok(vec![ok_response(transaction_id)])
        }

        // ── CloseSession (0x1003) ─────────────────────────────────────────────
        // Responds with OK and signals the server to close the connection.
        op if op == opcode::CLOSE_SESSION => Ok(vec![ok_response(transaction_id)]),

        // ── GetDevicePropValue (0x1015) ───────────────────────────────────────
        // Dispatches further on the property code in params[0].
        op if op == opcode::GET_DEVICE_PROP_VALUE => {
            let prop = params.first().copied().ok_or_else(|| {
                SimError::ProtocolViolation(
                    "GetDevicePropValue: missing property code parameter".into(),
                )
            })?;
            dispatch_get_prop(prop, transaction_id, config)
        }

        // ── SetDevicePropValue (0x1016) ───────────────────────────────────────
        // "SetFunctionMode": SET_DEVICE_PROP_VALUE on the mode property.
        // ptp-ptpip.md section 5.2: dispatched on (opcode=0x1016, params[0]=prop_code).
        // The server emits BUSY for `busy_count` calls then OK. If fatal_on_set_mode
        // is configured it always returns that status code instead.
        op if op == opcode::SET_DEVICE_PROP_VALUE => {
            let _ = data_payload; // consumed by caller; not needed for response logic
            if let Some(fatal_code) = config.fatal_on_set_mode {
                return Ok(vec![PtpIpPacket::OperationResponse {
                    response_code: fatal_code,
                    transaction_id,
                    result_params: vec![],
                }]);
            }
            if state.remaining_busy > 0 {
                state.remaining_busy -= 1;
                Ok(vec![PtpIpPacket::OperationResponse {
                    response_code: response_code::BUSY,
                    transaction_id,
                    result_params: vec![],
                }])
            } else {
                Ok(vec![ok_response(transaction_id)])
            }
        }

        // ── Unrecognised opcode ───────────────────────────────────────────────
        // Return OperationNotSupported rather than panicking or dropping silently.
        _ => Ok(vec![PtpIpPacket::OperationResponse {
            response_code: response_code::OPERATION_NOT_SUPPORTED,
            transaction_id,
            result_params: vec![],
        }]),
    }
}

// ── Property dispatch ─────────────────────────────────────────────────────────

// cancel-safe: synchronous — no .await points.
fn dispatch_get_prop(
    prop: u32,
    transaction_id: u32,
    config: &ServerConfig,
) -> Result<Vec<PtpIpPacket>, SimError> {
    // Cast to u16 for comparison with named prop_code constants.
    let prop_u16 = prop as u16;

    if prop_u16 == prop_code::IMAGE_GET_VERSION {
        // GetDevicePropValue(IMAGE_GET_VERSION / 0xDF21): return the firmware version word.
        // master-constants.md §3b: "Read then re-write to confirm support." [H]
        let payload = config.firmware_version_word.to_le_bytes().to_vec();
        return Ok(data_phase_response(transaction_id, payload));
    }

    if prop_u16 == prop_code::EVENTS_LIST {
        // GetDevicePropValue(EventsList / 0xD212): return an empty events list.
        // ptp-ptpip.md section 8.3: response is u16 count + count×{u16 code, u32 value}. [H]
        // Count = 0 → payload is just two zero bytes.
        let payload = vec![0u8, 0u8];
        return Ok(data_phase_response(transaction_id, payload));
    }

    if prop_u16 == prop_code::OBJECT_COUNT {
        // GetDevicePropValue(ObjectCount / 0xD222): return the configured object count.
        // master-constants.md §3b: UINT32. [H]
        let payload = config.object_count.to_le_bytes().to_vec();
        return Ok(data_phase_response(transaction_id, payload));
    }

    // Unsupported property: return DevicePropNotSupported.
    Ok(vec![PtpIpPacket::OperationResponse {
        response_code: response_code::DEVICE_PROP_NOT_SUPPORTED,
        transaction_id,
        result_params: vec![],
    }])
}

// ── Response builders ─────────────────────────────────────────────────────────

/// Builds the section 4.9 data-phase sequence: StartData, EndData, OperationResponse(OK).
// cancel-safe: synchronous — no .await points.
fn data_phase_response(transaction_id: u32, payload: Vec<u8>) -> Vec<PtpIpPacket> {
    let total_data_length = payload.len() as u64;
    vec![
        PtpIpPacket::StartData {
            transaction_id,
            total_data_length,
        },
        PtpIpPacket::EndData {
            transaction_id,
            payload,
        },
        ok_response(transaction_id),
    ]
}

/// Builds a plain OK `OperationResponse`.
// cancel-safe: synchronous — no .await points.
fn ok_response(transaction_id: u32) -> PtpIpPacket {
    PtpIpPacket::OperationResponse {
        response_code: response_code::OK,
        transaction_id,
        result_params: vec![],
    }
}

// ── DeviceInfo dataset ────────────────────────────────────────────────────────

/// Operations advertised in the DeviceInfo OperationsSupported array.
///
/// These are the standard PTP opcodes used in a Fujifilm Wi-Fi image import
/// session per ptp-ptpip.md sections 6.1 and 13.1. [H]
/// Cited: master-constants.md §2a.
const OPERATIONS_SUPPORTED: &[u16] = &[
    opcode::GET_DEVICE_INFO, // 0x1001 — always first probe, no session required
    opcode::OPEN_SESSION,    // 0x1002 — starts PTP session
    opcode::CLOSE_SESSION,   // 0x1003 — ends PTP session
    opcode::GET_DEVICE_PROP_DESC, // 0x1014 — capability query per property
    opcode::GET_DEVICE_PROP_VALUE, // 0x1015 — read property value / keep-alive
    opcode::SET_DEVICE_PROP_VALUE, // 0x1016 — write property / SetFunctionMode
    opcode::GET_OBJECT_INFO, // 0x1008 — metadata for each media object
    opcode::GET_OBJECT,      // 0x1009 — full object data
    opcode::GET_THUMB,       // 0x100A — JPEG thumbnail
    opcode::GET_PARTIAL_OBJECT, // 0x101B — 1 MB chunk download loop (Wi-Fi import)
];

/// Device properties advertised in the DeviceInfo DevicePropertiesSupported array.
///
/// These are the Fujifilm-specific property codes read/written in a Wi-Fi
/// image import session per ptp-ptpip.md sections 5.2, 8.3, and 13.1. [H]
/// Cited: master-constants.md §3b.
const DEVICE_PROPERTIES_SUPPORTED: &[u16] = &[
    prop_code::OBJECT_COUNT,             // 0xD222 — number of Wi-Fi objects
    prop_code::EVENTS_LIST,              // 0xD212 — status poll / keep-alive
    prop_code::IMAGE_GET_VERSION,        // 0xDF21 — capability gate: read then re-write
    prop_code::CLIENT_STATE,             // 0xDF01 — write to request function mode
    prop_code::CAMERA_STATE,             // 0xDF00 — poll for WAIT/MULTIPLE_TRANSFER/FULL_ACCESS
    prop_code::ENABLE_CORRECT_FILE_SIZE, // 0xD227 — must set 1 before GetObjectInfo
];

/// Builds a PTP DeviceInfo dataset payload advertising a realistic Fujifilm
/// Wi-Fi import capability set.
///
/// The format follows ISO 15740 §5.5.1. All fields are LE-encoded.
///
/// | Offset | Field                      | Value                                      |
/// |--------|----------------------------|--------------------------------------------|
/// | 0      | StandardVersion (u16)      | 100 (PTP 1.0)                              |
/// | 2      | VendorExtensionID (u32)    | 0x00000006 (Fujifilm)                      |
/// | 6      | VendorExtensionVersion (u16)| 100                                       |
/// | 8      | VendorExtensionDesc (PTP string)| "FUJIFILM"                            |
/// | …      | FunctionalMode (u16)       | 0                                          |
/// | …      | OperationsSupported (u16[])| see `OPERATIONS_SUPPORTED`                 |
/// | …      | EventsSupported (u16[])    | [] (empty — events via EventsList poll)    |
/// | …      | DevicePropertiesSupported (u16[])| see `DEVICE_PROPERTIES_SUPPORTED`   |
/// | …      | CaptureFormats (u16[])     | [] (import-only sim, no capture)           |
/// | …      | ImageFormats (u16[])       | [] (not required for import path)          |
/// | …      | Manufacturer (PTP string)  | "FUJIFILM"                                 |
/// | …      | Model (PTP string)         | "X-T5 (sim)"                               |
/// | …      | DeviceVersion (PTP string) | "1.00"                                     |
/// | …      | SerialNumber (PTP string)  | "0000000000"                               |
///
/// Sources:
/// - ptp-ptpip.md section 6.1 ("Returns manufacturer string FUJIFILM"). [H]
/// - ptp-ptpip.md section 4.12 (PTP array encoding: u32 count + count × element). [H]
/// - master-constants.md §2a (opcode list), §3b (property code list). [H]
// cancel-safe: synchronous — no .await points.
fn build_device_info_dataset() -> Vec<u8> {
    let mut buf: Vec<u8> = Vec::new();

    // StandardVersion: 100 (PTP 1.0) — u16 LE
    buf.extend_from_slice(&100u16.to_le_bytes());
    // VendorExtensionID: 0x00000006 (Fujifilm) — u32 LE
    buf.extend_from_slice(&0x0000_0006u32.to_le_bytes());
    // VendorExtensionVersion: 100 — u16 LE
    buf.extend_from_slice(&100u16.to_le_bytes());
    // VendorExtensionDesc: "FUJIFILM" as PTP string
    push_ptp_string(&mut buf, "FUJIFILM");
    // FunctionalMode: 0 — u16 LE
    buf.extend_from_slice(&0u16.to_le_bytes());
    // OperationsSupported: u16 array per ptp-ptpip.md section 4.12.
    push_u16_array(&mut buf, OPERATIONS_SUPPORTED);
    // EventsSupported: empty — Fujifilm delivers events via EventsList poll (0xD212),
    // not unsolicited PTP events. ptp-ptpip.md section 8.3. [H]
    push_empty_u16_array(&mut buf);
    // DevicePropertiesSupported: u16 array per ptp-ptpip.md section 4.12.
    push_u16_array(&mut buf, DEVICE_PROPERTIES_SUPPORTED);
    // CaptureFormats: empty — this is an import-only simulator.
    push_empty_u16_array(&mut buf);
    // ImageFormats: empty — not required for the Wi-Fi import path.
    push_empty_u16_array(&mut buf);
    // Manufacturer: "FUJIFILM" — ptp-ptpip.md section 6.1. [H]
    push_ptp_string(&mut buf, "FUJIFILM");
    // Model: "X-T5 (sim)"
    push_ptp_string(&mut buf, "X-T5 (sim)");
    // DeviceVersion: "1.00"
    push_ptp_string(&mut buf, "1.00");
    // SerialNumber: "0000000000" — clean-room placeholder, not a real serial.
    push_ptp_string(&mut buf, "0000000000");

    buf
}

/// Appends a PTP string to `buf`.
///
/// PTP string encoding (ptp-ptpip.md section 4.11):
///   u8 num_chars (including null terminator; 0 if empty)
///   num_chars × u16 LE code units, last = 0x0000
// cancel-safe: synchronous — no .await points.
fn push_ptp_string(buf: &mut Vec<u8>, s: &str) {
    let units: Vec<u16> = s.encode_utf16().collect();
    // num_chars includes the null terminator
    let num_chars = (units.len() + 1) as u8;
    buf.push(num_chars);
    for unit in &units {
        buf.extend_from_slice(&unit.to_le_bytes());
    }
    // null terminator
    buf.extend_from_slice(&0u16.to_le_bytes());
}

/// Appends a non-empty PTP u16 array to `buf`.
///
/// PTP array encoding (ptp-ptpip.md section 4.12): u32 LE count followed by
/// `count` × u16 LE elements.
// cancel-safe: synchronous — no .await points.
fn push_u16_array(buf: &mut Vec<u8>, elements: &[u16]) {
    buf.extend_from_slice(&(elements.len() as u32).to_le_bytes());
    for &elem in elements {
        buf.extend_from_slice(&elem.to_le_bytes());
    }
}

/// Appends an empty PTP u16 array (count = 0, no elements).
///
/// PTP array encoding (ptp-ptpip.md section 4.12): u32 count + count × element.
// cancel-safe: synchronous — no .await points.
fn push_empty_u16_array(buf: &mut Vec<u8>) {
    buf.extend_from_slice(&0u32.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::builder::FakeCameraBuilder;
    use fuji_ptp::constants::{opcode, response_code};

    fn default_config() -> ServerConfig {
        FakeCameraBuilder::new().build()
    }

    #[test]
    fn open_session_returns_ok() {
        let config = default_config();
        let mut state = DispatchState::new(&config);
        let pkts = dispatch_operation(
            opcode::OPEN_SESSION,
            0,
            &[fuji_ptp::constants::SESSION_ID],
            None,
            &mut state,
            &config,
        )
        .unwrap();
        assert_eq!(pkts.len(), 1);
        assert!(matches!(
            &pkts[0],
            PtpIpPacket::OperationResponse { response_code, .. }
            if *response_code == response_code::OK
        ));
    }

    #[test]
    fn get_device_info_returns_data_phase() {
        let config = default_config();
        let mut state = DispatchState::new(&config);
        let pkts =
            dispatch_operation(opcode::GET_DEVICE_INFO, 1, &[], None, &mut state, &config).unwrap();
        // StartData, EndData, OperationResponse
        assert_eq!(pkts.len(), 3);
        assert!(matches!(pkts[0], PtpIpPacket::StartData { .. }));
        assert!(matches!(pkts[1], PtpIpPacket::EndData { .. }));
        assert!(matches!(
            &pkts[2],
            PtpIpPacket::OperationResponse { response_code, .. }
            if *response_code == response_code::OK
        ));
    }

    #[test]
    fn set_device_prop_busy_then_ok() {
        let config = FakeCameraBuilder::new().busy_count(2).build();
        let mut state = DispatchState::new(&config);

        // First two calls → BUSY
        for _ in 0..2 {
            let pkts = dispatch_operation(
                opcode::SET_DEVICE_PROP_VALUE,
                1,
                &[prop_code::IMAGE_GET_VERSION as u32],
                Some(&[1u8, 0u8]),
                &mut state,
                &config,
            )
            .unwrap();
            assert!(matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::BUSY
            ));
        }

        // Third call → OK
        let pkts = dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            2,
            &[prop_code::IMAGE_GET_VERSION as u32],
            Some(&[1u8, 0u8]),
            &mut state,
            &config,
        )
        .unwrap();
        assert!(matches!(
            &pkts[0],
            PtpIpPacket::OperationResponse { response_code, .. }
            if *response_code == response_code::OK
        ));
    }

    #[test]
    fn fatal_on_set_mode_returns_configured_code() {
        use fuji_ptp::constants::response_code::FATAL_AUTH_FAILURE;
        let config = FakeCameraBuilder::new()
            .fatal_on_set_mode(Some(FATAL_AUTH_FAILURE))
            .build();
        let mut state = DispatchState::new(&config);
        let pkts = dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[],
            None,
            &mut state,
            &config,
        )
        .unwrap();
        assert!(matches!(
            &pkts[0],
            PtpIpPacket::OperationResponse { response_code, .. }
            if *response_code == FATAL_AUTH_FAILURE
        ));
    }

    #[test]
    fn get_object_count_returns_configured_value() {
        let config = FakeCameraBuilder::new().object_count(7).build();
        let mut state = DispatchState::new(&config);
        let pkts = dispatch_operation(
            opcode::GET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::OBJECT_COUNT as u32],
            None,
            &mut state,
            &config,
        )
        .unwrap();
        // data_phase: StartData, EndData(payload=7u32 LE), OK
        assert_eq!(pkts.len(), 3);
        if let PtpIpPacket::EndData { payload, .. } = &pkts[1] {
            assert_eq!(u32::from_le_bytes(payload[..4].try_into().unwrap()), 7);
        } else {
            panic!("expected EndData");
        }
    }

    #[test]
    fn get_events_list_returns_empty() {
        let config = default_config();
        let mut state = DispatchState::new(&config);
        let pkts = dispatch_operation(
            opcode::GET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::EVENTS_LIST as u32],
            None,
            &mut state,
            &config,
        )
        .unwrap();
        // EndData payload = 2 zero bytes (count=0)
        if let PtpIpPacket::EndData { payload, .. } = &pkts[1] {
            assert_eq!(payload, &[0u8, 0u8]);
        } else {
            panic!("expected EndData at index 1");
        }
    }

    #[test]
    fn unknown_opcode_returns_operation_not_supported() {
        let config = default_config();
        let mut state = DispatchState::new(&config);
        let pkts = dispatch_operation(0xFFFF, 99, &[], None, &mut state, &config).unwrap();
        assert!(matches!(
            &pkts[0],
            PtpIpPacket::OperationResponse { response_code, .. }
            if *response_code == response_code::OPERATION_NOT_SUPPORTED
        ));
    }

    /// Parses a PTP u16 array from a byte slice starting at `pos`.
    ///
    /// Format (ptp-ptpip.md section 4.12): u32 LE count, then count × u16 LE elements.
    /// Returns (elements, new_pos) or panics on truncation.
    fn parse_u16_array(buf: &[u8], pos: usize) -> (Vec<u16>, usize) {
        let count = u32::from_le_bytes(buf[pos..pos + 4].try_into().unwrap()) as usize;
        let mut elems = Vec::with_capacity(count);
        let mut p = pos + 4;
        for _ in 0..count {
            elems.push(u16::from_le_bytes(buf[p..p + 2].try_into().unwrap()));
            p += 2;
        }
        (elems, p)
    }

    /// Skips a PTP string in `buf` starting at `pos`.
    ///
    /// Format (ptp-ptpip.md section 4.11): u8 num_chars (incl. null), then num_chars × u16 LE.
    /// Returns the position after the string.
    fn skip_ptp_string(buf: &[u8], pos: usize) -> usize {
        let num_chars = buf[pos] as usize;
        pos + 1 + num_chars * 2
    }

    /// Verifies that the DeviceInfo binary payload produced by [`build_device_info_dataset`]
    /// advertises the expected OperationsSupported and DevicePropertiesSupported arrays.
    ///
    /// The test decodes the PTP DeviceInfo binary (ISO 15740 §5.5.1) using the
    /// field layout documented in `build_device_info_dataset`'s doc-comment, then
    /// asserts that all session/property/object/thumb ops and the three session
    /// properties appear in the respective arrays.
    ///
    /// References: ptp-ptpip.md sections 4.11, 4.12, 6.1; master-constants.md §2a, §3b.
    #[test]
    fn device_info_advertises_expected_operations_and_properties() {
        use fuji_ptp::constants::prop_code;

        let payload = build_device_info_dataset();
        let buf = &payload[..];

        // StandardVersion (u16) + VendorExtensionID (u32) + VendorExtensionVersion (u16) = 8 bytes
        let mut pos = 8;
        // VendorExtensionDesc (PTP string: "FUJIFILM")
        pos = skip_ptp_string(buf, pos);
        // FunctionalMode (u16)
        pos += 2;
        // OperationsSupported (u16 array)
        let (ops, after_ops) = parse_u16_array(buf, pos);
        pos = after_ops;
        // EventsSupported (u16 array — empty)
        let (events, after_events) = parse_u16_array(buf, pos);
        pos = after_events;
        // DevicePropertiesSupported (u16 array)
        let (props, _after_props) = parse_u16_array(buf, pos);

        // ── EventsSupported must be empty (Fujifilm uses EventsList poll) ──
        assert!(
            events.is_empty(),
            "EventsSupported should be empty; got {:04X?}",
            events
        );

        // ── OperationsSupported must contain all session-path opcodes ──
        let required_ops: &[u16] = &[
            opcode::GET_DEVICE_INFO,       // 0x1001
            opcode::OPEN_SESSION,          // 0x1002
            opcode::CLOSE_SESSION,         // 0x1003
            opcode::GET_DEVICE_PROP_DESC,  // 0x1014
            opcode::GET_DEVICE_PROP_VALUE, // 0x1015
            opcode::SET_DEVICE_PROP_VALUE, // 0x1016
            opcode::GET_OBJECT_INFO,       // 0x1008
            opcode::GET_OBJECT,            // 0x1009
            opcode::GET_THUMB,             // 0x100A
            opcode::GET_PARTIAL_OBJECT,    // 0x101B
        ];
        for &code in required_ops {
            assert!(
                ops.contains(&code),
                "OperationsSupported missing opcode 0x{:04X}; found: {:04X?}",
                code,
                ops
            );
        }

        // ── DevicePropertiesSupported must contain the three session properties ──
        let required_props: &[u16] = &[
            prop_code::OBJECT_COUNT,             // 0xD222
            prop_code::EVENTS_LIST,              // 0xD212
            prop_code::IMAGE_GET_VERSION,        // 0xDF21
            prop_code::CLIENT_STATE,             // 0xDF01
            prop_code::CAMERA_STATE,             // 0xDF00
            prop_code::ENABLE_CORRECT_FILE_SIZE, // 0xD227
        ];
        for &code in required_props {
            assert!(
                props.contains(&code),
                "DevicePropertiesSupported missing prop_code 0x{:04X}; found: {:04X?}",
                code,
                props
            );
        }
    }
}
