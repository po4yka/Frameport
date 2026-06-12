# Wi-Fi Network Routing

Camera Wi-Fi traffic must be explicitly routed by Android. The production flow is for Android to request the camera Wi-Fi network, bind sockets to the selected Android `Network`, connect to the camera endpoint through that bound socket, and pass an owned file descriptor to Rust for PTP-IP protocol handling.

Rust must not assume that a camera address is reachable from the process default network. It should speak protocol over a descriptor handed to it by the Android Wi-Fi adapter.

The initial `CameraWifiConnector` and `NoOpCameraWifiConnector` document this boundary without requesting networks, binding sockets, opening descriptors, adding cleartext app-wide traffic, or communicating with a real camera.
