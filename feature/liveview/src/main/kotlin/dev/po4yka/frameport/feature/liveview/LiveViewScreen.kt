package dev.po4yka.frameport.feature.liveview

import android.graphics.Bitmap
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.po4yka.frameport.camera.api.LiveViewUiState
import dev.po4yka.frameport.camera.api.SessionId
import dev.po4yka.frameport.core.designsystem.FrameportTheme
import java.util.concurrent.atomic.AtomicReference

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
 *
 * Stale-bitmap fix (H-10): [SurfaceHolder.Callback] methods (`surfaceCreated`,
 * `surfaceChanged`) fire after a rotation or surface re-creation. The `factory` lambda
 * runs only once per [AndroidView] instance, so any bitmap value captured by the factory
 * closure becomes stale on subsequent recompositions. We fix this by allocating a single
 * [AtomicReference] in `remember {}` (stable across recompositions), writing the latest
 * bitmap into it from the `update` lambda (which runs on every recomposition), and reading
 * from it inside the [SurfaceHolder.Callback] handlers. This ensures that a post-rotation
 * `surfaceCreated`/`surfaceChanged` always draws the most recent frame, not the first one.
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
                    if (bitmap != null) {
                        // latestBitmapRef is allocated once per AndroidView lifetime (remember {}).
                        // The update lambda writes the latest bitmap into it on every recomposition,
                        // so SurfaceHolder.Callback handlers always read the current frame even after
                        // a surface re-creation (e.g. rotation). This is the fix for H-10.
                        val latestBitmapRef = remember { AtomicReference<Bitmap?>(null) }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(
                                        object : SurfaceHolder.Callback {
                                            override fun surfaceCreated(h: SurfaceHolder) {
                                                latestBitmapRef.get()?.let { drawBitmap(h, it) }
                                            }

                                            override fun surfaceChanged(
                                                h: SurfaceHolder,
                                                format: Int,
                                                width: Int,
                                                height: Int,
                                            ) {
                                                latestBitmapRef.get()?.let { drawBitmap(h, it) }
                                            }

                                            override fun surfaceDestroyed(h: SurfaceHolder) = Unit
                                        },
                                    )
                                }
                            },
                            update = { surfaceView ->
                                // Write the current bitmap before drawing so that any immediately
                                // following surfaceCreated/surfaceChanged callback sees this value.
                                latestBitmapRef.set(bitmap)
                                drawBitmap(surfaceView.holder, bitmap)
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
