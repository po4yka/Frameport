# Security Policy

Frameport is a local-first Android camera companion app. Its security model is based on minimizing data collection, avoiding mandatory cloud services, keeping camera communication explicit and user-visible, and treating camera credentials, device identifiers, packet captures, and diagnostics as sensitive data.

Frameport is unofficial and independent. Contributors must avoid using proprietary vendor secrets, extracted binaries, official app assets, vendor cloud credentials, APK-derived credentials, firmware URLs, proprietary SDK references, or extracted binary details.

## Supported Versions

Frameport is currently in early development.

| Version / Branch | Supported | Notes |
|---|---:|---|
| `main` | Yes | Active development branch. Security reports are accepted. |
| Releases | TBD | Release support policy will be defined after the first public release. |

Until the first release, security fixes land on `main`.

## Reporting a Vulnerability

If GitHub Security Advisories are enabled for this repository, use the private advisory flow. If private advisories are not enabled, contact the maintainer using the preferred private channel listed in the repository profile or project metadata.

Do not open public issues for vulnerabilities that expose secrets, camera identifiers, bypasses, packet captures, exploit details, or private user data. Do not include live credentials, full serial numbers, Wi-Fi passphrases, access tokens, private packet captures, or private location data in public reports.

A useful vulnerability report should include:

* Affected version or commit
* Android device and Android version
* Camera model and firmware version if relevant
* Reproduction steps
* Expected behavior
* Actual behavior
* Logs or diagnostic bundle with sensitive fields redacted
* Impact assessment
* Whether physical camera access is required
* Whether user interaction is required

## Response Expectations

Maintainers should acknowledge valid private reports when possible, triage severity, request missing reproduction details if needed, avoid public disclosure until a fix or mitigation is available, credit reporters if they want credit, and publish advisories for confirmed vulnerabilities where appropriate.

No exact response time is promised while the project is early-stage.

## Security Scope

In-scope areas include:

* Android permission handling
* Bluetooth LE scanning, pairing, GATT reads and writes, and notifications
* Wi-Fi network handoff and routing
* Socket binding and file descriptor handoff
* Rust PTP/PTP-IP protocol parsing
* Rust binary packet parsing
* MediaStore import pipeline
* File descriptor ownership and cleanup
* Local database and settings storage
* Diagnostics generation and redaction
* Foreground service lifecycle
* Native crash safety
* JNI boundary safety
* Secret handling
* Compatibility metadata stored locally

Out-of-scope or currently unsupported areas include:

* Fujifilm cloud account flows, because Frameport v1 does not implement them
* Firmware update and firmware flashing, because Frameport v1 does not implement them
* Vendor backend security
* Official Fujifilm XApp vulnerabilities
* Vulnerabilities requiring modified proprietary camera firmware
* Attacks requiring malicious changes outside this repository
* Social engineering of maintainers or users
* Denial-of-service reports without practical security impact

## Sensitive Data Classification

When uncertain, contributors should treat data as sensitive.

### Highly Sensitive

Highly sensitive data must not be logged, committed, exported, or shared unless there is a specific reviewed security process for doing so.

* Camera Wi-Fi passphrases
* Pairing keys or pairing secrets
* Access tokens or future account tokens
* Private packet captures containing credentials or identifiers
* Precise GPS/location traces
* Unredacted diagnostic bundles
* Signing keys
* Release keystore files
* CI secrets
* Full camera serial numbers
* Full MAC addresses or stable hardware identifiers

### Sensitive

Sensitive data may be useful for diagnostics but must be minimized, redacted where practical, and handled with care.

* Camera model plus firmware plus serial prefix combinations
* Android device model plus OS version plus camera connection timeline
* Filenames and media metadata
* EXIF data
* Import history
* Local database exports
* Crash dumps
* Native traces
* BLE advertisement dumps

### Usually Non-Sensitive

Usually non-sensitive data can be included in ordinary diagnostics when it is not combined with identifiers or secrets.

* App version
* Anonymized session ID
* Generic camera model without serial number
* Generic firmware version without serial number
* Typed protocol error code
* Operation timing metrics
* Redacted logs

## Data Handling Rules

Never commit secrets. Never commit camera Wi-Fi passwords, access tokens, full camera serial numbers, private MAC addresses unless anonymized, packet captures unless sanitized and explicitly intended as test fixtures, real user photos or private media files, GPS traces, release keystores, proprietary firmware binaries, official app binaries, extracted native libraries, vendor API keys, or vendor credentials.

Store only the minimum data needed for the feature. Prefer local-only storage, opt-in diagnostics, redaction before logging, and redaction before exporting diagnostics.

## Diagnostic Redaction Policy

| Data | Default handling |
|---|---|
| Camera Wi-Fi passphrase | Never log; never export |
| Pairing key / secret | Never log; never export |
| Access token | Never log; never export |
| Full camera serial number | Redact by default |
| Camera MAC address | Redact by default |
| Phone MAC address / hardware ID | Never collect intentionally |
| Precise GPS | Redact by default |
| File names | Redact by default unless user opts in |
| EXIF metadata | Redact by default unless user opts in |
| Android model | Allowed |
| Android version | Allowed |
| App version | Allowed |
| Camera model | Allowed |
| Firmware version | Allowed |
| Protocol error code | Allowed |
| Timing metrics | Allowed |

Redaction examples:

```text
Serial: 3AB12345 -> 3AB1****
MAC: AA:BB:CC:DD:EE:FF -> AA:BB:**:**:**:**
Wi-Fi SSID: may be redacted if it contains personal data
Filename: DSCF1234.RAF -> <redacted-filename>.RAF
Location: 41.7151, 44.8271 -> <redacted-location>
```

## Logging Rules

Logs must be structured, use typed error codes, and avoid secrets. Logs must not include raw packet payloads by default. Debug packet logs must be behind explicit developer or debug build gates, and packet dumps must be sanitized before sharing.

JNI and Rust errors must be mapped to safe Kotlin/domain errors. Native panic messages must not leak sensitive buffers. Logs should preserve enough information for debugging without exposing private data.

Recommended log fields:

* App version
* Session ID
* Camera model
* Firmware version
* Android version
* Transport type
* Connection state
* Operation name
* Elapsed time
* Retry count
* Typed error code

Forbidden log fields:

* Wi-Fi passphrase
* Pairing secret
* Full serial number
* Full MAC address
* Raw token
* Precise location
* Raw media path
* Raw packet payloads in release builds

## Local Storage Rules

Use Room for structured non-secret state and Proto DataStore for app settings. Use encrypted storage for secrets if any secrets are cached.

Do not store camera Wi-Fi passphrases unless there is an explicit product decision. Do not store precise location unless the user explicitly enables a feature that requires it. Do not store account tokens in v1 because v1 has no account flow. Do not store firmware binaries because v1 has no firmware update.

Provide deletion paths for local diagnostic data.

## Network Security Rules

Frameport v1 should not have hidden cloud behavior.

Network rules:

* No mandatory account
* No mandatory cloud backend
* No analytics by default
* No hidden telemetry
* No vendor cloud API integrations in v1
* No app-wide cleartext traffic
* Any future network client must be documented
* Any future telemetry must be explicit opt-in
* Any future remote endpoint must be reviewed and documented
* Camera-local Wi-Fi communication is separate from internet communication and must be clearly scoped

Local camera communication may use camera-specific protocols over local Wi-Fi, but this does not justify enabling cleartext traffic app-wide.

## Bluetooth LE Security Rules

BLE scan results can reveal nearby devices and must be handled carefully. Do not infer or store physical location from BLE scans. Use `neverForLocation` only if the app does not derive location from scan results.

Do not log full BLE advertisement payloads in release builds. Do not log pairing secrets.

Only one GATT operation must be in flight at a time. Every GATT operation must have a timeout, and pending operations must be cancelled on disconnect.

BLE reconnect must be user-visible when it requires foreground service behavior. Background BLE behavior must be documented and minimized.

## Wi-Fi Security Rules

Camera Wi-Fi credentials are sensitive. Do not log passphrases, and do not persist passphrases unless there is an explicit product decision.

Route camera traffic through the Android `Network` selected for the camera. Do not assume camera endpoints are reachable from the default network. Clearly release network bindings after session close.

Do not send camera traffic to non-camera endpoints. Do not mix vendor cloud behavior with local camera Wi-Fi behavior.

## JNI / Native Security Rules

No Rust panic may cross the JNI boundary. All Rust errors must be converted into typed Kotlin errors.

File descriptor ownership must be explicit. Do not double-close file descriptors, and do not leak file descriptors on cancellation.

Do not trust camera-provided packet lengths. Validate binary packet sizes before allocation, use bounded buffers, avoid unbounded queues, and avoid per-frame allocation in live-view hot paths.

Fuzz binary parsers where practical. Treat malformed camera packets as expected input, not impossible states.

## Reverse-Engineering and Interoperability Policy

Allowed:

* Studying clean-room interoperability notes
* Studying open-source projects under their licenses
* Testing with personally owned hardware
* Creating original protocol implementations
* Creating sanitized packet fixtures
* Documenting assumptions and unknowns

Forbidden:

* Copying proprietary Fujifilm code
* Redistributing APKs
* Redistributing extracted native libraries
* Copying official app assets
* Copying official app strings
* Copying vendor credentials
* Using vendor API keys
* Committing firmware binaries
* Adding official SDK binaries without explicit license compliance
* Using cloud endpoints or tokens extracted from official apps

Contributors should keep Frameport's implementation clean-room and original.

## Firmware Update Security Policy

Frameport v1 must not implement firmware update.

Allowed in v1:

* Display camera firmware version
* Show compatibility warnings
* Record firmware version in compatibility matrix
* Document unsupported firmware behavior

Forbidden in v1:

* Firmware binary download
* Firmware transfer to camera
* Firmware flashing
* Firmware CDN integrations
* Firmware hash verification pipeline
* Firmware update UI

Firmware update has high bricking and liability risk and requires a separate security design.

## Dependency Security

Prefer well-maintained dependencies, pin versions through normal Gradle and Rust mechanisms, avoid unnecessary dependencies, and review dependencies that touch Bluetooth, Wi-Fi, USB, cryptography, storage, networking, or native code.

Run dependency update checks when configured. Keep license compatibility in mind. Do not add closed-source binary dependencies without explicit review.

## Build and Release Security

Release signing keys must never be committed. Keystore files must not be stored in the repository. CI secrets must stay in the CI secret store.

Debug builds must not be confused with release builds. Debug logging must not be enabled in release builds. Native symbols and crash reporting behavior should be reviewed before public release.

Reproducible build documentation can be added later.

## Security Checklist for Pull Requests

- [ ] No secrets committed
- [ ] No proprietary vendor code/assets/binaries added
- [ ] No hidden cloud/network behavior added
- [ ] No new permissions without documentation
- [ ] No raw packet logging in release code
- [ ] Diagnostics redact sensitive fields
- [ ] File descriptor ownership is documented
- [ ] Native errors are typed and safely mapped
- [ ] User-visible foreground/background behavior is documented
- [ ] Tests were added or updated where practical
- [ ] Documentation was updated for security-relevant changes

## Incident Handling

If a secret is accidentally committed:

* Consider it compromised
* Remove it from the repository
* Rotate it if applicable
* Document the incident privately first
* Avoid relying only on git history rewriting
* Notify users if user data or release security is affected

If diagnostic data is accidentally exposed:

* Remove public access
* Assess scope
* Notify affected parties if applicable
* Add or improve redaction tests

## Contact

Use the repository's configured private security reporting channel if available. If it is not available, contact the maintainer through the private contact method listed on the repository or maintainer profile. Do not disclose sensitive details in public issues.
