package app.harvester.companion.browser.toolbar

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.browser.menu.MainMenuDialogFragment
import app.harvester.companion.browser.search.UHSearchEnginePicker
import app.harvester.companion.search.UhSearchViewModel
import app.harvester.companion.search.ui.UhSearchScreen
import app.harvester.companion.settings.trustpanel.TrustPanelFragment
import mozilla.components.browser.state.selector.selectedTab

@Composable
fun UhGlobalToolbar(
    viewModel: BrowserViewModel,
    navController: NavController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val components = (context.applicationContext as UHApp).components
    val isEditing by viewModel.isEditing.collectAsState()
    val isBarBottom by viewModel.isBarBottom.collectAsState()
    val searchEngine by viewModel.searchEngine.collectAsState()
    val editText by viewModel.editText.collectAsState()
    val browserState by components.store.stateFlow.collectAsState()
    val selectedTab = browserState.selectedTab
    val searchViewModel: UhSearchViewModel = viewModel()
    var showSearchEnginePicker by remember { mutableStateOf(false) }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestinationId = backStackEntry?.destination?.id
    val isHome = currentDestinationId == R.id.homeFragment || currentDestinationId == null
    val isBrowser = currentDestinationId == R.id.browserFragment

    LaunchedEffect(isEditing, searchEngine.id) {
        if (isEditing) {
            searchViewModel.onQueryChanged(editText, isBarBottom)
        }
    }

    if (!isHome && !isBrowser && !isEditing) {
        return
    }

    val displayUrl = if (isBrowser) (selectedTab?.content?.url ?: "") else ""
    val pageProgress = if (isBrowser) (selectedTab?.content?.progress ?: 0) else 0
    val isPageLoading = isBrowser && (selectedTab?.content?.loading == true)
    val iconState = if (isBrowser) {
        val currentUrl = selectedTab?.content?.url ?: ""
        when {
            currentUrl.startsWith("https://") -> 1
            currentUrl.startsWith("http://") -> 2
            else -> 0
        }
    } else {
        0
    }

    val openTrustPanel = {
        val activity = context as? AppCompatActivity
        val tab = components.store.state.selectedTab
        if (activity == null) {
            UHLog.e("ui_error", "Cannot open TrustPanel: Context is not AppCompatActivity.")
        } else if (tab == null) {
            UHLog.w("ui_warn", "Cannot open TrustPanel: No active tab selected.")
        } else {
            try {
                TrustPanelFragment.newInstance(
                    sessionId = tab.id,
                    url = tab.content.url,
                    title = tab.content.title ?: "",
                    isSecured = tab.content.securityInfo.isSecure,
                    certificate = tab.content.securityInfo.certificate
                ).show(activity.supportFragmentManager, "TrustPanel")
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed to show TrustPanel", e)
            }
        }
    }

    val openMainMenu = {
        val activity = context as? AppCompatActivity
        if (activity == null) {
            UHLog.e("ui_error", "Cannot open MainMenu: Context is not AppCompatActivity.")
        } else {
            try {
                MainMenuDialogFragment.newInstance(!isBrowser).show(activity.supportFragmentManager, "MainMenu")
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed to show MainMenu", e)
            }
        }
    }

    val onSearchSubmit = { query: String ->
        val target = viewModel.normalize(query)
        if (target.isBlank()) {
            UHLog.w("ui_warn", "Search submit ignored: normalized URL is blank for query.")
        } else {
            try {
                if (isBrowser) {
                    components.useCases.sessionUseCases.loadUrl(target)
                } else {
                    val bundle = Bundle().apply { putString("url", target) }
                    navController.navigate(R.id.action_homeFragment_to_browserFragment, bundle)
                }
                viewModel.exitEditMode()
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed to submit address bar query", e)
            }
        }
    }

    val onFillUrl: (String) -> Unit = { url ->
        viewModel.onTextChanged(url)
        searchViewModel.onQueryChanged(url, isBarBottom)
    }

    Box(modifier = modifier) {
        if (isEditing) {
            UhSearchScreen(
                viewModel = searchViewModel,
                isBarBottom = isBarBottom,
                addressBarContent = {
                    UHAddressBar(
                        url = displayUrl,
                        isEditing = true,
                        tabCount = browserState.tabs.size,
                        iconState = iconState,
                        isWallpaperActive = false,
                        editText = editText,
                        progress = 0,
                        isLoading = false,
                        onEditTextChanged = {
                            viewModel.onTextChanged(it)
                            searchViewModel.onQueryChanged(it, isBarBottom)
                        },
                        onSubmit = onSearchSubmit,
                        onEditStart = { },
                        onClear = {
                            viewModel.onTextChanged("")
                            searchViewModel.clearQuery(isBarBottom)
                        },
                        onSearchEngineClick = { showSearchEnginePicker = true },
                        onLockClick = openTrustPanel,
                        onScriptMenuClick = {},
                        onMenuClick = openMainMenu,
                        onTabCountClick = {},
                        searchEngine = searchEngine
                    )
                },
                onDismiss = onDismiss,
                onSubmit = onSearchSubmit,
                onFillUrl = onFillUrl
            )
        } else {
            val barPositionModifier = if (isBarBottom) {
                Modifier.navigationBarsPadding()
            } else {
                Modifier.statusBarsPadding()
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = if (isBarBottom) Alignment.BottomCenter else Alignment.TopCenter
            ) {
                UHAddressBar(
                    url = displayUrl,
                    isEditing = false,
                    tabCount = browserState.tabs.size,
                    iconState = iconState,
                    isWallpaperActive = isHome,
                    editText = editText,
                    progress = pageProgress,
                    isLoading = isPageLoading,
                    onEditTextChanged = { viewModel.onTextChanged(it) },
                    onSubmit = onSearchSubmit,
                    onEditStart = {
                        viewModel.enterEditMode("")
                        searchViewModel.onQueryChanged("", isBarBottom)
                    },
                    onClear = { },
                    onSearchEngineClick = { showSearchEnginePicker = true },
                    onLockClick = openTrustPanel,
                    onScriptMenuClick = {},
                    onMenuClick = openMainMenu,
                    onTabCountClick = {},
                    searchEngine = searchEngine,
                    modifier = barPositionModifier
                )
            }
        }
    }

    if (showSearchEnginePicker) {
        UHSearchEnginePicker(
            onDismiss = { showSearchEnginePicker = false },
            onSelect = { showSearchEnginePicker = false }
        )
    }
}
