package com.shortcuts.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.CustomWidgetTemplate

class GridWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val db = AppDatabase.getDatabase(context)
        val binding = db.gridWidgetBindingDao().getBinding(appWidgetId)

        val templateIds: List<Int> = if (binding != null && binding.templateIdsJson.isNotBlank()) {
            try {
                val type = object : TypeToken<List<Int>>() {}.type
                Gson().fromJson(binding.templateIdsJson, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val templates = templateIds.take(6).mapNotNull { templateId ->
            db.customWidgetTemplateDao().getById(templateId)
        }

        provideContent {
            GridWidgetContent(templates = templates)
        }
    }
}

@Composable
private fun GridWidgetContent(templates: List<CustomWidgetTemplate>) {
    if (templates.isEmpty()) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(16.dp)
                .background(ColorProvider(WidgetColorKey.BLUE.composeColor))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Not configured",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 12.sp
                )
            )
        }
        return
    }

    val rows = templates.chunked(2)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(ColorProvider(Color(0xFF1C1C1E)))
            .padding(6.dp)
    ) {
        for (rowTemplates in rows) {
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight()
                    .padding(vertical = 3.dp)
            ) {
                for (template in rowTemplates) {
                    GridTileCell(
                        template = template,
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .padding(horizontal = 3.dp)
                    )
                }
                if (rowTemplates.size == 1) {
                    Spacer(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .fillMaxHeight()
                            .padding(horizontal = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GridTileCell(
    template: CustomWidgetTemplate,
    modifier: GlanceModifier = GlanceModifier
) {
    val colorKey = try {
        WidgetColorKey.valueOf(template.colorKey)
    } catch (e: Exception) {
        WidgetColorKey.BLUE
    }

    val iconKey = try {
        WidgetIconKey.valueOf(template.iconKey)
    } catch (e: Exception) {
        WidgetIconKey.STAR
    }

    Box(
        modifier = modifier
            .cornerRadius(12.dp)
            .background(ColorProvider(colorKey.composeColor))
            .clickable(
                actionRunCallback<RunAutomationCallback>(
                    actionParametersOf(RunAutomationCallback.AutomationIdParamKey to template.automationId)
                )
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(iconKey.drawableRes),
                contentDescription = null,
                modifier = GlanceModifier.size(20.dp)
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = template.label,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}
