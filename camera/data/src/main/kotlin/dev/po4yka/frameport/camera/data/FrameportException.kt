package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.core.model.FrameportError

/**
 * Carries a typed [FrameportError] across a boundary that requires a [Throwable]
 * (Result.failure / Flow exception). The data layer maps raw JNI throwables into
 * this wrapper so that no untyped exception escapes the :camera:data boundary into
 * the domain/UI layers. The [message] is the redacted [FrameportError.message];
 * raw native messages (which may contain filenames or paths) are never propagated.
 */
internal class FrameportException(
    val frameportError: FrameportError,
) : Exception(frameportError.message)

/**
 * Classifies an arbitrary native/JNI [Throwable] into a redacted [FrameportError].
 *
 * Only the throwable's class name is retained (safe, contains no user data); the
 * raw message is intentionally dropped per privacy-local-first.md. Richer
 * classification (mapping specific NativeException subtypes) lands in M09.
 */
internal fun Throwable.toRedactedFrameportError(): FrameportError =
    (this as? FrameportException)?.frameportError
        ?: FrameportError.Unknown("native SDK operation failed (${this::class.simpleName})")
