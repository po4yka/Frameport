package dev.po4yka.frameport.core.storage.timeline

import dev.po4yka.frameport.core.model.ImportSession
import dev.po4yka.frameport.core.storage.catalog.db.ImportCatalogDao
import dev.po4yka.frameport.core.storage.catalog.db.ImportedMediaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [LocalTimelineStore].
 *
 * Groups [ImportedMediaEntity] rows into [ImportSession] records using the following logic:
 *
 * 1. Rows with a non-null [ImportedMediaEntity.importSessionId] are grouped by that id.
 *    [ImportSession.sessionKey] = "session:<importSessionId>".
 * 2. Rows with a null [ImportedMediaEntity.importSessionId] are grouped by calendar-day bucket
 *    of [ImportedMediaEntity.importedAtEpochMillis] (UTC, ISO-8601 yyyy-MM-dd format).
 *    [ImportSession.sessionKey] = "day:<yyyy-MM-dd>".
 *
 * Within each group:
 * - [ImportSession.startedAtEpochMs] = minimum [importedAtEpochMillis] in the group.
 * - [ImportSession.endedAtEpochMs] = maximum [importedAtEpochMillis] in the group.
 * - [ImportSession.objectCount] = row count.
 * - [ImportSession.totalBytes] = sum of [sizeBytes] (null sizes contribute 0).
 * - [ImportSession.thumbnailUris] = up to [MAX_THUMBNAILS] [mediaStoreUri] values, in
 *   descending import-time order (the most recently imported objects first).
 * - [ImportSession.transportLabel] = "Unknown" (transport tracking deferred to M19+).
 *
 * The final list is ordered by [ImportSession.endedAtEpochMs] descending.
 *
 * Privacy: only MediaStore content:// URIs are exposed in [ImportSession.thumbnailUris].
 * No raw filenames, serials, SSID/BSSID, or MAC addresses are present in any output field.
 */
@Singleton
class RoomLocalTimelineStore
    @Inject
    constructor(
        private val importCatalogDao: ImportCatalogDao,
    ) : LocalTimelineStore {
        // cancel-safe: the underlying Room Flow collection unsubscribes the InvalidationTracker
        // observer cleanly on cancellation. The map operator runs synchronously and holds no
        // shared mutable state that would be mutated after cancellation.
        override fun observeSessions(): Flow<List<ImportSession>> =
            importCatalogDao.observeAllImported().map { entities -> groupIntoSessions(entities) }

        private fun groupIntoSessions(entities: List<ImportedMediaEntity>): List<ImportSession> {
            if (entities.isEmpty()) return emptyList()

            // Partition rows into two buckets: those with an explicit session id and those without.
            val withSession = entities.filter { it.importSessionId != null }
            val withoutSession = entities.filter { it.importSessionId == null }

            val sessions = mutableListOf<ImportSession>()

            // Group explicit-session rows by session id.
            withSession
                .groupBy { it.importSessionId!! }
                .forEach { (sessionId, rows) ->
                    sessions.add(rowsToSession("session:$sessionId", rows))
                }

            // Group null-session rows by UTC calendar day.
            withoutSession
                .groupBy { utcDayKey(it.importedAtEpochMillis) }
                .forEach { (dayKey, rows) ->
                    sessions.add(rowsToSession("day:$dayKey", rows))
                }

            // Sort by most-recently-ended session first.
            sessions.sortByDescending { it.endedAtEpochMs }
            return sessions
        }

        private fun rowsToSession(
            sessionKey: String,
            rows: List<ImportedMediaEntity>,
        ): ImportSession {
            val startedAt = rows.minOf { it.importedAtEpochMillis }
            val endedAt = rows.maxOf { it.importedAtEpochMillis }
            val totalBytes = rows.sumOf { it.sizeBytes ?: 0L }
            // Take the most-recent MAX_THUMBNAILS URIs (rows are already sorted DESC by the DAO).
            val thumbnailUris = rows.take(MAX_THUMBNAILS).map { it.mediaStoreUri }
            return ImportSession(
                sessionKey = sessionKey,
                startedAtEpochMs = startedAt,
                endedAtEpochMs = endedAt,
                objectCount = rows.size,
                totalBytes = totalBytes,
                thumbnailUris = thumbnailUris,
                // Transport label deferred to M19+ when session transport metadata is tracked.
                transportLabel = "Unknown",
            )
        }

        private companion object {
            const val MAX_THUMBNAILS = 4

            /**
             * Thread-safe, immutable formatter for UTC calendar-day keys ("yyyy-MM-dd").
             *
             * [DateTimeFormatter] is immutable and safe to share across coroutines without
             * ThreadLocal wrapping. Replaces the previous ThreadLocal<SimpleDateFormat>, which
             * was semantically incorrect in a coroutine context where a coroutine may migrate
             * between threads and inadvertently pick up a formatter initialised on a different
             * thread (though in practice SimpleDateFormat is also not thread-safe, making the
             * ThreadLocal necessary but still fragile under coroutine dispatch).
             */
            private val UTC_DAY_FORMATTER: DateTimeFormatter =
                DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

            /** Format an epoch-millis timestamp as a UTC calendar-day string "yyyy-MM-dd". */
            fun utcDayKey(epochMillis: Long): String = UTC_DAY_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
        }
    }
