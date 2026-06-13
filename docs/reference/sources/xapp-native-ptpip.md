# FUJIFILM XApp 2.7.5 — PTP-IP / Wi-Fi Transfer / Liveview Native Stack (Reversed APK)

**Source type:** Reversed proprietary Android APK (XApp 2.7.5, com.fujifilm.xapp, versionCode 64, Play timestamp 2026-05-15)
**Languages:** Kotlin (Android app layer), C++ (native libraries: `libFTLPTPIP.so`, `libFFIR.so`, `libXAPI.so`, `FTLPTP.so`), Java (deobfuscated JNI bridge classes from JADX/R8)
**Analysis tools:** Ghidra pseudo-C decompilation of native `.so` files; JADX deobfuscation of the Kotlin/Java layer

**Role for Frameport:** This source provides the highest-fidelity interoperability reference for the Fujifilm camera PTP-IP / Wi-Fi transfer / liveview protocol stack. It yields exact TCP port numbers, packet-field layouts, vendor opcode values, BLE GATT UUIDs, session lifecycle sequences, and transfer-path details that are not documented in any public specification. Every numeric constant derived here is treated as a wire-observable interoperability fact and must be re-implemented in original Frameport code (primarily `fuji-ptpip`, `fuji-liveview`, `fuji-transfer`, `fuji-ble-protocol`, `camera/wifi`, `camera/bluetooth`). No decompiled source code has been or may be copied verbatim into Frameport.

---

## Protocol Layers

### 1. PTP-IP Session Lifecycle
**Coverage:** Full
**Key files:** `_deobf/native-ghidra/libFTLPTPIP.so.c`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/ffir/ControlFFIR.java`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/camera_connect/CameraConnectModel.java`

Full TCP socket setup, Init_Command_Request/Ack handshake, OpenSession, function-mode negotiation, and graceful Close_Request recovered from Ghidra pseudo-C and deobfuscated Java. Three TCP channels on ports 55740/55741/55742. SessionID is hardcoded 1. Function-mode negotiation uses up to three sequential retry loops of 5 passes each with 100 ms delays and BUSY (4102) retries.

### 2. PTP-IP Liveview (Through-Picture)
**Coverage:** Full
**Key files:** `_deobf/native-ghidra/libFTLPTPIP.so.c`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/ui/liveview/FinderView.java`, `_deobf/native-ghidra/libFFIR.so.c`

Dedicated TCP channel on port 55742. Double-slot 200,000-byte native ring buffer with condvar signalling. Java polling loop allocates 204,800-byte buffer per frame. JPEG extracted at offset `secondaryLen+18` from the receive buffer. Mode code `SDK_MODE_IMAGE_LIVE_VIEW=22` must be set before polling begins.

### 3. Transfer
**Coverage:** Full
**Key files:** `_deobf/native-ghidra/libFTLPTPIP.so.c`, `_deobf/native-ghidra/libFFIR.so.c`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/ffir/ControlFFIR.java`

Wi-Fi object transfer uses `GetSpecifiedObjectHandles` (Fujifilm extension, maps to PTP GetObjectHandles 0x1007) → `GetExtensionObjectInfo` → `GetExtensionThumb` / `ReadThumbnail` → `GetExtensionPartialObject` for chunked download. Fujifilm uses vendor extension object info and partial-object opcodes rather than bare `GetObject` for normal image retrieval. MTP `GetObjectPropList`/`Value`/`SetValue` available for metadata.

### 4. PTP-USB
**Coverage:** Full
**Key files:** `_deobf/native-ghidra/FTLPTP.so.c`, `_deobf/native-ghidra/libXAPI.so.c`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/xsdk/XSDK.java`

`FTLPTP.so` uses `libusb_wrap_sys_device(NULL, fd, &handle)` for rootless USB access from an Android-granted file descriptor. Same PTP container format as Wi-Fi. Transaction ID counter at `CDeviceLayer+4` wraps `0xFFFFFFFF→1`. Interface and bulk endpoint addresses parsed from raw descriptor bytes passed from Java `UsbManager`.

### 5. Discovery
**Coverage:** Full
**Key files:** `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/BTConstansKt.java`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/camera_connect/WiFiHandOverService.java`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCamera.java`

BLE-only discovery path: Android BLE scan filtered by Fujifilm manufacturer ID (0x04D8 = 1240 decimal). After GATT connection, SSID read from characteristic `BF6DC9CF-3606-4EC9-A4C8-D77576E93EA4` and passphrase from `E809256A-915C-4967-92E8-53B7D4CAD213`. Wi-Fi handover uses `WifiNetworkSpecifier` with `PATTERN_LITERAL` SSID+BSSID+WPA2, `NET_CAPABILITY_INTERNET` removed. Up to 3 retries on `onUnavailable`.

### 6. BLE GATT
**Coverage:** Full
**Key files:** `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/BTConstansKt.java`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCamera.java`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCameraService.java`

14 Fujifilm-proprietary GATT services plus standard Device Information service. Remote shutter via GATT write to characteristic `7FCF49C6-4FF0-4777-A03D-1A79166AF7A8` in service `6514EB81-4E8F-458D-AA2A-E691336CDFAC` with S0/S1/S2 byte values. BLE service timer runs at 100 ms. 29 `QueueRequestId` command types. GATT timeout 5 seconds per operation.

### 7. Camera Properties
**Coverage:** Partial
**Key files:** `_deobf/native-ghidra/libFTLPTPIP.so.c`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/ffir/ControlFFIR.java`

`GetDevicePropDesc`/`All`, `GetDevicePropValue`, `SetDevicePropValue`, `ResetDevicePropValue` all exposed. Fpcsh version codes gate capability negotiation (0xDF21–0xDF31 range). `SetCameraEvent` (opcode 0x9060) sends property change events with `dataPhase=2`; payload is 6 bytes for non-string types, `stringLength+6` for type 0xFFFF. Bulk descriptor dump via `GetDevicePropDescAll` available. Exact property codes not extracted — require Ghidra hexdump.

### 8. Film Simulation
**Coverage:** Absent
**Key files:** (none found)

Film simulation property codes are not exposed in the decompiled Java layer or the recovered Ghidra pseudo-C at this analysis depth. They are expected to be among the camera device property codes set via `SetDevicePropValue` but the specific property code values were not found in string tables.

### 9. Session Lifecycle
**Coverage:** Full
**Key files:** `_deobf/native-ghidra/libFTLPTPIP.so.c`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/camera_connect/CameraConnectModel.java`, `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/ffir/ControlFFIR.java`

Full lifecycle: `FTL_Init` → `FTL_Open` (connect TCP 55740, send Init_Command_Request, receive Init_Command_Ack) → `FTL_PTP_OpenSession` (opcode 0x1002, SessionID=1) → `SetFunctionMode` → [operation] → `FTL_PTP_CloseSession` (opcode 0x1003) → `FTL_Close` → `FTL_Exit`. `cameraOpen()` retries up to 6 iterations over 30 seconds (OPEN_TIMEOUT=30000, ASYNC_OPEN_INTERVAL=5000). Event and through-picture channels opened lazily.

---

## Protocol Facts

All 57 facts from the analysis are rendered below, grouped by category.

### PTP-IP Handshake (10 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Command channel TCP port | `55740 (0xD9BC)` | Camera listens on TCP port 55740. Java enum `PORT_BASE_SOCK=55740`. `MngPTP::Open` connects to camera IP on this port first. `g_OpenSocket` slot index = `portNo - 0xD9BC = 0`. | `ControlFFIR.java:348; libFTLPTPIP.so.c:306-337` | high |
| Event channel TCP port | `55741 (0xD9BD)` | Java enum `PORT_THROUGH_SOCK=55741` but native code uses port `0xD9BD=55741` for the event channel (`EventReciever` thread). Note: Java enum labels `PORT_THROUGH_SOCK` and `PORT_EVENT_SOCK` are swapped relative to their native port usage — a confirmed symmetric label swap. `g_OpenSocket` slot index = 1. | `ControlFFIR.java:349; libFTLPTPIP.so.c:9911-9944` | high |
| Through-picture (liveview) channel TCP port | `55742 (0xD9BE)` | Java enum `PORT_EVENT_SOCK=55742` but native code uses `0xD9BE=55742` for the through-picture channel (`ThroughPictureReciever` thread). Label swap confirmed. `g_OpenSocket` slot index = 2. | `ControlFFIR.java:350; libFTLPTPIP.so.c:10122-10153` | high |
| Camera IP address | `192.168.0.1 (0xC0A80001)` | Production camera IP hardcoded as `0xC0A80001` packed big-endian. Stored at `MngPTP+0x58`. Emulator/debug override is `192.168.1.200`. Java creates TCP socket to this IP before passing fd to native via `SetOpenSocket`. | `ControlFFIR.java:687; libFTLPTPIP.so.c:1199-1294` | high |
| Init_Command_Request packet size | `0x52 (82 bytes)` | The packet is session-specific (not fixed): header words from ROM constants at `0x11D3D0-0x11D3D8`, 16-byte GUID from `0x11D3E0+0x11D3E8`, 4-byte host IP from `MngTCPIP::GetHostAddress`, 40-byte zero-padded UTF-16LE friendly-name (source is 27 bytes from `this[0x5c..0x76]`), 2-byte protocol version from `0x11D420`. Single `Send` call. | `libFTLPTPIP.so.c:9537-9635` | high |
| Init_Command_Ack packet size | `0x44 (68 bytes)` | Receive buffer is `0x44` bytes. A received length of `0x0C` triggers error path. The retry constant `0x50000000C` encodes `{length=0x0C, type=0x0005}` where type `0x0005` is Init_Command_Ack. Status `0x2019` triggers 500 ms retry, max 5 iterations (2.5 s total). Fatal codes: `0x201D`, `0x201E`, `0x2000`. On success, TransactionID counter at `this+0x14` is zeroed; Ack payload stored at `this+0x178..0x1AA`. | `libFTLPTPIP.so.c:9643-9706` | high |
| g_OpenSocket array layout | `3 × 8-byte slots` | Global array at `PTR_g_OpenSocket_00163170`. Each slot is 8 bytes: `u32 socketFd` at offset `+0`, `u32 portNo` at offset `+4`. Indexed by `portNo - 0xD9BC`. If slot fd is non-zero, `close()` is called before overwrite. The JNI bridge `dup()`s the Android fd before storing — only the dup'd fd is passed to `FTL_SetOpenSocket`; original fd retained by Java. | `libFTLPTPIP.so.c:306-337; libFFIR.so.c:5520-5539` | high |
| PTP OpenSession opcode and SessionID | `opcode 0x1002, SessionID=1` | `FTL_PTP_OpenSession` hardcodes `SessionID=1` (ROM literal at line 2561, not negotiated). Calls `MngPTP::ExecPTPOperation` with opcode `0x1002`. CloseSession uses opcode `0x1003`. | `libFTLPTPIP.so.c:2540-2578` | high |
| Event and through-picture channel open timing | `lazy, not from MngPTP::Open` | Neither the event channel (port 55741) nor the through-picture channel (port 55742) is opened during the initial handshake. Both are opened lazily on first use. The event channel is opened from `FTL_PTP_InitiateOpenCapture` and `FTL_PTP_InitiateOpenCapturePhase2` only. Both retry every 1 second and return `0xFFFFFFFF` on timeout. | `libFTLPTPIP.so.c:9911-9944, 10122-10153; libFTLPTPIP.so.c:6662, 6827` | high |
| Close_Request packet size and timeout | `8 bytes, 5000 ms` | `MngTCPIP::Close` sends 8 bytes from ROM buffer `DAT_001723c0` with 5000 ms timeout, then calls `shutdown(fd, 2)` and `close(fd)`, resets fd to `0xFFFFFFFF`. Early-exit guard: if `fd == -1`, returns 0 immediately. The 8-byte magic is detected on the receive side (line 11890) and throws `int 0x24`. | `libFTLPTPIP.so.c:11557-11578` | high |

### PTP Container (2 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| PTP-IP Operation Request packet format | `length(u32)+type=1(u16)+opcode(u16)+transactionID(u32)+params(4*N bytes)` | Assembled byte-by-byte in `CDeviceLayer::execPTPCommandPhase`. `transactionID` is a post-increment value from counter at `CDeviceLayer+4`. Counter wraps `0xFFFFFFFF→1` (value -1 skipped). PTP Response container uses `containerType=3`. Data container uses `containerType=2`. | `FTLPTP.so.c:1302-1371; FTLPTP.so.c:1303` | high |
| Through-picture receive buffer JPEG extraction offset | `secondaryLen + 18` | `secondaryLen` is a LE-uint32 at byte offset 12 of the receive buffer. JPEG data starts at `secondaryLen+18`. `outerLen` is read at byte offset 1 (not 0) during normal loop — terms cancel algebraically. Native ring buffer holds two 200,000-byte slots; Java polling buffer is 204,800 bytes (`REMOTE_RECEIVE_FILE_SIZE`). | `FinderView.java:361-372; libFTLPTPIP.so.c:13114-13142` | high |

### Opcodes (14 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| InitiateCapture (standard PTP) | `0x100E` | `FTL_PTP_InitiateCapture` sends opcode `0x100E` with `StorageID + ObjectFormatCode` parameters. Serialised on `mSDKCommunication` in `ControlFFIR`. Java cancel flag `DAT_001d0110&1` checked in JNI stub before forwarding. | `libFTLPTPIP.so.c:3499-3563; ControlFFIR.java:1736` | high |
| OpenSession (standard PTP) | `0x1002` | PTP standard opcode. Used by `FTL_PTP_OpenSession` with hardcoded `SessionID=1`. | `libFTLPTPIP.so.c:2540-2578` | high |
| GetObjectHandles (standard PTP) | `0x1007` | Wire opcode used by `FTL_PTP_GetObjectHandles`. XSDK internal code `0x1604` maps to this at translation loop `libXAPI.so.c:16161-16183`. Java calls `GetSpecifiedObjectHandles` via `ControlFFIR` or `XSDK.getSpecifiedObjectHandlesForUSB`. | `libXAPI.so.c:7265, 16161-16183` | high |
| GetNumObjects (standard PTP) | `0x1006` | Wire opcode for object count. XSDK internal code `0x1603` maps to this. Java calls `XSDK_GetSpecifiedObjectCount`. | `libXAPI.so.c:7224, 16161-16183` | high |
| LockS1Lock (Fujifilm vendor) | `0x9026` | Half-shutter press simulation. Single `AFArea` uint32 parameter. `arg8` is a zeroed stack buffer (not null). Debug log format `%08Xh` confirmed. | `libFTLPTPIP.so.c:7319-7357` | high |
| UnlockS1Lock (Fujifilm vendor) | `0x9027` | Half-shutter release. Five parameter slots all zero. | `libFTLPTPIP.so.c:7362-7397` | high |
| InitiateMovieCapture (Fujifilm vendor) | `0x9020` | Parameters: `[StorageID, ObjectFormatCode]`, `numParams=2`. TransactionID saved at `MngPTP+0x20`. `TerminateMovieCapture` (0x9021) reads this slot and zeros it after use. | `libFTLPTPIP.so.c:7097-7219` | high |
| TerminateMovieCapture (Fujifilm vendor) | `0x9021` | Reads `TransactionID` from `MngPTP+0x20` (saved by 0x9020) and zeros it after use. | `libFTLPTPIP.so.c:7199-7213` | high |
| SetCameraEvent (Fujifilm vendor) | `0x9060` | `dataPhase=2`. Payload size = 6 bytes for non-string types; `stringLength+6` for type `0xFFFF`. JNI marshalling uses `GetIntField` (not `GetShortField`) for all three fields (`eventCode`, `dataType`, `valueStringLength`). | `libFTLPTPIP.so.c:9028-9228` | high |
| VendorExtensionOperation (Fujifilm vendor) | `0x9803–0x9805 (range)` | Generic vendor-extension opcode escape. Three codes confirmed (`0x9803`, `0x9804`, `0x9805`) in `ExecPTPOperation`. Also present: `0x9040`, `0x9042`, `0x9053`, `0x9054`, `0x9056`, `0x9060` in the 39-entry opcode table. | `libFTLPTPIP.so.c (multiple sites); ANALYSIS.md:F09` | high |
| InitiateOpenCapture / Phase1 / Phase2 (Fujifilm/standard) | `0x101C` | Two wrappers share this opcode: `FTL_PTP_InitiateOpenCapture` (line 6649) and `FTL_PTP_InitiateOpenCapturePhase1` (line 6782). Phase2 uses `FTL_PTP_InitiateOpenCapturePhase2`. `TerminateOpenCapture` (0x1018) reads TransactionID from `MngPTP+0x1C`. | `libFTLPTPIP.so.c:6649, 6782` | high |
| FmSendObject (Fujifilm vendor — phone-to-camera) | `0x9041` | Phone-to-camera object send. Opcode `0x9041` is `FmSendObject` (corrected: originally mislabeled as a property-read). Also: `FmSendObjectInfo` (0x9040), `FmSendPartialObject` (0x9042). | `libFTLPTPIP.so.c (multiple sites); ANALYSIS.md:F10` | high |
| MTP GetObjectPropList / GetObjectPropValue / SetObjectPropValue | `FTL_MTP_* group` | Three MTP extension opcodes layered on top of PTP-IP for metadata operations. Exposed as `FTL_MTP_GetObjectPropList`, `FTL_MTP_GetObjectPropValue`, `FTL_MTP_SetObjectPropValue` in `libFTLPTPIP` and mirrored as `SDK_*` wrappers in `libFFIR`. | `NATIVE-MAP.md; nm -D libFTLPTPIP.so` | high |
| StepFnumber / StepShutterspeed / StepExposureBias (Fujifilm vendor) | `0x902C / 0x902D / 0x902E` | Direction parameter in `local_40[0]`, identical structure across all three. Used by `RemoteAPICall` for remote exposure control. | `libFTLPTPIP.so.c:7402-7518` | high |

### ExecPTPOperation Coverage (1 fact)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| ExecPTPOperation opcode count | `39 distinct opcodes` | 39 opcodes in `ExecPTPOperation` confirmed (not 23 or 25 as earlier estimates). Vendor range is `0x9020–0x9060`. Additional standard opcodes beyond the base set: `0x1006`, `0x1009`, `0x1014`, `0x1015`, `0x1016`, `0x101B`. | `libFTLPTPIP.so.c (multiple sites); ANALYSIS.md:F09-F10` | high |

### Session Lifecycle (4 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| SDK function mode codes | `IMAGE_RECEIVE=1, REMOTE=5, NEUTRAL20=6, FW_DATA_TRANSFER=19, IMAGE_VIEW_V2=20, RESERVED_PHOTO_RECEIVED20=21, IMAGE_LIVE_VIEW=22` | `SetFunctionMode` must be called after `OpenSession` before any operation-specific commands. The mode must match the intended operation class. BUSY error code 4102 triggers retry in `SetFunctionMode` and `SetFunctionVersion` loops (up to 5 passes, 100 ms delays). | `ControlFFIR.java:113-119` | high |
| Fpcsh capability version codes | `PhoneReceive=0xDF21(57121), PhotoView=0xDF22(57122), Remote=0xDF24(57124), RemotePhotoView=0xDF25(57125), FirmwareDataTransfer=0xDF27(57127), RemotePhotoViewEx=0xDF28(57128), RemoteEx=0xDF2A(57130), GPSSet=0xDF31(57137)` | `GetFunctionVersion` is called after `SetFunctionMode`. Returns a list of version codes that gates capability negotiation. Code `0xDF27` (FirmwareDataTransfer) and `0xDF28` (RemotePhotoViewEx) are dispatched as special cases in `CameraConnectModel`. | `ControlFFIR.java:51-59; CameraConnectModel.java:297-316` | high |
| XSDK error codes | `NOERR=0, BUSY=4102, UNSUPPORTED=4101, TIMEOUT=8194, COMBINATION=8195, FORCEMODE_BUSY=-2, UNKNOWN=37120` | 28 total XSDK error codes. `FORCEMODE_BUSY=-2` is distinct from all positive error codes. `BUSY=4102` is the retry trigger in connection and mode-negotiation loops. | `XSDK.java:45-72; ANALYSIS.md ptp-8, ptp-24` | high |
| Session open retry policy | `up to 6 iterations, 30 s total` | `CameraConnectModel.cameraOpen()` loops up to 6 times with `OPEN_TIMEOUT=30000 ms` and `ASYNC_OPEN_INTERVAL=5000 ms`. Uses `AtomicBoolean bStopCameraOpen` to cancel. `open()` called with `timeout=5000`, `ConnectType.KWlan`. Result `-2 = FORCEMODE_BUSY` (distinct from error). | `CameraConnectModel.java:33-81; ControlFFIR.java:672-709` | high |

### Fujifilm USB Vendor ID (1 fact)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Fujifilm USB vendor ID | `0x04CB (1227 decimal)` | `UsbReceiver` checks `bcdDevice` vendor ID `0x04CB` before calling `notifyUsbConnectStatusChanged`. USB webcam guard: class 239/subclass 2/protocol 1 (UVC) bypasses the PTP path entirely. | `UsbReceiver.java:46-94; USBConnectModel.java:126-128` | high |

### Liveview Format (3 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Through-picture native ring buffer layout | `2 slots × 200,000 bytes` | `ThroughPictureReciever` internal state: slot data at `this+0x84` with stride 200,000 bytes; current slot index at `this+0x7c` (u8); byte count of last received frame at `this+0x80`; ready flag at `this+0x7d` (u8); condvar at `this+0x61b2c`. Producer writes only when ready flag is clear (no overwrite of unconsumed frame). Consumer waits on `pthread_cond_timedwait`. | `libFTLPTPIP.so.c:13017-13142` | high |
| GetThroughPicture Java-side buffer allocation | `204,800 bytes per frame` | `REMOTE_RECEIVE_FILE_SIZE=204800` in `PhotoGateUtil`. `FinderView` allocates `new byte[204800]` and `new ThroughPictureInfo()` on every loop iteration, plus `Arrays.copyOf` to trim result. This creates GC pressure at live-view frame rates. `bWait` parameter is `FinderView.sTarminate` (dynamic, not hardcoded `true`). | `FinderView.java:341-353; PhotoGateUtil.java:18; ControlFFIR.java:1640-1652` | high |
| Command code for GetThroughPicture dispatch | `0x6012` | `CCameraCommandGetPTPQueingData::CommandExec` dispatches: `0x6012` → `CommandExec_GetThroughPicture`, `0x6013` → `CommandExec_GetEvent`. Dispatch via `vtable+0x18`. | `libFFIR.so.c:16706-16714` | high |

### Transfer (3 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| PTP object format codes (XSDK internal) | `JPEG=7(0x07), RAW/RAF=1(0x01), MOV=8(0x08), HEIF=18(0x12)` | XSDK internal format codes (not PTP wire codes). Rotation packed in upper byte: `0x06xx=90°`, `0x03xx=180°`, `0x08xx=270°`. PTP wire object format codes map: `12301→MOV`, `14337/14344→JPEG`, `45315→RAF`, `47490→HEIF`. Thumbnail for RAF/MOV/HEIF uses `libFFEXIF.so` via `FFExif.elibRdGetInfoRaf()`/`elibRdGetInfoMovie()`. `THUMB_RAF_MOV_DATA_LENGTH=10485760` (10 MB threshold). | `XSDK.java:74-94, 122-136; ANALYSIS.md ptp-10, ptp-25, ptp-26` | high |
| Object transfer JNI method chain (Wi-Fi) | `GetExtensionObjectInfo → GetExtensionPartialObject → GetExtensionThumb` | Normal Wi-Fi image retrieval uses Fujifilm extension opcodes, not bare `GetObject`. Sequence: `Java_SDK_GetExtensionObjectInfo` → `Java_SDK_GetExtensionPartialObject` (chunked download) → `Java_SDK_GetExtensionThumb`. `ReadThumbnail` (`Java_SDK_ReadThumbnail`) and `ReadImageInfo` (`Java_SDK_ReadImageInfo`) are alternate paths. | `ControlFFIR.java:884-922; ControlFFIR.java:1163-1165` | high |

### BLE GATT (11 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| BLE service: Camera Control | `6514EB81-4E8F-458D-AA2A-E691336CDFAC` | Primary camera control service. Remote shutter characteristic lives here. | `BTConstansKt.java:114` | high |
| BLE characteristic: Shooting Request (remote shutter) | `7FCF49C6-4FF0-4777-A03D-1A79166AF7A8` | GATT write target for S0/S1/S2 remote shutter byte values. Service: `6514EB81-4E8F-458D-AA2A-E691336CDFAC`. | `BTConstansKt.java:83; BTCamera.java:7036-7058` | high |
| BLE characteristic: Camera Wi-Fi SSID | `BF6DC9CF-3606-4EC9-A4C8-D77576E93EA4` | `BTCamera` reads camera SSID from this characteristic before requesting the Wi-Fi network. | `BTConstansKt.java:34; BTCamera.java:4043-4051` | high |
| BLE characteristic: Camera Wi-Fi Passphrase | `E809256A-915C-4967-92E8-53B7D4CAD213` | `BTCamera` reads WPA2 passphrase from this characteristic. Handed to `WiFiHandOverService` for `WifiNetworkSpecifier` construction. | `BTConstansKt.java:37; BTCamera.java:4043-4051` | high |
| BLE characteristic: Connected Device Identification Number | `F557D96B-8284-4667-8793-B971C1DECA2A` | Used in BLE handover flow. | `BTConstansKt.java:43` | high |
| BLE service: Camera State | `4C0020FE-F3B6-40DE-ACC9-77D129067B14` | Camera state notifications service. | `BTConstansKt.java:120` | high |
| BLE service: File Transfer | `AF854C2E-B214-458E-97E2-912C4ECF2CB8` | BLE file transfer service (for small files over BLE, not PTP-IP). | `BTConstansKt.java:125` | high |
| BLE service: Camera Setting | `4E941240-D01D-46B9-A5EA-67636806830B` | Camera settings service. | `BTConstansKt.java:117` | high |
| BLE service: Connected Device Information | `91F1DE68-DFF6-466E-8B65-FF13B0F16FB8` | Connected device information service. RED-variant: `123D8F06-62A1-4935-9322-833C531EE225`. | `BTConstansKt.java:121-122` | high |
| BLE service: Camera Information | `117C4142-EDD4-4C77-8696-DD18EEBB770A` | Camera information service. RED-variant: `A9D2B304-E8D6-4902-8336-352B772D7597`. | `BTConstansKt.java:115-116` | high |
| BLE service: Mounted Lens Information | `15CA59FE-620C-464D-A987-223FAB660CDE` | Lens information service. | `BTConstansKt.java:128` | high |

### BLE Scanning / Advertisement (2 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Fujifilm manufacturer identifier in BLE advertisement | `0x04D8 (1240 decimal)` | `FUJIFILM_MANUFACTURER_IDENTIFIER=1240` at advertisement byte offset `MANUFACTURER_OFFSET=23`, size `MANUFACTURER_SIZE=2`. Used for BLE scan filtering. | `BTConstansKt.java:104, 108-109` | high |
| BLE GATT characteristic read/write timeout | `5 seconds` | `BLE_CHARACTERISTIC_READ_WRITE_TIMEOUT_SEC=5`. | `BTConstansKt.java:10` | high |

### BLE Pairing (1 fact)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| BLE file transfer partial chunk size | `120 bytes` | `FILE_PARTIAL_SIZE=120` bytes per BLE GATT chunk. Last sequence number is `LAST_SEQUENCE_NO=65535 (0xFFFF)`. | `BTConstansKt.java:103, 106` | high |

### Discovery (1 fact)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| Wi-Fi network request Android API | `WifiNetworkSpecifier + ConnectivityManager.requestNetwork` | `WiFiHandOverService` uses `WifiNetworkSpecifier.Builder` with `PATTERN_LITERAL` SSID, BSSID, WPA2 passphrase. `NET_CAPABILITY_INTERNET` removed (local-only network). Android 31+ adds `FLAG_INCLUDE_LOCATION_INFO`. Up to 3 retries on `onUnavailable`; `arg1=1` when `retryCount<3 AND needsRetry AND !isFirstConnect`. `wifiErrorDetailCode=7` set after message on final failure. | `WiFiHandOverService.java:301-344, 551-563` | high |

### USB (2 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| USB rootless open mechanism | `libusb_wrap_sys_device(NULL, fd, &handle)` | `FTLPTP.so` resolves `libusb_wrap_sys_device` via `dlopen`+`dlsym` at runtime (not in ELF NEEDED). Android-granted fd from `UsbManager.openDevice().getFileDescriptor()` is passed directly. `libusb_open` exists as a secondary path in `detectConnectableDevice`. `libusb_claim_interface` is called on every PTP transaction. | `FTLPTP.so.c:1045-1136; ANALYSIS.md native-5` | high |
| USB bulk transfer chunk size | `0x100000 (1 MB) default` | `CDeviceLayer` `sendCommand` and `receiveCommand` default to `0x100000` bytes. This is overridden by `wMaxPacketSize*bMaxBurst` when a SuperSpeed companion descriptor (type `0x30`) is present. | `FTLPTP.so.c:1644-1800` | high |

### Other / Internal Implementation Details (3 facts)

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|-----------|------------|
| MngPTP object size | `0x1D8 bytes` | `FTL_Open` allocates a `0x1D8`-byte `MngPTP` object. Camera IP at `+0x58`, hostname at `+0x5C` (256-byte limit), timeout at `+0x15C`. Error codes `0x13` (general) / `0x14` (first-int==`0x25` case) on failure. On success: `MngPTP::Open` then `MngPTP::CreateExecPtpSection` called; `DAT_00167a40` set to 0. | `libFTLPTPIP.so.c:1199-1294` | high |
| TCP send flags | `MSG_DONTWAIT (0x4000) + SO_SNDTIMEO option 0x15` | All sends use `sendto(fd, buf, len, 0x4000, NULL, 0)`. `SetSocketTimeout` calls `setsockopt(fd, SOL_SOCKET=1, SO_SNDTIMEO=0x15, {tv_sec=timeout_ms/1000, tv_usec=0}, 16)`. Receive uses `recvfrom(MSG_DONTWAIT)` loop until all bytes received. | `libFTLPTPIP.so.c:11586-11613` | high |
| Native library load chain | `libFFIR.so → libFTLPTPIP.so (Wi-Fi); libXAPI.so → FTLPTP.so (USB)` | `libFFIR.so` has `libFTLPTPIP.so` in ELF NEEDED. `libXAPI.so` uses `dlopen` at runtime via `CFTLPTPLib::Load` / `CToolExternalModule` vtable — neither `FTLPTP.so` nor `libFTLPTPIP.so` is in its NEEDED list. `FTLPTP.so` resolves `libusb-1.0.so` via `dlopen`+`dlsym` at runtime. `CFTLPTPLib::Load` resolves exactly 32 `FTL_*` symbols (all mandatory). | `ANALYSIS.md native-3, native-5; libXAPI.so.c:38162-38502` | high |

---

## Frameport Mapping

| Target Crate / Module | What to Borrow | Clean-Room Note |
|----------------------|----------------|-----------------|
| `fuji-ptpip` | Three-channel TCP socket architecture (command/event/through-picture on ports 55740/55741/55742), `g_OpenSocket` 3-slot array indexed by `portNo-0xD9BC`, Init_Command_Request/Ack sequence including 0x52-byte packet size and session-specific GUID/friendly-name construction, Init_Command_Ack retry logic (status `0x2019` = 500 ms × 5 = 2.5 s cap; fatal codes `0x201D`/`0x201E`/`0x2000`), Close_Request 8-byte send + `shutdown(2)` + `close`, lazy event channel open only from `InitiateOpenCapture` paths, TransactionID counter wrapping `0xFFFFFFFF→1`. | Implement the three-channel TCP architecture independently. Port numbers 55740/55741/55742 are wire-observable facts; implement them as named constants in the `fuji-ptpip` crate. The slot-index formula is a derivable consequence of the three consecutive port numbers. Implement Init_Command_Request using the PTP-IP spec for packet structure; use a freshly generated GUID per session. The retry status codes are observable protocol facts — implement as `match` arms in the Ack parser. Do not copy any Ghidra pseudo-C struct layout or ROM constant addresses. |
| `fuji-ptpip` | `MngPTP` internal state field inventory — IP at `+0x58`, hostname at `+0x5C`, timeout at `+0x15C`, TransactionID counter at `+0x14`, Ack payload at `+0x178..0x1AA`, event receiver pointer at `+0x168`, through-picture receiver pointer at `+0x170` — defines which fields must be tracked in a clean-room PTP-IP session struct. | Define a Rust `PtpIpSession` struct with fields named after their purpose (`camera_ip`, `hostname`, `connect_timeout_ms`, `transaction_id`, `ack_payload`, `event_channel`, `liveview_channel`). The offset values from Ghidra are C++ class implementation artifacts — do not reproduce them; design struct layout independently. |
| `fuji-ptpip` | PTP-IP packet container format: `length(u32 LE)+type(u16 LE)+opcode(u16 LE)+transactionID(u32 LE)+params(N×u32 LE)`. Transaction ID post-increment with wrap sentinel `0xFFFFFFFF→1`. Three-phase PTP transaction: command container (`containerType=1`) → optional data container (`containerType=2`) → response container (`containerType=3`, 12 bytes minimum). | This layout is defined by the PTP-IP specification (PIMA 15740:2005) and is not proprietary. Implement from the spec directly. The `containerType` values 1/2/3 and field widths are spec-defined facts. |
| `fuji-ptpip` | Vendor opcode table: `0x9026` LockS1Lock (1 param: AFArea), `0x9027` UnlockS1Lock (0 params), `0x9020` InitiateMovieCapture (2 params: StorageID+ObjectFormatCode), `0x9021` TerminateMovieCapture (0 params; reads saved TransactionID), `0x9060` SetCameraEvent (data phase, 6-byte payload for non-string types), `0x902C`/`0x902D`/`0x902E` StepFnumber/StepShutterspeed/StepExposureBias (1 param: direction), `0x9040`/`0x9041`/`0x9042` FmSendObjectInfo/FmSendObject/FmSendPartialObject (phone→camera). | These opcode values are observable wire-protocol constants — they appear in any packet capture of a Fujifilm camera session. Implement as an enum `FujiVendorOpcode` with these numeric values. Do not copy any Ghidra function bodies or C++ struct definitions. |
| `fuji-ptpip` | Session open retry behavior: up to 6 attempts over 30 seconds, 5000 ms between attempts. BUSY error code 4102 as distinct retry trigger. Fpcsh version codes (0xDF21–0xDF31 range) as capability gate after `SetFunctionMode`. `SetFunctionMode` must precede operation-specific commands; mode codes: `IMAGE_RECEIVE=1`, `REMOTE=5`, `NEUTRAL20=6`, `IMAGE_LIVE_VIEW=22`. | Implement retry policy as configuration (`max_attempts`, `interval_ms`) rather than hardcoded constants. `BUSY=4102` is an observable protocol fact. Mode codes are observable from any Fujifilm Wi-Fi session; implement as a `FunctionMode` enum. |
| `fuji-liveview` | Through-picture channel on dedicated TCP port 55742. Double-slot 200,000-byte ring buffer with producer/consumer condvar. Java polling buffer 204,800 bytes. JPEG extraction at offset `secondaryLen+18` where `secondaryLen` is LE-u32 at buffer byte 12. `bWait` flag controls blocking vs non-blocking poll. `SDK_MODE_IMAGE_LIVE_VIEW=22` must be set before polling. | Implement a two-slot ring buffer in Rust with an async condvar (`tokio::sync::Notify`). Use 204,800 bytes as the receive buffer size (derive from observed protocol behavior). The JPEG offset formula (`secondaryLen+18`) should be derived from a captured packet and documented with a reference to the PTP-IP EndData header layout, not from the decompiled `FinderView` code. |
| `fuji-transfer` | Object enumeration sequence: `GetNumObjects` (0x1006) / `GetObjectHandles` (0x1007) using Fujifilm extension wrapper, then per-object `GetExtensionObjectInfo` → `GetExtensionThumb` for thumbnail → `GetExtensionPartialObject` for chunked full-image download. PTP object format wire codes: `JPEG=14337/14344`, `RAF=45315`, `MOV=12301`, `HEIF=47490`. 10 MB (10,485,760) threshold for requiring EXIF-based thumbnail extraction for RAF/MOV/HEIF. | The object format wire codes are standard PTP ObjectFormat codes observable from any camera. Implement the enumeration sequence from the PTP spec plus Fujifilm extension opcodes discovered by packet capture. Do not copy the XSDK internal code translation table (`0x1603→0x1006`, etc.) — design your own clean mapping. |
| `camera/wifi` | Android Wi-Fi handover sequence: read SSID from BLE characteristic `BF6DC9CF-3606-4EC9-A4C8-D77576E93EA4`, read passphrase from `E809256A-915C-4967-92E8-53B7D4CAD213`, build `WifiNetworkSpecifier` with `PATTERN_LITERAL` + WPA2 + no `NET_CAPABILITY_INTERNET`, call `ConnectivityManager.requestNetwork`, in `onAvailable` bind socket to the returned `Network` object before connecting TCP, retry up to 3 times on `onUnavailable`. | The BLE UUID values are observable from any Fujifilm camera BLE advertisement/GATT server — they are interoperability facts. The `WifiNetworkSpecifier` pattern is a standard Android API usage pattern. Implement the retry-up-to-3 policy as a configurable parameter in `CameraWifiConnector`. Do not copy `WiFiHandOverService` business logic; write an original implementation behind the `CameraWifiConnector` interface defined in `CLAUDE.md`. |
| `camera/bluetooth` | Complete Fujifilm BLE GATT service/characteristic UUID inventory: Camera Control service `6514EB81`, Shooting Request characteristic `7FCF49C6`, Camera State service `4C0020FE`, Camera Setting service `4E941240`, Camera Information service `117C4142`, Connected Device Information service `91F1DE68`, File Transfer service `AF854C2E`, Mounted Lens Information service `15CA59FE`. Manufacturer ID filter `0x04D8` at advertisement byte offset 23. GATT operation timeout 5 s. BLE file transfer chunk size 120 bytes. | All GATT UUIDs are observable from any Fujifilm camera's GATT server using standard Android `BluetoothGatt` APIs or nRF Connect. Record them as named constants in `fuji-ble-protocol`. The S0/S1/S2 byte values for the shooting request characteristic must be determined by packet capture — do not infer them from decompiled `BTCamera.writeShootingRequest` code. |

---

## Standout Findings

1. **Java enum label swap for port names is a known trap.** Three TCP ports are mandatory and ordered: 55740 (command, always connected first), 55741 (event, lazy — opened only when `InitiateOpenCapture` is called), 55742 (through-picture liveview, lazy). Java enum labels `PORT_THROUGH_SOCK` and `PORT_EVENT_SOCK` are swapped relative to their native usage — any implementation that follows the Java enum labels verbatim will connect the wrong channels.

2. **fd ownership: JNI bridge dup()s before handoff.** The JNI bridge `dup()`s the Android socket fd before passing it to the native layer. The original fd is retained by Java (wrapped in `ParcelFileDescriptor`). The native library owns and will close only the dup'd fd. Frameport's fd-handoff design (`docs/adr/0002-wifi-socket-fd-handoff.md`) must account for this: Android creates and dups, Rust receives the dup'd fd with clear ownership transfer.

3. **SessionID is always 1 — never negotiated.** `FTL_PTP_OpenSession` always sends PTP opcode `0x1002` with parameter value 1. Implementing dynamic session negotiation would be non-interoperable with Fujifilm cameras.

4. **SetFunctionMode is a mandatory post-OpenSession gate.** Function mode must be called after PTP `OpenSession` and before any operation-class commands. The mode codes are: `IMAGE_RECEIVE=1` for import, `REMOTE=5` for remote capture, `IMAGE_LIVE_VIEW=22` for liveview. After `SetFunctionMode`, `GetFunctionVersion` must be called to gate Fpcsh capability codes (0xDF21–0xDF31 range) before entering the operation loop. BUSY error 4102 triggers retry in both calls (5 passes, 100 ms delay each).

5. **Liveview JPEG offset is dynamic, not fixed.** Through-picture liveview JPEG is NOT at a fixed offset. The extraction formula is: `JPEG_start = secondaryLen + 18`, where `secondaryLen` is a LE-uint32 read from byte offset 12 of the receive buffer. The constant 18 corresponds to the PTP-IP EndData/StartData header overhead. The outer length field is at byte offset 1 (not 0) during normal operation.

6. **Standard GetObject does not work for Fujifilm Wi-Fi image download.** Wi-Fi object transfer uses Fujifilm vendor extension opcodes (`GetExtensionObjectInfo`, `GetExtensionPartialObject`, `GetExtensionThumb`), not the standard `GetObject` (0x1001). A clean-room implementation that uses only standard PTP opcodes for image download will fail on Fujifilm cameras. The MTP property opcodes (`FTL_MTP_GetObjectPropList` etc.) are also required for metadata operations.

7. **Native library load chain is fully dynamic; all 32 FTL_* symbols are mandatory.** The native library load chain: `libXAPI.so` `dlopen()`s `FTLPTP.so` at runtime for USB; `libFFIR.so` has `libFTLPTPIP.so` as a hard ELF dependency for Wi-Fi. Both `FTLPTP.so` and the USB `libusb-1.0.so` are resolved via `dlopen`/`dlsym` — absent from ELF NEEDED lists. The Fujifilm SDK resolves exactly 32 `FTL_*` function pointers from `FTLPTP.so`; all 32 are mandatory (missing pointer throws immediately).

8. **MUST-NOT-EMBED: hardcoded cloud credentials found in APK.** The app contains hardcoded AWS Cognito pool ID, app client ID, PostHog API key, Firebase API key, and Google Maps API key in plaintext. These are Fujifilm cloud service credentials — **Frameport must never embed or reference any of these.** The camera IP `192.168.0.1` and ports 55740–55742 are the only server-side facts Frameport legitimately uses.

---

## Caveats / IP & License Risk

1. **IP risk — clean-room obligation.** This analysis is based on a reverse-engineered proprietary Fujifilm application (XApp 2.7.5, `com.fujifilm.xapp`). All numeric constants, port numbers, opcode values, UUIDs, and behavioral sequences extracted here are interoperability facts observable on the wire or from standard GATT enumeration. No decompiled source code has been copied verbatim. Frameport must implement all functionality as original code using these facts as interoperability guidance only. Review with counsel before shipping any Fujifilm-interoperating feature.

2. **Staleness risk.** Analysis is of XApp version 2.7.5 (versionCode 64, Play timestamp 2026-05-15). Protocol constants and UUIDs may change in future firmware or app updates. Validate against a packet capture with the target camera firmware before finalizing any implementation.

3. **Model-specificity risk.** The primary test target in XApp analysis is unspecified but the README references X-M5 in string tables. The X-T5 is Frameport's v1 target. Some Fpcsh version codes and function modes may differ by camera model or firmware version. The RED-variant GATT service UUIDs (e.g., `SERVICE_FF_CAMERA_INFORMATION_RED`) suggest camera-family variants exist.

4. **Ghidra analysis gaps.** Several implementation details remain opaque after three analysis rounds: the exact byte values of the Init_Command_Request ROM constants (GUID fields at `0x11D3D0-0x11D420`), the full PTP-IP opcode and event-code name string tables, the real bodies of `MngPTP::ExecPTPOperation` and `CSDKAPI::API_CommandExec` (both thunks), and the exact layout of the `PTPCommandFrame` struct. These gaps require either packet capture or additional Ghidra hexdump work to close.

5. **BLE S0/S1/S2 shutter byte values not yet extracted.** The GATT write payload for `CHARACTERISTIC_FF_SHOOTING_REQUEST` is confirmed as S0/S1/S2 values but the exact byte values were not extracted from `BTCamera.writeShootingRequest` in this analysis pass. They must be determined from packet capture or continued decompilation before BLE remote shutter can be implemented.

6. **Emulator IP is a debug artifact only.** Emulator IP `192.168.1.200` present in `ControlFFIR.java:42` is a debug artifact, not a production value. Production IP is always `192.168.0.1`.

