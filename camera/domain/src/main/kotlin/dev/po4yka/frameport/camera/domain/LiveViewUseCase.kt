package dev.po4yka.frameport.camera.domain

import dev.po4yka.frameport.camera.api.FujiNativeSdk
import dev.po4yka.frameport.camera.api.SessionId
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Domain use case that wraps [FujiNativeSdk.liveViewFrames] into a [Flow<ByteArray>]
 * the ViewModel collects.
 *
 * Boundary contract:
 * - This use case NEVER accesses BluetoothGatt, Wi-Fi APIs, JNI, or any Android
 *   platform I/O type directly.
 * - All camera I/O is delegated to [FujiNativeSdk.liveViewFrames].
 * - The caller (ViewModel) is responsible for obtaining [liveViewFd] from
 *   [dev.po4yka.frameport.camera.api.CameraWifiConnector.openLiveViewSocket] before
 *   invoking this use case.
 *
 * Backpressure / latest-frame-wins contract:
 * The underlying [kotlinx.coroutines.channels.callbackFlow] inside [FujiNativeSdk]
 * uses [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST] so the Rust read
 * loop is never blocked on a slow Compose consumer. Callers MUST NOT assume every
 * frame is delivered.
 *
 * fd ownership contract for [liveViewFd]:
 * The caller must pass an Android-owned, dup'd raw socket fd already bound to the
 * camera network and connected to port 55742 (LIVEVIEW_CHANNEL_PORT = 0xD9BE,
 * master-constants.md §1). Ownership transfers to the Rust layer when the flow is
 * collected. Android MUST NOT close or use this fd after starting collection.
 * See docs/rust/fd-ownership.md and ADR-0002.
 *
 * Privacy: no fd values, IP addresses, session ids as raw device identifiers, or
 * filenames are logged. See privacy-local-first.md.
 *
 * See: docs/protocol/transfer-liveview.md §6g, docs/adr/0002-wifi-socket-fd-handoff.md
 */
class LiveViewUseCase
    @Inject
    constructor(
        private val fujiNativeSdk: FujiNativeSdk,
    ) {
        /**
         * Returns a cold [Flow<ByteArray>] of JPEG frames from the live-view channel.
         *
         * Each emission is a complete JPEG frame (SOI … EOI inclusive) parsed by the
         * Rust zero-alloc live-view parser. Collecting the flow starts the Rust read loop;
         * cancelling the collector stops it.
         *
         * @param sessionId Active PTP-IP session returned by [FujiNativeSdk.openWifiSession].
         * @param liveViewFd Android-owned, dup'd fd for the live-view socket (port 55742).
         *   Ownership transfers to Rust on collection. MUST NOT be used after this call.
         * @return Cold [Flow<ByteArray>] of JPEG frames. Latest-frame-wins; frames may be dropped.
         */
        // cancel-safe: delegates to FujiNativeSdk.liveViewFrames which is a callbackFlow with
        // awaitClose. Cancellation triggers awaitClose which stops the Rust read loop cleanly.
        operator fun invoke(
            sessionId: SessionId,
            liveViewFd: Int,
        ): Flow<ByteArray> = fujiNativeSdk.liveViewFrames(sessionId = sessionId, liveViewFd = liveViewFd)
    }
