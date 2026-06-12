# ADR 0007: Module Boundaries

## Status

Accepted

## Date

2026-06-11

## Context

Frameport needs strict module boundaries because it combines Android UI, Android permissions, Bluetooth LE, Wi-Fi network handoff, USB later, MediaStore import, local persistence, a Rust native protocol SDK, PTP/PTP-IP, Fujifilm protocol state machines, diagnostics, and future live-view.

Without explicit boundaries, camera companion apps tend to become tightly coupled around platform APIs, protocol code, UI state, services, and native bridges. That coupling makes it easier for JNI calls to leak into ViewModels, Android framework objects to leak into domain logic, protocol parsing to appear in UI code, and privacy-sensitive implementation details to reach logs or diagnostics.

Frameport should be modular from the beginning, even if the first implementation starts small. These boundaries are architectural rules, not a requirement to create every module immediately on day one. Modules may be introduced progressively as code appears.

## Decision

Frameport will use a layered, interface-first module structure.

The intended top-level structure is:

```text
:app

:core:common
:core:model
:core:designsystem
:core:permissions
:core:logging
:core:storage
:core:testing

:native:fuji-rust-android

:camera:api
:camera:domain
:camera:data
:camera:bluetooth
:camera:wifi
:camera:usb
:camera:media
:camera:diagnostics

:feature:onboarding
:feature:connection
:feature:gallery
:feature:import
:feature:remote
:feature:liveview
:feature:settings
:feature:diagnostics

rust/fuji-rs/
  crates/
    fuji-core
    fuji-ptp
    fuji-ptpip
    fuji-transfer
    fuji-liveview
    fuji-ble-protocol
    fuji-usb-ptp
    fuji-diagnostics
    fuji-sim
    fuji-ffi
    fuji-cli
```

The architecture should be:

```text
Features depend on camera/domain APIs.
Domain depends on abstractions.
Data/platform modules implement abstractions.
Native wrapper hides JNI.
Rust SDK hides protocol details.
UI never talks directly to Android Bluetooth/Wi-Fi/USB/JNI.
```

### Boundary Diagram

```text
:app
  ↓
:feature:*
  ↓
:camera:api
  ↓
:camera:domain
  ↓
:camera:data
  ├── :camera:bluetooth
  ├── :camera:wifi
  ├── :camera:usb
  ├── :camera:media
  ├── :camera:diagnostics
  └── :native:fuji-rust-android
        ↓
      rust/fuji-rs crates
```

Clean interaction model:

```text
Composable
  ↓
ViewModel
  ↓
Use case
  ↓
Repository interface
  ↓
Repository implementation
  ├── Bluetooth adapter
  ├── Wi-Fi adapter
  ├── MediaStore adapter
  └── Native Rust SDK adapter
        ↓
      Rust protocol SDK
```

## Consequences

Positive consequences:

* Protocol logic stays out of UI
* Android platform APIs stay behind platform adapters
* ViewModels remain testable
* BLE/Wi-Fi/USB implementations can be faked
* Rust SDK can evolve independently
* Feature modules stay focused on user workflows
* Privacy/security decisions are easier to audit
* Compatibility and diagnostics can be modeled centrally
* Future live-view and USB can be added without rewriting the app

Negative consequences:

* More modules and interfaces to maintain
* Initial setup is heavier
* Dependency graph must be enforced
* Small features may require touching several layers
* Developers must understand boundaries before contributing
* Build configuration may be more complex

## Alternatives Considered

### Alternative 1: Single Android app module

This alternative would put all Kotlin, Compose, platform, and native wrapper code into `:app`.

Rejected because UI, platform APIs, storage, protocol bridges, and business logic would mix; testing would be harder; native SDK calls could leak into ViewModels; BLE/Wi-Fi/USB code would be hard to isolate; and long-term production maintenance would suffer.

### Alternative 2: Feature-only modules

This alternative would create only feature modules and keep shared camera/platform code in a common module.

Rejected because camera workflows are cross-cutting, BLE/Wi-Fi/USB/MediaStore/Rust SDK require clear ownership, common modules often become dumping grounds, and protocol and platform abstractions need dedicated boundaries.

### Alternative 3: Rust-first repository with thin Android shell

This alternative would put most state, workflow, and domain logic in Rust while Android only renders UI and forwards events.

Rejected because Android permissions, lifecycle, foreground services, MediaStore, and navigation are platform-native concerns; app-level UX state is better expressed in Kotlin/Flow/ViewModel; and Rust should own protocol logic, not Android app orchestration.

### Alternative 4: Copy official app-style package structure

This alternative would use a package structure similar to a decompiled official companion app.

Rejected because Frameport is built from scratch, copied architecture may carry legacy and vendor-specific coupling, official app responsibilities include cloud/server-linkage not present in Frameport v1, and Frameport should use its own clean architecture and naming.

## Android Module Boundaries

### `:app`

Owns:

* Application entry point
* App-level navigation host
* Hilt application setup
* Top-level theme wiring
* Feature graph assembly

Must not own:

* Camera protocol logic
* BLE implementation details
* Wi-Fi implementation details
* MediaStore import internals
* Native JNI calls directly

### `:core:common`

Owns:

* Small shared utilities
* Result helpers
* Simple Kotlin extensions
* Common constants that are not feature-specific

Must not become a dumping ground.

Must not own:

* Android platform adapters
* Camera protocol logic
* Feature-specific state
* Storage schemas
* Native SDK calls

### `:core:model`

Owns:

* App-wide pure Kotlin value types
* Non-camera-specific domain primitives
* Shared UI/domain models where justified

Must avoid Android framework types unless explicitly needed.

### `:core:designsystem`

Owns:

* Compose theme
* Typography
* Color tokens
* Spacing tokens
* Common UI components
* Icons and illustrations created for Frameport

Must not own:

* Feature business logic
* Camera state
* Navigation decisions
* Platform operations

### `:core:permissions`

Owns:

* Permission abstractions
* Permission state models
* Reusable permission UI helpers
* Permission rationale models

Must not directly start camera workflows.

### `:core:logging`

Owns:

* Structured logging facade
* Redaction helpers
* Diagnostic-safe log fields

Must not log secrets.

### `:core:storage`

Owns:

* Shared storage configuration
* DataStore setup if not feature-specific
* Room database setup if centralized

Must not own camera protocol transfer.

### `:core:testing`

Owns:

* Test utilities
* Fake dispatchers
* Fake repositories
* Shared test fixtures
* Compose test helpers

### `:native:fuji-rust-android`

Owns:

* JNI wrapper
* Native library loading
* Kotlin facade over Rust SDK
* Mapping native errors to Kotlin domain errors
* Fd ownership API surface
* Rust build integration if needed

Must not expose raw packet APIs to feature modules.

### `:camera:api`

Owns:

* Public camera-facing interfaces consumed by features
* Repository interfaces
* Stable domain-facing contracts
* Use-case input/output models when shared

Must not depend on concrete BLE/Wi-Fi/USB/native implementations.

### `:camera:domain`

Owns:

* Camera workflow use cases
* Connection state machine at domain level
* Import orchestration
* Remote control orchestration
* Diagnostic domain models
* Compatibility rules

Must not directly use Android `BluetoothGatt`, `Network`, `UsbManager`, `MediaStore`, or JNI.

### `:camera:data`

Owns:

* Repository implementations
* Coordination between domain abstractions and platform/native adapters
* Local persistence integration for camera workflows
* Mapping between platform/native models and domain models

Must not contain UI code.

### `:camera:bluetooth`

Owns:

* BLE scan implementation
* GATT connection implementation
* Operation queue
* Characteristic read/write
* Notification flows
* BLE permission integration through abstractions

Must expose only project-defined interfaces.

### `:camera:wifi`

Owns:

* Camera Wi-Fi network request
* Android `Network` handling
* Network-bound socket creation
* Socket fd handoff preparation
* Wi-Fi connection diagnostics

Must not implement PTP/PTP-IP packet logic.

### `:camera:usb`

Owns later:

* USB permission flow
* USB device discovery
* USB descriptor access
* USB fd handoff preparation

Must not implement UI or MediaStore import logic.

### `:camera:media`

Owns:

* MediaStore writer
* Pending-item lifecycle
* Destination planning
* Filename sanitization
* MIME mapping
* Import finalization and cleanup

Must not implement camera protocol transfer.

### `:camera:diagnostics`

Owns:

* Diagnostic models
* Diagnostic aggregation
* Redaction
* Export formatting
* Connection timeline representation

Must not expose secrets.

### `:feature:onboarding`

Owns:

* First-run education
* Privacy-first explanation
* Permission explanation entry points

Must not request all permissions blindly.

### `:feature:connection`

Owns:

* Camera discovery UI
* Connection flow UI
* Connection diagnostics UI
* User actions for connect/disconnect

Must use use cases/repositories only.

### `:feature:gallery`

Owns:

* Camera media browsing UI
* Thumbnail display
* Selection state

Must not perform transfer directly.

### `:feature:import`

Owns:

* Import queue UI
* Progress UI
* Cancellation UI
* Import result UI

Must not create MediaStore items directly unless through domain/use-case abstractions.

### `:feature:remote`

Owns later:

* Remote shutter UI
* Basic remote control UI

Must not directly access BLE or PTP commands.

### `:feature:liveview`

Owns later:

* Live-view preview UI
* Frame display
* Live-view controls

Must not parse live-view frames itself.

### `:feature:settings`

Owns:

* App settings UI
* Privacy settings UI
* Import preferences UI
* Diagnostics preferences UI

### `:feature:diagnostics`

Owns:

* Diagnostic bundle UI
* Redaction options
* Troubleshooting screens

Must not expose raw secrets.

## Rust Workspace Boundaries

### `fuji-core`

Owns:

* Core domain types
* Camera identity
* Session identifiers
* Transport identifiers
* Shared error enums
* Capability models

### `fuji-ptp`

Owns:

* PTP packet structures
* Operation codes
* Response codes
* Event codes
* Transaction IDs
* Object metadata parsing

### `fuji-ptpip`

Owns:

* Wi-Fi PTP-IP session
* Command/event/live-view channel protocol
* Session handshake
* Transport read/write over owned fd-backed streams

### `fuji-transfer`

Owns:

* Media object enumeration flow
* Thumbnail transfer
* Object download
* Progress calculation
* Cancellation state

### `fuji-liveview`

Owns later:

* Live-view channel handling
* Frame parsing
* Reusable buffers
* Frame metadata

### `fuji-ble-protocol`

Owns:

* Fujifilm-specific BLE payload parsing
* BLE command payload generation
* Pairing/handoff protocol state

Must not own Android GATT.

### `fuji-usb-ptp`

Owns later:

* PTP-over-USB protocol behavior
* USB transport abstraction over Android-provided fd or adapter

### `fuji-diagnostics`

Owns:

* Protocol-level diagnostic events
* Redaction-aware diagnostic structures
* Native trace formatting

### `fuji-sim`

Owns:

* Fake camera server
* Fake protocol responses
* Failure injection
* Golden fixture replay

### `fuji-ffi`

Owns:

* JNI entry points
* Panic boundaries
* Fd ownership conversion
* Callback glue
* Error mapping

### `fuji-cli`

Owns:

* Development/debug CLI
* Protocol experiments
* Fake-camera interaction
* Local hardware validation outside Android where appropriate

Must not be required for Android runtime.

## Dependency Rules

Allowed:

```text
:feature:* -> :camera:api
:feature:* -> :core:designsystem
:feature:* -> :core:model
:feature:* -> :core:permissions where needed

:camera:domain -> :camera:api
:camera:domain -> :core:model
:camera:domain -> :core:common

:camera:data -> :camera:api
:camera:data -> :camera:domain
:camera:data -> :camera:bluetooth
:camera:data -> :camera:wifi
:camera:data -> :camera:media
:camera:data -> :camera:diagnostics
:camera:data -> :native:fuji-rust-android

:camera:bluetooth -> :camera:api or shared camera models
:camera:wifi -> :camera:api or shared camera models
:camera:media -> :camera:api or shared camera models

:native:fuji-rust-android -> :camera:api or native-specific models
```

Forbidden:

```text
:core:* -> :feature:*
:core:* -> :camera:data
:core:* -> :native:fuji-rust-android

:feature:* -> :camera:data
:feature:* -> :camera:bluetooth
:feature:* -> :camera:wifi
:feature:* -> :camera:usb
:feature:* -> :camera:media
:feature:* -> :native:fuji-rust-android

:camera:domain -> Android Bluetooth/Wi-Fi/USB framework APIs
:camera:domain -> JNI directly
:camera:domain -> Compose UI

Rust protocol crates -> Android concepts
Rust protocol crates -> Kotlin/Java framework assumptions
```

Dependency rules should be enforced through Gradle module dependencies and code review.

## Forbidden Dependencies

These architectural shortcuts are forbidden:

* Composable directly depends on `BluetoothGatt`
* Composable directly depends on `UsbManager`
* Composable directly calls native SDK
* ViewModel directly calls JNI
* ViewModel directly creates sockets
* ViewModel directly creates MediaStore items
* Rust owns Android permissions
* Rust owns Android `Network`
* Rust owns Android `BluetoothGatt`
* Rust writes shared media paths directly
* Feature module depends on concrete BLE implementation
* Feature module depends on concrete Wi-Fi implementation
* Feature module depends on concrete Rust JNI wrapper
* Protocol packet parsing exists in UI modules

## Migration / Progressive Adoption

Modules may be introduced gradually.

For a fresh repository:

* Start with the intended structure if practical
* Otherwise use fewer modules but keep package boundaries aligned
* Split modules before code becomes coupled
* Do not delay boundary enforcement until after protocol code lands

For existing code:

* Move code toward these boundaries incrementally
* Preserve tests
* Avoid broad unrelated refactors
* Document migration decisions in PRs

## Testing Implications

Feature modules should include:

* ViewModel tests
* UI state reducer tests
* Compose screenshot/UI tests where useful
* Fake use cases/repositories

Camera domain should include:

* Use-case tests
* State machine tests
* Import orchestration tests
* Connection orchestration tests
* Error mapping tests

Platform modules should include:

* Fake BLE tests
* Fake Wi-Fi connector tests
* MediaStore instrumentation tests
* USB permission flow tests later

Native wrapper should include:

* JNI load tests where possible
* Error mapping tests
* Fd ownership tests
* Fake native SDK tests

Rust crates should include:

* Unit tests per crate
* Golden packet tests
* Fake camera server tests
* Parser fuzzing where useful
* CLI smoke tests where practical

## Security and Privacy Implications

Module boundaries help prevent secret leakage. Logging and redaction should be centralized. BLE secrets remain in BLE/protocol layers. Wi-Fi passphrases must not reach UI except redacted state. MediaStore paths and filenames should be handled in the media layer. Diagnostics should aggregate redacted data rather than raw implementation objects. Cloud/server-linkage code is out of scope for v1 and should not appear in modules.

Dependency review is required for modules touching:

```text
Bluetooth
Wi-Fi
USB
MediaStore
JNI/native code
cryptography
logging
diagnostics
networking
storage
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
docs/adr/0003-ble-client-abstraction.md
docs/adr/0004-media-import-pipeline.md
docs/adr/0005-no-cloud-v1.md
docs/adr/0006-no-firmware-update-v1.md
docs/architecture/overview.md
docs/architecture/android-modules.md
docs/architecture/rust-workspace.md
docs/product/feature-scope.md
docs/security/reverse-engineering-boundary.md
```
