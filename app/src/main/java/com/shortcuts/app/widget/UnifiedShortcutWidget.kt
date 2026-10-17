package com.shortcuts.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.CustomWidgetTemplate
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.data.WidgetLayoutKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Parses legacy JSON defensively, so a corrupt old binding becomes setup rather than a blank UI. */
object WidgetConfigParser {
    fun automationIds(json: String?): List<Int> = try {
        if (json.isNullOrBlank()) emptyList() else Gson().fromJson<List<Int>>(
            json,
            object : TypeToken<List<Int>>() {}.type
        ).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}

internal data class ShortcutTileModel(
    val automation: Automation,
    val label: String,
    val colorKey: String?,
    val iconKey: String?
) {
    val appearance: WidgetAppearance
        get() = WidgetAppearance.fromKeys(
            colorKey = automation.colorKey ?: colorKey,
            iconKey = automation.iconKey ?: iconKey
        )
}

internal data class WidgetSnapshot(
    val shortcuts: List<ShortcutTileModel>,
    val layout: WidgetLayoutKey
)

internal fun resolveWidgetSnapshot(
    config: WidgetConfig?,
    automations: List<Automation>,
    templates: List<CustomWidgetTemplate>
): WidgetSnapshot {
    if (config == null) {
        return WidgetSnapshot(emptyList(), WidgetLayoutKey.AUTO)
    }
    val automationsById = automations.associateBy { it.id }
    val templatesById = templates.associateBy { it.id }

    val shortcuts = if (config.sourceType == WidgetConfigSource.GRID.name) {
        WidgetConfigParser.automationIds(config.templateIdsJson).mapNotNull { templateId ->
            val template = templatesById[templateId] ?: return@mapNotNull null
            val automation = automationsById[template.automationId] ?: return@mapNotNull null
            ShortcutTileModel(automation, template.label, template.colorKey, template.iconKey)
        }
    } else {
        val template = config.templateId?.let { templatesById[it] }
        val ids = WidgetConfigParser.automationIds(config.automationIdsJson)
        val orderedAutomations = ids.mapNotNull { automationsById[it] }
        val isSingle = orderedAutomations.size == 1
        orderedAutomations.map { automation ->
            val label = if (isSingle) {
                config.label?.takeIf { it.isNotBlank() }
                    ?: template?.label?.takeIf { it.isNotBlank() }
                    ?: automation.name
            } else {
                automation.name
            }
            ShortcutTileModel(
                automation = automation,
                label = label,
                colorKey = config.colorKey ?: template?.colorKey,
                iconKey = config.iconKey ?: template?.iconKey
            )
        }
    }

    val layout = WidgetLayoutKey.fromKeyOrAuto(config.layoutKey)
    return WidgetSnapshot(shortcuts, layout)
}

internal fun observeWidgetSnapshot(
    db: AppDatabase,
    appWidgetId: Int,
    sourceType: WidgetConfigSource
): Flow<WidgetSnapshot> = combine(
    db.widgetConfigDao().observeAllConfigs(),
    db.automationDao().getAllAutomations(),
    db.customWidgetTemplateDao().getAll()
) { configs, automations, templates ->
    val config = configs.firstOrNull { it.widgetId == appWidgetId && it.sourceType == sourceType.name }
        ?: if (sourceType != WidgetConfigSource.UNIFIED) {
            configs.firstOrNull { it.widgetId == appWidgetId && it.sourceType == WidgetConfigSource.UNIFIED.name }
        } else {
            null
        }
    resolveWidgetSnapshot(config, automations, templates)
}.distinctUntilChanged().flowOn(Dispatchers.IO)

/** Shared Glance implementation used by the new provider and all legacy-provider shims. */
open class UnifiedShortcutWidget(
    private val sourceType: WidgetConfigSource,
    private val configActivity: Class<out Activity>
) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)

        val configIntent = Intent(context, configActivity).putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            appWidgetId
        )

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            provideContent {
                GlanceTheme {
                    NeedsSetup(configIntent)
                }
            }
        }

        val db = AppDatabase.getDatabase(context)
        val initialSnapshot = runCatching {
            withContext(Dispatchers.IO) {
                val configs = db.widgetConfigDao().getAllConfigs()
                val config = configs.firstOrNull { it.widgetId == appWidgetId && it.sourceType == sourceType.name }
                    ?: if (sourceType != WidgetConfigSource.UNIFIED) {
                        configs.firstOrNull { it.widgetId == appWidgetId && it.sourceType == WidgetConfigSource.UNIFIED.name }
                    } else {
                        null
                    }
                val automations = db.automationDao().getAllAutomations().first()
                val templates = db.customWidgetTemplateDao().getAll().first()
                resolveWidgetSnapshot(config, automations, templates)
            }
        }.getOrDefault(WidgetSnapshot(emptyList(), WidgetLayoutKey.AUTO))

        provideContent {
            val flow = remember(appWidgetId) {
                observeWidgetSnapshot(db, appWidgetId, sourceType)
            }
            val snapshot by flow.collectAsState(initial = initialSnapshot)

            val prefs = currentState<Preferences>()
            // Reading this preference registers a recomposition dependency when updateAppWidgetState is called
            prefs[PREF_KEY_LAST_UPDATE]

            GlanceTheme {
                AdaptiveShortcutWidgetContent(
                    shortcuts = snapshot.shortcuts,
                    configIntent = configIntent,
                    layout = snapshot.layout
                )
            }
        }
    }
}

@Composable
private fun AdaptiveShortcutWidgetContent(
    shortcuts: List<ShortcutTileModel>,
    configIntent: Intent,
    layout: WidgetLayoutKey = WidgetLayoutKey.AUTO
) {
    val size = LocalSize.current
    if (shortcuts.isEmpty()) {
        NeedsSetup(configIntent)
        return
    }
    // An explicit choice wins over the size heuristic. AUTO keeps the original behaviour, so a
    // widget placed before layouts were selectable still adapts as the user resizes it.
    when (layout) {
        WidgetLayoutKey.SINGLE -> ShortcutTile(shortcuts.first(), compact = true)
        WidgetLayoutKey.GRID -> ShortcutGrid(shortcuts.take(6))
        WidgetLayoutKey.LIST -> ShortcutList(shortcuts)
        WidgetLayoutKey.AUTO -> when {
            size.width < 180.dp || size.height < 150.dp -> ShortcutTile(shortcuts.first(), compact = true)
            size.height < 220.dp -> ShortcutGrid(shortcuts.take(6))
            else -> ShortcutList(shortcuts)
        }
    }
}

@Composable
private fun NeedsSetup(configIntent: Intent) {
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(16.dp)
            .background(ColorProvider(WidgetColorKey.BLUE.composeColor))
            .clickable(actionStartActivity(configIntent))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                provider = ImageProvider(WidgetIconKey.STAR.drawableRes),
                contentDescription = null,
                modifier = GlanceModifier.size(36.dp)
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            Text(
                text = "Tap to set up",
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
private fun ShortcutTile(shortcut: ShortcutTileModel, compact: Boolean) {
    val appearance = shortcut.appearance
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .cornerRadius(16.dp)
            .background(ColorProvider(appearance.color.composeColor))
            .clickable(actionRunCallback<RunAutomationCallback>(
                actionParametersOf(RunAutomationCallback.AutomationIdParamKey to shortcut.automation.id)
            ))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(appearance.icon.drawableRes),
                contentDescription = null,
                modifier = GlanceModifier.size(if (compact) 36.dp else 42.dp)
            )
            Spacer(modifier = GlanceModifier.height(5.dp))
            Text(
                text = shortcut.label,
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ShortcutGrid(shortcuts: List<ShortcutTileModel>) {
    val rows = shortcuts.chunked(2)
    Column(modifier = GlanceModifier.fillMaxSize().padding(4.dp)) {
        for (row in rows) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                for (shortcut in row) {
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .padding(2.dp)
                    ) {
                        ShortcutTile(shortcut, compact = true)
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun ShortcutList(shortcuts: List<ShortcutTileModel>) {
    LazyColumn(modifier = GlanceModifier.fillMaxSize().padding(6.dp)) {
        items(shortcuts, itemId = { it.automation.id.toLong() }) { shortcut ->
            val appearance = shortcut.appearance
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
                    .cornerRadius(12.dp)
                    .background(ColorProvider(appearance.color.composeColor))
                    .clickable(actionRunCallback<RunAutomationCallback>(
                        actionParametersOf(RunAutomationCallback.AutomationIdParamKey to shortcut.automation.id)
                    ))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(appearance.icon.drawableRes),
                    contentDescription = null,
                    modifier = GlanceModifier.size(26.dp)
                )
                Spacer(modifier = GlanceModifier.width(10.dp))
                Text(
                    text = shortcut.label,
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
            }
        }
    }
}
