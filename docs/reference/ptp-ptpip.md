# PTP & PTP-IP Core — Wire Format, Handshake, Opcodes, and Session Lifecycle

**Status:** Interoperability reference — clean-room synthesis from open-source RE corpus.
**Frameport targets:** `fuji-ptp` (container codec), `fuji-ptpip` (transport + handshake).
**Confidence legend:** `[H]` = high (2+ independent sources agree on exact value); `[M]` = medium (single source or conflicting minor detail); `[L]` = low (single source, caveat noted).

---

## 1. Transport Topology

Fujifilm cameras over Wi-Fi use **three independent TCP connections** to a fixed camera IP. All three must be bound to the camera's Wi-Fi network object before use (see ADR-0002).

| Channel | Port | Hex | Use | Open timing |
|---|---|---|---|---|
| Command | 55740 | 0xD9BC | Synchronous PTP operations | First, during init handshake |
| Event | 55741 | 0xD9BD | Asynchronous camera events | Lazy — only from `InitiateOpenCapture` paths |
| Live-view | 55742 | 0xD9BE | Through-picture MJPEG stream | Lazy — after remote mode activated |

**[H]** — ports confirmed by fuji-cam-wifi-tool, libfuji, and XApp native analysis. The ISO PTP/IP standard defines port 15740; Fujifilm does **not** use it.

**Camera IP:** `192.168.0.1` (hardcoded, big-endian `0xC0A80001`). Camera always acts as Wi-Fi access point. No mDNS or SSDP discovery is used for this connection path. **[H]**

**TCP socket options:** Set `TCP_NODELAY = 1` (required by PTP-IP spec) and `SO_KEEPALIVE = 1` on the command socket. Non-blocking connect with a 1-second timeout via `select()`, then revert to blocking. **[H]**

---

## 2. Fujifilm Transport Framing (Fuji Length-Prefix Layer)

Fuji's early RE (fuji-cam-wifi-tool) shows a simple 4-byte framing wrapper around every control message **on the command channel**. This wrapper is distinct from the PTP-IP packet headers described in Section 4.

| Byte offset | Width | Description |
|---|---|---|
| 0 | u32 LE | Total frame length, **including** these 4 bytes |
| 4 | N | Message body (see Section 3) |

On receive: read 4 bytes → parse length L → read exactly `L − 4` bytes of body.

A received 4-byte value of `0xFFFFFFFF` signals camera busy or error. A received 4-byte value of `0x00000000 0x00000000 0x00000000 0xFF` (i.e., the 8-byte error pattern `05 00 00 00 19 20 00 00` at message level) signals registration rejected.

> **Note:** The Fuji framing layer described here maps to the Fuji-proprietary command-channel protocol observed in fuji-cam-wifi-tool. The libfuji/XApp stack (which implements the broader PTP-IP standard) uses the PTP-IP packet framing described in Section 4 on top of TCP directly. For `fuji-ptpip`, implement the Section 4 PTP-IP framing as the primary wire format; the Fuji length-prefix above is a secondary detail relevant when reading from the command channel in Fuji-specific mode.

---

## 3. Fuji Control Message Container (Command-Channel Body)

After stripping the 4-byte length prefix, each control message body has this layout:

| Byte offset | Width | Field | Notes |
|---|---|---|---|
| 0 | u16 LE | `index` | 1 = normal; 2 = second part of a two-part message; 0 = terminate sentinel |
| 2 | u16 LE | `message_type` | Fuji opcode (see Section 6) |
| 4 | u32 LE | `transaction_id` | Monotonically incrementing; shared across two-part messages |
| 8 | N | `payload` | Opcode-specific bytes, all LE |

**Success response pattern:** The camera acknowledges a command with exactly 8 payload bytes: `03 00 01 20` followed by the 4-byte little-endian `transaction_id` of the command being acknowledged. Any other content signals failure. **[H]**

Transaction IDs are generated from an atomic counter starting at 1. Two-part messages reuse the same ID for both parts.

---

## 4. PTP-IP Packet Container Format (Standard Layer)

These are the packets that travel directly over TCP in a standard PTP-IP session (as implemented in libfuji and the XApp native stack). All fields are little-endian.

### 4.1 Field-Offset Table: All PTP-IP Packet Types

**Common header (all packet types):**

| Byte offset | Width | Field | Notes |
|---|---|---|---|
| 0 | u32 | `length` | Total packet byte count including this field |
| 4 | u32 | `packet_type` | See type code table below |

**Packet type codes: [H]**

| Code | Name | Direction |
|---|---|---|
| 0x0001 | `PTPIP_INIT_COMMAND_REQ` | Initiator → Responder |
| 0x0002 | `PTPIP_INIT_COMMAND_ACK` | Responder → Initiator |
| 0x0003 | `PTPIP_INIT_EVENT_REQ` | Initiator → Responder |
| 0x0004 | `PTPIP_INIT_EVENT_ACK` | Responder → Initiator |
| 0x0005 | `PTPIP_INIT_FAIL` | Responder → Initiator |
| 0x0006 | `PTPIP_COMMAND_REQUEST` | Initiator → Responder |
| 0x0007 | `PTPIP_COMMAND_RESPONSE` | Responder → Initiator |
| 0x0008 | `PTPIP_EVENT` | Responder → Initiator |
| 0x0009 | `PTPIP_DATA_PACKET_START` | Either |
| 0x000A | `PTPIP_DATA_PACKET` | Either (intermediate chunk) |
| 0x000B | `PTPIP_CANCEL_TRANSACTION` | Either |
| 0x000C | `PTPIP_DATA_PACKET_END` | Either (final chunk + payload) |
| 0x000D | `PTPIP_PING` | Either |
| 0x000E | `PTPIP_PONG` | Either |

### 4.2 Init Command Request (0x0001) — 82 bytes total [H]

Sent once on the command TCP connection during handshake. Total size is 82 bytes (0x52). **Not** preceded by a Fuji length prefix — it is the sole raw-send in the protocol.

| Byte offset | Width | Field | Value / Notes |
|---|---|---|---|
| 0 | u32 | `length` | 0x00000052 (82) |
| 4 | u32 | `packet_type` | 0x00000001 |
| 8 | u32 | `version` | **0x8F53E4F2** — Fujifilm proprietary; standard PTP-IP version field is repurposed |
| 12 | u32 | GUID word 0 | Installation-unique; generate per app install |
| 16 | u32 | GUID word 1 | — |
| 20 | u32 | GUID word 2 | — |
| 24 | u32 | GUID word 3 | — |
| 28 | 54 | `device_name` | UTF-16LE, max 26 visible chars, null-padded to 54 bytes |

The `version` field value `0x8F53E4F2` is a Fujifilm protocol identifier. Cameras reject handshakes that send a different value here. **[H]** — confirmed by both libfuji (`lib/fujiptp.h:6`) and XApp Ghidra analysis.

The 24-byte header magic bytes also observed in fuji-cam-wifi-tool as `{01 00 00 00 f2 e4 53 8f ad a5 48 5d 87 b2 7f 0b d3 d5 de d0 02 78 a8 c0}` represent the same packet with fixed GUID values. Frameport should generate a stable random GUID per installation rather than using any hardcoded value from reference implementations.

### 4.3 Init Command Ack (0x0002) — 68 bytes total [H]

Camera response. Total size 68 bytes (0x44).

| Byte offset | Width | Field | Notes |
|---|---|---|---|
| 0 | u32 | `length` | 0x00000044 (68) |
| 4 | u32 | `packet_type` | 0x00000002 |
| 8 | 16 | GUID (4×u32) | Camera's GUID |
| 24 | 44 | `camera_name` | UTF-16LE camera name |

**Retry logic:** If the Ack carries status word `0x2019` (BUSY), sleep 500 ms and retry, up to 5 attempts (2.5 s total). Fatal status codes that abort connection: `0x201D`, `0x201E`, `0x2000`. **[H]** — XApp native analysis.

### 4.4 Init Event Request (0x0003) — 12 bytes [H]

Sent on the second (event) TCP connection.

| Byte offset | Width | Field | Value |
|---|---|---|---|
| 0 | u32 | `length` | 0x0000000C (12) |
| 4 | u32 | `packet_type` | 0x00000003 |
| 8 | u32 | `connection_number` | 1 |

Response is `PTPIP_INIT_EVENT_ACK` (0x0004), 8 bytes.

### 4.5 Operation Request (0x0006)

| Byte offset | Width | Field | Notes |
|---|---|---|---|
| 0 | u32 | `length` | 18 + (4 × num_params) |
| 4 | u32 | `packet_type` | 0x00000006 |
| 8 | u32 | `data_phase` | 1 = no data; 2 = initiator sends data |
| 12 | u16 | `opcode` | PTP operation code |
| 14 | u32 | `transaction_id` | Post-incrementing counter; wraps `0xFFFFFFFF → 1` (value `−1` is never transmitted) |
| 18 | 4×N | `params` | Up to 5 × u32 parameters (N = 0..5) |

**Transaction ID rules:** Counter starts at 0, is pre-incremented before each operation, so the first command uses ID 1. `OpenSession` must use transaction ID 0 (exception to the rule — explicitly reset before this call). **[H]** — fujihack camlib and filmkit confirm.

### 4.6 Command Response (0x0007)

| Byte offset | Width | Field | Notes |
|---|---|---|---|
| 0 | u32 | `length` | 14 + (4 × num_result_params) |
| 4 | u32 | `packet_type` | 0x00000007 |
| 8 | u16 | `response_code` | See PTP response codes, Section 7 |
| 10 | u32 | `transaction_id` | Echoes the request transaction ID |
| 14 | 4×N | `result_params` | Optional result parameters; count = (length − 14) / 4 |

### 4.7 Data Start Packet (0x0009) — 20 bytes fixed [H]

| Byte offset | Width | Field | Notes |
|---|---|---|---|
| 0 | u32 | `length` | 0x00000014 (20, always) |
| 4 | u32 | `packet_type` | 0x00000009 |
| 8 | u32 | `transaction_id` | Same as the command transaction |
| 12 | u64 | `payload_length` | Total bytes of data to follow across all data packets |

### 4.8 Data End Packet (0x000C)

| Byte offset | Width | Field | Notes |
|---|---|---|---|
| 0 | u32 | `length` | 12 + data_length |
| 4 | u32 | `packet_type` | 0x0000000C |
| 8 | u32 | `transaction_id` | Same transaction |
| 12 | N | payload | Actual data bytes |

Payload starts at byte offset 12 from the packet start.

### 4.9 PTP-IP Receive Sequence [H]

For a command that returns data:
1. Read first packet. If `packet_type == 0x0009` (Data Start), proceed to step 2. If `packet_type == 0x0007` (Response), no data phase — done.
2. Read second packet: `packet_type == 0x000C` (Data End) — extract payload.
3. Read third packet: `packet_type == 0x0007` (Response) — check response code.

For a command that sends data (write operations):
1. Send Operation Request with `data_phase = 2`.
2. Send Data Start packet.
3. Send Data End packet with payload.
4. Receive Response packet.

### 4.10 Close / Goodbye Packet [H]

On graceful disconnect, send an 8-byte goodbye packet, then `shutdown(fd, 2)` and `close(fd)`:

```
08 00 00 00 FF FF FF FF
```

(`length = 8`, `packet_type = 0xFFFFFFFF` — Fujifilm non-standard extension.) Confirmed by libfuji and XApp.

Alternatively (fuji-cam-wifi-tool style): send `stop` opcode (0x1003), then raw 4 bytes `0xFFFFFFFF`.

### 4.11 PTP String Wire Encoding [H]

A PTP string is encoded as:
1. `u8 num_chars` — character count **including** the null terminator (0 if empty string).
2. `num_chars × u16 LE` — UCS-2LE code units, last unit is null (`0x0000`).

An empty string is a single `0x00` byte. In PTP-IP init packets, device name is null-terminated UTF-16 without the length prefix byte.

### 4.12 PTP Array Wire Encoding [H]

Arrays (e.g., object handles, property lists) are encoded as:
1. `u32 LE` — element count.
2. `count × element_width LE` — elements.

---

## 5. PTP-IP Connection Handshake — Step-by-Step

### 5.1 Standard PTP-IP Handshake (as used by libfuji / XApp)

```
Step 1 — TCP connect to camera:55740
Step 2 — Send Init_Command_Request (82 bytes, no framing prefix)
          version = 0x8F53E4F2
          GUID = app-install-stable random 128-bit value
          device_name = UTF-16LE app name (≤26 chars, null-padded to 54 bytes)
Step 3 — Receive Init_Command_Ack (68 bytes)
          If status 0x2019: retry up to 5×, 500ms delay each (2.5s cap)
          If 0x201D / 0x201E / 0x2000: abort — rejected or already open
Step 4 — [Optional] Wait 50ms for WIRELESS_COMM cameras before proceeding
Step 5 — TCP connect to camera:55741 (event channel)
Step 6 — Send Init_Event_Request (12 bytes, connection_number=1)
Step 7 — Receive Init_Event_Ack (8 bytes)
Step 8 — Send OpenSession (opcode 0x1002, transaction_id=0, param[0]=1)
          SessionID is always hardcoded to 1 — not negotiated
Step 9 — Receive Response: 0x2001 (OK)
Step 10 — SetFunctionMode: write desired mode to camera (see Section 5.2)
Step 11 — GetFunctionVersion: read capability version codes (0xDF21–0xDF31 range)
Step 12 — [Mode-specific] Enter operation loop
Step 13 — Send CloseSession (opcode 0x1003)
Step 14 — Send goodbye packet (8 bytes: 08 00 00 00 FF FF FF FF)
          shutdown(fd, 2); close(fd)
```

**[H]** — libfuji `lib/fuji.c`, XApp `CameraConnectModel.java` and `libFTLPTPIP.so.c`.

### 5.2 Function Mode Selection (Fujifilm Extension) [H]

After `OpenSession`, the camera requires a mode negotiation step before accepting operation-class commands. Send `SetDevicePropValue` on the mode property, then call `GetFunctionVersion`. The camera returns BUSY error code `4102` as a retry trigger in these loops (up to 5 passes, 100ms delay per pass, 3 outer loops).

| SDK Mode Name | Value | Use |
|---|---|---|
| `IMAGE_RECEIVE` | 1 | Wi-Fi image import |
| `REMOTE` | 5 | Remote capture / shutter |
| `NEUTRAL20` | 6 | — |
| `FW_DATA_TRANSFER` | 19 | Firmware transfer (v1: do not implement) |
| `IMAGE_VIEW_V2` | 20 | Remote image view |
| `RESERVED_PHOTO_RECEIVED20` | 21 | — |
| `IMAGE_LIVE_VIEW` | 22 | Through-picture streaming |

### 5.3 Fuji-Specific Init Sequence (fuji-cam-wifi-tool Variant)

Observed on older X-series cameras using the Fuji control message framing (Section 3):

```
Step 1 — Send registration_message (78 bytes raw, type 0x0000)
Step 2 — Camera may show approval dialog; rejection = 8-byte error pattern
          {05 00 00 00 19 20 00 00}
Step 3 — Send start (0x1002), payload {01 00 00 00}
Step 4 — Send two_part (0x1016) part 1, payload {01 df 00 00}
Step 5 — Send two_part (0x1016) part 2, payload {05 00}
Step 6 — Send single_part (0x1015), mode selector {24 df 00 00}
          (0x24 = remote; 0x21 = receive; 0x22 = browse; 0x31 = geo)
Step 7 — Receive two responses from camera
Step 8 — Send two_part with same mode selector then {ff 00 02 00}
Step 9 — Send camera_capabilities (0x902b) — parse 392+ byte response
Step 10 — Receive trailing ack
Step 11 — Send camera_remote (0x101c), 8-byte zero payload
           Remote mode is now active (shutter, focus, property commands work)
```

`camera_remote` (0x101c) is the gate opcode that enables remote control. Sending shutter or property commands before this completes will fail. **[H]**

**Terminate sequence:** send `stop` (0x1003), then raw 4 bytes `0xFFFFFFFF`.

### 5.4 Remote / Live-View Channel Open Sequence [H]

To activate the event channel (55741) and live-view channel (55742):

```
Step 1 — Send InitiateOpenCapture (0x101C), params (0, 0)
Step 2 — Poll events (GetDevicePropValue on EventsList 0xD212)
Step 3 — Connect event socket to camera:55741
Step 4 — Send Init_Event_Request on event socket
Step 5 — Connect live-view socket to camera:55742
Step 6 — Set function mode to IMAGE_LIVE_VIEW (22)
Step 7 — Send TerminateOpenCapture (0x1018) with the TransactionID from Step 1
```

Connecting the sockets **before** `TerminateOpenCapture` causes the camera to stall. **[H]** — libfuji `lib/fuji.c:704-731`.

### 5.5 Session Open Retry Policy [H]

| Parameter | Value |
|---|---|
| Maximum connect attempts | 6 |
| Attempt interval | 5,000 ms |
| Total timeout | ~30 s |
| Cancel flag | Checked per iteration |
| BUSY error code | 4102 (triggers retry, distinct from protocol errors) |

### 5.6 Stale Session Recovery [H]

If `OpenSession` returns `0x201E` (`SessionAlreadyOpen`):
1. Send `CloseSession` (0x1003) best-effort — do not wait for response.
2. Close and reopen TCP connections to camera.
3. Reset transaction ID counter to 0.
4. Retry `OpenSession`. If retry fails, abort.

---

## 6. Consolidated Opcode Table

### 6.1 Standard PTP Opcodes [H]

Sources: ISO 15740, fujihack camlib, filmkit, libfuji. All confirmed across ≥2 sources.

| Opcode | Name | Notes |
|---|---|---|
| 0x1001 | `GetDeviceInfo` | No session required; always first probe. Returns manufacturer string `FUJIFILM`. |
| 0x1002 | `OpenSession` | param[0] = SessionID (always 1). Must use transaction_id = 0. |
| 0x1003 | `CloseSession` | No parameters. |
| 0x1004 | `GetStorageIDs` | Returns u32 array of storage IDs. |
| 0x1005 | `GetStorageInfo` | param[0] = storage_id. |
| 0x1006 | `GetNumObjects` | Object count. |
| 0x1007 | `GetObjectHandles` | param[0] = storage_id, param[1] = format filter (0 = all), param[2] = parent handle (0 = root). Returns u32 handle array. Poll with `[0xFFFFFFFF, 0x0000, 0x00000000]` for all objects. |
| 0x1008 | `GetObjectInfo` | param[0] = object handle. Returns ObjectInfo structure. |
| 0x1009 | `GetObject` | param[0] = object handle. Returns full object data. |
| 0x100A | `GetThumb` | param[0] = object handle. Returns JPEG thumbnail. |
| 0x100B | `DeleteObject` | param[0] = handle, param[1] = format code. |
| 0x100C | `SendObjectInfo` | Standard send; param[0] = storage_id, param[1] = parent. See vendor variant 0x900C. |
| 0x100D | `SendObject` | Standard send data. See vendor variant 0x900D. |
| 0x100E | `InitiateCapture` | param[0] = storage_id, param[1] = object format code. |
| 0x1014 | `GetDevicePropDesc` | param[0] = property code. Returns DevicePropDesc structure. |
| 0x1015 | `GetDevicePropValue` | param[0] = property code. Returns current value. |
| 0x1016 | `SetDevicePropValue` | param[0] = property code. Data phase carries new value. |
| 0x1018 | `TerminateOpenCapture` | param[0] = transaction_id of the matching InitiateOpenCapture. |
| 0x101B | `GetPartialObject` | param[0] = handle, param[1] = byte offset, param[2] = max bytes. Returns chunk. |
| 0x101C | `InitiateOpenCapture` | Opens capture mode; also used to trigger event/liveview channel activation. |

### 6.2 Fujifilm Vendor Opcodes [H] (unless noted)

Sources: fuji-cam-wifi-tool, libfuji, fujihack, filmkit, XApp.

| Opcode | Name | Params / Payload | Sources |
|---|---|---|---|
| 0x9020 | `InitiateMovieCapture` | param[0]=StorageID, param[1]=ObjectFormatCode. Save returned TransactionID. | libfuji, XApp |
| 0x9021 | `TerminateMovieCapture` | No params; reads saved TransactionID from session state, zeroes it. | libfuji, XApp |
| 0x9022 | `GetCapturePreview` / `camera_last_image` | No payload. Returns last-captured thumbnail (skip 8-byte header). | fuji-cam-wifi-tool, libfuji |
| 0x9023 | `StepZoom` | — | libfuji |
| 0x9024 | `StartZoom` | — | libfuji |
| 0x9025 | `StopZoom` | — | libfuji |
| 0x9026 | `LockS1Lock` / `focus_point` | param[0] = AFArea u32. Sets AF point. For focus point: payload = `{y_u8, x_u8, 0x02, 0x03}`. | fuji-cam-wifi-tool, libfuji, XApp |
| 0x9027 | `UnlockS1Lock` / `focus_unlock` | Five parameter slots all zero / no payload. | fuji-cam-wifi-tool, libfuji, XApp |
| 0x902B | `GetDeviceInfo` (Fuji) / `camera_capabilities` | No params. Response: 12-byte prefix, then TLV DevicePropDesc blocks. | fuji-cam-wifi-tool, libfuji |
| 0x902C | `StepShutterSpeed` | param[0] = direction: 1 = faster, 0 = slower. | fuji-cam-wifi-tool, libfuji, XApp |
| 0x902D | `StepFNumber` | param[0] = direction: 1 = open, 0 = close. | fuji-cam-wifi-tool, libfuji, XApp |
| 0x902E | `StepExposureBias` | param[0] = direction: 1 = positive, 0 = negative. | fuji-cam-wifi-tool, libfuji, XApp |
| 0x9030 | `CancelInitiateCapture` | — | libfuji |
| 0x9040 | `FmSendObjectInfo` | ObjectInfo data payload. | libfuji, XApp |
| 0x9041 | `FmSendObject` | Object data payload. | XApp |
| 0x9042 | `FmSendPartialObject` | Partial object data. | libfuji, XApp |
| 0x900C | `FujiSendObjectInfo` | ObjectInfo: StorageID u32, ObjectFormat u16 (`0xF802` for RAF), ProtectionStatus u16, CompressedSize u32, 40 bytes padding, filename PTP string. Used for file upload to camera. | fujihack, filmkit |
| 0x900D | `FujiSendObject2` | Object data. Semantics similar to 0x901D. | fujihack, filmkit |
| 0x901D | `FujiWriteFile` | File content data payload. Pair with 0x900C. | fujihack |
| 0x9053 | Vendor extension | — | XApp |
| 0x9054 | Vendor extension | — | libfuji, XApp |
| 0x9055 | Vendor extension | — | libfuji |
| 0x9056 | Vendor extension | — | XApp |
| 0x9060 | `SetCameraEvent` | data_phase = 2. Payload: 6 bytes for non-string types; `stringLength + 6` for type 0xFFFF. | XApp |

**Fuji-specific init opcodes (command-channel protocol, Section 3):**

| Opcode | Name | Notes |
|---|---|---|
| 0x0000 | `hello` / registration | Used only in raw registration_message header |
| 0x1002 | `start` | Payload `{01 00 00 00}` |
| 0x1003 | `stop` | Terminate sequence |
| 0x1008 | `image_info_by_index` | 4-byte payload = image index u32 |
| 0x100A | `thumbnail_by_index` | 4-byte payload = image index u32 |
| 0x100E | `shutter` | 8-byte zero payload |
| 0x1015 | `single_part` / `status poll` | With payload `{12 d2 00 00}` for status polling |
| 0x1016 | `two_part` | Two-part property write protocol |
| 0x101B | `full_image` | 8-byte payload: 4-byte index + 4-byte image_id |
| 0x101C | `camera_remote` | 8-byte zero payload; gate for remote control |

---

## 7. PTP Response Codes [H]

| Code | Name | Notes |
|---|---|---|
| 0x2001 | `OK` | Success |
| 0x2002 | `GeneralError` | — |
| 0x2003 | `SessionNotOpen` | — |
| 0x2004 | `InvalidTransactionID` | — |
| 0x2005 | `OperationNotSupported` | — |
| 0x2006 | `ParameterNotSupported` | — |
| 0x2007 | `IncompleteTransfer` | — |
| 0x2008 | `InvalidStorageID` | — |
| 0x2009 | `InvalidObjectHandle` | — |
| 0x200A | `DevicePropNotSupported` | — |
| 0x201E | `SessionAlreadyOpen` | Triggers stale session recovery |
| 0x2019 | BUSY | Retry trigger (500 ms × 5) |
| 0x201D | Fatal auth failure | Abort |
| 0x2000 | Fatal generic failure | Abort |
| 4101 | `UNSUPPORTED` (XSDK) | Fujifilm-layer error code |
| 4102 | `BUSY` (XSDK) | Fujifilm retry trigger in mode negotiation |
| 8194 | `TIMEOUT` (XSDK) | — |
| 8195 | `COMBINATION` (XSDK) | — |
| −2 | `FORCEMODE_BUSY` (XSDK) | Distinct from other errors; not a retry signal |
| 37120 | `UNKNOWN` (XSDK) | — |

---

## 8. Device Property Codes

### 8.1 Standard PTP Properties [H]

| Code | Name | Notes |
|---|---|---|
| 0x5001 | `BatteryLevel` | Standard |
| 0x5002 | `FunctionalMode` | Standard |
| 0x5003 | `ImageSize` | Standard |
| 0x5005 | `WhiteBalance` | Standard |
| 0x5007 | `FNumber` | u16, value = f-number × 100; 0x0000 = Auto, 0xFFFF = N/A |
| 0x500A | `FocusMode` | 1 = Manual, 0x8001 = Single AF, 0x8002 = Continuous AF |
| 0x500C | `FlashMode` | Standard |
| 0x500D | `ExposureTime` | Standard (shutter speed) |
| 0x500E | `ExposureProgramMode` | 1 = Manual, 2 = Program, 3 = AV, 4 = TV, 6 = Auto |
| 0x500F | `ExposureIndex` | ISO (standard) |
| 0x5010 | `ExposureBiasCompensation` | i16 in milli-EV; divide by 1000.0 for EV |
| 0x5011 | `DateTime` | Standard |
| 0x5012 | `CaptureDelay` / self-timer | 0=Off, 1=1s, 2=2s, 3=5s, 4=10s |
| 0x5013 | `StillCaptureMode` | Standard |

### 8.2 Fujifilm Vendor Properties [H unless noted]

| Code | Name | Value Encoding | Sources |
|---|---|---|---|
| 0xD001 | `FilmSimulation` | u16 enum; see Section 9 | fuji-cam-wifi-tool, libfuji, filmkit |
| 0xD002 | `FilmSimulationTune` | — | filmkit |
| 0xD003 | `DRangeMode` | — | filmkit |
| 0xD007 | `ColorTemperature` | — | filmkit |
| 0xD008 | `WhiteBalanceFineTune` | — | filmkit |
| 0xD00A | `NoiseReduction` | — | filmkit |
| 0xD00B | `ImageQuality` | 2=JPEG Fine, 3=JPEG Normal, 4=RAW+JPEG Fine, 5=RAW+JPEG Normal | filmkit |
| 0xD00C | `RecMode` | — | filmkit |
| 0xD00F | `FocusMode` | — | filmkit |
| 0xD016 | `USBMode` | 5=Tether, 6=RAW Conv, 8=Webcam; absence = Card Reader | libfuji |
| 0xD017 | `GrainEffect` | See Section 9.2 for dual encoding | filmkit |
| 0xD018 | `ImageFormat` | 2=JPEG Fine, 3=JPEG Normal, 4=RAW+JPEG Fine, 5=RAW+JPEG Normal | fuji-cam-wifi-tool |
| 0xD019 | `ShadowHighlight` / `RecmodeEnable` | fuji-cam-wifi-tool: 0=Unavailable, 1=Available; filmkit: shadow-highlight | fuji-cam-wifi-tool, filmkit |
| 0xD028 | `FSSControl` | 0=both adj, 1=SS limit, 2=aperture limit, 3=both limit | fuji-cam-wifi-tool |
| 0xD02A | `ISO` | u32; bit 31=auto flag, bit 30=emulated, bits 0-23=numeric ISO | fuji-cam-wifi-tool, libfuji |
| 0xD02B | `MovieISO` | Same encoding as ISO; 0xFFFFFFFF = auto | fuji-cam-wifi-tool |
| 0xD100 | `ExposureIndex` | — | filmkit |
| 0xD104 | `FocusMeteringMode` | — | filmkit |
| 0xD10A | `ShutterSpeed` | — | filmkit |
| 0xD10B | `ImageAspectRatio` | 2=S 3:2, 3=S 16:9, 4=S 1:1, 6=M 3:2, 7=M 16:9, 8=M 1:1, 10=L 3:2, 11=L 16:9, 12=L 1:1 | filmkit (codes), fuji-cam-wifi-tool (aspect) |
| 0xD171 | `RawConversionEdit` | — | filmkit |
| 0xD183 | `StartRawConversion` | Write u16 0x0000 to trigger conversion | filmkit |
| 0xD184 | `IOPCodes` | — | filmkit |
| 0xD185 | `RawConvProfile` | 601-byte (camera) or 625-byte (native X100VI) binary profile; see Section 9.3 | libfuji, filmkit |
| 0xD186 | `FirmwareVersion` | — | filmkit |
| 0xD187 | `FirmwareVersion2` | — | filmkit |
| 0xD18C | `PresetSlot` | Write 1–7 to select C1–C7 preset slot; wait 100 ms after | filmkit |
| 0xD18D | `PresetName` | PTP string | filmkit |
| 0xD18E–0xD1A5 | Preset settings | See Section 9.4 for full map | filmkit |
| 0xD20E | `FreeSDRAMImages` | Non-zero signals new image available for download | libfuji |
| 0xD212 | `EventsList` / `CurrentState` | Poll via GetDevicePropValue; returns u16 count + count × {u16 prop_code, u32 value} | libfuji, filmkit |
| 0xD21B | `DeviceError` | 0 = no error | fuji-cam-wifi-tool |
| 0xD222 | `ObjectCount` | Number of Wi-Fi-accessible objects; handles are sequential 1..N | libfuji |
| 0xD226 | `CompressSmall` | 1 = receive 400–800 KB preview instead of full file | libfuji |
| 0xD227 | `EnableCorrectFileSize` | Set to 1 before GetObjectInfo/GetObject; reset to 0 after. Default reports ~100 KB placeholder. | libfuji |
| 0xD229 | `ImageSpaceSD` | Remaining space on SD card | fuji-cam-wifi-tool |
| 0xD22A | `MovieRemainingTime` | Remaining recording time | fuji-cam-wifi-tool |
| 0xD240 | `ShutterSpeed` (vendor) | u32; bit 31: set=sub-second (1/N, N=bits 0-27÷1000), clear=seconds (N=bits 0-27÷1000); 0xFFFFFFFF=N/A | fuji-cam-wifi-tool |
| 0xD241 | `ImageAspect` | See 0xD10B codes | fuji-cam-wifi-tool |
| 0xD242 | `BatteryLevel` (vendor) | NP-W126: 1=Critical, 2=1 bar, 3=2 bars, 4=Full; NP-W126S: 6=Critical, 7-10=bars, 11=Full | fuji-cam-wifi-tool |
| 0xD36A | `BatteryInfo1` | Percentage value | libfuji |
| 0xD36B | `BatteryInfo2` | — | libfuji |
| 0xD500 | `Geolocation` | NMEA-like ASCII string [M] | libfuji |
| 0xD17C | `FocusPoint` | u32; x = bits 8-15, y = bits 0-7 | fuji-cam-wifi-tool |
| 0xD209 | `FocusLock` | 0 = unlocked, 1 = locked | fuji-cam-wifi-tool |
| 0xDF00 | `CameraState` | 0=WAIT, 1=MULTIPLE_TRANSFER, 2=FULL_ACCESS, 3=PC_AUTOSAVE, 6=REMOTE_ACCESS | libfuji |
| 0xDF01 | `ClientState` | Write target mode; triggers camera UI dialog on newer models | libfuji |
| 0xDF21 | `ImageGetVersion` | Read then write-back same value to confirm support | libfuji |
| 0xDF22 | `GetObjectVersion` | Version negotiation; read then write-back | libfuji |
| 0xDF24 | `RemoteVersion` | Camera Connect app 2.11 = 0x2000C; −1 = no remote mode | libfuji |
| 0xDF25 | `RemoteGetObjectVersion` / `RemotePhotoView` | Set to 5 during remote image view setup | libfuji, XApp |
| 0xDF27 | `FirmwareDataTransfer` | Capability gate for firmware transfer (v1: do not implement) | XApp |
| 0xDF28 | `RemotePhotoViewEx` | — | XApp |
| 0xDF2A | `RemoteEx` | — | XApp |
| 0xDF31 | `GPSSet` | — | XApp |

**Fujifilm version/capability property range:** 0xDF21–0xDF31. All are read after `SetFunctionMode` via `GetFunctionVersion` to gate capability negotiation.

### 8.3 Status Polling vs Capability Query [H]

Two distinct mechanisms exist for reading camera state; both must be implemented:

**One-time capability query:** `GetDevicePropValue(0x902B)` (or `GetDevicePropDesc`) returns the full `DevicePropDesc` structure per property (enumeration lists, min/max, defaults). Called once at session open.

**Continuous status polling:** `GetDevicePropValue(0xD212)` returns only current values as compact `{code u16, value u32}` pairs. Also must be called regularly during transfers to keep the connection alive.

---

## 9. Encoding Details for Non-Trivial Properties

### 9.1 ISO Value Bit-Field Encoding [H]

`property_iso` (0xD02A) and `property_movie_iso` (0xD02B) are u32 with:

| Bits | Meaning |
|---|---|
| 31 | Auto flag (1 = auto ISO) |
| 30 | Emulated flag |
| 0–23 | Numeric ISO value |

Value `0xFFFFFFFF` = auto for movie ISO.

### 9.2 Shutter Speed Bit-Field Encoding [H]

`property_shutter_speed` (0xD240) is u32:

| Bit 31 | Interpretation |
|---|---|
| Set (1) | Sub-second: speed = 1 / (bits 0-27 ÷ 1000) seconds |
| Clear (0) | Seconds: speed = bits 0-27 ÷ 1000 seconds |

Value `0xFFFFFFFF` = not applicable / bulb.

### 9.3 Film Simulation Codes [H]

Confirmed across fuji-cam-wifi-tool, libfuji, and filmkit. filmkit extends the list through 0x14 (X-Processor 5 sims); older sources go to 0x10.

| Value | Film Simulation | Monochrome? |
|---|---|---|
| 0x01 | Provia / Standard | No |
| 0x02 | Velvia / Vivid | No |
| 0x03 | Astia / Soft | No |
| 0x04 | PRO Neg Hi | No |
| 0x05 | PRO Neg Std | No |
| 0x06 | Monochrome | **Yes** |
| 0x07 | Monochrome + Ye filter | **Yes** |
| 0x08 | Monochrome + R filter | **Yes** |
| 0x09 | Monochrome + G filter | **Yes** |
| 0x0A | Sepia | **Yes** |
| 0x0B | Classic Chrome | No |
| 0x0C | Acros | **Yes** |
| 0x0D | Acros + Ye | **Yes** |
| 0x0E | Acros + R | **Yes** |
| 0x0F | Acros + G | **Yes** |
| 0x10 | Eterna / Cinema | No |
| 0x11 | Classic Neg | No [M] |
| 0x12 | Eterna Bleach Bypass | No [M] |
| 0x13 | Nostalgic Neg | No [M] |
| 0x14 | Reala Ace | No [M] |

> **libfuji conflict:** libfuji assigns slightly different numeric values in `fujiptp.h` (e.g., Pro Neg Hi=4 vs fuji-cam-wifi-tool's 6). filmkit (based on X100VI hardware testing, 2026-03) matches the 0x01–0x14 mapping above. Use filmkit's mapping as primary; validate against X-T5 packet capture before shipping.

**Monochrome constraint:** For monochrome sims, the Color parameter (0xD19F) must not be written. MonoWC (0xD193) and MonoMG (0xD194) apply only to monochrome sims.

### 9.4 Grain Effect Dual Encoding [H]

Two incompatible wire encodings exist — context determines which to use:

**In D185 profile (byte-packed u16):**
- Low byte: strength (0=Off, 2=Weak, 3=Strong)
- High byte: size (0=Small, 1=Large)
- Values: Off=0x0000, WeakSmall=0x0002, StrongSmall=0x0003, WeakLarge=0x0102, StrongLarge=0x0103

**In preset property 0xD195 (flat 1-indexed enum):**
- 1=Off, 2=WeakSmall, 3=StrongSmall, 4=WeakLarge, 5=StrongLarge

### 9.5 Tone Parameter ×10 Encoding [H]

HighlightTone, ShadowTone, Color, Sharpness, Clarity, MonoWC, MonoMG are stored as:
- `wire_value = ui_integer × 10`
- Signed i16 in preset properties, signed i32 in D185 profile
- Sentinel `0x8000` (i16: −32768) = "use EXIF default / unset"

### 9.6 HighIsoNR Non-Linear Encoding [H]

Property D1A1 (preset) / D185 profile index 20 uses a proprietary empirical lookup (not ×10):

| UI value | Wire value (u16) |
|---|---|
| −4 | 0x8000 |
| −3 | 0x7000 |
| −2 | 0x4000 |
| −1 | 0x3000 |
| 0 | 0x2000 |
| +1 | 0x1000 |
| +2 | 0x0000 |
| +3 | 0x6000 |
| +4 | 0x5000 |

**Critical:** `0x8000` here means −4 NR. In tone fields, `0x8000` is the "use EXIF default" sentinel. Context-dependent meaning of the same bit pattern.

### 9.7 D185 Raw Conversion Profile Layout [H]

Native profile returned by camera (confirmed X100VI, 2026-03): **625 bytes**.

- Byte 0–1: `u16 numParams` — count of i32 parameters at the end of the buffer.
- Parameters start at offset `(625 − numParams × 4)`.
- Each parameter is `i32 LE`.

**Field index table (NativeIdx):**

| Index | Field | Encoding |
|---|---|---|
| 4 | ExposureBias | millistops |
| 6 | DynamicRange% | 100 / 200 / 400 |
| 7 | DRangePriority | — |
| 8 | FilmSimulation | enum (Section 9.3) |
| 9 | GrainEffect | flat enum (Section 9.4) |
| 10 | ColorChrome | 1-indexed (1=Off, 2=Weak, 3=Strong) |
| 11 | SmoothSkin | 1-indexed |
| 12 | WBMode | 0 = use EXIF; other = WB code |
| 13 | WBShiftR | — |
| 14 | WBShiftB | — |
| 15 | WBColorTemp | Kelvin |
| 16 | HighlightTone | ×10 (Section 9.5) |
| 17 | ShadowTone | ×10 |
| 18 | Color | ×10 (sentinel 0 = use EXIF) |
| 19 | Sharpness | ×10 |
| 20 | NoiseReduction | non-linear (Section 9.6); sentinel 0x8000 when unset |
| 25 | ColorChromeFxBlue | 1-indexed |
| 27 | Clarity | ×10 |

**Patch strategy:** Copy the base profile byte-for-byte from the camera, then overwrite only the indices for user-changed parameters. Preserves EXIF sentinels in untouched fields.

### 9.8 Preset Slot Properties (0xD18E–0xD1A5) [H]

Full property map from filmkit (X100VI, cross-referenced via Wireshark against X RAW Studio):

| Code | Field | Notes |
|---|---|---|
| 0xD18E | ImageSize | Default observed: 7 |
| 0xD18F | ImageQuality | Default: 4 |
| 0xD190 | DynamicRange% | Wire: 100 / 200 / 400 (not enum) |
| 0xD191 | Unknown | Always 0 |
| 0xD192 | FilmSimulation | Enum (Section 9.3) |
| 0xD193 | MonoWC | ×10; only for monochrome sims; reject write of 0 |
| 0xD194 | MonoMG | ×10; only for monochrome sims; reject write of 0 |
| 0xD195 | GrainEffect | Flat enum (Section 9.4) |
| 0xD196 | ColorChrome | 1-indexed |
| 0xD197 | ColorChromeFxBlue | 1-indexed |
| 0xD198 | SmoothSkin | 1-indexed |
| 0xD199 | WhiteBalance | u16 (received as i16; mask with 0xFFFF) |
| 0xD19A | WBShiftR | — |
| 0xD19B | WBShiftB | — |
| 0xD19C | WBColorTemp | Kelvin; write only when WB mode = 0x8007 (ColorTemp) |
| 0xD19D | HighlightTone | ×10 |
| 0xD19E | ShadowTone | ×10 |
| 0xD19F | Color | ×10; omit for monochrome sims |
| 0xD1A0 | Sharpness | ×10 |
| 0xD1A1 | HighIsoNR | Non-linear (Section 9.6); default 0x4000 |
| 0xD1A2 | Clarity | ×10 |
| 0xD1A3 | LongExpNR | 1 = On (default) |
| 0xD1A4 | ColorSpace | 1 = sRGB (default) |
| 0xD1A5 | Unknown | Always 7 |

**Write order (confirmed from Wireshark of official app):** D18E, D18F, D190, D191, D192, [D193 if mono], [D194 if mono], D195, D196, D197, D198, D199, [D19C immediately after D199 if WB=0x8007], D19A, D19B, D19D, D19E, [D19F if not mono], D1A0, D1A1, D1A2, D1A3, D1A4, D1A5.

**Preset slot select:** Write D18C = slot_number (1–7), then wait 100 ms before any further reads or writes.

---

## 10. Event Codes

### 10.1 Standard PTP Event Codes [H]

| Code | Name |
|---|---|
| 0x4002 | `ObjectAdded` |
| 0x4003 | `ObjectRemoved` |
| 0x4006 | `DevicePropChanged` |
| 0x4009 | `RequestObjectTransfer` |
| 0x400D | `CaptureComplete` |

### 10.2 Fujifilm Vendor Event Codes [M]

| Code | Name | Notes |
|---|---|---|
| 0xC001 | `PreviewAvailable` | libfuji comment: "may be inaccurate" |
| 0xC004 | `ObjectAdded` (Fuji) | libfuji comment: "may be misplaced" |

> These codes are flagged as potentially inaccurate in libfuji. Validate against packet capture before relying on them. The EventsList polling mechanism (Section 8.3) is the primary event delivery path for Fujifilm cameras over Wi-Fi, not unsolicited PTP events.

---

## 11. PTP Data Type Codes [H]

Used in `DevicePropDesc` responses to identify property value types.

| Code | Name | Byte width |
|---|---|---|
| 0x0001 | INT8 | 1 |
| 0x0002 | UINT8 | 1 |
| 0x0003 | INT16 | 2 |
| 0x0004 | UINT16 | 2 |
| 0x0005 | INT32 | 4 |
| 0x0006 | UINT32 | 4 |
| 0x0007 | INT64 | 8 |
| 0x0008 | UINT64 | 8 |
| 0x4002 | UINT8[] | variable |
| 0x4004 | UINT16[] | variable |
| 0x4006 | UINT32[] | variable |
| 0xFFFF | STRING | variable (PTP string encoding) |

**DevicePropDesc form flag values:** 0x01 = Range (min, max, step), 0x02 = Enumeration (count, values[]).

---

## 12. DevicePropDesc Binary Structure [H]

Returned by `GetDevicePropDesc` (0x1014) and embedded in the `camera_capabilities` (0x902B) response. The 0x902B response begins with a 12-byte unknown prefix, then a sequence of TLV sub-messages: each sub-message has a 4-byte inclusive length prefix, then:

| Offset | Width | Field |
|---|---|---|
| 0 | u16 | `property_code` |
| 2 | u16 | `data_type` (see Section 11) |
| 4 | u8 | `get_set` (0 = read-only, 1 = read-write) |
| 5 | N | `factory_default_value` (width per data_type) |
| 5+N | N | `current_value` |
| 5+2N | u8 | `form_flag` (0x01 = Range, 0x02 = Enumeration) |
| 5+2N+1 | … | If Range: `{min, max, step}` each N bytes. If Enumeration: `u16 count` then `count × N` values. |

---

## 13. Session Open/Close Sequence Summary

### 13.1 Image Import Session (Wi-Fi)

```
1. Android: request Fujifilm camera Wi-Fi network
2. Android: bind socket to returned Network object
3. Android: connect TCP to 192.168.0.1:55740
4. Android: dup(fd); pass dup'd fd to Rust
5. Rust: send Init_Command_Request (82 bytes)
6. Rust: receive Init_Command_Ack (68 bytes); retry on BUSY up to 2.5s
7. Rust: wait 50ms (WIRELESS_COMM guard)
8. Rust: send OpenSession (0x1002, txid=0, SessionID=1)
9. Rust: receive OK (0x2001)
10. Rust: SetFunctionMode → IMAGE_RECEIVE (1)
11. Rust: GetFunctionVersion; check 0xDF22, 0xDF24 capability gates
12. Rust: SetDevicePropValue(0xD227, 1) — enable correct file sizes
13. Rust: GetDevicePropValue(0xD212) — poll EventsList for ObjectCount
14. Rust: GetObjectInfo(handle) for each object
15. Rust: GetThumb(handle) for thumbnails
16. Rust: GetPartialObject(handle, offset, 0x100000) looped — 1MB chunks
17. Rust: SetDevicePropValue(0xD227, 0) — reset file size flag
18. Rust: send CloseSession (0x1003)
19. Rust: send goodbye packet (8 bytes)
20. Rust: shutdown(fd, 2); close(fd)
```

### 13.2 Remote Capture Session (Wi-Fi)

```
1–11. Same as image import through GetFunctionVersion
12. Rust: SetFunctionMode → REMOTE (5)
13. Rust: GetFunctionVersion; check 0xDF21, 0xDF25 capability gates
14. Rust: send InitiateOpenCapture (0x101C, params=[0,0])
15. Rust: poll EventsList
16. Android: connect TCP to 192.168.0.1:55741 (event channel)
17. Android: send Init_Event_Request on event socket
18. Rust: send TerminateOpenCapture (0x1018) with saved TransactionID
19. [Remote operations: LockS1Lock (0x9026), InitiateCapture (0x100E), UnlockS1Lock (0x9027)]
20. Rust: receive async events from event channel
21. Rust: send CloseSession (0x1003)
22. Rust: send goodbye packet
```

### 13.3 Object Handle Numbering (Wi-Fi) [H]

**Do not call `GetObjectHandles` for Wi-Fi sessions.** Object handles are sequential integers `1..N` where `N` comes from `ObjectCount` (0xD222) in the EventsList. In `MULTIPLE_TRANSFER` mode (CameraState = 1), the handle is always literally `1` and the camera advances it to the next image after each successful download.

### 13.4 Two-Part Property Write Protocol [H]

Writing a mutable property uses a split message:

```
Part 1: two_part (0x1016), index=1, transaction_id=T, payload={property_code as u32 LE}
Part 2: two_part (0x1016), index=2, transaction_id=T, payload={new_value as u32 LE}
```

Wait for success response only after Part 2. Both parts share the same transaction ID.

This is Fujifilm-specific; standard PTP `SetDevicePropValue` (0x1016) carries the property code as a parameter and the value in the data phase rather than splitting across two messages.

---

## 14. Object Format Codes [H]

| Wire code | Format | Notes |
|---|---|---|
| 0x3002 | Script | Used in FUJI_CREATE_FILE (0x900C) for script upload |
| 0x3801 | JPEG | Standard |
| 0xB103 | RAF (Fujifilm RAW) | Standard across vendors |
| 0xF802 | Fuji firmware / RAW profile | Required for RAF upload via 0x900C; filename must be `FUP_FILE.dat` |
| 0xFFF1 | Fuji proprietary | PTP_OF_FUJI_FFF1 [M] |

**XSDK internal codes** (not PTP wire): JPEG=7, RAW=1, MOV=8, HEIF=18. Rotation packed in upper byte (0x06xx=90°, 0x03xx=180°, 0x08xx=270°).

**PTP wire object format codes (from XApp):** JPEG=14337/14344, RAF=45315, MOV=12301, HEIF=47490.

---

## 15. Live-View Frame Format [H]

Each Fuji-framed live-view payload on port 55742:

| Byte offset | Width | Field | Notes |
|---|---|---|---|
| 0 | u32 | Unknown | Observed as 0 |
| 4 | u32 | Frame counter | Increments per frame; use to detect dropped frames |
| 8 | 6 | Padding | Zeros |
| 14 | N | JPEG data | Starts with `FF D8`; validate as sanity check |

From the XApp native analysis, the extraction formula accounts for a secondary length field:

```
JPEG_start = secondaryLen + 18
```

where `secondaryLen` is a LE-u32 at byte offset 12 of the raw receive buffer (before stripping the 4-byte Fuji transport prefix). The constant 18 corresponds to the 4-byte transport prefix + 14-byte frame header.

Receive buffer size: **204,800 bytes** per frame (XApp `REMOTE_RECEIVE_FILE_SIZE`). Implement as a two-slot ring buffer with producer/consumer notification to avoid blocking the network reader.

---

## 16. Frameport Implementation Notes

### 16.1 Crate Responsibilities

| Crate | Responsibility |
|---|---|
| `fuji-ptp` | PTP container codec: encode/decode all packet types (Sections 3, 4). PTP string/array encoding. Standard opcode/response/event/format constants. Property code enum. |
| `fuji-ptpip` | TCP connection management: three-socket topology, init handshake state machine, transaction ID counter, session open/close. Owns `open_from_owned_socket_fd`. |
| `fuji-core` | Fujifilm-layer state: function mode negotiation, EventsList polling, property encoding (ISO bit-fields, shutter speed bit-fields, film sim enum, NR non-linear table, two-part write protocol). |
| `fuji-liveview` | Live-view socket: frame parsing (14-byte header + JPEG), frame counter, two-slot ring buffer. |
| `fuji-transfer` | Media object enumeration and download: GetObjectInfo, GetThumb, GetPartialObject chunked loop (1MB), EnableCorrectFileSize toggle, MediaStore fd sink. |

### 16.2 Critical Implementation Rules

1. **Port 55740, not 15740.** Fujifilm does not use the ISO PTP/IP standard port.
2. **Version magic `0x8F53E4F2`.** Cameras reject any other value in the Init_Command_Request version field.
3. **SessionID is always 1.** Not negotiated.
4. **Transaction ID 0 for OpenSession only.** All others use incrementing counter wrapping `0xFFFFFFFF → 1`.
5. **Event and live-view channels are lazy.** Do not connect them during the init handshake; open only from `InitiateOpenCapture` paths (Section 5.4).
6. **`EnableCorrectFileSize` (0xD227) must be set to 1** before `GetObjectInfo` / download and reset to 0 after. Skipping this makes all file sizes report as ~100 KB.
7. **Do not call `GetObjectHandles` for Wi-Fi.** Use `ObjectCount` from EventsList and sequential handles 1..N.
8. **GetPartialObject chunks ≤ 1MB (0x100000).** Some cameras hang with larger requests.
9. **EventsList polling keeps the connection alive.** Call it during transfers at regular intervals.
10. **JPEG starts at byte 14 of live-view payload** (or use `secondaryLen + 18` formula from XApp analysis).
11. **TerminateOpenCapture must come after connecting the event and live-view sockets** — not before.
12. **The fd passed to Rust is a dup'd fd.** Android retains ownership of the original; Rust owns and must close only the dup'd fd (see ADR-0002).

---

## 17. Clean-Room and IP Caveats

1. **Sources:** This document synthesizes interoperability facts from five open-source reverse-engineering projects: fuji-cam-wifi-tool (MIT), libfuji/libpict (open-source, Daniel C / petabyt), fujihack (GPL-3.0), filmkit (license to be reviewed), and analysis of FUJIFILM XApp 2.7.5 (proprietary). No function bodies, struct definitions, or creative expression from any of these projects have been copied. Only numeric constants, packet field layouts, timing values, and behavioral sequences — all of which are interoperability facts required to communicate with Fujifilm cameras — are recorded here.

2. **Fujifilm proprietary:** The PTP-IP protocol implemented by Fujifilm cameras is not publicly documented. These facts were recovered through clean-room reverse engineering of publicly observable wire behavior and open-source RE projects. All Frameport code implementing this protocol must be original.

3. **Registration magic header:** The 24-byte Init_Command_Request bytes including version `0x8F53E4F2` are a protocol-layer identifier analogous to a GUID. They appear in multiple independent open-source RE projects and are observable in any packet capture of a Fujifilm camera session. Legal confirmation that embedding these values in original Rust code is acceptable under applicable terms should be obtained before shipping.

4. **Model specificity:** Primary RE corpus targets X-T2, X-T10, X-T20, X-T100, X-T4, X-H1, X-S10, X-A2, and X100VI. The Frameport v1 target is the **X-T5** (X-Processor 5). Film simulation codes 0x11–0x14 (filmkit) are noted as "may need adjustment for X-Processor 5." All constants should be validated against a live X-T5 packet capture before shipping.

5. **D185 profile formats:** Two conflicting sizes are documented: 601 bytes (camera per libfuji, older models), 625 bytes (X100VI, filmkit, 2026-03), 629 bytes (X Raw Studio). Implement the 625-byte native format for X-Processor 5 cameras; the parser must be length-tolerant.

6. **HighIsoNR encoding:** The non-linear lookup table (Section 9.6) was derived from Wireshark captures of X RAW Studio and is entirely empirical. Future firmware may change it.

7. **Async event codes:** The event channel (port 55741) payload structure is not fully documented. Known event types around a shutter operation number up to 3 (empirical). EventsList polling (0xD212) is the primary mechanism; unsolicited event codes (Section 10.2) are flagged as potentially inaccurate.

8. **Fujifilm cloud credentials in XApp:** Firebase, PostHog, AWS Cognito, and Google Maps API keys are embedded in XApp source. None of these credentials or cloud infrastructure concepts may appear in Frameport.

9. **Firmware update opcodes:** The `FUJI_HIJACK` opcode `0x9805` exists only in patched firmware. Property `0xD406` caused camera crashes in a buffer-overflow test. Neither may be used or targeted by Frameport.

