---
name: android-foreground-service-types
description: Use when declaring or modifying a foreground service in any Frameport module (:camera:data, :feature:connection, or a dedicated import service), choosing between connectedDevice and mediaProcessing types, wiring manifest permissions for BLE/Wi-Fi/USB camera operations, or targeting API 34+ where foregroundServiceType is mandatory.
---

## Android foreground service types — Frameport camera operations

Complements `android-foreground-service-lifecycle.md` (lifecycle invariants, 5-second window, stop symmetry). This rule covers **which type to declare**, the exact permissions required, and Android 14/15 enforcement changes.

### API 34+ enforcement (hard requirement)

Every foreground service must carry `android:foregroundServiceType` in the manifest. Without it, `startForeground()` throws `MissingForegroundServiceTypeException` on API 34+. On API 31–33 the attribute is accepted but not enforced; declaring it now is safe and forward-correct.

### Type selection for Frameport

| Operation | Correct type | Rationale |
|---|---|---|
| BLE pairing, PTP-IP session, live-view streaming | `connectedDevice` | Active camera device link over BLE/Wi-Fi/USB |
| Long post-session media re-encode / format conversion | `mediaProcessing` | Runs after camera disconnects; no active device link |
| File transfer during an active camera session | `connectedDevice` | Transfer is part of the active device connection |

`connectedDevice` has **no timeout**. The 6-hour cap documented for `dataSync` and `mediaProcessing` does NOT apply to `connectedDevice`. The lifecycle rule's "stop promptly at session end" is a correctness and UX requirement, not a system-enforced timeout.

NEVER use `dataSync` for camera file import — it is capped at 6 cumulative hours per 24-hour window on API 35+.

### Manifest declarations

```xml
<!-- AndroidManifest.xml (module :camera:data or :feature:connection) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<!-- BLE path: runtime permissions (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- Wi-Fi path -->
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<!-- Wi-Fi peer scanning, runtime on API 33+ -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />

<!-- Add only if a separate long-import service uses mediaProcessing -->
<!-- <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" /> -->

<service
    android:name=".camera.CameraSessionService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
```

Both `FOREGROUND_SERVICE` (base, normal) AND `FOREGROUND_SERVICE_CONNECTED_DEVICE` (type-specific, normal) are required. Omitting the base permission throws `SecurityException` at `startForeground()` on all API levels.

### Runtime prerequisite for connectedDevice

On API 34+ the system validates at `startForeground()` time that at least one of the following holds:
- `BLUETOOTH_CONNECT` or `BLUETOOTH_SCAN` is granted at runtime (BLE path), **or**
- `CHANGE_WIFI_STATE` is declared in the manifest (Wi-Fi path), **or**
- `UsbManager.requestPermission()` has been called (USB path).

If none apply, `startForeground()` throws `SecurityException` even with a correct manifest. Ensure the relevant permission gate is cleared before starting the service.

### startForeground() call pattern

```kotlin
// In CameraSessionService.onStartCommand() — must complete within 5 seconds
ServiceCompat.startForeground(
    this,
    NOTIFICATION_ID,
    buildSessionNotification(),
    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
)
```

Use `ServiceCompat.startForeground(service, id, notification, serviceType)` from `androidx.core` 1.12+. The bare two-arg `Service.startForeground(int, Notification)` omits the type flag and causes `MissingForegroundServiceTypeException` on API 34+. Do not add manual `Build.VERSION.SDK_INT` guards at every call site — `ServiceCompat` selects the correct overload automatically.

### mediaProcessing timeout (API 35+)

If a separate `mediaProcessing` service is introduced for post-session import:

- The type is capped at 6 cumulative hours per 24-hour window (shared across all instances).
- When the cap is hit the system calls `Service.onTimeout(int startId, int fgsType)` (API 35+). The service has a few seconds to call `stopSelf()`; failure produces a fatal ANR: `"A foreground service of mediaProcessing did not stop within its timeout"`.
- `mediaProcessing` is an API 35 (Android 15) addition; it does not exist on API 34 or below. The `onTimeout(int, int)` callback is likewise API 35+. Guard the override with `@RequiresApi(35)`.
- The timer resets when the user brings the app to the foreground.

```kotlin
@RequiresApi(35)
override fun onTimeout(startId: Int, fgsType: Int) {
    stopSelf()
}
```

### Layer invariant

The service class lives in the Android layer (`:camera:data` module). A ViewModel calls a use-case which calls a repository interface; the repository implementation starts the service via `Intent`. The service itself calls through `CameraWifiConnector` / `FujiBleClient` interfaces, which dispatch to the Rust JNI bridge. ViewModels and Composables MUST NOT start services directly or call JNI.

No FGS may be started from a `BOOT_COMPLETED` receiver on API 35+. Camera/device connection must originate from a user-visible UI interaction.

### Key pitfalls

- Declaring `FOREGROUND_SERVICE_CONNECTED_DEVICE` but omitting the base `FOREGROUND_SERVICE` permission — both are required; omitting the base throws `SecurityException`.
- No runtime prerequisite satisfied when `startForeground()` is called (BLE path: no `BLUETOOTH_CONNECT`/`SCAN` grant; Wi-Fi path: `CHANGE_WIFI_STATE` absent; USB path: `requestPermission()` not called) — throws `SecurityException` at runtime despite correct manifest.
- Using the two-arg `Service.startForeground(id, notification)` — omits type flag, throws `MissingForegroundServiceTypeException` on API 34+. Use `ServiceCompat` instead.
- Declaring multiple types with `|` (pipe) without holding prerequisites for every declared type — the system validates prerequisites for all declared types at `startForeground()` time.
- Using `dataSync` or `mediaProcessing` for active camera transfers — these types are timeout-capped; use `connectedDevice` for active device link operations.
- Forgetting `NEARBY_WIFI_DEVICES` on API 33+ devices when scanning for camera Wi-Fi networks — this is a runtime permission separate from `CHANGE_WIFI_STATE` and is required for Wi-Fi peer discovery.
- Not implementing `onTimeout()` for any `mediaProcessing` service targeting API 35+ — the process will ANR after cap exhaustion.
- Calling `startForeground()` inside a coroutine that suspends before the 5-second window (counted from `onStartCommand()` return) expires — see `android-foreground-service-lifecycle.md`.

### References

- Android 14 foreground service types required: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Foreground service types reference: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android 15 foreground service type changes: https://developer.android.com/about/versions/15/changes/foreground-service-types
- Foreground service timeouts (API 35+): https://developer.android.com/develop/background-work/services/fgs/timeout
- Declare foreground services and request permissions: https://developer.android.com/develop/background-work/services/fgs/declare
