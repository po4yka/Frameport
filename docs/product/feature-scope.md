# Feature Scope

## Purpose

This document defines what Frameport is and is not building.

Scope control is critical because camera companion apps can easily expand into many independent surfaces: camera discovery, BLE pairing, Wi-Fi handoff, PTP-IP transfer, USB transfer, MediaStore import, remote shutter, live-view, camera settings, firmware update, cloud timeline, account sync, analytics, diagnostics, local catalog, and hardware compatibility tracking.

Frameport starts narrow: reliable local camera connection and import first.

## Current App Shell

The current Android shell is an implementation scaffold, not real camera support. It includes a Material 3 app scaffold, typed Navigation 3 destinations, feature route composables, Hilt ViewModels, `StateFlow` UI state, and placeholder screens for onboarding, home, camera scan, manual camera connection, gallery, import queue, remote shutter, live view, diagnostics, and settings.

The home screen exposes the intended product surfaces while marking unavailable items as planned or not implemented yet. Connection screens simulate placeholder UI states only: idle, scanning, camera found, requesting Wi-Fi, opening session, connected, and error. Gallery and import use empty states backed by no-op/fake repositories. Remote and live view are planned placeholders only.

This shell must not be treated as compatibility evidence. It does not scan Bluetooth, request Wi-Fi networks, open USB devices, access MediaStore, call JNI, speak PTP/PTP-IP, trigger capture, parse live view, or communicate with a camera.

## Product Positioning

Frameport is:

```text
A local-first Android camera companion app for Fujifilm cameras, focused on reliable connection diagnostics, Wi-Fi PTP-IP image import, and a clean Android/Rust architecture.
```

Frameport is unofficial, independent, Android-only, local-first, privacy-first, X-T5-first initially, built from scratch, and not a full clone of the official app.

Frameport optimizes for reliability, diagnosability, clear permissions, local ownership of imported media, conservative compatibility claims, maintainable architecture, and protocol correctness.

## Scope Principles

```text
1. Local camera workflows before cloud workflows.
2. Import reliability before advanced control.
3. Diagnostics before silent retries.
4. Manual flow before automatic background flow.
5. Read-only metadata before write/control operations.
6. Wi-Fi PTP-IP before BLE handoff automation.
7. BLE handoff before background BLE behavior.
8. Import before live-view.
9. Remote shutter before full remote control.
10. USB later, not first.
11. Firmware update excluded from v1.
12. Cloud/account features excluded from v1.
13. Compatibility claims require hardware evidence.
14. Location and geotagging features require explicit user consent, visible active state, minimal retention, and redaction-aware diagnostics.
15. GPS-to-camera EXIF geotagging is opt-in and foreground-only in v1.
16. Background location and persistent GPS history remain out of scope for v1.
```

Features should be staged only when the lower-level transport and diagnostics are stable.

## v1 Scope

v1 is the first useful production-oriented release. v1 must include only local workflows.

### v1 Core Features

```text
Project foundation:
  - Android app scaffold
  - Rust workspace scaffold
  - JNI/native loading scaffold
  - Hilt setup
  - Compose design foundation
  - Navigation 3 setup
  - structured logging foundation

Connection:
  - manual camera Wi-Fi connection flow
  - Android Wi-Fi network request
  - Android Network-bound socket creation
  - owned socket fd handoff to Rust
  - Rust PTP-IP session open
  - camera identity read where available
  - connection state machine
  - connection diagnostics screen

Media browsing:
  - media object enumeration
  - object info parsing
  - thumbnail fetch where available
  - gallery grid
  - selected object state

Import:
  - single image import
  - batch import
  - JPEG import
  - RAF import if verified for target camera
  - HEIF import if verified for target camera
  - MediaStore pending-item pipeline
  - progress reporting
  - cancellation
  - failure cleanup
  - duplicate detection baseline
  - local import catalog in Room

GPS-to-camera EXIF geotagging:
  - explicit opt-in location permission flow
  - clear permission rationale before Android permission request
  - foreground-only geotagging mode
  - user-visible active geotagging state
  - stop/pause control
  - acquire current phone GPS/location fix
  - validate location freshness and accuracy
  - send current coordinates to the connected camera when supported
  - allow camera to embed GPS coordinates into EXIF metadata during shooting where supported
  - no background location in v1
  - no persistent GPS history by default
  - no cloud upload of location data
  - precise coordinates redacted from diagnostics by default
  - no raw coordinates in release logs
  - camera/firmware compatibility verification

Settings:
  - local app settings
  - import preferences
  - privacy preferences
  - diagnostic preferences

Diagnostics:
  - typed connection errors
  - typed transfer errors
  - redacted diagnostic bundle
  - compatibility metadata display
  - firmware version display if read-only protocol support exists
```

GPS-to-camera EXIF geotagging is in v1 scope, but support must be claimed per camera model, firmware version, Android device/OS version where relevant, and verified shooting workflow.

### v1 Target Camera

```text
Primary target:
  Fujifilm X-T5

Support level:
  Feature-by-feature verification required.
```

Do not claim X-T5 support as complete unless verified in the compatibility matrix.

### v1 Target Platform

```text
Platform:
  Android-only

Initial ABI:
  arm64-v8a

Development/debug ABI:
  x86_64 may be added for emulator support if useful
```

The Android version policy is defined by build configuration and architecture ADRs. This document only defines product feature scope.

## v1 Non-Goals

```text
Not in v1:
  - firmware update
  - firmware binary download
  - firmware manifest fetch
  - cloud account login
  - Fujifilm account integration
  - Cognito/OAuth flows
  - vendor API integrations
  - cloud timeline
  - equipment cloud sync
  - backup/restore cloud sync
  - analytics
  - hidden telemetry
  - crash upload by default
  - automatic background import
  - background geotagging
  - automatic hidden location sync
  - persistent GPS history
  - cloud upload of location data
  - map/timeline cloud location features
  - live-view
  - full remote control
  - camera settings backup/restore
  - USB mode
  - video import unless explicitly verified and accepted
  - broad all-Fujifilm compatibility claims
  - iOS support
  - desktop support
```

Some items may become future candidates, but they are excluded from v1.

## v2 Candidate Scope

v2 means after the v1 import path is stable. These features are candidates, not commitments.

```text
BLE-assisted Wi-Fi handoff:
  - BLE scan
  - GATT connect
  - camera identity read
  - pairing state support
  - Wi-Fi SSID/passphrase handoff where supported
  - handoff into Android Wi-Fi connector
  - reconnect diagnostics

Remote shutter:
  - BLE remote shutter if verified
  - Wi-Fi remote shutter if PTP-IP path supports it
  - typed camera busy/error handling

Import improvements:
  - import presets
  - improved duplicate detection
  - better thumbnail cache
  - session restore after disconnect
  - user-controlled destination preferences

Compatibility:
  - expanded hardware test matrix
  - additional Fujifilm camera experiments
  - firmware-specific notes
```

## v3 Candidate Scope

v3 means after v1/v2 connection and import are stable. These features require separate design docs or ADR updates before implementation.

```text
Live-view:
  - auxiliary channel validation
  - frame receive pipeline
  - reusable frame buffers
  - Compose/Surface preview
  - latency metrics
  - frame drop diagnostics

Remote camera control:
  - read basic camera properties
  - set limited camera properties where verified
  - ISO/shutter/aperture/WB/film simulation only if supported and verified
  - capture state diagnostics

USB import:
  - Android USB permission flow
  - USB fd handoff
  - PTP-over-USB session
  - wired media enumeration
  - wired import into MediaStore
```

## Future / Later Scope

Later possibilities are not commitments.

```text
Local timeline:
  - local-only import history
  - local shooting sessions
  - local camera/lens usage stats from imported metadata

Camera profiles:
  - local camera profile metadata
  - local compatibility quirks
  - local user notes

Advanced diagnostics:
  - exportable support bundle
  - fake camera replay
  - packet-level debug in debug builds only
  - connection health scoring

Advanced import:
  - resume strategy if protocol supports it
  - checksum/hash tracking if useful
  - advanced duplicate detection
  - selective RAW/JPEG pairing behavior

Advanced geotagging later:
  - optional background geotagging only after separate ADR/security review
  - optional persistent location history only after separate ADR/security review
  - optional geotagging profiles
  - optional geotagging accuracy controls
  - optional EXIF location privacy tools

Optional external integrations:
  - explicit user-initiated share/export
  - explicit open-with workflows
  - user-owned backup only after separate ADR
```

## Permanently Excluded Unless Reconsidered

These are not merely postponed engineering tasks; they are separate product/security decisions and require a major ADR, security review, and legal review before any work.

```text
Requires separate ADR before any implementation:
  - firmware update
  - firmware binary transfer
  - vendor cloud account login
  - vendor cloud API integration
  - cloud media upload
  - automatic analytics
  - background telemetry
  - proprietary SDK binary integration
  - official app asset reuse
  - camera settings restore/write features that can alter user configuration broadly
  - background geotagging beyond explicit foreground GPS-to-camera sharing
  - automatic hidden location tracking
  - persistent GPS history
  - cloud location sync
  - automatic geotagging started without explicit user action
  - automatic background import
```

## Feature Acceptance Criteria

### General Criteria

```text
A feature is accepted only when:
  - scope is documented;
  - architecture boundary is respected;
  - privacy impact is reviewed;
  - permissions are justified;
  - errors are typed;
  - diagnostics exist;
  - tests are added where practical;
  - compatibility claims are conservative;
  - user-facing behavior is explicit;
  - failure and cancellation paths are handled.
```

### Connection Feature Criteria

```text
Connection features must:
  - expose current state;
  - distinguish permission/network/protocol/camera failures;
  - support cancellation;
  - clean up resources;
  - avoid hidden background behavior;
  - redact sensitive diagnostics.
```

### Import Feature Criteria

```text
Import features must:
  - use MediaStore;
  - use pending items for partial imports;
  - stream through fd;
  - report progress;
  - support cancellation;
  - clean failed pending items;
  - avoid logging private filenames by default;
  - record local metadata conservatively.
```

### BLE Feature Criteria

```text
BLE features must:
  - isolate Android GATT behind the BLE module;
  - use one GATT operation in flight;
  - include timeouts;
  - handle disconnect;
  - avoid logging pairing secrets or passphrases;
  - avoid background behavior unless explicitly approved.
```

### GPS-to-camera EXIF Geotagging Criteria

```text
GPS-to-camera EXIF geotagging must:
  - be opt-in;
  - request only the minimum location permission needed;
  - explain why location is needed before requesting permission;
  - explain that coordinates may be embedded into photo EXIF by the camera;
  - show user-visible active geotagging state;
  - support stop/pause at any time;
  - avoid background location in v1;
  - avoid storing precise GPS history by default;
  - never upload location data to cloud in v1;
  - redact precise coordinates from diagnostics by default;
  - avoid logging raw coordinates in release builds;
  - verify camera/firmware support before claiming compatibility;
  - verify actual EXIF embedding with a test photo before marking supported;
  - fail safely when location permission is denied;
  - fail safely when Android location provider is unavailable;
  - fail safely when camera does not support location sync;
  - distinguish permission, Android location, BLE/protocol, and camera-side failures.
```

### Native Feature Criteria

```text
Native/Rust features must:
  - keep packet logic in Rust;
  - avoid panics across JNI;
  - validate input sizes;
  - use bounded buffers;
  - provide typed errors;
  - include tests or fixtures where practical;
  - document fd ownership when fds are used.
```

## Scope Change Process

Process:

```text
1. Open an issue or planning document describing user value.
2. Identify product phase: v1, v2, v3, later, or excluded.
3. Identify architecture impact.
4. Identify privacy/security impact.
5. Identify permissions impact.
6. Identify protocol/hardware requirements.
7. Identify test requirements.
8. Update this document or add an ADR if the change is significant.
9. Do not implement high-risk features before scope is accepted.
```

Features requiring ADR before implementation:

```text
- cloud/network behavior beyond camera-local communication
- firmware update
- analytics/telemetry
- background geotagging beyond explicit foreground GPS-to-camera sharing
- automatic hidden location tracking
- persistent GPS history
- cloud location sync
- automatic background import
- proprietary SDK binary integration
- broad camera settings write/restore
- new storage model
- new native FFI boundary
```

## Compatibility Claim Rules

Do not claim support without compatibility matrix evidence. Do not claim all Fujifilm cameras are supported. Do not claim a camera is supported globally if only one feature was tested.

Claim support per feature, camera model, firmware version, Android device/OS where relevant. Use "experimental" for partially verified behavior. Use "unknown" for untested behavior. Keep user-facing claims narrower than engineering hopes.

GPS-to-camera EXIF geotagging support must be claimed per camera model, firmware version, Android device/OS version where relevant, and tested shooting workflow. Do not claim geotagging support merely because BLE connection works. Do not claim geotagging support merely because a location payload can be sent. A supported claim requires confirming that a test image captured after location sharing contains expected EXIF GPS metadata.

Example acceptable wording:

```text
Manual Wi-Fi import has been tested on Fujifilm X-T5 firmware X.Y with Frameport version Z.
```

Example unacceptable wording:

```text
Frameport supports all Fujifilm cameras.
Frameport fully replaces XApp.
Frameport supports firmware update.
```

## Privacy and Security Scope Rules

Default v1 privacy scope:

```text
v1 defaults:
  no account
  no cloud
  no analytics
  no telemetry
  no firmware update
  no automatic background import
  location access only for explicit GPS-to-camera EXIF geotagging
  no background location
  no persistent GPS history by default
  no cloud upload of location data
  precise coordinates redacted from diagnostics by default
  EXIF geotagging only after explicit user opt-in
  local diagnostics only
  local import history only
```

Non-geotagging workflows must work without location permission.

Feature proposals must identify whether they touch:

* Camera Wi-Fi credentials
* Pairing secrets
* Precise location
* GPS coordinates sent to camera
* EXIF location metadata
* Background location
* Location history
* Location diagnostics
* Location permission UX
* Filenames
* EXIF
* Serial numbers
* MAC addresses
* Raw packets
* Media bytes
* Cloud/internet endpoints
* Background services
* Native code

Any feature touching these areas must include redaction and user-consent behavior.

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
docs/adr/0007-module-boundaries.md
docs/security/reverse-engineering-boundary.md
docs/security/diagnostics-redaction.md
docs/protocol/compatibility-matrix.md
```
