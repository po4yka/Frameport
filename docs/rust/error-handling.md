# Rust Error Handling

Rust errors use `FujiError` and `FujiResult<T>` from `fuji-core`. Placeholder crates return typed `NotImplemented` errors instead of generic strings when a future protocol area is not implemented.

JNI functions must not let panics cross the FFI boundary. The initial `fuji-ffi` exports wrap JNI entrypoints with panic containment and return integer status codes for lifecycle/session stubs.

Kotlin maps native availability separately from native operation results. `NativeLibraryLoader` reports whether `libfuji_ffi.so` is loaded, and `JniNativeFujiSdk` falls back to `NoOpNativeFujiSdk` if loading fails. Missing native libraries are diagnostics, not app crashes.

Future native errors should map into Android-facing categories such as native initialization failure, socket ownership error, protocol unavailable, camera busy, unsupported function mode, object not found, transfer output write failure, and live-view parse failure. UI and ViewModels should receive typed domain errors through Kotlin interfaces rather than JNI exceptions.
