package app.harvester.companion.core.lifecycle

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import app.harvester.companion.SessionRecorder
import app.harvester.companion.SessionService
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHLog
import app.harvester.companion.browser.tabs.TabGroupStore
import app.harvester.companion.browser.tabs.TabPreviewCache
import app.harvester.companion.components.Components
import coil.Coil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import java.util.concurrent.atomic.AtomicBoolean

object AppSleepManager : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    private const val DEFAULT_SLEEP_DELAY_SEC = 90
    private const val AUTOSAVE_INTERVAL_MS = 30_000L

    private val initialized = AtomicBoolean(false)
    private val componentsAttached = AtomicBoolean(false)
    private lateinit var app: Application
    @Volatile
    private var components: Components? = null
    @Volatile
    private var sessionStorage: SessionStorage? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sleepJob: Job? = null
    private var autoSaveJob: Job? = null

    @Volatile
    private var startedActivities = 0
    @Volatile
    private var isSleeping = false
    @Volatile
    private var didRestore = false

    fun init(application: Application) {
        if (!initialized.compareAndSet(false, true)) return
        app = application
        TabPreviewCache.init(application)
        TabGroupStore.init(application)
        application.registerActivityLifecycleCallbacks(this)
        application.registerComponentCallbacks(this)
    }

    fun attachComponentsAndRestore(appComponents: Components) {
        if (!initialized.get()) {
            init(appComponents.context.applicationContext as Application)
        }
        if (!componentsAttached.compareAndSet(false, true)) return
        components = appComponents
        sessionStorage = SessionStorage(app, appComponents.engine)
        restoreIfNeeded()
        startAutoSaveLoop()
    }

    private fun restoreIfNeeded() {
        val appComponents = components ?: return
        val storage = sessionStorage ?: return
        if (didRestore) return
        didRestore = true
        scope.launch(Dispatchers.IO) {
            try {
                if (appComponents.store.state.tabs.isEmpty()) {
                    val recovered = storage.restore()
                    if (recovered != null && recovered.tabs.isNotEmpty()) {
                        // Never restore stale Private tabs on launch; keep only Normal tabs lazily
                        val normalTabs = recovered.tabs.filter { !it.state.private }
                        if (normalTabs.isNotEmpty()) {
                            val validIds = normalTabs.map { it.state.id }.toSet()
                            val selectedId = recovered.selectedTabId?.takeIf { it in validIds }
                                ?: normalTabs.lastOrNull()?.state?.id
                            withContext(Dispatchers.Main) {
                                if (appComponents.store.state.tabs.isEmpty()) {
                                    appComponents.store.dispatch(
                                        TabListAction.RestoreAction(
                                            tabs = normalTabs,
                                            selectedTabId = selectedId,
                                            restoreLocation = TabListAction.RestoreAction.RestoreLocation.BEGINNING,
                                            tabPartitions = emptyMap(),
                                        )
                                    )
                                    TabGroupStore.cleanupOrphanedTabs(validIds)
                                    TabPreviewCache.pruneToValidIds(validIds)
                                    UHLog.d("lifecycle", "Lazy-restored ${normalTabs.size} normal tab(s) from SessionStorage.")
                                }
                            }
                        } else {
                            TabGroupStore.cleanupOrphanedTabs(emptySet())
                            TabPreviewCache.clear()
                        }
                    }
                }
            } catch (e: Exception) {
                UHLog.throwable("lifecycle_warn", "Failed restoring session snapshot", e)
            }
        }
    }

    private fun startAutoSaveLoop() {
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(AUTOSAVE_INTERVAL_MS)
                val appComponents = components ?: continue
                if (!isSleeping && appComponents.store.state.tabs.any { !it.content.private }) {
                    saveNormalSnapshot(flushEngine = false)
                }
            }
        }
    }

    fun persistNow() {
        if (!initialized.get() || components == null) return
        scope.launch(Dispatchers.IO) {
            saveNormalSnapshot(flushEngine = true)
        }
    }

    fun onTaskRemoved() {
        val appComponents = components ?: return
        val storage = sessionStorage ?: return
        if (!initialized.get()) return
        try {
            runBlocking(Dispatchers.Main) {
                val state = appComponents.store.state
                val hasPrivate = state.tabs.any { it.content.private }
                if (hasPrivate) {
                    appComponents.store.dispatch(TabListAction.RemoveAllPrivateTabsAction)
                    try {
                        appComponents.runtime.storageController.clearDataForSessionContext("private")
                    } catch (_: Exception) {}
                }
                val normalTabs = appComponents.store.state.tabs.filter { !it.content.private }
                normalTabs.forEach { tab ->
                    setGeckoSessionActive(tab.engineState.engineSession, false)
                    if (tab.engineState.engineSession != null) {
                        appComponents.store.dispatch(EngineAction.FlushEngineSessionStateAction(tab.id))
                    }
                }
                TabGroupStore.cleanupOrphanedTabs(normalTabs.map { it.id }.toSet())
                TabPreviewCache.pruneToValidIds(normalTabs.map { it.id }.toSet())
            }
            Thread.sleep(150L)
            val normalState = appComponents.store.state.copy(
                tabs = appComponents.store.state.tabs.filter { !it.content.private }
            )
            storage.save(normalState)
            runBlocking(Dispatchers.Main) {
                appComponents.store.state.tabs.forEach { tab ->
                    if (tab.engineState.engineSession != null) {
                        appComponents.store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
                    }
                }
                TabPreviewCache.trimMemory()
                try {
                    Coil.imageLoader(app).memoryCache?.clear()
                } catch (_: Exception) {}
            }
            UHLog.d("lifecycle", "onTaskRemoved: wiped Private data, saved Normal tabs & freed RAM.")
        } catch (e: Exception) {
            UHLog.w("lifecycle_warn", "onTaskRemoved cleanup issue: ${e.message}")
        }
    }

    private suspend fun saveNormalSnapshot(flushEngine: Boolean) {
        val appComponents = components ?: return
        val storage = sessionStorage ?: return
        try {
            val state = appComponents.store.state
            val normalTabs = state.tabs.filter { !it.content.private }
            if (flushEngine && normalTabs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    normalTabs.forEach { tab ->
                        if (tab.engineState.engineSession != null) {
                            appComponents.store.dispatch(EngineAction.FlushEngineSessionStateAction(tab.id))
                        }
                    }
                }
                delay(200L)
            }
            val normalOnlyState = appComponents.store.state.copy(
                tabs = appComponents.store.state.tabs.filter { !it.content.private },
                selectedTabId = appComponents.store.state.selectedTabId?.takeIf { sid ->
                    appComponents.store.state.tabs.any { it.id == sid && !it.content.private }
                } ?: appComponents.store.state.tabs.lastOrNull { !it.content.private }?.id
            )
            storage.save(normalOnlyState)
            TabGroupStore.cleanupOrphanedTabs(normalTabs.map { it.id }.toSet())
        } catch (e: Exception) {
            UHLog.w("lifecycle_warn", "Session snapshot save skipped: ${e.message}")
        }
    }

    private fun setGeckoSessionActive(engineSession: mozilla.components.concept.engine.EngineSession?, active: Boolean) {
        val geckoWrapper = engineSession as? GeckoEngineSession ?: return
        try {
            val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
            field.isAccessible = true
            val session = field.get(geckoWrapper) as? org.mozilla.geckoview.GeckoSession
            session?.setActive(active)
        } catch (_: Exception) {}
    }

    private fun enterSleepMode() {
        val appComponents = components ?: return
        if (!initialized.get() || startedActivities > 0 || isSleeping) return
        isSleeping = true
        UHLog.d("lifecycle", "Entering low-power sleep mode for background session retention.")
        scope.launch {
            saveNormalSnapshot(flushEngine = true)
            withContext(Dispatchers.Main) {
                val state = appComponents.store.state
                val selectedId = state.selectedTabId
                state.tabs.forEach { tab ->
                    setGeckoSessionActive(tab.engineState.engineSession, false)
                    if (tab.id != selectedId && tab.engineState.engineSession != null) {
                        appComponents.store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
                    }
                }
                try {
                    Coil.imageLoader(app).memoryCache?.clear()
                } catch (_: Exception) {}
            }
        }
    }

    private fun wakeFromSleepMode() {
        sleepJob?.cancel()
        sleepJob = null
        val wasSleeping = isSleeping
        isSleeping = false
        val appComponents = components ?: return
        scope.launch(Dispatchers.Main) {
            val state = appComponents.store.state
            val selectedTab = state.tabs.firstOrNull { it.id == state.selectedTabId }
            if (wasSleeping) {
                setGeckoSessionActive(selectedTab?.engineState?.engineSession, true)
                UHLog.d("lifecycle", "Resumed from low-power sleep mode.")
            }
            SessionService.startOrUpdate(app, isSleeping = false, hasTabs = state.tabs.isNotEmpty())
            appComponents.resumePendingNetworkDownloads()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        val prev = startedActivities
        startedActivities = prev + 1
        if (prev == 0) {
            wakeFromSleepMode()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        val next = (startedActivities - 1).coerceAtLeast(0)
        startedActivities = next
        if (next == 0) {
            val appComponents = components ?: return
            val hasTabs = appComponents.store.state.tabs.isNotEmpty()
            SessionService.startOrUpdate(app, isSleeping = false, hasTabs = hasTabs)
            scope.launch(Dispatchers.IO) {
                saveNormalSnapshot(flushEngine = true)
            }
            val sleepSec = SettingsPrefs.getInt(app, SettingsPrefs.K_TAB_SLEEP_SECONDS, DEFAULT_SLEEP_DELAY_SEC)
            sleepJob?.cancel()
            if (sleepSec > 0) {
                sleepJob = scope.launch {
                    delay(sleepSec * 1000L)
                    enterSleepMode()
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        if (!initialized.get()) return
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                try {
                    Coil.imageLoader(app).memoryCache?.clear()
                    TabPreviewCache.trimMemory()
                } catch (_: Exception) {}
                val appComponents = components ?: return
                if (startedActivities == 0) {
                    sleepJob?.cancel()
                    enterSleepMode()
                } else {
                    scope.launch(Dispatchers.Main) {
                        val state = appComponents.store.state
                        val selectedId = state.selectedTabId
                        state.tabs.filter { it.id != selectedId && it.engineState.engineSession != null }.forEach {
                            appComponents.store.dispatch(EngineAction.SuspendEngineSessionAction(it.id))
                        }
                    }
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                if (startedActivities == 0 && components != null) {
                    scope.launch(Dispatchers.IO) {
                        saveNormalSnapshot(flushEngine = true)
                    }
                }
            }
        }
    }

    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {
        if (SessionRecorder.isActive()) {
            try { SessionRecorder.save(app) } catch (_: Exception) {}
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
