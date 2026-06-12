---
name: android-ble-gatt
description: Android BLE guidance for Frameport — BluetoothLeScanner scan/filter/settings, BluetoothGatt connect/discoverServices/requestMtu, single-in-flight GATT operation queue, characteristic read/write/notify with CCCD descriptor, bonding semantics, status 133 recovery, reconnect/backoff, and API 33 callback deprecations. Use when authoring or modifying code in :camera:bluetooth, the FujiBleClient interface/implementation, BleConnectionState, or any scan/GATT/notification flow in the camera/bluetooth module. Also use when reviewing permission declarations for BLUETOOTH_SCAN (neverForLocation) / BLUETOOTH_CONNECT or wiring the Rust fuji-ble-protocol crate over the FujiBleProtocolBridge JNI boundary.
---

# Android BLE / GATT — Frameport

## When to use

- Authoring or modifying scan logic (`BluetoothLeScanner`, `ScanFilter`, `ScanSettings`, `ScanCallback`) in `:camera:bluetooth`.
- Implementing or reviewing the `FujiBleClient` interface, its concrete implementation, or the `BleConnectionState` enum.
- Wiring GATT connection, service discovery, MTU negotiation, characteristic reads/writes, or notification subscriptions.
- Designing or auditing the GATT operation queue (single-in-flight serialization, timeout, cancellation on disconnect).
- Handling status 133 (`GATT_ERROR`) recovery and reconnect/backoff logic.
- Reviewing BLE permission declarations (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) in `:app` manifest.
- Authoring or reviewing code in `fuji-ble-protocol` that receives raw `ByteArray` payloads from Android over the `FujiBleProtocolBridge`.

## Architecture boundary (non-negotiable)

Per ADR 0003, the `:camera:bluetooth` module is the exclusive owner of all Android BLE types. No other module may import `BluetoothLeScanner`, `BluetoothGatt`, `BluetoothGattCharacteristic`, `BluetoothGattService`, or `BluetoothDevice`.

```
Compose UI  →  ViewModel  →  Use case  →  CameraRepository
  →  FujiBleClient interface  →  [camera:bluetooth implementation]
       ├─ BluetoothLeScanner
       ├─ BluetoothGatt + operation queue
       └─ raw ByteArray  →  FujiBleProtocolBridge (JNI)  →  fuji-ble-protocol (Rust)
```

ViewModels must not call JNI directly. Composables must not touch `BluetoothGatt`. Both receive `BleConnectionState` via `StateFlow<BleConnectionState>` from `FujiBleClient`.

## Permissions

### Manifest (`:app` — minSdk 31)

```xml
<!-- Legacy for completeness; no runtime effect at minSdk 31 -->
<uses-permission android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />

<!-- API 31+: Frameport does not infer location from scan results -->
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- API ≤30 fallback; maxSdkVersion keeps it off API 31+ -->
<uses-permission
    android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

`neverForLocation` on `BLUETOOTH_SCAN` means the app never needs `ACCESS_FINE_LOCATION` on API 31+. Trade-off: the system may filter BLE advertisements that do not include a service UUID. Camera advertisements that include a service UUID are expected to be unaffected, but validate on real hardware.

### Runtime checks

`BLUETOOTH_SCAN` is required before `startScan`. `BLUETOOTH_CONNECT` is required before `connectGatt`, reading the device name, or initiating bonding. The `:core:permissions` module owns the request flow. The `:camera:bluetooth` implementation observes the granted state; permission denial must surface as `BleConnectionState.Failed` (current enum) or, when the type is evolved into a sealed interface, as a typed `FujiBleError` variant (e.g. `PermissionDenied.BluetoothScan`).

Always gate scan startup with a hardware feature check:

```kotlin
if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
    // emit BleConnectionState.Failed (current enum); typed FujiBleError.Bluetooth.UnsupportedDevice when sealed interface is introduced
    return
}
```

## Scanning

### ScanFilter and ScanSettings

```kotlin
val filter = ScanFilter.Builder()
    // Filter by a known Fujifilm service UUID to avoid processing unrelated devices.
    // Verify the exact UUID against real-device interop notes — do not assert undocumented values as fact.
    .setServiceUuid(ParcelUuid(FUJI_BLE_SERVICE_UUID))
    .build()

val settings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)   // Use during active discovery UI only
    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
    .build()
```

Use `SCAN_MODE_BALANCED` or `SCAN_MODE_LOW_POWER` when not actively showing the discovery screen. Stop scanning before calling `connectGatt` (saves radio resources; mandated by ADR 0003).

### Scan throttle (API 31+)

Android throttles apps that start and stop scans more than 5 times in 30 seconds — `ScanCallback.onScanFailed(SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6)`. The `FujiBleClient.scan()` `Flow` must use bounded scan windows and must not restart on every `collect` subscription. A single shared `callbackFlow` or a `SharedFlow` with replay=0 is safer than restarting `startScan` per subscriber.

### Stopping the scan

Always stop using the same `ScanCallback` instance:

```kotlin
scanner.stopScan(scanCallback)
```

Leaking a scan (never calling `stopScan`) drains battery and triggers throttling on subsequent launches.

## GATT connection lifecycle

### Connect

```kotlin
// Use autoConnect=false for user-initiated direct connect.
// autoConnect=true is for deferred background reconnect — NOT a fix for status 133.
val gatt = device.connectGatt(context, false, gattCallback)
```

After a 133 error or any disconnect, the handle is poisoned. You must call `gatt.disconnect()` then `gatt.close()` and null the reference before retrying. Calling `close()` alone (without `disconnect()`) may leave the link in an inconsistent state on some stacks.

### State transitions

```kotlin
override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    if (status != BluetoothGatt.GATT_SUCCESS) {
        // status 133 (GATT_ERROR) is the most common; treat any non-zero status as fatal.
        handleGattError(gatt, status)
        return
    }
    when (newState) {
        BluetoothProfile.STATE_CONNECTED -> {
            // Safe to call discoverServices only here.
            gatt.discoverServices()
        }
        BluetoothProfile.STATE_DISCONNECTED -> handleDisconnect(gatt)
    }
}
```

Never call `discoverServices()` before `STATE_CONNECTED` arrives in `onConnectionStateChange`.

### Service discovery and MTU negotiation

Safe ordering: `onConnectionStateChange(CONNECTED)` → `discoverServices()` → `onServicesDiscovered(GATT_SUCCESS)` → `requestMtu(mtu)` → `onMtuChanged(GATT_SUCCESS)` → begin characteristic operations.

```kotlin
override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
    if (status != BluetoothGatt.GATT_SUCCESS) {
        // emit BleConnectionState.Failed (current enum); typed FujiBleError.Bluetooth.ServiceDiscoveryFailed when sealed interface is introduced
        return
    }
    // Request MTU before heavy data operations; valid range 23–517.
    gatt.requestMtu(512)
}
```

## GATT operation queue (single-in-flight serialization)

The Android GATT stack is single-threaded internally. Issuing a second operation before the callback for the first arrives produces unpredictable behavior or silent failure.

Every operation — including `writeDescriptor` for CCCD — must go through a serializing queue. One recommended pattern: a `Channel<GattOperation>` with a single consumer coroutine that suspends on `suspendCancellableCoroutine` and resumes from the GATT callback.

```kotlin
// Simplified sketch — adapt to Frameport's coroutine scope and error model.
private val operationChannel = Channel<GattOperation>(capacity = Channel.UNLIMITED)

// Single consumer — one coroutine, one operation in flight at a time.
private suspend fun drainQueue() {
    for (op in operationChannel) {
        val result = executeWithTimeout(op, timeoutMs = 5_000L)
        op.continuation.resume(result)
    }
}

// On disconnect: cancel the channel and resume all pending ops with failure.
private fun cancelPendingOperations(reason: FujiBleError) {
    operationChannel.cancel()
    // resume any active continuation with failure
}
```

The queue must be cancelled and all pending continuations resumed with a failure result when the connection closes. A suspended coroutine waiting for a GATT callback will hang indefinitely if disconnection does not release it.

## Characteristic reads and writes

### Read (API 31 + API 33 dual-override pattern)

```kotlin
// API 31–32: old overload. Suppress deprecation — needed for minSdk 31.
@Suppress("OVERRIDE_DEPRECATION")
override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    status: Int,
) {
    // On API 31-32, read the value from characteristic.value (the deprecated field).
    // This overload is NOT called on API 33+.
    if (status == BluetoothGatt.GATT_SUCCESS) {
        resumeCurrentOperation(characteristic.value ?: byteArrayOf())
    } else {
        resumeCurrentOperationWithError(status)
    }
}

// API 33+: new overload with explicit value parameter.
override fun onCharacteristicRead(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    status: Int,
) {
    if (status == BluetoothGatt.GATT_SUCCESS) {
        resumeCurrentOperation(value)
    } else {
        resumeCurrentOperationWithError(status)
    }
}
```

Do not read `characteristic.value` in the API 33 overload — use the explicit `value` parameter; the field reflects the last-seen value, not the just-read value.

### Write

```kotlin
// API 31–32: set write type on the characteristic then call writeCharacteristic.
// API 33+: use the new overload writeCharacteristic(characteristic, value, writeType).
fun enqueueWrite(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    payload: ByteArray,
    writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, // with ACK
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(characteristic, payload, writeType)
    } else {
        @Suppress("DEPRECATION")
        characteristic.value = payload
        @Suppress("DEPRECATION")
        characteristic.writeType = writeType
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(characteristic)
    }
}
```

## Notification subscription (CCCD two-step)

`setCharacteristicNotification` is a local-only flag. Server-side notification delivery requires writing the CCCD descriptor. Both steps must be sequenced through the operation queue.

CCCD UUID: `00002902-0000-1000-8000-00805f9b34fb`

```kotlin
// Step 1: local flag (does not suspend — not a GATT operation itself).
gatt.setCharacteristicNotification(characteristic, true)

// Step 2: enqueue the CCCD write as a GATT operation; suspend until onDescriptorWrite.
val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
val descriptor = characteristic.getDescriptor(cccdUuid)
    ?: error("CCCD descriptor not found for ${characteristic.uuid}")

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
} else {
    @Suppress("DEPRECATION")
    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    @Suppress("DEPRECATION")
    gatt.writeDescriptor(descriptor)
}

// Step 3: wait for onDescriptorWrite(status == GATT_SUCCESS) via operation queue.
// Only then emit from notifications(characteristicId) Flow.
```

The `notifications(CharacteristicId): Flow<ByteArray>` from `FujiBleClient` must be cancelled when the connection closes so callers do not hang indefinitely.

### onCharacteristicChanged — dual-override (API 31 + API 33)

```kotlin
@Suppress("OVERRIDE_DEPRECATION")
override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
) {
    // API 31–32 only. Use characteristic.value here.
    val value = characteristic.value ?: return
    dispatchNotification(characteristic.uuid, value)
}

override fun onCharacteristicChanged(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
) {
    // API 33+. Use the explicit value parameter.
    dispatchNotification(characteristic.uuid, value)
}
```

## Status 133 (GATT_ERROR) recovery

Status 133 is the most common Android BLE connection failure. It can result from a stale GATT handle, a link-layer collision, or a firmware quirk on the peripheral.

Correct recovery sequence:

1. `gatt.disconnect()` — initiate disconnection.
2. Wait for `onConnectionStateChange(STATE_DISCONNECTED)` (or a short timeout).
3. `gatt.close()` — release resources; the handle is now unusable.
4. Null the `gatt` reference.
5. Wait for a jittered exponential backoff delay.
6. Create a fresh handle via `device.connectGatt(context, false, newCallback)`.

Do not use `autoConnect=true` as a workaround. Field evidence shows it does not reduce 133 rates; it changes connection scheduling but the underlying causes remain.

```kotlin
private suspend fun recoverFrom133(gatt: BluetoothGatt) {
    gatt.disconnect()
    delay(150)           // brief pause for stack cleanup
    gatt.close()
    currentGatt = null
    val backoffMs = (retryCount * 1000L + jitter()).coerceAtMost(30_000L)
    delay(backoffMs)
    retryConnect()
}
```

Bound retries and surface the failure to the user as `BleConnectionState.Failed` (current enum) or, when the type is evolved into a sealed interface, as `BleConnectionState.Error(FujiBleError.Bluetooth.GattConnectionFailed)`, after exhausting them.

## Bonding

`BluetoothDevice.ACTION_BOND_STATE_CHANGED` carries bond state values: `BOND_NONE=10`, `BOND_BONDING=11`, `BOND_BONDED=12`. Android bonding (link-key exchange) is distinct from Fujifilm camera registration/pairing state. A device may be `BOND_BONDED` at the Android level but not registered with the camera application, or the camera may require its own handshake regardless of bond state. Verify the exact pairing handshake against real device interop notes — the sequence is vendor-specific and not publicly documented by Fujifilm.

## Threading — GATT callbacks run on the Binder thread

`BluetoothGattCallback` fires on a Binder thread, not the main thread and not a coroutine dispatcher. Bridge correctly:

```kotlin
// Inside the operation queue consumer:
suspendCancellableCoroutine { continuation ->
    activeContinuation = continuation
    gatt.readCharacteristic(characteristic)
    continuation.invokeOnCancellation {
        activeContinuation = null
        // The GATT read may still complete; guard against resuming a cancelled continuation.
    }
}
// The callback calls activeContinuation?.resume(value) — safe from any thread.
```

ViewModels collect `StateFlow<BleConnectionState>` which is updated from the Binder-thread callback via the queue consumer coroutine running in an appropriate dispatcher (e.g. `Dispatchers.IO`). No Binder-thread work reaches the ViewModel.

## Rust protocol boundary (fuji-ble-protocol)

Raw `ByteArray` from Android crosses the `FujiBleProtocolBridge` JNI boundary as opaque bytes. The Rust crate `fuji-ble-protocol` (at `rust/fuji-rs/crates/fuji-ble-protocol`) must:

- Treat every payload as untrusted: validate lengths before indexing — never panic on malformed input.
- Return typed errors via `thiserror`.
- Not store or log camera Wi-Fi passphrases, pairing keys, or full MAC addresses.
- Not store references to Android objects; the bridge is stateless from Android's perspective.
- Expose deterministic parsing suitable for fixture-based unit tests.

No Android objects cross the JNI boundary. Android passes bytes in; typed `FujiBleAction` variants come back. This is consistent with the Rust-owns-protocol / Android-owns-transport invariant from ADR 0001 and ADR 0003.

Fujifilm-specific characteristic UUIDs, opcode values, and payload layouts: mark as "to be confirmed against real device / clean-room interop notes" — they are vendor-specific and not covered by the ISO 15740 PTP standard. Reference open-source implementations (e.g. libgphoto2 Fuji driver, public community write-ups) for structural context, but never copy proprietary code or binary payloads.

## Pitfalls checklist

Verify each item during code review of any diff touching `:camera:bluetooth`:

- [ ] `gatt.close()` called after every disconnect and after every 133 error; handle nulled.
- [ ] No GATT handle is reused after `close()`.
- [ ] Every GATT operation (read, write, writeDescriptor) goes through the single-in-flight queue.
- [ ] `setCharacteristicNotification` is always followed by a queued CCCD `writeDescriptor`; waiting for `onDescriptorWrite(GATT_SUCCESS)` before trusting `onCharacteristicChanged`.
- [ ] `discoverServices()` called only from inside `onConnectionStateChange` when `newState == STATE_CONNECTED` and `status == GATT_SUCCESS`.
- [ ] Both API 31–32 and API 33+ overloads of `onCharacteristicRead` and `onCharacteristicChanged` are implemented; `@Suppress("OVERRIDE_DEPRECATION")` on the old overloads with an explanatory comment.
- [ ] MTU negotiation follows service discovery; not interleaved with discovery.
- [ ] Scan is stopped before `connectGatt`.
- [ ] Scan does not start/stop more than 5 times in 30 seconds (`SCANNING_TOO_FREQUENTLY` guard).
- [ ] Operation queue cancels all pending continuations on disconnect with a typed error.
- [ ] `notifications()` `Flow` is cancelled/closed when the connection closes.
- [ ] `autoConnect=true` is NOT used as a 133 workaround.
- [ ] Permission denial surfaces as `BleConnectionState.Failed` (not a crash); use a typed `FujiBleError` variant when the sealed interface is introduced.
- [ ] Camera Wi-Fi passphrase, pairing keys, full MAC addresses, and raw characteristic payloads are never logged.
- [ ] `BluetoothLeScanner`, `BluetoothGatt`, and related types do not appear outside `:camera:bluetooth`.

## Testing

**Unit tests (`:camera:bluetooth`):** fake `FujiBleClient` via interface; test `BleConnectionState` transitions, permission denial flows, scan timeout, operation timeout, disconnect cancellation, notification flow cancellation. No real `BluetoothGatt` needed.

**Rust unit tests (`fuji-ble-protocol`):** fixture-based advertisement parser tests, characteristic payload parser tests, malformed payload tests (length underflow, zero-length, max-length), command payload generation tests. Property-based testing (`proptest`) is recommended for the parsers.

**Integration / hardware tests:** scan starts and stops cleanly; camera appears in results; GATT connect / disconnect cycle; service discovery; MTU negotiation; repeated connect/disconnect; status 133 recovery; scan throttle guard; Android version matrix (API 31, 33, 35+); Pixel device first, then Samsung and Xiaomi.

## Related skills

- `android-permissions` — runtime permission request flow owned by `:core:permissions`.
- `rust-android-jni` — JNI boundary discipline for `FujiBleProtocolBridge` (`fuji-ffi` crate).
- `rust-unsafe` — `catch_unwind`, JString, untrusted-buffer parsing patterns.
- `diagnostics-system` — typed error states for `PermissionDenied.BluetoothScan`, `BleProtocol.*` errors.
- `rust-discipline` — panic policy and error model for `fuji-ble-protocol`.

## References

- Android Developers — Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android Developers — Connect to a GATT server: https://developer.android.com/guide/topics/connectivity/bluetooth/connect-gatt-server
- Android Developers — Transfer BLE data: https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data
- Android Developers — Find BLE devices: https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices
- Android Developers — BluetoothGatt API: https://developer.android.com/reference/android/bluetooth/BluetoothGatt
- Android Developers — BluetoothGattCallback API: https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback
- Android Developers — ScanSettings API: https://developer.android.com/reference/android/bluetooth/le/ScanSettings
- Android Developers — ScanFilter API: https://developer.android.com/reference/android/bluetooth/le/ScanFilter
- Android Developers — Behavior changes: Android 12: https://developer.android.com/about/versions/12/behavior-changes-12
- Christopher Renshaw — Surviving GATT_ERROR 133 on Android BLE: https://crickshaw.dev/notes/android-ble-gatt-error-133/
- Frameport ADR 0003 — BLE Client Abstraction: docs/adr/0003-ble-client-abstraction.md
- Frameport BLE Protocol doc: docs/protocol/bluetooth-le.md
- Frameport Bluetooth Architecture doc: docs/android/bluetooth-architecture.md
