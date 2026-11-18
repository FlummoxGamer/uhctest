package app.harvester.companion.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs

private val DarkColorScheme = darkColorScheme(
    primary = UH_Accent,
    onPrimary = Color.White,
    primaryContainer = UH_Accent_Dark_Container,
    onPrimaryContainer = UH_Dark_Text,
    secondary = UH_Accent,
    onSecondary = Color.White,
    secondaryContainer = UH_Dark_Surface3,
    onSecondaryContainer = UH_Dark_Text,
    background = UH_Dark_Bg,
    onBackground = UH_Dark_Text,
    surface = UH_Dark_Surface,
    onSurface = UH_Dark_Text,
    surfaceVariant = UH_Dark_Surface2,
    onSurfaceVariant = UH_Dark_Subtext,
    surfaceContainerLowest = UH_Dark_Bg,
    surfaceContainerLow = UH_Dark_Surface,
    surfaceContainer = UH_Dark_Surface2,
    surfaceContainerHigh = UH_Dark_Surface2,
    surfaceContainerHighest = UH_Dark_Surface3,
    error = UH_Dark_Red,
    outline = UH_Dark_Border,
    outlineVariant = UH_Dark_Border.copy(alpha = 0.55f)
)

private val AmoledDarkColorScheme = darkColorScheme(
    primary = UH_Accent,
    onPrimary = Color.White,
    primaryContainer = UH_Accent_Dark_Container,
    onPrimaryContainer = UH_Dark_Text,
    secondary = UH_Accent,
    onSecondary = Color.White,
    secondaryContainer = UH_Amoled_Surface3,
    onSecondaryContainer = UH_Dark_Text,
    background = UH_Amoled_Bg,
    onBackground = UH_Dark_Text,
    surface = UH_Amoled_Surface,
    onSurface = UH_Dark_Text,
    surfaceVariant = UH_Amoled_Surface2,
    onSurfaceVariant = UH_Dark_Subtext,
    surfaceContainerLowest = UH_Amoled_Bg,
    surfaceContainerLow = UH_Amoled_Surface,
    surfaceContainer = UH_Amoled_Surface2,
    surfaceContainerHigh = UH_Amoled_Surface2,
    surfaceContainerHighest = UH_Amoled_Surface3,
    error = UH_Dark_Red,
    outline = UH_Amoled_Border,
    outlineVariant = UH_Amoled_Border.copy(alpha = 0.65f)
)

private val LightColorScheme = lightColorScheme(
    primary = UH_Accent,
    onPrimary = Color.White,
    primaryContainer = UH_Accent_Light_Container,
    onPrimaryContainer = UH_Light_Text,
    secondary = UH_Accent_Strong,
    onSecondary = Color.White,
    secondaryContainer = UH_Light_Surface2,
    onSecondaryContainer = UH_Light_Text,
    background = UH_Light_Bg,
    onBackground = UH_Light_Text,
    surface = UH_Light_Surface,
    onSurface = UH_Light_Text,
    surfaceVariant = UH_Light_Surface2,
    onSurfaceVariant = UH_Light_Subtext,
    surfaceContainerLowest = UH_Light_Bg,
    surfaceContainerLow = UH_Light_Surface,
    surfaceContainer = UH_Light_Surface,
    surfaceContainerHigh = UH_Light_Bg,
    surfaceContainerHighest = UH_Light_Surface2,
    error = UH_Light_Red,
    outline = UH_Light_Border,
    outlineVariant = UH_Light_Border.copy(alpha = 0.6f)
)

@Composable
fun UHTheme(
    lightStatusBarOverride: Boolean? = null,
    lightNavigationBarOverride: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val mode by ThemePrefs.modeFlow(context).collectAsState()
    val amoled by SettingsPrefs.amoledDarkFlow(context).collectAsState()
    val systemDark = isSystemInDarkTheme()
    val isDark = when (mode) {
        ThemePrefs.MODE_DARK -> true
        ThemePrefs.MODE_LIGHT -> false
        else -> systemDark
    }
    val colorScheme = when {
        isDark && amoled -> AmoledDarkColorScheme
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        val useLightStatus = lightStatusBarOverride ?: !isDark
        val useLightNav = lightNavigationBarOverride ?: !isDark
        val bgArgb = colorScheme.background.toArgb()
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                if (lightStatusBarOverride == null && lightNavigationBarOverride == null) {
                    window.decorView.setBackgroundColor(bgArgb)
                }
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = useLightStatus
                controller.isAppearanceLightNavigationBars = useLightNav
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
