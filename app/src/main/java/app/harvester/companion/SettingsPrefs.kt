package app.harvester.companion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SettingsPrefs {
    private const val NAME = "uh_settings"
    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _addressBarBottomFlow = MutableStateFlow<Boolean?>(null)
    private val _customDownloadUriFlow = MutableStateFlow<String?>(null)
    private val _amoledDarkFlow = MutableStateFlow<Boolean?>(null)
    private val _enhanceDarkFlow = MutableStateFlow<Boolean?>(null)
    private val _trustPanelStyleFlow = MutableStateFlow<String?>(null)
    private val _privacyLevelFlow = MutableStateFlow<String?>(null)
    private val _textScaleFlow = MutableStateFlow<Int?>(null)
    private val _tabLayoutGridFlow = MutableStateFlow<Boolean?>(null)
    private val _translateEngineFlow = MutableStateFlow<String?>(null)
    private val _translateLangFlow = MutableStateFlow<String?>(null)

    fun addressBarBottomFlow(ctx: Context): StateFlow<Boolean> {
        if (_addressBarBottomFlow.value == null) {
            _addressBarBottomFlow.value = getInt(ctx, K_ADDRESS_BAR_POSITION, 1) == 1
        }
        @Suppress("UNCHECKED_CAST")
        return _addressBarBottomFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun customDownloadUriFlow(ctx: Context): StateFlow<String> {
        if (_customDownloadUriFlow.value == null) {
            _customDownloadUriFlow.value = getString(ctx, "custom_download_uri", "")
        }
        @Suppress("UNCHECKED_CAST")
        return _customDownloadUriFlow.asStateFlow() as StateFlow<String>
    }

    fun amoledDarkFlow(ctx: Context): StateFlow<Boolean> {
        if (_amoledDarkFlow.value == null) {
            _amoledDarkFlow.value = getBool(ctx, K_AMOLED_DARK, false)
        }
        @Suppress("UNCHECKED_CAST")
        return _amoledDarkFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun enhanceDarkFlow(ctx: Context): StateFlow<Boolean> {
        if (_enhanceDarkFlow.value == null) {
            _enhanceDarkFlow.value = getBool(ctx, K_ENHANCE_DARK, false)
        }
        @Suppress("UNCHECKED_CAST")
        return _enhanceDarkFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun trustPanelStyleFlow(ctx: Context): StateFlow<String> {
        if (_trustPanelStyleFlow.value == null) {
            _trustPanelStyleFlow.value = getString(ctx, K_TRUST_PANEL_STYLE, TRUST_STYLE_ADAPTIVE)
        }
        @Suppress("UNCHECKED_CAST")
        return _trustPanelStyleFlow.asStateFlow() as StateFlow<String>
    }

    fun privacyLevelFlow(ctx: Context): StateFlow<String> {
        if (_privacyLevelFlow.value == null) {
            _privacyLevelFlow.value = getString(ctx, K_PRIVACY_LEVEL, PRIVACY_STANDARD)
        }
        @Suppress("UNCHECKED_CAST")
        return _privacyLevelFlow.asStateFlow() as StateFlow<String>
    }

    fun textScaleFlow(ctx: Context): StateFlow<Int> {
        if (_textScaleFlow.value == null) {
            _textScaleFlow.value = getInt(ctx, K_TEXT_SCALE, 100)
        }
        @Suppress("UNCHECKED_CAST")
        return _textScaleFlow.asStateFlow() as StateFlow<Int>
    }

    fun tabLayoutGridFlow(ctx: Context): StateFlow<Boolean> {
        if (_tabLayoutGridFlow.value == null) {
            _tabLayoutGridFlow.value = getBool(ctx, K_TAB_LAYOUT_GRID, true)
        }
        @Suppress("UNCHECKED_CAST")
        return _tabLayoutGridFlow.asStateFlow() as StateFlow<Boolean>
    }

    fun translateEngineFlow(ctx: Context): StateFlow<String> {
        if (_translateEngineFlow.value == null) {
            _translateEngineFlow.value = getString(ctx, K_TRANSLATE_ENGINE, TRANSLATE_ENGINE_DIRECT)
        }
        @Suppress("UNCHECKED_CAST")
        return _translateEngineFlow.asStateFlow() as StateFlow<String>
    }

    fun translateLangFlow(ctx: Context): StateFlow<String> {
        if (_translateLangFlow.value == null) {
            _translateLangFlow.value = getString(ctx, K_TRANSLATE_LANG, "en")
        }
        @Suppress("UNCHECKED_CAST")
        return _translateLangFlow.asStateFlow() as StateFlow<String>
    }

    fun getBool(ctx: Context, key: String, def: Boolean = false): Boolean =
        p(ctx).getBoolean(key, def)

    fun setBool(ctx: Context, key: String, v: Boolean) {
        p(ctx).edit().putBoolean(key, v).apply()
        when (key) {
            K_AMOLED_DARK -> _amoledDarkFlow.value = v
            K_ENHANCE_DARK -> _enhanceDarkFlow.value = v
            K_TAB_LAYOUT_GRID -> _tabLayoutGridFlow.value = v
        }
    }

    fun getInt(ctx: Context, key: String, def: Int = 0): Int = p(ctx).getInt(key, def)

    fun setInt(ctx: Context, key: String, v: Int) {
        p(ctx).edit().putInt(key, v).apply()
        when (key) {
            K_ADDRESS_BAR_POSITION -> _addressBarBottomFlow.value = (v == 1)
            K_TEXT_SCALE -> _textScaleFlow.value = v
        }
    }

    fun getString(ctx: Context, key: String, def: String = ""): String =
        p(ctx).getString(key, def) ?: def

    fun setString(ctx: Context, key: String, v: String) {
        p(ctx).edit().putString(key, v).apply()
        when (key) {
            "custom_download_uri" -> _customDownloadUriFlow.value = v
            K_TRUST_PANEL_STYLE -> _trustPanelStyleFlow.value = v
            K_PRIVACY_LEVEL -> _privacyLevelFlow.value = v
            K_TRANSLATE_ENGINE -> _translateEngineFlow.value = v
            K_TRANSLATE_LANG -> _translateLangFlow.value = v
        }
    }

    const val TRUST_STYLE_ADAPTIVE = "adaptive"
    const val TRUST_STYLE_CYBERPUNK = "cyberpunk"

    const val PRIVACY_STANDARD = "standard"
    const val PRIVACY_STRICT = "strict"
    const val PRIVACY_CUSTOM = "custom"

    const val TRANSLATE_ENGINE_DIRECT = "direct"
    const val TRANSLATE_ENGINE_MLKIT = "mlkit"

    const val K_ENHANCE_DARK = "enhance_dark"
    const val K_AMOLED_DARK = "amoled_dark"
    const val K_TRUST_PANEL_STYLE = "trust_panel_style"
    const val K_AUTOPLAY = "autoplay_videos"
    const val K_QUALITY = "default_quality"
    const val K_TEXT_SCALE = "text_scale"
    const val K_FORCE_DARK_SITES = "force_dark_sites"
    const val K_ASK_DL = "ask_before_download"
    const val K_DL_LOCATION = "download_location"
    const val K_DELETE_BEHAVIOR = "delete_download_behavior"

    const val K_PRIVACY_LEVEL = "privacy_level"
    const val K_PG_ADS = "pg_ads"
    const val K_PG_HTTPS = "pg_https"
    const val K_PG_FINGERPRINT = "pg_fingerprint"
    const val K_PG_SCRIPT = "pg_script"
    const val K_PG_COOKIES = "pg_cookies"
    const val K_PG_REDIRECT = "pg_redirect"
    const val K_ADV_SAFE_BROWSING = "adv_safe_browsing"
    const val K_ADV_DNT = "adv_dnt"
    const val K_ADV_DEAMP = "adv_deamp"
    const val K_ADV_GPC = "adv_gpc"
    const val K_ADV_SECURE_DNS = "adv_secure_dns"
    const val K_WEBRTC_POLICY = "adv_webrtc_policy"
    const val K_ADDRESS_BAR_POSITION = "address_bar_position"
    const val K_AUTO_HIDE_BAR = "auto_hide_bar"
    const val K_SWIPE_FULLSCREEN = "swipe_fullscreen"

    const val K_SEARCH_SUGGESTIONS = "search_suggestions"
    const val K_SEARCH_SUGGESTIONS_PRIVATE = "search_suggestions_private"
    const val K_SEARCH_HISTORY = "search_history"
    const val K_SEARCH_BOOKMARKS = "search_bookmarks"
    const val K_SEARCH_CLIPBOARD = "search_clipboard"
    const val K_SEARCH_VOICE = "search_voice"
    const val K_SEARCH_AUTOCOMPLETE = "search_autocomplete"
    const val K_SEARCH_REMOTE = "search_remote"
    const val K_SHOW_FULL_URL = "show_full_url"

    const val K_TAB_SLEEP_SECONDS = "tab_sleep_seconds"
    const val K_TAB_CONFIRM_SWIPE = "tab_confirm_swipe"
    const val K_TAB_AUTO_COLLAPSE_GROUPS = "tab_auto_collapse_groups"
    const val K_TAB_LAYOUT_GRID = "tab_layout_grid"
    const val K_UBLOCK_ENABLED = "ublock_enabled"
    const val K_UBLOCK_AUTO_UPDATE = "ublock_auto_update"

    const val K_TRANSLATE_ENGINE = "translate_engine"
    const val K_TRANSLATE_LANG = "translate_target_lang"
    const val K_PAGE_ZOOM_DEFAULT = "page_zoom_default"
}
