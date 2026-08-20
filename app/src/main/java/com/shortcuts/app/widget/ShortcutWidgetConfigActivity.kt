package com.shortcuts.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.google.gson.Gson
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import com.shortcuts.app.data.ThemePreferences
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigDao
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.R
import com.shortcuts.app.ui.MainActivity
import com.shortcuts.app.ui.theme.LocalShortcutsPalette
import com.shortcuts.app.ui.theme.ShortcutsTheme
import com.shortcuts.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext

/** Configuration entry point for the one adaptive widget offered to new users. */
class ShortcutWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val database = AppDatabase.getDatabase(this)
        val selectionStore = ShortcutWidgetSelectionStore(
            automationDao = database.automationDao(),
            widgetConfigDao = database.widgetConfigDao()
        )
        val themePreferences = ThemePreferences(applicationContext)
        setContent {
            val themeMode by themePreferences.themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            val shortcuts by produceState<List<Automation>?>(null, selectionStore) {
                value = withContext(Dispatchers.IO) { selectionStore.loadShortcuts() }
            }
            ShortcutsTheme(mode = themeMode) {
                ShortcutWidgetConfigScreen(
                    shortcuts = shortcuts,
                    onBack = ::finish,
                    onCreateShortcut = ::openManualBuilder,
                    onPlaceWidget = { selected -> saveConfig(selectionStore, selected) }
                )
            }
        }
    }

    private fun saveConfig(selectionStore: ShortcutWidgetSelectionStore, selected: List<Automation>) {
        lifecycleScope.launch {
            saveShortcutWidgetConfig(
                selectionStore = selectionStore,
                widgetId = appWidgetId,
                shortcuts = selected,
                refreshWidget = { widgetId ->
                    refreshShortcutWidget(this@ShortcutWidgetConfigActivity, widgetId)
                },
                reportSuccess = {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                    finish()
                }
            )
        }
    }

    private fun openManualBuilder() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_START_DESTINATION, "manual_builder")
        )
        finish()
    }
}

@Composable
private fun ShortcutWidgetConfigScreen(
    shortcuts: List<Automation>?,
    onBack: () -> Unit,
    onCreateShortcut: () -> Unit,
    onPlaceWidget: (List<Automation>) -> Unit
) {
    val palette = LocalShortcutsPalette.current
    var selectedIds by remember(shortcuts) {
        mutableStateOf(shortcuts.orEmpty().take(MAX_SHORTCUTS_PER_WIDGET).map { it.id }.toSet())
    }
    val selectedShortcuts = shortcuts.orEmpty().filter { it.id in selectedIds }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.ground)
            .safeDrawingPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "Back", tint = palette.ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.shortcut_widget_picker_title), style = MaterialTheme.typography.titleLarge, color = palette.ink)
        }
        Text(
            stringResource(R.string.shortcut_widget_picker_explanation),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkFaint
        )
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when {
                shortcuts == null -> {
                    Text(
                        "Loading your shortcuts…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkMuted
                    )
                }
                shortcuts.isEmpty() -> WidgetPickerEmptyState(onCreateShortcut)
                else -> {
                    if (selectedShortcuts.isEmpty()) {
                        Text(
                            "Select at least one shortcut to preview your widget.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkMuted
                        )
                    } else {
                        WidgetPreviewTopRow(selectedShortcuts)
                        ScrollingListPreview(selectedShortcuts)
                    }
                    ShortcutSelectionRow(
                        shortcuts = shortcuts,
                        selectedIds = selectedIds,
                        onToggle = { shortcut ->
                            selectedIds = when {
                                shortcut.id in selectedIds -> selectedIds - shortcut.id
                                selectedIds.size < MAX_SHORTCUTS_PER_WIDGET -> selectedIds + shortcut.id
                                else -> selectedIds
                            }
                        }
                    )
                    WidgetReassuranceNote()
                }
            }
        }
        Button(
            onClick = { onPlaceWidget(selectedShortcuts) },
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            shape = RoundedCornerShape(26.dp),
            enabled = selectedShortcuts.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = palette.ink, contentColor = palette.ground)
        ) {
            Text(stringResource(R.string.shortcut_widget_place), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WidgetPreviewTopRow(shortcuts: List<Automation>) {
    val palette = LocalShortcutsPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.width(108.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(108.dp).clip(RoundedCornerShape(26.dp)).background(shortcuts.firstOrNull()?.tileColor() ?: palette.surfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.WbSunny, null, tint = palette.tileContent, modifier = Modifier.size(28.dp))
                    Text(
                        shortcuts.firstOrNull()?.name.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = palette.tileContent,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PreviewLabel("Single tile")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth().height(108.dp).clip(RoundedCornerShape(26.dp)).background(palette.surface)
                    .border(1.dp, palette.outline, RoundedCornerShape(26.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shortcuts.take(4).filterIndexed { index, _ -> index % 2 == 0 }.forEach { shortcut ->
                        PreviewGridTile(shortcut)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shortcuts.take(4).filterIndexed { index, _ -> index % 2 == 1 }.forEach { shortcut ->
                        PreviewGridTile(shortcut)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PreviewLabel("Grid of four")
        }
    }
}

@Composable
private fun ColumnScope.PreviewGridTile(shortcut: Automation) {
    val palette = LocalShortcutsPalette.current
    Row(
        modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(shortcut.tileColor()).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(Icons.Filled.WbSunny, null, tint = palette.tileContent, modifier = Modifier.size(15.dp))
        Text(shortcut.name, style = MaterialTheme.typography.labelSmall, color = palette.tileContent, maxLines = 1)
    }
}

@Composable
private fun ScrollingListPreview(shortcuts: List<Automation>) {
    val palette = LocalShortcutsPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(palette.surface)
                .border(1.dp, palette.outline, RoundedCornerShape(26.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            shortcuts.take(3).forEachIndexed { index, shortcut ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.surfaceMuted))
                PreviewListRow(shortcut)
            }
        }
        Spacer(Modifier.height(8.dp))
        PreviewLabel("Scrolling list")
    }
}

@Composable
private fun PreviewListRow(shortcut: Automation) {
    val palette = LocalShortcutsPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(shortcut.tileColor()), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.WbSunny, null, tint = palette.tileContent, modifier = Modifier.size(17.dp))
        }
        Text(shortcut.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = palette.ink)
        Text(shortcut.stepLabel(), style = MaterialTheme.typography.labelMedium, color = palette.inkFaint)
    }
}

@Composable
private fun ShortcutSelectionRow(
    shortcuts: List<Automation>,
    selectedIds: Set<Int>,
    onToggle: (Automation) -> Unit
) {
    val palette = LocalShortcutsPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose up to $MAX_SHORTCUTS_PER_WIDGET shortcuts", style = MaterialTheme.typography.labelLarge, color = palette.inkMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shortcuts, key = { it.id }) { shortcut ->
                val selected = shortcut.id in selectedIds
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) shortcut.tileColor() else palette.surface)
                        .then(if (selected) Modifier else Modifier.border(1.dp, palette.outline, RoundedCornerShape(18.dp)))
                        .clickable { onToggle(shortcut) }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        shortcut.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) palette.tileContent else palette.inkMuted,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetPickerEmptyState(onCreateShortcut: () -> Unit) {
    val palette = LocalShortcutsPalette.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(palette.surface)
            .border(1.dp, palette.outline, RoundedCornerShape(26.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("No shortcuts yet", style = MaterialTheme.typography.titleMedium, color = palette.ink)
        Text(
            "Create a shortcut before adding a widget. Your widget will only show shortcuts you save.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkMuted,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onCreateShortcut,
            colors = ButtonDefaults.buttonColors(containerColor = palette.ink, contentColor = palette.ground)
        ) { Text("Create a shortcut") }
    }
}

@Composable
private fun PreviewLabel(label: String) {
    val palette = LocalShortcutsPalette.current
    Text(label, style = MaterialTheme.typography.labelMedium, color = palette.inkMuted, textAlign = TextAlign.Center)
}

private const val MAX_SHORTCUTS_PER_WIDGET = 6

private fun Automation.tileColor(): androidx.compose.ui.graphics.Color = resolveWidgetColor(colorKey)

private fun Automation.stepLabel(): String {
    val count = runCatching { com.shortcuts.app.data.ActionConverter().toActionList(actionsJson).size }.getOrDefault(0)
    return if (count == 1) "1 step" else "$count steps"
}

/** Coordinates the real shortcut query and unified widget binding without involving Android UI. */
internal class ShortcutWidgetSelectionStore(
    private val automationDao: AutomationDao,
    private val widgetConfigDao: WidgetConfigDao,
    private val gson: Gson = Gson()
) {
    suspend fun loadShortcuts(): List<Automation> = automationDao.getAllAutomations().first()

    suspend fun saveSelection(widgetId: Int, shortcuts: List<Automation>) {
        widgetConfigDao.upsertConfig(unifiedWidgetConfig(widgetId, shortcuts.map { it.id }, gson))
    }
}

/** Builds the single source-of-truth Room row shared by picker and pin-success flows. */
internal fun unifiedWidgetConfig(
    widgetId: Int,
    automationIds: List<Int>,
    gson: Gson = Gson()
): WidgetConfig {
    require(automationIds.isNotEmpty()) { "A widget needs at least one shortcut" }
    return WidgetConfig(
        widgetId = widgetId,
        sourceType = WidgetConfigSource.UNIFIED.name,
        automationIdsJson = gson.toJson(automationIds.take(MAX_SHORTCUTS_PER_WIDGET))
    )
}

/**
 * Keeps the Activity result lifecycle explicit: Room persistence and the first render must
 * finish before the configuration Activity reports success to the launcher.
 */
internal suspend fun saveShortcutWidgetConfig(
    selectionStore: ShortcutWidgetSelectionStore,
    widgetId: Int,
    shortcuts: List<Automation>,
    refreshWidget: suspend (Int) -> Unit,
    reportSuccess: () -> Unit
) {
    withContext(Dispatchers.IO) {
        selectionStore.saveSelection(widgetId, shortcuts)
    }
    refreshWidget(widgetId)
    reportSuccess()
}

@Composable
private fun WidgetReassuranceNote() {
    val palette = LocalShortcutsPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surfaceMuted).padding(12.dp, 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Filled.Info, null, tint = palette.inkMuted, modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Text(
            "Widgets you already placed keep working. They'll pick up the new look automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkMuted
        )
    }
}
