package app.harvester.companion.wallpaper

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

/**
 * Pure Compose programmatic TextureView video surface replacing `uh_wallpaper_player_view.xml`
 * and legacy `PlayerView` inflation.
 *
 * Supports center-crop ([ContentScale.Crop]) and letterbox fit ([ContentScale.Fit]),
 * handles pixel aspect ratio & unapplied rotation metadata, prevents black-flash shutters,
 * and applies hardware 30fps surface pacing when [capTo30Fps] is enabled.
 */
@Composable
fun WallpaperVideoSurface(
    exoPlayer: ExoPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    capTo30Fps: Boolean = true,
) {
    var videoWidth by remember(exoPlayer) { mutableIntStateOf(exoPlayer.videoSize.width) }
    var videoHeight by remember(exoPlayer) { mutableIntStateOf(exoPlayer.videoSize.height) }
    var pixelRatio by remember(exoPlayer) { mutableFloatStateOf(exoPlayer.videoSize.pixelWidthHeightRatio) }
    var rotationDegrees by remember(exoPlayer) { mutableIntStateOf(exoPlayer.videoSize.unappliedRotationDegrees) }

    val surfaceHolder = remember { mutableStateOf<Surface?>(null) }
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
                pixelRatio = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                rotationDegrees = videoSize.unappliedRotationDegrees
                textureViewRef.value?.let { tv ->
                    applyVideoTransform(
                        textureView = tv,
                        videoWidth = videoWidth,
                        videoHeight = videoHeight,
                        pixelRatio = pixelRatio,
                        rotationDegrees = rotationDegrees,
                        contentScale = contentScale,
                    )
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            surfaceHolder.value?.let { surf ->
                exoPlayer.clearVideoSurface(surf)
                surf.release()
                surfaceHolder.value = null
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                textureViewRef.value = this
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        surfaceHolder.value?.let { old ->
                            exoPlayer.clearVideoSurface(old)
                            old.release()
                        }
                        val surface = Surface(st)
                        if (capTo30Fps) {
                            WallpaperPlaybackController.apply30FpsSurfaceCap(surface)
                        }
                        surfaceHolder.value = surface
                        exoPlayer.setVideoSurface(surface)
                        applyVideoTransform(
                            textureView = this@apply,
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            pixelRatio = pixelRatio,
                            rotationDegrees = rotationDegrees,
                            contentScale = contentScale,
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        applyVideoTransform(
                            textureView = this@apply,
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            pixelRatio = pixelRatio,
                            rotationDegrees = rotationDegrees,
                            contentScale = contentScale,
                        )
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        surfaceHolder.value?.let { surf ->
                            exoPlayer.clearVideoSurface(surf)
                            surf.release()
                            surfaceHolder.value = null
                        }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        update = { tv ->
            textureViewRef.value = tv
            val currentSurface = surfaceHolder.value
            if (currentSurface != null && currentSurface.isValid) {
                exoPlayer.setVideoSurface(currentSurface)
            }
            applyVideoTransform(
                textureView = tv,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pixelRatio = pixelRatio,
                rotationDegrees = rotationDegrees,
                contentScale = contentScale,
            )
        },
        modifier = modifier,
    )
}

private fun applyVideoTransform(
    textureView: TextureView,
    videoWidth: Int,
    videoHeight: Int,
    pixelRatio: Float,
    rotationDegrees: Int,
    contentScale: ContentScale,
) {
    val viewW = textureView.width.toFloat()
    val viewH = textureView.height.toFloat()
    if (viewW <= 0f || viewH <= 0f || videoWidth <= 0 || videoHeight <= 0) return

    val rotated = rotationDegrees == 90 || rotationDegrees == 270
    val effectiveVideoW = if (rotated) videoHeight.toFloat() else videoWidth * pixelRatio
    val effectiveVideoH = if (rotated) videoWidth * pixelRatio else videoHeight.toFloat()
    if (effectiveVideoW <= 0f || effectiveVideoH <= 0f) return

    val videoAspect = effectiveVideoW / effectiveVideoH
    val viewAspect = viewW / viewH

    var scaleX = 1f
    var scaleY = 1f
    if (contentScale == ContentScale.Fit) {
        if (videoAspect > viewAspect) {
            scaleY = viewAspect / videoAspect
        } else {
            scaleX = videoAspect / viewAspect
        }
    } else {
        // ContentScale.Crop (center-crop fill without distortion)
        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect
        } else {
            scaleY = viewAspect / videoAspect
        }
    }

    val pivotX = viewW / 2f
    val pivotY = viewH / 2f
    val matrix = Matrix()
    if (rotationDegrees != 0) {
        matrix.postRotate(rotationDegrees.toFloat(), pivotX, pivotY)
    }
    matrix.postScale(scaleX, scaleY, pivotX, pivotY)
    textureView.setTransform(matrix)
}
