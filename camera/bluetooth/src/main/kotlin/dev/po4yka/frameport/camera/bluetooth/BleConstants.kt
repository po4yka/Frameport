package dev.po4yka.frameport.camera.bluetooth

/**
 * BLE constants for Fujifilm camera discovery and communication.
 *
 * All values are traceable to docs/reference/ble-wifi-discovery.md and
 * docs/reference/master-constants.md. They are interoperability facts confirmed by
 * independent BLE packet capture and GATT enumeration. No value is copied from any
 * proprietary Fujifilm source or SDK.
 *
 * UUID case: lowercase is canonical at the protocol level.
 * Source confidence is noted inline per docs/reference/ble-wifi-discovery.md legend:
 *   [H] = cross-source agreement, [M] = single authoritative source.
 */
internal object BleConstants {
    // -------------------------------------------------------------------------
    // Manufacturer identification (docs/reference/ble-wifi-discovery.md §Advertisement)
    // -------------------------------------------------------------------------

    /** Fujifilm company identifier used in BLE manufacturer-specific advertisement data. [H] */
    const val MANUFACTURER_COMPANY_ID: Int = 0x04D8

    /**
     * Byte offset within the manufacturer-specific advertisement payload where the
     * 2-byte company identifier appears. [H]
     */
    const val MANUFACTURER_OFFSET: Int = 23

    /**
     * Type byte value within manufacturer payload (index 2 after company ID bytes) that
     * indicates the camera is in pairing mode. Followed by 4-byte pairing token. [M]
     */
    const val MANUFACTURER_TYPE_PAIRING: Byte = 0x02

    // -------------------------------------------------------------------------
    // GATT Service UUIDs (docs/reference/ble-wifi-discovery.md §Services)
    // -------------------------------------------------------------------------

    /** Remote shutter and capture control service. [H] */
    const val SERVICE_CAMERA_CONTROL = "6514eb81-4e8f-458d-aa2a-e691336cdfac"

    /** Camera state notifications (power, card, transfer, errors). [H] */
    const val SERVICE_CAMERA_STATE = "4c0020fe-f3b6-40de-acc9-77d129067b14"

    /** Image transfer settings, IPTC, logging. [H] */
    const val SERVICE_CAMERA_SETTING = "4e941240-d01d-46b9-a5ea-67636806830b"

    /** SSID, passphrase, MAC, serial, Y-number, BLE protocol version. [H] */
    const val SERVICE_CAMERA_INFORMATION = "117c4142-edd4-4c77-8696-dd18eebb770a"

    /** RED-compliant variant of SERVICE_CAMERA_INFORMATION. [H] */
    const val SERVICE_CAMERA_INFORMATION_RED = "a9d2b304-e8d6-4902-8336-352b772d7597"

    /** Pairing key, device name, app info, disconnect reason. [H] */
    const val SERVICE_CONNECTED_DEVICE_INFORMATION = "91f1de68-dff6-466e-8b65-ff13b0f16fb8"

    /** RED-compliant variant of SERVICE_CONNECTED_DEVICE_INFORMATION. [H] */
    const val SERVICE_CONNECTED_DEVICE_INFORMATION_RED = "123d8f06-62a1-4935-9322-833c531ee225"

    /** GPS geolocation and sync settings. [H] */
    const val SERVICE_CURRENT_LOCATION = "3b46ec2b-48ba-41fd-b1b8-ed860b60d22b"

    /** UTC and timezone synchronization. [H] */
    const val SERVICE_CURRENT_TIME = "e872b11f-d526-4ae1-9bb4-89a99d48fa59"

    /** Small-file BLE transfer (not image transfer). [H] */
    const val SERVICE_FILE_TRANSFER = "af854c2e-b214-458e-97e2-912c4ecf2cb8"

    /** Startup value characteristic. [H] */
    const val SERVICE_CAMERA_STARTUP_INFORMATION = "731893f9-744e-4899-b7e3-174106ff2b82"

    /** RED-compliant variant of SERVICE_CAMERA_STARTUP_INFORMATION. [H] */
    const val SERVICE_CAMERA_STARTUP_INFORMATION_RED = "804daa8e-ffeb-4ab3-8e75-6edd7303208d"

    /** Lens product name, serial, firmware version. [H] */
    const val SERVICE_MOUNTED_LENS_INFORMATION = "15ca59fe-620c-464d-a987-223fab660cde"

    /** Standard Bluetooth Device Information Service. [H] */
    const val SERVICE_DEVICE_INFORMATION = "0000180a-0000-1000-8000-00805f9b34fb"

    // -------------------------------------------------------------------------
    // Characteristic UUIDs — Camera Information
    // (docs/reference/ble-wifi-discovery.md §Characteristics — Camera Information)
    // PRIVACY: SSID and passphrase characteristics are sensitive — never log payloads.
    // -------------------------------------------------------------------------

    /** Wi-Fi SSID as UTF-8 string. Read from SERVICE_CAMERA_INFORMATION. [H] */
    const val CHR_CAMERA_SSID_NAME_STRING = "bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4"

    /**
     * WPA2 passphrase as UTF-8 string. Read from SERVICE_CAMERA_INFORMATION.
     * PRIVACY: payload must NEVER appear in any log at any level. [H]
     */
    const val CHR_CAMERA_WIFI_PASSPHRASE_STRING = "e809256a-915c-4967-92e8-53b7d4cad213"

    /**
     * Camera Wi-Fi MAC address (used as BSSID for WifiNetworkSpecifier).
     * PRIVACY: must be hashed before any diagnostic log entry. [H]
     */
    const val CHR_CAMERA_MAC_ADDRESS = "49a12959-dfaa-4eb2-89ce-62548ad948f3"

    /** 5-byte camera serial number. PRIVACY: hash before logging. [H] */
    const val CHR_CAMERA_SERIAL_NUMBER = "e8e40d50-a625-4f1d-96ed-8cec034f5690"

    /** Model variant identifier. [H] */
    const val CHR_CAMERA_Y_NUMBER = "27870478-94a9-4345-849b-efa3bf37887f"

    /** Camera BLE protocol version. [H] */
    const val CHR_CAMERA_BLE_PROTOCOL_VERSION = "389363e4-712e-4cf2-a72e-bfcf7fb6adc1"

    // -------------------------------------------------------------------------
    // Characteristic UUIDs — Connected Device Information (Pairing)
    // (docs/reference/ble-wifi-discovery.md §Characteristics — Connected Device Information)
    // -------------------------------------------------------------------------

    /** 4-byte raw pairing token. Write to SERVICE_CONNECTED_DEVICE_INFORMATION. [H] */
    const val CHR_PAIRING_KEY = "aba356eb-9633-4e60-b73f-f52516dbd671"

    /** ASCII client identifier string. Write. [H] */
    const val CHR_CONNECTED_DEVICE_NAME_STRING = "85b9163e-62d1-49ff-a6f5-054b4630d4a1"

    /** 3-byte payload: 1-byte app ID + 2-byte LE version. Write. [H] */
    const val CHR_CONNECTED_APPLICATION_INFORMATION = "8b5ecf55-fc6b-40d0-b4c1-76f64e5453c7"

    /** Phone BLE protocol version. Write. [H] */
    const val CHR_CONNECTED_DEVICE_BLE_PROTOCOL_VERSION = "eb4166b0-9cca-445e-a4e4-75b3817fd57a"

    /** Device identification number. Write. [H] */
    const val CHR_CONNECTED_DEVICE_IDENTIFICATION_NUMBER = "f557d96b-8284-4667-8793-b971c1deca2a"

    /** Disconnect reason (u16). Write. [H] */
    const val CHR_CONNECTED_DEVICE_DISCONNECTED_REASON = "7ede1988-b27e-43fc-80f4-6fec994f0552"

    /** Image receive readiness flag. Write. [H] */
    const val CHR_CONNECTED_DEVICE_IMAGE_RECEIVE_STATE = "a80be3f8-8bcb-4add-a725-170b7a53adc9"

    /** Number of paired devices on camera. Read. [H] */
    const val CHR_PAIRING_SMART_DEVICE_NUM = "8814441b-1d7b-4046-891d-d8f80864cc8e"

    // -------------------------------------------------------------------------
    // Characteristic UUIDs — Camera State (Notifications)
    // (docs/reference/ble-wifi-discovery.md §Characteristics — Camera State)
    // -------------------------------------------------------------------------

    /** Camera power and health state notification. [H] */
    const val CHR_CAMERA_VITAL_STATE = "e6692c5c-b7cd-44f4-95fc-eda07ce32560"

    /** Memory card state notification. [H] */
    const val CHR_CARD_STATE = "34d8c8de-e2a9-43ff-822c-7d945dd8d8e1"

    /** Transfer progress state notification. [H] */
    const val CHR_TRANSFER_STATE = "bd17ba04-b76b-4892-a545-b73ba1f74dae"

    /** Detailed error state. Read + Notify. [H] */
    const val CHR_STATE_ERROR_DETAILS = "1587b102-0b6d-4b63-9226-66fcc6d17387"

    /** Power key press state (1 byte). Notify. [H] */
    const val CHR_CAMERA_POWER_KEY_STATE = "f90f7d3a-3b64-45c6-ab21-933900184837"

    /** Camera AP state; also notification for function launch response. Notify. [H] */
    const val CHR_AP_STATE = "a68e3f66-0fcc-4395-8d4c-aa980b5877fa"

    // -------------------------------------------------------------------------
    // Characteristic UUIDs — Camera Control (Capture)
    // (docs/reference/ble-wifi-discovery.md §Characteristics — Camera Control)
    // -------------------------------------------------------------------------

    /** u16 LE: S0=0, S1=1, S2=2. Write. [H] */
    const val CHR_SHOOTING_REQUEST = "7fcf49c6-4ff0-4777-a03d-1a79166af7a8"

    /** u16 LE: function launch type. Write. [H] */
    const val CHR_FUNCTION_LAUNCH_REQUEST = "600655e6-3637-42f1-8fb2-44efc5c63b13"

    /** u16 LE: movie record type. Write. [H] */
    const val CHR_MOVIE_REC_REQUEST = "861442ab-b94e-4935-90d9-41e291d91374"

    /** Power state control. Write. [H] */
    const val CHR_POWER_CONTROL_REQUEST = "43070f6c-51e0-4887-86a7-5f762bda5791"

    /** Remote wakeup setting. Write. [H] */
    const val CHR_REMOTE_BOOT_SETTING = "7170fd5a-56d9-4c19-b043-7a7047d8e1a0"

    /** Camera wakeup mode. Write/Read. [H] */
    const val CHR_WAKEUP_MODE = "9c72c205-5740-4f17-9949-0d3fadf2f67a"

    // -------------------------------------------------------------------------
    // Characteristic UUIDs — Geolocation
    // (docs/reference/ble-wifi-discovery.md §Characteristics — Geolocation)
    // -------------------------------------------------------------------------

    /** 23-byte LE payload (LocationAndSpeed). Write. [H] */
    const val CHR_LOCATION_AND_SPEED = "0f36ec14-29e5-411a-a1b6-64ee8383f090"

    /** Enable/disable location sync (u8). Write/Read. [H] */
    const val CHR_LOCATION_SYNC_SETTING = "aab609c4-94dd-4d89-bc60-665d5090b828"

    /** u16 LE: sync interval in seconds. Write. [H] */
    const val CHR_LOCATION_SYNC_CYCLE = "c95d91ae-b247-4d6d-8661-7dd5d6a0f85b"

    // -------------------------------------------------------------------------
    // Characteristic UUIDs — Time Synchronization
    // (docs/reference/ble-wifi-discovery.md §Characteristics — Time Synchronization)
    // -------------------------------------------------------------------------

    /** UTC time and timezone offset. Read/Write. [H] */
    const val CHR_UTC_AND_TIMEZONE = "c52edbce-1fe2-4ecc-9483-907e6592be9e"

    /** Camera clock date/time. Read/Write. [H] */
    const val CHR_DATE_TIME = "b9bfd37f-ccad-4d36-a1ee-018e792b3edf"

    // -------------------------------------------------------------------------
    // Characteristic UUIDs — Settings and File Transfer
    // (docs/reference/ble-wifi-discovery.md §Characteristics — Settings and File Transfer)
    // -------------------------------------------------------------------------

    /** Format flags (JPEG/RAF/HEIF). Write. [H] */
    const val CHR_IMAGE_TRANSFER_SETTING = "caedb497-83bf-482c-91ef-91cf6f1216ff"

    /** Firmware update state notification. v1: out of scope. [H] */
    const val CHR_FWUPDATE_STATE = "049ec406-ef75-4205-a390-08fe209c51f0"

    /** BLE file chunk (120 bytes max). Write. [H] */
    const val CHR_FILE_PARTIAL_DATA = "ac0c799a-fa6c-4df5-bbc5-bb95cce7e6ea"

    /** BLE file transfer transaction state. Notify. [H] */
    const val CHR_FILE_TRANSACTION_STATE = "2e27ed9f-5506-41cd-ba48-dac06669ad95"

    /** Settings backup state. Notify. [H] */
    const val CHR_BACKUP_STATE = "11438c83-cfb0-4511-841b-759e0d2321c8"

    /** Settings restore state. Notify. [H] */
    const val CHR_RESTORE_STATE = "4b3a413c-230f-42bc-b3ed-b1db2eadee82"

    // -------------------------------------------------------------------------
    // CCCD Descriptor
    // (docs/reference/ble-wifi-discovery.md §CCCD Descriptor)
    // -------------------------------------------------------------------------

    /**
     * Standard GATT Client Characteristic Configuration Descriptor UUID.
     * Write 0x0001 to enable notifications, 0x0000 to disable.
     */
    const val CCCD_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /** CCCD value to enable notifications. */
    val CCCD_ENABLE_NOTIFICATION: ByteArray = byteArrayOf(0x01, 0x00)

    /** CCCD value to disable notifications. */
    val CCCD_DISABLE_NOTIFICATION: ByteArray = byteArrayOf(0x00, 0x00)

    // -------------------------------------------------------------------------
    // Timing constants
    // -------------------------------------------------------------------------

    /** Default per-operation GATT timeout in milliseconds. */
    const val GATT_OPERATION_TIMEOUT_MS: Long = 5_000L

    /** Timeout for the initial GATT connect + service discovery + MTU sequence. */
    const val GATT_CONNECT_TIMEOUT_MS: Long = 20_000L

    /** Desired MTU size for data transfer efficiency. [M] */
    const val PREFERRED_MTU: Int = 517

    /** Base delay in milliseconds for the first reconnect attempt. */
    const val RECONNECT_BASE_DELAY_MS: Long = 1_000L

    /** Maximum number of reconnect attempts before transitioning to [BleConnectionState.Failed]. */
    const val RECONNECT_MAX_ATTEMPTS: Int = 5
}
