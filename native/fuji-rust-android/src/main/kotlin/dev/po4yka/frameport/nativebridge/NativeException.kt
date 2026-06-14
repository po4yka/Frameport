package dev.po4yka.frameport.nativebridge

/**
 * Exception thrown by the fuji-ffi native layer for unexpected/exceptional
 * failures (e.g. a caught Rust panic). Expected/recoverable conditions
 * (unknown session, not initialised) are reported as integer sentinels on
 * jint-returning entry points instead.
 *
 * JNI's ThrowNew can only invoke the single-String constructor, so [errorCode]
 * defaults to [ERR_NATIVE] on that path. Kotlin callers that have an explicit
 * code should use the two-arg constructor.
 */
class NativeException : RuntimeException {
    val errorCode: Int

    constructor(errorCode: Int, message: String) : super(message) {
        this.errorCode = errorCode
    }

    /** Used by JNI ThrowNew — errorCode defaults to [ERR_NATIVE]. */
    constructor(message: String) : super(message) {
        this.errorCode = ERR_NATIVE
    }

    companion object {
        /** Matches ERR_PANIC in the Rust crate (-100). */
        const val ERR_NATIVE: Int = -100
    }
}
