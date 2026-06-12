# Bluetooth Architecture

BLE is Android-owned in Frameport. The Bluetooth module will own scanning, GATT connection lifecycle, service discovery, MTU negotiation, reads, writes, notification subscription, operation queueing, timeouts, reconnect behavior, and Android permission handling.

The Rust layer may later parse Fujifilm-specific BLE payloads or generate command payloads, but it should not own `BluetoothGatt` or Android lifecycle objects. Only one GATT operation should be in flight at a time, and disconnect must cancel pending work.

The initial `FujiBleClient` and `NoOpFujiBleClient` define the boundary without performing scans, opening GATT connections, requesting permissions, or communicating with a real camera.
