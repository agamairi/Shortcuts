package com.shortcuts.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.viewmodel.AiBuilderData
import com.shortcuts.app.viewmodel.AiBuilderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiBuilderScreen(
    onNavigateBack: () -> Unit,
    viewModel: AiBuilderViewModel? = null
) {
    val context = LocalContext.current
    val vm = viewModel ?: remember { AiBuilderViewModel() }

    val prompt by vm.prompt.collectAsState()
    val uiState by vm.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val aiData = (uiState as? UiState.Success)?.data ?: AiBuilderData(prompt = prompt)
    val errorMessage = (uiState as? UiState.Error)?.message

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
            )
            vm.clearError()
        }
    }

    LaunchedEffect(aiData.isSaved) {
        if (aiData.isSaved) {
            onNavigateBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AI Shortcuts Builder", style = MaterialTheme.typography.titleLarge) },
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
                .padding(16.dp)
        ) {
            Text(
                text = "Describe your automated workflow:",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { vm.updatePrompt(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                placeholder = { Text("e.g., Turn on WiFi, open Spotify and click the play button when I'm home") },
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { vm.downloadModelAndGenerate(context) },
                modifier = Modifier.fillMaxWidth(),
                enabled = prompt.isNotBlank() && aiData.downloadProgress == null && !aiData.isGenerating
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download Model & Generate")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Linear progress indicator for model download progress
            if (aiData.downloadProgress != null) {
                val progressVal = aiData.downloadProgress
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
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
                        LinearProgressIndicator(
                            progress = (progressVal / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Circular loading spinner for AI generation
            if (aiData.isGenerating) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Generating automation flow using AI...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Preview generated shortcut card
            if (aiData.generatedAutomation != null) {
                val automation = aiData.generatedAutomation
                val actionList = remember(automation.actionsJson) {
                    ActionConverter().toActionList(automation.actionsJson)
                }

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Generated Shortcut Preview",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = automation.name,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        AssistChip(
                            onClick = { },
                            label = { Text("Trigger: ${automation.triggerType}") }
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Actions (${actionList.size}):",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(actionList) { action ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Text(
                                            text = "Type: ${action.actionType.name}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        if (action.target != null) Text("Target: ${action.target}", style = MaterialTheme.typography.bodySmall)
                                        if (action.state != null) Text("State: ${action.state}", style = MaterialTheme.typography.bodySmall)
                                        if (action.packageName != null) Text("Package: ${action.packageName}", style = MaterialTheme.typography.bodySmall)
                                        if (action.url != null) Text("URL: ${action.url}", style = MaterialTheme.typography.bodySmall)
                                        if (action.targetNodeId != null) Text("Node ID: ${action.targetNodeId}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { vm.saveGeneratedAutomation() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = "Save Shortcut")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Shortcut")
                        }
                    }
                }
            }
        }
    }
}
