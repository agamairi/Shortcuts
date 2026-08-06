package com.shortcuts.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.GreetingWidgetBinding
import java.time.LocalTime

class GreetingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val db = AppDatabase.getDatabase(context)
        val binding = db.greetingWidgetBindingDao().getBinding(appWidgetId)
        val automationName = if (binding != null) {
            db.automationDao().getAutomationById(binding.automationId)?.name ?: "Deleted"
        } else {
            null
        }

        provideContent {
            GreetingWidgetContent(binding = binding, automationName = automationName)
        }
    }
}

@Composable
private fun GreetingWidgetContent(
    binding: GreetingWidgetBinding?,
    automationName: String?
) {
    if (binding == null) {
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

    val colorKey = try {
        WidgetColorKey.valueOf(binding.colorKey)
    } catch (e: Exception) {
        WidgetColorKey.BLUE
    }

    val greetingText = GreetingTextHelper.greetingFor(
        hour = LocalTime.now().hour,
        name = binding.userName
    )

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(ColorProvider(colorKey.composeColor))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = greetingText,
            style = TextStyle(
                color = ColorProvider(Color.White),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
        Spacer(modifier = GlanceModifier.height(12.dp))
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(12.dp)
                .background(ColorProvider(Color.White.copy(alpha = 0.2f)))
                .clickable(
                    actionRunCallback<RunAutomationCallback>(
                        actionParametersOf(RunAutomationCallback.AutomationIdParamKey to binding.automationId)
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = automationName ?: "Shortcut",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}
