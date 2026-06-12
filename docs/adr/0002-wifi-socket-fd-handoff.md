# ADR 0002: Wi-Fi Socket File Descriptor Handoff

## Status

Accepted

## Date

2026-06-11

## Context

Frameport needs to communicate with Fujifilm cameras over local camera Wi-Fi. The production Android path must account for Android network routing, user-visible Wi-Fi connection state, and the Rust SDK's responsibility for PTP-IP protocol behavior.

The expected camera Wi-Fi flow is:

```text
1. Android discovers or receives camera Wi-Fi credentials.
2. Android requests connection to the camera Wi-Fi network.
3. Android receives an Android Network for the camera Wi-Fi.
4. Android opens sockets bound to that Network.
5. Android passes owned socket file descriptors to Rust.
6. Rust speaks PTP-IP over those descriptors.
```

Camera Wi-Fi is special because the camera network may not provide internet, the camera network may not be the device default network, and Android may keep cellular or another Wi-Fi network as the default internet route. Rust cannot safely assume that `TcpStream::connect("192.168.0.1:55740")` will use the camera Wi-Fi interface. Android `Network` routing is platform-specific and must be handled in Kotlin/Android code.

Frameport's Wi-Fi protocol layer is expected to support multiple channels:

```text
command channel
event channel
live-view / through-picture channel later
```

Exact port semantics and model-specific behavior must be verified with real hardware and firmware. This ADR does not make broad compatibility claims.

## Decision

Frameport will use Android-owned network selection and socket creation for production Wi-Fi PTP-IP sessions.

Production flow:

```text
Android/Kotlin:
  request camera Wi-Fi network
  receive Android Network
  create socket
  bind socket to Network
  connect socket to camera endpoint
  duplicate/transfer owned file descriptor to Rust

Rust:
  wrap owned file descriptor
  own PTP-IP session state
  perform protocol handshake
  enumerate media
  transfer objects
  handle protocol errors
  close owned descriptors when the session ends
```

Rust must not blindly open sockets to `192.168.0.1` in production code unless a debug-only process-wide network binding mode is explicitly active.

The fd handoff is a first-class architecture primitive, not an implementation detail.

### Boundary Diagram

```text
Camera Wi-Fi credentials
        ↓
Android Wi-Fi connector
        ↓
WifiNetworkSpecifier / Network request
        ↓
NetworkCallback.onAvailable(Network)
        ↓
Network-bound socket creation
        ↓
Socket connect to camera endpoint
        ↓
Owned fd handoff through JNI
        ↓
Rust PTP-IP session
        ↓
Media enumeration / import / remote control / diagnostics
```

Channel-specific routing:

```text
Android Network
  ├── command socket fd ───────► Rust command channel
  ├── event socket fd ─────────► Rust event channel later
  └── live-view socket fd ─────► Rust live-view channel later
```

## Consequences

Positive consequences:

* Camera traffic is routed through the correct Android `Network`
* Rust does not need to know Android Wi-Fi APIs
* Connection behavior is explicit and diagnosable
* The app can avoid process-wide network binding in production
* Camera-local traffic is separated from general internet traffic
* Future event and live-view channels can reuse the same routing strategy
* File descriptor ownership can be tested and documented
* This preserves the Android/Rust boundary from ADR 0001

Negative consequences:

* Fd ownership must be precise
* Android socket creation and native wrapping are more complex than direct Rust `TcpStream::connect`
* Tests need fake and physical network scenarios
* Cancellation must close sockets correctly across JNI
* OEM-specific Wi-Fi behavior may still require device testing
* Error mapping must combine Android network failures and Rust protocol failures

## Alternatives Considered

### Alternative 1: Rust opens `TcpStream` directly

This alternative would have Rust call `TcpStream::connect("192.168.0.1:55740")`.

Rejected because Android default routing may not use camera Wi-Fi, camera Wi-Fi may be local-only without internet, the default network may be cellular or another Wi-Fi network, Rust cannot interact cleanly with Android `Network` selection, and failures would be difficult to diagnose.

This may be allowed only for controlled debug tools or CLI tools outside Android, not as the production Android path.

### Alternative 2: Bind the whole process to the camera Network

This alternative would have Android call process-wide network binding, then Rust would open sockets normally.

Accepted only as an MVP/debug fallback. The problems are that all app network traffic may route through the camera network, the camera network may not have internet, unrelated requests may fail, lifecycle cleanup becomes risky, and it is less precise than binding individual sockets.

### Alternative 3: Kotlin owns the PTP-IP protocol

This alternative would have Kotlin open sockets and implement the PTP-IP protocol directly.

Rejected because protocol state belongs in Rust, binary parsing is better isolated in Rust, Rust gives stronger tooling for parser tests, fake servers, fuzzing, and CLI tooling, and protocol logic would leak into Android layers.

### Alternative 4: Use proprietary native libraries for socket handling

This alternative would bundle or call proprietary vendor native libraries for PTP-IP.

Rejected because Frameport is built from scratch, proprietary binaries introduce licensing and redistribution constraints, this violates clean-room goals, and it prevents full diagnostic and error-model control.

## Implementation Rules

### Android Wi-Fi Rules

* Android owns Wi-Fi network requests.
* Android owns `NetworkCallback` lifecycle.
* Android owns socket creation and network binding.
* Android must expose camera network state as typed domain state.
* Android must release network callbacks when the session ends.
* Android must not keep hidden background Wi-Fi connections.
* Android must not silently route unrelated traffic through camera Wi-Fi.
* Android must make user-visible connection state clear.

### Socket Rules

* Sockets must be bound to the selected Android `Network` before connection.
* Sockets must be connected before handoff to Rust unless a specific native-connect path is explicitly documented.
* File descriptors passed to Rust must be owned descriptors.
* Android must duplicate descriptors before transferring ownership if the Android side keeps its own object.
* Ownership must be documented for every fd.
* Closing behavior must be deterministic.
* Cancellation must close active sockets.
* Session close must close all owned fds.
* Double-close must be prevented.

### Rust Rules

* Rust owns the PTP-IP session after receiving fd(s).
* Rust must validate packet lengths.
* Rust must use bounded buffers.
* Rust must map transport/protocol errors into typed errors.
* Rust must not panic across JNI.
* Rust must not assume the fd corresponds to a specific endpoint without Android-provided metadata.
* Rust must support clean session close.
* Rust must support cancellation-aware transfer operations.

### JNI Rules

* JNI must expose coarse-grained APIs.
* Do not expose packet-level send/read operations to Kotlin.
* Do not perform high-frequency JNI calls for each tiny socket operation.
* Native APIs should accept session-level fd handles.
* Native errors must be mapped to Kotlin domain errors.
* Fd ownership must be named in function documentation.

Good API examples:

```text
openWifiSession(commandFd, eventFd, liveViewFd, endpointMetadata)
closeWifiSession(sessionId)
listMedia(sessionId)
downloadObjectToFd(sessionId, objectId, outputFd)
startLiveView(sessionId)
stopLiveView(sessionId)
```

Bad API examples:

```text
sendPtpBytes(fd, bytes)
readPtpBytes(fd)
setSocketFd(fd)
parsePacket(bytes)
```

## Failure Modes

Android-side failures:

```text
PermissionDenied.NearbyWifiDevices
PermissionDenied.LocationIfRequired
Wifi.UserRejected
Wifi.NetworkUnavailable
Wifi.NetworkLost
Wifi.SocketBindFailed
Wifi.SocketConnectTimeout
Wifi.NoRouteToCamera
Wifi.CallbackTimeout
```

Rust-side failures:

```text
Protocol.HandshakeRejected
Protocol.UnexpectedPacket
Protocol.Timeout
Protocol.CameraBusy
Protocol.UnsupportedFunctionMode
Protocol.SessionClosed
Transport.ReadFailed
Transport.WriteFailed
Transport.ConnectionReset
```

Storage/import failures that may happen after Wi-Fi succeeds:

```text
Storage.OutputFdInvalid
Storage.OutputWriteFailed
Transfer.ObjectNotFound
Transfer.PartialReadFailed
Transfer.Cancelled
```

User-facing diagnostics must distinguish Android network failure from Rust protocol failure.

## Testing Implications

Android unit and fake tests should include:

* Fake `CameraWifiConnector`
* Fake `Network` state transitions
* Cancellation behavior
* Retry behavior
* ViewModel state transitions
* Error mapping

Android instrumentation and manual tests should include:

* Request camera Wi-Fi network
* User accepts network request
* User rejects network request
* Camera network disappears
* Socket bind failure
* Socket connect timeout
* Release network after session close
* Verify internet traffic is not silently routed through camera Wi-Fi in production path

Rust tests should include:

* Fd-backed stream wrapper tests where possible
* Fake PTP-IP server
* Session open tests
* Malformed handshake tests
* Timeout tests
* Close/cancel tests
* Transfer interruption tests

Physical hardware tests should include:

* Fujifilm X-T5 current firmware
* At least one Pixel device
* At least one Samsung device later
* At least one Xiaomi device later
* Android version matrix
* Large RAF transfer
* Repeated connect/disconnect cycles

## Security and Privacy Implications

Camera Wi-Fi passphrases are sensitive and must never be logged. Camera SSIDs may contain personal information and should be redacted in diagnostics by default.

Socket endpoints should be treated as local camera endpoints, not internet endpoints. The app must not send camera-local data to cloud endpoints in v1.

Packet dumps must be disabled in release builds unless explicitly sanitized and user-exported. Diagnostic exports must redact passphrases, full serial numbers, MAC addresses, precise location, and private filenames by default.

No hidden background connection should persist after user-visible camera operations end.

## Related Documents

```text
README.md
AGENTS.md
CONTRIBUTING.md
SECURITY.md
NOTICE
docs/adr/0001-android-rust-boundary.md
docs/adr/0003-ble-client-abstraction.md
docs/adr/0004-media-import-pipeline.md
docs/android/wifi-network-routing.md
docs/rust/fd-ownership.md
docs/protocol/wifi-ptp-ip.md
docs/protocol/error-model.md
```
