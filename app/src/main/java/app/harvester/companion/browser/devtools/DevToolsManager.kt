package app.harvester.companion.browser.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object DevToolsManager {

    enum class Tab { CONSOLE, NETWORK, ELEMENTS, STORAGE, PERFORMANCE }

    data class ConsoleEntry(
        val id: Long,
        val level: String, // log, info, warn, error, dir, result
        val message: String,
        val source: String,
        val timestampMs: Long
    )

    data class NetworkEntry(
        val id: Long,
        val method: String,
        val url: String,
        val status: Int,
        val statusText: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val reqType: String,
        val requestHeaders: Map<String, String>,
        val responseHeaders: Map<String, String>,
        val responsePreview: String,
        val timestampMs: Long
    )

    data class DomNodeSummary(
        val tag: String,
        val idAttr: String,
        val classes: String,
        val depth: Int,
        val childCount: Int,
        val selector: String,
        val textPreview: String
    )

    data class DomSnapshot(
        val title: String,
        val url: String,
        val doctype: String,
        val charset: String,
        val viewport: String,
        val nodeCount: Int,
        val nodes: List<DomNodeSummary>,
        val inspectedSelector: String = "",
        val inspectedComputed: Map<String, String> = emptyMap(),
        val inspectedInlineStyle: String = ""
    )

    data class StorageItem(
        val key: String,
        val value: String
    )

    data class StorageSnapshot(
        val origin: String = "",
        val localItems: List<StorageItem> = emptyList(),
        val sessionItems: List<StorageItem> = emptyList(),
        val cookies: List<StorageItem> = emptyList()
    )

    data class PerfSnapshot(
        val dnsMs: Long = 0,
        val tcpMs: Long = 0,
        val ttfbMs: Long = 0,
        val domInteractiveMs: Long = 0,
        val domCompleteMs: Long = 0,
        val loadEventMs: Long = 0,
        val resourceCount: Int = 0,
        val transferKb: Long = 0,
        val memoryMb: Float = 0f
    )

    private val seq = AtomicLong(1L)
    private const val MAX_CONSOLE = 400
    private const val MAX_NETWORK = 300

    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _activeTab = MutableStateFlow(Tab.CONSOLE)
    val activeTab: StateFlow<Tab> = _activeTab.asStateFlow()

    private val _erudaActive = MutableStateFlow(false)
    val erudaActive: StateFlow<Boolean> = _erudaActive.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val consoleLogs: StateFlow<List<ConsoleEntry>> = _consoleLogs.asStateFlow()

    private val _networkLogs = MutableStateFlow<List<NetworkEntry>>(emptyList())
    val networkLogs: StateFlow<List<NetworkEntry>> = _networkLogs.asStateFlow()

    private val _domSnapshot = MutableStateFlow<DomSnapshot?>(null)
    val domSnapshot: StateFlow<DomSnapshot?> = _domSnapshot.asStateFlow()

    private val _storageSnapshot = MutableStateFlow(StorageSnapshot())
    val storageSnapshot: StateFlow<StorageSnapshot> = _storageSnapshot.asStateFlow()

    private val _perfSnapshot = MutableStateFlow<PerfSnapshot?>(null)
    val perfSnapshot: StateFlow<PerfSnapshot?> = _perfSnapshot.asStateFlow()

    fun setOpen(open: Boolean) {
        _isOpen.value = open
    }

    fun selectTab(tab: Tab) {
        _activeTab.value = tab
    }

    fun setErudaActive(active: Boolean) {
        _erudaActive.value = active
    }

    fun clearConsole() {
        _consoleLogs.value = emptyList()
    }

    fun clearNetwork() {
        _networkLogs.value = emptyList()
    }

    fun appendConsole(level: String, message: String, source: String = "page") {
        val entry = ConsoleEntry(
            id = seq.getAndIncrement(),
            level = level.lowercase().ifBlank { "log" },
            message = message,
            source = source,
            timestampMs = System.currentTimeMillis()
        )
        val cur = _consoleLogs.value
        _consoleLogs.value = (cur + entry).takeLast(MAX_CONSOLE)
    }

    fun appendNetwork(json: JSONObject) {
        val reqHdrs = mutableMapOf<String, String>()
        json.optJSONObject("reqHeaders")?.let { obj ->
            for (k in obj.keys()) reqHdrs[k] = obj.optString(k)
        }
        val respHdrs = mutableMapOf<String, String>()
        json.optJSONObject("respHeaders")?.let { obj ->
            for (k in obj.keys()) respHdrs[k] = obj.optString(k)
        }
        val entry = NetworkEntry(
            id = seq.getAndIncrement(),
            method = json.optString("method", "GET").uppercase(),
            url = json.optString("url"),
            status = json.optInt("status", 0),
            statusText = json.optString("statusText"),
            durationMs = json.optLong("durationMs", 0L),
            sizeBytes = json.optLong("sizeBytes", 0L),
            reqType = json.optString("reqType", "fetch"),
            requestHeaders = reqHdrs,
            responseHeaders = respHdrs,
            responsePreview = json.optString("preview").take(4000),
            timestampMs = System.currentTimeMillis()
        )
        val cur = _networkLogs.value
        _networkLogs.value = (cur + entry).takeLast(MAX_NETWORK)
    }

    fun updateDom(json: JSONObject) {
        val arr = json.optJSONArray("nodes") ?: JSONArray()
        val nodes = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    DomNodeSummary(
                        tag = o.optString("tag"),
                        idAttr = o.optString("id"),
                        classes = o.optString("cls"),
                        depth = o.optInt("depth", 0),
                        childCount = o.optInt("children", 0),
                        selector = o.optString("selector"),
                        textPreview = o.optString("text")
                    )
                )
            }
        }
        val computedMap = linkedMapOf<String, String>()
        json.optJSONObject("computed")?.let { c ->
            for (k in c.keys()) computedMap[k] = c.optString(k)
        }
        _domSnapshot.value = DomSnapshot(
            title = json.optString("title"),
            url = json.optString("url"),
            doctype = json.optString("doctype", "html"),
            charset = json.optString("charset", "UTF-8"),
            viewport = json.optString("viewport"),
            nodeCount = json.optInt("nodeCount", 0),
            nodes = nodes,
            inspectedSelector = json.optString("inspectedSelector"),
            inspectedComputed = computedMap,
            inspectedInlineStyle = json.optString("inspectedInlineStyle")
        )
    }

    fun updateStorage(json: JSONObject) {
        fun parseItems(arr: JSONArray?): List<StorageItem> {
            if (arr == null) return emptyList()
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(StorageItem(o.optString("k"), o.optString("v")))
                }
            }
        }
        _storageSnapshot.value = StorageSnapshot(
            origin = json.optString("origin"),
            localItems = parseItems(json.optJSONArray("local")),
            sessionItems = parseItems(json.optJSONArray("session")),
            cookies = parseItems(json.optJSONArray("cookies"))
        )
    }

    fun updatePerf(json: JSONObject) {
        _perfSnapshot.value = PerfSnapshot(
            dnsMs = json.optLong("dnsMs", 0L),
            tcpMs = json.optLong("tcpMs", 0L),
            ttfbMs = json.optLong("ttfbMs", 0L),
            domInteractiveMs = json.optLong("domInteractiveMs", 0L),
            domCompleteMs = json.optLong("domCompleteMs", 0L),
            loadEventMs = json.optLong("loadEventMs", 0L),
            resourceCount = json.optInt("resourceCount", 0),
            transferKb = json.optLong("transferKb", 0L),
            memoryMb = json.optDouble("memoryMb", 0.0).toFloat()
        )
    }
}
