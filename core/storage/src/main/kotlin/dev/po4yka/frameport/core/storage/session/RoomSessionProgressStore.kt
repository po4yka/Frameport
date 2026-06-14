package dev.po4yka.frameport.core.storage.session

import dev.po4yka.frameport.core.storage.session.db.SessionProgressDao
import dev.po4yka.frameport.core.storage.session.db.SessionProgressEntity
import dev.po4yka.frameport.core.storage.session.db.SessionProgressState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [SessionProgressStore].
 * Delegates all persistence to [SessionProgressDao].
 */
@Singleton
class RoomSessionProgressStore
    @Inject
    constructor(
        private val dao: SessionProgressDao,
    ) : SessionProgressStore {
        // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
        override suspend fun upsert(
            sessionId: Long,
            objectHandle: Long,
            bytesTransferred: Long,
            totalBytes: Long,
            updatedAtMillis: Long,
        ) {
            dao.upsert(
                SessionProgressEntity(
                    sessionId = sessionId,
                    objectHandle = objectHandle,
                    bytesTransferred = bytesTransferred,
                    totalBytes = totalBytes,
                    state = SessionProgressState.IN_PROGRESS,
                    updatedAtMillis = updatedAtMillis,
                ),
            )
        }

        // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
        override suspend fun queryInProgress(): List<InterruptedSession> =
            dao.queryInProgress().map { entity ->
                InterruptedSession(
                    sessionId = entity.sessionId,
                    objectHandle = entity.objectHandle,
                    bytesTransferred = entity.bytesTransferred,
                    totalBytes = entity.totalBytes,
                    updatedAtMillis = entity.updatedAtMillis,
                )
            }

        // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
        override suspend fun markCompleted(
            sessionId: Long,
            updatedAtMillis: Long,
        ) {
            dao.markCompleted(sessionId, updatedAtMillis)
        }

        // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
        override suspend fun delete(sessionId: Long) {
            dao.delete(sessionId)
        }
    }
