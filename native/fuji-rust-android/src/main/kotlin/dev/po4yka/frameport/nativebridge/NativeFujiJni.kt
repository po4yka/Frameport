package dev.po4yka.frameport.nativebridge

class NativeFujiJni private constructor() {
    companion object {
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
    }
}
