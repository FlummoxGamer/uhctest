package app.harvester.companion.browser.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import app.harvester.companion.SearchEnginePrefs
import app.harvester.companion.SettingsPrefs
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun UHAddressBar(
    url: String,
    isEditing: Boolean,
    tabCount: Int,
    iconState: Int,
    isWallpaperActive: Boolean,
    editText: String,
    progress: Int = 0,
    isLoading: Boolean = false,
    onEditTextChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onEditStart: () -> Unit,
    onClear: () -> Unit,
    onSearchEngineClick: () -> Unit,
    onLockClick: () -> Unit,
    onScriptMenuClick: () -> Unit,
    onMenuClick: () -> Unit,
    onTabCountClick: () -> Unit,
    searchEngine: SearchEnginePrefs.Entry,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bottomBarColor = if (isWallpaperActive) Color.Transparent else MaterialTheme.colorScheme.background
    val showFullUrl = SettingsPrefs.getBool(context, SettingsPrefs.K_SHOW_FULL_URL, false)
    val keyboardController = LocalSoftwareKeyboardController.current

    fun formatUrl(rawUrl: String): String {
        var cleanUrl = rawUrl
        if (cleanUrl.startsWith("https://")) cleanUrl = cleanUrl.removePrefix("https://")
        if (cleanUrl.startsWith("http://")) cleanUrl = cleanUrl.removePrefix("http://")

        if (!showFullUrl) {
            if (cleanUrl.startsWith("www.")) cleanUrl = cleanUrl.removePrefix("www.")
            cleanUrl = cleanUrl.substringBefore("/")
        }
        return cleanUrl
    }

    val textState = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(url, isEditing, editText) {
        val targetText = if (!isEditing) formatUrl(url) else editText
        if (textState.text.toString() != targetText) {
            textState.edit {
                replace(0, length, targetText)
                placeCursorAtEnd()
            }
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            kotlinx.coroutines.delay(100)
            try {
                focusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(textState) {
        snapshotFlow { textState.text.toString() }
            .distinctUntilChanged()
            .collect { typed ->
                if (isEditing && typed != editText) {
                    onEditTextChanged(typed)
                }
            }
    }

    val pillShape = RoundedCornerShape(24.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(bottomBarColor)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .padding(horizontal = 2.dp)
                .clip(pillShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, pillShape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(start = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onScriptMenuClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_code),
                        contentDescription = "Scripts",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Box(
                    modifier = Modifier.size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { if (iconState == 0) onSearchEngineClick() else onLockClick() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        when (iconState) {
                            1 -> Icon(
                                painterResource(R.drawable.uh_ic_shield_checkmark_24),
                                contentDescription = "Secure",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            2 -> Icon(
                                painterResource(R.drawable.uh_ic_shield_slash_24),
                                contentDescription = "Not Secure",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            else -> {
                                AsyncImage(
                                    model = searchEngine.iconUrl,
                                    contentDescription = searchEngine.name,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                    if (iconState == 0) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_triangle_down),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.BottomEnd)
                                .padding(end = 2.dp, bottom = 2.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (isEditing) {
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides TextSelectionColors(
                                handleColor = Color.Transparent,
                                backgroundColor = Color.Transparent
                            )
                        ) {
                            BasicTextField(
                                state = textState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(end = 28.dp)
                                    .focusRequester(focusRequester)
                                    .background(Color.Transparent),
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                onKeyboardAction = KeyboardActionHandler {
                                    if (textState.text.isNotBlank()) onSubmit(textState.text.toString())
                                },
                                decorator = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (textState.text.isEmpty()) {
                                            Text(
                                                "Search or type URL",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 14.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                        if (textState.text.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    textState.edit {
                                        replace(0, length, "")
                                        placeCursorAtEnd()
                                    }
                                    onClear()
                                },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .size(28.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_close),
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = if (url.isBlank() || url == "about:blank") "Search or type URL" else formatUrl(url),
                            color = if (url.isBlank() || url == "about:blank") {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp)
                                .clickable { onEditStart() }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(24.dp)
                        .border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp))
                        .clickable { onTabCountClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$tabCount",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_menu),
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            if (!isEditing && isLoading && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}
