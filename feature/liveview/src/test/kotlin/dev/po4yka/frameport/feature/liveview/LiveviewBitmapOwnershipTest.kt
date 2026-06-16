package dev.po4yka.frameport.feature.liveview

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Regression guard for live-view bitmap ownership.
 *
 * Published bitmaps may still be referenced by SurfaceView callbacks after the ViewModel publishes a newer frame. The live-view feature must not manually recycle those Bitmap instances; Android GC owns their lifetime after publish.
 */
class LiveviewBitmapOwnershipTest {
    @Test
    fun liveViewFeatureDoesNotManuallyRecycleBitmaps() {
        val source =
            listOf(
                File("src/main/kotlin/dev/po4yka/frameport/feature/liveview/LiveViewScreen.kt"),
                File("feature/liveview/src/main/kotlin/dev/po4yka/frameport/feature/liveview/LiveViewScreen.kt"),
            ).first { it.exists() }
                .readText()

        assertFalse(
            "Live-view bitmap recycling is race-prone; do not call Bitmap.recycle() in this feature.",
            source.contains(".recycle("),
        )
    }
}
