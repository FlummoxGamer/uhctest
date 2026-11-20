package app.harvester.companion.wallpaper

import kotlin.math.abs

/**
 * Unified normalized framing transform shared identically between
 * Preview (WallpaperZoomableViewport) and Home (WallpaperBackground).
 *
 * - [scale]: 1f..4f zoom factor applied over ContentScale.Crop.
 * - [offsetXFraction]: horizontal translation as a fraction of viewport width (0f = centered).
 * - [offsetYFraction]: vertical translation as a fraction of viewport height (0f = centered).
 */
data class WallpaperTransform(
    val scale: Float = 1f,
    val offsetXFraction: Float = 0f,
    val offsetYFraction: Float = 0f,
) {
    val isIdentity: Boolean
        get() = scale <= 1.01f &&
            abs(offsetXFraction) < 0.002f &&
            abs(offsetYFraction) < 0.002f

    fun clamped(): WallpaperTransform {
        val safeScale = scale.coerceIn(1f, MAX_SCALE)
        val maxFrac = ((safeScale - 1f) / 2f).coerceAtLeast(0f)
        return WallpaperTransform(
            scale = safeScale,
            offsetXFraction = offsetXFraction.coerceIn(-maxFrac, maxFrac),
            offsetYFraction = offsetYFraction.coerceIn(-maxFrac, maxFrac),
        )
    }

    companion object {
        const val MAX_SCALE = 4f
        val Identity = WallpaperTransform(1f, 0f, 0f)
    }
}
