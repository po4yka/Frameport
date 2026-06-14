package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.api.CameraSessionState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.model.FrameportError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CameraSessionState.isTerminal].
 *
 * Pure JVM — no Android framework, no Robolectric.
 */
class CameraSessionStateTest {
    @Test
    fun `given Closed state, when isTerminal, then returns true`() {
        assertTrue(CameraSessionState.Closed.isTerminal())
    }

    @Test
    fun `given Failed state, when isTerminal, then returns true`() {
        val state = CameraSessionState.Failed(FrameportError.Unknown("protocol error"))
        assertTrue(state.isTerminal())
    }

    @Test
    fun `given Idle state, when isTerminal, then returns false`() {
        assertFalse(CameraSessionState.Idle.isTerminal())
    }

    @Test
    fun `given Connecting state, when isTerminal, then returns false`() {
        assertFalse(CameraSessionState.Connecting.isTerminal())
    }

    @Test
    fun `given SessionReady state, when isTerminal, then returns false`() {
        assertFalse(CameraSessionState.SessionReady(SessionId(42L)).isTerminal())
    }
}
