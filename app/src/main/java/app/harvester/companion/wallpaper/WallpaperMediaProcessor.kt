package app.harvester.companion.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import app.harvester.companion.UHLog
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * High-speed, zero-loss media pipeline for static and motion wallpapers:
 * - Static images: verbatim original file copy (zero re-compression, zero artificial downscale).
 * - Full MP4 videos: completely skips FFmpeg (instant single-pass file copy, 100% original quality).
 * - Trimmed MP4 videos: lossless FFmpeg stream-copy (`-c:v copy`) completing in < 1s.
 * - Non-MP4 / incompatible codecs: fast hardware MediaCodec encode at native resolution,
 *   resolution-adaptive high bitrate, and capped to <= 30fps for smooth performance.
 */
object WallpaperMediaProcessor {

    data class VideoProbe(
        val width: Int,
        val height: Int,
        val durationSec: Float,
        val fps: Float,
        val bitrateBps: Long,
        val isMp4Container: Boolean,
        val hasHardwarePlayableVideoTrack: Boolean,
    ) {
        val maxDim: Int get() = max(width, height)
    }

    /**
     * Lossless image import: copies original bytes verbatim while preserving native extension,
     * verifying decodability with a lightweight header-only bounds pass.
     */
    fun importLocalImageLossless(ctx: Context, uri: Uri, destDir: File): File? {
        return try {
            val ext = guessImageExtensionFromUri(ctx, uri)
            val tmpFile = File(destDir, "wall_${System.currentTimeMillis()}.tmp")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            if (!isValidImageFile(tmpFile)) {
                tmpFile.delete()
                return null
            }

            val detectedExt = detectImageExtensionFromMagicBytes(tmpFile) ?: ext
            val destFile = File(destDir, "wall_${System.currentTimeMillis()}.$detectedExt")
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            destFile.takeIf { it.exists() && it.length() > 64L }
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "importLocalImageLossless failed: ${e.message}")
            null
        }
    }

    /**
     * Moves or copies an already-downloaded remote image without any re-compression.
     */
    fun finalizeRemoteImageLossless(
        srcFile: File,
        destDir: File,
        hash: String,
        contentType: String = "",
        url: String = "",
    ): File? {
        if (!isValidImageFile(srcFile)) return null
        val ext = detectImageExtensionFromMagicBytes(srcFile)
            ?: guessImageExtensionFromMeta(contentType, url)
        val destFile = File(destDir, "wall_$hash.$ext")
        if (destFile.exists()) destFile.delete()
        if (!srcFile.renameTo(destFile)) {
            srcFile.copyTo(destFile, overwrite = true)
            srcFile.delete()
        }
        return destFile.takeIf { it.exists() && it.length() > 64L }
    }

    /**
     * Single-pass local video import from a gallery content Uri:
     * - Full MP4: streams directly once into wallpapers dir (0 temp copies, 0 FFmpeg).
     * - Trimmed MP4: instant lossless stream-copy trim (`-c:v copy`).
     * - Non-MP4: hardware MediaCodec high-bitrate conversion capped at <= 30fps.
     */
    fun importLocalVideo(
        ctx: Context,
        uri: Uri,
        destDir: File,
        startSec: Float? = null,
        durationSec: Float? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): File? {
        val isTrimming = startSec != null && durationSec != null
        val ext = guessVideoExtensionFromUri(ctx, uri)
        val destFile = File(destDir, "motion_${System.currentTimeMillis()}.mp4")

        // Fast path: Full clip that is already an MP4 — stream directly to final destination once!
        if (!isTrimming && (ext == "mp4" || ext == "m4v")) {
            onProgress?.invoke(0.15f)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return null

                if (destFile.exists() && destFile.length() > 1024L) {
                    val probe = probeVideo(destFile.absolutePath)
                    if (probe.isMp4Container && probe.hasHardwarePlayableVideoTrack) {
                        onProgress?.invoke(1f)
                        return destFile
                    }
                    // Rare case: .mp4 extension with unsupported codec — convert cleanly from destFile
                    val converted = File(destDir, "motion_${System.currentTimeMillis()}_conv.mp4")
                    val ok = prepareMotionVideo(ctx, destFile, converted, null, null, onProgress)
                    destFile.delete()
                    if (ok && converted.exists() && converted.length() > 1024L) {
                        return converted
                    }
                    converted.delete()
                    return null
                }
            } catch (e: Exception) {
                UHLog.e("wallpaper_error", "Direct MP4 import failed: ${e.message}")
                destFile.delete()
                return null
            }
        }

        // Trimming or non-MP4 source: single temp input -> direct write into destFile
        val rawInput = File(ctx.cacheDir, "motion_raw_${System.currentTimeMillis()}.$ext")
        return try {
            onProgress?.invoke(0.08f)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                rawInput.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            val ok = prepareMotionVideo(
                ctx = ctx,
                inputFile = rawInput,
                outputFile = destFile,
                startSec = startSec,
                durationSec = durationSec,
                onProgress = onProgress,
            )
            if (ok && destFile.exists() && destFile.length() > 1024L) {
                destFile
            } else {
                destFile.delete()
                null
            }
        } catch (e: Exception) {
            UHLog.e("wallpaper_error", "importLocalVideo failed: ${e.message}")
            destFile.delete()
            null
        } finally {
            rawInput.delete()
        }
    }

    /**
     * Prepares a motion video file into [outputFile]:
     * 1. Already-MP4 + full clip -> instant lossless copy (skips FFmpeg completely).
     * 2. Already-MP4 + trimmed -> instant lossless stream-copy trim (`-c:v copy`).
     * 3. Non-MP4 / incompatible codec -> hardware MediaCodec high-bitrate encode at native
     *    resolution, capped at <= 30fps.
     */
    fun prepareMotionVideo(
        ctx: Context,
        inputFile: File,
        outputFile: File,
        startSec: Float? = null,
        durationSec: Float? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Boolean {
        if (!inputFile.exists() || inputFile.length() == 0L) return false
        val probe = probeVideo(inputFile.absolutePath)
        val isTrimming = startSec != null && durationSec != null

        // Path 1: Already MP4 and full clip -> skip FFmpeg completely (zero quality loss, instant)
        if (!isTrimming && probe.isMp4Container && probe.hasHardwarePlayableVideoTrack) {
            return try {
                if (inputFile.absolutePath != outputFile.absolutePath) {
                    inputFile.copyTo(outputFile, overwrite = true)
                }
                onProgress?.invoke(1f)
                outputFile.exists() && outputFile.length() > 1024L
            } catch (e: Exception) {
                UHLog.e("wallpaper_error", "Instant MP4 copy failed: ${e.message}")
                false
            }
        }

        // Path 2: Already MP4 and trimmed -> instant lossless stream-copy trim (< 1s, zero quality loss)
        if (isTrimming && probe.isMp4Container && probe.hasHardwarePlayableVideoTrack) {
            val startStr = "%.2f".format(Locale.US, startSec!!.coerceAtLeast(0f))
            val durStr = "%.2f".format(Locale.US, durationSec!!.coerceAtLeast(0.5f))
            val streamCopyCmd =
                "-y -ss $startStr -i \"${inputFile.absolutePath}\" -t $durStr " +
                    "-c:v copy -an -movflags +faststart \"${outputFile.absolutePath}\""
            val copySession = FFmpegKit.execute(streamCopyCmd)
            if (ReturnCode.isSuccess(copySession.returnCode) && outputFile.exists() && outputFile.length() > 1024L) {
                onProgress?.invoke(1f)
                return true
            }
            UHLog.w("video_warn", "Lossless stream-copy trim fell back to hardware encode.")
            if (outputFile.exists()) outputFile.delete()
        }

        // Path 3: Non-MP4 or unsupported container/codec -> fast hardware encode at native screen-max
        // quality (no 2560px cap, no 6Mbps crush), capped at <= 30fps for smooth performance.
        val srcDurationMs = ((durationSec ?: probe.durationSec) * 1000f).coerceAtLeast(1000f)
        if (onProgress != null) {
            FFmpegKitConfig.enableStatisticsCallback { stats ->
                val pct = (stats.time.toFloat() / srcDurationMs).coerceIn(0.08f, 0.98f)
                onProgress(pct)
            }
        }

        val trimArgs = if (isTrimming) {
            "-ss ${"%.2f".format(Locale.US, startSec!!.coerceAtLeast(0f))} " +
                "-t ${"%.2f".format(Locale.US, durationSec!!.coerceAtLeast(0.5f))} "
        } else ""

        // Keep full native resolution (only ensure even dimensions); only cap fps if > 31fps
        val filterParts = mutableListOf("scale=trunc(iw/2)*2:trunc(ih/2)*2")
        if (probe.fps > 31f) {
            filterParts.add("fps=30")
        }
        val vf = filterParts.joinToString(",")

        val targetBitrateKbps = computeHighQualityBitrateKbps(probe)
        val codecs = listOf(
            "-c:v h264_mediacodec -b:v ${targetBitrateKbps}k -pix_fmt yuv420p",
            "-c:v libopenh264 -b:v ${targetBitrateKbps}k -pix_fmt yuv420p",
            "-c:v mpeg4 -q:v 1 -pix_fmt yuv420p",
        )

        try {
            for (codecArgs in codecs) {
                if (outputFile.exists()) outputFile.delete()
                val cmd =
                    "-y $trimArgs-i \"${inputFile.absolutePath}\" -vf \"$vf\" $codecArgs " +
                        "-an -movflags +faststart \"${outputFile.absolutePath}\""
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

    /**
     * Resolution-adaptive high bitrate so fallback encoding never crushes visual clarity.
     */
    private fun computeHighQualityBitrateKbps(probe: VideoProbe): Int {
        val srcKbps = if (probe.bitrateBps > 0L) (probe.bitrateBps / 1000L).toInt() else 0
        val minForRes = when {
            probe.maxDim >= 3000 -> 24_000
            probe.maxDim >= 2000 -> 18_000
            probe.maxDim >= 1400 -> 14_000
            else -> 10_000
        }
        return max(srcKbps, minForRes).coerceIn(10_000, 36_000)
    }

    fun probeVideo(path: String): VideoProbe {
        var width = 0
        var height = 0
        var durationSec = 15f
        var fps = 30f
        var bitrate = 0L
        var isMp4 = false
        var playableTrack = false

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15_000L
            durationSec = (durMs / 1000f).coerceAtLeast(1f)
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty().lowercase()
            isMp4 = mime.contains("mp4") || mime.contains("m4v") ||
                path.endsWith(".mp4", ignoreCase = true) || hasMp4FtypHeader(File(path))
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        // Inspect actual video track codec & frame rate via MediaExtractor (far more accurate than CAPTURE_FRAMERATE)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
                if (trackMime.startsWith("video/")) {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val extractedFps = try {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        } catch (_: Exception) {
                            try { format.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { fps }
                        }
                        if (extractedFps > 0f) fps = extractedFps
                    }
                    if (width == 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (height == 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    playableTrack = trackMime in setOf(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        MediaFormat.MIMETYPE_VIDEO_HEVC,
                        MediaFormat.MIMETYPE_VIDEO_VP9,
                        MediaFormat.MIMETYPE_VIDEO_VP8,
                        "video/av01",
                        MediaFormat.MIMETYPE_VIDEO_MPEG4,
                    )
                    break
                }
            }
        } catch (_: Exception) {
            if (isMp4 && width > 0 && height > 0) playableTrack = true
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        return VideoProbe(
            width = width,
            height = height,
            durationSec = durationSec,
            fps = fps,
            bitrateBps = bitrate,
            isMp4Container = isMp4,
            hasHardwarePlayableVideoTrack = playableTrack,
        )
    }

    fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() < 64L) return false
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }

    fun hasMp4FtypHeader(file: File): Boolean {
        return try {
            val header = ByteArray(12)
            val count = file.inputStream().use { it.read(header) }
            count >= 8 &&
                header[4] == 'f'.code.toByte() &&
                header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() &&
                header[7] == 'p'.code.toByte()
        } catch (_: Exception) {
            false
        }
    }

    fun hasVideoMagicBytes(file: File): Boolean {
        return try {
            val header = ByteArray(16)
            val count = file.inputStream().use { it.read(header) }
            if (count < 12) return false
            // ISO BMFF MP4/MOV ('ftyp' at offset 4) — exclude HEIF/AVIF still images!
            if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            ) {
                val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
                if (brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")) {
                    return false
                }
                return true
            }
            // Matroska / WebM EBML header
            header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
        } catch (_: Exception) {
            false
        }
    }

    fun detectImageExtensionFromMagicBytes(file: File): String? {
        return try {
            val h = ByteArray(16)
            val n = file.inputStream().use { it.read(h) }
            if (n < 12) return null
            when {
                h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte() -> "jpg"
                h[0] == 0x89.toByte() && h[1] == 0x50.toByte() && h[2] == 0x4E.toByte() && h[3] == 0x47.toByte() -> "png"
                h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte() &&
                    h[8] == 'W'.code.toByte() && h[9] == 'E'.code.toByte() && h[10] == 'B'.code.toByte() && h[11] == 'P'.code.toByte() -> "webp"
                h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() && h[2] == 'F'.code.toByte() -> "gif"
                h[4] == 'f'.code.toByte() && h[5] == 't'.code.toByte() && h[6] == 'y'.code.toByte() && h[7] == 'p'.code.toByte() -> {
                    val brand = String(h, 8, 4, Charsets.US_ASCII).lowercase()
                    when {
                        brand.startsWith("avif") || brand.startsWith("avis") -> "avif"
                        brand.startsWith("hei") || brand == "mif1" || brand == "msf1" -> "heic"
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun guessImageExtensionFromUri(ctx: Context, uri: Uri): String {
        val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
        return guessImageExtensionFromMeta(mime, uri.lastPathSegment.orEmpty())
    }

    fun guessImageExtensionFromMeta(contentType: String, urlOrPath: String): String {
        val mime = contentType.lowercase()
        return when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("avif") -> "avif"
            mime.contains("heic") || mime.contains("heif") -> "heic"
            mime.contains("gif") -> "gif"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            else -> {
                val ext = urlOrPath.substringBefore('?').substringAfterLast('.', "").lowercase()
                if (ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif")) {
                    if (ext == "jpeg") "jpg" else ext
                } else "jpg"
            }
        }
    }

    fun guessVideoExtensionFromUri(ctx: Context, uri: Uri): String {
        val mime = ctx.contentResolver.getType(uri).orEmpty().lowercase()
        return when {
            mime.contains("mp4") -> "mp4"
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

    @Suppress("DEPRECATION")
    fun webpPosterFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    /**
     * Extracts full-resolution first frame for crisp instant display before playback starts
     * and when Battery Saver pauses motion playback.
     */
    fun generateVideoPosterBitmap(videoPath: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
