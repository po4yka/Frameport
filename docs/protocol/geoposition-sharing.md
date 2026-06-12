# Geoposition Sharing Protocol

## Purpose

This document defines Frameport's v1 geoposition-sharing protocol scope.

Feature definition:

```text
Frameport v1 may use the Android phone's current GPS/location fix, with explicit user consent, and send that geoposition to the connected camera so the camera can embed GPS/location metadata into photo EXIF at capture time where supported.
```

This is not general location tracking. It is a camera companion feature intended to geotag photos during active shooting.

This document is an engineering/protocol and privacy document, not a compatibility guarantee.

## Scope

Covered:

```text
explicit opt-in geotagging mode
Android location permission model
foreground location acquisition
location freshness and accuracy handling
sending GPS coordinates to camera where supported
BLE/control-channel payload construction later
camera-side EXIF geotag behavior assumptions
diagnostics and redaction
compatibility verification
failure modes
```

Not covered in v1:

```text
background location tracking
persistent GPS history
cloud location sync
map/timeline cloud features
automatic hidden geotagging
firmware update
vendor account integration
vendor cloud APIs
iOS behavior
desktop behavior
```

Related documents:

```text
docs/protocol/bluetooth-le.md
docs/protocol/error-model.md
docs/security/diagnostics-redaction.md
docs/product/feature-scope.md
docs/adr/0003-ble-client-abstraction.md
docs/adr/0005-no-cloud-v1.md
```

## Product Status

```text
Status:
  v1 scope, compatibility-gated

Frameport v1 includes explicit foreground opt-in GPS-to-camera EXIF geotagging.

The feature remains disabled by default and requires explicit user action and Android location permission.
```

Non-geotagging workflows must remain usable without location permission.

Strict v1 limits:

```text
Allowed in v1:
  - explicit opt-in geotagging mode
  - foreground-only location sharing
  - minimum required Android location permission
  - user-visible active geotagging state
  - stop/pause control
  - no cloud upload
  - no persistent GPS history by default
  - no background location in v1
  - diagnostics with precise coordinates redacted by default
  - compatibility verification per camera/firmware

Still not allowed in v1:
  - background geotagging
  - automatic hidden location sync
  - persistent GPS history
  - cloud location sync
  - map/timeline cloud location features
  - starting geotagging without explicit user action
  - logging raw coordinates in release builds
  - claiming support without EXIF verification
```

Implementation requires:

```text
privacy review
permission UX review
camera/firmware compatibility testing
BLE/control-channel validation
diagnostics redaction implementation
EXIF verification workflow
```

BLE connection support does not imply geoposition-sharing support.

## Architecture Overview

Frameport uses this split:

```text
Android/Kotlin:
  owns Android location permission
  owns location provider access
  owns foreground geotagging state
  owns user-visible opt-in/stop controls
  owns BLE/GATT write lifecycle
  owns diagnostics presentation

Rust:
  owns Fujifilm-specific location payload construction later
  owns payload validation rules
  owns typed geoposition protocol errors
  owns protocol-level diagnostics
```

```text
User enables geotagging
  ↓
Android permission/rationale flow
  ↓
Foreground location acquisition
  ↓
Location freshness/accuracy validation
  ↓
Rust builds camera-compatible location payload later
  ↓
Android BLE/control-channel write
  ↓
Camera receives location update
  ↓
Camera embeds GPS into EXIF during shooting where supported
```

Rust must not request Android location permissions. Rust must not access Android location providers. Android must not build ad hoc proprietary payloads in feature UI code.

## Android/Rust Boundary

Android owns:

```text
location permission request
permission rationale UI
foreground active-state UI
foreground active-state notification if needed
location provider selection
location freshness checks
location accuracy checks
user stop/pause controls
BLE connection lifecycle
GATT characteristic write operation
write timeout/retry handling
user-visible state
diagnostics presentation
```

Rust owns:

```text
camera-specific location payload encoding later
payload length validation
coordinate value validation
timestamp value validation
typed protocol errors
protocol diagnostic events
synthetic fixture tests for payloads
```

Explicitly forbidden:

```text
Composable directly accessing location provider
ViewModel directly accessing BluetoothGatt
Rust directly accessing Android Location APIs
Rust storing precise GPS history
feature modules logging raw coordinates
diagnostic export including precise GPS by default
background location in v1
cloud upload of location data
```

## User Consent and Permission Model

Rules:

```text
geoposition sharing must be opt-in
permission rationale must appear before Android permission request
user must understand coordinates are sent to the camera
user must understand coordinates may be embedded into photo EXIF
user must be able to stop/pause sharing at any time
permission denial must fail safely
permission denial must not block non-location camera workflows
```

Minimum permission behavior:

```text
v1 implementation:
  foreground location only
  no background location
  no persistent location history
  no cloud location upload
```

Future background location:

```text
requires separate ADR
requires separate security/privacy review
requires explicit user value
requires persistent visible state
not part of v1
```

Android permission names and exact implementation may depend on minSdk/targetSdk and should be documented in `docs/android/permissions.md` if implemented.

## Geoposition Sharing Lifecycle

```text
Disabled
  ↓ user opts in
PermissionRationale
  ↓ user accepts
PermissionRequest
  ├── denied → DisabledWithPermissionDenied
  └── granted → WaitingForLocation

WaitingForLocation
  ├── fresh fix received → ReadyToShare
  ├── timeout → Error(Location.Timeout)
  └── provider unavailable → Error(Location.ProviderUnavailable)

ReadyToShare
  ↓ camera connected
SendingToCamera
  ├── success → SharingActive
  ├── camera unsupported → Error(Camera.LocationSyncUnsupported)
  ├── BLE/protocol failure → Error(...)
  └── user stops → Disabled

SharingActive
  ├── update current geoposition according to v1 cadence
  ├── camera disconnect → PausedCameraDisconnected
  ├── permission revoked → DisabledWithPermissionRevoked
  └── user stops → Disabled
```

Exact update cadence is not defined in this document and must be decided during implementation. Cadence must be conservative and battery-aware.

## Location Data Model

Conceptual model:

```kotlin
data class GeoPosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val horizontalAccuracyMeters: Float?,
    val verticalAccuracyMeters: Float?,
    val capturedAt: Instant,
    val provider: LocationProviderKind,
)
```

Validation rules:

```text
latitude must be in -90.0..90.0
longitude must be in -180.0..180.0
timestamp must be present
stale locations must be rejected or marked stale
low-accuracy locations must be user-visible if used
altitude is optional
bearing/speed are out of scope unless explicitly accepted later
```

Freshness concept:

```text
fresh:
  recent enough for active shooting

stale:
  too old to send without user-visible warning

unknown:
  timestamp missing or invalid
```

Do not hardcode freshness thresholds in this document unless the repository already defines them. Thresholds must be product-tested and documented before implementation.

## BLE / Camera Control Channel

Geoposition sharing is expected to use BLE or another camera control channel, not Wi-Fi image transfer and not MediaStore.

Rules:

```text
BLE/GATT lifecycle remains Android-owned
Rust may build camera-specific payloads
Android writes payload to characteristic/control endpoint
all writes must have timeouts
write failures must be typed
camera unsupported state must be typed
payloads must not be logged raw
```

Do not include exact GATT UUIDs or payload bytes unless they are already verified by project-owned, sanitized protocol research.

Exact service/characteristic identifiers and payload format require project-owned validation per camera/firmware before implementation.

## Camera EXIF Behavior

Frameport sends location information to the camera; the camera, if supported and configured, may embed that location into EXIF metadata during capture.

Do not promise that all cameras will embed EXIF GPS metadata.

Rules:

```text
EXIF geotagging support must be verified per camera/firmware
successful location write does not automatically prove EXIF was embedded
verification must include shooting a test image and inspecting resulting EXIF metadata
do not modify imported files to add GPS EXIF unless a separate feature is explicitly designed
do not strip or alter EXIF during import unless a separate privacy feature is added
```

Imported media containing EXIF GPS must be treated as sensitive user data.

## Foreground and Background Behavior

v1 behavior:

```text
foreground only
user-visible active state
manual enable/disable
no persistent GPS history
no background tracking
no cloud upload
```

Not allowed in v1:

```text
background location
always-on geotagging
automatic start on app launch
automatic start on camera proximity
persistent location timeline
cloud location sync
hidden location polling
```

If a foreground service is needed later:

```text
notification must be visible
notification must say geotagging/location sharing is active
notification must provide stop action where practical
service stops when feature stops
```

## Error Model

Permission errors:

```text
PermissionDenied.Location
PermissionDenied.BackgroundLocation
PermissionDenied.NotificationsIfForegroundServiceRequired
```

Android location errors:

```text
Location.ProviderUnavailable
Location.DisabledByUser
Location.Timeout
Location.NoRecentFix
Location.AccuracyInsufficient
Location.StaleFix
Location.InvalidCoordinates
```

BLE/control-channel errors:

```text
Bluetooth.GattDisconnected
Bluetooth.WriteTimeout
Bluetooth.CharacteristicNotFound
Bluetooth.OperationQueueClosed
BleProtocol.LocationPayloadUnsupported
BleProtocol.MalformedLocationPayload
BleProtocol.InvalidPayloadLength
```

Camera-side errors:

```text
Camera.LocationSyncUnsupported
Camera.LocationSyncRejected
Camera.Busy
Camera.Disconnected
Camera.ModeUnsupported
Camera.FirmwareUntested
```

Policy/privacy errors:

```text
Location.BackgroundNotAllowed
Location.CloudUploadForbidden
Location.HistoryPersistenceForbidden
Location.RedactionRequired
```

User cancellation:

```text
User.CancelledGeotagging
User.DisabledGeotagging
```

Permission, Android location, BLE transport, protocol, and camera-side failures must be distinguishable.

Location errors apply only when the user explicitly enables GPS-to-camera EXIF geotagging.

Location permission denial must not block non-geotagging workflows.

## Diagnostics

Allowed by default:

```text
geotagging enabled/disabled state
permission state
location provider category
location freshness category
accuracy bucket
last successful camera update time category
camera model
firmware version
BLE/control-channel state
typed error code
elapsed time
retry count
```

Redacted by default:

```text
precise latitude
precise longitude
altitude
raw location object
raw GATT payload
camera Wi-Fi passphrase
pairing secret
full serial number
full MAC address
private filenames
EXIF GPS values
```

Example diagnostic event:

```text
GeoShareEvent(
  state = "SendingToCamera",
  locationFreshness = "fresh",
  accuracyBucket = "medium",
  transport = "ble",
  cameraModel = "X-T5",
  error = null
)
```

Example failure event:

```text
GeoShareError(
  code = "Location.Timeout",
  permissionState = "granted",
  providerState = "enabled",
  preciseLocation = "<redacted>"
)
```

## Compatibility and Validation

Support must be claimed per:

```text
camera model
camera firmware version
Android device model
Android OS version
Frameport version/commit
BLE/control-channel behavior
shooting workflow
EXIF verification result
```

A "supported" claim requires:

```text
real hardware test
successful location acquisition
successful location write to camera
test photo captured after location write
EXIF inspected and GPS metadata confirmed
redacted diagnostic evidence
compatibility matrix update
repeatable behavior
```

Do not claim:

```text
BLE connect support implies geoposition-sharing support
successful payload write implies EXIF embedding
support on one firmware implies support on all firmware
support on one camera implies support on all Fujifilm cameras
```

Initial primary target:

```text
Fujifilm X-T5
```

Support must remain feature-by-feature and firmware-specific.

## Known Unknowns

Current unknowns:

```text
exact BLE/control-channel payload format for location sharing
whether location sharing works over BLE, Wi-Fi control channel, or both
whether camera requires pairing/registration before accepting location
whether location update must be sent once or periodically
whether camera embeds EXIF GPS immediately after receiving location
whether camera stores last known location after disconnect
whether firmware version changes location behavior
whether altitude/accuracy/timestamp are supported or ignored
whether Android location accuracy affects practical shooting workflow
whether camera settings can disable EXIF geotagging
whether EXIF verification differs between JPEG, HEIF, and RAF workflows
```

Unknowns must be resolved with project-owned hardware tests, sanitized traces, and compatibility matrix updates.

## Security and Privacy Rules

```text
Do not access location unless user explicitly enables GPS-to-camera EXIF geotagging.
Do not request background location in v1.
Do not store persistent GPS history by default.
Do not upload location data to cloud.
Do not log precise coordinates in release builds.
Do not export precise coordinates in diagnostics by default.
Do not log raw location payloads.
Do not log raw GATT payloads containing location.
Do not claim geotagging support without EXIF verification.
Do not start geotagging silently.
```

Location data is sensitive even when it is "only sent to the camera," because it may become embedded into photo EXIF and later be shared by the user.

## Testing Requirements

Android unit tests:

```text
permission denied flow
permission granted flow
location provider unavailable
location timeout
stale location rejection
accuracy bucket mapping
user stop/pause behavior
ViewModel state transitions
diagnostic redaction for GPS
non-geotagging workflows do not require location permission
```

Android instrumentation/manual tests:

```text
location permission prompt
foreground-only behavior
app background/foreground transition
provider disabled behavior
stop action behavior
no background location in v1
no location prompt before explicit geotagging opt-in
```

Rust tests:

```text
coordinate validation
timestamp validation
payload builder tests with synthetic data
invalid coordinate tests
invalid payload length tests
protocol error mapping
redaction-safe diagnostic event tests
```

Hardware tests:

```text
Fujifilm X-T5 current firmware
BLE/control-channel write validation
camera accepts/rejects location update
test image captured after update
EXIF inspected for GPS metadata
camera disconnect during geotagging
permission revoked during geotagging
Android device/OEM matrix
```

Privacy tests:

```text
no precise coordinates in release logs
no precise coordinates in diagnostic bundle by default
no persistent GPS history by default
no cloud upload
raw payload redaction
location permission not requested for non-geotagging workflows
```

## Related Documents

```text
README.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
NOTICE
docs/product/feature-scope.md
docs/security/diagnostics-redaction.md
docs/security/reverse-engineering-boundary.md
docs/protocol/bluetooth-le.md
docs/protocol/error-model.md
docs/protocol/compatibility-matrix.md
docs/adr/0001-android-rust-boundary.md
docs/adr/0003-ble-client-abstraction.md
docs/adr/0005-no-cloud-v1.md
docs/adr/0006-no-firmware-update-v1.md
```
