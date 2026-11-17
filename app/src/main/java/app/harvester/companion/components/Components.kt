package app.harvester.companion.components

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.harvester.companion.BuildConfig
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.ThemePrefs
import app.harvester.companion.UHLog
import app.harvester.companion.browser.storage.sync.GlobalPlacesDependencyProvider
import app.harvester.companion.browser.storage.sync.PlacesBookmarksStorage
import app.harvester.companion.browser.storage.sync.PlacesHistoryStorage
import app.harvester.companion.core.downloads.DownloadLocationResolver
import app.harvester.companion.core.downloads.UhDownloadMiddleware
import app.harvester.companion.core.downloads.UhDownloadService
import app.harvester.companion.data.downloads.UhDownloadStorage
import app.harvester.companion.feature.session.HistoryDelegate
import app.harvester.companion.service.nimbus.Nimbus
import app.harvester.companion.service.nimbus.NimbusAppInfo
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.contextmenu.ContextMenuUseCases
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.DownloadsUseCases
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.concept.engine.permission.SitePermissionsStorage
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.feature.tabs.TabsUseCases
import mozilla.components.lib.state.Middleware
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import org.mozilla.geckoview.GeckoRuntime

class Components(val context: Context) {

    val appStore: AppStore by lazy { AppStore() }

    val runtime: GeckoRuntime by lazy {
        GeckoRuntime.create(context.applicationContext)
    }

    val client: Client by lazy {
        GeckoViewFetchClient(context.applicationContext, runtime)
    }

    val geckoSitePermissionsStorage: SitePermissionsStorage by lazy {
        mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage(context.applicationContext)
    }

    val sitePermissionRules by lazy { app.harvester.companion.utils.SitePermissionRules(context.applicationContext) }

    val placesHistoryStorage: PlacesHistoryStorage by lazy {
        PlacesHistoryStorage(context.applicationContext)
    }

    val placesBookmarksStorage: PlacesBookmarksStorage by lazy {
        PlacesBookmarksStorage(context.applicationContext)
    }

    val historyDelegate: HistoryDelegate by lazy {
        HistoryDelegate(lazy { placesHistoryStorage })
    }

    val nimbus: Nimbus by lazy {
        Nimbus(
            context = context.applicationContext,
            appInfo = NimbusAppInfo("uh", BuildConfig.VERSION_NAME),
            server = null,
            recordedContext = null
        )
    }

    init {
        GlobalPlacesDependencyProvider.initialize(placesHistoryStorage)
        placesHistoryStorage.registerStorageMaintenanceWorker()
    }

    val fileSizeFormatter: FileSizeFormatter by lazy {
        DefaultFileSizeFormatter(context.applicationContext)
    }

    val downloadEstimator: DownloadEstimator by lazy {
        DownloadEstimator(DefaultDateTimeProvider())
    }

    val notificationsDelegate: NotificationsDelegate by lazy {
        NotificationsDelegate(NotificationManagerCompat.from(context.applicationContext))
    }

    val engine: Engine by lazy {
        GeckoEngine(
            context.applicationContext,
            defaultSettings = buildDefaultSettings(),
            runtime = runtime,
        )
    }

    val icons by lazy {
        mozilla.components.browser.icons.BrowserIcons(context.applicationContext, client)
    }

    val publicSuffixList by lazy {
        mozilla.components.lib.publicsuffixlist.PublicSuffixList(context.applicationContext)
    }

    val downloadFileUtils: DownloadFileUtils by lazy {
        DefaultDownloadFileUtils(context.applicationContext) {
            DownloadLocationResolver.resolveTargetDirectory(context.applicationContext)
        }
    }

    val downloadStorage: UhDownloadStorage by lazy { UhDownloadStorage(context.applicationContext) }

    private val networkResumeLock = Any()
    private val pendingNetworkResumeIds = mutableSetOf<String>()
    private val deferredNetworkResumeIds = mutableSetOf<String>()

    val downloadMiddleware: UhDownloadMiddleware by lazy {
        UhDownloadMiddleware(
            applicationContext = context.applicationContext,
            downloadServiceClass = UhDownloadService::class.java,
            deleteFileFromStorage = {
                SettingsPrefs.getString(context, SettingsPrefs.K_DELETE_BEHAVIOR, "ask") == "device"
            },
            downloadFileUtils = downloadFileUtils,
            downloadStorage = downloadStorage
        )
    }

    val store: BrowserStore by lazy {
        val middlewareList = listOf<Middleware<BrowserState, BrowserAction>>(downloadMiddleware)
        val browserStore = BrowserStore(
            middleware = middlewareList + EngineMiddleware.create(engine, trimMemoryAutomatically = false)
        )
        registerNetworkResumeCallback(browserStore)
        browserStore
    }

    private fun registerNetworkResumeCallback(browserStore: BrowserStore) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        if (connectivityManager == null) {
            UHLog.w("download_warn", "ConnectivityManager unavailable; network download auto-resume disabled.")
            return
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onLost(network: android.net.Network) {
                    val activeIds = browserStore.state.downloads.values
                        .filter {
                            it.status == DownloadState.Status.DOWNLOADING ||
                                it.status == DownloadState.Status.INITIATED
                        }
                        .map { it.id }
                    if (activeIds.isNotEmpty()) {
                        synchronized(networkResumeLock) {
                            pendingNetworkResumeIds.addAll(activeIds)
                        }
                        UHLog.w("download_warn", "Network connection lost during ${activeIds.size} active download(s); queued for auto-resume.")
                    }
                }

                override fun onAvailable(network: android.net.Network) {
                    val trackedIds = synchronized(networkResumeLock) {
                        val copy = (pendingNetworkResumeIds + deferredNetworkResumeIds).toSet()
                        pendingNetworkResumeIds.clear()
                        copy
                    }
                    val candidates = browserStore.state.downloads.values.filter { dl ->
                        dl.status == DownloadState.Status.FAILED ||
                            (dl.id in trackedIds && dl.status != DownloadState.Status.COMPLETED && dl.status != DownloadState.Status.CANCELLED && dl.status != DownloadState.Status.PAUSED)
                    }
                    if (candidates.isNotEmpty()) {
                        attemptResumeDownloads(browserStore, candidates)
                    }
                }
            })
        } catch (e: Exception) {
            UHLog.throwable("download_error", "Failed to register network callback for download auto-resume", e)
        }
    }

    fun resumePendingNetworkDownloads() {
        val ids = synchronized(networkResumeLock) {
            if (deferredNetworkResumeIds.isEmpty()) return
            val copy = deferredNetworkResumeIds.toSet()
            deferredNetworkResumeIds.clear()
            copy
        }
        val candidates = store.state.downloads.values.filter {
            it.id in ids && (it.status == DownloadState.Status.FAILED || it.status == DownloadState.Status.INITIATED)
        }
        if (candidates.isNotEmpty()) {
            attemptResumeDownloads(store, candidates)
        }
    }

    private fun attemptResumeDownloads(browserStore: BrowserStore, downloads: List<DownloadState>) {
        downloads.forEach { download ->
            try {
                val broadcast = Intent(mozilla.components.feature.downloads.AbstractFetchDownloadService.ACTION_TRY_AGAIN).apply {
                    setPackage(context.packageName)
                    putExtra("INTENT_EXTRA_DOWNLOAD_ID", download.id)
                    putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, download.id)
                }
                context.sendBroadcast(broadcast)

                val resumedState = download.copy(status = DownloadState.Status.INITIATED)
                browserStore.dispatch(DownloadAction.UpdateDownloadAction(resumedState))

                val serviceIntent = Intent(context, UhDownloadService::class.java).apply {
                    putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, download.id)
                    putExtra("INTENT_EXTRA_DOWNLOAD_ID", download.id)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                synchronized(networkResumeLock) {
                    deferredNetworkResumeIds.remove(download.id)
                }
            } catch (e: Exception) {
                synchronized(networkResumeLock) {
                    deferredNetworkResumeIds.add(download.id)
                }
                UHLog.w("download_warn", "Deferred network resume for download ${download.id} until app is foregrounded: ${e.message}")
            }
        }
    }

    val useCases: UseCases by lazy { UseCases(store, engine, downloadFileUtils) }

    private fun buildDefaultSettings(): DefaultSettings {
        val textScale = SettingsPrefs.getInt(context, SettingsPrefs.K_TEXT_SCALE, 100)
        return DefaultSettings(
            remoteDebuggingEnabled = false,
            testingModeEnabled = false,
            downloadDelegate = mozilla.components.browser.engine.gecko.util.EngineDownloadDelegate(
                context = context.applicationContext,
                downloadLocation = {
                    DownloadLocationResolver.resolveTargetDirectory(context.applicationContext)
                }
            ),
            preferredColorScheme = buildPreferredColorScheme(),
            automaticFontSizeAdjustment = textScale != 100,
            fontInflationEnabled = textScale != 100,
            suspendMediaWhenInactive = false,
            forceUserScalableContent = true,
            loginAutofillEnabled = false,
            enterpriseRootsEnabled = false,
            globalPrivacyControlEnabled = SettingsPrefs.getBool(context, SettingsPrefs.K_ADV_GPC, false),
            historyTrackingDelegate = historyDelegate,
        )
    }

    private fun buildPreferredColorScheme(): PreferredColorScheme {
        return when (ThemePrefs.getMode(context)) {
            ThemePrefs.MODE_LIGHT -> PreferredColorScheme.Light
            ThemePrefs.MODE_DARK -> PreferredColorScheme.Dark
            else -> {
                val night = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                if (night) PreferredColorScheme.Dark else PreferredColorScheme.Light
            }
        }
    }
}

class UseCases(store: BrowserStore, engine: Engine, downloadFileUtils: DownloadFileUtils) {
    val sessionUseCases by lazy { SessionUseCases(store) }
    val tabsUseCases by lazy { TabsUseCases(store) }
    val contextMenuUseCases by lazy { ContextMenuUseCases(store) }
    val downloadsUseCases by lazy { DownloadsUseCases(store, downloadFileUtils) }
    val searchUseCases by lazy { mozilla.components.feature.search.SearchUseCases(store, tabsUseCases, sessionUseCases) }
    val trackingProtectionUseCases by lazy { mozilla.components.feature.session.TrackingProtectionUseCases(store, engine) }
}
