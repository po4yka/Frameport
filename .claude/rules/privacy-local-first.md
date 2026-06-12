## Privacy — local-first invariants

Frameport is explicitly local-first: no cloud backend, no user account, no analytics SDK, no telemetry pipeline, no background sync in v1. This rule captures the invariants that every agent and contributor must honor.

### Hard invariants

1. **No outbound network calls from app code to Frameport-operated servers.** The only outbound connections are to the user's own Fujifilm camera (PTP-IP over Wi-Fi, BLE, USB). Any HTTP/HTTPS call in app code to a non-camera host is a bug.
2. **No analytics or telemetry SDK.** Do not add Firebase Analytics, Crashlytics, Sentry, Datadog, Mixpanel, Amplitude, or any equivalent. This includes transitive dependencies that phone home silently.
3. **No account or identity requirement.** No login, no OAuth flow, no device registration against a remote service.
4. **No background sync.** WorkManager tasks, JobScheduler jobs, and foreground services are permitted only during an active, user-initiated camera session. They MUST stop when the user ends the session.
5. **Diagnostic data stays on-device.** `camera/diagnostics` module writes logs and diagnostics to app-private storage only. No upload path. The user may share a diagnostic export manually; the app never transmits it automatically.

### Data stored on-device — handling rules

- Camera media (photos, videos, RAW files) imported from the camera are written to MediaStore under the standard `DCIM/Frameport/` path. They are the user's files; the app must not read, copy, or process them outside an explicit user action.
- PTP session state, connection preferences, and import history are stored in app-private storage (`context.filesDir` / Room database). Never write them to a world-readable path.
- Diagnostic logs must not contain: camera serial numbers in plain text, device IMEI/IMSI, Wi-Fi SSID/BSSID in plain text, Bluetooth device addresses in plain text, or any file path that includes the user's real name or account name. If any of these must appear in a diagnostic for debugging value, hash the value (SHA-256) first and log the hash.

### Play Store Data Safety implications

Because Frameport collects no personal data and transmits nothing to Frameport servers, the Data Safety declaration must remain minimal. Before each release, audit:

```bash
grep -rE 'analytics|crashlytics|firebase|sentry|datadog|mixpanel|amplitude|flurry' \
    app/src/ feature/ core/ camera/ --include='*.kt' --include='*.kts' --include='*.toml'
```

Any hit outside a comment or test is a blocker.

### Diagnostic-redaction gate for Rust code

In `fuji-diagnostics` and any crate that emits structured log events, apply this pattern:

```rust
// Do this:
tracing::debug!(session_id = %session_hash, "PTP session opened");

// Never this:
tracing::debug!(serial = %camera_serial, ip = %camera_ip, "PTP session opened");
```

Log the session hash, not raw identifiers. Raw identifiers (camera serial, IP, BLE address) may appear at `tracing::trace!` level only when the `frameport-dev-logs` feature flag is enabled — this flag must not ship in release builds.

### Agent checklist before adding any new dependency

- Does it make outbound network calls? If yes, block it.
- Does it collect device identifiers? If yes, block it.
- Does it add a background service or JobScheduler registration? If yes, scope it to active camera sessions only.

### Cross-references

- `android-foreground-service-lifecycle.md` — foreground service scoping for active camera sessions.
- `llm-rust-prompts.md` — sentinel pattern for forbidden identifiers in AI-generated log calls.
- `golden-bless-discipline.md` — goldens must not contain real device identifiers.
