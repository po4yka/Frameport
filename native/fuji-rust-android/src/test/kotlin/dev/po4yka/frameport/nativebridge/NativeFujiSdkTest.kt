package dev.po4yka.frameport.nativebridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFujiSdkTest {
    @Test
    fun noOpSdkProvidesNonCrashingSessionLifecycle() {
        val sdk = NoOpNativeFujiSdk()

        assertFalse(sdk.diagnosticState.isNativeLibraryLoaded)
        assertTrue(sdk.initialize().isSuccess)
        assertTrue(sdk.openNoopSession().getOrThrow().isNoOp)
        assertTrue(sdk.closeSession(NativeCameraSession.NoOp).isSuccess)
        assertTrue(sdk.shutdown().isSuccess)
    }

    @Test
    fun jniSdkFallsBackWhenNativeLibraryIsMissing() {
        val sdk = JniNativeFujiSdk(
            loader = NativeLibraryLoader("missing_frameport_test") {
                throw UnsatisfiedLinkError("missing_frameport_test")
            },
        )

        assertFalse(sdk.diagnosticState.isNativeLibraryLoaded)
        assertTrue(sdk.initialize().isSuccess)
        assertTrue(sdk.openNoopSession().getOrThrow().isNoOp)
        assertTrue(sdk.closeSession(NativeCameraSession.NoOp).isSuccess)
        assertTrue(sdk.shutdown().isSuccess)
    }
}
