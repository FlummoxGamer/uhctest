package app.harvester.companion.browser.translate

import android.content.Context
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import kotlin.coroutines.resume

object TranslationManager {

    data class LanguageOption(
        val code: String,
        val displayName: String,
        val mlKitTag: String
    )

    val supportedLanguages = listOf(
        LanguageOption("en", "English", TranslateLanguage.ENGLISH),
        LanguageOption("es", "Spanish", TranslateLanguage.SPANISH),
        LanguageOption("fr", "French", TranslateLanguage.FRENCH),
        LanguageOption("de", "German", TranslateLanguage.GERMAN),
        LanguageOption("ja", "Japanese", TranslateLanguage.JAPANESE),
        LanguageOption("ko", "Korean", TranslateLanguage.KOREAN),
        LanguageOption("zh", "Chinese", TranslateLanguage.CHINESE),
        LanguageOption("hi", "Hindi", TranslateLanguage.HINDI),
        LanguageOption("ar", "Arabic", TranslateLanguage.ARABIC),
        LanguageOption("pt", "Portuguese", TranslateLanguage.PORTUGUESE),
        LanguageOption("ru", "Russian", TranslateLanguage.RUSSIAN),
        LanguageOption("it", "Italian", TranslateLanguage.ITALIAN),
        LanguageOption("tr", "Turkish", TranslateLanguage.TURKISH),
        LanguageOption("vi", "Vietnamese", TranslateLanguage.VIETNAMESE),
        LanguageOption("id", "Indonesian", TranslateLanguage.INDONESIAN),
        LanguageOption("th", "Thai", TranslateLanguage.THAI),
        LanguageOption("nl", "Dutch", TranslateLanguage.DUTCH),
        LanguageOption("pl", "Polish", TranslateLanguage.POLISH),
        LanguageOption("uk", "Ukrainian", TranslateLanguage.UKRAINIAN)
    )

    private val _showBar = MutableStateFlow(false)
    val showBar: StateFlow<Boolean> = _showBar.asStateFlow()

    private val _showSettingsSheet = MutableStateFlow(false)
    val showSettingsSheet: StateFlow<Boolean> = _showSettingsSheet.asStateFlow()

    private val _isTranslating = MutableStateFlow(false)
    val isTranslating: StateFlow<Boolean> = _isTranslating.asStateFlow()

    private val _isTranslated = MutableStateFlow(false)
    val isTranslated: StateFlow<Boolean> = _isTranslated.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    private val _downloadingModel = MutableStateFlow<String?>(null)
    val downloadingModel: StateFlow<String?> = _downloadingModel.asStateFlow()

    fun openAndTranslate(ctx: Context) {
        _showBar.value = true
        val lang = SettingsPrefs.getString(ctx, SettingsPrefs.K_TRANSLATE_LANG, "en")
        translateCurrentPage(lang)
    }

    fun closeBar() {
        restoreOriginal()
        _showBar.value = false
    }

    fun openSettings() {
        refreshDownloadedModels()
        _showSettingsSheet.value = true
    }

    fun closeSettings() {
        _showSettingsSheet.value = false
    }

    fun translateCurrentPage(targetLang: String) {
        _lastError.value = null
        _isTranslating.value = true
        GeckoWebExtensionBridge.triggerTranslatePage(targetLang)
    }

    fun restoreOriginal() {
        _isTranslating.value = false
        _isTranslated.value = false
        _lastError.value = null
        GeckoWebExtensionBridge.restoreTranslatedPage()
    }

    fun markCompleted(success: Boolean, error: String? = null) {
        _isTranslating.value = false
        if (success) {
            _isTranslated.value = true
            _lastError.value = null
        } else if (!error.isNullOrBlank()) {
            _lastError.value = error
        }
    }

    fun languageDisplayName(code: String): String =
        supportedLanguages.firstOrNull { it.code.equals(code, ignoreCase = true) }?.displayName
            ?: code.uppercase()

    suspend fun translateBatch(
        ctx: Context,
        texts: List<String>,
        targetLang: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val engine = SettingsPrefs.getString(ctx, SettingsPrefs.K_TRANSLATE_ENGINE, SettingsPrefs.TRANSLATE_ENGINE_DIRECT)
        if (engine == SettingsPrefs.TRANSLATE_ENGINE_MLKIT) {
            translateViaMlKit(texts, targetLang)
        } else {
            translateViaDirectApi(texts, targetLang)
        }
    }

    private fun translateViaDirectApi(texts: List<String>, targetLang: String): List<String> {
        val out = MutableList(texts.size) { texts[it] }
        val tl = targetLang.ifBlank { "en" }
        // Group strings under ~1800 encoded chars per single HTTP call using rare separator
        val sep = "\n⟦UH_SEP⟧\n"
        val chunks = mutableListOf<Pair<List<Int>, String>>()
        val currentIdx = mutableListOf<Int>()
        val sb = StringBuilder()

        texts.forEachIndexed { idx, s ->
            if (currentIdx.isNotEmpty() && sb.length + s.length > 1400) {
                chunks.add(currentIdx.toList() to sb.toString())
                currentIdx.clear()
                sb.clear()
            }
            if (currentIdx.isNotEmpty()) sb.append(sep)
            currentIdx.add(idx)
            sb.append(s)
        }
        if (currentIdx.isNotEmpty()) {
            chunks.add(currentIdx.toList() to sb.toString())
        }

        for ((indices, payload) in chunks) {
            val translatedJoined = fetchGoogleGtxBatch(payload, tl)
                ?: fetchLingvaBatch(payload, tl)
            if (!translatedJoined.isNullOrBlank()) {
                val parts = translatedJoined.split(Regex("\\s*⟦UH_SEP⟧\\s*"))
                if (parts.size == indices.size) {
                    indices.forEachIndexed { j, origIndex ->
                        val cleaned = parts[j].trim()
                        if (cleaned.isNotEmpty()) out[origIndex] = cleaned
                    }
                } else {
                    // Fallback line-by-line within small chunk if delimiter altered
                    for (origIndex in indices) {
                        val single = fetchGoogleGtxBatch(texts[origIndex], tl)
                        if (!single.isNullOrBlank()) out[origIndex] = single
                    }
                }
            }
        }
        return out
    }

    private fun fetchGoogleGtxBatch(text: String, tl: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${URLEncoder.encode(tl, "UTF-8")}&dt=t&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            UhHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                val root = JSONArray(body)
                val segs = root.optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (i in 0 until segs.length()) {
                    val seg = segs.optJSONArray(i) ?: continue
                    val part = seg.optString(0)
                    if (part.isNotEmpty()) sb.append(part)
                }
                sb.toString().takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            UHLog.w("translate_warn", "Direct API fallback needed: ${e.message}")
            null
        }
    }

    private fun fetchLingvaBatch(text: String, tl: String): String? {
        return try {
            val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
            val url = "https://lingva.ml/api/v1/auto/${URLEncoder.encode(tl, "UTF-8")}/$encoded"
            val req = Request.Builder().url(url).build()
            UhHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                org.json.JSONObject(body).optString("translation").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun translateViaMlKit(texts: List<String>, targetLang: String): List<String> {
        val targetTag = supportedLanguages.firstOrNull { it.code.equals(targetLang, true) }?.mlKitTag
            ?: TranslateLanguage.ENGLISH
        // Identify likely source language or default English -> target / fallback auto check via Latin/CJK heuristic
        val sample = texts.take(5).joinToString(" ")
        val srcTag = guessSourceMlKitTag(sample, targetTag)
        if (srcTag == targetTag) return texts

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcTag)
            .setTargetLanguage(targetTag)
            .build()
        val client = Translation.getClient(options)
        return try {
            val downloaded = awaitTaskBoolean {
                client.downloadModelIfNeeded(DownloadConditions.Builder().build())
            }
            if (!downloaded) {
                _lastError.value = "Failed downloading offline ML Kit pack ($targetLang)"
                return texts
            }
            refreshDownloadedModels()
            texts.map { item ->
                awaitTaskString { client.translate(item) } ?: item
            }
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun guessSourceMlKitTag(sample: String, targetTag: String): String {
        for (ch in sample) {
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> return TranslateLanguage.JAPANESE
                Character.UnicodeBlock.HANGUL_SYLLABLES -> return TranslateLanguage.KOREAN
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> return TranslateLanguage.CHINESE
                Character.UnicodeBlock.ARABIC -> return TranslateLanguage.ARABIC
                Character.UnicodeBlock.DEVANAGARI -> return TranslateLanguage.HINDI
                Character.UnicodeBlock.CYRILLIC -> return TranslateLanguage.RUSSIAN
                Character.UnicodeBlock.THAI -> return TranslateLanguage.THAI
            }
        }
        return if (targetTag == TranslateLanguage.ENGLISH) TranslateLanguage.SPANISH else TranslateLanguage.ENGLISH
    }

    fun refreshDownloadedModels() {
        val modelManager = RemoteModelManager.getInstance()
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                _downloadedModels.value = models.map { it.language }.toSet()
            }
    }

    fun downloadOfflineModel(mlKitTag: String) {
        _downloadingModel.value = mlKitTag
        val model = TranslateRemoteModel.Builder(mlKitTag).build()
        RemoteModelManager.getInstance()
            .download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener {
                _downloadingModel.value = null
                refreshDownloadedModels()
            }
            .addOnFailureListener { e ->
                _downloadingModel.value = null
                _lastError.value = e.message ?: "Model download failed"
            }
    }

    fun deleteOfflineModel(mlKitTag: String) {
        val model = TranslateRemoteModel.Builder(mlKitTag).build()
        RemoteModelManager.getInstance()
            .deleteDownloadedModel(model)
            .addOnSuccessListener { refreshDownloadedModels() }
    }

    private suspend fun awaitTaskBoolean(block: () -> com.google.android.gms.tasks.Task<Void>): Boolean =
        suspendCancellableCoroutine { cont ->
            block()
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }

    private suspend fun awaitTaskString(block: () -> com.google.android.gms.tasks.Task<String>): String? =
        suspendCancellableCoroutine { cont ->
            block()
                .addOnSuccessListener { res -> if (cont.isActive) cont.resume(res) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
}
