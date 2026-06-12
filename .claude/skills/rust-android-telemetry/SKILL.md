---
name: rust-android-telemetry
description: In-process observability discipline for the Frameport Android Rust stack — control-plane vs data-plane channel selection (android_logger vs tracing), bounded event ring choice (broadcast vs ArrayQueue), atomic counters, pull-model 1Hz polling, deterministic JSON serialization for goldens. Use when authoring or modifying local diagnostic instrumentation, the bounded event ring, the Kotlin-side diagnostics consumer, or any per-frame/per-transfer logging. Frameport has NO network analytics or cloud telemetry — this skill covers only local, on-device diagnostics.
---

# Rust Android Telemetry — Frameport (local diagnostics only)

## Purpose

Frameport is explicitly NO-telemetry (no cloud, no analytics, no background sync). This skill covers **in-process, local-only** observability: logcat wiring, atomic counters for transfer/live-view throughput, bounded event rings for the Kotlin diagnostics UI, and golden-test serialization. Nothing here leaves the device.

Logging on Android has different cost shapes than on a server:
- `liblog` JNI overhead per event makes structured logging on the per-frame path a measurable CPU bottleneck.
- Kotlin UI consumers and golden-test harnesses need access to the same event stream — channel selection determines whether a slow consumer blocks the producer.
- LMK can SIGKILL the process at any time, so any "exactly-once" delivery assumption is wrong.

This skill codifies the channel-selection and serialization rules validated on real Android devices for camera-data throughput (live-view frames, bulk transfer).

## Logging channel selection

| Channel | Use for | Forbidden for | Cost |
|---------|---------|---------------|------|
| `android_logger` (forwarding `log` crate to `__android_log_print`) | Control plane: lifecycle, errors, single-shot diagnostics. | Per-frame, per-transfer-chunk paths. | ~1 µs per event without args; ~3 µs with formatted args. JNI call into `liblog`. |
| `tracing-android` / `tracing-logcat` (`tracing::Subscriber` to logcat) | Structured control-plane events; spans for control-flow tracing. | Per-frame, per-transfer-chunk paths. | ~3 µs per `event!()` including formatting and atomic-CAS on subscriber registry. |
| `tracing-android-trace` (Perfetto backend) | Performance investigations only, NOT production. | Permanently enabled in release. | Heavy. Use only behind a debug-build feature flag. |
| Bare `AtomicU64::fetch_add` | Data-plane counters (frames, bytes transferred, drops). | Any event that isn't a count. | Single instruction on ARM64 LDADD/LDSET. |

Rule: any `tracing::event!`, `tracing::span!`, `log::info!`, or `log::debug!` inside a per-frame or per-transfer-chunk code path is REJECTED. The exception is `tracing::span!(Level::ERROR, ...)` for error paths where the slow case is acceptable.

Detection:

```bash
rg "tracing::|log::" rust/fuji-rs/crates/fuji-liveview/src/ --type rust -n \
  | grep -vE "control|lifecycle|error" \
  | head -20
```

## Bounded event ring

For control-plane events that multiple consumers must read (Kotlin diagnostics UI, golden harness), pick a primitive:

| Primitive | Topology | Backpressure on lag | Memory | Frameport use |
|-----------|----------|---------------------|--------|---------------|
| `tokio::sync::broadcast` | SPMC or MPSC; multiple receivers see every message | `Lagged(n)` error returned to slow receivers; fast receivers unaffected | Fixed ring × N receivers | **Recommended for control events (connection state, transfer progress).** |
| `crossbeam-queue::ArrayQueue` | MPMC; one receiver pulls one message | Producer's `push` returns `Err` when full; data lost silently if not checked | Fixed ring | Acceptable for single-consumer counters. |
| `tokio::sync::mpsc` | MPSC; single receiver | Producer awaits when full | Bounded with size hint | Useful when ordering across producers must be preserved. |
| `RwLock<VecDeque>` | Any | Blocks hot path on reader | Unbounded | REJECTED. |

The `Lagged(n)` from `broadcast` is the signal you need: explicit count of dropped events per slow receiver. Pattern:

```rust
let (tx, _) = tokio::sync::broadcast::channel::<DiagnosticsEvent>(1024);

// Producer (in transfer loop or session task):
let _ = tx.send(DiagnosticsEvent::TransferProgress { bytes_done, total });

// Consumer (Kotlin polling via JNI, golden harness, etc.):
loop {
    match rx.recv().await {
        Ok(event) => process(event),
        Err(RecvError::Lagged(n)) => {
            // Slow consumer dropped n events; surface via metric.
            metrics::dropped_diag_events.fetch_add(n, Ordering::Relaxed);
        }
        Err(RecvError::Closed) => break,
    }
}
```

Forbidden: `while let Ok(event) = rx.recv().await { ... }`. This swallows `Lagged` silently. See `rust-async-internals` skill for the broader anti-pattern.

## Data-plane counters

Per-direction counters (frames delivered, bytes transferred, errors) live as `AtomicU64`:

```rust
pub struct TransferCounters {
    pub frames_delivered: AtomicU64,
    pub bytes_transferred: AtomicU64,
    pub objects_imported: AtomicU64,
    pub errors: AtomicU64,
}

impl TransferCounters {
    pub fn record_frame(&self, size: usize) {
        self.frames_delivered.fetch_add(1, Ordering::Relaxed);
        self.bytes_transferred.fetch_add(size as u64, Ordering::Relaxed);
    }
}
```

Ordering: `Relaxed` is correct for counters (atomicity, no happens-before required). See `memory-model` skill for the rationale.

## Pull-model 1 Hz polling from Kotlin

A push-model where Rust calls back into Java per event creates JNI back-pressure (5–15 µs per `attach_current_thread + call_method`). Pull-model:

```rust
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_FujiBindings_jniDiagnosticsSnapshot(
    mut env: EnvUnowned<'_>,
    _thiz: JObject,
) -> JString {
    env.with_env(|env| -> jni::errors::Result<JString> {
        let snap = DiagnosticsSnapshot {
            counters: COUNTERS.read(),
            events: EVENT_BUFFER.drain(),
        };
        let json = serde_json::to_string(&snap)
            .map_err(|_e| jni::errors::Error::JavaException)?;
        env.new_string(json)
    })
    .into_outcome()
    .ok_or_throw(env, JString::default())
}
```

Kotlin polls at 1 Hz from a coroutine, parses the JSON, updates UI. Single JNI call per second regardless of event rate.

## Deterministic JSON for goldens

Goldens compare serialized diagnostics across runs. Use `serde_json::ser::PrettyFormatter` with 2-space indent AND sorted keys for stability:

```rust
use serde::Serialize;
use serde_json::ser::{PrettyFormatter, Serializer};

fn to_golden_json<T: Serialize>(value: &T) -> Result<String, serde_json::Error> {
    let mut buf = Vec::new();
    let mut ser = Serializer::with_formatter(&mut buf, PrettyFormatter::with_indent(b"  "));
    value.serialize(&mut ser)?;
    Ok(String::from_utf8(buf).expect("valid UTF-8"))
}
```

For sorted keys: `serde_json` does not sort by default. Either use `#[derive(Serialize)]` with field order = alphabetical, or use a `BTreeMap<String, T>` instead of `HashMap`, or pipe through a post-processing step that sorts.

Add to `tests/golden/scrub.json`:

```json
{
  "scrub_paths": [
    "$.events[*].timestamp_ms",
    "$.events[*].session_id",
    "$.counters.uptime_ms"
  ]
}
```

## Crash and ANR observability

`liblog` truncates long lines. For panic info: install `std::panic::set_hook` in `JNI_OnLoad` that calls `android_log_writer::write_log!()` with the full payload split into <= 4 KiB chunks. Otherwise the panic info is truncated and the root cause is invisible in logcat.

ANR (Application Not Responding) is a Kotlin-side concept; from Rust's side, the signal is `tokio runtime stalled > N seconds` measurable via a heartbeat task:

```rust
tokio::spawn(async move {
    loop {
        tokio::time::sleep(Duration::from_secs(1)).await;
        HEARTBEAT.store(SystemTime::now()
            .duration_since(UNIX_EPOCH).unwrap().as_secs(),
            Ordering::Relaxed);
    }
});
```

Kotlin's main thread polls `HEARTBEAT` via JNI; if stale > 10s, raises an ANR-precursor alert.

## Related skills

- `rust-async-internals` — `broadcast` `Lagged` handling; `JoinSet` shutdown.
- `memory-model` — `Relaxed` ordering for counters.
- `rust-android-jni` — JNI call cost; thread-naming for logcat readability.
- `diagnostics-system` — the full Frameport diagnostics pipeline (fuji-diagnostics crate, wire protocol, golden contract tests).
