package dev.po4yka.frameport.nativebridge

class JniNativeFujiSdk(
    private val loader: NativeLibraryLoader = NativeLibraryLoader(),
    private val fallback: NativeFujiSdk = NoOpNativeFujiSdk(),
) : NativeFujiSdk {
    private val libraryState: NativeLibraryState by lazy { loader.load() }

    override val diagnosticState: NativeFujiDiagnosticState
        get() =
            if (libraryState.isLoaded) {
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

    override fun version(): String? =
        callOrFallback(
            nativeCall = NativeFujiJni::nativeVersion,
            fallbackCall = fallback::version,
        )

    override fun initialize(): Result<Unit> =
        callResultOrFallback(
            nativeCall = {
                NativeFujiJni.nativeInitialize().toUnitResult("native_initialize")
            },
            fallbackCall = fallback::initialize,
        )

    override fun shutdown(): Result<Unit> =
        callResultOrFallback(
            nativeCall = {
                NativeFujiJni.nativeShutdown().toUnitResult("native_shutdown")
            },
            fallbackCall = fallback::shutdown,
        )

    override fun openNoopSession(): Result<NativeCameraSession> =
        callResultOrFallback(
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
                    Result.failure(sessionId.toInt().toSessionError("native_open_noop_session"))
                }
            },
            fallbackCall = fallback::openNoopSession,
        )

    override fun closeSession(session: NativeCameraSession): Result<Unit> =
        callResultOrFallback(
            nativeCall = {
                NativeFujiJni.nativeCloseSession(session.id).toUnitResult("native_close_session")
            },
            fallbackCall = {
                fallback.closeSession(session)
            },
        )

    override fun openWifiSession(
        commandFd: Int,
        endpointMetadata: String,
    ): Result<NativeCameraSession> =
        callResultOrFallback(
            nativeCall = {
                val sessionId = NativeFujiJni.nativeOpenWifiSession(commandFd, endpointMetadata)
                if (sessionId > 0L) {
                    Result.success(
                        NativeCameraSession(
                            id = sessionId,
                            cameraId = null,
                            isNoOp = false,
                        ),
                    )
                } else {
                    Result.failure(sessionId.toInt().toSessionError("native_open_wifi_session"))
                }
            },
            fallbackCall = { fallback.openWifiSession(commandFd, endpointMetadata) },
        )

    override fun listMedia(session: NativeCameraSession): Result<ByteArray> =
        callResultOrFallback(
            nativeCall = {
                val bytes = NativeFujiJni.nativeListMedia(session.id)
                if (bytes != null) {
                    Result.success(bytes)
                } else {
                    Result.failure(
                        NativeBridgeError.InvalidSession("native_list_media", NativeFujiJni.ERR_INVALID_SESSION),
                    )
                }
            },
            fallbackCall = { fallback.listMedia(session) },
        )

    override fun getThumbnail(
        session: NativeCameraSession,
        objectHandle: Long,
    ): Result<ByteArray> =
        callResultOrFallback(
            nativeCall = {
                val bytes = NativeFujiJni.nativeGetThumbnail(session.id, objectHandle)
                if (bytes != null) {
                    Result.success(bytes)
                } else {
                    Result.failure(
                        NativeBridgeError.InvalidSession("native_get_thumbnail", NativeFujiJni.ERR_INVALID_SESSION),
                    )
                }
            },
            fallbackCall = { fallback.getThumbnail(session, objectHandle) },
        )

    override fun downloadObjectToFd(
        session: NativeCameraSession,
        objectHandle: Long,
        outputFd: Int,
    ): Result<Unit> =
        callResultOrFallback(
            nativeCall = {
                NativeFujiJni
                    .nativeDownloadObjectToFd(session.id, objectHandle, outputFd)
                    .toUnitResult("native_download_object_to_fd")
            },
            fallbackCall = { fallback.downloadObjectToFd(session, objectHandle, outputFd) },
        )

    override fun cancelTransfer(transferId: Long): Result<Unit> =
        callResultOrFallback(
            nativeCall = {
                NativeFujiJni.nativeCancelTransfer(transferId).toUnitResult("native_cancel_transfer")
            },
            fallbackCall = { fallback.cancelTransfer(transferId) },
        )

    override fun nativeLiveViewStart(
        sessionId: Long,
        liveViewFd: Int,
        callback: LiveViewFrameCallback,
    ): Result<Unit> =
        callResultOrFallback(
            nativeCall = {
                NativeFujiJni
                    .nativeLiveViewStart(sessionId, liveViewFd, callback)
                    .toUnitResult("native_liveview_start")
            },
            fallbackCall = { fallback.nativeLiveViewStart(sessionId, liveViewFd, callback) },
        )

    override fun nativeLiveViewStop(sessionId: Long): Result<Unit> =
        callResultOrFallback(
            nativeCall = {
                NativeFujiJni
                    .nativeLiveViewStop(sessionId)
                    .toUnitResult("native_liveview_stop")
            },
            fallbackCall = { fallback.nativeLiveViewStop(sessionId) },
        )

    override fun openUsbSession(
        fd: Int,
        descriptors: ByteArray,
    ): Result<Long> =
        callResultOrFallback(
            nativeCall = {
                val sessionId = NativeFujiJni.nativeUsbSessionOpen(fd, descriptors)
                if (sessionId > 0L) {
                    Result.success(sessionId)
                } else {
                    Result.failure(sessionId.toInt().toSessionError("native_usb_session_open"))
                }
            },
            fallbackCall = { fallback.openUsbSession(fd, descriptors) },
        )

    override fun closeUsbSession(sessionId: Long): Result<Unit> =
        callResultOrFallback(
            nativeCall = {
                NativeFujiJni.nativeUsbSessionClose(sessionId).toUnitResult("native_usb_session_close")
            },
            fallbackCall = { fallback.closeUsbSession(sessionId) },
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
        } catch (_: NativeException) {
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
        } catch (error: NativeException) {
            Result.failure(error)
        }
    }

    /**
     * Maps a JNI integer sentinel to a typed [Result].
     * 0 ([NativeFujiJni.OK]) -> success; negative sentinels -> [NativeBridgeError].
     */
    private fun Int.toUnitResult(operation: String): Result<Unit> =
        if (this == NativeFujiJni.OK) {
            Result.success(Unit)
        } else {
            Result.failure(toSessionError(operation))
        }

    /**
     * Maps a known negative JNI sentinel integer to the appropriate [NativeBridgeError] subtype.
     * Negative session-id returns from [nativeOpenNoopSession], [nativeOpenWifiSession], and
     * [nativeUsbSessionOpen] are cast to [Int] before calling this helper.
     */
    private fun Int.toSessionError(operation: String): NativeBridgeError =
        when (this) {
            NativeFujiJni.ERR_NOT_INITIALIZED -> NativeBridgeError.NotInitialized(operation)
            NativeFujiJni.ERR_INVALID_SESSION -> NativeBridgeError.InvalidSession(operation, this)
            NativeFujiJni.ERR_PANIC -> NativeBridgeError.Panic(operation)
            else -> NativeBridgeError.Unknown(operation, this)
        }
}
