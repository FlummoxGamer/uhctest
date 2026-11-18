package app.harvester.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import app.harvester.companion.core.network.UhHttpClient
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

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
        val dir = File(ctx.getExternalFilesDir(null)?.parentFile ?: ctx.filesDir, "wallpapers")
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) {
            try { nomedia.createNewFile() } catch (_: Exception) {}
        }
        return dir
    }

    fun posterFileFor(videoPath: String): File = File("${videoPath}.poster.webp")

    fun isVideoFileOrUrl(value: String): Boolean {
        val lower = value.lowercase().substringBefore('?')
        return lower.endsWith(".mp4") ||
            lower.endsWith(".webm") ||
            lower.endsWith(".mkv") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".m4v") ||
            lower.endsWith(".3gp") ||
            File(value).name.startsWith("motion_")
    }

    fun getOptimalDeviceLongEdge(ctx: Context): Int {
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
            val rawMax = max(w, h)
            val capped = rawMax.coerceIn(1080, 2560)
            (capped / 2) * 2
        } catch (_: Exception) {
            1920
        }
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    fun saveWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val tempFile = File(ctx.cacheDir, "wp_src_${System.currentTimeMillis()}")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            val destFile = File(getWallpaperDir(ctx), "wall_${System.currentTimeMillis()}.webp")
            val ok = normalizeImageToWebp(ctx, tempFile, destFile)
            tempFile.delete()
            if (!ok) return null

            dedupeWallpapers(ctx, destFile, "wall_")
            cleanOldWallpapers(ctx)
            destFile.absolutePath
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "saveWallpaper failed: ${e.message}")
            null
        }
    }

    fun saveMotionWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val ext = guessExtensionFromUri(ctx, uri)
            val rawInput = File(ctx.cacheDir, "motion_raw_${System.currentTimeMillis()}.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                rawInput.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val destFile = File(getWallpaperDir(ctx), "motion_${System.currentTimeMillis()}.mp4")
            val ok = transcodeToDeviceH264Mp4(ctx, rawInput, destFile)
            rawInput.delete()
            if (!ok) {
                destFile.delete()
                return null
            }
            ensureVideoPoster(destFile.absolutePath)
            cleanOldWallpapers(ctx)
            destFile.absolutePath
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "saveMotionWallpaper failed: ${e.message}")
            null
        }
    }

    fun saveMotionWallpaperFile(ctx: Context, fileToCopy: File): String? {
        return try {
            val file = File(getWallpaperDir(ctx), "motion_${System.currentTimeMillis()}.mp4")
            fileToCopy.copyTo(file, overwrite = true)
            ensureVideoPoster(file.absolutePath)
            cleanOldWallpapers(ctx)
            file.absolutePath
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "saveMotionWallpaperFile failed: ${e.message}")
            null
        }
    }

    fun downloadRemoteWallpaper(ctx: Context, url: String): String? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val dir = getWallpaperDir(ctx)

            val existingVideo = File(dir, "motion_$hash.mp4")
            if (existingVideo.exists() && existingVideo.length() > 1024L) {
                ensureVideoPoster(existingVideo.absolutePath)
                return existingVideo.absolutePath
            }
            val existingImage = File(dir, "wall_$hash.webp")
            if (existingImage.exists() && existingImage.length() > 512L) {
                return existingImage.absolutePath
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            val tmpDownload = File(ctx.cacheDir, "wp_dl_${hash}_${System.currentTimeMillis()}")
            var contentType = ""
            UhHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    UHLog.w("wallpaper_warn", "Download HTTP ${response.code} for $url")
                    return null
                }
                contentType = response.header("Content-Type").orEmpty().lowercase()
                val body = response.body ?: return null
                tmpDownload.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }

            if (!tmpDownload.exists() || tmpDownload.length() < 64L) {
                tmpDownload.delete()
                return null
            }

            val isVideo = contentType.startsWith("video/") ||
                isVideoFileOrUrl(url) ||
                hasVideoMagicBytes(tmpDownload)

            return if (isVideo) {
                val targetMp4 = File(dir, "motion_$hash.mp4")
                val ok = transcodeToDeviceH264Mp4(ctx, tmpDownload, targetMp4)
                tmpDownload.delete()
                if (ok && targetMp4.exists() && targetMp4.length() > 0L) {
                    ensureVideoPoster(targetMp4.absolutePath)
                    cleanOldWallpapers(ctx)
                    targetMp4.absolutePath
                } else {
                    targetMp4.delete()
                    null
                }
            } else {
                val targetWebp = File(dir, "wall_$hash.webp")
                val ok = normalizeImageToWebp(ctx, tmpDownload, targetWebp)
                tmpDownload.delete()
                if (ok && targetWebp.exists() && targetWebp.length() > 0L) {
                    cleanOldWallpapers(ctx)
                    targetWebp.absolutePath
                } else {
                    targetWebp.delete()
                    null
                }
            }
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "downloadRemoteWallpaper failed: ${e.message}")
            null
        }
    }

    suspend fun downloadAndPrepareWallpaper(ctx: Context, url: String): String? =
        withContext(Dispatchers.IO) { downloadRemoteWallpaper(ctx, url) }

    fun transcodeToDeviceH264Mp4(
        ctx: Context,
        inputFile: File,
        outputFile: File,
        startSec: Float? = null,
        durationSec: Float? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!inputFile.exists() || inputFile.length() == 0L) return false
        val targetMaxDim = getOptimalDeviceLongEdge(ctx)
        val probe = probeVideo(inputFile.absolutePath)
        val srcDurationMs = ((durationSec ?: probe.durationSec) * 1000f).coerceAtLeast(1000f)

        // Fast stream copy if full clip is already well-sized H.264 MP4 within 60fps
        if (startSec == null && durationSec == null &&
            probe.isMp4Container &&
            probe.maxDim in 1..targetMaxDim &&
            probe.fps <= 60f
        ) {
            val copyCmd = "-y -i \"${inputFile.absolutePath}\" -c:v copy -an -movflags +faststart \"${outputFile.absolutePath}\""
            val copySession = FFmpegKit.execute(copyCmd)
            if (ReturnCode.isSuccess(copySession.returnCode) && outputFile.exists() && outputFile.length() > 1024L) {
                onProgress?.invoke(1f)
                return true
            }
        }

        if (onProgress != null) {
            FFmpegKitConfig.enableStatisticsCallback { stats ->
                val pct = (stats.time.toFloat() / srcDurationMs).coerceIn(0f, 0.98f)
                onProgress(pct)
            }
        }

        val trimArgs = if (startSec != null && durationSec != null) {
            "-ss ${"%.2f".format(java.util.Locale.US, startSec)} -t ${"%.2f".format(java.util.Locale.US, durationSec)} "
        } else ""

        // Keep aspect ratio, clamp long edge to device resolution, force even dimensions & <= 60fps
        val vf = "scale=w='min(iw,$targetMaxDim)':h='min(ih,$targetMaxDim)':force_original_aspect_ratio=decrease," +
            "scale=trunc(iw/2)*2:trunc(ih/2)*2,fps=60"

        val codecs = listOf(
            "-c:v h264_mediacodec -b:v 6M -pix_fmt yuv420p",
            "-c:v libopenh264 -b:v 5M -pix_fmt yuv420p",
            "-c:v mpeg4 -q:v 2 -pix_fmt yuv420p"
        )

        try {
            for (codecArgs in codecs) {
                if (outputFile.exists()) outputFile.delete()
                val cmd = "-y $trimArgs-i \"${inputFile.absolutePath}\" -vf \"$vf\" $codecArgs -an -movflags +faststart \"${outputFile.absolutePath}\""
                val session = FFmpegKit.execute(cmd)
                if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 1024L) {
                    onProgress?.invoke(1f)
                    return true
                }
                UHLog.w("video_warn", "Encoder pass ($codecArgs) failed: ${session.failStackTrace}")
            }
        } finally {
            if (onProgress != null) {
                FFmpegKitConfig.enableStatisticsCallback(null)
            }
        }
        return false
    }

    private fun normalizeImageToWebp(ctx: Context, srcFile: File, destFile: File): Boolean {
        return try {
            val maxDim = getOptimalDeviceLongEdge(ctx)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(srcFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var inSample = 1
            while ((bounds.outWidth / inSample) > maxDim * 2 || (bounds.outHeight / inSample) > maxDim * 2) {
                inSample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = inSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeFile(srcFile.absolutePath, decodeOpts) ?: return false

            // Apply EXIF orientation if needed
            val rotation = readExifRotation(srcFile.absolutePath)
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                if (rotated !== bmp) {
                    bmp.recycle()
                    bmp = rotated
                }
            }

            // Downscale smoothly to optimal device resolution
            val curMax = max(bmp.width, bmp.height)
            if (curMax > maxDim) {
                val scale = maxDim.toFloat() / curMax.toFloat()
                val tw = (bmp.width * scale).toInt().coerceAtLeast(1)
                val th = (bmp.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                if (scaled !== bmp) {
                    bmp.recycle()
                    bmp = scaled
                }
            }

            val tmpOut = File(destFile.parentFile, "${destFile.name}.tmp")
            tmpOut.outputStream().use { out ->
                bmp.compress(webpFormat(), 90, out)
            }
            bmp.recycle()
            if (!tmpOut.renameTo(destFile)) {
                tmpOut.copyTo(destFile, overwrite = true)
                tmpOut.delete()
            }
            destFile.exists() && destFile.length() > 0L
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "normalizeImageToWebp failed: ${e.message}")
            false
        }
    }

    private fun ensureVideoPoster(videoPath: String) {
        val poster = posterFileFor(videoPath)
        if (poster.exists() && poster.length() > 256L) return
        val thumb = generateVideoThumbnail(videoPath) ?: return
        try {
            poster.outputStream().use { out ->
                thumb.compress(webpFormat(), 88, out)
            }
        } catch (_: Exception) {
        } finally {
            thumb.recycle()
        }
    }

    private fun readExifRotation(path: String): Int {
        return try {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun hasVideoMagicBytes(file: File): Boolean {
        return try {
            val header = ByteArray(16)
            val count = file.inputStream().use { it.read(header) }
            if (count < 12) return false
            // ISO BMFF MP4/MOV ('ftyp' at offset 4)
            if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            ) return true
            // Matroska / WebM EBML header
            if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            ) return true
            false
        } catch (_: Exception) {
            false
        }
    }

    private data class VideoProbe(
        val width: Int,
        val height: Int,
        val durationSec: Float,
        val fps: Float,
        val isMp4Container: Boolean
    ) {
        val maxDim: Int get() = max(width, height)
    }

    private fun probeVideo(path: String): VideoProbe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15000L
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty().lowercase()
            VideoProbe(
                width = w,
                height = h,
                durationSec = (durMs / 1000f).coerceAtLeast(1f),
                fps = fps,
                isMp4Container = mime.contains("mp4") || path.endsWith(".mp4", ignoreCase = true)
            )
        } catch (_: Exception) {
            VideoProbe(0, 0, 15f, 30f, false)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun guessExtensionFromUri(ctx: Context, uri: Uri): String {
        val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
        return when {
            mime.contains("webm") -> "webm"
            mime.contains("x-matroska") || mime.contains("mkv") -> "mkv"
            mime.contains("quicktime") -> "mov"
            mime.contains("3gpp") -> "3gp"
            mime.contains("avi") -> "avi"
            else -> {
                val pathExt = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
                if (pathExt in setOf("mp4", "webm", "mkv", "mov", "avi", "3gp", "m4v")) pathExt else "mp4"
            }
        }
    }

    private fun dedupeWallpapers(ctx: Context, newest: File, prefix: String) {
        val hash = getFastHash(newest)
        if (hash.isEmpty()) return
        getWallpaperDir(ctx).listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && !f.name.endsWith(".poster.webp")
        }?.forEach { existing ->
            if (existing.absolutePath != newest.absolutePath && getFastHash(existing) == hash) {
                existing.delete()
                posterFileFor(existing.absolutePath).delete()
            }
        }
    }

    fun generateVideoThumbnail(videoPath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
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
        val currentPath = getCurrentWallpaper(ctx)
        val files = dir.listFiles { f ->
            f.isFile &&
                (f.name.startsWith("wall_") || f.name.startsWith("motion_")) &&
                !f.name.endsWith(".poster.webp")
        } ?: return
        if (files.size > 20) {
            files.sortedBy { it.lastModified() }
                .filter { it.absolutePath != currentPath }
                .take((files.size - 20).coerceAtLeast(0))
                .forEach {
                    it.delete()
                    posterFileFor(it.absolutePath).delete()
                }
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
                if (isVideo) ensureVideoPoster(path)
                val poster = if (isVideo) posterFileFor(path) else null
                val bmp = when {
                    poster != null && poster.exists() -> getSampledBitmap(poster.absolutePath, 240, 420)
                    isVideo -> generateVideoThumbnail(path)
                    else -> getSampledBitmap(path, 240, 420)
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
        return dir.listFiles { file ->
            file.isFile &&
                (file.name.startsWith("wall_") || file.name.startsWith("motion_")) &&
                !file.name.endsWith(".poster.webp")
        }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.absolutePath }
            ?.take(20) ?: emptyList()
    }

    fun deleteWallpaper(path: String) {
        try {
            File(path).delete()
            posterFileFor(path).delete()
        } catch (_: Exception) {}
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
