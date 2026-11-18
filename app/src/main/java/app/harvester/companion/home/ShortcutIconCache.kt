package app.harvester.companion.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object ShortcutIconCache {
    private const val DIR_NAME = "shortcut_icons"
    private const val TTL_MS = 24L * 60L * 60L * 1000L // 24 hours

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val _versionTick = MutableStateFlow(0)
    val versionTick: StateFlow<Int> = _versionTick.asStateFlow()

    private val fastClient by lazy {
        UhHttpClient.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.applicationContext.cacheDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun keyFor(pageUrl: String): String {
        val normalized = normalizeHostOrUrl(pageUrl)
        val md = MessageDigest.getInstance("MD5")
        return md.digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun normalizeHostOrUrl(raw: String): String {
        return try {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: raw.trim().lowercase()
            val path = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: ""
            "$scheme://$host$path"
        } catch (_: Exception) {
            raw.trim().lowercase()
        }
    }

    private fun iconFile(ctx: Context, key: String): File = File(cacheDir(ctx), "icon_$key.png")
    private fun metaFile(ctx: Context, key: String): File = File(cacheDir(ctx), "icon_$key.json")

    fun getImmediateCachedFile(ctx: Context, pageUrl: String): File? {
        if (pageUrl.isBlank()) return null
        val key = keyFor(pageUrl)
        val f = iconFile(ctx, key)
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun invalidate(ctx: Context, pageUrl: String) {
        if (pageUrl.isBlank()) return
        val key = keyFor(pageUrl)
        runCatching { iconFile(ctx, key).delete() }
        runCatching { metaFile(ctx, key).delete() }
        _versionTick.value += 1
    }

    fun clearAll(ctx: Context) {
        runCatching {
            cacheDir(ctx).listFiles()?.forEach { it.delete() }
            _versionTick.value += 1
        }
    }

    suspend fun resolveIcon(ctx: Context, pageUrl: String, explicitIconUrl: String? = null): File? {
        if (pageUrl.isBlank()) return null
        val appCtx = ctx.applicationContext
        val key = keyFor(pageUrl)
        val lock = mutexes.getOrPut(key) { Mutex() }

        return lock.withLock {
            withContext(Dispatchers.IO) {
                val file = iconFile(appCtx, key)
                val meta = metaFile(appCtx, key)
                val metaJson = readMeta(meta)
                val fetchedAt = metaJson?.optLong("fetchedAt", 0L) ?: 0L
                val now = System.currentTimeMillis()

                // 1. Fresh cache (< 24h) -> instant return
                if (file.exists() && file.length() > 0L && (now - fetchedAt) < TTL_MS) {
                    return@withContext file
                }

                // 2. Stale cache (>= 24h) -> conditional ETag / Last-Modified / SHA-256 check
                if (file.exists() && file.length() > 0L && metaJson != null) {
                    val sourceUrl = metaJson.optString("sourceUrl")
                    val etag = metaJson.optString("etag")
                    val lastMod = metaJson.optString("lastModified")
                    val prevHash = metaJson.optString("sha256")
                    if (sourceUrl.isNotBlank()) {
                        val refreshed = checkConditionalUpdate(
                            sourceUrl = sourceUrl,
                            etag = etag,
                            lastMod = lastMod,
                            prevHash = prevHash,
                            destFile = file,
                            metaFile = meta
                        )
                        if (refreshed) {
                            return@withContext file
                        }
                    }
                }

                // 3. No valid cache (or source changed / failed conditional) -> multi-source resolve
                val candidates = buildCandidateUrls(pageUrl, explicitIconUrl)
                for (candidate in candidates) {
                    val downloaded = fetchAndStoreBitmap(candidate, file, meta)
                    if (downloaded != null) {
                        _versionTick.value += 1
                        return@withContext downloaded
                    }
                }

                // Fallback to stale cached file if network unavailable
                if (file.exists() && file.length() > 0L) file else null
            }
        }
    }

    private fun checkConditionalUpdate(
        sourceUrl: String,
        etag: String,
        lastMod: String,
        prevHash: String,
        destFile: File,
        metaFile: File
    ): Boolean {
        return try {
            val reqBuilder = Request.Builder()
                .url(sourceUrl)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
            if (etag.isNotBlank()) reqBuilder.header("If-None-Match", etag)
            if (lastMod.isNotBlank()) reqBuilder.header("If-Modified-Since", lastMod)

            fastClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 304) {
                    writeMeta(
                        metaFile,
                        sourceUrl = sourceUrl,
                        etag = resp.header("ETag") ?: etag,
                        lastMod = resp.header("Last-Modified") ?: lastMod,
                        sha256 = prevHash
                    )
                    return true
                }
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: return false
                    val newHash = sha256(bytes)
                    if (newHash == prevHash) {
                        writeMeta(
                            metaFile,
                            sourceUrl = sourceUrl,
                            etag = resp.header("ETag") ?: etag,
                            lastMod = resp.header("Last-Modified") ?: lastMod,
                            sha256 = prevHash
                        )
                        return true
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
                    if (bmp.width >= 16 && bmp.height >= 16) {
                        writePngAtomic(bmp, destFile)
                        bmp.recycle()
                        writeMeta(
                            metaFile,
                            sourceUrl = sourceUrl,
                            etag = resp.header("ETag").orEmpty(),
                            lastMod = resp.header("Last-Modified").orEmpty(),
                            sha256 = newHash
                        )
                        _versionTick.value += 1
                        return true
                    } else {
                        bmp.recycle()
                    }
                }
                false
            }
        } catch (_: Exception) {
            // Offline or transient failure: keep stale cache valid for now rather than blocking
            destFile.exists() && destFile.length() > 0L
        }
    }

    private fun buildCandidateUrls(pageUrl: String, explicitIconUrl: String?): List<String> {
        val list = linkedSetOf<String>()
        val uri = try { URI(pageUrl.trim()) } catch (_: Exception) { null }
        val scheme = uri?.scheme?.takeIf { it == "http" || it == "https" } ?: "https"
        val host = uri?.host?.takeIf { it.isNotBlank() }
        val cleanHost = host?.removePrefix("www.")

        // Discover <link rel="icon"> / apple-touch-icon directly from HTML head (fast partial read)
        if (host != null) {
            discoverHtmlIconUrls(pageUrl, scheme, host).forEach { list.add(it) }
            list.add("$scheme://$host/apple-touch-icon.png")
            list.add("$scheme://$host/apple-touch-icon-precomposed.png")
            list.add("$scheme://$host/favicon.png")
            list.add("$scheme://$host/favicon.ico")
        }

        if (!explicitIconUrl.isNullOrBlank() && explicitIconUrl.startsWith("http")) {
            list.add(explicitIconUrl)
        }

        if (!cleanHost.isNullOrBlank()) {
            list.add("https://www.google.com/s2/favicons?domain=$cleanHost&sz=128")
            list.add("https://icons.duckduckgo.com/ip3/$cleanHost.ico")
        }

        return list.toList()
    }

    private fun discoverHtmlIconUrls(pageUrl: String, scheme: String, host: String): List<String> {
        val found = mutableListOf<String>()
        try {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .header("Range", "bytes=0-24576")
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val ctype = resp.header("Content-Type").orEmpty()
                if (!ctype.contains("html", ignoreCase = true) && ctype.isNotBlank()) return emptyList()
                val body = resp.body ?: return emptyList()
                val buf = ByteArray(24576)
                val read = body.byteStream().read(buf)
                if (read <= 0) return emptyList()
                val snippet = String(buf, 0, read, Charsets.UTF_8)

                val matcher = LINK_TAG_PATTERN.matcher(snippet)
                val baseUri = URI("$scheme://$host")
                while (matcher.find()) {
                    val tag = matcher.group() ?: continue
                    val relMatch = REL_PATTERN.matcher(tag)
                    val rel = if (relMatch.find()) relMatch.group(1)?.lowercase() ?: "" else ""
                    if (rel.contains("icon")) {
                        val hrefMatch = HREF_PATTERN.matcher(tag)
                        val href = if (hrefMatch.find()) hrefMatch.group(1)?.trim() else null
                        if (!href.isNullOrBlank() && !href.startsWith("data:", ignoreCase = true)) {
                            val resolved = runCatching { baseUri.resolve(href).toString() }.getOrNull()
                            if (resolved != null && !resolved.endsWith(".svg", ignoreCase = true)) {
                                // Prefer larger / apple-touch-icons at the front
                                if (rel.contains("apple-touch-icon")) found.add(0, resolved)
                                else found.add(resolved)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return found.take(3)
    }

    private fun fetchAndStoreBitmap(url: String, destFile: File, metaFile: File): File? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.size < 32) return null
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                // Filter out 1x1 tracking pixels or broken empty icons
                if (bmp.width < 16 || bmp.height < 16) {
                    bmp.recycle()
                    return null
                }
                writePngAtomic(bmp, destFile)
                bmp.recycle()
                writeMeta(
                    metaFile = metaFile,
                    sourceUrl = url,
                    etag = resp.header("ETag").orEmpty(),
                    lastMod = resp.header("Last-Modified").orEmpty(),
                    sha256 = sha256(bytes)
                )
                destFile
            }
        } catch (e: Exception) {
            UHLog.w("ui_warn", "Icon fetch failed ($url): ${e.message}")
            null
        }
    }

    private fun writePngAtomic(bmp: Bitmap, destFile: File) {
        val tmp = File(destFile.parentFile, "${destFile.name}.tmp")
        tmp.outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (!tmp.renameTo(destFile)) {
            tmp.copyTo(destFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun readMeta(metaFile: File): JSONObject? {
        if (!metaFile.exists()) return null
        return runCatching { JSONObject(metaFile.readText()) }.getOrNull()
    }

    private fun writeMeta(
        metaFile: File,
        sourceUrl: String,
        etag: String,
        lastMod: String,
        sha256: String
    ) {
        val json = JSONObject().apply {
            put("fetchedAt", System.currentTimeMillis())
            put("sourceUrl", sourceUrl)
            put("etag", etag)
            put("lastModified", lastMod)
            put("sha256", sha256)
        }
        runCatching { metaFile.writeText(json.toString()) }
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val LINK_TAG_PATTERN = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE)
    private val REL_PATTERN = Pattern.compile("rel\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val HREF_PATTERN = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
}
