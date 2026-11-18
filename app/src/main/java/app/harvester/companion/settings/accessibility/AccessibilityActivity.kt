package app.harvester.companion.settings.accessibility

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.UHApp
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import kotlin.math.roundToInt

class AccessibilityActivity : AppCompatActivity() {

    private fun applyTextScaleLive(context: android.content.Context, percent: Int) {
        SettingsPrefs.setInt(context, SettingsPrefs.K_TEXT_SCALE, percent)
        try {
            val components = (context.applicationContext as? UHApp)?.components
            val factor = percent / 100f
            if (components != null) {
                components.engine.settings.fontSizeFactor = factor
                components.engine.settings.fontInflationEnabled = percent != 100
                components.engine.settings.automaticFontSizeAdjustment = percent != 100
                components.store.state.tabs.forEach { tab ->
                    tab.engineState.engineSession?.settings?.let { s ->
                        s.fontSizeFactor = factor
                        s.fontInflationEnabled = percent != 100
                        s.automaticFontSizeAdjustment = percent != 100
                    }
                }
            }
        } catch (_: Exception) {}
        ThemePrefs.syncWebAppearance(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                var textScale by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TEXT_SCALE, 100).coerceIn(75, 160))
                }

                UhSettingsScaffold(
                    title = "Accessibility",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Display & Text")
                    UhSettingsCardGroup {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_accessibility_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Text scaling",
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Scale article and webpage font sizes across all tabs",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                Text(
                                    text = "$textScale%",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Slider(
                                value = textScale.toFloat(),
                                onValueChange = { raw ->
                                    val snapped = ((raw / 5f).roundToInt() * 5).coerceIn(75, 160)
                                    textScale = snapped
                                    applyTextScaleLive(context, snapped)
                                },
                                valueRange = 75f..160f
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "75% · Default 100% · 160%",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                                if (textScale != 100) {
                                    TextButton(onClick = {
                                        textScale = 100
                                        applyTextScaleLive(context, 100)
                                    }) {
                                        Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
