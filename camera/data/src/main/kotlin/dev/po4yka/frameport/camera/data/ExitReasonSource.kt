package dev.po4yka.frameport.camera.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android adapter that reads [ApplicationExitInfo] records and maps them to [ExitReasonInfo].
 *
 * The entire body is guarded by [Build.VERSION.SDK_INT] >= [Build.VERSION_CODES.R] (API 30).
 * Frameport's minSdk is 31 so this guard is always true at runtime, but the explicit guard
 * is required for forward-compatibility and to satisfy the Android lint API check.
 *
 * PRIVACY: only numeric reason code, description string, timestamp, and pid are extracted.
 * No raw device identifiers (serial, IMEI, BSSID, IP) are read or passed downstream.
 */
@Singleton
class ExitReasonSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val MAX_EXIT_REASONS = 16
        }

        /**
         * Returns the historical process exit reasons for this package, mapped to [ExitReasonInfo].
         * Returns an empty list on API < 30 (unreachable at runtime given minSdk 31, but guarded
         * for correctness and lint compliance).
         */
        fun historicalExitReasons(): List<ExitReasonInfo> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()

            val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
            return try {
                am
                    .getHistoricalProcessExitReasons(context.packageName, /* pid = */ 0, MAX_EXIT_REASONS)
                    .map { info: ApplicationExitInfo ->
                        ExitReasonInfo(
                            reason = info.reason,
                            description = info.description,
                            timestampMillis = info.timestamp,
                            pid = info.pid,
                        )
                    }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Defensive: some OEMs throw on this API; surface nothing rather than crashing.
                emptyList()
            }
        }
    }
