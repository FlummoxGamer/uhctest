package app.harvester.companion.browser.devtools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.GeckoWebExtensionBridge
import app.harvester.companion.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DevToolsSheet(
    currentUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activeTab by DevToolsManager.activeTab.collectAsState()
    val erudaActive by DevToolsManager.erudaActive.collectAsState()
    val consoleLogs by DevToolsManager.consoleLogs.collectAsState()
    val networkLogs by DevToolsManager.networkLogs.collectAsState()
    val domSnapshot by DevToolsManager.domSnapshot.collectAsState()
    val storageSnapshot by DevToolsManager.storageSnapshot.collectAsState()
    val perfSnapshot by DevToolsManager.perfSnapshot.collectAsState()

    LaunchedEffect(activeTab, currentUrl) {
        when (activeTab) {
            DevToolsManager.Tab.ELEMENTS -> GeckoWebExtensionBridge.sendDevToolsCommand("INSPECT_DOM")
            DevToolsManager.Tab.STORAGE -> GeckoWebExtensionBridge.sendDevToolsCommand("INSPECT_STORAGE")
            DevToolsManager.Tab.PERFORMANCE -> GeckoWebExtensionBridge.sendDevToolsCommand("INSPECT_PERF")
            else -> Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.76f)
                .imePadding()
                .navigationBarsPadding()
                .clickable(enabled = false) {}
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_devtools),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Web DevTools",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = currentUrl.ifBlank { "No active page" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Toggle Floating Visual Inspector (Eruda Offline)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (erudaActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .border(
                                1.dp,
                                if (erudaActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                val next = !erudaActive
                                DevToolsManager.setErudaActive(next)
                                GeckoWebExtensionBridge.toggleErudaOverlay(context, next)
                                if (next) onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (erudaActive) "Hide Overlay" else "Eruda Overlay",
                            color = if (erudaActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_close_24),
                            contentDescription = "Close DevTools",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Tab Strip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        DevToolsManager.Tab.CONSOLE to "Console (${consoleLogs.size})",
                        DevToolsManager.Tab.NETWORK to "Network (${networkLogs.size})",
                        DevToolsManager.Tab.ELEMENTS to "Elements",
                        DevToolsManager.Tab.STORAGE to "Storage",
                        DevToolsManager.Tab.PERFORMANCE to "Performance"
                    ).forEach { (tab, title) ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable { DevToolsManager.selectTab(tab) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = title,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))

                when (activeTab) {
                    DevToolsManager.Tab.CONSOLE -> ConsoleTab(consoleLogs)
                    DevToolsManager.Tab.NETWORK -> NetworkTab(networkLogs)
                    DevToolsManager.Tab.ELEMENTS -> ElementsTab(domSnapshot)
                    DevToolsManager.Tab.STORAGE -> StorageTab(storageSnapshot)
                    DevToolsManager.Tab.PERFORMANCE -> PerformanceTab(perfSnapshot)
                }
            }
        }
    }
}

@Composable
private fun ConsoleTab(logs: List<DevToolsManager.ConsoleEntry>) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("all") }
    var jsInput by remember { mutableStateOf("") }
    val history = remember { mutableListOf<String>() }
    val filtered = remember(logs, filter) {
        if (filter == "all") logs else logs.filter { it.level == filter }
    }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("all" to "All", "error" to "Errors", "warn" to "Warn", "log" to "Log").forEach { (k, label) ->
                val sel = filter == k
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
                        .clickable { filter = k }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = label,
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Clear",
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { DevToolsManager.clearConsole() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = "Console ready. Type JavaScript below or inspect live page output.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(filtered, key = { it.id }) { entry ->
                val badgeColor = when (entry.level) {
                    "error" -> Color(0xFFEF4444)
                    "warn" -> Color(0xFFF59E0B)
                    "result" -> Color(0xFF22C55E)
                    else -> MaterialTheme.colorScheme.primary
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("console", entry.message))
                            Toast.makeText(context, "Copied log line", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 5.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.message,
                            color = if (entry.level == "error") Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${entry.level.uppercase()} • ${timeFmt.format(Date(entry.timestampMs))}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // REPL input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = ">",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                BasicTextField(
                    value = jsInput,
                    onValueChange = { jsInput = it },
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val code = jsInput.trim()
                            if (code.isNotEmpty()) {
                                history.add(code)
                                DevToolsManager.appendConsole("log", "> $code", "repl")
                                GeckoWebExtensionBridge.evalDevToolsJs(code)
                                jsInput = ""
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (jsInput.isEmpty()) {
                            Text(
                                "document.title, window.location...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        inner()
                    }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        val code = jsInput.trim()
                        if (code.isNotEmpty()) {
                            history.add(code)
                            DevToolsManager.appendConsole("log", "> $code", "repl")
                            GeckoWebExtensionBridge.evalDevToolsJs(code)
                            jsInput = ""
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("Run", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NetworkTab(logs: List<DevToolsManager.NetworkEntry>) {
    var selected by remember { mutableStateOf<DevToolsManager.NetworkEntry?>(null) }

    if (selected != null) {
        val item = selected!!
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("${item.method} • ${item.status}", fontSize = 16.sp) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text(item.url, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Duration: ${item.durationMs}ms  •  Type: ${item.reqType}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.requestHeaders.isNotEmpty()) {
                        item {
                            Text("Request Headers", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            item.requestHeaders.forEach { (k, v) ->
                                Text("$k: $v", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    if (item.responseHeaders.isNotEmpty()) {
                        item {
                            Text("Response Headers", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            item.responseHeaders.forEach { (k, v) ->
                                Text("$k: $v", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    if (item.responsePreview.isNotBlank()) {
                        item {
                            Text("Response Preview", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(item.responsePreview, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${logs.size} requests captured",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Clear",
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { DevToolsManager.clearNetwork() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (logs.isEmpty()) {
                item {
                    Text(
                        text = "No fetch or XHR requests captured on this page yet. Reload or interact with the page.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(logs.asReversed(), key = { it.id }) { item ->
                val statusColor = when {
                    item.status in 200..299 -> Color(0xFF22C55E)
                    item.status in 300..399 -> Color(0xFF38BDF8)
                    item.status >= 400 || item.status == 0 -> Color(0xFFEF4444)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { selected = item }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (item.status > 0) "${item.method} ${item.status}" else item.method,
                            color = statusColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.url,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.reqType} • ${item.durationMs}ms",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ElementsTab(snapshot: DevToolsManager.DomSnapshot?) {
    var cssSelector by remember { mutableStateOf("") }
    var inlineCss by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = snapshot?.let { "${it.nodeCount} DOM nodes • ${it.charset}" } ?: "Scanning DOM…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Refresh DOM",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { GeckoWebExtensionBridge.sendDevToolsCommand("INSPECT_DOM") }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (snapshot != null && snapshot.inspectedSelector.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Selected: ${snapshot.inspectedSelector}",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    snapshot.inspectedComputed.forEach { (k, v) ->
                        Text("$k: $v", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = inlineCss,
                            onValueChange = { inlineCss = it },
                            placeholder = { Text("color: red; border: 1px solid cyan;", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            val sel = cssSelector.ifBlank { snapshot.inspectedSelector }
                            GeckoWebExtensionBridge.applyElementStyle(sel, inlineCss)
                        }) {
                            Text("Apply", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val nodes = snapshot?.nodes.orEmpty()
            items(nodes) { node ->
                val indent = (node.depth.coerceIn(0, 8) * 10).dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = indent)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            cssSelector = node.selector
                            GeckoWebExtensionBridge.inspectDomSelector(node.selector)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildString {
                            append("<${node.tag}")
                            if (node.idAttr.isNotBlank()) append(" #${node.idAttr}")
                            if (node.classes.isNotBlank()) append(" .${node.classes.take(24)}")
                            append(">")
                            if (node.textPreview.isNotBlank()) append(" \"${node.textPreview.take(36)}\"")
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (node.childCount > 0) {
                        Text(
                            text = "${node.childCount}",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageTab(storage: DevToolsManager.StorageSnapshot) {
    var section by remember { mutableStateOf("local") }
    var editingKey by remember { mutableStateOf<String?>(null) }
    var editingVal by remember { mutableStateOf("") }

    if (editingKey != null) {
        AlertDialog(
            onDismissRequest = { editingKey = null },
            title = { Text("Edit ${section} Key", fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(editingKey!!, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = editingVal,
                        onValueChange = { editingVal = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    GeckoWebExtensionBridge.setStorageItem(section, editingKey!!, editingVal)
                    editingKey = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        GeckoWebExtensionBridge.removeStorageItem(section, editingKey!!)
                        editingKey = null
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { editingKey = null }) { Text("Cancel") }
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }

    val itemsList = when (section) {
        "session" -> storage.sessionItems
        "cookies" -> storage.cookies
        else -> storage.localItems
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                "local" to "Local (${storage.localItems.size})",
                "session" to "Session (${storage.sessionItems.size})",
                "cookies" to "Cookies (${storage.cookies.size})"
            ).forEach { (k, label) ->
                val sel = section == k
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
                        .clickable { section = k }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = label,
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Refresh",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { GeckoWebExtensionBridge.sendDevToolsCommand("INSPECT_STORAGE") }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (itemsList.isEmpty()) {
                item {
                    Text(
                        text = "No entries found in ${section} storage.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(itemsList) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            editingKey = item.key
                            editingVal = item.value
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(item.key, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(item.value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PerformanceTab(perf: DevToolsManager.PerfSnapshot?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Navigation & Runtime Telemetry",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Sample Now",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { GeckoWebExtensionBridge.sendDevToolsCommand("INSPECT_PERF") }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (perf == null) {
            Text(
                text = "Sampling page navigation timings...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        } else {
            PerfRow("DNS Lookup", "${perf.dnsMs} ms")
            PerfRow("TCP Handshake", "${perf.tcpMs} ms")
            PerfRow("Time to First Byte (TTFB)", "${perf.ttfbMs} ms")
            PerfRow("DOM Interactive", "${perf.domInteractiveMs} ms")
            PerfRow("DOM Complete", "${perf.domCompleteMs} ms")
            PerfRow("Page Load Event", "${perf.loadEventMs} ms")
            PerfRow("Loaded Resources", "${perf.resourceCount}")
            PerfRow("Transferred Payload", "${perf.transferKb} KB")
            if (perf.memoryMb > 0f) {
                PerfRow("JS Heap Used", "${"%.1f".format(perf.memoryMb)} MB")
            }
        }
    }
}

@Composable
private fun PerfRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}
