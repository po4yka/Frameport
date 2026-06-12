---
name: diagnostics-system
description: Use when modifying the connection diagnostics pipeline, typed error states, fuji-diagnostics crate, camera/diagnostics or feature/diagnostics modules, wire-schema contracts between Rust and Kotlin, DIAGNOSTICS_ENGINE_SCHEMA_VERSION, golden contract tests, or adding a new diagnostic check type. Triggers on camera connection failures, BLE/Wi-Fi/USB diagnostics, typed error states (PermissionDenied, WifiNotConnected, ProtocolError, TransferError), fuji-diagnostics crate changes, or anything in camera/diagnostics or feature/diagnostics.
---

# Diagnostics System — Frameport

## 1. Overview

The diagnostics system is a two-tier pipeline:

- **Rust engine** (`rust/fuji-rs/crates/fuji-diagnostics/`) — executes camera-connection probes and collects structured diagnostic evidence, producing a `DiagnosticsReport` with typed `CheckResult` and `CheckObservation` values.
- **Kotlin orchestration** (`camera/diagnostics/`) — manages lifecycle, check catalog loading, JNI bridge, report enrichment, persistence, and presentation to `feature/diagnostics`.

The Rust engine is stateless per run; the Kotlin layer owns state, scheduling, and cross-run coordination.

## 2. Diagnostic Error Taxonomy

Frameport connection failures map to a typed error hierarchy used in both the Rust wire types and the Kotlin domain models:

| Error kind | Rust variant | Kotlin sealed class | Typical cause |
|------------|-------------|---------------------|---------------|
| Bluetooth permission denied | `ErrorKind::PermissionDenied` | `DiagError.PermissionDenied` | Missing `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` permission |
| Bluetooth off | `ErrorKind::BluetoothOff` | `DiagError.BluetoothOff` | Adapter disabled |
| Wi-Fi not connected | `ErrorKind::WifiNotConnected` | `DiagError.WifiNotConnected` | Not on camera's Wi-Fi hotspot |
| PTP protocol error | `ErrorKind::ProtocolError` | `DiagError.ProtocolError` | Unexpected PTP response, wrong camera state |
| PTP-IP connection failed | `ErrorKind::PtpIpConnectionFailed` | `DiagError.PtpIpConnectionFailed` | TCP connect to camera IP:port timed out or refused |
| Transfer error | `ErrorKind::TransferError` | `DiagError.TransferError` | File transfer interrupted, storage full, PTP transaction error |
| USB permission denied | `ErrorKind::UsbPermissionDenied` | `DiagError.UsbPermissionDenied` | Android USB host permission not granted |
| USB device not found | `ErrorKind::UsbDeviceNotFound` | `DiagError.UsbDeviceNotFound` | Camera not detected on USB |
| Camera busy | `ErrorKind::CameraBusy` | `DiagError.CameraBusy` | Camera in use by another app or in a state that rejects connections |

All check results carry one of these `ErrorKind` variants on failure paths. The Kotlin classifier maps them to user-facing `DiagnosisFinding` entries.

## 3. Rust Engine Pipeline

### Entry point

`DiagnosticsSession::run()` in `fuji-diagnostics/src/lib.rs` validates the wire request, spawns a worker, and calls `run_checks()`.

### Stage-based execution model

The engine uses a plan-then-execute architecture:

1. **Plan** (`engine/plan.rs`): `build_check_plan()` creates an ordered `Vec<CheckStageId>` from the requested check families.

2. **Coordinate** (`engine/runtime.rs`): `CheckCoordinator` iterates `plan.stage_order`, invoking each runner. It checks cancellation and deadline between stages.

3. **Run** (`engine/runners/`): Each runner implements `CheckStageRunner` with `id()`, `total_steps()`, and `run()`. Runners produce `RunnerOutput` (check results + observations) and call `runtime.record_step()`.

4. **Report** (`engine/report.rs`): `build_report()` assembles the final `DiagnosticsReport` from accumulated results and observations. Includes `engine_schema_version`.

### Cancellation and deadlines

`CheckRuntime` checks `is_cancelled()` (cooperative via `AtomicBool`) and `is_past_deadline()` between stages. A cancelled run still produces a partial report.

### Progress reporting

Progress flows through `SharedState` (behind `Arc<Mutex<>>`) and is polled by Kotlin via `poll_progress_json()`. The `DiagProgressWire` includes stage name and step counts.

## 4. Check Types

### Connectivity checks

| Stage ID | What it checks | Key types | Outcomes |
|----------|---------------|-----------|----------|
| `Environment` | Android permission state for BLE and USB; Wi-Fi adapter state | `PermissionSnapshot` | `permissions_ok`, `bluetooth_permission_denied`, `usb_permission_denied` |
| `BluetoothScan` | BLE advertisement from camera; checks camera is discoverable | `BleTarget` | `ble_found`, `ble_not_found`, `ble_off` |
| `WifiReachability` | TCP reachability to camera's PTP-IP endpoint (default: 192.168.0.1:15740) | `PtpIpTarget` | `ptpip_reachable`, `ptpip_timeout`, `wifi_not_connected` |
| `PtpHandshake` | Full PTP-IP session open + `GetDeviceInfo` round-trip | `PtpSessionTarget` | `ptp_ok`, `ptp_protocol_error`, `ptp_camera_busy` |
| `UsbEnumeration` | USB device enumeration; check for camera VID/PID | `UsbTarget` | `usb_found`, `usb_not_found`, `usb_permission_denied` |

### Transfer checks

| Stage ID | What it checks | Key types | Outcomes |
|----------|---------------|-----------|----------|
| `StorageInfo` | Camera storage object count and free space via `GetStorageInfo` | `StorageTarget` | `storage_ok`, `storage_full`, `ptp_protocol_error` |
| `SampleTransfer` | Transfers a single small object to verify the full transfer path | `TransferTarget` | `transfer_ok`, `transfer_error`, `transfer_cancelled` |

### How to add a new check type

#### Rust side

1. Add `ErrorKind` variant if needed in `fuji-diagnostics/src/types/error.rs`.
2. Define target type in `fuji-diagnostics/src/types/target.rs`.
3. Add `CheckTaskFamily` variant in `fuji-diagnostics/src/types/request.rs`.
4. Add `CheckStageId` variant in `fuji-diagnostics/src/engine/runtime.rs`.
5. Create runner implementing `CheckStageRunner` in `fuji-diagnostics/src/engine/runners/`.
6. Register runner in `fuji-diagnostics/src/engine/runners/mod.rs`.
7. Add stage to plan in `fuji-diagnostics/src/engine/plan.rs`.
8. Add observation mapping in `fuji-diagnostics/src/observations.rs` if structured facts are needed.
9. Update contract fixtures.

#### Kotlin side

1. Add target model in `camera/diagnostics/.../Models.kt`.
2. Mirror wire types in `camera/diagnostics/.../contract/DiagnosticsContract.kt`.
3. Add to catalog domain in `DiagnosticsCatalogDomain.kt`.
4. Update catalog rendering to serialize the new target list.
5. Add contract fixture entries and update golden tests.

## 5. Kotlin Orchestration Layer

### Call chain

```
ConnectionDiagnosticsController.startDiagnostics()
  -> DiagnosticsAdmissionService.admitRun()
  -> DiagnosticsRequestFactory.prepareRun()
  -> BridgeExecutionService.createHandle()
  -> BridgeExecutionService.start() — calls bridge.startDiagnostics(requestJson)
  -> DiagnosticsExecutionCoordinator.execute()
      -> BridgePollingService.awaitCompletion()
      -> DiagnosticsFinalizationService.finalize()
```

### Key classes

| Class | Responsibility |
|-------|---------------|
| `DefaultConnectionDiagnosticsController` | Entry point for manual diagnostics runs. |
| `DiagnosticsAdmissionService` | Guards against concurrent runs. |
| `ActiveRunRegistry` | Tracks active bridges, execution jobs, cancellation state. |
| `BridgeExecutionService` | Creates and destroys `CameraDiagnosticsBridge` (JNI). |
| `BridgePollingService` | Polls `pollProgressJson()`/`takeReportJson()` on interval. Timeout: 120s. |
| `DiagnosticsFinalizationService` | Enriches report (classifier, permission recommendations), persists results. |
| `DiagnosticsRequestFactory` | Builds wire-format request JSON from check profile + settings. |

## 6. JNI Diagnostics Bundle with Redaction

The `jniCollectDiagnosticsBundle()` export in `fuji-ffi` aggregates Rust-side diagnostic state for a support bundle. Redaction rules:

- Camera IP addresses: replaced with `<camera-ip-redacted>`.
- BLE MAC addresses: replaced with `<mac-redacted>`.
- File names and paths: replaced with `<path-redacted>` (privacy; user content).
- Error messages from OS APIs: kept verbatim (no PII in OS error strings for camera ops).
- PTP transaction IDs and object handles: kept (non-identifying, needed for diagnosis).

The Kotlin side calls `jniCollectDiagnosticsBundle()` on a background thread (not the main thread; it may block for up to 2s collecting log snapshots). Apply Rule 2 (AttachCurrentThread discipline) from the `rust-android-jni` skill.

## 7. Wire Protocol

Rust and Kotlin communicate via JSON serialization over JNI. The wire types mirror each other:

| Rust type | Kotlin type | Direction |
|-----------|-------------|-----------|
| `DiagScanRequestWire` | `DiagScanRequestWire` | Kotlin -> Rust |
| `DiagProgressWire` | `DiagProgressWire` | Rust -> Kotlin (poll) |
| `DiagScanReportWire` | `DiagScanReportWire` | Rust -> Kotlin (take) |
| `DiagCheckResultWire` | `DiagCheckResultWire` | Embedded in report |

Schema version is tracked via `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` in both Rust (`fuji-diagnostics/src/wire.rs`) and Kotlin (`camera/diagnostics/.../contract/DiagnosticsContract.kt`). Both must be equal; this is enforced by contract tests.

See `references/wire-protocol.md` for field-level details.

### Backward Compatibility Rules

1. Adding optional fields with `#[serde(default)]` / Kotlin defaults is always safe. No version bump needed.
2. Removing fields requires a schema version bump.
3. Renaming fields is a breaking change. Prefer adding the new name and deprecating the old.
4. Adding enum variants is safe with a default fallback; removing is breaking.
5. The Kotlin side deserializes with `ignoreUnknownKeys = true` so unknown fields from newer Rust versions are safely ignored.

## 8. Testing

### Golden contract tests

- **Shared fixtures** in `contract-fixtures/` — schema version, field manifests for progress and report types.
- **Rust side**: decodes all shared fixtures, verifies schema version matches.
- **Kotlin side**: decodes the same fixtures, verifies schema version, checks bundled catalog matches fixture.

### Golden file support

Set `FRAMEPORT_BLESS_GOLDENS=1` when running Kotlin tests to regenerate golden files. Diff artifacts are written to `camera/diagnostics/build/golden-diffs/`.

### Key test classes

| Test | What it verifies |
|------|-----------------|
| `DiagnosticsWireContractTest` | Field-level compatibility between Rust and Kotlin wire types |
| `DiagnosticsContractGovernanceTest` | Schema versions match, fixtures decode |
| `ConnectionDiagnosticsWorkflowTest` | Check lifecycle: start, cancel |
| `DiagnosticsRequestFactoryTest` | Wire request construction from check profiles |

### Wire compatibility verification

After any wire type change:
1. Run `cargo test -p fuji-diagnostics` — catches fixture decode failures.
2. Run `:camera:diagnostics:testDebugUnitTest` — catches field manifest and schema version mismatches.
3. If fields were added: bless goldens with `FRAMEPORT_BLESS_GOLDENS=1` and commit updated fixtures.

## 9. Logcat Capture

Logcat collection for diagnostics bundles:

| Scope | Flag | Use case |
|-------|------|----------|
| `app_snapshot` | `-T <timestamp>` | Captures logs from diagnostics run start time |

The `-T sinceTimestampMs` flag is set to the diagnostics run start time. This scopes the log capture to the relevant window and avoids including unrelated app history.

## Related skills

- `rust-android-jni` — JNI export patterns, thread-attachment discipline, hot-path data marshaling.
- `rust-android-build` — `.so` size, ELF symbol allowlist, 16 KiB alignment.
- `cargo-workflows` — workspace structure, crate dependency management.
