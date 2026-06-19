package dev.po4yka.frameport.nativebridge

import dev.po4yka.frameport.core.model.CameraId

/**
 * Per-frame callback for live-view JPEG frames delivered from the Rust read loop.
 *
 * Implementations receive complete JPEG frames (SOI … EOI inclusive) as [ByteArray].
 * This allocation is the unavoidable JVM marshaling cost at the JNI boundary and is
 * explicitly excluded from the Rust zero-alloc parser constraint.
 *
 * Contract:
 * - Called on the Rust worker thread (attached to the JVM as a daemon thread).
 * - MUST NOT block; use [kotlinx.coroutines.channels.SendChannel.trySend] with
 *   [kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST] to stay latest-frame-wins.
 * - MUST NOT retain [jpeg] beyond the call (the backing buffer may be reused).
 */
fun interface LiveViewFrameCallback {
    fun onLiveViewFrame(jpeg: ByteArray)
}

interface NativeFujiSdk {
    val diagnosticState: NativeFujiDiagnosticState

    // Returns null when the native library returns a null version string on error.
    // Callers must substitute a sentinel (e.g. "unknown") rather than force-unwrapping.
    fun version(): String?

    fun initialize(): Result<Unit>

    fun shutdown(): Result<Unit>

    fun openNoopSession(): Result<NativeCameraSession>

    fun closeSession(session: NativeCameraSession): Result<Unit>

    /**
     * Open a Wi-Fi PTP-IP session using [commandFd].
     *
     * fd ownership contract:
     * [commandFd] is a detached Android socket fd. Rust borrows and dups it during
     * this call, then owns only the dup. The caller is responsible for closing
     * [commandFd] after this call returns; the higher-level camera data adapter
     * does that for production callers.
     */
    fun openWifiSession(
        commandFd: Int,
        endpointMetadata: String,
    ): Result<NativeCameraSession>

    /**
     * Returns a serialised media-object-list payload. Deserialisation is
     * deferred to a future milestone; callers receive the raw bytes and must
     * not assume any encoding beyond "non-empty means data is present".
     */
    fun listMedia(session: NativeCameraSession): Result<ByteArray>

    /**
     * Returns a serialised thumbnail payload for [objectHandle]. Raw bytes;
     * deserialisation is deferred to a future milestone.
     */
    fun getThumbnail(
        session: NativeCameraSession,
        objectHandle: Long,
    ): Result<ByteArray>

    /**
     * Stream the object identified by [objectHandle] from the camera into the
     * Android-owned [outputFd]. Rust dups the fd and closes its copy when
     * done; the caller must close the original after this call returns.
     */
    fun downloadObjectToFd(
        session: NativeCameraSession,
        objectHandle: Long,
        outputFd: Int,
    ): Result<Unit>

    fun cancelTransfer(transferId: Long): Result<Unit>

    /**
     * Start the Rust live-view read loop for [sessionId] over [liveViewFd].
     *
     * fd ownership contract:
     * [liveViewFd] is an Android-owned, detached raw socket fd already bound to the
     * camera network and connected to port 55742 (LIVEVIEW_CHANNEL_PORT = 0xD9BE,
     * master-constants.md §1). Rust dups it immediately inside [nativeLiveViewStart]
     * and takes ownership of the dup. The caller must close [liveViewFd] after this
     * call returns; the higher-level camera data adapter does that for production callers.
     *
     * [callback] is registered as a JNI GlobalRef inside the Rust worker. The
     * GlobalRef is released when [nativeLiveViewStop] completes or on panic.
     *
     * The worker thread is a dedicated std::thread running a current-thread tokio
     * runtime; the JNIEnv is obtained inside the worker via
     * `JavaVM::attach_current_thread_as_daemon`. The JNIEnv is NEVER captured across
     * thread boundaries — see jni-error-mapping.md.
     *
     * @param sessionId Session id returned by [openWifiSession].
     * @param liveViewFd Detached fd for port 55742. Rust borrows and dups it.
     * @param callback Receives each parsed JPEG frame on the Rust worker thread.
     * @return [Result.success] if the worker started; [Result.failure] on native error.
     */
    fun nativeLiveViewStart(
        sessionId: Long,
        liveViewFd: Int,
        callback: LiveViewFrameCallback,
    ): Result<Unit>

    /**
     * Stop the Rust live-view read loop for [sessionId].
     *
     * Sets the atomic stop flag, waits for the worker thread to drain and exit,
     * releases the JNI GlobalRef for the callback, removes the session from the
     * live-view registry, and closes the Rust dup of the fd.
     *
     * Idempotent: calling stop on an unknown or already-stopped session returns
     * [Result.success] without side effects.
     *
     * @param sessionId Session id of the running live-view worker.
     * @return [Result.success] on clean stop; [Result.failure] on panic (ERR_PANIC).
     */
    fun nativeLiveViewStop(sessionId: Long): Result<Unit>

    /**
     * Open a USB PTP session for a camera device connected over UsbManager.
     *
     * fd ownership contract:
     * [fd] is an Android-produced dup of [UsbDeviceConnection.fileDescriptor].
     * // OWNERSHIP: Android keeps + closes the original via UsbDeviceConnection.close().
     * //            Rust owns + closes the dup via OwnedFd on Drop in fuji-ffi.
     * [descriptors] are raw USB interface descriptor bytes; Rust validates every
     * length field before indexing (no alignment guarantee on ByteArray from JVM).
     *
     * @param fd Dup'd raw fd for the USB device. Ownership transfers to Rust.
     * @param descriptors Raw USB interface descriptor bytes for endpoint discovery.
     * @return [Result.success] with the Rust-assigned session id; [Result.failure] on error.
     */
    fun openUsbSession(
        fd: Int,
        descriptors: ByteArray,
    ): Result<Long>

    /**
     * Close a USB PTP session previously opened via [openUsbSession].
     *
     * Rust drops the OwnedFd (closing the dup) and removes the session from
     * USB_SESSIONS. Idempotent: unknown session ids return [Result.success].
     *
     * @param sessionId Session id returned by [openUsbSession].
     * @return [Result.success] on clean close; [Result.failure] on panic (ERR_PANIC).
     */
    fun closeUsbSession(sessionId: Long): Result<Unit>
}

data class NativeCameraSession(
    val id: Long,
    val cameraId: CameraId?,
    val isNoOp: Boolean,
) {
    companion object {
        val NoOp =
            NativeCameraSession(
                id = -1L,
                cameraId = null,
                isNoOp = true,
            )
    }
}

data class NativeFujiDiagnosticState(
    val isNativeLibraryLoaded: Boolean,
    val message: String,
    val failure: String? = null,
)
