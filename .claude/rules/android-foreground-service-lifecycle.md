## Android foreground service lifecycle — camera session invariants

Frameport uses a foreground service exclusively for active, user-visible camera operations: PTP-IP transfer sessions, live-view streaming, and BLE-initiated connection flows. There is no VPN, no persistent background tunnel. This rule documents the lifecycle invariants that Kotlin service code and Rust JNI code MUST honor.

### Foreground service contract

`startForeground(NOTIFICATION_ID, notification)` MUST be called within 5 seconds of `onStartCommand` returning. The notification MUST be visible (no transparent/blank notifications); Android demotes the service to LMK-eligible if the notification disappears.

The service type must be declared in `AndroidManifest.xml` as `android:foregroundServiceType="connectedDevice"` (appropriate for camera communication over Wi-Fi/BLE/USB). Do not use `dataSync` or `mediaProjection` — they require different permissions and imply different OS policy.

Worker threads must set a readable name:
- pthread: `pthread_setname_np(thread, "frameport-...")`.
- tokio: `Builder::new_multi_thread().thread_name_fn(|| { /* atomic counter + "frameport-tokio-worker-N" */ })`.

Unnamed threads in logcat are a debugging tax — enforce naming in `JNI_OnLoad` or runtime construction.

### Session scope — start and stop symmetry

The foreground service MUST start only when the user initiates a camera session and MUST stop when:
- The user explicitly disconnects or exits the session screen.
- The session completes (transfer finished, live-view closed).
- An unrecoverable error terminates the session.

It is FORBIDDEN to leave the foreground service running as a background daemon between sessions. Any state that needs to survive across sessions must be persisted to the Room database or app-private storage — not kept alive in an in-memory service.

### State persistence — assume process death

Low Memory Killer (LMK) terminates the process with `SIGKILL`. NO Drop runs. NO `tokio::runtime::Runtime::shutdown_background()` runs. Any state required across a kill cycle MUST be persisted via:

- A WAL-backed store (`sled`, `sqlite` via `rusqlite`/`sqlx`) for Rust-side session state.
- Room database for Kotlin-side import history and connection preferences.
- For small state (< 1 KiB, infrequent updates): `serde_json::to_writer(BufWriter::new(...))` + explicit `fsync` after every significant transition.

`serde_json::to_writer` to a path that contains user data, with NO fsync, is FORBIDDEN. The next LMK kill discards everything written since the last fsync.

### Tokio runtime shutdown — avoid the self-deadlock

If a JNI method runs inside a tokio task (via `block_on`), and that method tries to `Runtime::shutdown_background()` on the same runtime, the runtime waits for itself to drain — deadlock. The canonical pattern:

1. `Service.onDestroy()` → JNI callback `fuji_session_shutdown()`.
2. `fuji_session_shutdown` sends a `Shutdown` command over an mpsc channel and returns immediately.
3. The tokio main loop receives `Shutdown`, completes in-flight work, drops the runtime from OUTSIDE a tokio context.
4. `JNI_OnUnload` then runs cleanly.

NEVER call `runtime.block_on(runtime.shutdown_*)` from a JNI method.

### Doze and App Standby Buckets

With Android 6+ Doze: timer-based alarms via `AlarmManager.setExactAndAllowWhileIdle` may be deferred. Frameport does not use background sync, so Doze impact is limited to active sessions that lose Wi-Fi connectivity mid-transfer. Rules:

- Persist transfer progress on every completed chunk, not on a periodic timer. A Doze-deferred timer loses partial progress; an event-driven save captures every chunk regardless of Doze.
- When connectivity is lost mid-session, surface a clear notification ("Transfer paused — camera out of range") and hold the foreground service alive only as long as reconnection is plausible (configurable timeout, default 30 s).

### Signal handling

SIGPIPE is masked in JVM-spawned threads. Tokio threads created via `pthread_create` directly do NOT inherit the mask. Either:
- Use `tokio::net::TcpStream` which sets `MSG_NOSIGNAL` on writes (Linux).
- Install a process-wide SIGPIPE handler via `nix::sys::signal::signal(Signal::SIGPIPE, SigHandler::SigIgn)` in `JNI_OnLoad`.

A panic that originates from an unhandled SIGPIPE crashes the entire process and is invisible in logcat past the JNI_OnUnload boundary.

### Android 17 memory-limiter kill detection

Android 17 introduces a per-app memory cap. A foreground camera service holding large live-view buffers is a candidate target. On startup, read recent exits to distinguish a memory-cap kill from a crash:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
    val am = context.getSystemService(ActivityManager::class.java)
    am?.getHistoricalProcessExitReasons(context.packageName, /* pid = */ 0, /* maxNum = */ 16)
        ?.filter { it.reason == ApplicationExitInfo.REASON_OTHER &&
            it.description?.contains("MemoryLimiter:AnonSwap") == true }
        ?.forEach { /* persist as a diagnostics event, keyed by (timestamp, pid) for idempotent re-scan */ }
}
```

Rules:
- Guard every call behind `SDK_INT >= R` — `ApplicationExitInfo` does not exist below API 30 and `minSdk` is 31 (already satisfied, but guard anyway for forward-compat).
- Key the recorded event by a deterministic id derived from `(timestamp, pid)` so re-scanning the same history on every later launch is idempotent.
- Do the read off the main thread (suspend / Room); failure-isolate it so a read error never affects startup.
- Implementation belongs in `camera/diagnostics`, invoked from `AppStartupInitializer.initialize()`.

### Process death simulation in tests

CI matrix MUST include `adb shell am kill <package>` mid-session and verify the next session reconstructs transfer state correctly. Without this, persistence regressions ship unnoticed.

### Cross-references

- `rust-async-internals` skill — JNI-to-async bridge canonical pattern.
- `rust-android-jni` skill — JNI panic safety and pthread setname.
- `privacy-local-first.md` rule — session state that must survive kill must not contain raw device identifiers.
- `llm-rust-prompts.md` — sentinel patterns for JNI lifetime and async shutdown errors.
