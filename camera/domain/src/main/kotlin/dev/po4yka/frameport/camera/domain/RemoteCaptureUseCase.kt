package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import dev.po4yka.frameport.camera.api.FujiBleClient
import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.RemoteCaptureError
import dev.po4yka.frameport.camera.api.RemoteCaptureRequest
import dev.po4yka.frameport.camera.api.RemoteCaptureState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.ShutterAction
import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Orchestrates remote capture operations over BLE or PTP-IP.
 *
 * Boundary contract:
 * - This use case NEVER accesses BluetoothGatt, JNI, or any Android platform I/O type directly.
 * - BLE I/O is delegated entirely to [FujiBleClient].
 * - PTP-IP I/O is delegated entirely to [FujiNativeSdk.remoteShutter].
 * - The compatibility gate fires FIRST; [RemoteCaptureError.IncompatibleCamera] terminates the
 *   flow immediately without attempting any camera I/O.
 *
 * BLE wire mapping (master-constants.md §5, ble-wifi-discovery.md §ShootingRequest):
 *   The 2-byte LE payload is built by the Rust layer (fuji-ble-protocol::build_shooting_request).
 *   On the Kotlin side we write the pre-encoded bytes supplied by [BleShotPayloads].
 *   Characteristic UUID: [CHR_SHOOTING_REQUEST] = "7fcf49c6-4ff0-4777-a03d-1a79166af7a8"
 *   Source: master-constants.md §5. [H] LFJ, XPN, XBL
 *
 * PTP-IP wire mapping (master-constants.md §3):
 *   HalfPress / FullPress → InitiateCapture (0x100E) via RemoteSession (Rust layer)
 *   Release               → TerminateOpenCapture (0x1018) via RemoteSession (Rust layer)
 *   Wire encoding is the responsibility of the Rust layer (fuji-ptpip::RemoteSession).
 *
 * Privacy: no MAC address, SSID, passphrase, or raw payload is logged at any level.
 * See docs/security/privacy-local-first.md.
 *
 * See: docs/adr/0003-ble-client-abstraction.md, docs/protocol/wifi-ptp-ip.md,
 *      docs/android/bluetooth-architecture.md, docs/product/feature-scope.md §Remote Capture
 */
class RemoteCaptureUseCase
    @Inject
    constructor(
        private val fujiBleClient: FujiBleClient,
        private val fujiNativeSdk: FujiNativeSdk,
        private val capabilityChecker: RemoteCapabilityChecker,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Execute a remote capture request, emitting typed [RemoteCaptureState] transitions.
         *
         * The returned flow is cold: no I/O is initiated until collection begins. Each call
         * to [invoke] produces an independent flow; multiple concurrent calls are permitted
         * but callers are responsible for sequencing half-press → full-press → release.
         *
         * Emitted states (happy path):
         * - BLE path:  HalfPressed/FullPressed/Idle → CaptureComplete(objectHandle=null) [for FullPress]
         * - PTP-IP path: HalfPressed/FullPressed/Idle → CaptureComplete(objectHandle=null) [for FullPress]
         *
         * Terminal error state: [RemoteCaptureState.Error] — the flow completes after emission.
         *
         * @param request The capture request (BLE or PTP-IP shutter action).
         * @param cameraModel The camera model identifier from identity negotiation. Null if unknown.
         */
        // cancel-safe: cold Flow; each suspend call inside is individually cancel-safe; flowOn(ioDispatcher)
        // keeps the collector cooperative. On cancellation no partial camera state is committed because
        // each BLE write and SDK call is atomic at the wire level.
        operator fun invoke(
            request: RemoteCaptureRequest,
            cameraModel: String? = null,
        ): Flow<RemoteCaptureState> =
            flow {
                // NOTE: no leading Idle emission here. The ViewModel's StateFlow starts as Idle;
                // emitting Idle from the use case would produce a spurious intermediate Idle when
                // transitioning between states (e.g. HalfPress → FullPress). Callers that need
                // to observe Idle as a terminal state (e.g. Release) receive it via the transitional
                // emit inside executeBleShutter / executePtpIpShutter.

                // Compatibility gate: check allowlist BEFORE any camera I/O.
                // Emit IncompatibleCamera and complete immediately for unknown/unverified models.
                if (!capabilityChecker.isRemoteCapable(cameraModel)) {
                    emit(RemoteCaptureState.Error(RemoteCaptureError.IncompatibleCamera))
                    return@flow
                }

                when (request) {
                    is RemoteCaptureRequest.BleShutter -> executeBleShutter(request.action)
                    is RemoteCaptureRequest.PtpIpShutter -> executePtpIpShutter(request.action, request.sessionId)
                }
            }.flowOn(ioDispatcher)

        // cancel-safe: FujiBleClient.write enqueues one op; cancellation removes the op before dispatch.
        private suspend fun kotlinx.coroutines.flow.FlowCollector<RemoteCaptureState>.executeBleShutter(
            action: ShutterAction,
        ) {
            // Guard: BLE must be connected before issuing a characteristic write.
            if (fujiBleClient.connectionState.value != BleConnectionState.Connected) {
                emit(RemoteCaptureState.Error(RemoteCaptureError.NotConnectedBle))
                return
            }

            // Emit the transitional state matching the requested action.
            when (action) {
                ShutterAction.HalfPress -> emit(RemoteCaptureState.HalfPressed)
                ShutterAction.FullPress -> emit(RemoteCaptureState.FullPressed)
                ShutterAction.Release -> emit(RemoteCaptureState.Idle)
            }

            // Build the 2-byte LE payload for the CHR_SHOOTING_REQUEST characteristic.
            // Wire encoding mirrors fuji-ble-protocol::build_shooting_request (Rust layer).
            // Values from master-constants.md §5: S0=0x0000, S1=0x0001, S2=0x0002 (u16 LE).
            // No heap allocation: the payload is a fixed-size ByteArray(2).
            val payload = BleShotPayloads.forAction(action)

            val writeResult =
                fujiBleClient.write(
                    characteristic = CharacteristicId(CHR_SHOOTING_REQUEST),
                    payload = payload,
                )

            if (writeResult.isFailure) {
                val detail = writeResult.exceptionOrNull()?.javaClass?.simpleName ?: "write-failed"
                emit(RemoteCaptureState.Error(RemoteCaptureError.BleWriteFailed(detail)))
                return
            }

            // For the BLE path, characteristic write success = capture acknowledged.
            // The BLE protocol is fire-and-forget at the transport level; no CaptureComplete event
            // is received over BLE (that event comes over PTP-IP only).
            if (action == ShutterAction.FullPress) {
                emit(RemoteCaptureState.CaptureComplete(objectHandle = null))
            }
        }

        // cancel-safe: FujiNativeSdk.remoteShutter is a single withContext call; cancel-safe per its contract.
        private suspend fun kotlinx.coroutines.flow.FlowCollector<RemoteCaptureState>.executePtpIpShutter(
            action: ShutterAction,
            sessionId: SessionId,
        ) {
            // Emit the transitional state matching the requested action.
            when (action) {
                ShutterAction.HalfPress -> {
                    emit(RemoteCaptureState.HalfPressed)
                }

                ShutterAction.FullPress -> {
                    emit(RemoteCaptureState.FullPressed)
                    emit(RemoteCaptureState.CapturingInProgress)
                }

                ShutterAction.Release -> {
                    emit(RemoteCaptureState.Idle)
                }
            }

            val result = fujiNativeSdk.remoteShutter(sessionId = sessionId, action = action)

            if (result.isFailure) {
                val detail = result.exceptionOrNull()?.javaClass?.simpleName ?: "sdk-failed"
                emit(RemoteCaptureState.Error(RemoteCaptureError.PtpIpFailed(detail)))
                return
            }

            // For the PTP-IP path, RemoteSession success = shutter command dispatched.
            // The CaptureComplete event (0x400D) is handled by EventChannelReader (Rust layer).
            // The domain layer reports CaptureComplete when the SDK confirms dispatch for FullPress.
            // objectHandle is unavailable here; the EventChannelReader delivers it asynchronously (M16).
            if (action == ShutterAction.FullPress) {
                emit(RemoteCaptureState.CaptureComplete(objectHandle = null))
            }
        }

        private companion object {
            /**
             * Characteristic UUID for remote shutter (Shooting Request).
             * Source: master-constants.md §5, ble-wifi-discovery.md §Characteristics — Camera Control.
             * [H] LFJ, XPN, XBL
             *
             * Mirrors [dev.po4yka.frameport.camera.bluetooth.BleConstants.CHR_SHOOTING_REQUEST].
             * The value is intentionally duplicated here (not imported from :camera:bluetooth)
             * to keep :camera:domain free of a dependency on the Android-platform bluetooth module.
             */
            const val CHR_SHOOTING_REQUEST = "7fcf49c6-4ff0-4777-a03d-1a79166af7a8"
        }
    }

/**
 * Pre-encoded 2-byte LE payloads for the CHR_SHOOTING_REQUEST BLE characteristic.
 *
 * Wire format: u16 little-endian. Values from master-constants.md §5:
 *   S0 (Release)   = 0x0000 LE → [0x00, 0x00]
 *   S1 (HalfPress) = 0x0001 LE → [0x01, 0x00]
 *   S2 (FullPress)  = 0x0002 LE → [0x02, 0x00]
 *
 * Source: ble-wifi-discovery.md §"ShootingRequest (Remote Shutter)". [H] LFJ, XPN, XBL
 *
 * No heap allocation for each call: the payloads are pre-allocated constants.
 */
internal object BleShotPayloads {
    /**
     * S0: Release / cancel. u16 LE = 0x0000.
     * Source: master-constants.md §5 [H] LFJ, XPN, XBL
     */
    private val S0_RELEASE = byteArrayOf(0x00, 0x00)

    /**
     * S1: Half-press (autofocus). u16 LE = 0x0001.
     * Source: master-constants.md §5 [H] LFJ, XPN, XBL
     */
    private val S1_HALF_PRESS = byteArrayOf(0x01, 0x00)

    /**
     * S2: Full-press (capture). u16 LE = 0x0002.
     * Source: master-constants.md §5 [H] LFJ, XPN, XBL
     */
    private val S2_FULL_PRESS = byteArrayOf(0x02, 0x00)

    /**
     * Returns a fresh copy of the appropriate 2-byte payload for [action].
     *
     * A copy is returned so callers cannot mutate the internal pre-allocated arrays.
     * The allocation is small (2 bytes) and occurs at most once per user gesture.
     */
    fun forAction(action: ShutterAction): ByteArray =
        when (action) {
            ShutterAction.Release -> S0_RELEASE.copyOf()
            ShutterAction.HalfPress -> S1_HALF_PRESS.copyOf()
            ShutterAction.FullPress -> S2_FULL_PRESS.copyOf()
        }
}
