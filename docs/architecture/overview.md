# Architecture Overview

Frameport is an Android-only, local-first Fujifilm companion app with a Kotlin Android application layer and a future Rust native camera SDK behind JNI. The initial architecture intentionally compiles with no real camera communication, no cloud dependencies, no proprietary Fujifilm assets, and no protocol payloads copied from reverse-engineered applications.

The Android layer owns user-facing lifecycle and platform integration: permissions, BLE scanning and GATT, Wi-Fi network requests and socket binding, USB permission and device opening, MediaStore writes, foreground-service lifecycle, notifications, UI state, navigation, and dependency injection.

The future Rust layer owns protocol correctness: PTP packet encoding and decoding, PTP-IP session state, Fujifilm protocol state machines, transfer control, live-view parsing, protocol diagnostics, and typed native errors. Kotlin should call Rust only through coarse-grained interfaces that match user-level camera operations.

The first implementation keeps protocol and platform work behind contracts and no-op implementations. This lets app and feature modules compile while preserving boundaries for later real BLE, Wi-Fi, USB, media import, and JNI implementations.

The current app shell uses Compose, Material 3, Navigation 3, MVVM, Hilt, and unidirectional state flow. `FrameportApp` owns the Material scaffold and top app bar, `FrameportNavHost` owns the Navigation 3 back stack, and feature modules expose route composables backed by stateless content composables plus `StateFlow`-based ViewModels where the screen has state or actions.

Navigation keys are typed `NavKey` objects under the app layer. Feature screens receive callbacks and state, not navigation controllers, repositories that navigate, or large route payloads. Placeholder implementations remain explicit: connection, gallery, import, remote, live view, diagnostics, and settings screens show planned or not-implemented states without opening Bluetooth, Wi-Fi, USB, MediaStore, native SDK, or camera protocol paths.
