# Wi-Fi Network Routing

Camera Wi-Fi traffic must be explicitly routed by Android. The production flow is for Android to request the camera Wi-Fi network, bind sockets to the selected Android `Network`, connect to the camera endpoint through that bound socket, and pass a documented file descriptor to Rust for PTP-IP protocol handling.

Rust must not assume that a camera address is reachable from the process default network. It should speak protocol over a descriptor handed to it by the Android Wi-Fi adapter.

When BLE handoff provides the camera Wi-Fi MAC address, `CameraWifiConnector` must include it as the `WifiNetworkSpecifier` BSSID. SSID and passphrase alone are not precise enough because another AP can advertise the same SSID. Manual SSID entry may omit BSSID until a verified camera MAC is available, but BLE-assisted handoff must target the exact BSSID read from the camera information service.
