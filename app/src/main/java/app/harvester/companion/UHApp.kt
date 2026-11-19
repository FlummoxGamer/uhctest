package app.harvester.companion

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.harvester.companion.components.Components
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xcrash.ICrashCallback
import xcrash.XCrash

class UHApp : Application(), coil.ImageLoaderFactory {

    @Volatile
    var isComponentsInitialized: Boolean = false
        private set

    private val browserServicesStarted = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    val components by lazy {
        Components(this).also {
            isComponentsInitialized = true
        }
    }
    val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            UHLog.e("scope", "Unhandled coroutine error: ${throwable.message}")
        }
    )

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initXCrash(base)
    }

    override fun onCreate() {
        val start = System.currentTimeMillis()
        super.onCreate()

        if (applicationContext.packageName != Application.getProcessName()) {
            return
        }

        UHLog.init(this)
        ThemePrefs.applyCurrent(this)
        movePendingCrashFile()

        if (File(filesDir, "safe_mode.flag").exists()) {
            UHLog.w("safe_mode", "Pending safe_mode.flag detected; deferring browser engine startup.")
            return
        }

        SessionRecorder.init(this)
        app.harvester.companion.core.lifecycle.AppSleepManager.init(this)

        applicationScope.launch {
            try {
                val req = PeriodicWorkRequestBuilder<CleanupWorker>(6, TimeUnit.HOURS).build()
                WorkManager.getInstance(this@UHApp).enqueueUniquePeriodicWork(
                    "uh_cleanup",
                    ExistingPeriodicWorkPolicy.KEEP,
                    req,
                )
            } catch (_: Exception) {}
        }

        if (BuildConfig.DEBUG) {
            StrictModeManager.enableStrictMode(true)
            LeakCanarySetup.setup(this)
        }

        mainHandler.post {
            ensureBrowserServicesStarted()
        }

        UHLog.d("perf", "App onCreate: ${System.currentTimeMillis() - start}ms")
    }

    fun ensureBrowserServicesStarted() {
        if (File(filesDir, "safe_mode.flag").exists()) return
        if (!browserServicesStarted.compareAndSet(false, true)) return
        val start = System.currentTimeMillis()
        try {
            val appComponents = components
            appComponents.store.dispatch(mozilla.components.browser.state.action.DownloadAction.RestoreDownloadsStateAction)
            app.harvester.companion.core.lifecycle.AppSleepManager.attachComponentsAndRestore(appComponents)
            UHLog.d("perf", "Browser services started: ${System.currentTimeMillis() - start}ms")
        } catch (e: Exception) {
            browserServicesStarted.set(false)
            UHLog.throwable("boot_error", "Failed starting browser services", e)
        }
    }

    override fun newImageLoader(): coil.ImageLoader {
        return coil.ImageLoader.Builder(this)
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
                add(coil.decode.GifDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    private fun movePendingCrashFile() {
        try {
            val crashFile = File(filesDir, "last_crash.txt")
            if (crashFile.exists()) {
                val crashDir = File(filesDir, "crashes").apply { mkdirs() }
                crashFile.renameTo(File(crashDir, "crash_${System.currentTimeMillis()}.txt"))
            }
        } catch (_: Exception) {}
    }

    private fun initXCrash(context: Context) {
        try {
            val params = XCrash.InitParameters()
            params.setAppVersion(BuildConfig.VERSION_NAME)
            params.setLogDir("${context.filesDir.absolutePath}/crashes")

            val crashCallback = ICrashCallback { logPath, emergency ->
                try {
                    val crashFile = File(context.filesDir, "last_crash.txt")
                    val content = if (!logPath.isNullOrBlank() && File(logPath).exists()) {
                        File(logPath).readText()
                    } else {
                        emergency ?: "Unknown Crash"
                    }
                    crashFile.writeText(content)
                    File(context.filesDir, "safe_mode.flag").createNewFile()
                } catch (_: Exception) {}
            }

            val anrCallback = ICrashCallback { logPath, emergency ->
                try {
                    val crashDir = File(context.filesDir, "crashes").apply { mkdirs() }
                    val content = if (!logPath.isNullOrBlank() && File(logPath).exists()) {
                        File(logPath).readText()
                    } else {
                        emergency ?: "ANR detected"
                    }
                    File(crashDir, "anr_${System.currentTimeMillis()}.txt").writeText(content)
                } catch (_: Exception) {}
            }

            params.setJavaCallback(crashCallback)
            params.setNativeCallback(crashCallback)
            params.setAnrCallback(anrCallback)

            XCrash.init(context, params)
        } catch (_: Throwable) {}
    }
}
