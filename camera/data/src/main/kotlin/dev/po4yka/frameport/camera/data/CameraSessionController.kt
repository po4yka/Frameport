package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.core.storage.session.SessionProgressStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Testable session-lifecycle seam that bridges the [CameraRepository] state machine and
 * [SessionProgressStore] persistence to [CameraSessionService].
 *
 * This class is pure Kotlin — no Android framework imports — so it can be exercised with
 * pure-JVM unit tests (no Robolectric required). [CameraSessionService] calls [start] and
 * uses [onTerminal] to know when to call [Service.stopSelf].
 *
 * Responsibilities:
 * 1. Collect [sessionState]; on the FIRST terminal state ([CameraSessionState.isTerminal]),
 *    mark the [sessionId] progress row completed (so startup reconstruction does not later
 *    misreport a cleanly-closed or failed session as SIGKILL-interrupted) and call [onTerminal].
 * 2. Collect [progressFlow]; call [SessionProgressStore.upsert] (keyed by [sessionId]) on
 *    every emission, so a row exists to survive process death between chunks.
 *
 * Both collections are launched into the caller-supplied [scope] and are cancelled when
 * the scope is cancelled (i.e. when [CameraSessionService.onDestroy] cancels the service scope).
 *
 * @param sessionId the session this controller manages (from the service start Intent). All
 *   progress rows and the terminal completion are keyed by it.
 */
class CameraSessionController(
    private val sessionId: Long,
    private val sessionState: StateFlow<CameraSessionState>,
    private val progressFlow: Flow<TransferProgress>,
    private val sessionProgressStore: SessionProgressStore,
    private val onTerminal: () -> Unit,
) {
    private var stateJob: Job? = null
    private var progressJob: Job? = null

    // Guards onTerminal + markCompleted to fire exactly once even if more than one terminal
    // state is observed (e.g. Failed then Closed). StateFlow conflates identical values, but
    // distinct terminal transitions could otherwise double-fire.
    private var terminalHandled = false

    /**
     * Starts collecting the session state and progress flows within [scope].
     *
     * Safe to call multiple times — subsequent calls cancel the prior jobs first.
     */
    // cancel-safe: both launched jobs cooperate with the parent scope's cancellation.
    fun start(scope: CoroutineScope) {
        stateJob?.cancel()
        progressJob?.cancel()

        stateJob =
            scope.launch {
                sessionState.collect { state ->
                    if (state.isTerminal() && !terminalHandled) {
                        terminalHandled = true
                        // A terminal state (Closed or Failed) is a clean lifecycle end, not a
                        // SIGKILL interruption — mark the row completed so the next launch's
                        // queryInProgress() does not surface it as "Previous transfer interrupted".
                        sessionProgressStore.markCompleted(sessionId, System.currentTimeMillis())
                        onTerminal()
                    }
                }
            }

        progressJob =
            scope.launch {
                progressFlow.collect { progress ->
                    sessionProgressStore.upsert(
                        sessionId = sessionId,
                        objectHandle = 0L, // object handle is not carried in TransferProgress; 0 = unknown stub
                        bytesTransferred = progress.bytesTransferred,
                        totalBytes = progress.totalBytes,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                }
            }
    }

    /** Cancels both collection jobs without touching the parent scope. */
    fun stop() {
        stateJob?.cancel()
        progressJob?.cancel()
    }
}

/**
 * Returns true if the session has reached a terminal state from which no recovery is expected.
 *
 * [CameraSessionState.Closed] — user-initiated disconnect or successful session end.
 * [CameraSessionState.Failed] — unrecoverable protocol or connection error from the JNI layer.
 *
 * [CameraSessionState.Idle], [CameraSessionState.Connecting], and
 * [CameraSessionState.SessionReady] are non-terminal.
 */
fun CameraSessionState.isTerminal(): Boolean = this is CameraSessionState.Closed || this is CameraSessionState.Failed
