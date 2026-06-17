# Bluetooth Architecture

BLE is Android-owned in Frameport. The Bluetooth module will own scanning, GATT connection lifecycle, service discovery, MTU negotiation, reads, writes, notification subscription, operation queueing, timeouts, reconnect behavior, and Android permission handling.

The Rust layer may later parse Fujifilm-specific BLE payloads or generate command payloads, but it should not own `BluetoothGatt` or Android lifecycle objects. Only one GATT operation should be in flight at a time, and disconnect must cancel pending work.

The production `:camera:bluetooth` implementation uses JuulLabs Kable internally for Android scanning, peripheral connection, service discovery, characteristic I/O, and observation flows. Kable types must remain private to `:camera:bluetooth`; app, feature, domain, repository, and Rust code continue to depend only on Frameport-owned interfaces such as `FujiBleClient`, `BleScanner`, and `GattTransport`.

Scan results cache Kable advertisements for immediate scan-to-connect, but reconnect must also work from a saved `BleCameraRef.id` without a fresh scan. In that path, `AndroidGattTransport` creates the Kable `Peripheral` from the saved identifier directly and still applies Frameport's timeout, serialization, and privacy logging policy.
