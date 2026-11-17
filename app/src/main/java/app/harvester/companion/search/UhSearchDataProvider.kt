package app.harvester.companion.search

import android.content.ClipboardManager
import android.content.Context
import android.text.Html
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.search.SearchSuggestionClient
import app.harvester.companion.search.model.SuggestionType
import app.harvester.companion.search.model.UhSearchListItem
import app.harvester.companion.utils.SearchHistoryUtils
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import org.json.JSONArray
import org.json.JSONObject

object UhSearchDataProvider {
    private val suggestionClient = SearchSuggestionClient()

    data class SearchFetchResult(
        val items: List<UhSearchListItem>,
        val clipboardUrl: String?,
        val currentTab: UhSearchListItem.CurrentTab?
    )

    suspend fun fetchData(context: Context, query: String): SearchFetchResult = withContext(Dispatchers.IO) {
        val items = mutableListOf<UhSearchListItem>()
        var clipboardUrl: String? = null

        if (SettingsPrefs.getBool(context, SettingsPrefs.K_SEARCH_CLIPBOARD, true)) {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (clipText?.startsWith("http://") == true || clipText?.startsWith("https://") == true) {
                    clipboardUrl = clipText
                }
            } catch (e: Exception) {
                UHLog.e("search_error", "Clipboard read failed: ${e.message}")
            }
        }

        val components = (context.applicationContext as UHApp).components
        val selectedTab = components.store.state.selectedTab
        val currentTabItem = if (
            selectedTab != null &&
            selectedTab.content.url != "about:blank" &&
            selectedTab.content.url.isNotBlank()
        ) {
            UhSearchListItem.CurrentTab(
                title = selectedTab.content.title.ifBlank { selectedTab.content.url },
                url = selectedTab.content.url,
                icon = selectedTab.content.icon
            )
        } else {
            null
        }

        val activeEngine = SearchEnginePrefs.currentEntry(context)

        if (query.isBlank()) {
            val recentTerms = linkedMapOf<String, Pair<Long, String>>()
            try {
                val metadata = components.placesHistoryStorage.getHistoryMetadataSince(0L)
                metadata.asSequence()
                    .filter { !it.key.searchTerm.isNullOrBlank() }
                    .forEach { item ->
                        val cleaned = SearchHistoryUtils.stripEngineSuffix(item.key.searchTerm.orEmpty())
                        if (cleaned.isNotBlank()) {
                            val key = cleaned.lowercase()
                            val existing = recentTerms[key]
                            if (existing == null || item.createdAt > existing.first) {
                                recentTerms[key] = item.createdAt to cleaned
                            }
                        }
                    }

                val visits = components.placesHistoryStorage.getDetailedVisits(0L, System.currentTimeMillis())
                visits.asSequence()
                    .filter { SearchHistoryUtils.isSearchUrl(context, it.url) }
                    .forEach { visit ->
                        val parsed = SearchHistoryUtils.extractSearchQuery(context, visit.url, visit.title)
                        if (!parsed.isNullOrBlank()) {
                            val key = parsed.lowercase()
                            val existing = recentTerms[key]
                            if (existing == null || visit.visitTime > existing.first) {
                                recentTerms[key] = visit.visitTime to parsed
                            }
                        }
                    }
            } catch (e: Exception) {
                UHLog.e("search_error", "Failed to fetch recent searches: ${e.message}")
            }

            val topRecent = recentTerms.values
                .sortedByDescending { it.first }
                .map { it.second }
                .take(5)

            if (topRecent.isNotEmpty()) {
                items.add(UhSearchListItem.Header("Recent Searches"))
                topRecent.forEach { term ->
                    items.add(UhSearchListItem.Suggestion(term, SuggestionType.HISTORY))
                }
            }

            val trendingUrl = activeEngine.trendingUrl
            if (!trendingUrl.isNullOrBlank()) {
                val trends = fetchTrending(trendingUrl)
                if (trends.isNotEmpty()) {
                    items.add(UhSearchListItem.Header("Trending"))
                    trends.take(5).forEach { trend ->
                        items.add(UhSearchListItem.Suggestion(trend, SuggestionType.TRENDING))
                    }
                }
            }
        } else {
            items.add(UhSearchListItem.Header("Search"))
            try {
                val remoteSuggestions = suggestionClient.getSuggestions(activeEngine.id, activeEngine.suggestionsUrl, query)
                if (remoteSuggestions.isNullOrEmpty()) {
                    items.add(UhSearchListItem.Suggestion(query, SuggestionType.SEARCH))
                } else {
                    remoteSuggestions.take(5).forEach { sug ->
                        items.add(UhSearchListItem.Suggestion(sug, SuggestionType.SEARCH))
                    }
                }
            } catch (e: Exception) {
                UHLog.w("search_warn", "Remote suggestions failed: ${e.message}")
                items.add(UhSearchListItem.Suggestion(query, SuggestionType.SEARCH))
            }

            if (SettingsPrefs.getBool(context, SettingsPrefs.K_SEARCH_HISTORY, true)) {
                try {
                    val visits = components.placesHistoryStorage.getDetailedVisits(0L, System.currentTimeMillis())
                    val historyMatches = visits
                        .asSequence()
                        .filter {
                            !SearchHistoryUtils.isSearchUrl(context, it.url) &&
                                (it.url.contains(query, ignoreCase = true) ||
                                    (it.title?.contains(query, ignoreCase = true) == true))
                        }
                        .sortedByDescending { it.visitTime }
                        .distinctBy { it.url }
                        .take(5)
                        .toList()

                    if (historyMatches.isNotEmpty()) {
                        items.add(UhSearchListItem.Header("History"))
                        historyMatches.forEach { visit ->
                            val cleanTitle = visit.title?.let { SearchHistoryUtils.stripEngineSuffix(it) }
                                ?.takeIf { it.isNotBlank() } ?: visit.url
                            items.add(UhSearchListItem.History(cleanTitle, visit.url, null))
                        }
                    }
                } catch (e: Exception) {
                    UHLog.e("search_error", "Local history search failed: ${e.message}")
                }
            }
        }

        SearchFetchResult(items = items, clipboardUrl = clipboardUrl, currentTab = currentTabItem)
    }

    private fun fetchTrending(endpoint: String): List<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                connectTimeout = 4000
                readTimeout = 4000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                UHLog.w("search_warn", "Trending endpoint returned HTTP $code")
                return emptyList()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank()) return emptyList()
            parseTrendingBody(body)
        } catch (e: Exception) {
            UHLog.w("search_warn", "Trending fetch failed: ${e.message}")
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseTrendingBody(body: String): List<String> {
        val trimmed = body.trimStart()
        val out = mutableListOf<String>()
        if (trimmed.startsWith("<")) {
            val itemRegex = Regex("<item>[\\s\\S]*?<title>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?<\\/title>[\\s\\S]*?<\\/item>", RegexOption.IGNORE_CASE)
            for (match in itemRegex.findAll(trimmed)) {
                val decoded = Html.fromHtml(match.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString().trim()
                if (decoded.isNotBlank()) out.add(decoded)
            }
        } else if (trimmed.startsWith("[")) {
            try {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i).trim()
                    if (v.isNotBlank()) out.add(v)
                }
            } catch (e: Exception) {
                UHLog.w("search_warn", "Failed parsing JSON array trending response: ${e.message}")
            }
        } else if (trimmed.startsWith("{")) {
            try {
                val obj = JSONObject(trimmed)
                val candidates = listOf("topics", "Topics", "items", "data", "trends", "trending")
                for (key in candidates) {
                    val arr = obj.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        val el = arr.opt(i)
                        when (el) {
                            is String -> if (el.isNotBlank()) out.add(el.trim())
                            is JSONObject -> {
                                val title = el.optString("query")
                                    .ifBlank { el.optString("title") }
                                    .ifBlank { el.optString("Title") }
                                    .ifBlank { el.optString("name") }
                                    .trim()
                                if (title.isNotBlank()) out.add(title)
                            }
                        }
                    }
                    if (out.isNotEmpty()) break
                }
            } catch (e: Exception) {
                UHLog.w("search_warn", "Failed parsing JSON object trending response: ${e.message}")
            }
        }
        return out.distinctBy { it.lowercase() }.take(5)
    }
}
