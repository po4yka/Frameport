package dev.po4yka.frameport.camera.usb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.CameraUsbConnector
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.UsbDeviceRef
import dev.po4yka.frameport.camera.api.UsbSessionState
import dev.po4yka.frameport.camera.api.UsbTransportHandle
import dev.po4yka.frameport.camera.domain.ImportObjectUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the USB PTP session screen.
 *
 * Collects [CameraUsbConnector.state] and drives the USB connection lifecycle:
 * permission request → session open → import via [ImportObjectUseCase] → session close.
 *
 * Invariants:
 * - Never calls JNI directly — delegates through [CameraUsbConnector] and [ImportObjectUseCase].
 * - Never uses GlobalScope.
 * - [ImportObjectUseCase] is invoked unchanged; USB transport is transparent to the use case.
 * - All mutations run on [viewModelScope] (Main dispatcher by default).
 *
 * The [importState] flow is updated for every item imported via [importObject]. Only one
 * import is driven at a time; concurrent imports require a dedicated import queue ViewModel
 * (see [dev.po4yka.frameport.feature.importmedia.ImportViewModel]).
 */
@HiltViewModel
class UsbSessionViewModel
    @Inject
    constructor(
        private val cameraUsbConnector: CameraUsbConnector,
        private val importObjectUseCase: ImportObjectUseCase,
    ) : ViewModel() {
        /** Mirrors [CameraUsbConnector.state] for UI consumption. */
        val usbState: StateFlow<UsbSessionState> = cameraUsbConnector.state

        private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)

        /** State of the most recently started import operation. */
        val importState: StateFlow<ImportState> = _importState.asStateFlow()

        init {
            // Collect USB connector state; no additional transformation needed —
            // the UI collects usbState directly.
            cameraUsbConnector.state
                .onEach { /* state forwarded via usbState; observe in init for side-effects if needed */ }
                .launchIn(viewModelScope)
        }

        /**
         * Request UsbManager permission for [device].
         *
         * Suspends until the user responds to the system dialog. On success,
         * the connector state transitions to [UsbSessionState.OpeningDevice] and
         * [openSession] should be called next.
         *
         * Call from a UI event handler (e.g. a button click).
         */
        fun requestPermission(device: UsbDeviceRef) {
            viewModelScope.launch {
                cameraUsbConnector.requestPermission(device)
                // On success the connector emits OpeningDevice; on failure it emits
                // PermissionDenied or Error. The UI observes usbState for transitions.
            }
        }

        /**
         * Open a USB PTP session for [device] using the provided [descriptors].
         *
         * Drives the full open sequence:
         * OpeningDevice → DeviceOpen → FdHandoff → RustTransportOpen
         * → PtpSessionOpening → UsbSessionReady
         *
         * [descriptors] are the raw USB interface descriptor bytes retrieved by the caller
         * from [android.hardware.usb.UsbDeviceConnection.getRawDescriptors] before calling
         * this method. The ViewModel does not access UsbManager directly.
         *
         * On success the connector emits [UsbSessionState.UsbSessionReady].
         */
        fun openSession(
            device: UsbDeviceRef,
            descriptors: ByteArray,
        ) {
            viewModelScope.launch {
                cameraUsbConnector.openSession(device, descriptors)
            }
        }

        /**
         * Import a media object using the EXISTING [ImportObjectUseCase].
         *
         * The USB transport is transparent to [ImportObjectUseCase]: it receives the
         * [dev.po4yka.frameport.camera.api.SessionId] from [handle] and invokes the
         * same transfer path as a Wi-Fi session. The use case interface is NOT modified.
         *
         * @param handle The [UsbTransportHandle] from [UsbSessionState.UsbSessionReady].
         * @param objectHandle The camera object to import.
         */
        fun importObject(
            handle: UsbTransportHandle,
            objectHandle: CameraObjectHandle,
        ) {
            // The existing ImportObjectUseCase.invoke(sessionId, handle) operates over
            // &mut dyn CommandTransport in Rust — the USB session id is fully interchangeable
            // with a Wi-Fi session id from the Rust perspective.
            importObjectUseCase
                .invoke(handle.sessionId, objectHandle)
                .onEach { state -> _importState.value = state }
                .launchIn(viewModelScope)
        }

        /**
         * Close the USB session and release all resources.
         *
         * Safe to call at any point; idempotent. After this call the connector state
         * transitions to [UsbSessionState.Closing] → [UsbSessionState.Closed].
         *
         * @param handle The handle from [UsbSessionState.UsbSessionReady]. Pass the last
         *   known handle; passing a stale handle is safe — the connector is idempotent.
         */
        fun closeSession(handle: UsbTransportHandle) {
            viewModelScope.launch {
                cameraUsbConnector.close(handle)
            }
        }

        override fun onCleared() {
            // ViewModel is being destroyed. Best-effort: if a session is still open,
            // drive it to Closed so the Rust fd is released and the UsbDeviceConnection
            // is closed cleanly. This guards against the caller forgetting to call
            // closeSession() when navigating away.
            val currentState = cameraUsbConnector.state.value
            if (currentState is UsbSessionState.UsbSessionReady) {
                viewModelScope.launch {
                    cameraUsbConnector.close(currentState.handle)
                }
            }
            super.onCleared()
        }
    }
