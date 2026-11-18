package app.harvester.companion.home

import android.content.res.Configuration
import android.net.Uri
import android.view.LayoutInflater
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.harvester.companion.R
import app.harvester.companion.WallpaperStore
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import java.io.File

@Composable
fun WallpaperBackground(
    path: String?,
    isVideo: Boolean,
    modifier: Modifier = Modifier
) {
    if (path == null || path == "default") return

    if (isVideo) {
        VideoWallpaper(path, modifier)
    } else {
        ImageWallpaper(path, modifier)
    }
}

@Composable
private fun ImageWallpaper(path: String, modifier: Modifier) {
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val transform = remember(path, isLandscape) {
        WallpaperStore.getWallpaperTransform(context, isLandscape)
    }
    val zoom = transform.first.coerceIn(1f, 3f)
    val panX = (transform.second - 0.5f).coerceIn(-0.4f, 0.4f)
    val panY = (transform.third - 0.5f).coerceIn(-0.4f, 0.4f)

    val model = remember(path) {
        if (path.startsWith("content://") || path.startsWith("http://") ||
            path.startsWith("https://") || path.startsWith("file://") || path.startsWith("asset://")
        ) {
            Uri.parse(path)
        } else {
            File(path)
        }
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                if (zoom > 1.01f) {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = -panX * size.width * (zoom - 1f)
                    translationY = -panY * size.height * (zoom - 1f)
                }
            }
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoWallpaper(path: String, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var firstFrameRendered by remember(path) { mutableStateOf(false) }

    val posterFile = remember(path) {
        WallpaperStore.posterFileFor(path).takeIf { it.exists() && it.length() > 0L }
    }

    val mediaUri = remember(path) {
        if (path.startsWith("content://") || path.startsWith("http://") ||
            path.startsWith("https://") || path.startsWith("file://") || path.startsWith("asset://")
        ) {
            Uri.parse(path)
        } else {
            Uri.fromFile(File(path))
        }
    }

    val exoPlayer = remember(path) {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 8000, 250, 500)
            .build()
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrameRendered = true
                    }
                })
            }
    }

    DisposableEffect(exoPlayer, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    exoPlayer.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    LaunchedEffect(mediaUri, exoPlayer) {
        firstFrameRendered = false
        exoPlayer.setMediaItem(MediaItem.fromUri(mediaUri))
        exoPlayer.prepare()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            exoPlayer.play()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Show instant cached poster or first frame underneath until TextureView paints frame 1
        if (!firstFrameRendered) {
            val thumbModel: Any = posterFile ?: remember(path) {
                ImageRequest.Builder(context)
                    .data(mediaUri)
                    .videoFrameMillis(0L)
                    .build()
            }
            AsyncImage(
                model = thumbModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.uh_wallpaper_player_view, null, false) as PlayerView).apply {
                    player = exoPlayer
                }
            },
            update = { playerView ->
                if (playerView.player !== exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
