package com.shortcuts.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigSource

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

private data class ShortcutTileModel(
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

/** Shared Glance implementation used by the new provider and all legacy-provider shims. */
open class UnifiedShortcutWidget(
    private val sourceType: WidgetConfigSource,
    private val configActivity: Class<out Activity>
) : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val db = AppDatabase.getDatabase(context)
        // A legacy provider can receive a newly allocated ID from the launcher. Its retained
        // configuration Activity forwards to the adaptive picker, which writes UNIFIED. Prefer
        // the legacy row for widgets that were already placed, then fall back to UNIFIED so the
        // forwarded setup renders in that retained provider as well.
        val config = db.widgetConfigDao().getConfig(appWidgetId, sourceType.name)
            ?: if (sourceType != WidgetConfigSource.UNIFIED) {
                db.widgetConfigDao().getConfig(appWidgetId, WidgetConfigSource.UNIFIED.name)
            } else {
                null
            }
        val shortcuts = config?.let { resolveShortcuts(db, it) }.orEmpty()
        val configIntent = Intent(context, configActivity).putExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            appWidgetId
        )

        provideContent {
            GlanceTheme {
                AdaptiveShortcutWidgetContent(shortcuts, configIntent)
            }
        }
    }

    private suspend fun resolveShortcuts(
        db: AppDatabase,
        config: WidgetConfig
    ): List<ShortcutTileModel> {
        if (config.sourceType == WidgetConfigSource.GRID.name) {
            return WidgetConfigParser.automationIds(config.templateIdsJson).mapNotNull { templateId ->
                val template = db.customWidgetTemplateDao().getById(templateId) ?: return@mapNotNull null
                val automation = db.automationDao().getAutomationById(template.automationId)
                    ?: return@mapNotNull null
                ShortcutTileModel(automation, template.label, template.colorKey, template.iconKey)
            }
        }

        val template = config.templateId?.let { db.customWidgetTemplateDao().getById(it) }
        return WidgetConfigParser.automationIds(config.automationIdsJson).mapNotNull { automationId ->
            val automation = db.automationDao().getAutomationById(automationId) ?: return@mapNotNull null
            ShortcutTileModel(
                automation = automation,
                label = config.label ?: template?.label ?: automation.name,
                colorKey = config.colorKey ?: template?.colorKey,
                iconKey = config.iconKey ?: template?.iconKey
            )
        }
    }
}

@Composable
private fun AdaptiveShortcutWidgetContent(
    shortcuts: List<ShortcutTileModel>,
    configIntent: Intent
) {
    val size = LocalSize.current
    when {
        shortcuts.isEmpty() -> NeedsSetup(configIntent)
        size.width < 180.dp || size.height < 150.dp -> ShortcutTile(shortcuts.first(), compact = true)
        size.height < 220.dp -> ShortcutGrid(shortcuts.take(6))
        else -> ShortcutList(shortcuts)
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
    Column(modifier = GlanceModifier.fillMaxSize().padding(4.dp)) {
        shortcuts.chunked(2).forEach { row ->
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                row.forEach { shortcut ->
                    Box(modifier = GlanceModifier.defaultWeight().height(52.dp).padding(2.dp)) {
                        ShortcutTile(shortcut, compact = true)
                    }
                }
                if (row.size == 1) Spacer(modifier = GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun ShortcutList(shortcuts: List<ShortcutTileModel>) {
    LazyColumn(modifier = GlanceModifier.fillMaxSize().padding(6.dp)) {
        items(shortcuts) { shortcut ->
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
