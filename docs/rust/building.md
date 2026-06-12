# Building Rust

Run Rust tests from the workspace root:

```bash
cd rust/fuji-rs
cargo test --workspace
```

The Android Gradle module also exposes an explicit verification task:

```bash
./gradlew :native:fuji-rust-android:cargoTestRust
```

Build the Android JNI library with `cargo-ndk` only when native artifacts are intentionally needed. The first production target is `arm64-v8a`; `x86_64` is available for emulator debugging:

```bash
./gradlew :native:fuji-rust-android:cargoBuildRustArm64
./gradlew :native:fuji-rust-android:cargoBuildRustX86_64
```

The cargo-ndk tasks write JNI libraries under `native/fuji-rust-android/src/main/jniLibs`. They are explicit tasks and are not dependencies of normal Android assemble tasks. `./gradlew assembleDebug` must continue to work when `libfuji_ffi.so` is absent.

The current native library is a placeholder. It exports lifecycle and no-op session symbols only; it does not include protocol implementation, proprietary vendor material, camera communication, cloud integration, firmware update behavior, or bundled SDK binaries.
