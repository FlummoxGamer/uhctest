package app.harvester.companion.settings.privacy

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchItem
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class PrivacyGuardActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val components = (context.applicationContext as UHApp).components
                val privacyLevel by SettingsPrefs.privacyLevelFlow(context).collectAsState()

                var adsBlocked by remember {
                    mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_PG_ADS, false))
                }
                var forceHttps by remember {
                    mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_PG_HTTPS, false))
                }
                var antiFingerprint by remember {
                    mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_PG_FINGERPRINT, false))
                }
                var scriptControl by remember {
                    mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_PG_SCRIPT, false))
                }
                var cookieChoice by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_PG_COOKIES, 0))
                }
                var redirectChoice by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_PG_REDIRECT, 1))
                }
                var showCookieDialog by remember { mutableStateOf(false) }
                var showRedirectDialog by remember { mutableStateOf(false) }

                fun reloadStatesFromPrefs() {
                    adsBlocked = SettingsPrefs.getBool(context, SettingsPrefs.K_PG_ADS, false)
                    forceHttps = SettingsPrefs.getBool(context, SettingsPrefs.K_PG_HTTPS, false)
                    antiFingerprint = SettingsPrefs.getBool(context, SettingsPrefs.K_PG_FINGERPRINT, false)
                    scriptControl = SettingsPrefs.getBool(context, SettingsPrefs.K_PG_SCRIPT, false)
                    cookieChoice = SettingsPrefs.getInt(context, SettingsPrefs.K_PG_COOKIES, 0)
                }

                fun onCustomToggle(key: String, value: Boolean) {
                    SettingsPrefs.setBool(context, key, value)
                    SettingsPrefs.setString(context, SettingsPrefs.K_PRIVACY_LEVEL, SettingsPrefs.PRIVACY_CUSTOM)
                    PrivacyEngineSync.applyAll(context, components)
                }

                val cookieOptions = listOf("Don't block any cookies", "Block third-party cookies only")
                val redirectOptions = listOf("Stop redirecting to app", "Ask before redirecting", "Always allow")

                if (showCookieDialog) {
                    SingleChoiceDialog(
                        title = "Cookie Management",
                        options = cookieOptions,
                        selectedIndex = cookieChoice.coerceIn(0, cookieOptions.lastIndex),
                        onSelect = { idx ->
                            cookieChoice = idx
                            SettingsPrefs.setInt(context, SettingsPrefs.K_PG_COOKIES, idx)
                            SettingsPrefs.setString(context, SettingsPrefs.K_PRIVACY_LEVEL, SettingsPrefs.PRIVACY_CUSTOM)
                            PrivacyEngineSync.applyAll(context, components)
                            showCookieDialog = false
                        },
                        onDismiss = { showCookieDialog = false }
                    )
                }

                if (showRedirectDialog) {
                    SingleChoiceDialog(
                        title = "Stop App Redirects",
                        options = redirectOptions,
                        selectedIndex = redirectChoice.coerceIn(0, redirectOptions.lastIndex),
                        onSelect = { idx ->
                            redirectChoice = idx
                            SettingsPrefs.setInt(context, SettingsPrefs.K_PG_REDIRECT, idx)
                            showRedirectDialog = false
                        },
                        onDismiss = { showRedirectDialog = false }
                    )
                }

                UhSettingsScaffold(
                    title = "Privacy Guard",
                    subtitle = "Preset: ${PrivacyEngineSync.presetDisplayName(privacyLevel)}",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Protection Intensity")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PresetCard(
                            title = "Standard",
                            subtitle = "Balanced shields",
                            selected = privacyLevel == SettingsPrefs.PRIVACY_STANDARD,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                PrivacyEngineSync.applyPreset(context, components, SettingsPrefs.PRIVACY_STANDARD)
                                reloadStatesFromPrefs()
                            }
                        )
                        PresetCard(
                            title = "Strict",
                            subtitle = "Max hardening",
                            selected = privacyLevel == SettingsPrefs.PRIVACY_STRICT,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                PrivacyEngineSync.applyPreset(context, components, SettingsPrefs.PRIVACY_STRICT)
                                reloadStatesFromPrefs()
                            }
                        )
                        PresetCard(
                            title = "Custom",
                            subtitle = "Per-toggle rules",
                            selected = privacyLevel == SettingsPrefs.PRIVACY_CUSTOM,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                SettingsPrefs.setString(context, SettingsPrefs.K_PRIVACY_LEVEL, SettingsPrefs.PRIVACY_CUSTOM)
                                PrivacyEngineSync.applyAll(context, components)
                            }
                        )
                    }

                    SettingsCategory("Privacy Control")
                    UhSettingsCardGroup {
                        SettingsSwitchItem(
                            title = "Ads & Trackers Blocker",
                            subtitle = "Block trackers, analytics, cryptominers & banners",
                            checked = adsBlocked,
                            iconRes = R.drawable.fx_ic_shield_lock_24,
                            insideCard = true,
                            onCheckedChange = {
                                adsBlocked = it
                                onCustomToggle(SettingsPrefs.K_PG_ADS, it)
                            }
                        )
                        UhSettingsDivider()
                        SettingsSwitchItem(
                            title = "Always Use HTTPS",
                            subtitle = "Upgrade HTTP requests to encrypted HTTPS",
                            checked = forceHttps,
                            iconRes = R.drawable.fx_ic_lock_24,
                            insideCard = true,
                            onCheckedChange = {
                                forceHttps = it
                                onCustomToggle(SettingsPrefs.K_PG_HTTPS, it)
                            }
                        )
                        UhSettingsDivider()
                        SettingsSwitchItem(
                            title = "Remove Fingerprint",
                            subtitle = "Canvas noise & hardware concurrency shielding",
                            checked = antiFingerprint,
                            iconRes = R.drawable.fx_ic_shield_lock_24,
                            insideCard = true,
                            onCheckedChange = {
                                antiFingerprint = it
                                onCustomToggle(SettingsPrefs.K_PG_FINGERPRINT, it)
                            }
                        )
                        UhSettingsDivider()
                        SettingsSwitchItem(
                            title = "Script Control",
                            subtitle = "Strict execution policy for third-party scripts",
                            checked = scriptControl,
                            iconRes = R.drawable.fx_ic_scripts_24,
                            insideCard = true,
                            onCheckedChange = {
                                scriptControl = it
                                onCustomToggle(SettingsPrefs.K_PG_SCRIPT, it)
                            }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Cookie Management",
                            subtitle = cookieOptions.getOrElse(cookieChoice) { cookieOptions.first() },
                            iconRes = R.drawable.fx_ic_storage_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showCookieDialog = true }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Stop App Redirects",
                            subtitle = redirectOptions.getOrElse(redirectChoice) { redirectOptions[1] },
                            iconRes = R.drawable.fx_ic_link_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showRedirectDialog = true }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "Site Settings",
                            subtitle = "Camera, location, mic, autoplay & site exceptions",
                            iconRes = R.drawable.fx_ic_settings_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, SiteSettingsActivity::class.java))
                            }
                        )
                    }

                    SettingsCategory("More")
                    UhSettingsCardGroup {
                        SettingsRow(
                            title = "Advanced",
                            subtitle = "Safe Browsing, DNT, GPC, Secure DNS & WebRTC",
                            iconRes = R.drawable.fx_ic_settings_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = {
                                context.startActivity(Intent(context, AdvancedActivity::class.java))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = MaterialTheme.colorScheme.primary)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp)
    )
}
