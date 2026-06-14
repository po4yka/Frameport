package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.DiagnosticEvent
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.core.storage.session.ExitReasonDedupStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pure-JVM unit tests for [MemoryLimiterExitScanner] (T3 in M10).
 *
 * Verifies:
 * - Memory-limiter kills (REASON_OTHER + "MemoryLimiter:AnonSwap") are classified and recorded.
 * - Non-matching reason codes are ignored.
 * - Non-matching description strings are ignored.
 * - Re-scanning the same list does NOT double-record (dedup via [ExitReasonDedupStore]).
 * - A DiagnosticEvent is recorded with Category.Native and a category-only message.
 *
 * No Robolectric, no Android framework.
 */
class MemoryLimiterExitScannerTest {
    private val dedupStore = mockk<ExitReasonDedupStore>()
    private val diagnosticsRepository = mockk<DiagnosticsRepository>(relaxed = true)
    private val scanner = MemoryLimiterExitScanner(dedupStore, diagnosticsRepository)

    // ── Matching kill — new record ─────────────────────────────────────────────

    @Test
    fun `given matching LMK entry not yet seen, when scan, then records DiagnosticEvent`() =
        runTest {
            // Arrange
            val entry =
                ExitReasonInfo(
                    reason = MemoryLimiterExitScanner.REASON_OTHER_VALUE,
                    description = "MemoryLimiter:AnonSwap exceeded",
                    timestampMillis = 1_700_000_000_000L,
                    pid = 12345,
                )
            coEvery { dedupStore.recordIfAbsent(any(), any()) } returns true

            // Act
            scanner.scan(listOf(entry))

            // Assert — one DiagnosticEvent with Category.Native and category-only message
            coVerify(exactly = 1) {
                diagnosticsRepository.recordEvent(
                    match { event ->
                        event.category == DiagnosticEvent.Category.Native &&
                            event.message == "Process terminated by system memory limiter" &&
                            !event.message.contains("12345") // no raw pid
                    },
                )
            }
        }

    // ── Re-scan dedup — already recorded ─────────────────────────────────────

    @Test
    fun `given matching LMK entry already recorded, when scan again, then does NOT double-record`() =
        runTest {
            // Arrange
            val entry =
                ExitReasonInfo(
                    reason = MemoryLimiterExitScanner.REASON_OTHER_VALUE,
                    description = "MemoryLimiter:AnonSwap",
                    timestampMillis = 1_700_000_000_000L,
                    pid = 12345,
                )
            // dedupStore returns false = already seen
            coEvery { dedupStore.recordIfAbsent(any(), any()) } returns false

            // Act — scan twice with the same entry
            scanner.scan(listOf(entry))
            scanner.scan(listOf(entry))

            // Assert — recordEvent never called
            coVerify(exactly = 0) { diagnosticsRepository.recordEvent(any()) }
        }

    // ── Wrong reason code — ignored ───────────────────────────────────────────

    @Test
    fun `given entry with non-REASON_OTHER reason code, when scan, then ignored`() =
        runTest {
            // Arrange — reason 4 = REASON_CRASH (not REASON_OTHER)
            val entry =
                ExitReasonInfo(
                    reason = 4,
                    description = "MemoryLimiter:AnonSwap",
                    timestampMillis = 1_700_000_000_000L,
                    pid = 999,
                )

            // Act
            scanner.scan(listOf(entry))

            // Assert — dedupStore and diagnosticsRepository never touched
            coVerify(exactly = 0) { dedupStore.recordIfAbsent(any(), any()) }
            coVerify(exactly = 0) { diagnosticsRepository.recordEvent(any()) }
        }

    // ── Wrong description — ignored ───────────────────────────────────────────

    @Test
    fun `given REASON_OTHER entry without MemoryLimiter marker in description, when scan, then ignored`() =
        runTest {
            // Arrange — correct reason but unrelated description
            val entry =
                ExitReasonInfo(
                    reason = MemoryLimiterExitScanner.REASON_OTHER_VALUE,
                    description = "ANR in foreground service",
                    timestampMillis = 1_700_000_000_000L,
                    pid = 888,
                )

            // Act
            scanner.scan(listOf(entry))

            // Assert
            coVerify(exactly = 0) { dedupStore.recordIfAbsent(any(), any()) }
            coVerify(exactly = 0) { diagnosticsRepository.recordEvent(any()) }
        }

    // ── Null description — ignored ────────────────────────────────────────────

    @Test
    fun `given REASON_OTHER entry with null description, when scan, then ignored`() =
        runTest {
            val entry =
                ExitReasonInfo(
                    reason = MemoryLimiterExitScanner.REASON_OTHER_VALUE,
                    description = null,
                    timestampMillis = 1_700_000_000_000L,
                    pid = 777,
                )

            scanner.scan(listOf(entry))

            coVerify(exactly = 0) { dedupStore.recordIfAbsent(any(), any()) }
            coVerify(exactly = 0) { diagnosticsRepository.recordEvent(any()) }
        }

    // ── Empty list — no-op ────────────────────────────────────────────────────

    @Test
    fun `given empty entry list, when scan, then nothing recorded`() =
        runTest {
            scanner.scan(emptyList())

            coVerify(exactly = 0) { dedupStore.recordIfAbsent(any(), any()) }
            coVerify(exactly = 0) { diagnosticsRepository.recordEvent(any()) }
        }

    // ── Multiple entries — only matching ones recorded ────────────────────────

    @Test
    fun `given mixed entries, when scan, then only LMK entry is recorded`() =
        runTest {
            // Arrange
            val lmkEntry =
                ExitReasonInfo(
                    reason = MemoryLimiterExitScanner.REASON_OTHER_VALUE,
                    description = "MemoryLimiter:AnonSwap",
                    timestampMillis = 1_700_000_001_000L,
                    pid = 111,
                )
            val crashEntry =
                ExitReasonInfo(
                    reason = 4, // REASON_CRASH
                    description = "NullPointerException",
                    timestampMillis = 1_700_000_000_000L,
                    pid = 222,
                )
            coEvery { dedupStore.recordIfAbsent(any(), any()) } returns true

            // Act
            scanner.scan(listOf(crashEntry, lmkEntry))

            // Assert — only one recordEvent call (for lmkEntry)
            coVerify(exactly = 1) { diagnosticsRepository.recordEvent(any()) }
        }
}
