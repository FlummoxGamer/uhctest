package app.harvester.companion.settings.appearance

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.harvester.companion.R
import app.harvester.companion.UHLog
import app.harvester.companion.WallpaperStore
import app.harvester.companion.theme.UHTheme
import app.harvester.companion.wallpaper.WallpaperMediaProcessor
import app.harvester.companion.wallpaper.WallpaperPlaybackController
import app.harvester.companion.wallpaper.WallpaperVideoSurface
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min

class TrimVideoActivity : AppCompatActivity() {

    private data class VideoMeta(
        val durationSec: Float,
        val width: Int,
        val height: Int,
        val isMp4: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        val uriString = intent.getStringExtra("videoUri")
        if (uriString == null) {
            finish()
            return
        }
        val videoUri = Uri.parse(uriString)
        val meta = readVideoMeta(videoUri)
        val durationSec = meta.durationSec

        setContent {
            UHTheme(
                lightStatusBarOverride = false,
                lightNavigationBarOverride = false,
            ) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                val maxClipWindow = min(durationSec, 30f)

                var range by remember { mutableStateOf(0f..min(durationSec, 15f)) }
                var frames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
                var isPlaying by remember { mutableStateOf(true) }
                var currentPlayheadSec by remember { mutableFloatStateOf(0f) }
                var isProcessing by remember { mutableStateOf(false) }
                var processingLabel by remember { mutableStateOf("Preparing Motion Wallpaper") }
                var progressFraction by remember { mutableFloatStateOf(0f) }
                val animatedProgress by animateFloatAsState(targetValue = progressFraction, label = "trim_progress")

                val exoPlayer = remember(videoUri) {
                    WallpaperPlaybackController.createWallpaperExoPlayer(
                        context = context,
                        scalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT,
                    ).apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                UHLog.e("video", "Trim preview error: ${error.message}")
                                Toast.makeText(
                                    context,
                                    "Cannot preview video directly; conversion still supported",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        })
                        setMediaItem(MediaItem.fromUri(videoUri))
                        prepare()
                        play()
                    }
                }

                // Loop playback cleanly inside user's selected trim range
                LaunchedEffect(exoPlayer, range, isPlaying) {
                    while (isActive) {
                        val posSec = exoPlayer.currentPosition / 1000f
                        currentPlayheadSec = posSec
                        if (isPlaying && posSec >= range.endInclusive) {
                            exoPlayer.seekTo((range.start * 1000f).toLong())
                        }
                        delay(80)
                    }
                }

                DisposableEffect(exoPlayer) {
                    onDispose {
                        exoPlayer.stop()
                        exoPlayer.release()
                        FFmpegKitConfig.enableStatisticsCallback(null)
                        FFmpegKitConfig.clearSessions()
                    }
                }

                LaunchedEffect(videoUri) {
                    frames = withContext(Dispatchers.IO) {
                        extractFrames(videoUri, durationSec)
                    }
                }

                fun processAndOpenPreview(useFullVideo: Boolean) {
                    if (isProcessing) return
                    val coversFullClip = useFullVideo ||
                        (range.start <= 0.15f && (durationSec - range.endInclusive) <= 0.25f)

                    val startSec = if (coversFullClip) null else range.start.coerceAtLeast(0f)
                    val clipDuration = if (coversFullClip) null else (range.endInclusive - range.start).coerceIn(1f, 30f)

                    processingLabel = when {
                        coversFullClip && meta.isMp4 -> "Copying lossless MP4..."
                        !coversFullClip && meta.isMp4 -> "Lossless stream-copy trim..."
                        else -> "Converting hardware MP4 (<= 30fps)..."
                    }
                    isProcessing = true
                    progressFraction = 0.08f
                    exoPlayer.pause()

                    scope.launch(Dispatchers.IO) {
                        try {
                            val finalPath = WallpaperStore.saveLocalVideo(
                                ctx = this@TrimVideoActivity,
                                uri = videoUri,
                                startSec = startSec,
                                durationSec = clipDuration,
                                onProgress = { pct -> progressFraction = pct },
                            )

                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                if (finalPath != null) {
                                    startActivity(
                                        Intent(this@TrimVideoActivity, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imagePath", finalPath)
                                            putExtra("isCustom", true)
                                            putExtra("isVideo", true)
                                        },
                                    )
                                    finish()
                                } else {
                                    Toast.makeText(
                                        this@TrimVideoActivity,
                                        "Could not process this video",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            UHLog.e("video", "Trim process exception: ${e.message}")
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                Toast.makeText(
                                    this@TrimVideoActivity,
                                    "Error processing video",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0E)),
                ) {
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black)
                                    .clickable {
                                        isPlaying = !isPlaying
                                        if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                                    },
                            ) {
                                WallpaperVideoSurface(
                                    exoPlayer = exoPlayer,
                                    contentScale = ContentScale.Fit,
                                    capTo30Fps = true,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                PlayPauseBadge(isPlaying = isPlaying, modifier = Modifier.align(Alignment.Center))
                                TopBarControls(
                                    onClose = { finish() },
                                    selectedDurationSec = (range.endInclusive - range.start).coerceAtLeast(1f),
                                    srcMeta = if (meta.width > 0) "${meta.width}x${meta.height}" else "Video",
                                    onTrimAndUse = { processAndOpenPreview(useFullVideo = false) },
                                    isProcessing = isProcessing,
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                )
                            }

                            TrimControlsPanel(
                                range = range,
                                durationSec = durationSec,
                                maxWindow = maxClipWindow,
                                currentPlayheadSec = currentPlayheadSec,
                                frames = frames,
                                isPlaying = isPlaying,
                                isMp4FastPath = meta.isMp4,
                                onTogglePlay = {
                                    isPlaying = !isPlaying
                                    if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                                },
                                onRangeChange = { updated ->
                                    range = updated
                                    exoPlayer.seekTo((updated.start * 1000f).toLong())
                                },
                                onPresetDuration = { sec ->
                                    val end = min(durationSec, range.start + sec)
                                    val start = (end - sec).coerceAtLeast(0f)
                                    range = start..end
                                    exoPlayer.seekTo((start * 1000f).toLong())
                                },
                                onUseFullVideo = { processAndOpenPreview(useFullVideo = true) },
                                isProcessing = isProcessing,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding(),
                        ) {
                            TopBarControls(
                                onClose = { finish() },
                                selectedDurationSec = (range.endInclusive - range.start).coerceAtLeast(1f),
                                srcMeta = if (meta.width > 0) "${meta.width}x${meta.height}" else "Video",
                                onTrimAndUse = { processAndOpenPreview(useFullVideo = false) },
                                isProcessing = isProcessing,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color.Black)
                                    .clickable {
                                        isPlaying = !isPlaying
                                        if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                                    },
                            ) {
                                WallpaperVideoSurface(
                                    exoPlayer = exoPlayer,
                                    contentScale = ContentScale.Fit,
                                    capTo30Fps = true,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                PlayPauseBadge(isPlaying = isPlaying, modifier = Modifier.align(Alignment.Center))
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            TrimControlsPanel(
                                range = range,
                                durationSec = durationSec,
                                maxWindow = maxClipWindow,
                                currentPlayheadSec = currentPlayheadSec,
                                frames = frames,
                                isPlaying = isPlaying,
                                isMp4FastPath = meta.isMp4,
                                onTogglePlay = {
                                    isPlaying = !isPlaying
                                    if (isPlaying) exoPlayer.play() else exoPlayer.pause()
                                },
                                onRangeChange = { updated ->
                                    range = updated
                                    exoPlayer.seekTo((updated.start * 1000f).toLong())
                                },
                                onPresetDuration = { sec ->
                                    val end = min(durationSec, range.start + sec)
                                    val start = (end - sec).coerceAtLeast(0f)
                                    range = start..end
                                    exoPlayer.seekTo((start * 1000f).toLong())
                                },
                                onUseFullVideo = { processAndOpenPreview(useFullVideo = true) },
                                isProcessing = isProcessing,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }

                    // Processing Progress Overlay
                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.72f))
                                .clickable(enabled = false) {},
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = Color(0xFF1A1A24),
                                tonalElevation = 8.dp,
                                modifier = Modifier
                                    .padding(24.dp)
                                    .fillMaxWidth(if (isLandscape) 0.48f else 0.86f),
                            ) {
                                Column(
                                    modifier = Modifier.padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    CircularProgressIndicator(
                                        progress = { animatedProgress.coerceIn(0.08f, 1f) },
                                        modifier = Modifier.size(44.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "Preparing Motion Wallpaper",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "$processingLabel (${(animatedProgress * 100).toInt()}%)",
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontSize = 13.sp,
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    LinearProgressIndicator(
                                        progress = { animatedProgress.coerceIn(0.08f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = Color.White.copy(alpha = 0.14f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun readVideoMeta(uri: Uri): VideoMeta {
        val ext = WallpaperMediaProcessor.guessVideoExtensionFromUri(this, uri)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty().lowercase()
            val isMp4 = mime.contains("mp4") || mime.contains("m4v") || ext == "mp4" || ext == "m4v"
            VideoMeta((durMs / 1000f).coerceAtLeast(1f), w, h, isMp4)
        } catch (_: Exception) {
            VideoMeta(15f, 0, 0, ext == "mp4" || ext == "m4v")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun extractFrames(uri: Uri, duration: Float): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val numFrames = 12
            val interval = (duration / numFrames).coerceAtLeast(0.25f)
            for (i in 0 until numFrames) {
                val timeUs = (i * interval * 1_000_000).toLong()
                val bmp = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 144, 144)
                if (bmp != null) frames.add(bmp)
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return frames
    }
}

@Composable
private fun PlayPauseBadge(isPlaying: Boolean, modifier: Modifier = Modifier) {
    if (!isPlaying) {
        Box(
            modifier = modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.58f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_play_24),
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun TopBarControls(
    onClose: () -> Unit,
    selectedDurationSec: Float,
    srcMeta: String,
    onTrimAndUse: () -> Unit,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.52f)),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_close_24),
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.52f),
        ) {
            Text(
                text = "${"%.1f".format(Locale.US, selectedDurationSec)}s • $srcMeta",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Button(
            enabled = !isProcessing,
            onClick = onTrimAndUse,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_check_24),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Trim & Use", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TrimControlsPanel(
    range: ClosedFloatingPointRange<Float>,
    durationSec: Float,
    maxWindow: Float,
    currentPlayheadSec: Float,
    frames: List<Bitmap>,
    isPlaying: Boolean,
    isMp4FastPath: Boolean,
    onTogglePlay: () -> Unit,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onPresetDuration: (Float) -> Unit,
    onUseFullVideo: () -> Unit,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF15151E),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f)),
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isPlaying) R.drawable.fx_ic_pause_24 else R.drawable.fx_ic_play_24,
                            ),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column {
                        Text(
                            text = "${formatSec(range.start)}  —  ${formatSec(range.endInclusive)}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (isMp4FastPath) {
                                "MP4 lossless fast-path • 30fps playback cap"
                            } else {
                                "Native resolution HW MP4 • Capped at 30fps"
                            },
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            // Frame Strip + Active Trim Overlay + Live Playhead
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF232330)),
            ) {
                if (frames.isNotEmpty()) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        frames.forEach { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val safeDur = durationSec.coerceAtLeast(1f)
                    val startFrac = (range.start / safeDur).coerceIn(0f, 1f)
                    val endFrac = (range.endInclusive / safeDur).coerceIn(startFrac, 1f)
                    val playFrac = (currentPlayheadSec / safeDur).coerceIn(0f, 1f)

                    val startX = size.width * startFrac
                    val endX = size.width * endFrac
                    val playX = size.width * playFrac

                    if (startX > 0f) {
                        drawRect(Color.Black.copy(alpha = 0.62f), Offset.Zero, Size(startX, size.height))
                    }
                    if (endX < size.width) {
                        drawRect(
                            Color.Black.copy(alpha = 0.62f),
                            Offset(endX, 0f),
                            Size(size.width - endX, size.height),
                        )
                    }

                    drawRect(
                        color = primary,
                        topLeft = Offset(startX, 0f),
                        size = Size((endX - startX).coerceAtLeast(4f), size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                    )

                    drawLine(
                        color = Color.White,
                        start = Offset(playX, 0f),
                        end = Offset(playX, size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }

            RangeSlider(
                value = range,
                onValueChange = { updated ->
                    var s = updated.start
                    var e = updated.endInclusive
                    if (e - s < 1f) e = min(durationSec, s + 1f)
                    if (e - s > maxWindow) e = s + maxWindow
                    if (e > durationSec) {
                        e = durationSec
                        s = (e - maxWindow).coerceAtLeast(0f)
                    }
                    onRangeChange(s..e)
                },
                valueRange = 0f..durationSec,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            )

            // Quick Loop Length Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5f to "5s", 10f to "10s", 15f to "15s", 30f to "30s").forEach { (sec, label) ->
                    val enabled = durationSec >= sec - 0.5f || label == "5s"
                    val isSelected = kotlin.math.abs((range.endInclusive - range.start) - min(durationSec, sec)) < 0.8f
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) primary.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = enabled) { onPresetDuration(sec) },
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) primary else Color.White.copy(alpha = if (enabled) 0.9f else 0.35f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // One-tap bypass to skip trimming and import the full video losslessly
            OutlinedButton(
                enabled = !isProcessing,
                onClick = onUseFullVideo,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (isMp4FastPath) {
                        "Use Full Video (${formatSec(durationSec)} • Instant)"
                    } else {
                        "Use Full Video (${formatSec(durationSec)})"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun formatSec(sec: Float): String {
    val totalTenths = (sec * 10).toInt().coerceAtLeast(0)
    val mins = (totalTenths / 10) / 60
    val remSec = (totalTenths / 10) % 60
    val tenths = totalTenths % 10
    return String.format(Locale.US, "%d:%02d.%d", mins, remSec, tenths)
}
