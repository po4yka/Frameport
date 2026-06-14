package dev.po4yka.frameport.feature.connection

import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.model.TransportKind

/**
 * Feature-local UI discriminant for connection errors.
 *
 * This is NOT a domain error type — it lives in :feature:connection only and is
 * mapped from the flat [FrameportError] sealed hierarchy by [mapToConnectionError].
 * The Composable maps each variant to a human-readable string; the ViewModel maps
 * [FrameportError] -> [ConnectionError] via [mapToConnectionError].
 */
sealed interface ConnectionError {
    sealed interface Permission : ConnectionError {
        /** android.permission.BLUETOOTH_SCAN was denied. */
        data object BluetoothScan : Permission

        /** android.permission.BLUETOOTH_CONNECT was denied. */
        data object BluetoothConnect : Permission

        /** android.permission.NEARBY_WIFI_DEVICES was denied. */
        data object NearbyWifiDevices : Permission

        /** Some other permission was denied; carries the raw permission string for diagnostics. */
        data class Other(
            val permission: String,
        ) : Permission
    }

    sealed interface Wifi : ConnectionError {
        /** Camera Wi-Fi network was not available when the system was queried. */
        data object NetworkUnavailable : Wifi

        /** User rejected the system Wi-Fi network request dialog. */
        data object UserRejected : Wifi

        /** Socket could not be bound to the camera network. */
        data object SocketBindFailed : Wifi
    }

    sealed interface Protocol : ConnectionError {
        /** PTP-IP handshake was rejected by the camera. */
        data object HandshakeRejected : Protocol

        /** Camera is busy (another client is connected or a mode conflict exists). */
        data object CameraBusy : Protocol
    }

    /**
     * Catch-all for errors that do not map to a known family.
     * [message] is a safe, non-PII diagnostic string (already redacted by the domain layer).
     */
    data class Unknown(
        val message: String,
    ) : ConnectionError
}

/**
 * Pure mapping function from the flat [FrameportError] domain type to the
 * feature-local [ConnectionError] UI discriminant.
 *
 * This function is intentionally free of Android framework types so it can be
 * unit-tested without instrumentation.
 */
fun mapToConnectionError(error: FrameportError): ConnectionError =
    when (error) {
        is FrameportError.PermissionDenied -> {
            when {
                error.permission.endsWith("BLUETOOTH_SCAN") -> ConnectionError.Permission.BluetoothScan
                error.permission.endsWith("BLUETOOTH_CONNECT") -> ConnectionError.Permission.BluetoothConnect
                error.permission.endsWith("NEARBY_WIFI_DEVICES") -> ConnectionError.Permission.NearbyWifiDevices
                else -> ConnectionError.Permission.Other(error.permission)
            }
        }

        is FrameportError.TransportUnavailable -> {
            when (error.transportKind) {
                TransportKind.WifiPtpIp -> ConnectionError.Wifi.NetworkUnavailable
                TransportKind.BluetoothLe -> ConnectionError.Unknown(error.message)
                TransportKind.UsbPtp -> ConnectionError.Unknown(error.message)
            }
        }

        is FrameportError.ProtocolUnavailable -> {
            ConnectionError.Protocol.HandshakeRejected
        }

        is FrameportError.MediaUnavailable -> {
            ConnectionError.Unknown(error.message)
        }

        is FrameportError.Unknown -> {
            ConnectionError.Unknown(error.message)
        }
    }
