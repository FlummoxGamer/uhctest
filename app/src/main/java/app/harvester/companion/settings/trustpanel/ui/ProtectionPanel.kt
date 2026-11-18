package app.harvester.companion.settings.trustpanel.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.components.menu.compose.MenuGroup
import app.harvester.companion.components.menu.compose.MenuItem
import app.harvester.companion.components.menu.compose.MenuScaffold
import app.harvester.companion.settings.privacy.PrivacyEngineSync
import app.harvester.companion.settings.trustpanel.store.AutoplayValue
import app.harvester.companion.settings.trustpanel.store.WebsiteInfoState
import app.harvester.companion.settings.trustpanel.store.WebsitePermission

private val CyberBg = Color(0xFF070B12)
private val CyberCard = Color(0xFF0E1624)
private val CyberCyan = Color(0xFF00E5FF)
private val CyberGreen = Color(0xFF10B981)
private val CyberRed = Color(0xFFFF3366)
private val CyberText = Color(0xFFE6F4FF)
private val CyberMuted = Color(0xFF7FA0C4)

@Composable
internal fun ProtectionPanel(
    websiteInfoState: WebsiteInfoState,
    icon: Bitmap?,
    isTrackingProtectionEnabled: Boolean,
    isGlobalTrackingProtectionEnabled: Boolean,
    isLocalPdf: Boolean,
    numberOfTrackersBlocked: Int,
    panelStyle: String = SettingsPrefs.TRUST_STYLE_ADAPTIVE,
    privacyPreset: String = SettingsPrefs.PRIVACY_STANDARD,
    onToggleStyle: () -> Unit = {},
    ublockInstalled: Boolean = false,
    ublockGlobalEnabled: Boolean = true,
    ublockSitePaused: Boolean = false,
    ublockVersion: String = "",
    ublockBlockedCount: Int = 0,
    onToggleUblockSite: () -> Unit = {},
    onOpenExtensions: () -> Unit = {},
    websitePermissions: List<WebsitePermission>,
    onTrackerBlockedMenuClick: () -> Unit,
    onTrackingProtectionToggleClick: () -> Unit,
    onClearSiteDataMenuClick: () -> Unit,
    onPrivacySecuritySettingsClick: () -> Unit,
    onAutoplayValueClick: (AutoplayValue) -> Unit,
    onToggleablePermissionClick: (WebsitePermission.Toggleable) -> Unit,
    onViewCertificateClick: () -> Unit,
) {
    val isCyberpunk = panelStyle == SettingsPrefs.TRUST_STYLE_CYBERPUNK
    val totalTrackersBlocked = numberOfTrackersBlocked + if (ublockInstalled && ublockGlobalEnabled && !ublockSitePaused) ublockBlockedCount else 0

    if (isCyberpunk) {
        CyberpunkTrustHud(
            websiteInfoState = websiteInfoState,
            isTrackingProtectionEnabled = isGlobalTrackingProtectionEnabled && (isTrackingProtectionEnabled || isLocalPdf),
            rawTrackersBlocked = numberOfTrackersBlocked,
            totalTrackersBlocked = totalTrackersBlocked,
            privacyPreset = privacyPreset,
            isLocalPdf = isLocalPdf,
            onToggleStyle = onToggleStyle,
            ublockInstalled = ublockInstalled,
            ublockGlobalEnabled = ublockGlobalEnabled,
            ublockSitePaused = ublockSitePaused,
            ublockVersion = ublockVersion,
            onToggleUblockSite = onToggleUblockSite,
            onOpenExtensions = onOpenExtensions,
            websitePermissions = websitePermissions,
            onTrackerBlockedMenuClick = onTrackerBlockedMenuClick,
            onTrackingProtectionToggleClick = onTrackingProtectionToggleClick,
            onClearSiteDataMenuClick = onClearSiteDataMenuClick,
            onPrivacySecuritySettingsClick = onPrivacySecuritySettingsClick,
            onAutoplayValueClick = onAutoplayValueClick,
            onToggleablePermissionClick = onToggleablePermissionClick,
            onViewCertificateClick = onViewCertificateClick
        )
        return
    }

    MenuScaffold {
        // Adaptive M3 Telemetry Header + Live Style Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Trust Telemetry",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = PrivacyEngineSync.presetDisplayName(privacyPreset),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable(onClick = onToggleStyle)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Style: M3",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        ProtectionPanelBanner(
            isSecured = websiteInfoState.isSecured,
            isTrackingProtectionEnabled = isGlobalTrackingProtectionEnabled && (isTrackingProtectionEnabled || isLocalPdf),
            numberOfTrackersBlocked = totalTrackersBlocked,
            onClick = onTrackerBlockedMenuClick.takeIf { numberOfTrackersBlocked > 0 },
        )

        Spacer(modifier = Modifier.height(6.dp))

        MenuGroup {
            val ublockStatusDesc = when {
                !ublockInstalled -> "Provisioning uBlock Origin..."
                !ublockGlobalEnabled -> "Disabled globally in Extensions"
                ublockSitePaused -> "Paused for this site"
                ublockVersion.isNotBlank() -> "Active (v$ublockVersion)"
                else -> "Active protection"
            }
            MenuItem(
                label = "uBlock Origin Adblocker",
                description = ublockStatusDesc,
                beforeIconPainter = painterResource(id = R.drawable.fx_ic_shield_lock_24),
                onClick = if (ublockInstalled && ublockGlobalEnabled) onToggleUblockSite else onOpenExtensions,
                afterContent = {
                    if (ublockInstalled && ublockGlobalEnabled) {
                        Switch(
                            checked = !ublockSitePaused,
                            onCheckedChange = { onToggleUblockSite() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.background,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    } else {
                        Text(
                            text = "Manage",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onOpenExtensions() }
                        )
                    }
                }
            )
        }

        MenuGroup {
            MenuItem(
                label = "Tracking Protection",
                description = if (isTrackingProtectionEnabled) "On for this site" else "Off for this site",
                beforeIconPainter = painterResource(id = R.drawable.ic_shield),
                onClick = onTrackingProtectionToggleClick,
                afterContent = {
                    Switch(
                        checked = isTrackingProtectionEnabled,
                        onCheckedChange = { onTrackingProtectionToggleClick() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.background,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            )
        }

        MenuGroup {
            MenuItem(
                label = if (websiteInfoState.isSecured) "Secure connection" else "Not secure",
                description = if (websiteInfoState.isSecured) "Tap to view TLS certificate" else "Unencrypted HTTP connection",
                beforeIconPainter = painterResource(
                    id = if (websiteInfoState.isSecured) R.drawable.uh_ic_lock else R.drawable.uh_ic_lock_open
                ),
                onClick = onViewCertificateClick,
            )
        }

        if (!isLocalPdf) {
            MenuGroup {
                MenuItem(
                    label = "Clear cookies and site data",
                    beforeIconPainter = painterResource(id = R.drawable.fx_ic_delete_24),
                    onClick = onClearSiteDataMenuClick,
                )
            }
        }

        if (websitePermissions.isNotEmpty()) {
            Text(
                text = "Permissions",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp, start = 16.dp)
            )
            MenuGroup {
                websitePermissions.forEach { websitePermission ->
                    key(websitePermission.deviceFeature) {
                        val stateDescription = when (websitePermission) {
                            is WebsitePermission.Autoplay -> when (websitePermission.autoplayValue) {
                                AutoplayValue.AUTOPLAY_ALLOW_ALL -> "Allowed"
                                AutoplayValue.AUTOPLAY_BLOCK_AUDIBLE -> "Block Audio"
                                AutoplayValue.AUTOPLAY_BLOCK_ALL -> "Blocked"
                            }
                            is WebsitePermission.Toggleable -> if (websitePermission.isBlockedByAndroid) "Blocked by Android" else if (websitePermission.isEnabled) "Allowed" else "Blocked"
                        }
                        MenuItem(
                            label = stringResource(id = websitePermission.deviceFeature.getLabelId()),
                            beforeIconPainter = painterResource(id = websitePermission.deviceFeature.getIconId()),
                            stateDescription = stateDescription,
                            onClick = {
                                when (websitePermission) {
                                    is WebsitePermission.Toggleable -> onToggleablePermissionClick(websitePermission)
                                    is WebsitePermission.Autoplay -> onAutoplayValueClick(websitePermission.autoplayValue)
                                }
                            },
                            afterContent = {
                                Text(
                                    text = stateDescription,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.End
                                )
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Privacy Settings",
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onPrivacySecuritySettingsClick() }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Extensions",
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onOpenExtensions() }
            )
        }
    }
}

@Composable
private fun CyberpunkTrustHud(
    websiteInfoState: WebsiteInfoState,
    isTrackingProtectionEnabled: Boolean,
    rawTrackersBlocked: Int,
    totalTrackersBlocked: Int,
    privacyPreset: String,
    isLocalPdf: Boolean,
    onToggleStyle: () -> Unit,
    ublockInstalled: Boolean,
    ublockGlobalEnabled: Boolean,
    ublockSitePaused: Boolean,
    ublockVersion: String,
    onToggleUblockSite: () -> Unit,
    onOpenExtensions: () -> Unit,
    websitePermissions: List<WebsitePermission>,
    onTrackerBlockedMenuClick: () -> Unit,
    onTrackingProtectionToggleClick: () -> Unit,
    onClearSiteDataMenuClick: () -> Unit,
    onPrivacySecuritySettingsClick: () -> Unit,
    onAutoplayValueClick: (AutoplayValue) -> Unit,
    onToggleablePermissionClick: (WebsitePermission.Toggleable) -> Unit,
    onViewCertificateClick: () -> Unit,
) {
    val shieldAccent = when {
        !websiteInfoState.isSecured -> CyberRed
        !isTrackingProtectionEnabled -> Color(0xFFFFB020)
        else -> CyberCyan
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberBg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Header HUD bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "UH // SHIELD HUD",
                    color = CyberCyan,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "MODE: ${PrivacyEngineSync.presetDisplayName(privacyPreset).uppercase()}",
                    color = CyberMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberCard)
                    .border(1.dp, CyberCyan.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleStyle)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "HUD: CYBER",
                    color = CyberCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Neon Telemetry Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(CyberCard, Color(0xFF0A1D2C))))
                .border(1.dp, shieldAccent.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .clickable(enabled = rawTrackersBlocked > 0, onClick = onTrackerBlockedMenuClick)
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (websiteInfoState.isSecured) "TLS VERIFIED" else "UNENCRYPTED HTTP",
                        color = shieldAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = websiteInfoState.websiteTitle.ifBlank { websiteInfoState.websiteUrl.ifBlank { "Active Session" } },
                        color = CyberText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$totalTrackersBlocked trackers & ads intercepted",
                        color = CyberGreen,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_shield),
                    contentDescription = null,
                    tint = shieldAccent,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        // uBlock Origin HUD card
        CyberHudCard(accent = if (!ublockSitePaused && ublockInstalled && ublockGlobalEnabled) CyberGreen else CyberMuted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = if (ublockInstalled && ublockGlobalEnabled) onToggleUblockSite else onOpenExtensions),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("uBlock Origin", color = CyberText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            !ublockInstalled -> "PROVISIONING"
                            !ublockGlobalEnabled -> "DISABLED GLOBALLY"
                            ublockSitePaused -> "PAUSED ON HOST"
                            else -> "ACTIVE ${if (ublockVersion.isNotBlank()) "v$ublockVersion" else ""}"
                        },
                        color = if (!ublockSitePaused && ublockInstalled && ublockGlobalEnabled) CyberGreen else CyberMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (ublockInstalled && ublockGlobalEnabled) {
                    Switch(
                        checked = !ublockSitePaused,
                        onCheckedChange = { onToggleUblockSite() },
                        colors = SwitchDefaults.colors(checkedThumbColor = CyberBg, checkedTrackColor = CyberGreen)
                    )
                }
            }
        }

        // Tracking Protection HUD card
        CyberHudCard(accent = if (isTrackingProtectionEnabled) CyberCyan else CyberRed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTrackingProtectionToggleClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Engine Tracking Shield", color = CyberText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (isTrackingProtectionEnabled) "STATUS: ENFORCED" else "STATUS: BYPASSED",
                        color = if (isTrackingProtectionEnabled) CyberCyan else CyberRed,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked = isTrackingProtectionEnabled,
                    onCheckedChange = { onTrackingProtectionToggleClick() },
                    colors = SwitchDefaults.colors(checkedThumbColor = CyberBg, checkedTrackColor = CyberCyan)
                )
            }
        }

        // Action row: TLS Cert + Clear Site Data
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CyberHudCard(accent = CyberCyan.copy(alpha = 0.5f), modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onViewCertificateClick)) {
                    Text("TLS CERT", color = CyberCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(if (websiteInfoState.isSecured) "Inspect Chain" else "Not Secure", color = CyberText, fontSize = 13.sp)
                }
            }
            if (!isLocalPdf) {
                CyberHudCard(accent = CyberRed.copy(alpha = 0.5f), modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClearSiteDataMenuClick)) {
                        Text("SITE DATA", color = CyberRed, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("Wipe Cookies", color = CyberText, fontSize = 13.sp)
                    }
                }
            }
        }

        if (websitePermissions.isNotEmpty()) {
            CyberHudCard(accent = CyberMuted.copy(alpha = 0.4f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SITE PERMISSIONS", color = CyberMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    websitePermissions.forEach { perm ->
                        val stateDesc = when (perm) {
                            is WebsitePermission.Autoplay -> when (perm.autoplayValue) {
                                AutoplayValue.AUTOPLAY_ALLOW_ALL -> "ALLOW"
                                AutoplayValue.AUTOPLAY_BLOCK_AUDIBLE -> "NO AUDIO"
                                AutoplayValue.AUTOPLAY_BLOCK_ALL -> "BLOCK"
                            }
                            is WebsitePermission.Toggleable -> if (perm.isBlockedByAndroid) "ANDROID BLOCKED" else if (perm.isEnabled) "ALLOW" else "BLOCK"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when (perm) {
                                        is WebsitePermission.Toggleable -> onToggleablePermissionClick(perm)
                                        is WebsitePermission.Autoplay -> onAutoplayValueClick(perm.autoplayValue)
                                    }
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(perm.deviceFeature.getLabelId()),
                                color = CyberText,
                                fontSize = 13.sp
                            )
                            Text(
                                text = stateDesc,
                                color = CyberCyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "[ PRIVACY GUARD ]",
                color = CyberCyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onPrivacySecuritySettingsClick)
            )
            Text(
                text = "[ EXTENSIONS ]",
                color = CyberCyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onOpenExtensions)
            )
        }
    }
}

@Composable
private fun CyberHudCard(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CyberCard)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        content()
    }
}
