package dev.po4yka.frameport.core.model

/**
 * Wraps a typed [FrameportError] as an [Exception] so it can flow through
 * [Result.failure] / [Result.getOrThrow] without losing type information at the
 * use-case boundary.
 *
 * Usage in OpenCameraSessionUseCase failure branch:
 * ```
 * Result.failure<SessionId>(FrameportErrorException(FrameportError.TransportUnavailable(...)))
 * ```
 *
 * Usage in callers:
 * ```
 * result.onFailure { throwable ->
 *     val error = (throwable as? FrameportErrorException)?.error
 *         ?: FrameportError.Unknown(throwable.message ?: "unknown")
 * }
 * ```
 */
class FrameportErrorException(
    val error: FrameportError,
) : Exception(error.message)
