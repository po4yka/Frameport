package dev.po4yka.frameport.nativebridge

class NoOpNativeFujiSdk(
    override val diagnosticState: NativeFujiDiagnosticState = NativeFujiDiagnosticState(
        isNativeLibraryLoaded = false,
        message = "Native Fuji SDK is running in no-op mode.",
    ),
) : NativeFujiSdk {
    override fun version(): String = "0.1.0-noop"

    override fun initialize(): Result<Unit> = Result.success(Unit)

    override fun shutdown(): Result<Unit> = Result.success(Unit)

    override fun openNoopSession(): Result<NativeCameraSession> = Result.success(NativeCameraSession.NoOp)

    override fun closeSession(session: NativeCameraSession): Result<Unit> = Result.success(Unit)
}
