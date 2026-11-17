package app.harvester.companion.core.lifecycle

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import app.harvester.companion.SessionRecorder
import app.harvester.companion.SessionService
import app.harvester.companion.UHLog
import app.harvester.companion.components.Components
import coil.Coil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.action.TabListAction
import java.util.concurrent.atomic.AtomicBoolean

object AppSleepManager : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    private const val SLEEP_DELAY_MS = 90_000L
    private const val AUTOSAVE_INTERVAL_MS = 30_000L

    private val initialized = AtomicBoolean(false)
    private lateinit var app: Application
    private lateinit var components: Components
    private lateinit var sessionStorage: SessionStorage

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sleepJob: Job? = null
    private var autoSaveJob: Job? = null

    @Volatile
    private var startedActivities = 0
    @Volatile
    private var isSleeping = false
    @Volatile
    private var didRestore = false

    fun init(application: Application, appComponents: Components) {
        if (!initialized.compareAndSet(false, true)) return
        app = application
        components = appComponents
        sessionStorage = SessionStorage(application, appComponents.engine)
        application.registerActivityLifecycleCallbacks(this)
        application.registerComponentCallbacks(this)
        restoreIfNeeded()
        startAutoSaveLoop()
    }

    private fun restoreIfNeeded() {
        if (didRestore) return
        didRestore = true
        scope.launch(Dispatchers.IO) {
            try {
                if (components.store.state.tabs.isEmpty()) {
                    val recovered = sessionStorage.restore()
                    if (recovered != null && recovered.tabs.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (components.store.state.tabs.isEmpty()) {
                                components.store.dispatch(
                                    TabListAction.RestoreAction(
                                        tabs = recovered.tabs,
                                        selectedTabId = recovered.selectedTabId,
                                        restoreLocation = TabListAction.RestoreAction.RestoreLocation.BEGINNING,
                                        tabPartitions = emptyMap(),
                                    )
                                )
                                UHLog.d("lifecycle", "Restored ${recovered.tabs.size} tab(s) from SessionStorage.")
                            }
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
                if (!isSleeping && components.store.state.tabs.isNotEmpty()) {
                    saveSnapshotNow(flushEngine = false)
                }
            }
        }
    }

    fun persistNow() {
        if (!initialized.get()) return
        scope.launch(Dispatchers.IO) {
            saveSnapshotNow(flushEngine = true)
        }
    }

    private suspend fun saveSnapshotNow(flushEngine: Boolean) {
        try {
            val state = components.store.state
            if (flushEngine && state.tabs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    state.tabs.forEach { tab ->
                        components.store.dispatch(EngineAction.FlushEngineSessionStateAction(tab.id))
                    }
                }
                delay(250L)
            }
            sessionStorage.save(components.store.state)
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
        if (!initialized.get() || startedActivities > 0 || isSleeping) return
        isSleeping = true
        UHLog.d("lifecycle", "Entering low-power sleep mode for background session retention.")
        scope.launch {
            saveSnapshotNow(flushEngine = true)
            withContext(Dispatchers.Main) {
                val state = components.store.state
                val selectedId = state.selectedTabId
                state.tabs.forEach { tab ->
                    setGeckoSessionActive(tab.engineState.engineSession, false)
                    if (tab.id != selectedId && tab.engineState.engineSession != null) {
                        components.store.dispatch(EngineAction.SuspendEngineSessionAction(tab.id))
                    }
                }
                try {
                    Coil.imageLoader(app).memoryCache?.clear()
                } catch (_: Exception) {}
            }
            SessionService.startOrUpdate(app, isSleeping = true, hasTabs = components.store.state.tabs.isNotEmpty())
        }
    }

    private fun wakeFromSleepMode() {
        sleepJob?.cancel()
        sleepJob = null
        val wasSleeping = isSleeping
        isSleeping = false
        scope.launch(Dispatchers.Main) {
            val state = components.store.state
            val selectedTab = state.tabs.firstOrNull { it.id == state.selectedTabId }
            if (wasSleeping) {
                setGeckoSessionActive(selectedTab?.engineState?.engineSession, true)
                UHLog.d("lifecycle", "Resumed from low-power sleep mode.")
            }
            SessionService.startOrUpdate(app, isSleeping = false, hasTabs = state.tabs.isNotEmpty())
            components.resumePendingNetworkDownloads()
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
            val hasTabs = components.store.state.tabs.isNotEmpty()
            SessionService.startOrUpdate(app, isSleeping = false, hasTabs = hasTabs)
            scope.launch(Dispatchers.IO) {
                saveSnapshotNow(flushEngine = true)
            }
            sleepJob?.cancel()
            sleepJob = scope.launch {
                delay(SLEEP_DELAY_MS)
                enterSleepMode()
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
                } catch (_: Exception) {}
                if (startedActivities == 0) {
                    sleepJob?.cancel()
                    enterSleepMode()
                } else {
                    scope.launch(Dispatchers.Main) {
                        val state = components.store.state
                        val selectedId = state.selectedTabId
                        state.tabs.filter { it.id != selectedId && it.engineState.engineSession != null }.forEach {
                            components.store.dispatch(EngineAction.SuspendEngineSessionAction(it.id))
                        }
                    }
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                if (startedActivities == 0) {
                    scope.launch(Dispatchers.IO) {
                        saveSnapshotNow(flushEngine = true)
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
