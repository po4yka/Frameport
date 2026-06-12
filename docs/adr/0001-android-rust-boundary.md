# ADR 0001: Android/Rust Boundary

## Status

Accepted

## Date

2026-06-11

## Context

Frameport needs a stable architecture for an Android-only camera companion app with a native Rust SDK. The app targets reliable local camera workflows first: connection diagnostics, Wi-Fi PTP-IP image import, and later BLE-assisted Wi-Fi handoff, remote capture, live view, and USB mode.

The major technical domains include Android runtime permissions, Bluetooth LE scanning and GATT lifecycle, Wi-Fi network request and network-bound sockets, USB permission flow later, ForegroundService lifecycle, MediaStore file import, Compose UI and navigation, Rust protocol parsing, PTP and PTP-IP session state, object enumeration and transfer, remote capture, live-view parsing later, and protocol diagnostics.

Camera companion apps are difficult because Android platform APIs and camera wire protocols have very different lifecycle, threading, permission, and failure models. Android permissions, Wi-Fi routing, Bluetooth GATT, MediaStore, foreground services, UI state, and navigation are user-visible platform concerns. PTP/PTP-IP packet parsing, Fujifilm protocol state machines, transfer state, and malformed camera input handling are protocol concerns.

Frameport intentionally separates platform ownership from protocol ownership so each side can be implemented, tested, diagnosed, and reviewed using the tools and idioms best suited to that domain.

## Decision

Frameport will use a split architecture:

```text
Android/Kotlin owns Android platform integration.
Rust owns camera protocol logic.
```

Android/Kotlin owns:

* Runtime permissions
* Bluetooth scanning
* Bluetooth GATT connection lifecycle
* GATT service discovery
* GATT characteristic reads and writes
* GATT notifications
* Wi-Fi network request flow
* Android `Network` handling
* Socket creation and network binding
* USB permission flow later
* ForegroundService lifecycle
* Notifications
* MediaStore writes
* Room and DataStore access
* App lifecycle
* Compose UI
* Navigation 3
* ViewModels
* Dependency injection
* User-visible diagnostics presentation

Rust owns:

* PTP packet encoding and decoding
* PTP response and event parsing
* PTP-IP session state
* Fujifilm protocol state machines
* Media object enumeration
* Object info parsing
* Thumbnail transfer
* Image and object transfer
* Transfer progress calculation
* Transfer cancellation state
* Remote capture command sequencing
* Live-view frame parsing later
* Protocol-level diagnostics
* BLE payload parsing
* BLE command payload generation
* Typed native error model
* Fake camera and protocol test tools

Rust must not directly own Android `BluetoothGatt`, Android Wi-Fi APIs, Android USB permission APIs, MediaStore, Compose, Navigation, Hilt, Android lifecycle, or foreground service behavior.

Android must not implement PTP/PTP-IP packet logic directly in ViewModels, repositories, Composables, or platform adapters.

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
Platform adapters
  ├── Bluetooth adapter  ── GATT bytes ──► Rust BLE protocol parser
  ├── Wi-Fi adapter      ── socket fd ───► Rust PTP-IP session
  ├── USB adapter        ── usb fd ──────► Rust PTP-over-USB session later
  └── MediaStore writer  ◄─ output fd ─── Rust transfer writer
       ↓
Rust Fuji SDK
  ├── PTP/PTP-IP
  ├── transfer
  ├── remote control
  ├── live-view later
  └── diagnostics
```

## Consequences

Positive consequences:

* Cleaner separation of platform code and protocol code
* Android lifecycle remains testable and idiomatic
* Rust protocol logic can be unit-tested independently
* Binary parsers can be fuzzed
* Protocol state machines can be reused by CLI and fake camera tooling
* JNI surface can stay coarse-grained
* Platform permissions remain user-visible and reviewable
* MediaStore and foreground service behavior remain Android-native
* Connection diagnostics can combine Android platform state and Rust protocol state

Negative consequences:

* FFI boundary design becomes critical
* File descriptor ownership must be documented precisely
* Some operations require careful async coordination across Kotlin and Rust
* Live-view may need optimized callback and buffer design
* BLE logic is split between Android GATT operations and Rust payload parsing
* Integration tests require real hardware eventually

## Alternatives Considered

### Alternative 1: Android-only implementation

This alternative would implement PTP/PTP-IP, BLE payload parsing, transfer, and live-view entirely in Kotlin.

Rejected because binary protocol parsing is better isolated in Rust, transfer and live-view hot paths need stricter allocation and error control, parser fuzzing and CLI tooling are easier in Rust, and protocol logic would likely leak into Android layers.

### Alternative 2: Rust owns everything

This alternative would make Rust own BLE, Wi-Fi sockets, USB, storage, and protocol behavior.

Rejected because Android permissions and lifecycle cannot be cleanly owned by Rust, `BluetoothGatt` lifecycle is Android-specific, Wi-Fi `Network` routing and socket binding are Android-specific, MediaStore is Android-specific, foreground service behavior is Android-specific, and this would produce brittle JNI and lifecycle behavior.

### Alternative 3: Thin Rust helper library only

This alternative would make Kotlin own most protocol logic while Rust provides only small utility functions.

Rejected because PTP/PTP-IP state machines need coherent ownership, transfer and live-view need consistent protocol-level error handling, diagnostics become fragmented, and Rust benefits are reduced while FFI complexity remains.

### Alternative 4: Use proprietary vendor SDK as primary core

This alternative would use an official or proprietary SDK as the central camera protocol layer.

Rejected for v1 because Frameport should be built from scratch, proprietary SDK binaries introduce licensing and redistribution constraints, Android Wi-Fi and BLE companion behavior may not be covered, and this would weaken clean-room control over architecture, diagnostics, and privacy.

## Implementation Rules

### General Rules

* Composables must not perform I/O.
* Composables must not call JNI.
* Composables must not access Bluetooth, Wi-Fi, USB, MediaStore, or Room directly.
* ViewModels must not call JNI directly.
* ViewModels must interact with repositories/use cases only.
* Repositories must depend on interfaces for platform adapters and native SDK wrappers.
* Protocol packet logic must live in Rust.
* Android platform operations must live in Android modules.
* All native errors must map to typed Kotlin domain errors.
* All long-running camera operations must be cancellable.

### JNI Rules

* JNI must be coarse-grained.
* Do not expose packet-level APIs over JNI.
* Do not expose high-frequency tiny operations over JNI unless performance has been designed and measured.
* Every file descriptor passed over JNI must have documented ownership.
* Android must duplicate file descriptors before transferring ownership to Rust.
* Rust must not double-close file descriptors.
* Rust panics must never cross the JNI boundary.
* Native functions must return typed errors or safe result wrappers.
* Callbacks must avoid leaking sensitive data.

Good JNI/API examples:

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

Bad JNI/API examples:

```text
sendPacket(...)
readPacket(...)
parseBytes(...)
nextChunk(...)
```

### BLE Rules

* BLE scan and GATT lifecycle are Android-owned.
* Rust may parse BLE payloads and build BLE command payloads.
* Only one GATT operation may be in flight at a time.
* Every GATT operation must have a timeout.
* Pending GATT operations must be cancelled on disconnect.
* No direct `BluetoothGatt` access outside the Bluetooth module.
* No background BLE behavior without explicit user-visible product decision.

### Wi-Fi Rules

* Camera Wi-Fi traffic must be routed through the Android `Network`.
* Production flow should use Android-created, network-bound sockets.
* Rust should receive owned socket file descriptors.
* Rust must not assume `192.168.0.1` is reachable from the default network.
* Network bindings must be released after session close.

### MediaStore Rules

* Android owns MediaStore.
* Android creates pending media items.
* Android passes owned output file descriptors to Rust.
* Rust streams bytes to the provided fd.
* Android finalizes or cancels the MediaStore item.
* Rust must not write arbitrary shared storage paths.

## Testing Implications

Android tests should include:

* ViewModel tests for state transitions
* Fake repository tests
* Fake BLE client tests
* Fake Wi-Fi connector tests
* MediaStore instrumentation tests
* Foreground service lifecycle tests
* Navigation tests for route behavior

Rust tests should include:

* PTP packet encode/decode tests
* Response and event parsing tests
* Transfer state tests
* Fake camera server tests
* Malformed packet tests
* Property-based parser tests where useful
* Fuzzing for binary parsers where practical

Integration tests should include:

* Manual Wi-Fi PTP-IP session on Fujifilm X-T5
* Socket binding behavior on real Android devices
* Import-to-MediaStore verification
* BLE handoff tests later
* USB tests later

## Security and Privacy Implications

Keeping Android permissions in Kotlin keeps user consent visible and reviewable. Keeping protocol parsing in Rust improves binary parsing robustness and makes malformed packet handling easier to test and fuzz.

Frameport remains local-first by default and avoids unnecessary cloud exposure. Diagnostics must redact sensitive data. Camera Wi-Fi passphrases and pairing secrets must never be logged. Precise location must not be collected unless an explicit geotagging feature exists.

No firmware update is implemented in v1. No vendor cloud account flow is implemented in v1.

## Related Documents

```text
README.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
NOTICE
docs/adr/0002-wifi-socket-fd-handoff.md
docs/adr/0003-ble-client-abstraction.md
docs/adr/0004-media-import-pipeline.md
docs/security/reverse-engineering-boundary.md
docs/protocol/compatibility-matrix.md
```
