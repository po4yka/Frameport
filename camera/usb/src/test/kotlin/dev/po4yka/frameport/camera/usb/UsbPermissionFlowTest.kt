package dev.po4yka.frameport.camera.usb

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.UsbDeviceRef
import dev.po4yka.frameport.camera.api.UsbSessionError
import dev.po4yka.frameport.camera.api.UsbSessionState
import dev.po4yka.frameport.camera.api.UsbTransportHandle
import dev.po4yka.frameport.core.testing.FakeCameraUsbConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the USB permission acquisition flow using [FakeCameraUsbConnector].
 *
 * Covers:
 * - Happy path: Disconnected → DeviceDetected → PermissionPending → OpeningDevice
 * - Deny path: PermissionPending → PermissionDenied (armed OpenDeviceFailed error)
 *
 * No Android framework, no UsbManager — FakeCameraUsbConnector is pure JVM.
 * Uses UnconfinedTestDispatcher (default for runTest) via Turbine; no real delays.
 *
 * Test naming: methodUnderTest_scenario_expectedOutcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsbPermissionFlowTest {
    private lateinit var connector: FakeCameraUsbConnector

    private val fakeDevice =
        UsbDeviceRef(
            deviceKey = "0x04CB:0x0171:1",
            displayName = "USB Device [1227:369]",
        )

    @Before
    fun setUp() {
        connector = FakeCameraUsbConnector()
    }

    @After
    fun tearDown() {
        connector.reset()
    }

    // ─── Attach detection ─────────────────────────────────────────────────────

    @Test
    fun emit_deviceDetected_stateIsDeviceDetected() =
        runTest {
            connector.state.test {
                assertEquals(UsbSessionState.Disconnected, awaitItem())

                val ref = UsbDeviceRef(deviceKey = "0x04CB:0x0171:1", displayName = "USB Device [1227:369]")
                connector.emit(UsbSessionState.DeviceDetected(ref))

                val detected = awaitItem()
                assertTrue(detected is UsbSessionState.DeviceDetected)
                assertEquals(ref.deviceKey, (detected as UsbSessionState.DeviceDetected).deviceRef.deviceKey)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Permission happy path ────────────────────────────────────────────────

    /**
     * Grants: Disconnected → (DeviceDetected, not driven by requestPermission itself) →
     * PermissionPending → OpeningDevice.
     *
     * The FakeCameraUsbConnector.requestPermission emits PermissionPending then OpeningDevice,
     * matching the contract of the production connector on a granted result.
     */
    @Test
    fun requestPermission_granted_emitsPermissionPendingThenOpeningDevice() =
        runTest {
            connector.state.test {
                assertEquals(UsbSessionState.Disconnected, awaitItem())

                connector.requestPermission(fakeDevice)

                assertEquals(UsbSessionState.PermissionPending, awaitItem())
                assertEquals(UsbSessionState.OpeningDevice, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun requestPermission_granted_recordsDeviceRef() =
        runTest {
            connector.requestPermission(fakeDevice)

            assertEquals(1, connector.permissionRequests.size)
            assertEquals(fakeDevice, connector.permissionRequests[0])
        }

    @Test
    fun requestPermission_granted_finalStateIsOpeningDevice() =
        runTest {
            connector.requestPermission(fakeDevice)

            assertEquals(UsbSessionState.OpeningDevice, connector.state.value)
        }

    // ─── Permission deny path ─────────────────────────────────────────────────

    /**
     * Deny: permission is "denied" by arming [UsbSessionError.OpenDeviceFailed] before calling
     * requestPermission. The fake emits Error(OpenDeviceFailed) to model a deny outcome.
     *
     * In the production connector, denial produces PermissionDenied; the fake uses the
     * OpenDeviceFailed sentinel to model any non-success result from requestPermission.
     * A separate UsbDetachDuringPermissionTest covers the detach-while-pending case.
     */
    @Test
    fun requestPermission_denied_emitsErrorState() =
        runTest {
            connector.armError(UsbSessionError.OpenDeviceFailed)

            connector.state.test {
                awaitItem() // Disconnected

                connector.requestPermission(fakeDevice)

                val errorState = awaitItem()
                assertTrue(errorState is UsbSessionState.Error)
                assertEquals(
                    UsbSessionError.OpenDeviceFailed,
                    (errorState as UsbSessionState.Error).error,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun requestPermission_denied_returnsFailure() =
        runTest {
            connector.armError(UsbSessionError.OpenDeviceFailed)
            val result = connector.requestPermission(fakeDevice)
            assertTrue(result.isFailure)
        }

    @Test
    fun requestPermission_denied_stateRemainsError() =
        runTest {
            connector.armError(UsbSessionError.OpenDeviceFailed)
            connector.requestPermission(fakeDevice)

            assertTrue(connector.state.value is UsbSessionState.Error)
        }

    // ─── PermissionDenied variant ─────────────────────────────────────────────

    @Test
    fun emit_permissionDenied_stateIsPermissionDenied() =
        runTest {
            connector.state.test {
                awaitItem() // Disconnected

                connector.emit(UsbSessionState.PermissionDenied)
                assertEquals(UsbSessionState.PermissionDenied, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Full lifecycle: DeviceDetected → permission → OpeningDevice ──────────

    @Test
    fun fullPermissionFlow_deviceDetectedThenGranted_allStatesInOrder() =
        runTest {
            val collected = mutableListOf<UsbSessionState>()

            connector.state.test {
                // Seed the initial Disconnected
                collected.add(awaitItem())

                // Simulate device attach
                val ref = UsbDeviceRef(deviceKey = "0x04CB:0x0171:1", displayName = "USB Device [1227:369]")
                connector.emit(UsbSessionState.DeviceDetected(ref))
                collected.add(awaitItem())

                // User triggers permission request (already granted / happy-path)
                connector.requestPermission(fakeDevice)
                collected.add(awaitItem()) // PermissionPending
                collected.add(awaitItem()) // OpeningDevice

                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(4, collected.size)
            assertTrue(collected[0] is UsbSessionState.Disconnected)
            assertTrue(collected[1] is UsbSessionState.DeviceDetected)
            assertEquals(UsbSessionState.PermissionPending, collected[2])
            assertEquals(UsbSessionState.OpeningDevice, collected[3])
        }

    // ─── Reset between calls ──────────────────────────────────────────────────

    @Test
    fun reset_afterPermissionGranted_stateIsDisconnected() =
        runTest {
            connector.requestPermission(fakeDevice)
            connector.reset()
            assertEquals(UsbSessionState.Disconnected, connector.state.value)
        }

    @Test
    fun reset_clearsPermissionRequests() =
        runTest {
            connector.requestPermission(fakeDevice)
            assertEquals(1, connector.permissionRequests.size)
            connector.reset()
            assertEquals(0, connector.permissionRequests.size)
        }

    // ─── openSession after permission ─────────────────────────────────────────

    @Test
    fun openSession_afterPermissionGranted_emitsUsbSessionReady() =
        runTest {
            connector.requestPermission(fakeDevice)
            assertEquals(UsbSessionState.OpeningDevice, connector.state.value)

            val descriptors = byteArrayOf(0x09, 0x04, 0x00, 0x00, 0x03, 0x06, 0x01, 0x01, 0x00)
            val result = connector.openSession(fakeDevice, descriptors)

            assertTrue(result.isSuccess)
            assertTrue(connector.state.value is UsbSessionState.UsbSessionReady)
            assertEquals(
                SessionId(FakeCameraUsbConnector.FAKE_SESSION_ID),
                (connector.state.value as UsbSessionState.UsbSessionReady).handle.sessionId,
            )
        }

    @Test
    fun openSession_afterPermissionGranted_handleMatchesFakeSessionId() =
        runTest {
            val customId = 55L
            connector.armSessionId(customId)
            connector.requestPermission(fakeDevice)

            val descriptors = byteArrayOf(0x09, 0x04)
            val result = connector.openSession(fakeDevice, descriptors)

            assertTrue(result.isSuccess)
            val handle: UsbTransportHandle = result.getOrThrow()
            assertEquals(SessionId(customId), handle.sessionId)
        }
}
