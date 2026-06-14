package dev.po4yka.frameport.camera.diagnostics

import dev.po4yka.frameport.camera.api.DiagnosticCategory
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.defaultCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Verifies that [defaultCategory] covers every [ErrorLayer] variant and that each
 * mapping is correct, non-null, and has the right owning [layer].
 *
 * The exhaustive `when` in [defaultCategory] is a compile-time guarantee (no `else`
 * branch), but these tests act as a runtime regression net: if a new [ErrorLayer]
 * variant is added without updating [defaultCategory] the build fails at compile time,
 * and these tests document the expected mapping explicitly.
 *
 * Synthetic fixtures only — no real device identifiers.
 * FRAMEPORT_BLESS_GOLDENS=1 is never used here.
 */
class ErrorLayerCoverageTest {
    /**
     * An exhaustive `when` over every [ErrorLayer] variant mapping each to its
     * expected [DiagnosticCategory] subtype. This mirrors the contract in [defaultCategory]
     * and will fail to compile if a new [ErrorLayer] is added without updating this test.
     */
    private fun expectedCategory(layer: ErrorLayer): DiagnosticCategory =
        when (layer) {
            ErrorLayer.UserAction -> DiagnosticCategory.UserInitiated
            ErrorLayer.Permission -> DiagnosticCategory.PermissionDenied
            ErrorLayer.Bluetooth -> DiagnosticCategory.BluetoothEvent
            ErrorLayer.Wifi -> DiagnosticCategory.WifiEvent
            ErrorLayer.Socket -> DiagnosticCategory.SocketEvent
            ErrorLayer.Usb -> DiagnosticCategory.UsbEvent
            ErrorLayer.NativeBoundary -> DiagnosticCategory.NativeBoundaryEvent
            ErrorLayer.Transport -> DiagnosticCategory.TransportEvent
            ErrorLayer.Protocol -> DiagnosticCategory.ProtocolEvent
            ErrorLayer.CameraState -> DiagnosticCategory.CameraStateEvent
            ErrorLayer.MediaTransfer -> DiagnosticCategory.MediaTransferEvent
            ErrorLayer.Storage -> DiagnosticCategory.StorageEvent
            ErrorLayer.Location -> DiagnosticCategory.LocationEvent
            ErrorLayer.Diagnostics -> DiagnosticCategory.DiagnosticsEvent
        }

    // ── per-layer non-null assertions ─────────────────────────────────────────────

    @Test
    fun defaultCategory_userAction_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.UserAction))
    }

    @Test
    fun defaultCategory_permission_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Permission))
    }

    @Test
    fun defaultCategory_bluetooth_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Bluetooth))
    }

    @Test
    fun defaultCategory_wifi_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Wifi))
    }

    @Test
    fun defaultCategory_socket_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Socket))
    }

    @Test
    fun defaultCategory_usb_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Usb))
    }

    @Test
    fun defaultCategory_nativeBoundary_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.NativeBoundary))
    }

    @Test
    fun defaultCategory_transport_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Transport))
    }

    @Test
    fun defaultCategory_protocol_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Protocol))
    }

    @Test
    fun defaultCategory_cameraState_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.CameraState))
    }

    @Test
    fun defaultCategory_mediaTransfer_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.MediaTransfer))
    }

    @Test
    fun defaultCategory_storage_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Storage))
    }

    @Test
    fun defaultCategory_location_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Location))
    }

    @Test
    fun defaultCategory_diagnostics_nonNull() {
        assertNotNull(defaultCategory(ErrorLayer.Diagnostics))
    }

    // ── exhaustive correctness sweep ──────────────────────────────────────────────

    @Test
    fun defaultCategory_allLayers_matchExpectedSubtype() {
        ErrorLayer.entries.forEach { layer ->
            val actual = defaultCategory(layer)
            val expected = expectedCategory(layer)
            assertSame(
                "defaultCategory($layer) expected $expected but got $actual",
                expected,
                actual,
            )
        }
    }

    @Test
    fun defaultCategory_allLayers_categoryLayerMatchesInputLayer() {
        ErrorLayer.entries.forEach { layer ->
            val category = defaultCategory(layer)
            assertEquals(
                "category.layer for $layer must equal the input layer",
                layer,
                category.layer,
            )
        }
    }

    // ── exact count guard — fails to compile if ErrorLayer grows beyond 14 ───────

    @Test
    fun errorLayer_hasExactly14Variants() {
        assertEquals(
            "ErrorLayer must have exactly 14 variants (docs/protocol/error-model.md line 118)",
            14,
            ErrorLayer.entries.size,
        )
    }

    // ── per-layer identity spot-checks ────────────────────────────────────────────

    @Test
    fun defaultCategory_userAction_returnsUserInitiated() {
        assertSame(DiagnosticCategory.UserInitiated, defaultCategory(ErrorLayer.UserAction))
    }

    @Test
    fun defaultCategory_permission_returnsPermissionDenied() {
        assertSame(DiagnosticCategory.PermissionDenied, defaultCategory(ErrorLayer.Permission))
    }

    @Test
    fun defaultCategory_bluetooth_returnsBluetoothEvent() {
        assertSame(DiagnosticCategory.BluetoothEvent, defaultCategory(ErrorLayer.Bluetooth))
    }

    @Test
    fun defaultCategory_wifi_returnsWifiEvent() {
        assertSame(DiagnosticCategory.WifiEvent, defaultCategory(ErrorLayer.Wifi))
    }

    @Test
    fun defaultCategory_socket_returnsSocketEvent() {
        assertSame(DiagnosticCategory.SocketEvent, defaultCategory(ErrorLayer.Socket))
    }

    @Test
    fun defaultCategory_usb_returnsUsbEvent() {
        assertSame(DiagnosticCategory.UsbEvent, defaultCategory(ErrorLayer.Usb))
    }

    @Test
    fun defaultCategory_nativeBoundary_returnsNativeBoundaryEvent() {
        assertSame(DiagnosticCategory.NativeBoundaryEvent, defaultCategory(ErrorLayer.NativeBoundary))
    }

    @Test
    fun defaultCategory_transport_returnsTransportEvent() {
        assertSame(DiagnosticCategory.TransportEvent, defaultCategory(ErrorLayer.Transport))
    }

    @Test
    fun defaultCategory_protocol_returnsProtocolEvent() {
        assertSame(DiagnosticCategory.ProtocolEvent, defaultCategory(ErrorLayer.Protocol))
    }

    @Test
    fun defaultCategory_cameraState_returnsCameraStateEvent() {
        assertSame(DiagnosticCategory.CameraStateEvent, defaultCategory(ErrorLayer.CameraState))
    }

    @Test
    fun defaultCategory_mediaTransfer_returnsMediaTransferEvent() {
        assertSame(DiagnosticCategory.MediaTransferEvent, defaultCategory(ErrorLayer.MediaTransfer))
    }

    @Test
    fun defaultCategory_storage_returnsStorageEvent() {
        assertSame(DiagnosticCategory.StorageEvent, defaultCategory(ErrorLayer.Storage))
    }

    @Test
    fun defaultCategory_location_returnsLocationEvent() {
        assertSame(DiagnosticCategory.LocationEvent, defaultCategory(ErrorLayer.Location))
    }

    @Test
    fun defaultCategory_diagnostics_returnsDiagnosticsEvent() {
        assertSame(DiagnosticCategory.DiagnosticsEvent, defaultCategory(ErrorLayer.Diagnostics))
    }
}
