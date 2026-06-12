# Contributing to Frameport

Frameport is an early-stage project, but contributions should be written as if the app is expected to become production software. This guide applies to human contributors and AI coding agents.

## Project Mission

Frameport is an unofficial Android-only Fujifilm camera companion app focused on reliable local camera workflows. The project prioritizes transparent connection diagnostics, Wi-Fi PTP-IP image import, and a maintainable Android/Rust architecture that can later support BLE-assisted Wi-Fi handoff, remote shutter, live view, and USB mode.

The initial target is deliberately narrow:

* Android-only
* Fujifilm X-T5 first
* Wi-Fi PTP-IP image import first
* BLE-assisted handoff later
* USB later
* No cloud account in v1
* No firmware update in v1
* No proprietary Fujifilm binary redistribution

Frameport is built from scratch with Kotlin, Jetpack Compose, Material 3, Navigation 3, MVVM/UDF, Hilt, Kotlin Coroutines and Flow, Room, Proto DataStore, Android MediaStore, ForegroundService for active camera sessions, and a Rust native SDK exposed through JNI and built for Android with `cargo-ndk`.

## Contribution Principles

Prefer small, reviewable changes that preserve the existing architecture boundaries. Add tests when practical, document assumptions, and avoid broad rewrites unless there is a clear design decision behind them.

Keep privacy-first behavior as the default. Do not add telemetry, cloud behavior, hidden network calls, background sync, background location, or persistent foreground behavior without an explicit product decision.

Do not claim camera compatibility without hardware evidence. Compatibility notes should say what was tested, on which body and firmware, and what workflow was exercised.

When uncertain, choose a small reversible change, preserve Android/Rust separation, and make unknowns explicit in code comments, docs, diagnostics, or PR notes.

## Legal and Reverse-Engineering Boundary

Contributors may use clean-room interoperability notes and open-source references under their licenses to understand camera behavior, protocol structure, packet formats, and Android platform edge cases. These references must inform original implementation work, not become copied source material.

Do not copy, commit, redistribute, or suggest using proprietary Fujifilm code, official app assets, strings, icons, layouts, APK content, extracted native libraries, SDK binaries, keys, tokens, cloud endpoints, firmware binaries, or vendor credentials.

Packet captures, logs, protocol notes, and compatibility notes must be sanitized before being committed. Remove secrets and reduce device identifiers to the minimum needed for debugging.

The repository must never contain:

* Camera Wi-Fi passwords
* Full camera serial numbers
* Private MAC addresses unless anonymized
* Access tokens
* Vendor API keys
* Firmware binaries
* Official app assets

## Architecture Overview

Frameport has two primary layers: an Android app layer and a Rust native camera SDK layer. The Android layer owns Android platform integration and app state. The Rust layer owns protocol correctness and Fujifilm camera state machines.

Android owns:

* Runtime permissions
* BLE scanning and GATT lifecycle
* Wi-Fi `Network` requests and socket binding
* USB permission flow
* ForegroundService lifecycle
* MediaStore writes
* Notifications
* UI state
* Navigation
* App lifecycle

Rust owns:

* PTP packet encoding and decoding
* Fujifilm PTP-IP session state
* Media object enumeration
* Thumbnail and object transfer
* Transfer progress and cancellation
* Remote capture commands
* Live-view parsing later
* Protocol-level diagnostics
* Typed protocol errors
* BLE payload parsing and command payload generation

The boundary is strict: ViewModels must not call JNI directly, Composables must not perform I/O, and Rust must not own Android `BluetoothGatt`, Android Wi-Fi APIs, USB permission flows, or MediaStore operations. Put protocol logic behind native/domain interfaces and put Android platform APIs behind platform adapters.

## Android Development Guidelines

Write new Android code in Kotlin using the style already present in the repository. Prefer explicit types at module boundaries, clear sealed state models for user-visible state, and small functions that keep platform details out of domain code.

New UI should use Compose and Material 3. Compose screens should render immutable state, emit user actions, and delegate lifecycle, I/O, protocol work, Bluetooth, Wi-Fi, USB, storage, and native SDK calls to ViewModels or lower layers.

Use MVVM/UDF for feature screens. ViewModels should expose long-lived screen state through `StateFlow` and one-shot events through `SharedFlow`. Use internal channels only for actor queues or carefully scoped coordination.

Use Hilt for dependency injection. Inject interfaces into domain and use-case layers, keep dispatchers qualified, and keep platform implementations replaceable in tests.

Prefer repository/use-case layering for behavior that outgrows a screen. Keep Bluetooth, Wi-Fi, USB, MediaStore, and Native SDK access behind interfaces so features can use fakes in tests.

New navigation should use typed/key-based Navigation 3 routes. Pass stable IDs through routes instead of large objects.

New persistence should use Room for structured data such as camera profiles, import records, transfer history, diagnostics sessions, and compatibility records. Use Proto DataStore for settings, preferences, privacy choices, and connection preferences.

## Rust Development Guidelines

Rust code should live in a workspace layout that keeps protocol primitives, transport state machines, transfer behavior, diagnostics, simulation, FFI, and CLI tooling separate. The expected crates are conceptual and do not need to exist before a contribution can be useful:

* `fuji-core`
* `fuji-ptp`
* `fuji-ptpip`
* `fuji-transfer`
* `fuji-liveview`
* `fuji-ble-protocol`
* `fuji-usb-ptp`
* `fuji-diagnostics`
* `fuji-sim`
* `fuji-ffi`
* `fuji-cli`

Crates should expose domain-level APIs rather than leaking packet-level mechanics into Android. Keep PTP packet encoding and parsing in protocol crates, PTP-IP session state in transport crates, media transfer in transfer crates, and JNI entry points in the FFI crate.

Use typed errors and structured diagnostics. Do not allow panics across FFI, do not use ambiguous file descriptor ownership, do not rely on unbounded queues, and avoid per-frame allocation in live-view hot paths.

Add packet parsing tests, encode/decode tests, golden fixtures, transfer failure tests, and fake camera server tests where practical. Complex binary parsers should be written with deterministic tests first and can add property or fuzz testing when the code is ready for it.

Expected Rust verification commands include:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

## JNI / FFI Guidelines

JNI should be coarse-grained. Android should call native operations that map to meaningful camera workflows, not individual packet operations.

Good FFI examples:

* `openWifiSession(...)`
* `listMedia(...)`
* `getThumbnail(...)`
* `downloadObjectToFd(...)`
* `capture(...)`
* `startLiveView(...)`
* `closeSession(...)`

Bad FFI examples:

* `sendPacket(...)`
* `readPacket(...)`
* `parseBytes(...)`
* `nextChunk(...)`

Document ownership for every file descriptor passed across JNI. Duplicate file descriptors on the Android side before transferring ownership to Rust, and make the close responsibility explicit in the API contract.

Never let Rust panics cross JNI. Catch unwind boundaries where needed, convert native errors into typed Kotlin domain errors, and avoid high-frequency JNI calls for live-view frames unless the delivery path is explicitly designed for allocation, backpressure, and frame dropping.

## Bluetooth Contribution Guidelines

BLE is Android-owned. The Bluetooth module owns scanning, GATT connection, service discovery, MTU negotiation, characteristic reads and writes, notification subscriptions, operation queues, timeouts, reconnect behavior, and Android permissions. Rust handles only Fujifilm-specific BLE payload interpretation and command payload generation.

Only one GATT operation may be in flight at a time. Every operation must have a timeout, disconnect must cancel pending operations, and no code outside the Bluetooth module should access `BluetoothGatt` directly.

Do not keep a permanent foreground service for BLE. Use foreground services only when the user has started an active camera operation that requires user-visible ongoing work.

Do not request background location unless implementing an explicit geotagging feature with a reviewed product decision and clear user-facing controls.

## Wi-Fi Contribution Guidelines

Camera Wi-Fi traffic must be explicitly routed through the Android `Network`. Do not assume the device default network can reach the camera.

The expected production flow is:

* Android requests the camera Wi-Fi network
* Android binds a socket to that network
* Android connects the socket to the camera endpoint
* Android passes an owned file descriptor to Rust
* Rust speaks PTP-IP over that descriptor

Rust must not blindly assume `192.168.0.1` is reachable unless the socket or process has been explicitly bound to the camera network. Connection failures should surface typed diagnostics such as user rejection, network unavailability, socket bind failure, endpoint timeout, handshake rejection, or camera busy.

## Media Import Guidelines

Imported media should go through Android MediaStore, not arbitrary direct writes into shared storage paths.

The expected import flow is:

* Create a MediaStore item with `IS_PENDING = 1`
* Open a `ParcelFileDescriptor`
* Pass an owned output file descriptor to Rust
* Rust streams the camera object into the file descriptor
* Close the file descriptor
* Set `IS_PENDING = 0`
* Record import metadata in Room

The import pipeline should be cancelable, report progress, avoid duplicate records, and leave failed imports in an explainable state.

## Privacy and Security Guidelines

Frameport is local-first by default:

* No account
* No cloud
* No analytics
* No telemetry
* No background location
* No hidden background sync
* No firmware update in v1

Diagnostic bundles must redact Wi-Fi passphrases, precise GPS, access tokens, full serial numbers by default, private MAC addresses by default, and file names unless the user opts in. Prefer local export and user review over automatic upload.

Do not introduce app-wide cleartext traffic. Any network behavior must be explicit, justified by the camera workflow, and represented in diagnostics.

## Testing Expectations

Expected verification commands may evolve while the scaffold is still forming, but contributors should be prepared to run the relevant subset and explain anything that could not run:

```bash
./gradlew test
./gradlew lint
cargo test --workspace
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
```

Android changes should add or update unit tests, Flow tests, ViewModel tests, Hilt graph tests, Compose UI tests for stable screens, fake BLE client tests, fake Wi-Fi connector tests, and MediaStore instrumentation tests where practical.

Rust changes should add or update packet encode/decode tests, parser tests, golden fixture tests, fake PTP-IP camera server tests, transfer failure tests, and physical camera test notes when hardware behavior is involved.

Physical camera testing should record the camera body, firmware version when available, Android device, Android version, connection mode, and workflow tested.

## Commit and Pull Request Guidelines

Pull requests should have a clear title, concise summary, architecture impact, testing performed, known limitations, screenshots for UI changes, and diagnostics or log examples for connection changes. If expected verification commands could not run because the scaffold or environment is not ready, say that explicitly.

PR checklist:

* The change preserves Android/Rust boundaries
* The change avoids proprietary Fujifilm code, assets, binaries, credentials, and endpoints
* UI code is Compose and Material 3 where applicable
* ViewModels do not call JNI directly
* Composables do not perform I/O
* Bluetooth, Wi-Fi, USB, MediaStore, and Native SDK access remain behind interfaces
* File descriptor ownership is documented for JNI changes
* Errors and diagnostics are typed and actionable
* Privacy defaults are preserved
* Compatibility claims are backed by evidence
* Tests or verification notes are included
* Documentation is updated when behavior or boundaries change

## Documentation Guidelines

Update documentation when changing architecture, module boundaries, protocol assumptions, JNI ownership, BLE behavior, Wi-Fi behavior, USB behavior, permissions, privacy behavior, or compatibility matrix entries.

Prefer ADRs for major decisions. ADRs should describe the context, decision, alternatives considered, consequences, and migration impact.

Keep documentation practical and current. Avoid speculative promises, broad compatibility claims, and operational details that would expose secrets or proprietary material.

## Compatibility Claims

Compatibility claims must be conservative and backed by real hardware and firmware testing. Do not infer broad Fujifilm support from one body, one firmware version, or one successful operation.

Initial compatibility wording should follow this shape:

* Tested: Fujifilm X-T5 only when verified
* Experimental: other Fujifilm X/GFX bodies
* Unsupported in v1: firmware update, cloud timeline, vendor account sync, camera settings backup/restore

When adding compatibility evidence, include the tested camera body, firmware version when available, Android device, Android version, connection path, workflow, and known limitations.

## Local Development Setup

Install Android Studio latest stable, JDK 17 or newer, Rust stable, the Android NDK, and `cargo-ndk`. Use a physical Android device for camera integration and a supported Fujifilm camera for real hardware tests.

Useful Rust and Android target setup:

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android
```

Use emulator tests for UI, ViewModel, persistence, and fake integration coverage. Use physical hardware for camera Wi-Fi, BLE, transfer, diagnostics, and compatibility claims.
