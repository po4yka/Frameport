package dev.po4yka.frameport.nativebridge

class JniNativeFujiSdk(
    private val loader: NativeLibraryLoader = NativeLibraryLoader(),
    private val fallback: NativeFujiSdk = NoOpNativeFujiSdk(),
) : NativeFujiSdk {
    private val libraryState: NativeLibraryState by lazy { loader.load() }

    override val diagnosticState: NativeFujiDiagnosticState
        get() = if (libraryState.isLoaded) {
            NativeFujiDiagnosticState(
                isNativeLibraryLoaded = true,
                message = libraryState.message,
            )
        } else {
            NativeFujiDiagnosticState(
                isNativeLibraryLoaded = false,
                message = libraryState.message,
                failure = libraryState.failure,
            )
        }

    override fun version(): String = callOrFallback(
        nativeCall = NativeFujiJni::nativeVersion,
        fallbackCall = fallback::version,
    )

    override fun initialize(): Result<Unit> = callResultOrFallback(
        nativeCall = {
            NativeFujiJni.nativeInitialize().toUnitResult("native_initialize")
        },
        fallbackCall = fallback::initialize,
    )

    override fun shutdown(): Result<Unit> = callResultOrFallback(
        nativeCall = {
            NativeFujiJni.nativeShutdown().toUnitResult("native_shutdown")
        },
        fallbackCall = fallback::shutdown,
    )

    override fun openNoopSession(): Result<NativeCameraSession> = callResultOrFallback(
        nativeCall = {
            val sessionId = NativeFujiJni.nativeOpenNoopSession()
            if (sessionId > 0L) {
                Result.success(
                    NativeCameraSession(
                        id = sessionId,
                        cameraId = null,
                        isNoOp = true,
                    ),
                )
            } else {
                Result.failure(IllegalStateException("native_open_noop_session failed with code $sessionId"))
            }
        },
        fallbackCall = fallback::openNoopSession,
    )

    override fun closeSession(session: NativeCameraSession): Result<Unit> = callResultOrFallback(
        nativeCall = {
            NativeFujiJni.nativeCloseSession(session.id).toUnitResult("native_close_session")
        },
        fallbackCall = {
            fallback.closeSession(session)
        },
    )

    private fun <T> callOrFallback(
        nativeCall: () -> T,
        fallbackCall: () -> T,
    ): T {
        if (!libraryState.isLoaded) return fallbackCall()
        return try {
            nativeCall()
        } catch (_: UnsatisfiedLinkError) {
            fallbackCall()
        } catch (_: SecurityException) {
            fallbackCall()
        }
    }

    private fun <T> callResultOrFallback(
        nativeCall: () -> Result<T>,
        fallbackCall: () -> Result<T>,
    ): Result<T> {
        if (!libraryState.isLoaded) return fallbackCall()
        return try {
            nativeCall()
        } catch (error: UnsatisfiedLinkError) {
            fallbackCall().recoverCatching { throw error }
        } catch (error: SecurityException) {
            fallbackCall().recoverCatching { throw error }
        }
    }

    private fun Int.toUnitResult(operation: String): Result<Unit> =
        if (this == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("$operation failed with code $this"))
        }
}
