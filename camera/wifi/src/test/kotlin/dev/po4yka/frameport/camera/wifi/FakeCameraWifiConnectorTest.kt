package dev.po4yka.frameport.camera.wifi

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.CameraEndpoint
import dev.po4yka.frameport.camera.api.CameraNetworkHandle
import dev.po4yka.frameport.camera.api.CameraWifiCredentials
import dev.po4yka.frameport.camera.api.CameraWifiError
import dev.po4yka.frameport.camera.api.CameraWifiState
import dev.po4yka.frameport.core.testing.FakeCameraWifiConnector
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [FakeCameraWifiConnector] — verifies that the test harness emits the correct
 * typed [CameraWifiState] sequences for each scenario.
 *
 * Uses Turbine to assert exact ordered state emissions via StateFlow.
 * All tests run with [UnconfinedTestDispatcher] so coroutines execute eagerly without yielding.
 */
class FakeCameraWifiConnectorTest {
    private lateinit var fake: FakeCameraWifiConnector

    private val defaultCredentials = CameraWifiCredentials(ssid = "FUJIFILM-XTF", passphrase = "secret")
    private val defaultEndpoint = CameraEndpoint(host = "192.168.0.1", port = 55740)
    private val defaultHandle = CameraNetworkHandle(FakeCameraWifiConnector.FAKE_NETWORK_HANDLE)

    @Before
    fun setUp() {
        fake = FakeCameraWifiConnector()
    }

    /**
     * Happy-path: calling requestCameraNetwork then openBoundSocket drives the full ordered sequence:
     * Idle -> RequestingNetwork -> NetworkAvailable -> BindingSocket -> ConnectingSocket
     * -> HandingOffSocketFd -> Connected
     */
    @Test
    fun `happy-path emits full ordered state sequence from Idle to Connected`() =
        runTest(UnconfinedTestDispatcher()) {
            fake.state.test {
                // Initial emission from StateFlow
                assertEquals(CameraWifiState.Idle, awaitItem())

                // Drive requestCameraNetwork
                val networkResult = fake.requestCameraNetwork(defaultCredentials)
                assertTrue(networkResult.isSuccess)
                assertEquals(CameraWifiState.RequestingNetwork, awaitItem())
                assertEquals(CameraWifiState.NetworkAvailable, awaitItem())

                // Drive openBoundSocket
                val socketResult = fake.openBoundSocket(defaultHandle, defaultEndpoint)
                assertTrue(socketResult.isSuccess)
                assertEquals(CameraWifiState.BindingSocket, awaitItem())
                assertEquals(CameraWifiState.ConnectingSocket, awaitItem())
                assertEquals(CameraWifiState.HandingOffSocketFd, awaitItem())
                assertEquals(CameraWifiState.Connected, awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * User-rejection: arming UserRejectedNetworkRequest before requestCameraNetwork causes the
     * terminal state Error(UserRejectedNetworkRequest) to be emitted.
     */
    @Test
    fun `user-rejection - armed error produces terminal Error(UserRejectedNetworkRequest)`() =
        runTest(UnconfinedTestDispatcher()) {
            fake.armError(CameraWifiError.UserRejectedNetworkRequest)

            fake.state.test {
                assertEquals(CameraWifiState.Idle, awaitItem())

                val result = fake.requestCameraNetwork(defaultCredentials)
                assertTrue(result.isFailure)

                val terminal = awaitItem()
                assertEquals(CameraWifiState.Error(CameraWifiError.UserRejectedNetworkRequest), terminal)

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Socket-bind-failure: arming SocketBindFailed("loopback") before openBoundSocket causes
     * terminal Error(SocketBindFailed) with the exact detail string.
     */
    @Test
    fun `socket-bind-failure - armed error produces terminal Error(SocketBindFailed) with detail`() =
        runTest(UnconfinedTestDispatcher()) {
            fake.state.test {
                assertEquals(CameraWifiState.Idle, awaitItem())

                // First drive to NetworkAvailable via requestCameraNetwork
                fake.requestCameraNetwork(defaultCredentials)
                assertEquals(CameraWifiState.RequestingNetwork, awaitItem())
                assertEquals(CameraWifiState.NetworkAvailable, awaitItem())

                // Arm the socket-bind error, then call openBoundSocket
                fake.armError(CameraWifiError.SocketBindFailed("loopback"))
                val result = fake.openBoundSocket(defaultHandle, defaultEndpoint)
                assertTrue(result.isFailure)

                val terminal = awaitItem()
                val expected = CameraWifiState.Error(CameraWifiError.SocketBindFailed("loopback"))
                assertEquals(expected, terminal)
                // Verify the detail string is exactly "loopback"
                val errorCause = (terminal as CameraWifiState.Error).cause as CameraWifiError.SocketBindFailed
                assertEquals("loopback", errorCause.detail)

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Network-lost-mid-session: drive to Connected, then inject NetworkLost via failWith;
     * assert Error(NetworkLost) follows Connected.
     */
    @Test
    fun `network-lost-mid-session - Error(NetworkLost) follows Connected state`() =
        runTest(UnconfinedTestDispatcher()) {
            fake.state.test {
                assertEquals(CameraWifiState.Idle, awaitItem())

                fake.requestCameraNetwork(defaultCredentials)
                assertEquals(CameraWifiState.RequestingNetwork, awaitItem())
                assertEquals(CameraWifiState.NetworkAvailable, awaitItem())

                fake.openBoundSocket(defaultHandle, defaultEndpoint)
                assertEquals(CameraWifiState.BindingSocket, awaitItem())
                assertEquals(CameraWifiState.ConnectingSocket, awaitItem())
                assertEquals(CameraWifiState.HandingOffSocketFd, awaitItem())
                assertEquals(CameraWifiState.Connected, awaitItem())

                // Simulate network loss mid-session
                fake.failWith(CameraWifiError.NetworkLost)
                assertEquals(CameraWifiState.Error(CameraWifiError.NetworkLost), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Release-idempotency: calling release() twice must not throw; Closed is emitted exactly once;
     * the second call is a silent no-op.
     */
    @Test
    fun `release-idempotency - second release() is no-op and Closed emitted exactly once`() =
        runTest(UnconfinedTestDispatcher()) {
            // Drive to Connected first
            fake.requestCameraNetwork(defaultCredentials)
            fake.openBoundSocket(defaultHandle, defaultEndpoint)

            fake.state.test {
                // Consume the current Connected state
                assertEquals(CameraWifiState.Connected, awaitItem())

                // First release — should emit Releasing then Closed
                fake.release(defaultHandle)
                assertEquals(CameraWifiState.Releasing, awaitItem())
                assertEquals(CameraWifiState.Closed, awaitItem())

                // Second release — must be a no-op; no additional state emission
                fake.release(defaultHandle)
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    /**
     * Permission-denied: arming PermissionDenied before requestCameraNetwork causes the first
     * terminal state to be Error(PermissionDenied), never reaching RequestingNetwork.
     */
    @Test
    fun `permission-denied - Error(PermissionDenied) is terminal before RequestingNetwork`() =
        runTest(UnconfinedTestDispatcher()) {
            fake.armError(CameraWifiError.PermissionDenied)

            fake.state.test {
                assertEquals(CameraWifiState.Idle, awaitItem())

                val result = fake.requestCameraNetwork(defaultCredentials)
                assertTrue(result.isFailure)

                // The first (and only) state emitted after Idle must be Error(PermissionDenied)
                val firstTerminal = awaitItem()
                assertEquals(CameraWifiState.Error(CameraWifiError.PermissionDenied), firstTerminal)

                // No RequestingNetwork or NetworkAvailable must ever appear
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }
}
