# Frameport Fujifilm Protocol Master Constants Reference

**Purpose:** Lookup table for implementers of `fuji-ptp`, `fuji-ptpip`, `fuji-ble-protocol`, `fuji-transfer`, and `fuji-liveview`. Every numeric constant here is an interoperability fact derived from clean-room study of open-source reverse-engineering projects and observable wire behavior. No proprietary Fujifilm source code, credentials, cloud keys, or firmware binaries are reproduced.

**Clean-room boundary:** Opcodes, property codes, event codes, GATT UUIDs, port numbers, and packet field offsets are facts — they must be reproduced precisely for the camera to respond. Function bodies, struct definitions, and creative expression from any source project must not be copied into Frameport. See `CLAUDE.md` hard boundaries.

**Confidence legend:**
- `[H]` Cross-source agreement (two or more independent sources).
- `[M]` Single source, high-confidence (directly observable on the wire or from authoritative RE project).
- `[L]` Single source with caveat or contested interpretation — verify on real hardware before relying.

**Sources cited:**
| ID | Project |
|----|---------|
| FCW | `fuji-cam-wifi-tool` (hkr/fuji-cam-wifi-tool, C++, MIT) |
| LFJ | `libfuji` + `libpict` submodule (C, open-source) |
| FJH | `fujihack` + `camlib` submodule (C, GPL-3.0 / Apache-2.0) |
| FLK | `filmkit` (eggricesoy/filmkit, TypeScript, USB/WebUSB) |
| XPN | `XApp 2.7.5 — PTP-IP native stack analysis` (Ghidra + JADX) |
| XBL | `XApp 2.7.5 — BLE / Wi-Fi handoff analysis` (Ghidra + JADX) |

> **MUST-NOT-EMBED:** The XApp analysis identified plaintext proprietary Fujifilm cloud credentials (AWS Cognito pool ID, PostHog API key, Firebase API key, Google Maps API key). None of these appear in this document and none must ever appear in Frameport source code or configuration.

---

## 1. TCP Ports and Network Endpoints

| Port | Hex | Purpose | Notes | Conf | Sources |
|------|-----|---------|-------|------|---------|
| **55740** | `0xD9BC` | Command / control channel | All PTP operation request/response traffic. Must be the first socket connected. | [H] | FCW, LFJ, FJH, XPN, XBL |
| **55741** | `0xD9BD` | Async event channel | Unsolicited camera event notifications. Opened **lazily** — only after `InitiateOpenCapture` (0x101C) is called, not during the initial handshake. XApp native layer retries every 1 s; returns `0xFFFFFFFF` on timeout. | [H] | FCW, LFJ, XPN, XBL |
| **55742** | `0xD9BE` | Live-view / through-picture stream | Continuous MJPEG stream. Opened lazily alongside the event channel. XApp Java enum labels for ports 55741 and 55742 are **swapped** relative to native usage — the native code is authoritative. | [H] | FCW, LFJ, XPN, XBL |
| **192.168.0.1** | `0xC0A80001` | Camera Wi-Fi AP IPv4 address | Camera always acts as access point. Android socket must be explicitly bound to the Fujifilm `Network` object before `connect()` — see ADR-0002. Debug/emulator override in XApp: `192.168.1.200` (not a production value). | [H] | FCW, XPN, XBL |
| **15740** | `0x3D6C` | ISO 15740 standard PTP/IP port | Fujifilm does **not** use this port. Listed here as a known wrong value — implementing against 15740 will always fail. | [H] | LFJ, FJH |
| **51540** | — | PC AutoSave TCP notify port | Camera-initiated PC AutoSave discovery (not used in v1). | [M] | LFJ |
| **51541** | — | PC AutoSave UDP connect port | Camera-initiated PC AutoSave discovery (not used in v1). | [M] | LFJ |
| **51542** | — | PC AutoSave UDP register port | Camera-initiated PC AutoSave discovery (not used in v1). | [M] | LFJ |
| **51560** | — | Wireless Tether TCP listen port | Client listens; camera connects. Not used in v1. | [M] | LFJ |
| **51562** | — | PCSS broadcast UDP port | Client sends `DISCOVERY * HTTP/1.1` to `192.168.1.255:51562`. Not used in v1. | [M] | LFJ |

**Socket configuration required:** `TCP_NODELAY = true` (disable Nagle), `SO_KEEPALIVE = true`. Non-blocking connect with 1-second timeout via `select()`. XApp uses `sendto(MSG_DONTWAIT=0x4000)` with `setsockopt(SO_SNDTIMEO=0x15, {tv_sec, tv_usec=0})`. [H] FJH, LFJ, XPN

---

## 2. PTP Operation Opcodes

### 2a. Standard PTP Opcodes (ISO 15740)

These opcodes are defined by the PTP standard and are transport-agnostic (valid over both PTP-IP/Wi-Fi and PTP-USB).

| Opcode | Name | Request params / data | Response / data out | Conf | Sources |
|--------|------|-----------------------|---------------------|------|---------|
| `0x1001` | GetDeviceInfo | none | DeviceInfo dataset (manufacturer, supported ops/props/events, model, serial) | [H] | FJH, FLK, XPN |
| `0x1002` | OpenSession | `[SessionID=1]` (hardcoded, never negotiated) | none — txn ID must be 0 for this call | [H] | FJH, FLK, LFJ, XPN, XBL |
| `0x1003` | CloseSession | none | none | [H] | FJH, FLK, LFJ, XPN |
| `0x1004` | GetStorageIDs | none | array of storage IDs | [H] | FJH, FLK |
| `0x1005` | GetStorageInfo | `[StorageID]` | StorageInfo dataset | [H] | FJH, FLK |
| `0x1006` | GetNumObjects | `[StorageID, FormatCode, ParentHandle]` | `[NumObjects]` | [H] | FJH, FLK, XPN |
| `0x1007` | GetObjectHandles | `[StorageID, FormatCode=0, Parent=0]` | array of u32 handles. **Wi-Fi note:** do not call this for Wi-Fi transfers — handles are sequential 1..N from `EventsList` (0xD222) instead. | [H] | FJH, FLK, XPN |
| `0x1008` | GetObjectInfo | `[ObjectHandle]` | ObjectInfo dataset (filename, format, size, parent, storage) | [H] | FCW, FJH, FLK, XPN |
| `0x1009` | GetObject | `[ObjectHandle]` | full object data | [H] | FJH, FLK, XPN |
| `0x100A` | GetThumb | `[ObjectHandle]` | JPEG thumbnail bytes | [H] | FCW, FJH, FLK |
| `0x100B` | DeleteObject | `[ObjectHandle, FormatCode]` | none | [H] | FJH, FLK |
| `0x100C` | SendObjectInfo | `[StorageID, ParentHandle]` | `[StorageID, ParentHandle, ObjectHandle]` | [H] | FJH, FLK |
| `0x100D` | SendObject | none (data follows) | none | [H] | FJH, FLK |
| `0x100E` | InitiateCapture | `[StorageID, ObjectFormatCode]` | none — triggers shutter; async events follow on port 55741 | [H] | FCW, FJH, FLK, XPN, XBL |
| `0x1014` | GetDevicePropDesc | `[PropertyCode]` | DevicePropDesc (type, get/set, default, current, form) | [H] | FJH, FLK, XPN |
| `0x1015` | GetDevicePropValue | `[PropertyCode]` | current property value bytes | [H] | FCW, FJH, FLK, XPN |
| `0x1016` | SetDevicePropValue | `[PropertyCode]` + data | none | [H] | FCW, FJH, FLK, XPN |
| `0x1018` | TerminateOpenCapture | `[TransactionID]` (from the matching InitiateOpenCapture) | none | [H] | LFJ, XPN, XBL |
| `0x101B` | GetPartialObject | `[ObjectHandle, Offset, MaxBytes]` | partial object data. Fujifilm max chunk size: **0x100000 (1 MB)**. | [H] | FCW, FJH, LFJ, XPN |
| `0x101C` | InitiateOpenCapture | `[StorageID=0, ObjectFormatCode=0]` | none — triggers camera to open event + liveview sockets | [H] | LFJ, XPN, XBL |

### 2b. Fujifilm Vendor Opcodes (0x9xxx range)

All Fujifilm vendor opcodes are in the `0x9000–0x90FF` range. They are observable on any Fujifilm PTP-IP or PTP-USB session.

| Opcode | Name | Params / data | Notes | Conf | Sources |
|--------|------|---------------|-------|------|---------|
| `0x9020` | InitiateMovieCapture | `[StorageID, ObjectFormatCode]` | Starts video. Camera saves the response TransactionID; `0x9021` must echo it. | [H] | FCW, LFJ, XPN, XBL |
| `0x9021` | TerminateMovieCapture | none params (reads saved txn ID internally) | Stops video. Must be paired with the exact TransactionID from `0x9020`. | [H] | FCW, LFJ, XPN, XBL |
| `0x9022` | GetCapturePreview | none | Returns JPEG thumbnail of most-recently captured image. First 8 bytes of response are a header; JPEG starts at offset 8. | [H] | FCW, LFJ |
| `0x9023` | StepZoom | — | Zoom step. | [M] | LFJ |
| `0x9024` | StartZoom | — | Zoom start. | [M] | LFJ |
| `0x9025` | StopZoom | — | Zoom stop. | [M] | LFJ |
| `0x9026` | LockS1Lock (focus_point) | `[AFArea: u32]` (or 4-byte payload `{y, x, 0x02, 0x03}` in FCW framing) | Half-shutter AF lock. Over Wi-Fi FCW framing: 4-byte payload `{y_u8, x_u8, 0x02, 0x03}`. XApp: single `AFArea` u32 param. | [H] | FCW, LFJ, XPN, XBL |
| `0x9027` | UnlockS1Lock (focus_unlock) | none / five zero params | AF lock release. | [H] | FCW, LFJ, XPN, XBL |
| `0x902B` | GetDeviceInfo (Fuji capabilities) | none | Returns 392+ byte response: 12-byte unknown prefix, then TLV sub-messages each with a 4-byte inclusive length prefix followed by a PTP DevicePropDesc blob. Called once at session start. | [H] | FCW |
| `0x902C` | StepShutterSpeed | `[Direction: u32]` — `0x01`=faster/increment, `0x00`=slower/decrement | Relative shutter speed adjustment. Bytes 1–3 are zero. | [H] | FCW, LFJ, XPN, XBL |
| `0x902D` | StepFNumber | `[Direction: u32]` — `0x01`=open, `0x00`=close | Relative aperture adjustment by one third-stop. | [H] | FCW, LFJ, XPN, XBL |
| `0x902E` | StepExposureBias | `[Direction: u32]` — `0x01`=positive, `0x00`=negative | Relative exposure compensation adjustment. | [H] | FCW, LFJ, XPN, XBL |
| `0x9030` | CancelInitiateCapture | — | Cancel an in-progress capture. | [M] | LFJ |
| `0x9040` | FmSendObjectInfo | `[StorageID=0, Handle=0, 0]` + ObjectInfo data | Fujifilm variant of SendObjectInfo for phone-to-camera transfers (e.g. RAF upload). | [H] | LFJ, XPN |
| `0x9041` | FmSendObject | data only | Fujifilm variant of SendObject. | [H] | LFJ, XPN |
| `0x9042` | FmSendPartialObject | — | Fujifilm variant of GetPartialObject for phone-to-camera. | [M] | LFJ, XPN |
| `0x9053` | — (vendor) | — | Present in XApp opcode table; semantics not extracted. | [L] | XPN |
| `0x9054` | — (vendor) | — | Present in XApp and LFJ opcode tables; semantics not extracted. | [L] | LFJ, XPN |
| `0x9055` | — (vendor) | — | Present in LFJ; noted alongside 0x9054. | [L] | LFJ |
| `0x9056` | — (vendor) | — | Present in XApp opcode table. | [L] | XPN |
| `0x9060` | SetCameraEvent | data phase: 6 bytes for non-string props; `stringLength+6` for type `0xFFFF` | Generic camera event/property setter. Uses `dataPhase=2`. JNI bridge reads `eventCode`, `dataType`, `valueStringLength` as `int` (not `short`). | [H] | XPN, XBL |

**RAF upload via Fujifilm vendor opcodes (USB context confirmed, Wi-Fi likely same):**

The standard `SendObjectInfo` (0x100C) / `SendObject` (0x100D) pair does NOT work for RAF upload. Use the Fujifilm vendor variants:

| Step | Opcode | Payload details |
|------|--------|-----------------|
| 1 | `0x900C` (FujiOp.SendObjectInfo) | ObjectInfo struct: StorageID=0, ObjectFormat=**0xF802** (RAF), ProtectionStatus=0, CompressedSize=file_size_bytes, then zeroed fields, then PTP string filename = **`"FUP_FILE.dat"`**. Params: `[0, 0, 0]`. Wrong format code causes silent failure. |
| 2 | `0x900D` (FujiOp.SendObject2) | Raw RAF binary bytes. 60-second timeout for large files. |
| 3 | `0xD183` SetDevicePropValue | Trigger conversion: write `uint16 = 0x0000` to property `0xD183` (StartRawConversion). |
| 4 | `0x1007` GetObjectHandles | Poll with params `[0xFFFFFFFF, 0x0000, 0x00000000]` every 1 s until count > 0. 30-second overall timeout. |
| 5 | `0x1009` GetObject | Download converted JPEG. 60-second timeout. |
| 6 | `0x100B` DeleteObject | Clean up converted object. |

[H] FLK. Object format `0xF802` confirmed by FLK and LFJ independently.

**Fuji object format wire codes (PTP standard codes for Fujifilm content):**

| Format code (decimal) | Hex | Type | Conf | Sources |
|----------------------|-----|------|------|---------|
| 12301 | `0x300D` | MOV video | [H] | XPN, XBL |
| 14337 | `0x3801` | JPEG (standard) | [H] | FJH, XPN, XBL |
| 14344 | `0x3808` | JPEG (variant — rotated) | [H] | XPN, XBL |
| 45315 | `0xB103` | RAF (Fujifilm RAW) | [H] | FJH, XPN, XBL |
| 47490 | `0xB982` | HEIF | [H] | XPN, XBL |
| 61442 | `0xF002` | Script (`PTP_OF_Script`) | [H] | FJH |
| 63490 | `0xF802` | Fujifilm RAW profile / object upload | [H] | LFJ, FLK |
| 65521 | `0xFFF1` | `PTP_OF_FUJI_FFF1` — Fujifilm proprietary | [M] | LFJ |

---

## 3. Device Property Codes

### 3a. Standard PTP Device Properties (0x5xxx range)

| Code | Name | Type | Key encoding | Conf | Sources |
|------|------|------|--------------|------|---------|
| `0x5001` | BatteryLevel | UINT8 | 0–100 | [H] | FJH |
| `0x5002` | FunctionalMode | UINT16 | — | [H] | FJH |
| `0x5003` | ImageSize | UINT16 | — | [H] | FJH |
| `0x5005` | WhiteBalance | UINT16 | standard PTP values | [H] | FCW, FJH |
| `0x5007` | FNumber (aperture) | UINT16 | `f-number × 100`; 0x0000=Auto, 0xFFFF=N/A | [H] | FCW, FJH |
| `0x500A` | FocusMode | UINT16 | 1=Manual, 0x8001=AF-S, 0x8002=AF-C | [H] | FCW, FJH |
| `0x500C` | FlashMode | UINT16 | standard PTP enum | [H] | FCW, FJH |
| `0x500D` | ExposureTime | UINT32 | shutter speed (standard) | [H] | FJH |
| `0x500E` | ExposureProgramMode | UINT16 | 1=M, 2=P, 3=Av, 4=Tv, 6=Auto | [H] | FCW, FJH |
| `0x500F` | ExposureIndex (ISO) | UINT16 | standard ISO value | [H] | FJH |
| `0x5010` | ExposureBiasCompensation | INT16 | milli-EV (÷1000 for stops) | [H] | FCW, FJH |
| `0x5011` | DateTime | STRING | — | [H] | FJH |
| `0x5012` | CaptureDelay | UINT16 | 0=Off, 1=1s, 2=2s, 3=5s, 4=10s | [H] | FCW |
| `0x5013` | StillCaptureMode | UINT16 | — | [H] | FJH |

### 3b. Fujifilm Vendor Device Properties (0xDxxx range)

**Important:** The standard `SetDevicePropValue` (0x1016) / `GetDevicePropValue` (0x1015) opcodes operate on these property codes directly over PTP-USB. Over Fujifilm's proprietary Wi-Fi framing (FCW protocol), writes use the **two-part message pattern** (opcode 0x1016 framing, part 1 = property code, part 2 = value — see Section 6). `GetDevicePropValue` maps to FCW status poll (0x1015).

| Code | Name | Type | Key encoding | Conf | Sources |
|------|------|------|--------------|------|---------|
| `0xD001` | FilmSimulation | UINT16 | See film simulation table (§3c). | [H] | FCW, LFJ, FLK |
| `0xD002` | FilmSimulationTune | — | Tuning offset for film sim | [M] | FLK |
| `0xD003` | DRangeMode | — | Dynamic range mode | [M] | FLK |
| `0xD007` | ColorTemperature | UINT16 | Kelvin value for WB=ColorTemp | [M] | FLK |
| `0xD008` | WhiteBalanceFineTune | — | Fine-tune adjustment | [M] | FLK |
| `0xD00A` | NoiseReduction | — | NR level | [M] | FLK |
| `0xD00B` | ImageQuality | UINT16 | 2=JPEG Fine, 3=JPEG Normal, 4=RAW+JPEG Fine, 5=RAW+JPEG Normal. Camera reports RAW+Fine when in pure-RAW mode (no pure-RAW enum exists). | [H] | FCW (`0xD018`) / FLK (`0xD00B`) — **code differs by context; verify on X-T5** |
| `0xD00C` | RecMode | — | Video record availability | [M] | FLK |
| `0xD00F` | FocusMode | — | Focus mode enum | [M] | FLK |
| `0xD016E` | USBMode | UINT16 | 5=USB_TETHER, 6=RAW_CONV, 8=WEBCAM | [H] | LFJ |
| `0xD017` | GrainEffect | UINT16 | See grain encoding (§3d) | [M] | FLK |
| `0xD018` | ImageFormat (Wi-Fi) | UINT16 | 2=JPEG Fine, 3=Normal, 4=RAW+JPEG Fine, 5=RAW+JPEG Normal | [H] | FCW |
| `0xD019` | RecModeEnable / ShadowHighlight | UINT16 | As RecModeEnable: 0=unavailable, 1=available. Note: code `0xD019` is used for ShadowHighlight in FLK context. **Context-dependent.** | [M] | FCW, FLK — **conflict, see §7** |
| `0xD01A` to `0xD099` | — | — | Unextracted vendor range | — | — |
| `0xD100` | ExposureIndex | — | ISO in alternate encoding | [M] | FLK |
| `0xD104` | FocusMeteringMode | — | — | [M] | FLK |
| `0xD10A` | ShutterSpeed | — | Alternate shutter speed code | [M] | FLK |
| `0xD10B` | ImageAspectRatio | — | — | [M] | FLK |
| `0xD16E` | USBMode | UINT16 | 5=USB_TETHER, 6=RAW_CONV, 8=WEBCAM. Absent = USB_CARD_READER. | [H] | LFJ |
| `0xD171` | RawConversionEdit | — | Edit mode flag for RAW conv | [H] | FLK |
| `0xD17C` | FocusPoint | UINT32 | `x = val >> 8`, `y = val & 0xFF`. X-T100: x range 1–13, y range 1–7. | [H] | FCW |
| `0xD183` | StartRawConversion | UINT16 | Write `0x0000` to trigger conversion | [H] | FLK |
| `0xD184` | IOPCodes | — | IOP code block for RAW profile | [H] | FLK |
| `0xD185` | RawConvProfile | BINARY | 601-byte camera format (n_props=0x17) or 629-byte X Raw Studio format (n_props=0x1D). See §3e for field index table. | [H] | LFJ, FLK |
| `0xD186` | FirmwareVersion | STRING | Firmware version string | [H] | FLK |
| `0xD187` | FirmwareVersion2 | STRING | Secondary firmware version | [H] | FLK |
| `0xD18C` | PresetSlot | UINT16 | Write 1–7 to select preset slot (C1–C7). Wait 100 ms after write before further preset operations. | [H] | FLK |
| `0xD18D` | PresetName | STRING | PTP string (UTF-16LE with length prefix) | [H] | FLK |
| `0xD18E` | Preset: ImageSize | UINT16 | Default observed: 7 | [H] | FLK |
| `0xD18F` | Preset: ImageQuality | UINT16 | Default observed: 4 | [H] | FLK |
| `0xD190` | Preset: DynamicRange% | UINT16 | Wire values: 100, 200, or 400 | [H] | FLK |
| `0xD191` | Preset: unknown | UINT16 | Always observed as 0 | [M] | FLK |
| `0xD192` | Preset: FilmSimulation | UINT16 | Same enum as 0xD001 | [H] | FLK |
| `0xD193` | Preset: MonoWC×10 | INT16 | Monochrome warm/cool. **Only write for monochrome sims. Camera rejects write of 0.** | [H] | FLK |
| `0xD194` | Preset: MonoMG×10 | INT16 | Monochrome magenta/green. **Only write for monochrome sims. Camera rejects write of 0.** | [H] | FLK |
| `0xD195` | Preset: GrainEffect | UINT16 | Flat 1-indexed enum: 1=Off, 2=WeakSmall, 3=StrongSmall, 4=WeakLarge, 5=StrongLarge | [H] | FLK |
| `0xD196` | Preset: ColorChrome | UINT16 | 1=Off, 2=Weak, 3=Strong | [H] | FLK |
| `0xD197` | Preset: ColorChromeFxBlue | UINT16 | 1=Off, 2=Weak, 3=Strong | [H] | FLK |
| `0xD198` | Preset: SmoothSkin | UINT16 | 1=Off, 2=Weak, 3=Strong | [H] | FLK |
| `0xD199` | Preset: WhiteBalance | UINT16 | WB mode codes (see §3f). Stored as UINT16 but returned as INT16 — mask with `0xFFFF` to recover unsigned value. | [H] | FLK |
| `0xD19A` | Preset: WBShiftR | INT16 | Red WB shift | [H] | FLK |
| `0xD19B` | Preset: WBShiftB | INT16 | Blue WB shift | [H] | FLK |
| `0xD19C` | Preset: ColorTempK | UINT16 | Kelvin value. **Write only when WB=0x8007 (ColorTemp). Must be written immediately after D199.** | [H] | FLK |
| `0xD19D` | Preset: HighlightTone×10 | INT16 | Range raw = UI × 10. Sentinel `0x8000` = use EXIF default. | [H] | FLK |
| `0xD19E` | Preset: ShadowTone×10 | INT16 | Same ×10 encoding | [H] | FLK |
| `0xD19F` | Preset: Color×10 | INT16 | **Omit for monochrome film sims.** Sentinel `0x8000` = use EXIF default. | [H] | FLK |
| `0xD1A0` | Preset: Sharpness×10 | INT16 | ×10 encoding | [H] | FLK |
| `0xD1A1` | Preset: HighIsoNR | UINT16 | **Non-linear encoding** (see §3g). Default when NR not applied: `0x4000`. | [H] | FLK |
| `0xD1A2` | Preset: Clarity×10 | INT16 | ×10 encoding | [H] | FLK |
| `0xD1A3` | Preset: LongExpNR | UINT16 | 1=On (default), 0=Off | [H] | FLK |
| `0xD1A4` | Preset: ColorSpace | UINT16 | 1=sRGB (default) | [H] | FLK |
| `0xD1A5` | Preset: unknown | UINT16 | Always observed as 7 | [M] | FLK |
| `0xD20E` | FreeSDRAMImages | UINT16 | Non-zero signals new image captured and available (wireless tether mode) | [H] | LFJ |
| `0xD212` | EventsList / CurrentState | ARRAY | GetPropValue returns: `u16 count`, then `count × {u16 code, u32 value}` pairs LE. Also used as heartbeat/keep-alive ping. Called in polling loop during transfers to keep connection alive. | [H] | LFJ, FLK |
| `0xD222` | ObjectCount | UINT32 | Available via EventsList after session open. Wi-Fi object handles are sequential 1..ObjectCount (not from GetObjectHandles). | [H] | LFJ |
| `0xD226` | CompressSmall | UINT16 | Set 1 to receive 400–800 KB compressed preview; 0 for full size | [H] | LFJ |
| `0xD227` | EnableCorrectFileSize | UINT16 | **Set to 1 before GetObjectInfo/GetObject for accurate sizes.** Default is a ~100 KB placeholder. Reset to 0 after download. | [H] | LFJ |
| `0xD229` | ImageSpaceSD | UINT32 | Remaining SD card space (units unclear) | [H] | FCW |
| `0xD22A` | MovieRemainingTime | UINT32 | Remaining video record time on SD (units unclear) | [H] | FCW |
| `0xD240` | ShutterSpeed (Wi-Fi) | UINT32 | Bit 31 set = sub-second: `1/(bits 0–27 / 1000.0)`. Bit 31 clear = seconds: `bits 0–27 / 1000.0`. Value `0xFFFFFFFF` = bulb/N/A. | [H] | FCW |
| `0xD241` | ImageAspect | UINT16 | 2=S 3:2, 3=S 16:9, 4=S 1:1, 6=M 3:2, 7=M 16:9, 8=M 1:1, 10=L 3:2, 11=L 16:9, 12=L 1:1 | [H] | FCW |
| `0xD242` | BatteryLevel (Wi-Fi ext.) | UINT16 | NP-W126: 1=Critical, 2=1 bar, 3=2 bars, 4=Full. NP-W126S: 6=Critical, 7–10=bars, 11=Full | [H] | FCW |
| `0xD28` | ApertureShutterControl | UINT16 | 0=both free, 1=SS limited, 2=aperture limited, 3=both limited | [H] | FCW (`0xD028`) |
| `0xD02A` | ISO | UINT32 | Bit 31=auto flag, bit 30=emulated flag, bits 0–23=numeric ISO value. `0xFFFFFFFF`=auto. | [H] | FCW |
| `0xD02B` | MovieISO | UINT32 | Same encoding as `0xD02A` | [H] | FCW |
| `0xD209` | FocusLock | UINT16 | 0=unlocked, 1=locked | [H] | FCW |
| `0xD21B` | DeviceError | UINT16 | 0=no error | [H] | FCW |
| `0xD36A` | BatteryInfo1 | UINT16 | Percentage value | [H] | LFJ |
| `0xD36B` | BatteryInfo2 | UINT16 | Secondary battery | [H] | LFJ |
| `0xD500` | Geolocation (PTP-IP) | STRING | NMEA-like ASCII: `"0000.000000,N00000.000000,E00000.00,M 000.0,K0000:00:0000:00:00.000"` | [M] | LFJ |
| `0xDF00` | CameraState | UINT16 | 0=WAIT_FOR_ACCESS, 1=MULTIPLE_TRANSFER, 2=FULL_ACCESS, 3=PC_AUTO_SAVE, 6=REMOTE_ACCESS. Poll via EventsList (0xD212) after OpenSession. | [H] | LFJ |
| `0xDF01` | ClientState | UINT16 | Write to set mode: 1=VIEW_MULTIPLE, 2=VIEW_ALL_IMGS, 3=OLD_REMOTE, 5=REMOTE_MODE, 8=MULTIPLE_TRANSFER_REQ, 9=IMG_VIEW_IN_CAM, 11=REMOTE_IMG_VIEW, 17=SET_GPS, 18=LIMITED_IMG_TRANSMISSION, 20=REMOTE_IMG_VIEW_XAPP. Writing triggers camera UI dialog; use response_wait=255. | [H] | LFJ |
| `0xDF21` | ImageGetVersion / Fpcsh_PhoneReceive | UINT32 | Read then re-write to confirm support. X-T20 reports 0x20004. | [H] | LFJ, XPN, XBL |
| `0xDF22` | GetObjectVersion / Fpcsh_PhotoView | UINT32 | Read then re-write. Version negotiation gate; camera rejects downloads if skipped. | [H] | LFJ, XPN, XBL |
| `0xDF24` | RemoteVersion / Fpcsh_Remote | UINT32 | Camera Connect app v2.11 = `0x2000C`. X-T20 reports `0x20004`. Value `-1` = no remote mode. | [H] | LFJ, XPN, XBL |
| `0xDF25` | RemoteGetObjectVersion / Fpcsh_RemotePhotoView | UINT32 | Set to 5 during remote image view setup. X-H1 requires ≥4. | [H] | LFJ, XPN, XBL |
| `0xDF27` | Fpcsh_FirmwareDataTransfer | UINT32 | Gates firmware update capability negotiation | [H] | XPN, XBL |
| `0xDF28` | Fpcsh_RemotePhotoViewEx | UINT32 | Extended remote photo view | [H] | XPN, XBL |
| `0xDF2A` | Fpcsh_RemoteEx | UINT32 | Extended remote mode | [H] | XPN, XBL |
| `0xDF31` | Fpcsh_GPSSet | UINT32 | GPS capability gate | [H] | XPN, XBL |

### 3c. Film Simulation Codes (property 0xD001 / preset 0xD192)

The following numeric codes apply to both the `FilmSimulation` property (0xD001) and the preset slot property (0xD192). There is a **conflict** between FCW and LFJ on the numbering for codes 4–9; FLK and LFJ agree on the version below. The FCW mapping (with different assignments for ProNeg/Mono) should be treated as lower confidence and verified on hardware.

| Code | Film Simulation | Monochrome? | Conf | Sources |
|------|----------------|-------------|------|---------|
| `0x01` | Provia / Standard | No | [H] | FCW, LFJ, FLK |
| `0x02` | Velvia / Vivid | No | [H] | FCW, LFJ, FLK |
| `0x03` | Astia / Soft | No | [H] | FCW, LFJ, FLK |
| `0x04` | Pro Neg Hi | No | [H] | LFJ, FLK; FCW maps this to Monochrome — **conflict, see §7** |
| `0x05` | Pro Neg Std | No | [H] | LFJ, FLK; FCW maps this to Sepia — **conflict, see §7** |
| `0x06` | Monochrome | Yes | [H] | LFJ, FLK |
| `0x07` | Monochrome+Ye | Yes | [H] | LFJ, FLK |
| `0x08` | Monochrome+R | Yes | [H] | LFJ, FLK |
| `0x09` | Monochrome+G | Yes | [H] | LFJ, FLK |
| `0x0A` | Sepia | Yes | [H] | LFJ, FLK |
| `0x0B` | Classic Chrome | No | [H] | FCW, LFJ, FLK |
| `0x0C` | Acros | Yes | [H] | FCW (=12), LFJ, FLK |
| `0x0D` | Acros+Ye | Yes | [H] | FCW (=13), LFJ, FLK |
| `0x0E` | Acros+R | Yes | [H] | FCW (=14), LFJ, FLK |
| `0x0F` | Acros+G | Yes | [H] | FCW (=15), LFJ, FLK |
| `0x10` | Eterna / Cinema | No | [H] | FCW (=16), LFJ, FLK |
| `0x11` | Classic Neg | No | [M] | FLK (noted "may need adjustment for X-Processor 5") |
| `0x12` | Eterna Bleach Bypass | No | [M] | FLK |
| `0x13` | Nostalgic Neg | No | [M] | FLK |
| `0x14` | Reala Ace | No | [M] | FLK |

**Monochrome simulation set** (for conditional property-write logic — do NOT write Color (0xD19F) for these; DO write MonoWC/MonoMG (0xD193/0xD194)):
`{0x06, 0x07, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E, 0x0F}` [H] FLK

### 3d. Grain Effect Encoding Duality

**Two incompatible wire encodings exist. Context determines which to use.**

| Context | Encoding | Wire values |
|---------|----------|-------------|
| D185 RAW profile (field index 9) | Byte-packed UINT16: high byte = size (0=Small, 1=Large), low byte = strength (0=Off, 2=Weak, 3=Strong) | Off=`0x0000`, WeakSmall=`0x0002`, StrongSmall=`0x0003`, WeakLarge=`0x0102`, StrongLarge=`0x0103` |
| Preset property 0xD195 | Flat 1-indexed enum | 1=Off, 2=WeakSmall, 3=StrongSmall, 4=WeakLarge, 5=StrongLarge |

[H] FLK — confirmed via Wireshark capture of official X RAW Studio app.

### 3e. D185 RawConvProfile (0xD185) Native Field Index Table

**Camera-returned format: 625 bytes** (confirmed X100VI with X-Processor 5, 2026-03). Structure: `u16 numParams` at offset 0, then parameter array at offset `(total_length - numParams × 4)` where each param is a signed `i32` LE.

| Index | Field | Encoding |
|-------|-------|----------|
| 4 | ExposureBias | INT32 in milli-stops |
| 6 | DynamicRange% | INT32; values 100, 200, or 400 |
| 7 | DRangePriority | INT32 |
| 8 | FilmSimulation | INT32; same enum as §3c |
| 9 | GrainEffect | INT32; byte-packed (D185 encoding from §3d) |
| 10 | ColorChrome | INT32; 1-indexed: 1=Off, 2=Weak, 3=Strong |
| 11 | SmoothSkin | INT32; 1-indexed |
| 12 | WBMode | INT32; 0=use EXIF sentinel |
| 13 | WBShiftR | INT32 |
| 14 | WBShiftB | INT32 |
| 15 | WBColorTempK | INT32 |
| 16 | HighlightTone | INT32; raw = UI × 10 |
| 17 | ShadowTone | INT32; raw = UI × 10 |
| 18 | Color | INT32; raw = UI × 10; 0=sentinel (use EXIF) |
| 19 | Sharpness | INT32; raw = UI × 10 |
| 20 | NoiseReduction | INT32; **non-linear** (§3g) |
| 25 | ColorChromeFxBlue | INT32; 1-indexed |
| 27 | Clarity | INT32; raw = UI × 10 |

**Patch strategy:** Clone the camera-supplied base profile byte-for-byte, then overwrite only the changed field indices. Building from scratch will silently corrupt fields with EXIF sentinel values. [H] FLK

**Format conflict:** LFJ documents 601-byte camera format and 629-byte X Raw Studio format (n_props=0x17 vs 0x1D). FLK confirms 625-byte native format on X100VI (X-Processor 5, 2026-03). The 601/629-byte measurements are from older cameras. The numParams-based layout calculation (params at offset `total_len - numParams×4`) is self-describing and handles size variation. [L] — verify on X-T5.

### 3f. White Balance Mode Codes (0xD199 preset / WBMode field in D185)

| Code | WB Mode |
|------|---------|
| `0x0000` | As Shot (D185 sentinel: use EXIF) |
| `0x0002` | Auto |
| `0x0004` | Daylight |
| `0x0006` | Incandescent |
| `0x0008` | Underwater |
| `0x8001` | Fluorescent 1 |
| `0x8002` | Fluorescent 2 |
| `0x8003` | Fluorescent 3 |
| `0x8006` | Shade |
| `0x8007` | Color Temperature (K) — must also write D19C |
| `0x8021` | Ambience Priority |

**Important:** Values with bit 15 set (≥0x8000) are returned as negative when read as INT16. Mask with `0xFFFF` to recover the unsigned WB code. [H] FLK

### 3g. HighIsoNR Non-Linear Encoding (0xD1A1 preset / D185 index 20)

This encoding is **not** a ×10 formula. It was reverse-engineered from Wireshark captures of X RAW Studio and is entirely empirical.

| UI value | Wire UINT16 | Note |
|----------|-------------|------|
| −4 | `0x8000` | **NB:** `0x8000` means −4 NR here — NOT the "use EXIF default" sentinel that all other tone fields use. Context is critical. |
| −3 | `0x7000` | |
| −2 | `0x4000` | Default when NR not applied |
| −1 | `0x3000` | |
| 0 | `0x2000` | |
| +1 | `0x1000` | |
| +2 | `0x0000` | |
| +3 | `0x6000` | |
| +4 | `0x5000` | |

[H] FLK — confirmed via Wireshark; future firmware may change this table.

---

## 4. Event Codes

### 4a. Standard PTP Events

| Code | Name | Meaning | Conf | Sources |
|------|------|---------|------|---------|
| `0x4002` | ObjectAdded | New object created (e.g. photo captured) | [H] | FJH |
| `0x4003` | ObjectRemoved | Object deleted | [H] | FJH |
| `0x4006` | DevicePropChanged | A device property value changed | [H] | FJH |
| `0x4009` | RequestObjectTransfer | Camera requests initiator to pull an object | [H] | FJH |
| `0x400D` | CaptureComplete | Capture operation finished | [H] | FJH |

### 4b. Fujifilm Vendor Events (via EventsList 0xD212 polling)

**Important:** Fujifilm cameras deliver most state changes through the `EventsList` property poll (GetPropValue on 0xD212) rather than as unsolicited PTP events on the event socket. The event socket (port 55741) carries raw async notifications with no documented structure from the studied sources.

| Code | Name / Meaning | Conf | Sources |
|------|----------------|------|---------|
| `0xC001` | PreviewAvailable — capture preview ready | [M] | LFJ (noted as "may be inaccurate") |
| `0xC004` | ObjectAdded — new image available | [M] | LFJ (noted as "may be inaccurate") |

**EventsList polling pattern:** Call `GetDevicePropValue(0xD212)` in a loop. Response: `u16 count`, then `count × {u16 eventCode, u32 value}`. Must also be polled during file transfers to keep the connection alive. [H] LFJ, FLK

**SDK function mode codes** (XApp, passed to SetFunctionMode after OpenSession):

| Code | Mode |
|------|------|
| 1 | IMAGE_RECEIVE |
| 5 | REMOTE |
| 6 | NEUTRAL20 |
| 19 | FW_DATA_TRANSFER |
| 20 | IMAGE_VIEW_V2 |
| 21 | RESERVED_PHOTO_RECEIVED20 |
| 22 | IMAGE_LIVE_VIEW |

[H] XPN, XBL

**PTP Response codes:**

| Code | Name |
|------|------|
| `0x2001` | OK |
| `0x2002` | GeneralError |
| `0x2003` | SessionNotOpen |
| `0x2004` | InvalidTransactionID |
| `0x2005` | OperationNotSupported |
| `0x2006` | ParameterNotSupported |
| `0x2007` | IncompleteTransfer |
| `0x2008` | InvalidStorageID |
| `0x2009` | InvalidObjectHandle |
| `0x200A` | DevicePropNotSupported |
| `0x201E` | SessionAlreadyOpen — triggers recovery: CloseSession + transport reset + retry OpenSession |
| `0x2019` | BUSY — triggers 500 ms retry, max 5 iterations (2.5 s total) in XApp Ack handling |

[H] FLK, FJH, XPN

**XSDK error codes (XApp internal):**

| Code | Name |
|------|------|
| 0 | NOERR |
| 4101 | UNSUPPORTED |
| 4102 | BUSY — retry trigger in function mode negotiation loops |
| 8194 | TIMEOUT |
| 8195 | COMBINATION |
| −2 | FORCEMODE_BUSY — distinct from positive error codes |
| 37120 | UNKNOWN |

[H] XPN, XBL

---

## 5. BLE GATT Services and Characteristics

### 5a. Fujifilm Manufacturer Advertisement Filter

| Field | Value | Notes | Conf | Sources |
|-------|-------|-------|------|---------|
| Company identifier (BLE Manufacturer Specific Data) | `0x04D8` (decimal 1240) | Use this as the BLE scan filter to find Fujifilm cameras. | [H] | XPN, XBL |
| Manufacturer data byte offset | 23 (`MANUFACTURER_OFFSET`) | Offset within raw advertisement bytes | [H] | XPN, XBL |
| Manufacturer data field size | 2 bytes (`MANUFACTURER_SIZE`) | | [H] | XPN, XBL |
| Pairing-mode type byte | `0x02` | In LFJ libfuji BLE pairing flow: manufacturer data structure is `{company_id: u16, type: u8, token: u32}`. Type must equal `0x02` for pairing mode. | [H] | LFJ |
| Pairing token size | 4 bytes | Token extracted from manufacturer data; written to CHR_PAIR characteristic | [H] | LFJ |

**Note on two pairing models:** LFJ describes a token-based pairing (read 4-byte token from advertisement, write to CHR_PAIR). XApp/XBL describes a key-based pairing (read characteristics in sequence, write PAIRING_KEY to SERVICE_FF_CONNECTED_DEVICE_INFORMATION). These may be different generations of the same pairing handshake. Verify on target hardware.

### 5b. GATT Services

| Service UUID | Name | Purpose | Conf | Sources |
|--------------|------|---------|------|---------|
| `6514EB81-4E8F-458D-AA2A-E691336CDFAC` | Camera Control | Remote shutter, shooting request | [H] | LFJ, XPN, XBL |
| `4C0020FE-F3B6-40DE-ACC9-77D129067B14` | Camera State | Camera state notifications (transfer, vital, card, error) | [H] | LFJ, XPN, XBL |
| `91F1DE68-DFF6-466E-8B65-FF13B0F16FB8` | Connected Device Information | Pairing key, device name, app info (also SVC_PAIR in LFJ) | [H] | LFJ, XPN, XBL |
| `4E941240-D01D-46B9-A5EA-67636806830B` | Camera Setting | Settings, backup/restore state, FW update state, log transfer | [H] | XPN, XBL |
| `117C4142-EDD4-4C77-8696-DD18EEBB770A` | Camera Information | SSID, passphrase, MAC, serial, Y-number, BLE protocol version. RED-variant: `A9D2B304-E8D6-4902-8336-352B772D7597` | [H] | XPN, XBL |
| `3B46EC2B-48BA-41FD-B1B8-ED860B60D22B` | Current Location (GPS) | Location and speed, sync settings | [H] | LFJ, XBL |
| `E872B11F-D526-4AE1-9BB4-89A99D48FA59` | Current Time | UTC and timezone, date/time sync | [H] | XBL |
| `AF854C2E-B214-458E-97E2-912C4ECF2CB8` | File Transfer | BLE file transfer chunks, transaction state | [H] | XPN, XBL |
| `731893F9-744E-4899-B7E3-174106FF2B82` | Camera Startup Information | Camera startup value. RED-variant: `804DAA8E-FFEB-4AB3-8E75-6EDD7303208D` | [H] | XBL |
| `462426E1-D712-4A28-BE62-61A786DAA866` | File Transfer X-Half | X Half companion app file transfer. RED-variant: `CC74BD26-4ABB-4911-AE72-9CD93109A772` | [H] | XBL |
| `15CA59FE-620C-464D-A987-223FAB660CDE` | Mounted Lens Information | Lens name, serial, firmware | [H] | XPN, XBL |
| `0000180A-0000-1000-8000-00805F9B34FB` | Device Information (standard) | FIRMWARE_REVISION_STRING, MANUFACTURER_NAME_STRING, SERIAL_NUMBER_STRING, GAP_DEVICE_NAME | [H] | XBL |
| `123D8F06-62A1-4935-9322-833C531EE225` | Connected Device Information (RED) | RED-compliant camera variant of `91F1DE68-...` | [M] | XBL |

### 5c. Key Characteristics

| Characteristic UUID | Name | Service | Direction | Payload summary | Conf | Sources |
|--------------------|------|---------|-----------|-----------------|------|---------|
| `7FCF49C6-4FF0-4777-A03D-1A79166AF7A8` | Shooting Request (remote shutter) | Camera Control | Write | UShort: **S0=0** (release), **S1=1** (half-press / AF), **S2=2** (full-press / capture). 5-second write lock. | [H] | LFJ, XPN, XBL |
| `ABA356EB-9633-4E60-B73F-F52516DBD671` | Pairing Key / CHR_PAIR | Connected Device Information | Write | 4 bytes raw from manufacturer advertisement token (LFJ) or `pairingKeyBuffer` (XBL) | [H] | LFJ, XBL |
| `85B9163E-62D1-49FF-A6F5-054B4630D4A1` | Connected Device Name / CHR_IDEN | Connected Device Information | Write | ASCII client name string (e.g. device model + identifier) | [H] | LFJ, XBL |
| `BF6DC9CF-3606-4EC9-A4C8-D77576E93EA4` | Camera SSID Name | Camera Information | Read | Camera Wi-Fi SSID string — read during handoff to construct `WifiNetworkSpecifier` | [H] | XPN, XBL |
| `E809256A-915C-4967-92E8-53B7D4CAD213` | Camera Wi-Fi Passphrase | Camera Information | Read | WPA2 passphrase — read during handoff | [H] | XPN, XBL |
| `49A12959-DFAA-4EB2-89CE-62548AD948F3` | Camera MAC Address | Camera Information | Read | Used as BSSID in `WifiNetworkSpecifier` for precise AP targeting | [H] | XBL |
| `8B5ECF55-FC6B-40D0-B4C1-76F64E5453C7` | Connected Application Information | Connected Device Information | Write | 1-byte app ID (`0x80` for XApp) + 2-byte version (`0x0101`) | [H] | XBL |
| `0F36EC14-29E5-411A-A1B6-64EE8383F090` | Location and Speed | Current Location | Write | 23-byte LE payload (see §5d) | [H] | LFJ, XBL |
| `C95D91AE-B247-4D6D-8661-7DD5D6A0F85B` | Location Sync Cycle | Current Location | Write | UShort: update interval in seconds | [H] | XBL |
| `AAB609C4-94DD-4D89-BC60-665D5090B828` | Location Sync Setting | Current Location | Write/Read | Enable/disable GPS sync | [H] | XBL |
| `C52EDBCE-1FE2-4ECC-9483-907E6592BE9E` | UTC and Timezone | Current Time | Read | UTC time and timezone for camera clock sync | [H] | XBL |
| `B9BFD37F-CCAD-4D36-A1EE-018E792B3EDF` | Date Time | Current Time | Write | Camera clock synchronization | [H] | XBL |
| `A68E3F66-0FCC-4395-8D4C-AA980B5877FA` | AP State / CHR_IND1 | Camera State | Notify | AP state of camera. In LFJ: SVC_CONF subscription. In XBL: AP_STATE notification. | [H] | LFJ, XBL |
| `BD17BA04-B76B-4892-A545-B73BA1F74DAE` | Transfer State / CHR_IND2 | Camera State | Notify | Transfer progress state | [H] | LFJ, XBL |
| `F9150137-5D40-4801-A8DC-F7FC5B01DA50` | CHR_NOT1 | Camera State (SVC_CONF per LFJ) | Notify | — | [H] | LFJ |
| `049EC406-EF75-4205-A390-08FE209C51F0` | FWUpdate State / CHR_IND3 | Camera Setting | Notify | Firmware update progress. Subscribe alone via `enableNotifyToFWUpdate`. | [H] | LFJ, XBL |
| `E6692C5C-B7CD-44F4-95FC-EDA07CE32560` | Camera Vital State | Camera State | Notify | Camera health/power state | [H] | XBL |
| `34D8C8DE-E2A9-43FF-822C-7D945DD8D8E1` | Card State | Camera State | Notify | SD card insertion state | [H] | XBL |
| `1587B102-0B6D-4B63-9226-66FCC6D17387` | State Error Details | Camera State | Read/Notify | Detailed error state | [H] | XBL |
| `2E27ED9F-5506-41CD-BA48-DAC06669AD95` | File Transaction State | File Transfer | Notify | BLE file transfer transaction progress | [H] | XBL |
| `AC0C799A-FA6C-4DF5-BBC5-BB95CCE7E6EA` | File Partial Data | File Transfer | Write | 120-byte max per chunk (`FILE_PARTIAL_SIZE=120`); sequence numbers 0–65535 | [H] | XBL |
| `F557D96B-8284-4667-8793-B971C1DECA2A` | Connected Device ID Number | Connected Device Information | Write | Device identification number | [H] | XPN, XBL |
| `E8E40D50-A625-4F1D-96ED-8CEC034F5690` | Camera Serial Number | Camera Information | Read | 5 bytes (`CAMERA_SERIAL_NO_SIZE=5`); DATATYPE tag = 1 | [H] | XBL |
| `27870478-94A9-4345-849B-EFA3BF37887F` | Camera Y-Number | Camera Information | Read | Model variant identifier; read during pairing sequence | [H] | XBL |
| `389363E4-712E-4CF2-A72E-BFCF7FB6ADC1` | Camera BLE Protocol Version | Camera Information | Read | BLE protocol version for capability negotiation | [H] | XBL |
| `EB4166B0-9CCA-445E-A4E4-75B3817FD57A` | Connected Device BLE Protocol Version | Connected Device Information | Write | Phone's BLE protocol version | [H] | XBL |
| `CAEDB497-83BF-482C-91EF-91CF6F1216FF` | Image Transfer Setting | Camera Setting | Write | RAF transfer enabled by default (value 1); HEIF disabled by default (value 0) | [H] | XBL |
| `11438C83-CFB0-4511-841B-759E0D2321C8` | Backup State | Camera Setting | Notify | Subscribe together with FILE_TRANSACTION_STATE for backup flow | [H] | XBL |
| `4B3A413C-230F-42BC-B3ED-B1DB2EADEE82` | Restore State | Camera Setting | Notify | Subscribe together with FILE_TRANSACTION_STATE for restore flow | [H] | XBL |
| `2F6CB772-1D69-448E-8819-FE6B5C20B094` | Log Transfer State | Camera Setting | Notify | Subscribe alone for log transfer | [H] | XBL |
| `F90F7D3A-3B64-45C6-AB21-933900184837` | Camera Power Key State | Camera State | Notify | 1-byte power key press state | [H] | XBL |
| `43070F6C-51E0-4887-86A7-5F762BDA5791` | Power Control Request | Camera Control | Write | UShort from PowerControlRequestType enum | [H] | XBL |
| `7170FD5A-56D9-4C19-B043-7A7047D8E1A0` | Remote Boot Setting | Camera Setting | Write/Notify | BLE-triggered camera wakeup | [H] | XBL |
| `9C72C205-5740-4F17-9949-0D3FADF2F67A` | Wakeup Mode | Camera Control | Write | Camera wakeup mode configuration | [H] | XBL |
| `861442AB-B94E-4935-90D9-41E291D91374` | Movie Rec Request | Camera Control | Write | UShort — start/stop video via BLE | [H] | XBL |
| `600655E6-3637-42F1-8FB2-44EFC5C63B13` | Function Launch Request | Camera Control | Write | UShort FunctionLaunchRequestType; response via AP_STATE notification | [H] | XBL |
| `8814441B-1D7B-4046-891D-D8F80864CC8E` | Pairing Smart Device Num | Connected Device Information | Read | Number of paired smart devices on camera | [H] | XBL |
| `B1307521-7AC5-4199-AAEE-9D094781CE69` | FW Update Request | Camera Setting | Write | Initiates BLE-based firmware update (v1: do not implement per CLAUDE.md) | [H] | XBL |
| `A80BE3F8-8BCB-4ADD-A725-170B7A53ADC9` | Connected Device Image Receive State | Connected Device Information | Write | Phone signals readiness to receive images | [H] | XBL |
| `7EDE1988-B27E-43FC-80F4-6FEC994F0552` | Connected Device Disconnected Reason | Connected Device Information | Write | UShort disconnect reason — written before intentional disconnect | [H] | XBL |
| `00002902-0000-1000-8000-00805F9B34FB` | CCCD (Client Characteristic Config Descriptor) | Standard GATT | Write | `0x0001` = enable notifications, `0x0002` = indications, `0x0000` = disable | [H] | XBL |

### 5d. LocationAndSpeed Payload Layout (23 bytes, little-endian)

Written to `CHARACTERISTIC_FF_LOCATION_AND_SPEED` (`0F36EC14-...`) in `SERVICE_FF_CURRENT_LOCATION`.

| Bytes | Field | Type | Encoding |
|-------|-------|------|----------|
| 0–3 | latitude | i32 LE | degrees × 10,000,000 |
| 4–7 | longitude | i32 LE | degrees × 10,000,000 |
| 8–11 | altitude | i32 LE | meters, rounded |
| 12–15 | speed | i32 LE | m/s × 100, rounded |
| 16–17 | year | u16 LE | UTC year |
| 18 | month | u8 | 1-based |
| 19 | day | u8 | |
| 20 | hours | u8 | UTC |
| 21 | minutes | u8 | |
| 22 | seconds | u8 | |

[H] LFJ, XBL — two independent sources agree on layout. LFJ uses `int32 scaled by 10,000,000` with `fujifilm_time_t` struct; XBL confirms via `LocationAndSpeed.java` decompilation.

### 5e. BLE Operational Constants

| Constant | Value | Notes | Conf | Sources |
|----------|-------|-------|------|---------|
| GATT operation timeout | 5 s | Per characteristic read/write | [H] | XPN, XBL |
| BLE lock timeout | 20 s | Global BLE lock (e.g. shooting request lock) | [H] | XBL |
| Notification subscription retry count | 5 | CHECK_NOTIFY_INDICATORS_RETRY_COUNT | [H] | XBL |
| Notification subscription retry interval | 500 ms | Between retries | [H] | XBL |
| BLE service poll timer | 100 ms | BTCameraService internal timer interval | [H] | XPN, XBL |
| Pairing bond read retry limit | 20 | BONDING_READ_RETRY_LIMIT | [H] | XBL |
| Pairing bond read retry delay | 1000 ms | Between read retries | [H] | XBL |
| BLE file transfer chunk size | 120 bytes | FILE_PARTIAL_SIZE | [H] | XPN, XBL |
| BLE file transfer last sequence number | 65535 (0xFFFF) | LAST_SEQUENCE_NO | [H] | XPN, XBL |
| XApp application identifier byte | `0x80` | Written to CONNECTED_APPLICATION_INFORMATION. Value −128 as signed byte. | [H] | XBL |
| XApp BLE connected version | `0x0101` (257) | Written alongside app identifier | [H] | XBL |

**Notification subscription groupings** (subscribe the correct group before each operation):

| Group | Characteristics to subscribe |
|-------|------------------------------|
| Core / always-on | TRANSFER_STATE, CAMERA_VITAL_STATE, CARD_STATE, CONNECTED_ERROR_STATE, STATE_ERROR_DETAILS |
| Backup flow | FILE_TRANSACTION_STATE + BACKUP_STATE |
| Restore flow | FILE_TRANSACTION_STATE + RESTORE_STATE |
| FW update | FWUPDATE_STATE only |
| Log transfer | LOG_TRANSFER_STATE only |

[H] XBL

---

## 6. Magic Constants and Packet Header Layouts

### 6a. Fujifilm Wi-Fi Transport Framing (FCW protocol — `fuji-cam-wifi-tool` framing)

This framing is used by the FCW-based open-source tools (fuji-cam-wifi-tool, libfuji in WIRELESS_COMM mode). It is **distinct** from standard PTP-IP framing. Frameport v1 should implement the standard PTP-IP packet format (§6c) and confirm on hardware which framing the X-T5 expects.

| Field | Offset | Size | Value |
|-------|--------|------|-------|
| Length prefix | 0 | u32 LE | Total frame length including this 4-byte prefix (i.e., `payload_size + 4`) |
| Index | 4 | u16 LE | 1=normal single-part; 2=second part of two-part message; 0=terminate sentinel |
| Message type / opcode | 6 | u16 LE | Fuji command opcode |
| Transaction ID | 8 | u32 LE | Monotonically incrementing counter |
| Payload | 12 | N bytes | Opcode-specific |

[H] FCW

**Success acknowledgement pattern:** Camera returns exactly 8 payload bytes: `{03 00 01 20}` followed by the 4-byte LE transaction ID of the acknowledged command. [H] FCW

**Error / busy sentinel:** A 4-byte receive of `FF FF FF FF` (0xFFFFFFFF) indicates camera error or busy state. [H] FCW, LFJ

**Terminate session sentinel:** After sending stop (0x1003), client sends raw 4 bytes `FF FF FF FF` (not framed). [H] FCW

**Two-part property write protocol:** Writing a property uses two messages sharing the same transaction ID: part 1 (index=1) carries the 4-byte LE property code as payload; part 2 (index=2) carries the 4-byte LE new value. Both use opcode 0x1016. Success response only sent after part 2. [H] FCW

**Status poll payload:** `0x1015` with payload `{12 D2 00 00}` and transaction ID 0. Response: 8-byte header, then `u16 count`, then `count × {u16 property_code, u32 value}`. [H] FCW

**Session mode selector bytes** (used in FCW init handshake):

| Byte 0 | Mode | Use |
|--------|------|-----|
| `0x24` | Remote / camera-control | Shutter, liveview — Frameport v1 target mode |
| `0x21` | Receive (image browse) | Browse images on camera |
| `0x22` | Browse | — |
| `0x31` | Geo | GPS mode |

Full mode selector payload pattern: `{mode_byte, 0xDF, 0x00, 0x00}`. [H] FCW

### 6b. Fujifilm PTP-IP Registration Magic Header

**This 24-byte blob is the fixed GUID/version field of the Fuji-specific Init_Command_Request.** It is a protocol interoperability constant, not a secret or credential.

```
01 00 00 00  F2 E4 53 8F  AD A5 48 5D  87 B2 7F 0B
D3 D5 DE D0  02 78 A8 C0
```

[H] FCW — observed as the leading bytes of the 78-byte registration message in the open-source `fuji-cam-wifi-tool`.

**Alternatively expressed as the `version` field:** `0x8F53E4F2` (little-endian u32 at bytes 4–7 of the FujiInitPacket). This is the value that replaces the standard PTP-IP version number. [H] LFJ

**Full FujiInitPacket layout (82 bytes = 0x52):**

| Offset | Size | Field |
|--------|------|-------|
| 0 | u32 LE | Packet length = `0x52` |
| 4 | u32 LE | Packet type = `PTPIP_INIT_COMMAND_REQ = 0x01` |
| 8 | u32 LE | Version magic = `0x8F53E4F2` |
| 12 | 16 bytes (4×u32) | GUID — generate a stable random GUID per installation; LFJ test code uses `{0x5d48a5ad, 0xb7fb287, 0xd0ded5d3, 0x0}` (placeholder only) |
| 28 | 4 bytes | Host IP address (from XPN analysis) |
| 32 | 40 bytes | UTF-16LE friendly name (zero-padded; max 20 visible chars in this allocation) |
| 72 | 2 bytes | Protocol version |

[H] LFJ, XPN

**Init response (FujiInitResp / PtpFujiInitResp) layout (68–70 bytes):**

| Offset | Size | Field |
|--------|------|-------|
| 0 | 4 × u32 = 16 bytes | Unknown fields (x1–x4) |
| 16 | 54 bytes | Camera name (UTF-16LE, null-padded) |

[H] LFJ. XPN confirms response buffer is `0x44 = 68` bytes; length `0x0C` (12 bytes) in the receive buffer triggers an error path.

**Post-init delay:** After successful FujiInitPacket exchange in WIRELESS_COMM mode, wait at least **50 ms** before sending OpenSession. [H] LFJ

**Goodbye / Close_Request packet (8 bytes):**

```
08 00 00 00  FF FF FF FF
```

Length = 8, type = `0xFFFFFFFF`. Sent then `shutdown(fd, 2)` then `close(fd)`. [H] LFJ, XPN

### 6c. Standard PTP-IP Packet Type Codes

| Code | Name |
|------|------|
| `0x01` | PTPIP_INIT_COMMAND_REQ |
| `0x02` | PTPIP_INIT_COMMAND_ACK |
| `0x03` | PTPIP_INIT_EVENT_REQ |
| `0x04` | PTPIP_INIT_EVENT_ACK |
| `0x05` | PTPIP_INIT_FAIL |
| `0x06` | PTPIP_COMMAND_REQUEST |
| `0x07` | PTPIP_COMMAND_RESPONSE |
| `0x08` | PTPIP_EVENT |
| `0x09` | PTPIP_DATA_PACKET_START |
| `0x0A` | PTPIP_DATA_PACKET (intermediate) |
| `0x0B` | PTPIP_CANCEL_TRANSACTION |
| `0x0C` | PTPIP_DATA_PACKET_END |
| `0x0D` | PTPIP_PING |
| `0x0E` | PTPIP_PONG |

[H] LFJ, FJH

### 6d. PTP-IP Operation Request Container Layout

All fields little-endian.

| Offset | Size | Field | Notes |
|--------|------|-------|-------|
| 0 | u32 | length | Total packet bytes including header |
| 4 | u32 | type | `0x00000006` (PTPIP_COMMAND_REQUEST) |
| 8 | u32 | data_phase | `1` = no data; `2` = initiator sends data |
| 12 | u16 | code | PTP opcode |
| 14 | u32 | transaction_id | Post-increment counter; wraps `0xFFFFFFFF → 1` (value −1 / `0xFFFFFFFF` is never transmitted) |
| 18 | 4 × N | params | Up to 5 × u32 params |

Base size (no params) = 18 bytes. With N params = 18 + 4N bytes. [H] LFJ, FJH, FLK, XPN

### 6e. PTP-IP Data Phase Packets

**DataStart (type 0x09):**

| Offset | Size | Field |
|--------|------|-------|
| 0 | u32 | length = 20 |
| 4 | u32 | type = `0x09` |
| 8 | u32 | transaction_id |
| 12 | u64 | payload_length (total data bytes to follow) |

[H] LFJ, FJH

**DataEnd (type 0x0C):**

| Offset | Size | Field |
|--------|------|-------|
| 0 | u32 | length = 12 + data_length |
| 4 | u32 | type = `0x0C` |
| 8 | u32 | transaction_id |
| 12 | N | payload bytes |

[H] LFJ, FJH

**Response (type 0x07):**

| Offset | Size | Field |
|--------|------|-------|
| 0 | u32 | length |
| 4 | u32 | type = `0x07` |
| 8 | u16 | response code (RC) |
| 10 | u32 | transaction_id |
| 14 | 4 × N | result params (0 to 5) |

Minimum 14 bytes. Param count = `(length − 14) / 4`. [H] LFJ, FJH

**PTP-IP receive sequence:** For commands that return data: receive (1) DataStart (`0x09`), then (2) DataEnd (`0x0C`) containing payload, then (3) Response (`0x07`). For commands with no data: only Response is sent. Discriminate by type field of first received packet. [H] LFJ, FJH, FLK

### 6f. PTP-USB Bulk Container Layout

For USB transport only (not PTP-IP). All fields little-endian.

| Offset | Size | Field | Container type values |
|--------|------|-------|-----------------------|
| 0 | u32 | length | — |
| 4 | u16 | container_type | 1=Command, 2=Data, 3=Response, 4=Event |
| 6 | u16 | code | opcode or RC |
| 8 | u32 | transaction_id | — |
| 12 | 4 × N | params (command) or payload (data) | — |

USB base header = 12 bytes. Max USB read chunk = 512 bytes. Max TCP packet size = `0xFFFF` (65535). USB bulk transfer chunk for large files: 512 KB (filmkit WebUSB) or 1 MB (XApp FTLPTP.so). [H] LFJ, FJH, FLK, XPN

**Fujifilm USB vendor ID:** `0x04CB` (1227 decimal) [H] LFJ, FLK, XPN, XBL

**Known Fujifilm USB product IDs:**

| PID | Model |
|-----|-------|
| `0x02E3` | X-T30 |
| `0x02E5` | X100V |
| `0x02E7` | X-T4 |
| `0x0305` | X100VI |

[M] FLK

### 6g. Live-View Frame Header Layout (port 55742)

Two related descriptions from different sources:

**FCW description (framing-based):**
After the 4-byte Fuji transport length prefix, the frame payload starts with a 14-byte header:

| Offset in payload | Size | Field |
|-------------------|------|-------|
| 0–3 | u32 LE | Always 0 (observed) |
| 4–7 | u32 LE | Frame counter (increments per frame) |
| 8–13 | 6 bytes | Zeros |
| 14+ | N | JPEG data (starts with `FF D8`) |

Wireshark confirms JPEG at raw TCP offset 18 (4-byte length prefix + 14-byte header). [H] FCW

**XApp description (PTP-IP EndData framing):**
JPEG extraction offset = `secondaryLen + 18`, where `secondaryLen` is a LE-u32 at byte offset 12 of the receive buffer. The constant 18 corresponds to PTP-IP EndData header overhead. [H] XPN, XBL

These are consistent: the 14-byte FCW frame header + 4-byte transport prefix = 18 bytes matches the XApp offset formula when `secondaryLen = 0`.

**Live-view buffer size:** 204,800 bytes (`REMOTE_RECEIVE_FILE_SIZE`). XApp allocates this per frame on the Java side. [H] XPN, XBL

**Live-view activation:** Set SDK function mode `IMAGE_LIVE_VIEW = 22` before opening the through-picture channel. [H] XPN, XBL

### 6h. PTP String Wire Encoding

Standard PTP strings (both over USB and PTP-IP):

| Offset | Size | Field |
|--------|------|-------|
| 0 | u8 | numChars (character count including null terminator) |
| 1 | numChars × 2 | UTF-16LE code units, including trailing `0x0000` |

An empty string is a single byte `0x00`. The `numChars` field counts the null terminator (`numChars = str.length + 1`). [H] FJH, FLK

### 6i. Session Lifecycle and Timing Summary

| Parameter | Value | Conf | Sources |
|-----------|-------|------|---------|
| PTP SessionID | Always `1` — hardcoded, never negotiated | [H] | FLK, XPN, XBL |
| OpenSession transaction ID | Must be `0` | [H] | FJH |
| Transaction ID wrap sentinel | `0xFFFFFFFF → 1` (value `0xFFFFFFFF` is never transmitted) | [H] | XPN, XBL |
| Post-init delay (WIRELESS_COMM) | 50 ms before OpenSession | [H] | LFJ |
| Fujifilm response_wait_default | 3 poll iterations | [H] | LFJ |
| ClientState write response_wait | 255 (long wait for camera UI dialog) | [H] | LFJ |
| Camera open retry | Up to 6 iterations, 5000 ms apart (~30 s total) | [H] | XPN, XBL |
| BUSY retry (function mode negotiation) | 3 loops × 5 passes × 100 ms = up to 1.5 s total | [H] | XPN, XBL |
| Ack BUSY retry | 500 ms × 5 iterations = 2.5 s total | [H] | XPN, XBL |
| Ack fatal response codes | `0x201D`, `0x201E`, `0x2000` | [H] | XPN, XBL |
| Wi-Fi network request retries | Up to 3 on `onUnavailable` | [H] | XPN, XBL |
| GetPartialObject chunk size (Wi-Fi) | `0x100000` bytes (1 MB) | [H] | LFJ |
| Preset slot count | 7 (C1–C7), 1-indexed | [H] | FLK |
| Preset slot inter-write delay | 100 ms after writing `0xD18C` | [H] | FLK |
| Virtual object handle (version negotiation) | `0xFFFFFFF1` | [M] | LFJ |
| Wi-Fi object handle in MULTIPLE_TRANSFER mode | Always `1` (camera swaps to next image after each download) | [H] | LFJ |

---

## 7. Conflicts and Uncertainty

Implementers must verify the following on real X-T5 hardware before relying on these values.

### 7a. Film Simulation Code Numbering: FCW vs LFJ/FLK

**Conflict:** FCW assigns film simulation codes 1–16 in a different order than LFJ and FLK for the Pro Neg and Monochrome variants.

| Code | FCW mapping | LFJ / FLK mapping |
|------|-------------|-------------------|
| `0x04` | Monochrome | Pro Neg Hi |
| `0x05` | Sepia | Pro Neg Std |
| `0x06` | Pro Neg Hi | Monochrome |
| `0x07` | Monochrome+Y | Monochrome+Ye |
| `0x08` | Monochrome+R | Monochrome+R |
| `0x09` | Monochrome+G | Monochrome+G |
| `0x0A` | Pro Neg Std | Sepia |

**Recommendation:** Trust LFJ + FLK (two independent sources, including FLK which was verified on X100VI hardware in 2026). The FCW mapping is from an older codebase targeting X-T10/X-T100. [L] FCW vs [H] LFJ+FLK.

### 7b. Property Code 0xD018 (FCW) vs 0xD00B (FLK): Image Quality/Format

**Conflict:** FCW documents ImageFormat at `0xD018`. FLK documents ImageQuality at `0xD00B`. These may be different properties on different camera models or different firmware versions, or the same property at a relocated code. Both values may be valid simultaneously.

**Recommendation:** Read both on the target camera via GetDevicePropDesc to confirm which is supported. [L]

### 7c. Property Code 0xD019: RecModeEnable (FCW) vs ShadowHighlight (FLK)

**Conflict:** FCW documents `0xD019` as RecModeEnable (video record availability, 0/1). FLK documents `0xD019` as ShadowHighlight (preset tone control). These are likely different cameras/firmware versions.

**Recommendation:** Do not use `0xD019` without first reading the property descriptor on the connected camera. [L]

### 7b bis. D185 Profile Size: 601 (LFJ) vs 625 (FLK) vs 629 (LFJ/FLK X Raw Studio)

**Conflict:** LFJ reports the camera-returned D185 profile as 601 bytes (X-H1, older cameras, n_props=0x17). FLK reports it as 625 bytes on X100VI (X-Processor 5, 2026-03, confirmed via hardware). X Raw Studio produces 629 bytes. The string length quirk (camera writes 8 instead of 9 for an IOP codes field) is documented by LFJ.

**Recommendation:** Use the self-describing `numParams` field at offset 0 to calculate the parameter array start: `param_start = total_length - numParams * 4`. Do not assume a fixed total size. The 625-byte format with the NativeIdx field table is the confirmed current format for X-Processor 5 cameras. [H for structure, L for size on X-T5 specifically]

### 7c bis. FCW Wi-Fi Framing vs Standard PTP-IP Framing

**Conflict:** The FCW / libfuji project uses a Fujifilm-proprietary transport framing (4-byte length prefix, 8-byte message container) that differs from standard PTP-IP framing. XApp's native library (`libFTLPTPIP.so`) uses standard PTP-IP packet types (0x01–0x0E) with the Fuji version magic `0x8F53E4F2`. It is not confirmed which framing the X-T5 uses natively when connecting from a clean-room app.

**Recommendation:** Implement standard PTP-IP framing (§6c–6f) as the primary path, since XApp (the official app) uses it and it conforms to the CIPA DC-005 spec. If connection fails, investigate whether the FCW custom framing is required as a fallback. Verify on X-T5 hardware. [L]

### 7d. Event Socket: Fuji-Framed vs Raw

**Uncertainty:** No source provides a documented event packet structure for port 55741. FCW logs raw bytes without parsing. LFJ references it as unsolicited PTP events but provides no event type table for Fuji-specific events. XPN shows the event channel is opened lazily from `InitiateOpenCapture` only.

**Recommendation:** Open the event channel, read raw bytes, and log them during early development. Implement `EventsList` polling (0xD212) as the primary event mechanism, which is confirmed and documented. [L for raw event structure]

### 7e. BLE Pairing: Token-Based (LFJ) vs Key-Based (XBL)

**Uncertainty:** LFJ describes pairing as: read 4-byte token from manufacturer advertisement data (type byte = `0x02`), write token to CHR_PAIR. XBL describes: read a sequence of characteristics, write PAIRING_KEY (4 bytes) derived from `pairingKeyBuffer`. It is unclear whether these are the same mechanism described differently, or distinct pairing flows for different generations of firmware.

**Recommendation:** Implement the XBL flow (newer source, X-series 2018+ generation, matches XApp 2.7.5 which targets current hardware). The manufacturer advertisement type byte check (`0x02`) from LFJ is likely still valid as a filter for pairing-mode detection. [L]

### 7f. Through-Picture JPEG Offset Formula

**Uncertainty:** XPN gives the JPEG start formula as `secondaryLen + 18`, where `secondaryLen` is a LE-u32 at byte offset 12 of the receive buffer. FCW gives a fixed 14-byte header. These are consistent only when `secondaryLen = 0`. If `secondaryLen` is sometimes non-zero, the FCW-derived fixed offset would be wrong.

**Recommendation:** Parse `secondaryLen` dynamically per the XApp formula. Use FCW's frame counter extraction (bytes 4–7 of the payload) only after accounting for the variable `secondaryLen`. [M for XPN formula, L for FCW fixed offset]

### 7g. Single-Source or Unverified Items (Low Confidence)

| Item | Value | Concern |
|------|-------|---------|
| Fuji event codes 0xC001/0xC004 | PreviewAvailable / ObjectAdded | LFJ source explicitly notes "most appear to be inaccurate or misplaced". [L] |
| Vendor opcode 0x900D vs 0x9042 | SendObject2 vs FmSendObject2 | FCW/FJH use 0x900D; LFJ/XPN use 0x9042 for phone-to-camera partial send. FLK uses 0x900D for USB RAF upload. Context may differ by transport. [L] |
| Object format code `0xFFF1` | PTP_OF_FUJI_FFF1 | Single source (LFJ), medium confidence, no cross-validation. [L] |
| Film simulation codes 0x11–0x14 | Classic Neg, Eterna Bleach Bypass, Nostalgic Neg, Reala Ace | FLK notes "from rawji, may need adjustment for X-Processor 5". [M] |
| Fpcsh version codes 0xDF27–0xDF31 | FW transfer / RemoteEx / GPS gate | Cross-confirmed XPN+XBL but not exercised by Frameport v1. [H for codes, L for v1 relevance] |
| Preset properties D191 (always 0) and D1A5 (always 7) | Unknown semantics | FLK observes these defaults but semantics are unknown. Write the observed defaults; do not omit. [M] |
| Proprietary opcode 0x9805 | FUJI_HIJACK | **MUST NOT USE.** Only exists in patched firmware (fujihack). FJH confirms it does NOT exist in stock Fujifilm cameras. |
| Property 0xD406 | Buffer overflow test artifact | **MUST NOT USE.** Appeared only in a commented-out crash test in fujihack source. Sending large payloads to this code crashes cameras. |
| AWS Cognito, PostHog, Firebase, Google Maps credentials in XApp | Cloud service credentials | **MUST NOT EMBED.** These are Fujifilm proprietary service credentials with no role in Frameport's local-first architecture. |
| RED-compliant camera variant detection | `isREDCompliant` flag | The condition that triggers alternate `*_RED` service UUID selection was not traced to a specific characteristic value in XBL analysis. Implement both UUID sets; probe at connect time. [L] |
| XApp emulator IP 192.168.1.200 | Debug artifact | Not a production value. Production camera IP is always `192.168.0.1`. |

