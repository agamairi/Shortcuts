package com.shortcuts.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.Automation

class ShortcutsListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val db = AppDatabase.getDatabase(context)
        val binding = db.widgetListBindingDao().getBinding(appWidgetId)

        val automationIds: List<Int> = if (binding != null && binding.automationIdsJson.isNotBlank()) {
            try {
                val type = object : TypeToken<List<Int>>() {}.type
                Gson().fromJson(binding.automationIdsJson, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val automations = automationIds.take(4).mapNotNull { autoId ->
            db.automationDao().getAutomationById(autoId)
        }

        provideContent {
            GlanceTheme {
                ListWidgetContent(automations = automations)
            }
        }
    }
}

@Composable
private fun ListWidgetContent(automations: List<Automation>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.background)
            .padding(12.dp)
    ) {
        Text(
            text = "Shortcuts",
            style = TextStyle(
                color = GlanceTheme.colors.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = GlanceModifier.padding(bottom = 8.dp)
        )
        if (automations.isEmpty()) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No shortcuts configured",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                )
            }
        } else {
            LazyColumn(
                modifier = GlanceModifier.fillMaxSize()
            ) {
                items(automations) { automation ->
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .cornerRadius(8.dp)
                            .background(GlanceTheme.colors.primaryContainer)
                            .clickable(
                                actionRunCallback<RunAutomationCallback>(
                                    actionParametersOf(RunAutomationCallback.AutomationIdParamKey to automation.id)
                                )
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = automation.name,
                            style = TextStyle(
                                color = GlanceTheme.colors.onPrimaryContainer,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
