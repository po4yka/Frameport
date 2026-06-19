//! Dispatch table for PTP-IP operation requests.
//!
//! The server calls [`dispatch_operation`] once it has decoded an
//! [`OperationRequest`] packet from the client.  The function returns a
//! [`DispatchResult`] that the server layer uses to write response packets.
//!
//! Data-returning operations (small payloads) follow the section 4.9 sequence:
//!   StartData → EndData → OperationResponse(OK)
//! Non-data operations return a single OperationResponse.
//!
//! `GetObject` returns [`DispatchResult::StreamObject`] so the server can stream
//! the object payload directly to the TCP socket without buffering the entire
//! object in RAM as a `PtpIpPacket::EndData`.
//!
//! All named constants come from `fuji_ptp::constants`; no bare hex literals are
//! used in match arms or response builders.

use fuji_ptp::constants::{opcode, prop_code, response_code};
use fuji_ptp::{ObjectInfo, PtpIpPacket, encode_object_info};

use crate::builder::{SIM_STORAGE_ID, ServerConfig};
use crate::error::SimError;

// ── Client-side retry policy constants ───────────────────────────────────────

// Source: ptp-ptpip.md section 4.3 BUSY retry policy. [H]
// The server never sleeps; these constants describe what the CLIENT should do.
// Defined as `pub` so test code can reference them directly.

/// Delay in milliseconds between BUSY retries on the client side.
/// ptp-ptpip.md section 4.3: "sleep 500 ms and retry". [H]
pub const RETRY_DELAY_MS: u64 = 500;

/// Maximum number of BUSY retry attempts on the client side.
/// ptp-ptpip.md section 4.3: "up to 5 attempts (2.5 s total)". [H]
pub const MAX_BUSY_RETRIES: u32 = 5;

// ── DispatchResult ────────────────────────────────────────────────────────────

/// Result of dispatching one PTP-IP operation.
///
/// Most operations return `Packets` — a sequence of [`PtpIpPacket`]s that the
/// server layer encodes and writes normally.
///
/// `GetObject` returns `StreamObject` so the server can stream the payload
/// directly to the TCP socket without buffering the entire object in a
/// `PtpIpPacket::EndData` (which would hold the whole object in RAM and violate
/// the streaming requirement for large objects).  The server writes the raw
/// framing bytes manually per the wire-format ground truth.
///
/// `ResetAfterStream` tells the server to close the TCP connection after writing
/// `reset_after_bytes` bytes of the EndData body — used for the connection-reset
/// fault injection scenario.
#[derive(Debug)]
pub enum DispatchResult {
    /// Normal case: encode and send these packets in order.
    Packets(Vec<PtpIpPacket>),

    /// `GetObject` streaming case.
    ///
    /// The server must write:
    /// 1. A 20-byte `StartData` frame (type=0x09) with `total_data_length`.
    /// 2. The EndData frame header + body:
    ///    - length  u32 LE = 12 + object_bytes.len()
    ///    - type    u32 LE = 0x0C
    ///    - txn_id  u32 LE
    ///    - then the raw `object_bytes`
    /// 3. A normal `OperationResponse(OK)` packet.
    ///
    /// `reset_after_bytes`: when `Some(k)`, drop the connection after writing
    /// exactly `k` bytes of the EndData body (connection-reset fault).
    StreamObject {
        transaction_id: u32,
        /// Length announced in `StartData.total_data_length`.
        total_data_length: u64,
        /// Raw payload bytes to stream as the EndData body.
        object_bytes: Vec<u8>,
        /// Connection-reset fault: drop TCP after this many body bytes, if `Some`.
        reset_after_bytes: Option<usize>,
    },
}

// ── DispatchState ─────────────────────────────────────────────────────────────

/// Mutable dispatch state shared across multiple operation calls within a session.
///
/// The server creates one `DispatchState` per accepted connection and passes it
/// through each call to `dispatch_operation`.
///
/// M-8: `session_open` tracks whether `OpenSession` has been called.  Operations
/// other than `GetDeviceInfo` and `OpenSession` return `SESSION_NOT_OPEN` (0x2003)
/// when `session_open` is `false`, matching the PTP spec invariant and preventing
/// false-green CI where operations succeed without a prior `OpenSession`.
#[derive(Debug)]
pub struct DispatchState {
    /// How many more BUSY responses to emit before returning OK on mode write.
    pub remaining_busy: u32,
    /// Whether `OpenSession` has been called on this connection.
    ///
    /// Set to `true` by the `OpenSession` arm; never reset (CloseSession ends the
    /// TCP session entirely).  Operations other than `GetDeviceInfo` and `OpenSession`
    /// return `SESSION_NOT_OPEN` (0x2003) when this is `false`.
    pub session_open: bool,
}

impl DispatchState {
    // cancel-safe: this is a synchronous constructor, no .await points.
    pub fn new(config: &ServerConfig) -> Self {
        Self {
            remaining_busy: config.busy_count,
            session_open: false,
        }
    }
}

// ── dispatch_operation ────────────────────────────────────────────────────────

/// Dispatches a single PTP-IP OperationRequest and returns the response.
///
/// The caller must have already extracted `opcode`, `transaction_id`, and `params`
/// from the decoded [`PtpIpPacket::OperationRequest`].
///
/// Returns `Ok(DispatchResult)`.
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
) -> Result<DispatchResult, SimError> {
    // M-8: session-open guard.
    // GetDeviceInfo (0x1001) and OpenSession (0x1002) are the only operations
    // permitted before OpenSession has been called, per ISO 15740 §10.1.
    // All other operations return SESSION_NOT_OPEN (0x2003) when session_open
    // is false.  This prevents false-green CI where operations succeed without
    // a prior OpenSession call.
    let requires_open_session =
        opcode_val != opcode::GET_DEVICE_INFO && opcode_val != opcode::OPEN_SESSION;
    if requires_open_session && !state.session_open {
        return Ok(DispatchResult::Packets(vec![
            PtpIpPacket::OperationResponse {
                response_code: response_code::SESSION_NOT_OPEN,
                transaction_id,
                result_params: vec![],
            },
        ]));
    }

    match opcode_val {
        // ── GetDeviceInfo (0x1001) ────────────────────────────────────────────
        // Returns a minimal DeviceInfo dataset in the section 4.9 data phase.
        // Cited: ptp-ptpip.md section 6.1, master-constants.md §2a.
        op if op == opcode::GET_DEVICE_INFO => {
            let payload = build_device_info_dataset();
            Ok(DispatchResult::Packets(data_phase_packets(
                transaction_id,
                payload,
            )))
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
            // M-8: mark session as open so subsequent operations are permitted.
            state.session_open = true;
            Ok(DispatchResult::Packets(vec![ok_response(transaction_id)]))
        }

        // ── CloseSession (0x1003) ─────────────────────────────────────────────
        // Responds with OK and signals the server to close the connection.
        op if op == opcode::CLOSE_SESSION => {
            Ok(DispatchResult::Packets(vec![ok_response(transaction_id)]))
        }

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
        // The server validates the data-phase payload width for known property codes
        // before applying the busy_count/fatal_on_set_mode logic.
        op if op == opcode::SET_DEVICE_PROP_VALUE => {
            let prop = params.first().copied().map(|p| p as u16);

            // Validate data-phase payload width for known properties.
            // Missing data phase on any set operation is a framing error.
            // Wrong width for a known property returns PARAMETER_NOT_SUPPORTED
            // (0x2006) — the value cannot be accepted per the property contract.
            //
            // Property widths sourced from master-constants.md §3b:
            //   ClientState      (0xDF01) — UINT16 — 2 bytes [H]
            //   ImageGetVersion  (0xDF21) — UINT32 — 4 bytes [H]
            //   CameraState      (0xDF00) — UINT16 — 2 bytes [H]
            //   EnableCorrectFileSize (0xD227) — UINT16 — 2 bytes [H]
            if let Some(prop_u16) = prop {
                let expected_width: Option<usize> = if prop_u16 == prop_code::IMAGE_GET_VERSION {
                    Some(4) // UINT32 — master-constants.md §3b
                } else if prop_u16 == prop_code::CLIENT_STATE
                    || prop_u16 == prop_code::CAMERA_STATE
                    || prop_u16 == prop_code::ENABLE_CORRECT_FILE_SIZE
                {
                    Some(2) // UINT16 — master-constants.md §3b
                } else {
                    None // unknown property: no width check
                };

                if let Some(width) = expected_width {
                    match data_payload {
                        None => {
                            // SetDevicePropValue on a known writable property with no data phase
                            // is a structural protocol violation — the framing is broken.
                            return Err(SimError::ProtocolViolation(format!(
                                "SetDevicePropValue(prop=0x{prop_u16:04X}): missing data phase; expected {width}-byte payload"
                            )));
                        }
                        Some(payload) if payload.len() != width => {
                            // Wrong-width payload for a known property: the value cannot be
                            // accepted. Return PARAMETER_NOT_SUPPORTED (0x2006) — the
                            // parameter's encoded form is not acceptable per ISO 15740 §12.
                            // ptp-ptpip.md section 6.1 / master-constants.md §4b.
                            return Ok(DispatchResult::Packets(vec![
                                PtpIpPacket::OperationResponse {
                                    response_code: response_code::PARAMETER_NOT_SUPPORTED,
                                    transaction_id,
                                    result_params: vec![],
                                },
                            ]));
                        }
                        Some(_) => {
                            // Payload is present and the correct width — proceed to
                            // busy_count / fatal_on_set_mode logic below.
                        }
                    }
                }
            }

            if let Some(fatal_code) = config.fatal_on_set_mode {
                return Ok(DispatchResult::Packets(vec![
                    PtpIpPacket::OperationResponse {
                        response_code: fatal_code,
                        transaction_id,
                        result_params: vec![],
                    },
                ]));
            }
            if state.remaining_busy > 0 {
                state.remaining_busy -= 1;
                Ok(DispatchResult::Packets(vec![
                    PtpIpPacket::OperationResponse {
                        response_code: response_code::BUSY,
                        transaction_id,
                        result_params: vec![],
                    },
                ]))
            } else {
                Ok(DispatchResult::Packets(vec![ok_response(transaction_id)]))
            }
        }

        // ── GetObjectInfo (0x1008) ────────────────────────────────────────────
        // Returns a PTP ObjectInfo dataset for the requested object handle.
        // Data phase: StartData → EndData(ObjectInfo bytes) → OperationResponse(OK).
        //
        // Fault: if `faults.malformed_object_info_handle == Some(handle)`, sends a
        // 4-byte EndData payload (too short to decode as ObjectInfo) so the client
        // decoder returns `PtpCodecError::PacketTooShort`.
        //
        // The object handle is params[0]; it is NOT a field inside ObjectInfo.
        // master-constants.md §6d; transfer-liveview.md section 3.1. [H]
        op if op == opcode::GET_OBJECT_INFO => {
            let handle = params.first().copied().unwrap_or(0);

            // Malformed-ObjectInfo fault: return a 4-byte payload so the decoder
            // returns PtpCodecError::PacketTooShort.
            if config.faults.malformed_object_info_handle == Some(handle) {
                let bad_payload = vec![0xFFu8, 0xFFu8, 0xFFu8, 0xFFu8]; // 4 bytes — too short
                return Ok(DispatchResult::Packets(data_phase_packets(
                    transaction_id,
                    bad_payload,
                )));
            }

            match config.object_by_handle(handle) {
                None => Ok(DispatchResult::Packets(vec![
                    PtpIpPacket::OperationResponse {
                        response_code: response_code::INVALID_OBJECT_HANDLE,
                        transaction_id,
                        result_params: vec![],
                    },
                ])),
                Some(obj) => {
                    let info = ObjectInfo {
                        storage_id: SIM_STORAGE_ID,
                        object_format: obj.format_code,
                        protection_status: 0,
                        object_compressed_size: obj.compressed_size,
                        thumb_format: fuji_ptp::constants::object_format::JPEG,
                        thumb_compressed_size: obj.thumb_bytes.len() as u32,
                        thumb_pix_width: 160,
                        thumb_pix_height: 120,
                        image_pix_width: 0,
                        image_pix_height: 0,
                        image_bit_depth: 0,
                        parent_object: 0,
                        association_type: 0,
                        association_desc: 0,
                        sequence_number: handle,
                        filename: obj.filename.clone(),
                        capture_date: obj.capture_date.clone(),
                        modification_date: String::new(),
                        keywords: String::new(),
                    };
                    let payload = encode_object_info(&info);
                    Ok(DispatchResult::Packets(data_phase_packets(
                        transaction_id,
                        payload,
                    )))
                }
            }
        }

        // ── GetThumb (0x100A) ─────────────────────────────────────────────────
        // Returns the thumbnail bytes for the requested handle.
        // Data phase: StartData → EndData(thumb_bytes) → OperationResponse(OK).
        // master-constants.md §6d; transfer-liveview.md section 5.1. [H]
        op if op == opcode::GET_THUMB => {
            let handle = params.first().copied().unwrap_or(0);
            match config.object_by_handle(handle) {
                None => Ok(DispatchResult::Packets(vec![
                    PtpIpPacket::OperationResponse {
                        response_code: response_code::INVALID_OBJECT_HANDLE,
                        transaction_id,
                        result_params: vec![],
                    },
                ])),
                Some(obj) => {
                    let payload = obj.thumb_bytes.clone();
                    Ok(DispatchResult::Packets(data_phase_packets(
                        transaction_id,
                        payload,
                    )))
                }
            }
        }

        // ── GetObject (0x1009) ────────────────────────────────────────────────
        // Streams the full object payload.
        //
        // Returns DispatchResult::StreamObject so the server layer writes the raw
        // bytes directly to the TCP socket without buffering the whole object as
        // a PtpIpPacket::EndData (streaming requirement).
        //
        // Size-mismatch fault: when `faults.size_mismatch_handle == Some(handle)`,
        // `total_data_length` in StartData is set to `obj.compressed_size` (the
        // advertised size, which may differ from `obj.object_bytes.len()`).  The
        // client's integrity check (`bytes_written != ObjectInfo.compressed_size`)
        // fires after streaming.
        //
        // Connection-reset fault: when `faults.connection_reset_after_bytes ==
        // Some((handle, k))`, the server drops the TCP connection after writing
        // `k` bytes of the EndData body.
        //
        // NOTE: production against a real X-T5 should use GetPartialObject (0x101B)
        // with ≤1 MiB chunks per transfer-liveview.md section 5.2 (stall caveat).
        // For this milestone (validated only against fuji-sim), GetObject single-
        // phase is used with 64 KiB socket reads.
        // TODO(frameport): switch to GetPartialObject chunking for real-hardware path.
        op if op == opcode::GET_OBJECT => {
            let handle = params.first().copied().unwrap_or(0);
            match config.object_by_handle(handle) {
                None => Ok(DispatchResult::Packets(vec![
                    PtpIpPacket::OperationResponse {
                        response_code: response_code::INVALID_OBJECT_HANDLE,
                        transaction_id,
                        result_params: vec![],
                    },
                ])),
                Some(obj) => {
                    // total_data_length always equals object_bytes.len() — consistent
                    // on-wire framing.  The size-mismatch fault is expressed exclusively
                    // through ObjectInfo: GetObjectInfo advertises compressed_size
                    // (e.g. 102400) while object_bytes.len() is the smaller actual
                    // streamed length (e.g. 51200).  After streaming, download_to_owned_fd
                    // compares bytes_written against the expected size from ObjectInfo and
                    // returns TransferError::SizeMismatch.
                    let total_data_length = obj.object_bytes.len() as u64;

                    let reset_after_bytes =
                        config
                            .faults
                            .connection_reset_after_bytes
                            .and_then(|(fault_handle, k)| {
                                if fault_handle == handle {
                                    Some(k)
                                } else {
                                    None
                                }
                            });

                    Ok(DispatchResult::StreamObject {
                        transaction_id,
                        total_data_length,
                        object_bytes: obj.object_bytes.clone(),
                        reset_after_bytes,
                    })
                }
            }
        }

        // ── InitiateCapture (0x100E) ─────────────────────────────────────────
        // Remote shutter: triggers a capture. Returns OK immediately.
        // master-constants.md §2a; ptp-ptpip.md opcode table. [H]
        op if op == opcode::INITIATE_CAPTURE => {
            Ok(DispatchResult::Packets(vec![ok_response(transaction_id)]))
        }

        // ── InitiateOpenCapture (0x101C) ──────────────────────────────────────
        // Opens the remote-capture session. Per protocol the camera then opens
        // the event (55741) and liveview (55742) channels. The sim returns OK;
        // the event-channel path is exercised separately in scenarios.rs Scenario E.
        // master-constants.md §2a; ptp-ptpip.md opcode table. [H]
        op if op == opcode::INITIATE_OPEN_CAPTURE => {
            Ok(DispatchResult::Packets(vec![ok_response(transaction_id)]))
        }

        // ── TerminateOpenCapture (0x1018) ─────────────────────────────────────
        // Closes the remote-capture session. Returns OK.
        // master-constants.md §2a; ptp-ptpip.md opcode table. [H]
        op if op == opcode::TERMINATE_OPEN_CAPTURE => {
            Ok(DispatchResult::Packets(vec![ok_response(transaction_id)]))
        }

        // ── Unrecognised opcode ───────────────────────────────────────────────
        // Return OperationNotSupported rather than panicking or dropping silently.
        _ => Ok(DispatchResult::Packets(vec![
            PtpIpPacket::OperationResponse {
                response_code: response_code::OPERATION_NOT_SUPPORTED,
                transaction_id,
                result_params: vec![],
            },
        ])),
    }
}

// ── Property dispatch ─────────────────────────────────────────────────────────

// cancel-safe: synchronous — no .await points.
fn dispatch_get_prop(
    prop: u32,
    transaction_id: u32,
    config: &ServerConfig,
) -> Result<DispatchResult, SimError> {
    // Cast to u16 for comparison with named prop_code constants.
    let prop_u16 = prop as u16;

    if prop_u16 == prop_code::IMAGE_GET_VERSION {
        // GetDevicePropValue(IMAGE_GET_VERSION / 0xDF21): return the firmware version word.
        // master-constants.md §3b: "Read then re-write to confirm support." [H]
        let payload = config.firmware_version_word.to_le_bytes().to_vec();
        return Ok(DispatchResult::Packets(data_phase_packets(
            transaction_id,
            payload,
        )));
    }

    if prop_u16 == prop_code::EVENTS_LIST {
        // GetDevicePropValue(EventsList / 0xD212): return an empty events list.
        // ptp-ptpip.md section 8.3: response is u16 count + count×{u16 code, u32 value}. [H]
        // Count = 0 → payload is just two zero bytes.
        let payload = vec![0u8, 0u8];
        return Ok(DispatchResult::Packets(data_phase_packets(
            transaction_id,
            payload,
        )));
    }

    if prop_u16 == prop_code::OBJECT_COUNT {
        // GetDevicePropValue(ObjectCount / 0xD222): return the configured object count.
        // master-constants.md §3b: UINT32. [H]
        let payload = config.object_count.to_le_bytes().to_vec();
        return Ok(DispatchResult::Packets(data_phase_packets(
            transaction_id,
            payload,
        )));
    }

    // Unsupported property: return DevicePropNotSupported.
    Ok(DispatchResult::Packets(vec![
        PtpIpPacket::OperationResponse {
            response_code: response_code::DEVICE_PROP_NOT_SUPPORTED,
            transaction_id,
            result_params: vec![],
        },
    ]))
}

// ── Response builders ─────────────────────────────────────────────────────────

/// Builds the section 4.9 data-phase packet sequence:
/// `StartData`, `EndData`, `OperationResponse(OK)`.
// cancel-safe: synchronous — no .await points.
fn data_phase_packets(transaction_id: u32, payload: Vec<u8>) -> Vec<PtpIpPacket> {
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
    // Remote-capture opcodes (M15) — master-constants.md §2a. [H]
    opcode::INITIATE_CAPTURE,       // 0x100E — remote shutter trigger
    opcode::TERMINATE_OPEN_CAPTURE, // 0x1018 — close remote-capture session
    opcode::INITIATE_OPEN_CAPTURE,  // 0x101C — open remote-capture session (event+liveview)
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

// ── Unit tests ────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::builder::FakeCameraBuilder;
    use fuji_ptp::constants::{object_format, opcode, response_code};

    fn default_config() -> ServerConfig {
        FakeCameraBuilder::new().build()
    }

    fn unwrap_packets(result: Result<DispatchResult, SimError>) -> Vec<PtpIpPacket> {
        match result.expect("dispatch must succeed") {
            DispatchResult::Packets(pkts) => pkts,
            DispatchResult::StreamObject { .. } => panic!("expected Packets, got StreamObject"),
        }
    }

    /// Calls OpenSession on `state` so that subsequent operations are permitted.
    ///
    /// M-8: tests that exercise operations other than GetDeviceInfo/OpenSession
    /// must call this helper first; without it the session-open guard returns
    /// SESSION_NOT_OPEN (0x2003) before the operation logic runs.
    fn open_session_on(state: &mut DispatchState, config: &ServerConfig) {
        let pkts = unwrap_packets(dispatch_operation(
            opcode::OPEN_SESSION,
            0,
            &[fuji_ptp::constants::SESSION_ID],
            None,
            state,
            config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "open_session_on: OpenSession must return OK; got {pkts:?}"
        );
    }

    #[test]
    fn open_session_returns_ok() {
        let config = default_config();
        let mut state = DispatchState::new(&config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::OPEN_SESSION,
            0,
            &[fuji_ptp::constants::SESSION_ID],
            None,
            &mut state,
            &config,
        ));
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
        let pkts = unwrap_packets(dispatch_operation(
            opcode::GET_DEVICE_INFO,
            1,
            &[],
            None,
            &mut state,
            &config,
        ));
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
        open_session_on(&mut state, &config);

        // IMAGE_GET_VERSION (0xDF21) is UINT32 — payload must be exactly 4 bytes.
        // master-constants.md §3b. [H]
        let valid_payload: &[u8] = &[0x04u8, 0x00u8, 0x02u8, 0x00u8]; // 0x00020004 LE

        // First two calls → BUSY
        for _ in 0..2 {
            let pkts = unwrap_packets(dispatch_operation(
                opcode::SET_DEVICE_PROP_VALUE,
                1,
                &[prop_code::IMAGE_GET_VERSION as u32],
                Some(valid_payload),
                &mut state,
                &config,
            ));
            assert!(matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::BUSY
            ));
        }

        // Third call → OK
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            2,
            &[prop_code::IMAGE_GET_VERSION as u32],
            Some(valid_payload),
            &mut state,
            &config,
        ));
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
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[],
            None,
            &mut state,
            &config,
        ));
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
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::GET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::OBJECT_COUNT as u32],
            None,
            &mut state,
            &config,
        ));
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
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::GET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::EVENTS_LIST as u32],
            None,
            &mut state,
            &config,
        ));
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
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            0xFFFF,
            99,
            &[],
            None,
            &mut state,
            &config,
        ));
        assert!(matches!(
            &pkts[0],
            PtpIpPacket::OperationResponse { response_code, .. }
            if *response_code == response_code::OPERATION_NOT_SUPPORTED
        ));
    }

    // ── GetObjectInfo unit tests ──────────────────────────────────────────────

    #[test]
    fn get_object_info_returns_data_phase_for_valid_handle() {
        let config = FakeCameraBuilder::new()
            .add_object(
                object_format::JPEG,
                1024,
                "TEST.JPG",
                "20241225T120000",
                vec![0u8; 1024],
                vec![0u8; 64],
            )
            .build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::GET_OBJECT_INFO,
            1,
            &[1u32], // handle 1
            None,
            &mut state,
            &config,
        ));
        assert_eq!(pkts.len(), 3, "must be StartData, EndData, OK");
        assert!(matches!(pkts[0], PtpIpPacket::StartData { .. }));
        assert!(matches!(
            &pkts[2],
            PtpIpPacket::OperationResponse { response_code, .. }
            if *response_code == response_code::OK
        ));
        // The EndData payload must be decodeable as ObjectInfo.
        if let PtpIpPacket::EndData { payload, .. } = &pkts[1] {
            let info = fuji_ptp::decode_object_info(payload).expect("ObjectInfo must decode");
            assert_eq!(info.object_format, object_format::JPEG);
            assert_eq!(info.object_compressed_size, 1024);
            assert_eq!(info.filename, "TEST.JPG");
        } else {
            panic!("expected EndData at index 1");
        }
    }

    #[test]
    fn get_object_info_returns_invalid_handle_for_missing_object() {
        let config = FakeCameraBuilder::new().build(); // no objects
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::GET_OBJECT_INFO,
            1,
            &[99u32], // no object with handle 99
            None,
            &mut state,
            &config,
        ));
        assert!(matches!(
            &pkts[0],
            PtpIpPacket::OperationResponse { response_code, .. }
            if *response_code == response_code::INVALID_OBJECT_HANDLE
        ));
    }

    #[test]
    fn get_object_info_malformed_fault_sends_short_payload() {
        let config = FakeCameraBuilder::new()
            .add_object(
                object_format::JPEG,
                1024,
                "X.JPG",
                "",
                vec![0u8; 1024],
                vec![],
            )
            .with_malformed_object_info_fault(1)
            .build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::GET_OBJECT_INFO,
            1,
            &[1u32],
            None,
            &mut state,
            &config,
        ));
        if let PtpIpPacket::EndData { payload, .. } = &pkts[1] {
            // 4-byte payload — too short to decode as ObjectInfo (min 52 bytes).
            assert_eq!(
                payload.len(),
                4,
                "malformed payload must be exactly 4 bytes"
            );
            let result = fuji_ptp::decode_object_info(payload);
            assert!(
                result.is_err(),
                "4-byte payload must fail to decode as ObjectInfo"
            );
        } else {
            panic!("expected EndData at index 1");
        }
    }

    // ── GetThumb unit test ────────────────────────────────────────────────────

    #[test]
    fn get_thumb_returns_thumb_bytes() {
        let thumb = vec![0xFFu8, 0xD8u8, 0xFFu8, 0xE0u8]; // minimal JPEG magic
        let config = FakeCameraBuilder::new()
            .add_object(
                object_format::JPEG,
                1024,
                "T.JPG",
                "",
                vec![0u8; 1024],
                thumb.clone(),
            )
            .build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::GET_THUMB,
            1,
            &[1u32],
            None,
            &mut state,
            &config,
        ));
        assert_eq!(pkts.len(), 3);
        if let PtpIpPacket::EndData { payload, .. } = &pkts[1] {
            assert_eq!(*payload, thumb, "thumb payload must match stored bytes");
        } else {
            panic!("expected EndData at index 1");
        }
    }

    // ── GetObject unit test ───────────────────────────────────────────────────

    #[test]
    fn get_object_returns_stream_result() {
        let data = vec![0xAAu8; 4096];
        let config = FakeCameraBuilder::new()
            .add_object(
                object_format::JPEG,
                data.len() as u32,
                "D.JPG",
                "",
                data.clone(),
                vec![],
            )
            .build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let result = dispatch_operation(opcode::GET_OBJECT, 1, &[1u32], None, &mut state, &config)
            .expect("dispatch must succeed");
        match result {
            DispatchResult::StreamObject {
                total_data_length,
                object_bytes,
                reset_after_bytes,
                ..
            } => {
                assert_eq!(total_data_length, 4096);
                assert_eq!(object_bytes, data);
                assert!(reset_after_bytes.is_none(), "no fault configured");
            }
            DispatchResult::Packets(_) => panic!("expected StreamObject for GetObject"),
        }
    }

    #[test]
    fn get_object_size_mismatch_fault_total_equals_actual_bytes() {
        // object_bytes has 100 bytes but compressed_size advertises 999.
        // The size-mismatch is expressed via ObjectInfo (compressed_size=999) only;
        // total_data_length in StartData must equal object_bytes.len() (100) for
        // consistent on-wire framing.  The client's post-loop integrity check
        // (bytes_written != ObjectInfo.compressed_size) produces SizeMismatch.
        let config = FakeCameraBuilder::new()
            .add_object(
                object_format::JPEG,
                999, // advertised size — intentionally wrong (used in ObjectInfo)
                "M.JPG",
                "",
                vec![0u8; 100], // actual bytes — determines total_data_length
                vec![],
            )
            .with_size_mismatch_fault(1)
            .build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let result = dispatch_operation(opcode::GET_OBJECT, 1, &[1u32], None, &mut state, &config)
            .expect("dispatch must succeed");
        match result {
            DispatchResult::StreamObject {
                total_data_length,
                object_bytes,
                ..
            } => {
                // total_data_length must equal actual bytes (100), not compressed_size (999).
                assert_eq!(
                    total_data_length, 100,
                    "StartData total_data_length must equal object_bytes.len()"
                );
                assert_eq!(object_bytes.len(), 100, "object_bytes is the real data");
            }
            DispatchResult::Packets(_) => panic!("expected StreamObject"),
        }
    }

    #[test]
    fn get_object_connection_reset_fault_propagates_reset_after_bytes() {
        let config = FakeCameraBuilder::new()
            .add_object(
                object_format::JPEG,
                1024,
                "R.JPG",
                "",
                vec![0u8; 1024],
                vec![],
            )
            .with_connection_reset_fault(1, 512)
            .build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let result = dispatch_operation(opcode::GET_OBJECT, 1, &[1u32], None, &mut state, &config)
            .expect("dispatch must succeed");
        match result {
            DispatchResult::StreamObject {
                reset_after_bytes, ..
            } => {
                assert_eq!(reset_after_bytes, Some(512));
            }
            DispatchResult::Packets(_) => panic!("expected StreamObject"),
        }
    }

    // ── SetDevicePropValue payload-validation unit tests ──────────────────────

    /// A well-formed ClientState write (UINT16, 2 bytes) with busy_count=0 must
    /// return OK.
    ///
    /// ClientState (0xDF01) is UINT16 — 2 bytes per master-constants.md §3b. [H]
    #[test]
    fn set_client_state_well_formed_returns_ok() {
        let config = FakeCameraBuilder::new().busy_count(0).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        // UINT16 LE payload: value=1 (IMAGE_RECEIVE mode)
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::CLIENT_STATE as u32],
            Some(&[1u8, 0u8]),
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "well-formed ClientState write (busy_count=0) must return OK; got {pkts:?}"
        );
    }

    /// A ClientState write with a wrong-width payload (3 bytes instead of 2) must
    /// return PARAMETER_NOT_SUPPORTED (0x2006) rather than OK or BUSY.
    ///
    /// ClientState (0xDF01) is UINT16 — 2 bytes per master-constants.md §3b. [H]
    /// PARAMETER_NOT_SUPPORTED: ptp-ptpip.md section 6.1 / master-constants.md §4b.
    #[test]
    fn set_client_state_wrong_width_returns_parameter_not_supported() {
        let config = FakeCameraBuilder::new().busy_count(0).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        // 3-byte payload for a UINT16 property — wrong width.
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::CLIENT_STATE as u32],
            Some(&[1u8, 0u8, 0u8]),
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::PARAMETER_NOT_SUPPORTED
            ),
            "wrong-width ClientState write must return PARAMETER_NOT_SUPPORTED; got {pkts:?}"
        );
    }

    /// A ClientState write with an empty payload must return PARAMETER_NOT_SUPPORTED.
    ///
    /// An empty data-phase for a known property is also a width violation (0 ≠ 2).
    #[test]
    fn set_client_state_empty_payload_returns_parameter_not_supported() {
        let config = FakeCameraBuilder::new().busy_count(0).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::CLIENT_STATE as u32],
            Some(&[]),
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::PARAMETER_NOT_SUPPORTED
            ),
            "empty payload for ClientState must return PARAMETER_NOT_SUPPORTED; got {pkts:?}"
        );
    }

    /// A ClientState write with no data phase at all (None) on a known property must
    /// return a ProtocolViolation error — the framing itself is broken.
    ///
    /// ptp-ptpip.md section 4.9: a write operation carries data_phase=2; the server
    /// reads StartData→EndData before dispatch. None here means the caller did not
    /// supply any data phase bytes, which is a structural framing error. [H]
    #[test]
    fn set_client_state_no_data_phase_is_protocol_violation() {
        let config = FakeCameraBuilder::new().busy_count(0).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let result = dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::CLIENT_STATE as u32],
            None, // no data phase supplied for a known write property
            &mut state,
            &config,
        );
        assert!(
            matches!(result, Err(SimError::ProtocolViolation(_))),
            "missing data phase for ClientState must be a ProtocolViolation; got {result:?}"
        );
    }

    /// An ImageGetVersion write with a wrong-width payload (2 bytes instead of 4) must
    /// return PARAMETER_NOT_SUPPORTED.
    ///
    /// ImageGetVersion (0xDF21) is UINT32 — 4 bytes per master-constants.md §3b. [H]
    #[test]
    fn set_image_get_version_wrong_width_returns_parameter_not_supported() {
        let config = FakeCameraBuilder::new().busy_count(0).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        // 2-byte payload for a UINT32 property — wrong width.
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::IMAGE_GET_VERSION as u32],
            Some(&[0x04u8, 0x00u8]),
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::PARAMETER_NOT_SUPPORTED
            ),
            "wrong-width ImageGetVersion write must return PARAMETER_NOT_SUPPORTED; got {pkts:?}"
        );
    }

    /// An EnableCorrectFileSize write with wrong-width payload (1 byte instead of 2) must
    /// return PARAMETER_NOT_SUPPORTED.
    ///
    /// EnableCorrectFileSize (0xD227) is UINT16 — 2 bytes per master-constants.md §3b. [H]
    #[test]
    fn set_enable_correct_file_size_wrong_width_returns_parameter_not_supported() {
        let config = FakeCameraBuilder::new().busy_count(0).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        // 1-byte payload for a UINT16 property — wrong width.
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::ENABLE_CORRECT_FILE_SIZE as u32],
            Some(&[1u8]),
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::PARAMETER_NOT_SUPPORTED
            ),
            "wrong-width EnableCorrectFileSize write must return PARAMETER_NOT_SUPPORTED; got {pkts:?}"
        );
    }

    /// A well-formed EnableCorrectFileSize write (UINT16, 2 bytes, value=1) with
    /// busy_count=0 must still return OK — the existing busy/fatal logic is not regressed.
    ///
    /// EnableCorrectFileSize (0xD227) is UINT16 — 2 bytes per master-constants.md §3b. [H]
    #[test]
    fn set_enable_correct_file_size_well_formed_returns_ok() {
        let config = FakeCameraBuilder::new().busy_count(0).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::ENABLE_CORRECT_FILE_SIZE as u32],
            Some(&[1u8, 0u8]),
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "well-formed EnableCorrectFileSize write (busy_count=0) must return OK; got {pkts:?}"
        );
    }

    /// Payload-validation fires BEFORE busy_count logic: a wrong-width ClientState
    /// write must return PARAMETER_NOT_SUPPORTED even when busy_count > 0.
    ///
    /// This ensures that the validation gate is not bypassed by the retry-state path.
    #[test]
    fn payload_validation_fires_before_busy_count() {
        // busy_count=3 — if validation were skipped, we'd get BUSY instead.
        let config = FakeCameraBuilder::new().busy_count(3).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        // 3-byte payload for UINT16 property — wrong width.
        let pkts = unwrap_packets(dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[prop_code::CLIENT_STATE as u32],
            Some(&[1u8, 0u8, 0u8]),
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::PARAMETER_NOT_SUPPORTED
            ),
            "wrong-width payload must return PARAMETER_NOT_SUPPORTED even with busy_count>0; got {pkts:?}"
        );
        // busy count must not have been decremented — the invalid write was rejected.
        assert_eq!(
            state.remaining_busy, 3,
            "remaining_busy must not be decremented on a rejected write"
        );
    }

    // ── M-8: session-open guard tests ────────────────────────────────────────

    /// GetDeviceInfo is permitted before OpenSession (no session required).
    #[test]
    fn get_device_info_allowed_before_open_session() {
        let config = default_config();
        let mut state = DispatchState::new(&config);
        assert!(!state.session_open, "session must start closed");
        let pkts = unwrap_packets(dispatch_operation(
            opcode::GET_DEVICE_INFO,
            1,
            &[],
            None,
            &mut state,
            &config,
        ));
        // StartData, EndData, OK — not SESSION_NOT_OPEN.
        assert_eq!(pkts.len(), 3);
        assert!(
            !matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::SESSION_NOT_OPEN
            ),
            "GetDeviceInfo must not return SESSION_NOT_OPEN before OpenSession"
        );
    }

    /// Any operation other than GetDeviceInfo/OpenSession returns SESSION_NOT_OPEN
    /// (0x2003) when no OpenSession has been called.
    #[test]
    fn operation_before_open_session_returns_session_not_open() {
        let config = default_config();
        let mut state = DispatchState::new(&config);
        assert!(!state.session_open);

        // CloseSession without OpenSession must return SESSION_NOT_OPEN.
        let pkts = unwrap_packets(dispatch_operation(
            opcode::CLOSE_SESSION,
            1,
            &[],
            None,
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::SESSION_NOT_OPEN
            ),
            "CloseSession before OpenSession must return SESSION_NOT_OPEN; got {pkts:?}"
        );

        // GetDevicePropValue without OpenSession must also return SESSION_NOT_OPEN.
        let pkts2 = unwrap_packets(dispatch_operation(
            opcode::GET_DEVICE_PROP_VALUE,
            2,
            &[prop_code::OBJECT_COUNT as u32],
            None,
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts2[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::SESSION_NOT_OPEN
            ),
            "GetDevicePropValue before OpenSession must return SESSION_NOT_OPEN; got {pkts2:?}"
        );

        // session_open must still be false — guard must not mutate state on rejection.
        assert!(
            !state.session_open,
            "session_open must remain false after rejected operations"
        );
    }

    /// After a successful OpenSession, subsequent operations are permitted normally.
    #[test]
    fn operations_succeed_after_open_session() {
        let config = default_config();
        let mut state = DispatchState::new(&config);

        // OpenSession first.
        let pkts = unwrap_packets(dispatch_operation(
            opcode::OPEN_SESSION,
            0,
            &[fuji_ptp::constants::SESSION_ID],
            None,
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "OpenSession must return OK; got {pkts:?}"
        );
        assert!(
            state.session_open,
            "session_open must be true after OpenSession"
        );

        // CloseSession now permitted.
        let pkts2 = unwrap_packets(dispatch_operation(
            opcode::CLOSE_SESSION,
            1,
            &[],
            None,
            &mut state,
            &config,
        ));
        assert!(
            matches!(
                &pkts2[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "CloseSession after OpenSession must return OK; got {pkts2:?}"
        );
    }

    /// An unknown property code with no data phase passes through to BUSY/OK logic
    /// (unknown properties are not validated for width).
    ///
    /// This matches the dispatch contract: only known property codes are width-checked.
    #[test]
    fn set_unknown_property_no_data_is_not_protocol_violation() {
        // Unknown property 0xFFFF — not in the known-width table.
        let config = FakeCameraBuilder::new().busy_count(0).build();
        let mut state = DispatchState::new(&config);
        open_session_on(&mut state, &config);
        let result = dispatch_operation(
            opcode::SET_DEVICE_PROP_VALUE,
            1,
            &[0xFFFFu32],
            None, // no data phase — allowed for unknown properties
            &mut state,
            &config,
        );
        // Must not be a ProtocolViolation — unknown props skip width validation.
        assert!(
            result.is_ok(),
            "unknown property with None data must not be a ProtocolViolation; got {result:?}"
        );
        let pkts = unwrap_packets(result);
        assert!(
            matches!(
                &pkts[0],
                PtpIpPacket::OperationResponse { response_code, .. }
                if *response_code == response_code::OK
            ),
            "unknown property with None data and busy_count=0 must return OK; got {pkts:?}"
        );
    }

    /// DeviceInfo binary correctly advertises required operations and properties.
    ///
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
