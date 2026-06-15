package dev.po4yka.frameport.core.model

/**
 * Bitmask-encoded transport capabilities reported by a camera profile.
 *
 * Each variant encodes a single power-of-two bit so sets can be persisted as a single Long.
 * Use [Set.toBitmask] to encode and [Long.toTransportCapabilities] to decode.
 */
enum class TransportCapability(
    val bit: Long,
) {
    WIFI(1L),
    BLE(2L),
    USB(4L),
}

/** Fold a set of [TransportCapability] values into a single bitmask Long. */
fun Set<TransportCapability>.toBitmask(): Long = fold(0L) { acc, cap -> acc or cap.bit }

/**
 * Expand a bitmask Long into the set of [TransportCapability] values whose bits are set.
 * Unknown bits are silently ignored (forward-compatible with future capability additions).
 */
fun Long.toTransportCapabilities(): Set<TransportCapability> =
    TransportCapability.entries.filterTo(mutableSetOf()) { cap -> this and cap.bit != 0L }
