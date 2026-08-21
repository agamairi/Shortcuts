package com.shortcuts.app.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.Automation
import com.shortcuts.app.service.ActionExecutorService
import com.shortcuts.app.util.ActionDescriber
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.ui.theme.LocalShortcutsPalette
import com.shortcuts.app.util.AutomationVisuals
import com.shortcuts.app.viewmodel.AutomationViewModel
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetConfigParser
import com.shortcuts.app.widget.WidgetIconKey
import com.shortcuts.app.widget.resolveWidgetColor
import com.shortcuts.app.widget.resolveWidgetIconKey
import com.shortcuts.app.widget.ShortcutWidgetPinRequest
import com.shortcuts.app.widget.ShortcutWidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DashboardFilter(val label: String) {
    ALL("All"),
    HOME("On homescreen"),
    RECENT("Recent")
}

@Composable
fun DashboardScreen(
    viewModel: AutomationViewModel,
    onNavigateToManualBuilder: () -> Unit,
    onNavigateToEditShortcut: (Int) -> Unit,
    onNavigateToAiBuilder: () -> Unit,
    onNavigateToRecorder: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val pendingDeletion by viewModel.pendingDeletion.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DashboardScreenContent(
        uiState = uiState,
        onNavigateToManualBuilder = onNavigateToManualBuilder,
        onNavigateToAiBuilder = onNavigateToAiBuilder,
        onNavigateToRecorder = onNavigateToRecorder,
        onNavigateToSettings = onNavigateToSettings,
        onRun = { automation ->
            scope.launch(Dispatchers.IO) {
                val actions = runCatching { ActionConverter().toActionList(automation.actionsJson) }
                    .getOrDefault(emptyList())
                val result = runCatching {
                    ActionExecutorService(context).executeActions(actions, automation.name)
                }
                // Report exactly what Test Run reports. Running from the dashboard used to say only
                // "Couldn't run 'X'", so the same failure was diagnosable in the builder and opaque
                // here — which is backwards, since this is where shortcuts are actually used.
                val message = result.getOrNull()
                    ?.userSummary { index -> actions.getOrNull(index)?.let(ActionDescriber::describe) }
                    ?: "Couldn't run \"${automation.name}\""
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        },
        onDelete = viewModel::requestDelete,
        onAddToHomeScreen = { shortcut -> requestShortcutWidgetPin(context, shortcut) },
        onEdit = { shortcut -> onNavigateToEditShortcut(shortcut.id) }
    )
    pendingDeletion?.let { pending ->
        DeleteShortcutConfirmationDialog(
            pending = pending,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete
        )
    }
}

/**
 * Pixel-faithful dashboard content. Widget bindings are read here only to derive the filter and
 * count; shortcut mutation remains owned by [AutomationViewModel].
 */
@Composable
fun DashboardScreenContent(
    uiState: UiState<List<Automation>>,
    errorState: String? = null,
    onNavigateToManualBuilder: () -> Unit,
    onNavigateToAiBuilder: () -> Unit,
    onNavigateToRecorder: () -> Unit = {},
    onNavigateToCreateWidget: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onPinListWidget: () -> Unit = {},
    onPinGridWidget: () -> Unit = {},
    onPinGreetingWidget: () -> Unit = {},
    onPinCustomWidget: () -> Unit = {},
    onToggleActive: (Automation) -> Unit = {},
    onDelete: (Automation) -> Unit = {},
    onRun: (Automation) -> Unit = {},
    onUpdateAppearance: (Automation, WidgetColorKey, WidgetIconKey) -> Unit = { _, _, _ -> },
    onClearError: () -> Unit = {},
    onAddToHomeScreen: (Automation) -> Unit = {},
    onEdit: (Automation) -> Unit = {}
) {
    val context = LocalContext.current
    val palette = LocalShortcutsPalette.current
    var filter by remember { mutableStateOf(DashboardFilter.ALL) }
    var isGridView by remember { mutableStateOf(true) }
    var homescreenIds by remember { mutableStateOf(emptySet<Int>()) }

    // Observed, not read once: pinning a widget changes widget_configs without changing the
    // shortcut list, so a LaunchedEffect keyed on uiState never re-ran and the count stayed at
    // its old value — which is why the header read "0 on homescreen" after a successful add.
    LaunchedEffect(context) {
        AppDatabase.getDatabase(context).widgetConfigDao()
            .observeAllConfigs()
            .flowOn(Dispatchers.IO)
            .catch { homescreenIds = emptySet() }
            .collect { configs ->
                homescreenIds = configs
                    .flatMap { WidgetConfigParser.automationIds(it.automationIdsJson) }
                    .toSet()
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.ground)
            .safeDrawingPadding()
    ) {
        DashboardHeader(
            shortcutCount = (uiState as? UiState.Success)?.data?.size ?: 0,
            homescreenCount = homescreenIds.size,
            isGridView = isGridView,
            onToggleView = { isGridView = !isGridView },
            onNavigateToSettings = onNavigateToSettings
        )
        DashboardFilters(selected = filter, onSelected = { filter = it })

        when (uiState) {
            UiState.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = palette.ink)
                    Spacer(Modifier.height(12.dp))
                    Text("Loading shortcuts...", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
                }
            }

            is UiState.Error -> Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = errorState ?: uiState.message,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = palette.danger
                )
            }

            is UiState.Success -> {
                val visible = when (filter) {
                    DashboardFilter.ALL -> uiState.data
                    DashboardFilter.HOME -> uiState.data.filter { it.id in homescreenIds }
                    DashboardFilter.RECENT -> uiState.data.asReversed()
                }
                if (isGridView) {
                    DashboardGrid(
                        automations = visible,
                        onRun = onRun,
                        onDelete = onDelete,
                        onAddToHomeScreen = onAddToHomeScreen,
                        onEdit = onEdit,
                        onNewShortcut = onNavigateToManualBuilder,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    DashboardList(
                        automations = visible,
                        onRun = onRun,
                        onDelete = onDelete,
                        onAddToHomeScreen = onAddToHomeScreen,
                        onEdit = onEdit,
                        onNewShortcut = onNavigateToManualBuilder,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        DashboardBottomBar(onDescribe = onNavigateToAiBuilder, onCreate = onNavigateToManualBuilder
        , onRecord = onNavigateToRecorder)
    }
}

@Composable
private fun DashboardHeader(
    shortcutCount: Int,
    homescreenCount: Int,
    isGridView: Boolean,
    onToggleView: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val palette = LocalShortcutsPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Shortcuts", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, color = palette.ink)
            Spacer(Modifier.height(2.dp))
            Text(
                "$shortcutCount shortcuts · $homescreenCount on homescreen",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = palette.inkFaint
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onToggleView, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = if (isGridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (isGridView) "Switch to List View" else "Switch to Grid View",
                    tint = palette.ink,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Settings, "Settings", tint = palette.ink, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun DashboardFilters(selected: DashboardFilter, onSelected: (DashboardFilter) -> Unit) {
    val palette = LocalShortcutsPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DashboardFilter.entries.forEach { filter ->
            val selectedFilter = filter == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selectedFilter) palette.ink else palette.surface)
                    .then(if (selectedFilter) Modifier else Modifier.border(1.dp, palette.outline, RoundedCornerShape(18.dp)))
                    .clickable { onSelected(filter) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    filter.label,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = if (selectedFilter) palette.ground else palette.inkMuted
                )
            }
        }
    }
}

@Composable
private fun DashboardGrid(
    automations: List<Automation>,
    onRun: (Automation) -> Unit,
    onDelete: (Automation) -> Unit,
    onAddToHomeScreen: (Automation) -> Unit,
    onEdit: (Automation) -> Unit,
    onNewShortcut: () -> Unit,
    modifier: Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(automations, key = { it.id }) { shortcut ->
            DashboardTile(shortcut, onRun, onDelete, onAddToHomeScreen, onEdit)
        }
        item { NewShortcutTile(onNewShortcut) }
    }
}

@Composable
private fun DashboardList(
    automations: List<Automation>,
    onRun: (Automation) -> Unit,
    onDelete: (Automation) -> Unit,
    onAddToHomeScreen: (Automation) -> Unit,
    onEdit: (Automation) -> Unit,
    onNewShortcut: () -> Unit,
    modifier: Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(automations, key = { it.id }) { shortcut ->
            ShortcutListRow(shortcut, onRun, onDelete, onAddToHomeScreen, onEdit)
        }
        item { NewShortcutTile(onNewShortcut) }
    }
}

@Composable
private fun DashboardTile(
    shortcut: Automation,
    onRun: (Automation) -> Unit,
    onDelete: (Automation) -> Unit,
    onAddToHomeScreen: (Automation) -> Unit,
    onEdit: (Automation) -> Unit
) {
    val palette = LocalShortcutsPalette.current
    val color = tileColor(shortcut.colorKey, shortcut.id)
    val icon = tileIcon(shortcut.iconKey, shortcut.id)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(color)
            .clickable { onRun(shortcut) }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = palette.tileContent, modifier = Modifier.size(26.dp))
            ShortcutOverflowMenu(
                shortcut = shortcut,
                handleTint = palette.tileContent,
                onRun = onRun,
                onAddToHomeScreen = onAddToHomeScreen,
                onDelete = onDelete,
                onEdit = onEdit
            )
        }
        Column {
            Text(
                shortcut.name,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = palette.tileContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                pluralSteps(shortcut),
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = palette.tileContent.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun ShortcutListRow(
    shortcut: Automation,
    onRun: (Automation) -> Unit,
    onDelete: (Automation) -> Unit,
    onAddToHomeScreen: (Automation) -> Unit,
    onEdit: (Automation) -> Unit
) {
    val palette = LocalShortcutsPalette.current
    val color = tileColor(shortcut.colorKey, shortcut.id)
    Row(
        modifier = Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(24.dp)).background(palette.surface)
            .border(1.dp, palette.outline, RoundedCornerShape(24.dp)).clickable { onRun(shortcut) }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(tileIcon(shortcut.iconKey, shortcut.id), null, tint = palette.tileContent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(shortcut.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = palette.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pluralSteps(shortcut), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = palette.inkFaint)
        }
        ShortcutOverflowMenu(
            shortcut = shortcut,
            handleTint = palette.ink,
            onRun = onRun,
            onAddToHomeScreen = onAddToHomeScreen,
            onDelete = onDelete,
            onEdit = onEdit
        )
    }
}

@Composable
private fun ShortcutOverflowMenu(
    shortcut: Automation,
    handleTint: Color,
    onRun: (Automation) -> Unit,
    onAddToHomeScreen: (Automation) -> Unit,
    onDelete: (Automation) -> Unit,
    onEdit: (Automation) -> Unit
) {
    val palette = LocalShortcutsPalette.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(handleTint.copy(alpha = 0.28f))
                .clickable { expanded = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "Shortcut actions for ${shortcut.name}",
                tint = if (handleTint == palette.ink) palette.ink else palette.tileContent,
                modifier = Modifier.size(16.dp)
            )
        }
        // DropdownMenu gained `containerColor` after compose-bom 2023.10.01, which this
        // project pins. It is unnecessary here: the theme already maps Material's `surface`
        // slot onto palette.surface, so the menu picks up the right ground on its own.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Run", color = palette.ink) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, null, tint = palette.ink) },
                onClick = { expanded = false; onRun(shortcut) }
            )
            DropdownMenuItem(
                text = { Text("Edit", color = palette.ink) },
                leadingIcon = { Icon(Icons.Filled.Edit, null, tint = palette.ink) },
                onClick = { expanded = false; onEdit(shortcut) }
            )
            DropdownMenuItem(
                text = { Text("Add to homescreen", color = palette.ink) },
                onClick = { expanded = false; onAddToHomeScreen(shortcut) }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = palette.danger) },
                onClick = { expanded = false; onDelete(shortcut) }
            )
        }
    }
}

@Composable
private fun DeleteShortcutConfirmationDialog(
    pending: com.shortcuts.app.viewmodel.PendingDeletion,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val palette = LocalShortcutsPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(com.shortcuts.app.R.string.delete_shortcut_dialog_title, pending.automation.name),
                color = palette.ink
            )
        },
        text = {
            val textRes = if (pending.affectedWidgetCount == 1) {
                com.shortcuts.app.R.string.delete_shortcut_dialog_body_one
            } else {
                com.shortcuts.app.R.string.delete_shortcut_dialog_body_other
            }
            val text = if (pending.affectedWidgetCount == 1) {
                stringResource(textRes)
            } else {
                stringResource(textRes, pending.affectedWidgetCount)
            }
            Text(text, color = palette.inkMuted)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(com.shortcuts.app.R.string.delete_shortcut_dialog_confirm), color = palette.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.shortcuts.app.R.string.delete_shortcut_dialog_cancel), color = palette.ink)
            }
        },
        containerColor = palette.surface
    )
}

private fun requestShortcutWidgetPin(context: android.content.Context, shortcut: Automation) {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    if (appWidgetManager.isRequestPinAppWidgetSupported) {
        appWidgetManager.requestPinAppWidget(
            ComponentName(context, ShortcutWidgetReceiver::class.java),
            null,
            ShortcutWidgetPinRequest.createSuccessCallback(context, shortcut.id)
        )
    } else {
        Toast.makeText(context, "Your launcher doesn't support adding widgets from Shortcuts.", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun NewShortcutTile(onClick: () -> Unit) {
    val palette = LocalShortcutsPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(24.dp))
            .background(palette.surface).dashedRoundedBorder(1.5.dp, palette.outlineDashed, 24.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Add, null, tint = palette.inkFaint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text("New shortcut", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = palette.inkFaint)
    }
}

@Composable
private fun DashboardBottomBar(onDescribe: () -> Unit, onCreate: () -> Unit, onRecord: () -> Unit) {
    val palette = LocalShortcutsPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(palette.ink).clickable(onClick = onDescribe),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AutoAwesome, null, tint = palette.ground, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Describe a shortcut", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = palette.ground)
        }
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(palette.surface).border(1.dp, palette.outline, CircleShape).clickable(onClick = onRecord),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.RadioButtonChecked, "Record a Shortcut", tint = palette.danger, modifier = Modifier.size(20.dp)) }
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(palette.surface).border(1.dp, palette.outline, CircleShape).clickable(onClick = onCreate),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Add, "Add Manual Shortcut", tint = palette.ink, modifier = Modifier.size(20.dp)) }
    }
}

private fun pluralSteps(shortcut: Automation): String {
    val count = runCatching { ActionConverter().toActionList(shortcut.actionsJson).size }.getOrDefault(0)
    return if (count == 1) "1 step" else "$count steps"
}

private fun tileColor(colorKey: String?, id: Int): Color =
    resolveWidgetColor(colorKey, AutomationVisuals.colorForAutomation(id))

private fun tileIcon(iconKey: String?, id: Int) =
    resolveWidgetIconKey(iconKey, AutomationVisuals.iconForAutomation(id)).composeIcon

private fun Modifier.dashedRoundedBorder(width: androidx.compose.ui.unit.Dp, color: Color, radius: androidx.compose.ui.unit.Dp): Modifier =
    drawBehind {
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
            style = Stroke(
                width = width.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            )
        )
    }
