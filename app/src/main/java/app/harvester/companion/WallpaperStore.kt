package app.harvester.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

object WallpaperStore {
    private const val PREFS_NAME = "uh_wallpapers"
    private const val KEY_CURRENT = "current_wallpaper_path"
    private const val KEY_TEXT_COLOR = "wallpaper_text_color"
    private const val KEY_IS_VIDEO = "wallpaper_is_video"
    private const val KEY_TOP_DARK = "wallpaper_top_dark"
    private const val KEY_BOTTOM_DARK = "wallpaper_bottom_dark"
    private const val KEY_ZOOM_PORTRAIT = "wallpaper_zoom_portrait"
    private const val KEY_PAN_X_PORTRAIT = "wallpaper_pan_x_portrait"
    private const val KEY_PAN_Y_PORTRAIT = "wallpaper_pan_y_portrait"
    private const val KEY_ZOOM_LANDSCAPE = "wallpaper_zoom_landscape"
    private const val KEY_PAN_X_LANDSCAPE = "wallpaper_pan_x_landscape"
    private const val KEY_PAN_Y_LANDSCAPE = "wallpaper_pan_y_landscape"

    data class WallpaperState(
        val path: String?,
        val isVideo: Boolean,
        val textColor: Int,
        val isTopDark: Boolean?,
        val isBottomDark: Boolean?,
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _stateFlow = MutableStateFlow<WallpaperState?>(null)

    private fun getPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun wallpaperFlow(ctx: Context): StateFlow<WallpaperState> {
        val current = _stateFlow.value
        if (current == null) {
            val loaded = readState(ctx)
            _stateFlow.value = loaded
            if (!loaded.path.isNullOrBlank() && loaded.path != "default" && loaded.isTopDark == null) {
                computeAndPersistLuminanceAsync(ctx.applicationContext, loaded.path, loaded.isVideo, loaded.textColor)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return _stateFlow.asStateFlow() as StateFlow<WallpaperState>
    }

    private fun readState(ctx: Context): WallpaperState {
        val prefs = getPrefs(ctx)
        val path = prefs.getString(KEY_CURRENT, null)
        val isVideo = prefs.getBoolean(KEY_IS_VIDEO, false)
        val textColor = prefs.getInt(KEY_TEXT_COLOR, 0)
        val hasCustom = !path.isNullOrBlank() && path != "default"
        val topInt = if (hasCustom) prefs.getInt(KEY_TOP_DARK, -1) else -1
        val bottomInt = if (hasCustom) prefs.getInt(KEY_BOTTOM_DARK, -1) else -1
        return WallpaperState(
            path = path,
            isVideo = isVideo,
            textColor = textColor,
            isTopDark = when (topInt) { 1 -> true; 0 -> false; else -> null },
            isBottomDark = when (bottomInt) { 1 -> true; 0 -> false; else -> null },
        )
    }

    fun getWallpaperDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null)?.parentFile, "wallpapers")
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Exception) {}
        }
        return dir
    }

    fun saveWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                else -> "jpg"
            }
            val file = File(getWallpaperDir(ctx), "wall_${System.currentTimeMillis()}.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val hash = getFastHash(file)
            if (hash.isNotEmpty()) {
                val existing = getWallpaperDir(ctx).listFiles { f -> f.name.startsWith("wall_") }?.find {
                    it.absolutePath != file.absolutePath && getFastHash(it) == hash
                }
                if (existing != null) existing.delete()
            }
            cleanOldWallpapers(ctx)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun saveMotionWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val file = File(getWallpaperDir(ctx), "motion_${System.currentTimeMillis()}.mp4")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            cleanOldWallpapers(ctx)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun saveMotionWallpaperFile(ctx: Context, fileToCopy: File): String? {
        return try {
            val file = File(getWallpaperDir(ctx), "motion_${System.currentTimeMillis()}.mp4")
            fileToCopy.copyTo(file, overwrite = true)
            cleanOldWallpapers(ctx)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun downloadRemoteWallpaper(ctx: Context, url: String): String? {
        return try {
            val isVideo = url.endsWith(".mp4") || url.contains("sample")
            val ext = if (isVideo) "mp4" else "jpg"
            val prefix = if (isVideo) "motion_" else "wall_"

            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val file = File(getWallpaperDir(ctx), "$prefix$hash.$ext")

            if (file.exists()) {
                return file.absolutePath
            }

            val request = Request.Builder().url(url).build()
            val response = OkHttpClient().newCall(request).execute()
            if (response.isSuccessful && response.body != null) {
                response.body!!.byteStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                cleanOldWallpapers(ctx)
                file.absolutePath
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun generateVideoThumbnail(videoPath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun getFastHash(file: File): String {
        return try {
            if (!file.exists() || file.length() == 0L) return ""
            val md = MessageDigest.getInstance("MD5")
            md.update(file.length().toString().toByteArray())
            val buffer = ByteArray(1024)
            val bytesRead = file.inputStream().use { it.read(buffer) }
            if (bytesRead > 0) md.update(buffer, 0, bytesRead)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }

    private fun cleanOldWallpapers(ctx: Context) {
        val dir = getWallpaperDir(ctx)
        val files = dir.listFiles { f -> f.isFile && (f.name.startsWith("wall_") || f.name.startsWith("motion_")) } ?: return
        if (files.size > 20) {
            files.sortedBy { it.lastModified() }.take(files.size - 20).forEach { it.delete() }
        }
    }

    fun setCurrentWallpaper(ctx: Context, path: String?, textColor: Int, isVideo: Boolean = false) {
        val appCtx = ctx.applicationContext
        val isCustom = !path.isNullOrBlank() && path != "default"
        val editor = getPrefs(appCtx).edit()
            .putString(KEY_CURRENT, path)
            .putInt(KEY_TEXT_COLOR, textColor)
            .putBoolean(KEY_IS_VIDEO, isVideo)

        if (!isCustom) {
            editor.putInt(KEY_TOP_DARK, -1).putInt(KEY_BOTTOM_DARK, -1).apply()
            _stateFlow.value = WallpaperState(path, isVideo, textColor, null, null)
            return
        }

        // Fallback estimate immediately from textColor (white text => dark bg) then refine asynchronously from edge pixels
        val estDark = if (textColor != 0) ColorUtils.calculateLuminance(textColor) > 0.5 else true
        editor.putInt(KEY_TOP_DARK, if (estDark) 1 else 0)
            .putInt(KEY_BOTTOM_DARK, if (estDark) 1 else 0)
            .apply()
        _stateFlow.value = WallpaperState(path, isVideo, textColor, estDark, estDark)

        computeAndPersistLuminanceAsync(appCtx, path!!, isVideo, textColor)
    }

    private fun computeAndPersistLuminanceAsync(ctx: Context, path: String, isVideo: Boolean, textColor: Int) {
        ioScope.launch {
            try {
                val bmp = if (isVideo) {
                    generateVideoThumbnail(path)
                } else {
                    getSampledBitmap(path, 240, 420)
                }
                if (bmp != null) {
                    val topDark = isStripDark(bmp, topRatioStart = 0f, topRatioEnd = 0.14f)
                    val bottomDark = isStripDark(bmp, topRatioStart = 0.86f, topRatioEnd = 1.0f)
                    bmp.recycle()
                    val prefs = getPrefs(ctx)
                    if (prefs.getString(KEY_CURRENT, null) == path) {
                        prefs.edit()
                            .putInt(KEY_TOP_DARK, if (topDark) 1 else 0)
                            .putInt(KEY_BOTTOM_DARK, if (bottomDark) 1 else 0)
                            .apply()
                        _stateFlow.value = WallpaperState(path, isVideo, textColor, topDark, bottomDark)
                    }
                }
            } catch (e: Exception) {
                UHLog.w("wallpaper_warn", "Failed calculating wallpaper strip luminance: ${e.message}")
            }
        }
    }

    fun isStripDark(bmp: Bitmap, topRatioStart: Float, topRatioEnd: Float): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return true
        val startY = (h * topRatioStart).toInt().coerceIn(0, h - 1)
        val endY = (h * topRatioEnd).toInt().coerceIn(startY + 1, h)
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = ((endY - startY) / 6).coerceAtLeast(1)
        var lumSum = 0.0
        var count = 0
        var y = startY
        while (y < endY) {
            var x = 0
            while (x < w) {
                val pixel = bmp.getPixel(x, y)
                lumSum += ColorUtils.calculateLuminance(pixel)
                count++
                x += stepX
            }
            y += stepY
        }
        val avgLum = if (count > 0) lumSum / count else 0.0
        return avgLum < 0.5
    }

    fun getCurrentWallpaper(ctx: Context): String? = getPrefs(ctx).getString(KEY_CURRENT, null)

    fun isCurrentVideo(ctx: Context): Boolean = getPrefs(ctx).getBoolean(KEY_IS_VIDEO, false)

    fun getTextColor(ctx: Context): Int = getPrefs(ctx).getInt(KEY_TEXT_COLOR, 0)

    fun setWallpaperTransform(ctx: Context, isLandscape: Boolean, zoom: Float, panX: Float, panY: Float) {
        val zoomKey = if (isLandscape) KEY_ZOOM_LANDSCAPE else KEY_ZOOM_PORTRAIT
        val panXKey = if (isLandscape) KEY_PAN_X_LANDSCAPE else KEY_PAN_X_PORTRAIT
        val panYKey = if (isLandscape) KEY_PAN_Y_LANDSCAPE else KEY_PAN_Y_PORTRAIT
        getPrefs(ctx).edit()
            .putFloat(zoomKey, zoom)
            .putFloat(panXKey, panX)
            .putFloat(panYKey, panY)
            .apply()
    }

    fun getWallpaperTransform(ctx: Context, isLandscape: Boolean): Triple<Float, Float, Float> {
        val zoomKey = if (isLandscape) KEY_ZOOM_LANDSCAPE else KEY_ZOOM_PORTRAIT
        val panXKey = if (isLandscape) KEY_PAN_X_LANDSCAPE else KEY_PAN_X_PORTRAIT
        val panYKey = if (isLandscape) KEY_PAN_Y_LANDSCAPE else KEY_PAN_Y_PORTRAIT
        val zoom = getPrefs(ctx).getFloat(zoomKey, 1f)
        val panX = getPrefs(ctx).getFloat(panXKey, 0.5f)
        val panY = getPrefs(ctx).getFloat(panYKey, 0.5f)
        return Triple(zoom, panX, panY)
    }

    fun getRecent(ctx: Context): List<String> {
        val dir = getWallpaperDir(ctx)
        return dir.listFiles { file -> file.isFile && (file.name.startsWith("wall_") || file.name.startsWith("motion_")) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.absolutePath }
            ?.take(20) ?: emptyList()
    }

    fun deleteWallpaper(path: String) {
        try { File(path).delete() } catch (_: Exception) {}
    }

    fun clearAll(ctx: Context) {
        try {
            getWallpaperDir(ctx).listFiles()?.forEach {
                if (it.name != ".nomedia") it.delete()
            }
            getPrefs(ctx).edit().clear().apply()
            _stateFlow.value = WallpaperState(null, false, 0, null, null)
        } catch (_: Exception) {}
    }

    fun getSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
