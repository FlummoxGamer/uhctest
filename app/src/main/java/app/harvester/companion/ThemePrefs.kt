package app.harvester.companion

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme

object ThemePrefs {

    private const val NAME = "uh_theme"
    private const val KEY_MODE = "mode"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    private val _modeFlow = MutableStateFlow<Int?>(null)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): Int = prefs(ctx).getInt(KEY_MODE, MODE_SYSTEM)

    fun modeFlow(ctx: Context): StateFlow<Int> {
        if (_modeFlow.value == null) {
            _modeFlow.value = getMode(ctx)
        }
        @Suppress("UNCHECKED_CAST")
        return _modeFlow.asStateFlow() as StateFlow<Int>
    }

    fun isDark(ctx: Context, mode: Int = getMode(ctx)): Boolean = when (mode) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun isDarkActive(ctx: Context): Boolean = isDark(ctx)

    fun isAmoledActive(ctx: Context, mode: Int = getMode(ctx)): Boolean {
        return isDark(ctx, mode) && SettingsPrefs.getBool(ctx, SettingsPrefs.K_AMOLED_DARK, false)
    }

    fun isEnhanceDarkActive(ctx: Context, mode: Int = getMode(ctx)): Boolean {
        return isDark(ctx, mode) && SettingsPrefs.getBool(ctx, SettingsPrefs.K_ENHANCE_DARK, false)
    }

    fun setMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_MODE, mode).apply()
        _modeFlow.value = mode
        syncEngineColorScheme(ctx, mode)
        syncWebAppearance(ctx)
    }

    fun applyCurrent(ctx: Context) {
        val mode = getMode(ctx)
        _modeFlow.value = mode
    }

    fun syncWebAppearance(ctx: Context) {
        try {
            val isDark = isDark(ctx)
            val enhanceDark = isDark && SettingsPrefs.getBool(ctx, SettingsPrefs.K_ENHANCE_DARK, false)
            val textScale = SettingsPrefs.getInt(ctx, SettingsPrefs.K_TEXT_SCALE, 100)
            GeckoWebExtensionBridge.pushAppearanceUpdate(
                enhanceDark = enhanceDark,
                textScale = textScale
            )
        } catch (_: Exception) {}
    }

    private fun syncEngineColorScheme(ctx: Context, mode: Int) {
        try {
            val app = ctx.applicationContext as? UHApp ?: return
            if (!app.isComponentsInitialized) return
            app.components.engine.settings.preferredColorScheme = when (mode) {
                MODE_LIGHT -> PreferredColorScheme.Light
                MODE_DARK -> PreferredColorScheme.Dark
                else -> PreferredColorScheme.System
            }
        } catch (e: Exception) {
            UHLog.w("theme_warn", "Failed updating engine preferredColorScheme: ${e.message}")
        }
    }
}
