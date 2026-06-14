package dev.po4yka.frameport.camera.media

import dev.po4yka.frameport.camera.api.CameraObjectHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameValidatorTest {
    // ─── Valid names ──────────────────────────────────────────────────────────

    @Test
    fun `valid simple name passes`() {
        val result = FilenameValidator.validate("FRP_12345678.jpg")
        assertTrue(result.isSuccess)
        assertEquals("FRP_12345678.jpg", result.getOrThrow())
    }

    @Test
    fun `valid name with letters digits underscore passes`() {
        assertTrue(FilenameValidator.validate("FRP_999.raf").isSuccess)
    }

    @Test
    fun `valid name with an interior space passes`() {
        // A space (0x20) is NOT a control char and is a legal MediaStore display-name char.
        assertTrue(FilenameValidator.validate("FRP 123.jpg").isSuccess)
    }

    // ─── Empty / blank ────────────────────────────────────────────────────────

    @Test
    fun `empty string fails`() {
        assertFalse(FilenameValidator.validate("").isSuccess)
    }

    @Test
    fun `blank string fails`() {
        assertFalse(FilenameValidator.validate("   ").isSuccess)
    }

    // ─── Reserved names ───────────────────────────────────────────────────────

    @Test
    fun `dot fails`() {
        assertFalse(FilenameValidator.validate(".").isSuccess)
    }

    @Test
    fun `double dot fails`() {
        assertFalse(FilenameValidator.validate("..").isSuccess)
    }

    // ─── Path separators ─────────────────────────────────────────────────────

    @Test
    fun `forward slash fails`() {
        assertFalse(FilenameValidator.validate("dir/file.jpg").isSuccess)
    }

    @Test
    fun `backslash fails`() {
        assertFalse(FilenameValidator.validate("dir\\file.jpg").isSuccess)
    }

    // ─── Control characters (built via toChar() so source stays pure ASCII) ────

    @Test
    fun `NUL byte fails`() {
        val name = "file" + 0.toChar() + ".jpg"
        assertFalse(FilenameValidator.validate(name).isSuccess)
    }

    @Test
    fun `control character 0x01 fails`() {
        val name = "file" + 0x01.toChar() + ".jpg"
        assertFalse(FilenameValidator.validate(name).isSuccess)
    }

    @Test
    fun `control character 0x1F fails`() {
        val name = "file" + 0x1F.toChar() + ".jpg"
        assertFalse(FilenameValidator.validate(name).isSuccess)
    }

    // ─── Length ───────────────────────────────────────────────────────────────

    @Test
    fun `name of exactly 255 chars passes`() {
        val name = "a".repeat(255)
        assertTrue(FilenameValidator.validate(name).isSuccess)
    }

    @Test
    fun `name of 256 chars fails`() {
        val name = "a".repeat(256)
        assertFalse(FilenameValidator.validate(name).isSuccess)
    }

    // ─── Trailing dot / space ─────────────────────────────────────────────────

    @Test
    fun `trailing dot fails`() {
        assertFalse(FilenameValidator.validate("file.").isSuccess)
    }

    @Test
    fun `trailing space fails`() {
        assertFalse(FilenameValidator.validate("file ").isSuccess)
    }

    // ─── generateSafeName ─────────────────────────────────────────────────────

    @Test
    fun `generateSafeName produces FRP prefix plus handle plus extension`() {
        val handle = CameraObjectHandle(12345678L)
        val name = FilenameValidator.generateSafeName(handle, "jpg")
        assertEquals("FRP_12345678.jpg", name)
    }

    @Test
    fun `generateSafeName result passes validate`() {
        val handle = CameraObjectHandle(99999999L)
        val name = FilenameValidator.generateSafeName(handle, "raf")
        assertTrue(FilenameValidator.validate(name).isSuccess)
    }

    @Test
    fun `generateSafeName works for all supported extensions`() {
        val handle = CameraObjectHandle(1L)
        listOf("jpg", "raf", "heif", "mov", "bin").forEach { ext ->
            val name = FilenameValidator.generateSafeName(handle, ext)
            assertTrue(
                "Expected valid name for extension $ext",
                FilenameValidator.validate(name).isSuccess,
            )
        }
    }

    @Test
    fun `generateSafeName with handle value zero produces valid name`() {
        val handle = CameraObjectHandle(0L)
        val name = FilenameValidator.generateSafeName(handle, "jpg")
        assertEquals("FRP_0.jpg", name)
        assertTrue(FilenameValidator.validate(name).isSuccess)
    }

    @Test
    fun `generateSafeName with max Long handle value produces valid name`() {
        val handle = CameraObjectHandle(Long.MAX_VALUE)
        val name = FilenameValidator.generateSafeName(handle, "jpg")
        assertTrue(FilenameValidator.validate(name).isSuccess)
        assertTrue(name.startsWith("FRP_"))
    }
}
