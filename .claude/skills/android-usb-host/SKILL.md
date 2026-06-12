---
name: android-usb-host
description: Use when implementing or modifying the Android USB host pipeline in :camera:usb — UsbManager device enumeration, requestPermission() flow, dynamic BroadcastReceiver registration (ACTION_USB_PERMISSION with RECEIVER_NOT_EXPORTED on API 34+), UsbDeviceConnection open/claimInterface/bulkTransfer, Os.dup() fd handoff to fuji-usb-ptp via fuji-ffi JNI, or any foreground service with foregroundServiceType="connectedDevice". Also triggers on USB PTP endpoint discovery (class 6 / USB_CLASS_STILL_IMAGE), device_filter.xml authoring, PTP interface scanning, or error-state mapping to PermissionDenied.Usb / Usb.FdDupFailed / Usb.DeviceDetached.
---

# Android USB Host — Camera PTP Skill

## When to use

- Authoring or modifying `camera/usb` module (Kotlin side of USB lifecycle).
- Adding or changing the `CameraUsbConnector` interface or its implementation.
- Wiring `fuji-ffi` JNI exports that accept a dup()ed file descriptor for `fuji-usb-ptp`.
- Authoring `res/xml/device_filter.xml` for camera auto-launch.
- Diagnosing `SecurityException` on `registerReceiver` / `IllegalArgumentException` on `PendingIntent` (API 31+ / API 34+ regressions).
- Authoring or reviewing any `Service` that performs USB transfers in the background (foreground service type).
- Writing Rust transport code in `fuji-usb-ptp` that reads/writes the fd received via JNI.

USB PTP is **future / later scope** for Frameport v1 (primary transport is Wi-Fi PTP-IP). Design and boundary definitions are complete; this skill guides correct implementation when USB work begins.

---

## Layer invariants (non-negotiable)

- `Composable` functions in `:feature:connection` / `:feature:import` MUST NOT reference `UsbManager`, `UsbDeviceConnection`, or any `android.hardware.usb.*` type.
- `ViewModel`s MUST NOT call `UsbManager.openDevice()`, `requestPermission()`, or any JNI function directly. They call `UseCase`s.
- `UseCase`s call `CameraUsbConnector` (interface in `:camera:usb`).
- The `fuji-usb-ptp` Rust crate and `fuji-ffi` JNI bridge MUST NOT request Android USB permissions; that is Android's responsibility.
- Rust MUST NOT write to shared storage; MediaStore fd creation is Android-owned.

---

## Manifest requirements (minSdk 31, targetSdk 37)

### AndroidManifest.xml

```xml
<!-- USB host hardware feature. Set required="false" if BLE/Wi-Fi are primary. -->
<uses-feature
    android:name="android.hardware.usb.host"
    android:required="false" />

<!-- Permission for any Service doing background USB transfers (API 34+). -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<!-- Activity or Service that handles auto-launch on camera attach: -->
<activity android:name=".connection.ConnectionActivity"
          android:exported="true">
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/device_filter" />
</activity>

<!-- Any Service performing USB transfers in the background: -->
<service
    android:name=".usb.UsbTransferService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
```

### res/xml/device_filter.xml

Filter by vendor-id/product-id for known Fujifilm cameras. Do NOT rely solely on `class="6"` at the device level — many cameras set class 0 (per-interface) at device level and expose class 6 on the interface. Use interface-level inspection after opening (see endpoint discovery below).

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
      Fujifilm vendor ID = 0x04CB (verify against real hardware / public USB ID lists).
      Add a <usb-device> entry per supported product ID.
      product-id values must be confirmed on real devices — do not assert undocumented IDs.
    -->
    <usb-device vendor-id="1227" />   <!-- 0x04CB decimal; all Fujifilm products (broad) -->
    <!-- Or, more precise per model (preferred once product IDs are confirmed): -->
    <!-- <usb-device vendor-id="1227" product-id="NNNN" /> -->
</resources>
```

---

## Permission flow (`:camera:usb` module)

### 1. Register a dynamic BroadcastReceiver

Register dynamically in the Activity/Service that manages the USB session. **Never** register `ACTION_USB_PERMISSION` as a static (manifest) receiver — doing so causes it to fire when the app is backgrounded, which conflicts with API 34+ background start restrictions.

```kotlin
// API 34+: RECEIVER_NOT_EXPORTED is required for ACTION_USB_PERMISSION.
// ACTION_USB_PERMISSION is NOT a protected system broadcast; omitting the flag
// causes SecurityException on targetSdk 34+ / API 34+ devices.
// Use ContextCompat.registerReceiver for backward compatibility to minSdk 31.

private val usbPermissionReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_PERMISSION) return
        // getParcelableExtra(String) is deprecated on API 33+ and returns null on targetSdk 33+.
        // Use the typed overload with a Build.VERSION check for compat across minSdk 31.
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        if (device == null) return
        if (granted) {
            // proceed to openDevice
        } else {
            // emit PermissionDenied.Usb
        }
    }
}

// In onCreate / onStart:
ContextCompat.registerReceiver(
    this,
    usbPermissionReceiver,
    IntentFilter(UsbManager.ACTION_USB_PERMISSION),
    ContextCompat.RECEIVER_NOT_EXPORTED,   // required on API 34+; ContextCompat handles older APIs
)

// In onDestroy / onStop:
unregisterReceiver(usbPermissionReceiver)
```

### 2. Handle detach race

`ACTION_USB_DEVICE_DETACHED` can arrive before `ACTION_USB_PERMISSION`. The receiver must check whether the device is still present:

```kotlin
val detachReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
        // Transition any PermissionPending state → Usb.DeviceDetachedBeforePermission
    }
}
// ACTION_USB_DEVICE_DETACHED IS a protected system broadcast; no exported flag needed,
// but still prefer dynamic registration for lifecycle correctness.
```

### 3. Request permission

```kotlin
val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

fun requestUsbPermission(device: UsbDevice) {
    if (usbManager.hasPermission(device)) {
        openDevice(device)
        return
    }
    val intent = Intent(ACTION_USB_PERMISSION_ACTION)
        .setPackage(context.packageName)   // scopes broadcast to this app
    // FLAG_IMMUTABLE required on API 31+. Omitting either FLAG_IMMUTABLE or FLAG_MUTABLE
    // throws IllegalArgumentException on API 31+.
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE,
    )
    usbManager.requestPermission(device, pendingIntent)
}

private const val ACTION_USB_PERMISSION_ACTION = "dev.po4yka.frameport.USB_PERMISSION"
```

---

## Device open and interface claim

```kotlin
fun openDevice(device: UsbDevice): Result<UsbSession> {
    val connection = usbManager.openDevice(device)
        ?: return Result.failure(UsbError.DeviceOpenFailed)

    // Find the PTP-compatible interface (class 6 at INTERFACE level, not device level).
    val ptpInterface = (0 until device.interfaceCount)
        .map(device::getInterface)
        .firstOrNull { intf ->
            intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&  // 6
            intf.interfaceSubclass == 1 &&
            intf.interfaceProtocol == 1
        } ?: run {
            connection.close()
            return Result.failure(UsbError.UnsupportedInterface)
        }

    // force=true detaches any kernel driver (e.g., the MTP kernel driver) that may have
    // claimed the interface. Without this, bulkTransfer returns -1 silently.
    val claimed = connection.claimInterface(ptpInterface, /* force= */ true)
    if (!claimed) {
        connection.close()
        return Result.failure(UsbError.ClaimInterfaceFailed)
    }

    return Result.success(UsbSession(connection, ptpInterface))
}
```

### Endpoint discovery

Per libgphoto2/ISO 15740 open-source references (verify endpoint indices against real hardware):

```kotlin
data class PtpEndpoints(
    val bulkOut: UsbEndpoint,   // USB_DIR_OUT + USB_ENDPOINT_XFER_BULK
    val bulkIn: UsbEndpoint,    // USB_DIR_IN  + USB_ENDPOINT_XFER_BULK
    val interruptIn: UsbEndpoint?,  // USB_DIR_IN + USB_ENDPOINT_XFER_INT (optional events)
)

fun findPtpEndpoints(intf: UsbInterface): PtpEndpoints? {
    var bulkOut: UsbEndpoint? = null
    var bulkIn: UsbEndpoint? = null
    var interruptIn: UsbEndpoint? = null

    for (i in 0 until intf.endpointCount) {
        val ep = intf.getEndpoint(i)
        when {
            ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_OUT -> bulkOut = ep

            ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                ep.direction == UsbConstants.USB_DIR_IN -> bulkIn = ep

            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                ep.direction == UsbConstants.USB_DIR_IN -> interruptIn = ep
        }
    }
    return if (bulkOut != null && bulkIn != null) {
        PtpEndpoints(bulkOut, bulkIn, interruptIn)
    } else null
}
```

---

## bulkTransfer

`bulkTransfer` with `timeout = 0` waits indefinitely. It MUST run on a background coroutine/thread — never on `Dispatchers.Main`. An indefinite block on the main thread causes ANR.

```kotlin
// In a suspend fun inside :camera:usb — always dispatched to IO/Default.
suspend fun sendBulkData(
    connection: UsbDeviceConnection,
    endpoint: UsbEndpoint,
    data: ByteArray,
    timeoutMs: Int = 5_000,
): Int = withContext(Dispatchers.IO) {
    connection.bulkTransfer(endpoint, data, data.size, timeoutMs)
    // Returns bytes transferred, or negative on error.
}
```

For non-blocking async reads, `UsbRequest.initialize(connection, endpoint)` + `UsbRequest.queue(ByteBuffer)` + `UsbDeviceConnection.requestWait()` is an alternative; prefer it for event endpoints where latency matters.

---

## fd handoff to Rust (fuji-ffi / fuji-usb-ptp)

`UsbDeviceConnection.getFileDescriptor()` returns a raw `Int` fd valid only while the connection is open. Passing this raw fd to Rust without duplicating it creates a use-after-close or double-close race. Always `Os.dup()` before crossing JNI.

```kotlin
import android.system.Os

fun handoffFdToRust(connection: UsbDeviceConnection): Int {
    val rawFd = connection.fileDescriptor   // -1 if connection not open
    check(rawFd != -1) { "UsbDeviceConnection is not open" }

    // dup() produces an independent fd with its own close lifetime.
    // Rust (fuji-usb-ptp) owns the duplicate and closes it in Drop.
    // Android continues to own the original via UsbDeviceConnection.close().
    val dupFd = Os.dup(rawFd)   // throws ErrnoException on failure → map to Usb.FdDupFailed
    return dupFd
}
```

Then pass `dupFd` as a `jlong`/`jint` into the JNI function exposed by `fuji-ffi`:

```kotlin
// ViewModel → UseCase → CameraUsbConnector → here:
NativeBridge.openUsbTransport(dupFd.toLong())
// After this call, Kotlin must NOT close dupFd — Rust owns it.
// Kotlin closes only connection.close() at session end (the original fd).
```

### Rust side (fuji-ffi crate)

The JNI export in `fuji-ffi` accepts the dup()ed fd as a `jlong` and must:
- Wrap it in `fuji-usb-ptp`'s transport type, which closes the fd in `Drop`.
- Not assume the fd is valid after `Drop`.
- Annotate the function: `// SAFETY: caller guarantees fd is a dup()ed, owned fd; fd is valid and open at call time.`

Verify exact function signatures against `fuji-ffi`'s existing JNI export conventions (see `rust-android-jni` skill for JNI panic containment and local-ref frame discipline).

---

## Foreground service requirements (API 34+)

Any `Service` performing USB data transfers must:

1. Declare `android:foregroundServiceType="connectedDevice"` in the manifest.
2. Declare `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />`.
3. Call `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` on API 29+; use the single-argument overload on older APIs via a compat wrapper.
4. Start the service from a visible context (Activity in foreground, or a service already running). API 34 tightened background foreground-service start restrictions.

The runtime prerequisite (USB permission dialog shown via `UsbManager.requestPermission()`) satisfies the `connectedDevice` type's requirement — no additional runtime permission is needed.

---

## Connection lifecycle states

From `docs/protocol/usb-ptp.md`:

```
Disconnected → DeviceDetected → PermissionPending
  ├─ denied  → PermissionDenied      (emit PermissionDenied.Usb)
  └─ granted → OpeningDevice
       ├─ failure → Error(Usb.OpenFailed)
       └─ success → DeviceOpen → FdHandoff → RustTransportOpen
            → PtpSessionOpening
                ├─ failure → Error(Protocol.OpenSessionFailed)
                └─ success → UsbSessionReady
                     ├─ MediaBrowsing / Transferring / Closing
                     └─ Closing → Closed
```

Cleanup on any error or detach: cancel active transfer → Rust closes dup()ed fd → Android calls `connection.releaseInterface(intf)` then `connection.close()` → unregister receivers if session-scoped → emit final diagnostic state.

USB permissions are **not** persistent. After detach+reattach, `requestPermission()` must be called again regardless of prior grant.

---

## Diagnostic error states (`:camera:diagnostics`)

Map Android USB events to the typed error states consumed by `fuji-diagnostics`:

| Event | Typed error |
|---|---|
| Permission dialog dismissed / denied | `PermissionDenied.Usb` |
| `requestPermission` call failed | `Usb.PermissionRequestFailed` |
| Device detached before permission granted | `Usb.DeviceDetachedBeforePermission` |
| `openDevice()` returns null | `Usb.DeviceOpenFailed` |
| No PTP interface found | `Usb.UnsupportedInterface` |
| `claimInterface` returns false | `Usb.ClaimInterfaceFailed` |
| `getFileDescriptor()` returns -1 | `Usb.FdUnavailable` |
| `Os.dup()` throws ErrnoException | `Usb.FdDupFailed` |
| Device detached during session | `Usb.DeviceDetached` |
| `bulkTransfer` returns negative | `Usb.BulkReadFailed` / `Usb.BulkWriteFailed` |

Diagnostic fields allowed by `docs/protocol/usb-ptp.md`: interface count, endpoint count, endpoint direction category, endpoint transfer type category, typed error code, retry count, elapsed time. Redact: full serial number, raw descriptor bytes, raw PTP packets.

---

## Key pitfalls

1. **Missing `Os.dup()` before JNI handoff.** Passing `connection.fileDescriptor` directly to Rust causes use-after-close when `connection.close()` is later called by Android. Always dup().

2. **`claimInterface(intf, false)` instead of `true`.** Without `force = true`, an existing kernel MTP driver retains the interface; `bulkTransfer` returns -1 silently.

3. **`bulkTransfer(timeout = 0)` on the main thread.** Indefinite wait → ANR. Always dispatch to `Dispatchers.IO`.

4. **Missing `RECEIVER_NOT_EXPORTED` on API 34+.** `ACTION_USB_PERMISSION` is not a protected system broadcast. Omitting the flag on a targetSdk 34+ app targeting API 34+ devices causes `SecurityException`. Use `ContextCompat.registerReceiver` with `ContextCompat.RECEIVER_NOT_EXPORTED`.

5. **`PendingIntent` without `FLAG_IMMUTABLE`.** On API 31+ this throws `IllegalArgumentException`. Use `FLAG_IMMUTABLE` for the USB permission `PendingIntent`.

6. **Static manifest receiver for `ACTION_USB_PERMISSION`.** Causes the app to receive USB permission callbacks while backgrounded, conflicting with API 34+ background foreground-service-start restrictions. Register dynamically and unregister in `onDestroy`/`onStop`.

7. **Class 6 at device level in `device_filter.xml` only.** Many cameras set class 0 at device level (per-interface class model) and expose class 6 on the interface. Use vendor-id/product-id in `device_filter.xml` for reliable auto-launch; do per-interface class inspection after opening.

8. **Assuming USB permission survives detach/reattach.** Android resets USB permissions on device detach. Always call `requestPermission()` again after reconnection.

9. **Using `nusb` or `mtp-rs` for Android in `fuji-usb-ptp`.** `nusb` explicitly targets Linux/macOS/Windows only (no Android fd-injection support as of 2025). `mtp-rs` is built on `nusb`. Neither is usable for the Android target without major rework. The correct Rust approach: raw `read()`/`write()` syscalls on the dup()ed fd, or link `libusb >= 1.0.24` and call `libusb_set_option(LIBUSB_OPTION_NO_DEVICE_DISCOVERY)` + `libusb_wrap_sys_device(ctx, fd, &devh)`.

10. **ViewModel calling USB APIs directly.** Violates Frameport's layer invariant. USB access lives in `:camera:usb` behind `CameraUsbConnector`; ViewModels call UseCases only.

11. **`fuji-usb-ptp` not closing the dup()ed fd in `Drop`.** Resource leak and potential fd exhaustion. The crate's transport type must close the fd in its `Drop` impl and never use it after `Drop`.

---

## Fujifilm-specific notes (verify against real devices)

The following are sourced from open-source references (libgphoto2, ISO 15740) and require validation against actual Fujifilm hardware before being treated as production facts:

- Fujifilm USB vendor ID: `0x04CB` (1227 decimal) — verify against public USB ID lists and real hardware.
- PTP interface class/subclass/protocol: 6/1/1 (per libgphoto2 / ISO 15740 open-source references).
- Specific product IDs per camera model: confirm against public USB ID database and device inspection in the lab.
- Whether all target cameras expose a PTP-compatible interface in their default USB mode, or require a mode switch in the camera menu, is unknown until tested on real hardware. See `docs/protocol/usb-ptp.md` § Known Unknowns.
- PTP operation code support (which opcodes the camera accepts) must be determined by clean-room interop testing; do not assert undocumented Fujifilm opcodes as fact.

---

## References

- [USB host overview — Android Developers](https://developer.android.com/develop/connectivity/usb/host)
- [UsbDeviceConnection — Android API Reference](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)
- [UsbManager — Android API Reference](https://developer.android.com/reference/android/hardware/usb/UsbManager)
- [UsbDeviceConnection.java — AOSP mirror](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/hardware/usb/UsbDeviceConnection.java)
- [Foreground service types — Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Behavior changes: Apps targeting Android 12](https://developer.android.com/about/versions/12/behavior-changes-12)
- [Behavior changes: Apps targeting Android 14](https://developer.android.com/about/versions/14/behavior-changes-14)
- [libgphoto2 ptp.h — PTP USB class constants](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)
- [libgphoto2 issue #683 — non-rooted Android support](https://github.com/gphoto/libgphoto2/issues/683)
- [libusb Android directory — libusb_wrap_sys_device](https://github.com/libusb/libusb/tree/master/android)
- [nusb — pure-Rust USB (no Android)](https://docs.rs/nusb/latest/nusb/)
- [mtp-rs — Rust MTP/PTP over nusb](https://github.com/vdavid/mtp-rs)
- `docs/protocol/usb-ptp.md` — Frameport USB PTP protocol and architecture spec
- `docs/rust/fd-ownership.md` — Frameport fd ownership rules across the JNI boundary
