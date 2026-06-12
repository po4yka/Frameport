# CLAUDE.md

This file defines working rules for AI coding agents (Claude Code and others) and human contributors working on Frameport. `AGENTS.md` is a symlink to this file — edit `CLAUDE.md`, never the symlink.

Frameport is an Android-only Fujifilm camera companion app built from scratch with a Kotlin Android layer and a Rust native camera SDK. The project uses open-source implementations and reverse-engineering research as interoperability references, but all production code, UI, assets, and documentation in this repository must be original.

## Core Mission

Build a production-grade, privacy-first Android camera companion app.

Primary v1 target:

```text
Android-only
Fujifilm X-T5 first
Wi-Fi PTP-IP image import first
BLE-assisted handoff later
USB mode later
No firmware update in v1
No cloud account in v1
```

The application should be reliable, diagnosable, maintainable, and architecturally clean. Frameport should be boring, explicit, debuggable, and trustworthy.

## Current State (scaffold)

The repository is an early, compiling scaffold. The architecture, module graph, and Rust workspace exist; real camera communication does not. The current app shell compiles with **no real camera communication, no cloud dependencies, no proprietary Fujifilm assets, and no protocol payloads copied from reverse-engineered apps**.

What exists today:

* Android app shell: `FrameportApp` (Material 3 scaffold + top app bar), `FrameportNavHost` (Navigation 3 back stack), typed `NavKey` destinations, Hilt application, dispatcher and camera-binding DI modules.
* Feature modules are placeholders that render planned / not-implemented states without touching Bluetooth, Wi-Fi, USB, MediaStore, or the native SDK.
* Rust workspace `rust/fuji-rs/` with 11 crates (mostly stubs, ~560 LOC total) and a JNI bridge crate `fuji-ffi`.
* `:native:fuji-rust-android` Android library wrapping the JNI bridge (`NativeFujiJni`, `NativeFujiSdk`) with a unit test.

Most `:core:*`, `:camera:*`, and `:feature:*` modules contain a single placeholder source file or none yet. Implement behind the contracts described below; do not break the boundaries to ship a feature faster.

## Tooling & Versions

Pin versions from `gradle/libs.versions.toml` and `rust/fuji-rs/Cargo.toml`; quote them when delegating, do not guess.

```text
AGP 9.2.1            Kotlin 2.3.10        KSP 2.3.4
compileSdk 37        targetSdk 37         minSdk 31
Compose BOM 2026.05.01                    Navigation3 1.1.2
Hilt 2.59.2          Room 2.8.4           DataStore 1.2.0
Coroutines 1.10.2    Coil 2.7.0           Timber 5.0.1
JDK target 17        applicationId/namespace: dev.po4yka.frameport
Rust edition 2024    resolver 3           jni 0.21   thiserror 2
```

## Build & Verify

Gradle (run from repo root via the wrapper):

```bash
./gradlew assembleDebug                 # build the app
./gradlew test                          # JVM unit tests
./gradlew lint                          # Android lint
./gradlew connectedAndroidTest          # instrumented tests (device/emulator)
```

Rust (workspace at `rust/fuji-rs/`):

```bash
cargo test --workspace
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
```

The Rust build is intentionally **not** wired into `assemble`. Use the explicit Gradle tasks in `:native:fuji-rust-android` (which shell out to cargo / `cargo-ndk`):

```bash
./gradlew :native:fuji-rust-android:cargoTestRust          # cargo test --workspace
./gradlew :native:fuji-rust-android:cargoBuildRustArm64    # cargo-ndk build fuji-ffi -> jniLibs (arm64-v8a)
./gradlew :native:fuji-rust-android:cargoBuildRustX86_64   # same for x86_64 (emulator)
```

Before opening a PR, run the Gradle and Cargo verification commands above. If a command is not yet runnable because the repo is still being scaffolded, say so explicitly in the PR description rather than skipping silently.

## Repository Layout

```text
app/                          Android application shell (Compose, Nav3, Hilt)
core/                         common, model, designsystem, permissions, logging, storage, testing
camera/                       api, domain, data, bluetooth, wifi, usb, media, diagnostics
feature/                      onboarding, connection, gallery, import, remote, liveview, settings, diagnostics
native/fuji-rust-android/     Android library wrapping the Rust JNI bridge
rust/fuji-rs/                 Cargo workspace (crates/fuji-*) — the native camera SDK
docs/                         architecture, adr, android, protocol, product, rust, security
```

Android module graph (declared in `settings.gradle.kts`, type-safe project accessors enabled):

```text
:app

:core:common  :core:model  :core:designsystem  :core:permissions
:core:logging :core:storage :core:testing

:native:fuji-rust-android

:camera:api  :camera:domain  :camera:data  :camera:bluetooth
:camera:wifi :camera:usb     :camera:media :camera:diagnostics

:feature:onboarding :feature:connection :feature:gallery  :feature:import
:feature:remote     :feature:liveview   :feature:settings :feature:diagnostics
```

Rust workspace (`rust/fuji-rs/crates/`):

```text
fuji-core/   fuji-ptp/        fuji-ptpip/   fuji-transfer/  fuji-liveview/
fuji-ble-protocol/  fuji-usb-ptp/  fuji-diagnostics/  fuji-sim/  fuji-ffi/  fuji-cli/
```

## Documentation Map

Read the relevant doc before changing a subsystem. Major decisions are recorded as ADRs; prefer a new ADR for major choices.

```text
docs/architecture/overview.md          Layering and current scaffold intent
docs/architecture/android-modules.md   Module responsibilities and dependencies
docs/adr/0001-android-rust-boundary.md
docs/adr/0002-wifi-socket-fd-handoff.md
docs/adr/0003-ble-client-abstraction.md
docs/adr/0004-media-import-pipeline.md
docs/adr/0005-no-cloud-v1.md
docs/adr/0006-no-firmware-update-v1.md
docs/adr/0007-module-boundaries.md
docs/android/{navigation,bluetooth-architecture,wifi-network-routing}.md
docs/protocol/{wifi-ptp-ip,usb-ptp,bluetooth-le,liveview,media-transfer,
               error-model,compatibility-matrix,geoposition-sharing}.md
docs/rust/{sdk-design,ffi-boundary,fd-ownership,error-handling,building}.md
docs/product/feature-scope.md
README.md  CONTRIBUTING.md  SECURITY.md  NOTICE  LICENSE (Apache-2.0)
```

## Hard Boundaries

Agents must not:

* Copy proprietary Fujifilm code
* Copy proprietary APK assets, icons, strings, layouts, or branding
* Redistribute extracted APKs, native libraries, SDK binaries, keys, tokens, or credentials
* Use hardcoded vendor API keys or endpoints from reverse-engineered apps
* Implement Fujifilm cloud account flows in v1
* Implement firmware update in v1
* Claim official Fujifilm affiliation
* Claim broad camera compatibility without test evidence
* Add hidden analytics or telemetry
* Add app-wide cleartext traffic
* Put protocol logic directly into UI code
* Let ViewModels call JNI directly
* Let Composables perform I/O, Bluetooth, Wi-Fi, USB, or storage operations

Agents may:

* Study repository documentation
* Use clean-room interoperability notes
* Use open-source projects according to their licenses
* Create original protocol implementations
* Create original Android/Rust architecture
* Create tests, fixtures, fake servers, and diagnostics
* Document assumptions and unknowns clearly

## Architecture Rules

Frameport has two main layers:

```text
Android layer:
  Kotlin, Compose, Navigation 3, MVVM, Hilt, Coroutines, Flow

Native SDK layer:
  Rust, JNI, PTP, PTP-IP, Fuji protocol state machines
```

Android owns:

* Runtime permissions
* BLE scanning and GATT lifecycle
* Wi-Fi network requests
* Socket binding to Android `Network`
* USB permission flow
* Foreground services
* MediaStore
* Notifications
* UI state
* Navigation
* App lifecycle

Rust owns:

* PTP packet encoding/decoding
* Fujifilm PTP-IP session state
* Media object enumeration
* Transfer protocol
* Remote capture commands
* Live-view parsing
* Protocol-level diagnostics
* Typed native errors
* BLE payload parsing and command generation

## Android Coding Rules

Use:

* Kotlin
* Compose
* Material 3
* Navigation 3
* MVVM
* Hilt
* Coroutines
* Flow / StateFlow / SharedFlow
* Room
* Proto DataStore
* MediaStore
* ForegroundService for active camera operations
* WorkManager only for deferred reliable maintenance work

Avoid:

* LiveData in new code
* GlobalScope
* AsyncTask
* Direct JNI calls from ViewModels
* BluetoothGatt access outside the Bluetooth module
* Socket creation inside UI or ViewModel layers
* Storage writes outside MediaStore/import abstractions
* Business logic in Composables

State rules:

```text
StateFlow:
  long-lived UI state

SharedFlow:
  one-shot UI events

Channel:
  internal actor queues only

suspend functions:
  request/response operations
```

## Compose Rules

Composable functions should be pure UI.

Composables may:

* Render state
* Emit user actions
* Collect lifecycle-aware state from ViewModels
* Display previews

Composables must not:

* Open sockets
* Access BluetoothGatt
* Access USB APIs
* Call JNI
* Write files
* Query Room directly
* Hold long-lived protocol state

## Navigation Rules

Navigation should be typed and key-based. Navigation keys are typed `NavKey` objects in the app layer; feature screens receive state and callbacks, not navigation controllers.

Do not pass large objects through navigation routes. Pass stable IDs only.

Navigation mutations should happen in the navigation host layer. Repositories and native SDK wrappers must never navigate.

## Dependency Injection Rules

Use Hilt for Android DI.

Required patterns:

* Inject interfaces, not concrete platform classes, into domain/use-case layers
* Use qualifiers for dispatchers
* Keep JNI/native SDK behind an interface
* Keep BLE, Wi-Fi, USB, and MediaStore behind separate adapters
* Do not expose Android framework types outside platform modules unless necessary

Recommended qualifiers:

```kotlin
@Qualifier annotation class IoDispatcher
@Qualifier annotation class DefaultDispatcher
@Qualifier annotation class MainDispatcher
@Qualifier annotation class ApplicationScope
```

## Rust Coding Rules

Use Rust for protocol correctness, state machines, binary parsing, transfer control, and native diagnostics.

Rust crates should expose domain-level APIs, not packet-level APIs, to Android.

Good FFI boundary:

```text
openWifiSession(...)
listMedia(...)
getThumbnail(...)
downloadObjectToFd(...)
enterRemoteMode(...)
capture(...)
startLiveView(...)
stopLiveView(...)
closeSession(...)
```

Bad FFI boundary:

```text
sendPacket(...)
readPacket(...)
parseBytes(...)
nextChunk(...)
```

Rust should use typed errors and map them into Android-facing error categories.

Use:

* `thiserror` for error types
* `tracing` for structured diagnostics
* `bytes` for binary buffers where useful
* `proptest` for parser invariants
* `cargo-fuzz` for binary parser fuzzing where useful
* `jni` crate for manual JNI
* `cargo-ndk` for Android builds

Avoid:

* Panics across FFI
* Unbounded queues
* Unstructured integer error codes
* Per-frame allocation in live-view hot paths
* Hidden global mutable session state
* Blocking UI thread through JNI
* File descriptor ownership ambiguity

Apply the global LLM-Rust review discipline: pin dependency versions from `Cargo.lock` when delegating, annotate cancel-safety on every async fn, require a `// SAFETY:` block for every `unsafe` block, and route any diff touching `unsafe`, `transmute`, a `Mutex`, manual `Send`/`Sync`, or a blanket public-trait impl through a separate reviewer pass before commit.

## JNI and FFI Rules

JNI must be coarse-grained.

Required:

* Document ownership for every file descriptor passed to Rust
* Duplicate file descriptors on the Android side when ownership is transferred
* Never allow Rust panics to cross JNI
* Convert all native errors into typed Kotlin errors
* Keep live-view and transfer hot paths allocation-aware
* Keep callbacks minimal and structured

Manual JNI is preferred for:

* File descriptor handoff
* Transfer-to-fd
* Transfer progress callbacks
* Live-view frame callbacks
* Native session lifecycle

UniFFI may be considered for:

* DTOs
* Enums
* Configuration types
* Non-hot control APIs

## Wi-Fi Rules

Camera Wi-Fi traffic must be explicitly routed.

Preferred production flow:

```text
Android requests camera Wi-Fi network
Android binds socket to that Android Network
Android connects socket to camera endpoint
Android passes owned file descriptor to Rust
Rust speaks PTP-IP over the provided descriptor
```

Do not assume `192.168.0.1` is reachable from Rust unless the socket or process has been explicitly bound to the camera network.

The Wi-Fi module should expose a high-level API such as:

```kotlin
interface CameraWifiConnector {
    val state: StateFlow<CameraWifiState>

    suspend fun requestCameraNetwork(credentials: CameraWifiCredentials): Result<CameraNetworkHandle>
    suspend fun openBoundSocket(handle: CameraNetworkHandle, endpoint: CameraEndpoint): Result<OwnedSocketHandle>
    suspend fun release(handle: CameraNetworkHandle)
}
```

## Bluetooth Rules

BLE is Android-owned.

The Bluetooth module owns:

* Scanning
* GATT connection
* Service discovery
* MTU negotiation
* Characteristic reads
* Characteristic writes
* Notification subscriptions
* GATT operation queue
* Timeouts
* Reconnect behavior
* Android permissions

Rust owns only Fujifilm-specific BLE payload interpretation and command payload generation.

Recommended app-facing BLE abstraction:

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

Rules:

* Only one GATT operation may be in flight at a time
* Every operation must have a timeout
* Disconnect must cancel pending operations
* Never call `BluetoothGatt` directly from multiple coroutines
* Do not keep a permanent foreground service unless the user explicitly started an active camera operation
* Do not request background location unless implementing an explicit geotagging feature

## USB Rules

USB is not part of the first MVP unless explicitly requested.

When implemented:

* Android owns `UsbManager` permission flow
* Android opens the USB device
* Android passes file descriptor and descriptors to Rust
* Rust implements PTP-over-USB or wraps a controlled transport layer
* USB mode must not require root

## Media Import Rules

Use MediaStore for imported media.

Expected import flow:

```text
Create MediaStore item with IS_PENDING = 1
Open output ParcelFileDescriptor
Pass owned fd to Rust
Rust streams object into fd
Close fd
Set IS_PENDING = 0
Record import metadata in Room
```

Do not write directly into arbitrary shared storage paths.

## Persistence Rules

Use Room for:

* Imported media catalog
* Camera profiles
* Transfer history
* Diagnostics sessions
* Compatibility matrix
* Local timeline later

Use Proto DataStore for:

* App settings
* Import preferences
* Privacy settings
* Connection preferences

Use encrypted storage for:

* Camera Wi-Fi credentials if cached
* Pairing secrets if needed
* Any future account tokens if account support is ever added

## Privacy Rules

Frameport is local-first.

Default behavior:

* No account
* No cloud
* No analytics
* No telemetry
* No location tracking
* No background sync
* No hidden network calls

Any future telemetry must be explicit opt-in.

Diagnostic bundles must be local-first and must redact:

* Wi-Fi passphrases
* Tokens
* Precise location
* Full serial numbers unless user opts in
* File names unless user opts in

## Firmware Update Rule

Do not implement firmware update in v1.

Agents may add:

* Firmware version display
* Compatibility warnings
* Read-only firmware metadata

Agents must not add:

* Firmware binary download
* Firmware transfer to camera
* Firmware flashing workflow
* Firmware CDN integrations

## Testing Requirements

For every meaningful change, add or update tests where practical.

Android tests:

* ViewModel tests
* Use-case tests
* Flow tests
* Hilt graph tests
* Compose UI tests for stable screens
* Fake BLE client tests
* Fake Wi-Fi connector tests
* MediaStore instrumentation tests when needed

Rust tests:

* Packet encode/decode tests
* Parser tests
* Golden fixture tests
* Fake camera server tests
* Transfer failure tests
* Property-based tests for binary parsers
* Fuzz targets for unsafe or complex binary parsing

Integration tests should eventually cover:

* Physical Fujifilm X-T5
* Pixel device
* Samsung device
* Xiaomi device
* Android version matrix
* Local-only Wi-Fi routing
* BLE reconnect
* Large RAF transfer

## Diagnostics Requirements

Connection failures must be explainable.

Prefer typed states such as:

```text
PermissionDenied.BluetoothScan
PermissionDenied.BluetoothConnect
PermissionDenied.NearbyWifiDevices
Wifi.UserRejected
Wifi.NetworkUnavailable
Wifi.SocketBindFailed
Wifi.CameraEndpointTimeout
Bluetooth.GattConnectionFailed
Bluetooth.ServiceNotFound
Bluetooth.CharacteristicTimeout
Protocol.HandshakeRejected
Protocol.CameraBusy
Protocol.UnsupportedFunctionMode
Transfer.ObjectNotFound
Transfer.OutputFdWriteFailed
```

Avoid generic errors such as:

```text
Connection failed
Unknown error
SDK error
Something went wrong
```

## Documentation Rules

Update documentation when changing:

* Architecture
* Module boundaries
* Native APIs
* JNI ownership
* BLE behavior
* Wi-Fi behavior
* USB behavior
* Import pipeline
* Permissions
* Privacy behavior
* Compatibility matrix

Prefer ADRs for major choices; add the next numbered file under `docs/adr/`.

## Commit and PR Rules

Use Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`, `test:`), imperative mood, subject under 72 characters. Commit or push only when the user asks; branch first if on `main`.

Before opening a PR, verify:

```bash
./gradlew test
./gradlew lint
cargo test --workspace
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
```

If some commands are not available yet because the repository is still being scaffolded, mention that explicitly in the PR description.

PRs should include:

* What changed
* Why it changed
* Architecture impact
* Testing performed
* Known limitations
* Screenshots for UI changes
* Diagnostics/log examples for connection changes

## Code Review Checklist

Reviewers and agents should check:

* Does this preserve the Android/Rust boundary?
* Does this avoid proprietary code/assets?
* Is protocol logic outside UI?
* Is BLE access isolated?
* Is Wi-Fi routing explicit?
* Is fd ownership documented?
* Are errors typed?
* Is privacy preserved?
* Are tests updated?
* Are docs updated?
* Is compatibility claimed conservatively?

## Product Direction

Frameport should start narrow and reliable.

Recommended v1:

```text
Manual Wi-Fi connection
PTP-IP session open
Camera identity read
Media object list
Thumbnail loading
Single image import
Batch import
Transfer progress
Connection diagnostics
Local settings
No cloud
No firmware update
```

Recommended v2:

```text
BLE-assisted Wi-Fi handoff
Remote shutter
Improved reconnect
Import presets
Compatibility matrix
```

Recommended v3:

```text
Live view
Remote camera properties
USB import
Local timeline
Camera profiles
```

## Agent Behavior

When uncertain:

* Prefer a small, reversible change
* Preserve architecture boundaries
* Add TODOs only with precise context
* Document assumptions
* Avoid broad rewrites
* Do not invent camera compatibility
* Do not silently introduce cloud/network behavior
* Do not add dependencies without clear justification

When implementing protocol logic:

* Keep packet handling in Rust
* Add fixtures
* Add typed errors
* Add diagnostics
* Avoid global mutable state
* Prefer deterministic tests

When implementing Android platform logic:

* Keep platform APIs behind interfaces
* Respect lifecycle
* Use foreground services only for active user-visible operations
* Keep permissions minimal
* Handle denial paths explicitly
* Expose state through Flow
