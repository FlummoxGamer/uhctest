package app.harvester.companion.settings.appearance

import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import app.harvester.companion.UHLog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.abs

@Stable
class WallpaperTransformState {
    var scale by mutableFloatStateOf(1f)
    var translationX by mutableFloatStateOf(0f)
    var translationY by mutableFloatStateOf(0f)
    var viewportSize by mutableStateOf(IntSize.Zero)

    fun clampOffsets(newScale: Float, newX: Float, newY: Float) {
        val clampedScale = newScale.coerceIn(1f, 3f)
        scale = clampedScale
        if (clampedScale <= 1.01f || viewportSize.width <= 0 || viewportSize.height <= 0) {
            scale = if (clampedScale <= 1.01f) 1f else clampedScale
            translationX = 0f
            translationY = 0f
            return
        }
        val maxX = viewportSize.width * (clampedScale - 1f) * 0.4f
        val maxY = viewportSize.height * (clampedScale - 1f) * 0.4f
        translationX = newX.coerceIn(-maxX, maxX)
        translationY = newY.coerceIn(-maxY, maxY)
    }

    fun toNormalizedTriple(): Triple<Float, Float, Float> {
        if (scale <= 1.01f || viewportSize.width <= 0 || viewportSize.height <= 0) {
            return Triple(1f, 0.5f, 0.5f)
        }
        val denomX = viewportSize.width * (scale - 1f)
        val denomY = viewportSize.height * (scale - 1f)
        val scrollX = (0.5f - (translationX / denomX)).coerceIn(0.1f, 0.9f)
        val scrollY = (0.5f - (translationY / denomY)).coerceIn(0.1f, 0.9f)
        return Triple(scale.coerceIn(1f, 3f), scrollX, scrollY)
    }
}

@Composable
fun ZoomableWallpaperImage(
    imageModel: Any,
    transformState: WallpaperTransformState,
    onDetectedTextColor: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(imageModel) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .crossfade(true)
            .allowHardware(false)
            .listener(
                onSuccess = { _, result ->
                    val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bmp != null) {
                        val palette = Palette.from(bmp).generate()
                        val dom = palette.getDominantColor(AndroidColor.DKGRAY)
                        val lum = ColorUtils.calculateLuminance(dom)
                        onDetectedTextColor(if (lum > 0.5) AndroidColor.BLACK else AndroidColor.WHITE)
                    }
                },
                onError = { _, result ->
                    UHLog.e("preview", "Coil error: ${result.throwable.message}")
                }
            )
            .build()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { newSize ->
                transformState.viewportSize = newSize
                transformState.clampOffsets(
                    transformState.scale,
                    transformState.translationX,
                    transformState.translationY
                )
            }
            .pointerInput(transformState) {
                detectTapGestures(
                    onDoubleTap = {
                        if (transformState.scale > 1.05f) {
                            transformState.clampOffsets(1f, 0f, 0f)
                        } else {
                            transformState.clampOffsets(2f, 0f, 0f)
                        }
                    }
                )
            }
            .pointerInput(transformState) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val pointerCount = event.changes.count { it.pressed }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val isZooming = pointerCount > 1 || abs(zoomChange - 1f) > 0.005f
                            val isZoomed = transformState.scale > 1.01f

                            if (isZooming || isZoomed) {
                                val nextScale = (transformState.scale * zoomChange).coerceIn(1f, 3f)
                                transformState.clampOffsets(
                                    newScale = nextScale,
                                    newX = transformState.translationX + panChange.x,
                                    newY = transformState.translationY + panChange.y
                                )
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) {
                                        change.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = transformState.scale
                    scaleY = transformState.scale
                    translationX = transformState.translationX
                    translationY = transformState.translationY
                }
        )
    }
}
