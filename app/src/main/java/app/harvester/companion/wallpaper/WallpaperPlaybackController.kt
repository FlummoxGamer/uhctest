package app.harvester.companion.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/**
 * Playback & power-management utilities for motion wallpapers:
 * - Always muted and configured with `handleAudioFocus = false` so background music/podcasts never pause.
 * - Real-time Battery Saver observer (`PowerManager.isPowerSaveMode`).
 * - Hardware Surface 30fps pacing cap on Android 11+ so skipped high-fps MP4s remain smooth and battery-friendly.
 */
object WallpaperPlaybackController {

    const val TARGET_MAX_FPS = 30f

    fun isPowerSaveMode(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode == true
        } catch (_: Exception) {
            false
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun createWallpaperExoPlayer(
        context: Context,
        scalingMode: Int = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING,
    ): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 1500,
                /* maxBufferMs = */ 8000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 500,
            )
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = scalingMode
            }
    }

    /**
     * Caps Surface presentation pacing to 30fps on Android 11+ (API 30+) without re-encoding
     * the underlying MP4 stream. Keeps high-fps wallpapers smooth and power-efficient.
     */
    fun apply30FpsSurfaceCap(surface: Surface) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    TARGET_MAX_FPS,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(
                    TARGET_MAX_FPS,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                )
            }
        } catch (_: Exception) {
            // Gracefully ignored on devices whose compositor does not support setFrameRate
        }
    }
}

/**
 * Observes Android Battery Saver state in real time so motion wallpapers pause seamlessly
 * into their crisp static poster frame while Power Save mode is active.
 */
@Composable
fun rememberPowerSaveMode(): State<Boolean> {
    val context = LocalContext.current
    val state = remember(context) {
        mutableStateOf(WallpaperPlaybackController.isPowerSaveMode(context))
    }

    DisposableEffect(context) {
        state.value = WallpaperPlaybackController.isPowerSaveMode(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                state.value = WallpaperPlaybackController.isPowerSaveMode(context)
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    return state
}
