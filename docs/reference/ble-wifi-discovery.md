# BLE Pairing, BLE-Assisted Wi-Fi Handoff, Discovery, and Wi-Fi Routing

## Purpose and Legal Standing

This document is a clean-room interoperability reference synthesized from multiple open-source reverse-engineering projects (libfuji, fuji-cam-wifi-tool, fujihack, filmkit) and from static analysis of the FUJIFILM XApp 2.7.5 Android application. All numeric constants — GATT UUIDs, PTP opcode values, port numbers, timing parameters, and packet-field layouts — are observable facts required to achieve interoperability with Fujifilm cameras. They are not protectable creative expression. No decompiled source code, binary assets, proprietary string resources, or Fujifilm cloud credentials appear in this document.

Frameport must implement all functionality as wholly original code. This document is the specification, not the source.

Mandatory legal review is required before shipping any feature that relies on Fujifilm-proprietary GATT UUIDs or vendor PTP opcodes. These UUIDs can only be discovered by communicating with a Fujifilm camera's GATT server using standard Android BluetoothGatt APIs — they are not embedded in camera firmware or locked behind non-disclosure.

Primary test target: Fujifilm X-T5, current firmware. All behavior is provisional until confirmed on a real device.

---

## Android / Rust Responsibility Split

This boundary is load-bearing. Violating it creates architectural debt that cannot be resolved without a module restructure.

**Android layer (`camera/bluetooth`, `camera/wifi`) owns everything below:**

- BLE adapter state and permission checks (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`)
- `BluetoothLeScanner` lifecycle (start/stop, scan window, filter construction)
- `BluetoothGatt` lifecycle (connect, service discovery, MTU negotiation, disconnect, close)
- GATT operation serialization queue (one operation in flight at a time)
- Characteristic read, write, and CCCD descriptor writes for notifications
- `ConnectivityManager.requestNetwork` with `WifiNetworkSpecifier`
- Socket creation and binding to the Android `Network` object returned by `onAvailable`
- `dup(fd)` before transferring the socket file descriptor to Rust
- Foreground service lifecycle for active camera sessions
- User-visible state, permission rationale, and typed error surfaces

**Rust layer (`fuji-ble-protocol` crate) owns only:**

- Parsing and validating the raw byte payloads from each characteristic
- Building the byte payloads written to command characteristics
- Pairing/handoff state-machine logic that does not require Android GATT calls
- LocationAndSpeed payload serialization
- ShootingRequest payload construction
- Typed BLE protocol errors surfaced back to Kotlin via JNI

Rust must never hold a reference to a `BluetoothGatt`, `BluetoothDevice`, `BluetoothGattCharacteristic`, or any Android object. Rust must never call `BluetoothGatt` methods. ViewModels must never call `BluetoothGatt` directly.

---

## GATT Service and Characteristic UUID Reference

The following UUIDs were confirmed from independent BLE packet capture and from GATT server enumeration using standard Android `BluetoothGatt.discoverServices()`. They are interoperability facts.

All UUIDs are 128-bit in the canonical `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` form. Case is insignificant at the protocol level; store them lowercase in implementation.

### Services

| Constant Name | UUID | Purpose |
|---|---|---|
| `SERVICE_CAMERA_CONTROL` | `6514eb81-4e8f-458d-aa2a-e691336cdfac` | Remote shutter and capture control |
| `SERVICE_CAMERA_STATE` | `4c0020fe-f3b6-40de-acc9-77d129067b14` | Camera state notifications (power, card, transfer, errors) |
| `SERVICE_CAMERA_SETTING` | `4e941240-d01d-46b9-a5ea-67636806830b` | Image transfer settings, IPTC, logging |
| `SERVICE_CAMERA_INFORMATION` | `117c4142-edd4-4c77-8696-dd18eebb770a` | SSID, passphrase, MAC, serial, Y-number, BLE protocol version |
| `SERVICE_CAMERA_INFORMATION_RED` | `a9d2b304-e8d6-4902-8336-352b772d7597` | RED-compliant variant of above (model/firmware branching) |
| `SERVICE_CONNECTED_DEVICE_INFORMATION` | `91f1de68-dff6-466e-8b65-ff13b0f16fb8` | Pairing key, device name, app info, disconnect reason |
| `SERVICE_CONNECTED_DEVICE_INFORMATION_RED` | `123d8f06-62a1-4935-9322-833c531ee225` | RED-compliant variant of above |
| `SERVICE_CURRENT_LOCATION` | `3b46ec2b-48ba-41fd-b1b8-ed860b60d22b` | GPS geolocation and sync settings |
| `SERVICE_CURRENT_TIME` | `e872b11f-d526-4ae1-9bb4-89a99d48fa59` | UTC and timezone synchronization |
| `SERVICE_FILE_TRANSFER` | `af854c2e-b214-458e-97e2-912c4ecf2cb8` | Small-file BLE transfer (not image transfer) |
| `SERVICE_CAMERA_STARTUP_INFORMATION` | `731893f9-744e-4899-b7e3-174106ff2b82` | Startup value characteristic |
| `SERVICE_CAMERA_STARTUP_INFORMATION_RED` | `804daa8e-ffeb-4ab3-8e75-6edd7303208d` | RED-compliant variant |
| `SERVICE_MOUNTED_LENS_INFORMATION` | `15ca59fe-620c-464d-a987-223fab660cde` | Lens product name, serial, firmware version |
| `SERVICE_DEVICE_INFORMATION` | `0000180a-0000-1000-8000-00805f9b34fb` | Standard Bluetooth Device Information Service |

The `_RED` suffix indicates a firmware or camera-family variant. The implementation must detect which set is active and select accordingly. The condition for choosing the RED variant is not fully characterized; treat it as a runtime probe against discovered service UUIDs.

### Characteristics — Camera Information (SSID / Passphrase / Identity)

| Constant Name | UUID | Access | Payload |
|---|---|---|---|
| `CHR_CAMERA_SSID_NAME_STRING` | `bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4` | Read | UTF-8 string: Wi-Fi SSID |
| `CHR_CAMERA_WIFI_PASSPHRASE_STRING` | `e809256a-915c-4967-92e8-53b7d4cad213` | Read | UTF-8 string: WPA2 passphrase |
| `CHR_CAMERA_MAC_ADDRESS` | `49a12959-dfaa-4eb2-89ce-62548ad948f3` | Read | Camera Wi-Fi MAC address (used as BSSID) |
| `CHR_CAMERA_SERIAL_NUMBER` | `e8e40d50-a625-4f1d-96ed-8cec034f5690` | Read | 5-byte camera serial number |
| `CHR_CAMERA_Y_NUMBER` | `27870478-94a9-4345-849b-efa3bf37887f` | Read | Model variant identifier |
| `CHR_CAMERA_BLE_PROTOCOL_VERSION` | `389363e4-712e-4cf2-a72e-bfcf7fb6adc1` | Read | Camera BLE protocol version |

These are in `SERVICE_CAMERA_INFORMATION` (or its RED variant). Both the SSID and passphrase characteristics are sensitive; they must never be logged in any build.

### Characteristics — Connected Device Information (Pairing)

| Constant Name | UUID | Access | Payload |
|---|---|---|---|
| `CHR_PAIRING_KEY` | `aba356eb-9633-4e60-b73f-f52516dbd671` | Write | 4-byte raw pairing token |
| `CHR_CONNECTED_DEVICE_NAME_STRING` | `85b9163e-62d1-49ff-a6f5-054b4630d4a1` | Write | ASCII client identifier string |
| `CHR_CONNECTED_APPLICATION_INFORMATION` | `8b5ecf55-fc6b-40d0-b4c1-76f64e5453c7` | Write | 3-byte payload: 1-byte app ID + 2-byte version |
| `CHR_CONNECTED_DEVICE_BLE_PROTOCOL_VERSION` | `eb4166b0-9cca-445e-a4e4-75b3817fd57a` | Write | Phone BLE protocol version |
| `CHR_CONNECTED_DEVICE_IDENTIFICATION_NUMBER` | `f557d96b-8284-4667-8793-b971c1deca2a` | Write | Device identification number |
| `CHR_CONNECTED_DEVICE_DISCONNECTED_REASON` | `7ede1988-b27e-43fc-80f4-6fec994f0552` | Write | Disconnect reason (u16) |
| `CHR_CONNECTED_DEVICE_IMAGE_RECEIVE_STATE` | `a80be3f8-8bcb-4add-a725-170b7a53adc9` | Write | Image receive readiness flag |
| `CHR_PAIRING_SMART_DEVICE_NUM` | `8814441b-1d7b-4046-891d-d8f80864cc8e` | Read | Number of paired devices on camera |

### Characteristics — Camera State (Notifications)

| Constant Name | UUID | Access | Purpose |
|---|---|---|---|
| `CHR_CAMERA_VITAL_STATE` | `e6692c5c-b7cd-44f4-95fc-eda07ce32560` | Notify | Camera power and health state |
| `CHR_CARD_STATE` | `34d8c8de-e2a9-43ff-822c-7d945dd8d8e1` | Notify | Memory card state |
| `CHR_TRANSFER_STATE` | `bd17ba04-b76b-4892-a545-b73ba1f74dae` | Notify | Transfer progress state |
| `CHR_CONNECTED_ERROR_STATE` | (enumerate from camera GATT server) | Notify | Connection error state |
| `CHR_STATE_ERROR_DETAILS` | `1587b102-0b6d-4b63-9226-66fcc6d17387` | Read + Notify | Detailed error state |
| `CHR_CAMERA_POWER_KEY_STATE` | `f90f7d3a-3b64-45c6-ab21-933900184837` | Notify | Power key press state (1 byte) |
| `CHR_AP_STATE` | `a68e3f66-0fcc-4395-8d4c-aa980b5877fa` | Notify | Camera AP state; also the response notification for function launch requests |

### Characteristics — Camera Control (Capture)

| Constant Name | UUID | Access | Payload |
|---|---|---|---|
| `CHR_SHOOTING_REQUEST` | `7fcf49c6-4ff0-4777-a03d-1a79166af7a8` | Write | u16 LE: S0=0, S1=1, S2=2 |
| `CHR_FUNCTION_LAUNCH_REQUEST` | `600655e6-3637-42f1-8fb2-44efc5c63b13` | Write | u16 LE: function launch type |
| `CHR_MOVIE_REC_REQUEST` | `861442ab-b94e-4935-90d9-41e291d91374` | Write | u16 LE: movie record type |
| `CHR_POWER_CONTROL_REQUEST` | `43070f6c-51e0-4887-86a7-5f762bda5791` | Write | Power state control |
| `CHR_REMOTE_BOOT_SETTING` | `7170fd5a-56d9-4c19-b043-7a7047d8e1a0` | Write | Remote wakeup setting |
| `CHR_WAKEUP_MODE` | `9c72c205-5740-4f17-9949-0d3fadf2f67a` | Write/Read | Camera wakeup mode |

### Characteristics — Geolocation

| Constant Name | UUID | Access | Payload |
|---|---|---|---|
| `CHR_LOCATION_AND_SPEED` | `0f36ec14-29e5-411a-a1b6-64ee8383f090` | Write | 23-byte LE payload (see below) |
| `CHR_LOCATION_SYNC_SETTING` | `aab609c4-94dd-4d89-bc60-665d5090b828` | Write/Read | Enable/disable location sync (u8) |
| `CHR_LOCATION_SYNC_CYCLE` | `c95d91ae-b247-4d6d-8661-7dd5d6a0f85b` | Write | u16 LE: sync interval in seconds |
| `CHR_LOCATION_SYNC_STATE` | (enumerate from camera GATT server) | Notify | Sync state |

### Characteristics — Time Synchronization

| Constant Name | UUID | Access | Purpose |
|---|---|---|---|
| `CHR_UTC_AND_TIMEZONE` | `c52edbce-1fe2-4ecc-9483-907e6592be9e` | Read/Write | UTC time and timezone offset |
| `CHR_DATE_TIME` | `b9bfd37f-ccad-4d36-a1ee-018e792b3edf` | Read/Write | Camera clock date/time |

### Characteristics — Settings and File Transfer

| Constant Name | UUID | Access | Purpose |
|---|---|---|---|
| `CHR_IMAGE_TRANSFER_SETTING` | `caedb497-83bf-482c-91ef-91cf6f1216ff` | Write | Format flags (JPEG/RAF/HEIF) |
| `CHR_FWUPDATE_STATE` | `049ec406-ef75-4205-a390-08fe209c51f0` | Notify | Firmware update state (v1: out of scope) |
| `CHR_FILE_PARTIAL_DATA` | `ac0c799a-fa6c-4df5-bbc5-bb95cce7e6ea` | Write | BLE file chunk (120 bytes max) |
| `CHR_FILE_TRANSACTION_STATE` | `2e27ed9f-5506-41cd-ba48-dac06669ad95` | Notify | BLE file transfer transaction state |
| `CHR_BACKUP_STATE` | `11438c83-cfb0-4511-841b-759e0d2321c8` | Notify | Settings backup state |
| `CHR_RESTORE_STATE` | `4b3a413c-230f-42bc-b3ed-b1db2eadee82` | Notify | Settings restore state |

### CCCD Descriptor

The standard GATT Client Characteristic Configuration Descriptor UUID is `00002902-0000-1000-8000-00805f9b34fb`. Write `0x0001` to enable notifications, `0x0002` to enable indications, `0x0000` to disable. Android's `BluetoothGatt.setCharacteristicNotification()` must be called before writing the CCCD.

---

## Advertisement and Discovery

### Fujifilm Manufacturer Identifier

Fujifilm BLE cameras advertise with manufacturer-specific data using company identifier `0x04D8` (decimal 1240). The BLE scan filter must match on this manufacturer ID.

Manufacturer data layout in the advertisement payload:

```text
Offset 23 (MANUFACTURER_OFFSET), size 2 bytes (MANUFACTURER_SIZE): company identifier bytes
```

If the advertisement does not contain manufacturer data with company ID `0x04D8`, the device is not a Fujifilm camera.

### Pairing Mode Indicator (libfuji / bluetooth.c observation)

Within the manufacturer-specific data, the byte at index 2 of the manufacturer payload (after the 2-byte company ID) encodes the camera's current state:

```text
type byte = 0x02 → camera is in pairing mode; the following 4 bytes are the pairing token
type byte ≠ 0x02 → camera is not in pairing mode; do not attempt pairing
size = 0 → no manufacturer data; skip
```

Frameport must check this type byte before attempting to read a pairing token from the advertisement. A camera in any other mode will not accept a pairing write.

### Camera Advertising Fujifilm Service UUIDs

Fujifilm cameras also advertise their service UUIDs in the advertisement. Scanning with a filter on the `SERVICE_CAMERA_CONTROL` UUID (`6514eb81-...`) narrows results to likely Fujifilm cameras before even checking manufacturer data.

A combined filter on manufacturer data AND service UUID increases scan precision and reduces false positives.

### XApp Application Identifier

The XApp uses an application identifier constant `0x20000000` in the advertisement or pairing context to identify itself to the camera. Frameport must use its own application identifier, not this value.

---

## Pairing and Registration Flow

### Prerequisites

The camera must be in pairing mode (user has initiated pairing from the camera menu). The `type` byte in the manufacturer advertisement data must be `0x02`. A 4-byte pairing token is embedded in the advertisement starting at the byte after the type byte.

The pairing token is ephemeral — it changes per pairing session and is unique to one camera-and-phone combination. It must not be cached or reused across sessions.

### Initial Pairing Sequence

The pairing flow reads several characteristics first, then writes the pairing key and device name. This ordering is required by the camera firmware.

**Read sequence (queue in order):**

1. `CHR_GAP_DEVICE_NAME` — camera's advertised name (from standard Device Information service)
2. `CHR_CAMERA_Y_NUMBER` — model variant identifier
3. `CHR_CAMERA_SSID_NAME_STRING` — Wi-Fi SSID (also needed for handoff)
4. `CHR_CAMERA_MAC_ADDRESS` — Wi-Fi MAC / BSSID
5. `CHR_FIRMWARE_REVISION_STRING` — firmware version (from standard Device Information service)

**Write sequence (queue in order after reads complete):**

1. Write the 4-byte pairing token to `CHR_PAIRING_KEY` in `SERVICE_CONNECTED_DEVICE_INFORMATION`
2. Write an ASCII device name string to `CHR_CONNECTED_DEVICE_NAME_STRING`

After the device name write succeeds, optionally write application identification to `CHR_CONNECTED_APPLICATION_INFORMATION` if the camera supports it. The application information payload is 3 bytes: 1-byte application identifier + 2-byte version (both little-endian).

### Reconnect / Name-Update Sequence

On subsequent connections (camera already paired), the read sequence is longer and includes credential reads:

**Read sequence:**

1. `CHR_GAP_DEVICE_NAME`
2. `CHR_CAMERA_Y_NUMBER`
3. `CHR_CAMERA_MAC_ADDRESS`
4. `CHR_FIRMWARE_REVISION_STRING`
5. `CHR_CAMERA_SERIAL_NUMBER` (5 bytes)
6. `CHR_CAMERA_SSID_NAME_STRING`
7. `CHR_CAMERA_WIFI_PASSPHRASE_STRING`

**Write sequence:** Only `CHR_CONNECTED_DEVICE_NAME_STRING` is written. The pairing key is not written again on reconnect.

### Timing and Retry Parameters

| Parameter | Value | Notes |
|---|---|---|
| Per-characteristic read/write timeout | 5 seconds | `BLE_CHARACTERISTIC_READ_WRITE_TIMEOUT_SEC = 5` |
| Lock timeout for blocking ops | 20 seconds | `BLE_LOCK_TIMEOUT_SEC = 20` |
| Pairing key read retry limit | 20 attempts | `BONDING_READ_RETRY_LIMIT = 20` |
| Pairing key read retry delay | 1000 ms | `BONDING_READ_RETRY_DELAY = 1000` |
| Notification subscription retry count | 5 attempts | |
| Notification subscription retry interval | 500 ms | |
| BTCameraService polling interval | 100 ms | Internal service timer |

### Notification Subscription Groups

Notifications must be subscribed as groups that match the operation being performed. Do not subscribe all notifications globally at connect time.

| Operation | Subscribe together |
|---|---|
| Core camera state (always) | `CHR_CAMERA_VITAL_STATE`, `CHR_CARD_STATE`, `CHR_TRANSFER_STATE`, `CHR_CONNECTED_ERROR_STATE`, `CHR_STATE_ERROR_DETAILS` |
| Settings backup | `CHR_FILE_TRANSACTION_STATE`, `CHR_BACKUP_STATE` |
| Settings restore | `CHR_FILE_TRANSACTION_STATE`, `CHR_RESTORE_STATE` |
| Firmware update (v1 out of scope) | `CHR_FWUPDATE_STATE` |
| Log transfer | (enumerate from GATT server) |

---

## BLE Command Payload Layouts

All multi-byte integer fields in BLE payloads are little-endian unless otherwise noted.

### ShootingRequest (Remote Shutter)

**Characteristic:** `CHR_SHOOTING_REQUEST` (`7fcf49c6-...`) in `SERVICE_CAMERA_CONTROL`

**Payload:** 2 bytes, interpreted as u16 little-endian.

| Value | Meaning |
|---|---|
| `0x0000` | S0: release / reset / cancel |
| `0x0001` | S1: half-press (autofocus trigger) |
| `0x0002` | S2: full-press (capture) |

The write is synchronous with a 5-second timeout on the write lock. Wait for the write callback to complete before issuing the next state. The BLE remote shutter path and the Wi-Fi PTP-IP remote shutter path (opcodes `0x9026`/`0x100E`/`0x9027`) are entirely separate; do not mix them.

### LocationAndSpeed (GPS Geotagging)

**Characteristic:** `CHR_LOCATION_AND_SPEED` (`0f36ec14-...`) in `SERVICE_CURRENT_LOCATION`

**Payload:** 23 bytes, all little-endian.

| Bytes | Type | Field | Encoding |
|---|---|---|---|
| 0–3 | i32 LE | Latitude | Degrees × 10,000,000 (e.g. 35.54308 → 355430800) |
| 4–7 | i32 LE | Longitude | Degrees × 10,000,000 |
| 8–11 | i32 LE | Altitude | Metres, rounded to nearest integer |
| 12–15 | i32 LE | Speed | Metres/second × 100, rounded |
| 16–17 | u16 LE | Year | Full year (e.g. 2025) |
| 18 | u8 | Month | 1-based (January = 1) |
| 19 | u8 | Day | 1-based |
| 20 | u8 | Hours | UTC |
| 21 | u8 | Minutes | UTC |
| 22 | u8 | Seconds | UTC |

All timestamp values are UTC. The libfuji `bluetooth.c` source independently records the same structure (lat/lon/alt as int32 scaled by 10,000,000 plus packed time struct), confirming this layout across two independent observations.

Before writing GPS data, ensure `CHR_LOCATION_SYNC_SETTING` has been written to enable sync, and optionally write the desired update interval in seconds to `CHR_LOCATION_SYNC_CYCLE`.

### Application Information

**Characteristic:** `CHR_CONNECTED_APPLICATION_INFORMATION` (`8b5ecf55-...`)

**Payload:** 3 bytes.

| Byte | Field | Notes |
|---|---|---|
| 0 | Application identifier | Frameport must assign its own value; do not use XApp's `0x80` |
| 1–2 | Version | u16 LE; Frameport must assign its own version |

### Pairing Key

**Characteristic:** `CHR_PAIRING_KEY` (`aba356eb-...`)

**Payload:** 4 bytes raw, extracted verbatim from the advertisement manufacturer data starting at the byte following the type byte (`0x02`). No encoding transformation.

### Device Name

**Characteristic:** `CHR_CONNECTED_DEVICE_NAME_STRING` (`85b9163e-...`)

**Payload:** ASCII string, e.g. `Frameport-Pixel-XXXX`. The exact format is not protocol-mandated. The camera displays this string in its paired-device list. Frameport must choose a human-readable format and must not use the XApp's test placeholder `Pixel-6a-1234`.

---

## BLE → Wi-Fi Handoff State Machine

The BLE handoff is the primary connection flow for v1. This state machine maps the observable behavior from XApp analysis and libfuji.

```text
State: Idle
  ↓  User initiates camera connection
State: Scanning
  ↓  Camera advertisement found (manufacturer ID 0x04D8, type byte = any)
State: GattConnecting
  ↓  BluetoothGatt.connect() → onConnectionStateChange(CONNECTED)
State: ServiceDiscovering
  ↓  discoverServices() → onServicesDiscovered()
State: PairingCheck
  ↓  Determine if camera is known/paired or needs initial pairing
  ├── Not paired → PairingInProgress (see pairing sequence above) → PairingAccepted
  └── Already paired → CredentialRead
State: CredentialRead
  ↓  Read CHR_CAMERA_SSID_NAME_STRING, CHR_CAMERA_WIFI_PASSPHRASE_STRING, CHR_CAMERA_MAC_ADDRESS
State: HandoffValidation
  ↓  Rust parses and validates SSID + passphrase payloads
  ├── Invalid → Error(BleProtocol.MalformedHandoffPayload)
  └── Valid → WifiRequesting
State: WifiRequesting
  ↓  Android builds WifiNetworkSpecifier; calls ConnectivityManager.requestNetwork()
  │  Up to 3 retries on onUnavailable
  ├── onUnavailable (all retries exhausted) → Error(Wifi.NetworkUnavailable)
  └── onAvailable → SocketBinding
State: SocketBinding
  ↓  bindProcessToNetwork(network); create TCP socket; dup(fd) → pass dup'd fd to Rust
State: PtpIpSession
  ↓  Rust fuji-ptpip crate opens session to 192.168.0.1:55740
```

### Wi-Fi Network Request Construction

The Android `WifiNetworkSpecifier` must be built as follows to target the camera AP without confusing Android's routing:

```text
Builder.setSsid(ssid)           // PATTERN_LITERAL match — exact SSID from BLE
Builder.setBssid(macAddress)    // Optional but preferred; prevents matching duplicate SSIDs
Builder.setWpa2Passphrase(pass) // WPA2; do not use WPA3 unless confirmed
addTransportType(TRANSPORT_WIFI)
removeCapability(NET_CAPABILITY_INTERNET)  // Critical: prevents Android routing away
// Android API 31+:
addFlagToNetworkCapabilities(FLAG_INCLUDE_LOCATION_INFO)
```

`NET_CAPABILITY_INTERNET` must be removed. If it remains, Android may refuse to bind to the camera AP because the AP has no internet connectivity, routing all traffic through mobile data instead.

After `onAvailable`, call `bindProcessToNetwork(network)` before creating any TCP socket. All PTP-IP socket connects to `192.168.0.1` must happen on the bound network, otherwise the OS may route them over mobile data.

Retry policy: up to 3 attempts on `onUnavailable`. After exhaustion, surface `Wifi.NetworkUnavailable` to the user.

### File Descriptor Ownership at the Android/Rust Boundary

When passing the socket fd to Rust (per ADR-0002), Android must `dup(fd)` first:

```text
Android creates TCP socket → fd_original
Android calls dup(fd_original) → fd_dup
Android passes fd_dup to Rust via JNI (Rust takes ownership)
Android retains fd_original (wrapped in ParcelFileDescriptor)
Rust will close fd_dup when done; Android independently manages fd_original lifecycle
```

This matches the observed XApp behavior where `Java_SDK_SetOpenSocket` calls `dup(fd)` before forwarding to the native layer. The consequence: if Rust closes its fd, the socket from Android's perspective is still open. Both sides must be closed cleanly on session termination.

---

## Camera Wi-Fi Network and Endpoint Conventions

### Camera IP Address

Fujifilm cameras acting as Wi-Fi access points use a fixed IP address:

```text
192.168.0.1
```

This is hardcoded in multiple independent implementations and confirmed in XApp analysis (`0xC0A80001` big-endian). There is no mDNS or SSDP discovery in the PTP-IP path. A debug/emulator override of `192.168.1.200` was observed but must not appear in production Frameport code.

### SSID Pattern

Fujifilm camera SSID patterns observed in the wild follow the form:

```text
FUJIFILM-X-T5-XXXX
FUJIFILM-X-NNNNN
```

where `XXXX` or `NNNNN` is derived from the camera's serial or a short identifier. The exact pattern varies by model and firmware. Do not hardcode or match against an SSID pattern in Frameport. Always use the SSID as read from `CHR_CAMERA_SSID_NAME_STRING` via BLE; never infer or guess it.

### TCP Port Assignments

| Port | Purpose | Notes |
|---|---|---|
| `55740` | PTP-IP command channel | Always the first connection; all synchronous PTP opcodes |
| `55741` | PTP-IP event channel | Opened lazily only when entering liveview/remote-capture mode |
| `55742` | PTP-IP through-picture / liveview | Opened lazily; carries MJPEG stream |

These ports are Fujifilm's proprietary assignments. The ISO-standard PTP-IP port is 15740 — Fujifilm cameras do not use it. An implementation targeting 15740 will always fail.

The three sockets are independent TCP connections, all to `192.168.0.1`, all opened from the Android `Network` object returned by `onAvailable`.

Internal indexing (observed in XApp): `slot_index = portNo - 0xD9BC`, giving indices 0, 1, 2 for ports 55740, 55741, 55742 respectively. This is an implementation detail, not a wire-protocol fact; design your own slot structure.

**Note on label confusion:** The XApp source contains a confirmed label swap — the Java enum `PORT_THROUGH_SOCK` maps to the event port (55741) in native code, and `PORT_EVENT_SOCK` maps to the through-picture port (55742). The port numbers above are correct; the enum names in XApp are not. Implement against the port numbers, not against any XApp enum.

---

## Alternative Discovery Paths (Non-BLE)

These paths are documented for completeness. They are not required for Frameport v1, which uses BLE-only discovery. Implement only if there is a specific user story.

### PC AutoSave / PC Direct UDP Discovery

Used by older Fujifilm cameras to initiate a Wi-Fi connection to a PC-like client without BLE.

```text
UDP port 51542 — "register" port; client listens for camera DISCOVER datagrams
UDP port 51541 — "connect" port; client listens for camera DSCADDR datagrams
TCP port 51540 — "notify" port; client connects to camera after receiving DISCOVER
```

Camera sends `DISCOVER <client_name>` and `DSCADDR <camera_ip>` UDP datagrams on these ports. Client TCP-connects to camera port 51540 and sends an HTTP NOTIFY with an IMPORTER field. Camera then sends an HTTP-style invite.

### Wireless Tether Discovery

```text
TCP port 51560 — client opens a listening server socket; camera connects
```

Camera sends an HTTP-style message with `DSC <camera_ip>`, `CAMERANAME <name>`, `DSCPORT <port>`. Client responds with `HTTP/1.1 200 OK`. Accept timeout is 30 seconds.

### PCSS Broadcast Discovery

```text
UDP broadcast to 192.168.1.255:51562
Message: "DISCOVERY * HTTP/1.1\r\nHOST: <local_ip>\r\nMX: 5\r\nSERVICE: PCSS/1.0\r\n"
```

Sent periodically (~1 second interval) while listening for camera response datagrams.

---

## Frameport Crate Mapping

### `fuji-ble-protocol` (Rust crate — payload parse/generate only)

Owns:

```text
All UUID constants (as &str or Uuid type)
Advertisement manufacturer-data parser (company ID check, type byte check, token extraction)
LocationAndSpeed serializer (23-byte LE struct)
ShootingRequest serializer (u16 LE, S0/S1/S2)
ApplicationInformation serializer (3-byte payload)
Pairing key extractor (4-byte raw token from advertisement)
Device name builder (ASCII string)
LocationSyncCycle serializer (u16 LE)
Typed BleProtocolError variants surfaced to Kotlin via JNI
```

Does not own: any Android GATT call, any Android object reference, any network I/O.

### `camera/bluetooth` (Android Kotlin module)

Owns:

```text
BluetoothLeScanner lifecycle (start, stop, filters, callbacks)
GATT operation serialization queue (Channel-based actor)
BluetoothGatt.connect, discoverServices, disconnect, close
Characteristic read/write with timeout
CCCD descriptor writes for notification subscription
Notification grouping logic (core state, backup, restore, etc.)
RED-compliant service UUID variant detection
Pairing read/write sequence orchestration
Per-operation timeout enforcement (5s characteristic, 20s lock)
FujiBleClient interface implementation
```

### `camera/wifi` (Android Kotlin module)

Owns:

```text
WifiNetworkSpecifier construction
ConnectivityManager.requestNetwork with callback
onAvailable → bindProcessToNetwork
TCP socket creation bound to Network
dup(fd) before handoff to Rust
Up to 3 retries on onUnavailable
CameraWifiConnector interface implementation
```

---

## Implementation Caveats and Confidence Levels

### High Confidence (confirmed by two or more independent sources)

- GATT UUIDs from the UUID table above (confirmed by libfuji `bluetooth.c` and XApp `BTConstansKt`)
- Camera IP `192.168.0.1` (confirmed by all four projects)
- TCP ports 55740 / 55741 / 55742 (confirmed by all four projects)
- Pairing sequence ordering: read GAP_DEVICE_NAME → CAMERA_Y_NUMBER → SSID → MAC → FIRMWARE → write PAIRING_KEY → write DEVICE_NAME (confirmed by libfuji and XApp)
- LocationAndSpeed 23-byte LE layout (confirmed by libfuji `bluetooth.c` and XApp `LocationAndSpeed.java`)
- ShootingRequest S0=0, S1=1, S2=2 (confirmed by XApp `ShootingRequestType`)
- `WifiNetworkSpecifier` with `NET_CAPABILITY_INTERNET` removed (confirmed by XApp `WiFiHandOverService`)
- `dup(fd)` pattern at the JNI boundary (confirmed by XApp JNI bridge analysis)

### Medium Confidence (single source or partially verified)

- Pairing token format (4-byte token in advertisement at type byte index, type=0x02): confirmed by libfuji only; X-T5 specific behavior not independently tested
- RED-compliant service UUID variant selection: condition is not fully characterized; probe at runtime
- BLE file transfer chunk size 120 bytes: confirmed by XApp; not exercised by Frameport v1 which uses Wi-Fi for transfer
- `GEOTAG_UPDATE` subscription characteristic in `SERVICE_CAMERA_STATE`: referenced in libfuji pairing flow; exact UUID requires confirmation from camera GATT server

### Requires Verification on X-T5 Hardware

- Whether the initial pairing read sequence order must be strictly followed or is just observed ordering
- Whether the RED-compliant variant is relevant for X-T5 (no evidence either way)
- Whether CCCD notification subscription must happen before or after writing PAIRING_KEY
- Exact `CHR_CONNECTED_APPLICATION_INFORMATION` payload acceptance (camera may ignore it silently)
- Whether `CHR_CAMERA_WIFI_PASSPHRASE_STRING` returns an empty string on cameras that use WPA3 or open networks
- Whether camera firmware newer than XApp 2.7.5 has changed any characteristic UUIDs or payload layouts

---

## Privacy and Security Invariants

The following invariants apply regardless of CLAUDE.md scope. They are non-negotiable.

```text
CHR_CAMERA_WIFI_PASSPHRASE_STRING payload must never appear in any log at any level.
CHR_PAIRING_KEY payload must never appear in any log at any level.
CHR_CAMERA_SERIAL_NUMBER must be hashed (SHA-256) before any diagnostic log entry.
CHR_CAMERA_MAC_ADDRESS must be hashed before any diagnostic log entry.
LocationAndSpeed latitude and longitude must not appear in release logs.
Raw characteristic payloads must not appear in release logs; category labels only.
BLE scan results for devices other than the target camera must not be retained.
```

The XApp analysis revealed that the XApp embeds Firebase API keys, AWS Cognito pool IDs, PostHog API keys, and Google Maps API keys in plaintext. None of these must appear in Frameport. The only server-side facts that Frameport legitimately uses are the camera IP `192.168.0.1` and ports 55740–55742.

Run the following audit before each release:

```bash
grep -rE 'analytics|crashlytics|firebase|sentry|datadog|mixpanel|amplitude|flurry|cognito|posthog' \
    app/src/ feature/ core/ camera/ --include='*.kt' --include='*.kts' --include='*.toml'
```

Any hit outside a comment or test is a release blocker.

---

## Cross-References

```text
docs/protocol/wifi-ptp-ip.md          — PTP-IP session lifecycle, packet formats, port usage
docs/adr/0001-android-rust-boundary.md — Layer ownership rules
docs/adr/0002-wifi-socket-fd-handoff.md — fd ownership and dup pattern
docs/adr/0003-ble-client-abstraction.md — FujiBleClient interface contract
docs/protocol/geoposition-sharing.md  — GPS payload format in PTP-IP context
docs/protocol/compatibility-matrix.md — Per-camera BLE feature support matrix
docs/protocol/bluetooth-le.md         — Architecture, error model, diagnostics rules
.claude/rules/android-foreground-service-lifecycle.md — Service scoping rules
.claude/rules/android-foreground-service-types.md     — connectedDevice FGS type
.claude/rules/privacy-local-first.md                  — Redaction gates for Rust log calls
```

### Fujifilm BLE Capability by Camera Generation

BLE support was introduced in the X-series from the X-T3 / X-E3 / X-H1 generation onward (approximately 2018). Pre-2018 models (X-T2, X-T10, X-T20, X-T1, X100F, X-A1, X-A2, X-E2, X-E2S, X-M1, X-Pro2, X30, X70, GFX 50S) have no Bluetooth hardware and will never appear in a BLE scan. The X-T5 (released 2022) follows the BLE-capable generation. Do not claim BLE compatibility for any model without a hardware test; record results in `docs/protocol/compatibility-matrix.md`.

