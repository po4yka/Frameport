package dev.po4yka.frameport.nativebridge

class NativeFujiJni private constructor() {
    companion object {
        // Integer sentinels returned by jint-returning JNI entry points.
        // Kept in sync with the Rust constants in fuji-ffi/src/lib.rs.
        const val OK: Int = 0
        const val ERR_NOT_INITIALIZED: Int = -1
        const val ERR_INVALID_SESSION: Int = -2
        const val ERR_PANIC: Int = -100

        // Returns null on error (Rust side may return a null jstring on failure);
        // callers must handle null and fall back to a sentinel such as "unknown".
        @JvmStatic
        external fun nativeVersion(): String?

        @JvmStatic
        external fun nativeInitialize(): Int

        @JvmStatic
        external fun nativeShutdown(): Int

        @JvmStatic
        external fun nativeOpenNoopSession(): Long

        @JvmStatic
        external fun nativeCloseSession(sessionId: Long): Int

        @JvmStatic
        external fun nativeOpenWifiSession(
            commandFd: Int,
            endpointMetadata: String,
        ): Long

        @JvmStatic
        external fun nativeListMedia(sessionId: Long): ByteArray?

        @JvmStatic
        external fun nativeGetThumbnail(
            sessionId: Long,
            objectHandle: Long,
        ): ByteArray?

        @JvmStatic
        external fun nativeDownloadObjectToFd(
            sessionId: Long,
            objectHandle: Long,
            outputFd: Int,
        ): Int

        @JvmStatic
        external fun nativeCancelTransfer(transferId: Long): Int

        /**
         * Start the Rust live-view read loop.
         *
         * fd ownership contract:
         * [liveViewFd] is an Android-owned, detached raw socket fd. Rust dups it
         * immediately and takes ownership of the dup. The Java/Kotlin caller must
         * close [liveViewFd] after this call returns and must not use it again.
         *
         * [callback] is registered as a JNI GlobalRef inside Rust. It is released
         * when [nativeLiveViewStop] completes or on panic.
         *
         * Returns [OK] (0) on successful worker start, or a negative ERR_* sentinel
         * on failure. On panic, throws [NativeException] and returns [ERR_PANIC].
         *
         * @param sessionId Active PTP-IP session id (> 0).
         * @param liveViewFd Detached socket fd for port 55742. Rust borrows and dups it.
         * @param callback Per-frame callback; called on the Rust worker thread (JVM daemon thread).
         */
        @JvmStatic
        external fun nativeLiveViewStart(
            sessionId: Long,
            liveViewFd: Int,
            callback: LiveViewFrameCallback,
        ): Int

        /**
         * Stop the Rust live-view read loop for [sessionId].
         *
         * Sets the atomic stop flag, joins the worker thread, releases the callback
         * GlobalRef, and removes the session from the live-view registry.
         *
         * Idempotent: unknown or already-stopped sessions return [OK] without error.
         *
         * Returns [OK] (0) on clean stop, or [ERR_PANIC] + [NativeException] on panic.
         *
         * @param sessionId Session id passed to [nativeLiveViewStart].
         */
        @JvmStatic
        external fun nativeLiveViewStop(sessionId: Long): Int

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
         * Returns a positive session id (Long > 0) on success, or a negative ERR_*
         * sentinel on failure. On panic, throws [NativeException] and returns [ERR_PANIC].
         *
         * @param fd      Dup'd raw fd for the USB device. Ownership transfers to Rust.
         * @param descriptors Raw USB interface descriptor bytes for endpoint discovery.
         */
        @JvmStatic
        external fun nativeUsbSessionOpen(
            fd: Int,
            descriptors: ByteArray,
        ): Long

        /**
         * Close a USB PTP session previously opened via [nativeUsbSessionOpen].
         *
         * Rust drops the OwnedFd (closing the dup) and removes the session from
         * USB_SESSIONS. Idempotent: unknown session ids return [OK].
         *
         * Returns [OK] (0) on success, or [ERR_PANIC] + [NativeException] on panic.
         *
         * @param sessionId Session id returned by [nativeUsbSessionOpen].
         */
        @JvmStatic
        external fun nativeUsbSessionClose(sessionId: Long): Int
    }
}
