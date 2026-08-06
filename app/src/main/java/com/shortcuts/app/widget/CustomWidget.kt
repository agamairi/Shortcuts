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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.CustomWidgetTemplate

class CustomWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val db = AppDatabase.getDatabase(context)
        val binding = db.customWidgetBindingDao().getBinding(appWidgetId)
        val template = if (binding != null) {
            db.customWidgetTemplateDao().getById(binding.templateId)
        } else {
            null
        }

        provideContent {
            CustomWidgetTile(template = template)
        }
    }
}

@Composable
private fun CustomWidgetTile(template: CustomWidgetTemplate?) {
    if (template == null) {
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
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(16.dp)
            .background(ColorProvider(colorKey.composeColor))
            .clickable(
                actionRunCallback<RunAutomationCallback>(
                    actionParametersOf(RunAutomationCallback.AutomationIdParamKey to template.automationId)
                )
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                provider = ImageProvider(iconKey.drawableRes),
                contentDescription = null,
                modifier = GlanceModifier.size(24.dp)
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = template.label,
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
