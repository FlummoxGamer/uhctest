package app.harvester.companion.browser.tabs

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.harvester.companion.R
import coil.compose.AsyncImage
import mozilla.components.browser.state.state.TabSessionState
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TabsTrayScreen(
    tabs: List<TabSessionState>,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllTabs: (isPrivate: Boolean) -> Unit,
    onNewTab: (isPrivate: Boolean) -> Unit,
    onCloseSelectedTabs: (List<String>) -> Unit,
    onShareSelectedTabs: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val initialPrivate = remember { tabs.find { it.id == selectedTabId }?.content?.private ?: false }
    var isPrivateMode by remember { mutableStateOf(initialPrivate) }
    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedTabIds by remember { mutableStateOf(setOf<String>()) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val filteredTabs = remember(tabs, isPrivateMode) {
        tabs.filter { it.content.private == isPrivateMode }
    }
    val normalCount = remember(tabs) { tabs.count { !it.content.private } }
    val privateCount = remember(tabs) { tabs.count { it.content.private } }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridColumns = if (isLandscape) 4 else 2

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Header Bar
            if (isMultiSelect) {
                MultiSelectHeader(
                    selectedCount = selectedTabIds.size,
                    totalCount = filteredTabs.size,
                    onToggleSelectAll = {
                        selectedTabIds = if (selectedTabIds.size == filteredTabs.size) {
                            emptySet()
                        } else {
                            filteredTabs.map { it.id }.toSet()
                        }
                    },
                    onDeleteSelected = {
                        if (selectedTabIds.isNotEmpty()) {
                            onCloseSelectedTabs(selectedTabIds.toList())
                            selectedTabIds = emptySet()
                            isMultiSelect = false
                        }
                    },
                    onShareSelected = {
                        if (selectedTabIds.isNotEmpty()) {
                            onShareSelectedTabs(selectedTabIds.toList())
                        }
                    },
                    onDone = {
                        isMultiSelect = false
                        selectedTabIds = emptySet()
                    }
                )
            } else {
                TabsTrayHeader(
                    isPrivateMode = isPrivateMode,
                    normalCount = normalCount,
                    privateCount = privateCount,
                    onModeChange = { isPrivateMode = it },
                    onDismiss = onDismiss
                )
            }

            // Private Browsing Info Banner
            if (isPrivateMode && filteredTabs.isNotEmpty()) {
                PrivateModeBanner(
                    onCloseAllPrivate = { onCloseAllTabs(true) }
                )
            }

            // Tabs Grid
            if (filteredTabs.isEmpty()) {
                EmptyTabsView(
                    isPrivate = isPrivateMode,
                    onNewTab = { onNewTab(isPrivateMode) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(filteredTabs, key = { it.id }) { tab ->
                        val isSelected = tab.id == selectedTabId
                        val isChecked = tab.id in selectedTabIds

                        if (isMultiSelect) {
                            TabCardContent(
                                tab = tab,
                                isSelected = isSelected,
                                isMultiSelect = true,
                                isChecked = isChecked,
                                onClick = {
                                    selectedTabIds = if (isChecked) {
                                        selectedTabIds - tab.id
                                    } else {
                                        selectedTabIds + tab.id
                                    }
                                },
                                onClose = { onCloseTab(tab.id) }
                            )
                        } else {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    if (value != SwipeToDismissBoxValue.Settled) {
                                        onCloseTab(tab.id)
                                        true
                                    } else false
                                }
                            )

                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = true,
                                enableDismissFromEndToStart = true,
                                backgroundContent = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer)
                                            .padding(horizontal = 16.dp),
                                        contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                                            Alignment.CenterStart
                                        } else Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.fx_ic_delete_24),
                                            contentDescription = "Close Tab",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            ) {
                                TabCardContent(
                                    tab = tab,
                                    isSelected = isSelected,
                                    isMultiSelect = false,
                                    isChecked = false,
                                    onClick = { onSelectTab(tab.id) },
                                    onClose = { onCloseTab(tab.id) }
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Bar
            if (!isMultiSelect) {
                TabsTrayBottomBar(
                    isPrivateMode = isPrivateMode,
                    hasTabs = filteredTabs.isNotEmpty(),
                    showMenu = showOverflowMenu,
                    onMenuToggle = { showOverflowMenu = it },
                    onCloseAll = {
                        showOverflowMenu = false
                        onCloseAllTabs(isPrivateMode)
                    },
                    onStartSelect = {
                        showOverflowMenu = false
                        isMultiSelect = true
                        selectedTabIds = emptySet()
                    },
                    onNewTab = { onNewTab(isPrivateMode) }
                )
            }
        }
    }
}

@Composable
private fun TabsTrayHeader(
    isPrivateMode: Boolean,
    normalCount: Int,
    privateCount: Int,
    onModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Normal Tabs Segment
            TabSegmentButton(
                title = stringResource(R.string.tabs_tray_normal),
                count = normalCount,
                isSelected = !isPrivateMode,
                iconRes = R.drawable.ic_tabs,
                onClick = { onModeChange(false) }
            )

            // Private Tabs Segment
            TabSegmentButton(
                title = stringResource(R.string.tabs_tray_private),
                count = privateCount,
                isSelected = isPrivateMode,
                iconRes = R.drawable.uh_ic_private_mode_24,
                onClick = { onModeChange(true) }
            )
        }

        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_close_24),
                contentDescription = "Close Tabs Tray",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun TabSegmentButton(
    title: String,
    count: Int,
    isSelected: Boolean,
    iconRes: Int,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = title,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "$count",
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MultiSelectHeader(
    selectedCount: Int,
    totalCount: Int,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$selectedCount selected",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = onToggleSelectAll) {
                Text(
                    text = if (selectedCount == totalCount) "Deselect All" else "Select All",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (selectedCount > 0) {
                IconButton(onClick = onShareSelected, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                        contentDescription = "Share",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDeleteSelected, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_delete_24),
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            TextButton(onClick = onDone) {
                Text(
                    text = stringResource(R.string.tabs_tray_done),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PrivateModeBanner(
    onCloseAllPrivate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_private_mode_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "Private Browsing",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Tabs leave no trace on this device",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        TextButton(
            onClick = onCloseAllPrivate,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Close all",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun TabCardContent(
    tab: TabSessionState,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    isChecked: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isChecked -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    }
    val borderWidth = if (isSelected || isChecked) 2.dp else 1.dp

    val host = remember(tab.content.url) {
        try {
            val uri = URI(tab.content.url)
            uri.host?.removePrefix("www.") ?: tab.content.url
        } catch (_: Exception) {
            tab.content.url
        }
    }

    val previewBitmap = remember(tab.id) { TabPreviewCache.get(tab.id) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 6.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Card Top Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (tab.content.icon != null) {
                    Image(
                        bitmap = tab.content.icon!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_globe),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = tab.content.title.ifBlank { host.ifBlank { "New Tab" } },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (isMultiSelect) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_close_24),
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Preview Body
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                if (previewBitmap != null && !previewBitmap.isRecycled) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (host.firstOrNull() ?: 'U').uppercase(),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Card Footer
            Text(
                text = host.ifBlank { "about:blank" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun EmptyTabsView(
    isPrivate: Boolean,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(
                id = if (isPrivate) R.drawable.uh_ic_private_mode_24 else R.drawable.ic_tabs
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isPrivate) "No private tabs open" else "No open tabs",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isPrivate) {
                "Tabs you open in private mode won't be saved in your browsing history."
            } else {
                "Tap the button below to open a new tab."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onNewTab,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_plus_24),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open New Tab")
        }
    }
}

@Composable
private fun TabsTrayBottomBar(
    isPrivateMode: Boolean,
    hasTabs: Boolean,
    showMenu: Boolean,
    onMenuToggle: (Boolean) -> Unit,
    onCloseAll: () -> Unit,
    onStartSelect: () -> Unit,
    onNewTab: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            IconButton(
                onClick = { onMenuToggle(true) },
                enabled = hasTabs,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_more_24),
                    contentDescription = "Tabs Options",
                    tint = if (hasTabs) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { onMenuToggle(false) },
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isPrivateMode) stringResource(R.string.tabs_tray_close_all_private) else stringResource(R.string.tabs_tray_close_all),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_delete_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = onCloseAll
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.tabs_tray_select_tabs),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(id = R.drawable.fx_ic_check_24),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = onStartSelect
                )
            }
        }

        // New Tab Button
        Button(
            onClick = onNewTab,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_plus_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.tabs_tray_new_tab),
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
