package dev.po4yka.frameport.camera.data

/**
 * Plain data class representing one [android.app.ApplicationExitInfo] record.
 *
 * Pure Kotlin — no Android framework imports — so it can be used in pure-JVM unit tests.
 * [ExitReasonSource] maps [ApplicationExitInfo] to this type; [MemoryLimiterExitScanner]
 * consumes it without touching the Android API.
 *
 * PRIVACY: [pid] and [timestampMillis] are numeric values only; the dedup [id] derived from
 * them ("<timestampMillis>_<pid>") contains no user-identifiable data.
 */
data class ExitReasonInfo(
    /**
     * Exit reason code. Corresponds to [android.app.ApplicationExitInfo.getReason].
     * The value 28 corresponds to [android.app.ApplicationExitInfo.REASON_OTHER].
     */
    val reason: Int,
    /**
     * Description string from [android.app.ApplicationExitInfo.getDescription].
     * May be null if the OS did not supply one.
     */
    val description: String?,
    /** UTC epoch millis from [android.app.ApplicationExitInfo.getTimestamp]. */
    val timestampMillis: Long,
    /** PID from [android.app.ApplicationExitInfo.getPid]. Used only for dedup key derivation. */
    val pid: Int,
)
