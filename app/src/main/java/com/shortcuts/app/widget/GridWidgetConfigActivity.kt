package com.shortcuts.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.google.gson.Gson
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.CustomWidgetTemplate
import com.shortcuts.app.data.GridWidgetBinding
import com.shortcuts.app.ui.ShortcutsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class GridWidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val db = AppDatabase.getDatabase(this)
        val templateDao = db.customWidgetTemplateDao()

        setContent {
            ShortcutsTheme {
                GridConfigScreen(
                    templatesFlow = templateDao.getAll(),
                    onSaveSelected = { selectedIds ->
                        onSaveGrid(selectedIds, db)
                    }
                )
            }
        }
    }

    private fun onSaveGrid(selectedIds: List<Int>, db: AppDatabase) {
        CoroutineScope(Dispatchers.IO).launch {
            val json = Gson().toJson(selectedIds)
            db.gridWidgetBindingDao().upsertBinding(
                GridWidgetBinding(widgetId = appWidgetId, templateIdsJson = json)
            )

            val manager = GlanceAppWidgetManager(this@GridWidgetConfigActivity)
            val glanceId = manager.getGlanceIdBy(appWidgetId)
            GridWidget().update(this@GridWidgetConfigActivity, glanceId)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultValue)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridConfigScreen(
    templatesFlow: Flow<List<CustomWidgetTemplate>>,
    onSaveSelected: (List<Int>) -> Unit
) {
    val templates by templatesFlow.collectAsState(initial = null)
    val selectedIds = remember { mutableStateListOf<Int>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Select Widget Templates (Max 6)",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            )
        },
        bottomBar = {
            val currentTemplates = templates
            if (currentTemplates != null && currentTemplates.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = { onSaveSelected(selectedIds.toList()) },
                        enabled = selectedIds.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Widget (${selectedIds.size}/6)")
                    }
                }
            }
        }
    ) { padding ->
        val currentTemplates = templates
        if (currentTemplates == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (currentTemplates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Create a widget template first in 'Create Your Own Widget'",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                if (selectedIds.size >= 6) {
                    Text(
                        text = "Max 6 selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(currentTemplates, key = { it.id }) { template ->
                        val isChecked = selectedIds.contains(template.id)
                        val canSelect = isChecked || selectedIds.size < 6
                        val colorKey = try { WidgetColorKey.valueOf(template.colorKey) } catch (e: Exception) { WidgetColorKey.BLUE }
                        val iconKey = try { WidgetIconKey.valueOf(template.iconKey) } catch (e: Exception) { WidgetIconKey.STAR }

                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canSelect) {
                                    if (isChecked) {
                                        selectedIds.remove(template.id)
                                    } else if (selectedIds.size < 6) {
                                        selectedIds.add(template.id)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked && selectedIds.size < 6) {
                                            selectedIds.add(template.id)
                                        } else if (!checked) {
                                            selectedIds.remove(template.id)
                                        }
                                    },
                                    enabled = canSelect
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = iconKey.composeIcon,
                                    contentDescription = null,
                                    tint = colorKey.composeColor,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(
                                        text = template.label,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                    Text(
                                        text = "Style: ${template.colorKey} • ${iconKey.displayLabel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
