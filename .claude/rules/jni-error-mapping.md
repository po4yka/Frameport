---
name: jni-fd-and-error-mapping
description: Use when authoring or reviewing any #[unsafe(no_mangle)] pub extern "system" function in fuji-ffi, adding a new FujiError variant, defining or changing a Kotlin exception class in dev.po4yka.frameport.nativebridge, or deciding how JniNativeFujiSdk catches and converts native errors into the diagnostics domain model (PermissionDenied/WifiNotConnected/ProtocolError/TransferError). Cross-links: rust-android-jni skill (panic containment template, local-ref frame discipline) and diagnostics-system skill (DIAGNOSTICS_ENGINE_SCHEMA_VERSION wire contract).
---

# Rule: Rust-to-Kotlin Typed Error Mapping at the JNI Boundary

## Scope

Applies to every `#[unsafe(no_mangle)] pub extern "system"` entry point in `rust/fuji-rs/crates/fuji-ffi` and the corresponding Kotlin catch/map logic in `JniNativeFujiSdk` (`dev.po4yka.frameport.nativebridge`).

Note on syntax: Rust edition 2024 (used in this workspace) requires `#[unsafe(no_mangle)]` and the calling convention for JNI exports must be `extern "system"` (not `extern "C"`). The two forms are ABI-equivalent on Android/Linux but `extern "system"` is the correct, idiomatic choice for JNI.

## 1 — Panic containment at every JNI entry point

Every JNI entry point body must be wrapped in `std::panic::catch_unwind`. On `Err(_)`, return the error sentinel immediately. Throwing a typed JNI exception from the panic path is optional but requires a live, non-pending `JNIEnv`.

```rust
use std::panic::{catch_unwind, AssertUnwindSafe};
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_NativeFujiJni_nativeInitialize(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    catch_unwind(native_initialize).unwrap_or(ERR_PANIC) as jint
}
```

The current `fuji-ffi` implementation uses integer return codes (0 = OK, negative = error) decoded by `JniNativeFujiSdk.toUnitResult()` on the Kotlin side. This is valid for the current scaffold; see Section 2 for the target typed-exception path.

Never use `extern "C-unwind"` for JNI exports — the JVM does not understand Rust unwinding.

## 2 — Target design: thiserror variants → distinct typed exception classes (not yet implemented)

When `fuji-ffi` grows real protocol operations, the current integer-code scheme should be replaced with typed JNI exceptions. The actual `FujiError` enum in `fuji-core` has these variants (as of the current scaffold):

```rust
pub enum FujiError {
    InvalidInput(&'static str),
    NotInitialized,
    NotImplemented(&'static str),
    SessionNotFound(i64),
    ProtocolUnavailable(&'static str),
    TransportUnavailable(TransportKind),
}
```

A future `throw_typed` helper in `fuji-ffi` should map these to JNI exception classes. Use slash-separated class names — dot-separated names cause `FindClass` to return null silently. Example skeleton (verify class names against actual Kotlin exception hierarchy when implemented):

```rust
fn throw_typed(env: &JNIEnv, err: &FujiError) {
    let class = match err {
        FujiError::NotInitialized | FujiError::SessionNotFound(_) =>
            "dev/po4yka/frameport/nativebridge/error/NativeException",
        FujiError::ProtocolUnavailable(_) | FujiError::TransportUnavailable(_) =>
            "dev/po4yka/frameport/nativebridge/error/NativeException",
        FujiError::InvalidInput(_) | FujiError::NotImplemented(_) | _ =>
            "dev/po4yka/frameport/nativebridge/error/NativeException",
    };
    // Clear any already-pending exception before throwing a new one.
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
    }
    let _ = env.throw_new(class, err.to_string());
    // Return immediately after this call — make no further JNI calls.
}
```

The `#[error("...")]` attributes on `FujiError` variants (via `thiserror 2.x`) produce the message string forwarded to `throw_new`. Note: in jni 0.21, `JNIEnv::throw_new` takes `&self` (shared reference); `mut env` is not required.

When adding exception classes, create them under `dev.po4yka.frameport.nativebridge` (or a `.error` sub-package) and verify the slash-separated path used in Rust matches the Kotlin package exactly.

## 3 — Return sentinel immediately after throw

After any `throw_new` / `throw_typed` call, return a zero/null sentinel. Make no further JNI calls while an exception is pending — only `exception_check`, `exception_clear`, `exception_occurred`, `exception_describe`, and ref-deletion calls are safe in that state.

## 4 — Current integer-code contract

The existing `fuji-ffi` / `JniNativeFujiSdk` contract uses integer return codes:

- `0` = success
- `-1` = ERR_NOT_INITIALIZED
- `-2` = ERR_INVALID_SESSION
- `-100` = ERR_PANIC (returned when `catch_unwind` catches a panic)

`JniNativeFujiSdk` decodes these via `toUnitResult(operation: String)` which wraps non-zero codes in `Result.failure(IllegalStateException(...))`. When migrating to typed exceptions, this helper and the Kotlin catch sites must be updated together.

## 5 — Kotlin error propagation in JniNativeFujiSdk

`JniNativeFujiSdk` currently catches `UnsatisfiedLinkError` and `SecurityException`, falling back to `NoOpNativeFujiSdk`. ViewModels receive typed domain errors through the `NativeFujiSdk` interface — never raw JNI exceptions or integer codes directly.

When typed exception classes are introduced in `dev.po4yka.frameport.nativebridge` (e.g. a future `NativeBridgeError` sealed hierarchy), `JniNativeFujiSdk` should catch each subclass and convert to the diagnostics domain model (PermissionDenied / WifiNotConnected / ProtocolError / TransferError) as defined in the **diagnostics-system** skill and the `DIAGNOSTICS_ENGINE_SCHEMA_VERSION` wire contract.

## 6 — panic=abort guard

If `[profile.release]` or `[profile.android-jni]` in `rust/fuji-rs/Cargo.toml` sets `panic = "abort"`, `catch_unwind` becomes a no-op and panics abort the process. Add a prominent comment at that profile block:

```toml
# WARNING: panic = "abort" disables catch_unwind in fuji-ffi JNI entry points.
# The panic-containment safety net is inactive; any panic aborts the JVM process.
# Review fuji-ffi/src/lib.rs before enabling.
```

Verify against the active `[profile.android-jni]` block before enabling size-optimization flags.

## Key pitfalls

- **`extern "C"` instead of `extern "system"`** — use `extern "system"` for all JNI exports on Android.
- **`#[no_mangle]` instead of `#[unsafe(no_mangle)]`** — Rust edition 2024 requires the `unsafe(...)` form.
- **Dot-separated class names in Rust** — `FindClass` silently returns null; always use slash-separated paths (e.g. `dev/po4yka/frameport/nativebridge/error/NativeException`).
- **Continuing after throw_new** — undefined JVM behavior; return the sentinel immediately.
- **`mut env` for `throw_new`** — jni 0.22 `JNIEnv::throw_new` takes `&self`; no mutable borrow needed.
- **Not clearing a pending exception** before calling `throw_new` — the second throw is silently ignored; call `env.exception_clear()` first.

## References

- `std::panic::catch_unwind` — https://doc.rust-lang.org/std/panic/fn.catch_unwind.html
- FFI and Unwinding, The Rustonomicon — https://doc.rust-lang.org/nomicon/ffi.html
- jni 0.22.x `JNIEnv::throw_new`, `exception_check`, `exception_clear` — https://docs.rs/jni/0.22.4/jni/struct.JNIEnv.html
- jni::errors (Exception struct, Error enum) — https://docs.rs/jni/latest/jni/errors/
- JNI Tips — Exception Handling (Android NDK) — https://developer.android.com/training/articles/perf-jni
- Frameport local: `docs/rust/error-handling.md`, `docs/rust/ffi-boundary.md`
- Cross-links: **rust-android-jni** skill, **diagnostics-system** skill
