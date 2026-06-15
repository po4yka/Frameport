package dev.po4yka.frameport.camera.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Persisted record of a camera body seen by Frameport.
 *
 * Fields use primitive types and String throughout — no Android types — so this class
 * is safe to use in pure-JVM (:camera:api, :camera:domain) and Rust FFI boundary layers.
 *
 * PRIVACY invariants:
 * - [serialHash] is a lowercase hex SHA-256 of the raw serial or stable device id.
 *   The raw serial must NEVER be stored, logged, or transmitted. See privacy-local-first.md.
 * - [cameraModel] and [firmwareVersion] are placeholder strings until GetDeviceInfo PTP
 *   command is wired (deferred to M19+). The actual values are not available at session open
 *   because CameraRepositoryImpl.openSession() is currently stubbed with no handshake.
 * - No SSID, BSSID, BLE MAC, or IP address is stored here.
 *
 * [profileId] is a UUID string assigned locally; it is stable across sessions for the same
 * camera body (deduplication is done by [serialHash] unique index in the DB).
 *
 * [transportCapabilities] and [compatibilityFlags] are bitmask Longs; use the extension
 * functions in TransportCapability.kt and CompatibilityFlag.kt (core:model) to encode/decode.
 */
data class CameraProfile(
    /** UUID string, locally assigned, stable for the lifetime of the profile row. */
    val profileId: String,
    /**
     * Camera model name string.
     * Placeholder ("unknown") until GetDeviceInfo is wired (M19+).
     */
    val cameraModel: String,
    /**
     * Firmware version string reported by the camera.
     * Placeholder ("unknown") until GetDeviceInfo is wired (M19+).
     */
    val firmwareVersion: String,
    /**
     * Lowercase hex SHA-256 of the raw camera serial or stable device identifier.
     * NEVER store or log the raw serial — use Sha256.hex() from :core:common.
     */
    val serialHash: String,
    /**
     * Bitmask of [dev.po4yka.frameport.core.model.TransportCapability] bits.
     * Decode with Long.toTransportCapabilities().
     */
    val transportCapabilities: Long,
    /**
     * Bitmask of [dev.po4yka.frameport.core.model.CompatibilityFlag] bits.
     * Decode with Long.toCompatibilityFlags().
     */
    val compatibilityFlags: Long,
    /** Epoch millis when this camera was first seen by Frameport. */
    val firstSeenEpochMs: Long,
    /** Epoch millis of the most recent session open with this camera. */
    val lastSeenEpochMs: Long,
    /** Optional free-text notes (user-supplied or auto-generated diagnostics summary). */
    val notes: String?,
)

/**
 * Read/write interface for persisting and querying camera profiles.
 *
 * Implementations live in :camera:data. Callers (use-cases, ViewModels) depend on this
 * interface — never on Room entities or DAOs.
 *
 * Threading: all suspend functions are main-safe; implementations must dispatch to an
 * appropriate dispatcher internally.
 */
interface CameraProfileRepository {
    /**
     * Cold StateFlow of all known camera profiles, ordered by [CameraProfile.lastSeenEpochMs]
     * descending. Replays the latest list to new collectors.
     */
    val profiles: StateFlow<List<CameraProfile>>

    /**
     * Create or update the profile for the camera identified by [rawSerialOrStableId].
     *
     * [rawSerialOrStableId] is hashed via SHA-256 internally; it must never be persisted
     * or logged in plain text. If a profile with the same serial hash already exists, its
     * [lastSeenEpochMs], [transportCapabilities], and [compatibilityFlags] are updated.
     * Otherwise a new profile row is inserted with a freshly generated UUID [profileId].
     *
     * [cameraModel] and [firmwareVersion] are placeholder strings in M18 because
     * GetDeviceInfo is not yet wired. Pass "unknown" for both until M19+.
     *
     * cancel-safe: delegates to a Room suspend DAO upsert; cancellation propagates cleanly.
     */
    suspend fun upsertOnSessionOpen(
        sessionId: SessionId,
        cameraModel: String,
        firmwareVersion: String,
        rawSerialOrStableId: String,
        transportCapabilities: Long,
        compatibilityFlags: Long,
    )

    /**
     * Look up the profile whose [CameraProfile.serialHash] matches [serialHash].
     * Returns null when no profile is found for this hash.
     *
     * cancel-safe: delegates to a Room suspend DAO query; cancellation propagates cleanly.
     */
    suspend fun getProfileBySerialHash(serialHash: String): CameraProfile?

    /**
     * Permanently delete a camera profile by its [profileId].
     * No-op if the profile does not exist.
     *
     * cancel-safe: delegates to a Room suspend DAO delete; cancellation propagates cleanly.
     */
    suspend fun deleteProfile(profileId: String)

    /**
     * Returns the profile most recently updated (highest [CameraProfile.lastSeenEpochMs]).
     * This is a best-effort approximation for the "current camera" until the session layer
     * can pass a live session id to the repository (M19+).
     *
     * Returns null when no profiles have been persisted yet.
     */
    fun getProfileForCurrentCamera(): CameraProfile?
}
