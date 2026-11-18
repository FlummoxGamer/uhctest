package app.harvester.companion.settings.addons

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object ExtensionInstallManager {

    enum class Status {
        DOWNLOADING,
        INSTALLING,
        INSTALLED,
        FAILED
    }

    data class InstallRecord(
        val key: String,
        val displayName: String,
        val xpiUrl: String,
        val status: Status,
        val progress: Float = 0f,
        val errorMessage: String? = null,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    val BUILTIN_INTERNAL_IDS = setOf(
        "uh-bridge@harvester.app",
        "header-injector@harvester.app"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val promptDelegateRegistered = AtomicBoolean(false)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _recordsFlow = MutableStateFlow<Map<String, InstallRecord>>(emptyMap())
    val recordsFlow: StateFlow<Map<String, InstallRecord>> = _recordsFlow.asStateFlow()

    private val _installedExtensionsFlow = MutableStateFlow<List<WebExtension>>(emptyList())
    val installedExtensionsFlow: StateFlow<List<WebExtension>> = _installedExtensionsFlow.asStateFlow()

    private val _installedIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    val installedIdsFlow: StateFlow<Set<String>> = _installedIdsFlow.asStateFlow()

    fun ensurePromptDelegate(runtime: GeckoRuntime) {
        if (!promptDelegateRegistered.compareAndSet(false, true)) {
            if (runtime.webExtensionController.promptDelegate != null) return
        }
        mainHandler.post {
            try {
                runtime.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
                    override fun onInstallPromptRequest(
                        extension: WebExtension,
                        permissions: Array<out String>,
                        origins: Array<out String>,
                        dataCollectionPermissions: Array<out String>
                    ): GeckoResult<WebExtension.PermissionPromptResponse> {
                        return GeckoResult.fromValue(
                            WebExtension.PermissionPromptResponse(true, true, false)
                        )
                    }

                    override fun onUpdatePrompt(
                        extension: WebExtension,
                        newPermissions: Array<out String>,
                        newOrigins: Array<out String>,
                        newDataCollectionPermissions: Array<out String>
                    ): GeckoResult<AllowOrDeny> {
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }

                    override fun onOptionalPrompt(
                        extension: WebExtension,
                        permissions: Array<out String>,
                        origins: Array<out String>,
                        dataCollectionPermissions: Array<out String>
                    ): GeckoResult<AllowOrDeny> {
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }
                }
            } catch (e: Exception) {
                promptDelegateRegistered.set(false)
                UHLog.w("ext", "Failed setting WebExtension PromptDelegate: ${e.message}")
            }
        }
    }

    fun refreshInstalledExtensions(runtime: GeckoRuntime, onError: ((String) -> Unit)? = null) {
        ensurePromptDelegate(runtime)
        mainHandler.post {
            runtime.webExtensionController.list().accept({ extList ->
                val filtered = extList?.filter {
                    it.id !in BUILTIN_INTERNAL_IDS && !it.isBuiltIn
                } ?: emptyList()
                val ids = extList?.map { it.id }?.toSet() ?: emptySet()
                _installedExtensionsFlow.value = filtered
                _installedIdsFlow.value = ids
            }, { err ->
                onError?.invoke(err?.message ?: "Failed to load extensions")
            })
        }
    }

    fun dismissRecord(key: String) {
        _recordsFlow.value = _recordsFlow.value - key
    }

    fun enqueueInstall(
        ctx: Context,
        runtime: GeckoRuntime,
        key: String,
        displayName: String,
        xpiUrl: String
    ) {
        val appCtx = ctx.applicationContext
        ensurePromptDelegate(runtime)
        if (xpiUrl.isBlank()) {
            updateRecord(
                InstallRecord(
                    key = key,
                    displayName = displayName,
                    xpiUrl = xpiUrl,
                    status = Status.FAILED,
                    errorMessage = "Invalid package URL"
                )
            )
            return
        }
        val existing = activeJobs[key]
        if (existing != null && existing.isActive) return

        val job = scope.launch {
            try {
                updateRecord(
                    InstallRecord(
                        key = key,
                        displayName = displayName,
                        xpiUrl = xpiUrl,
                        status = Status.DOWNLOADING,
                        progress = 0.05f
                    )
                )

                val sourceToInstall: String = if (xpiUrl.startsWith("http://", true) || xpiUrl.startsWith("https://", true)) {
                    val safeName = key.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
                    val cacheDir = File(appCtx.filesDir, "extensions_cache").apply { mkdirs() }
                    val targetXpi = File(cacheDir, "$safeName.xpi")
                    val tmpXpi = File(cacheDir, "$safeName.xpi.tmp")

                    val req = Request.Builder()
                        .url(xpiUrl)
                        .header("User-Agent", UhHttpClient.DEFAULT_USER_AGENT)
                        .build()

                    UhHttpClient.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful || resp.body == null) {
                            throw IllegalStateException("Download HTTP ${resp.code}")
                        }
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
                                        updateRecord(
                                            InstallRecord(
                                                key = key,
                                                displayName = displayName,
                                                xpiUrl = xpiUrl,
                                                status = Status.DOWNLOADING,
                                                progress = (copied.toFloat() / total.toFloat()).coerceIn(0.05f, 0.92f)
                                            )
                                        )
                                    }
                                    read = input.read(buf)
                                }
                            }
                        }
                    }

                    if (!tmpXpi.exists() || tmpXpi.length() < 512L) {
                        tmpXpi.delete()
                        throw IllegalStateException("Corrupt or empty package")
                    }
                    if (!tmpXpi.renameTo(targetXpi)) {
                        tmpXpi.copyTo(targetXpi, overwrite = true)
                        tmpXpi.delete()
                    }
                    Uri.fromFile(targetXpi).toString()
                } else {
                    xpiUrl
                }

                updateRecord(
                    InstallRecord(
                        key = key,
                        displayName = displayName,
                        xpiUrl = xpiUrl,
                        status = Status.INSTALLING,
                        progress = 0.95f
                    )
                )

                val installCompleted = AtomicBoolean(false)
                // Watchdog so GeckoView install can never hang indefinitely
                val watchdog = scope.launch {
                    delay(30_000L)
                    if (installCompleted.compareAndSet(false, true)) {
                        updateRecord(
                            InstallRecord(
                                key = key,
                                displayName = displayName,
                                xpiUrl = xpiUrl,
                                status = Status.FAILED,
                                errorMessage = "Install timed out. Tap retry."
                            )
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    ensurePromptDelegate(runtime)
                    runtime.webExtensionController.install(sourceToInstall).accept({ ext ->
                        if (installCompleted.compareAndSet(false, true)) {
                            watchdog.cancel()
                            if (ext != null) {
                                runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                                updateRecord(
                                    InstallRecord(
                                        key = key,
                                        displayName = displayName,
                                        xpiUrl = xpiUrl,
                                        status = Status.INSTALLED,
                                        progress = 1f
                                    )
                                )
                                refreshInstalledExtensions(runtime)
                                UBlockOriginManager.syncInstalledState()
                                Toast.makeText(appCtx, "$displayName installed", Toast.LENGTH_SHORT).show()
                            } else {
                                updateRecord(
                                    InstallRecord(
                                        key = key,
                                        displayName = displayName,
                                        xpiUrl = xpiUrl,
                                        status = Status.FAILED,
                                        errorMessage = "Engine returned empty add-on"
                                    )
                                )
                            }
                        }
                    }, { err ->
                        // Fallback directly to URL if local file:// URI rejected
                        if (sourceToInstall != xpiUrl && xpiUrl.startsWith("http", true)) {
                            runtime.webExtensionController.install(xpiUrl).accept({ ext ->
                                if (installCompleted.compareAndSet(false, true)) {
                                    watchdog.cancel()
                                    if (ext != null) {
                                        runtime.webExtensionController.setAllowedInPrivateBrowsing(ext, true)
                                        updateRecord(
                                            InstallRecord(
                                                key = key,
                                                displayName = displayName,
                                                xpiUrl = xpiUrl,
                                                status = Status.INSTALLED,
                                                progress = 1f
                                            )
                                        )
                                        refreshInstalledExtensions(runtime)
                                        UBlockOriginManager.syncInstalledState()
                                        Toast.makeText(appCtx, "$displayName installed", Toast.LENGTH_SHORT).show()
                                    } else {
                                        updateRecord(
                                            InstallRecord(
                                                key = key,
                                                displayName = displayName,
                                                xpiUrl = xpiUrl,
                                                status = Status.FAILED,
                                                errorMessage = err?.message ?: "Install failed"
                                            )
                                        )
                                    }
                                }
                            }, { err2 ->
                                if (installCompleted.compareAndSet(false, true)) {
                                    watchdog.cancel()
                                    updateRecord(
                                        InstallRecord(
                                            key = key,
                                            displayName = displayName,
                                            xpiUrl = xpiUrl,
                                            status = Status.FAILED,
                                            errorMessage = err2?.message ?: err?.message ?: "Install failed"
                                        )
                                    )
                                }
                            })
                        } else if (installCompleted.compareAndSet(false, true)) {
                            watchdog.cancel()
                            updateRecord(
                                InstallRecord(
                                    key = key,
                                    displayName = displayName,
                                    xpiUrl = xpiUrl,
                                    status = Status.FAILED,
                                    errorMessage = err?.message ?: "Install failed"
                                )
                            )
                        }
                    })
                }
            } catch (e: Exception) {
                UHLog.w("ext", "Background extension install failed for $displayName: ${e.message}")
                updateRecord(
                    InstallRecord(
                        key = key,
                        displayName = displayName,
                        xpiUrl = xpiUrl,
                        status = Status.FAILED,
                        errorMessage = e.message ?: "Download failed"
                    )
                )
            } finally {
                activeJobs.remove(key)
            }
        }
        activeJobs[key] = job
    }

    private fun updateRecord(record: InstallRecord) {
        val current = _recordsFlow.value.toMutableMap()
        current[record.key] = record
        _recordsFlow.value = current
    }
}
