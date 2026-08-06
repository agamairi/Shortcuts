package com.shortcuts.app.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.appwidget.cornerRadius
import com.shortcuts.app.data.AppDatabase

class AutomationWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val db = AppDatabase.getDatabase(context)
        val binding = db.widgetBindingDao().getBinding(appWidgetId)
        val automationName = if (binding != null) {
            db.automationDao().getAutomationById(binding.automationId)?.name ?: "Deleted"
        } else {
            "Not configured"
        }

        provideContent {
            GlanceTheme {
                WidgetTile(name = automationName)
            }
        }
    }
}

@Composable
private fun WidgetTile(name: String) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.primaryContainer)
            .clickable(actionRunCallback<RunAutomationCallback>())
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            style = TextStyle(
                color = GlanceTheme.colors.onPrimaryContainer,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 2
        )
    }
}
