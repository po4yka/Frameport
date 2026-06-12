# Rust FFI Boundary

The JNI boundary should stay coarse-grained. Kotlin should request platform resources, bind sockets to Android networks, duplicate and transfer file descriptors when ownership moves to Rust, then call native operations such as opening a Wi-Fi session, listing media, downloading an object to an owned descriptor, entering or leaving remote mode, and closing a session.

Rust must not expose packet-level primitives to ViewModels or UI. Future APIs should map native errors into typed Kotlin categories and must not allow panics to cross JNI. File descriptor ownership must be documented for every FFI method that accepts a descriptor.

The initial `fuji-ffi` crate exports only placeholder calls: `native_version`, `native_initialize`, `native_shutdown`, `native_open_noop_session`, and `native_close_session`. JNI symbols match `dev.po4yka.frameport.nativebridge.NativeFujiJni`.

The Android bridge provides `NativeFujiSdk`, `JniNativeFujiSdk`, `NoOpNativeFujiSdk`, `NativeFujiJni`, and `NativeLibraryLoader`. `JniNativeFujiSdk` checks whether `libfuji_ffi.so` loads before calling JNI. If the library is missing, Android falls back to no-op behavior and exposes diagnostic state instead of crashing.

The current FFI layer does not open sockets, parse protocol data, communicate with cameras, include native SDK binaries, or implement real transfer/live-view behavior.
