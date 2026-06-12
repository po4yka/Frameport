# Rust SDK Design

The Frameport Rust SDK lives in `rust/fuji-rs` and is currently a placeholder workspace only. It exists to establish crate boundaries, typed domain models, error handling, tests, and JNI build hooks before any Fujifilm protocol implementation is written.

Kotlin owns Android lifecycle, runtime permissions, BLE scanning and GATT, Wi-Fi network requests, socket binding to Android `Network`, USB permission flow, MediaStore, foreground services, notifications, UI state, and navigation. Rust will own future PTP/PTP-IP packet handling, Fuji protocol state machines, media transfer control, live-view parsing, protocol diagnostics, and typed native errors.

The workspace crates are intentionally narrow: `fuji-core` defines shared domain types and `FujiError`; `fuji-ptp`, `fuji-ptpip`, `fuji-transfer`, `fuji-liveview`, `fuji-ble-protocol`, and `fuji-usb-ptp` expose placeholder APIs for their future protocol areas; `fuji-diagnostics` exposes diagnostic event helpers; `fuji-sim` provides a fake camera placeholder; `fuji-ffi` exports coarse JNI stubs; and `fuji-cli` prints the SDK version.

No crate opens sockets, talks to cameras, parses proprietary payloads, embeds proprietary assets, copies vendor code, calls cloud services, or implements firmware update behavior. Placeholder APIs return typed `NotImplemented` errors where future protocol work belongs.
