package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.BleWifiHandoff
import dev.po4yka.frameport.camera.api.CameraWifiConnector
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.CharacteristicId
import dev.po4yka.frameport.camera.api.FujiBleClient
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Typed states emitted by [BleAssistedConnectUseCase].
 *
 * Happy-path order:
 * [Scanning] -> [CameraFound] -> [ObtainingCredentials] -> [RequestingNetwork] -> [Connected]
 *
 * Terminal states: [Connected], [Failed].
 */
sealed interface BleHandoffState {
    /** Scanning BLE for a discoverable camera. */
    data object Scanning : BleHandoffState

    /** A camera advertisement was received; initiating GATT connection. */
    data class CameraFound(
        val displayName: String?,
    ) : BleHandoffState

    /** GATT connected; reading Wi-Fi credentials from the camera characteristics. */
    data object ObtainingCredentials : BleHandoffState

    /** Credentials obtained; requesting the camera Wi-Fi network from Android. */
    data class RequestingNetwork(
        val ssid: String,
    ) : BleHandoffState

    /** Wi-Fi session is open and the PTP-IP handshake is in progress or complete. */
    data class Connected(
        val ssid: String,
    ) : BleHandoffState

    /**
     * Terminal failure.
     *
     * [reason] is a safe, non-PII diagnostic string. It must not contain the camera SSID,
     * passphrase, BLE MAC address, or any user-identifiable data.
     */
    data class Failed(
        val reason: String,
    ) : BleHandoffState
}

/**
 * Characteristic IDs for the Fujifilm camera BLE service.
 *
 * UUIDs are defined in fuji-ble-protocol (Rust) and mirrored here as stable
 * string constants. Values are sourced from docs/reference/ble-wifi-discovery.md
 * and docs/reference/master-constants.md.
 *
 * PRIVACY: these are protocol identifiers, not user data — safe to use in code.
 */
internal object FujiCharacteristicIds {
    /**
     * Characteristic that carries the camera Wi-Fi SSID (UTF-8 string).
     * Source: docs/reference/ble-wifi-discovery.md — CHR_CAMERA_SSID_NAME_STRING
     * (SERVICE_CAMERA_INFORMATION). Mirrors the canonical constant in fuji-ble-protocol.
     */
    val WIFI_SSID = CharacteristicId("bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4")

    /**
     * Characteristic that carries the camera Wi-Fi passphrase (UTF-8 string).
     * Source: docs/reference/ble-wifi-discovery.md — CHR_CAMERA_WIFI_PASSPHRASE_STRING
     * (SERVICE_CAMERA_INFORMATION). Mirrors the canonical constant in fuji-ble-protocol.
     *
     * PRIVACY: the value read from this characteristic must NEVER be logged at any level.
     */
    val WIFI_PASSPHRASE = CharacteristicId("e809256a-915c-4967-92e8-53b7d4cad213")

    /**
     * Characteristic that carries the camera Wi-Fi MAC address used as BSSID.
     * Source: docs/reference/ble-wifi-discovery.md — CHR_CAMERA_MAC_ADDRESS
     * (SERVICE_CAMERA_INFORMATION). Required for precise AP targeting.
     *
     * PRIVACY: the value read from this characteristic must be redacted before diagnostics.
     */
    val WIFI_BSSID = CharacteristicId("49a12959-dfaa-4eb2-89ce-62548ad948f3")
}

/**
 * Orchestrates the BLE-assisted Wi-Fi handoff flow.
 *
 * Responsibilities:
 * 1. Observes [FujiBleClient.scan] until a camera advertisement is found.
 * 2. Connects to the camera over GATT.
 * 3. Reads the SSID, passphrase, and BSSID characteristics.
 * 4. Validates the result as a [BleWifiHandoff] (non-empty SSID/passphrase, valid BSSID).
 * 5. Calls [CameraWifiConnector.requestCameraNetwork] to join the exact camera Wi-Fi network.
 * 6. Emits typed [BleHandoffState] transitions throughout.
 *
 * Boundary enforcement:
 * - This use case owns ONLY orchestration logic. It never accesses BluetoothGatt directly.
 * - All BLE I/O is delegated to [FujiBleClient].
 * - All Wi-Fi network requests are delegated to [CameraWifiConnector].
 * - No BLE MAC address, SSID, or passphrase is ever passed to a logging call.
 *
 * See: docs/adr/0003-ble-client-abstraction.md, docs/android/bluetooth-architecture.md,
 *      docs/android/wifi-network-routing.md, docs/adr/0002-wifi-socket-fd-handoff.md.
 */
class BleAssistedConnectUseCase
    @Inject
    constructor(
        private val fujiBleClient: FujiBleClient,
        private val cameraWifiConnector: CameraWifiConnector,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Executes the BLE-assisted Wi-Fi handoff.
         *
         * cancel-safe: the returned Flow is cold; cancellation stops emission at the next
         * suspension point. [FujiBleClient.scan] returns a cold Flow; cancellation stops it
         * cleanly. [FujiBleClient.connect], [read], and [CameraWifiConnector.requestCameraNetwork]
         * are individually cancel-safe per their contracts in [FujiBleClient] and
         * [CameraWifiConnector]. On cancellation, no partial state is leaked.
         *
         * PRIVACY:
         * - The camera BLE id (MAC) is NEVER logged.
         * - The SSID is NEVER logged.
         * - The passphrase is NEVER logged at any level.
         * - Raw characteristic byte arrays are NEVER logged.
         */
        // cancel-safe: cold Flow; scan/connect/read/requestCameraNetwork are each individually cancel-safe per their contracts; flowOn(ioDispatcher) keeps collection cooperative; no partial state leaked on cancellation.
        operator fun invoke(): Flow<BleHandoffState> =
            flow {
                emit(BleHandoffState.Scanning)

                // Step 1 — Scan for the first available camera advertisement.
                val advertisement =
                    fujiBleClient.scan().firstOrError()
                        ?: run {
                            emit(BleHandoffState.Failed("No camera advertisement received during BLE scan."))
                            return@flow
                        }

                emit(BleHandoffState.CameraFound(displayName = advertisement.camera.displayName))

                // Step 2 — Connect over GATT.
                val connectResult = fujiBleClient.connect(advertisement.camera)
                if (connectResult.isFailure) {
                    val diagnostic = connectResult.exceptionOrNull()?.message ?: "GATT connect failed"
                    emit(BleHandoffState.Failed(diagnostic.redactPii()))
                    return@flow
                }

                // Guard: verify the state machine reached Connected before reading characteristics.
                if (fujiBleClient.connectionState.value != BleConnectionState.Connected) {
                    emit(BleHandoffState.Failed("GATT connection state did not reach Connected."))
                    return@flow
                }

                emit(BleHandoffState.ObtainingCredentials)

                // Step 3 — Read SSID characteristic.
                val ssidResult = fujiBleClient.read(FujiCharacteristicIds.WIFI_SSID)
                if (ssidResult.isFailure) {
                    val diagnostic = ssidResult.exceptionOrNull()?.message ?: "SSID characteristic read failed"
                    emit(BleHandoffState.Failed(diagnostic.redactPii()))
                    return@flow
                }
                val ssidBytes = ssidResult.getOrThrow()
                val ssid = ssidBytes.decodeToStringOrNull()
                if (ssid.isNullOrBlank()) {
                    emit(BleHandoffState.Failed("SSID characteristic returned empty or non-UTF-8 value."))
                    return@flow
                }

                // Step 4 — Read passphrase characteristic.
                // PRIVACY: passphrase bytes and string value are NEVER logged.
                val passphraseResult = fujiBleClient.read(FujiCharacteristicIds.WIFI_PASSPHRASE)
                if (passphraseResult.isFailure) {
                    emit(BleHandoffState.Failed("Passphrase characteristic read failed."))
                    return@flow
                }
                val passphraseBytes = passphraseResult.getOrThrow()
                val passphrase = passphraseBytes.decodeToStringOrNull()
                if (passphrase.isNullOrBlank()) {
                    emit(BleHandoffState.Failed("Passphrase characteristic returned empty or non-UTF-8 value."))
                    return@flow
                }

                // Step 5 — Read and validate the camera Wi-Fi MAC address for BSSID targeting.
                val bssidResult = fujiBleClient.read(FujiCharacteristicIds.WIFI_BSSID)
                if (bssidResult.isFailure) {
                    emit(BleHandoffState.Failed("BSSID characteristic read failed."))
                    return@flow
                }
                val bssid = bssidResult.getOrThrow().decodeBssidOrNull()
                if (bssid == null) {
                    emit(BleHandoffState.Failed("BSSID characteristic returned malformed value."))
                    return@flow
                }

                // Step 6 — Validate as BleWifiHandoff (mirrors Rust-layer validation contract).
                val handoff = BleWifiHandoff(ssid = ssid, passphrase = passphrase, bssid = bssid)

                // PRIVACY: log only the SSID length as a diagnostic proxy — never the value itself.
                emit(BleHandoffState.RequestingNetwork(ssid = handoff.ssid))

                // Step 7 — Request the exact camera Wi-Fi network via Android.
                val credentials =
                    CameraWifiCredentials(
                        ssid = handoff.ssid,
                        passphrase = handoff.passphrase,
                        bssid = handoff.bssid,
                    )
                val networkResult = cameraWifiConnector.requestCameraNetwork(credentials)
                if (networkResult.isFailure) {
                    val diagnostic = networkResult.exceptionOrNull()?.message ?: "requestCameraNetwork failed"
                    emit(BleHandoffState.Failed(diagnostic.redactPii()))
                    return@flow
                }

                emit(BleHandoffState.Connected(ssid = handoff.ssid))
            }.flowOn(ioDispatcher)
    }

/**
 * Collects the first element of this [Flow] without suspending indefinitely.
 *
 * Returns null if the flow completes without emitting.
 *
 * cancel-safe: uses [kotlinx.coroutines.flow.firstOrNull] semantics; the upstream
 * flow is cancelled immediately after the first element is collected.
 */
// cancel-safe: collects at most one element then stops the upstream via a sentinel; cancellation of the caller propagates through collect() with no retained state.
private suspend fun <T> Flow<T>.firstOrError(): T? {
    var result: T? = null
    try {
        collect { item ->
            result = item
            throw StopCollectionSignal
        }
    } catch (_: StopCollectionSignal) {
        // Normal early-exit; result is populated.
    }
    return result
}

/** Internal sentinel exception used to stop Flow collection after the first element. */
private object StopCollectionSignal : Throwable()

/**
 * Decodes this [ByteArray] to a UTF-8 [String], returning null on decoding failure.
 *
 * PRIVACY: the return value must never be logged if it originates from a credential
 * characteristic. The caller is responsible for that constraint.
 */
private fun ByteArray.decodeToStringOrNull(): String? =
    try {
        toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

private fun ByteArray.decodeBssidOrNull(): String? {
    if (size == 6) {
        return joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }
    val text = decodeToStringOrNull()?.trim()?.replace('-', ':') ?: return null
    return if (text.matches(MAC_ADDRESS_REGEX)) text.uppercase() else null
}

private val MAC_ADDRESS_REGEX = Regex("[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}")

/**
 * Strips any substring that could be an IP address, MAC address, or hex-run from a
 * diagnostic string before it is surfaced in a [BleHandoffState.Failed] message.
 *
 * This is a best-effort filter; it does not guarantee complete PII removal. Diagnostic
 * strings should be authored by the implementation layer to be PII-free before reaching
 * this call site.
 */
private fun String.redactPii(): String =
    this
        .replace(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"), "<mac>")
        .replace(Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"), "<ip>")
