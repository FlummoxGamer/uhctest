package app.harvester.companion.settings.appearance

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.harvester.companion.R
import app.harvester.companion.UHLog
import app.harvester.companion.WallpaperStore
import app.harvester.companion.theme.UHTheme
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

class TrimVideoActivity : AppCompatActivity() {

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

        val retriever = MediaMetadataRetriever()
        val durationSec = try {
            retriever.setDataSource(this, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (durationMs / 1000f).coerceAtLeast(1f)
        } catch (_: Exception) {
            15f
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        setContent {
            UHTheme(
                lightStatusBarOverride = false,
                lightNavigationBarOverride = false
            ) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var range by remember { mutableStateOf(0f..min(durationSec, 15f)) }
                var frames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
                var isCompressing by remember { mutableStateOf(false) }

                val exoPlayer = remember(videoUri) {
                    val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                    ExoPlayer.Builder(context, renderersFactory).build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                UHLog.e("video", "Trim preview error: ${error.message}")
                                Toast.makeText(context, "Cannot play this video format", Toast.LENGTH_SHORT).show()
                            }
                        })
                        setMediaItem(MediaItem.fromUri(videoUri))
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ONE
                        prepare()
                        play()
                    }
                }

                DisposableEffect(exoPlayer) {
                    onDispose {
                        exoPlayer.stop()
                        exoPlayer.release()
                        FFmpegKitConfig.clearSessions()
                    }
                }

                LaunchedEffect(videoUri) {
                    frames = withContext(Dispatchers.IO) {
                        extractFrames(videoUri, durationSec)
                    }
                }

                if (isCompressing) {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        title = { Text("Processing Video", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(26.dp))
                                Text("Compressing video...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                player = exoPlayer
                            }
                        },
                        update = { pv -> pv.player = exoPlayer },
                        modifier = Modifier.fillMaxSize()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_close_24),
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (isCompressing) return@IconButton
                                val startSec = range.start.toInt()
                                val endSec = range.endInclusive.toInt().coerceAtLeast(startSec + 1)
                                isCompressing = true

                                val inputFile = File(cacheDir, "input_${System.currentTimeMillis()}.mp4")
                                val outputFile = File(cacheDir, "output_${System.currentTimeMillis()}.mp4")

                                scope.launch(Dispatchers.IO) {
                                    try {
                                        contentResolver.openInputStream(videoUri)?.use { input ->
                                            inputFile.outputStream().use { output -> input.copyTo(output) }
                                        }
                                        val duration = (endSec - startSec).coerceAtLeast(1)
                                        val cmd = "-y -ss $startSec -t $duration -i ${inputFile.absolutePath} -c:v mpeg4 -q:v 3 ${outputFile.absolutePath}"
                                        FFmpegKit.executeAsync(cmd) { session ->
                                            val returnCode = session.returnCode
                                            if (ReturnCode.isSuccess(returnCode)) {
                                                val finalPath = WallpaperStore.saveMotionWallpaperFile(this@TrimVideoActivity, outputFile)
                                                runOnUiThread {
                                                    isCompressing = false
                                                    if (finalPath != null) {
                                                        startActivity(
                                                            Intent(this@TrimVideoActivity, WallpaperPreviewActivity::class.java).apply {
                                                                putExtra("imagePath", finalPath)
                                                                putExtra("isCustom", true)
                                                                putExtra("isVideo", true)
                                                            }
                                                        )
                                                        finish()
                                                    } else {
                                                        Toast.makeText(this@TrimVideoActivity, "Failed to save video", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } else {
                                                runOnUiThread {
                                                    isCompressing = false
                                                    UHLog.e("video", "FFmpeg failed: ${session.failStackTrace} | Logs: ${session.allLogsAsString}")
                                                    Toast.makeText(this@TrimVideoActivity, "Compression failed", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            inputFile.delete()
                                            outputFile.delete()
                                        }
                                    } catch (e: Exception) {
                                        runOnUiThread {
                                            isCompressing = false
                                            UHLog.e("video", "Video processing error: ${e.message}")
                                            Toast.makeText(this@TrimVideoActivity, "Error processing video", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_check_24),
                                contentDescription = "Save trim",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.72f))
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = "Selected: ${range.start.toInt()}s - ${range.endInclusive.toInt()}s",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        if (frames.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            ) {
                                frames.forEach { bmp ->
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        RangeSlider(
                            value = range,
                            onValueChange = { updated ->
                                val clamped = if (updated.endInclusive - updated.start > 15f) {
                                    updated.start..(updated.start + 15f)
                                } else {
                                    updated
                                }
                                range = clamped
                                exoPlayer.seekTo((clamped.start * 1000).toLong())
                            },
                            valueRange = 0f..durationSec
                        )
                    }
                }
            }
        }
    }

    private fun extractFrames(uri: Uri, duration: Float): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val numFrames = 10
            val interval = (duration / numFrames).coerceAtLeast(1f)
            for (i in 0 until numFrames) {
                val timeUs = (i * interval * 1_000_000).toLong()
                val bmp = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 128)
                if (bmp != null) frames.add(bmp)
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return frames
    }
}
