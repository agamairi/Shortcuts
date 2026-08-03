package com.shortcuts.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.viewmodel.AutomationViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualBuilderScreen(
    onNavigateBack: () -> Unit,
    viewModel: AutomationViewModel? = null,
    onSaveAutomation: ((Automation) -> Unit)? = null
) {
    var shortcutName by remember { mutableStateOf("") }
    var selectedTrigger by remember { mutableStateOf("MANUAL") }
    val actions = remember { mutableStateListOf<Action>() }
    var isSaving by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val triggerOptions = listOf("MANUAL", "WIDGET", "SCHEDULE")

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Manual Builder", style = MaterialTheme.typography.titleLarge) },
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
            OutlinedTextField(
                value = shortcutName,
                onValueChange = { shortcutName = it },
                label = { Text("Shortcut Name") },
                placeholder = { Text("e.g., Morning Routine") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Trigger Type",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                triggerOptions.forEach { trigger ->
                    FilterChip(
                        selected = selectedTrigger == trigger,
                        onClick = { selectedTrigger = trigger },
                        label = { Text(trigger) },
                        leadingIcon = if (selectedTrigger == trigger) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Actions (${actions.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                OutlinedButton(
                    onClick = {
                        actions.add(
                            Action(
                                actionType = ActionType.SYSTEM_TOGGLE,
                                target = "WIFI",
                                state = "ON"
                            )
                        )
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Action")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Action")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(actions) { index, action ->
                    ActionEditorCard(
                        index = index,
                        totalActions = actions.size,
                        action = action,
                        onUpdateAction = { updated -> actions[index] = updated },
                        onMoveUp = {
                            if (index > 0) {
                                val item = actions.removeAt(index)
                                actions.add(index - 1, item)
                            }
                        },
                        onMoveDown = {
                            if (index < actions.size - 1) {
                                val item = actions.removeAt(index)
                                actions.add(index + 1, item)
                            }
                        },
                        onRemove = { actions.removeAt(index) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (shortcutName.isBlank()) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Shortcut name cannot be empty")
                        }
                        return@Button
                    }
                    if (actions.isEmpty()) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Please add at least one action")
                        }
                        return@Button
                    }

                    isSaving = true
                    val actionsJson = ActionConverter().fromActionList(actions.toList())
                    val automation = Automation(
                        name = shortcutName.trim(),
                        actionsJson = actionsJson,
                        triggerType = selectedTrigger
                    )

                    if (onSaveAutomation != null) {
                        onSaveAutomation(automation)
                    } else if (viewModel != null) {
                        viewModel.insert(automation)
                    }
                    isSaving = false
                    onNavigateBack()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.Save, contentDescription = "Save")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Shortcut")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionEditorCard(
    index: Int,
    totalActions: Int,
    action: Action,
    onUpdateAction: (Action) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Action #${index + 1}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Row {
                    IconButton(onClick = onMoveUp, enabled = index > 0) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move Up")
                    }
                    IconButton(onClick = onMoveDown, enabled = index < totalActions - 1) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move Down")
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove Action", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionType.values().forEach { type ->
                    FilterChip(
                        selected = action.actionType == type,
                        onClick = { onUpdateAction(action.copy(actionType = type)) },
                        label = { Text(type.name, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when (action.actionType) {
                ActionType.SYSTEM_TOGGLE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = action.target ?: "WIFI",
                            onValueChange = { onUpdateAction(action.copy(target = it)) },
                            label = { Text("Target (e.g. WIFI)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = action.state ?: "ON",
                            onValueChange = { onUpdateAction(action.copy(state = it)) },
                            label = { Text("State (ON/OFF)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                ActionType.APP_INTENT -> {
                    OutlinedTextField(
                        value = action.packageName ?: "",
                        onValueChange = { onUpdateAction(action.copy(packageName = it)) },
                        label = { Text("Package Name") },
                        placeholder = { Text("com.spotify.music") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ActionType.HTTP_REQUEST -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = action.url ?: "",
                            onValueChange = { onUpdateAction(action.copy(url = it)) },
                            label = { Text("URL") },
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = action.method ?: "GET",
                            onValueChange = { onUpdateAction(action.copy(method = it)) },
                            label = { Text("Method") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                ActionType.UI_AUTOMATION -> {
                    OutlinedTextField(
                        value = action.targetNodeId ?: "",
                        onValueChange = { onUpdateAction(action.copy(targetNodeId = it)) },
                        label = { Text("Target Node ID / Text") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
