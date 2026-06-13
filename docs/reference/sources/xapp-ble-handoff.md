# FUJIFILM XApp 2.7.5 — BLE Pairing, Wi-Fi Handoff, Discovery, and Geotagging Reference

**Source identity:** FUJIFILM XApp 2.7.5 (com.fujifilm.xapp, build 64, 2026-05-15) — reversed APK (Kotlin app layer, decompiled Java via jadx-r8) plus native libraries (libFTLPTPIP.so, libFFIR.so, libXAPI.so) via Ghidra pseudo-C.

**Languages analysed:** Kotlin (app layer), Java (decompiled output), C/C++ (native library pseudo-C from Ghidra).

**Role for Frameport:** This source is the primary reference for the Fujifilm BLE GATT service/characteristic UUID table, the BLE-to-Wi-Fi handoff sequence, the geotagging payload layout, and the PTP-IP session lifecycle as exercised by the official XApp. Every protocol numeric constant extracted here (GATT UUIDs, PTP opcodes, packet sizes, timing values, port numbers, payload field offsets) is an interoperability fact required to communicate with Fujifilm cameras; no decompiled source code, binary asset, proprietary string resource, or cloud credential from this APK may appear in Frameport. Frameport's fuji-ble-protocol, camera/bluetooth, camera/wifi, fuji-ptpip, and fuji-transfer crates/modules are the direct beneficiaries of this analysis.

---

## Protocol Layers

### 1. BLE-discovery — coverage: full

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/BTConstansKt.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCamera.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCameraService.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTManager.java`

**Summary:** Camera advertises with Fujifilm manufacturer identifier 0x04D8 (decimal 1240). App scans for this manufacturer data at byte offset 23 (MANUFACTURER_OFFSET=23), size 2 bytes (MANUFACTURER_SIZE=2). XAPP_IDENTIFIER constant is 0x20000000 (decimal 536870912). BTCameraService dispatches per-service UUID sets based on whether the camera is RED-compliant (a firmware/model flag), selecting between normal and *_RED service UUID variants. BTManager owns scanning and GATT connection lifecycle.

---

### 2. BLE-pairing — coverage: full

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCamera.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/BTConstansKt.java`

**Summary:** Pairing uses a 4-byte pairing key (PAIRING_KEY_SIZE=4) stored in a ByteBuffer (pairingKeyBuffer). The pairing sequence reads GAP_DEVICE_NAME, CAMERA_Y_NUMBER, CAMERA_SSID_NAME_STRING, CAMERA_MAC_ADDRESS, FIRMWARE_REVISION_STRING in order (via prepareToRegisterPairingKey mReadCharacteristic queue), then writes PAIRING_KEY to SERVICE_FF_CONNECTED_DEVICE_INFORMATION, then writes CONNECTED_DEVICE_NAME_STRING. BONDING_READ_RETRY_LIMIT=20, BONDING_READ_RETRY_DELAY=1000ms. BLE_CHARACTERISTIC_READ_WRITE_TIMEOUT_SEC=5s, BLE_LOCK_TIMEOUT_SEC=20s. Two data type tags: DATATYPE_CAMERA_SERIAL_NO=1, DATATYPE_PAIRING_KEY=2.

---

### 3. BLE-GATT — coverage: full

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/BTConstansKt.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCamera.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCameraModel.java`

**Summary:** 12 Fujifilm-proprietary GATT services plus standard Generic Access, Generic Attribute, Device Information. Each service contains multiple characteristics. CCCD descriptor UUID 00002902-0000-1000-8000-00805f9b34fb used for notification subscription. Notification groupings confirmed: enableNotifyToBackup subscribes FILE_TRANSACTION_STATE + BACKUP_STATE; enableNotifyToRestore subscribes FILE_TRANSACTION_STATE + RESTORE_STATE; enableNotifyToFWUpdate subscribes FWUPDATE_STATE; enableNotifyToLogTransfer subscribes LOG_TRANSFER_STATE. General enableNotify covers TRANSFER_STATE, CAMERA_VITAL_STATE, CARD_STATE, CONNECTED_ERROR_STATE and others. BTCameraService 100ms polling timer; QueueRequestId has 29 queued operation types.

---

### 4. BLE-WiFi-handoff — coverage: full

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTCamera.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/camera_connect/WiFiHandOverService.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/camera_connect/CameraConnectModel.java`

**Summary:** App reads SSID from CHARACTERISTIC_FF_CAMERA_SSID_NAME_STRING (BF6DC9CF-…) and passphrase from CHARACTERISTIC_FF_CAMERA_WIFI_PASSPHRASE_STRING (E809256A-…) over BLE. WiFiHandOverService uses WifiNetworkSpecifier with PATTERN_LITERAL SSID match, optional BSSID (from MAC address characteristic), WPA2 passphrase, TRANSPORT_WIFI (addTransportType=1), NET_CAPABILITY_INTERNET removed (removeCapability=12). Android 31+ uses FLAG_INCLUDE_LOCATION_INFO. Up to 3 retries on onUnavailable (wifiErrorDetailCode=7 set on failure). PTP-IP session opened after onAvailable callback: bindProcessToNetwork then connect to 192.168.0.1 port 55740.

---

### 5. PTP-IP — coverage: full

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/ffir/ControlFFIR.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/camera_connect/CameraConnectModel.java`
- `_deobf/native-ghidra/libFTLPTPIP.so.c`
- `_deobf/native-ghidra/libFFIR.so.c`

**Summary:** Three TCP channels: command port 55740 (0xD9BC), event port 55741 (0xD9BD), through-picture/liveview port 55742 (0xD9BE). Init_Command_Request is 82 bytes (0x52), sent to command port. Ack is 44 bytes (0x44); magic word check; BUSY retry up to 2.5s (500ms x 5). SessionID always hardcoded to 1. Operation Request packet: length(u32)+type=1(u16)+opcode(u16)+transactionID(u32)+params. Transaction ID increments monotonically, wraps 0xFFFFFFFF→1. Function mode negotiation: 3 retry loops x 5 passes, 100ms delays, BUSY (4102) retries. Liveview buffer: 204800 bytes. Camera IP: 192.168.0.1 (0xC0A80001).

---

### 6. liveview-format — coverage: partial

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/ffir/ControlFFIR.java`
- `_deobf/native-ghidra/libFTLPTPIP.so.c`

**Summary:** GetThroughPicture uses port 55742 (through-picture channel). Buffer size is 204800 bytes (REMOTE_RECEIVE_FILE_SIZE). Packet format: packet data starts at offset +8 from CThroughPicturePacket base; min(requested, vtable[1]()) bytes copied. Frame delivery via ThroughPictureReciever thread. SDK_MODE_IMAGE_LIVE_VIEW=22 activates liveview mode.

---

### 7. discovery — coverage: full

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/bleconnect/BTManager.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/BTConstansKt.java`

**Summary:** BLE advertisement filter uses manufacturer-specific data with Fujifilm company ID 0x04D8 (1240 decimal). Manufacturer data is at byte offset 23 (MANUFACTURER_OFFSET), 2 bytes wide (MANUFACTURER_SIZE). Camera advertises Fujifilm service UUIDs. BTManager performs scan/connect; BTCameraService routes per service UUID set (normal vs RED-compliant variant).

---

### 8. camera-properties — coverage: partial

**Key files:**
- `_deobf/native-ghidra/libFTLPTPIP.so.c`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/ffir/ControlFFIR.java`

**Summary:** FunctionVersion constants (Fpcsh_Version*): 0xDF21–0xDF2A plus 0xDF31; RemotePhotoView=0xDF25, RemoteEx=0xDF2A. Seven SDK_MODE_* constants: IMAGE_RECEIVE=1, REMOTE=5, NEUTRAL20=6, FW_DATA_TRANSFER=19, IMAGE_VIEW_V2=20, RESERVED_PHOTO_RECEIVED20=21, IMAGE_LIVE_VIEW=22. Device property get/set via FTL_PTP_GetDevicePropDesc, GetDevicePropValue, SetDevicePropValue, ResetDevicePropValue, SetCameraEvent (opcode 0x9060). GetDevicePropDescAll returns bulk descriptor dump.

---

### 9. transfer — coverage: full

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/ffir/ControlFFIR.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/BTConstansKt.java`
- `_deobf/native-ghidra/libFTLPTPIP.so.c`

**Summary:** Wi-Fi transfer: PTP-IP GetObject/GetPartialObject/GetThumb over command channel (55740). BLE file transfer uses FILE_TRANSFER service with FILE_INFORMATION, FILE_PARTIAL_DATA (120-byte chunks, FILE_PARTIAL_SIZE=120), FILE_PARTIAL_SIZE, FILE_TRANSACTION_STATE, FILE_NUMBER, FILE_TRANSFER_INDEX, FILE_TRANSFER_RESULT characteristics. Sequence number range: 0–65535 (LAST_SEQUENCE_NO). Image format codes: MOV=12301 (0x300D), JPEG=14337/14344, RAF=45315, HEIF=47490. RAF transfer enabled by default (RAF_TRANSFER_SETTING=1). HEIF transfer not set by default (HEIF_TRANSFER_NOT_SETTING=0).

---

### 10. usb — coverage: full

**Key files:**
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/common/xsdk/XSDK.java`
- `_deobf/jadx-r8/sources/com/fujifilm/xapp/model/usbconnect/USBConnectModel.java`
- `_deobf/native-ghidra/libXAPI.so.c`

**Summary:** Fujifilm USB vendor ID: 0x04CB (1227 decimal). UVC webcam guard: class 239/subclass 2/protocol 1 bypasses PTP path. XSDK_SetUSBDeviceHandle: command code 0x1602. USB connection-mode negotiation timeout: 5000ms. Error codes UNSUPPORTED=4101, TIMEOUT=8194. libusb_wrap_sys_device used to bind Android-granted fd.

---

## Protocol Facts

### BLE-GATT Services and Characteristics

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| SERVICE_FF_CAMERA_CONTROL | `6514EB81-4E8F-458D-AA2A-E691336CDFAC` | Primary camera control GATT service. Contains SHOOTING_REQUEST characteristic for BLE remote shutter release. | BTConstansKt.java:114 | high |
| SERVICE_FF_CAMERA_STATE | `4C0020FE-F3B6-40DE-ACC9-77D129067B14` | Camera state service. Contains CAMERA_VITAL_STATE, CARD_STATE, TRANSFER_STATE, CONNECTED_ERROR_STATE, STATE_ERROR_DETAILS characteristics. | BTConstansKt.java:120 | high |
| SERVICE_FF_CAMERA_SETTING | `4E941240-D01D-46B9-A5EA-67636806830B` | Camera settings service. Contains image transfer settings, image resize, IPTC, logging, location sync settings, and date/time characteristics. | BTConstansKt.java:117 | high |
| SERVICE_FF_CAMERA_INFORMATION | `117C4142-EDD4-4C77-8696-DD18EEBB770A` | Camera information service (standard firmware). Contains SSID, passphrase, MAC address, serial number, Y-number, BLE protocol version, startup value. RED-compliant variant: A9D2B304-E8D6-4902-8336-352B772D7597. | BTConstansKt.java:115-116 | high |
| SERVICE_FF_CONNECTED_DEVICE_INFORMATION | `91F1DE68-DFF6-466E-8B65-FF13B0F16FB8` | Connected device (phone) information service. Contains PAIRING_KEY, CONNECTED_DEVICE_NAME_STRING, CONNECTED_APPLICATION_INFORMATION, BLE protocol version, disconnected reason, identification number, image receive state. RED-compliant variant: 123D8F06-62A1-4935-9322-833C531EE225. | BTConstansKt.java:121-122 | high |
| SERVICE_FF_CURRENT_LOCATION | `3B46EC2B-48BA-41FD-B1B8-ED860B60D22B` | GPS/location service. Contains LOCATION_AND_SPEED, LOCATION_SYNC_SETTING, LOCATION_SYNC_CYCLE, LOCATION_SYNC_STATE characteristics for geotagging. | BTConstansKt.java:123 | high |
| SERVICE_FF_CURRENT_TIME | `E872B11F-D526-4AE1-9BB4-89A99D48FA59` | Time synchronization service. Contains UTC_AND_TIMEZONE and DATE_TIME characteristics. | BTConstansKt.java:124 | high |
| SERVICE_FF_FILE_TRANSFER | `AF854C2E-B214-458E-97E2-912C4ECF2CB8` | BLE file transfer service (standard). Contains FILE_INFORMATION, FILE_NUMBER, FILE_PARTIAL_DATA, FILE_PARTIAL_SIZE, FILE_TRANSACTION_STATE, FILE_TRANSFER_INDEX, FILE_TRANSFER_RESULT characteristics. | BTConstansKt.java:125 | high |
| SERVICE_FF_FILE_TRANSFER_XHALF | `462426E1-D712-4A28-BE62-61A786DAA866` | X Half companion app file transfer service. RED-compliant variant: CC74BD26-4ABB-4911-AE72-9CD93109A772. | BTConstansKt.java:126-127 | high |
| SERVICE_FF_CAMERA_STARTUP_INFORMATION | `731893F9-744E-4899-B7E3-174106FF2B82` | Startup information service. Contains CAMERA_STARTUP_VALUE. RED-compliant variant: 804DAA8E-FFEB-4AB3-8E75-6EDD7303208D. | BTConstansKt.java:118-119 | high |
| SERVICE_FF_MOUNTED_LENS_INFORMATION | `15CA59FE-620C-464D-A987-223FAB660CDE` | Lens information service. Contains LENS_PRODUCT_NAME_STRING, LENS_SERIAL_NUMBER, LENS_FIRMWARE_VERSION_STRING characteristics. | BTConstansKt.java:128 | high |
| SERVICE_DEVICE_INFORMATION | `0000180A-0000-1000-8000-00805F9B34FB` | Standard Bluetooth Device Information Service. Contains FIRMWARE_REVISION_STRING, MANUFACTURER_NAME_STRING, SERIAL_NUMBER_STRING, GAP_DEVICE_NAME. | BTConstansKt.java:113 | high |
| CHARACTERISTIC_FF_SHOOTING_REQUEST | `7FCF49C6-4FF0-4777-A03D-1A79166AF7A8` | Write target for BLE remote shutter. In SERVICE_FF_CAMERA_CONTROL. Accepts UShort value from ShootingRequestType enum: S0=0 (release/cancel), S1=1 (half-press/AF), S2=2 (full-press/capture). writeShootingRequestLock has 5s timeout. Lock released on write callback. | BTConstansKt.java:83; BTCamera.java:7036-7071; ShootingRequestType.java:25-28 | high |
| CHARACTERISTIC_FF_CAMERA_SSID_NAME_STRING | `BF6DC9CF-3606-4EC9-A4C8-D77576E93EA4` | Read from camera to get Wi-Fi SSID for BLE-to-WiFi handoff. In SERVICE_FF_CAMERA_INFORMATION. Read during prepareToRegisterPairingKey sequence and in readCameraSsidName coroutine. | BTConstansKt.java:34; BTCamera.java:4417, 2529 | high |
| CHARACTERISTIC_FF_CAMERA_WIFI_PASSPHRASE_STRING | `E809256A-915C-4967-92E8-53B7D4CAD213` | Read from camera to get WPA2 passphrase for Wi-Fi connection. In SERVICE_FF_CAMERA_INFORMATION. Read during prepareToRegisterWriteDeviceNameForCamera sequence alongside SSID. | BTConstansKt.java:37; BTCamera.java:4469 | high |
| CHARACTERISTIC_FF_CAMERA_MAC_ADDRESS | `49A12959-DFAA-4EB2-89CE-62548AD948F3` | Read from camera to get MAC address, used as BSSID in WifiNetworkSpecifier for precise camera AP targeting. | BTConstansKt.java:30; BTCamera.java:4420 | high |
| CHARACTERISTIC_FF_PAIRING_KEY | `ABA356EB-9633-4E60-B73F-F52516DBD671` | 4-byte pairing key (PAIRING_KEY_SIZE=4) written to SERVICE_FF_CONNECTED_DEVICE_INFORMATION. Written as raw byte array from pairingKeyBuffer. After successful write callback, coroutine chain continues with CONNECTED_DEVICE_NAME_STRING write. | BTConstansKt.java:76; BTCamera.java:4393-4508 | high |
| CHARACTERISTIC_FF_CONNECTED_DEVICE_NAME_STRING | `85B9163E-62D1-49FF-A6F5-054B4630D4A1` | Phone device name written to camera after pairing key. Triggers updateDataBeforeConnection() or a follow-up coroutine if bSupportConnectAppInfoWrite is set and not RED-compliant with zero deviceIdentificationNumber. | BTConstansKt.java:45; BTCamera.java:3037-3048 | high |
| CHARACTERISTIC_FF_CONNECTED_APPLICATION_INFORMATION | `8B5ECF55-FC6B-40D0-B4C1-76F64E5453C7` | App identification written to camera. Contains application byte (BLE_CONNECTED_APPLICATION_FOR_XAPP = -128 = 0x80) and version short (BLE_CONNECTED_VERSION = 257 = 0x0101). | BTConstansKt.java:40; BTConstansKt.java:11-12 | high |
| CHARACTERISTIC_FF_LOCATION_AND_SPEED | `0F36EC14-29E5-411A-A1B6-64EE8383F090` | GPS geotag payload written to camera periodically. 23-byte little-endian payload: latitude (4B int, degrees x1e7), longitude (4B int, degrees x1e7), altitude (4B int, meters rounded), speed (4B int, m/s x100 rounded), year (2B UShort LE), month (1B UByte, 1-based), day (1B), hours (1B), minutes (1B), seconds (1B). All in UTC. Service: SERVICE_FF_CURRENT_LOCATION. | BTCamera.java:6922-6938; LocationAndSpeed.java:214-244 | high |
| CHARACTERISTIC_FF_LOCATION_SYNC_CYCLE | `C95D91AE-B247-4D6D-8661-7DD5D6A0F85B` | UShort value written to configure how often (in seconds) the camera expects GPS updates. Written via writeLocationSyncCycle with value from app settings. | BTCamera.java:6973-6978; BTCameraModel.java:1142 | high |
| CHARACTERISTIC_FF_LOCATION_SYNC_SETTING | `AAB609C4-94DD-4D89-BC60-665D5090B828` | Written to enable or disable location sync. Read back after write to confirm. Guarded by writeLocationSyncSettingLock (5s timeout). | BTCamera.java:2798, 3577, 6780 | high |
| CHARACTERISTIC_FF_UTC_AND_TIMEZONE | `C52EDBCE-1FE2-4ECC-9483-907E6592BE9E` | Time sync characteristic in SERVICE_FF_CURRENT_TIME. Read on connection. Contains UTC time and timezone offset for camera clock synchronization. | BTCamera.java:2181; BTConstansKt.java:86 | high |
| CHARACTERISTIC_FF_DATE_TIME | `B9BFD37F-CCAD-4D36-A1EE-018E792B3EDF` | Date/time characteristic in SERVICE_FF_CURRENT_TIME for camera clock sync. | BTConstansKt.java:48 | high |
| CHARACTERISTIC_FF_TRANSFER_STATE | `BD17BA04-B76B-4892-A545-B73BA1F74DAE` | Notify characteristic in SERVICE_FF_CAMERA_STATE. Subscribed via enableNotify (main group). Carries transfer progress state from camera. | BTConstansKt.java:85; BTCamera.java:4542 | high |
| CHARACTERISTIC_FF_CAMERA_VITAL_STATE | `E6692C5C-B7CD-44F4-95FC-EDA07CE32560` | Notify in SERVICE_FF_CAMERA_STATE. Camera health/power state. Part of main enableNotify group. | BTConstansKt.java:36 | high |
| CHARACTERISTIC_FF_STATE_ERROR_DETAILS | `1587B102-0B6D-4B63-9226-66FCC6D17387` | Read and notify in SERVICE_FF_CAMERA_STATE. Detailed error state information. Read in analyzeReadCharacteristic and triggered via analyzeChangeCharacteristic notification. | BTCamera.java:3819; BTConstansKt.java:84 | high |
| CHARACTERISTIC_FF_FILE_TRANSACTION_STATE | `2E27ED9F-5506-41CD-BA48-DAC06669AD95` | Notify in SERVICE_FF_FILE_TRANSFER. Included in enableNotifyToBackup and enableNotifyToRestore groups. Tracks BLE file transfer transaction progress. | BTCamera.java:4542, 4620; BTConstansKt.java:53 | high |
| CHARACTERISTIC_FF_BACKUP_STATE | `11438C83-CFB0-4511-841B-759E0D2321C8` | Notify in SERVICE_FF_CAMERA_SETTING. Subscribed together with FILE_TRANSACTION_STATE via enableNotifyToBackup. | BTCamera.java:4542; BTConstansKt.java:25 | high |
| CHARACTERISTIC_FF_RESTORE_STATE | `4B3A413C-230F-42BC-B3ED-B1DB2EADEE82` | Notify in SERVICE_FF_CAMERA_SETTING. Subscribed together with FILE_TRANSACTION_STATE via enableNotifyToRestore. | BTCamera.java:4620; BTConstansKt.java:81 | high |
| CHARACTERISTIC_FF_FWUPDATE_STATE | `049EC406-EF75-4205-A390-08FE209C51F0` | Notify in SERVICE_FF_CAMERA_SETTING. Subscribed alone via enableNotifyToFWUpdate for firmware update progress. | BTCamera.java:4777; BTConstansKt.java:59 | high |
| CHARACTERISTIC_FF_LOG_TRANSFER_STATE | `2F6CB772-1D69-448E-8819-FE6B5C20B094` | Notify in SERVICE_FF_CAMERA_SETTING. Subscribed alone via enableNotifyToLogTransfer. | BTCamera.java:4698; BTConstansKt.java:74 | high |
| CHARACTERISTIC_FF_CAMERA_POWER_KEY_STATE | `F90F7D3A-3B64-45C6-AB21-933900184837` | Power key press state notification from camera. POWER_KEY_STATE_SIZE=1 byte. In SERVICE_FF_CAMERA_STATE. | BTConstansKt.java:32, 111 | high |
| CHARACTERISTIC_FF_POWER_CONTROL_REQUEST | `43070F6C-51E0-4887-86A7-5F762BDA5791` | Written to control camera power state. BTCameraModel.writePowerControlRequest sends value from PowerControlRequestType. Used for camera wakeup and shutdown. | BTCamera.java:4954; BTCameraModel.java:1595, 2998 | high |
| CHARACTERISTIC_FF_REMOTE_BOOT_SETTING | `7170FD5A-56D9-4C19-B043-7A7047D8E1A0` | Remote boot/wakeup setting. Analyzed in BTCameraModel analyzeChangeCharacteristic. Allows phone to request camera wake from sleep via BLE. | BTCameraModel.java:3105 | high |
| CHARACTERISTIC_FF_WAKEUP_MODE | `9C72C205-5740-4F17-9949-0D3FADF2F67A` | Camera wakeup mode configuration. Paired with POWER_CONTROL_REQUEST for BLE-triggered camera wakeup flow. | BTConstansKt.java:87 | high |
| CHARACTERISTIC_FF_PAIRING_SMART_DEVICE_NUM | `8814441B-1D7B-4046-891D-D8F80864CC8E` | Number of paired smart devices registered on camera. Read during connection to determine slot availability. | BTConstansKt.java:77 | high |
| CHARACTERISTIC_FF_CAMERA_BLE_PROTOCOL_VERSION | `389363E4-712E-4CF2-A72E-BFCF7FB6ADC1` | Camera's BLE protocol version. Read to negotiate feature compatibility. In SERVICE_FF_CAMERA_INFORMATION. | BTConstansKt.java:26 | high |
| CHARACTERISTIC_FF_CONNECTED_DEVICE_BLE_PROTOCOL_VERSION | `EB4166B0-9CCA-445E-A4E4-75B3817FD57A` | Phone's BLE protocol version written to camera. In SERVICE_FF_CONNECTED_DEVICE_INFORMATION. | BTConstansKt.java:41 | high |
| CHARACTERISTIC_FF_IMAGE_TRANSFER_SETTING | `CAEDB497-83BF-482C-91EF-91CF6F1216FF` | Controls which image formats are transferred (JPEG, RAF, HEIF). RAF_TRANSFER_SETTING=1 (enabled by default). HEIF_TRANSFER_NOT_SETTING=0 (disabled by default). | BTConstansKt.java:62; BTConstansKt.java:105, 112 | high |
| CHARACTERISTIC_FF_FILE_PARTIAL_DATA | `AC0C799A-FA6C-4DF5-BBC5-BB95CCE7E6EA` | BLE file transfer chunk data characteristic. Each chunk is 120 bytes maximum (FILE_PARTIAL_SIZE=120). Sequence numbers 0–65535 (LAST_SEQUENCE_NO=65535). partialDataLock released in write callback. | BTConstansKt.java:51, 103, 106; BTCamera.java:3092-3099 | high |
| CHARACTERISTIC_FF_CAMERA_SERIAL_NUMBER | `E8E40D50-A625-4F1D-96ED-8CEC034F5690` | Camera serial number (5 bytes, CAMERA_SERIAL_NO_SIZE=5). Read during connection setup. DATATYPE_CAMERA_SERIAL_NO tag=1. | BTConstansKt.java:33, 21, 101 | high |
| CHARACTERISTIC_FF_CAMERA_Y_NUMBER | `27870478-94A9-4345-849B-EFA3BF37887F` | Camera Y-number (model variant identifier). Read during pairing key preparation sequence. | BTConstansKt.java:38; BTCamera.java:4414 | high |
| CHARACTERISTIC_FF_CARD_STATE | `34D8C8DE-E2A9-43FF-822C-7D945DD8D8E1` | Memory card insertion/state notification in SERVICE_FF_CAMERA_STATE. | BTConstansKt.java:39 | high |
| CHARACTERISTIC_FF_CONNECTED_DEVICE_IMAGE_RECEIVE_STATE | `A80BE3F8-8BCB-4ADD-A725-170B7A53ADC9` | Phone signals to camera whether it is ready to receive images. Written from phone side. | BTConstansKt.java:44 | high |
| CHARACTERISTIC_FF_AP_STATE | `A68E3F66-0FCC-4395-8D4C-AA980B5877FA` | AP (access point) state of camera. Read/notify. FUNCTION_LAUNCH_REQUEST writes a UShort FunctionLaunchRequestType value to CHARACTERISTIC_FF_FUNCTION_LAUNCH_REQUEST and uses AP_STATE as the notification characteristic to wait for response. | BTCamera.java:4513, 6301 | high |
| CHARACTERISTIC_FF_FUNCTION_LAUNCH_REQUEST | `600655E6-3637-42F1-8FB2-44EFC5C63B13` | Written to request mode or function change. UShort value from FunctionLaunchRequestType. Response monitored via AP_STATE notification. | BTCamera.java:4513; BTConstansKt.java:56 | high |
| CHARACTERISTIC_FF_MOVIE_REC_REQUEST | `861442AB-B94E-4935-90D9-41E291D91374` | Written to start/stop video recording via BLE. UShort value from movie request type. | BTConstansKt.java:75; BTCamera.java:4976 | high |
| CHARACTERISTIC_FF_FWUPDATE_REQUEST | `B1307521-7AC5-4199-AAEE-9D094781CE69` | Firmware update request characteristic. Written to initiate BLE-based firmware update flow. FWUPDATE_FILE_INFORMATION (20EA1FAC-…) carries file metadata. | BTConstansKt.java:58; BTCamera.java:5024 | high |
| CHARACTERISTIC_FF_CONNECTED_DEVICE_IDENTIFICATION_NUMBER | `F557D96B-8284-4667-8793-B971C1DECA2A` | Device identification number written by phone. When isREDCompliant and deviceIdentificationNumber==0, CONNECTED_DEVICE_NAME_STRING write is skipped. | BTConstansKt.java:43; BTCamera.java:3039 | high |
| CHARACTERISTIC_FF_CONNECTED_DEVICE_DISCONNECTED_REASON | `7EDE1988-B27E-43FC-80F4-6FEC994F0552` | Disconnect reason written by phone before intentional disconnect. UShort value from disconnect reason enum. | BTCamera.java:5361; BTConstansKt.java:42 | high |
| CLIENT_CH_CONFIG | `00002902-0000-1000-8000-00805f9b34fb` | Standard GATT Client Characteristic Configuration Descriptor UUID used to subscribe to notifications. Written with 0x0001 to enable notifications, 0x0002 for indications, 0x0000 to disable. | BTConstansKt.java:99 | high |
| FUJIFILM_MANUFACTURER_IDENTIFIER | `0x04D8` | Fujifilm company BLE manufacturer identifier (decimal 1240). Used as advertisement filter. Located at byte offset 23 (MANUFACTURER_OFFSET), size 2 bytes (MANUFACTURER_SIZE) in the advertisement payload. | BTConstansKt.java:104, 108-109 | high |
| XAPP_IDENTIFIER | `0x20000000` | XApp application identifier constant (decimal 536870912). Used in advertisement or pairing context to identify the XApp client. | BTConstansKt.java:131 | high |
| CHECK_NOTIFY_INDICATORS_RETRY_COUNT | `5` | Number of retries when verifying notification subscription was accepted. CHECK_NOTIFY_INDICATORS_RETRY_INTERVAL=500ms between attempts. | BTConstansKt.java:97-98 | high |
| BLE_CONNECTED_APPLICATION_FOR_XAPP | `0x80` | Application identifier byte written to CONNECTED_APPLICATION_INFORMATION characteristic. Value -128 as signed byte = 0x80 unsigned. Identifies this as the XApp client to the camera. | BTConstansKt.java:11 | high |
| BLE_CONNECTED_VERSION | `0x0101` | BLE protocol version short written alongside application identifier. Value 257 = 0x0101. | BTConstansKt.java:12 | high |

---

### BLE-Pairing Procedures

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| ShootingRequestType values S0/S1/S2 | S0=0, S1=1, S2=2 | UShort enum written to CHARACTERISTIC_FF_SHOOTING_REQUEST for BLE remote shutter. S0=0 (release/reset), S1=1 (half-press, autofocus), S2=2 (full-press, capture). Write is synchronous via writeRequestForSync with writeShootingRequestLock (5s timeout). | ShootingRequestType.java:25-28; BTCamera.java:7036-7071 | high |
| Pairing key size | `4` | PAIRING_KEY_SIZE=4 bytes. Written raw from pairingKeyBuffer.array() to CHARACTERISTIC_FF_PAIRING_KEY in SERVICE_FF_CONNECTED_DEVICE_INFORMATION. BONDING_READ_RETRY_LIMIT=20 read attempts, BONDING_READ_RETRY_DELAY=1000ms apart. | BTConstansKt.java:110, 16-17; BTCamera.java:4502-4506 | high |
| Pairing read sequence order | _(ordered steps)_ | prepareToRegisterPairingKey reads in this queue order: (1) GAP_DEVICE_NAME, (2) CAMERA_Y_NUMBER, (3) CAMERA_SSID_NAME_STRING, (4) CAMERA_MAC_ADDRESS, (5) FIRMWARE_REVISION_STRING. Then writes: (1) PAIRING_KEY to SERVICE_FF_CONNECTED_DEVICE_INFORMATION, (2) CONNECTED_DEVICE_NAME_STRING. | BTCamera.java:4400-4438 | high |
| Wi-Fi credential read sequence | _(ordered steps)_ | prepareToRegisterWriteDeviceNameForCamera reads: GAP_DEVICE_NAME, CAMERA_Y_NUMBER, CAMERA_MAC_ADDRESS, FIRMWARE_REVISION_STRING, CAMERA_SERIAL_NUMBER, CAMERA_SSID_NAME_STRING, CAMERA_WIFI_PASSPHRASE_STRING. Writes: CONNECTED_DEVICE_NAME_STRING only. This is the reconnect/non-initial-pair flow. | BTCamera.java:4440-4481 | high |

---

### PTP-IP Handshake

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| Init_Command_Request size | `0x52` (82 bytes) | 82-byte Init_Command_Request packet. Contains session-specific GUID from MngPTP this[0x5c..0x76]. Sent to command port 55740 via single MngTCPIP::Send call. | _deobf/ANALYSIS.md:442; libFTLPTPIP.so.c:9538-9636 | high |
| Init_Command_Ack size and magic | `0x44` (68 bytes) | 68-byte Ack buffer. Two 64-bit magic words checked: -0x6cba74f59e4f8ff8 and 0x50e036dd5793e7b2. BUSY retry: status word must equal 0x2019, sleeps 500ms up to 5 iterations (2.5s total). Fatal codes: 0x201D, 0x201E, 0x2000. Throws exception 0x13 on GUID mismatch or fatal. | _deobf/ANALYSIS.md:444; libFTLPTPIP.so.c:9652-9713 | high |
| PTP-IP port assignment | 55740/55741/55742 | g_OpenSocket[3]: 3 slots of 8 bytes each {socketFd:u32, portNo:u32}. Index = portNo - 0xD9BC. Slot 0=55740 command, slot 1=55741 event, slot 2=55742 through-picture. Close-before-overwrite guard present. | _deobf/ANALYSIS.md:438; libFTLPTPIP.so.c:306-337 | high |
| PTP SessionID | `1` | FTL_PTP_OpenSession always uses SessionID=1, hardcoded ROM literal. Not negotiated. | _deobf/ANALYSIS.md:472; libFTLPTPIP.so.c:2540-2578 | high |
| Event and through-picture channel open | 1s retry, 0xFFFFFFFF timeout | Event channel (55741) opened lazily: retries every 1s (usleep(1000000)), success creates EventReciever thread at MngPTP+0x168. Through-picture channel (55742) mirrors exactly, receiver pointer at +0x170. Both return 0xFFFFFFFF on timeout. | _deobf/ANALYSIS.md:446; libFTLPTPIP.so.c:9911-9944, 10122-10153 | high |
| Close_Request packet size | `8` | 8 bytes sent with 5000ms timeout. shutdown(fd,2) then close(fd). fd reset to 0xFFFFFFFF. Early-exit guard: returns immediately if fd==-1. | _deobf/ANALYSIS.md:448; libFTLPTPIP.so.c:11557-11578 | high |
| Function mode negotiation retry | 3x5 retries, 100ms delay | Three sequential retry loops, each capped at 5 passes, 100ms delays between passes. BUSY (error code 4102) triggers retry. GetFunctionVersion delays unconditionally. | ANALYSIS.md:47; CameraConnectModel.java:166-345 | high |

---

### PTP Opcodes

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| LockS1Lock PTP vendor opcode | `0x9026` | Half-press autofocus lock. One AFArea uint32 parameter. arg8 is zeroed stack buffer. FFIR opcode: 0x6060 (CCameraCommandLockS1Lock). | _deobf/ANALYSIS.md:466; libFTLPTPIP.so.c:7319-7357 | high |
| UnlockS1Lock PTP vendor opcode | `0x9027` | Half-press release. Five parameter slots all zero. FFIR opcode: 0x6061 (CCameraCommandLockS1Lock class reused). | _deobf/ANALYSIS.md:467; libFTLPTPIP.so.c:7362-7397 | high |
| InitiateCapture PTP standard opcode | `0x100E` | Full capture trigger. Parameters: StorageID + ObjectFormatCode. FFIR opcode: 0x6002 (CCameraCommandOpenCapture). Checks cancel flag DAT_001d0110&1 before forwarding. | _deobf/ANALYSIS.md:675; libFTLPTPIP.so.c:3499-3563 | high |
| InitiateOpenCapture PTP opcode | `0x101C` | Two-phase open capture (InitiateOpenCapture and InitiateOpenCapturePhase1 both use 0x101C). TerminateOpenCapture reads TransactionID from +0x1C and opcode 0x1018. | _deobf/ANALYSIS.md:473-474; libFTLPTPIP.so.c:6649, 6782 | high |
| InitiateMovieCapture vendor opcode | `0x9020` | Movie record start. Two parameters: StorageID, ObjectFormatCode. TransactionID saved at MngPTP+0x20. | _deobf/ANALYSIS.md:468; libFTLPTPIP.so.c:7097-7219 | high |
| TerminateMovieCapture vendor opcode | `0x9021` | Movie record stop. Reads TransactionID from MngPTP+0x20, zeros it after use. | _deobf/ANALYSIS.md:474; libFTLPTPIP.so.c:7199-7213 | high |
| SetCameraEvent vendor opcode | `0x9060` | Generic camera event setter. dataPhase=2 (data-out). Payload size: 6 bytes for non-string types; stringLength+6 for type 0xFFFF (string). JNI marshalling uses GetIntField (not GetShortField) for eventCode, dataType, valueStringLength. | _deobf/ANALYSIS.md:469; libFTLPTPIP.so.c:9028-9228 | high |
| StepFnumber vendor opcode | `0x902C` | Aperture step. Direction parameter in local_40[0]. Same structure as StepShutterspeed (0x902D) and StepExposureBias (0x902E). | _deobf/ANALYSIS.md:467; libFTLPTPIP.so.c:7402-7518 | high |

---

### Property Codes

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| Fpcsh_Version range | `0xDF21-0xDF2A, 0xDF31` | Nine Fujifilm function version constants. RemotePhotoView=0xDF25, RemoteEx=0xDF2A. Used in GetFunctionVersion/SetFunctionVersion negotiation. | ANALYSIS.md:61; ControlFFIR.java:51-59 | high |
| SDK_MODE constants | IMAGE_RECEIVE=1, REMOTE=5, NEUTRAL20=6, FW_DATA_TRANSFER=19, IMAGE_VIEW_V2=20, RESERVED_PHOTO_RECEIVED20=21, IMAGE_LIVE_VIEW=22 | SetFunctionMode mode codes. SDK_MODE_REMOTE=5 for remote shutter/liveview. SDK_MODE_IMAGE_LIVE_VIEW=22 for through-picture streaming. | ANALYSIS.md:48; ControlFFIR.java:113-119 | high |

---

### Liveview Format

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| Liveview buffer size | `204800` | REMOTE_RECEIVE_FILE_SIZE=204800 bytes. GetThroughPicture reads from ThroughPictureReciever thread at MngPTP+0x170. Frame data starts at packet+8 offset. | ANALYSIS.md:49; ControlFFIR.java:1640-1652 | high |

---

### Transfer

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| PTP object format codes | MOV=12301, JPEG=14337/14344, RAF=45315, HEIF=47490 | XSDK.convertFormat() mapping. Rotation packed in upper byte: 0x06xx=90deg, 0x03xx=180deg, 0x08xx=270deg; format in low byte. | ANALYSIS.md:43,58; XSDK.java:78-136 | high |

---

### Session Lifecycle

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| Camera IP address | `192.168.0.1` | Production camera Wi-Fi IP: 0xC0A80001. Emulator test IP: 192.168.1.200. MngPTP stores IP big-endian at offset +0x58, hostname at +0x5C (256-byte limit). | ANALYSIS.md:246; ControlFFIR.java:42, 687 | high |
| WiFi handoff retry count | `3` | WiFiHandOverService retries requestNetwork up to 3 times on onUnavailable. wifiErrorDetailCode=7 set after exhaustion. First-connect flag (isFirstConnect) affects retry arg1 value. | ANALYSIS.md:53; WiFiHandOverService.java:551-563 | high |
| Camera open retry loop | 6 iterations, ~30s | cameraOpen() retries up to 6 iterations (~30s total). bStopCameraOpen flag checked first inside loop, forces jOpen=0 silently on cancel. OPEN_TIMEOUT=30000ms, ASYNC_OPEN_INTERVAL=5000ms. | ANALYSIS.md:61; CameraConnectModel.java:45-81, 32 | high |

---

### USB

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| USB Fujifilm vendor ID | `0x04CB` | Decimal 1227. UsbReceiver checks this vendor ID then gates on isUsbConnectSupported(). UVC webcam bypass: class 239/subclass 2/protocol 1. | ANALYSIS.md:39; UsbReceiver.java:46-94; USBConnectModel.java:126-128 | high |

---

### Other Protocol Constants

| Name | Value | Detail | Source Ref | Confidence |
|------|-------|--------|------------|------------|
| BLE file transfer chunk size | `120` | FILE_PARTIAL_SIZE=120 bytes per BLE file transfer chunk. Sequence numbers 0–65535 (LAST_SEQUENCE_NO=65535). Transaction state tracked via FILE_TRANSACTION_STATE notify. | BTConstansKt.java:103, 106 | high |
| LocationAndSpeed payload byte layout | 23 bytes total | Little-endian byte order (toByteArray uses low-byte-first). Fields in order: latitude[4] (int, deg x1e7), longitude[4] (int, deg x1e7), altitude[4] (int, meters rounded), speed[4] (int, m/s x100 rounded), year[2] (UShort LE), month[1] (UByte, 1-based), day[1], hours[1], minutes[1], seconds[1]. All timestamps in UTC. Written to CHARACTERISTIC_FF_LOCATION_AND_SPEED. | LocationAndSpeed.java:214-244; BTCamera.java:6922-6938 | high |
| BTCameraService polling interval | `100ms` | BTCameraService internal timer fires every 100ms. QueueRequestId enum has 29 distinct queued operation types. RESTART_PAIRING triggered only on Bluetooth re-enable (not on disable). | ANALYSIS.md:56; BTCameraService.java:327-334 | high |
| PTP-IP packet Operation Request layout | _(field table)_ | Fields: length(u32) + type=1(u16) + opcode(u16) + transactionID(u32) + params(4 bytes each x N). Transaction ID is post-increment, wraps 0xFFFFFFFF→1 (value -1 triggers wrap, never transmitted). Assembled byte-by-byte in local_80 stack buffer. | ANALYSIS.md:690; FTLPTP.so.c:1302-1371 | high |
| PTP-IP socket send flags | MSG_DONTWAIT=0x4000, SO_SNDTIMEO=0x15 | sendto uses MSG_DONTWAIT (0x4000). setsockopt uses SO_SNDTIMEO optname=0x15 (21 decimal, not 0xD). Timeout set as {tv_sec=timeout_ms/1000, tv_usec=0}. | ANALYSIS.md:450; libFTLPTPIP.so.c:11586-11613 | high |
| JNI bridge SetOpenSocket dup | _(fd ownership)_ | Java_SDK_SetOpenSocket receives (socketFd, portNo), calls dup(fd) on the Android side, and forwards the DUPLICATED fd to SDK_SetOpenSocket. The Android layer retains ownership of the original fd. | ANALYSIS.md:475; libFFIR.so.c:5520-5539 | high |

---

## Frameport Mapping

| Target Crate / Module | What to Borrow | Clean-Room Note |
|-----------------------|----------------|-----------------|
| `fuji-ble-protocol` | Complete GATT service and characteristic UUID table (all 12 Fujifilm services, all ~90 characteristics). Shooting request S0/S1/S2 byte values. LocationAndSpeed 23-byte little-endian payload layout. Application identifier byte (0x80) and version (0x0101). Manufacturer advertisement filter (company ID 0x04D8, offset 23, size 2). Notification groupings per operation (backup, restore, FW update, log transfer). Pairing read/write queue ordering. BLE_CHARACTERISTIC_READ_WRITE_TIMEOUT_SEC=5, BLE_LOCK_TIMEOUT_SEC=20, bonding retry parameters. | Define all UUIDs as string constants in a Rust module (e.g. `fuji_ble_protocol::uuids`). These are interoperability facts (Bluetooth GATT UUIDs are required to connect to the camera, not copyrightable logic). Implement LocationAndSpeed serialization independently: the layout (lat/lon/alt/speed as little-endian i32, year as LE u16, month/day/hours/minutes/seconds as u8) is a protocol fact, not copied code. Implement ShootingRequest as a Rust enum with u16 discriminants 0/1/2. Implement the advertisement filter by scanning for manufacturer data matching company ID 0x04D8 at the documented offset. |
| `camera/bluetooth` | GATT operation queue discipline (one operation in flight at a time, BTCameraService 100ms timer, BTDelayLock per characteristic). enableNotify groupings: backup=(FILE_TRANSACTION_STATE, BACKUP_STATE), restore=(FILE_TRANSACTION_STATE, RESTORE_STATE), FW update=(FWUPDATE_STATE), log transfer=(LOG_TRANSFER_STATE). Notification retry: CHECK_NOTIFY_INDICATORS_RETRY_COUNT=5, interval=500ms. Pairing key write flow: read queue then write queue ordering. CCCD descriptor UUID for notification subscription. RESTART_PAIRING triggered on BT re-enable only. isREDCompliant flag gates alternate service UUID set. | Implement the GATT queue as a Kotlin coroutine actor or Channel-based serializer in `camera/bluetooth` module, following the `FujiBleClient` interface already defined in CLAUDE.md. The BTDelayLock timeout values (5s per characteristic write) are interoperability timing facts. The notify grouping sets are protocol facts describing which characteristics must be subscribed together for a given operation. Do not copy the class hierarchy or method names from BTCamera; re-implement the state machine using the protocol facts as the specification. |
| `camera/wifi` | WifiNetworkSpecifier construction: PATTERN_LITERAL SSID match (PatternMatcher type 0), BSSID from MAC address characteristic, WPA2 passphrase from passphrase characteristic, addTransportType(1)=TRANSPORT_WIFI, removeCapability(12)=NET_CAPABILITY_INTERNET. Android 31+ FLAG_INCLUDE_LOCATION_INFO. bindProcessToNetwork before socket creation. Up to 3 retries on onUnavailable. Message code 103 sent to client on failure. Target IP 192.168.0.1 after network available. | Implement `CameraWifiConnector` using the Android `ConnectivityManager.requestNetwork` API with `WifiNetworkSpecifier` as documented in the Android SDK. The SSID/passphrase come from BLE characteristics (interop fact). The retry count of 3 and error code 7 are interop timing facts, not protectable expression. The `NetworkRequest` builder call sequence (`addTransportType`, `removeCapability`) is required by the Android API to achieve local-only Wi-Fi without internet routing — this is standard Android API usage, not copied logic. |
| `fuji-ptpip` | Init_Command_Request size (82 bytes), Ack size (68 bytes), magic word check, BUSY retry (500ms x 5 = 2.5s), fatal codes (0x201D, 0x201E, 0x2000). g_OpenSocket slot structure (3 slots, 8 bytes each, indexed by portNo-0xD9BC). Close_Request size (8 bytes), 5000ms timeout. Event channel retry (1s). SessionID always 1. Operation Request layout: length+type+opcode+transactionID+params. Transaction ID wrap 0xFFFFFFFF→1. sendto MSG_DONTWAIT with SO_SNDTIMEO=0x15. | All packet sizes, magic numbers, timing values, and port numbers are interoperability facts required to communicate with the camera. Implement the PTP-IP state machine in Rust from the PTP-IP specification (CIPA DC-005-2005), using these confirmed values as test oracles. The 82-byte Init_Command_Request and 68-byte Ack are PTP-IP spec sizes; the magic words are camera-specific protocol constants. The MngPTP struct layout (offsets 0x58, 0x5C, 0x15C) informs the session context design but should be reimplemented as idiomatic Rust fields. |
| `fuji-transfer` | PTP object format codes: MOV=12301, JPEG=14337/14344, RAF=45315, HEIF=47490. RAF_TRANSFER_SETTING=1 (enabled by default). BLE chunk size 120 bytes. Sequence number range 0–65535. FILE_PARTIAL_DATA, FILE_PARTIAL_SIZE, FILE_TRANSACTION_STATE, FILE_TRANSFER_INDEX, FILE_TRANSFER_RESULT characteristic roles. Liveview buffer size 204800 bytes, frame data at packet+8 offset. | PTP object format codes are standardized in the PTP spec and Fujifilm extensions; they are interoperability numeric constants. BLE transfer chunk size and sequence range are protocol facts. Implement the BLE file transfer protocol as a Rust state machine in `fuji-transfer`, accepting chunks via the characteristic notification flow. The liveview frame parsing (CThroughPicturePacket, data at offset+8) should be reimplemented as an original Rust struct with the observed layout as the specification. |
| `camera/diagnostics` | XSDK error code table: NOERR=0, UNSUPPORTED=4101, BUSY=4102, TIMEOUT=8194, UNKNOWN=37120, FORCEMODE_BUSY=-2, COMBINATION=8195. PTP function mode negotiation error handling. WiFi error detail code 7 for network unavailable. Camera open failure after 6 retries / 30s. BLE connection state: CONNECTION_STATE_UNKNOWN=-1. | All error codes are interoperability numeric constants observed from the camera protocol. Map them to typed Frameport diagnostic error variants (PermissionDenied, Wifi.*, Protocol.*, Transfer.*) as defined in CLAUDE.md diagnostics requirements. Do not copy error message strings from the XApp; write original descriptions. |

---

## Standout Findings

1. There are TWO distinct read-sequence flows: (1) initial pairing reads GAP_DEVICE_NAME + CAMERA_Y_NUMBER + CAMERA_SSID_NAME_STRING + CAMERA_MAC_ADDRESS + FIRMWARE_REVISION_STRING then writes PAIRING_KEY + CONNECTED_DEVICE_NAME_STRING; (2) reconnect/name-update reads additionally CAMERA_SERIAL_NUMBER + CAMERA_WIFI_PASSPHRASE_STRING but writes ONLY CONNECTED_DEVICE_NAME_STRING. Frameport must implement both flows.

2. The camera has two service UUID sets: standard and RED-compliant (*_RED). BTCameraService.getServiceFromCharacteristic dispatches based on an isREDCompliant flag (likely firmware/model version derived from BLE advertisement or a characteristic read). Frameport fuji-ble-protocol must handle both UUID sets for cross-model compatibility.

3. LocationAndSpeed is a 23-byte little-endian payload: latitude and longitude are integer degrees multiplied by 1e7 (i32), altitude is meters rounded (i32), speed is m/s x100 rounded (i32), timestamp is year (u16 LE) + month (u8, 1-based) + day + hours + minutes + seconds all UTC. Written to CHARACTERISTIC_FF_LOCATION_AND_SPEED in SERVICE_FF_CURRENT_LOCATION.

4. BLE remote shutter uses a simple UShort write to CHARACTERISTIC_FF_SHOOTING_REQUEST: S0=0 (release), S1=1 (half-press/AF), S2=2 (full-press/capture). The write is synchronous with a 5-second per-lock timeout. Wi-Fi remote shutter goes through a completely separate path: PTP vendor opcode 0x9026 (LockS1), 0x100E (InitiateCapture), 0x9027 (UnlockS1).

5. WiFiHandOverService uses WifiNetworkSpecifier (not the legacy WifiConfiguration API). CRITICAL: NET_CAPABILITY_INTERNET must be removed (removeCapability(12)) so Android does not route away from the camera AP. Android 31+ FLAG_INCLUDE_LOCATION_INFO must be set. bindProcessToNetwork must be called before any socket operations to route PTP-IP traffic through the camera network.

6. The JNI bridge for SetOpenSocket calls dup(fd) on the Android side before passing the fd to Rust (libFFIR.so). Frameport's ADR-0002 fd-handoff pattern should document this: Android duplicates the socket fd, passes the duplicate to Rust, and retains ownership of the original. This prevents use-after-close from either side.

7. The app includes Firebase, PostHog analytics, and AWS Cognito — none of which are relevant to Frameport's local-first architecture and must not be adopted. The app also embeds AWS API keys, PostHog API keys, Firebase API keys, and Cognito pool IDs in plaintext — all flagged as proprietary credentials that Frameport must never embed. (See Caveats.)

8. The GATT notification subscription model uses grouped subscriptions per operation type: backup flow subscribes FILE_TRANSACTION_STATE + BACKUP_STATE together; restore subscribes FILE_TRANSACTION_STATE + RESTORE_STATE; FW update subscribes only FWUPDATE_STATE; log transfer subscribes only LOG_TRANSFER_STATE. The main enableNotify covers the core state notifications (TRANSFER_STATE, CAMERA_VITAL_STATE, CARD_STATE, CONNECTED_ERROR_STATE, STATE_ERROR_DETAILS). Frameport must subscribe the correct group before initiating each operation.

---

## Caveats / IP and License Risk

1. **IP risk — source of analysis:** This analysis is derived from static analysis of a reverse-engineered proprietary application (FUJIFILM XApp 2.7.5, com.fujifilm.xapp). The GATT UUIDs, PTP opcode values, packet sizes, timing constants, and payload layouts extracted here are interoperability facts required to communicate with Fujifilm cameras and are not themselves protectable as code. However, any implementation built on these facts must be wholly original code. No decompiled source, no binary assets, no string resources, and no proprietary logic from the XApp may appear in Frameport.

2. **Jurisdiction risk — Fujifilm-proprietary GATT UUIDs:** The extracted GATT UUIDs are Fujifilm-proprietary UUIDs (not assigned by the Bluetooth SIG). Their use for interoperability with Fujifilm cameras is consistent with the EU Software Directive Article 6 and similar interoperability provisions, but Frameport's legal review should confirm applicability in all target jurisdictions before v1 release.

3. **Model specificity and RED-compliant variant:** Analysis is based on XApp 2.7.5 (build 64, 2026-05-15). The RED-compliant service UUID variants (SERVICE_FF_CAMERA_INFORMATION_RED, SERVICE_FF_CAMERA_STARTUP_INFORMATION_RED, etc.) suggest firmware or model-specific branching that Frameport must detect and handle. The exact condition for isREDCompliant was not traced to a specific characteristic value or model string in this analysis pass.

4. **Partially opaque BLE flows:** Several BLE flows (startRestore in BTCameraModel, full BTManager retry/error handling) produced CFR decompilation failures ('Back jump on a try block') and remain partially opaque. The characteristic UUIDs are confirmed but the exact byte values and sequencing of the settings-restore BLE flow are not fully verified.

5. **GUID field content not extracted:** The PTP-IP Init_Command_Request GUID field content (ROM addresses 0x11D3D0–0x11D420 in libFTLPTPIP.so) was not extracted from the raw binary in this analysis pass. The packet structure (size, port, send call) is confirmed; the specific GUID bytes require a hexdump of libFTLPTPIP.so at the identified offsets.

6. **MUST-NOT-EMBED — proprietary cloud credentials:** Hardcoded proprietary credentials found in the app must never appear in Frameport. These include: AWS Cognito Pool ID `ap-northeast-1_FfkNtFqhf`, App Client ID `1c88lhbe7g4q6i81ges4pink1d`, PostHog API key `phc_RKLZBRYQKRjxJWmly1UVRfbVJ4gVaxNSL9mtcODL1hI`, Firebase API key `AIzaSyDAr5eq0QAGkKX73cjYUC7ELufKut__H0Q`, and Google Maps key `AIzaSyCzMiE-x2TR3M2AeVcgJNX7WZ1Ghd5qyTk`. These are Fujifilm's cloud service credentials with no role in a local-first camera companion.

