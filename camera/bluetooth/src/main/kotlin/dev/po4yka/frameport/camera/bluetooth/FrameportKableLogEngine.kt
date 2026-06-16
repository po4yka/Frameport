package dev.po4yka.frameport.camera.bluetooth

import com.juul.kable.logs.LogEngine
import timber.log.Timber

/**
 * Routes Kable warnings through Frameport's existing Timber pipeline.
 *
 * Kable logging is configured with a non-identifying prefix and Warnings level only, so this
 * bridge must never receive raw BLE payload bytes or device MAC addresses.
 */
internal object FrameportKableLogEngine : LogEngine {
    override fun verbose(
        throwable: Throwable?,
        tag: String,
        message: String,
    ) {
        Timber.tag(tag).v(throwable, message)
    }

    override fun debug(
        throwable: Throwable?,
        tag: String,
        message: String,
    ) {
        Timber.tag(tag).d(throwable, message)
    }

    override fun info(
        throwable: Throwable?,
        tag: String,
        message: String,
    ) {
        Timber.tag(tag).i(throwable, message)
    }

    override fun warn(
        throwable: Throwable?,
        tag: String,
        message: String,
    ) {
        Timber.tag(tag).w(throwable, message)
    }

    override fun error(
        throwable: Throwable?,
        tag: String,
        message: String,
    ) {
        Timber.tag(tag).e(throwable, message)
    }

    override fun assert(
        throwable: Throwable?,
        tag: String,
        message: String,
    ) {
        Timber.tag(tag).wtf(throwable, message)
    }
}
