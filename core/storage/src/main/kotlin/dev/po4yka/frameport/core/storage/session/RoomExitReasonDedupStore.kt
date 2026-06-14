package dev.po4yka.frameport.core.storage.session

import dev.po4yka.frameport.core.storage.session.db.ExitReasonDao
import dev.po4yka.frameport.core.storage.session.db.RecordedExitReasonEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [ExitReasonDedupStore].
 * Uses [ExitReasonDao.insertIfAbsent] (IGNORE conflict) as the dedup gate.
 */
@Singleton
class RoomExitReasonDedupStore
    @Inject
    constructor(
        private val dao: ExitReasonDao,
    ) : ExitReasonDedupStore {
        // cancel-safe: delegates to a Room suspend DAO call; cancellation propagates cleanly.
        override suspend fun recordIfAbsent(
            id: String,
            recordedAtMillis: Long,
        ): Boolean {
            val rowId =
                dao.insertIfAbsent(
                    RecordedExitReasonEntity(
                        id = id,
                        recordedAtMillis = recordedAtMillis,
                    ),
                )
            return rowId != -1L
        }
    }
