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
    }
}
