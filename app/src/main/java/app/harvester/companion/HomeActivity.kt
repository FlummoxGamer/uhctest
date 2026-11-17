package app.harvester.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentContainerView
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import app.harvester.companion.browser.BrowserFragment
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.toolbar.UhGlobalToolbar
import app.harvester.companion.theme.UHTheme

class HomeActivity : AppCompatActivity() {

    private val browserViewModel: BrowserViewModel by viewModels()
    private var navControllerState by mutableStateOf<NavController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        setContent {
            UHTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            FragmentContainerView(context).apply {
                                id = R.id.nav_host_fragment
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = {
                            try {
                                val existing = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                                val host = if (existing == null) {
                                    val created = NavHostFragment.create(R.navigation.nav_graph)
                                    supportFragmentManager.beginTransaction()
                                        .replace(R.id.nav_host_fragment, created)
                                        .setPrimaryNavigationFragment(created)
                                        .commitNow()
                                    created
                                } else {
                                    existing
                                }
                                val controller = host.navController
                                if (navControllerState !== controller) {
                                    navControllerState = controller
                                    handleIntent(intent, controller)
                                }
                            } catch (e: Exception) {
                                UHLog.throwable("ui_error", "Failed to initialize NavHostFragment/NavController", e)
                            }
                        }
                    )

                    val controller = navControllerState
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val controller = navControllerState
                if (browserViewModel.isEditing.value) {
                    browserViewModel.exitEditMode()
                } else if (controller != null && controller.currentDestination?.id == R.id.browserFragment) {
                    try {
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)?.childFragmentManager?.primaryNavigationFragment
                        if (currentFragment is BrowserFragment) {
                            if (!currentFragment.onBackPressed()) {
                                controller.popBackStack()
                            }
                        } else {
                            controller.popBackStack()
                        }
                    } catch (e: Exception) {
                        UHLog.throwable("ui_error", "Failed handling back press in BrowserFragment", e)
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun handleIntent(intent: Intent?, controller: NavController? = navControllerState) {
        val url = intent?.data?.toString() ?: return
        if (!url.startsWith("http")) {
            UHLog.w("ui_warn", "Ignored non-http intent URL: $url")
            return
        }
        if (controller == null) {
            UHLog.w("ui_warn", "Cannot handle intent URL before NavController is ready: $url")
            return
        }
        try {
            if (controller.currentDestination?.id == R.id.homeFragment) {
                val bundle = Bundle().apply { putString("url", url) }
                controller.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
            }
        } catch (e: Exception) {
            UHLog.throwable("ui_error", "Failed to navigate for intent URL: $url", e)
        }
    }

    override fun onResume() {
        super.onResume()
        (application as? UHApp)?.components?.resumePendingNetworkDownloads()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }
}
