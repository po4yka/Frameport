package dev.po4yka.frameport.core.model

/**
 * A logical grouping of media imports that occurred in a single camera session or on a
 * single calendar day (when session-level grouping is unavailable).
 *
 * [sessionKey] is either "session:<import_session_id>" (when rows carry an explicit session id)
 * or "day:<yyyy-MM-dd>" (for rows with a null session id, bucketed by import date in UTC).
 *
 * PRIVACY: no raw filenames, camera serials, SSID/BSSID, or MAC addresses. [thumbnailUris]
 * are MediaStore content:// URIs which contain no camera-specific identifiers.
 */
data class ImportSession(
    /** Stable grouping key; never exposes raw device identifiers. */
    val sessionKey: String,
    /** Epoch millis of the earliest import in this session/day group. */
    val startedAtEpochMs: Long,
    /** Epoch millis of the latest import in this session/day group. */
    val endedAtEpochMs: Long,
    /** Number of media objects imported. */
    val objectCount: Int,
    /** Sum of [sizeBytes] for all objects in this group; 0 when sizes were not reported. */
    val totalBytes: Long,
    /**
     * A sample of MediaStore content:// URIs for thumbnail display (at most a few).
     * Empty when no successfully imported objects are in this group.
     */
    val thumbnailUris: List<String>,
    /**
     * Human-readable transport label (e.g. "Wi-Fi", "USB", "Unknown").
     * "Unknown" is the safe default until transport tracking is wired (M19+).
     */
    val transportLabel: String,
) {
    /** Wall-clock duration of the session/group in milliseconds. Always >= 0. */
    val durationMs: Long get() = maxOf(0L, endedAtEpochMs - startedAtEpochMs)
}
