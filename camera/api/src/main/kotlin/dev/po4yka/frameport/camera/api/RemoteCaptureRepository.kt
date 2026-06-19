package dev.po4yka.frameport.camera.api

/**
 * Domain-layer repository for PTP-IP remote shutter commands.
 *
 * Owned by :camera:data. :camera:domain depends on this interface for the PTP-IP
 * capture path; it never touches [FujiNativeSdk] or any JNI/Android-platform type
 * directly.
 *
 * Wire mapping (master-constants.md §3, docs/protocol/wifi-ptp-ip.md):
 *   HalfPress / FullPress → InitiateCapture (0x100E) via RemoteSession (Rust layer)
 *   Release               → TerminateOpenCapture (0x1018) via RemoteSession (Rust layer)
 *
 * Privacy: no MAC address, SSID, passphrase, or raw payload is logged.
 * See privacy-local-first.md.
 *
 * See: docs/adr/0003-ble-client-abstraction.md, docs/protocol/wifi-ptp-ip.md
 */
interface RemoteCaptureRepository {
    /**
     * Send a remote shutter action to the camera over PTP-IP.
     *
     * JNI wiring is DEFERRED to M16: the adapter currently returns a stub success.
     * TODO(M16): wire to fuji-ffi JNI entry point for RemoteSession shutter command.
     *
     * @param sessionId Active PTP-IP session returned by the Wi-Fi session open.
     * @param action    The shutter state to send (HalfPress, FullPress, or Release).
     * @return [Result.success] on acceptance; [Result.failure] with a typed error on rejection.
     */
    // cancel-safe: single withContext call delegated to JNI stub; no shared mutable state mutated after cancellation.
    suspend fun remoteShutter(
        sessionId: SessionId,
        action: ShutterAction,
    ): Result<Unit>
}
