# Frameport

**Frameport** is an unofficial Android-only companion app for Fujifilm X/GFX cameras.

The project is designed as a production-grade, privacy-first alternative to the official camera companion workflow: fast image import, transparent connection diagnostics, remote camera control, and eventually live view — without requiring a cloud account or a vendor-controlled timeline.

Frameport is built from scratch. Open-source projects and reverse-engineering research are used only as interoperability references for understanding camera connection behavior, protocol structure, and platform edge cases.

## Status

Frameport is in early development.

Current target:

* Android-only
* Fujifilm X-T5 first
* Wi-Fi PTP-IP image import first
* BLE-assisted Wi-Fi handoff later
* USB tethering later
* No firmware update in v1
* No cloud account in v1
* No proprietary Fujifilm binaries, assets, or SDK redistribution

## Project Goals

Frameport aims to provide:

* Reliable camera connection flow with explicit diagnostics
* Image browsing and import from camera to Android MediaStore
* JPEG / HEIF / RAF-oriented transfer pipeline where supported
* Remote shutter release
* Future live-view support
* Local-first camera history and import catalog
* Privacy-first design with no mandatory cloud backend
* Clean Android/Rust architecture suitable for long-term maintenance

## Non-Goals

Frameport is not:

* An official Fujifilm product
* A clone of FUJIFILM XApp
* A firmware update tool
* A cloud timeline service
* A Fujifilm account client
* A wrapper around proprietary Fujifilm app binaries
* A redistribution of proprietary SDKs, APKs, native libraries, assets, keys, or endpoints

## Architecture

Frameport is split into two major layers:

```text
Android app layer
  Kotlin / Compose / Navigation 3 / MVVM / Hilt / Coroutines / Flow

Native camera SDK layer
  Rust / JNI / PTP / PTP-IP / Fuji protocol state machines
```

The Android layer owns Android-specific responsibilities:

* Runtime permissions
* Bluetooth LE scanning and GATT lifecycle
* Wi-Fi network requests and socket binding
* USB permission flow
* Foreground services
* MediaStore writes
* Notifications
* UI state and navigation
* App lifecycle

The Rust layer owns camera protocol responsibilities:

* PTP packet encoding and decoding
* Fujifilm PTP-IP session state
* Media object enumeration
* Thumbnail and object transfer
* Transfer progress and cancellation
* Remote capture commands
* Live-view frame receive/parsing
* Typed protocol errors
* Protocol diagnostics
* BLE payload parsing and command generation

## Planned Module Structure

```text
.
├── app/
│   └── Android application entry point
│
├── core/
│   ├── common/
│   ├── model/
│   ├── designsystem/
│   ├── permissions/
│   ├── logging/
│   ├── storage/
│   └── testing/
│
├── camera/
│   ├── api/
│   ├── domain/
│   ├── data/
│   ├── bluetooth/
│   ├── wifi/
│   ├── usb/
│   ├── media/
│   └── diagnostics/
│
├── feature/
│   ├── onboarding/
│   ├── connection/
│   ├── gallery/
│   ├── import/
│   ├── remote/
│   ├── liveview/
│   ├── settings/
│   └── diagnostics/
│
├── native/
│   └── fuji-rust-android/
│
└── rust/
    └── fuji-rs/
```

## Rust SDK Structure

The native SDK is expected to be a Rust workspace:

```text
rust/fuji-rs/
├── crates/
│   ├── fuji-core/
│   ├── fuji-ptp/
│   ├── fuji-ptpip/
│   ├── fuji-transfer/
│   ├── fuji-liveview/
│   ├── fuji-ble-protocol/
│   ├── fuji-usb-ptp/
│   ├── fuji-diagnostics/
│   ├── fuji-sim/
│   ├── fuji-ffi/
│   └── fuji-cli/
```

### `fuji-core`

Shared domain types:

* Camera identity
* Session state
* Transport kind
* Capabilities
* Media object metadata
* Camera properties
* Error types
* Diagnostic events

### `fuji-ptp`

Core PTP primitives:

* Packet framing
* Operation codes
* Response codes
* Event codes
* Transaction IDs
* Session IDs
* Object handles
* Object info parsing
* Fujifilm-specific extensions

### `fuji-ptpip`

Wi-Fi camera transport:

* Command channel
* Event channel
* Live-view / through-picture channel
* Session handshake
* Open / close lifecycle
* Retry and timeout behavior
* Android-provided socket file descriptor handoff

### `fuji-transfer`

Image transfer pipeline:

* Object enumeration
* Thumbnail fetch
* Full object download
* Partial object reads
* Transfer progress
* Cancellation
* Duplicate detection support
* Output-to-file-descriptor streaming

### `fuji-liveview`

Future live-view implementation:

* Dedicated frame receiver
* Reusable buffers
* Frame dropping policy
* Compressed JPEG frame delivery
* Backpressure handling
* Low-allocation hot path

### `fuji-ble-protocol`

Fujifilm BLE protocol logic only:

* Advertisement parsing
* GATT payload parsing
* Pairing state
* Wi-Fi credential extraction
* Remote shutter command payloads
* Camera state notifications

Android remains responsible for actual BLE scanning, connection, service discovery, characteristic reads/writes, notifications, and permission handling.

### `fuji-usb-ptp`

Future USB mode:

* USB descriptor parsing
* PTP-over-USB transaction flow
* Bulk endpoint read/write
* Android USB file descriptor handoff

### `fuji-diagnostics`

Structured debugging and support:

* Connection timeline
* Protocol trace
* Operation timings
* Last camera response codes
* BLE operation state
* Wi-Fi socket/channel state
* Exportable diagnostic bundle

### `fuji-sim`

Development and test tooling:

* Fake PTP-IP camera server
* Fake media catalog
* Transfer simulation
* Timeout/failure injection
* Golden packet replay

### `fuji-ffi`

Android native boundary:

* JNI entry points
* Native session lifecycle
* File descriptor ownership
* Transfer callbacks
* Live-view callbacks
* Typed error mapping

## Android Stack

Frameport uses a modern Android stack:

* Kotlin
* Jetpack Compose
* Material 3
* Navigation 3
* MVVM with unidirectional state flow
* Hilt for dependency injection
* Kotlin Coroutines
* Flow / StateFlow / SharedFlow
* Room for structured local persistence
* Proto DataStore for settings
* MediaStore for imported media
* ForegroundService for active camera sessions
* WorkManager only for deferred maintenance tasks

## Navigation

Navigation is key-based and typed.

Example destination model:

```kotlin
@Serializable
sealed interface AppRoute {
    @Serializable data object Onboarding : AppRoute
    @Serializable data object CameraScan : AppRoute
    @Serializable data class CameraConnect(val cameraId: String) : AppRoute
    @Serializable data object Gallery : AppRoute
    @Serializable data class MediaDetail(val objectId: Long) : AppRoute
    @Serializable data object Remote : AppRoute
    @Serializable data object LiveView : AppRoute
    @Serializable data object Diagnostics : AppRoute
    @Serializable data object Settings : AppRoute
}
```

Navigation mutations should happen at the navigation host layer, not inside repositories or native SDK wrappers.

## State Management

UI state follows a unidirectional model:

```text
Composable
  -> user action
  -> ViewModel
  -> use case / interactor
  -> repository
  -> platform adapter / native SDK
  -> StateFlow<UiState>
```

Rules:

* `StateFlow` for screen state
* `SharedFlow` for one-shot UI events
* `Channel` only for internal actors/queues
* No direct JNI calls from ViewModels
* No direct BluetoothGatt access from UI or ViewModel
* No protocol logic in Composables

## Bluetooth Strategy

Bluetooth LE is Android-owned and protocol-assisted by Rust.

Android/Kotlin owns:

* BLE scan
* GATT connection
* Service discovery
* MTU negotiation
* Characteristic reads/writes
* Notification subscription
* Runtime permissions
* Foreground service lifecycle
* Reconnect behavior

Rust owns:

* Fujifilm-specific BLE payload parsing
* Command payload generation
* Pairing state interpretation
* Wi-Fi handoff data interpretation
* Remote shutter payload construction

Initial implementation should use an interface-first BLE layer:

```kotlin
interface FujiBleClient {
    val connectionState: StateFlow<BleConnectionState>

    fun scan(): Flow<BleCameraAdvertisement>

    suspend fun connect(camera: BleCameraRef): Result<Unit>
    suspend fun read(characteristic: CharacteristicId): Result<ByteArray>
    suspend fun write(characteristic: CharacteristicId, payload: ByteArray): Result<Unit>
    fun notifications(characteristic: CharacteristicId): Flow<ByteArray>
    suspend fun disconnect()
}
```

A concrete implementation may use Nordic Android BLE Library or a thin wrapper over Android `BluetoothGatt`, but the rest of the app must depend only on `FujiBleClient`.

## Wi-Fi Strategy

Wi-Fi camera traffic must be routed through the Android `Network` selected for the camera.

Production design:

```text
Android requests camera Wi-Fi network
Android binds socket to that Network
Android connects socket to camera endpoint
Android passes owned file descriptor to Rust
Rust speaks PTP-IP over the provided descriptor
```

Rust should not blindly call `TcpStream::connect("192.168.0.1:55740")` unless the process has already been explicitly bound to the camera network for a controlled MVP/debug flow.

## File Import Strategy

Imported media should be written through Android MediaStore.

Expected flow:

```text
Android creates MediaStore item with IS_PENDING = 1
Android opens ParcelFileDescriptor
Android passes owned output fd to Rust
Rust streams camera object into fd
Android finalizes MediaStore item with IS_PENDING = 0
Android records import metadata in Room
```

The Rust SDK should never write directly to arbitrary shared storage paths.

## Privacy and Security

Frameport is local-first by default.

Rules:

* No mandatory account
* No cloud backend in v1
* No analytics by default
* No hidden telemetry
* No hardcoded third-party service keys
* No copied vendor tokens, endpoints, or credentials
* No proprietary assets from official apps
* No app-wide cleartext traffic
* Encrypt secrets if stored locally
* Keep diagnostic exports local unless the user explicitly shares them

## Reverse-Engineering Boundary

Frameport may use interoperability research to understand behavior and protocol compatibility.

Allowed:

* Studying documented reverse-engineering notes
* Studying open-source implementations under their licenses
* Capturing traffic from owned devices where legally permitted
* Writing clean-room protocol implementations
* Creating original code, UI, documentation, tests, and assets

Not allowed:

* Copying proprietary Fujifilm source code
* Redistributing APKs or extracted native libraries
* Copying official app assets, icons, strings, or branding
* Reusing hardcoded vendor API keys or tokens
* Using proprietary SDK binaries without explicit license compliance
* Circumventing access controls unrelated to interoperability

## Roadmap

### Phase 0 — Repository Bootstrap

* Android project scaffold
* Rust workspace scaffold
* Gradle + cargo-ndk integration
* CI skeleton
* Architecture documentation
* Agent instructions

### Phase 1 — Wi-Fi PTP-IP Spike

* Manual camera Wi-Fi connection flow
* Android network request
* Socket binding / fd handoff
* Rust session open
* Basic camera identity read
* Diagnostic screen

### Phase 2 — Media Browser

* Object handle enumeration
* Object info parsing
* Thumbnail loading
* Gallery grid
* Basic compatibility matrix

### Phase 3 — Import

* Single image import
* Batch import
* Progress/cancel/retry
* MediaStore writer
* Room import catalog
* Duplicate detection

### Phase 4 — BLE Handoff

* BLE scan
* GATT connection
* Camera identity read
* Pairing flow
* Wi-Fi SSID/passphrase handoff
* BLE-assisted connection UX

### Phase 5 — Remote Control

* BLE remote shutter
* Wi-Fi remote shutter
* Basic camera properties
* Remote capture diagnostics

### Phase 6 — Live View

* Auxiliary channel validation
* Frame receiver
* Reusable frame buffers
* Compose/Surface preview
* Latency and frame-drop diagnostics

### Phase 7 — USB Mode

* Android USB permission flow
* USB fd handoff
* PTP-over-USB session
* Wired import mode

## Development Setup

Required tools:

* Android Studio latest stable
* JDK 17 or newer
* Rust stable toolchain
* Android NDK
* `cargo-ndk`
* A physical Android device
* A supported Fujifilm camera for integration testing

Install Rust Android tooling:

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android
```

Expected build flow after project scaffold:

```bash
./gradlew assembleDebug
./gradlew test
cargo test --workspace
```

## Testing Strategy

Android:

* Unit tests for ViewModels and use cases
* Flow tests with Turbine
* Hilt tests for graph wiring
* Compose UI tests
* Instrumentation tests for MediaStore and service lifecycle
* Fake BLE and Wi-Fi adapters

Rust:

* Unit tests for packet encode/decode
* Golden packet fixtures
* Property-based tests for parsers
* Fake PTP-IP camera server
* Transfer failure simulation
* Fuzzing for binary parsers

Integration:

* Physical Fujifilm X-T5 test matrix
* Android vendor matrix: Pixel, Samsung, Xiaomi
* Android version matrix
* BLE reconnect tests
* Local-only Wi-Fi routing tests
* Large RAF transfer tests

## Compatibility Policy

Initial public compatibility should be conservative:

```text
Tested:
  Fujifilm X-T5

Experimental:
  Other Fujifilm X/GFX cameras

Unsupported in v1:
  Firmware update
  Cloud timeline
  Vendor account sync
  Camera settings backup/restore
```

Do not claim universal Fujifilm compatibility until tested with real hardware and firmware versions.

## Legal Notice

Frameport is an independent, unofficial project and is not affiliated with, endorsed by, or sponsored by Fujifilm.

Fujifilm, FUJIFILM XApp, X Series, GFX, and related names are trademarks of their respective owners. They are used only to describe interoperability targets.
