package app.harvester.companion.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.harvester.companion.Shortcut
import app.harvester.companion.ShortcutStore
import app.harvester.companion.WallpaperStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _shortcuts = MutableStateFlow(ShortcutStore.list(context))
    val shortcuts: StateFlow<List<Shortcut>> = _shortcuts

    private val _wallpaperPath = MutableStateFlow(WallpaperStore.getCurrentWallpaper(context))
    val wallpaperPath: StateFlow<String?> = _wallpaperPath

    private val _isVideo = MutableStateFlow(WallpaperStore.isCurrentVideo(context))
    val isVideo: StateFlow<Boolean> = _isVideo

    private val _textColor = MutableStateFlow(WallpaperStore.getTextColor(context))
    val textColor: StateFlow<Int> = _textColor

    init {
        viewModelScope.launch {
            WallpaperStore.wallpaperFlow(context).collect { wp ->
                _wallpaperPath.value = wp.path
                _isVideo.value = wp.isVideo
                _textColor.value = wp.textColor
            }
        }
    }

    fun refresh() {
        _shortcuts.value = ShortcutStore.list(context)
        val wp = WallpaperStore.wallpaperFlow(context).value
        _wallpaperPath.value = wp.path
        _isVideo.value = wp.isVideo
        _textColor.value = wp.textColor
    }

    fun moveShortcut(from: Int, to: Int) {
        val list = _shortcuts.value.toMutableList()
        if (from in list.indices && to in list.indices) {
            if (from < to) {
                for (i in from until to) {
                    java.util.Collections.swap(list, i, i + 1)
                }
            } else {
                for (i in from downTo to + 1) {
                    java.util.Collections.swap(list, i, i - 1)
                }
            }
            _shortcuts.value = list
            ShortcutStore.save(context, list)
        }
    }

    fun addShortcut(shortcut: Shortcut) {
        ShortcutStore.add(context, shortcut)
        refresh()
    }

    fun removeShortcut(shortcut: Shortcut) {
        ShortcutStore.remove(context, shortcut.url)
        refresh()
    }

    fun updateShortcut(old: Shortcut, new: Shortcut) {
        ShortcutStore.remove(context, old.url)
        ShortcutStore.add(context, new)
        refresh()
    }
}
