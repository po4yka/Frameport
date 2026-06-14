package dev.po4yka.frameport.camera.media

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a [CameraMediaFormat] enum value (already decoded upstream from the PTP wire code)
 * to a [FormatDescriptor] carrying a MIME type and file extension.
 *
 * PTP wire-code rationale (for documentation only; codes are never runtime inputs here):
 *   Jpeg  → 0x3801 (EXIF/JPEG) / 0x3808 (JFIF)
 *   Raf   → 0xB103 (Fujifilm RAW)
 *   Heif  → 0xB982 (HEIF)
 *   Mov   → 0x300D (QuickTime)
 *
 * MIME types follow ADR-0004 rules:
 *   Jpeg  → image/jpeg          (.jpg)
 *   Raf   → image/x-fuji-raf    (.raf)   [octet-stream fallback noted in ADR-0004]
 *   Heif  → image/heif          (.heif)
 *   Mov   → video/quicktime     (.mov)
 *   Unknown → application/octet-stream (.bin)
 */
@Singleton
class FujiFormatMimeMapper
    @Inject
    constructor() {
        /**
         * Returns the [FormatDescriptor] for the given [format].
         * This function is pure (no I/O, no side effects).
         *
         * Note: [CameraMediaFormat] has exactly {Jpeg, Raf, Heif, Mov, Unknown}.
         * There is no Mp4 member; no branch for it is present.
         */
        fun descriptorFor(format: CameraMediaFormat): FormatDescriptor =
            when (format) {
                CameraMediaFormat.Jpeg -> {
                    FormatDescriptor(
                        mimeType = "image/jpeg",
                        fileExtension = "jpg",
                        mediaCategory = MediaCategory.Image,
                    )
                }

                CameraMediaFormat.Raf -> {
                    FormatDescriptor(
                        mimeType = "image/x-fuji-raf",
                        fileExtension = "raf",
                        mediaCategory = MediaCategory.Image,
                    )
                }

                CameraMediaFormat.Heif -> {
                    FormatDescriptor(
                        mimeType = "image/heif",
                        fileExtension = "heif",
                        mediaCategory = MediaCategory.Image,
                    )
                }

                CameraMediaFormat.Mov -> {
                    FormatDescriptor(
                        mimeType = "video/quicktime",
                        fileExtension = "mov",
                        mediaCategory = MediaCategory.Video,
                    )
                }

                CameraMediaFormat.Unknown -> {
                    FormatDescriptor(
                        mimeType = "application/octet-stream",
                        fileExtension = "bin",
                        mediaCategory = MediaCategory.Unknown,
                    )
                }
            }
    }

/**
 * MIME type, file extension, and media category for a [CameraMediaFormat].
 *
 * [fileExtension] does NOT include the leading dot.
 */
data class FormatDescriptor(
    val mimeType: String,
    val fileExtension: String,
    val mediaCategory: MediaCategory,
)

/** Broad media category used to select the correct MediaStore collection. */
enum class MediaCategory {
    Image,
    Video,
    Unknown,
}
