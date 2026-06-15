package dev.po4yka.frameport.core.storage.timeline

import dev.po4yka.frameport.core.model.ImportSession
import kotlinx.coroutines.flow.Flow

/**
 * Domain-facing interface for observing the local import timeline.
 *
 * Returns a stream of [ImportSession] records derived from the import catalog, grouped
 * by session id (when available) or by calendar-day bucket (for pre-M18 rows).
 *
 * Privacy: no raw filenames, serials, SSID/BSSID, or MAC addresses are exposed.
 * [ImportSession.thumbnailUris] are MediaStore content:// URIs only.
 *
 * Implementations must be bound via Hilt @Singleton in [StorageModule].
 */
interface LocalTimelineStore {
    /**
     * Cold [Flow] of [ImportSession] records ordered by [ImportSession.endedAtEpochMs] descending.
     *
     * Re-emits on every write to the imported_media table. The list is empty when no imports
     * have been recorded yet.
     *
     * cancel-safe: the underlying Room Flow collection unsubscribes the InvalidationTracker
     * observer cleanly on cancellation. The grouping map operation runs synchronously in the
     * flow operator chain; no shared mutable state is mutated after cancellation.
     */
    fun observeSessions(): Flow<List<ImportSession>>
}
