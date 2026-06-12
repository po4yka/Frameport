# ADR 0003: Bluetooth LE Client Abstraction

## Status

Accepted

## Date

2026-06-11

## Context

Frameport needs Bluetooth LE for camera discovery and future BLE-assisted Wi-Fi handoff. BLE is expected to support discovering nearby supported cameras, connecting to the camera GATT server, discovering Fujifilm-specific services, reading camera identity metadata, performing or supporting pairing state, reading Wi-Fi SSID and passphrase where supported, triggering BLE remote shutter later, receiving camera state notifications later, and assisting handoff into a Wi-Fi PTP-IP session.

BLE is not expected to carry full image transfer or live-view traffic. Those workflows belong to Wi-Fi PTP-IP or future USB PTP.

Android must own BLE operations because `BluetoothLeScanner` is Android-specific, `BluetoothGatt` lifecycle is Android-specific, permissions differ across Android versions, background behavior is tightly controlled by Android, foreground service policy is Android-specific, and GATT operations are callback-driven and lifecycle-sensitive.

Rust should still participate because Fujifilm-specific payloads should be parsed in the same protocol-oriented SDK that owns PTP/PTP-IP logic. Rust can provide typed parsing, command construction, and protocol state validation, and BLE payload parsing can be tested independently from Android GATT.

## Decision

Frameport will use an Android-owned BLE client abstraction.

Android/Kotlin owns:

* BLE scanning
* Scan filtering
* Device selection
* GATT connection
* Service discovery
* MTU negotiation if needed
* Characteristic reads
* Characteristic writes
* Notification subscription
* Notification delivery
* Operation queue
* Operation timeouts
* Reconnect policy
* Android runtime permissions
* Foreground service lifecycle
* User-visible connection state

Rust owns:

* Fujifilm-specific BLE advertisement parsing where useful
* Fujifilm-specific GATT payload parsing
* Command payload generation
* Pairing-state interpretation
* Wi-Fi handoff data interpretation
* BLE remote shutter payload construction
* Typed BLE protocol errors
* Protocol diagnostics that are independent of Android GATT internals

Rust must not directly use or wrap Android `BluetoothGatt`.

ViewModels and Composables must not directly access `BluetoothGatt`.

The app must depend on a `FujiBleClient` interface rather than a concrete BLE library.

The initial implementation may use a production-ready Android BLE helper library or a thin custom wrapper over Android BLE APIs, but the rest of the app must not depend on that implementation detail.

### Boundary Diagram

```text
Compose UI
  ↓
ViewModel
  ↓
Use case / interactor
  ↓
CameraRepository
  ↓
FujiBleClient interface
  ↓
Android BLE implementation
  ├── BluetoothLeScanner
  ├── BluetoothGatt
  ├── GATT operation queue
  ├── characteristic read/write
  └── notifications
        ↓ raw bytes
Rust BLE protocol layer
  ├── advertisement parser
  ├── payload parser
  ├── command builder
  ├── pairing/handoff state
  └── typed protocol diagnostics
```

Intended handoff flow:

```text
BLE scan
  ↓
camera selected
  ↓
GATT connect
  ↓
service discovery
  ↓
read camera identity
  ↓
pairing / registration state
  ↓
read Wi-Fi SSID/passphrase
  ↓
Android Wi-Fi connector
  ↓
network-bound socket fd handoff
  ↓
Rust PTP-IP session
```

## Consequences

Positive consequences:

* Android lifecycle and permissions remain in Android code
* Rust protocol code stays platform-independent
* BLE implementation can be swapped without changing domain/UI code
* BLE behavior is easier to fake in tests
* One GATT operation queue can be enforced centrally
* Reconnect and timeout policy can be made explicit
* BLE payload parsing can be unit-tested in Rust
* Wi-Fi handoff can be expressed as a clean transition from BLE state to Wi-Fi connector state

Negative consequences:

* BLE logic is split across Android and Rust
* The interface must carefully distinguish Android transport errors from Fujifilm payload/protocol errors
* Async coordination is non-trivial
* GATT callback behavior differs across Android vendors
* Physical hardware testing is mandatory
* Background BLE behavior requires strict product and permission decisions

## Alternatives Considered

### Alternative 1: Direct Android BLE usage from ViewModels

This alternative would have ViewModels directly use `BluetoothLeScanner` and `BluetoothGatt`.

Rejected because it leaks platform APIs into UI state management, makes testing difficult, encourages multiple concurrent GATT operations, makes lifecycle and cancellation fragile, and violates the architecture boundary.

### Alternative 2: Rust owns BLE fully

This alternative would have Rust own scanning, GATT connection, characteristic operations, and notifications through JNI/native calls.

Rejected because Android BLE APIs are Java/Kotlin framework APIs, runtime permissions and background behavior are Android-specific, `BluetoothGatt` callback lifecycle is not a good Rust-owned abstraction, JNI surface would become brittle and overly chatty, and foreground service behavior cannot be cleanly owned by Rust.

### Alternative 3: BLE payload parsing in Kotlin only

This alternative would have Kotlin own both Android GATT operations and Fujifilm-specific payload parsing.

Rejected because protocol logic would be fragmented between Kotlin and Rust, Wi-Fi/PTP-IP and BLE protocol diagnostics should share typed protocol concepts, Rust tests and fixtures are better suited for binary parsing, and future protocol changes would be harder to keep consistent.

### Alternative 4: Bind app to one BLE library directly everywhere

This alternative would use a concrete BLE library directly from repositories and ViewModels.

Rejected because library choice may change, the Android BLE ecosystem evolves, testing should not require real BLE library objects, and domain code should depend on project-defined interfaces.

## Proposed API Shape

The intended Android-facing BLE interface is:

```kotlin
interface FujiBleClient {
    val connectionState: StateFlow<BleConnectionState>

    fun scan(filter: FujiBleScanFilter = FujiBleScanFilter.Default): Flow<BleCameraAdvertisement>

    suspend fun connect(camera: BleCameraRef): Result<Unit>

    suspend fun disconnect()

    suspend fun read(characteristic: CharacteristicId): Result<ByteArray>

    suspend fun write(
        characteristic: CharacteristicId,
        payload: ByteArray,
        writeType: BleWriteType = BleWriteType.WithResponse,
    ): Result<Unit>

    fun notifications(characteristic: CharacteristicId): Flow<ByteArray>
}
```

Conceptual domain types:

```kotlin
data class BleCameraAdvertisement(
    val id: BleCameraId,
    val displayName: String?,
    val rssi: Int?,
    val modelHint: String?,
    val rawServiceUuids: List<String>,
)

data class BleCameraRef(
    val id: BleCameraId,
    val addressOrAssociationId: String,
    val displayName: String?,
)

sealed interface BleConnectionState {
    data object Idle : BleConnectionState
    data object Scanning : BleConnectionState
    data class Found(val cameras: List<BleCameraAdvertisement>) : BleConnectionState
    data class Connecting(val camera: BleCameraRef) : BleConnectionState
    data class DiscoveringServices(val camera: BleCameraRef) : BleConnectionState
    data class Ready(val camera: BleCameraRef) : BleConnectionState
    data class Disconnecting(val camera: BleCameraRef) : BleConnectionState
    data class Disconnected(val reason: BleDisconnectReason?) : BleConnectionState
    data class Error(val error: FujiBleError) : BleConnectionState
}
```

Conceptual Rust protocol bridge:

```kotlin
interface FujiBleProtocolBridge {
    fun parseAdvertisement(bytes: ByteArray): Result<FujiBleAdvertisementData?>

    fun onCharacteristicRead(
        characteristic: CharacteristicId,
        payload: ByteArray,
    ): Result<List<FujiBleAction>>

    fun onNotification(
        characteristic: CharacteristicId,
        payload: ByteArray,
    ): Result<List<FujiBleAction>>

    fun buildCommand(command: FujiBleCommand): Result<ByteArray>
}
```

Example actions:

```kotlin
sealed interface FujiBleAction {
    data class Read(val characteristic: CharacteristicId) : FujiBleAction
    data class Write(val characteristic: CharacteristicId, val payload: ByteArray) : FujiBleAction
    data class Subscribe(val characteristic: CharacteristicId) : FujiBleAction
    data class StartWifiHandoff(val credentials: CameraWifiCredentials) : FujiBleAction
    data class EmitCameraEvent(val event: CameraBleEvent) : FujiBleAction
}
```

These APIs are illustrative and may evolve, but the architecture boundary must remain.

## Implementation Rules

### Android BLE Rules

* BLE scan and GATT lifecycle must live in the Bluetooth module.
* Only the Bluetooth module may directly access `BluetoothLeScanner`, `BluetoothDevice`, `BluetoothGatt`, `BluetoothGattService`, or `BluetoothGattCharacteristic`.
* Only one GATT operation may be in flight at a time.
* Every GATT operation must have a timeout.
* Pending operations must be cancelled on disconnect.
* Disconnect must be idempotent.
* Reconnect attempts must be bounded and user-visible if they require foreground behavior.
* Service discovery must complete before characteristic access.
* Characteristic UUID lookup must return typed errors, not null crashes.
* Notification flows must stop when the connection closes.
* Scans must be bounded unless the user explicitly started an active scan screen.
* BLE implementation details must not leak into feature modules.

### Permission Rules

* Request only the minimum BLE permissions needed for the active feature.
* For Android 12+, use `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
* Use `neverForLocation` only if the app does not infer location from scan results.
* Do not request background location for BLE.
* Do not request location unless required by a specific feature or Android version policy.
* Permission denial must produce a typed, user-visible state.

### Foreground Service Rules

* Use foreground service only for active user-visible camera operations.
* Do not keep a permanent BLE service.
* Foreground behavior must be visible in notifications.
* Stopping a camera operation must stop unnecessary BLE work.
* Background reconnect must not be added without a separate product/security decision.

### Rust BLE Protocol Rules

* Rust must treat all BLE payloads as untrusted input.
* Rust must validate payload lengths.
* Rust must not panic on malformed BLE payloads.
* Rust must return typed errors.
* Rust must not store Android BLE objects.
* Rust must not store camera secrets in logs.
* Rust must expose deterministic parser behavior suitable for tests.

### Repository/Domain Rules

* Repositories depend on `FujiBleClient`, not a concrete BLE library.
* ViewModels depend on use cases/repositories, not `FujiBleClient` directly unless the module architecture explicitly permits it.
* UI state must use typed BLE states and errors.
* BLE handoff to Wi-Fi must go through a domain use case, not direct cross-module calls.

## Failure Modes

Permission failures:

```text
PermissionDenied.BluetoothScan
PermissionDenied.BluetoothConnect
PermissionDenied.LocationIfRequired
PermissionDenied.NotificationsIfForegroundServiceRequired
```

Scan failures:

```text
Bluetooth.ScanFailed
Bluetooth.ScanTimeout
Bluetooth.NoCameraFound
Bluetooth.AdapterDisabled
Bluetooth.UnsupportedDevice
```

Connection failures:

```text
Bluetooth.GattConnectionFailed
Bluetooth.GattDisconnected
Bluetooth.GattTimeout
Bluetooth.MtuRequestFailed
Bluetooth.ServiceDiscoveryFailed
Bluetooth.ServiceNotFound
Bluetooth.CharacteristicNotFound
```

Operation failures:

```text
Bluetooth.ReadTimeout
Bluetooth.WriteTimeout
Bluetooth.NotificationSubscribeFailed
Bluetooth.OperationCancelled
Bluetooth.OperationQueueClosed
Bluetooth.UnexpectedCallback
```

Protocol failures:

```text
BleProtocol.MalformedPayload
BleProtocol.UnsupportedCameraState
BleProtocol.PairingRejected
BleProtocol.MissingWifiCredentials
BleProtocol.UnsupportedCharacteristicPayload
```

Diagnostics must distinguish Android BLE transport failures from Fujifilm BLE payload/protocol failures.

## Testing Implications

Android unit tests should cover:

* Fake `FujiBleClient`
* ViewModel state transitions
* Permission denial flows
* Scan timeout behavior
* Operation timeout behavior
* Disconnect cancellation
* Notification flow cancellation

Android integration and manual tests should cover:

* Scan starts and stops cleanly
* Camera appears in scan results
* User denies Bluetooth permissions
* Bluetooth adapter disabled
* GATT connection failure
* Service discovery failure
* Repeated connect/disconnect
* App background/foreground transitions
* Foreground service notification behavior when active

Rust tests should cover:

* Advertisement parser fixtures
* Characteristic payload parser fixtures
* Malformed payload tests
* Command payload generation tests
* Pairing/handoff state machine tests
* Property-based parser tests where useful

Hardware tests should cover:

* Fujifilm X-T5 current firmware
* Pixel device
* Samsung device later
* Xiaomi device later
* Android version matrix
* BLE scan reliability
* GATT reconnect reliability
* Wi-Fi handoff credential read
* BLE remote shutter later

## Security and Privacy Implications

BLE scan data can reveal nearby devices. Scan results must not be used to infer location. Camera identifiers must be redacted in diagnostics by default. Full camera serial numbers must not be logged. Camera MAC addresses must be redacted by default.

Pairing secrets must never be logged or exported. Wi-Fi passphrases read over BLE must never be logged. Raw BLE advertisement payloads must not be logged in release builds. Background BLE behavior must be minimized and user-visible. Diagnostic exports must redact sensitive BLE and camera fields.

Allowed by default:

```text
app version
Android version
camera model
firmware version if known
BLE state
operation name
elapsed time
typed error code
retry count
```

Redacted or forbidden by default:

```text
camera Wi-Fi passphrase
pairing key
full serial number
full MAC address
raw advertisement payload
raw characteristic payload
precise location
private filenames
```

## Related Documents

```text
README.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
NOTICE
docs/adr/0001-android-rust-boundary.md
docs/adr/0002-wifi-socket-fd-handoff.md
docs/adr/0004-media-import-pipeline.md
docs/android/bluetooth-architecture.md
docs/android/permissions.md
docs/security/diagnostics-redaction.md
docs/protocol/bluetooth-le.md
docs/protocol/error-model.md
```
