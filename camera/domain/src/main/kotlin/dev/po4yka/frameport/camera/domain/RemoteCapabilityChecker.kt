package dev.po4yka.frameport.camera.domain

/**
 * Gate that determines whether a connected camera supports remote capture.
 *
 * The allowlist is intentionally conservative: only models explicitly verified
 * against hardware are included. Unknown models return false.
 *
 * Source: docs/product/feature-scope.md §Remote Capture, docs/reference/ble-wifi-discovery.md
 *
 * Compatibility: the X-T5 is the primary M15 target. Additional models are added only when
 * verified against real hardware. See docs/protocol/compatibility-matrix.md.
 */
interface RemoteCapabilityChecker {
    /**
     * Returns true if the given [cameraModel] string (from camera identity negotiation)
     * is in the verified remote-capable allowlist.
     *
     * [cameraModel] is expected to be the camera model identifier returned by the native
     * SDK (e.g. "X-T5"). When null (session not established or identity not yet read),
     * returns false to fail safe.
     */
    fun isRemoteCapable(cameraModel: String?): Boolean
}

/**
 * Production implementation backed by a hardcoded allowlist of verified models.
 *
 * Adding a model here constitutes a claim that remote capture has been tested against
 * real hardware. Do NOT add models speculatively. See CONTRIBUTING.md §Compatibility Claims.
 *
 * Conservative list: only X-T5 is verified for M15.
 */
object AllowlistRemoteCapabilityChecker : RemoteCapabilityChecker {
    /**
     * Verified remote-capable camera model identifiers.
     * Source: docs/reference/master-constants.md §Camera identity, hardware test logs.
     * Matching is case-insensitive and trims surrounding whitespace.
     */
    private val REMOTE_CAPABLE_MODELS: Set<String> =
        setOf(
            "X-T5",
        )

    override fun isRemoteCapable(cameraModel: String?): Boolean {
        if (cameraModel.isNullOrBlank()) return false
        val trimmed = cameraModel.trim()
        return REMOTE_CAPABLE_MODELS.any { it.equals(trimmed, ignoreCase = true) }
    }
}
