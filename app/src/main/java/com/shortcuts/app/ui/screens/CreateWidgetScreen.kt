package com.shortcuts.app.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.viewmodel.CustomWidgetBuilderData
import com.shortcuts.app.viewmodel.CustomWidgetViewModel
import com.shortcuts.app.widget.CustomWidgetReceiver
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWidgetScreen(
    viewModel: CustomWidgetViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToAiBuilder: () -> Unit
) {
    val context = LocalContext.current
    val db = remember(context) { AppDatabase.getDatabase(context) }
    val automations by db.automationDao().getAllAutomations().collectAsState(initial = emptyList())

    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val data = (uiState as? UiState.Success)?.data ?: CustomWidgetBuilderData()
    val errorMessage = (uiState as? UiState.Error)?.message

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Create Custom Widget",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: Describe with AI
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Describe it with AI",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    OutlinedTextField(
                        value = data.aiPrompt,
                        onValueChange = { viewModel.updateAiPrompt(it) },
                        label = { Text("Describe your widget") },
                        placeholder = { Text("e.g. A blue button that toggles WiFi") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Button(
                        onClick = { viewModel.generateWithAi(context) },
                        enabled = data.downloadProgress == null && !data.isGeneratingAi && data.aiPrompt.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (data.isGeneratingAi) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generating...")
                        } else {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generate with AI")
                        }
                    }

                    if (data.downloadProgress != null) {
                        val progressVal = data.downloadProgress
                        androidx.compose.material3.Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Downloading Model...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${progressVal}%",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = (progressVal / 100f).coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (data.aiNoMatchMessage != null) {
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = data.aiNoMatchMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = onNavigateToAiBuilder,
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Open AI Builder")
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Customize Manually
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Or customize manually",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )

                    // Shortcut Picker Dropdown
                    var expanded by remember { mutableStateOf(false) }
                    val selectedAutomation = automations.firstOrNull { it.id == data.selectedAutomationId }

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedAutomation?.name ?: "Select a Shortcut",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Target Shortcut") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            if (automations.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No shortcuts available") },
                                    onClick = { expanded = false }
                                )
                            } else {
                                automations.forEach { auto ->
                                    DropdownMenuItem(
                                        text = { Text(auto.name) },
                                        onClick = {
                                            viewModel.selectAutomation(auto.id)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Color Picker
                    Column {
                        Text(
                            text = "Tile Color",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            WidgetColorKey.entries.forEach { colorKey ->
                                val isSelected = data.selectedColorKey == colorKey
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(colorKey.composeColor)
                                        .border(
                                            width = if (isSelected) 3.dp else 0.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { viewModel.selectColor(colorKey) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "Selected",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Icon Picker
                    Column {
                        Text(
                            text = "Tile Icon",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            WidgetIconKey.entries.forEach { iconKey ->
                                val isSelected = data.selectedIconKey == iconKey
                                IconButton(
                                    onClick = { viewModel.selectIcon(iconKey) },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            width = if (isSelected) 2.dp else 0.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                ) {
                                    Icon(
                                        imageVector = iconKey.composeIcon,
                                        contentDescription = iconKey.displayLabel,
                                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // Label TextField
                    OutlinedTextField(
                        value = data.label,
                        onValueChange = { viewModel.updateLabel(it) },
                        label = { Text("Widget Label") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            // Live Preview Card
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Live Widget Preview",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val tileBg = data.selectedColorKey?.composeColor ?: WidgetColorKey.BLUE.composeColor
                    val tileIcon = data.selectedIconKey?.composeIcon ?: WidgetIconKey.STAR.composeIcon
                    val tileLabel = if (data.label.isBlank()) "Preview" else data.label

                    Box(
                        modifier = Modifier
                            .size(width = 140.dp, height = 80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(tileBg)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tileIcon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tileLabel,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Save / Pin Buttons
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.saveTemplate() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = data.label.isNotBlank() && data.selectedColorKey != null && data.selectedIconKey != null && data.selectedAutomationId != null
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (data.isSaved) "Saved!" else "Save Widget Template")
                }

                if (data.isSaved) {
                    Button(
                        onClick = {
                            val appWidgetManager = AppWidgetManager.getInstance(context)
                            val provider = ComponentName(context, CustomWidgetReceiver::class.java)
                            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                                appWidgetManager.requestPinAppWidget(provider, null, null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PushPin, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pin to Home Screen")
                    }
                }
            }
        }
    }
}
