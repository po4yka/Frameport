# PTP-IP Golden Fixtures

These files contain hand-authored minimal valid PTP-IP wire encodings.
Every byte is derived from clean-room protocol constants in `docs/reference/ptp-ptpip.md`
and `docs/reference/master-constants.md`. No bytes were copied from a real camera capture
or any reference-project binary.

The hex text format is: whitespace-separated two-character hex bytes, line-wrapped for
readability. The `golden_roundtrip` integration test parses these files, decodes them
with `decode_packet`, re-encodes with `encode_packet`, and asserts that the resulting
bytes are identical to the fixture bytes.

---

## init_command_req.hex — 82 bytes, type 0x0001

Source: ptp-ptpip.md §4.2 "Init Command Request — 82 bytes total".

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `52 00 00 00` | `length` = 82 | §4.2: "Total size is 82 bytes (0x52)" |
| 4–7 | u32 | `01 00 00 00` | `packet_type` = 0x0001 | §4.1 type table: PTPIP_INIT_COMMAND_REQ |
| 8–11 | u32 | `F2 E4 53 8F` | `version` = 0x8F53E4F2 | §4.2: "Fujifilm proprietary; 0x8F53E4F2" [H] |
| 12–27 | 16 B | `01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10` | `guid` | §4.2: "Installation-unique; generate per app install" — invented placeholder |
| 28–81 | 54 B | see below | `device_name` | §4.2: "UTF-16LE, max 26 visible chars, null-padded to 54 bytes" |

Device name "Frameport" in UTF-16LE (9 chars × 2 bytes = 18 bytes), then 36 zero bytes:
`46 00 72 00 61 00 6D 00 65 00 70 00 6F 00 72 00 74 00` followed by 36 `00` bytes.

---

## init_command_ack.hex — 68 bytes, type 0x0002

Source: ptp-ptpip.md §4.3 "Init Command Ack — 68 bytes total".

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `44 00 00 00` | `length` = 68 | §4.3: "Total size 68 bytes (0x44)" |
| 4–7 | u32 | `02 00 00 00` | `packet_type` = 0x0002 | §4.1: PTPIP_INIT_COMMAND_ACK |
| 8–23 | 16 B | `A1 B2 C3 D4 E5 F6 07 18 29 3A 4B 5C 6D 7E 8F 90` | camera GUID | §4.3: "Camera's GUID" — invented placeholder |
| 24–67 | 44 B | see below | `camera_name` | §4.3: "UTF-16LE camera name" |

Camera name "X-T5" in UTF-16LE (4 chars × 2 bytes = 8 bytes), then 36 zero bytes:
`58 00 2D 00 54 00 35 00` followed by 36 `00` bytes.

---

## init_event_req.hex — 12 bytes, type 0x0003

Source: ptp-ptpip.md §4.4 "Init Event Request — 12 bytes".

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `0C 00 00 00` | `length` = 12 | §4.4: "0x0000000C (12)" |
| 4–7 | u32 | `03 00 00 00` | `packet_type` = 0x0003 | §4.1: PTPIP_INIT_EVENT_REQ |
| 8–11 | u32 | `01 00 00 00` | `connection_number` = 1 | §4.4: "1" (always 1 in practice) |

---

## init_event_ack.hex — 8 bytes, type 0x0004

Source: ptp-ptpip.md §4.4: "Response is PTPIP_INIT_EVENT_ACK (0x0004), 8 bytes."

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `08 00 00 00` | `length` = 8 | §4.1: minimum valid packet is 8-byte header only |
| 4–7 | u32 | `04 00 00 00` | `packet_type` = 0x0004 | §4.1: PTPIP_INIT_EVENT_ACK |

---

## operation_request.hex — 22 bytes, type 0x0006

Source: ptp-ptpip.md §4.5 "Operation Request". Encodes OpenSession (opcode 0x1002) with
transaction_id=0 and param[0]=1 (SESSION_ID). Per §4.5, OpenSession must use txn_id=0.

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `16 00 00 00` | `length` = 22 | §4.5: "18 + (4 × num_params)" = 18 + 4 |
| 4–7 | u32 | `06 00 00 00` | `packet_type` = 0x0006 | §4.1: PTPIP_COMMAND_REQUEST |
| 8–11 | u32 | `01 00 00 00` | `data_phase` = 1 | §4.5: "1 = no data" |
| 12–13 | u16 | `02 10` | `opcode` = 0x1002 | master-constants.md §2a: OpenSession |
| 14–17 | u32 | `00 00 00 00` | `transaction_id` = 0 | §4.5: "OpenSession must use transaction ID 0" |
| 18–21 | u32 | `01 00 00 00` | `params[0]` = 1 | constants.rs: SESSION_ID = 1 |

---

## operation_response.hex — 14 bytes, type 0x0007

Source: ptp-ptpip.md §4.6 "Command Response". Encodes OK response to transaction 1.

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `0E 00 00 00` | `length` = 14 | §4.6: "14 + (4 × result_params)" = 14 + 0 |
| 4–7 | u32 | `07 00 00 00` | `packet_type` = 0x0007 | §4.1: PTPIP_COMMAND_RESPONSE |
| 8–9 | u16 | `01 20` | `response_code` = 0x2001 | master-constants.md §4b: OK = 0x2001 |
| 10–13 | u32 | `01 00 00 00` | `transaction_id` = 1 | echoes the matching request's txn_id |

---

## event.hex — 14 bytes, type 0x0008

Source: ptp-ptpip.md §4.1: PTPIP_EVENT = 0x0008. Encodes ObjectAdded event (0x4002)
for transaction 1 with no parameters.

IMPORTANT: Event = 0x0008, NOT 0x000C. 0x000C is DataPacketEnd.

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `0E 00 00 00` | `length` = 14 | same layout as OperationResponse (§4.1 field pattern) |
| 4–7 | u32 | `08 00 00 00` | `packet_type` = 0x0008 | §4.1: PTPIP_EVENT |
| 8–9 | u16 | `02 40` | `event_code` = 0x4002 | master-constants.md §4a: ObjectAdded |
| 10–13 | u32 | `01 00 00 00` | `transaction_id` = 1 | transaction this event relates to |

---

## start_data.hex — 20 bytes, type 0x0009

Source: ptp-ptpip.md §4.7 "Data Start — 20 bytes fixed".

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `14 00 00 00` | `length` = 20 | §4.7: "20 bytes fixed" |
| 4–7 | u32 | `09 00 00 00` | `packet_type` = 0x0009 | §4.1: PTPIP_DATA_PACKET_START |
| 8–11 | u32 | `01 00 00 00` | `transaction_id` = 1 | matching operation txn_id |
| 12–19 | u64 | `00 01 00 00 00 00 00 00` | `total_data_length` = 256 | §4.7: total bytes that will follow |

---

## data_packet.hex — 16 bytes, type 0x000A

Source: ptp-ptpip.md §4.1: PTPIP_DATA_PACKET = 0x000A (intermediate chunk).

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `10 00 00 00` | `length` = 16 | 8 (header) + 4 (txid) + 4 (payload) |
| 4–7 | u32 | `0A 00 00 00` | `packet_type` = 0x000A | §4.1: PTPIP_DATA_PACKET |
| 8–11 | u32 | `01 00 00 00` | `transaction_id` = 1 | matching operation txn_id |
| 12–15 | 4 B | `DE AD BE EF` | payload | invented placeholder data chunk |

---

## end_data.hex — 16 bytes, type 0x000C

Source: ptp-ptpip.md §4.8 "Data End (0x000C)". Final data chunk.

IMPORTANT: DataPacketEnd = 0x000C. Event is 0x0008 (a common off-by-one error).

| Byte offset | Width | Value (hex LE) | Field | Provenance |
|---|---|---|---|---|
| 0–3 | u32 | `10 00 00 00` | `length` = 16 | 8 (header) + 4 (txid) + 4 (payload) |
| 4–7 | u32 | `0C 00 00 00` | `packet_type` = 0x000C | §4.1: PTPIP_DATA_PACKET_END |
| 8–11 | u32 | `01 00 00 00` | `transaction_id` = 1 | matching operation txn_id |
| 12–15 | 4 B | `DE AD BE EF` | payload | invented placeholder final data chunk |
