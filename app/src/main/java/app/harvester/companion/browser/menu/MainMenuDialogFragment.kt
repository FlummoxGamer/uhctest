package app.harvester.companion.browser.menu

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import app.harvester.companion.settings.SettingsActivity
import app.harvester.companion.settings.addons.ScriptsActivity
import app.harvester.companion.settings.appearance.CustomizeHomeActivity
import app.harvester.companion.settings.tools.ToolsActivity
import app.harvester.companion.theme.UHTheme
import mozilla.components.browser.state.selector.selectedTab
import com.google.android.material.R as materialR

class MainMenuDialogFragment : BottomSheetDialogFragment() {

    private var bottomSheetBehavior: BottomSheetBehavior<View>? = null

    companion object {
        private const val ARG_IS_HOME = "is_home"
        fun newInstance(isHome: Boolean): MainMenuDialogFragment {
            val args = Bundle().apply { putBoolean(ARG_IS_HOME, isHome) }
            return MainMenuDialogFragment().apply { arguments = args }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).apply {
            setOnShowListener {
                val bottomSheet = findViewById<View?>(materialR.id.design_bottom_sheet)
                bottomSheet?.let {
                    bottomSheetBehavior = BottomSheetBehavior.from(it).apply {
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
                bottomSheet?.setBackgroundResource(android.R.color.transparent)
            }
        }
    }

    private fun navigateSafe(destinationId: Int) {
        val hostActivity = activity as? HomeActivity
        dismissAllowingStateLoss()
        if (hostActivity != null) {
            hostActivity.navigateFromMenu(destinationId)
        } else {
            UHLog.e("ui_error", "MainMenu failed to navigate: HomeActivity unavailable.")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val isHome = arguments?.getBoolean(ARG_IS_HOME) ?: true
        val app = requireActivity().application as UHApp
        val components = app.components

        return ComposeView(requireContext()).apply {
            setContent {
                UHTheme {
                    val browserState by components.store.stateFlow.collectAsState()
                    val selectedTab = browserState.selectedTab
                    val isDesktopMode = selectedTab?.content?.desktopMode == true
                    val canGoBack = selectedTab?.content?.canGoBack == true
                    val canGoForward = selectedTab?.content?.canGoForward == true

                    Surface(
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Drag Handle
                            Box(
                                modifier = Modifier
                                    .size(36.dp, 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            if (isHome) {
                                // Home 3-Dot Menu State
                                HomeMenuState(
                                    onHistory = { navigateSafe(R.id.historyFragment) },
                                    onDownloads = { navigateSafe(R.id.downloadsFragment) },
                                    onCaptureTools = {
                                        dismissAllowingStateLoss()
                                        context.startActivity(Intent(context, ToolsActivity::class.java))
                                    },
                                    onSettings = {
                                        dismissAllowingStateLoss()
                                        context.startActivity(Intent(context, SettingsActivity::class.java))
                                    },
                                    onCustomizeHome = {
                                        dismissAllowingStateLoss()
                                        context.startActivity(Intent(context, CustomizeHomeActivity::class.java))
                                    },
                                    onScripts = {
                                        dismissAllowingStateLoss()
                                        context.startActivity(Intent(context, ScriptsActivity::class.java))
                                    },
                                    onQuit = {
                                        dismissAllowingStateLoss()
                                        activity?.finish()
                                    }
                                )
                            } else {
                                // Browser 3-Dot Menu State
                                BrowserMenuState(
                                    canGoBack = canGoBack,
                                    canGoForward = canGoForward,
                                    isDesktopMode = isDesktopMode,
                                    onBack = {
                                        dismissAllowingStateLoss()
                                        selectedTab?.let { components.useCases.sessionUseCases.goBack(it.id) }
                                    },
                                    onForward = {
                                        dismissAllowingStateLoss()
                                        selectedTab?.let { components.useCases.sessionUseCases.goForward(it.id) }
                                    },
                                    onReload = {
                                        dismissAllowingStateLoss()
                                        selectedTab?.let { components.useCases.sessionUseCases.reload(it.id) }
                                    },
                                    onBookmark = {
                                        dismissAllowingStateLoss()
                                        // Toggle bookmark
                                    },
                                    onShare = {
                                        dismissAllowingStateLoss()
                                        val url = selectedTab?.content?.url
                                        if (!url.isNullOrBlank() && url != "about:blank") {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, url)
                                            }
                                            startActivity(Intent.createChooser(intent, "Share"))
                                        }
                                    },
                                    onTranslate = { dismissAllowingStateLoss() },
                                    onToggleDesktop = {
                                        dismissAllowingStateLoss()
                                        selectedTab?.let {
                                            components.useCases.sessionUseCases.requestDesktopSite(!isDesktopMode, it.id)
                                        }
                                    },
                                    onFindInPage = { dismissAllowingStateLoss() },
                                    onAddToHome = { dismissAllowingStateLoss() },
                                    onCaptureTools = {
                                        dismissAllowingStateLoss()
                                        context.startActivity(Intent(context, ToolsActivity::class.java))
                                    },
                                    onDownloads = { navigateSafe(R.id.downloadsFragment) },
                                    onHistory = { navigateSafe(R.id.historyFragment) },
                                    onSettings = {
                                        dismissAllowingStateLoss()
                                        context.startActivity(Intent(context, SettingsActivity::class.java))
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomeMenuState(
        onHistory: () -> Unit,
        onDownloads: () -> Unit,
        onCaptureTools: () -> Unit,
        onSettings: () -> Unit,
        onCustomizeHome: () -> Unit,
        onScripts: () -> Unit,
        onQuit: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Quick Action Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionItem(
                    label = "History",
                    iconRes = R.drawable.ic_history,
                    modifier = Modifier.weight(1f),
                    onClick = onHistory
                )
                QuickActionItem(
                    label = "Downloads",
                    iconRes = R.drawable.fx_ic_downloads_24,
                    modifier = Modifier.weight(1f),
                    onClick = onDownloads
                )
                QuickActionItem(
                    label = "Capture",
                    iconRes = R.drawable.ic_tools,
                    modifier = Modifier.weight(1f),
                    onClick = onCaptureTools
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Menu Items List Card
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_appearance_24,
                        label = "Customize Home",
                        onClick = onCustomizeHome
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_scripts_24,
                        label = "User Scripts",
                        onClick = onScripts
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_settings_24,
                        label = "Settings",
                        onClick = onSettings
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.ic_power,
                        label = "Quit",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = onQuit
                    )
                }
            }
        }
    }

    @Composable
    private fun BrowserMenuState(
        canGoBack: Boolean,
        canGoForward: Boolean,
        isDesktopMode: Boolean,
        onBack: () -> Unit,
        onForward: () -> Unit,
        onReload: () -> Unit,
        onBookmark: () -> Unit,
        onShare: () -> Unit,
        onTranslate: () -> Unit,
        onToggleDesktop: () -> Unit,
        onFindInPage: () -> Unit,
        onAddToHome: () -> Unit,
        onCaptureTools: () -> Unit,
        onDownloads: () -> Unit,
        onHistory: () -> Unit,
        onSettings: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Browser Top Quick Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, enabled = canGoBack) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_back_24),
                        contentDescription = "Back",
                        tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onForward, enabled = canGoForward) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_forward_24),
                        contentDescription = "Forward",
                        tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onReload) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_reload_24),
                        contentDescription = "Reload",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onBookmark) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_bookmark_24),
                        contentDescription = "Bookmark",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body Menu Items
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CompactMenuItem(
                        iconRes = R.drawable.uh_ic_translate_24,
                        label = "Translate",
                        onClick = onTranslate
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.uh_ic_device_desktop_24,
                        label = if (isDesktopMode) "Request Mobile Site" else "Request Desktop Site",
                        onClick = onToggleDesktop
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_search_24,
                        label = "Find in Page",
                        onClick = onFindInPage
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.uh_ic_plus_24,
                        label = "Add to Home Screen",
                        onClick = onAddToHome
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.ic_tools,
                        label = "Session Capture Tools",
                        onClick = onCaptureTools
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                    )

                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_downloads_24,
                        label = "Downloads",
                        onClick = onDownloads
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.ic_history,
                        label = "History",
                        onClick = onHistory
                    )
                    CompactMenuItem(
                        iconRes = R.drawable.fx_ic_settings_24,
                        label = "Settings",
                        onClick = onSettings
                    )
                }
            }
        }
    }

    @Composable
    private fun QuickActionItem(
        label: String,
        iconRes: Int,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    @Composable
    private fun CompactMenuItem(
        iconRes: Int,
        label: String,
        tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
