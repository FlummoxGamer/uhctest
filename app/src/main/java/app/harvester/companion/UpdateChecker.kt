package app.harvester.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import app.harvester.companion.core.network.UhHttpClient
import app.harvester.companion.settings.UpdaterActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object UpdateChecker {

    data class UpdateManifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkUrlArm64: String,
        val apkUrlArm32: String,
        val changelog: String,
        val isDevChannel: Boolean
    )

    private fun manifestUrl(): Pair<String, Boolean> {
        val isDev = BuildConfig.VERSION_NAME.contains("-dev")
        val branch = if (isDev) "builds-dev" else "builds"
        val file = if (isDev) "version-dev.json" else "version.json"
        return "https://gh-proxy.com/https://raw.githubusercontent.com/FlummoxGamer/universal-harvester-companion/$branch/$file" to isDev
    }

    private fun requireHttpsUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val uri = Uri.parse(trimmed)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("Update URL must use HTTPS")
        }
        return trimmed
    }

    fun resolveApkUrlForDevice(manifest: UpdateManifest): String {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        val candidate = when {
            abis.any { it.equals("arm64-v8a", ignoreCase = true) } ->
                manifest.apkUrlArm64.ifBlank { manifest.apkUrl }
            abis.any { it.equals("armeabi-v7a", ignoreCase = true) || it.equals("armeabi", ignoreCase = true) } ->
                manifest.apkUrlArm32.ifBlank { manifest.apkUrl }
            else -> manifest.apkUrl
        }
        return candidate.ifBlank {
            manifest.apkUrlArm64.ifBlank { manifest.apkUrlArm32 }
        }
    }

    fun cleanupDownloadedUpdates(context: Context) {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) return
            updatesDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    fun openUpdater(activity: Activity) {
        activity.startActivity(Intent(activity, UpdaterActivity::class.java))
    }

    suspend fun fetchLatestManifest(): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        try {
            val (url, isDev) = manifestUrl()
            val req = Request.Builder()
                .url(requireHttpsUrl(url))
                .header("User-Agent", "${UHBrand.NAME}-Updater/${BuildConfig.VERSION_NAME} (Android)")
                .build()
            UhHttpClient.secureClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val o = JSONObject(body)
                val vc = o.optInt("versionCode", 0)
                val vn = o.optString("versionName", "")
                val apkUrl = o.optString("apkUrl", "")
                val apkUrlArm64 = o.optString("apkUrlArm64", "")
                val apkUrlArm32 = o.optString("apkUrlArm32", "")
                val changelog = o.optString("changelog", "")
                val manifest = UpdateManifest(vc, vn, apkUrl, apkUrlArm64, apkUrlArm32, changelog, isDev)
                val resolvedUrl = resolveApkUrlForDevice(manifest)
                if (vc <= 0 || resolvedUrl.isBlank()) throw IllegalStateException("Invalid manifest payload")
                requireHttpsUrl(resolvedUrl)
                Result.success(manifest)
            }
        } catch (e: Exception) {
            UHLog.throwable("update", "manifest fetch failed", e)
            Result.failure(e)
        }
    }

    suspend fun downloadApk(
        activity: Activity,
        manifest: UpdateManifest,
        onProgress: (downloadedBytes: Long, totalBytes: Long, bytesPerSec: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(activity.cacheDir, "updates").apply { mkdirs() }
            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && old.name.endsWith(".apk")) old.delete()
            }
            val downloadUrl = requireHttpsUrl(resolveApkUrlForDevice(manifest))
            val safeVersion = manifest.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outFile = File(updatesDir, "uh_update_${safeVersion}.apk")
            val req = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "${UHBrand.NAME}-Updater/${BuildConfig.VERSION_NAME} (Android)")
                .build()

            UhHttpClient.secureClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("Empty response body")
                val total = body.contentLength()
                var downloaded = 0L
                var windowBytes = 0L
                var lastReportAt = System.currentTimeMillis()

                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            windowBytes += n
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt >= 200L) {
                                val elapsedMs = (now - lastReportAt).coerceAtLeast(1L)
                                val bps = (windowBytes * 1000L) / elapsedMs
                                windowBytes = 0L
                                lastReportAt = now
                                withContext(Dispatchers.Main) {
                                    onProgress(downloaded, total, bps)
                                }
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    onProgress(downloaded, total.coerceAtLeast(downloaded), 0L)
                }
            }
            if (!outFile.exists() || outFile.length() == 0L) {
                throw IllegalStateException("Downloaded APK is empty")
            }
            Result.success(outFile)
        } catch (e: Exception) {
            UHLog.throwable("update", "APK download failed", e)
            Result.failure(e)
        }
    }

    fun resumePendingInstallIfPossible(activity: Activity) {
        val path = DebugPrefs.pendingApk(activity) ?: return
        val apk = File(path)
        if (!apk.exists() || apk.length() == 0L) {
            DebugPrefs.setPendingApk(activity, null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            return
        }
        DebugPrefs.setPendingApk(activity, null)
        installApk(activity, apk)
    }

    fun installApk(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            DebugPrefs.setPendingApk(activity, apk.absolutePath)
            AlertDialog.Builder(activity)
                .setTitle("Permission needed")
                .setMessage(
                    "Allow ${UHBrand.NAME} to install unknown apps. After enabling it in Settings, return here and the installation will continue."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    val i = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(i)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    DebugPrefs.setPendingApk(activity, null)
                }
                .show()
            return
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
            UHLog.d("update", "handed APK to installer")
        } catch (e: Exception) {
            UHLog.throwable("update", "installer launch failed", e)
            AlertDialog.Builder(activity)
                .setTitle("Install failed")
                .setMessage(e.message ?: "unknown error")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
