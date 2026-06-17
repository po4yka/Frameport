package dev.po4yka.frameport.camera.bluetooth

import dev.po4yka.frameport.camera.api.CharacteristicId

internal sealed class BleTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class PermissionDenied(
        val permission: String,
        cause: Throwable? = null,
    ) : BleTransportException("Bluetooth permission denied: $permission", cause)

    class BluetoothDisabled(
        cause: Throwable? = null,
    ) : BleTransportException("Bluetooth adapter is disabled", cause)

    class GattConnectionFailed(
        cause: Throwable? = null,
    ) : BleTransportException("Bluetooth GATT connection failed", cause)

    class ServiceNotFound(
        val characteristicId: CharacteristicId,
    ) : BleTransportException("No BLE service found for characteristic ${characteristicId.value}")

    class CharacteristicTimeout(
        val characteristicId: CharacteristicId,
        val operation: Operation,
        cause: Throwable? = null,
    ) : BleTransportException("Bluetooth characteristic ${operation.label} timed out for ${characteristicId.value}", cause)

    class CharacteristicOperationFailed(
        val characteristicId: CharacteristicId,
        val operation: Operation,
        cause: Throwable,
    ) : BleTransportException("Bluetooth characteristic ${operation.label} failed for ${characteristicId.value}", cause)

    enum class Operation(
        val label: String,
    ) {
        Read("read"),
        Write("write"),
        Observe("observe"),
    }
}

internal fun Throwable.asBleConnectFailure(): Throwable =
    when {
        this is BleTransportException -> this
        this is SecurityException -> BleTransportException.PermissionDenied(
            permission = BLUETOOTH_CONNECT_PERMISSION,
            cause = this,
        )
        message.orEmpty().contains("bluetooth disabled", ignoreCase = true) -> {
            BleTransportException.BluetoothDisabled(this)
        }
        message.orEmpty().contains("bluetooth adapter is disabled", ignoreCase = true) -> {
            BleTransportException.BluetoothDisabled(this)
        }
        else -> BleTransportException.GattConnectionFailed(this)
    }

internal fun Throwable.asBleScanFailure(): Throwable =
    when {
        this is BleTransportException -> this
        this is SecurityException -> BleTransportException.PermissionDenied(
            permission = BLUETOOTH_SCAN_PERMISSION,
            cause = this,
        )
        message.orEmpty().contains("bluetooth disabled", ignoreCase = true) -> {
            BleTransportException.BluetoothDisabled(this)
        }
        message.orEmpty().contains("bluetooth adapter is disabled", ignoreCase = true) -> {
            BleTransportException.BluetoothDisabled(this)
        }
        else -> this
    }

private const val BLUETOOTH_CONNECT_PERMISSION = "android.permission.BLUETOOTH_CONNECT"
private const val BLUETOOTH_SCAN_PERMISSION = "android.permission.BLUETOOTH_SCAN"
