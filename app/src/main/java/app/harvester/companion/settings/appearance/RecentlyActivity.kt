package app.harvester.companion.settings.appearance

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.WallpaperStore
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class RecentlyActivity : AppCompatActivity() {

    private var reloadTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val tick = reloadTick
                val wallpaperState by WallpaperStore.wallpaperFlow(context).collectAsState()
                val activePath = wallpaperState.path ?: "default"
                val recentTiles = remember(tick, activePath) {
                    WallpaperStore.getRecent(context).map { path ->
                        if (path.endsWith(".mp4")) HomeWallpaperTile.Motion(path)
                        else HomeWallpaperTile.Custom(path)
                    }
                }

                UhSettingsScaffold(
                    title = "Recent Wallpapers",
                    onBack = { finish() }
                ) {
                    if (recentTiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .height(320.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_appearance_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No recent wallpapers yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        WallpaperTileGrid(
                            tiles = recentTiles,
                            activePath = activePath,
                            onTileClick = { tile ->
                                val path = when (tile) {
                                    is HomeWallpaperTile.Custom -> tile.path
                                    is HomeWallpaperTile.Motion -> tile.path
                                    else -> ""
                                }
                                if (path.isNotEmpty()) {
                                    context.startActivity(
                                        Intent(context, WallpaperPreviewActivity::class.java).apply {
                                            putExtra("imagePath", path)
                                            putExtra("isCustom", true)
                                            putExtra("isVideo", tile is HomeWallpaperTile.Motion)
                                        }
                                    )
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
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadTick++
    }
}
