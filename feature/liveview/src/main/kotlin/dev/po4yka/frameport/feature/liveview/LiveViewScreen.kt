package dev.po4yka.frameport.feature.liveview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.po4yka.frameport.camera.api.DiagnosticCategory
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.api.ErrorLayer
import dev.po4yka.frameport.camera.api.LiveViewUiState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.camera.api.diagnosticEvent
import dev.po4yka.frameport.camera.domain.LiveViewUseCase
import dev.po4yka.frameport.core.common.di.IoDispatcher
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the live-view screen.
 *
 * Boundary contract:
 * - NEVER calls JNI directly, accesses BluetoothGatt, Wi-Fi APIs, or sockets.
 * - All camera I/O is delegated to [LiveViewUseCase].
 * - Decoding is done on [IoDispatcher]; UI state is pushed to [StateFlow] for Compose.
 * - [bitmapState] is latest-wins: only the most recent decoded frame is kept.
 *   No unbounded queue; the callbackFlow inside the SDK already drops old frames.
 *
 * fps throttle: displayed fps is updated once per second to avoid recomposition noise.
 * dropCount: mirrors FrameStats.drop_count from the Rust parser; privacy-safe counter.
 *
 * Diagnostics: one [DiagnosticEvent] per second during streaming + one on stop, using
 * [DiagnosticCategory.TransportEvent] (ErrorLayer.Transport) with fps/dropCount in metadata.
 * Privacy: no IP, fd, session raw id, or filename in any log or diagnostic message.
 *
 * See: docs/protocol/transfer-liveview.md §6g, docs/adr/0002-wifi-socket-fd-handoff.md,
 *      docs/android/wifi-network-routing.md, privacy-local-first.md
 */
@HiltViewModel
class LiveviewViewModel
    @Inject
    constructor(
        private val liveViewUseCase: LiveViewUseCase,
        private val diagnosticsRepository: DiagnosticsRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        /**
         * Seam for decoding JPEG bytes into a [Bitmap]. Defaults to [BitmapFactory.decodeByteArray].
         *
         * Override in unit tests (where the Android bitmap codec is absent) to inject a stub decoder
         * that returns a non-null [Bitmap], enabling assertions on [bitmapState] without Robolectric.
         * Set this before calling [start]; it is read inside the stream-collection coroutine.
         *
         * Internal visibility restricts the seam to this Gradle module (`:feature:liveview`).
         * Production code must not override this; the default is the only production-valid value.
         */
        internal var frameDecoder: (ByteArray) -> Bitmap? = { jpeg ->
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        }
        private val _uiState = MutableStateFlow<LiveViewUiState>(LiveViewUiState.Idle)
        val uiState: StateFlow<LiveViewUiState> = _uiState.asStateFlow()

        private val _bitmapState = MutableStateFlow<Bitmap?>(null)

        /** Latest decoded JPEG frame as a [Bitmap]. Latest-frame-wins; may be null when not streaming. */
        val bitmapState: StateFlow<Bitmap?> = _bitmapState.asStateFlow()

        // Mutable frame counters — only written on the IO dispatcher; read on the fps-tick coroutine.
        @Volatile private var frameCount: Long = 0L

        @Volatile private var dropCount: Long = 0L

        private var streamJob: Job? = null
        private var fpsTickJob: Job? = null

        /**
         * Start the live-view stream for [sessionId] using the pre-dup'd [liveViewFd].
         *
         * fd ownership: [liveViewFd] must be an Android-owned, dup'd raw socket fd already
         * bound to the camera network and connected to port 55742. Ownership transfers to
         * the Rust layer when collection begins. Do NOT use the fd after calling this method.
         *
         * Idempotent: calling start while already streaming is a no-op.
         *
         * @param sessionId Active PTP-IP session.
         * @param liveViewFd Owned, dup'd fd for the live-view socket (port 55742).
         */
        fun start(
            sessionId: SessionId,
            liveViewFd: Int,
        ) {
            if (streamJob?.isActive == true) return

            _uiState.value = LiveViewUiState.Connecting
            frameCount = 0L
            dropCount = 0L

            streamJob =
                viewModelScope.launch(ioDispatcher) {
                    var firstFrame = true
                    try {
                        liveViewUseCase(sessionId = sessionId, liveViewFd = liveViewFd)
                            .collect { jpeg ->
                                // Frame arrival drives the state machine, regardless of decode result.
                                // A null decode means the bitmap display does not update but the frame
                                // is still counted and the Streaming state is still entered/maintained.
                                // This keeps the state machine testable on the JVM (no Android codec needed).
                                frameCount++

                                if (firstFrame) {
                                    firstFrame = false
                                    // Transition to Streaming with initial fps=0; fps-tick updates it each second.
                                    _uiState.value = LiveViewUiState.Streaming(fps = 0f, dropCount = dropCount)
                                    startFpsTick(sessionId)
                                }

                                // Decode on IO dispatcher — BitmapFactory.decodeByteArray allocates once per frame;
                                // this is the intentional JVM-boundary allocation excluded from the Rust zero-alloc rule.
                                // In unit tests, frameDecoder is overridden with a stub that avoids the Android codec.
                                // A null result (malformed frame or JVM codec absent) is silent; the Rust parser
                                // should have already filtered invalid frames before they arrive here.
                                val bitmap = frameDecoder(jpeg)
                                if (bitmap != null) {
                                    // Swap in the new frame and recycle the previous one.
                                    // The previous bitmap is safe to recycle here: StateFlow
                                    // observers (Compose via collectAsStateWithLifecycle) will
                                    // not read the old value again once the new value is set,
                                    // and the SurfaceView draw path in AndroidView.update fires
                                    // only during recomposition with the NEW bitmapRef (keyed
                                    // via remember(currentBitmap)). Recycling before the
                                    // assignment would risk a use-after-free; recycling after
                                    // is safe because Compose has already snapshotted the
                                    // previous reference from the last recomposition.
                                    val previous = _bitmapState.value
                                    _bitmapState.value = bitmap
                                    previous?.recycle()
                                }
                            }
                        // Flow completed normally (Rust read loop stopped cleanly).
                        _uiState.value = LiveViewUiState.Stopped
                        stopFpsTick()
                    } catch (e: Exception) {
                        // Re-throw CancellationException so structured concurrency works correctly.
                        // A user-initiated stop() cancels streamJob; CancellationException must
                        // propagate so the parent scope's cancellation protocol is not violated.
                        // stop() already sets _uiState = Stopped before cancelling.
                        if (e is kotlinx.coroutines.CancellationException) {
                            stopFpsTick()
                            throw e
                        }
                        stopFpsTick()
                        val message = "Live-view stream error: ${e.javaClass.simpleName}"
                        _uiState.value = LiveViewUiState.Error(message)
                    } finally {
                        // Always record a stop diagnostic — on normal completion, error, or cancellation.
                        // cancel-safe: withContext(ioDispatcher) inside recordStopDiagnostic is a single
                        // suspend call; SharedFlow.tryEmit in FakeDiagnosticsRepository never blocks.
                        // In production, recordStopDiagnostic is called from the finally block even if
                        // the coroutine is cancelled (the finally runs before the CancellationException
                        // propagates). This is the standard pattern for cleanup in coroutines.
                        recordStopDiagnostic(sessionId)
                    }
                }
        }

        /**
         * Stop the live-view stream. Cancels the collection coroutine; the Rust read loop
         * drains and exits via awaitClose inside the SDK callbackFlow.
         *
         * Idempotent: calling stop when not streaming transitions to [LiveViewUiState.Stopped].
         */
        fun stop() {
            streamJob?.cancel()
            streamJob = null
            stopFpsTick()
            _uiState.value = LiveViewUiState.Stopped
        }

        // Starts a 1 Hz fps-update tick coroutine.
        // cancel-safe: delay is cancellable; coroutine exits cleanly on cancel.
        private fun startFpsTick(sessionId: SessionId) {
            fpsTickJob?.cancel()
            fpsTickJob =
                viewModelScope.launch(ioDispatcher) {
                    var lastFrameCount = 0L
                    while (isActive) {
                        delay(FPS_TICK_INTERVAL_MS)
                        val current = frameCount
                        val fps = (current - lastFrameCount).toFloat()
                        lastFrameCount = current
                        val drops = dropCount
                        _uiState.value = LiveViewUiState.Streaming(fps = fps, dropCount = drops)
                        recordStreamingDiagnostic(sessionId, fps, drops)
                    }
                }
        }

        private fun stopFpsTick() {
            fpsTickJob?.cancel()
            fpsTickJob = null
        }

        // cancel-safe: single suspend recordEvent call; SharedFlow emit never blocks.
        private suspend fun recordStreamingDiagnostic(
            sessionId: SessionId,
            fps: Float,
            drops: Long,
        ) {
            // Privacy: session id as opaque handle, not raw device id. No IP/filename/MAC in metadata.
            diagnosticsRepository.recordEvent(
                diagnosticEvent(
                    layer = ErrorLayer.Transport,
                    category = DiagnosticCategory.TransportEvent,
                    message = "live-view streaming",
                    metadata =
                        mapOf(
                            "fps" to fps.toString(),
                            "drop_count" to drops.toString(),
                        ),
                    sessionId = sessionId.value.toString(),
                ),
            )
        }

        // cancel-safe: uses NonCancellable so it can execute inside a finally block even when
        // the surrounding coroutine has been cancelled. This is the standard pattern for
        // guaranteed cleanup work that must run on all exit paths including cancellation.
        // See: https://kotlinlang.org/docs/cancellation-and-timeouts.html#run-non-cancellable-block
        private suspend fun recordStopDiagnostic(sessionId: SessionId) {
            withContext(kotlinx.coroutines.NonCancellable) {
                diagnosticsRepository.recordEvent(
                    diagnosticEvent(
                        layer = ErrorLayer.Transport,
                        category = DiagnosticCategory.TransportEvent,
                        message = "live-view stopped",
                        metadata =
                            mapOf(
                                "total_frames" to frameCount.toString(),
                                "total_drops" to dropCount.toString(),
                            ),
                        sessionId = sessionId.value.toString(),
                    ),
                )
            }
        }

        override fun onCleared() {
            super.onCleared()
            streamJob?.cancel()
            fpsTickJob?.cancel()
            // Recycle the last decoded frame bitmap when the ViewModel is destroyed so the
            // native heap backing the Bitmap is released promptly (not deferred to GC).
            _bitmapState.value?.recycle()
            _bitmapState.value = null
        }

        private companion object {
            /** Fps display update interval — once per second to avoid recomposition noise. */
            const val FPS_TICK_INTERVAL_MS = 1_000L
        }
    }

// ─── Route ───────────────────────────────────────────────────────────────────

/**
 * Route entry point for the live-view screen. Wires the [LiveviewViewModel] to the
 * Compose screen. The ViewModel is scoped to the nav back-stack entry via [hiltViewModel].
 *
 * @param sessionId Active PTP-IP session id. Passed from the navigation layer; must be a
 *   stable [Long] extracted from the nav route — do NOT pass large objects through nav.
 * @param liveViewFd Android-owned, dup'd fd for the live-view socket (port 55742). The
 *   caller (navigation host / feature coordinator) is responsible for opening and dup'ing
 *   this fd before navigating here. Ownership transfers to Rust on [LiveviewViewModel.start].
 */
@Composable
fun LiveViewRoute(
    sessionId: Long,
    liveViewFd: Int,
    viewModel: LiveviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bitmap by viewModel.bitmapState.collectAsStateWithLifecycle()

    // Trigger start automatically when composable enters composition. This mirrors how
    // the connection screen drives connection — the VM receives inputs from the nav layer,
    // not from user gesture. Stop is user-driven (button) or lifecycle-driven (onDispose).
    DisposableEffect(sessionId, liveViewFd) {
        viewModel.start(SessionId(sessionId), liveViewFd)
        onDispose { viewModel.stop() }
    }

    LiveViewScreen(
        uiState = uiState,
        bitmap = bitmap,
        onStop = { viewModel.stop() },
        onStart = { viewModel.start(SessionId(sessionId), liveViewFd) },
    )
}

// ─── Screen ──────────────────────────────────────────────────────────────────

/**
 * Pure UI composable for the live-view screen.
 *
 * Composables MUST NOT:
 * - Open sockets, access BluetoothGatt, call JNI, write files, or query Room directly.
 * - Hold long-lived protocol state.
 *
 * The [bitmap] is rendered via a [SurfaceView]-backed [AndroidView] for minimal overdraw.
 * The SurfaceHolder callback draws the bitmap onto the surface canvas.
 */
@Composable
fun LiveViewScreen(
    uiState: LiveViewUiState,
    bitmap: Bitmap?,
    onStop: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // ── Frame surface ──────────────────────────────────────────────────
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (uiState) {
                is LiveViewUiState.Idle, is LiveViewUiState.Stopped -> {
                    Text(
                        text = "Live view stopped",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                is LiveViewUiState.Connecting -> {
                    Text(
                        text = "Connecting…",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                is LiveViewUiState.Streaming -> {
                    // Render bitmap via SurfaceView. The SurfaceHolder callback draws on the
                    // hardware-accelerated canvas — no intermediate Compose ImageBitmap allocation.
                    val currentBitmap = bitmap
                    if (currentBitmap != null) {
                        val bitmapRef = remember(currentBitmap) { currentBitmap }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(
                                        object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(h: SurfaceHolder) {
                                                drawBitmap(h, bitmapRef)
                                            }

                                            override fun surfaceChanged(
                                                h: SurfaceHolder,
                                                format: Int,
                                                width: Int,
                                                height: Int,
                                            ) {
                                                drawBitmap(h, bitmapRef)
                                            }

                                            override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                                        },
                                    )
                                }
                            },
                            update = { surfaceView ->
                                drawBitmap(surfaceView.holder, bitmapRef)
                            },
                        )
                    } else {
                        Text(
                            text = "Waiting for first frame…",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }

                is LiveViewUiState.Error -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = uiState.message,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // ── Status bar ────────────────────────────────────────────────────
        if (uiState is LiveViewUiState.Streaming) {
            Text(
                text = "${uiState.fps.toInt()} fps  |  drops: ${uiState.dropCount}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── Controls ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStart,
                enabled =
                    uiState is LiveViewUiState.Idle || uiState is LiveViewUiState.Stopped ||
                        uiState is LiveViewUiState.Error,
                modifier = Modifier.weight(1f),
            ) {
                Text("Start")
            }

            OutlinedButton(
                onClick = onStop,
                enabled = uiState is LiveViewUiState.Streaming || uiState is LiveViewUiState.Connecting,
                modifier = Modifier.weight(1f),
            ) {
                Text("Stop", color = Color.White)
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun drawBitmap(
    holder: SurfaceHolder,
    bitmap: Bitmap,
) {
    val canvas = holder.lockCanvas() ?: return
    try {
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    } finally {
        holder.unlockCanvasAndPost(canvas)
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LiveViewScreenIdlePreview() {
    FrameportTheme {
        LiveViewScreen(
            uiState = LiveViewUiState.Idle,
            bitmap = null,
            onStop = {},
            onStart = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LiveViewScreenConnectingPreview() {
    FrameportTheme {
        LiveViewScreen(
            uiState = LiveViewUiState.Connecting,
            bitmap = null,
            onStop = {},
            onStart = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LiveViewScreenStreamingPreview() {
    FrameportTheme {
        LiveViewScreen(
            uiState = LiveViewUiState.Streaming(fps = 24f, dropCount = 0L),
            bitmap = null,
            onStop = {},
            onStart = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LiveViewScreenErrorPreview() {
    FrameportTheme {
        LiveViewScreen(
            uiState = LiveViewUiState.Error("Session disconnected"),
            bitmap = null,
            onStop = {},
            onStart = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun LiveViewScreenStoppedPreview() {
    FrameportTheme {
        LiveViewScreen(
            uiState = LiveViewUiState.Stopped,
            bitmap = null,
            onStop = {},
            onStart = {},
        )
    }
}
