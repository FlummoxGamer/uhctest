package app.harvester.companion.browser.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs

@Composable
fun TranslateBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isTranslating by TranslationManager.isTranslating.collectAsState()
    val isTranslated by TranslationManager.isTranslated.collectAsState()
    val lastError by TranslationManager.lastError.collectAsState()
    val targetLang by SettingsPrefs.translateLangFlow(context).collectAsState()
    val activeEngine by SettingsPrefs.translateEngineFlow(context).collectAsState()
    val langLabel = TranslationManager.languageDisplayName(targetLang)
    val engineBadge = if (activeEngine == SettingsPrefs.TRANSLATE_ENGINE_MLKIT) "Offline" else "Direct"

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_translate_24),
                contentDescription = "Translate",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { TranslationManager.openSettings() }
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = when {
                        isTranslating -> "Translating to $langLabel..."
                        !lastError.isNullOrBlank() -> lastError!!
                        isTranslated -> "Translated to $langLabel ($engineBadge)"
                        else -> "Translate to $langLabel ($engineBadge)"
                    },
                    color = if (!lastError.isNullOrBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isTranslating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                TextButton(
                    onClick = {
                        if (isTranslated) {
                            TranslationManager.restoreOriginal()
                        } else {
                            TranslationManager.translateCurrentPage(targetLang)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isTranslated) "Original" else "Translate",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = { TranslationManager.openSettings() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_settings_24),
                    contentDescription = "Translation settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = { TranslationManager.closeBar() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_close_24),
                    contentDescription = "Close translate bar",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun TranslateSettingsDialog() {
    val context = LocalContext.current
    val activeEngine by SettingsPrefs.translateEngineFlow(context).collectAsState()
    val targetLang by SettingsPrefs.translateLangFlow(context).collectAsState()
    val downloadedModels by TranslationManager.downloadedModels.collectAsState()
    val downloadingModel by TranslationManager.downloadingModel.collectAsState()

    AlertDialog(
        onDismissRequest = { TranslationManager.closeSettings() },
        title = {
            Text("Translation Settings", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "TRANSLATION SERVICE",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )

                listOf(
                    Triple(
                        SettingsPrefs.TRANSLATE_ENGINE_DIRECT,
                        "Direct Cloud API (Fast)",
                        "Translates in-place without leaving page or triggering CAPTCHAs"
                    ),
                    Triple(
                        SettingsPrefs.TRANSLATE_ENGINE_MLKIT,
                        "Offline ML Kit (On-Device)",
                        "Private local translation using downloaded language packs"
                    )
                ).forEach { (id, title, sub) ->
                    val selected = activeEngine == id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                SettingsPrefs.setString(context, SettingsPrefs.K_TRANSLATE_ENGINE, id)
                                if (id == SettingsPrefs.TRANSLATE_ENGINE_MLKIT) {
                                    TranslationManager.refreshDownloadedModels()
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                SettingsPrefs.setString(context, SettingsPrefs.K_TRANSLATE_ENGINE, id)
                            }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Text(
                    text = "TARGET LANGUAGE",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(TranslationManager.supportedLanguages, key = { it.code }) { lang ->
                        val isSelected = targetLang.equals(lang.code, ignoreCase = true)
                        val isDownloaded = downloadedModels.contains(lang.mlKitTag)
                        val isDownloadingThis = downloadingModel == lang.mlKitTag

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surface
                                )
                                .clickable {
                                    SettingsPrefs.setString(context, SettingsPrefs.K_TRANSLATE_LANG, lang.code)
                                    TranslationManager.closeSettings()
                                    TranslationManager.translateCurrentPage(lang.code)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lang.displayName,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            if (activeEngine == SettingsPrefs.TRANSLATE_ENGINE_MLKIT) {
                                when {
                                    isDownloadingThis -> {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    }
                                    isDownloaded -> {
                                        Text(
                                            text = "Offline ✓",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { TranslationManager.deleteOfflineModel(lang.mlKitTag) }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = "Download",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { TranslationManager.downloadOfflineModel(lang.mlKitTag) }
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            } else if (isSelected) {
                                Icon(
                                    painter = painterResource(id = R.drawable.fx_ic_check_24),
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { TranslationManager.closeSettings() }) {
                Text("Done")
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(20.dp)
    )
}
