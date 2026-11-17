package app.harvester.companion.settings.addons

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.harvester.companion.GeckoEngine
import app.harvester.companion.HomeActivity
import app.harvester.companion.R
import app.harvester.companion.settings.components.UhSettingsScaffold
import app.harvester.companion.theme.UHTheme
import org.mozilla.geckoview.WebExtension

class ExtensionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContent {
            UHTheme {
                val context = LocalContext.current
                var extensions by remember { mutableStateOf<List<WebExtension>>(emptyList()) }
                var showInstallDialog by remember { mutableStateOf(false) }
                var confirmUninstall by remember { mutableStateOf<WebExtension?>(null) }

                fun loadExtensions() {
                    val runtime = GeckoEngine.getRuntime(context) ?: return
                    runtime.webExtensionController.list().accept({ extList ->
                        val filtered = extList?.filter {
                            it.id != "uh-bridge@harvester.app" && !it.isBuiltIn
                        } ?: emptyList()
                        runOnUiThread { extensions = filtered }
                    }, {
                        runOnUiThread {
                            Toast.makeText(context, "Failed to load extensions", Toast.LENGTH_SHORT).show()
                        }
                    })
                }

                LaunchedEffect(Unit) {
                    loadExtensions()
                }

                if (showInstallDialog) {
                    var urlInput by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showInstallDialog = false },
                        title = { Text("Install Extension", color = MaterialTheme.colorScheme.onSurface) },
                        text = {
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                label = { Text("Extension URL (.xpi)") },
                                placeholder = { Text("https://addons.mozilla.org/...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val trimmed = urlInput.trim()
                                    showInstallDialog = false
                                    if (trimmed.isNotBlank()) {
                                        val runtime = GeckoEngine.getRuntime(context) ?: return@TextButton
                                        runtime.webExtensionController.install(trimmed).accept({
                                            runOnUiThread {
                                                Toast.makeText(context, "Installed", Toast.LENGTH_SHORT).show()
                                                loadExtensions()
                                            }
                                        }, { err ->
                                            runOnUiThread {
                                                Toast.makeText(context, "Failed: ${err?.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        })
                                    }
                                }
                            ) {
                                Text("Install", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showInstallDialog = false }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                confirmUninstall?.let { ext ->
                    val extTitle = ext.metaData?.name ?: ext.id
                    AlertDialog(
                        onDismissRequest = { confirmUninstall = null },
                        title = { Text("Uninstall $extTitle?", color = MaterialTheme.colorScheme.onSurface) },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    confirmUninstall = null
                                    val runtime = GeckoEngine.getRuntime(context) ?: return@TextButton
                                    runtime.webExtensionController.uninstall(ext).accept({
                                        runOnUiThread {
                                            Toast.makeText(context, "Uninstalled", Toast.LENGTH_SHORT).show()
                                            loadExtensions()
                                        }
                                    }, {
                                        runOnUiThread {
                                            Toast.makeText(context, "Failed to uninstall", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                                }
                            ) {
                                Text("Uninstall", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmUninstall = null }) {
                                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(20.dp)
                    )
                }

                UhSettingsScaffold(
                    title = "Extensions",
                    scrollable = false,
                    onBack = { finish() },
                    actions = {
                        Button(
                            onClick = { showInstallDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Install", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                ) {
                    if (extensions.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_extensions_24),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No extensions installed",
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
                            items(extensions, key = { it.id }) { ext ->
                                ExtensionCard(
                                    ext = ext,
                                    onOpenOptions = { url ->
                                        context.startActivity(
                                            Intent(context, HomeActivity::class.java).apply {
                                                data = Uri.parse(url)
                                            }
                                        )
                                    },
                                    onUninstall = { confirmUninstall = ext }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    ext: WebExtension,
    onOpenOptions: (String) -> Unit,
    onUninstall: () -> Unit,
) {
    val meta = ext.metaData
    val iconBitmap by produceState<Bitmap?>(initialValue = null, key1 = ext.id) {
        meta?.icon?.getBitmap(64)?.accept(
            { bmp -> value = bmp },
            {}
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_extensions_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meta?.name ?: ext.id,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${meta?.version ?: "1.0"} · ${meta?.description ?: "No description"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        val optionsUrl = meta?.optionsPageUrl
        if (!optionsUrl.isNullOrBlank()) {
            IconButton(onClick = { onOpenOptions(optionsUrl) }) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_settings_24),
                    contentDescription = "Extension options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        IconButton(onClick = onUninstall) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_delete_24),
                contentDescription = "Uninstall extension",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
