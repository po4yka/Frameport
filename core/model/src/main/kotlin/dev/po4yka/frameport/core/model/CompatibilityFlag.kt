package dev.po4yka.frameport.core.model

/**
 * Bitmask-encoded camera feature compatibility flags.
 *
 * Each variant encodes a single power-of-two bit derived from the feature columns in
 * docs/protocol/compatibility-matrix.md. Bits are stable identifiers — do not reorder or reuse.
 *
 * Use [Set.toCompatibilityBitmask] to encode and [Long.toCompatibilityFlags] to decode.
 *
 * All definitions are original; no proprietary Fujifilm constants are reproduced here.
 */
enum class CompatibilityFlag(
    val bit: Long,
) {
    BLE_SCAN(1L shl 0),
    BLE_CONNECT(1L shl 1),
    WIFI_PTP_IP(1L shl 2),
    OBJECT_LIST(1L shl 3),
    THUMBNAIL(1L shl 4),
    JPEG_IMPORT(1L shl 5),
    RAF_IMPORT(1L shl 6),
    HEIF_IMPORT(1L shl 7),
    REMOTE_SHUTTER(1L shl 8),
    LIVE_VIEW(1L shl 9),
    USB_PTP(1L shl 10),
}

/** Fold a set of [CompatibilityFlag] values into a single bitmask Long. */
fun Set<CompatibilityFlag>.toCompatibilityBitmask(): Long = fold(0L) { acc, flag -> acc or flag.bit }

/**
 * Expand a bitmask Long into the set of [CompatibilityFlag] values whose bits are set.
 * Unknown bits are silently ignored (forward-compatible with future flag additions).
 */
fun Long.toCompatibilityFlags(): Set<CompatibilityFlag> =
    CompatibilityFlag.entries.filterTo(mutableSetOf()) { flag -> this and flag.bit != 0L }
