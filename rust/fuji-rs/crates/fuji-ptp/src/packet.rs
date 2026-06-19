//! PTP-IP packet type codes and the [`PtpIpPacket`] enum.
//!
//! All 14 packet types from ptp-ptpip.md section 4.1 are represented here.
//! Field layouts follow the per-type tables in sections 4.2–4.12.

use crate::error::PtpCodecError;
use crate::header::{HEADER_SIZE, PtpIpHeader};
use crate::string::{DEVICE_NAME_FIELD_BYTES, decode_device_name, encode_device_name};

// ── Packet type codes ─────────────────────────────────────────────────────────

/// All 14 PTP-IP packet type codes (ptp-ptpip.md section 4.1). [H]
///
/// Numeric values are fixed wire-format constants; changing them breaks camera
/// interoperability.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u32)]
pub enum PtpIpPacketType {
    /// `0x0001` Initiator → Responder — first packet of the command-channel handshake.
    InitCommandRequest = 0x0001,
    /// `0x0002` Responder → Initiator — camera acknowledges the command-channel connection.
    InitCommandAck = 0x0002,
    /// `0x0003` Initiator → Responder — opens the event channel.
    InitEventRequest = 0x0003,
    /// `0x0004` Responder → Initiator — camera acknowledges the event channel.
    InitEventAck = 0x0004,
    /// `0x0005` Responder → Initiator — camera rejects the connection attempt.
    InitFail = 0x0005,
    /// `0x0006` Initiator → Responder — a PTP operation request (command).
    OperationRequest = 0x0006,
    /// `0x0007` Responder → Initiator — response to an operation request.
    OperationResponse = 0x0007,
    /// `0x0008` Responder → Initiator — asynchronous event from the camera.
    Event = 0x0008,
    /// `0x0009` Either — signals the start of a data transfer with the total length.
    DataPacketStart = 0x0009,
    /// `0x000A` Either — an intermediate data chunk.
    DataPacket = 0x000A,
    /// `0x000B` Either — cancels the current data transfer.
    CancelTransaction = 0x000B,
    /// `0x000C` Either — final data chunk (end of the data phase).
    DataPacketEnd = 0x000C,
    /// `0x000D` Either — keep-alive ping.
    Ping = 0x000D,
    /// `0x000E` Either — response to a Ping.
    Pong = 0x000E,
}

impl PtpIpPacketType {
    /// Converts a raw wire code to [`PtpIpPacketType`], or `None` if the code is unknown.
    pub fn from_code(code: u32) -> Option<Self> {
        match code {
            0x0001 => Some(Self::InitCommandRequest),
            0x0002 => Some(Self::InitCommandAck),
            0x0003 => Some(Self::InitEventRequest),
            0x0004 => Some(Self::InitEventAck),
            0x0005 => Some(Self::InitFail),
            0x0006 => Some(Self::OperationRequest),
            0x0007 => Some(Self::OperationResponse),
            0x0008 => Some(Self::Event),
            0x0009 => Some(Self::DataPacketStart),
            0x000A => Some(Self::DataPacket),
            0x000B => Some(Self::CancelTransaction),
            0x000C => Some(Self::DataPacketEnd),
            0x000D => Some(Self::Ping),
            0x000E => Some(Self::Pong),
            _ => None,
        }
    }

    /// Returns the wire code for this packet type.
    pub fn to_code(self) -> u32 {
        self as u32
    }
}

// ── Packet variants ───────────────────────────────────────────────────────────

/// A decoded PTP-IP packet with typed fields.
///
/// Field layouts follow ptp-ptpip.md sections 4.2–4.12.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PtpIpPacket {
    // ── Handshake ──────────────────────────────────────────────────────────
    /// `PTPIP_INIT_COMMAND_REQ` (0x0001) — 82 bytes total (section 4.2).
    ///
    /// The `version` field carries the Fujifilm magic `0x8F53E4F2`. Cameras
    /// reject any other value. [H]
    InitCommandRequest {
        /// Fujifilm protocol magic: must be `0x8F53E4F2` for Fujifilm cameras.
        version: u32,
        /// 128-bit installation-unique GUID. Generate once per app install.
        guid: [u8; 16],
        /// Device (app) name. Encoded as 54-byte null-padded UTF-16LE (section 4.2).
        device_name: String,
    },

    /// `PTPIP_INIT_COMMAND_ACK` (0x0002) — 68 bytes total (section 4.3).
    InitCommandAck {
        /// Camera's 128-bit GUID.
        guid: [u8; 16],
        /// Camera model name. Encoded as 44-byte null-padded UTF-16LE (section 4.3).
        camera_name: String,
    },

    /// `PTPIP_INIT_EVENT_REQ` (0x0003) — 12 bytes (section 4.4).
    InitEventRequest {
        /// Connection number; always `1` in practice. [H]
        connection_number: u32,
    },

    /// `PTPIP_INIT_EVENT_ACK` (0x0004) — 8 bytes (header only; no payload fields).
    InitEventAck,

    /// `PTPIP_INIT_FAIL` (0x0005).
    ///
    /// The standard does not define a fixed per-field table for this type.
    /// We carry the raw trailing bytes (after the 8-byte header) so the packet
    /// round-trips correctly. The failure reason code, if present, is the first
    /// 4 bytes interpreted as a u32 LE.
    InitFail {
        /// Raw payload bytes following the 8-byte header.
        raw_payload: Vec<u8>,
    },

    // ── Operations ─────────────────────────────────────────────────────────
    /// `PTPIP_COMMAND_REQUEST` (0x0006) — section 4.5.
    ///
    /// Total length = 18 + (4 × params.len()) bytes.
    OperationRequest {
        /// Data-phase flag: `1` = no data, `2` = initiator sends data.
        data_phase: u32,
        /// PTP operation opcode (e.g. `0x1002` = OpenSession).
        opcode: u16,
        /// Transaction ID. Pre-incremented counter; `0` reserved for OpenSession only.
        transaction_id: u32,
        /// Up to 5 operation parameters, each a u32.
        params: Vec<u32>,
    },

    /// `PTPIP_COMMAND_RESPONSE` (0x0007) — section 4.6.
    ///
    /// Total length = 14 + (4 × result_params.len()) bytes.
    OperationResponse {
        /// PTP response code (e.g. `0x2001` = OK).
        response_code: u16,
        /// Echoes the transaction ID from the matching request.
        transaction_id: u32,
        /// Optional result parameters. Count = (length − 14) / 4.
        result_params: Vec<u32>,
    },

    /// `PTPIP_EVENT` (0x0008).
    ///
    /// Note: Event code is 0x0008, NOT 0x000C (common off-by-one error).
    ///
    /// Fields follow the same layout pattern as OperationResponse.
    Event {
        /// PTP or Fujifilm event code.
        event_code: u16,
        /// Transaction ID this event relates to.
        transaction_id: u32,
        /// Optional event parameters.
        params: Vec<u32>,
    },

    // ── Data transfer ──────────────────────────────────────────────────────
    /// `PTPIP_DATA_PACKET_START` (0x0009) — 20 bytes fixed (section 4.7).
    StartData {
        /// Transaction ID matching the operation request.
        transaction_id: u32,
        /// Total data length in bytes that will follow across all data packets.
        total_data_length: u64,
    },

    /// `PTPIP_DATA_PACKET` (0x000A) — intermediate data chunk.
    ///
    /// The standard does not specify a separate field table; the payload is
    /// the raw data following transaction_id (bytes 8..12 of the packet body).
    DataPacket {
        /// Transaction ID.
        transaction_id: u32,
        /// Chunk payload.
        payload: Vec<u8>,
    },

    /// `PTPIP_CANCEL_TRANSACTION` (0x000B).
    ///
    /// No per-field table in the doc. We carry the raw payload bytes so the
    /// packet round-trips. The transaction ID, if present, is the first 4 bytes.
    CancelTransaction {
        /// Raw payload bytes following the 8-byte header.
        raw_payload: Vec<u8>,
    },

    /// `PTPIP_DATA_PACKET_END` (0x000C) — section 4.8.
    EndData {
        /// Transaction ID.
        transaction_id: u32,
        /// Final data chunk payload (starts at byte offset 12 from packet start).
        payload: Vec<u8>,
    },

    // ── Keep-alive ─────────────────────────────────────────────────────────
    /// `PTPIP_PING` (0x000D) — 8 bytes (header only).
    ///
    /// No per-field table in the doc; raw payload carried for round-tripping.
    Ping {
        /// Raw payload bytes following the 8-byte header (normally empty).
        raw_payload: Vec<u8>,
    },

    /// `PTPIP_PONG` (0x000E) — 8 bytes (header only).
    ///
    /// Response to a Ping. Raw payload carried for round-tripping.
    Pong {
        /// Raw payload bytes following the 8-byte header (normally empty).
        raw_payload: Vec<u8>,
    },
}

// ── Init Command Ack camera_name field ───────────────────────────────────────

/// Byte width of the camera-name field in Init Command Ack (section 4.3).
const CAMERA_NAME_FIELD_BYTES: usize = 44;

/// Decode the 44-byte null-padded UTF-16LE camera name from the Ack packet.
fn decode_camera_name(buf: &[u8], offset: usize) -> Result<String, PtpCodecError> {
    let end = offset
        .checked_add(CAMERA_NAME_FIELD_BYTES)
        .ok_or(PtpCodecError::LengthOverflow)?;
    if end > buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }
    let field = &buf[offset..end];
    let pair_count = CAMERA_NAME_FIELD_BYTES / 2;
    let mut units = Vec::with_capacity(pair_count);
    for i in 0..pair_count {
        let lo = field[i * 2];
        let hi = field[i * 2 + 1];
        units.push(u16::from_le_bytes([lo, hi]));
    }
    let without_null: Vec<u16> = units.into_iter().take_while(|&c| c != 0u16).collect();
    String::from_utf16(&without_null).map_err(|_| PtpCodecError::InvalidUtf16)
}

/// Encode the camera name into exactly 44 bytes of null-padded UTF-16LE.
fn encode_camera_name(s: &str) -> [u8; CAMERA_NAME_FIELD_BYTES] {
    let mut out = [0u8; CAMERA_NAME_FIELD_BYTES];
    // 21 visible chars × 2 bytes = 42 bytes; last 2 bytes remain null (null terminator).
    let units: Vec<u16> = s.encode_utf16().take(21).collect();
    for (i, unit) in units.iter().enumerate() {
        let le = unit.to_le_bytes();
        out[i * 2] = le[0];
        out[i * 2 + 1] = le[1];
    }
    out
}

// ── Safe slice helpers ────────────────────────────────────────────────────────

/// Reads a `u16` little-endian from `buf[offset..]`.
///
/// # Errors
/// Returns [`PtpCodecError::PacketTooShort`] if there are fewer than 2 bytes available.
fn read_u16_le(buf: &[u8], offset: usize) -> Result<u16, PtpCodecError> {
    let end = offset.checked_add(2).ok_or(PtpCodecError::LengthOverflow)?;
    if end > buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }
    Ok(u16::from_le_bytes([buf[offset], buf[offset + 1]]))
}

/// Reads a `u32` little-endian from `buf[offset..]`.
///
/// # Errors
/// Returns [`PtpCodecError::PacketTooShort`] if there are fewer than 4 bytes available.
fn read_u32_le(buf: &[u8], offset: usize) -> Result<u32, PtpCodecError> {
    let end = offset.checked_add(4).ok_or(PtpCodecError::LengthOverflow)?;
    if end > buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }
    Ok(u32::from_le_bytes([
        buf[offset],
        buf[offset + 1],
        buf[offset + 2],
        buf[offset + 3],
    ]))
}

/// Reads a `u64` little-endian from `buf[offset..]`.
///
/// # Errors
/// Returns [`PtpCodecError::PacketTooShort`] if there are fewer than 8 bytes available.
fn read_u64_le(buf: &[u8], offset: usize) -> Result<u64, PtpCodecError> {
    let end = offset.checked_add(8).ok_or(PtpCodecError::LengthOverflow)?;
    if end > buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }
    Ok(u64::from_le_bytes([
        buf[offset],
        buf[offset + 1],
        buf[offset + 2],
        buf[offset + 3],
        buf[offset + 4],
        buf[offset + 5],
        buf[offset + 6],
        buf[offset + 7],
    ]))
}

/// Reads a 16-byte GUID from `buf[offset..]`.
fn read_guid(buf: &[u8], offset: usize) -> Result<[u8; 16], PtpCodecError> {
    let end = offset
        .checked_add(16)
        .ok_or(PtpCodecError::LengthOverflow)?;
    if end > buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }
    let mut guid = [0u8; 16];
    guid.copy_from_slice(&buf[offset..end]);
    Ok(guid)
}

/// Reads `n` u32 LE values from `buf[offset..]`.
fn read_u32_array(buf: &[u8], offset: usize, n: usize) -> Result<Vec<u32>, PtpCodecError> {
    let byte_count = n.checked_mul(4).ok_or(PtpCodecError::LengthOverflow)?;
    let end = offset
        .checked_add(byte_count)
        .ok_or(PtpCodecError::LengthOverflow)?;
    if end > buf.len() {
        return Err(PtpCodecError::PacketTooShort {
            expected: end,
            got: buf.len(),
        });
    }
    let mut out = Vec::with_capacity(n);
    for i in 0..n {
        out.push(read_u32_le(buf, offset + i * 4)?);
    }
    Ok(out)
}

// ── Decode ────────────────────────────────────────────────────────────────────

/// Decodes a complete PTP-IP packet from `buf`.
///
/// The buffer must contain exactly one packet starting at index 0. `buf` may
/// be longer than the declared `length` field; extra trailing bytes are ignored.
///
/// # Validation
/// - Returns [`PtpCodecError::PacketTooShort`] when `buf.len() < 8` or when
///   the declared `length` field is less than 8 (header-only minimum).
/// - Returns [`PtpCodecError::PacketTooShort`] when the buffer does not hold
///   `length` bytes.
/// - Returns [`PtpCodecError::UnknownPacketType`] for unrecognised type codes.
/// - Never panics on any input (empty, truncated, oversized-length, unknown-type).
pub fn decode_packet(buf: &[u8]) -> Result<PtpIpPacket, PtpCodecError> {
    // Step 1: decode common header (needs 8 bytes).
    let hdr = PtpIpHeader::decode(buf)?;

    // Step 2: validate declared length.
    if hdr.length < HEADER_SIZE as u32 {
        return Err(PtpCodecError::PacketTooShort {
            expected: HEADER_SIZE,
            got: hdr.length as usize,
        });
    }
    let pkt_len = hdr.length as usize;
    if buf.len() < pkt_len {
        return Err(PtpCodecError::PacketTooShort {
            expected: pkt_len,
            got: buf.len(),
        });
    }

    // Work on the slice declared by the length field.
    let pkt = &buf[..pkt_len];

    // Step 3: look up the packet type.
    let ptype = PtpIpPacketType::from_code(hdr.packet_type)
        .ok_or(PtpCodecError::UnknownPacketType(hdr.packet_type))?;

    // Step 4: decode per-type fields. All offsets are from the start of `pkt`.
    match ptype {
        // ── 4.2 Init Command Request — 82 bytes ────────────────────────────
        PtpIpPacketType::InitCommandRequest => {
            // Minimum: 8 (header) + 4 (version) + 16 (guid) + 54 (device_name) = 82
            let min = 8 + 4 + 16 + DEVICE_NAME_FIELD_BYTES;
            if pkt.len() < min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: min,
                    got: pkt.len(),
                });
            }
            let version = read_u32_le(pkt, 8)?;
            let guid = read_guid(pkt, 12)?;
            let device_name = decode_device_name(pkt, 28)?;
            Ok(PtpIpPacket::InitCommandRequest {
                version,
                guid,
                device_name,
            })
        }

        // ── 4.3 Init Command Ack — 68 bytes ───────────────────────────────
        PtpIpPacketType::InitCommandAck => {
            // Minimum: 8 (header) + 16 (guid) + 44 (camera_name) = 68
            let min = 8 + 16 + CAMERA_NAME_FIELD_BYTES;
            if pkt.len() < min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: min,
                    got: pkt.len(),
                });
            }
            let guid = read_guid(pkt, 8)?;
            let camera_name = decode_camera_name(pkt, 24)?;
            Ok(PtpIpPacket::InitCommandAck { guid, camera_name })
        }

        // ── 4.4 Init Event Request — 12 bytes ─────────────────────────────
        PtpIpPacketType::InitEventRequest => {
            let min = 8 + 4;
            if pkt.len() < min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: min,
                    got: pkt.len(),
                });
            }
            let connection_number = read_u32_le(pkt, 8)?;
            Ok(PtpIpPacket::InitEventRequest { connection_number })
        }

        // ── Init Event Ack — 8 bytes (header only) ────────────────────────
        PtpIpPacketType::InitEventAck => Ok(PtpIpPacket::InitEventAck),

        // ── Init Fail — header + raw payload ──────────────────────────────
        // No per-field table in the doc; carry raw trailing bytes for round-tripping.
        PtpIpPacketType::InitFail => {
            let raw_payload = pkt[HEADER_SIZE..].to_vec();
            Ok(PtpIpPacket::InitFail { raw_payload })
        }

        // ── 4.5 Operation Request ──────────────────────────────────────────
        PtpIpPacketType::OperationRequest => {
            // Fixed portion: 8 (header) + 4 (data_phase) + 2 (opcode) + 4 (txid) = 18
            let fixed_min = 18;
            if pkt.len() < fixed_min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: fixed_min,
                    got: pkt.len(),
                });
            }
            let data_phase = read_u32_le(pkt, 8)?;
            let opcode = read_u16_le(pkt, 12)?;
            let transaction_id = read_u32_le(pkt, 14)?;
            // Remaining bytes are params: each 4 bytes, up to 5 params.
            let remaining = pkt.len() - fixed_min;
            let param_count = (remaining / 4).min(5);
            let params = read_u32_array(pkt, fixed_min, param_count)?;
            Ok(PtpIpPacket::OperationRequest {
                data_phase,
                opcode,
                transaction_id,
                params,
            })
        }

        // ── 4.6 Command Response ───────────────────────────────────────────
        PtpIpPacketType::OperationResponse => {
            // Fixed: 8 (header) + 2 (response_code) + 4 (txid) = 14
            let fixed_min = 14;
            if pkt.len() < fixed_min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: fixed_min,
                    got: pkt.len(),
                });
            }
            let response_code = read_u16_le(pkt, 8)?;
            let transaction_id = read_u32_le(pkt, 10)?;
            let remaining = pkt.len() - fixed_min;
            // PTP specifies a maximum of 5 response parameters (section 4.6).
            let param_count = (remaining / 4).min(5);
            let result_params = read_u32_array(pkt, fixed_min, param_count)?;
            Ok(PtpIpPacket::OperationResponse {
                response_code,
                transaction_id,
                result_params,
            })
        }

        // ── Event (0x0008) ─────────────────────────────────────────────────
        PtpIpPacketType::Event => {
            // Same layout as OperationResponse: 8 + 2 + 4 = 14 fixed bytes.
            let fixed_min = 14;
            if pkt.len() < fixed_min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: fixed_min,
                    got: pkt.len(),
                });
            }
            let event_code = read_u16_le(pkt, 8)?;
            let transaction_id = read_u32_le(pkt, 10)?;
            let remaining = pkt.len() - fixed_min;
            // PTP specifies a maximum of 5 event parameters (mirrors OperationRequest cap).
            let param_count = (remaining / 4).min(5);
            let params = read_u32_array(pkt, fixed_min, param_count)?;
            Ok(PtpIpPacket::Event {
                event_code,
                transaction_id,
                params,
            })
        }

        // ── 4.7 Data Start — 20 bytes ─────────────────────────────────────
        PtpIpPacketType::DataPacketStart => {
            // 8 (header) + 4 (txid) + 8 (total_data_length) = 20
            let min = 20;
            if pkt.len() < min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: min,
                    got: pkt.len(),
                });
            }
            let transaction_id = read_u32_le(pkt, 8)?;
            let total_data_length = read_u64_le(pkt, 12)?;
            Ok(PtpIpPacket::StartData {
                transaction_id,
                total_data_length,
            })
        }

        // ── Data Packet (0x000A) — intermediate chunk ─────────────────────
        PtpIpPacketType::DataPacket => {
            // 8 (header) + 4 (txid) = 12 minimum
            let min = 12;
            if pkt.len() < min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: min,
                    got: pkt.len(),
                });
            }
            let transaction_id = read_u32_le(pkt, 8)?;
            let payload = pkt[12..].to_vec();
            Ok(PtpIpPacket::DataPacket {
                transaction_id,
                payload,
            })
        }

        // ── Cancel Transaction (0x000B) — raw payload ─────────────────────
        // No per-field table in the doc; carry raw trailing bytes.
        PtpIpPacketType::CancelTransaction => {
            let raw_payload = pkt[HEADER_SIZE..].to_vec();
            Ok(PtpIpPacket::CancelTransaction { raw_payload })
        }

        // ── 4.8 Data End (0x000C) ─────────────────────────────────────────
        PtpIpPacketType::DataPacketEnd => {
            // 8 (header) + 4 (txid) = 12 minimum; payload starts at offset 12.
            let min = 12;
            if pkt.len() < min {
                return Err(PtpCodecError::PacketTooShort {
                    expected: min,
                    got: pkt.len(),
                });
            }
            let transaction_id = read_u32_le(pkt, 8)?;
            let payload = pkt[12..].to_vec();
            Ok(PtpIpPacket::EndData {
                transaction_id,
                payload,
            })
        }

        // ── Ping (0x000D) — header + optional payload ─────────────────────
        // No per-field table in the doc.
        PtpIpPacketType::Ping => {
            let raw_payload = pkt[HEADER_SIZE..].to_vec();
            Ok(PtpIpPacket::Ping { raw_payload })
        }

        // ── Pong (0x000E) — header + optional payload ─────────────────────
        // No per-field table in the doc.
        PtpIpPacketType::Pong => {
            let raw_payload = pkt[HEADER_SIZE..].to_vec();
            Ok(PtpIpPacket::Pong { raw_payload })
        }
    }
}

// ── Encode ────────────────────────────────────────────────────────────────────

/// Encodes a [`PtpIpPacket`] into a `Vec<u8>`.
///
/// The returned buffer contains a complete, self-describing packet with the
/// `length` field set to the actual encoded size.
pub fn encode_packet(packet: &PtpIpPacket) -> Vec<u8> {
    match packet {
        // ── Init Command Request — 82 bytes ────────────────────────────────
        PtpIpPacket::InitCommandRequest {
            version,
            guid,
            device_name,
        } => {
            // Total: 8 (header) + 4 (version) + 16 (guid) + 54 (device_name) = 82
            let total: u32 = 82;
            let mut out = Vec::with_capacity(total as usize);
            encode_header_into(&mut out, PtpIpPacketType::InitCommandRequest, total);
            out.extend_from_slice(&version.to_le_bytes());
            out.extend_from_slice(guid);
            out.extend_from_slice(&encode_device_name(device_name));
            out
        }

        // ── Init Command Ack — 68 bytes ────────────────────────────────────
        PtpIpPacket::InitCommandAck { guid, camera_name } => {
            // Total: 8 + 16 + 44 = 68
            let total: u32 = 68;
            let mut out = Vec::with_capacity(total as usize);
            encode_header_into(&mut out, PtpIpPacketType::InitCommandAck, total);
            out.extend_from_slice(guid);
            out.extend_from_slice(&encode_camera_name(camera_name));
            out
        }

        // ── Init Event Request — 12 bytes ──────────────────────────────────
        PtpIpPacket::InitEventRequest { connection_number } => {
            let total: u32 = 12;
            let mut out = Vec::with_capacity(total as usize);
            encode_header_into(&mut out, PtpIpPacketType::InitEventRequest, total);
            out.extend_from_slice(&connection_number.to_le_bytes());
            out
        }

        // ── Init Event Ack — 8 bytes ───────────────────────────────────────
        PtpIpPacket::InitEventAck => {
            let mut out = Vec::with_capacity(8);
            encode_header_into(&mut out, PtpIpPacketType::InitEventAck, 8);
            out
        }

        // ── Init Fail — header + raw payload ──────────────────────────────
        PtpIpPacket::InitFail { raw_payload } => {
            let byte_len = HEADER_SIZE + raw_payload.len();
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "InitFail encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::InitFail, total);
            out.extend_from_slice(raw_payload);
            out
        }

        // ── Operation Request ──────────────────────────────────────────────
        PtpIpPacket::OperationRequest {
            data_phase,
            opcode,
            transaction_id,
            params,
        } => {
            // 8 + 4 + 2 + 4 + 4*n; PTP caps at 5 parameters.
            let param_count = params.len().min(5);
            let byte_len = 18 + param_count * 4;
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "OperationRequest encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::OperationRequest, total);
            out.extend_from_slice(&data_phase.to_le_bytes());
            out.extend_from_slice(&opcode.to_le_bytes());
            out.extend_from_slice(&transaction_id.to_le_bytes());
            for p in params.iter().take(5) {
                out.extend_from_slice(&p.to_le_bytes());
            }
            out
        }

        // ── Operation Response ─────────────────────────────────────────────
        PtpIpPacket::OperationResponse {
            response_code,
            transaction_id,
            result_params,
        } => {
            // 8 + 2 + 4 + 4*n = 14 + 4*n; PTP caps at 5 result parameters.
            let param_count = result_params.len().min(5);
            let byte_len = 14 + param_count * 4;
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "OperationResponse encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::OperationResponse, total);
            out.extend_from_slice(&response_code.to_le_bytes());
            out.extend_from_slice(&transaction_id.to_le_bytes());
            for p in result_params.iter().take(5) {
                out.extend_from_slice(&p.to_le_bytes());
            }
            out
        }

        // ── Event ──────────────────────────────────────────────────────────
        PtpIpPacket::Event {
            event_code,
            transaction_id,
            params,
        } => {
            // PTP caps event parameters at 5 (mirrors OperationRequest/OperationResponse).
            let param_count = params.len().min(5);
            let byte_len = 14 + param_count * 4;
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "Event encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::Event, total);
            out.extend_from_slice(&event_code.to_le_bytes());
            out.extend_from_slice(&transaction_id.to_le_bytes());
            for p in params.iter().take(5) {
                out.extend_from_slice(&p.to_le_bytes());
            }
            out
        }

        // ── Start Data — 20 bytes ──────────────────────────────────────────
        PtpIpPacket::StartData {
            transaction_id,
            total_data_length,
        } => {
            let total: u32 = 20;
            let mut out = Vec::with_capacity(total as usize);
            encode_header_into(&mut out, PtpIpPacketType::DataPacketStart, total);
            out.extend_from_slice(&transaction_id.to_le_bytes());
            out.extend_from_slice(&total_data_length.to_le_bytes());
            out
        }

        // ── Data Packet ────────────────────────────────────────────────────
        PtpIpPacket::DataPacket {
            transaction_id,
            payload,
        } => {
            let byte_len = 12 + payload.len();
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "DataPacket encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::DataPacket, total);
            out.extend_from_slice(&transaction_id.to_le_bytes());
            out.extend_from_slice(payload);
            out
        }

        // ── Cancel Transaction ─────────────────────────────────────────────
        PtpIpPacket::CancelTransaction { raw_payload } => {
            let byte_len = HEADER_SIZE + raw_payload.len();
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "CancelTransaction encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::CancelTransaction, total);
            out.extend_from_slice(raw_payload);
            out
        }

        // ── End Data ───────────────────────────────────────────────────────
        PtpIpPacket::EndData {
            transaction_id,
            payload,
        } => {
            let byte_len = 12 + payload.len();
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "EndData encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::DataPacketEnd, total);
            out.extend_from_slice(&transaction_id.to_le_bytes());
            out.extend_from_slice(payload);
            out
        }

        // ── Ping ───────────────────────────────────────────────────────────
        PtpIpPacket::Ping { raw_payload } => {
            let byte_len = HEADER_SIZE + raw_payload.len();
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "Ping encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::Ping, total);
            out.extend_from_slice(raw_payload);
            out
        }

        // ── Pong ───────────────────────────────────────────────────────────
        PtpIpPacket::Pong { raw_payload } => {
            let byte_len = HEADER_SIZE + raw_payload.len();
            debug_assert!(
                byte_len <= u32::MAX as usize,
                "Pong encode: packet length {byte_len} overflows u32"
            );
            let total = u32::try_from(byte_len).unwrap_or(u32::MAX);
            let mut out = Vec::with_capacity(byte_len);
            encode_header_into(&mut out, PtpIpPacketType::Pong, total);
            out.extend_from_slice(raw_payload);
            out
        }
    }
}

// ── Internal encode helpers ───────────────────────────────────────────────────

fn encode_header_into(out: &mut Vec<u8>, ptype: PtpIpPacketType, total_length: u32) {
    let hdr = PtpIpHeader::new(ptype, total_length);
    hdr.encode_into(out);
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── PtpIpPacketType ────────────────────────────────────────────────────

    #[test]
    fn packet_type_from_code_known() {
        assert_eq!(
            PtpIpPacketType::from_code(0x0001),
            Some(PtpIpPacketType::InitCommandRequest)
        );
        assert_eq!(
            PtpIpPacketType::from_code(0x0008),
            Some(PtpIpPacketType::Event)
        );
        assert_eq!(
            PtpIpPacketType::from_code(0x000C),
            Some(PtpIpPacketType::DataPacketEnd)
        );
        assert_eq!(
            PtpIpPacketType::from_code(0x000E),
            Some(PtpIpPacketType::Pong)
        );
    }

    #[test]
    fn packet_type_from_code_unknown() {
        assert_eq!(PtpIpPacketType::from_code(0x0000), None);
        assert_eq!(PtpIpPacketType::from_code(0x000F), None);
        assert_eq!(PtpIpPacketType::from_code(0xFFFF), None);
    }

    #[test]
    fn packet_type_to_code_roundtrip() {
        for code in 0x0001u32..=0x000E {
            if let Some(ptype) = PtpIpPacketType::from_code(code) {
                assert_eq!(ptype.to_code(), code);
            }
        }
    }

    // ── decode_packet error cases ──────────────────────────────────────────

    #[test]
    fn decode_empty_buffer_returns_too_short() {
        let result = decode_packet(&[]);
        assert!(matches!(
            result,
            Err(PtpCodecError::PacketTooShort {
                expected: 8,
                got: 0
            })
        ));
    }

    #[test]
    fn decode_7_bytes_returns_too_short() {
        let result = decode_packet(&[0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00]);
        assert!(matches!(
            result,
            Err(PtpCodecError::PacketTooShort { expected: 8, .. })
        ));
    }

    #[test]
    fn decode_unknown_type_returns_error() {
        // valid-length header but type = 0xDEAD
        let buf = [
            0x08, 0x00, 0x00, 0x00, // length = 8
            0xAD, 0xDE, 0x00, 0x00, // type = 0xDEAD
        ];
        assert!(matches!(
            decode_packet(&buf),
            Err(PtpCodecError::UnknownPacketType(0xDEAD))
        ));
    }

    #[test]
    fn decode_length_less_than_8_returns_error() {
        // Declared length = 4 (less than HEADER_SIZE)
        let buf = [
            0x04, 0x00, 0x00, 0x00, // length = 4
            0x04, 0x00, 0x00, 0x00, // type = InitEventAck
        ];
        assert!(matches!(
            decode_packet(&buf),
            Err(PtpCodecError::PacketTooShort { .. })
        ));
    }

    #[test]
    fn decode_buffer_shorter_than_declared_length_returns_error() {
        // Declares 12 bytes but we only provide 8 (missing last 4 bytes).
        let buf = [
            0x0C, 0x00, 0x00, 0x00, // length = 12
            0x03, 0x00, 0x00, 0x00, // type = InitEventRequest
        ];
        assert!(matches!(
            decode_packet(&buf),
            Err(PtpCodecError::PacketTooShort {
                expected: 12,
                got: 8
            })
        ));
    }

    // ── InitEventAck ──────────────────────────────────────────────────────

    #[test]
    fn init_event_ack_roundtrip() {
        let pkt = PtpIpPacket::InitEventAck;
        let encoded = encode_packet(&pkt);
        assert_eq!(encoded.len(), 8);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    // ── InitEventRequest ──────────────────────────────────────────────────

    #[test]
    fn init_event_request_roundtrip() {
        let pkt = PtpIpPacket::InitEventRequest {
            connection_number: 1,
        };
        let encoded = encode_packet(&pkt);
        assert_eq!(encoded.len(), 12);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    // ── InitCommandRequest ────────────────────────────────────────────────

    #[test]
    fn init_command_request_roundtrip() {
        // Fujifilm magic version: 0x8F53E4F2
        let pkt = PtpIpPacket::InitCommandRequest {
            version: 0x8F53E4F2,
            guid: [
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E,
                0x0F, 0x10,
            ],
            device_name: "Frameport".to_owned(),
        };
        let encoded = encode_packet(&pkt);
        assert_eq!(encoded.len(), 82);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    #[test]
    fn init_command_request_version_field_is_at_offset_8() {
        let pkt = PtpIpPacket::InitCommandRequest {
            version: 0x8F53E4F2,
            guid: [0u8; 16],
            device_name: "Test".to_owned(),
        };
        let encoded = encode_packet(&pkt);
        // Bytes 8..12 = version in LE
        let version = u32::from_le_bytes([encoded[8], encoded[9], encoded[10], encoded[11]]);
        assert_eq!(version, 0x8F53E4F2);
    }

    // ── InitCommandAck ────────────────────────────────────────────────────

    #[test]
    fn init_command_ack_roundtrip() {
        let pkt = PtpIpPacket::InitCommandAck {
            guid: [
                0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88, 0x99,
            ],
            camera_name: "X-T5".to_owned(),
        };
        let encoded = encode_packet(&pkt);
        assert_eq!(encoded.len(), 68);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    // ── OperationRequest ──────────────────────────────────────────────────

    #[test]
    fn operation_request_no_params_roundtrip() {
        let pkt = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: 0x1002, // OpenSession
            transaction_id: 0,
            params: vec![1],
        };
        let encoded = encode_packet(&pkt);
        // 18 + 4 = 22 bytes
        assert_eq!(encoded.len(), 22);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    #[test]
    fn operation_request_five_params_roundtrip() {
        let pkt = PtpIpPacket::OperationRequest {
            data_phase: 1,
            opcode: 0x1007,
            transaction_id: 5,
            params: vec![0xFFFFFFFF, 0x0000, 0x00000000, 0xAAAAAAAA, 0x12345678],
        };
        let encoded = encode_packet(&pkt);
        assert_eq!(encoded.len(), 18 + 5 * 4);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    // ── OperationResponse ─────────────────────────────────────────────────

    #[test]
    fn operation_response_ok_roundtrip() {
        let pkt = PtpIpPacket::OperationResponse {
            response_code: 0x2001, // OK
            transaction_id: 1,
            result_params: vec![],
        };
        let encoded = encode_packet(&pkt);
        assert_eq!(encoded.len(), 14);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    #[test]
    fn operation_response_six_params_capped_to_five_on_encode_and_decode() {
        // Supply 6 params — encode must silently cap to 5 (PTP wire limit).
        let pkt = PtpIpPacket::OperationResponse {
            response_code: 0x2001,
            transaction_id: 42,
            result_params: vec![1, 2, 3, 4, 5, 6],
        };
        let encoded = encode_packet(&pkt);
        // Wire size: 14 fixed + 5 * 4 = 34 bytes (not 38).
        assert_eq!(encoded.len(), 34, "encode must cap at 5 params");
        let decoded = decode_packet(&encoded).unwrap();
        match decoded {
            PtpIpPacket::OperationResponse { result_params, .. } => {
                assert_eq!(result_params.len(), 5, "decode must read at most 5 params");
                assert_eq!(result_params, vec![1, 2, 3, 4, 5]);
            }
            other => panic!("unexpected variant: {other:?}"),
        }
    }

    // ── Event ──────────────────────────────────────────────────────────────

    #[test]
    fn event_packet_type_code_is_0x0008() {
        let pkt = PtpIpPacket::Event {
            event_code: 0x4006,
            transaction_id: 3,
            params: vec![],
        };
        let encoded = encode_packet(&pkt);
        // Bytes 4..8 = packet type in LE; should be 0x0008
        let type_code = u32::from_le_bytes([encoded[4], encoded[5], encoded[6], encoded[7]]);
        assert_eq!(type_code, 0x0008);
    }

    #[test]
    fn event_packet_roundtrip() {
        let pkt = PtpIpPacket::Event {
            event_code: 0x4002,
            transaction_id: 7,
            params: vec![0xABCDEF01],
        };
        let encoded = encode_packet(&pkt);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    #[test]
    fn event_six_params_capped_to_five_on_encode_and_decode() {
        // Supply 6 params — encode must silently cap to 5 (PTP wire limit).
        let pkt = PtpIpPacket::Event {
            event_code: 0x4003,
            transaction_id: 10,
            params: vec![10, 20, 30, 40, 50, 60],
        };
        let encoded = encode_packet(&pkt);
        // Wire size: 14 fixed + 5 * 4 = 34 bytes (not 38).
        assert_eq!(encoded.len(), 34, "encode must cap at 5 params");
        let decoded = decode_packet(&encoded).unwrap();
        match decoded {
            PtpIpPacket::Event { params, .. } => {
                assert_eq!(params.len(), 5, "decode must read at most 5 params");
                assert_eq!(params, vec![10, 20, 30, 40, 50]);
            }
            other => panic!("unexpected variant: {other:?}"),
        }
    }

    // ── StartData / EndData ────────────────────────────────────────────────

    #[test]
    fn start_data_roundtrip() {
        let pkt = PtpIpPacket::StartData {
            transaction_id: 42,
            total_data_length: 0x0001_0000_0000,
        };
        let encoded = encode_packet(&pkt);
        assert_eq!(encoded.len(), 20);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    #[test]
    fn end_data_roundtrip() {
        let payload = vec![0xDE, 0xAD, 0xBE, 0xEF];
        let pkt = PtpIpPacket::EndData {
            transaction_id: 42,
            payload: payload.clone(),
        };
        let encoded = encode_packet(&pkt);
        // 8 (header) + 4 (txid) + 4 (payload) = 16
        assert_eq!(encoded.len(), 16);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    // ── DataPacket ────────────────────────────────────────────────────────

    #[test]
    fn data_packet_roundtrip() {
        let payload = vec![0x01u8; 1024];
        let pkt = PtpIpPacket::DataPacket {
            transaction_id: 10,
            payload: payload.clone(),
        };
        let encoded = encode_packet(&pkt);
        assert_eq!(encoded.len(), 12 + 1024);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }

    // ── Ping / Pong ───────────────────────────────────────────────────────

    #[test]
    fn ping_pong_roundtrip() {
        for pkt in [
            PtpIpPacket::Ping {
                raw_payload: vec![],
            },
            PtpIpPacket::Pong {
                raw_payload: vec![],
            },
        ] {
            let encoded = encode_packet(&pkt);
            assert_eq!(encoded.len(), 8);
            let decoded = decode_packet(&encoded).unwrap();
            assert_eq!(decoded, pkt);
        }
    }

    // ── CancelTransaction ─────────────────────────────────────────────────

    #[test]
    fn cancel_transaction_roundtrip() {
        let pkt = PtpIpPacket::CancelTransaction {
            raw_payload: vec![0x01, 0x00, 0x00, 0x00],
        };
        let encoded = encode_packet(&pkt);
        let decoded = decode_packet(&encoded).unwrap();
        assert_eq!(decoded, pkt);
    }
}
