package dev.po4yka.frameport.core.storage.session

/**
 * Domain interface for deduplicating ApplicationExitInfo records.
 *
 * Ensures that re-scanning the same [ApplicationExitInfo] history across process restarts
 * never double-records a DiagnosticEvent. The idempotency key is derived externally as
 * "<timestampMillis>_<pid>" so the scanner can call [recordIfAbsent] without holding a
 * reference to Android framework types. See G7/G8/G9.
 *
 * PRIVACY: the id string contains only opaque numeric values — no raw device identifiers.
 */
interface ExitReasonDedupStore {
    /**
     * Attempts to persist [id] as a new recorded exit reason.
     *
     * Returns true if this is the first time [id] has been seen (the row was inserted).
     * Returns false if [id] was already recorded (the row existed; IGNORE conflict).
     *
     * Callers should record a DiagnosticEvent only when this returns true.
     */
    // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
    suspend fun recordIfAbsent(
        id: String,
        recordedAtMillis: Long,
    ): Boolean
}
