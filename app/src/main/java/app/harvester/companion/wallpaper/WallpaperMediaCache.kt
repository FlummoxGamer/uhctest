package app.harvester.companion.wallpaper

import android.content.Context
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-flight remote wallpaper cache so assets loaded in Preview are promoted
 * straight into wallpaper storage on Apply with zero redundant re-downloads.
 */
object WallpaperMediaCache {

    private const val CACHE_DIR_NAME = "wallpaper_remote_cache"
    private const val MAX_CACHE_BYTES = 180L * 1024L * 1024L // 180 MB cap for preview cache

    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun urlHash(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(WallpaperCatalog.canonicalSourceKey(url).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun getCachedVideoFile(ctx: Context, url: String): File? {
        val file = File(cacheDir(ctx), "vid_${urlHash(url)}.mp4")
        return if (file.exists() && file.length() > 1024L) file else null
    }

    /**
     * Starts (or joins) a single-flight background download for a remote motion wallpaper
     * while the user is previewing it.
     */
    fun prefetchVideoAsync(ctx: Context, url: String) {
        val appCtx = ctx.applicationContext
        if (getCachedVideoFile(appCtx, url) != null) return
        cacheScope.launch {
            getOrDownloadVideo(appCtx, url)
        }
    }

    /**
     * Returns a complete local cached video file, joining any in-flight preview prefetch
     * instead of starting a duplicate download.
     */
    suspend fun getOrDownloadVideo(ctx: Context, url: String): File? = withContext(Dispatchers.IO) {
        val appCtx = ctx.applicationContext
        getCachedVideoFile(appCtx, url)?.let { existing ->
            existing.setLastModified(System.currentTimeMillis())
            return@withContext existing
        }

        val key = "vid_${urlHash(url)}"
        val existingDeferred = inFlight[key]
        if (existingDeferred != null) {
            return@withContext existingDeferred.await()
        }

        val deferred = CompletableDeferred<File?>()
        val raced = inFlight.putIfAbsent(key, deferred)
        if (raced != null) {
            return@withContext raced.await()
        }

        try {
            val dest = File(cacheDir(appCtx), "$key.mp4")
            val part = File(cacheDir(appCtx), "$key.mp4.part")
            val ok = downloadToFile(url, part)
            val result = if (ok && part.exists() && part.length() > 1024L) {
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                trimCacheIfNeeded(appCtx)
                dest
            } else {
                part.delete()
                null
            }
            deferred.complete(result)
            result
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "Remote video cache failed: ${e.message}")
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /**
     * Attempts to export an already-cached remote image directly from Coil's disk cache
     * (zero re-download, original bytes preserved verbatim).
     */
    @OptIn(ExperimentalCoilApi::class)
    fun tryExportCoilCachedImage(ctx: Context, url: String, destFile: File): Boolean {
        return try {
            val diskCache = ctx.applicationContext.imageLoader.diskCache ?: return false
            val snapshot = diskCache.openSnapshot(url) ?: return false
            snapshot.use { snap ->
                val src = snap.data.toFile()
                if (src.exists() && src.length() > 128L) {
                    src.copyTo(destFile, overwrite = true)
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun downloadToFile(url: String, destFile: File): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            UhHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    UHLog.w("wallpaper_warn", "Download HTTP ${response.code} for $url")
                    return false
                }
                val body = response.body ?: return false
                destFile.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            destFile.exists() && destFile.length() > 64L
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "downloadToFile failed: ${e.message}")
            false
        }
    }

    private fun trimCacheIfNeeded(ctx: Context) {
        try {
            val files = cacheDir(ctx).listFiles { f -> f.isFile && !f.name.endsWith(".part") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= MAX_CACHE_BYTES * 8 / 10) return@forEach
                val size = file.length()
                if (file.delete()) total -= size
            }
        } catch (_: Exception) {}
    }

    fun clearCache(ctx: Context) {
        try {
            cacheDir(ctx).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }
}
