package dev.po4yka.frameport.core.storage.session

/**
 * Domain interface for persisting session transfer progress across process death.
 *
 * Progress is written on every [TransferProgress] emission (not on a periodic timer) so that
 * LMK SIGKILL does not lose intermediate state. See android-foreground-service-lifecycle.md.
 *
 * PRIVACY: no raw filenames, camera serial, SSID, BSSID, MAC, or IP stored through this interface.
 */
interface SessionProgressStore {
    /**
     * Persist or update the progress row for [sessionId].
     *
     * Called on every TransferProgress emission. Idempotent for the same sessionId — uses REPLACE.
     */
    // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
    suspend fun upsert(
        sessionId: Long,
        objectHandle: Long,
        bytesTransferred: Long,
        totalBytes: Long,
        updatedAtMillis: Long,
    )

    /**
     * Returns all sessions whose state is "in_progress". Used at startup to detect interrupted
     * transfers and surface a DiagnosticEvent (G11).
     */
    // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
    suspend fun queryInProgress(): List<InterruptedSession>

    /**
     * Marks a session as completed. Idempotent if the row does not exist.
     */
    // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
    suspend fun markCompleted(
        sessionId: Long,
        updatedAtMillis: Long,
    )

    /**
     * Deletes the progress row for a session after it closes cleanly.
     */
    // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
    suspend fun delete(sessionId: Long)
}

/** Read-only view of an interrupted session returned by [SessionProgressStore.queryInProgress]. */
data class InterruptedSession(
    val sessionId: Long,
    val objectHandle: Long,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val updatedAtMillis: Long,
)
