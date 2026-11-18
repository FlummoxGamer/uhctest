package app.harvester.companion.settings.privacy

import android.graphics.Color
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import app.harvester.companion.UHApp
import app.harvester.companion.settings.PhoneFeature
import app.harvester.companion.settings.components.SettingsCategory
import app.harvester.companion.settings.components.SettingsRow
import app.harvester.companion.settings.components.UhSettingsCardGroup
import app.harvester.companion.settings.components.UhSettingsDivider
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.permission.SitePermissions

class SiteSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val ctx = LocalContext.current
                val components = (ctx.applicationContext as UHApp).components
                val scope = rememberCoroutineScope()

                val features = remember {
                    listOf(
                        PhoneFeature.CAMERA to ("Camera" to R.drawable.uh_ic_camera_24),
                        PhoneFeature.LOCATION to ("Location" to R.drawable.uh_ic_location_24),
                        PhoneFeature.MICROPHONE to ("Microphone" to R.drawable.uh_ic_microphone_24),
                        PhoneFeature.NOTIFICATION to ("Notifications" to R.drawable.uh_ic_notification_24),
                        PhoneFeature.PERSISTENT_STORAGE to ("Persistent Storage" to R.drawable.uh_ic_storage_24),
                        PhoneFeature.CROSS_ORIGIN_STORAGE_ACCESS to ("Third-Party Storage" to R.drawable.uh_ic_cookies_24),
                        PhoneFeature.AUTOPLAY_AUDIBLE to ("Autoplay Audio" to R.drawable.uh_ic_autoplay_24)
                    )
                }

                var permDefaults by remember {
                    mutableStateOf(
                        features.associate { (f, _) ->
                            f to SettingsPrefs.getInt(ctx, "site_perm_${f.name.lowercase()}", 1)
                        }
                    )
                }
                var editingFeature by remember { mutableStateOf<Pair<PhoneFeature, String>?>(null) }
                var savedSites by remember { mutableStateOf<List<SitePermissions>>(emptyList()) }
                var selectedSite by remember { mutableStateOf<SitePermissions?>(null) }
                var confirmClearAll by remember { mutableStateOf(false) }

                fun reloadSites() {
                    scope.launch {
                        val list = withContext(Dispatchers.IO) {
                            try {
                                components.geckoSitePermissionsStorage.all()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                        savedSites = list.sortedBy { it.origin.lowercase() }
                    }
                }

                LaunchedEffect(Unit) { reloadSites() }

                val modeLabels = listOf("Blocked", "Ask to allow", "Allowed")

                editingFeature?.let { (feature, label) ->
                    val curVal = permDefaults[feature] ?: 1
                    AlertDialog(
                        onDismissRequest = { editingFeature = null },
                        title = { Text("$label Default", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Column {
                                modeLabels.forEachIndexed { idx, modeName ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                SettingsPrefs.setInt(ctx, "site_perm_${feature.name.lowercase()}", idx)
                                                permDefaults = permDefaults + (feature to idx)
                                                editingFeature = null
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(selected = curVal == idx, onClick = {
                                            SettingsPrefs.setInt(ctx, "site_perm_${feature.name.lowercase()}", idx)
                                            permDefaults = permDefaults + (feature to idx)
                                            editingFeature = null
                                        })
                                        Spacer(Modifier.width(8.dp))
                                        Text(modeName, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { editingFeature = null }) {
                                Text("Close", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                selectedSite?.let { site ->
                    AlertDialog(
                        onDismissRequest = { selectedSite = null },
                        title = { Text(site.origin, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SitePermSummaryRow("Camera", site.camera.name)
                                SitePermSummaryRow("Location", site.location.name)
                                SitePermSummaryRow("Microphone", site.microphone.name)
                                SitePermSummaryRow("Notifications", site.notification.name)
                                SitePermSummaryRow("Local Storage", site.localStorage.name)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    components.geckoSitePermissionsStorage.remove(site, private = false)
                                    withContext(Dispatchers.Main) {
                                        selectedSite = null
                                        reloadSites()
                                    }
                                }
                            }) {
                                Text("Reset Site", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { selectedSite = null }) {
                                Text("Close", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                if (confirmClearAll) {
                    AlertDialog(
                        onDismissRequest = { confirmClearAll = false },
                        title = { Text("Reset all site permissions?", color = MaterialTheme.colorScheme.onSurface) },
                        text = { Text("All saved per-site exceptions will be removed.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    components.geckoSitePermissionsStorage.removeAll()
                                    withContext(Dispatchers.Main) {
                                        confirmClearAll = false
                                        reloadSites()
                                    }
                                }
                            }) {
                                Text("Reset All", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmClearAll = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Site Settings",
                    subtitle = "Global defaults & per-site exceptions",
                    onBack = { finish() }
                ) {
                    SettingsCategory("Default Site Permissions")
                    UhSettingsCardGroup {
                        features.forEachIndexed { idx, (feature, pair) ->
                            val (label, iconRes) = pair
                            val stateVal = permDefaults[feature] ?: 1
                            SettingsRow(
                                title = label,
                                subtitle = modeLabels.getOrElse(stateVal) { "Ask to allow" },
                                iconRes = iconRes,
                                showChevron = true,
                                insideCard = true,
                                onClick = { editingFeature = feature to label }
                            )
                            if (idx < features.lastIndex) UhSettingsDivider()
                        }
                    }

                    SettingsCategory("Saved Site Exceptions (${savedSites.size})")
                    UhSettingsCardGroup {
                        if (savedSites.isEmpty()) {
                            SettingsRow(
                                title = "No site exceptions saved",
                                subtitle = "Permissions granted or blocked on websites appear here",
                                iconRes = R.drawable.fx_ic_shield_lock_24,
                                insideCard = true,
                                onClick = {}
                            )
                        } else {
                            savedSites.forEachIndexed { idx, site ->
                                SettingsRow(
                                    title = site.origin,
                                    subtitle = buildExceptionSummary(site),
                                    iconRes = R.drawable.fx_ic_shield_lock_24,
                                    showChevron = true,
                                    insideCard = true,
                                    onClick = { selectedSite = site }
                                )
                                if (idx < savedSites.lastIndex) UhSettingsDivider()
                            }
                            UhSettingsDivider()
                            SettingsRow(
                                title = "Reset All Site Exceptions",
                                subtitle = "Restore every website back to default rules",
                                iconRes = R.drawable.fx_ic_delete_24,
                                insideCard = true,
                                onClick = { confirmClearAll = true }
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SitePermSummaryRow(name: String, status: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(
            text = status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun buildExceptionSummary(site: SitePermissions): String {
    val active = mutableListOf<String>()
    if (site.camera != SitePermissions.Status.NO_DECISION) active.add("Camera: ${site.camera.name.lowercase()}")
    if (site.location != SitePermissions.Status.NO_DECISION) active.add("Location: ${site.location.name.lowercase()}")
    if (site.microphone != SitePermissions.Status.NO_DECISION) active.add("Mic: ${site.microphone.name.lowercase()}")
    if (site.notification != SitePermissions.Status.NO_DECISION) active.add("Notify: ${site.notification.name.lowercase()}")
    return if (active.isEmpty()) "Custom rule" else active.take(2).joinToString(" • ")
}
