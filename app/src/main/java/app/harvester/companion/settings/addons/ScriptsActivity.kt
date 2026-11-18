package app.harvester.companion.settings.addons

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.R
import app.harvester.companion.UserscriptManager
import app.harvester.companion.UserscriptStore
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import coil.compose.AsyncImage

class ScriptsActivity : AppCompatActivity() {

    private var reloadTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                val tick = reloadTick
                val rows = remember(tick) {
                    UserscriptManager.loadScripts(context).map { s ->
                        val perms = UserscriptStore.getPermissions(context, s.name)
                        ScriptRowUi(
                            name = s.name,
                            displayName = s.displayName,
                            description = s.description,
                            version = s.version,
                            author = s.author,
                            homepage = s.homepage,
                            runAt = s.runAt,
                            patterns = if (perms.overrideMatches.isNotEmpty()) perms.overrideMatches else s.patterns,
                            rawPatterns = s.patterns,
                            excludes = s.excludes,
                            grants = s.grants,
                            connects = s.connects,
                            requires = s.requires,
                            usesSystemjs = s.usesSystemjs,
                            fileSize = s.fileSize,
                            enabled = UserscriptStore.isEnabled(context, s.name),
                            iconUrl = s.iconUrl,
                            perms = perms
                        )
                    }
                }

                var showInstallOptions by remember { mutableStateOf(false) }
                var showUrlDialog by remember { mutableStateOf(false) }
                var showTextDialog by remember { mutableStateOf(false) }
                var infoRow by remember { mutableStateOf<ScriptRowUi?>(null) }
                var permsRow by remember { mutableStateOf<ScriptRowUi?>(null) }
                var deleteTarget by remember { mutableStateOf<String?>(null) }

                val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    uri?.let {
                        if (UserscriptManager.installFromUri(context, it)) {
                            GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                            Toast.makeText(context, "Installed", Toast.LENGTH_SHORT).show()
                            reloadTick++
                        } else {
                            Toast.makeText(context, "Install failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                if (showInstallOptions) {
                    AlertDialog(
                        onDismissRequest = { showInstallOptions = false },
                        title = { Text("Install Userscript", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Column {
                                InstallOptionItem("From URL", R.drawable.fx_ic_link_24) {
                                    showInstallOptions = false
                                    showUrlDialog = true
                                }
                                InstallOptionItem("From File (.user.js / .zip)", R.drawable.fx_ic_folder_24) {
                                    showInstallOptions = false
                                    filePicker.launch(arrayOf("*/*"))
                                }
                                InstallOptionItem("Write / Paste Code", R.drawable.fx_ic_edit_24) {
                                    showInstallOptions = false
                                    showTextDialog = true
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showInstallOptions = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                if (showUrlDialog) {
                    var url by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showUrlDialog = false },
                        title = { Text("Install from URL", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("Userscript URL") },
                                placeholder = { Text("https://example.com/script.user.js") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val trimmed = url.trim()
                                    showUrlDialog = false
                                    if (trimmed.isNotBlank()) {
                                        Toast.makeText(context, "Downloading…", Toast.LENGTH_SHORT).show()
                                        UserscriptManager.installFromUrl(context, trimmed) { ok ->
                                            runOnUiThread {
                                                if (ok) GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                                                Toast.makeText(
                                                    context,
                                                    if (ok) "Installed" else "Failed",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                reloadTick++
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text("Install", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showUrlDialog = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                if (showTextDialog) {
                    var body by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showTextDialog = false },
                        title = { Text("Paste or Write Userscript", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            OutlinedTextField(
                                value = body,
                                onValueChange = { body = it },
                                placeholder = {
                                    Text("// ==UserScript==\n// @name ...\n// @match ...\n// ==/UserScript==")
                                },
                                minLines = 8,
                                maxLines = 14,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showTextDialog = false
                                    if (body.isNotBlank()) {
                                        if (UserscriptManager.installFromText(context, body)) {
                                            GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                                            Toast.makeText(context, "Installed", Toast.LENGTH_SHORT).show()
                                            reloadTick++
                                        } else {
                                            Toast.makeText(context, "Install failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ) {
                                Text("Install", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showTextDialog = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                permsRow?.let { row ->
                    PermissionsOverrideDialog(
                        row = row,
                        onSave = { updatedPerms ->
                            UserscriptStore.setPermissions(context, row.name, updatedPerms)
                            UserscriptManager.invalidateCache()
                            GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                            permsRow = null
                            reloadTick++
                            Toast.makeText(context, "Permissions updated", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = { permsRow = null }
                    )
                }

                infoRow?.let { row ->
                    AlertDialog(
                        onDismissRequest = { infoRow = null },
                        title = { Text(row.displayName.ifBlank { row.name }, color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            Text(
                                text = buildInfoText(row),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { infoRow = null }) {
                                Text("OK", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                deleteTarget?.let { targetName ->
                    AlertDialog(
                        onDismissRequest = { deleteTarget = null },
                        title = { Text("Delete $targetName?", color = MaterialTheme.colorScheme.onSurface) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    deleteTarget = null
                                    UserscriptManager.uninstall(context, targetName)
                                    GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                                    reloadTick++
                                }
                            ) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { deleteTarget = null }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Userscripts",
                    subtitle = "${rows.count { it.enabled }}/${rows.size} active",
                    scrollable = false,
                    onBack = { finish() },
                    actions = {
                        Button(
                            onClick = { showInstallOptions = true },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Install", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                ) {
                    if (rows.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_scripts_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No userscripts installed",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(rows, key = { _, r -> r.name }) { idx, row ->
                                ScriptCard(
                                    index = idx + 1,
                                    row = row,
                                    onToggle = { enabled ->
                                        UserscriptStore.setEnabled(context, row.name, enabled)
                                        GeckoWebExtensionBridge.reloadScriptsOnActiveTab()
                                        reloadTick++
                                    },
                                    onPermissions = { permsRow = row },
                                    onEdit = {
                                        context.startActivity(
                                            Intent(context, ScriptEditorActivity::class.java)
                                                .putExtra("name", row.name)
                                        )
                                    },
                                    onInfo = { infoRow = row },
                                    onOpenHomepage = {
                                        if (row.homepage.isNotBlank()) {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(row.homepage)))
                                            } catch (_: Exception) {}
                                        }
                                    },
                                    onDelete = { deleteTarget = row.name }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadTick++
    }

    private fun buildInfoText(row: ScriptRowUi): String {
        val sb = StringBuilder()
        if (row.version.isNotBlank()) sb.append("Version: ").append(row.version).append("\n")
        if (row.author.isNotBlank()) sb.append("Author: ").append(row.author).append("\n")
        sb.append("Run at: ").append(row.runAt).append("\n")
        sb.append("Size: ").append(formatSize(row.fileSize)).append("\n")
        sb.append("CSP Bypass: ").append(if (row.perms.bypassCsp) "Enabled" else "Off").append("\n")
        sb.append("Unrestricted @grant: ").append(if (row.perms.allowAllGrants) "Granted" else "Restricted").append("\n")
        if (row.patterns.isNotEmpty()) {
            sb.append("\nMatches:\n")
            row.patterns.take(10).forEach { sb.append("  ").append(it).append("\n") }
            if (row.patterns.size > 10) sb.append("  …").append(row.patterns.size - 10).append(" more\n")
        }
        if (row.grants.isNotEmpty()) {
            sb.append("\nDeclared Grants:\n")
            row.grants.take(15).forEach { sb.append("  ").append(it).append("\n") }
        }
        if (row.connects.isNotEmpty()) {
            sb.append("\n@connect:\n")
            row.connects.take(10).forEach { sb.append("  ").append(it).append("\n") }
        }
        if (row.requires.isNotEmpty()) {
            sb.append("\nRequires: ").append(row.requires.size).append(" external script(s)\n")
        }
        if (row.description.isNotBlank()) {
            sb.append("\n").append(row.description)
        }
        return sb.toString()
    }

    private fun formatSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.1f MB".format(b / (1024.0 * 1024.0))
    }
}

private data class ScriptRowUi(
    val name: String,
    val displayName: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String,
    val runAt: String,
    val patterns: List<String>,
    val rawPatterns: List<String>,
    val excludes: List<String>,
    val grants: List<String>,
    val connects: List<String>,
    val requires: List<String>,
    val usesSystemjs: Boolean,
    val fileSize: Long,
    val enabled: Boolean,
    val iconUrl: String,
    val perms: UserscriptStore.UserscriptPermissions
)

@Composable
private fun PermissionsOverrideDialog(
    row: ScriptRowUi,
    onSave: (UserscriptStore.UserscriptPermissions) -> Unit,
    onDismiss: () -> Unit
) {
    var allowAllGrants by remember { mutableStateOf(row.perms.allowAllGrants) }
    var bypassCsp by remember { mutableStateOf(row.perms.bypassCsp) }
    var allowAllConnect by remember { mutableStateOf(row.perms.allowAllConnect) }
    var allFrames by remember { mutableStateOf(row.perms.allFrames) }
    var matchText by remember { mutableStateOf(row.perms.overrideMatches.joinToString("\n")) }
    var excludeText by remember { mutableStateOf(row.perms.overrideExcludes.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Permissions & Overrides", color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(row.displayName.ifBlank { row.name }, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PermToggleRow("Unrestricted GM_* APIs", "Grant full GM_xmlhttpRequest, Storage, Download, Tabs", allowAllGrants) { allowAllGrants = it }
                PermToggleRow("Bypass Page CSP", "Strip restrictive Content-Security-Policy headers", bypassCsp) { bypassCsp = it }
                PermToggleRow("Allow All @connect Domains", "Bypass cross-origin XHR domain restrictions (*)", allowAllConnect) { allowAllConnect = it }
                PermToggleRow("Run in All Frames", "Override @noframes to inject in iframes too", allFrames) { allFrames = it }

                OutlinedTextField(
                    value = matchText,
                    onValueChange = { matchText = it },
                    label = { Text("Override @match patterns (one per line)") },
                    placeholder = { Text(row.rawPatterns.take(2).joinToString("\n")) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = excludeText,
                    onValueChange = { excludeText = it },
                    label = { Text("Extra @exclude patterns (one per line)") },
                    placeholder = { Text("https://example.com/exclude/*") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val matches = matchText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val excludes = excludeText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                onSave(
                    UserscriptStore.UserscriptPermissions(
                        allowAllGrants = allowAllGrants,
                        bypassCsp = bypassCsp,
                        allowAllConnect = allowAllConnect,
                        allFrames = allFrames,
                        overrideMatches = matches,
                        overrideExcludes = excludes
                    )
                )
            }) {
                Text("Apply", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun PermToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                checkedTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun InstallOptionItem(label: String, iconRes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
    }
}

@Composable
private fun ScriptCard(
    index: Int,
    row: ScriptRowUi,
    onToggle: (Boolean) -> Unit,
    onPermissions: () -> Unit,
    onEdit: () -> Unit,
    onInfo: () -> Unit,
    onOpenHomepage: () -> Unit,
    onDelete: () -> Unit,
) {
    val capabilities = remember(row) {
        buildList {
            if (row.perms.allowAllGrants) add("Full GM API")
            if (row.perms.bypassCsp) add("CSP Bypass")
            if (row.perms.allowAllConnect || "*" in row.connects) add("@connect *")
            if (row.grants.any { it.contains("xmlhttpRequest", true) }) add("XHR")
            if (row.grants.any { it.contains("Value", true) }) add("Storage")
            if (row.perms.allFrames) add("All Frames")
        }.distinct()
    }

    val statusColor = if (row.enabled) ComposeColor(0xFF10B981) else MaterialTheme.colorScheme.outlineVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (row.enabled) statusColor.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Tampermonkey-style numbered slot + script icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (row.iconUrl.isNotBlank()) {
                    AsyncImage(
                        model = row.iconUrl,
                        contentDescription = null,
                        error = painterResource(id = R.drawable.fx_ic_scripts_24),
                        modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Text(
                        text = "$index",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = row.displayName.ifBlank { row.name },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (row.version.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "v${row.version}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Text(
                    text = buildString {
                        append(row.runAt)
                        if (row.author.isNotBlank()) append(" · ${row.author}")
                        append(" · ${row.patterns.size} rule(s)")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.background,
                    checkedTrackColor = statusColor
                )
            )
        }

        if (row.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = row.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Granted permission badges
        if (capabilities.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                capabilities.forEach { cap ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = cap,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
                    .clickable(onClick = onPermissions)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Permissions",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.homepage.isNotBlank()) {
                    IconButton(onClick = onOpenHomepage, modifier = Modifier.size(34.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_link_24),
                            contentDescription = "Homepage",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(onClick = onInfo, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_info_24),
                        contentDescription = "Script info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_edit_24),
                        contentDescription = "Edit script",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_delete_24),
                        contentDescription = "Delete script",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
