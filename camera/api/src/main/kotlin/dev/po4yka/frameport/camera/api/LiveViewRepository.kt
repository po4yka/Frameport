package dev.po4yka.frameport.camera.api

import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer repository for live-view frame streaming.
 *
 * Owned by :camera:data. :camera:domain depends on this interface; it never touches
 * [FujiNativeSdk] or any JNI/Android-platform type directly.
 *
 * Backpressure / latest-frame-wins contract:
 * The underlying [kotlinx.coroutines.channels.callbackFlow] in the implementation uses
 * [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST] so the Rust read loop is
 * never blocked on a slow Compose consumer. Callers MUST NOT assume every frame is
 * delivered — only the most recent frame is guaranteed at any point.
 *
 * fd ownership contract for [liveViewFd]:
 * The caller must pass an Android-owned, detached raw socket fd already bound to the
 * camera network and connected to port 55742 (LIVEVIEW_CHANNEL_PORT, master-constants.md §1).
 * The implementation borrows and dups it when the flow is collected; the data-layer
 * adapter closes the detached fd after the start call returns. Callers MUST NOT close or
 * use this fd after starting collection.
 * See docs/rust/fd-ownership.md and ADR-0002.
 *
 * Privacy: no fd values, IP addresses, session ids, or filenames are logged.
 * See privacy-local-first.md.
 */
interface LiveViewRepository {
    /**
     * Returns a cold [Flow<ByteArray>] of JPEG frames from the live-view channel.
     *
     * Each emission is a complete JPEG frame (SOI … EOI inclusive) parsed by the
     * Rust zero-alloc live-view parser. Collecting the flow starts the Rust read loop;
     * cancelling the collector stops it.
     *
     * @param sessionId Active PTP-IP session.
     * @param liveViewFd Detached fd for the live-view socket (port 55742).
     *   The implementation borrows and dups it on collection. MUST NOT be used after this call.
     * @return Cold [Flow<ByteArray>] of JPEG frames. Latest-frame-wins; frames may be dropped.
     */
    // cancel-safe: delegates to FujiNativeSdk.liveViewFrames which is a callbackFlow with
    // awaitClose. Cancellation triggers awaitClose which stops the Rust read loop cleanly.
    fun liveViewFrames(
        sessionId: SessionId,
        liveViewFd: Int,
    ): Flow<ByteArray>
}
