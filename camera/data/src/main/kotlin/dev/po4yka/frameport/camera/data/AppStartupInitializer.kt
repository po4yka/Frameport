package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs startup-time background initializations that must not affect application launch latency
 * and must be failure-isolated (a read error here must never crash or delay startup).
 *
 * Called at the END of [FrameportApplication.onCreate] so that the Hilt graph is fully
 * assembled before any initialization logic runs.
 *
 * Current initializations (G9):
 * 1. LMK exit-reason scan — reads [ApplicationExitInfo] history via [ExitReasonSource] and
 *    passes results to [MemoryLimiterExitScanner] to record new [DiagnosticEvent]s idempotently.
 *
 * THREADING: all work is launched on [ioDispatcher] via a private [CoroutineScope] with
 * [SupervisorJob] so a child failure does not affect other initializations. The scope is
 * intentionally private and long-lived (tied to the application process), because
 * [AppStartupInitializer] is a [Singleton].
 *
 * FAILURE ISOLATION: the launched body is wrapped in try/catch so any exception (e.g. an OEM
 * ActivityManager implementation that throws unexpectedly) is silently swallowed. This is the
 * approved pattern per android-foreground-service-lifecycle.md §"Android 17 memory-limiter kill
 * detection".
 */
@Singleton
class AppStartupInitializer
    @Inject
    constructor(
        private val scanner: MemoryLimiterExitScanner,
        private val exitReasonSource: ExitReasonSource,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        // Private scope: SupervisorJob so child failures don't cancel siblings; IoDispatcher for
        // off-main-thread execution. Not @ApplicationScope because that qualifier does not exist
        // in this repository; a private scope is equivalent for a Singleton.
        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

        /**
         * Triggers all startup initializations asynchronously.
         *
         * Returns immediately; all work runs in the background on [ioDispatcher].
         * Must be called from [FrameportApplication.onCreate] after Hilt injection.
         */
        // cancel-safe: fire-and-forget launch into a private SupervisorJob scope;
        // individual child failures are caught internally and do not cancel the scope.
        fun initialize() {
            scope.launch {
                try {
                    val entries = exitReasonSource.historicalExitReasons()
                    scanner.scan(entries)
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    // Failure isolation: startup must not be affected by a read/scan error.
                    // The error is silently swallowed; the diagnostic simply won't be recorded
                    // for this restart cycle.
                }
            }
        }
    }
