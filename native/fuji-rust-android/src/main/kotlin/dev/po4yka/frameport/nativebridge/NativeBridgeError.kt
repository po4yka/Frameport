package dev.po4yka.frameport.nativebridge

/**
 * Typed error hierarchy for integer-sentinel failures returned by JNI entry points
 * in [NativeFujiJni]. Replaces bare [IllegalStateException] so callers can branch
 * on the failure kind without parsing message strings.
 *
 * Wire contract: the underlying integer sentinel values are defined in
 * [NativeFujiJni] (`OK`, `ERR_NOT_INITIALIZED`, `ERR_INVALID_SESSION`, `ERR_PANIC`)
 * and must not change without updating the Rust constants in `fuji-ffi/src/lib.rs`.
 */
sealed class NativeBridgeError(
    message: String,
) : RuntimeException(message) {
    /** Rust returned [NativeFujiJni.ERR_NOT_INITIALIZED] (-1): SDK not yet initialised. */
    class NotInitialized(
        operation: String,
    ) : NativeBridgeError("$operation failed: native SDK not initialised (ERR_NOT_INITIALIZED)")

    /** Rust returned [NativeFujiJni.ERR_INVALID_SESSION] (-2): session id unknown or expired. */
    class InvalidSession(
        operation: String,
        code: Int,
    ) : NativeBridgeError("$operation failed: invalid or expired session (code $code)")

    /**
     * Rust returned [NativeFujiJni.ERR_PANIC] (-100) or [NativeFujiJni] threw
     * [NativeException]. The JVM process is in an unknown state for this session.
     */
    class Panic(
        operation: String,
    ) : NativeBridgeError("$operation failed: native panic detected (ERR_PANIC)")

    /** Rust returned an unrecognised negative sentinel not covered by the above variants. */
    class Unknown(
        operation: String,
        code: Int,
    ) : NativeBridgeError("$operation failed with unrecognised native error code $code")
}
