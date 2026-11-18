package app.harvester.companion.settings.addons

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.core.network.UhHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

object UBlockOriginManager {
    const val UBLOCK_ID = "uBlock0@raymondhill.net"
    private const val UBLOCK_SLUG = "ublock-origin"
    private const val PREFS_NAME = "uh_ublock"
    private const val KEY_PAUSED_HOSTS = "paused_hosts"
    private const val KEY_LAST_CHECK = "last_update_check_ms"
    private const val KEY_INSTALLED_VER = "installed_version"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    data class State(
        val isInstalled: Boolean = false,
        val isEnabled: Boolean = true,
        val isUpdating: Boolean = false,
        val version: String = "",
        val pausedHosts: Set<String> = emptySet(),
        val totalBlockedCount: Int = 0,
        val progress: Float = 0f,
        val errorMessage: String? = null
    )

    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var geckoRuntime: GeckoRuntime? = null
    @Volatile
    private var installedExt: WebExtension? = null
    @Volatile
    private var updateJob: Job? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(ctx: Context, runtime: GeckoRuntime) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = ctx.applicationContext
        geckoRuntime = runtime
        ExtensionInstallManager.ensurePromptDelegate(runtime)

        val enabled = SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, true)
        val paused = readPausedHosts(ctx)
        val cachedVer = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_INSTALLED_VER, "") ?: ""
        _state.value = _state.value.copy(
            isEnabled = enabled,
            pausedHosts = paused,
            version = cachedVer
        )

        syncInstalledState { isPresent ->
            val autoUpdate = SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_AUTO_UPDATE, true)
            val lastCheck = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0L)
            val dueForUpdate = autoUpdate && (System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS)
            if (enabled && (!isPresent || dueForUpdate)) {
                checkAndInstallOrUpdate(force = !isPresent)
            }
        }
    }

    fun isSitePaused(url: String): Boolean {
        val host = extractHost(url) ?: return false
        return host in _state.value.pausedHosts
    }

    fun toggleSitePaused(url: String): Boolean {
        val host = extractHost(url) ?: return false
        val ctx = appContext ?: return false
        val next = _state.value.pausedHosts.toMutableSet()
        val nowPaused = if (host in next) {
            next.remove(host)
            false
        } else {
            next.add(host)
            true
        }
        savePausedHosts(ctx, next)
        _state.value = _state.value.copy(pausedHosts = next)
        return nowPaused
    }

    fun setGloballyEnabled(enabled: Boolean) {
        val ctx = appContext ?: return
        val runtime = geckoRuntime ?: return
        ExtensionInstallManager.ensurePromptDelegate(runtime)
        SettingsPrefs.setBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, enabled)
        _state.value = _state.value.copy(isEnabled = enabled)

        mainHandler.post {
            val ext = installedExt
            if (ext != null) {
                if (enabled) {
                    runtime.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER).accept({ updated ->
                        installedExt = updated
                        _state.value = _state.value.copy(isInstalled = true, isEnabled = true, errorMessage = null)
                    }, { err ->
                        _state.value = _state.value.copy(errorMessage = err?.message)
                    })
                } else {
                    runtime.webExtensionController.disable(ext, WebExtensionController.EnableSource.USER).accept({ updated ->
                        installedExt = updated
                        _state.value = _state.value.copy(isEnabled = false, errorMessage = null)
                    }, { err ->
                        _state.value = _state.value.copy(errorMessage = err?.message)
                    })
                }
            } else if (enabled) {
                checkAndInstallOrUpdate(force = true)
            }
        }
    }

    fun recordBlocked(count: Int = 1) {
        if (count <= 0) return
        _state.value = _state.value.copy(totalBlockedCount = _state.value.totalBlockedCount + count)
    }

    fun syncInstalledState(onComplete: ((Boolean) -> Unit)? = null) {
        val runtime = geckoRuntime ?: return
        ExtensionInstallManager.ensurePromptDelegate(runtime)
        mainHandler.post {
            runtime.webExtensionController.list().accept({ list ->
                val found = list?.firstOrNull { it.id == UBLOCK_ID }
                installedExt = found
                if (found != null) {
                    val ver = found.metaData.version
                    val enabled = found.metaData.enabled
                    appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()?.putString(KEY_INSTALLED_VER, ver)?.apply()
                    if (!found.metaData.allowedInPrivateBrowsing) {
                        runtime.webExtensionController.setAllowedInPrivateBrowsing(found, true)
                    }
                    _state.value = _state.value.copy(
                        isInstalled = true,
                        isEnabled = enabled,
                        version = ver,
                        errorMessage = null
                    )
                    onComplete?.invoke(true)
                } else {
                    _state.value = _state.value.copy(isInstalled = false)
                    onComplete?.invoke(false)
                }
            }, {
                onComplete?.invoke(false)
            })
        }
    }

    fun checkAndInstallOrUpdate(force: Boolean = false) {
        val ctx = appContext ?: return
        val runtime = geckoRuntime ?: return
        if (_state.value.isUpdating && updateJob?.isActive == true) return

        ExtensionInstallManager.ensurePromptDelegate(runtime)
        _state.value = _state.value.copy(isUpdating = true, progress = 0.05f, errorMessage = null)

        updateJob = scope.launch {
            val done = AtomicBoolean(false)
            val watchdog = scope.launch {
                delay(45_000L)
                if (done.compareAndSet(false, true)) {
                    _state.value = _state.value.copy(
                        isUpdating = false,
                        progress = 0f,
                        errorMessage = "Package update timed out"
                    )
                }
            }
            try {
                val addon = AmoApiClient.fetchAddon(UBLOCK_SLUG)
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                if (addon == null || addon.xpiUrl.isBlank()) {
                    if (done.compareAndSet(false, true)) {
                        watchdog.cancel()
                        _state.value = _state.value.copy(
                            isUpdating = false,
                            progress = 0f,
                            errorMessage = "Could not reach package catalog"
                        )
                    }
                    return@launch
                }

                val currentVer = _state.value.version
                if (!force && _state.value.isInstalled && currentVer == addon.version) {
                    if (done.compareAndSet(false, true)) {
                        watchdog.cancel()
                        _state.value = _state.value.copy(isUpdating = false, progress = 1f, errorMessage = null)
                    }
                    return@launch
                }

                val extDir = File(ctx.filesDir, "extensions").apply { mkdirs() }
                val targetXpi = File(extDir, "ublock_origin.xpi")
                val tmpXpi = File(extDir, "ublock_origin.xpi.tmp")

                val req = Request.Builder()
                    .url(addon.xpiUrl)
                    .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                    .build()

                var downloaded = false
                UhHttpClient.client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful && resp.body != null) {
                        val body = resp.body!!
                        val total = body.contentLength()
                        var copied = 0L
                        val buf = ByteArray(16 * 1024)
                        body.byteStream().use { input ->
                            tmpXpi.outputStream().use { out ->
                                var read = input.read(buf)
                                while (read >= 0) {
                                    out.write(buf, 0, read)
                                    copied += read
                                    if (total > 0L) {
                                        _state.value = _state.value.copy(
                                            progress = (copied.toFloat() / total.toFloat()).coerceIn(0.1f, 0.9f)
                                        )
                                    }
                                    read = input.read(buf)
                                }
                            }
                        }
                        if (tmpXpi.length() > 10_000L) {
                            if (!tmpXpi.renameTo(targetXpi)) {
                                tmpXpi.copyTo(targetXpi, overwrite = true)
                                tmpXpi.delete()
                            }
                            downloaded = true
                        }
                    }
                }
                if (!downloaded) {
                    tmpXpi.delete()
                }

                val installSource = if (downloaded && targetXpi.isFile) {
                    Uri.fromFile(targetXpi).toString()
                } else {
                    addon.xpiUrl
                }

                _state.value = _state.value.copy(progress = 0.95f)

                mainHandler.post {
                    ExtensionInstallManager.ensurePromptDelegate(runtime)
                    runtime.webExtensionController.install(installSource).accept({ ext ->
                        if (done.compareAndSet(false, true)) {
                            watchdog.cancel()
                            installedExt = ext
                            if (ext != null) {
                                runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                                val prefEnabled = SettingsPrefs.getBool(ctx, SettingsPrefs.K_UBLOCK_ENABLED, true)
                                if (!prefEnabled) {
                                    runtime.webExtensionController.disable(ext, WebExtensionController.EnableSource.USER)
                                }
                                val ver = ext.metaData.version.ifBlank { addon.version }
                                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit().putString(KEY_INSTALLED_VER, ver).apply()
                                _state.value = _state.value.copy(
                                    isInstalled = true,
                                    isEnabled = prefEnabled,
                                    isUpdating = false,
                                    version = ver,
                                    progress = 1f,
                                    errorMessage = null
                                )
                                ExtensionInstallManager.refreshInstalledExtensions(runtime)
                                UHLog.d("ublock", "uBlock Origin ready (v$ver)")
                            } else {
                                _state.value = _state.value.copy(
                                    isUpdating = false,
                                    progress = 0f,
                                    errorMessage = "Installation returned null"
                                )
                            }
                        }
                    }, { err ->
                        UHLog.w("ublock", "Local file install fallback/error: ${err?.message}")
                        if (installSource != addon.xpiUrl) {
                            mainHandler.post {
                                runtime.webExtensionController.install(addon.xpiUrl).accept({ ext ->
                                    if (done.compareAndSet(false, true)) {
                                        watchdog.cancel()
                                        installedExt = ext
                                        if (ext != null) {
                                            runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                                        }
                                        _state.value = _state.value.copy(
                                            isInstalled = ext != null,
                                            isUpdating = false,
                                            version = ext?.metaData?.version ?: addon.version,
                                            progress = if (ext != null) 1f else 0f,
                                            errorMessage = if (ext == null) "Install failed" else null
                                        )
                                        ExtensionInstallManager.refreshInstalledExtensions(runtime)
                                    }
                                }, { err2 ->
                                    if (done.compareAndSet(false, true)) {
                                        watchdog.cancel()
                                        _state.value = _state.value.copy(
                                            isUpdating = false,
                                            progress = 0f,
                                            errorMessage = err2?.message ?: err?.message ?: "Install failed"
                                        )
                                    }
                                })
                            }
                        } else if (done.compareAndSet(false, true)) {
                            watchdog.cancel()
                            _state.value = _state.value.copy(
                                isUpdating = false,
                                progress = 0f,
                                errorMessage = err?.message ?: "Install failed"
                            )
                        }
                    })
                }
            } catch (e: Exception) {
                UHLog.w("ublock", "uBlock Origin auto-install failed: ${e.message}")
                if (done.compareAndSet(false, true)) {
                    watchdog.cancel()
                    _state.value = _state.value.copy(
                        isUpdating = false,
                        progress = 0f,
                        errorMessage = e.message ?: "Update failed"
                    )
                }
            }
        }
    }

    private fun readPausedHosts(ctx: Context): Set<String> {
        val raw = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PAUSED_HOSTS, emptySet())
        return raw?.toSet() ?: emptySet()
    }

    private fun savePausedHosts(ctx: Context, hosts: Set<String>) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PAUSED_HOSTS, hosts).apply()
    }

    private fun extractHost(url: String): String? {
        return try {
            URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }
}
