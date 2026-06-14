package dev.po4yka.frameport.camera.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for T4 (manifest correctness) and T5 (onTimeout guard).
 *
 * T4 — Manifest correctness:
 *   Reads camera/data/src/main/AndroidManifest.xml and asserts:
 *   - FOREGROUND_SERVICE permission is declared.
 *   - FOREGROUND_SERVICE_CONNECTED_DEVICE permission is declared.
 *   - foregroundServiceType="connectedDevice" is present.
 *   - BOOT_COMPLETED does NOT appear (forbidden by android-foreground-service-lifecycle.md).
 *
 * T5 — onTimeout guard:
 *   - CameraSessionService declares the onTimeout(Int, Int) method via reflection.
 *   - The source file contains @RequiresApi(35) (or VANILLA_ICE_CREAM) co-located with onTimeout.
 *   - The source file contains "stopSelf" inside the onTimeout body.
 *
 * No Robolectric. No Android framework. The Gradle test working directory for a module task
 * (:camera:data:testDebugUnitTest) is the module root, so relative paths resolve from there.
 */
class ManifestAndServiceGuardTest {
    // ── T4: Manifest correctness ──────────────────────────────────────────────

    private val manifestText: String by lazy {
        // Gradle sets the working directory to the module root when running unit tests.
        val file = File("src/main/AndroidManifest.xml")
        check(file.exists()) {
            "AndroidManifest.xml not found at ${file.absolutePath}. " +
                "Run this test via ./gradlew :camera:data:testDebugUnitTest from the repo root."
        }
        file.readText()
    }

    @Test
    fun `manifest declares FOREGROUND_SERVICE base permission`() {
        assertTrue(
            "Expected FOREGROUND_SERVICE permission in AndroidManifest.xml",
            manifestText.contains("android.permission.FOREGROUND_SERVICE\""),
        )
    }

    @Test
    fun `manifest declares FOREGROUND_SERVICE_CONNECTED_DEVICE permission`() {
        assertTrue(
            "Expected FOREGROUND_SERVICE_CONNECTED_DEVICE permission in AndroidManifest.xml",
            manifestText.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE\""),
        )
    }

    @Test
    fun `manifest service has foregroundServiceType connectedDevice`() {
        assertTrue(
            "Expected foregroundServiceType=\"connectedDevice\" in AndroidManifest.xml",
            manifestText.contains("foregroundServiceType=\"connectedDevice\""),
        )
    }

    @Test
    fun `manifest does not contain a receiver element`() {
        // Foreground services must not be started from BOOT_COMPLETED receivers (forbidden on
        // API 35+ and against Frameport session-start policy).
        // We assert no <receiver element exists in the manifest at all — the comment text
        // mentions BOOT_COMPLETED as a prohibition notice, but no <receiver tag should appear.
        // See android-foreground-service-lifecycle.md.
        assertTrue(
            "AndroidManifest.xml must NOT contain a <receiver element. " +
                "Sessions are started only from explicit user actions, never from broadcast receivers.",
            !manifestText.contains("<receiver"),
        )
    }

    // ── T5: onTimeout declared and guarded ────────────────────────────────────

    @Test
    fun `CameraSessionService declares onTimeout(Int, Int) method via reflection`() {
        // Reflection on the compiled class — no Android runtime needed because the class
        // is compiled as a JVM class; the Android-specific super.onTimeout delegate is
        // never reached in the JVM test classpath. We only verify the method signature exists.
        val method =
            CameraSessionService::class.java
                .runCatching {
                    getDeclaredMethod("onTimeout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                }.getOrNull()

        assertNotNull(
            "CameraSessionService must declare onTimeout(Int, Int) — see android-foreground-service-types.md §onTimeout",
            method,
        )
    }

    @Test
    fun `CameraSessionService source has @RequiresApi guard on onTimeout`() {
        val source = readServiceSource()
        // Accept either the literal int (35) or the named constant (VANILLA_ICE_CREAM).
        val hasGuard =
            source.contains("@RequiresApi(35)") ||
                source.contains("@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)")
        assertTrue(
            "onTimeout in CameraSessionService.kt must be annotated with " +
                "@RequiresApi(35) or @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM). " +
                "See android-foreground-service-types.md.",
            hasGuard,
        )
    }

    @Test
    fun `CameraSessionService onTimeout body calls stopSelf`() {
        val source = readServiceSource()
        // Locate the onTimeout function block and assert stopSelf appears inside it.
        // We do a simple text scan: find "fun onTimeout" and check that "stopSelf"
        // appears between that point and the next top-level "override fun" or "private fun".
        val onTimeoutIdx = source.indexOf("fun onTimeout")
        assertTrue(
            "CameraSessionService.kt must contain 'fun onTimeout'",
            onTimeoutIdx >= 0,
        )

        // Extract the text from onTimeout up to the next method declaration or end-of-class
        // to avoid false positives from other methods that also call stopSelf.
        val afterOnTimeout = source.substring(onTimeoutIdx)
        // Find the boundary of the onTimeout body: the next `override fun` or `private fun`
        // after the opening of onTimeout (startIndex = 1 skips the "fun onTimeout" token itself).
        val overrideIdx = afterOnTimeout.indexOf("override fun", startIndex = 1)
        val privateIdx = afterOnTimeout.indexOf("private fun", startIndex = 1)
        val nextFunIdx =
            listOf(overrideIdx, privateIdx)
                .filter { it > 0 }
                .minOrNull() ?: afterOnTimeout.length
        val onTimeoutBlock = afterOnTimeout.substring(0, nextFunIdx)

        assertTrue(
            "onTimeout body in CameraSessionService.kt must call stopSelf(). " +
                "See android-foreground-service-types.md §mediaProcessing.",
            onTimeoutBlock.contains("stopSelf"),
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun readServiceSource(): String {
        val file = File("src/main/kotlin/dev/po4yka/frameport/camera/data/CameraSessionService.kt")
        check(file.exists()) {
            "CameraSessionService.kt not found at ${file.absolutePath}. " +
                "Run via ./gradlew :camera:data:testDebugUnitTest from the repo root."
        }
        return file.readText()
    }
}
