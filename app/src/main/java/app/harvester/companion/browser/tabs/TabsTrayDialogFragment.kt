package app.harvester.companion.browser.tabs

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.browser.BrowserViewModel
import app.harvester.companion.theme.UHTheme
import com.google.android.material.R as materialR

class TabsTrayDialogFragment : BottomSheetDialogFragment() {

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    companion object {
        const val TAG = "TabsTrayDialogFragment"
        fun newInstance(): TabsTrayDialogFragment = TabsTrayDialogFragment()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            setOnShowListener {
                val bottomSheet = findViewById<View?>(materialR.id.design_bottom_sheet)
                bottomSheet?.let {
                    bottomSheetBehavior = BottomSheetBehavior.from(it).apply {
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                        isDraggable = true
                    }
                }
                bottomSheet?.setBackgroundResource(android.R.color.transparent)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val app = requireActivity().application as UHApp
        val components = app.components
        val hostActivity = activity as? HomeActivity

        return ComposeView(requireContext()).apply {
            setContent {
                UHTheme {
                    val browserState by components.store.stateFlow.collectAsState()
                    val tabs = browserState.tabs
                    val selectedTabId = browserState.selectedTabId

                    TabsTrayScreen(
                        tabs = tabs,
                        selectedTabId = selectedTabId,
                        onSelectTab = { tabId ->
                            dismissAllowingStateLoss()
                            components.useCases.tabsUseCases.selectTab(tabId)
                            navigateToBrowserIfNeeded()
                        },
                        onCloseTab = { tabId ->
                            components.useCases.tabsUseCases.removeTab(tabId)
                        },
                        onCloseAllTabs = { isPrivate ->
                            val targetTabs = tabs.filter { it.content.private == isPrivate }
                            if (targetTabs.isNotEmpty()) {
                                components.useCases.tabsUseCases.removeTabs(targetTabs.map { it.id })
                            }
                        },
                        onNewTab = { isPrivate ->
                            dismissAllowingStateLoss()
                            components.useCases.tabsUseCases.addTab("about:blank", selectTab = true, private = isPrivate)
                            navigateToBrowserIfNeeded()
                        },
                        onCloseSelectedTabs = { selectedIds ->
                            if (selectedIds.isNotEmpty()) {
                                components.useCases.tabsUseCases.removeTabs(selectedIds)
                            }
                        },
                        onShareSelectedTabs = { selectedIds ->
                            val selectedUrls = tabs.filter { it.id in selectedIds }.map { it.content.url }.filter { it.isNotBlank() && it != "about:blank" }
                            if (selectedUrls.isNotEmpty()) {
                                val text = selectedUrls.joinToString("\n")
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                startActivity(Intent.createChooser(intent, "Share Tabs"))
                            }
                        },
                        onDismiss = { dismissAllowingStateLoss() }
                    )
                }
            }
        }
    }

    private fun navigateToBrowserIfNeeded() {
        val act = activity ?: return
        val navHostFragment = act.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val controller = navHostFragment?.navController ?: return
        if (controller.currentDestination?.id != R.id.browserFragment) {
            try {
                controller.navigate(R.id.action_homeFragment_to_browserFragment)
            } catch (e: Exception) {
                UHLog.throwable("ui_error", "Failed navigating to browser from TabsTray", e)
            }
        }
    }
}
