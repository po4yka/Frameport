# ADR 0006: No Firmware Update in v1

## Status

Accepted

## Date

2026-06-11

## Context

Firmware update is materially different from normal camera companion workflows. Frameport v1 focuses on local camera workflows: camera connection diagnostics, Wi-Fi PTP-IP image import, BLE-assisted Wi-Fi handoff later, remote shutter later, live-view later, USB import later, local import catalog, and local compatibility diagnostics.

Firmware update may involve discovering firmware availability, fetching firmware metadata, downloading firmware binaries, verifying firmware file integrity, transferring firmware data to camera, asking the camera to enter update mode, monitoring camera-side update status, handling interruption, low battery, disconnect, corrupted file, wrong model, wrong region, or version mismatch, avoiding camera bricking, and handling user support and liability if something goes wrong.

Firmware update has a higher safety and liability profile than image import or remote control. Frameport should first prove stable camera connection, import, diagnostics, and protocol handling before entering firmware update territory.

## Decision

Frameport v1 will not implement firmware update.

Frameport v1 will not:

* Download firmware metadata from vendor services
* Download firmware binaries
* Verify firmware binary hashes
* Transfer firmware data to camera
* Trigger camera firmware update mode
* Implement firmware flashing UI
* Implement background firmware checks
* Cache firmware binaries
* Parse vendor firmware manifests
* Notify users about available firmware updates from remote services

Frameport v1 may implement read-only firmware-related behavior:

* Read camera firmware version if available through camera protocol
* Display firmware version in camera diagnostics
* Record firmware version in local compatibility matrix
* Show local compatibility notes based on verified project data
* Show generic advice such as "check the official manufacturer website/app for firmware updates" without linking to vendor update automation
* Use firmware version as a diagnostic field when troubleshooting connection/import behavior

Firmware update is not a hidden future task inside v1. It is excluded until a separate ADR, threat model, safety model, legal review, and hardware recovery plan exist.

### Boundary Diagram

```text
Allowed in v1:

Camera
  ↓ read-only metadata
Frameport
  ├── firmware version display
  ├── compatibility diagnostics
  └── local compatibility matrix

Not allowed in v1:

Frameport
  ↓
firmware manifest fetch
  ↓
firmware binary download
  ↓
firmware hash verification
  ↓
firmware transfer to camera
  ↓
camera firmware update / flashing
```

## Consequences

Positive consequences:

* Avoids bricking risk in v1
* Avoids firmware binary handling
* Avoids vendor firmware endpoint/legal boundary
* Avoids requiring firmware CDN integrations
* Avoids storing large firmware files
* Keeps v1 focused on import and connection reliability
* Reduces support burden
* Simplifies security model
* Avoids user trust damage from failed updates
* Avoids misleading users into thinking Frameport is an official firmware tool

Negative consequences:

* Users cannot update camera firmware through Frameport
* Frameport cannot provide official firmware availability notifications
* Compatibility issues caused by outdated firmware must be handled through diagnostics and documentation
* Users must rely on official firmware update mechanisms
* Some camera behavior may vary by firmware and require manual compatibility notes

## Alternatives Considered

### Alternative 1: Full firmware update support in v1

This alternative would have Frameport download firmware metadata, download binaries, verify hashes, transfer firmware to camera, and guide the update.

Rejected because it is too high risk for an initial release, requires robust safety design, requires legal/licensing review, requires extensive hardware testing, a failed update may brick or disable a camera, and firmware update is not necessary for first product value.

### Alternative 2: Firmware metadata checks only

This alternative would have Frameport check remote firmware metadata and notify users about updates without transferring firmware.

Rejected for v1 because it still requires remote endpoint integration, still creates a vendor/server-linkage boundary, still requires data freshness and correctness guarantees, may mislead users if metadata is wrong or outdated, and conflicts with the no-cloud/no-vendor-network v1 decision.

### Alternative 3: Manual firmware file transfer

This alternative would have the user download firmware manually, select a local file, and use Frameport to transfer it to the camera.

Rejected for v1 because wrong file/model/version risk remains, transfer interruption risk remains, user-selected firmware files require validation, camera-side update behavior still creates bricking/liability risk, and support burden remains high.

### Alternative 4: Hidden experimental firmware update behind debug flag

This alternative would implement firmware update for internal testing only.

Rejected for v1 because debug-only high-risk code can leak into release, high-risk protocol paths need separate architecture and safety review, and hidden firmware code increases maintenance and security surface.

### Alternative 5: Read-only firmware version display

This alternative reads and displays the currently installed camera firmware version.

Accepted for v1 because it is read-only, helps compatibility diagnostics, does not transfer firmware files, does not trigger update mode, and does not require vendor cloud integration.

## Explicitly Out of Scope for v1

```text
Firmware network behavior:
  - firmware manifest fetch
  - firmware update check
  - firmware binary download
  - firmware CDN integration
  - remote firmware metadata sync

Firmware file handling:
  - firmware binary storage
  - firmware binary parsing
  - firmware hash verification pipeline
  - firmware cache cleanup
  - user-selected firmware file transfer

Camera update behavior:
  - entering firmware update mode
  - firmware transfer to camera
  - firmware flashing workflow
  - firmware update progress monitoring
  - update result code handling
  - firmware rollback/recovery flow

UI:
  - firmware update screen
  - update available prompt
  - background update notification
  - firmware download progress
  - firmware transfer progress
```

## Allowed v1 Firmware-Related Behavior

Allowed:

```text
Read-only camera metadata:
  - camera firmware version
  - camera model
  - protocol version if available
  - firmware version in diagnostics
  - firmware version in compatibility matrix

User guidance:
  - generic warning that outdated firmware may affect compatibility
  - instruction to use official firmware update mechanisms
  - link to official support only if a later documentation/privacy review allows external links

Local compatibility:
  - project-owned compatibility notes
  - verified hardware/firmware test matrix
  - local warnings for known unsupported firmware behavior
```

Not allowed:

```text
Remote firmware lookup
Firmware download
Firmware transfer
Firmware flashing
Vendor firmware API integration
Background firmware checking
```

## Implementation Rules

### General Rules

* Do not implement firmware update in v1.
* Do not add firmware update UI.
* Do not add firmware download code.
* Do not add firmware manifest parsing.
* Do not add firmware transfer commands.
* Do not add firmware update background workers.
* Do not add firmware CDN endpoints.
* Do not add firmware binary fixtures.
* Do not add firmware binaries to tests.
* Do not add vendor firmware metadata to the repository.

### Android Rules

* Do not add firmware update screens.
* Do not add firmware update notifications.
* Do not add firmware background services.
* Do not add firmware update WorkManager jobs.
* Do not add firmware download code.
* Do not add file pickers for firmware files.
* Do not add permissions only needed for firmware update.
* Do not add release UI copy that implies Frameport can update firmware.

### Rust Rules

* Rust SDK must not implement firmware transfer commands in v1.
* Rust SDK must not parse firmware binaries in v1.
* Rust SDK must not expose firmware update APIs in v1.
* Rust SDK may expose read-only camera firmware version if available through normal camera info.
* Rust SDK must not include firmware test binaries.
* Rust SDK must not include vendor firmware metadata.

### Documentation Rules

Documentation may mention firmware only to clarify:

* v1 does not support firmware update
* firmware version may be used for compatibility diagnostics
* users should use official update mechanisms
* firmware update requires a future ADR

Do not document implementation instructions for firmware flashing in v1 documentation.

### API Naming Rules

Avoid adding public APIs with names such as:

```text
downloadFirmware(...)
transferFirmware(...)
startFirmwareUpdate(...)
enterFirmwareUpdateMode(...)
checkFirmwareUpdate(...)
```

Allowed read-only names:

```text
getCameraFirmwareVersion(...)
readCameraInfo(...)
recordFirmwareCompatibility(...)
```

## Failure Modes

Allowed diagnostic states:

```text
Firmware.VersionUnknown
Firmware.VersionReadFailed
Firmware.VersionUnsupportedForFeature
Firmware.VersionUntested
Firmware.VersionKnownCompatible
```

Forbidden v1 states because they imply update support:

```text
Firmware.UpdateAvailable
Firmware.Downloading
Firmware.DownloadFailed
Firmware.Transferring
Firmware.Flashing
Firmware.UpdateSucceeded
Firmware.UpdateFailed
Firmware.RollbackRequired
```

Compatibility diagnostics must not become firmware update prompts.

## Testing Implications

Allowed tests:

* Reading camera firmware version
* Showing firmware version in diagnostics
* Storing firmware version in compatibility matrix
* Warning when firmware is untested for a feature
* Ensuring import and connection work without firmware network calls
* Ensuring no firmware update UI exists
* Ensuring no firmware background work exists

Static/project checks:

* Search for firmware download endpoints
* Search for firmware binary fixtures
* Search for update-mode APIs
* Search for vendor firmware URLs
* Review manifest/services/workers for firmware behavior
* Review release UI for unsupported update claims

Manual tests:

* Connect to camera and display firmware version if available
* Use app with outdated/unknown firmware without update prompts
* Verify user is not directed into a firmware workflow
* Verify no firmware network traffic occurs

## Security and Privacy Implications

Firmware update can brick hardware if interrupted or incorrect. Firmware files are large and security-sensitive. Firmware manifests/endpoints may create vendor dependency and legal boundary. Firmware update status may encourage users to trust unofficial update flows.

Frameport v1 avoids these risks by excluding firmware update. Read-only firmware version is lower risk but still should be treated as device metadata. Firmware version can be included in diagnostics because it helps compatibility, but should not be combined with full serial numbers by default.

Allowed by default:

```text
camera model
firmware version
feature compatibility status
typed diagnostic code
```

Redacted or forbidden by default:

```text
full serial number
camera MAC address
firmware binary filename from vendor source
firmware download URL
firmware hash from vendor manifest
raw firmware file data
```

## Future Reconsideration Criteria

Firmware update may only be reconsidered after core local workflows are stable.

A future firmware update proposal must include:

```text
separate ADR
threat model
safety model
hardware recovery plan
battery/precondition checks
wrong-model prevention
version compatibility validation
file integrity validation
interruption handling
user confirmation UX
legal/licensing review
vendor endpoint policy
test matrix
support plan
rollback/recovery limitations
clear disclaimer
```

Minimum future requirements before implementation:

* Multiple physical camera tests
* Repeatable transfer reliability
* Documented recovery behavior
* No use of proprietary firmware metadata without permission
* No firmware binary redistribution unless legally allowed
* Explicit user consent
* No background/automatic flashing
* Clear unsupported-state handling

Do not implement any of this in v1.

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
docs/security/reverse-engineering-boundary.md
docs/security/diagnostics-redaction.md
docs/product/feature-scope.md
docs/protocol/compatibility-matrix.md
```
