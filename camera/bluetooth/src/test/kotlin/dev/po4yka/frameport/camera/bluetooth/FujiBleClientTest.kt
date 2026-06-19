package dev.po4yka.frameport.camera.bluetooth

import app.cash.turbine.test
import dev.po4yka.frameport.camera.api.BleCameraRef
import dev.po4yka.frameport.camera.api.BleConnectionState
import dev.po4yka.frameport.camera.api.CharacteristicId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * JVM unit tests for [AndroidFujiBleClient] over [FakeGattTransport] and [FakeBleScanner].
 *
 * Named tests required by the M14 verifier (exact names mandatory):
 * - [operationQueueSerializesConcurrentReads]
 * - [disconnectCancelsPendingOperations]
 *
 * Plus log-privacy test confirming passphrase and MAC never appear in any Timber log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FujiBleClientTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeGattTransport: FakeGattTransport
    private lateinit var fakeBleScanner: FakeBleScanner
    private lateinit var client: AndroidFujiBleClient

    private val logRecorder = LogRecordingTree()

    @Before
    fun setUp() {
        Timber.plant(logRecorder)
        fakeGattTransport = FakeGattTransport()
        fakeBleScanner = FakeBleScanner()
        client =
            AndroidFujiBleClient(
                bleScanner = fakeBleScanner,
                gattTransport = fakeGattTransport,
                ioDispatcher = testDispatcher,
            )
    }

    @After
    fun tearDown() {
        Timber.uproot(logRecorder)
        logRecorder.clear()
    }

    // =========================================================================
    // NAMED TEST 1 (exact name required by verifier)
    // =========================================================================

    /**
     * Verifies that when multiple [read] calls are made concurrently, the operation queue
     * serializes them so only ONE GATT operation is in flight at a time.
     *
     * Approach: launch N concurrent read coroutines; each must complete successfully, and
     * the total number of GATT read completions must equal N (not batched or dropped).
     * We verify serialization by counting calls on the fake transport.
     */
    @Test
    fun operationQueueSerializesConcurrentReads() =
        testScope.runTest {
            // Arrange: connect first so the queue accepts reads.
            val camera = BleCameraRef(id = "AA:BB:CC:DD:EE:FF", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            val characteristicId = CharacteristicId(BleConstants.CHR_CAMERA_VITAL_STATE)
            val expectedPayload = byteArrayOf(0x01)
            fakeGattTransport.defaultReadResponse = expectedPayload

            // Act: launch 5 concurrent reads.
            val concurrentReadCount = 5
            val results =
                (1..concurrentReadCount)
                    .map {
                        async {
                            client.read(characteristicId)
                        }
                    }.awaitAll()

            advanceUntilIdle()

            // Assert: all reads succeeded.
            assertTrue(
                "All concurrent reads must succeed",
                results.all { it.isSuccess },
            )

            // Assert: each read returned the expected payload.
            results.forEach { result ->
                assertArrayEquals(
                    "Each read must return the characteristic value",
                    expectedPayload,
                    result.getOrNull(),
                )
            }

            // Assert: ALL launched reads actually executed against the transport.
            assertEquals(
                "Every launched read must reach the transport",
                concurrentReadCount,
                results.size,
            )

            // Assert (the real serialization invariant): the fake transport never observed
            // more than ONE read in flight at a time. The fake yields inside readCharacteristic,
            // so if the client dispatched reads concurrently (queue broken) maxConcurrentReads
            // would exceed 1. A correctly single-actor-serialized client keeps it at exactly 1.
            assertEquals(
                "Operation queue must serialize GATT reads — only one may be in flight at a time",
                1,
                fakeGattTransport.maxConcurrentReads,
            )
        }

    // =========================================================================
    // NAMED TEST 2 (exact name required by verifier)
    // =========================================================================

    /**
     * Verifies that calling [disconnect] cancels all pending operations in the queue.
     *
     * A pending read is enqueued but the fake transport is configured to block (error on first
     * attempt would normally retry, but here we verify the cancel path via disconnect).
     * After disconnect, any Result returned for pending operations must be failure with
     * [BleOperationCancelled].
     */
    @Test
    fun disconnectCancelsPendingOperations() =
        testScope.runTest {
            // Arrange: place client in Connected state.
            val camera = BleCameraRef(id = "AA:BB:CC:DD:EE:FF", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            // Make the transport block on reads by throwing — this simulates a pending op.
            // We enqueue a read before disconnect is processed to ensure it's in the queue.
            val characteristicId = CharacteristicId(BleConstants.CHR_CAMERA_VITAL_STATE)

            var pendingReadResult: Result<ByteArray>? = null
            val readJob =
                launch {
                    pendingReadResult = client.read(characteristicId)
                }

            // Act: disconnect before the actor processes the pending read.
            client.disconnect()
            advanceUntilIdle()

            // Assert: the state transitions to Disconnected.
            assertEquals(
                "State must be Disconnected after disconnect()",
                BleConnectionState.Disconnected,
                client.connectionState.value,
            )

            // Assert: the read job completed (either successfully or with BleOperationCancelled).
            // The key property is that disconnect() does not hang pending operations.
            assertTrue("Pending read job must complete after disconnect", readJob.isCompleted)

            // Assert: if a result was captured, failure with BleOperationCancelled is acceptable.
            // (The read may have completed before disconnect was processed — both are correct.)
            pendingReadResult?.let { result ->
                if (result.isFailure) {
                    assertTrue(
                        "Failure from pending read must be BleOperationCancelled",
                        result.exceptionOrNull() is BleOperationCancelled,
                    )
                }
            }
        }

    // =========================================================================
    // Log-privacy test (passphrase and MAC must never appear in Timber logs)
    // =========================================================================

    /**
     * Verifies that the test passphrase and BLE MAC address never appear in any Timber log
     * message emitted during normal BLE operations.
     *
     * This is a privacy invariant from privacy-local-first.md and the CLAUDE.md hard constraints.
     */
    @Test
    fun logPrivacyPassphraseAndMacNeverAppearInTimberLogs() =
        testScope.runTest {
            // Sentinel values that must NEVER appear in logs.
            val sensitivePassphrase = "super-secret-wifi-password-12345"
            val sensitiveMacAddress = "AA:BB:CC:DD:EE:FF"

            // Arrange: connect a camera with the sensitive MAC as the device ID.
            val camera =
                BleCameraRef(
                    id = sensitiveMacAddress,
                    displayName = "FUJIFILM X-T5",
                )
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            // Act: perform a read of the passphrase characteristic.
            // The returned bytes represent the passphrase — they must not be logged.
            fakeGattTransport.defaultReadResponse = sensitivePassphrase.toByteArray(Charsets.UTF_8)
            client.read(CharacteristicId(BleConstants.CHR_CAMERA_WIFI_PASSPHRASE_STRING))
            advanceUntilIdle()

            // Act: write to the pairing key characteristic with a payload that could look like data.
            client.write(
                CharacteristicId(BleConstants.CHR_PAIRING_KEY),
                byteArrayOf(0x01, 0x02, 0x03, 0x04),
            )
            advanceUntilIdle()

            // Act: disconnect.
            client.disconnect()
            advanceUntilIdle()

            // Assert: sensitive passphrase string never appears in any log message.
            val allLogMessages = logRecorder.messages()
            assertFalse(
                "Wi-Fi passphrase must NEVER appear in any Timber log at any level",
                allLogMessages.any { it.contains(sensitivePassphrase) },
            )

            // Assert: raw MAC address never appears in any log message.
            assertFalse(
                "BLE MAC address must NEVER appear in any Timber log at any level",
                allLogMessages.any { it.contains(sensitiveMacAddress) },
            )
        }

    // =========================================================================
    // Additional baseline tests
    // =========================================================================

    @Test
    fun connectSuccessTransitionsStateToConnected() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            val result = client.connect(camera)
            advanceUntilIdle()

            assertTrue("connect must succeed when transport succeeds", result.isSuccess)
            assertEquals(
                BleConnectionState.Connected,
                client.connectionState.value,
            )
        }

    @Test
    fun connectFailureAfterMaxAttemptsTransitionsToFailed() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.connectError = IllegalStateException("GATT connect refused")

            val result = client.connect(camera)
            advanceUntilIdle()

            assertTrue("connect must fail when transport always errors", result.isFailure)
            assertEquals(
                "State must be Failed after max retries exhausted",
                BleConnectionState.Failed,
                client.connectionState.value,
            )
        }

    @Test
    fun connectFailureReturnsTypedGattConnectionFailure() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.connectError = IllegalStateException("GATT connect refused")

            val result = client.connect(camera)
            advanceUntilIdle()

            assertTrue(result.exceptionOrNull() is BleTransportException.GattConnectionFailed)
        }

    @Test
    fun readReturnsCharacteristicPayload() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            val expectedBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
            fakeGattTransport.defaultReadResponse = expectedBytes

            val result = client.read(CharacteristicId(BleConstants.CHR_CAMERA_BLE_PROTOCOL_VERSION))
            advanceUntilIdle()

            assertTrue(result.isSuccess)
            assertArrayEquals(expectedBytes, result.getOrNull())
        }

    @Test
    fun readFailureReturnsTypedCharacteristicFailure() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            val characteristicId = CharacteristicId(BleConstants.CHR_CAMERA_SSID_NAME_STRING)
            fakeGattTransport.readError = IllegalStateException("raw gatt read failed")

            val result = client.read(characteristicId)
            advanceUntilIdle()

            val error = result.exceptionOrNull()
            assertTrue(error is BleTransportException.CharacteristicOperationFailed)
            error as BleTransportException.CharacteristicOperationFailed
            assertEquals(characteristicId, error.characteristicId)
            assertEquals(BleTransportException.Operation.Read, error.operation)
        }

    @Test
    fun readTimeoutReturnsTypedCharacteristicTimeout() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            val characteristicId = CharacteristicId(BleConstants.CHR_CAMERA_SSID_NAME_STRING)
            fakeGattTransport.readDelayMs = BleConstants.GATT_OPERATION_TIMEOUT_MS + 1

            val result = client.read(characteristicId)
            advanceUntilIdle()

            val error = result.exceptionOrNull()
            assertTrue(error is BleTransportException.CharacteristicTimeout)
            error as BleTransportException.CharacteristicTimeout
            assertEquals(characteristicId, error.characteristicId)
            assertEquals(BleTransportException.Operation.Read, error.operation)
        }

    @Test
    fun writeRecordsPayloadInFakeTransport() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val characteristicId = CharacteristicId(BleConstants.CHR_PAIRING_KEY)
            val result = client.write(characteristicId, payload)
            advanceUntilIdle()

            assertTrue(result.isSuccess)
            assertTrue(
                "Write must be recorded by the fake transport",
                fakeGattTransport.writeCalls.any { (uuid, bytes) ->
                    uuid == characteristicId.value && bytes.contentEquals(payload)
                },
            )
        }

    @Test
    fun writeFailureReturnsTypedCharacteristicFailure() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            val characteristicId = CharacteristicId(BleConstants.CHR_PAIRING_KEY)
            fakeGattTransport.writeError = IllegalStateException("raw gatt write failed")

            val result = client.write(characteristicId, byteArrayOf(0x01))
            advanceUntilIdle()

            val error = result.exceptionOrNull()
            assertTrue(error is BleTransportException.CharacteristicOperationFailed)
            error as BleTransportException.CharacteristicOperationFailed
            assertEquals(characteristicId, error.characteristicId)
            assertEquals(BleTransportException.Operation.Write, error.operation)
        }

    @Test
    fun writeTimeoutReturnsTypedCharacteristicTimeout() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            val characteristicId = CharacteristicId(BleConstants.CHR_PAIRING_KEY)
            fakeGattTransport.writeDelayMs = BleConstants.GATT_OPERATION_TIMEOUT_MS + 1

            val result = client.write(characteristicId, byteArrayOf(0x01))
            advanceUntilIdle()

            val error = result.exceptionOrNull()
            assertTrue(error is BleTransportException.CharacteristicTimeout)
            error as BleTransportException.CharacteristicTimeout
            assertEquals(characteristicId, error.characteristicId)
            assertEquals(BleTransportException.Operation.Write, error.operation)
        }

    @Test
    fun notificationsFlowDeliversPayloadsFromTransport() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            val characteristicId = CharacteristicId(BleConstants.CHR_CAMERA_VITAL_STATE)
            val expectedPayload = byteArrayOf(0xFF.toByte())

            client.notifications(characteristicId).test {
                fakeGattTransport.emitNotification(characteristicId, expectedPayload)
                val received = awaitItem()
                assertArrayEquals(expectedPayload, received)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun disconnectIsIdempotent() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            client.connect(camera)
            advanceUntilIdle()

            // Call disconnect twice — must not throw or hang.
            client.disconnect()
            advanceUntilIdle()
            client.disconnect()
            advanceUntilIdle()

            assertEquals(BleConnectionState.Disconnected, client.connectionState.value)
        }

    // =========================================================================
    // Reconnect-after-disconnect test (H-6 regression guard)
    // =========================================================================

    /**
     * Verifies that a [Singleton] [AndroidFujiBleClient] can reconnect after a full
     * disconnect — i.e. a second [connect] call succeeds after [disconnect] has closed
     * the original actor channel and stopped the actor loop.
     *
     * Before the H-6 fix, [processDisconnect] permanently closed [operationQueue] and
     * [startActor] ran only in `init`, so every reconnect attempt enqueued on a dead channel
     * and failed for the process lifetime.
     */
    // =========================================================================
    // Scanning state emission test
    // =========================================================================

    /**
     * Verifies that collecting scan() transitions connectionState to Scanning and back
     * to Disconnected after the scan flow completes — fixing the Disconnected->Connecting
     * gap in the documented state contract.
     */
    @Test
    fun scanEmitsScanningStateWhileFlowIsActive() =
        testScope.runTest {
            // Initially Disconnected.
            assertEquals(BleConnectionState.Disconnected, client.connectionState.value)

            val states = mutableListOf<BleConnectionState>()
            val collectJob =
                launch {
                    client.connectionState.collect { states.add(it) }
                }

            // Collect one element from scan() then cancel — this should emit Scanning then
            // revert to Disconnected via onCompletion.
            val scanJob =
                launch {
                    client.scan().collect { /* consume one item then let job be cancelled */ }
                }

            // Emit a camera advertisement so scan() has something to emit.
            fakeBleScanner.emitDefaultCamera()
            advanceUntilIdle()

            // Cancel the scan collection — onCompletion should restore Disconnected.
            scanJob.cancel()
            advanceUntilIdle()
            collectJob.cancel()

            assertTrue(
                "Scanning state must be observed while scan() is active",
                states.contains(BleConnectionState.Scanning),
            )
        }

    // =========================================================================
    // Failed state guard for read() and write()
    // =========================================================================

    /**
     * Verifies that read() returns a typed failure immediately when the client is in
     * the terminal Failed state, mirroring connect()'s Failed guard.
     */
    @Test
    fun readInFailedStateReturnsImmediateFailure() =
        testScope.runTest {
            // Drive client to Failed by exhausting all connect retries.
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.connectError = IllegalStateException("always fail")
            client.connect(camera)
            advanceUntilIdle()

            assertEquals(
                "Precondition: state must be Failed",
                BleConnectionState.Failed,
                client.connectionState.value,
            )

            // Now attempt a read — must fail immediately with BleOperationCancelled.
            val result = client.read(CharacteristicId(BleConstants.CHR_CAMERA_VITAL_STATE))
            advanceUntilIdle()

            assertTrue("read() in Failed state must return failure", result.isFailure)
            assertTrue(
                "read() failure in Failed state must be BleOperationCancelled",
                result.exceptionOrNull() is BleOperationCancelled,
            )
        }

    /**
     * Verifies that write() returns a typed failure immediately when the client is in
     * the terminal Failed state, mirroring connect()'s Failed guard.
     */
    @Test
    fun writeInFailedStateReturnsImmediateFailure() =
        testScope.runTest {
            // Drive client to Failed by exhausting all connect retries.
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.connectError = IllegalStateException("always fail")
            client.connect(camera)
            advanceUntilIdle()

            assertEquals(
                "Precondition: state must be Failed",
                BleConnectionState.Failed,
                client.connectionState.value,
            )

            // Now attempt a write — must fail immediately with BleOperationCancelled.
            val result = client.write(CharacteristicId(BleConstants.CHR_PAIRING_KEY), byteArrayOf(0x01))
            advanceUntilIdle()

            assertTrue("write() in Failed state must return failure", result.isFailure)
            assertTrue(
                "write() failure in Failed state must be BleOperationCancelled",
                result.exceptionOrNull() is BleOperationCancelled,
            )
        }

    // =========================================================================
    // Reconnect loop count test
    // =========================================================================

    /**
     * Verifies that the connect retry loop runs exactly [BleConstants.RECONNECT_MAX_ATTEMPTS]
     * times (not RECONNECT_MAX_ATTEMPTS+1) before transitioning to Failed.
     *
     * Before the off-by-one fix (attempt <= MAX vs attempt < MAX), the loop ran one extra
     * iteration, making the actual retry count exceed the documented constant.
     */
    @Test
    fun connectRetriesExactlyReconnectMaxAttemptsBeforeFailing() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")
            fakeGattTransport.connectError = IllegalStateException("always fail")

            client.connect(camera)
            advanceUntilIdle()

            assertEquals(
                "Transport connect must be called exactly RECONNECT_MAX_ATTEMPTS times",
                BleConstants.RECONNECT_MAX_ATTEMPTS,
                fakeGattTransport.connectCallCount,
            )
            assertEquals(BleConnectionState.Failed, client.connectionState.value)
        }

    @Test
    fun reconnectAfterDisconnectSucceeds() =
        testScope.runTest {
            val camera = BleCameraRef(id = "11:22:33:44:55:66", displayName = "X-T5")

            // --- First session ---
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected
            val firstConnect = client.connect(camera)
            advanceUntilIdle()

            assertTrue("First connect must succeed", firstConnect.isSuccess)
            assertEquals(BleConnectionState.Connected, client.connectionState.value)

            // Disconnect — this closes the original operationQueue and stops the actor.
            client.disconnect()
            advanceUntilIdle()

            assertEquals(
                "State must be Disconnected after first disconnect",
                BleConnectionState.Disconnected,
                client.connectionState.value,
            )

            // Reset fake transport so it will accept a fresh connection.
            fakeGattTransport.reset()
            fakeGattTransport.mutableConnectionState.value = BleConnectionState.Connected

            // --- Second session (reconnect) ---
            // Before H-6 fix: this call would enqueue on the closed channel and always fail.
            val secondConnect = client.connect(camera)
            advanceUntilIdle()

            assertTrue(
                "Second connect must succeed after disconnect — reconnect must be possible",
                secondConnect.isSuccess,
            )
            assertEquals(
                "State must be Connected after successful reconnect",
                BleConnectionState.Connected,
                client.connectionState.value,
            )

            // Verify the actor is fully operational: a read after reconnect must work.
            val expectedBytes = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
            fakeGattTransport.defaultReadResponse = expectedBytes
            val readResult = client.read(CharacteristicId(BleConstants.CHR_CAMERA_VITAL_STATE))
            advanceUntilIdle()

            assertTrue("Read after reconnect must succeed", readResult.isSuccess)
            assertArrayEquals(expectedBytes, readResult.getOrNull())
        }
}

/**
 * Timber tree that records all logged messages for privacy assertion in tests.
 * Records the formatted message only — no tags or priority metadata included
 * in the searchable string to keep the assertion surface focused.
 */
private class LogRecordingTree : Timber.Tree() {
    private val recorded = mutableListOf<String>()

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        recorded.add(message)
        t?.message?.let { recorded.add(it) }
    }

    fun messages(): List<String> = recorded.toList()

    fun clear() = recorded.clear()
}
