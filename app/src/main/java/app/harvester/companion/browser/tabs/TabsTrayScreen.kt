package app.harvester.companion.browser.tabs

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.harvester.companion.R
import app.harvester.companion.SettingsPrefs
import kotlinx.coroutines.launch
import mozilla.components.browser.state.state.TabSessionState
import java.net.URI

private enum class TraySection { NORMAL, PRIVATE, GROUPS }

private sealed interface TrayGridEntry {
    val key: String

    data class GroupHeader(
        val group: TabGroup,
        val memberTabs: List<TabSessionState>
    ) : TrayGridEntry {
        override val key: String get() = "hdr_${group.id}"
    }

    data class TabEntry(
        val tab: TabSessionState,
        val group: TabGroup?
    ) : TrayGridEntry {
        override val key: String get() = "tab_${tab.id}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsTrayScreen(
    tabs: List<TabSessionState>,
    groups: List<TabGroup>,
    selectedTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onRestoreClosedTab: (ClosedTabSnapshot) -> Unit,
    onCloseAllTabs: (isPrivate: Boolean) -> Unit,
    onNewTab: (isPrivate: Boolean) -> Unit,
    onCloseSelectedTabs: (List<String>) -> Unit,
    onShareSelectedTabs: (List<String>) -> Unit,
    onCreateGroup: (name: String, colorArgb: Long, tabIds: List<String>) -> Unit,
    onAddToGroup: (groupId: String, tabId: String) -> Unit,
    onRenameGroup: (groupId: String, name: String, colorArgb: Long) -> Unit,
    onToggleGroupCollapsed: (groupId: String) -> Unit,
    onUngroup: (groupId: String) -> Unit,
    onCloseGroupTabs: (groupId: String, tabIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val initialPrivate = remember { tabs.find { it.id == selectedTabId }?.content?.private ?: false }
    var activeSection by remember { mutableStateOf(if (initialPrivate) TraySection.PRIVATE else TraySection.NORMAL) }
    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedTabIds by remember { mutableStateOf(setOf<String>()) }

    var showOptionsSheet by remember { mutableStateOf(false) }
    var showGroupCreatorPicker by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var pendingGroupCreateIds by remember { mutableStateOf<List<String>?>(null) }
    var editingGroup by remember { mutableStateOf<TabGroup?>(null) }

    val isGridLayout by SettingsPrefs.tabLayoutGridFlow(context).collectAsState()
    var autoCollapseGroups by remember {
        mutableStateOf(SettingsPrefs.getBool(context, SettingsPrefs.K_TAB_AUTO_COLLAPSE_GROUPS, false))
    }
    var sleepSeconds by remember {
        mutableIntStateOf(SettingsPrefs.getInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, 0))
    }

    LaunchedEffect(autoCollapseGroups) {
        if (autoCollapseGroups && selectedTabId != null) {
            val activeGroupId = groups.firstOrNull { selectedTabId in it.tabIds }?.id
            groups.forEach { g ->
                if (g.id != activeGroupId && !g.isCollapsed) {
                    TabGroupStore.setCollapsed(g.id, true)
                }
            }
        }
    }

    val normalTabs = remember(tabs) { tabs.filter { !it.content.private } }
    val privateTabs = remember(tabs) { tabs.filter { it.content.private } }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val gridColumns = if (!isGridLayout) 1 else if (isLandscape) 4 else 2
    val isDark = isSystemInDarkTheme()

    val surfaceBg by animateColorAsState(
        targetValue = when {
            activeSection == TraySection.PRIVATE && isDark -> Color(0xFF140F20)
            activeSection == TraySection.PRIVATE && !isDark -> Color(0xFFF3EEFF)
            else -> MaterialTheme.colorScheme.background
        },
        label = "TrayBg"
    )
    val sectionAccent = if (activeSection == TraySection.PRIVATE) {
        if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
    } else {
        MaterialTheme.colorScheme.primary
    }

    // Build flattened grid items for NORMAL section (collapsible group stacks + ungrouped tabs)
    val normalGridEntries = remember(normalTabs, groups) {
        val tabMap = normalTabs.associateBy { it.id }
        val groupedTabIds = groups.flatMap { it.tabIds }.toSet()
        val entries = mutableListOf<TrayGridEntry>()

        for (group in groups) {
            val members = group.tabIds.mapNotNull { tabMap[it] }
            if (members.isNotEmpty()) {
                entries.add(TrayGridEntry.GroupHeader(group, members))
                if (!group.isCollapsed) {
                    members.forEach { member ->
                        entries.add(TrayGridEntry.TabEntry(member, group))
                    }
                }
            }
        }

        normalTabs.filter { it.id !in groupedTabIds }.forEach { tab ->
            entries.add(TrayGridEntry.TabEntry(tab, null))
        }
        entries
    }

    val privateGridEntries = remember(privateTabs) {
        privateTabs.map { TrayGridEntry.TabEntry(it, null) }
    }

    val activeEntries: List<TrayGridEntry> = when (activeSection) {
        TraySection.NORMAL -> normalGridEntries
        TraySection.PRIVATE -> privateGridEntries
        TraySection.GROUPS -> emptyList()
    }

    val lazyGridState = rememberLazyGridState()
    // Root-coordinate tracking for reliable drag-over-card / drag-over-group-header grouping
    val itemBounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingEntryKey by remember { mutableStateOf<String?>(null) }
    var dragTranslation by remember { mutableStateOf(Offset.Zero) }
    var hoveredDropTargetKey by remember { mutableStateOf<String?>(null) }
    val latestGroups by rememberUpdatedState(groups)

    fun closeTabWithUndo(tab: TabSessionState) {
        val group = groups.firstOrNull { tab.id in it.tabIds }
        val snapshot = ClosedTabSnapshot(
            url = tab.content.url,
            title = tab.content.title,
            isPrivate = tab.content.private,
            groupId = group?.id
        )
        onCloseTab(tab.id)
        val label = tab.content.title.ifBlank { extractHostLabel(tab.content.url).ifBlank { "Tab" } }
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val res = snackbarHostState.showSnackbar(
                message = "Closed \"$label\"",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (res == SnackbarResult.ActionPerformed) {
                onRestoreClosedTab(snapshot)
            }
        }
    }

    // Multi-tab group creation picker dialog (accessible directly from Options OR Groups section)
    if (showGroupCreatorPicker) {
        CreateGroupTabPickerDialog(
            normalTabs = normalTabs,
            existingGroups = groups,
            onCreate = { name, colorArgb, pickedIds ->
                if (pickedIds.isNotEmpty()) {
                    onCreateGroup(name, colorArgb, pickedIds)
                }
                showGroupCreatorPicker = false
            },
            onDismiss = { showGroupCreatorPicker = false }
        )
    }

    pendingGroupCreateIds?.let { idsToGroup ->
        GroupNameColorDialog(
            title = "New Tab Group",
            initialName = "Group ${groups.size + 1}",
            initialColorArgb = TabGroupStore.GROUP_COLORS[groups.size % TabGroupStore.GROUP_COLORS.size],
            confirmLabel = "Create",
            onConfirm = { name, color ->
                onCreateGroup(name, color, idsToGroup)
                pendingGroupCreateIds = null
                isMultiSelect = false
                selectedTabIds = emptySet()
            },
            onDismiss = { pendingGroupCreateIds = null }
        )
    }

    editingGroup?.let { grp ->
        GroupNameColorDialog(
            title = "Edit Group",
            initialName = grp.name,
            initialColorArgb = grp.colorArgb,
            confirmLabel = "Save",
            onConfirm = { name, color ->
                onRenameGroup(grp.id, name, color)
                editingGroup = null
            },
            onDismiss = { editingGroup = null }
        )
    }

    if (showSleepDialog) {
        val sleepChoices = listOf(
            0 to "Never sleep inactive tabs",
            1800 to "After 30 minutes",
            3600 to "After 1 hour",
            21600 to "After 6 hours",
            86400 to "After 24 hours"
        )
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("Auto-Sleep Inactive Tabs", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    sleepChoices.forEach { (sec, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sleepSeconds = sec
                                    SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, sec)
                                    showSleepDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = sleepSeconds == sec, onClick = {
                                sleepSeconds = sec
                                SettingsPrefs.setInt(context, SettingsPrefs.K_TAB_SLEEP_SECONDS, sec)
                                showSleepDialog = false
                            })
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepDialog = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showOptionsSheet) {
        val currentScopeTabs = if (activeSection == TraySection.PRIVATE) privateTabs else normalTabs
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Tab Settings & Actions",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                // View Layout toggle (Grid vs List)
                TabOptionRow(
                    title = if (isGridLayout) "Layout: Grid View" else "Layout: List View",
                    subtitle = "Switch between compact list and visual preview cards",
                    iconRes = R.drawable.ic_grid_view,
                    onClick = {
                        SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_LAYOUT_GRID, !isGridLayout)
                    },
                    trailing = {
                        Text(
                            text = if (isGridLayout) "Grid" else "List",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                )

                if (activeSection == TraySection.NORMAL) {
                    TabOptionRow(
                        title = "Create Tab Group",
                        subtitle = "Pick tabs or customize group name & color",
                        iconRes = R.drawable.uh_ic_plus_24,
                        onClick = {
                            showOptionsSheet = false
                            showGroupCreatorPicker = true
                        }
                    )
                }

                TabOptionRow(
                    title = stringResource(R.string.tabs_tray_select_tabs),
                    subtitle = "Batch close, group, or share multiple tabs",
                    iconRes = R.drawable.fx_ic_check_24,
                    onClick = {
                        showOptionsSheet = false
                        isMultiSelect = true
                        selectedTabIds = emptySet()
                    }
                )

                TabOptionRow(
                    title = "Auto-Collapse Tab Groups",
                    subtitle = "Automatically collapse inactive tab groups",
                    iconRes = R.drawable.ic_triangle_down,
                    onClick = {
                        val next = !autoCollapseGroups
                        autoCollapseGroups = next
                        SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_AUTO_COLLAPSE_GROUPS, next)
                    },
                    trailing = {
                        Switch(
                            checked = autoCollapseGroups,
                            onCheckedChange = { next ->
                                autoCollapseGroups = next
                                SettingsPrefs.setBool(context, SettingsPrefs.K_TAB_AUTO_COLLAPSE_GROUPS, next)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.background,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                )

                TabOptionRow(
                    title = "Auto-Sleep Inactive Tabs",
                    subtitle = when (sleepSeconds) {
                        0 -> "Never"
                        1800 -> "After 30 minutes"
                        3600 -> "After 1 hour"
                        21600 -> "After 6 hours"
                        else -> "After 24 hours"
                    },
                    iconRes = R.drawable.ic_history,
                    onClick = {
                        showOptionsSheet = false
                        showSleepDialog = true
                    }
                )

                val duplicateIds = remember(currentScopeTabs) {
                    val seen = mutableSetOf<String>()
                    val dupes = mutableListOf<String>()
                    currentScopeTabs.forEach { t ->
                        val u = t.content.url.trim()
                        if (u.isNotBlank() && u != "about:blank" && !seen.add(u)) {
                            dupes.add(t.id)
                        }
                    }
                    dupes
                }
                if (duplicateIds.isNotEmpty()) {
                    TabOptionRow(
                        title = "Close Duplicate Tabs (${duplicateIds.size})",
                        subtitle = "Keep one tab per unique URL",
                        iconRes = R.drawable.fx_ic_close_24,
                        onClick = {
                            showOptionsSheet = false
                            onCloseSelectedTabs(duplicateIds)
                        }
                    )
                }

                TabOptionRow(
                    title = "Share All Tab URLs",
                    subtitle = "Export open links from this section",
                    iconRes = R.drawable.uh_ic_share_24,
                    onClick = {
                        showOptionsSheet = false
                        onShareSelectedTabs(currentScopeTabs.map { it.id })
                    }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                TabOptionRow(
                    title = if (activeSection == TraySection.PRIVATE) {
                        stringResource(R.string.tabs_tray_close_all_private)
                    } else {
                        stringResource(R.string.tabs_tray_close_all)
                    },
                    subtitle = "Remove all open tabs in this section",
                    iconRes = R.drawable.fx_ic_delete_24,
                    isDestructive = true,
                    onClick = {
                        showOptionsSheet = false
                        onCloseAllTabs(activeSection == TraySection.PRIVATE)
                    }
                )

                Spacer(Modifier.height(12.dp))
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = surfaceBg
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                if (isMultiSelect && activeSection != TraySection.GROUPS) {
                    val scopeTabs = if (activeSection == TraySection.PRIVATE) privateTabs else normalTabs
                    MultiSelectHeader(
                        selectedCount = selectedTabIds.size,
                        totalCount = scopeTabs.size,
                        allowGrouping = activeSection == TraySection.NORMAL && selectedTabIds.isNotEmpty(),
                        onToggleSelectAll = {
                            selectedTabIds = if (selectedTabIds.size == scopeTabs.size) emptySet()
                            else scopeTabs.map { it.id }.toSet()
                        },
                        onGroupSelected = {
                            if (selectedTabIds.isNotEmpty()) {
                                pendingGroupCreateIds = selectedTabIds.toList()
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
                        activeSection = activeSection,
                        normalCount = normalTabs.size,
                        privateCount = privateTabs.size,
                        groupCount = groups.size,
                        sectionAccent = sectionAccent,
                        onSectionChange = {
                            activeSection = it
                            isMultiSelect = false
                            selectedTabIds = emptySet()
                        },
                        onDismiss = onDismiss
                    )
                }

                if (activeSection == TraySection.PRIVATE && privateTabs.isNotEmpty()) {
                    PrivateModeBanner(
                        accentColor = sectionAccent,
                        onCloseAllPrivate = { onCloseAllTabs(true) }
                    )
                }

                when (activeSection) {
                    TraySection.GROUPS -> {
                        if (groups.isEmpty()) {
                            EmptyGroupsView(
                                modifier = Modifier.weight(1f),
                                onCreateGroup = { showGroupCreatorPicker = true },
                                onGoToNormalTabs = { activeSection = TraySection.NORMAL }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(groups, key = { it.id }) { grp ->
                                    val members = grp.tabIds.mapNotNull { id -> normalTabs.firstOrNull { it.id == id } }
                                    GroupOverviewCard(
                                        group = grp,
                                        members = members,
                                        onTabClick = onSelectTab,
                                        onOpenInTabs = {
                                            if (grp.isCollapsed) onToggleGroupCollapsed(grp.id)
                                            activeSection = TraySection.NORMAL
                                        },
                                        onEdit = { editingGroup = grp },
                                        onUngroup = { onUngroup(grp.id) },
                                        onCloseGroup = { onCloseGroupTabs(grp.id, grp.tabIds) }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = { showGroupCreatorPicker = true },
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("New Group", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    else -> {
                        val activeTabsList = if (activeSection == TraySection.PRIVATE) privateTabs else normalTabs
                        if (activeTabsList.isEmpty()) {
                            EmptyTabsView(
                                isPrivate = activeSection == TraySection.PRIVATE,
                                accentColor = sectionAccent,
                                onNewTab = { onNewTab(activeSection == TraySection.PRIVATE) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(gridColumns),
                                state = lazyGridState,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                items(
                                    items = activeEntries,
                                    key = { it.key },
                                    span = { entry ->
                                        when (entry) {
                                            is TrayGridEntry.GroupHeader -> GridItemSpan(gridColumns)
                                            is TrayGridEntry.TabEntry -> GridItemSpan(1)
                                        }
                                    }
                                ) { entry ->
                                    when (entry) {
                                        is TrayGridEntry.GroupHeader -> {
                                            val isDropHovered = hoveredDropTargetKey == entry.key
                                            Box(
                                                modifier = Modifier.onGloballyPositioned { coords ->
                                                    itemBounds[entry.key] = coords.boundsInRoot()
                                                }
                                            ) {
                                                TabGroupStackHeader(
                                                    group = entry.group,
                                                    memberTabs = entry.memberTabs,
                                                    isDropHovered = isDropHovered,
                                                    onToggleCollapse = { onToggleGroupCollapsed(entry.group.id) },
                                                    onEdit = { editingGroup = entry.group },
                                                    onUngroup = { onUngroup(entry.group.id) },
                                                    onCloseGroup = { onCloseGroupTabs(entry.group.id, entry.group.tabIds) }
                                                )
                                            }
                                        }

                                        is TrayGridEntry.TabEntry -> {
                                            val tab = entry.tab
                                            val isSelected = tab.id == selectedTabId
                                            val isChecked = tab.id in selectedTabIds
                                            val isDraggingThis = draggingEntryKey == entry.key
                                            val isDropTarget = hoveredDropTargetKey == entry.key

                                            val itemModifier = Modifier
                                                .zIndex(if (isDraggingThis) 20f else 0f)
                                                .onGloballyPositioned { coords ->
                                                    if (!isDraggingThis) {
                                                        itemBounds[entry.key] = coords.boundsInRoot()
                                                    }
                                                }
                                                .graphicsLayer {
                                                    if (isDraggingThis) {
                                                        translationX = dragTranslation.x
                                                        translationY = dragTranslation.y
                                                        scaleX = 1.05f
                                                        scaleY = 1.05f
                                                    }
                                                }
                                                .then(
                                                    if (!isMultiSelect && activeSection == TraySection.NORMAL) {
                                                        Modifier.pointerInput(entry.key) {
                                                            detectDragGesturesAfterLongPress(
                                                                onDragStart = {
                                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                    draggingEntryKey = entry.key
                                                                    dragTranslation = Offset.Zero
                                                                    hoveredDropTargetKey = null
                                                                },
                                                                onDrag = { change, dragAmount ->
                                                                    change.consume()
                                                                    dragTranslation += dragAmount
                                                                    val myBounds = itemBounds[entry.key]
                                                                    if (myBounds != null) {
                                                                        val draggedCenter = myBounds.center + dragTranslation
                                                                        val hit = itemBounds.entries.firstOrNull { (k, rect) ->
                                                                            k != entry.key &&
                                                                                (k.startsWith("tab_") || k.startsWith("hdr_")) &&
                                                                                rect.inflate(16f).contains(draggedCenter)
                                                                        }
                                                                        hoveredDropTargetKey = hit?.key
                                                                    }
                                                                },
                                                                onDragEnd = {
                                                                    val targetKey = hoveredDropTargetKey
                                                                    draggingEntryKey = null
                                                                    dragTranslation = Offset.Zero
                                                                    hoveredDropTargetKey = null
                                                                    if (targetKey != null) {
                                                                        if (targetKey.startsWith("hdr_")) {
                                                                            onAddToGroup(targetKey.removePrefix("hdr_"), tab.id)
                                                                        } else if (targetKey.startsWith("tab_")) {
                                                                            val otherId = targetKey.removePrefix("tab_")
                                                                            if (otherId != tab.id) {
                                                                                val existingGroup = latestGroups.firstOrNull { otherId in it.tabIds }
                                                                                if (existingGroup != null) {
                                                                                    onAddToGroup(existingGroup.id, tab.id)
                                                                                } else {
                                                                                    pendingGroupCreateIds = listOf(otherId, tab.id)
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                },
                                                                onDragCancel = {
                                                                    draggingEntryKey = null
                                                                    dragTranslation = Offset.Zero
                                                                    hoveredDropTargetKey = null
                                                                }
                                                            )
                                                        }
                                                    } else Modifier
                                                )

                                            Box(modifier = itemModifier) {
                                                if (isGridLayout) {
                                                    TabCardContent(
                                                        tab = tab,
                                                        groupColor = entry.group?.colorArgb?.let { Color(it) },
                                                        accentColor = sectionAccent,
                                                        isSelected = isSelected,
                                                        isMultiSelect = isMultiSelect,
                                                        isChecked = isChecked,
                                                        isDropTarget = isDropTarget,
                                                        onClick = {
                                                            if (isMultiSelect) {
                                                                selectedTabIds = if (isChecked) selectedTabIds - tab.id
                                                                else selectedTabIds + tab.id
                                                            } else {
                                                                onSelectTab(tab.id)
                                                            }
                                                        },
                                                        onClose = { closeTabWithUndo(tab) }
                                                    )
                                                } else {
                                                    TabListRowContent(
                                                        tab = tab,
                                                        groupColor = entry.group?.colorArgb?.let { Color(it) },
                                                        accentColor = sectionAccent,
                                                        isSelected = isSelected,
                                                        isMultiSelect = isMultiSelect,
                                                        isChecked = isChecked,
                                                        isDropTarget = isDropTarget,
                                                        onClick = {
                                                            if (isMultiSelect) {
                                                                selectedTabIds = if (isChecked) selectedTabIds - tab.id
                                                                else selectedTabIds + tab.id
                                                            } else {
                                                                onSelectTab(tab.id)
                                                            }
                                                        },
                                                        onClose = { closeTabWithUndo(tab) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isMultiSelect && activeSection != TraySection.GROUPS) {
                    TabsTrayBottomBar(
                        isPrivateMode = activeSection == TraySection.PRIVATE,
                        hasTabs = if (activeSection == TraySection.PRIVATE) privateTabs.isNotEmpty() else normalTabs.isNotEmpty(),
                        accentColor = sectionAccent,
                        onOpenOptions = { showOptionsSheet = true },
                        onNewTab = { onNewTab(activeSection == TraySection.PRIVATE) }
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 68.dp, start = 16.dp, end = 16.dp)
            )
        }
    }
}

@Composable
private fun TabOptionRow(
    title: String,
    subtitle: String,
    iconRes: Int,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun CreateGroupTabPickerDialog(
    normalTabs: List<TabSessionState>,
    existingGroups: List<TabGroup>,
    onCreate: (name: String, colorArgb: Long, tabIds: List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var groupName by remember { mutableStateOf("Group ${existingGroups.size + 1}") }
    var selectedColor by remember { mutableStateOf(TabGroupStore.GROUP_COLORS[existingGroups.size % TabGroupStore.GROUP_COLORS.size]) }
    var chosenIds by remember { mutableStateOf(normalTabs.take(2).map { it.id }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Tab Group", color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TabGroupStore.GROUP_COLORS.forEach { argb ->
                        val picked = argb == selectedColor
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (picked) 2.5.dp else 0.dp,
                                    color = if (picked) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = argb }
                        )
                    }
                }
                Text("Select Tabs (${chosenIds.size})", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (normalTabs.isEmpty()) {
                    Text("No open normal tabs to group.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                } else {
                    normalTabs.forEach { tab ->
                        val isPicked = tab.id in chosenIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    chosenIds = if (isPicked) chosenIds - tab.id else chosenIds + tab.id
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isPicked,
                                onCheckedChange = {
                                    chosenIds = if (isPicked) chosenIds - tab.id else chosenIds + tab.id
                                }
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = tab.content.title.ifBlank { extractHostLabel(tab.content.url).ifBlank { "New Tab" } },
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosenIds.isNotEmpty(),
                onClick = { onCreate(groupName.trim().ifBlank { "Tab Group" }, selectedColor, chosenIds.toList()) }
            ) {
                Text("Create", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
private fun TabsTrayHeader(
    activeSection: TraySection,
    normalCount: Int,
    privateCount: Int,
    groupCount: Int,
    sectionAccent: Color,
    onSectionChange: (TraySection) -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabSegmentButton(
                title = stringResource(R.string.tabs_tray_normal),
                count = normalCount,
                isSelected = activeSection == TraySection.NORMAL,
                accentColor = MaterialTheme.colorScheme.primary,
                iconRes = R.drawable.uh_ic_tab_modern_24,
                onClick = { onSectionChange(TraySection.NORMAL) }
            )
            TabSegmentButton(
                title = stringResource(R.string.tabs_tray_private),
                count = privateCount,
                isSelected = activeSection == TraySection.PRIVATE,
                accentColor = sectionAccent,
                iconRes = R.drawable.uh_ic_private_vibe_24,
                onClick = { onSectionChange(TraySection.PRIVATE) }
            )
            TabSegmentButton(
                title = "Groups",
                count = groupCount,
                isSelected = activeSection == TraySection.GROUPS,
                accentColor = MaterialTheme.colorScheme.primary,
                iconRes = R.drawable.ic_grid_view,
                onClick = { onSectionChange(TraySection.GROUPS) }
            )
        }

        IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_close_24),
                contentDescription = "Close Tabs Tray",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TabSegmentButton(
    title: String,
    count: Int,
    isSelected: Boolean,
    accentColor: Color,
    iconRes: Int,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
    val contentColor = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = title,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = title,
            color = contentColor,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(contentColor.copy(alpha = 0.16f))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        ) {
            Text(
                text = "$count",
                color = contentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TabGroupStackHeader(
    group: TabGroup,
    memberTabs: List<TabSessionState>,
    isDropHovered: Boolean,
    onToggleCollapse: () -> Unit,
    onEdit: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit
) {
    val color = Color(group.colorArgb)
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isDropHovered) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                width = if (isDropHovered) 2.dp else 1.dp,
                color = if (isDropHovered) MaterialTheme.colorScheme.primary else color.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onToggleCollapse() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isDropHovered) "Drop to add to ${group.name}" else group.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (group.isCollapsed && !isDropHovered) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                memberTabs.take(3).forEach { m ->
                    val host = extractHostLabel(m.content.url)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(color.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = host.take(10),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        Text(
            text = "${memberTabs.size}",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(end = 6.dp)
        )

        Icon(
            painter = painterResource(id = R.drawable.ic_triangle_down),
            contentDescription = if (group.isCollapsed) "Expand" else "Collapse",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = if (group.isCollapsed) 180f else 0f }
        )

        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_more_24),
                    contentDescription = "Group options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                DropdownMenuItem(
                    text = { Text("Rename & Color") },
                    onClick = { showMenu = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text("Ungroup") },
                    onClick = { showMenu = false; onUngroup() }
                )
                DropdownMenuItem(
                    text = { Text("Close Group", color = MaterialTheme.colorScheme.error) },
                    onClick = { showMenu = false; onCloseGroup() }
                )
            }
        }
    }
}

@Composable
private fun GroupOverviewCard(
    group: TabGroup,
    members: List<TabSessionState>,
    onTabClick: (String) -> Unit,
    onOpenInTabs: () -> Unit,
    onEdit: () -> Unit,
    onUngroup: () -> Unit,
    onCloseGroup: () -> Unit
) {
    val accent = Color(group.colorArgb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable { onOpenInTabs() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = group.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${members.size} tabs",
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_edit_24),
                    contentDescription = "Edit Group",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onCloseGroup, modifier = Modifier.size(30.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.fx_ic_delete_24),
                    contentDescription = "Close Group",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (members.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                members.take(4).forEach { tab ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onTabClick(tab.id) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tab.content.title.ifBlank { extractHostLabel(tab.content.url).ifBlank { "New Tab" } },
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onUngroup, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Ungroup", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GroupNameColorDialog(
    title: String,
    initialName: String,
    initialColorArgb: Long,
    confirmLabel: String,
    onConfirm: (name: String, colorArgb: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedColor by remember { mutableStateOf(initialColorArgb) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Group Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TabGroupStore.GROUP_COLORS.forEach { argb ->
                        val isPicked = argb == selectedColor
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (isPicked) 2.5.dp else 0.dp,
                                    color = if (isPicked) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = argb }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifBlank { initialName }, selectedColor) }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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
private fun MultiSelectHeader(
    selectedCount: Int,
    totalCount: Int,
    allowGrouping: Boolean,
    onToggleSelectAll: () -> Unit,
    onGroupSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$selectedCount selected",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onToggleSelectAll,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (selectedCount == totalCount && totalCount > 0) "Deselect" else "Select All",
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = onDone,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tabs_tray_done),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        if (selectedCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (allowGrouping) {
                    TextButton(
                        onClick = onGroupSelected,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Group ($selectedCount)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                TextButton(
                    onClick = onShareSelected,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.uh_ic_share_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Share", fontSize = 13.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurface)
                }
                TextButton(
                    onClick = onDeleteSelected,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_delete_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Close ($selectedCount)", fontSize = 13.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PrivateModeBanner(
    accentColor: Color,
    onCloseAllPrivate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_private_vibe_24),
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Private browsing — cleared when closed",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(
            onClick = onCloseAllPrivate,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "Close all",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun TabListRowContent(
    tab: TabSessionState,
    groupColor: Color?,
    accentColor: Color,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    isChecked: Boolean,
    isDropTarget: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val borderColor = when {
        isDropTarget -> accentColor
        isSelected -> accentColor
        isChecked -> accentColor
        groupColor != null -> groupColor.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    }
    val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 6.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(if (isDropTarget || isSelected || isChecked) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (groupColor != null) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(groupColor))
                Spacer(Modifier.width(8.dp))
            }
            if (tab.content.icon != null) {
                Image(
                    bitmap = tab.content.icon!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_globe),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isDropTarget) "Drop to group" else tab.content.title.ifBlank { host.ifBlank { "New Tab" } },
                    color = if (isDropTarget) accentColor else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (isDropTarget) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = host.ifBlank { "New Tab" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isMultiSelect) {
                Checkbox(checked = isChecked, onCheckedChange = { onClick() })
            } else {
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.fx_ic_close_24),
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TabCardContent(
    tab: TabSessionState,
    groupColor: Color?,
    accentColor: Color,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    isChecked: Boolean,
    isDropTarget: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val borderColor = when {
        isDropTarget -> accentColor
        isSelected -> accentColor
        isChecked -> accentColor
        groupColor != null -> groupColor.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    }
    val borderWidth = if (isDropTarget || isSelected || isChecked) 2.dp else 1.dp

    val host = remember(tab.content.url) { extractHostLabel(tab.content.url) }
    val previewBitmap = remember(tab.id) { TabPreviewCache.get(tab.id) }

    Surface(
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 6.dp else 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (groupColor != null) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(groupColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                if (tab.content.icon != null) {
                    Image(
                        bitmap = tab.content.icon!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_globe),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = if (isDropTarget) "Drop to group" else tab.content.title.ifBlank { host.ifBlank { "New Tab" } },
                    color = if (isDropTarget) accentColor else MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = if (isDropTarget) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (isMultiSelect) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.size(22.dp)
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f)
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
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (host.firstOrNull() ?: 'U').uppercase(),
                            color = accentColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Text(
                text = host.ifBlank { "New Tab" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun EmptyTabsView(
    isPrivate: Boolean,
    accentColor: Color,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(
                id = if (isPrivate) R.drawable.uh_ic_private_vibe_24 else R.drawable.uh_ic_tab_modern_24
            ),
            contentDescription = null,
            tint = accentColor.copy(alpha = 0.7f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = if (isPrivate) "No private tabs open" else "No open tabs",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (isPrivate) {
                "Private tabs leave no history or site data after you close them."
            } else {
                "Open a fresh tab to start browsing."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onNewTab,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_plus_24),
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open New Tab", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyGroupsView(
    modifier: Modifier = Modifier,
    onCreateGroup: () -> Unit,
    onGoToNormalTabs: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_grid_view),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No Tab Groups yet",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Long-press a tab and drop it onto another tab, or create a group here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onCreateGroup, shape = RoundedCornerShape(20.dp)) {
                Text("Create Group")
            }
            TextButton(onClick = onGoToNormalTabs) {
                Text("View Tabs")
            }
        }
    }
}

@Composable
private fun TabsTrayBottomBar(
    isPrivateMode: Boolean,
    hasTabs: Boolean,
    accentColor: Color,
    onOpenOptions: () -> Unit,
    onNewTab: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenOptions,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.fx_ic_more_24),
                contentDescription = "Tabs Options",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }

        Button(
            onClick = onNewTab,
            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.uh_ic_plus_24),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isPrivateMode) "New Private Tab" else stringResource(R.string.tabs_tray_new_tab),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

private fun extractHostLabel(rawUrl: String): String {
    if (rawUrl.isBlank() || rawUrl == "about:blank") return ""
    return try {
        URI(rawUrl).host?.removePrefix("www.") ?: rawUrl
    } catch (_: Exception) {
        rawUrl
    }
}
