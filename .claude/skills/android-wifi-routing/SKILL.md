---
name: android-wifi-routing
description: Routing camera Wi-Fi traffic on Android using WifiNetworkSpecifier + ConnectivityManager.requestNetwork, binding sockets to the granted Network via Network.bindSocket before connect(), extracting and transferring the fd to Rust over JNI, and handling NEARBY_WIFI_DEVICES (API 33+) / ACCESS_FINE_LOCATION (API 31–32) / ACCESS_LOCAL_NETWORK (targetSdk 37) permissions. Use when authoring or modifying CameraWifiConnector, the :camera:wifi module, the camera/wifi fd-handoff path in fuji-ffi, or any code that creates sockets for camera hotspot connections.
---

# Android Wi-Fi Routing — Frameport

## Purpose

Fujifilm cameras running PTP-IP expose a local-only Wi-Fi hotspot (typically `192.168.0.1`). Android's default route is cellular or the primary Wi-Fi network; a socket opened without explicit network binding cannot reach `192.168.0.1`. This skill documents the complete, production-correct path from permission checking through `WifiNetworkSpecifier` request, socket creation, `Network.bindSocket`, fd extraction, and JNI handoff to `fuji-ffi` — and the pitfalls that make each step fail silently.

## Module mapping

| Concern | Frameport location |
|---|---|
| `WifiNetworkSpecifier` construction, `requestNetwork`, `NetworkCallback`, socket creation, `bindSocket`, fd extraction | `:camera:wifi` / `CameraWifiConnector` impl |
| Interface definition | `:camera:api` (`CameraWifiConnector`) |
| Permission checks and typed `PermissionDenied` errors | `:core:permissions`, `:camera:diagnostics` |
| JNI fd receive, `OwnedFd` wrap, async I/O | `rust/fuji-rs/crates/fuji-ffi`, `fuji-ptpip` |
| ViewModel (no JNI, no sockets, no ConnectivityManager) | `:feature:connection` |
| Test double (no framework deps) | `NoOpCameraWifiConnector` (see ADR 0002) |

**Layer invariant**: ViewModels in `:feature:connection` MUST NOT call `ConnectivityManager`, `Network`, or `Socket` directly. Composables MUST NOT open sockets. All of the below belongs inside the `CameraWifiConnector` implementation in `:camera:wifi`.

## When to consult

- Implementing or modifying `CameraWifiConnector` or any class in `:camera:wifi` that touches `ConnectivityManager` or `Network`.
- Adding or changing the fd-handoff path between Kotlin sockets and `fuji-ffi`.
- Reviewing a diff that calls `WifiNetworkSpecifier.Builder`, `requestNetwork`, `bindSocket`, `detachFd`, or `android_setsocknetwork`.
- Triaging a bug where the camera at `192.168.0.1` is unreachable (`ENETUNREACH`, `ETIMEDOUT`).
- Adding or changing Wi-Fi permission declarations for API 31–37 compatibility.

---

## 1. Permissions

### Manifest (`:app` `AndroidManifest.xml`)

```xml
<!-- Normal permissions — no runtime grant needed -->
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />

<!-- API 33+: replaces ACCESS_FINE_LOCATION for Wi-Fi peer-to-peer.
     neverForLocation: we derive no location from the SSID. -->
<uses-permission
    android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />

<!-- API 31–32 fallback; suppressed on API 33+ by maxSdkVersion -->
<uses-permission
    android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="32" />

<!-- API 36 opt-in / API 37 mandatory for targetSdk 37 LAN socket access.
     Frameport targets SDK 37; declare now, request at runtime.
     Verify exact enforcement behavior on Android 17 preview builds
     with a WifiNetworkSpecifier-granted Network (see §5). -->
<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
```

### Runtime request (`:core:permissions`)

```kotlin
// API 31–32: request ACCESS_FINE_LOCATION
// API 33+:   request NEARBY_WIFI_DEVICES
// API 36+:   also request ACCESS_LOCAL_NETWORK (targetSdk 37 = mandatory)
val wifiPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.NEARBY_WIFI_DEVICES
} else {
    Manifest.permission.ACCESS_FINE_LOCATION
}
// Request wifiPermission + ACCESS_LOCAL_NETWORK together on API 36+.
// Map SecurityException from requestNetwork → PermissionDenied.NearbyWifiDevices
// (typed error defined in :camera:diagnostics / ADR 0002).
```

---

## 2. WifiNetworkSpecifier construction

```kotlin
// In CameraWifiConnector implementation, :camera:wifi

val specifier = WifiNetworkSpecifier.Builder()
    .setSsid(cameraHotspotSsid)           // exact SSID of the camera hotspot
    // .setBssid(MacAddress.fromString(bssid))  // add if BSSID is known — skips repeat dialog
    .setWpa2Passphrase(hotspotPassphrase) // API 29+; use setWpa3Passphrase (API 30+) if camera supports SAE
    // Passphrase values come from clean-room interop notes / pairing flow —
    // verify against a real device; do not hard-code vendor-specific defaults as fact.
    .build()

val networkRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    // Camera hotspot has no internet. Remove the default capability or
    // the system will immediately fire onUnavailable (no network satisfies it).
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .setNetworkSpecifier(specifier)
    .build()
```

---

## 3. Requesting the network and binding the socket

This is the critical sequence. Every step must happen in the order shown.

```kotlin
private lateinit var connectivityManager: ConnectivityManager
private var networkCallback: ConnectivityManager.NetworkCallback? = null

fun connect(
    ssid: String,
    passphrase: String,
    commandPort: Int,
    executor: Executor,          // caller-supplied; never dispatch I/O on the main thread
    onFdReady: (Int) -> Unit,    // called once with the owned raw fd; Rust takes ownership
    onFailure: (Throwable) -> Unit,
) {
    val specifier = buildSpecifier(ssid, passphrase)
    val request = buildRequest(specifier)

    val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            // Android has granted the scoped peer-to-peer Network.
            // All socket work happens here, on the executor thread.
            executor.execute {
                runCatching {
                    // Step A: create socket — NOT yet connected.
                    val socket = Socket()

                    // Step B: bind to the granted Network BEFORE connect().
                    // This routes the socket through the camera hotspot, not the default route.
                    network.bindSocket(socket)   // Network.bindSocket(Socket) — API 21+

                    // Step C: now connect.
                    socket.connect(InetSocketAddress("192.168.0.1", commandPort), 10_000)
                    // 192.168.0.1 is the conventional camera command endpoint;
                    // verify the actual address/port from clean-room PTP-IP interop notes
                    // and libgphoto2 source (fuji-ptpip crate / docs/protocol/wifi-ptp-ip.md).

                    // Step D: extract the fd and transfer ownership to Rust.
                    // detachFd() makes the ParcelFileDescriptor invalid; Rust MUST close.
                    val pfd = ParcelFileDescriptor.fromSocket(socket)
                    val rawFd = pfd.detachFd()   // ownership transferred; do NOT close rawFd from Kotlin

                    onFdReady(rawFd)
                }.onFailure { onFailure(it) }
            }
        }

        override fun onUnavailable() {
            onFailure(CameraWifiException.NetworkUnavailable)
        }

        override fun onLost(network: Network) {
            onFailure(CameraWifiException.NetworkLost)
        }
    }

    networkCallback = callback

    // API 26+ overload with timeoutMs prevents an unresolvable hanging callback.
    // Both Frameport minSdk (31) and compileSdk (37) satisfy this.
    connectivityManager.requestNetwork(request, callback, NETWORK_REQUEST_TIMEOUT_MS)
}

fun disconnect() {
    networkCallback?.let {
        // Must be called when the session ends. Failure leaks the Wi-Fi connection,
        // drains battery, and prevents the device reconnecting to home Wi-Fi.
        connectivityManager.unregisterNetworkCallback(it)
        networkCallback = null
    }
}

companion object {
    private const val NETWORK_REQUEST_TIMEOUT_MS = 30_000
}
```

---

## 4. Receiving the fd in Rust (fuji-ffi / fuji-ptpip)

The Kotlin side calls a JNI function with the raw `Int` fd after `detachFd()`.

```rust
// fuji-ffi/src/wifi.rs

use std::os::unix::io::FromRawFd;
use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JClass, JObject};
use jni::sys::{jint, jlong};

/// Receives an owned fd from Kotlin after Network.bindSocket + detachFd().
///
/// # Safety
/// `fd` must be a valid, connected TCP socket fd whose ownership has been
/// transferred via ParcelFileDescriptor.detachFd(). Kotlin MUST NOT use
/// the fd or its originating Socket after calling this function.
// SAFETY annotation required per workspace lint policy: every unsafe block
// must document the invariant it relies on.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_po4yka_frameport_nativebridge_FujiBindings_startWifiSession(
    _env: JNIEnv<'_>,
    _thiz: JObject<'_>,
    fd: jint,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: Kotlin transferred ownership via detachFd(); fd is a valid,
        // connected TCP socket fd. We take exclusive ownership here; Kotlin
        // must not close or read from it after this call.
        let std_stream = unsafe { std::net::TcpStream::from_raw_fd(fd) };

        if std_stream.set_nonblocking(true).is_err() {
            return -1_jlong;
        }

        // Convert to tokio TcpStream for async I/O in fuji-ptpip.
        // tokio::net::TcpStream::from_std requires the socket to be in
        // non-blocking mode (set above) and must be called from within a
        // tokio runtime context or via block_on.
        // TODO: wire RUNTIME and PtpIpSession::new once fuji-ptpip is
        // implemented; return a session handle as jlong.
        // Placeholder: return 0 (invalid handle) until implementation lands.
        let _ = std_stream; // placeholder; replace with session creation
        0_jlong
    }))
    .unwrap_or(-1_jlong)
}
```

`fuji-ptpip` then drives PTP-IP command/data/event channels over the `TcpStream`. The specific PTP-IP port numbers and opcode layout follow ISO 15740 / PTP-IP (CIPA DC-005-2005); Fujifilm-specific extensions must be verified against clean-room interop notes and libgphoto2 (see `docs/protocol/wifi-ptp-ip.md`).

---

## 5. ACCESS_LOCAL_NETWORK (targetSdk 37)

Frameport sets `targetSdk 37`. Android 16 (API 36) introduces `ACCESS_LOCAL_NETWORK` as opt-in; Android 17 (API 37) makes it mandatory for apps targeting SDK 37+.

**What it gates**: raw socket connections to LAN-range addresses (`192.168.x.x`, `10.x.x.x`, etc.) that are NOT already attributed to a peer-to-peer network grant.

**Interaction with WifiNetworkSpecifier**: when a socket is explicitly bound to the granted `Network` via `Network.bindSocket()` before `connect()`, Android should recognize the traffic as belonging to the peer-to-peer grant rather than a generic LAN socket. Whether this attribution satisfies `ACCESS_LOCAL_NETWORK` enforcement needs hardware validation on Android 17 preview builds — do not assume it is automatically exempt.

**Action**: declare the permission (shown in §1), add it to the `:core:permissions` runtime-request sequence alongside `NEARBY_WIFI_DEVICES`, and test on an Android 17 device/emulator image once available. Track in `docs/android/wifi-network-routing.md`.

---

## 6. Pitfalls

### P1 — Missing `removeCapability(NET_CAPABILITY_INTERNET)`

Camera hotspots never satisfy `NET_CAPABILITY_INTERNET`. Without this call the system has no matching network and fires `onUnavailable` immediately.

```kotlin
// WRONG — omitting this causes instant onUnavailable
NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .setNetworkSpecifier(specifier)
    .build()

// CORRECT
NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .setNetworkSpecifier(specifier)
    .build()
```

### P2 — `connect()` before `bindSocket()`

`Network.bindSocket(Socket)` requires the socket to NOT be connected. Calling `connect()` first is either silently ignored or throws `IOException`. Order is non-negotiable: `new Socket()` → `bindSocket()` → `connect()`.

### P3 — `bindProcessToNetwork` in production code

`ConnectivityManager.bindProcessToNetwork(Network)` reroutes **all** process sockets through the camera Wi-Fi. Background HTTP calls (OkHttp, etc.) will fail because the camera hotspot has no internet. This is the ADR 0002 "Alternative 2 — debug fallback only" path. It must never appear in the production `CameraWifiConnector` implementation.

### P4 — `getFd()` instead of `detachFd()` across JNI

`ParcelFileDescriptor.getFd()` does not transfer fd ownership. If Rust closes that fd, the Java `Socket`/`ParcelFileDescriptor` still holds a reference to the now-closed fd, causing `EBADF` on any subsequent Java operation. Use `detachFd()` to transfer exclusive ownership. After `detachFd()` the Kotlin side must not use the socket or the `ParcelFileDescriptor` again.

### P5 — 192.168.0.1 unreachable without binding

This is the most common integration failure. A `Socket` opened with `new Socket()` (no `bindSocket`) routes through Android's default network (cellular or home Wi-Fi). The camera hotspot subnet is unreachable over the default route; the result is `ENETUNREACH` or a connection timeout. Always call `network.bindSocket(socket)` before `connect()`.

### P6 — Not unregistering `NetworkCallback`

Omitting `unregisterNetworkCallback` when the session ends keeps the camera Wi-Fi connection alive, drains battery, and may block the device from reconnecting to its home Wi-Fi. Unregister in `CameraWifiConnector.disconnect()`, which must be called from the `:camera:wifi` `LifecycleObserver` or `viewModelScope` cancellation handler.

### P7 — Wrong permission on API 33+

Declaring only `ACCESS_FINE_LOCATION` without `NEARBY_WIFI_DEVICES` on a device running API 33+ causes a `SecurityException` from `requestNetwork` even if location permission is already granted. The two permission groups are separate; both must be declared with `maxSdkVersion`/`minSdkVersion` guards (see §1).

### P8 — `requestNetwork` main-thread dispatch

The two-argument `requestNetwork(request, callback)` dispatches callbacks on the main thread's `Handler`. For a callback that creates sockets and calls `connect()` this risks ANR. Use the three-argument overload:

```kotlin
// Preferred: route callbacks to a background Handler to prevent main-thread I/O.
// ConnectivityManager has no Executor overload; use Handler or the timeoutMs overload.
connectivityManager.requestNetwork(request, callback, backgroundHandler)
// Or use the timeout overload (callbacks dispatched on internal handler; do socket
// work on a caller-supplied Executor inside onAvailable, as shown in §3):
connectivityManager.requestNetwork(request, callback, TIMEOUT_MS)
// There is no requestNetwork(request, Executor, callback) overload in the Android API.
```

### P9 — `bindProcessToNetwork(null)` resetting global state

Calling `bindProcessToNetwork(null)` resets the process-default network to system default, which breaks any other component that had set its own binding. If you ever use `bindProcessToNetwork` in debug paths, save and restore the prior binding via `getProcessDefaultNetwork()`.

### P10 — Passing `net_handle_t = 0` to NDK

`Network.getNetworkHandle()` returns a `Long` (`net_handle_t` / `uint64_t`). A value of `0` means "unspecified network" and clears any binding — it is not the default network. Validate the handle is non-zero before passing to `android_setsocknetwork`.

**Note on NDK path**: `android_setsocknetwork` / `android_setprocnetwork` (declared in `<android/multinetwork.h>`, NDK API 23+) are available in principle, but ADR 0002 assigns socket creation to Kotlin. The NDK path is not the production path for Frameport and should not be introduced without a new ADR.

---

## 7. LifecycleObserver integration sketch

```kotlin
// :camera:wifi — wires CameraWifiConnector to a lifecycle-aware owner

class CameraWifiLifecycleObserver(
    private val connector: CameraWifiConnector,
) : DefaultLifecycleObserver {

    override fun onStop(owner: LifecycleOwner) {
        connector.disconnect()   // unregisters NetworkCallback
    }
}
```

Register in the `:feature:connection` Fragment/Activity, not the ViewModel, so the ViewModel never owns the `ConnectivityManager` reference.

---

## 8. Test double

The `NoOpCameraWifiConnector` (see `docs/android/wifi-network-routing.md`) is the correct test double for `:feature:connection` ViewModel unit tests. It implements the `CameraWifiConnector` interface without touching `ConnectivityManager`, `Network`, `Socket`, or JNI, so tests run on the JVM without an Android framework dependency.

---

## References

1. Wi-Fi Network Request API for peer-to-peer connectivity — Android Developers: https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap
2. `ConnectivityManager` API reference: https://developer.android.com/reference/android/net/ConnectivityManager
3. `WifiNetworkSpecifier` API reference: https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier
4. `WifiNetworkSpecifier.Builder` API reference: https://developer.android.com/reference/android/net/wifi/WifiNetworkSpecifier.Builder
5. Request permission to access nearby Wi-Fi devices: https://developer.android.com/develop/connectivity/wifi/wifi-permissions
6. Local network permission (ACCESS_LOCAL_NETWORK): https://developer.android.com/privacy-and-security/local-network-permission
7. Android NDK Networking (`android/multinetwork.h`): https://developer.android.com/ndk/reference/group/networking
8. `Network.bindSocket` overloads: https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)
9. Frameport ADR 0002 — Wi-Fi socket fd handoff: `docs/adr/0002-wifi-socket-fd-handoff.md`
10. Frameport — Android Wi-Fi network routing guide: `docs/android/wifi-network-routing.md`
11. Frameport — PTP-IP protocol notes: `docs/protocol/wifi-ptp-ip.md`
12. Frameport — Rust fd ownership: `docs/rust/fd-ownership.md`
