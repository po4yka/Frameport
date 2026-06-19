package dev.po4yka.frameport.camera.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.po4yka.frameport.camera.api.CameraRepository
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.TransferProgress
import dev.po4yka.frameport.camera.api.defaultCategory
import dev.po4yka.frameport.camera.api.diagnosticEvent
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.storage.session.SessionProgressStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service managing the active camera session lifecycle.
 *
 * Lifecycle invariants (android-foreground-service-lifecycle.md):
 * - [ServiceCompat.startForeground] with [ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE]
 *   is called within 5 seconds of [onStartCommand] returning (synchronously, before any suspend).
 * - The service stops itself when [CameraSessionController] detects a terminal state
 *   ([CameraSessionState.isTerminal] == true): either [CameraSessionState.Closed] (clean
 *   disconnect) or [CameraSessionState.Failed] (unrecoverable error).
 * - [onDestroy] cancels the service coroutine scope, which propagates to both the state
 *   and progress collection jobs inside [CameraSessionController].
 *
 * FGS type prerequisites (android-foreground-service-types.md):
 * - [android.permission.CHANGE_WIFI_STATE] declared in :camera:wifi (merges app-wide).
 * - [android.permission.BLUETOOTH_CONNECT] / [android.permission.BLUETOOTH_SCAN] declared in
 *   :camera:bluetooth (merges app-wide).
 * At least one must be satisfied at [startForeground] time on API 34+.
 *
 * Notification: IMPORTANCE_LOW channel; category-only title — no camera identifiers.
 */
@AndroidEntryPoint
class CameraSessionService : Service() {
    companion object {
        const val EXTRA_SESSION_ID = "dev.po4yka.frameport.camera.data.EXTRA_SESSION_ID"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "frameport_camera_session"
    }

    @Inject
    lateinit var cameraRepository: CameraRepository

    @Inject
    lateinit var diagnosticsRepository: DiagnosticsRepository

    @Inject
    lateinit var sessionProgressStore: SessionProgressStore

    // Injected IO dispatcher: protocol/service work (state collection, progress persistence,
    // interrupted-session checks) is I/O-bound and must not run on Main. Dispatchers.Main.immediate
    // was incorrect here because it would run coroutines on the main thread whenever the main
    // looper is idle, blocking UI for synchronous database or storage calls.
    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    // Service coroutine scope: SupervisorJob so individual child failures don't cancel the scope.
    // Uses ioDispatcher (injected) so protocol/storage work never runs on the main thread.
    // Cancelled in onDestroy to stop all collections cleanly.
    // Note: serviceScope is created lazily after injection so ioDispatcher is available.
    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + ioDispatcher) }

    private var controller: CameraSessionController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Step 1: create the notification channel (idempotent on repeated calls).
        createNotificationChannel()

        // Step 2: call startForeground BEFORE any suspend — must complete within 5 seconds
        // of onStartCommand returning. ServiceCompat selects the correct overload automatically:
        // on API 34+ it passes the service type flag; on older APIs it falls back to the
        // two-arg form. NEVER use the bare two-arg Service.startForeground(id, notification).
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildSessionNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        // Step 3: wire the session controller (G5/G6).
        // TODO(audit): progressFlow is emptyFlow() because TransferRepository.importObject
        //   returns a per-import Flow<ImportState>, not a persistent Flow<TransferProgress>
        //   available at service-start time. The correct source is a session-scoped
        //   SharedFlow<TransferProgress> that TransferRepositoryImpl exposes and into which
        //   each importObject call emits its progress events. Wire this in M11 when
        //   TransferRepositoryImpl gains a session-scoped progress bus.
        val progressFlow = emptyFlow<TransferProgress>()

        // Session this service instance manages (0 = unknown; the stub session uses SessionId(0L)).
        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, 0L) ?: 0L

        val ctrl =
            CameraSessionController(
                sessionId = sessionId,
                sessionState = cameraRepository.sessionState,
                progressFlow = progressFlow,
                sessionProgressStore = sessionProgressStore,
                onTerminal = { stopSelf() },
            )
        controller = ctrl
        ctrl.start(serviceScope)

        // Step 4: check for interrupted sessions from a prior process cycle (G11).
        serviceScope.launch {
            checkForInterruptedSessions()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Called by Android when a mediaProcessing or connectedDevice foreground service exceeds
     * its allowed runtime on API 35+. connectedDevice has no enforced timeout, but the override
     * is provided for forward-correctness.
     *
     * See android-foreground-service-lifecycle.md and android-foreground-service-types.md.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM) // API 35
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                // Category-only channel name — no camera identifiers.
                "Camera Session",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active camera connection"
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds a minimal ongoing notification.
     * PRIVACY: title and text are category-only strings — no camera serial, SSID, MAC, or IP.
     */
    private fun buildSessionNotification(): Notification =
        Notification
            .Builder(this, CHANNEL_ID)
            .setContentTitle("Camera session active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    /**
     * Queries [SessionProgressStore] for any in-progress row from a prior cycle and surfaces
     * a category-only [DiagnosticEvent] if one is found (G11).
     *
     * PRIVACY: the DiagnosticEvent message is category-only ("Previous transfer interrupted") —
     * no session id, object handle, or byte counts are included.
     */
    // cancel-safe: suspend call propagates cancellation via the serviceScope SupervisorJob.
    private suspend fun checkForInterruptedSessions() {
        try {
            val interrupted = sessionProgressStore.queryInProgress()
            if (interrupted.isNotEmpty()) {
                diagnosticsRepository.recordEvent(
                    diagnosticEvent(
                        layer = ErrorLayer.MediaTransfer,
                        category = defaultCategory(ErrorLayer.MediaTransfer),
                        // Category-only message — no raw session id, object handle, or sizes.
                        message = "Previous transfer interrupted",
                    ),
                )
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // Failure isolation: a storage read error must not affect the service lifecycle.
        }
    }
}
