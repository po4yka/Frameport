//! UUID and numeric constants for Fujifilm BLE camera communication.
//!
//! Every constant here is an interoperability fact derived from:
//! - `docs/reference/ble-wifi-discovery.md` (primary spec)
//! - `docs/reference/master-constants.md` §5 (cross-confirmed)
//!
//! Source confidence tags match the master-constants legend:
//!   [H] = two or more independent sources agree
//!   [M] = single high-confidence source
//!
//! No Fujifilm proprietary source code, credentials, or firmware binaries are
//! reproduced. These are observable interoperability facts.

// ─── Fujifilm Manufacturer Advertisement Filter ─────────────────────────────

/// Fujifilm BLE manufacturer company identifier embedded in advertisement data.
///
/// Used as the primary BLE scan filter to identify Fujifilm cameras.
/// Source: ble-wifi-discovery.md "Fujifilm Manufacturer Identifier";
/// master-constants.md §5a. [H] XPN, XBL
pub const MANUFACTURER_COMPANY_ID: u16 = 0x04D8;

/// Size in bytes of the manufacturer company-id field.
///
/// Source: master-constants.md §5a `MANUFACTURER_SIZE`. [H] XPN, XBL
pub const MANUFACTURER_SIZE: usize = 2;

/// Type byte value in the manufacturer payload that indicates the camera is
/// in BLE pairing mode.
///
/// Layout within manufacturer payload (after 2-byte company ID):
/// `[type: u8][token: 4 bytes]`
///
/// Source: ble-wifi-discovery.md §"Pairing Mode Indicator";
/// master-constants.md §5a. [H] LFJ
pub const PAIRING_MODE_TYPE_BYTE: u8 = 0x02;

/// Number of bytes in the pairing token embedded in the advertisement when
/// `type == PAIRING_MODE_TYPE_BYTE`.
///
/// Source: ble-wifi-discovery.md "Pairing token size";
/// master-constants.md §5a. [H] LFJ
pub const PAIRING_TOKEN_SIZE: usize = 4;

// ─── GATT Service UUIDs ───────────────────────────────────────────────────────

/// Remote shutter and capture control service.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CAMERA_CONTROL`. [H] LFJ, XPN, XBL
pub const SERVICE_CAMERA_CONTROL: &str = "6514eb81-4e8f-458d-aa2a-e691336cdfac";

/// Camera state notifications (power, card, transfer, errors).
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CAMERA_STATE`. [H] LFJ, XPN, XBL
pub const SERVICE_CAMERA_STATE: &str = "4c0020fe-f3b6-40de-acc9-77d129067b14";

/// Image transfer settings, IPTC, logging.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CAMERA_SETTING`. [H] XPN, XBL
pub const SERVICE_CAMERA_SETTING: &str = "4e941240-d01d-46b9-a5ea-67636806830b";

/// SSID, passphrase, MAC, serial, Y-number, BLE protocol version.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CAMERA_INFORMATION`. [H] XPN, XBL
pub const SERVICE_CAMERA_INFORMATION: &str = "117c4142-edd4-4c77-8696-dd18eebb770a";

/// RED-compliant variant of camera information service.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CAMERA_INFORMATION_RED`. [M] XBL
pub const SERVICE_CAMERA_INFORMATION_RED: &str = "a9d2b304-e8d6-4902-8336-352b772d7597";

/// Pairing key, device name, app info, disconnect reason.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CONNECTED_DEVICE_INFORMATION`. [H] LFJ, XPN, XBL
pub const SERVICE_CONNECTED_DEVICE_INFORMATION: &str = "91f1de68-dff6-466e-8b65-ff13b0f16fb8";

/// RED-compliant variant of connected device information.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CONNECTED_DEVICE_INFORMATION_RED`. [M] XBL
pub const SERVICE_CONNECTED_DEVICE_INFORMATION_RED: &str = "123d8f06-62a1-4935-9322-833c531ee225";

/// GPS geolocation and sync settings.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CURRENT_LOCATION`. [H] LFJ, XBL
pub const SERVICE_CURRENT_LOCATION: &str = "3b46ec2b-48ba-41fd-b1b8-ed860b60d22b";

/// UTC and timezone synchronization.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CURRENT_TIME`. [H] XBL
pub const SERVICE_CURRENT_TIME: &str = "e872b11f-d526-4ae1-9bb4-89a99d48fa59";

/// Small-file BLE transfer (not image transfer).
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_FILE_TRANSFER`. [H] XPN, XBL
pub const SERVICE_FILE_TRANSFER: &str = "af854c2e-b214-458e-97e2-912c4ecf2cb8";

/// Startup value characteristic service.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CAMERA_STARTUP_INFORMATION`. [H] XBL
pub const SERVICE_CAMERA_STARTUP_INFORMATION: &str = "731893f9-744e-4899-b7e3-174106ff2b82";

/// RED-compliant variant of startup information.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_CAMERA_STARTUP_INFORMATION_RED`. [H] XBL
pub const SERVICE_CAMERA_STARTUP_INFORMATION_RED: &str = "804daa8e-ffeb-4ab3-8e75-6edd7303208d";

/// Lens product name, serial, firmware version.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_MOUNTED_LENS_INFORMATION`. [H] XPN, XBL
pub const SERVICE_MOUNTED_LENS_INFORMATION: &str = "15ca59fe-620c-464d-a987-223fab660cde";

/// Standard Bluetooth Device Information Service.
/// Source: ble-wifi-discovery.md §"Services" `SERVICE_DEVICE_INFORMATION`. [H] XBL
pub const SERVICE_DEVICE_INFORMATION: &str = "0000180a-0000-1000-8000-00805f9b34fb";

// ─── GATT Characteristic UUIDs — Camera Information ─────────────────────────

/// Wi-Fi SSID string (UTF-8 read).
/// Source: ble-wifi-discovery.md §"Camera Information" `CHR_CAMERA_SSID_NAME_STRING`. [H] XPN, XBL
pub const CHR_CAMERA_SSID_NAME_STRING: &str = "bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4";

/// WPA2 passphrase (UTF-8 read).  MUST NEVER be logged at any level.
/// Source: ble-wifi-discovery.md §"Camera Information" `CHR_CAMERA_WIFI_PASSPHRASE_STRING`. [H] XPN, XBL
pub const CHR_CAMERA_WIFI_PASSPHRASE_STRING: &str = "e809256a-915c-4967-92e8-53b7d4cad213";

/// Camera Wi-Fi MAC address (read; used as BSSID).
/// Source: ble-wifi-discovery.md §"Camera Information" `CHR_CAMERA_MAC_ADDRESS`. [H] XBL
pub const CHR_CAMERA_MAC_ADDRESS: &str = "49a12959-dfaa-4eb2-89ce-62548ad948f3";

/// Camera serial number (5 bytes).
/// Source: ble-wifi-discovery.md §"Camera Information" `CHR_CAMERA_SERIAL_NUMBER`. [H] XBL
pub const CHR_CAMERA_SERIAL_NUMBER: &str = "e8e40d50-a625-4f1d-96ed-8cec034f5690";

/// Model variant identifier.
/// Source: ble-wifi-discovery.md §"Camera Information" `CHR_CAMERA_Y_NUMBER`. [H] XBL
pub const CHR_CAMERA_Y_NUMBER: &str = "27870478-94a9-4345-849b-efa3bf37887f";

/// Camera BLE protocol version.
/// Source: ble-wifi-discovery.md §"Camera Information" `CHR_CAMERA_BLE_PROTOCOL_VERSION`. [H] XBL
pub const CHR_CAMERA_BLE_PROTOCOL_VERSION: &str = "389363e4-712e-4cf2-a72e-bfcf7fb6adc1";

// ─── GATT Characteristic UUIDs — Connected Device Information (Pairing) ─────

/// 4-byte pairing token write target.
/// Source: ble-wifi-discovery.md §"Connected Device Information" `CHR_PAIRING_KEY`. [H] LFJ, XBL
pub const CHR_PAIRING_KEY: &str = "aba356eb-9633-4e60-b73f-f52516dbd671";

/// ASCII device name write (e.g. "Frameport-Pixel-XXXX").
/// Source: ble-wifi-discovery.md §"Connected Device Information" `CHR_CONNECTED_DEVICE_NAME_STRING`. [H] LFJ, XBL
pub const CHR_CONNECTED_DEVICE_NAME_STRING: &str = "85b9163e-62d1-49ff-a6f5-054b4630d4a1";

/// 3-byte app info: 1-byte app ID + 2-byte LE version.
/// Source: ble-wifi-discovery.md §"Connected Device Information" `CHR_CONNECTED_APPLICATION_INFORMATION`. [H] XBL
pub const CHR_CONNECTED_APPLICATION_INFORMATION: &str = "8b5ecf55-fc6b-40d0-b4c1-76f64e5453c7";

/// Phone BLE protocol version write.
/// Source: ble-wifi-discovery.md §"Connected Device Information" `CHR_CONNECTED_DEVICE_BLE_PROTOCOL_VERSION`. [H] XBL
pub const CHR_CONNECTED_DEVICE_BLE_PROTOCOL_VERSION: &str = "eb4166b0-9cca-445e-a4e4-75b3817fd57a";

/// Device identification number write.
/// Source: ble-wifi-discovery.md §"Connected Device Information" `CHR_CONNECTED_DEVICE_IDENTIFICATION_NUMBER`. [H] XPN, XBL
pub const CHR_CONNECTED_DEVICE_IDENTIFICATION_NUMBER: &str = "f557d96b-8284-4667-8793-b971c1deca2a";

/// Disconnect reason write (u16).
/// Source: ble-wifi-discovery.md §"Connected Device Information" `CHR_CONNECTED_DEVICE_DISCONNECTED_REASON`. [H] XBL
pub const CHR_CONNECTED_DEVICE_DISCONNECTED_REASON: &str = "7ede1988-b27e-43fc-80f4-6fec994f0552";

/// Image receive readiness flag write.
/// Source: ble-wifi-discovery.md §"Connected Device Information" `CHR_CONNECTED_DEVICE_IMAGE_RECEIVE_STATE`. [H] XBL
pub const CHR_CONNECTED_DEVICE_IMAGE_RECEIVE_STATE: &str = "a80be3f8-8bcb-4add-a725-170b7a53adc9";

/// Number of paired smart devices on camera (read).
/// Source: ble-wifi-discovery.md §"Connected Device Information" `CHR_PAIRING_SMART_DEVICE_NUM`. [H] XBL
pub const CHR_PAIRING_SMART_DEVICE_NUM: &str = "8814441b-1d7b-4046-891d-d8f80864cc8e";

// ─── GATT Characteristic UUIDs — Camera State (Notifications) ───────────────

/// Camera power and health state notification.
/// Source: ble-wifi-discovery.md §"Camera State" `CHR_CAMERA_VITAL_STATE`. [H] XBL
pub const CHR_CAMERA_VITAL_STATE: &str = "e6692c5c-b7cd-44f4-95fc-eda07ce32560";

/// Memory card state notification.
/// Source: ble-wifi-discovery.md §"Camera State" `CHR_CARD_STATE`. [H] XBL
pub const CHR_CARD_STATE: &str = "34d8c8de-e2a9-43ff-822c-7d945dd8d8e1";

/// Transfer progress state notification.
/// Source: ble-wifi-discovery.md §"Camera State" `CHR_TRANSFER_STATE`. [H] LFJ, XBL
pub const CHR_TRANSFER_STATE: &str = "bd17ba04-b76b-4892-a545-b73ba1f74dae";

/// Detailed error state (read + notify).
/// Source: ble-wifi-discovery.md §"Camera State" `CHR_STATE_ERROR_DETAILS`. [H] XBL
pub const CHR_STATE_ERROR_DETAILS: &str = "1587b102-0b6d-4b63-9226-66fcc6d17387";

/// Power key press state notification (1 byte).
/// Source: ble-wifi-discovery.md §"Camera State" `CHR_CAMERA_POWER_KEY_STATE`. [H] XBL
pub const CHR_CAMERA_POWER_KEY_STATE: &str = "f90f7d3a-3b64-45c6-ab21-933900184837";

/// Camera AP state notification; also the response for function launch requests.
/// Source: ble-wifi-discovery.md §"Camera State" `CHR_AP_STATE`. [H] LFJ, XBL
pub const CHR_AP_STATE: &str = "a68e3f66-0fcc-4395-8d4c-aa980b5877fa";

// ─── GATT Characteristic UUIDs — Camera Control ─────────────────────────────

/// Remote shutter request write (u16 LE: S0=0, S1=1, S2=2).
/// Source: ble-wifi-discovery.md §"Camera Control" `CHR_SHOOTING_REQUEST`. [H] LFJ, XPN, XBL
pub const CHR_SHOOTING_REQUEST: &str = "7fcf49c6-4ff0-4777-a03d-1a79166af7a8";

/// Function launch request write (u16 LE).
/// Source: ble-wifi-discovery.md §"Camera Control" `CHR_FUNCTION_LAUNCH_REQUEST`. [H] XBL
pub const CHR_FUNCTION_LAUNCH_REQUEST: &str = "600655e6-3637-42f1-8fb2-44efc5c63b13";

/// Movie record request write (u16 LE).
/// Source: ble-wifi-discovery.md §"Camera Control" `CHR_MOVIE_REC_REQUEST`. [H] XBL
pub const CHR_MOVIE_REC_REQUEST: &str = "861442ab-b94e-4935-90d9-41e291d91374";

/// Power state control write.
/// Source: ble-wifi-discovery.md §"Camera Control" `CHR_POWER_CONTROL_REQUEST`. [H] XBL
pub const CHR_POWER_CONTROL_REQUEST: &str = "43070f6c-51e0-4887-86a7-5f762bda5791";

/// Remote wakeup setting write.
/// Source: ble-wifi-discovery.md §"Camera Control" `CHR_REMOTE_BOOT_SETTING`. [H] XBL
pub const CHR_REMOTE_BOOT_SETTING: &str = "7170fd5a-56d9-4c19-b043-7a7047d8e1a0";

/// Camera wakeup mode write/read.
/// Source: ble-wifi-discovery.md §"Camera Control" `CHR_WAKEUP_MODE`. [H] XBL
pub const CHR_WAKEUP_MODE: &str = "9c72c205-5740-4f17-9949-0d3fadf2f67a";

// ─── GATT Characteristic UUIDs — Geolocation ─────────────────────────────────

/// 23-byte LE location-and-speed payload write.
/// Source: ble-wifi-discovery.md §"Geolocation" `CHR_LOCATION_AND_SPEED`. [H] LFJ, XBL
pub const CHR_LOCATION_AND_SPEED: &str = "0f36ec14-29e5-411a-a1b6-64ee8383f090";

/// Enable/disable location sync (u8) write/read.
/// Source: ble-wifi-discovery.md §"Geolocation" `CHR_LOCATION_SYNC_SETTING`. [H] XBL
pub const CHR_LOCATION_SYNC_SETTING: &str = "aab609c4-94dd-4d89-bc60-665d5090b828";

/// Location sync interval in seconds (u16 LE) write.
/// Source: ble-wifi-discovery.md §"Geolocation" `CHR_LOCATION_SYNC_CYCLE`. [H] XBL
pub const CHR_LOCATION_SYNC_CYCLE: &str = "c95d91ae-b247-4d6d-8661-7dd5d6a0f85b";

// ─── GATT Characteristic UUIDs — Time Synchronization ───────────────────────

/// UTC time and timezone offset read/write.
/// Source: ble-wifi-discovery.md §"Time Synchronization" `CHR_UTC_AND_TIMEZONE`. [H] XBL
pub const CHR_UTC_AND_TIMEZONE: &str = "c52edbce-1fe2-4ecc-9483-907e6592be9e";

/// Camera clock date/time read/write.
/// Source: ble-wifi-discovery.md §"Time Synchronization" `CHR_DATE_TIME`. [H] XBL
pub const CHR_DATE_TIME: &str = "b9bfd37f-ccad-4d36-a1ee-018e792b3edf";

// ─── GATT Characteristic UUIDs — Settings and File Transfer ─────────────────

/// Image transfer format flags write.
/// Source: ble-wifi-discovery.md §"Settings" `CHR_IMAGE_TRANSFER_SETTING`. [H] XBL
pub const CHR_IMAGE_TRANSFER_SETTING: &str = "caedb497-83bf-482c-91ef-91cf6f1216ff";

/// BLE file chunk write (max 120 bytes).
/// Source: ble-wifi-discovery.md §"File Transfer" `CHR_FILE_PARTIAL_DATA`. [H] XPN, XBL
pub const CHR_FILE_PARTIAL_DATA: &str = "ac0c799a-fa6c-4df5-bbc5-bb95cce7e6ea";

/// BLE file transfer transaction state notify.
/// Source: ble-wifi-discovery.md §"File Transfer" `CHR_FILE_TRANSACTION_STATE`. [H] XBL
pub const CHR_FILE_TRANSACTION_STATE: &str = "2e27ed9f-5506-41cd-ba48-dac06669ad95";

/// Settings backup state notify.
/// Source: ble-wifi-discovery.md §"File Transfer" `CHR_BACKUP_STATE`. [H] XBL
pub const CHR_BACKUP_STATE: &str = "11438c83-cfb0-4511-841b-759e0d2321c8";

/// Settings restore state notify.
/// Source: ble-wifi-discovery.md §"File Transfer" `CHR_RESTORE_STATE`. [H] XBL
pub const CHR_RESTORE_STATE: &str = "4b3a413c-230f-42bc-b3ed-b1db2eadee82";

// ─── CCCD Descriptor ─────────────────────────────────────────────────────────

/// Standard GATT Client Characteristic Configuration Descriptor.
/// Write 0x0001 to enable notifications, 0x0002 indications, 0x0000 to disable.
/// Source: ble-wifi-discovery.md §"CCCD Descriptor". [H] XBL
pub const CCCD_UUID: &str = "00002902-0000-1000-8000-00805f9b34fb";

/// CCCD value to enable notifications.
/// Source: ble-wifi-discovery.md §"CCCD Descriptor". [H] XBL
pub const CCCD_ENABLE_NOTIFICATIONS: [u8; 2] = [0x01, 0x00];

/// CCCD value to disable notifications/indications.
/// Source: ble-wifi-discovery.md §"CCCD Descriptor". [H] XBL
pub const CCCD_DISABLE: [u8; 2] = [0x00, 0x00];

// ─── BLE Payload Sizes ────────────────────────────────────────────────────────

/// Expected byte length of a LocationAndSpeed payload.
/// Source: ble-wifi-discovery.md §"LocationAndSpeed"; master-constants.md §5d. [H] LFJ, XBL
pub const LOCATION_AND_SPEED_PAYLOAD_LEN: usize = 23;

/// Expected byte length of a ShootingRequest payload (u16 LE).
/// Source: ble-wifi-discovery.md §"ShootingRequest". [H] LFJ, XPN, XBL
pub const SHOOTING_REQUEST_PAYLOAD_LEN: usize = 2;

/// Expected byte length of an ApplicationInformation payload.
/// Source: ble-wifi-discovery.md §"Application Information". [H] XBL
pub const APP_INFO_PAYLOAD_LEN: usize = 3;

/// Maximum BLE file transfer chunk size in bytes.
/// Source: master-constants.md §5e `FILE_PARTIAL_SIZE`. [H] XPN, XBL
pub const FILE_PARTIAL_SIZE: usize = 120;

/// Last BLE file transfer sequence number.
/// Source: master-constants.md §5e `LAST_SEQUENCE_NO`. [H] XPN, XBL
pub const LAST_SEQUENCE_NO: u16 = 0xFFFF;

/// Expected byte length of the serial number characteristic payload.
/// Source: master-constants.md §5c `CAMERA_SERIAL_NO_SIZE`. [H] XBL
pub const CAMERA_SERIAL_NO_SIZE: usize = 5;
