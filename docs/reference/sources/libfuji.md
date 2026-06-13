# libfuji (C) — Multi-Transport Fujifilm PTP Protocol Reference

**Source project:** libfuji (with libpict submodule)
**Language:** C
**Source refs in this document:** `lib/fuji.c`, `lib/fuji.h`, `lib/fuji_usb.c`, `lib/fujiptp.h`, `lib/bluetooth.c`, `lib/discovery.c`, `libpict/src/ip.c`, `libpict/src/packet.c`, `libpict/src/transport.c`, `libpict/src/ptp.h`, `fp/src/fp.h`, `fp/src/d185.c`

**Role in Frameport:** libfuji is the most complete publicly available C implementation of the Fujifilm camera protocol stack, covering PTP-IP over TCP, USB PTP bulk transport, BLE pairing and geolocation, Wi-Fi camera discovery (three sub-protocols), remote mode / live-view socket sequencing, chunked file transfer, session state-machine lifecycle, and the D185 binary film-simulation profile codec. Frameport uses this source exclusively as a clean-room interoperability reference — reading packet field widths, port numbers, opcode constants, GATT UUIDs, and protocol sequences as factual wire-format data — while implementing all production code in Rust and Kotlin from scratch without reproducing any C function bodies or struct definitions verbatim.

---

## 1. Protocol Layers

### 1.1 PTP-IP
**Coverage:** full
**Key files:** `libpict/src/ip.c`, `libpict/src/packet.c`, `libpict/src/transport.c`, `libpict/src/ptp.h`, `lib/fuji.c`

Full PTP-IP transport implementation: socket connect/disconnect, a Fujifilm-specific four-phase handshake (init command req/ack, event req/ack), bulk packet send/receive, data-start/data-end/response packet sequencing, `TCP_NODELAY` required on the command socket, non-blocking connect with `select()` timeout, and a Fujifilm-proprietary init packet (82 bytes, version magic `0x8f53e4f2`, GUID + unicode device name). A non-standard 8-byte "goodbye" sentinel is sent on disconnect. Fujifilm departs from the ISO standard by using port 55740 for commands instead of the ISO 15740 port.

### 1.2 PTP-USB
**Coverage:** full
**Key files:** `lib/fuji_usb.c`, `libpict/src/ptp.h`, `libpict/src/packet.c`

USB PTP bulk container format with a 12-byte header (`length u32`, `type u16`, `code u16`, `transaction u32`) followed by up to five `u32` parameters. Fujifilm USB vendor ID is `0x04CB`. USB operating mode is discovered via property `0xD16E` (`USBMode`): value 5 = tether, 6 = raw conversion, 8 = webcam. Absence of the property indicates card-reader mode. Standard USB PTP class ID is 6. MTP control request codes: cancel `0x64`, get-ext-event-data `0x65`, reset `0x66`, status `0x67`.

### 1.3 BLE
**Coverage:** full
**Key files:** `lib/bluetooth.c`

Complete BLE pairing flow: read a 4-byte token from the manufacturer advertisement data (type byte must be `0x02`), write the token to the `CHR_PAIR` characteristic, then write an ASCII client-name string to the `CHR_IDEN` characteristic. Subscribe to five notification/indication characteristics in the `SVC_CONF` service plus the `GEOTAG_UPDATE` characteristic. Geolocation is delivered via a separate `SVC_GEOTAG`/`CHR_GEOTAG` characteristic as a packed binary struct: latitude, longitude, and altitude as `int32` values scaled by 10,000,000 (lat/lon) and in millimetres (alt), four pad bytes, then a packed time struct (`year u16`, `month/day/hour/min/sec u8` each).

### 1.4 Liveview
**Coverage:** partial
**Key files:** `lib/fuji.c`, `lib/fujiptp.h`, `libpict/src/ip.c`

Remote live-view uses a dedicated TCP connection to port 55742 (`FUJI_LIVEVIEW_IP_PORT`), opened after `PTP_OC_InitiateOpenCapture` (`0x101C`) triggers remote mode. The video socket (`vidfd`) in `PtpCommPriv` carries an MJPEG stream. A separate event socket connects to port 55741. Both sockets are opened after `InitiateOpenCapture` and closed via `TerminateOpenCapture`. The `libpict` liveview parser only covers Canon/EOS and is not used for Fujifilm; the per-frame MJPEG framing requires additional reverse-engineering.

### 1.5 Transfer
**Coverage:** full
**Key files:** `lib/fuji.c`, `lib/fuji.h`, `lib/fuji_usb.c`

File transfer uses `GetPartialObject` (`0x101B`) in 1 MB (`0x100000`) chunks, looped until the object is fully downloaded. Property `PTP_DPC_FUJI_EnableCorrectFileSize` (`0xD227`) must be set to 1 before `GetObjectInfo`/`GetObject` calls to receive accurate file sizes; it is reset to 0 after transfer. Setting `PTP_DPC_FUJI_CompressSmall` (`0xD226`) to 1 requests 400–800 KB preview downloads. `fuji_get_events()` (reads `EventsList` `0xD212`) must be called at regular intervals during transfers to keep the connection alive. For Wi-Fi transfers, object handles are sequential integers starting at 1 rather than values returned by `GetObjectHandles`.

### 1.6 Discovery
**Coverage:** full
**Key files:** `lib/discovery.c`

Three-protocol discovery: (1) **PC AutoSave** — UDP on ports 51542 (register) and 51541 (connect); camera sends `DISCOVER`/`DSCADDR` datagrams; client connects back to camera TCP port 51540 (`NOTIFY`) then listens for camera HTTP invites. (2) **Wireless Tether** — client listens on TCP port 51560; camera connects and sends `DSC`/`CAMERANAME`/`DSCPORT` headers. (3) **PCSS broadcast** — client sends UDP discovery to `192.168.1.255:51562`. All three handshakes use HTTP/1.1-style line-delimited text framing.

### 1.7 Film Simulation
**Coverage:** full
**Key files:** `fp/src/fp.h`, `fp/src/d185.c`, `lib/fujiptp.h`

Film simulation profiles are transmitted via PTP property `0xD185` (`RawConvProfile`) as a binary structure: a `u16` property count, a 511-byte `iop_codes` block (MTP string encoding), then up to `0x1D` `u32` property values. Total observed sizes: 601 bytes from camera (X-H1), 629 bytes from X Raw Studio. Film simulation codes span Provia (`1`) through Eterna (`0x10`). Adjustment range values use two's-complement 32-bit integers in units of 10 (e.g. +4 = 40, −0.5 = `0xFFFFFFFB`).

### 1.8 Camera Properties
**Coverage:** full
**Key files:** `lib/fujiptp.h`, `lib/fuji.c`

Fujifilm vendor property codes span `0xD001`–`0xDF44`. The session state machine is driven by reading `PTP_DPC_FUJI_CameraState` (`0xDF00`) and writing `PTP_DPC_FUJI_ClientState` (`0xDF01`). Version negotiation: read `GetObjectVersion` (`0xDF22`) and `RemoteVersion` (`0xDF24`), then re-write the same or an upgraded value to confirm support. Events are polled by calling `GetPropValue` on `EventsList` (`0xD212`), which returns an array of `(code u16, value u32)` pairs. Camera state values: 0 = wait, 1 = multiple-transfer, 2 = full-access, 3 = PC-autosave, 6 = remote-access.

---

## 2. Protocol Facts

All 63 facts are tabulated below, grouped by category. Confidence levels reflect the survey's assessment: **high** = confirmed from well-labeled source code; **medium** = inferred or noted as potentially inaccurate in comments.

### 2.1 PTP-IP Handshake (12 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Fuji PTP-IP command port | `55740` | Fujifilm cameras listen for PTP-IP command connections on TCP port 55740, not the ISO standard 15740. | `lib/fujiptp.h:30` | high |
| Fuji PTP-IP event port | `55741` | Separate event socket connects to TCP port 55741. | `lib/fujiptp.h:31` | high |
| Fuji liveview/video port | `55742` | Live-view (MJPEG stream) socket connects to TCP port 55742. | `lib/fujiptp.h:32` | high |
| Fuji protocol version magic | `0x8f53e4f2` | The `FujiInitPacket` version field is always set to `0x8f53e4f2`. This is Fuji's proprietary protocol version identifier, sent in the `PTPIP_INIT_COMMAND_REQ` packet instead of a standard PTP-IP version. Cameras reject handshakes that present any other value here. | `lib/fujiptp.h:6` | high |
| FujiInitPacket size | `0x52` (82 decimal) | The init request packet is exactly 82 bytes: 4-byte length field set to `0x52`, 4-byte type (`PTPIP_INIT_COMMAND_REQ=1`), 4-byte version (`0x8f53e4f2`), four 4-byte GUID words (16 bytes total), then 54 bytes of unicode device name. | `lib/fujiptp.h:401-413` / `lib/fuji.c:251-263` | high |
| PTP-IP init packet type codes | `0x1`–`0x5` | `PTPIP_INIT_COMMAND_REQ=0x1`, `PTPIP_INIT_COMMAND_ACK=0x2`, `PTPIP_INIT_EVENT_REQ=0x3`, `PTPIP_INIT_EVENT_ACK=0x4`, `PTPIP_INIT_FAIL=0x5`. | `libpict/src/ptp.h:676-682` | high |
| PTP-IP operation packet type codes | `0x6`–`0xE` | `PTPIP_COMMAND_REQUEST=0x6`, `PTPIP_COMMAND_RESPONSE=0x7`, `PTPIP_EVENT=0x8`, `PTPIP_DATA_PACKET_START=0x9`, `PTPIP_DATA_PACKET=0xA`, `PTPIP_CANCEL_TRANSACTION=0xB`, `PTPIP_DATA_PACKET_END=0xC`, `PTPIP_PING=0xD`, `PTPIP_PONG=0xE`. | `libpict/src/ptp.h:682-691` | high |
| TCP_NODELAY required | _(flag)_ | PTP-IP spec requires `TCP_NODELAY=true` on the command socket. libpict sets this immediately after socket creation. | `libpict/src/ip.c:86-89` | high |
| Post-init delay for WIRELESS_COMM | `50000 microseconds` | After a successful `FujiInitPacket` exchange, Fuji cameras in `WIRELESS_COMM` mode require at least 50 ms delay before proceeding to `OpenSession`. | `lib/fuji.c:147-149` | high |
| PtpFujiInitResp size | `70` bytes | The camera's response to `FujiInitPacket` is 70 bytes: four 4-byte unknown fields (`x1`–`x4`, 16 bytes total) followed by 54 bytes of camera name as a unicode string. | `lib/fujiptp.h:416-424` | high |
| Goodbye packet on disconnect | `08 00 00 00 FF FF FF FF` | When disconnecting from a Fuji PTP-IP session, send an 8-byte goodbye packet with bytes `{0x08, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF}`. This is a non-standard Fuji extension: length field = 8, type field = `0xFFFFFFFF`. | `lib/fuji.c:52-53` | high |
| Remote mode socket open sequence | _(sequence)_ | To enter remote mode: (1) call `PTP_OC_InitiateOpenCapture` (`0x101C`) with params `(0,0)` — this signals the camera to open video and event sockets; (2) poll `fuji_get_events`; (3) connect the event socket to port 55741; (4) connect the video socket to port 55742; (5) call `PTP_OC_TerminateOpenCapture` (`0x1018`) with the original transaction ID from step 1. Connecting the sockets before `TerminateOpenCapture` causes the camera to stall. | `lib/fuji.c:704-731` | high |

### 2.2 PTP Container Formats (7 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| USB PTP bulk container layout | _(layout)_ | `PtpBulkContainer` fields in order: `length u32` (total packet bytes), `type u16` (1=cmd, 2=data, 3=resp, 4=event), `code u16` (opcode or response code), `transaction u32`, then up to 5 `u32` params (command) or payload (data). Header without params is 12 bytes; full command header with 5 params is 32 bytes. | `libpict/src/ptp.h:19-31` | high |
| PTP-IP request container layout | `18` bytes (base) | `PtpIpRequestContainer` fields: `length u32`, `type u32`, `data_phase u32` (1=no data, 2=data follows), `code u16`, `transaction u32`, `params[5] u32`. Base size without params = 18 bytes; with N params = 18 + 4×N bytes. | `libpict/src/ptp.h:47-54` / `libpict/src/packet.c:13` | high |
| PTP-IP data start packet layout | `20` bytes | `PtpIpStartDataPacket` fields: `length u32` (always 20), `type u32` (`PTPIP_DATA_PACKET_START=0x9`), `transaction u32`, `payload_length u64`. Total fixed size: 20 bytes. | `libpict/src/ptp.h:64-69` / `libpict/src/packet.c:36-43` | high |
| PTP-IP data end packet layout | `12` bytes (header) | `PtpIpEndDataPacket` fields: `length u32` (12 + data_length), `type u32` (`PTPIP_DATA_PACKET_END=0xC`), `transaction u32`. Payload immediately follows the 12-byte header. | `libpict/src/ptp.h:71-75` / `libpict/src/packet.c:45-54` | high |
| PTP-IP response container layout | `14` bytes (base) | `PtpIpResponseContainer` fields: `length u32`, `type u32`, `code u16` (response code), `transaction u32`, `params[5] u32`. Param count = `(length − 14) / 4`. | `libpict/src/ptp.h:56-62` / `libpict/src/packet.c:186` | high |
| PTP-IP receive sequence | _(sequence)_ | Receive loop: read packet 1 (always first); if type == `DATA_PACKET_START` (`0x9`), read packet 2 (data end); then read packet 3 (response). If there is no data phase, packet 1 is directly the response (`type == COMMAND_RESPONSE=0x7`). Payload of a data response starts at offset: `start_packet_length + 12` bytes into the receive buffer. | `libpict/src/packet.c:92-113` / `libpict/src/transport.c:105-120` | high |
| Max TCP packet size | `0xFFFF` (65535) | libpict sets `r->max_packet_size = 0xFFFF` for PTP-IP TCP connections. For USB RAW file sends, Fuji uses 261632. | `libpict/src/ip.c:175` | high |

### 2.3 Opcodes (5 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Standard PTP opcodes | `0x1001`–`0x101C` | `GetDeviceInfo=0x1001`, `OpenSession=0x1002`, `CloseSession=0x1003`, `GetStorageIDs=0x1004`, `GetStorageInfo=0x1005`, `GetNumObjects=0x1006`, `GetObjectHandles=0x1007`, `GetObjectInfo=0x1008`, `GetObject=0x1009`, `GetThumb=0x100A`, `DeleteObject=0x100B`, `SendObjectInfo=0x100C`, `SendObject=0x100D`, `InitiateCapture=0x100E`, `GetDevicePropDesc=0x1014`, `GetDevicePropValue=0x1015`, `SetDevicePropValue=0x1016`, `TerminateOpenCapture=0x1018`, `GetPartialObject=0x101B`, `InitiateOpenCapture=0x101C`. | `libpict/src/ptp.h:90-117` | high |
| Fuji Wi-Fi/IP opcodes | `0x9020`–`0x9042` | `InitiateMovieCapture=0x9020`, `TerminateMovieCapture=0x9021`, `GetCapturePreview=0x9022`, `StepZoom=0x9023`, `StartZoom=0x9024`, `StopZoom=0x9025`, `LockS1Lock=0x9026`, `UnlockS1Lock=0x9027`, `GetDeviceInfo=0x902B`, `StepShutterSpeed=0x902C`, `StepFNumber=0x902D`, `StepExposureBias=0x902E`, `CancelInitiateCapture=0x9030`, `FmSendObjectInfo=0x9040`, `FmSendObject=0x9041`, `FmSendPartialObject=0x9042`. | `lib/fujiptp.h:379-395` | high |
| Fuji USB/generic opcodes | `0x900C`, `0x900D`, `0x901D` | `SendObjectInfo=0x900C` (create file), `SendObject2=0x900D` (alias for `0x901D`), `SendObject=0x901D` (write to file). Unknown downloader opcodes also observed: `0x9054`, `0x9055`. | `lib/fujiptp.h:35-37` | high |
| Fuji RAW profile object format code | `0xF802` | Object format code for Fuji RAW conversion firmware/profile data is `0xF802` (not the standard PTP firmware format `0xB802`). Filename used: `FUP_FILE.dat`. | `lib/fuji_usb.c:308-309` | high |
| Fuji FFF1 object format | `0xFFF1` | `PTP_OF_FUJI_FFF1=0xFFF1` — Fujifilm-specific object format code for proprietary file types. | `lib/fujiptp.h:398` | medium |

### 2.4 Property Codes (15 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| CameraState property | `0xDF00` | `PTP_DPC_FUJI_CameraState`. Values: `0=WAIT_FOR_ACCESS`, `1=MULTIPLE_TRANSFER`, `2=FULL_ACCESS`, `3=PC_AUTO_SAVE`, `6=REMOTE_ACCESS`. Must poll via `EventsList` (`0xD212`) after `OpenSession` to determine the active connection mode. | `lib/fujiptp.h:48` / `lib/fujiptp.h:136-149` | high |
| ClientState property | `0xDF01` | `PTP_DPC_FUJI_ClientState`. Write to set the desired mode: `1=VIEW_MULTIPLE`, `2=VIEW_ALL_IMGS`, `3=OLD_REMOTE`, `5=REMOTE_MODE`, `8=MULTIPLE_TRANSFER_REQ`, `9=IMG_VIEW_IN_CAM`, `11=REMOTE_IMG_VIEW`, `17=SET_GPS`, `18=LIMITED_IMG_TRANSMISSION`, `20=REMOTE_IMG_VIEW_XAPP`. On newer cameras, writing this property triggers a user-confirmation dialog on the camera; must wait with `response_wait=255`. | `lib/fujiptp.h:49` / `lib/fujiptp.h:103-131` | high |
| EventsList property | `0xD212` | `PTP_DPC_FUJI_EventsList` (also noted as `CurrentState` at the same value). `GetPropValue` returns a `PtpFujiEvents` structure: `u16` count followed by an array of `(code u16, value u32)` pairs. This polling loop replaces waiting for unsolicited PTP events and must also be called regularly during transfers to keep the connection alive. | `lib/fujiptp.h:45` / `lib/fuji.c:498-544` | high |
| ObjectCount property | `0xD222` | `PTP_DPC_FUJI_ObjectCount`. Available via `EventsList` after session open. For Wi-Fi transfers, object handles are sequential integers `1..num_objects` rather than values returned by `GetObjectHandles`. | `lib/fujiptp.h:47` / `lib/fuji.c:529` | high |
| EnableCorrectFileSize | `0xD227` | `PTP_DPC_FUJI_EnableCorrectFileSize`. Set to `1` before `GetObjectInfo`/`GetObject` calls to receive accurate file sizes (the default reported size is a ~100 KB placeholder). Reset to `0` after download. Required for reliable transfer; on some cameras it slows `GetThumb`. | `lib/fujiptp.h:58` / `lib/fuji.c:432-440` | high |
| CompressSmall property | `0xD226` | `PTP_DPC_FUJI_CompressSmall`. Set to `1` to receive heavily compressed 400–800 KB preview images instead of full-resolution files. | `lib/fujiptp.h:51-52` | high |
| GetObjectVersion / ImageGetVersion | `0xDF22` / `0xDF21` | `PTP_DPC_FUJI_GetObjectVersion=0xDF22`, `PTP_DPC_FUJI_ImageGetVersion=0xDF21`. After reading these values, write them back unchanged (or at a higher negotiated value) to confirm client support. The camera will reject downloads if this negotiation is skipped on newer firmware. | `lib/fuji.c:583-656` | high |
| RemoteVersion property | `0xDF24` / `0x2000C` | `PTP_DPC_FUJI_RemoteVersion`. libfuji sets this to `FUJI_CAM_CONNECT_REMOTE_VER=0x2000C` (Camera Connect app version 2.11) during init. X-T20 reports `0x20004`; X-S10 can be negotiated from `0x2000A` to `0x2000B`. A returned value of −1 (property absent) means the camera does not support remote mode. | `lib/fujiptp.h:62` / `lib/fuji.c:663` | high |
| RemoteGetObjectVersion | `0xDF25` | `PTP_DPC_FUJI_RemoteGetObjectVersion`. Differentiates `GetObjectInfo`/`GetObject` behavior for cameras supporting remote-mode image view. libfuji reads the current value then sets it to `5` during remote image view setup (X-S10 and X-H1 require at least `4`). | `lib/fujiptp.h:97` / `lib/fuji.c:767-771` | high |
| USBMode property | `0xD16E` | `PTP_DPC_FUJI_USBMode`. Values: `5=USB_TETHER_SHOOT`, `6=RAW_CONV`, `8=WEBCAM`. Absence of the property (`PTP_CHECK_CODE`) indicates `USB_CARD_READER` mode. | `lib/fujiptp.h:268` / `lib/fuji_usb.c:116-133` | high |
| FilmSimulation property | `0xD001` | `PTP_DPC_FUJI_FilmSimulation`. Codes: `Provia/Standard=1`, `Velvia/Vivid=2`, `Astia/Soft=3`, `PRO Neg Hi=4`, `PRO Neg Std=5`, `Monochrome=6`, `Monochrome+Ye=7`, `Monochrome+R=8`, `Monochrome+G=9`, `Sepia=0xA`, `Classic Chrome=0xB`, `Acros=0xC`, `Acros+Ye=0xD`, `Acros+R=0xE`, `Acros+G=0xF`, `Eterna=0x10`. Also embedded in `RawConvProfile` (`0xD185`). | `lib/fujiptp.h:162` / `fp/src/fp.h:54-71` | high |
| Geolocation property (PTP/IP) | `0xD500` | `PTP_DPC_FUJI_Geolocation`. Format is an NMEA-like ASCII string: `'0000.000000,N00000.000000,E00000.00,M 000.0,K0000:00:0000:00:00.000'`. | `lib/fujiptp.h:83` | medium |
| RawConvProfile property | `0xD185` | `PTP_DPC_FUJI_RawConvProfile`. Binary structure: `u16 n_props`, 511-byte `iop_codes` block, then `n_props × 4-byte u32` values. Observed total sizes: 601 bytes from camera (X-H1, `n_props=0x17` but always padded to 601), 629 bytes from X Raw Studio (`n_props=0x1D`). | `lib/fujiptp.h:287` / `fp/src/fp.h:201-243` | high |
| BatteryInfo properties | `0xD36A` / `0xD36B` | `PTP_DPC_FUJI_BatteryInfo1=0xD36A`, `PTP_DPC_FUJI_BatteryInfo2=0xD36B`. `BatteryInfo1` returns a percentage value directly parseable via `ptp_parse_prop_value`. | `lib/fujiptp.h:357-358` / `lib/fuji_usb.c:245-253` | high |
| FreeSDRAMImages property | `0xD20E` | `PTP_DPC_FUJI_FreeSDRAMImages`. Received in events during wireless tether mode. A non-zero value signals that a new image has been captured and is available for download. | `lib/fuji.c:533-537` | high |

### 2.5 Event Codes (1 fact)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Fuji event codes | `0xC001`, `0xC004` | `PTP_EC_FUJI_PreviewAvailable=0xC001`, `PTP_EC_FUJI_ObjectAdded=0xC004`. Source comments note that "most appear to be inaccurate or misplaced" relative to libgphoto2. | `lib/fujiptp.h:159-160` | medium |

### 2.6 BLE GATT UUIDs (7 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| BLE pairing service UUID | `91f1de68-dff6-466e-8b65-ff13b0f16fb8` | `SVC_PAIR_UUID` (internal label `0x4001`). This is the Fujifilm GATT service for initiating BLE pairing. | `lib/bluetooth.c:6` | high |
| BLE pairing characteristic UUID | `aba356eb-9633-4e60-b73f-f52516dbd671` | `CHR_PAIR_UUID` (`0x4042`). Write the 4-byte token (extracted from manufacturer advertisement data) to this characteristic to initiate pairing. | `lib/bluetooth.c:8` | high |
| BLE identity/client-name characteristic UUID | `85b9163e-62d1-49ff-a6f5-054b4630d4a1` | `CHR_IDEN_UUID` (`0x4012`). Write an ASCII client-name string (e.g. `'Pixel-6a-1234'`) to this characteristic after writing the pairing token to `CHR_PAIR`. | `lib/bluetooth.c:10` | high |
| BLE configuration/subscription service UUID | `4c0020fe-f3b6-40de-acc9-77d129067b14` | `SVC_CONF_UUID`. Contains five characteristics that must be subscribed: `CHR_IND1`, `CHR_IND2`, `CHR_NOT1`, `GEOTAG_UPDATE`, `CHR_IND3`. | `lib/bluetooth.c:13` | high |
| BLE subscription characteristic UUIDs | `a68e3f66-...`, `bd17ba04-...`, `f9150137-...`, `049ec406-...` | Full UUIDs: `CHR_IND1` (`0x5013`): `a68e3f66-0fcc-4395-8d4c-aa980b5877fa`; `CHR_IND2` (`0x5023`): `bd17ba04-b76b-4892-a545-b73ba1f74dae`; `CHR_NOT1` (`0x5033`): `f9150137-5d40-4801-a8dc-f7fc5b01da50`; `CHR_IND3`: `049ec406-ef75-4205-a390-08fe209c51f0`. All are subscribed by writing `0x01` to their CCCD (Client Characteristic Configuration Descriptor). | `lib/bluetooth.c:15-20` | high |
| BLE shutter service and characteristic UUIDs | `6514eb81-4e8f-458d-aa2a-e691336cdfac` / `7fcf49c6-4ff0-4777-a03d-1a79166af7a8` | `SVC_SHUTTER_UUID`: `6514eb81-4e8f-458d-aa2a-e691336cdfac`; `CHR_SHUTTER_UUID`: `7fcf49c6-4ff0-4777-a03d-1a79166af7a8`. | `lib/bluetooth.c:23-26` | high |
| BLE geolocation service and characteristic UUIDs | `3b46ec2b-48ba-41fd-b1b8-ed860b60d22b` / `0f36ec14-29e5-411a-a1b6-64ee8383f090` | `SVC_GEOTAG_UUID`: `3b46ec2b-48ba-41fd-b1b8-ed860b60d22b`; `CHR_GEOTAG_UUID`: `0f36ec14-29e5-411a-a1b6-64ee8383f090`; `GEOTAG_UPDATE` characteristic (inside `SVC_CONF`): `ad06c7b7-f41a-46f4-a29a-712055319122`. | `lib/bluetooth.c:29-31` | high |

### 2.7 BLE Pairing Protocol (2 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| BLE advertisement manufacturer data structure | `0x02` (type byte) | Manufacturer advertisement data layout: 2-byte `company_id` (Fujifilm), 1-byte `type` (must be `0x02` to indicate pairing mode), followed by 4-byte `token`. Total 7 bytes. If the payload size is 0 or the type byte is not `0x02`, the device is not in pairing mode and the pairing flow must not proceed. | `lib/bluetooth.c:42-56` / `lib/bluetooth.c:123-129` | high |
| BLE geolocation binary packet | _(struct layout)_ | Geolocation write to `CHR_GEOTAG`: packed binary struct with the following fields in order: `int32 latitude` (degrees × 10,000,000), `int32 longitude` (degrees × 10,000,000), `int32 altitude` (millimetres), 4 pad bytes, `u16 year`, `u8 month`, `u8 day`, `u8 hour`, `u8 minute`, `u8 second`. Total 23 bytes. | `lib/bluetooth.c:72-90` / `lib/bluetooth.c:212-230` | high |

### 2.8 Discovery Protocol (4 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| PC AutoSave discovery ports | `51540` / `51541` / `51542` | UDP register port `51542`, UDP connect port `51541`, TCP notify port `51540`. Client listens on `51542` and `51541` for camera UDP datagrams containing `'DISCOVER <client_name>'` and `'DSCADDR <camera_ip>'`. Client then TCP-connects to the camera at port `51540` and sends an HTTP NOTIFY message with an `IMPORTER` field. | `lib/discovery.c:37-39` | high |
| Wireless Tether discovery port | `51560` | TCP port `51560`. Client opens a listening TCP server; camera connects and sends an HTTP-style message containing `'DSC <camera_ip>'`, `'CAMERANAME <name>'`, and `'DSCPORT <port>'` headers. Client responds with `'HTTP/1.1 200 OK\r\n'`. The invite listener has a 30-second accept timeout. | `lib/discovery.c:31` / `lib/discovery.c:401-459` | high |
| PCSS broadcast discovery | `51562` | UDP broadcast to `192.168.1.255` port `51562`. Client sends: `'DISCOVERY * HTTP/1.1\r\nHOST: <local_ip>\r\nMX: 5\r\nSERVICE: PCSS/1.0\r\n'`. Sent periodically (approximately every 1 second) while listening for camera response datagrams. | `lib/discovery.c:32` / `lib/discovery.c:473-494` | high |
| AutoSave HTTP registration responses | _(text payloads)_ | Register invite server responds: `'HTTP/1.1 200 OK\r\nFOLDER: guest\r\nServiceName: PCAUTOSAVE/1.0\r\n'`. Connect invite server responds: `'HTTP/1.1 200 OK\r\n'`. Camera's invite to client contains `DSCNAME` and `DSCMODEL` headers. | `lib/discovery.c:223-235` | high |

### 2.9 Session Lifecycle (5 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Wi-Fi session init sequence | _(sequence)_ | Full Wi-Fi session initialisation steps: (1) TCP connect to `camera_ip:55740`; (2) send `FujiInitPacket` (82 bytes), receive `PtpFujiInitResp` (70 bytes); (3) 50 ms delay if `WIRELESS_COMM` mode; (4) PTP `OpenSession` (`0x1002`); (5) poll `EventsList` (`0xD212`) until `CameraState != WAIT_FOR_ACCESS`; (6) read `GetObjectVersion` (`0xDF22`), `RemoteGetObjectVersion` (`0xDF25`), `ImageGetVersion` (`0xDF21`), `RemoteVersion` (`0xDF24`) and write them back; (7) write `ClientState` (`0xDF01`) to the desired mode; (8) if `REMOTE_ACCESS`: call `InitiateOpenCapture`, connect event+video sockets, call `TerminateOpenCapture`. | `lib/fuji.c:124-246` | high |
| MULTIPLE_TRANSFER mode object download | Object handle `1` | In `MULTIPLE_TRANSFER` mode (`CameraState=1`), the object handle to request is always `1`. After each successful download, poll `EventsList` — if the camera has no more images it closes the connection. The camera internally "swaps" object ID `1` to point to the next available image. | `lib/fuji.c:911-943` | high |
| Fuji wait response default | `3` / `255` | `r->response_wait_default` is set to `3` for Fuji cameras (they are inherently slow). For `ClientState` writes that trigger a user-confirmation dialog on the camera, the value is temporarily raised to `255`. | `lib/fuji.c:29` / `lib/fuji.c:624-627` | high |
| Virtual object handle 0xFFFFFFF1 | `0xFFFFFFF1` | Object handle `0xFFFFFFF1` is queried with `GetObjectInfo` during remote version configuration. The camera returns info for an opaque object used for version/capability negotiation; the meaning of its content is not yet fully understood. | `lib/fuji.c:668-678` | medium |
| USB Fujifilm vendor ID | `0x04CB` | Fujifilm USB vendor ID is `0x04CB`. Used during USB device enumeration to identify Fuji cameras among all connected USB devices. | `lib/fuji_usb.c:68` | high |

### 2.10 Transfer (1 fact)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| GetPartialObject chunk size | `0x100000` (1 MB) | `FUJI_MAX_PARTIAL_OBJECT=0x100000`. File transfers loop by calling `GetPartialObject(handle, offset, min(remaining, 1MB))` until fully downloaded. Confirmed mandatory: some cameras (X-A2) stall or hang permanently with a single large-object request even when the total file size is under 1 MB. | `lib/fuji.h:13` / `lib/fuji.c:866` | high |

### 2.11 Film Simulation (2 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| FujiBinaryProfile size | `601` (camera) / `629` (X Raw Studio) | D185 binary profile: always 601 bytes from camera (`n_props=0x17` but structure is always padded to 601 bytes), 629 bytes from X Raw Studio (`n_props=0x1D`). Structure layout: `u16 n_props` + 511-byte `iop_codes` block + `0x1D × 4-byte u32` property values. Known quirk: the camera incorrectly writes the MTP string length as 8 instead of the correct value 9. | `fp/src/fp.h:201-243` | high |
| FujiProfile adjustment range encoding | _(encoding)_ | Profile adjustment steps (Color, Sharpness, HighlightTone, ShadowTone, NoiseReduction) are encoded as 32-bit two's-complement integers in units of 10: `+4=40`, `+3=30`, `+2=20`, `+1=10`, `0=0`, `−0.5=0xFFFFFFFB`, `−1=0xFFFFFFF6`, `−2=0xFFFFFFEC`, `−4=0xFFFFFFD8`. Noise Reduction uses a different `0x_000` scale. | `fp/src/fp.h:75-92` / `fp/src/fp.h:94-104` | high |

### 2.12 Other (2 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Standard PTP ISO port | `15740` | The ISO 15740 standard PTP/IP port is 15740. Fujifilm does NOT use this — all Fuji cameras use port 55740 for commands instead. Implementing against 15740 will always fail. | `libpict/src/ptp.h:696` | high |
| USB PTP class ID | `6` | Standard USB interface Class ID for PTP is 6, as per the USB device class specification. | `libpict/src/ptp.h:700` | high |

---

## 3. Frameport Mapping

| Target Crate / Module | What to Borrow (interoperability facts) | Clean-Room Note |
|---|---|---|
| `fuji-ptpip` | Fuji-specific PTP-IP handshake: `FujiInitPacket` layout (82-byte packet, version field `0x8f53e4f2`, GUID 4×`u32`, 54-byte unicode device name); `PtpFujiInitResp` layout (70 bytes); port assignments (55740/55741/55742); goodbye packet sentinel (`08 00 00 00 FF FF FF FF`); 50 ms post-init delay for `WIRELESS_COMM` mode. | Define your own Rust structs for `FujiInitPacket` and `FujiInitResp` matching the byte layout (field widths and order) derived from the published constants. Do not copy struct definitions verbatim. Port constants are interoperability facts, not creative expression. |
| `fuji-ptp` | Generic PTP container formats: USB `BulkContainer` (12-byte header: `length u32`, `type u16`, `code u16`, `transaction u32`); PTP-IP `RequestContainer` (`length u32`, `type u32`, `data_phase u32`, `code u16`, `transaction u32`, up to 5×`u32` params); `DataStart` packet (20 bytes: `length u32`, `type u32`, `transaction u32`, `payload_length u64`); `DataEnd` packet (12-byte header + payload); `Response` container (`length u32`, `type u32`, `code u16`, `transaction u32`, up to 5×`u32` params). PTP-IP receive sequence: read packet 1; if `DATA_START` read packets 2+3; else packet 1 is the response. | Implement each container as a Rust struct with explicit field widths in little-endian. The field layout is mandated by ISO 15740 and the PTP-IP spec — it is not copyrightable. Write your own serialization/deserialization using `byteorder` or `zerocopy` crates. |
| `fuji-transfer` | Chunked download via `GetPartialObject` in `0x100000`-byte (1 MB) chunks with offset tracking; property handshake: set `EnableCorrectFileSize` (`0xD227`) to `1` before `GetObjectInfo`/transfer, reset to `0` after; poll `EventsList` (`0xD212`) during transfer to keep connection alive; Wi-Fi object handle numbering: sequential `1..num_objects` from `EventsList` `ObjectCount` (`0xD222`), not from `GetObjectHandles`; `MULTIPLE_TRANSFER` mode always uses handle `1`. | Implement the download loop as a Rust async function that takes handle, size, and a write callback/sink. The chunk size and property toggling sequence are interoperability facts, not code. Design your own loop structure around these facts. |
| `fuji-ble-protocol` | BLE pairing flow: read 4-byte token from manufacturer data (type byte must be `0x02`), write token to `CHR_PAIR`, write ASCII client name to `CHR_IDEN`, subscribe to five characteristics in `SVC_CONF`; all 13 GATT UUIDs (`SVC_PAIR`, `CHR_PAIR`, `CHR_IDEN`, `SVC_CONF`, `CHR_IND1`, `CHR_IND2`, `CHR_NOT1`, `CHR_IND3`, `GEOTAG_UPDATE`, `SVC_SHUTTER`, `CHR_SHUTTER`, `SVC_GEOTAG`, `CHR_GEOTAG`); geolocation binary format: `lat/lon/alt` as `int32` × 10,000,000, 4 pad bytes, packed time struct. | UUIDs and protocol sequences are interoperability facts (not copyrightable). Implement the pairing state machine in Rust as a plain enum-driven state machine. The geolocation packet layout is a protocol wire format — implement it as a Rust `#[repr(C, packed)]` struct or manual byte serialization. |
| `:camera:wifi` (Android module) | Discovery protocol: UDP ports `51541`/`51542` for PC AutoSave datagrams, TCP port `51540` for `NOTIFY` handshake, TCP port `51560` for wireless tether, UDP broadcast to `192.168.1.255:51562` for PCSS; HTTP/1.1 text-based message framing with `DISCOVER`/`DSCADDR`/`DSCNAME`/`DSCMODEL`/`DSC`/`CAMERANAME`/`DSCPORT` fields; registration vs connection invite server differentiation and response strings. | Protocol message field names and port numbers are interoperability facts. Write a clean Rust or Kotlin parser for the line-delimited HTTP-style messages using a hand-written state machine or a minimal header parser. Do not copy the C `strtok_r` parsing logic. |
| `fuji-core` | Session state machine: `CameraState` (`0xDF00`) and `ClientState` (`0xDF01`) enumeration values; version negotiation sequence (read then re-write `GetObjectVersion`/`RemoteVersion`/`ImageGetVersion`); remote mode socket open sequence using `InitiateOpenCapture`/`TerminateOpenCapture`; event polling loop; `response_wait_default=3`. | Model the session lifecycle as a Rust enum state machine (e.g. `enum FujiSessionState`). State numeric values and transition ordering are interoperability facts. The state machine structure, error types, and retry logic should be designed from scratch. |
| `fuji-sim` (future) | Film simulation codec: D185 binary profile structure (`u16 n_props` + 511-byte `iop_codes` block + `u32` array); film simulation numeric codes (`Provia=1` through `Eterna=0x10`); adjustment range encoding (units of 10 as `i32` two's-complement); profile size quirks (601 vs 629 bytes); `FFF1` object format code; `IOPCode` field role. | Implement the D185 parser/builder in Rust as a dedicated parser module with explicit offset constants. The binary layout is a protocol wire format. Film simulation codes are interoperability constants. Adjustment encoding math (multiply/divide by 10) is trivial arithmetic, not creative expression. |

---

## 4. Standout Findings

1. **Fujifilm does NOT use the ISO standard PTP-IP port 15740.** All cameras use port 55740 for commands, port 55741 for events, and port 55742 for live-view. Any implementation targeting port 15740 will always fail to connect.

2. **The `FujiInitPacket` carries a proprietary version magic (`0x8f53e4f2`) in the field that standard PTP-IP uses for a version number.** Cameras reject handshakes that present any standard PTP-IP version value in this field.

3. **Property `PTP_DPC_FUJI_EnableCorrectFileSize` (`0xD227`) must be set to `1` before `GetObjectInfo`/`GetObject` calls to receive accurate file sizes.** The default is a ~100 KB placeholder. Skipping this step causes transfer progress calculations to be entirely wrong.

4. **Events are not delivered as unsolicited PTP events over the event socket during the initial Wi-Fi session.** Instead, the client must poll `GetPropValue` on `EventsList` (`0xD212`), which returns a variable-length array of `(code u16, value u32)` pairs. This polling must also run regularly during transfers to prevent the camera from dropping the connection.

5. **For Wi-Fi transfers, do not call `GetObjectHandles`.** Object handles are sequential integers `1..N` where N comes from `ObjectCount` (`0xD222`) in the events list. In `MULTIPLE_TRANSFER` mode the handle is always literally `1` and the camera swaps it to the next image after each successful download.

6. **BLE pairing requires reading a 4-byte token from the manufacturer advertisement data before connecting.** The type byte must be `0x02` (pairing mode indicator). Writing the token to `CHR_PAIR` and then the client name to `CHR_IDEN` is the complete pairing handshake — no PIN or out-of-band confirmation is needed beyond the user enabling pairing mode on the camera.

7. **Remote mode (live-view + remote shutter) requires a precise 5-step socket sequence.** The ordering is: send `InitiateOpenCapture` (`0x101C`), poll events, connect the event socket (55741) and video socket (55742), then send `TerminateOpenCapture` (`0x1018`) with the original transaction ID from step 1. Connecting the sockets before `TerminateOpenCapture` causes the camera to stall.

8. **`GetPartialObject` must use chunks of at most `0x100000` bytes (1 MB).** Some cameras (confirmed: X-A2) stall or hang permanently with a single large-object request even when the total file size is under 1 MB. Chunking is mandatory, not optional.

---

## 5. Caveats / IP & License Risk

1. **License:** libfuji and libpict are published as open-source (copyright Daniel C / petabyt). Protocol constants, packet field layouts, and port numbers are interoperability facts and are not subject to copyright. Frameport must not copy C function bodies, struct definitions verbatim, or any creative expression from these source files.

2. **Property code accuracy:** Several property codes in `fujiptp.h` are labeled "Unknown", "inaccurate", or "misplaced" in source comments. Treat all facts marked **medium** or **low** confidence with caution and verify against observed wire traffic before relying on them in production code.

3. **Hardcoded GUID:** The `FujiInitPacket` GUID fields in `fuji.c` use hardcoded development values (`0x5d48a5ad`, `0xb7fb287`, `0xd0ded5d3`, `0x0`). Production Frameport must generate a stable random GUID per installation rather than reusing these test values.

4. **Hardcoded client name:** The BLE pairing implementation in `bluetooth.c` uses the placeholder client name `'Pixel-6a-1234'`. This is a test artifact, not a protocol requirement. Frameport must supply its own client identifier string.

5. **Camera test corpus is limited:** libfuji only covers cameras tested by the author (X-T2, X-T4, X-T5, X-T20, X-S10, X-H1 are noted in test logs). X-T5 compatibility is confirmed. Behavior may differ on cameras not present in this corpus.

6. **Live-view wire format is incompletely documented:** The liveview stream on port 55742 is identified as MJPEG but is not further parsed in libfuji. Additional reverse-engineering will be required to extract and decode individual frames.

7. **D185 profile parser must be lenient:** The quirks in the D185 binary profile (601 vs 629 bytes, the camera writing MTP string length `8` instead of `9`) mean that the parser must not assume fixed offsets beyond the documented structure boundaries.

8. **Knowledge cutoff:** The survey reflects the libfuji codebase as of August 2025. Newer Fujifilm firmware may change protocol behavior, particularly around `RemoteVersion` negotiation and the `ClientState` confirmation dialog flow introduced in recent X-series camera firmware.

