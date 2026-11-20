package app.harvester.companion.home

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.harvester.companion.WallpaperStore
import app.harvester.companion.wallpaper.WallpaperDisplayMetrics
import app.harvester.companion.wallpaper.WallpaperPlaybackController
import app.harvester.companion.wallpaper.WallpaperTransform
import app.harvester.companion.wallpaper.WallpaperVideoSurface
import app.harvester.companion.wallpaper.rememberPowerSaveMode
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.size.Precision
import java.io.File

@Composable
fun WallpaperBackground(
    path: String?,
    isVideo: Boolean,
    dimAlpha: Float = 0f,
    portraitTransform: WallpaperTransform = WallpaperTransform.Identity,
    landscapeTransform: WallpaperTransform = WallpaperTransform.Identity,
    isPlaybackActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (path.isNullOrBlank() || path == "default") return

    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        if (isVideo) {
            VideoWallpaper(
                path = path,
                isPlaybackActive = isPlaybackActive,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val transform = if (isLandscape) landscapeTransform else portraitTransform
            ImageWallpaper(
                path = path,
                transform = transform,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val safeDim = dimAlpha.coerceIn(0f, WallpaperStore.MAX_DIM_ALPHA)
        if (safeDim > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = safeDim)),
            )
        }
    }
}

@Composable
private fun ImageWallpaper(
    path: String,
    transform: WallpaperTransform,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val clamped = remember(transform) { transform.clamped() }

    val model = remember(path) {
        if (path.startsWith("content://") || path.startsWith("http://") ||
            path.startsWith("https://") || path.startsWith("file://") || path.startsWith("asset://")
        ) {
            Uri.parse(path)
        } else {
            File(path)
        }
    }

    // Request native screen-max pixels (with zoom headroom up to safe GPU max texture edge)
    val request = remember(context, model, clamped.scale) {
        val (targetW, targetH) = WallpaperDisplayMetrics.getDecodeTargetSize(context, clamped.scale)
        ImageRequest.Builder(context)
            .data(model)
            .size(targetW, targetH)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowHardware(true)
            .crossfade(false)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                if (!clamped.isIdentity) {
                    scaleX = clamped.scale
                    scaleY = clamped.scale
                    translationX = clamped.offsetXFraction * size.width
                    translationY = clamped.offsetYFraction * size.height
                }
            },
    )
}

@Composable
private fun VideoWallpaper(
    path: String,
    isPlaybackActive: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isPowerSaveMode by rememberPowerSaveMode()
    var isLifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var firstFrameRendered by remember(path) { mutableStateOf(false) }

    val shouldPlay = isPlaybackActive && isLifecycleResumed && !isPowerSaveMode

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

    val posterRequest = remember(context, path, posterFile) {
        val (targetW, targetH) = WallpaperDisplayMetrics.getDecodeTargetSize(context, 1f)
        val data: Any = posterFile ?: mediaUri
        ImageRequest.Builder(context)
            .data(data)
            .size(targetW, targetH)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .apply {
                if (posterFile == null) videoFrameMillis(0L)
            }
            .crossfade(false)
            .build()
    }

    val exoPlayer = remember(path) {
        WallpaperPlaybackController.createWallpaperExoPlayer(context).apply {
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
                Lifecycle.Event.ON_RESUME -> isLifecycleResumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isLifecycleResumed = false
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
    }

    LaunchedEffect(exoPlayer, shouldPlay) {
        if (shouldPlay) {
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        } else {
            exoPlayer.playWhenReady = false
            exoPlayer.pause()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Always hold crisp full-res poster underneath so Battery Saver / off-screen pause
        // and first-frame startup never flash black.
        if (!firstFrameRendered || isPowerSaveMode || !shouldPlay) {
            AsyncImage(
                model = posterRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!isPowerSaveMode) {
            WallpaperVideoSurface(
                exoPlayer = exoPlayer,
                contentScale = ContentScale.Crop,
                capTo30Fps = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
