package app.harvester.companion

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun setMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_MODE, mode).apply()
        _modeFlow.value = mode
        apply(mode)
    }

    fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }

    fun applyCurrent(ctx: Context) {
        val mode = getMode(ctx)
        _modeFlow.value = mode
        apply(mode)
    }
}
