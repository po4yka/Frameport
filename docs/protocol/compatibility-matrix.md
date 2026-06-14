# Compatibility Matrix

Last updated: 2026-06-14

## Purpose

Frameport must track camera compatibility conservatively. Compatibility depends on camera model, firmware version, Android device model, Android version, transport path, BLE behavior, Wi-Fi PTP-IP behavior, USB behavior later, media formats, remote control support, live-view support, and feature-specific quirks.

Frameport must not claim broad Fujifilm compatibility without direct hardware evidence. The initial primary development target is Fujifilm X-T5, but even X-T5 support must be verified by firmware version and feature.

## Compatibility Policy

Compatibility claims must be backed by real hardware testing or clearly marked as assumed/unknown. "Supported" means tested on real hardware and passing the documented feature test. "Experimental" means expected to work or partially verified, but not enough for a support claim. "Unknown" means not tested. "Unsupported" means tested and known not to work, or intentionally out of scope. "Out of scope" means the feature is intentionally not implemented, even if the camera supports it.

Firmware version must be recorded when possible. Android device and OS version should be recorded for connection-related tests. Compatibility notes must not contain full serial numbers, MAC addresses, Wi-Fi passphrases, or private filenames.

Do not use this file as marketing copy. It is an engineering and support document.

## Status Definitions

| Status | Meaning | User-facing claim allowed? |
|---|---|---|
| Supported | Verified on real hardware for the listed feature and firmware version | Yes, with exact scope |
| Experimental | Partially verified or expected to work, but not enough evidence | Only as experimental |
| Unknown | Not tested | No |
| Unsupported | Tested and known not to work, or intentionally blocked | Yes, as unsupported |
| Out of scope | Not implemented by Frameport even if possible | Yes, as out of scope |

## Feature Definitions

| Feature | Definition of passing |
|---|---|
| BLE scan | Camera appears in bounded BLE scan with expected identity signal |
| BLE connect | App connects to camera GATT server and discovers required services |
| BLE identity read | App reads enough metadata to identify camera model/firmware where available |
| BLE Wi-Fi handoff | App obtains or confirms camera Wi-Fi handoff data and starts Wi-Fi connection flow |
| BLE remote shutter | App can trigger expected shutter sequence over BLE |
| GPS-to-camera EXIF geotagging | App sends current phone GPS/location data to the connected camera and a test image captured afterwards contains expected EXIF GPS metadata |
| Manual Wi-Fi connect | User can manually connect app to camera Wi-Fi path |
| Wi-Fi socket handoff | Android binds socket to camera network and passes fd to Rust successfully |
| PTP-IP session open | Rust opens camera PTP-IP session and reads basic camera info |
| Object enumeration | App lists camera media objects |
| Thumbnail fetch | App loads thumbnails for listed objects |
| JPEG import | App imports JPEG into MediaStore successfully |
| HEIF import | App imports HEIF into MediaStore successfully |
| RAF import | App imports RAF into MediaStore successfully |
| Video import | App imports video object into MediaStore successfully |
| Batch import | App imports multiple selected objects with progress and cancellation |
| Remote shutter over Wi-Fi | App triggers remote shutter through Wi-Fi/PTP-IP path |
| Live view | App displays a continuous live-view stream with acceptable stability |
| USB discovery | Android detects camera over USB host mode |
| USB PTP session | Rust/native layer opens PTP-over-USB session |
| USB import | App imports media over USB into MediaStore |
| Firmware update | Out of scope for v1 |
| Cloud/account sync | Out of scope for v1 |

## Initial Compatibility Matrix

| Camera | Firmware | BLE Scan | BLE Connect | BLE Handoff | Manual Wi-Fi | PTP-IP Session | Object List | Thumbnail | JPEG Import | HEIF Import | RAF Import | Remote Shutter | Live View | USB | Status | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Fujifilm X-T5 | Unknown / unverified | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Out of scope | Unknown | Primary first-run target. No hardware testing has been performed yet. Every feature column must be verified independently on real hardware before any status is upgraded from Unknown. Firmware version must be recorded at time of first test. |
| Other Fujifilm X/GFX cameras | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Unknown | Out of scope | Out of scope | Unknown | Do not claim support until tested on real hardware. |

## Android Reference Devices

This table records the Android devices intended for integration testing. These are not cameras. Entries here track platform-layer verification — socket handoff, PTP-IP session establishment, and import pipeline — independently of camera-model support.

| Android Device | Android Version | Wi-Fi Socket Handoff | PTP-IP Session Open | Notes |
|---|---|---|---|---|
| Google Pixel (generation TBD) | TBD | Unknown | Unknown | Intended CI and primary integration-test device. Results to be recorded after first hardware run. No specific Pixel generation or Android version confirmed yet. |

## Geotagging Compatibility Matrix

GPS-to-camera EXIF geotagging is in v1 scope, but support must be verified with a real shooting workflow. Do not mark geotagging as supported merely because BLE connects or because a location payload can be sent.

| Camera | Firmware | Android Device | Android Version | Location Permission | Camera Location Write | EXIF GPS Verified | Status | Evidence | Notes |
|---|---|---|---|---|---|---|---|---|---|
| Fujifilm X-T5 | Unknown / unverified | Unverified | Unverified | Unknown | Unknown | Unknown | Unknown | None yet | v1 feature target; verify with real shooting workflow before claiming support |
| Other Fujifilm X/GFX cameras | Unknown | Unverified | Unverified | Unknown | Unknown | Unknown | Unknown | None yet | Experimental until tested |

## Hardware Test Matrix

No verified hardware test results have been recorded in this repository yet. Add hardware test entries only after real testing with redacted evidence.

Expected columns for future entries:

| Test ID | Android Device | Android Version | Camera | Firmware | Transport | Feature | Result | Evidence | Notes |
|---|---|---|---|---|---|---|---|---|---|

## Evidence Requirements

Acceptable evidence categories:

```text
Strong evidence:
  - real hardware test with camera model and firmware version
  - Android device and OS version recorded
  - feature-specific manual test result
  - sanitized diagnostic bundle
  - project-owned packet fixture or fake-server replay where applicable

Medium evidence:
  - partial hardware test
  - open-source reference behavior
  - documented protocol assumption
  - static analysis note

Weak evidence:
  - memory
  - forum post
  - unverified assumption
  - behavior on another camera model
```

Only strong evidence can justify "Supported". Medium evidence can justify "Experimental". Weak evidence should remain "Unknown" unless explicitly marked as an assumption. Static analysis and reverse-engineering notes can guide implementation but do not equal production support. Open-source project behavior on another camera does not prove Frameport support.

## Adding a New Camera or Firmware Version

Procedure:

```text
1. Add camera model and firmware version.
2. Record Android device and Android version.
3. Run the smallest relevant feature test.
4. Record result per feature, not globally.
5. Attach or reference sanitized evidence.
6. Record quirks and failure modes.
7. Keep unsupported/out-of-scope features explicit.
8. Do not upgrade status to Supported until tests pass repeatedly.
```

Required fields for each compatibility note:

```text
Camera model:
Firmware version:
Android device:
Android version:
Frameport version/commit:
Transport:
Feature:
Result:
Evidence:
Known quirks:
Redactions applied:
```

## Known Unknowns

Current unknowns:

```text
- Exact behavior by camera firmware version.
- Whether all cameras expose the same BLE service/characteristic behavior.
- Whether Wi-Fi PTP-IP channel semantics are identical across models.
- Whether object format codes and thumbnail behavior vary across cameras.
- Whether RAF import is available through the same flow for every supported camera.
- Whether HEIF import depends on camera settings or firmware.
- Whether live-view frame format and channel behavior differ by model.
- Whether Android OEM Wi-Fi routing behavior affects camera connection reliability.
- Whether Android OEM BLE stack behavior affects reconnect reliability.
- USB behavior is not part of v1 and remains unverified.
- Firmware update is out of scope for v1.
- Cloud/account sync is out of scope for v1.
```

## Privacy and Redaction Rules

Compatibility records must not include:

* Full camera serial numbers
* Full MAC addresses
* Camera Wi-Fi passphrases
* Pairing secrets
* Private filenames
* Exact GPS coordinates
* Private packet captures
* Real user photos/videos
* Access tokens
* Vendor API keys

Allowed by default:

* Camera model
* Firmware version
* Android device model
* Android version
* Frameport version/commit
* Transport
* Typed error code
* Feature result
* Redacted notes

Redaction examples:

```text
Serial: 3AB12345 -> 3AB1****
MAC: AA:BB:CC:DD:EE:FF -> AA:BB:**:**:**:**
Filename: DSCF1234.RAF -> <redacted-filename>.RAF
Wi-Fi SSID: redact if it contains personal data
```

## Related Documents

```text
README.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
NOTICE
docs/security/reverse-engineering-boundary.md
docs/security/diagnostics-redaction.md
docs/protocol/wifi-ptp-ip.md
docs/protocol/bluetooth-le.md
docs/protocol/media-transfer.md
docs/protocol/error-model.md
docs/adr/0001-android-rust-boundary.md
docs/adr/0002-wifi-socket-fd-handoff.md
docs/adr/0003-ble-client-abstraction.md
docs/adr/0004-media-import-pipeline.md
docs/adr/0005-no-cloud-v1.md
docs/adr/0006-no-firmware-update-v1.md
```
