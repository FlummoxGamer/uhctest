package app.harvester.companion.settings.appearance

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class AppearanceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val themeMode by ThemePrefs.modeFlow(context).collectAsState()

                UhSettingsScaffold(
                    title = "Appearance",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Theme")
                    UhSettingsCardGroup {
                        ThemeOptionRow(
                            title = "Follow System",
                            subtitle = "Match your device dark or light appearance",
                            selected = themeMode == ThemePrefs.MODE_SYSTEM,
                            onClick = { ThemePrefs.setMode(context, ThemePrefs.MODE_SYSTEM) }
                        )
                        UhSettingsDivider()
                        ThemeOptionRow(
                            title = "Light",
                            selected = themeMode == ThemePrefs.MODE_LIGHT,
                            onClick = { ThemePrefs.setMode(context, ThemePrefs.MODE_LIGHT) }
                        )
                        UhSettingsDivider()
                        ThemeOptionRow(
                            title = "Dark",
                            selected = themeMode == ThemePrefs.MODE_DARK,
                            onClick = { ThemePrefs.setMode(context, ThemePrefs.MODE_DARK) }
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Enhance Dark Theme",
                            subtitle = "Apply deeper contrast across dark surfaces",
                            key = SettingsPrefs.K_ENHANCE_DARK,
                            default = false,
                            insideCard = true
                        )
                    }

                    SettingsCategory("Customize")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Home & Wallpaper",
                            subtitle = "Wallpapers, motion backgrounds & recents",
                            iconRes = R.drawable.fx_ic_appearance_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, CustomizeHomeActivity::class.java))
                            }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Customize Address Bar",
                            subtitle = "Position, auto-hide & URL formatting",
                            iconRes = R.drawable.fx_ic_settings_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, CustomizeAddressBarActivity::class.java))
                            }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Menu Layout",
                            iconRes = R.drawable.fx_ic_more_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Tab Bar Style",
                            iconRes = R.drawable.fx_ic_storage_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "App Language",
                            iconRes = R.drawable.fx_ic_info_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                Toast.makeText(context, "Coming soon", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}
