package app.harvester.companion.settings.privacy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.SettingsSwitchRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme

class AdvancedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val components = (context.applicationContext as UHApp).components

                var webrtcPolicy by remember {
                    mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_WEBRTC_POLICY, 0))
                }
                var showWebRtcDialog by remember { mutableStateOf(false) }

                val webrtcOptions = listOf(
                    "Default (Standard WebRTC)" to "Allows normal peer-to-peer WebRTC connections",
                    "Disable non-proxied UDP (Relay only)" to "Prevents local & public IP leaks via ICE relay restriction",
                    "Disable WebRTC completely" to "Blocks RTCPeerConnection on all web pages"
                )

                fun markCustomAndSync() {
                    SettingsPrefs.setString(context, SettingsPrefs.K_PRIVACY_LEVEL, SettingsPrefs.PRIVACY_CUSTOM)
                    PrivacyEngineSync.applyAll(context, components)
                }

                if (showWebRtcDialog) {
                    AlertDialog(
                        onDismissRequest = { showWebRtcDialog = false },
                        title = { Text("WebRTC IP Protection", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Column {
                                webrtcOptions.forEachIndexed { idx, (label, desc) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                webrtcPolicy = idx
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_WEBRTC_POLICY, idx)
                                                markCustomAndSync()
                                                showWebRtcDialog = false
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = webrtcPolicy == idx,
                                            onClick = {
                                                webrtcPolicy = idx
                                                SettingsPrefs.setInt(context, SettingsPrefs.K_WEBRTC_POLICY, idx)
                                                markCustomAndSync()
                                                showWebRtcDialog = false
                                            }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                                            Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showWebRtcDialog = false }) {
                                Text("Close", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Advanced",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Protections")
                    UhSettingsCardGroup {
                        SettingsSwitchRow(
                            title = "Safe Browsing",
                            subtitle = "Warn on dangerous or deceptive sites",
                            key = SettingsPrefs.K_ADV_SAFE_BROWSING,
                            default = false,
                            insideCard = true,
                            onChanged = { markCustomAndSync() }
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Do Not Track",
                            subtitle = "Send DNT: 1 header & navigator signal",
                            key = SettingsPrefs.K_ADV_DNT,
                            default = false,
                            insideCard = true,
                            onChanged = { markCustomAndSync() }
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Auto De-AMP",
                            subtitle = "Redirect AMP pages to canonical URLs",
                            key = SettingsPrefs.K_ADV_DEAMP,
                            default = false,
                            insideCard = true,
                            onChanged = { markCustomAndSync() }
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Global Privacy Control",
                            subtitle = "Send Sec-GPC: 1 legal opt-out signal",
                            key = SettingsPrefs.K_ADV_GPC,
                            default = false,
                            insideCard = true,
                            onChanged = { markCustomAndSync() }
                        )
                        UhSettingsDivider()
                        SettingsSwitchRow(
                            title = "Secure DNS",
                            subtitle = "DNS-over-HTTPS (Cloudflare Resolver)",
                            key = SettingsPrefs.K_ADV_SECURE_DNS,
                            default = false,
                            insideCard = true,
                            onChanged = { markCustomAndSync() }
                        )
                        UhSettingsDivider()
                        SettingsRow(
                            title = "WebRTC IP Protection",
                            subtitle = webrtcOptions.getOrElse(webrtcPolicy) { webrtcOptions.first() }.first,
                            iconRes = R.drawable.fx_ic_shield_lock_24,
                            showChevron = true,
                            insideCard = true,
                            onClick = { showWebRtcDialog = true }
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
