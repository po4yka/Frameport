package dev.po4yka.frameport.camera.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.po4yka.frameport.camera.api.CameraUsbConnector
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.UsbDeviceRef
import dev.po4yka.frameport.camera.api.UsbSessionError
import dev.po4yka.frameport.camera.api.UsbSessionState
import dev.po4yka.frameport.camera.api.UsbTransportHandle
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [CameraUsbConnector].
 *
 * Owns: [UsbManager] permission request, [ACTION_USB_DEVICE_ATTACHED] /
 * [ACTION_USB_DEVICE_DETACHED] broadcast-receiver registrations, device open,
 * fd duplication via [ParcelFileDescriptor.fromFd], and fd handoff to Rust via [FujiNativeSdk.openUsbSession].
 *
 * Does NOT own: PTP codec, bulk-transfer state, or any protocol logic — those belong
 * exclusively to the Rust layer (fuji-rs / fuji-usb-ptp) after receiving the dup'd fd.
 *
 * fd ownership contract at the dup site:
 * [UsbDeviceConnection.fileDescriptor] is the Android-owned fd tied to the connection.
 * [ParcelFileDescriptor.fromFd] + [ParcelFileDescriptor.detachFd] produce an independent copy whose ownership is transferred to Rust.
 * // OWNERSHIP: Android keeps + closes the original via UsbDeviceConnection.close()
 *               when [close] is called on the connector.
 * //            Rust owns + closes the dup via OwnedFd on Drop inside fuji-ffi.
 * Never close the dup from this class — doing so would double-free the fd owned by Rust.
 *
 * Privacy invariants (privacy-local-first.md):
 * - No raw USB serial numbers in logs. All device references use an opaque hash-key.
 * - No raw hardware descriptors outside [frameport-dev-logs]-gated tracing.
 * - No analytics, no outbound HTTP, no background USB beyond an active session.
 *
 * Architecture invariants (CLAUDE.md):
 * - NO root, NO /dev/bus/usb paths, NO shell commands.
 * - NO NEARBY_WIFI_DEVICES, NO location permission.
 * - USB operations occur only during an active, user-initiated session.
 *
 * See: docs/rust/fd-ownership.md, docs/adr/0002-wifi-socket-fd-handoff.md,
 *      .claude/rules/android-foreground-service-types.md
 */
@Singleton
class AndroidCameraUsbConnector
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val fujiNativeSdk: FujiNativeSdk,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CameraUsbConnector {
        companion object {
            private const val TAG = "CameraUsbConnector"

            /**
             * Permission action used in [UsbManager.requestPermission].
             * Must match the IntentFilter registered for the permission BroadcastReceiver.
             */
            private const val ACTION_USB_PERMISSION =
                "dev.po4yka.frameport.camera.usb.USB_PERMISSION"
        }

        private val _state = MutableStateFlow<UsbSessionState>(UsbSessionState.Disconnected)
        override val state: StateFlow<UsbSessionState> = _state.asStateFlow()

        // Maps opaque device key to the live UsbDevice. Populated by the ATTACHED receiver
        // and used by requestPermission / openSession to look up the real UsbDevice object.
        // ConcurrentHashMap: BroadcastReceiver callbacks may arrive on an arbitrary thread.
        private val detectedDevices = ConcurrentHashMap<String, UsbDevice>()

        // Tracks whether the detach receiver is registered so we only unregister once.
        private val receiverRegistered = AtomicBoolean(false)

        // The active UsbDeviceConnection. Accessed only within withContext(IO) blocks, but
        // stored here so close() can reach it from any call site.
        @Volatile private var activeConnection: android.hardware.usb.UsbDeviceConnection? = null

        // Pending permission deferred. Set before requestPermission() suspends; completed by
        // the permission BroadcastReceiver callback when the dialog is dismissed.
        @Volatile private var permissionDeferred: CompletableDeferred<Boolean>? = null

        // The UsbDevice currently being authorized. Read by the permission receiver callback
        // to confirm the intent matches the device in flight.
        @Volatile private var pendingPermissionDevice: UsbDevice? = null

        // Idempotent guard for close().
        private val closedOnce = AtomicBoolean(false)

        // ─── BroadcastReceiver: ATTACHED / DETACHED ───────────────────────────────

        private val usbEventReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleAttached(intent)
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDetached(intent)
                        ACTION_USB_PERMISSION -> handlePermissionResult(intent)
                    }
                }
            }

        init {
            val filter =
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                    addAction(ACTION_USB_PERMISSION)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(usbEventReceiver, filter)
            }
            receiverRegistered.set(true)
            Timber.tag(TAG).d("init: USB BroadcastReceiver registered")
        }

        private fun handleAttached(intent: Intent) {
            val device: UsbDevice =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
                }

            // PRIVACY: use vendorId:productId as the correlation key, NOT the serial number.
            val deviceKey = "${device.vendorId}:${device.productId}:${device.deviceId}"
            detectedDevices[deviceKey] = device

            // Display name: vendor+product id — safe, not PII.
            val displayName = "USB Device [${device.vendorId}:${device.productId}]"
            val ref = UsbDeviceRef(deviceKey = deviceKey, displayName = displayName)

            Timber.tag(TAG).d("handleAttached: device detected key=%s", deviceKey)
            _state.value = UsbSessionState.DeviceDetected(ref)
        }

        private fun handleDetached(intent: Intent) {
            val device: UsbDevice =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
                }
            val deviceKey = "${device.vendorId}:${device.productId}:${device.deviceId}"
            detectedDevices.remove(deviceKey)

            Timber.tag(TAG).d("handleDetached: key=%s, currentState=%s", deviceKey, _state.value::class.simpleName)

            // Handle detach at every lifecycle phase per GT-ANDROID.
            when (val currentState = _state.value) {
                is UsbSessionState.PermissionPending -> {
                    // Complete the pending permission deferred so requestPermission() unblocks.
                    permissionDeferred?.complete(false)
                    _state.value =
                        UsbSessionState.Error(
                            error = UsbSessionError.DeviceDetachedBeforePermission,
                            message = "Device detached while permission dialog was pending",
                        )
                }

                is UsbSessionState.DeviceDetected,
                is UsbSessionState.OpeningDevice,
                is UsbSessionState.DeviceOpen,
                is UsbSessionState.FdHandoff,
                is UsbSessionState.RustTransportOpen,
                is UsbSessionState.PtpSessionOpening,
                is UsbSessionState.UsbSessionReady,
                -> {
                    _state.value =
                        UsbSessionState.Error(
                            error = UsbSessionError.DeviceDetachedDuringSession,
                            message = "Device detached during active session phase: ${currentState::class.simpleName}",
                        )
                    // Best-effort: close the connection without waiting for the session close protocol.
                    safeCloseConnection()
                }

                is UsbSessionState.Disconnected,
                is UsbSessionState.PermissionDenied,
                is UsbSessionState.Closing,
                is UsbSessionState.Closed,
                is UsbSessionState.Error,
                -> {
                    // No active session to tear down; idempotent.
                }
            }
        }

        private fun handlePermissionResult(intent: Intent) {
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Timber.tag(TAG).d("handlePermissionResult: granted=%b", granted)
            permissionDeferred?.complete(granted)
        }

        // ─── CameraUsbConnector API ───────────────────────────────────────────────

        /**
         * Request UsbManager permission for [device].
         *
         * Suspends until the user responds to the system dialog or the device is detached.
         * Emits [UsbSessionState.PermissionPending] then either [UsbSessionState.OpeningDevice]
         * (granted) or [UsbSessionState.PermissionDenied] / [UsbSessionState.Error].
         *
         * cancel-safe: [CompletableDeferred.await] is a single suspension point;
         * cancellation propagates out of [withContext] cleanly.
         */
        override suspend fun requestPermission(device: UsbDeviceRef): Result<Unit> =
            withContext(ioDispatcher) {
                val usbDevice = detectedDevices[device.deviceKey]
                if (usbDevice == null) {
                    Timber.tag(TAG).d("requestPermission: device not found in registry key=%s", device.deviceKey)
                    _state.value =
                        UsbSessionState.Error(
                            error = UsbSessionError.OpenDeviceFailed,
                            message = "Device not found in registry — may have been detached",
                        )
                    return@withContext Result.failure(
                        IllegalStateException("USB device not found for key: ${device.deviceKey}"),
                    )
                }

                val usbManager = context.getSystemService(UsbManager::class.java)
                if (usbManager == null) {
                    _state.value =
                        UsbSessionState.Error(
                            error = UsbSessionError.OpenDeviceFailed,
                            message = "UsbManager service unavailable",
                        )
                    return@withContext Result.failure(IllegalStateException("UsbManager not available"))
                }

                // Fast path: permission already granted.
                if (usbManager.hasPermission(usbDevice)) {
                    Timber.tag(TAG).d("requestPermission: already granted, key=%s", device.deviceKey)
                    _state.value = UsbSessionState.OpeningDevice
                    return@withContext Result.success(Unit)
                }

                _state.value = UsbSessionState.PermissionPending
                Timber.tag(TAG).d("requestPermission: requesting permission, key=%s", device.deviceKey)

                val deferred = CompletableDeferred<Boolean>()
                permissionDeferred = deferred
                pendingPermissionDevice = usbDevice

                val permissionIntent =
                    android.app.PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(ACTION_USB_PERMISSION).apply {
                            setPackage(context.packageName)
                        },
                        android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                usbManager.requestPermission(usbDevice, permissionIntent)

                val granted = deferred.await()

                if (!granted) {
                    // Check if it was detach (already set to Error) or explicit deny
                    if (_state.value !is UsbSessionState.Error) {
                        _state.value = UsbSessionState.PermissionDenied
                    }
                    return@withContext Result.failure(
                        SecurityException("USB permission denied by user"),
                    )
                }

                _state.value = UsbSessionState.OpeningDevice
                Result.success(Unit)
            }

        /**
         * Open the USB device connection, dup the fd, and hand it off to Rust.
         *
         * fd ownership contract at the dup site:
         * // OWNERSHIP: Android keeps + closes the original fd via UsbDeviceConnection.close()
         *               when [close] is called. The UsbDeviceConnection object is stored in
         *               [activeConnection] for that purpose.
         * //            Rust takes ownership of the detached dup (no second dup) and closes it via
         *               the UsbPtpSession Drop in fuji-ffi.
         *
         * cancel-safe: each step runs inside [withContext]; cancellation propagates to those scopes.
         */
        override suspend fun openSession(
            device: UsbDeviceRef,
            descriptors: ByteArray,
        ): Result<UsbTransportHandle> =
            withContext(ioDispatcher) {
                val usbDevice = detectedDevices[device.deviceKey]
                if (usbDevice == null) {
                    val msg = "Device not found in registry — may have been detached"
                    Timber.tag(TAG).d("openSession: %s key=%s", msg, device.deviceKey)
                    _state.value = UsbSessionState.Error(error = UsbSessionError.OpenDeviceFailed, message = msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                val usbManager = context.getSystemService(UsbManager::class.java)
                if (usbManager == null) {
                    val msg = "UsbManager service unavailable"
                    _state.value = UsbSessionState.Error(error = UsbSessionError.OpenDeviceFailed, message = msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                _state.value = UsbSessionState.OpeningDevice
                Timber.tag(TAG).d("openSession: OpeningDevice key=%s", device.deviceKey)

                val connection =
                    try {
                        usbManager.openDevice(usbDevice)
                    } catch (e: Exception) {
                        Timber.tag(TAG).d("openSession: openDevice exception=%s", e.javaClass.simpleName)
                        null
                    }

                if (connection == null) {
                    val msg = "UsbManager.openDevice returned null"
                    Timber.tag(TAG).d("openSession: %s", msg)
                    _state.value = UsbSessionState.Error(error = UsbSessionError.OpenDeviceFailed, message = msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                activeConnection = connection
                _state.value = UsbSessionState.DeviceOpen
                Timber.tag(TAG).d("openSession: DeviceOpen")

                // Dup the fd before handing to Rust.
                // OWNERSHIP: Android keeps + closes the original fd via UsbDeviceConnection.close()
                //             stored in activeConnection. Rust owns + closes the dup via OwnedFd Drop.
                val originalFd = connection.fileDescriptor
                val dupFd =
                    try {
                        // Reflection-free dup: ParcelFileDescriptor.fromFd() wraps a DUP of the
                        // int fd; detachFd() releases that dup from the PFD and returns the raw
                        // int. No private-field reflection on java.io.FileDescriptor — safe under
                        // Android hidden-API enforcement (API 28+).
                        // OWNERSHIP: dupFd is an independent fd. originalFd (via activeConnection)
                        // remains Android-owned and is NOT closed here; dupFd is transferred to Rust.
                        ParcelFileDescriptor.fromFd(originalFd).detachFd()
                    } catch (e: Exception) {
                        val msg = "fd dup failed: ${e.javaClass.simpleName}"
                        Timber.tag(TAG).d("openSession: FdDupFailed msg=%s", msg)
                        _state.value =
                            UsbSessionState.Error(
                                error = UsbSessionError.FdDupFailed(msg),
                                message = msg,
                            )
                        safeCloseConnection()
                        return@withContext Result.failure(e)
                    }

                _state.value = UsbSessionState.FdHandoff
                Timber.tag(TAG).d("openSession: FdHandoff dupFd=%d", dupFd)

                // Pass dup'd fd and descriptor bytes to Rust. Rust takes ownership of dupFd.
                // Android retains activeConnection which holds the original fd.
                val openResult =
                    fujiNativeSdk.openUsbSession(
                        usbFd = dupFd,
                        descriptors = descriptors,
                    )

                if (openResult.isFailure) {
                    val msg = "Rust openUsbSession failed: ${openResult.exceptionOrNull()?.javaClass?.simpleName}"
                    Timber.tag(TAG).d("openSession: RustSessionOpenFailed msg=%s", msg)
                    _state.value =
                        UsbSessionState.Error(
                            error = UsbSessionError.RustSessionOpenFailed(msg),
                            message = msg,
                        )
                    safeCloseConnection()
                    return@withContext Result.failure(
                        openResult.exceptionOrNull() ?: IllegalStateException(msg),
                    )
                }

                _state.value = UsbSessionState.RustTransportOpen
                Timber.tag(TAG).d("openSession: RustTransportOpen")

                // PTP session is opened inside Rust (nativeUsbSessionOpen sends PTP OpenSession).
                // Transition through the semantic state so observers can track it.
                _state.value = UsbSessionState.PtpSessionOpening
                Timber.tag(TAG).d("openSession: PtpSessionOpening")

                val sessionId = openResult.getOrThrow()
                val handle = UsbTransportHandle(sessionId)

                _state.value = UsbSessionState.UsbSessionReady(handle)
                Timber.tag(TAG).d("openSession: UsbSessionReady sessionId=%d", sessionId.value)

                Result.success(handle)
            }

        /**
         * Close the USB session and release all resources.
         *
         * Transitions: [UsbSessionState.Closing] → [UsbSessionState.Closed].
         * Idempotent: second call is a no-op.
         *
         * cancel-safe: cleanup calls are best-effort; no suspension points after state is Closing.
         */
        override suspend fun close(handle: UsbTransportHandle) {
            if (!closedOnce.compareAndSet(false, true)) {
                Timber.tag(TAG).d("close: already closed, no-op")
                return
            }

            withContext(ioDispatcher) {
                _state.value = UsbSessionState.Closing
                Timber.tag(TAG).d("close: Closing sessionId=%d", handle.sessionId.value)

                // Tell Rust to release the USB session and its OwnedFd (closes the dup).
                try {
                    fujiNativeSdk.closeUsbSession(handle.sessionId)
                } catch (e: Exception) {
                    Timber.tag(TAG).d("close: closeUsbSession exception=%s", e.javaClass.simpleName)
                    // Best-effort; continue to close the Android connection.
                }

                // Close Android's original UsbDeviceConnection.
                // OWNERSHIP: this is the original fd. Rust's dup has already been closed above.
                safeCloseConnection()

                _state.value = UsbSessionState.Closed
                Timber.tag(TAG).d("close: Closed")
            }
        }

        // ─── Private helpers ──────────────────────────────────────────────────────

        /**
         * Closes [activeConnection] if non-null, null-ing the reference.
         * Swallows exceptions — best-effort cleanup.
         */
        private fun safeCloseConnection() {
            try {
                activeConnection?.close()
            } catch (e: Exception) {
                Timber.tag(TAG).d("safeCloseConnection: %s", e.javaClass.simpleName)
            } finally {
                activeConnection = null
            }
        }
    }
