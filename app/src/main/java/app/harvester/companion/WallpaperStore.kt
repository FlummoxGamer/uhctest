package app.harvester.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.core.graphics.ColorUtils
import app.harvester.companion.wallpaper.WallpaperCatalog
import app.harvester.companion.wallpaper.WallpaperMediaCache
import app.harvester.companion.wallpaper.WallpaperMediaProcessor
import app.harvester.companion.wallpaper.WallpaperTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object WallpaperStore {
    private const val PREFS_NAME = "uh_wallpapers"
    private const val KEY_CURRENT = "current_wallpaper_path"
    private const val KEY_SOURCE_URL = "current_wallpaper_source_url"
    private const val KEY_TEXT_COLOR = "wallpaper_text_color"
    private const val KEY_IS_VIDEO = "wallpaper_is_video"
    private const val KEY_DIM_ALPHA = "wallpaper_dim_alpha"
    private const val KEY_TOP_DARK = "wallpaper_top_dark"
    private const val KEY_BOTTOM_DARK = "wallpaper_bottom_dark"
    private const val KEY_TRANSFORM_V2 = "wallpaper_transform_v2"
    private const val KEY_ZOOM_PORTRAIT = "wallpaper_zoom_portrait"
    private const val KEY_PAN_X_PORTRAIT = "wallpaper_pan_x_portrait"
    private const val KEY_PAN_Y_PORTRAIT = "wallpaper_pan_y_portrait"
    private const val KEY_ZOOM_LANDSCAPE = "wallpaper_zoom_landscape"
    private const val KEY_PAN_X_LANDSCAPE = "wallpaper_pan_x_landscape"
    private const val KEY_PAN_Y_LANDSCAPE = "wallpaper_pan_y_landscape"

    private const val MAX_RECENT_ITEMS = 20
    private const val MAX_NON_ACTIVE_STORAGE_BYTES = 350L * 1024L * 1024L
    const val MAX_DIM_ALPHA = 0.65f

    data class WallpaperState(
        val path: String?,
        val sourceUrl: String? = null,
        val isVideo: Boolean = false,
        val textColor: Int = 0,
        val dimAlpha: Float = 0f,
        val isTopDark: Boolean? = null,
        val isBottomDark: Boolean? = null,
        val portraitTransform: WallpaperTransform = WallpaperTransform.Identity,
        val landscapeTransform: WallpaperTransform = WallpaperTransform.Identity,
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
                computeAndPersistLuminanceAsync(
                    ctx = ctx.applicationContext,
                    path = loaded.path,
                    isVideo = loaded.isVideo,
                    textColor = loaded.textColor,
                    dimAlpha = loaded.dimAlpha,
                )
            }
        }
        @Suppress("UNCHECKED_CAST")
        return _stateFlow.asStateFlow() as StateFlow<WallpaperState>
    }

    private fun readState(ctx: Context): WallpaperState {
        val prefs = getPrefs(ctx)
        var path = prefs.getString(KEY_CURRENT, null)
        var isVideo = prefs.getBoolean(KEY_IS_VIDEO, false)
        var textColor = prefs.getInt(KEY_TEXT_COLOR, 0)
        var sourceUrl = prefs.getString(KEY_SOURCE_URL, null)
        var dimAlpha = prefs.getFloat(KEY_DIM_ALPHA, 0f).coerceIn(0f, MAX_DIM_ALPHA)

        // Guard against deleted/missing local file leaving a broken black Home background
        if (!path.isNullOrBlank() && path != "default" && !path.startsWith("http") && !path.startsWith("content://")) {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                path = "default"
                isVideo = false
                textColor = 0
                sourceUrl = null
                dimAlpha = 0f
                prefs.edit()
                    .putString(KEY_CURRENT, "default")
                    .remove(KEY_SOURCE_URL)
                    .putBoolean(KEY_IS_VIDEO, false)
                    .putInt(KEY_TEXT_COLOR, 0)
                    .putFloat(KEY_DIM_ALPHA, 0f)
                    .putInt(KEY_TOP_DARK, -1)
                    .putInt(KEY_BOTTOM_DARK, -1)
                    .apply()
            }
        }

        val hasCustom = !path.isNullOrBlank() && path != "default"
        val topInt = if (hasCustom) prefs.getInt(KEY_TOP_DARK, -1) else -1
        val bottomInt = if (hasCustom) prefs.getInt(KEY_BOTTOM_DARK, -1) else -1
        return WallpaperState(
            path = path,
            sourceUrl = if (hasCustom) sourceUrl else null,
            isVideo = isVideo,
            textColor = textColor,
            dimAlpha = if (hasCustom) dimAlpha else 0f,
            isTopDark = when (topInt) { 1 -> true; 0 -> false; else -> null },
            isBottomDark = when (bottomInt) { 1 -> true; 0 -> false; else -> null },
            portraitTransform = readTransform(prefs, isLandscape = false),
            landscapeTransform = readTransform(prefs, isLandscape = true),
        )
    }

    private fun readTransform(
        prefs: android.content.SharedPreferences,
        isLandscape: Boolean,
    ): WallpaperTransform {
        if (!prefs.getBoolean(KEY_TRANSFORM_V2, false)) {
            return WallpaperTransform.Identity
        }
        val zoomKey = if (isLandscape) KEY_ZOOM_LANDSCAPE else KEY_ZOOM_PORTRAIT
        val panXKey = if (isLandscape) KEY_PAN_X_LANDSCAPE else KEY_PAN_X_PORTRAIT
        val panYKey = if (isLandscape) KEY_PAN_Y_LANDSCAPE else KEY_PAN_Y_PORTRAIT
        return WallpaperTransform(
            scale = prefs.getFloat(zoomKey, 1f),
            offsetXFraction = prefs.getFloat(panXKey, 0f),
            offsetYFraction = prefs.getFloat(panYKey, 0f),
        ).clamped()
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

    /**
     * Lossless local image import — keeps original file verbatim (zero compression, zero downscale),
     * deduplicates by content hash, and updates recency timestamp on duplicates.
     */
    fun saveWallpaper(ctx: Context, uri: Uri): String? {
        return try {
            val destFile = WallpaperMediaProcessor.importLocalImageLossless(ctx, uri, getWallpaperDir(ctx))
                ?: return null
            val canonical = dedupeWallpapers(ctx, destFile, "wall_")
            cleanOldWallpapers(ctx)
            canonical.absolutePath
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "saveWallpaper failed: ${e.message}")
            null
        }
    }

    /**
     * Single-pass local video import:
     * - Full MP4: skips FFmpeg completely (instant direct copy).
     * - Trimmed MP4: lossless stream-copy trim (< 1s).
     * - Non-MP4: hardware MediaCodec high-bitrate conversion capped at <= 30fps.
     * Also deduplicates videos by content hash so re-importing the same clip does not waste storage.
     */
    fun saveLocalVideo(
        ctx: Context,
        uri: Uri,
        startSec: Float? = null,
        durationSec: Float? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): String? {
        return try {
            val destFile = WallpaperMediaProcessor.importLocalVideo(
                ctx = ctx,
                uri = uri,
                destDir = getWallpaperDir(ctx),
                startSec = startSec,
                durationSec = durationSec,
                onProgress = onProgress,
            ) ?: return null

            val canonical = dedupeWallpapers(ctx, destFile, "motion_")
            ensureVideoPoster(canonical.absolutePath)
            cleanOldWallpapers(ctx)
            canonical.absolutePath
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "saveLocalVideo failed: ${e.message}")
            null
        }
    }

    /**
     * Downloads or promotes a cached remote wallpaper at maximum screen resolution without
     * lossy re-compression or slow MP4 re-encoding.
     */
    suspend fun downloadAndPrepareWallpaper(ctx: Context, rawUrl: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val resolvedUrl = WallpaperCatalog.fullResolutionUrl(ctx, rawUrl)
                val hash = WallpaperMediaCache.urlHash(rawUrl)
                val dir = getWallpaperDir(ctx)

                // 1. Reuse already-saved wallpaper in permanent storage if present
                findExistingByHash(dir, hash)?.let { existing ->
                    existing.setLastModified(System.currentTimeMillis())
                    if (isVideoFileOrUrl(existing.absolutePath)) {
                        ensureVideoPoster(existing.absolutePath)
                    }
                    return@withContext existing.absolutePath
                }

                val expectVideo = isVideoFileOrUrl(rawUrl)
                if (expectVideo) {
                    // 2a. Reuse single-flight preview cache (zero duplicate network download!)
                    val cachedVideo = WallpaperMediaCache.getOrDownloadVideo(ctx, resolvedUrl)
                        ?: return@withContext null
                    val targetMp4 = File(dir, "motion_$hash.mp4")
                    val ok = WallpaperMediaProcessor.prepareMotionVideo(
                        ctx = ctx,
                        inputFile = cachedVideo,
                        outputFile = targetMp4,
                    )
                    if (ok && targetMp4.exists() && targetMp4.length() > 1024L) {
                        ensureVideoPoster(targetMp4.absolutePath)
                        cleanOldWallpapers(ctx)
                        return@withContext targetMp4.absolutePath
                    }
                    targetMp4.delete()
                    return@withContext null
                }

                // 2b. Remote image: promote directly from Coil disk cache if already loaded in Preview
                val tmpImage = File(ctx.cacheDir, "wp_dl_${hash}_${System.currentTimeMillis()}.tmp")
                val promotedFromCache = WallpaperMediaCache.tryExportCoilCachedImage(ctx, resolvedUrl, tmpImage)
                val ready = if (promotedFromCache) {
                    true
                } else {
                    WallpaperMediaCache.downloadToFile(resolvedUrl, tmpImage)
                }

                if (!ready || !tmpImage.exists() || tmpImage.length() < 64L) {
                    tmpImage.delete()
                    return@withContext null
                }

                // Detect unexpected video payload vs. lossless image finalization
                if (WallpaperMediaProcessor.hasVideoMagicBytes(tmpImage)) {
                    val targetMp4 = File(dir, "motion_$hash.mp4")
                    val ok = WallpaperMediaProcessor.prepareMotionVideo(ctx, tmpImage, targetMp4)
                    tmpImage.delete()
                    if (ok && targetMp4.exists() && targetMp4.length() > 1024L) {
                        ensureVideoPoster(targetMp4.absolutePath)
                        cleanOldWallpapers(ctx)
                        return@withContext targetMp4.absolutePath
                    }
                    targetMp4.delete()
                    return@withContext null
                }

                val finalImg = WallpaperMediaProcessor.finalizeRemoteImageLossless(
                    srcFile = tmpImage,
                    destDir = dir,
                    hash = hash,
                    url = resolvedUrl,
                )
                tmpImage.delete()
                if (finalImg != null) {
                    cleanOldWallpapers(ctx)
                    finalImg.absolutePath
                } else {
                    null
                }
            } catch (e: Exception) {
                UHLog.e("wallpaper_error", "downloadAndPrepareWallpaper failed: ${e.message}")
                null
            }
        }

    private fun findExistingByHash(dir: File, hash: String): File? {
        val existingVideo = File(dir, "motion_$hash.mp4")
        if (existingVideo.exists() && existingVideo.length() > 1024L) return existingVideo
        return dir.listFiles { f ->
            f.isFile &&
                f.name.startsWith("wall_$hash.") &&
                !f.name.endsWith(".poster.webp") &&
                f.length() > 128L
        }?.firstOrNull()
    }

    fun isRemoteUrlActive(ctx: Context, rawUrl: String, state: WallpaperState): Boolean {
        val activePath = state.path
        if (activePath.isNullOrBlank() || activePath == "default") return false
        val canonical = WallpaperCatalog.canonicalSourceKey(rawUrl)
        if (!state.sourceUrl.isNullOrBlank() && state.sourceUrl == canonical) return true
        val hash = WallpaperMediaCache.urlHash(rawUrl)
        val activeName = File(activePath).name
        return activeName == "motion_$hash.mp4" || activeName.startsWith("wall_$hash.")
    }

    fun ensureVideoPoster(videoPath: String) {
        val poster = posterFileFor(videoPath)
        if (poster.exists() && poster.length() > 256L) return
        val thumb = WallpaperMediaProcessor.generateVideoPosterBitmap(videoPath) ?: return
        try {
            poster.outputStream().use { out ->
                thumb.compress(WallpaperMediaProcessor.webpPosterFormat(), 95, out)
            }
        } catch (_: Exception) {
        } finally {
            thumb.recycle()
        }
    }

    fun generateVideoThumbnail(videoPath: String): Bitmap? =
        WallpaperMediaProcessor.generateVideoPosterBitmap(videoPath)

    /**
     * Deduplicates both static and motion wallpapers using size + head/tail content fingerprint.
     * If a matching file already exists, updates its timestamp so it surfaces at the top of Recents
     * and deletes the newly written duplicate.
     */
    private fun dedupeWallpapers(ctx: Context, newest: File, prefix: String): File {
        val hash = getFastContentFingerprint(newest)
        if (hash.isEmpty()) return newest
        val duplicate = getWallpaperDir(ctx).listFiles { f ->
            f.isFile &&
                f.name.startsWith(prefix) &&
                !f.name.endsWith(".poster.webp") &&
                f.absolutePath != newest.absolutePath
        }?.firstOrNull { existing ->
            getFastContentFingerprint(existing) == hash
        }

        if (duplicate != null) {
            duplicate.setLastModified(System.currentTimeMillis())
            newest.delete()
            posterFileFor(newest.absolutePath).delete()
            return duplicate
        }
        return newest
    }

    private fun getFastContentFingerprint(file: File): String {
        return try {
            if (!file.exists() || file.length() == 0L) return ""
            val len = file.length()
            val md = MessageDigest.getInstance("MD5")
            md.update(len.toString().toByteArray())
            java.io.RandomAccessFile(file, "r").use { raf ->
                val chunk = ByteArray(4096)
                val headRead = raf.read(chunk)
                if (headRead > 0) md.update(chunk, 0, headRead)
                if (len > 8192L) {
                    raf.seek(len / 2L)
                    val midRead = raf.read(chunk)
                    if (midRead > 0) md.update(chunk, 0, midRead)
                    raf.seek((len - 4096L).coerceAtLeast(0L))
                    val tailRead = raf.read(chunk)
                    if (tailRead > 0) md.update(chunk, 0, tailRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanOldWallpapers(ctx: Context) {
        val dir = getWallpaperDir(ctx)
        val currentPath = getCurrentWallpaper(ctx)
        val files = dir.listFiles { f ->
            f.isFile &&
                (f.name.startsWith("wall_") || f.name.startsWith("motion_")) &&
                !f.name.endsWith(".poster.webp")
        } ?: return

        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        val nonActive = sortedOldestFirst.filter { it.absolutePath != currentPath }.toMutableList()

        // 1. Enforce max recent count (never delete active wallpaper)
        var currentCount = files.size
        val iter = nonActive.iterator()
        while (currentCount > MAX_RECENT_ITEMS && iter.hasNext()) {
            val victim = iter.next()
            victim.delete()
            posterFileFor(victim.absolutePath).delete()
            iter.remove()
            currentCount--
        }

        // 2. Enforce non-active storage budget so uncompressed 4K videos don't bloat disk forever
        var nonActiveBytes = nonActive.sumOf { it.length() }
        val sizeIter = nonActive.iterator()
        while (nonActiveBytes > MAX_NON_ACTIVE_STORAGE_BYTES && sizeIter.hasNext()) {
            val victim = sizeIter.next()
            val size = victim.length()
            victim.delete()
            posterFileFor(victim.absolutePath).delete()
            nonActiveBytes -= size
            sizeIter.remove()
        }

        // 3. Clean orphaned posters
        dir.listFiles { f -> f.isFile && f.name.endsWith(".poster.webp") }?.forEach { poster ->
            val parentVideo = File(poster.absolutePath.removeSuffix(".poster.webp"))
            if (!parentVideo.exists()) poster.delete()
        }
    }

    fun setCurrentWallpaper(
        ctx: Context,
        path: String?,
        textColor: Int,
        isVideo: Boolean = false,
        sourceUrl: String? = null,
        dimAlpha: Float = 0f,
    ) {
        val appCtx = ctx.applicationContext
        val prefs = getPrefs(appCtx)
        val isCustom = !path.isNullOrBlank() && path != "default"
        val safeDim = if (isCustom) dimAlpha.coerceIn(0f, MAX_DIM_ALPHA) else 0f
        val canonicalSource = if (isCustom && !sourceUrl.isNullOrBlank()) {
            WallpaperCatalog.canonicalSourceKey(sourceUrl)
        } else null

        val editor = prefs.edit()
            .putString(KEY_CURRENT, path)
            .putString(KEY_SOURCE_URL, canonicalSource)
            .putInt(KEY_TEXT_COLOR, if (isCustom) textColor else 0)
            .putBoolean(KEY_IS_VIDEO, isCustom && isVideo)
            .putFloat(KEY_DIM_ALPHA, safeDim)

        if (!isCustom) {
            editor.putInt(KEY_TOP_DARK, -1)
                .putInt(KEY_BOTTOM_DARK, -1)
                .putBoolean(KEY_TRANSFORM_V2, true)
                .putFloat(KEY_ZOOM_PORTRAIT, 1f)
                .putFloat(KEY_PAN_X_PORTRAIT, 0f)
                .putFloat(KEY_PAN_Y_PORTRAIT, 0f)
                .putFloat(KEY_ZOOM_LANDSCAPE, 1f)
                .putFloat(KEY_PAN_X_LANDSCAPE, 0f)
                .putFloat(KEY_PAN_Y_LANDSCAPE, 0f)
                .apply()
            _stateFlow.value = WallpaperState(
                path = path,
                sourceUrl = null,
                isVideo = false,
                textColor = 0,
                dimAlpha = 0f,
                isTopDark = null,
                isBottomDark = null,
                portraitTransform = WallpaperTransform.Identity,
                landscapeTransform = WallpaperTransform.Identity,
            )
            return
        }

        val estDark = if (safeDim >= 0.35f) {
            true
        } else if (textColor != 0) {
            ColorUtils.calculateLuminance(textColor) > 0.5
        } else {
            true
        }

        editor.putInt(KEY_TOP_DARK, if (estDark) 1 else 0)
            .putInt(KEY_BOTTOM_DARK, if (estDark) 1 else 0)
            .apply()

        val portraitTransform = readTransform(prefs, isLandscape = false)
        val landscapeTransform = readTransform(prefs, isLandscape = true)
        _stateFlow.value = WallpaperState(
            path = path,
            sourceUrl = canonicalSource,
            isVideo = isVideo,
            textColor = textColor,
            dimAlpha = safeDim,
            isTopDark = estDark,
            isBottomDark = estDark,
            portraitTransform = portraitTransform,
            landscapeTransform = landscapeTransform,
        )

        computeAndPersistLuminanceAsync(appCtx, path!!, isVideo, textColor, safeDim)
    }

    private fun computeAndPersistLuminanceAsync(
        ctx: Context,
        path: String,
        isVideo: Boolean,
        textColor: Int,
        dimAlpha: Float,
    ) {
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
                    val topDark = isStripDark(bmp, topRatioStart = 0f, topRatioEnd = 0.14f, dimAlpha = dimAlpha)
                    val bottomDark = isStripDark(bmp, topRatioStart = 0.86f, topRatioEnd = 1.0f, dimAlpha = dimAlpha)
                    bmp.recycle()
                    val prefs = getPrefs(ctx)
                    if (prefs.getString(KEY_CURRENT, null) == path) {
                        prefs.edit()
                            .putInt(KEY_TOP_DARK, if (topDark) 1 else 0)
                            .putInt(KEY_BOTTOM_DARK, if (bottomDark) 1 else 0)
                            .apply()
                        val prev = _stateFlow.value ?: readState(ctx)
                        _stateFlow.value = prev.copy(
                            isTopDark = topDark,
                            isBottomDark = bottomDark,
                        )
                    }
                }
            } catch (e: Exception) {
                UHLog.w("wallpaper_warn", "Failed calculating wallpaper strip luminance: ${e.message}")
            }
        }
    }

    fun isStripDark(
        bmp: Bitmap,
        topRatioStart: Float,
        topRatioEnd: Float,
        dimAlpha: Float = 0f,
    ): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return true
        val startY = (h * topRatioStart).toInt().coerceIn(0, h - 1)
        val endY = (h * topRatioEnd).toInt().coerceIn(startY + 1, h)
        val stepX = (w / 16).coerceAtLeast(1)
        val stepY = ((endY - startY) / 6).coerceAtLeast(1)
        val safeDim = dimAlpha.coerceIn(0f, 1f)
        var lumSum = 0.0
        var count = 0
        var y = startY
        while (y < endY) {
            var x = 0
            while (x < w) {
                val rawPixel = bmp.getPixel(x, y)
                val blended = if (safeDim > 0.01f) {
                    ColorUtils.blendARGB(rawPixel, Color.BLACK, safeDim)
                } else rawPixel
                lumSum += ColorUtils.calculateLuminance(blended)
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

    fun getDimAlpha(ctx: Context): Float = getPrefs(ctx).getFloat(KEY_DIM_ALPHA, 0f).coerceIn(0f, MAX_DIM_ALPHA)

    fun setWallpaperTransform(ctx: Context, isLandscape: Boolean, transform: WallpaperTransform) {
        val clamped = transform.clamped()
        val zoomKey = if (isLandscape) KEY_ZOOM_LANDSCAPE else KEY_ZOOM_PORTRAIT
        val panXKey = if (isLandscape) KEY_PAN_X_LANDSCAPE else KEY_PAN_X_PORTRAIT
        val panYKey = if (isLandscape) KEY_PAN_Y_LANDSCAPE else KEY_PAN_Y_PORTRAIT
        getPrefs(ctx).edit()
            .putBoolean(KEY_TRANSFORM_V2, true)
            .putFloat(zoomKey, clamped.scale)
            .putFloat(panXKey, clamped.offsetXFraction)
            .putFloat(panYKey, clamped.offsetYFraction)
            .apply()

        val prev = _stateFlow.value
        if (prev != null) {
            _stateFlow.value = if (isLandscape) {
                prev.copy(landscapeTransform = clamped)
            } else {
                prev.copy(portraitTransform = clamped)
            }
        }
    }

    fun resetWallpaperTransforms(ctx: Context) {
        getPrefs(ctx).edit()
            .putBoolean(KEY_TRANSFORM_V2, true)
            .putFloat(KEY_ZOOM_PORTRAIT, 1f)
            .putFloat(KEY_PAN_X_PORTRAIT, 0f)
            .putFloat(KEY_PAN_Y_PORTRAIT, 0f)
            .putFloat(KEY_ZOOM_LANDSCAPE, 1f)
            .putFloat(KEY_PAN_X_LANDSCAPE, 0f)
            .putFloat(KEY_PAN_Y_LANDSCAPE, 0f)
            .apply()
        val prev = _stateFlow.value
        if (prev != null) {
            _stateFlow.value = prev.copy(
                portraitTransform = WallpaperTransform.Identity,
                landscapeTransform = WallpaperTransform.Identity,
            )
        }
    }

    fun getWallpaperTransform(ctx: Context, isLandscape: Boolean): WallpaperTransform =
        readTransform(getPrefs(ctx), isLandscape)

    fun getRecent(ctx: Context): List<String> {
        val dir = getWallpaperDir(ctx)
        return dir.listFiles { file ->
            file.isFile &&
                (file.name.startsWith("wall_") || file.name.startsWith("motion_")) &&
                !file.name.endsWith(".poster.webp")
        }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.absolutePath }
            ?.take(MAX_RECENT_ITEMS) ?: emptyList()
    }

    /**
     * Deletes a wallpaper and its poster. If the deleted file is the currently active wallpaper,
     * immediately falls back cleanly to "default" so Home never renders a broken blank canvas.
     */
    fun deleteWallpaper(ctx: Context, path: String) {
        try {
            val isDeletingActive = getCurrentWallpaper(ctx) == path
            File(path).delete()
            posterFileFor(path).delete()
            if (isDeletingActive) {
                setCurrentWallpaper(ctx, "default", 0, isVideo = false, sourceUrl = null, dimAlpha = 0f)
            }
        } catch (_: Exception) {}
    }

    fun clearAll(ctx: Context) {
        try {
            getWallpaperDir(ctx).listFiles()?.forEach {
                if (it.name != ".nomedia") it.delete()
            }
            WallpaperMediaCache.clearCache(ctx)
            getPrefs(ctx).edit().clear().apply()
            _stateFlow.value = WallpaperState(
                path = null,
                sourceUrl = null,
                isVideo = false,
                textColor = 0,
                dimAlpha = 0f,
                isTopDark = null,
                isBottomDark = null,
                portraitTransform = WallpaperTransform.Identity,
                landscapeTransform = WallpaperTransform.Identity,
            )
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
