package app.harvester.companion.wallpaper

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Flagship Jetpack Compose zoom & pan framing viewport replacing legacy `TouchImageView`:
 * - Centroid-anchored multi-touch pinch zoom (1x..4x) so fingers stay pinned to the exact pixel.
 * - Pager-friendly gesture arbitration: single-finger swipes at 1.0x pass directly to HorizontalPager.
 * - Double-tap spring toggle (1.0x <-> 2.2x) anchored at the tap location.
 * - Velocity-based fling inertia with strict boundary clamping (zero black gaps).
 * - Rule-of-thirds alignment grid fading in smoothly while actively framing.
 */
@Composable
fun WallpaperZoomableViewport(
    transform: WallpaperTransform,
    onTransformChange: (WallpaperTransform) -> Unit,
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = WallpaperTransform.MAX_SCALE,
    doubleTapScale: Float = 2.2f,
    showAlignmentGridWhileGesturing: Boolean = true,
    onInteractionChanged: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestOnTransformChange by rememberUpdatedState(onTransformChange)
    val latestOnInteractionChanged by rememberUpdatedState(onInteractionChanged)

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var isInteracting by remember { mutableStateOf(false) }

    val scaleAnim = remember { Animatable(transform.scale.coerceIn(minScale, maxScale)) }
    val offsetXAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }
    var activeAnimationJob by remember { mutableStateOf<Job?>(null) }

    // Synchronize external resets or restored orientation transforms smoothly
    LaunchedEffect(transform, viewportSize) {
        if (isInteracting || viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        val targetScale = transform.scale.coerceIn(minScale, maxScale)
        val maxOffX = ((targetScale - 1f) * viewportSize.width / 2f).coerceAtLeast(0f)
        val maxOffY = ((targetScale - 1f) * viewportSize.height / 2f).coerceAtLeast(0f)
        val targetX = (transform.offsetXFraction * viewportSize.width).coerceIn(-maxOffX, maxOffX)
        val targetY = (transform.offsetYFraction * viewportSize.height).coerceIn(-maxOffY, maxOffY)

        val scaleDiff = abs(scaleAnim.value - targetScale)
        val xDiff = abs(offsetXAnim.value - targetX)
        val yDiff = abs(offsetYAnim.value - targetY)
        if (scaleDiff > 0.005f || xDiff > 1f || yDiff > 1f) {
            scaleAnim.snapTo(targetScale)
            offsetXAnim.snapTo(targetX)
            offsetYAnim.snapTo(targetY)
        }
    }

    fun emitNormalized(scale: Float, offX: Float, offY: Float) {
        val w = viewportSize.width.toFloat()
        val h = viewportSize.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val next = WallpaperTransform(
            scale = scale,
            offsetXFraction = if (scale <= 1.005f) 0f else offX / w,
            offsetYFraction = if (scale <= 1.005f) 0f else offY / h,
        ).clamped()
        latestOnTransformChange(next)
    }

    val gridAlpha by animateFloatAsState(
        targetValue = if (isInteracting && showAlignmentGridWhileGesturing) 0.55f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "framing_grid_alpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { newSize ->
                val prev = viewportSize
                viewportSize = newSize
                if (prev != IntSize.Zero && newSize.width > 0 && newSize.height > 0 && prev != newSize) {
                    scope.launch {
                        val s = transform.scale.coerceIn(minScale, maxScale)
                        val maxX = ((s - 1f) * newSize.width / 2f).coerceAtLeast(0f)
                        val maxY = ((s - 1f) * newSize.height / 2f).coerceAtLeast(0f)
                        scaleAnim.snapTo(s)
                        offsetXAnim.snapTo((transform.offsetXFraction * newSize.width).coerceIn(-maxX, maxX))
                        offsetYAnim.snapTo((transform.offsetYFraction * newSize.height).coerceIn(-maxY, maxY))
                    }
                }
            }
            // Double-tap spring toggle anchored at tap point
            .pointerInput(minScale, maxScale, doubleTapScale) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        val w = viewportSize.width.toFloat()
                        val h = viewportSize.height.toFloat()
                        if (w <= 0f || h <= 0f) return@detectTapGestures

                        activeAnimationJob?.cancel()
                        val currentlyZoomed = scaleAnim.value > minScale + 0.08f
                        val targetScale = if (currentlyZoomed) minScale else doubleTapScale.coerceIn(minScale, maxScale)
                        val targetOffset = if (currentlyZoomed) {
                            Offset.Zero
                        } else {
                            val center = Offset(w / 2f, h / 2f)
                            val deltaFromCenter = tapOffset - center
                            val desiredX = -deltaFromCenter.x * (targetScale - 1f)
                            val desiredY = -deltaFromCenter.y * (targetScale - 1f)
                            val maxX = ((targetScale - 1f) * w / 2f).coerceAtLeast(0f)
                            val maxY = ((targetScale - 1f) * h / 2f).coerceAtLeast(0f)
                            Offset(desiredX.coerceIn(-maxX, maxX), desiredY.coerceIn(-maxY, maxY))
                        }

                        val animSpec = spring<Float>(
                            dampingRatio = 0.85f,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                        activeAnimationJob = scope.launch {
                            val j1 = launch { scaleAnim.animateTo(targetScale, animSpec) }
                            val j2 = launch { offsetXAnim.animateTo(targetOffset.x, animSpec) }
                            val j3 = launch { offsetYAnim.animateTo(targetOffset.y, animSpec) }
                            j1.join()
                            j2.join()
                            j3.join()
                            emitNormalized(scaleAnim.value, offsetXAnim.value, offsetYAnim.value)
                        }
                    }
                )
            }
            // Centroid-anchored pinch + pager-friendly pan + velocity fling
            .pointerInput(minScale, maxScale) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    activeAnimationJob?.cancel()
                    val velocityTracker = VelocityTracker()
                    var gestureCaptured = false
                    var wasMultiTouch = false

                    do {
                        val event = awaitPointerEvent()
                        val pressedChanges = event.changes.filter { it.pressed }
                        val pointerCount = pressedChanges.size
                        if (pointerCount >= 2) wasMultiTouch = true

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = true)

                        val isZoomed = scaleAnim.value > minScale + 0.01f
                        val shouldHandle = pointerCount >= 2 || isZoomed

                        if (shouldHandle && (zoomChange != 1f || panChange != Offset.Zero)) {
                            val w = viewportSize.width.toFloat()
                            val h = viewportSize.height.toFloat()
                            if (w > 0f && h > 0f) {
                                val oldScale = scaleAnim.value
                                val newScale = (oldScale * zoomChange).coerceIn(minScale, maxScale)
                                val scaleRatio = if (oldScale > 0f) newScale / oldScale else 1f

                                // Anchor zoom directly around active pinch centroid
                                val centerX = w / 2f
                                val centerY = h / 2f
                                val centroidFromCenterX = if (centroid != Offset.Unspecified) centroid.x - centerX else 0f
                                val centroidFromCenterY = if (centroid != Offset.Unspecified) centroid.y - centerY else 0f

                                val rawX = (offsetXAnim.value - centroidFromCenterX) * scaleRatio + centroidFromCenterX + panChange.x
                                val rawY = (offsetYAnim.value - centroidFromCenterY) * scaleRatio + centroidFromCenterY + panChange.y

                                val maxX = ((newScale - 1f) * w / 2f).coerceAtLeast(0f)
                                val maxY = ((newScale - 1f) * h / 2f).coerceAtLeast(0f)

                                // Let HorizontalPager take over if user swipes horizontally at a hard edge with 1 finger
                                val atHorizontalEdge = pointerCount == 1 && !wasMultiTouch &&
                                    abs(panChange.x) > abs(panChange.y) * 1.35f &&
                                    ((offsetXAnim.value >= maxX - 1f && panChange.x > 0f) ||
                                        (offsetXAnim.value <= -maxX + 1f && panChange.x < 0f))

                                if (!atHorizontalEdge) {
                                    if (!gestureCaptured) {
                                        gestureCaptured = true
                                        isInteracting = true
                                        latestOnInteractionChanged?.invoke(true)
                                    }

                                    val clampedX = rawX.coerceIn(-maxX, maxX)
                                    val clampedY = rawY.coerceIn(-maxY, maxY)

                                    scope.launch {
                                        scaleAnim.snapTo(newScale)
                                        offsetXAnim.snapTo(clampedX)
                                        offsetYAnim.snapTo(clampedY)
                                    }
                                    emitNormalized(newScale, clampedX, clampedY)

                                    if (pointerCount == 1) {
                                        val primary = pressedChanges.firstOrNull { it.id == firstDown.id } ?: pressedChanges.first()
                                        velocityTracker.addPosition(primary.uptimeMillis, primary.position)
                                    }

                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (gestureCaptured) {
                        val w = viewportSize.width.toFloat()
                        val h = viewportSize.height.toFloat()
                        val curScale = scaleAnim.value
                        val maxX = ((curScale - 1f) * w / 2f).coerceAtLeast(0f)
                        val maxY = ((curScale - 1f) * h / 2f).coerceAtLeast(0f)
                        val velocity = if (!wasMultiTouch && curScale > minScale + 0.02f) {
                            velocityTracker.calculateVelocity()
                        } else {
                            androidx.compose.ui.unit.Velocity.Zero
                        }

                        activeAnimationJob = scope.launch {
                            try {
                                if (abs(velocity.x) > 120f || abs(velocity.y) > 120f) {
                                    val decay = exponentialDecay<Float>(frictionMultiplier = 2.4f)
                                    offsetXAnim.updateBounds(-maxX, maxX)
                                    offsetYAnim.updateBounds(-maxY, maxY)
                                    val fx = launch {
                                        offsetXAnim.animateDecay(velocity.x, decay) {
                                            emitNormalized(scaleAnim.value, value, offsetYAnim.value)
                                        }
                                    }
                                    val fy = launch {
                                        offsetYAnim.animateDecay(velocity.y, decay) {
                                            emitNormalized(scaleAnim.value, offsetXAnim.value, value)
                                        }
                                    }
                                    fx.join()
                                    fy.join()
                                    offsetXAnim.updateBounds(null, null)
                                    offsetYAnim.updateBounds(null, null)
                                }
                                // Clean snap if scale settled near 1x
                                if (scaleAnim.value <= minScale + 0.02f) {
                                    val resetSpec = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)
                                    val j1 = launch { scaleAnim.animateTo(minScale, resetSpec) }
                                    val j2 = launch { offsetXAnim.animateTo(0f, resetSpec) }
                                    val j3 = launch { offsetYAnim.animateTo(0f, resetSpec) }
                                    j1.join(); j2.join(); j3.join()
                                }
                                emitNormalized(scaleAnim.value, offsetXAnim.value, offsetYAnim.value)
                            } finally {
                                isInteracting = false
                                latestOnInteractionChanged?.invoke(false)
                            }
                        }
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value
                },
        ) {
            content()
        }

        // Translucent rule-of-thirds alignment grid shown only while framing
        if (gridAlpha > 0.01f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = 1.dp.toPx()
                val lineColor = Color.White.copy(alpha = gridAlpha)
                val thirdW = size.width / 3f
                val thirdH = size.height / 3f

                drawLine(lineColor, Offset(thirdW, 0f), Offset(thirdW, size.height), strokeWidth = strokePx)
                drawLine(lineColor, Offset(thirdW * 2f, 0f), Offset(thirdW * 2f, size.height), strokeWidth = strokePx)
                drawLine(lineColor, Offset(0f, thirdH), Offset(size.width, thirdH), strokeWidth = strokePx)
                drawLine(lineColor, Offset(0f, thirdH * 2f), Offset(size.width, thirdH * 2f), strokeWidth = strokePx)
            }
        }
    }
}
