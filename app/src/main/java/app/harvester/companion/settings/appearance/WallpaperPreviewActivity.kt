package app.harvester.companion.settings.appearance

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.palette.graphics.Palette
import app.harvester.companion.R
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutStore
import app.harvester.companion.TouchImageView
import app.harvester.companion.UHLog
import app.harvester.companion.WallpaperStore
import app.harvester.companion.theme.UHTheme
import coil.compose.AsyncImage
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WallpaperPreviewActivity : AppCompatActivity() {

    private val touchViews = mutableMapOf<Int, TouchImageView>()

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        val initialPath = intent.getStringExtra("imagePath")
        val initialUrl = intent.getStringExtra("imageUrl")
        val isCustom = intent.getBooleanExtra("isCustom", true)
        val forceVideo = intent.getBooleanExtra("isVideo", false)

        val uris = buildList {
            if (isCustom) {
                val recents = WallpaperStore.getRecent(this@WallpaperPreviewActivity).toMutableList()
                if (initialPath != null) {
                    recents.remove(initialPath)
                    recents.add(0, initialPath)
                }
                addAll(recents)
            } else {
                add(initialUrl ?: "default")
            }
            if (isEmpty()) add("default")
        }

        val initialIndex = uris.indexOf(initialPath).coerceAtLeast(0)

        setContent {
            UHTheme(
                lightStatusBarOverride = false,
                lightNavigationBarOverride = false
            ) {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val shortcuts = remember { ShortcutStore.list(context).take(8) }
                val pagerState = rememberPagerState(initialPage = initialIndex) { uris.size }
                val previewTextColors = remember { mutableStateMapOf<Int, Int>() }
                var isApplying by remember { mutableIntStateOf(0) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val item = uris[page]
                        val isVideo = item.endsWith(".mp4") || item.contains("sample") || (forceVideo && page == initialIndex)
                        val titleColorInt = previewTextColors[page]
                            ?: if (item == "default") AndroidColor.WHITE else AndroidColor.WHITE

                        WallpaperPreviewPage(
                            item = item,
                            isVideo = isVideo,
                            shortcuts = shortcuts,
                            titleColorInt = titleColorInt,
                            onBindTouchView = { tv -> touchViews[page] = tv },
                            onUnbindTouchView = { touchViews.remove(page) },
                            onDetectedTextColor = { detected -> previewTextColors[page] = detected }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { finish() },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_close_24),
                                contentDescription = "Close preview",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Button(
                            enabled = isApplying == 0,
                            onClick = {
                                val currentPage = pagerState.currentPage
                                if (currentPage !in uris.indices) return@Button
                                val selectedUri = uris[currentPage]
                                if (selectedUri == "default") {
                                    WallpaperStore.setCurrentWallpaper(context, "default", 0, false)
                                    finish()
                                    return@Button
                                }

                                val touchView = touchViews[currentPage]
                                if (touchView != null) {
                                    WallpaperStore.setWallpaperTransform(
                                        context,
                                        isLandscape,
                                        touchView.currentZoom,
                                        touchView.scrollPosition.x,
                                        touchView.scrollPosition.y
                                    )
                                }

                                val isVideo = selectedUri.endsWith(".mp4") || (forceVideo && currentPage == initialIndex)
                                isApplying = 1
                                Toast.makeText(context, "Applying wallpaper...", Toast.LENGTH_SHORT).show()

                                scope.launch(Dispatchers.IO) {
                                    try {
                                        var finalUriString = selectedUri
                                        if (selectedUri.startsWith("http")) {
                                            val downloaded = WallpaperStore.downloadRemoteWallpaper(context, selectedUri)
                                            if (downloaded != null) {
                                                finalUriString = downloaded
                                            }
                                        }
                                        val bitmap = if (isVideo) {
                                            WallpaperStore.generateVideoThumbnail(finalUriString)
                                        } else {
                                            BitmapFactory.decodeFile(finalUriString)
                                        }

                                        val textColor = if (bitmap != null) {
                                            val palette = Palette.from(bitmap).generate()
                                            val dom = palette.getDominantColor(AndroidColor.GRAY)
                                            val lum = ColorUtils.calculateLuminance(dom)
                                            bitmap.recycle()
                                            if (lum > 0.5) AndroidColor.BLACK else AndroidColor.WHITE
                                        } else {
                                            AndroidColor.WHITE
                                        }

                                        withContext(Dispatchers.Main) {
                                            WallpaperStore.setCurrentWallpaper(
                                                context,
                                                finalUriString,
                                                textColor,
                                                isVideo
                                            )
                                            finish()
                                        }
                                    } catch (_: Exception) {
                                        withContext(Dispatchers.Main) {
                                            WallpaperStore.setCurrentWallpaper(context, "default", 0, false)
                                            finish()
                                        }
                                    }
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.fx_ic_check_24),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isApplying == 0) "Set Wallpaper" else "Applying...")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperPreviewPage(
    item: String,
    isVideo: Boolean,
    shortcuts: List<Shortcut>,
    titleColorInt: Int,
    onBindTouchView: (TouchImageView) -> Unit,
    onUnbindTouchView: () -> Unit,
    onDetectedTextColor: (Int) -> Unit,
) {
    val context = LocalContext.current
    val titleColor = if (item == "default") MaterialTheme.colorScheme.onBackground else Color(titleColorInt)
    val shadowColor = if (titleColorInt == AndroidColor.BLACK) Color.White else Color.Black

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            item == "default" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                )
            }
            isVideo -> {
                val exoPlayer = remember(item) {
                    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                    ExoPlayer.Builder(context, renderers).build().apply {
                        val mediaUri = if (item.startsWith("http")) Uri.parse(item) else Uri.fromFile(File(item))
                        val mediaItem = MediaItem.Builder()
                            .setUri(mediaUri)
                            .setMimeType(MimeTypes.APPLICATION_MP4)
                            .build()
                        setMediaItem(mediaItem)
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ONE
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                UHLog.e("video", "ExoPlayer Error: ${error.message}")
                            }
                        })
                        prepare()
                        play()
                    }
                }

                DisposableEffect(exoPlayer) {
                    onDispose {
                        exoPlayer.stop()
                        exoPlayer.release()
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            player = exoPlayer
                        }
                    },
                    update = { pv -> pv.player = exoPlayer },
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                val imageModel: Any = if (item.startsWith("http")) item else File(item)
                DisposableEffect(item) {
                    onDispose { onUnbindTouchView() }
                }
                AndroidView(
                    factory = { ctx ->
                        TouchImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            onBindTouchView(this)
                        }
                    },
                    update = { view ->
                        onBindTouchView(view)
                        view.load(imageModel) {
                            crossfade(true)
                            allowHardware(false)
                            listener(
                                onSuccess = { _, result ->
                                    val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                                    if (bmp != null) {
                                        val palette = Palette.from(bmp).generate()
                                        val dom = palette.getDominantColor(AndroidColor.GRAY)
                                        val lum = ColorUtils.calculateLuminance(dom)
                                        onDetectedTextColor(if (lum > 0.5) AndroidColor.BLACK else AndroidColor.WHITE)
                                    }
                                },
                                onError = { _, result ->
                                    UHLog.e("preview", "Coil error: ${result.throwable.message}")
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(78.dp))
            Text(
                text = "Harvester",
                color = titleColor,
                fontSize = 32.sp,
                fontFamily = FontFamily(Font(R.font.app_font)),
                style = TextStyle(
                    shadow = if (item == "default") null else Shadow(
                        color = shadowColor,
                        offset = Offset(0f, 2f),
                        blurRadius = 8f
                    )
                )
            )
            Spacer(modifier = Modifier.height(28.dp))

            shortcuts.chunked(4).forEach { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { s ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = s.iconUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = s.name,
                                color = titleColor,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                style = TextStyle(
                                    shadow = if (item == "default") null else Shadow(
                                        color = shadowColor,
                                        offset = Offset(0f, 1.5f),
                                        blurRadius = 6f
                                    )
                                )
                            )
                        }
                    }
                    repeat(4 - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Search or enter address",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                Text(
                    text = "1",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp
                )
            }
        }
    }
}
