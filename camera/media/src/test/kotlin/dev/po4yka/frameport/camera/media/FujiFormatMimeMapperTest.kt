package dev.po4yka.frameport.camera.media

import dev.po4yka.frameport.camera.api.CameraMediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class FujiFormatMimeMapperTest {
    private val mapper = FujiFormatMimeMapper()

    @Test
    fun `jpeg maps to image-jpeg jpg Image category`() {
        val desc = mapper.descriptorFor(CameraMediaFormat.Jpeg)
        assertEquals("image/jpeg", desc.mimeType)
        assertEquals("jpg", desc.fileExtension)
        assertEquals(MediaCategory.Image, desc.mediaCategory)
    }

    @Test
    fun `raf maps to image-x-fuji-raf raf Image category`() {
        val desc = mapper.descriptorFor(CameraMediaFormat.Raf)
        assertEquals("image/x-fuji-raf", desc.mimeType)
        assertEquals("raf", desc.fileExtension)
        assertEquals(MediaCategory.Image, desc.mediaCategory)
    }

    @Test
    fun `heif maps to image-heif heif Image category`() {
        val desc = mapper.descriptorFor(CameraMediaFormat.Heif)
        assertEquals("image/heif", desc.mimeType)
        assertEquals("heif", desc.fileExtension)
        assertEquals(MediaCategory.Image, desc.mediaCategory)
    }

    @Test
    fun `mov maps to video-quicktime mov Video category`() {
        val desc = mapper.descriptorFor(CameraMediaFormat.Mov)
        assertEquals("video/quicktime", desc.mimeType)
        assertEquals("mov", desc.fileExtension)
        assertEquals(MediaCategory.Video, desc.mediaCategory)
    }

    @Test
    fun `unknown maps to application-octet-stream bin Unknown category`() {
        val desc = mapper.descriptorFor(CameraMediaFormat.Unknown)
        assertEquals("application/octet-stream", desc.mimeType)
        assertEquals("bin", desc.fileExtension)
        assertEquals(MediaCategory.Unknown, desc.mediaCategory)
    }

    @Test
    fun `all enum members have a non-blank mimeType`() {
        CameraMediaFormat.entries.forEach { format ->
            val desc = mapper.descriptorFor(format)
            assert(desc.mimeType.isNotBlank()) {
                "Expected non-blank mimeType for $format"
            }
        }
    }

    @Test
    fun `all enum members have a non-blank fileExtension without leading dot`() {
        CameraMediaFormat.entries.forEach { format ->
            val desc = mapper.descriptorFor(format)
            assert(desc.fileExtension.isNotBlank()) {
                "Expected non-blank fileExtension for $format"
            }
            assert(!desc.fileExtension.startsWith('.')) {
                "fileExtension must not start with '.' for $format"
            }
        }
    }
}
