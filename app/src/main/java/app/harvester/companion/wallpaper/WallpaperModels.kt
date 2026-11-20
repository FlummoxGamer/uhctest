package app.harvester.companion.wallpaper

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

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
            kotlin.math.abs(offsetXFraction) < 0.002f &&
            kotlin.math.abs(offsetYFraction) < 0.002f

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

/**
 * Physical display resolution metrics without artificial downscaling ceilings.
 */
data class ScreenResolution(
    val widthPx: Int,
    val heightPx: Int,
) {
    val longEdge: Int get() = max(widthPx, heightPx)
    val shortEdge: Int get() = min(widthPx, heightPx)
}

object WallpaperDisplayMetrics {
    /** Safe hardware GPU max bitmap/texture edge to avoid Canvas/GLES OOM on extreme photos. */
    const val GPU_MAX_TEXTURE_EDGE = 4096

    fun getNativeScreenResolution(ctx: Context): ScreenResolution {
        return try {
            val dm = ctx.resources.displayMetrics
            var w = dm.widthPixels
            var h = dm.heightPixels
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                w = max(w, bounds.width())
                h = max(h, bounds.height())
            }
            ScreenResolution(
                widthPx = w.coerceAtLeast(1080),
                heightPx = h.coerceAtLeast(1080),
            )
        } catch (_: Exception) {
            ScreenResolution(1080, 2400)
        }
    }

    /**
     * Computes exact pixel decode dimensions for Coil so images render at full native
     * display clarity (including crisp headroom when zoomed), safely clamped to GPU max texture.
     */
    fun getDecodeTargetSize(ctx: Context, zoomFactor: Float = 1f): Pair<Int, Int> {
        val screen = getNativeScreenResolution(ctx)
        val factor = zoomFactor.coerceIn(1f, 2.25f)
        val targetW = (screen.widthPx * factor).toInt().coerceIn(screen.widthPx, GPU_MAX_TEXTURE_EDGE)
        val targetH = (screen.heightPx * factor).toInt().coerceIn(screen.heightPx, GPU_MAX_TEXTURE_EDGE)
        return targetW to targetH
    }
}
