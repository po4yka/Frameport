package dev.po4yka.frameport.feature.liveview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guards for live-view bitmap handling.
 *
 * 1. No manual Bitmap.recycle() calls — published bitmaps may still be referenced by
 *    SurfaceView callbacks after the ViewModel publishes a newer frame; Android GC owns
 *    their lifetime after publish.
 *
 * 2. SurfaceHolder.Callback reads bitmap via AtomicReference (H-10 fix) — the factory
 *    lambda of AndroidView runs only once, so any bitmap value captured at factory time
 *    becomes stale after a rotation/surface re-creation. The fix stores the latest frame
 *    in an AtomicReference written by the update lambda and read by the callbacks.
 */
class LiveviewBitmapOwnershipTest {
    private fun screenSource(): String =
        listOf(
            File("src/main/kotlin/dev/po4yka/frameport/feature/liveview/LiveViewScreen.kt"),
            File("feature/liveview/src/main/kotlin/dev/po4yka/frameport/feature/liveview/LiveViewScreen.kt"),
        ).first { it.exists() }
            .readText()

    private fun viewModelSource(): String =
        listOf(
            File("src/main/kotlin/dev/po4yka/frameport/feature/liveview/LiveviewViewModel.kt"),
            File("feature/liveview/src/main/kotlin/dev/po4yka/frameport/feature/liveview/LiveviewViewModel.kt"),
        ).first { it.exists() }
            .readText()

    @Test
    fun liveViewScreenDoesNotManuallyRecycleBitmaps() {
        assertFalse(
            "Live-view bitmap recycling is race-prone; do not call Bitmap.recycle() in LiveViewScreen.",
            screenSource().contains(".recycle("),
        )
    }

    @Test
    fun liveViewViewModelDoesNotManuallyRecycleBitmaps() {
        assertFalse(
            "Live-view bitmap recycling is race-prone; do not call Bitmap.recycle() in LiveviewViewModel.",
            viewModelSource().contains(".recycle("),
        )
    }

    /**
     * Regression guard for H-10: SurfaceHolder callbacks must read the latest bitmap from
     * an AtomicReference rather than from a value captured at factory time. Without this,
     * surfaceCreated/surfaceChanged after rotation re-draws the first frame, not the latest.
     *
     * Asserts that LiveViewScreen.kt uses AtomicReference as the indirection layer between
     * the AndroidView update lambda and the SurfaceHolder.Callback handlers.
     */
    @Test
    fun surfaceHolderCallbackUsesAtomicReferenceForLatestBitmap() {
        val source = screenSource()
        assertTrue(
            "H-10: SurfaceHolder.Callback must read bitmap via AtomicReference so post-rotation " +
                "surfaceCreated/surfaceChanged sees the latest frame, not a stale factory-time capture. " +
                "Expected AtomicReference usage in LiveViewScreen.kt.",
            source.contains("AtomicReference"),
        )
        // The update lambda must write into the reference (.set(...)) before drawing.
        assertTrue(
            "H-10: The AndroidView update lambda must call AtomicReference.set() to update the " +
                "latest bitmap before the SurfaceHolder callback can read it.",
            source.contains(".set(bitmap)"),
        )
        // The callbacks must read from the reference (.get()) rather than closing over a val.
        assertTrue(
            "H-10: SurfaceHolder callbacks must call AtomicReference.get() to obtain the latest " +
                "bitmap rather than closing over a stale captured value.",
            source.contains(".get()"),
        )
    }
}
