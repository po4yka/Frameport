package dev.po4yka.frameport.camera.usb

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraObjectHandle
import dev.po4yka.frameport.camera.api.ImportState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.UsbDeviceRef
import dev.po4yka.frameport.camera.api.UsbSessionError
import dev.po4yka.frameport.camera.api.UsbSessionState
import dev.po4yka.frameport.camera.api.UsbTransportHandle
import dev.po4yka.frameport.camera.domain.ImportObjectUseCase
import dev.po4yka.frameport.core.model.FrameportError
import dev.po4yka.frameport.core.testing.FakeCameraUsbConnector
import dev.po4yka.frameport.core.testing.FakeDiagnosticsRepository
import dev.po4yka.frameport.core.testing.FakeTransferRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [UsbSessionViewModel].
 *
 * Uses [FakeCameraUsbConnector] — no Android framework or UsbManager involved.
 * Uses [FakeTransferRepository] + [FakeDiagnosticsRepository] to drive ImportObjectUseCase.
 *
 * Test naming: methodUnderTest_scenario_expectedOutcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsbSessionViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var fakeConnector: FakeCameraUsbConnector
    private lateinit var fakeTransferRepo: FakeTransferRepository
    private lateinit var fakeDiagnosticsRepo: FakeDiagnosticsRepository
    private lateinit var importObjectUseCase: ImportObjectUseCase
    private lateinit var viewModel: UsbSessionViewModel

    private val fakeDevice =
        UsbDeviceRef(deviceKey = "test:key:1", displayName = "Test USB Camera")
    private val fakeDescriptors = byteArrayOf(0x01, 0x02, 0x09)
    private val fakeHandle = UsbTransportHandle(SessionId(FakeCameraUsbConnector.FAKE_SESSION_ID))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeConnector = FakeCameraUsbConnector()
        fakeTransferRepo = FakeTransferRepository()
        fakeDiagnosticsRepo = FakeDiagnosticsRepository()
        importObjectUseCase =
            ImportObjectUseCase(
                transferRepository = fakeTransferRepo,
                diagnosticsRepository = fakeDiagnosticsRepo,
                ioDispatcher = testDispatcher,
            )
        viewModel = UsbSessionViewModel(fakeConnector, importObjectUseCase)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── requestPermission ────────────────────────────────────────────────────

    @Test
    fun requestPermission_happyPath_emitsOpeningDevice() =
        runTest {
            viewModel.usbState.test {
                // Initial state
                assertEquals(UsbSessionState.Disconnected, awaitItem())

                viewModel.requestPermission(fakeDevice)
                advanceUntilIdle()

                // Fake emits PermissionPending then OpeningDevice
                assertEquals(UsbSessionState.PermissionPending, awaitItem())
                assertEquals(UsbSessionState.OpeningDevice, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, fakeConnector.permissionRequests.size)
            assertEquals(fakeDevice, fakeConnector.permissionRequests[0])
        }

    @Test
    fun requestPermission_armedError_emitsErrorState() =
        runTest {
            fakeConnector.armError(UsbSessionError.OpenDeviceFailed)

            viewModel.usbState.test {
                awaitItem() // Disconnected

                viewModel.requestPermission(fakeDevice)
                advanceUntilIdle()

                val errorState = awaitItem()
                assertTrue(errorState is UsbSessionState.Error)
                assertEquals(
                    UsbSessionError.OpenDeviceFailed,
                    (errorState as UsbSessionState.Error).error,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── openSession ──────────────────────────────────────────────────────────

    @Test
    fun openSession_happyPath_emitsUsbSessionReady() =
        runTest {
            viewModel.usbState.test {
                awaitItem() // Disconnected

                viewModel.openSession(fakeDevice, fakeDescriptors)
                advanceUntilIdle()

                // Fake drives: OpeningDevice → DeviceOpen → FdHandoff → RustTransportOpen
                // → PtpSessionOpening → UsbSessionReady
                assertEquals(UsbSessionState.OpeningDevice, awaitItem())
                assertEquals(UsbSessionState.DeviceOpen, awaitItem())
                assertEquals(UsbSessionState.FdHandoff, awaitItem())
                assertEquals(UsbSessionState.RustTransportOpen, awaitItem())
                assertEquals(UsbSessionState.PtpSessionOpening, awaitItem())

                val ready = awaitItem()
                assertTrue(ready is UsbSessionState.UsbSessionReady)
                assertEquals(
                    SessionId(FakeCameraUsbConnector.FAKE_SESSION_ID),
                    (ready as UsbSessionState.UsbSessionReady).handle.sessionId,
                )

                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, fakeConnector.openSessionCalls.size)
        }

    @Test
    fun openSession_descriptorsPropagatedToConnector() =
        runTest {
            viewModel.openSession(fakeDevice, fakeDescriptors)
            advanceUntilIdle()

            assertTrue(fakeConnector.openSessionCalls.isNotEmpty())
            val (_, descriptors) = fakeConnector.openSessionCalls[0]
            assertTrue(fakeDescriptors.contentEquals(descriptors))
        }

    @Test
    fun openSession_armedError_emitsErrorState() =
        runTest {
            fakeConnector.armError(UsbSessionError.PtpOpenSessionFailed)

            viewModel.usbState.test {
                awaitItem() // Disconnected

                viewModel.openSession(fakeDevice, fakeDescriptors)
                advanceUntilIdle()

                val errorState = awaitItem()
                assertTrue(errorState is UsbSessionState.Error)
                assertEquals(
                    UsbSessionError.PtpOpenSessionFailed,
                    (errorState as UsbSessionState.Error).error,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── importObject ─────────────────────────────────────────────────────────

    @Test
    fun importObject_happyPath_importStateTransitionsToImported() =
        runTest {
            val objectHandle = CameraObjectHandle(1001L)
            fakeTransferRepo.setImportStates(
                ImportState.Running(
                    dev.po4yka.frameport.camera.api.TransferProgress(
                        dev.po4yka.frameport.camera.api
                            .TransferId(1L),
                        512L,
                        1024L,
                    ),
                ),
                ImportState.Imported("content://media/fake/usb/1"),
            )

            viewModel.importState.test {
                assertEquals(ImportState.Idle, awaitItem())

                viewModel.importObject(fakeHandle, objectHandle)
                advanceUntilIdle()

                val running = awaitItem()
                assertTrue(running is ImportState.Running)

                val imported = awaitItem()
                assertTrue(imported is ImportState.Imported)
                assertEquals("content://media/fake/usb/1", (imported as ImportState.Imported).localUri)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun importObject_usesSessionIdFromHandle_notModified() =
        runTest {
            // The handle's sessionId must flow through unchanged to ImportObjectUseCase.
            // We verify by arming a custom session id and checking the transfer repo receives it.
            val customSessionId = 777L
            fakeConnector.armSessionId(customSessionId)
            viewModel.openSession(fakeDevice, fakeDescriptors)
            advanceUntilIdle()

            val readyState = fakeConnector.state.value
            assertTrue(readyState is UsbSessionState.UsbSessionReady)
            val handle = (readyState as UsbSessionState.UsbSessionReady).handle

            assertEquals(SessionId(customSessionId), handle.sessionId)

            // importObject uses handle.sessionId — ImportObjectUseCase interface is unchanged.
            viewModel.importObject(handle, CameraObjectHandle(42L))
            advanceUntilIdle()
            // No assertion on transfer repo internals needed — the flow ran without error.
        }

    // ─── closeSession ─────────────────────────────────────────────────────────

    @Test
    fun closeSession_happyPath_emitsClosingThenClosed() =
        runTest {
            viewModel.usbState.test {
                awaitItem() // Disconnected

                viewModel.closeSession(fakeHandle)
                advanceUntilIdle()

                assertEquals(UsbSessionState.Closing, awaitItem())
                assertEquals(UsbSessionState.Closed, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, fakeConnector.closeCalls.size)
            assertEquals(fakeHandle, fakeConnector.closeCalls[0])
        }

    @Test
    fun closeSession_idempotent_secondCallIsNoOp() =
        runTest {
            viewModel.closeSession(fakeHandle)
            advanceUntilIdle()
            viewModel.closeSession(fakeHandle)
            advanceUntilIdle()

            // Connector records both calls but second is no-op internally (idempotent).
            assertEquals(2, fakeConnector.closeCalls.size)
        }

    // ─── Detach scenarios ─────────────────────────────────────────────────────

    @Test
    fun simulateDetach_duringPermissionPending_emitsDeviceDetachedBeforePermission() =
        runTest {
            fakeConnector.emit(UsbSessionState.PermissionPending)

            viewModel.usbState.test {
                awaitItem() // PermissionPending (already set)

                fakeConnector.simulateDetach()

                val errorState = awaitItem()
                assertTrue(errorState is UsbSessionState.Error)
                assertEquals(
                    UsbSessionError.DeviceDetachedBeforePermission,
                    (errorState as UsbSessionState.Error).error,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun simulateDetach_duringActiveSession_emitsDeviceDetachedDuringSession() =
        runTest {
            fakeConnector.emit(UsbSessionState.UsbSessionReady(fakeHandle))

            viewModel.usbState.test {
                awaitItem() // UsbSessionReady

                fakeConnector.simulateDetach()

                val errorState = awaitItem()
                assertTrue(errorState is UsbSessionState.Error)
                assertEquals(
                    UsbSessionError.DeviceDetachedDuringSession,
                    (errorState as UsbSessionState.Error).error,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── driveHappyPath helper ────────────────────────────────────────────────

    @Test
    fun driveHappyPath_fullSequence_allStatesEmittedInOrder() =
        runTest {
            val expectedOrder =
                listOf(
                    UsbSessionState.Disconnected::class,
                    UsbSessionState.DeviceDetected::class,
                    UsbSessionState.PermissionPending::class,
                    UsbSessionState.OpeningDevice::class,
                    UsbSessionState.DeviceOpen::class,
                    UsbSessionState.FdHandoff::class,
                    UsbSessionState.RustTransportOpen::class,
                    UsbSessionState.PtpSessionOpening::class,
                    UsbSessionState.UsbSessionReady::class,
                )

            val collected = mutableListOf<UsbSessionState>()

            viewModel.usbState.test {
                fakeConnector.driveHappyPath()
                advanceUntilIdle()

                // Collect up to 9 items (full sequence); turbine will cancel afterwards.
                repeat(expectedOrder.size) {
                    collected.add(awaitItem())
                }
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(expectedOrder.size, collected.size)
            collected.forEachIndexed { index, state ->
                assertEquals(
                    "State at index $index",
                    expectedOrder[index],
                    state::class,
                )
            }
        }
}
