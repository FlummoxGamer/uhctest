package app.harvester.companion.browser.tabs

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap

object TabPreviewCache {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    @Volatile
    private var lastActiveTabBitmap: Bitmap? = null

    fun put(tabId: String, bitmap: Bitmap) {
        val old = cache.put(tabId, bitmap)
        if (old != null && old !== bitmap && !old.isRecycled) {
            old.recycle()
        }
        setLastActiveBitmap(bitmap)
    }

    fun get(tabId: String): Bitmap? = cache[tabId]

    fun setLastActiveBitmap(bitmap: Bitmap?) {
        val old = lastActiveTabBitmap
        if (old !== bitmap) {
            lastActiveTabBitmap = bitmap
            if (old != null && !cache.values.contains(old) && !old.isRecycled) {
                old.recycle()
            }
        }
    }

    fun getLastActiveBitmap(): Bitmap? = lastActiveTabBitmap

    fun remove(tabId: String) {
        val old = cache.remove(tabId)
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
    }

    fun clear() {
        cache.values.forEach { if (!it.isRecycled) it.recycle() }
        cache.clear()
        val old = lastActiveTabBitmap
        lastActiveTabBitmap = null
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
    }
}
