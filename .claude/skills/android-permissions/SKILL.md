---
name: android-permissions
description: Runtime permission flows for Frameport — BLUETOOTH_SCAN/BLUETOOTH_CONNECT (API 31+), NEARBY_WIFI_DEVICES (API 33+, neverForLocation), POST_NOTIFICATIONS (API 33+), foreground-service types (connectedDevice), rationale/denied/permanently-denied paths, registerForActivityResult(RequestMultiplePermissions), and the :core:permissions interface contract. Use when authoring or modifying AndroidManifest entries for Bluetooth/Wi-Fi/notification/FGS permissions, implementing PermissionController or PermissionState in :core:permissions, writing Compose permission-request UI in :feature:onboarding or :feature:connection, or auditing that ViewModels and Composables respect the layer invariants around permission calls.
---

# Android Permissions — Frameport

## When to consult

- Adding or changing `<uses-permission>` entries in any module manifest.
- Implementing or extending `PermissionController` / `PermissionState` in `:core:permissions`.
- Writing a Composable that launches a permission request dialog (`:feature:onboarding`, `:feature:connection`).
- Auditing that a ViewModel or Rust/JNI layer does not call permission APIs directly.
- Adding or modifying the `CameraConnectionService` foreground service or its manifest entry.
- Raising `minSdk` or `targetSdk` and checking behavioral changes.

## Manifest declarations (by module)

Permissions belong in the manifest of the module that *owns* the feature. The Gradle manifest merger combines them into the app manifest at build time.

### `camera/bluetooth/src/main/AndroidManifest.xml`

```xml
<!-- API 31+ Bluetooth runtime permissions -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

<!-- Legacy Bluetooth — inert on API 31+ but retained for compatibility layer edge cases -->
<uses-permission android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />

<!-- Location was required for BLE scan below API 31; cap it here -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="30" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

`neverForLocation` on `BLUETOOTH_SCAN` means the system will filter out BLE advertisement packets that originate from location beacons. Fujifilm cameras do not advertise as location beacons, so this is safe and correct for Frameport. It keeps the app free of any location permission dependency.

### `camera/wifi/src/main/AndroidManifest.xml`

```xml
<!-- API 33+ Wi-Fi P2P/Aware/RTT runtime permission -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />

<!-- Backward-compat: Wi-Fi P2P on API 31-32 still requires ACCESS_FINE_LOCATION -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
    android:maxSdkVersion="32" />
```

Note: `WifiManager.getScanResults()` and `WifiManager.startScan()` still require `ACCESS_FINE_LOCATION` even on API 33+. Frameport uses Wi-Fi P2P / direct socket connections to the camera hotspot, not general Wi-Fi scanning — verify the exact API surface against real-device interop notes before removing `ACCESS_FINE_LOCATION` for higher API levels.

### `app/src/main/AndroidManifest.xml`

```xml
<!-- Notification permission (API 33+); new installs on API 33+ start with notifications OFF -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### `camera/data/src/main/AndroidManifest.xml` (or the module owning the FGS)

```xml
<!-- Required for any foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<!-- Required on API 34+ for connectedDevice FGS type -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<!-- Service declaration -->
<service
    android:name=".CameraConnectionService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
```

`FOREGROUND_SERVICE_CAMERA` is not needed for v1 (no background camera capture). Add it only if a future feature captures camera output while the app is in the background.

## API-level behavior summary

| API level | Key change |
|---|---|
| 31 (minSdk) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` become runtime/dangerous. Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` become inert — cap them at `maxSdkVersion="30"`. `NEARBY_WIFI_DEVICES` does not exist yet; Wi-Fi P2P needs `ACCESS_FINE_LOCATION`. `POST_NOTIFICATIONS` does not exist yet; notifications are implicitly allowed. |
| 33 | `NEARBY_WIFI_DEVICES` (runtime) replaces `ACCESS_FINE_LOCATION` for Wi-Fi P2P/Aware/RTT. `POST_NOTIFICATIONS` (runtime) is required; new-install default is OFF; apps upgrading from API 32 with notifications already enabled are auto-granted. |
| 34 | All FGS declarations **must** include `android:foregroundServiceType`; omitting it throws `MissingForegroundServiceTypeException`. Each type also needs its corresponding normal permission (e.g. `FOREGROUND_SERVICE_CONNECTED_DEVICE`). At least one of `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, or `UWB_RANGING` must be held before calling `startForeground()` on a `connectedDevice` service. Camera/microphone FGS types cannot be started from background (while-in-use restriction). |
| 35 | `mediaProcessing` FGS type is capped at 6 hours per 24-hour window; when the timeout is reached the system calls `Service.onTimeout(int, int)` and the service must stop itself within a few seconds or an ANR is raised. `connectedDevice` type is not subject to this timeout. `dataSync`, `camera`, `mediaPlayback`, `mediaProjection`, `phoneCall`, and `microphone` (the latter since API 34) types cannot be launched from a `BOOT_COMPLETED` receiver — attempting to do so throws `ForegroundServiceStartNotAllowedException`; `connectedDevice` is not restricted. |

## :core:permissions interface contract

The `:core:permissions` module is the single source of truth for permission state. No other module imports `android.Manifest`, `ActivityCompat`, or `ContextCompat.checkSelfPermission` for the purpose of requesting permissions.

### PermissionState enum

```kotlin
enum class PermissionState {
    NOT_REQUESTED,      // checkSelfPermission not yet called this session
    GRANTED,
    DENIED_RATIONALE,   // denied once; shouldShowRequestPermissionRationale == true
    DENIED_PERMANENT,   // denied + shouldShowRequestPermissionRationale == false
}
```

### PermissionController interface

```kotlin
interface PermissionController {
    val permissionStates: StateFlow<Map<String, PermissionState>>

    fun checkPermission(permission: String): PermissionState
    fun requestPermissions(permissions: List<String>)
    fun openAppSettings()  // navigates to Settings.ACTION_APPLICATION_DETAILS_SETTINGS
}
```

The concrete implementation lives inside `:core:permissions` and is backed by `ActivityResultContracts.RequestMultiplePermissions`. It is injected into Composable screens (not ViewModels) via Hilt or passed as a parameter. ViewModels observe `StateFlow<Map<String, PermissionState>>` only — they never call `requestPermissions()` directly.

### Disambiguating permanently-denied vs never-asked

```kotlin
fun resolveState(context: Context, activity: Activity, permission: String): PermissionState {
    val granted = ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return PermissionState.GRANTED

    val shouldShow = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    // shouldShowRequestPermissionRationale returns:
    //   true  → denied once (show rationale UI)
    //   false → either never asked OR permanently denied
    // We distinguish never-asked from permanently-denied by tracking whether we have
    // ever launched the request (e.g. a boolean in DataStore / SharedPreferences).
    return if (shouldShow) PermissionState.DENIED_RATIONALE else {
        // Caller must check "have we ever requested this permission?" from persisted state.
        // If never requested → NOT_REQUESTED. If requested at least once → DENIED_PERMANENT.
        PermissionState.DENIED_PERMANENT  // or NOT_REQUESTED — see persisted flag
    }
}
```

## Composable permission request pattern

Register the launcher at composition time, then launch from a `SideEffect` or button `onClick`. Never launch inside the Composable body during composition.

```kotlin
@Composable
fun BluetoothPermissionScreen(
    onPermissionsResult: (Map<String, Boolean>) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = onPermissionsResult
    )

    // Build the list of permissions to request for the current API level.
    val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Button(onClick = { launcher.launch(permissions.toTypedArray()) }) {
        Text("Connect to camera")
    }
}
```

For `NEARBY_WIFI_DEVICES`, guard the request:

```kotlin
val wifiPermissions = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
```

Request each permission group in context (BLE pairing screen asks for BLE permissions; first notification-worthy action asks for `POST_NOTIFICATIONS`). Do not batch unrelated permissions into a single dialog.

## Rationale and permanently-denied recovery

Show rationale UI when `PermissionState == DENIED_RATIONALE`. Guide the user to system settings when `PermissionState == DENIED_PERMANENT`:

```kotlin
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    context.startActivity(intent)
}
```

For notifications, check grant state without triggering a system dialog:

```kotlin
val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
```

This call is valid on all API levels including below API 33.

## Layer invariants — what goes where

| Layer | Allowed | Not allowed |
|---|---|---|
| Composable | `launcher.launch(...)` in `onClick` or `SideEffect`; read `PermissionState` from ViewModel `StateFlow` | Opening sockets, calling `BluetoothGatt`, writing files, importing `ActivityCompat` for request calls |
| ViewModel | Observe `StateFlow<Map<String, PermissionState>>` from `:core:permissions`; emit UI state | Importing `android.Manifest`, calling `ActivityCompat.requestPermissions`, calling JNI |
| `:core:permissions` | `ContextCompat.checkSelfPermission`, `ActivityCompat.shouldShowRequestPermissionRationale`, `ActivityResultContracts.RequestMultiplePermissions`, `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` | Business logic, protocol state, BLE/Wi-Fi operations |
| Rust / JNI | Receive an already-opened fd, `BluetoothGatt` handle, or USB file descriptor from the Android layer | Any knowledge of Android permissions; any JNI call that checks or requests a permission |

`NoOpFujiBleClient` (the test double per `docs/android/bluetooth-architecture.md`) must mirror the same permission-check path so that UI flow tests exercise the denied/rationale/permanently-denied states without a real device.

## Foreground service: starting CameraConnectionService correctly

```kotlin
// In the Android layer (Activity or bound Service context) — after permissions are granted:
val intent = Intent(context, CameraConnectionService::class.java)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    context.startForegroundService(intent)
} else {
    context.startService(intent)
}

// Inside CameraConnectionService.onStartCommand():
// On API 34+, the system enforces that BLUETOOTH_CONNECT (or another qualifying
// connectedDevice prerequisite) is held before startForeground() is called.
// The Android layer must have already obtained that grant before starting the service.
startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
```

`ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` is available from `android.content.pm.ServiceInfo` (API 29+). For API < 29 use the two-arg `startForeground(id, notification)` overload — verify the exact compat path against `ServiceCompat.startForeground()` in `androidx.core:core`.

## Key pitfalls

1. **`maxSdkVersion="30"` omitted on legacy Bluetooth permissions.** Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` without the cap pollute the permission list shown to users on API 31+ devices, even though they are functionally inert.

2. **`neverForLocation` omitted on `BLUETOOTH_SCAN`.** Without it, the system requires `ACCESS_FINE_LOCATION` at runtime on API 31+, triggering a location permission dialog that confuses users and increases denial rate.

3. **`shouldShowRequestPermissionRationale() == false` treated as always permanently-denied.** It also returns `false` on the very first request (permission never asked). Always check `ContextCompat.checkSelfPermission()` first; combine with a persisted "have we ever requested this?" flag to distinguish the two cases.

4. **`POST_NOTIFICATIONS` or `NEARBY_WIFI_DEVICES` requested without an API-level guard.** These permission strings do not exist below API 33. Requesting them on API 31-32 will throw `IllegalArgumentException`. Always guard behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`.

5. **`FOREGROUND_SERVICE_CONNECTED_DEVICE` missing when targeting API 34+.** `startForeground()` throws `SecurityException` even if `FOREGROUND_SERVICE` is declared.

6. **`connectedDevice` FGS started without holding a qualifying runtime permission (API 34+).** The system checks that at least one of `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, or `UWB_RANGING` is granted before allowing the start.

7. **`registerForActivityResult()` called inside a click handler or Composable body (not at composition time).** This throws `IllegalStateException` because the launcher must be registered before the lifecycle reaches `STARTED`.

8. **Requesting permissions from a ViewModel or from JNI.** This violates Frameport's layer invariants. All permission request calls must originate from an Activity/Fragment/Composable that holds a registered `ActivityResultLauncher`.

9. **Unrelated permissions batched into one dialog.** Showing `POST_NOTIFICATIONS` alongside BLE pairing permissions confuses users and increases overall denial rates. Request each group in the relevant context screen.

10. **`FOREGROUND_SERVICE_CAMERA` declared in v1.** There is no background camera capture in v1. Declaring this type unnecessarily broadens the app's permission surface and is rejected if the runtime prerequisites are not met.

11. **`ACCESS_BACKGROUND_LOCATION` declared anywhere.** Frameport explicitly excludes geotagging in v1. This permission must not appear in any module manifest. When minSdk is raised above 32, `ACCESS_FINE_LOCATION` entries capped at `maxSdkVersion="30"` and `maxSdkVersion="32"` can be removed entirely.

## No-background-location invariant

`ACCESS_BACKGROUND_LOCATION` must never be declared. `ACCESS_FINE_LOCATION` appears only with explicit `maxSdkVersion` caps:
- `maxSdkVersion="30"` alongside Bluetooth permissions (BLE scan required location below API 31).
- `maxSdkVersion="32"` alongside `NEARBY_WIFI_DEVICES` (Wi-Fi P2P required location on API 31-32).

When minSdk is raised above 32, both of these entries are dead and can be deleted.

## References

- Bluetooth permissions — Android Developers: https://developer.android.com/guide/topics/connectivity/bluetooth/permissions
- Request permission to access nearby Wi-Fi devices — Android Developers: https://developer.android.com/develop/connectivity/wifi/wifi-permissions
- Notification runtime permission — Android Developers: https://developer.android.com/about/versions/13/changes/notification-permission
- Notification runtime permission (Compose) — Android Developers: https://developer.android.com/develop/ui/compose/notifications/notification-permission
- Foreground service types — Android Developers: https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground service types required (Android 14) — Android Developers: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Declare foreground services and request permissions — Android Developers: https://developer.android.com/develop/background-work/services/fgs/declare
- Changes to foreground service types for Android 15 — Android Developers: https://developer.android.com/about/versions/15/changes/foreground-service-types
- Request runtime permissions — Android Developers: https://developer.android.com/training/permissions/requesting
- App permissions best practices — Android Developers: https://developer.android.com/training/permissions/usage-notes
- ActivityResultContracts.RequestMultiplePermissions — AndroidX API reference: https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.RequestMultiplePermissions
- Behavior changes: Apps targeting Android 13 — Android Developers: https://developer.android.com/about/versions/13/behavior-changes-13
- Behavior changes: Apps targeting Android 15 — Android Developers: https://developer.android.com/about/versions/15/behavior-changes-15
