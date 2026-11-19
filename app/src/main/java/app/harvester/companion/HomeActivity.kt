package app.harvester.companion

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.rememberFragmentState
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.browser.BrowserFragment
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.toolbar.UhGlobalToolbar
import app.harvester.companion.safemode.SafeModeActivity
import app.harvester.companion.theme.UHTheme
import app.harvester.companion.utils.CrashChecker
import java.io.File

class HomeActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private var navControllerState by mutableStateOf<NavController?>(null)
    private var navHostFragmentRef: NavHostFragment? = null
    private var pendingIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 30) {
            CrashChecker.checkNativeCrashes(this)
        }
        if (File(filesDir, "safe_mode.flag").exists()) {
            startActivity(
                Intent(this, SafeModeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
            finish()
            return
        }

        pendingIntent = intent

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            val controller = navControllerState
            val backStackEntry = controller?.currentBackStackEntryAsState()?.value
            val currentDestId = backStackEntry?.destination?.id
            val isEditing by browserViewModel.isEditing.collectAsState()
            val wallpaperState by WallpaperStore.wallpaperFlow(this).collectAsState()
            val isOnHome = (currentDestId == R.id.homeFragment || currentDestId == null) && !isEditing
            val lightStatusOverride = if (isOnHome) wallpaperState.isTopDark?.let { !it } else null
            val lightNavOverride = if (isOnHome) wallpaperState.isBottomDark?.let { !it } else null
            val navHostState = rememberFragmentState()
            val navHostArgs = remember {
                NavHostFragment.create(R.navigation.nav_graph).arguments ?: Bundle.EMPTY
            }

            UHTheme(
                lightStatusBarOverride = lightStatusOverride,
                lightNavigationBarOverride = lightNavOverride
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidFragment<NavHostFragment>(
                        modifier = Modifier.fillMaxSize(),
                        fragmentState = navHostState,
                        arguments = navHostArgs,
                        onUpdate = { host ->
                            navHostFragmentRef = host
                            val navCtrl = host.navController
                            if (navControllerState !== navCtrl) {
                                navControllerState = navCtrl
                            }
                            val queued = pendingIntent
                            if (queued != null) {
                                pendingIntent = null
                                window?.decorView?.post {
                                    handleIntent(queued, navCtrl)
                                }
                            }
                        }
                    )

                    if (controller != null) {
                        UhGlobalToolbar(
                            viewModel = browserViewModel,
                            navController = controller,
                            onDismiss = { browserViewModel.exitEditMode() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        android.os.Looper.myQueue().addIdleHandler {
            try {
                (application as? UHApp)?.ensureBrowserServicesStarted()
            } catch (_: Exception) {}
            false
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val controller = activeNavController()
                if (browserViewModel.isEditing.value) {
                    browserViewModel.exitEditMode()
                    return
                }
                if (browserViewModel.showFindInPage.value) {
                    browserViewModel.closeFindInPage()
                    return
                }
                val navHost = activeNavHostFragment()
                val activeBrowser = navHost?.childFragmentManager?.fragments
                    ?.firstOrNull { it is BrowserFragment && it.isVisible } as? BrowserFragment
                if (activeBrowser != null) {
                    if (activeBrowser.onBackPressed()) {
                        return
                    }
                    if (controller != null && controller.currentDestination?.id == R.id.browserFragment) {
                        controller.popBackStack()
                        return
                    }
                }
                if (controller != null && controller.currentDestination?.id != R.id.homeFragment) {
                    if (controller.popBackStack()) {
                        return
                    }
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    fun activeNavHostFragment(): NavHostFragment? {
        return navHostFragmentRef
            ?: supportFragmentManager.fragments.filterIsInstance<NavHostFragment>().firstOrNull()
    }

    fun activeNavController(): NavController? {
        return navControllerState ?: activeNavHostFragment()?.navController
    }

    fun navigateFromMenu(destinationId: Int) {
        window?.decorView?.post {
            val controller = activeNavController() ?: return@post
            try {
                if (controller.currentDestination?.id == destinationId) return@post
                val options = NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .build()
                controller.navigate(destinationId, null, options)
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Menu navigation failed for destination $destinationId", e)
            }
        }
    }

    private fun handleIntent(intent: Intent?, controller: NavController? = activeNavController()) {
        val url = intent?.data?.toString() ?: return
        if (!url.startsWith("http")) {
            UHLog.w("ui_warn", "Ignored non-http intent URL: $url")
            return
        }
        if (controller == null) {
            pendingIntent = intent
            return
        }
        try {
            if (controller.currentDestination?.id == R.id.homeFragment) {
                val bundle = Bundle().apply { putString("url", url) }
                controller.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
            } else {
                (application as? UHApp)?.components?.useCases?.sessionUseCases?.loadUrl(url)
            }
        } catch (e: Exception) {
            UHLog.throwable("ui_error", "Failed to navigate for intent URL: $url", e)
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as? UHApp
        if (app?.isComponentsInitialized == true) {
            app.components.resumePendingNetworkDownloads()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
}
