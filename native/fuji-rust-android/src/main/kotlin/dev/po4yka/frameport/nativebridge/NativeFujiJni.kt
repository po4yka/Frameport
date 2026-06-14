package dev.po4yka.frameport.nativebridge

class NativeFujiJni private constructor() {
    companion object {
        // Integer sentinels returned by jint-returning JNI entry points.
        // Kept in sync with the Rust constants in fuji-ffi/src/lib.rs.
        const val OK: Int = 0
        const val ERR_NOT_INITIALIZED: Int = -1
        const val ERR_INVALID_SESSION: Int = -2
        const val ERR_PANIC: Int = -100

        @JvmStatic
        external fun nativeVersion(): String

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
         * [liveViewFd] is an Android-owned, dup'd raw socket fd. Rust dups it
         * immediately and takes ownership of the dup. Android MUST NOT close or use
         * [liveViewFd] after this call returns successfully.
         *
         * [callback] is registered as a JNI GlobalRef inside Rust. It is released
         * when [nativeLiveViewStop] completes or on panic.
         *
         * Returns [OK] (0) on successful worker start, or a negative ERR_* sentinel
         * on failure. On panic, throws [NativeException] and returns [ERR_PANIC].
         *
         * @param sessionId Active PTP-IP session id (> 0).
         * @param liveViewFd Android-owned dup'd socket fd for port 55742. Ownership transfers to Rust.
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
    }
}
