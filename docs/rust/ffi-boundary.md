# Rust FFI Boundary

The JNI boundary should stay coarse-grained. Kotlin should request platform resources, bind sockets to Android networks, duplicate and transfer file descriptors when ownership moves to Rust, then call native operations such as opening a Wi-Fi session, listing media, downloading an object to an owned descriptor, entering or leaving remote mode, and closing a session.

Rust must not expose packet-level primitives to ViewModels or UI. Future APIs should map native errors into typed Kotlin categories and must not allow panics to cross JNI. File descriptor ownership must be documented for every FFI method that accepts a descriptor.

The initial `NativeFujiSdk` and `NoOpNativeFujiSdk` are placeholders only. They do not load native libraries, open sockets, parse protocol data, communicate with cameras, or include proprietary SDK binaries.
