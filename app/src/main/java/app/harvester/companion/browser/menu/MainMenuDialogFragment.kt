package app.harvester.companion.browser.menu

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
        val components = (requireActivity().application as UHApp).components
        return ComposeView(requireContext()).apply {
            setContent {
                UHTheme {
                    val browserState by components.store.stateFlow.collectAsState()
                    val selectedTab = browserState.selectedTab
                    val isDesktopMode = selectedTab?.content?.desktopMode == true

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp, 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                .align(Alignment.CenterHorizontally)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            key("fav") {
                                TopRowItem("Favorites", R.drawable.ic_favorites, Modifier.weight(1f)) {
                                    dismissAllowingStateLoss()
                                }
                            }
                            key("history") {
                                TopRowItem("History", R.drawable.ic_history, Modifier.weight(1f)) {
                                    navigateSafe(R.id.historyFragment)
                                }
                            }
                            key("downloads") {
                                TopRowItem("Downloads", R.drawable.fx_ic_downloads_24, Modifier.weight(1f)) {
                                    navigateSafe(R.id.downloadsFragment)
                                }
                            }
                            key("playlist") {
                                TopRowItem("Playlist", R.drawable.ic_playlist, Modifier.weight(1f)) {
                                    dismissAllowingStateLoss()
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(vertical = 8.dp)
                        ) {
                            if (!isHome) {
                                MenuRowItem(R.drawable.ic_menu_translate, "Translate") { dismissAllowingStateLoss() }
                                MenuRowItem(R.drawable.ic_menu_find, "Find in Page") { dismissAllowingStateLoss() }
                                MenuRowItem(
                                    iconRes = if (isDesktopMode) R.drawable.ic_menu_mobile else R.drawable.ic_menu_desktop,
                                    label = if (isDesktopMode) "Request Mobile Site" else "Request Desktop Site"
                                ) {
                                    dismissAllowingStateLoss()
                                    selectedTab?.let { tab ->
                                        components.useCases.sessionUseCases.requestDesktopSite(!isDesktopMode, tab.id)
                                    } ?: UHLog.w("ui_warn", "Request desktop site clicked with no active tab.")
                                }
                                MenuRowItem(R.drawable.ic_menu_add_home, "Add to Home Screen") { dismissAllowingStateLoss() }
                                MenuRowItem(R.drawable.ic_menu_dev, "Developer Tools") { dismissAllowingStateLoss() }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .height(1.dp)
                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                                )
                            }

                            MenuRowItem(R.drawable.fx_ic_settings_24, "Settings") {
                                val ctx = context
                                dismissAllowingStateLoss()
                                ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(32.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomBarItem("Home", R.drawable.ic_home) {
                                val act = activity
                                dismissAllowingStateLoss()
                                val navHostFragment = act?.supportFragmentManager?.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
                                val controller = navHostFragment?.navController
                                if (controller == null) {
                                    UHLog.w("ui_warn", "Home clicked but NavController is unavailable.")
                                } else if (!controller.popBackStack(R.id.homeFragment, false)) {
                                    try {
                                        controller.navigate(R.id.homeFragment)
                                    } catch (e: Exception) {
                                        UHLog.throwable("ui_error", "Failed navigating to homeFragment", e)
                                    }
                                }
                            }
                            BottomBarItem("Add", R.drawable.ic_heart_plus) { dismissAllowingStateLoss() }
                            BottomBarItem("Quit", R.drawable.ic_power) {
                                val act = activity
                                dismissAllowingStateLoss()
                                act?.finish()
                            }
                            BottomBarItem("Scripts", R.drawable.fx_ic_scripts_24) {
                                val ctx = context
                                dismissAllowingStateLoss()
                                ctx.startActivity(Intent(ctx, ScriptsActivity::class.java))
                            }
                            BottomBarItem("Reload", R.drawable.ic_reload) {
                                dismissAllowingStateLoss()
                                val tab = selectedTab ?: components.store.state.selectedTab
                                if (tab != null) {
                                    components.useCases.sessionUseCases.reload(tab.id)
                                } else {
                                    UHLog.w("ui_warn", "Reload clicked in MainMenu with no active tab.")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun TopRowItem(label: String, iconRes: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick() }
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    @Composable
    private fun MenuRowItem(iconRes: Int, label: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }

    @Composable
    private fun BottomBarItem(label: String, iconRes: Int, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
