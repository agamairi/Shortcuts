@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.shortcuts.app.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.planner.DraftStep
import com.shortcuts.app.service.StepResult
import com.shortcuts.app.util.ActionDescriber

/** A stateless card for one visible draft clause. All mutations belong to the ViewModel. */
@Composable
fun DraftStepCard(
    index: Int,
    totalSteps: Int,
    step: DraftStep,
    result: StepResult?,
    onUpdateAction: (Action) -> Unit,
    onChooseApp: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onFixManually: () -> Unit,
    onOpenSettings: (Intent) -> Unit,
    modifier: Modifier = Modifier
) {
    when (step) {
        is DraftStep.Resolved -> ResolvedDraftStepCard(
            index = index,
            totalSteps = totalSteps,
            step = step,
            result = result,
            onUpdateAction = onUpdateAction,
            onChooseApp = onChooseApp,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDelete = onDelete,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
        is DraftStep.Unresolved -> UnresolvedDraftStepCard(
            index = index,
            totalSteps = totalSteps,
            step = step,
            result = result,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDelete = onDelete,
            onFixManually = onFixManually,
            modifier = modifier
        )
    }
}

@Composable
private fun ResolvedDraftStepCard(
    index: Int,
    totalSteps: Int,
    step: DraftStep.Resolved,
    result: StepResult?,
    onUpdateAction: (Action) -> Unit,
    onChooseApp: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onOpenSettings: (Intent) -> Unit,
    modifier: Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. ${ActionDescriber.describe(step.action)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (step.confidence < 0.8f) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.WarningAmber,
                                contentDescription = "Double-check this step",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.height(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Double-check this",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
                StepControls(index, totalSteps, onMoveUp, onMoveDown, onDelete)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "From: \"${step.sourceText}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            ActionParameterEditor(step.action, onUpdateAction, onChooseApp)
            RestrictedToggleNote(step.action)
            if (result != null) {
                Spacer(Modifier.height(12.dp))
                StepResultMessage(result, onOpenSettings)
            }
        }
    }
}

@Composable
private fun RestrictedToggleNote(action: Action) {
    if (action.actionType != ActionType.SYSTEM_TOGGLE) return
    val note = when (action.target?.trim()?.lowercase()?.replace("_", "")) {
        "wifi" -> "Android doesn't let apps turn Wi-Fi on directly — this opens the Wi-Fi panel for you."
        "bluetooth" -> "Android requires you to confirm Bluetooth changes in its system prompt."
        "airplanemode" -> "Android doesn't let apps change Airplane mode — this opens its settings instead."
        "location" -> "Android doesn't let apps change Location directly — this opens Location settings instead."
        else -> null
    }
    if (note != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UnresolvedDraftStepCard(
    index: Int,
    totalSteps: Int,
    step: DraftStep.Unresolved,
    result: StepResult?,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onFixManually: () -> Unit,
    modifier: Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${index + 1}. Needs your input",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "From: \"${step.sourceText}\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                StepControls(index, totalSteps, onMoveUp, onMoveDown, onDelete, MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(step.reason, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onFixManually) {
                Text("Fix manually")
            }
            if (result != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Not tested until this step is fixed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun StepControls(
    index: Int,
    totalSteps: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMoveUp, enabled = index > 0) {
            Icon(Icons.Filled.ArrowUpward, "Move step up", tint = tint)
        }
        IconButton(onClick = onMoveDown, enabled = index < totalSteps - 1) {
            Icon(Icons.Filled.ArrowDownward, "Move step down", tint = tint)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, "Delete step", tint = tint)
        }
    }
}

@Composable
private fun ActionParameterEditor(
    action: Action,
    onUpdateAction: (Action) -> Unit,
    onChooseApp: () -> Unit
) {
    when (action.actionType) {
        ActionType.SYSTEM_TOGGLE -> {
            OutlinedTextField(
                value = action.target.orEmpty(),
                onValueChange = { onUpdateAction(action.copy(target = it)) },
                label = { Text("Device control") },
                placeholder = { Text("Wi-Fi, Bluetooth, flashlight…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("On", "Off", "Toggle").forEach { state ->
                    FilterChip(
                        selected = action.state.equals(state, ignoreCase = true),
                        onClick = { onUpdateAction(action.copy(state = state)) },
                        label = { Text(state) }
                    )
                }
            }
        }
        ActionType.APP_INTENT -> {
            OutlinedTextField(
                value = action.packageName.orEmpty(),
                onValueChange = { onUpdateAction(action.copy(packageName = it)) },
                label = { Text("App to open") },
                placeholder = { Text("com.spotify.music") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            TextButton(onClick = onChooseApp) { Text("Choose installed app") }
        }
        ActionType.SEND_MESSAGE -> {
            OutlinedTextField(
                value = action.target.orEmpty(),
                onValueChange = { onUpdateAction(action.copy(target = it)) },
                label = { Text("Send to") },
                placeholder = { Text("Phone number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = action.textInput.orEmpty(),
                onValueChange = { onUpdateAction(action.copy(textInput = it)) },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your messaging app opens with this ready — you tap send.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ActionType.DIAL_NUMBER -> {
            OutlinedTextField(
                value = action.target.orEmpty(),
                onValueChange = { onUpdateAction(action.copy(target = it)) },
                label = { Text("Call") },
                placeholder = { Text("Phone number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Your dialer opens with the number ready — you place the call.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ActionType.HTTP_REQUEST -> {
            OutlinedTextField(
                value = action.url.orEmpty(),
                onValueChange = { onUpdateAction(action.copy(url = it)) },
                label = { Text("Web address") },
                placeholder = { Text("https://example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = action.method ?: "GET",
                onValueChange = { onUpdateAction(action.copy(method = it)) },
                label = { Text("Request method") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        ActionType.UI_AUTOMATION -> {
            OutlinedTextField(
                value = action.targetText ?: action.targetNodeId ?: action.target.orEmpty(),
                onValueChange = { onUpdateAction(action.copy(targetText = it, targetNodeId = it)) },
                label = { Text("What appears on screen") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = action.textInput.orEmpty(),
                onValueChange = { onUpdateAction(action.copy(textInput = it, uiActionType = "TYPE_TEXT")) },
                label = { Text("Text to type (leave blank to tap)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

@Composable
private fun StepResultMessage(result: StepResult, onOpenSettings: (Intent) -> Unit) {
    when (result) {
        StepResult.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Passed", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        is StepResult.Failed -> Text(
            text = result.userMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        is StepResult.NeedsPermission -> {
            Text("Permission needed: ${result.permission}", color = MaterialTheme.colorScheme.error)
            result.settingsIntent?.let { intent ->
                TextButton(onClick = { onOpenSettings(intent) }) { Text("Open settings") }
            }
        }
        is StepResult.Skipped -> Text(
            "Not run: ${result.why}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
