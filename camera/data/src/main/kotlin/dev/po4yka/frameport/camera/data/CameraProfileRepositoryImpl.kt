package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraProfile
import dev.po4yka.frameport.camera.api.CameraProfileRepository
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.common.Sha256
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.storage.profile.db.CameraProfileDao
import dev.po4yka.frameport.core.storage.profile.db.CameraProfileEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of [CameraProfileRepository].
 *
 * All suspend functions dispatch to [ioDispatcher] internally; callers do not need to switch
 * context before calling. The [profiles] StateFlow is backed by a Room [CameraProfileDao]
 * Flow and kept alive while any subscriber is active (WhileSubscribed with a 5 s grace window).
 *
 * Privacy invariants:
 * - Raw serial / stable device id is NEVER stored. Only the SHA-256 hex digest ([serialHash])
 *   reaches the database. See privacy-local-first.md.
 * - [cameraModel] and [firmwareVersion] are placeholder strings until GetDeviceInfo (PTP
 *   opcode 0x1001) is wired in M19+.
 */
@Singleton
class CameraProfileRepositoryImpl
    @Inject
    constructor(
        private val cameraProfileDao: CameraProfileDao,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CameraProfileRepository {
        /**
         * Tracks the most recently upserted profile for [getProfileForCurrentCamera].
         * Written under [ioDispatcher]; read from any thread via volatile visibility guarantee.
         */
        @Volatile
        private var lastUpsertedProfile: CameraProfile? = null

        private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

        /**
         * Hot StateFlow of all persisted camera profiles, ordered by most-recently-seen first.
         * Starts collecting the Room Flow on first subscriber; keeps the upstream alive for up to
         * 5 seconds after the last subscriber disappears (WhileSubscribed grace window).
         */
        override val profiles: StateFlow<List<CameraProfile>> =
            cameraProfileDao
                .observeAll()
                .map { entities -> entities.map(::entityToDomain) }
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
                    initialValue = emptyList(),
                )

        /**
         * Create or update the camera profile identified by [rawSerialOrStableId].
         *
         * The raw id is hashed with SHA-256 before any persistence — the plaintext never leaves
         * this function. If a profile with the same hash exists, its [lastSeenEpochMs],
         * [transportCapabilities], and [compatibilityFlags] are updated while [firstSeenEpochMs]
         * and [profileId] are preserved. Otherwise a new row is inserted with a freshly generated
         * UUID [profileId] and the current time as [firstSeenEpochMs].
         *
         * cancel-safe: the only suspension point is the Room DAO upsert; Room suspend DAOs are
         * cancel-safe — the cancellation propagates to the underlying coroutine context cleanly.
         */
        override suspend fun upsertOnSessionOpen(
            sessionId: SessionId,
            cameraModel: String,
            firmwareVersion: String,
            rawSerialOrStableId: String,
            transportCapabilities: Long,
            compatibilityFlags: Long,
        ): Unit =
            withContext(ioDispatcher) {
                val serialHash = Sha256.hex(rawSerialOrStableId)
                val nowMs = System.currentTimeMillis()

                // Use the @Transaction-wrapped DAO method to make the read-then-write atomic.
                // This prevents a TOCTOU race where two concurrent callers both read null,
                // each generate a fresh profileId, and the UNIQUE constraint on serial_hash
                // causes the second REPLACE to silently delete the first row (losing firstSeenEpochMs).
                // cancel-safe: upsertBySerialHash is a Room @Transaction suspend call;
                // cancellation between the inner DAO calls rolls back the SQLite transaction.
                var upsertedEntity: CameraProfileEntity? = null
                cameraProfileDao.upsertBySerialHash(serialHash) { existing ->
                    val entity =
                        if (existing != null) {
                            // Preserve profileId and firstSeenEpochMs; update mutable fields.
                            existing.copy(
                                cameraModel = cameraModel,
                                firmwareVersion = firmwareVersion,
                                transportCapabilities = transportCapabilities,
                                compatibilityFlags = compatibilityFlags,
                                lastSeenEpochMs = nowMs,
                            )
                        } else {
                            CameraProfileEntity(
                                profileId = UUID.randomUUID().toString(),
                                cameraModel = cameraModel,
                                firmwareVersion = firmwareVersion,
                                serialHash = serialHash,
                                transportCapabilities = transportCapabilities,
                                compatibilityFlags = compatibilityFlags,
                                firstSeenEpochMs = nowMs,
                                lastSeenEpochMs = nowMs,
                                notes = null,
                            )
                        }
                    upsertedEntity = entity
                    entity
                }
                lastUpsertedProfile = upsertedEntity?.let(::entityToDomain)
            }

        /**
         * Look up a profile by its [serialHash].
         * Returns null when no matching row exists.
         *
         * cancel-safe: Room suspend DAO call; cancellation propagates cleanly.
         */
        override suspend fun getProfileBySerialHash(serialHash: String): CameraProfile? =
            withContext(ioDispatcher) {
                cameraProfileDao.getBySerialHash(serialHash)?.let(::entityToDomain)
            }

        /**
         * Permanently delete a camera profile by its [profileId].
         * No-op if the profile does not exist.
         *
         * cancel-safe: Room suspend DAO call; cancellation propagates cleanly.
         */
        override suspend fun deleteProfile(profileId: String): Unit =
            withContext(ioDispatcher) {
                cameraProfileDao.deleteById(profileId)
            }

        /**
         * Returns the profile most recently updated via [upsertOnSessionOpen], or null if no
         * session has been opened in the current process lifetime.
         *
         * This is a best-effort approximation until a live session id can be propagated through
         * the repository layer (M19+). The @Volatile field provides visibility across threads
         * without a lock; the value may be momentarily stale if two sessions open concurrently,
         * which is not a supported scenario in v1.
         */
        override fun getProfileForCurrentCamera(): CameraProfile? = lastUpsertedProfile

        // --- Internal mappers -----------------------------------------------------------------

        private fun entityToDomain(entity: CameraProfileEntity): CameraProfile =
            CameraProfile(
                profileId = entity.profileId,
                cameraModel = entity.cameraModel,
                firmwareVersion = entity.firmwareVersion,
                serialHash = entity.serialHash,
                transportCapabilities = entity.transportCapabilities,
                compatibilityFlags = entity.compatibilityFlags,
                firstSeenEpochMs = entity.firstSeenEpochMs,
                lastSeenEpochMs = entity.lastSeenEpochMs,
                notes = entity.notes,
            )
    }
