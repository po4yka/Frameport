# ADR 0005: No Cloud Services in v1

## Status

Accepted

## Date

2026-06-11

## Context

Frameport's primary product value is a reliable local camera workflow: camera connection diagnostics, Wi-Fi PTP-IP image import, BLE-assisted Wi-Fi handoff later, remote shutter later, live-view later, USB mode later, local import history, and local compatibility diagnostics.

Official camera companion apps often combine two different concerns:

```text
camera-connect:
  BLE discovery
  Wi-Fi handoff
  PTP-IP
  USB
  media transfer
  remote control

server-linkage:
  account login
  cloud timeline
  equipment sync
  backup sync
  firmware metadata
  analytics
  remote APIs
```

Frameport v1 intentionally focuses only on the local camera-connect side.

This matters because cloud features increase privacy risk, require account/auth/token storage, require backend maintenance, add legal and vendor-boundary risk, can obscure the core connection/import product, and make the app harder to test, reason about, and trust. Local-first behavior is a product decision and a security boundary.

## Decision

Frameport v1 will not include mandatory or optional cloud services.

Frameport v1 will not implement:

* User accounts
* Fujifilm account login
* Cognito or OAuth flows
* Vendor API Gateway integrations
* Cloud timeline
* Equipment cloud sync
* Backup/restore cloud sync
* Cloud media upload
* Analytics or product telemetry
* Remote configuration
* Firmware metadata downloads from vendor services
* Firmware binary downloads
* Crash reporting to third-party services by default
* Hidden background network calls

Frameport v1 will be local-first:

```text
Default:
  local camera connection
  local image import
  local diagnostics
  local settings
  local import catalog

Not included:
  account
  cloud sync
  analytics
  telemetry
  vendor server linkage
```

Camera-local Wi-Fi communication is allowed because it is required for local camera workflows. Internet/cloud communication is not allowed in v1 unless it is strictly for development tooling and excluded from release builds. Any future cloud feature requires a separate ADR, privacy review, security review, and explicit user opt-in.

### Boundary Diagram

```text
Allowed in v1:

Android app
  ↓
BLE camera discovery / handoff
  ↓
Camera local Wi-Fi / USB later
  ↓
Rust PTP/PTP-IP / PTP-over-USB
  ↓
MediaStore import
  ↓
Local Room / DataStore state

Not allowed in v1:

Android app
  ↓
vendor account login
  ↓
vendor cloud API
  ↓
timeline/equipment/backup/analytics/firmware services
```

## Consequences

Positive consequences:

* Simpler threat model
* No account tokens to store
* No vendor credentials or endpoints in the repository
* No mandatory internet dependency
* Fewer permissions
* Clearer privacy story
* Less legal and licensing risk
* Easier testing
* Easier offline use
* Product focus stays on camera connection and import reliability

Negative consequences:

* No cross-device cloud timeline
* No account-based backup
* No equipment sync with vendor services
* No vendor-driven firmware notification
* No remote configuration
* No cloud analytics for product usage
* Compatibility metadata must be local, bundled, manually updated, or user-supplied until a future design exists

## Alternatives Considered

### Alternative 1: Implement vendor account login

This alternative would add account login and integrate with vendor cloud account flows.

Rejected for v1 because it requires handling auth tokens, creates vendor endpoint and credential boundary issues, is not necessary for local image import, expands the privacy/security surface, and risks making Frameport look like an unofficial vendor cloud client.

### Alternative 2: Add cloud timeline sync

This alternative would upload or sync local camera/import history to a backend.

Rejected for v1 because timeline data can reveal location, habits, devices, and filenames; it requires a backend; it adds privacy and data retention obligations; and local import history is sufficient for v1.

### Alternative 3: Add analytics by default

This alternative would collect product usage, connection failure, import success, and crash telemetry automatically.

Rejected for v1 because connection diagnostics may contain sensitive camera and device context, telemetry requires consent, redaction, retention, and backend design, and hidden telemetry conflicts with local-first trust goals.

### Alternative 4: Add optional opt-in telemetry immediately

This alternative would add telemetry but make it opt-in.

Rejected for v1 because even opt-in telemetry requires security/privacy design, redaction rules and retention policy must be implemented carefully, and the project should first establish local diagnostics and exportable support bundles.

This may be reconsidered later through a separate ADR.

### Alternative 5: Use vendor cloud firmware metadata

This alternative would fetch firmware compatibility metadata or firmware update manifests from vendor services.

Rejected for v1 because firmware-related behavior is explicitly out of scope, server endpoint use adds legal and privacy concerns, compatibility metadata can be local/manual initially, and firmware update requires a separate high-risk design.

## Explicitly Out of Scope for v1

```text
Account and identity:
  - user accounts
  - Fujifilm account login
  - Apple/Google/Facebook sign-in
  - OAuth
  - Cognito
  - account tokens

Vendor cloud:
  - vendor API Gateway calls
  - vendor timeline APIs
  - vendor equipment APIs
  - vendor backup APIs
  - vendor firmware APIs
  - vendor analytics endpoints

Project cloud:
  - Frameport-hosted user backend
  - media upload
  - cloud import history
  - cloud diagnostics upload
  - remote configuration
  - push notification backend

Telemetry:
  - automatic analytics
  - hidden crash upload
  - product usage tracking
  - background diagnostics upload

Firmware:
  - firmware binary download
  - firmware metadata download from vendor services
  - firmware transfer to camera
  - firmware flashing workflow
```

## Allowed v1 Network Behavior

Allowed:

```text
Camera-local network traffic:
  - BLE scan/connect to camera
  - GATT reads/writes for camera connection features
  - Wi-Fi network request for camera local network
  - PTP-IP traffic to camera-local endpoint
  - USB local communication later

Development-only network behavior:
  - dependency downloads through Gradle/Cargo during development
  - CI dependency resolution
  - local fake camera server tests
```

Conditionally allowed with documentation:

```text
External links opened by user action:
  - project documentation
  - issue tracker
  - privacy policy if added later
```

Not allowed in release v1:

```text
automatic internet calls
cloud sync
vendor API calls
analytics upload
hidden crash upload
remote config fetch
firmware manifest fetch
firmware binary download
diagnostic upload
```

If any release code makes an internet request, it must be explicitly documented, reviewed, and justified by a separate ADR.

## Implementation Rules

### General Rules

* Do not add account login.
* Do not add OAuth.
* Do not add Cognito.
* Do not add vendor API clients.
* Do not add cloud sync.
* Do not add analytics.
* Do not add hidden telemetry.
* Do not add remote configuration.
* Do not add firmware network integrations.
* Do not add backend dependencies.
* Do not add hardcoded vendor endpoints.
* Do not add hardcoded vendor API keys.
* Do not add copied vendor cloud configuration.

### Android Rules

* Do not add networking libraries for cloud behavior without an ADR.
* Do not add internet-facing services without an ADR.
* Keep `INTERNET` permission justified if present.
* Do not add background workers that perform external network calls.
* Do not add push notification registration.
* Do not add account manager integrations.
* Do not add sign-in UI.
* Do not add analytics SDKs.
* Do not add crash-reporting SDKs without explicit ADR and opt-in/privacy design.

### Rust Rules

* Rust SDK must communicate with local camera transports only.
* Rust must not contain vendor cloud endpoints.
* Rust must not contain auth tokens.
* Rust must not upload diagnostics.
* Rust must not fetch remote firmware metadata.
* Rust must not include cloud client logic.

### Documentation Rules

If future work proposes a cloud feature, it must add a separate ADR covering:

```text
purpose
user value
data collected
data retention
authentication
encryption
backend ownership
telemetry policy
opt-in/opt-out behavior
deletion behavior
security review
legal/licensing review
```

## Testing Implications

Static/project checks should include:

* Search for hardcoded vendor endpoints
* Search for API keys or tokens
* Review added dependencies for analytics/cloud behavior
* Review manifest for new permissions
* Review background workers/services for network behavior

Unit and integration tests should verify:

* App works without internet
* Camera-local Wi-Fi flow does not require cloud
* Import works without account
* Diagnostics can be generated locally
* No telemetry is emitted during local workflows
* No hidden background network work starts after import
* Settings do not include account state in v1

Manual tests should include:

* Run app in airplane mode with Bluetooth/Wi-Fi enabled where possible
* Connect to camera-local Wi-Fi
* Import media without internet
* Verify no account prompt appears
* Verify no cloud setup is required
* Inspect logs for absence of cloud tokens/endpoints

## Security and Privacy Implications

No account means no account token storage. No cloud means no server-side retention. No analytics means fewer hidden identifiers. No telemetry means diagnostics remain user-controlled.

Local-first design reduces exposure of filenames, EXIF, location, camera identifiers, and import history. Local diagnostics still need redaction. Camera Wi-Fi credentials and pairing secrets remain sensitive. Future cloud features would require a new security model.

Privacy defaults:

```text
Default in v1:
  no account
  no cloud
  no analytics
  no telemetry
  no background upload
  no firmware network calls
  local diagnostics only
  local import history only
```

## Future Reconsideration Criteria

Cloud features may only be reconsidered after local camera workflows are stable.

Future cloud/telemetry proposals must satisfy:

* Explicit user value
* No vendor-secret dependency
* No unofficial vendor account scraping
* Opt-in by default
* Clear privacy policy
* Clear deletion model
* Clear data retention model
* Security review
* Threat model
* Documented backend ownership
* Ability to use the app without cloud
* Separate ADR accepted before implementation

Examples of future features that might be considered only after separate ADRs:

```text
optional encrypted user-owned backup
manual diagnostic bundle upload
optional compatibility metadata update
optional crash reporting
optional release update check
```

Do not implement them in v1.

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
docs/adr/0006-no-firmware-update-v1.md
docs/security/reverse-engineering-boundary.md
docs/security/diagnostics-redaction.md
docs/product/feature-scope.md
docs/product/ux-principles.md
```
