package dev.po4yka.frameport.camera.media

import dev.po4yka.frameport.camera.api.CameraObjectHandle

/**
 * Validates and generates safe display-name strings for MediaStore entries.
 *
 * PRIVACY / G2: The raw camera filename is NEVER available at this layer.
 * [CameraMediaObject.fileNameHash] is an opaque SHA-256 and must NOT be used as
 * a display name. Display names are generated deterministically from the object
 * handle and format extension via [generateSafeName].
 *
 * Validation rules (applied to any candidate display name before it reaches MediaStore):
 *   - Not blank (empty or whitespace-only).
 *   - Not "." or "..".
 *   - Does not contain path separators ('/' or '\\').
 *   - Does not contain a NUL byte (code point 0).
 *   - Does not contain ASCII control characters (code points < 0x20; this also covers NUL).
 *   - Not longer than 255 characters (POSIX / ext4 filename limit).
 *   - Does not end with a dot or a space (Windows reserved; also rejected by some Android OEMs).
 */
object FilenameValidator {
    private const val MAX_LENGTH = 255

    /**
     * Validates [candidate] as a MediaStore DISPLAY_NAME.
     *
     * Returns [Result.success] with the candidate unchanged if all rules pass.
     * Returns [Result.failure] with an [IllegalArgumentException] describing the
     * violation (message contains no user data — only the rule violated).
     *
     * This function is pure (no I/O).
     */
    fun validate(candidate: String): Result<String> {
        if (candidate.isBlank()) {
            return Result.failure(IllegalArgumentException("Display name must not be empty or blank"))
        }
        if (candidate == "." || candidate == "..") {
            return Result.failure(IllegalArgumentException("Display name must not be '.' or '..'"))
        }
        if (candidate.contains('/') || candidate.contains('\\')) {
            return Result.failure(IllegalArgumentException("Display name must not contain path separators"))
        }
        // NUL byte (code point 0). Redundant with the control-char check below, but kept
        // explicit and ASCII-source-safe so the intent is unambiguous to every editor/reviewer.
        if (candidate.any { it.code == 0 }) {
            return Result.failure(IllegalArgumentException("Display name must not contain a NUL byte"))
        }
        if (candidate.any { it.code < 0x20 }) {
            return Result.failure(IllegalArgumentException("Display name must not contain ASCII control characters"))
        }
        if (candidate.length > MAX_LENGTH) {
            return Result.failure(
                IllegalArgumentException(
                    "Display name must not exceed $MAX_LENGTH characters",
                ),
            )
        }
        if (candidate.endsWith('.') || candidate.endsWith(' ')) {
            return Result.failure(
                IllegalArgumentException(
                    "Display name must not end with a dot or space",
                ),
            )
        }
        return Result.success(candidate)
    }

    /**
     * Generates a safe, deterministic display name from [handle] and [extension].
     *
     * Pattern: "FRP_" + handle.value + "." + extension
     * Example: handle=12345678, extension="jpg" -> "FRP_12345678.jpg"
     *
     * The generated name is immediately validated through [validate]. A failure here
     * indicates a programming error (e.g. a malformed extension string).
     *
     * @param handle The [CameraObjectHandle] — an opaque integer; not user-identifiable.
     * @param extension File extension WITHOUT a leading dot (e.g. "jpg", "raf").
     * @throws IllegalArgumentException if the generated name fails validation (programming error).
     */
    fun generateSafeName(
        handle: CameraObjectHandle,
        extension: String,
    ): String {
        val candidate = "FRP_${handle.value}.$extension"
        return validate(candidate).getOrElse { cause ->
            throw IllegalArgumentException(
                "Generated display name failed validation (extension='$extension'): ${cause.message}",
                cause,
            )
        }
    }
}
