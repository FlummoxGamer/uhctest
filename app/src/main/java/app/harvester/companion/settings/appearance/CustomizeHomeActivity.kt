package app.harvester.companion.settings.appearance

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.ShortcutStore
import app.harvester.companion.WallpaperStore
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CustomizeHomeActivity : AppCompatActivity() {

    private var reloadTick by mutableIntStateOf(0)

    private val categorizedWallpapers = linkedMapOf(
        "Motion" to listOf(
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_1.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_2.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_3.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_4.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_5.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_6.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_7.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_8.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_9.mp4",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/motion/motion_10.mp4"
        ),
        "Anime" to listOf(
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_1.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_2.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_3.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_4.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_5.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_6.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_7.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_8.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_9.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_10.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_11.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_12.webp",
            "https://cdn.jsdelivr.net/gh/FlummoxGamer/universal-harvester-companion@dev/wallpapers/anime/anime_13.webp"
        ),
        "Cinematic" to listOf(
            "https://images.unsplash.com/photo-1478720568477-152d9b164e26?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1536440136628-849c177e76a1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1517604931442-7e0c8ed2963c?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1440404653325-ab127d49abc1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1485095329183-d0797cdc5676?q=80&w=2000&auto=format&fit=crop"
        ),
        "Abstract" to listOf(
            "https://images.unsplash.com/photo-1614850523459-c2f4c699c52e?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1579546929518-9e396f3cc809?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1554034483-04fda0d3507b?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1507608616759-54f48f0af0ee?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1557672172-298e090bd0f1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1604871000636-074fa5117945?q=80&w=2000&auto=format&fit=crop"
        ),
        "Liquid Metal" to listOf(
            "https://images.unsplash.com/photo-1614850523459-c2f4c699c52e?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1604871000636-074fa5117945?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1557672172-298e090bd0f1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1614850715649-1d0106293bd1?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1550684848-fac1c5b4e853?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1620121692029-d088224ddc74?q=80&w=2000&auto=format&fit=crop",
            "https://images.unsplash.com/photo-1618005198919-d3d4b5a92ead?q=80&w=2000&auto=format&fit=crop"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val tick = reloadTick
                val wallpaperState by WallpaperStore.wallpaperFlow(context).collectAsState()
                val activePath = wallpaperState.path ?: "default"
                val storedRecents = remember(tick, activePath) {
                    WallpaperStore.getRecent(context).take(2)
                }
                var showResetConfirm by remember { mutableStateOf(false) }

                val pickMedia = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    if (uri != null) {
                        val mime = context.contentResolver.getType(uri)
                        if (mime != null && mime.startsWith("video/")) {
                            context.startActivity(
                                Intent(context, TrimVideoActivity::class.java)
                                    .putExtra("videoUri", uri.toString())
                            )
                        } else {
                            Toast.makeText(context, "Processing image...", Toast.LENGTH_SHORT).show()
                            scope.launch(Dispatchers.IO) {
                                val localPath = WallpaperStore.saveWallpaper(context, uri)
                                withContext(Dispatchers.Main) {
                                    if (localPath != null) {
                                        context.startActivity(
                                            Intent(context, WallpaperPreviewActivity::class.java).apply {
                                                putExtra("imagePath", localPath)
                                                putExtra("isCustom", true)
                                            }
                                        )
                                    } else {
                                        Toast.makeText(context, "Failed to save wallpaper", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("Reset Home?", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                "Are you sure you want to reset all widgets and wallpapers to defaults?",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showResetConfirm = false
                                    WallpaperStore.setCurrentWallpaper(context, "default", 0, false)
                                    ShortcutStore.clear(context)
                                    Toast.makeText(context, "Home reset to defaults", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            ) {
                                Text("Confirm", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Customize Home",
                    onBack = { finish() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MY WALLPAPERS",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(context, RecentlyActivity::class.java))
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("See All", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    val topTiles: List<HomeWallpaperTile> = buildList {
                        add(HomeWallpaperTile.Add)
                        add(HomeWallpaperTile.Default)
                        storedRecents.forEach { path ->
                            add(
                                if (path.endsWith(".mp4")) HomeWallpaperTile.Motion(path)
                                else HomeWallpaperTile.Custom(path)
                            )
                        }
                    }

                    WallpaperTileGrid(
                        tiles = topTiles,
                        activePath = activePath,
                        onTileClick = { tile ->
                            when (tile) {
                                is HomeWallpaperTile.Add -> {
                                    pickMedia.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                }
                                is HomeWallpaperTile.Default -> {
                                    WallpaperStore.setCurrentWallpaper(context, "default", 0, false)
                                    finish()
                                }
                                is HomeWallpaperTile.Custom -> {
                                    context.startActivity(
                                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imagePath", tile.path)
                                            putExtra("isCustom", true)
                                        }
                                    )
                                }
                                is HomeWallpaperTile.Motion -> {
                                    context.startActivity(
                                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imagePath", tile.path)
                                            putExtra("isCustom", true)
                                            putExtra("isVideo", true)
                                        }
                                    )
                                }
                                is HomeWallpaperTile.Remote -> Unit
                            }
                        },
                        onSetFromMenu = { path ->
                            context.startActivity(
                                Intent(context, WallpaperPreviewActivity::class.java).apply {
                                    putExtra("imagePath", path)
                                    putExtra("isCustom", true)
                                    putExtra("isVideo", path.endsWith(".mp4"))
                                }
                            )
                        },
                        onDeleteFromMenu = { path ->
                            WallpaperStore.deleteWallpaper(path)
                            reloadTick++
                        }
                    )

                    categorizedWallpapers.forEach { (genre, urls) ->
                        Text(
                            text = genre,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)
                        )
                        val isVideo = genre == "Motion"
                        WallpaperTileGrid(
                            tiles = urls.map { HomeWallpaperTile.Remote(it, isVideo) },
                            activePath = activePath,
                            onTileClick = { tile ->
                                if (tile is HomeWallpaperTile.Remote) {
                                    context.startActivity(
                                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imageUrl", tile.url)
                                            putExtra("isCustom", false)
                                            putExtra("isVideo", tile.isVideo)
                                        }
                                    )
                                }
                            },
                            onSetFromMenu = {},
                            onDeleteFromMenu = {}
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Reset Home to Defaults", modifier = Modifier.padding(vertical = 4.dp))
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadTick++
    }
}

sealed class HomeWallpaperTile {
    data object Add : HomeWallpaperTile()
    data object Default : HomeWallpaperTile()
    data class Custom(val path: String) : HomeWallpaperTile()
    data class Motion(val path: String) : HomeWallpaperTile()
    data class Remote(val url: String, val isVideo: Boolean) : HomeWallpaperTile()
}

@Composable
fun WallpaperTileGrid(
    tiles: List<HomeWallpaperTile>,
    activePath: String,
    onTileClick: (HomeWallpaperTile) -> Unit,
    onSetFromMenu: (String) -> Unit,
    onDeleteFromMenu: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(4).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowTiles.forEach { tile ->
                    Box(modifier = Modifier.weight(1f)) {
                        WallpaperGridTile(
                            tile = tile,
                            activePath = activePath,
                            onClick = { onTileClick(tile) },
                            onSetFromMenu = onSetFromMenu,
                            onDeleteFromMenu = onDeleteFromMenu
                        )
                    }
                }
                repeat(4 - rowTiles.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WallpaperGridTile(
    tile: HomeWallpaperTile,
    activePath: String,
    onClick: () -> Unit,
    onSetFromMenu: (String) -> Unit,
    onDeleteFromMenu: (String) -> Unit,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val customPath = when (tile) {
        is HomeWallpaperTile.Custom -> tile.path
        is HomeWallpaperTile.Motion -> tile.path
        else -> null
    }

    val isActive = when (tile) {
        is HomeWallpaperTile.Default -> activePath == "default"
        is HomeWallpaperTile.Custom -> tile.path == activePath
        is HomeWallpaperTile.Motion -> tile.path == activePath
        is HomeWallpaperTile.Remote -> tile.url == activePath
        is HomeWallpaperTile.Add -> false
    }

    val borderColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val borderWidth = if (isActive) 2.5.dp else 1.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.66f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (customPath != null) {
                    { showMenu = true }
                } else null
            ),
        contentAlignment = Alignment.Center
    ) {
        when (tile) {
            is HomeWallpaperTile.Add -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = "Add wallpaper",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
            is HomeWallpaperTile.Default -> {
                Icon(
                    painter = painterResource(id = R.drawable.ic_home),
                    contentDescription = "Default theme",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            is HomeWallpaperTile.Custom -> {
                val model: Any = if (tile.path.startsWith("http")) tile.path else File(tile.path)
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is HomeWallpaperTile.Motion -> {
                val model: Any = if (tile.path.startsWith("http")) tile.path else File(tile.path)
                val req = remember(tile.path) {
                    ImageRequest.Builder(context)
                        .data(model)
                        .videoFrameMillis(1000L)
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    error = painterResource(id = R.drawable.fx_ic_video_24),
                    modifier = Modifier.fillMaxSize()
                )
            }
            is HomeWallpaperTile.Remote -> {
                val req = remember(tile.url, tile.isVideo) {
                    ImageRequest.Builder(context)
                        .data(tile.url)
                        .apply {
                            if (tile.isVideo) videoFrameMillis(1000L)
                        }
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    error = painterResource(id = if (tile.isVideo) R.drawable.fx_ic_video_24 else R.drawable.fx_ic_appearance_24),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_check_24),
                    contentDescription = "Active wallpaper",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        if (customPath != null) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Set as Wallpaper") },
                    onClick = {
                        showMenu = false
                        onSetFromMenu(customPath)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showMenu = false
                        onDeleteFromMenu(customPath)
                    }
                )
            }
        }
    }
}
