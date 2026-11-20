package app.harvester.companion.wallpaper

import android.content.Context
import android.os.Build
import android.view.WindowManager
import coil.size.Dimension
import coil.size.Size
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Resolves exact physical display bounds without artificial downscale caps,
 * ensuring wallpapers decode at the maximum native resolution the device screen can show.
 */
object WallpaperDisplayMetrics {

    // Safe OpenGL hardware texture ceiling on modern Android devices (API 29+)
    private const val GPU_SAFE_MAX_TEXTURE_PX = 4096

    data class ScreenBounds(
        val widthPx: Int,
        val heightPx: Int,
    ) {
        val longEdge: Int get() = max(widthPx, heightPx)
        val shortEdge: Int get() = min(widthPx, heightPx)
    }

    fun getScreenBounds(ctx: Context): ScreenBounds {
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
            ScreenBounds(
                widthPx = w.coerceAtLeast(1080),
                heightPx = h.coerceAtLeast(1080),
            )
        } catch (_: Exception) {
            ScreenBounds(1080, 2400)
        }
    }

    /**
     * Exact native long edge of the display, rounded to even pixels, with no artificial 2560px ceiling.
     */
    fun getNativeLongEdge(ctx: Context): Int {
        val raw = getScreenBounds(ctx).longEdge.coerceIn(1080, GPU_SAFE_MAX_TEXTURE_PX)
        return (raw / 2) * 2
    }

    /**
     * Computes the optimal Coil decode [Size] for rendering a wallpaper on screen.
     *
     * Multiplies native display pixels by [zoomMultiplier] so zoomed-in wallpapers remain
     * crisp without exceeding safe GPU texture memory bounds.
     */
    fun computeCoilTargetSize(ctx: Context, zoomMultiplier: Float = 1f): Size {
        val bounds = getScreenBounds(ctx)
        val factor = zoomMultiplier.coerceIn(1f, 2.5f)
        val targetW = (bounds.widthPx * factor).roundToInt().coerceIn(bounds.widthPx, GPU_SAFE_MAX_TEXTURE_PX)
        val targetH = (bounds.heightPx * factor).roundToInt().coerceIn(bounds.heightPx, GPU_SAFE_MAX_TEXTURE_PX)
        return Size(Dimension(targetW), Dimension(targetH))
    }

    /**
     * Upgrades Unsplash catalog URLs to deliver full native screen resolution at maximum quality (q=100),
     * avoiding server-side compression and 2000px caps.
     */
    fun upgradeRemoteImageUrlForDisplay(ctx: Context, rawUrl: String): String {
        if (!rawUrl.contains("images.unsplash.com")) return rawUrl
        val base = rawUrl.substringBefore('?')
        val targetLongEdge = (getScreenBounds(ctx).longEdge * 1.5f)
            .roundToInt()
            .coerceIn(2400, GPU_SAFE_MAX_TEXTURE_PX)
        return "$base?q=100&w=$targetLongEdge&auto=format&fit=max"
    }

    /**
     * Compact thumbnail URL for snappy scrolling in Wallpaper Gallery grids.
     */
    fun buildGridThumbnailUrl(rawUrl: String): String {
        if (!rawUrl.contains("images.unsplash.com")) return rawUrl
        val base = rawUrl.substringBefore('?')
        return "$base?q=85&w=540&auto=format&fit=crop"
    }
}
