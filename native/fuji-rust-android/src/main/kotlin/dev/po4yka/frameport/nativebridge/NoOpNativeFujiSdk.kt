package dev.po4yka.frameport.nativebridge

class NoOpNativeFujiSdk(
    override val diagnosticState: NativeFujiDiagnosticState =
        NativeFujiDiagnosticState(
            isNativeLibraryLoaded = false,
            message = "Native Fuji SDK is running in no-op mode.",
        ),
) : NativeFujiSdk {
    override fun version(): String? = "0.1.0-noop"

    override fun initialize(): Result<Unit> = Result.success(Unit)

    override fun shutdown(): Result<Unit> = Result.success(Unit)

    override fun openNoopSession(): Result<NativeCameraSession> = Result.success(NativeCameraSession.NoOp)

    override fun closeSession(session: NativeCameraSession): Result<Unit> = Result.success(Unit)

    override fun openWifiSession(
        commandFd: Int,
        endpointMetadata: String,
    ): Result<NativeCameraSession> = Result.success(NativeCameraSession.NoOp)

    override fun listMedia(session: NativeCameraSession): Result<ByteArray> = Result.success(ByteArray(0))

    override fun getThumbnail(
        session: NativeCameraSession,
        objectHandle: Long,
    ): Result<ByteArray> = Result.success(ByteArray(0))

    override fun downloadObjectToFd(
        session: NativeCameraSession,
        objectHandle: Long,
        outputFd: Int,
    ): Result<Unit> = Result.success(Unit)

    override fun cancelTransfer(transferId: Long): Result<Unit> = Result.success(Unit)

    override fun nativeLiveViewStart(
        sessionId: Long,
        liveViewFd: Int,
        callback: LiveViewFrameCallback,
    ): Result<Unit> = Result.success(Unit)

    override fun nativeLiveViewStop(sessionId: Long): Result<Unit> = Result.success(Unit)

    override fun openUsbSession(
        fd: Int,
        descriptors: ByteArray,
    ): Result<Long> = Result.success(-1L)

    override fun closeUsbSession(sessionId: Long): Result<Unit> = Result.success(Unit)
}
