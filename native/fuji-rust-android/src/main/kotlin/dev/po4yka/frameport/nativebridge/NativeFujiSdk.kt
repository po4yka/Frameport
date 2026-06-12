package dev.po4yka.frameport.nativebridge

import dev.po4yka.frameport.core.model.CameraId

interface NativeFujiSdk {
    val diagnosticState: NativeFujiDiagnosticState

    fun version(): String

    fun initialize(): Result<Unit>

    fun shutdown(): Result<Unit>

    fun openNoopSession(): Result<NativeCameraSession>

    fun closeSession(session: NativeCameraSession): Result<Unit>
}

data class NativeCameraSession(
    val id: Long,
    val cameraId: CameraId?,
    val isNoOp: Boolean,
) {
    companion object {
        val NoOp = NativeCameraSession(
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
