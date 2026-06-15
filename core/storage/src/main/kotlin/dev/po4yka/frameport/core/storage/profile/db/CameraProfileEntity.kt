package dev.po4yka.frameport.core.storage.profile.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the camera-profile catalog.
 *
 * Table name: camera_profile
 *
 * PRIVACY invariants (privacy-local-first.md):
 * - [serialHash] stores only a lowercase hex SHA-256 of the raw serial or stable device id.
 *   The raw serial is NEVER persisted, logged, or exposed outside :core:storage.
 * - [cameraModel] and [firmwareVersion] are placeholder strings in M18 (GetDeviceInfo not
 *   yet wired); they contain no PII when populated with "unknown".
 * - No SSID, BSSID, BLE MAC, IP address, or GPS data is stored.
 *
 * The [serialHash] unique index drives upsert deduplication: a second session with the
 * same camera body updates [lastSeenEpochMs] rather than creating a duplicate row.
 */
@Entity(
    tableName = "camera_profile",
    indices = [Index(value = ["serial_hash"], unique = true)],
)
data class CameraProfileEntity(
    /** UUID string, locally generated. Stable for the lifetime of the profile row. */
    @PrimaryKey
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    /**
     * Camera model name reported by the camera.
     * "unknown" until GetDeviceInfo is wired (M19+).
     */
    @ColumnInfo(name = "camera_model")
    val cameraModel: String,
    /**
     * Firmware version string.
     * "unknown" until GetDeviceInfo is wired (M19+).
     */
    @ColumnInfo(name = "firmware_version")
    val firmwareVersion: String,
    /**
     * Lowercase hex SHA-256 of the raw camera serial or stable device id.
     * NEVER store or log the raw serial.
     */
    @ColumnInfo(name = "serial_hash")
    val serialHash: String,
    /**
     * Bitmask of TransportCapability bits (core:model).
     * Decode with Long.toTransportCapabilities().
     */
    @ColumnInfo(name = "transport_capabilities")
    val transportCapabilities: Long,
    /**
     * Bitmask of CompatibilityFlag bits (core:model).
     * Decode with Long.toCompatibilityFlags().
     */
    @ColumnInfo(name = "compatibility_flags")
    val compatibilityFlags: Long,
    /** Epoch millis when this camera was first seen by Frameport. */
    @ColumnInfo(name = "first_seen_epoch_ms")
    val firstSeenEpochMs: Long,
    /** Epoch millis of the most recent session open with this camera. */
    @ColumnInfo(name = "last_seen_epoch_ms")
    val lastSeenEpochMs: Long,
    /** Optional free-text notes; null when none set. */
    @ColumnInfo(name = "notes")
    val notes: String?,
)
