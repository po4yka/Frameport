# fujihack — Fujifilm Camera PTP Opcodes, Capability Tables, and Firmware Internals (C)

**Languages:** C (primary), Python (tooling), ARM assembly (firmware patches), Rust (experimental)
**License:** GNU GPL v3.0 (fujihack core); camlib submodule Apache-2.0
**Repository role for Frameport:** fujihack is an open-source, community-maintained firmware-hacking project for Fujifilm X-series cameras. It contains a complete, working PTP/IP and PTP/USB implementation (the "camlib" submodule, Apache-2.0) plus a small set of Fujifilm vendor-specific PTP opcodes observed through USB analysis. Its primary value for Frameport is as a cross-reference for (a) the exact PTP/IP two-channel handshake sequence, (b) packet-type constants and their field layouts, (c) the standard ISO 15740 opcode/property/event code table as actually exercised against real Fuji hardware, and (d) a per-model capability matrix (BLE support, remote capture) covering models through the X-T4/X100V generation. Frameport must extract only protocol facts and constants; no GPL-licensed C code may be copied under any circumstances.

---

## Protocol Layers

### PTP-IP (coverage: full)

**Key files:** `usb/camlib/src/ip.c`, `usb/camlib/src/transport.c`, `usb/camlib/src/packet.c`, `usb/camlib/src/ptp.h`, `usb/camlib/docs/ptp.md`

Complete PTP/IP implementation targeting ISO-defined TCP port 15740. The architecture uses two separate TCP connections to the same port: a command channel (opened first, full handshake) and an event channel (opened second, lighter handshake). Handshake sequence on the command channel: `PTPIP_INIT_COMMAND_REQ` (type 0x1) → camera replies `PTPIP_INIT_COMMAND_ACK` (0x2) or `PTPIP_INIT_FAIL` (0x5). Then a second connection for events: `PTPIP_INIT_EVENT_REQ` (type 0x3) → `PTPIP_INIT_EVENT_ACK` (0x4). Packet types 0x1–0xE are defined. The data phase for commands that return data uses a `StartData` (0x9) + `EndData` (0xC) + `Response` (0x7) triple. The `data_phase` field in a command request encodes direction: 1 = no data, 2 = initiator-to-responder data. `TCP_NODELAY` and `SO_KEEPALIVE` are set on all sockets; connection establishment uses a 1-second non-blocking select() timeout before reverting to blocking I/O.

### PTP-USB (coverage: partial)

**Key files:** `usb/camlib/src/transport.c`, `usb/camlib/src/packet.c`, `usb/camlib/src/ptp.h`, `usb/camlib/src/libusb.c`

Standard ISO 15740 USB bulk transfer using a `BulkContainer` format. Fields: 4-byte length, 2-byte type, 2-byte code, 4-byte transaction ID, up to 5 × 4-byte parameters. USB Class ID is 0x6. Reads are performed in 512-byte chunks. The MTP request codes defined include: CANCEL = 0x64, GET_EXT_EVENT_DATA = 0x65, RESET = 0x66, STATUS = 0x67.

### Liveview Format (coverage: reference-only)

**Key files:** `usb/camlib/src/liveview.c`

No Fuji-specific liveview implementation exists in this codebase. The `liveview.c` file implements Canon/EOS and Magic Lantern variants only. Fuji liveview protocol details must be sourced from other references.

### Transfer (coverage: partial)

**Key files:** `usb/ptp.c`, `usb/camlib/src/operations.c`

Standard PTP object transfer via `GetObject` (0x1009), `GetThumb` (0x100A), and `GetPartialObject` (0x101B) for chunked download. Two Fuji vendor opcodes for file upload observed over USB: `FUJI_CREATE_FILE` (0x900C) accepts an ObjectInfo-style payload; `FUJI_WRITE_FILE` (0x901D) sends file content. A third opcode 0x900D is noted as behaving similarly to 0x901D.

### Discovery (coverage: reference-only)

**Key files:** `usb/camlib/src/lib.c`, `usb/camlib/src/operations.c`

Camera identification by manufacturer string: `GetDeviceInfo` returns a manufacturer field; the string `"FUJIFILM"` (exact) maps to the internal `PTP_DEV_FUJI = 5` enum. `GetDeviceInfo` (0x1001) is always the first call after connecting; it returns the supported-operations list, which camlib uses to probe capabilities via `ptp_check_opcode()`.

### Camera Properties (coverage: partial)

**Key files:** `usb/camlib/src/ptp.h`, `usb/ptp.c`

ISO 15740 standard device property codes 0x5001–0x502C are fully defined. No Fuji vendor property codes in the 0xDxxx range are named with a `FUJI_` prefix in any camlib header. Standard operations `GetDevicePropValue` (0x1015), `SetDevicePropValue` (0x1016), and `GetDevicePropDesc` (0x1014) are used.

### Session Lifecycle (coverage: full)

**Key files:** `usb/camlib/src/operations.c`, `usb/camlib/src/ptp.h`

Full PTP session lifecycle: `OpenSession` (0x1002) with session ID starting at 1, transaction ID fixed at 0 for this call; `CloseSession` (0x1003). Transaction ID increments per command thereafter. `GetDeviceInfo` may be called without an open session. Standard PTP response codes 0x2000–0x2020 are fully defined.

---

## Protocol Facts

### Category: ptp-ip-handshake

| Name | Value | Detail | Source Ref | Confidence |
|---|---|---|---|---|
| PTP/IP standard port | 15740 | ISO-defined TCP port for PTP/IP. Used as `PTP_IP_PORT` constant. Both command and event channels connect to this port. | `usb/camlib/src/ptp.h:688` | high |
| PTPIP_INIT_COMMAND_REQ packet type | 0x1 | First packet sent by initiator on the command TCP connection. Contains a 128-bit GUID (4 × uint32), device name as null-terminated UTF-16 string (8 bytes minimum in struct), and major/minor version fields (uint16 each). camlib sets all GUID fields to 0xFFFFFFFF, minor_ver=1, device_name=`'cam'` in UTF-16. | `usb/camlib/src/ptp.h:668`, `usb/camlib/src/operations.c:26-55` | high |
| PTPIP_INIT_COMMAND_ACK packet type | 0x2 | Camera response to INIT_COMMAND_REQ. If camera rejects, it sends PTPIP_INIT_FAIL (0x5) instead. | `usb/camlib/src/ptp.h:669` | high |
| PTPIP_INIT_EVENT_REQ packet type | 0x3 | Sent on the second (event) TCP connection. camlib sends a PtpIpHeader (length=12, type=0x3, params[0]=1). The ACK response is 8 bytes. | `usb/camlib/src/ptp.h:670`, `usb/camlib/src/operations.c:68-83` | high |
| PTPIP_INIT_EVENT_ACK packet type | 0x4 | Camera response to INIT_EVENT_REQ on the event channel. | `usb/camlib/src/ptp.h:671` | high |
| PTPIP_INIT_FAIL packet type | 0x5 | Camera sends this in response to INIT_COMMAND_REQ when it rejects the initiator. camlib returns PTP_CHECK_CODE on receipt. | `usb/camlib/src/ptp.h:674`, `usb/camlib/src/operations.c:51-53` | high |
| OpenSession transaction ID rule | 0 | PTP OpenSession (0x1002) must always use transaction ID 0. camlib explicitly sets `r->transaction = 0` before the OpenSession call. Session ID is an incrementing counter starting at 1. | `usb/camlib/src/operations.c:85-98` | high |

### Category: ptp-container

| Name | Value | Detail | Source Ref | Confidence |
|---|---|---|---|---|
| PTPIP_COMMAND_REQUEST packet type | 0x6 | PTP-IP command request packet. Layout: uint32 length, uint32 type, uint32 data_phase (1=no data, 2=initiator sends data), uint16 code, uint32 transaction, uint32 params[5]. Total base size = 18 + (4 × param_count) bytes. | `usb/camlib/src/ptp.h:47-55`, `usb/camlib/src/packet.c:144-167` | high |
| PTPIP_COMMAND_RESPONSE packet type | 0x7 | Response packet from camera. Layout: uint32 length, uint32 type, uint16 code (response/return code), uint32 transaction, uint32 params[5]. Minimum 14 bytes. Param count = (length - 14) / 4. | `usb/camlib/src/ptp.h:56-63`, `usb/camlib/src/packet.c:331-345` | high |
| PTPIP_DATA_PACKET_START packet type | 0x9 | Precedes data transfer in PTP-IP. Layout: uint32 length (fixed 0x20 = 32 bytes), uint32 type, uint32 transaction, uint64 data_phase_length (total bytes of payload to follow). | `usb/camlib/src/ptp.h:64-69`, `usb/camlib/src/packet.c:169-177` | high |
| PTPIP_DATA_PACKET (intermediate) packet type | 0xA | Intermediate data packet for multi-chunk transfers. Defined in the packet type enum. | `usb/camlib/src/ptp.h:679` | high |
| PTPIP_CANCEL_TRANSACTION packet type | 0xB | Cancel transaction packet type code. | `usb/camlib/src/ptp.h:680` | high |
| PTPIP_DATA_PACKET_END packet type | 0xC | Final data packet containing the actual payload. Layout: uint32 length, uint32 type, uint32 transaction, then payload bytes. Payload starts at offset 12 from packet start. Length = 12 + data_length. | `usb/camlib/src/ptp.h:681`, `usb/camlib/src/packet.c:179-188` | high |
| PTPIP_PING and PTPIP_PONG packet types | 0xD, 0xE | Keep-alive ping/pong packet type codes used on the PTP-IP connection. | `usb/camlib/src/ptp.h:682-683` | high |
| PTP-IP response sequence for data-returning commands | (sequence) | When a command returns data, the camera sends: (1) PTPIP_DATA_PACKET_START (0x9), then (2) PTPIP_DATA_PACKET_END (0xC) containing the payload, then (3) PTPIP_COMMAND_RESPONSE (0x7). If no data is returned, only PTPIP_COMMAND_RESPONSE is sent. | `usb/camlib/src/transport.c:126-162`, `usb/camlib/docs/ptp.md:26-36` | high |
| USB BulkContainer layout | (compound) | USB-only: uint32 length, uint16 type, uint16 code, uint32 transaction, uint32 params[5]. Base command packet size = 12 + (4 × param_count). Type values: COMMAND=0x1, DATA=0x2, RESPONSE=0x3, EVENT=0x4. | `usb/camlib/src/ptp.h:18-29` | high |
| PTP string encoding | (compound) | Standard PTP strings are UTF-16LE: uint8 length (character count, not byte count), followed by length×2 bytes of UTF-16 code units. In PTP-IP init packets, device name is null-terminated UTF-16 (no length prefix). | `usb/camlib/src/packet.c:21-87` | high |

### Category: opcode

| Name | Value | Detail | Source Ref | Confidence |
|---|---|---|---|---|
| GetDeviceInfo | 0x1001 | Standard PTP. No session required. Returns device info including manufacturer string (`'FUJIFILM'` for Fuji cameras), supported operations list, supported properties list, and extensions string. Always the first probe after connecting. | `usb/camlib/src/ptp.h:90`, `usb/camlib/src/operations.c:107-115` | high |
| OpenSession | 0x1002 | Standard PTP. Param[0] = session ID. Transaction ID must be 0 for this call. Must be called before any other operations (except GetDeviceInfo). | `usb/camlib/src/ptp.h:91`, `usb/camlib/src/operations.c:85-98` | high |
| CloseSession | 0x1003 | Standard PTP. No parameters. | `usb/camlib/src/ptp.h:92`, `usb/camlib/src/operations.c:100-105` | high |
| GetStorageIDs | 0x1004 | Standard PTP. Returns array of storage IDs. First storage ID (index 0) used for object enumeration. | `usb/camlib/src/ptp.h:93` | high |
| GetStorageInfo | 0x1005 | Standard PTP. Param[0] = storage ID. | `usb/camlib/src/ptp.h:94` | high |
| GetNumObjects | 0x1006 | Standard PTP. | `usb/camlib/src/ptp.h:95` | high |
| GetObjectHandles | 0x1007 | Standard PTP. Param[0]=storage ID, param[1]=object format filter (0=all), param[2]=parent object handle (0=recursive/all). Returns array of uint32 handles. | `usb/camlib/src/ptp.h:96`, `usb/camlib/src/operations.c` | high |
| GetObjectInfo | 0x1008 | Standard PTP. Param[0]=object handle. Returns ObjectInfo structure including filename, object format code, size, parent handle, storage ID. | `usb/camlib/src/ptp.h:97` | high |
| GetObject | 0x1009 | Standard PTP. Param[0]=object handle. Returns full object data. | `usb/camlib/src/ptp.h:98` | high |
| GetThumb | 0x100A | Standard PTP. Param[0]=object handle. Returns JPEG thumbnail data directly accessible from payload. | `usb/camlib/src/ptp.h:99`, `usb/camlib/src/cl_ops.h:81` | high |
| DeleteObject | 0x100B | Standard PTP. Param[0]=handle, param[1]=format code. | `usb/camlib/src/ptp.h:100` | high |
| SendObjectInfo | 0x100C | Standard PTP. Param[0]=storage ID, param[1]=parent handle. Sends ObjectInfo data packet first, before SendObject. | `usb/camlib/src/ptp.h:101` | high |
| SendObject | 0x100D | Standard PTP. Sends object data after SendObjectInfo. No parameters. | `usb/camlib/src/ptp.h:102` | high |
| InitiateCapture | 0x100E | Standard PTP. Param[0]=storage ID, param[1]=object format. | `usb/camlib/src/ptp.h:103` | high |
| GetDevicePropDesc | 0x1014 | Standard PTP. Param[0]=property code. Returns property descriptor including data type, get/set flag, current value, and either range or enumeration form of valid values. | `usb/camlib/src/ptp.h:109` | high |
| GetDevicePropValue | 0x1015 | Standard PTP. Param[0]=property code. | `usb/camlib/src/ptp.h:110` | high |
| SetDevicePropValue | 0x1016 | Standard PTP. Param[0]=property code. Data payload contains new value. | `usb/camlib/src/ptp.h:111` | high |
| GetPartialObject | 0x101B | Standard PTP. Used by camlib `ptp_download_object` for chunked transfer. Param[0]=handle, param[1]=offset, param[2]=max bytes. | `usb/camlib/src/ptp.h:116`, `usb/camlib/src/cl_ops.h:85` | high |
| FUJI_CREATE_FILE (Fuji vendor) | 0x900C | Fuji vendor opcode. Takes an ObjectInfo-style data payload: uint32 storage_id, uint32 object_format (0x3002 = Script), uint32 file_size, 40 bytes padding, then filename as null-terminated UTF-16. Used by fujihack to upload files (specifically AUTO_ACT.SCR) to the camera over USB. Observed on X-A2 and other models patched by fujihack. | `usb/ptp.c:7,130-151` | high |
| FUJI_UNKNOWN1 (Fuji vendor) | 0x900D | Fuji vendor opcode. Noted as "seems to be mostly similar to 901d" (FUJI_WRITE_FILE). Exact semantics unclear. | `usb/ptp.c:8` | low |
| FUJI_WRITE_FILE (Fuji vendor) | 0x901D | Fuji vendor opcode. Sends file content data payload to camera. Used after FUJI_CREATE_FILE (0x900C) to write the actual file bytes. | `usb/ptp.c:9,155-163` | high |
| FUJI_HIJACK (firmware research only) | 0x9805 | **FIRMWARE RESEARCH OPCODE ONLY — NOT a legitimate app-visible operation.** This opcode exists only in firmware patched by the fujihack PTP debugger patch. It is NOT present in stock Fujifilm firmware. Sub-commands: FH_ZERO=4 (zero a byte), FH_WRITE=5 (write a byte), FH_EXEC=6 (execute), FH_RESET=7 (reset), FH_SETADDR=8 (set address), FH_GET=9 (read 32-bit value from address). Used to write and execute arbitrary ARM code via USB. Note: 0x9805 also coincides with the MTP opcode `PTP_OC_MTP_GetObjPropList` — this collision is coincidental and context-dependent. Frameport must never target this opcode. | `usb/ptp.c:11-28` | high |

### Category: property-code

| Name | Value | Detail | Source Ref | Confidence |
|---|---|---|---|---|
| BatteryLevel | 0x5001 | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:507` | high |
| FunctionalMode | 0x5002 | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:508` | high |
| ImageSize | 0x5003 | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:509` | high |
| WhiteBalance | 0x5005 | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:511` | high |
| FNumber | 0x5007 | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:513` | high |
| FocusMode | 0x500A | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:516` | high |
| FlashMode | 0x500C | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:518` | high |
| ExposureTime | 0x500D | Standard ISO 15740 device property (shutter speed). | `usb/camlib/src/ptp.h:519` | high |
| ExposureProgramMode | 0x500E | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:520` | high |
| ExposureIndex (ISO) | 0x500F | Standard ISO 15740 device property (ISO sensitivity). | `usb/camlib/src/ptp.h:521` | high |
| ExposureBiasCompensation | 0x5010 | Standard ISO 15740 device property (exposure compensation). | `usb/camlib/src/ptp.h:522` | high |
| DateTime | 0x5011 | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:523` | high |
| StillCaptureMode | 0x5013 | Standard ISO 15740 device property. | `usb/camlib/src/ptp.h:525` | high |
| Unknown vendor property used in buffer-overflow test (research only) | 0xD406 | **FIRMWARE RESEARCH ONLY.** Appeared in a commented-out buffer-overflow crash test in fujihack source, not a documented legitimate property. Sending large payloads to this property code crashed tested cameras. Must not be used in Frameport. | `usb/ptp.c:210-212` | low |

### Category: event-code

| Name | Value | Detail | Source Ref | Confidence |
|---|---|---|---|---|
| ObjectAdded | 0x4002 | Standard PTP event. Camera signals a new object was added (e.g., photo captured). | `usb/camlib/src/ptp.h:236` | high |
| ObjectRemoved | 0x4003 | Standard PTP event. | `usb/camlib/src/ptp.h:237` | high |
| DevicePropChanged | 0x4006 | Standard PTP event. Camera notifies that a device property value changed. | `usb/camlib/src/ptp.h:240` | high |
| RequestObjectTransfer | 0x4009 | Standard PTP event. Camera requests initiator to pull an object. | `usb/camlib/src/ptp.h:244` | high |
| CaptureComplete | 0x400D | Standard PTP event. Camera signals capture is done. | `usb/camlib/src/ptp.h:248` | high |

### Category: other

| Name | Value | Detail | Source Ref | Confidence |
|---|---|---|---|---|
| PTP device type identifier: FUJIFILM manufacturer string | `"FUJIFILM"` | camlib identifies Fuji cameras by checking `DeviceInfo.manufacturer == "FUJIFILM"` (exact string match). This maps to `PTP_DEV_FUJI = 5` in the enum. No Fuji-specific vendor operation codes are registered in camlib's `enum_dump` — Fuji is not explicitly handled beyond manufacturer detection. | `usb/camlib/src/lib.c:253-254`, `usb/camlib/src/camlib.h:65` | high |
| Fuji capability matrix: Bluetooth generation split | (table) | Pre-2018 X-series (X-T2, X-T10, X-T20, X-T1, X100F, X-A1, X-A2, X-E2, X-E2S, X-M1, X-Pro2, X30, X70, GFX 50S) have NO Bluetooth. Post-2018 models with BLE: X-T3, X-T30, X-T4, X-H1, X-E3, GFX 50R, GFX 100, X-A5, X-A7, X-Pro3, X100V, X-T200, XF10. The X-T5 (Frameport primary target) is not in the list but follows the same BLE-capable generation as X-T4. | `etc/models.h:1-66`, `etc/models.json:1-394` | medium |
| Fuji capability matrix: remote capture support | (table) | `capture_support=true` models (remote shutter supported): X-T3, X-T30, X-T4, X-H1, X-E3, GFX 50R, GFX 100, X-A7, X-Pro3, X100V, X-T200, XF10, FinePix XP140/XP141. `capture_support=false` (no remote capture): X-T2, X-T10, X-T20, X-T1, X100F, X-A1, X-A2, X-A3, X-A5, X-A10, X-E2, X-E2S, X-M1, X-Pro2, X30, X70, X100T, GFX 50S, X-T100, FinePix XP130. | `etc/models.h:1-66` | medium |
| Firmware internal: Fuji PTP dispatch function address (X-T20 fw 2.10) | 0x00ecbb94 | **FIRMWARE INTERNAL ONLY.** The firmware address of the PTP opcode 0x9805 handler in X-T20 firmware version 2.10. Not useful for app development. Included to document that the PTP dispatch table is a single function dispatching on opcode. | `model/xt20_210.h:5` | high |
| Firmware internal: Fuji PTP finish function address (X-T20 fw 2.10) | 0x00ed2b68 | **FIRMWARE INTERNAL ONLY.** Address of `fuji_ptp_finish()` — called by each PTP handler to send the response. Signature: `fuji_ptp_finish(params, retcode, retcode2)` where retcode2 is generally 0. | `model/xt20_210.h:6`, `src/ff_ptp.h:37` | high |
| Firmware internal: Fuji PTP dispatch max entries (X-T20 fw 2.10) | 2000 | **FIRMWARE INTERNAL ONLY.** `FIRM_PTP_MAX=2000` for X-T20 fw 2.10, indicating the camera's PTP dispatch table has up to 2000 opcode handler slots. | `model/xt20_210.h:7` | medium |
| Firmware internal: PTP handler calling convention | (compound) | **FIRMWARE INTERNAL ONLY.** Each PTP opcode handler on Fuji cameras takes `(uint8 mode, FujiPTPParams*, FujiPTPData*)`. The params struct has: uint32 code, uint32 transid, uint32 sessionid, uint32 length, uint32 params[5], void* payload_ptr, uint32 payload_length. Response struct has: uint32 code, uint32 transid, uint32 sessionid, uint32 nparam, uint32 param1/2/3. | `src/ff_ptp.h:8-38` | high |
| Object format code: Script | 0x3002 | Used in FUJI_CREATE_FILE (0x900C) payload to indicate script file type. Same as standard PTP `PTP_OF_Script`. | `usb/ptp.c:135`, `usb/camlib/src/ptp.h:285` | high |
| PTP property descriptor form types | 0x1 (Range), 0x2 (Enumeration) | Used in GetDevicePropDesc response to indicate whether valid values are a range or enumeration list. | `usb/camlib/src/ptp.h:664-665` | high |
| PTP data type codes | 0x1=INT8, 0x2=UINT8, 0x3=INT16, 0x4=UINT16, 0x5=INT32, 0x6=UINT32, 0x7=INT64, 0x8=UINT64, 0xFFFF=STRING | Used in property descriptors to indicate the data type of a property value. | `usb/camlib/src/ptp.h:646-662` | high |
| PTP USB Class ID | 0x6 | USB Device Class ID for PTP/MTP devices. Standard value per USB specification. | `usb/camlib/src/ptp.h:691` | high |
| TCP socket options for PTP-IP | TCP_NODELAY=1, SO_KEEPALIVE=1 | camlib sets `TCP_NODELAY` (disable Nagle) and `SO_KEEPALIVE` on all PTP-IP sockets. Non-blocking connect with 1-second `select()` timeout for initial connection establishment. Reverts to blocking after connect. | `usb/camlib/src/ip.c:44-58,96-116` | high |

---

## Frameport Mapping

| Frameport Target | What to Borrow | Clean-Room Note |
|---|---|---|
| `fuji-ptpip` | PTP-IP packet type constants (0x1–0xE), two-connection handshake sequence (command then event), INIT_COMMAND_REQ layout with GUID + device_name + version fields, INIT_EVENT_REQ sending connection_number=1 and expecting 8-byte ACK, data_phase field semantics (1=no data, 2=H2D data), StartData/EndData/Response triple for data-returning commands. | Define your own Rust structs for each packet type with the documented field names and sizes. The field order and sizes are protocol facts (GUID=4×u32, device_name=null-terminated UTF-16, version=2×u16). The two-socket model and packet-type dispatch are architectural facts, not copyrightable expression — implement from scratch in `fuji-ptpip/src/`. |
| `fuji-ptpip` | TCP socket setup: TCP_NODELAY + SO_KEEPALIVE, non-blocking connect with 1-second timeout via `select()`, revert to blocking after handshake. Port 15740 is the ISO-defined PTP/IP port. | In Rust, use `tokio::net::TcpStream` with connect timeout or a manual timeout future. Set TCP_NODELAY via `set_nodelay(true)`. SO_KEEPALIVE via the `socket2` crate. These are OS/protocol facts, not code. |
| `fuji-ptpip` | Packet read loop pattern: read exactly 4 bytes first to get the length field, then read (length - 4) remaining bytes. This avoids blocking indefinitely on reads of unknown length. | Implement as a two-phase async read: `read_exact(4)` → parse length → `read_exact(length-4)`. The pattern is derived from the packet format specification, not from the C code itself. |
| `fuji-transfer` | Standard transfer sequence: GetStorageIDs → GetObjectHandles(storage_id, format=0, parent=0) → GetObjectInfo(handle) → GetThumb(handle) for thumbnail → GetPartialObject(handle, offset, max) for chunked download. Object format codes: JPEG=0x3801, RAF=0xB103 (shared across vendors). | These are ISO 15740 standard operations. Implement the sequence in `fuji-transfer` using typed Rust structs for ObjectInfo. The chunked GetPartialObject loop is a protocol pattern derivable from the standard. |
| `fuji-transfer` | Fuji vendor opcodes for file upload: FUJI_CREATE_FILE (0x900C) ObjectInfo payload layout (storage_id u32, format u32, size u32, 40-byte padding, filename as null-terminated UTF-16), then FUJI_WRITE_FILE (0x901D) with file content. Opcode 0x900D is similar to 0x901D but semantics unclear. | These opcode values are interoperability facts. The ObjectInfo payload layout for 0x900C is described in field terms: first 12 bytes = 3 u32 fields, 40 bytes zero padding, then UTF-16 filename. Implement a builder for this payload in `fuji-transfer`. Note: these opcodes were observed by fujihack from the USB side and may only be relevant for USB mode. |
| `fuji-ptp` | Complete set of standard PTP opcode values (0x1001–0x101C), response codes (0x2000–0x2020), event codes (0x4000–0x400E), object format codes (0x3000–0x3810, 0xB103 for RAW/RAF), property codes (0x5001–0x502C), data type codes (0x1–0xA, 0xFFFF), property descriptor form types (0x1=Range, 0x2=Enumeration), and association types. | All values are from ISO 15740 and are protocol facts, not creative expression. Define them as Rust constants in `fuji-ptp/src/constants.rs` with doc comments citing their ISO section. |
| `fuji-ptp` | PTP transaction lifecycle rules: GetDeviceInfo needs no open session; OpenSession uses transaction ID 0; all other operations use an incrementing transaction counter; CloseSession gracefully terminates. Camera manufacturer string `"FUJIFILM"` is the detection key from GetDeviceInfo. | These are protocol state machine rules. Encode them as a session state enum in `fuji-ptp` (Disconnected → Connected → SessionOpen) with type-level enforcement so callers cannot issue session-dependent ops before OpenSession. |
| `camera/diagnostics` | Per-model capability matrix from `etc/models.h` / `models.json`: which Fuji camera models have Bluetooth, which support remote capture, which support firmware update. The split between pre-2018 (no BLE) and post-2018 (BLE) models is a verified interoperability fact. | Reconstruct from primary sources (Fujifilm product pages, official compatibility docs) rather than copying the JSON directly. The fujihack matrix is reverse-engineered and may have inaccuracies. Use it only as a cross-check. Encode in `docs/protocol/compatibility-matrix.md` and a Rust enum or const table, not by copying the JSON. |

---

## Standout Findings

- fujihack contains NO Fuji-specific named vendor property codes (0xDxxx range) in any header. The `ptp.h` file only defines Canon/EOS/Nikon vendor codes. The only Fuji vendor items found are three opcodes (0x900C, 0x900D, 0x901D for file upload) and the firmware-research-only hijack opcode 0x9805. Fuji vendor device property codes must be sourced from other references (libgphoto2, gphoto2's `fuji.c`, or other community reverse-engineering projects).
- The FUJI_HIJACK opcode 0x9805 exists ONLY in patched firmware. It is not present in stock Fujifilm cameras and must never be targeted by Frameport. Confusingly, 0x9805 also coincides with the MTP opcode `PTP_OC_MTP_GetObjPropList` — this collision is coincidental and context-dependent.
- Property 0xD406 appeared only in a commented-out buffer-overflow crash test (`usb/ptp.c` lines 210-212). It caused camera crashes when large payloads were sent. This is not a legitimate app-facing property and must not be used by Frameport.
- The MODEL_CODE strings in `model/*.h` files are firmware-patcher byte-pattern identifiers used by the fujihack web patcher to locate specific code locations in firmware images. They are NOT PTP opcode or property code lists and have no relevance to app-layer PTP communication.
- PTP-IP requires two separate TCP connections to the same port (15740): one for commands (opened first, full handshake) and one for events (opened second, lighter handshake sending connection_number=1). Events are received asynchronously on the second socket.
- The camera compatibility matrix (`etc/models.h`, `models.json`) clearly shows Bluetooth was introduced in the X-series from X-T3/X-E3/X-H1 generation onward (2018+). The X-T4 (the nearest Frameport-confirmed-capable model) has BLE, GPS, capture, and firmware-update support confirmed in this dataset.
- camlib detects Fuji cameras purely by GetDeviceInfo manufacturer string match (`"FUJIFILM"`). No Fuji-specific capability probing or custom session init was found — Fuji cameras appear to use the standard PTP session lifecycle with vendor opcodes layered on top.
- The Fuji camera-side PTP handler architecture (from `src/ff_ptp.h`): each opcode maps to a handler called as `handler(mode, FujiPTPParams*, FujiPTPData*)`. The params struct exposes transid, sessionid, up to 5 parameters, and a payload pointer. This is the camera-internal firmware ABI, not the wire format, and is firmware-internal only — but it confirms the camera dispatches on opcode via a single function with a table of up to 2000 entries.

---

## Caveats / IP & License Risk

- **FIRMWARE-INTERNAL FACTS:** Items marked "FIRMWARE INTERNAL ONLY" — including FUJI_HIJACK 0x9805 sub-commands, firmware function addresses in `model/*.h`, the PTP handler calling convention in `src/ff_ptp.h`, `FIRM_PTP_MAX`, and the `fuji_ptp_finish` address — describe the camera's internal firmware structure. They are only visible in patched firmware and are irrelevant to app-layer PTP communication. Frameport must never attempt to use opcode 0x9805.
- **NO FUJI VENDOR PROPERTY CODES FOUND:** This repository does not define any Fuji vendor device property codes in the 0xDxxx range. The camlib `ptp.h` only defines Canon/EOS/Nikon vendor properties. Fuji vendor property codes (e.g., for film simulation, remote control settings) must be sourced from other references.
- **LIVEVIEW NOT COVERED:** The fujihack/camlib codebase has no Fuji-specific liveview implementation. The `liveview.c` only handles Canon/EOS and Magic Lantern variants. Fuji liveview protocol details must be sourced elsewhere.
- **MODEL_CODE IS NOT A PTP CODE LIST:** The MODEL_CODE `#define` strings in `model/*.h` are firmware binary pattern identifiers for the fujihack web patcher. They are not lists of supported PTP opcodes or property codes.
- **COMPATIBILITY MATRIX CONFIDENCE:** The `etc/models.h` and `models.json` capability matrix is reverse-engineered by the fujihack community and may not be complete or fully accurate. The X-T5 (Frameport primary target) does not appear in the matrix — it was released after the dataset was assembled. Treat as indicative, not authoritative.
- **LICENSE:** fujihack is licensed under GNU GPL v3.0. No code may be copied into Frameport (Apache-2.0). Protocol constants (PTP opcode numbers, property codes) are ISO 15740 facts and are not copyrightable. The camlib submodule is Apache-2.0 licensed, but again — no code copying, only knowledge extraction.
- **STALENESS:** The fujihack repository covers camera models through the X-T4/X100V/GFX 100 generation. The X-T5 (released 2022) is not present. Protocol behavior on the X-T5 may differ, particularly for newer BLE pairing protocols and any firmware changes introduced post-2020.

