package com.shortcuts.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.service.AutomationRecorder
import com.shortcuts.app.ui.theme.LocalShortcutsPalette
import com.shortcuts.app.util.AccessibilityStatusChecker
import com.shortcuts.app.viewmodel.AutomationViewModel
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.shortcuts.app.widget.WidgetIconKey
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.planner.PackageManagerInstalledAppSource
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    viewModel: AutomationViewModel?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalShortcutsPalette.current
    val isRecording by AutomationRecorder.isRecording.collectAsState()
    val recordedActionsFlow by AutomationRecorder.recordedActions.collectAsState()
    val accessibilityAcknowledged by AccessibilityAutomationOptIn.isAcknowledged(context).collectAsState(initial = false)
    var isAccessibilityServiceActive by remember {
        mutableStateOf(AccessibilityStatusChecker.isAccessibilityServiceActive(context))
    }
    var recordingStatusMessage by remember { mutableStateOf<String?>(null) }
    val recorderSessionController = remember { RecorderSessionController() }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityServiceActive = AccessibilityStatusChecker.isAccessibilityServiceActive(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var shortcutName by remember { mutableStateOf("Recorded shortcut") }
    var selectedColor by remember { mutableStateOf(WidgetColorKey.BLUE) }
    var selectedIcon by remember { mutableStateOf(WidgetIconKey.BOLT) }
    var nameEditorOpen by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<ManualSlotPicker?>(null) }
    var textInputState by remember { mutableStateOf("") }
    
    val installedApps = remember(context) {
        PackageManagerInstalledAppSource(context.packageManager).launchableApps()
            .map { InstalledAppInfo(it.userVisibleLabel, it.packageName) }
            .sortedBy { it.label.lowercase() }
    }

    val editableActions = remember { mutableStateListOf<Action>() }
    var wasRecording by remember { mutableStateOf(isRecording) }

    LaunchedEffect(isRecording, isAccessibilityServiceActive, recordedActionsFlow) {
        if (!isRecording && recordedActionsFlow.isNotEmpty() && editableActions.isEmpty()) {
            editableActions.addAll(recordedActionsFlow)
        }
        if (wasRecording && !isRecording) {
            val serviceStillActive = AccessibilityStatusChecker.isAccessibilityServiceActive(context)
            isAccessibilityServiceActive = serviceStillActive
            if (!serviceStillActive && recordedActionsFlow.isNotEmpty()) {
                recordingStatusMessage = "Accessibility was turned off, so recording ended. Your captured steps are ready to review."
            }
        }
        wasRecording = isRecording
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val recorderUiState = determineRecorderUiState(
        consentGiven = accessibilityAcknowledged,
        serviceActive = isAccessibilityServiceActive
    )
    val sessionUiState = determineRecorderSessionUiState(
        isRecording = isRecording,
        recordedActionsCount = recordedActionsFlow.size,
        editableActionsCount = editableActions.size
    )
    val shouldShowPrerequisite = sessionUiState == RecorderSessionUiState.START_RECORDING &&
        recorderUiState != RecorderUiState.READY_TO_RECORD

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = if (sessionUiState == RecorderSessionUiState.START_RECORDING) palette.surface else selectedColor.composeColor
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = {
                            if (isRecording) {
                                AutomationRecorder.stopRecording(context)
                                AutomationRecorder.clearRecording()
                            }
                            onNavigateBack()
                        }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.ArrowBack, "Back", tint = palette.ink, modifier = Modifier.size(22.dp))
                }

                if (sessionUiState != RecorderSessionUiState.START_RECORDING) {
                    Box(
                        Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.ink.copy(alpha = .22f))
                            .clickable { nameEditorOpen = true }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(shortcutName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
                            Icon(Icons.Filled.Edit, "Edit shortcut name", tint = palette.ink, modifier = Modifier.size(13.dp))
                        }
                    }
                }

                if (sessionUiState == RecorderSessionUiState.REVIEW) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(palette.ink)
                            .clickable {
                                if (shortcutName.isBlank()) {
                                    scope.launch { snackbarHostState.showSnackbar("Shortcut name cannot be empty") }
                                    return@clickable
                                }
                                if (editableActions.isEmpty()) {
                                    scope.launch { snackbarHostState.showSnackbar("No steps to save") }
                                    return@clickable
                                }
                                val automation = ManualBuilderUtils.buildManualAutomation(
                                    shortcutName,
                                    editableActions.toList(),
                                    selectedColor.name,
                                    selectedIcon.name
                                )
                                viewModel?.insert(automation)
                                AutomationRecorder.clearRecording()
                                onNavigateBack()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Check, "Save shortcut", tint = selectedColor.composeColor, modifier = Modifier.size(20.dp))
                    }
                } else {
                    Spacer(Modifier.size(44.dp))
                }
            }

            if (shouldShowPrerequisite) {
                RecorderPrerequisiteContent(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    uiState = recorderUiState,
                    serviceActive = isAccessibilityServiceActive,
                    onAcknowledgeDisclosure = {
                        scope.launch { AccessibilityAutomationOptIn.acknowledge(context) }
                    },
                    onOpenAccessibilitySettings = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenAppInfo = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    }
                )
            } else if (sessionUiState == RecorderSessionUiState.START_RECORDING) {
                // Not recording, no actions
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.RadioButtonChecked, null, tint = palette.danger, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Record a Shortcut",
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.ink,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "We'll capture your taps and typing.\nGo to another app and perform the actions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Text(
                            "Note: Replay depends on screens appearing in the same order. " +
                            "It replays what was tapped and may not work if the app changes or is slow to load.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Spacer(Modifier.height(32.dp))
                    recordingStatusMessage?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.danger,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                        )
                    }
                    Button(
                        onClick = {
                            val didStart = recorderSessionController.startIfServiceActive(context) {
                                editableActions.clear()
                                recordingStatusMessage = null
                                AutomationRecorder.startRecording(context)
                            }
                            if (didStart) {
                                Toast.makeText(context, "Recording started. Leave the app to record steps.", Toast.LENGTH_LONG).show()
                            } else {
                                isAccessibilityServiceActive = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.ink)
                    ) {
                        Text("Start Recording")
                    }
                }
            } else if (sessionUiState == RecorderSessionUiState.RECORDING) {
                // Recording state
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.StopCircle, null, tint = palette.danger, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Recording in progress...",
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.ink,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Steps recorded so far: ${recordedActionsFlow.size}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.inkMuted
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = {
                            AutomationRecorder.stopRecording(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.ink)
                    ) {
                        Text("Stop Recording")
                    }
                }
            } else {
                // Review and Save
                Column(Modifier.weight(1f).padding(start = 24.dp, end = 24.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    recordingStatusMessage?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.danger
                        )
                    }
                    editableActions.forEachIndexed { index, action ->
                        ManualSentenceRow(
                            index = index,
                            totalActions = editableActions.size,
                            action = action,
                            installedApps = installedApps,
                            onOpenPicker = { picker = it },
onMoveUp = { RecorderListOperations.moveUp(editableActions, index) },
onMoveDown = { RecorderListOperations.moveDown(editableActions, index) },
onRemove = { RecorderListOperations.remove(editableActions, index) }
                        )
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
                ) {
                    items(WidgetIconKey.entries) { icon ->
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.ink.copy(alpha=0.18f))
                                .then(if (icon == selectedIcon) Modifier.border(3.dp, palette.ink, CircleShape) else Modifier)
                                .clickable { selectedIcon = icon },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon.composeIcon, null, tint = palette.ink, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
                ) {
                    items(WidgetColorKey.entries) { colorKey ->
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colorKey.composeColor)
                                .then(if (colorKey == selectedColor) Modifier.border(3.dp, palette.ink, CircleShape) else Modifier)
                                .clickable { selectedColor = colorKey }
                        )
                    }
                }
            }
        }
    }

    if (nameEditorOpen) {
        AlertDialog(
            onDismissRequest = { nameEditorOpen = false },
            title = { Text("Shortcut name") },
            text = { OutlinedTextField(shortcutName, { shortcutName = it }, singleLine = true) },
            confirmButton = { TextButton({ nameEditorOpen = false }) { Text("Done") } }
        )
    }

    picker?.let { currentPicker ->
        if (currentPicker.kind == ManualSlotKind.TEXT) {
            LaunchedEffect(currentPicker) { textInputState = currentPicker.initialValue }
            AlertDialog(
                onDismissRequest = { picker = null },
                title = { Text(currentPicker.label) },
                text = {
                    OutlinedTextField(
                        value = textInputState,
                        onValueChange = { textInputState = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val index = currentPicker.stepIndex
                        if (index in editableActions.indices) {
                            val action = editableActions[index]
                            editableActions[index] = if (currentPicker.isSecondaryText) {
                                action.copy(textInput = textInputState)
                            } else {
                                when (action.actionType) {
                                    ActionType.HTTP_REQUEST -> action.copy(url = textInputState)
                                    ActionType.SEND_MESSAGE -> action.copy(target = textInputState)
                                    ActionType.DIAL_NUMBER -> action.copy(target = textInputState)
                                    ActionType.UI_AUTOMATION -> action.copy(targetText = textInputState)
                                    else -> action
                                }
                            }
                        }
                        picker = null
                    }) { Text("OK") }
                },
                dismissButton = {
                    TextButton(onClick = { picker = null }) { Text("Cancel") }
                }
            )
        } else {
            ManualSlotPickerDialog(currentPicker, installedApps, onDismiss = { picker = null }) { picked ->
                val index = currentPicker.stepIndex
                if (index in editableActions.indices) {
                    val action = editableActions[index]
                    editableActions[index] = when (currentPicker.kind) {
                        ManualSlotKind.STATE -> action.copy(state = picked)
                        ManualSlotKind.CONTROL -> action.copy(target = picked)
                        ManualSlotKind.APP -> action.copy(packageName = picked)
                        ManualSlotKind.HTTP_METHOD -> action.copy(method = picked)
                        ManualSlotKind.UI_ACTION_TYPE -> action.copy(uiActionType = picked)
                        else -> action
                    }
                }
                picker = null
            }
        }
    }

}


object RecorderListOperations {
    fun moveUp(list: MutableList<Action>, index: Int) {
        if (index in 1 until list.size) {
            val item = list.removeAt(index)
            list.add(index - 1, item)
        }
    }

    fun moveDown(list: MutableList<Action>, index: Int) {
        if (index in 0 until list.size - 1) {
            val item = list.removeAt(index)
            list.add(index + 1, item)
        }
    }

    fun remove(list: MutableList<Action>, index: Int) {
        if (index in 0 until list.size) {
            list.removeAt(index)
        }
    }
}

enum class RecorderUiState { CONSENT_REQUIRED, SERVICE_NOT_ENABLED, READY_TO_RECORD }

fun determineRecorderUiState(
    consentGiven: Boolean,
    serviceActive: Boolean
): RecorderUiState {
    return if (!consentGiven) {
        RecorderUiState.CONSENT_REQUIRED
    } else if (!serviceActive) {
        RecorderUiState.SERVICE_NOT_ENABLED
    } else {
        RecorderUiState.READY_TO_RECORD
    }
}

enum class RecorderSessionUiState { START_RECORDING, RECORDING, REVIEW }

fun determineRecorderSessionUiState(
    isRecording: Boolean,
    recordedActionsCount: Int,
    editableActionsCount: Int
): RecorderSessionUiState {
    return if (isRecording) {
        RecorderSessionUiState.RECORDING
    } else if (recordedActionsCount == 0 && editableActionsCount == 0) {
        RecorderSessionUiState.START_RECORDING
    } else {
        RecorderSessionUiState.REVIEW
    }
}

@Composable
private fun RecorderPrerequisiteContent(
    modifier: Modifier,
    uiState: RecorderUiState,
    serviceActive: Boolean,
    onAcknowledgeDisclosure: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.RadioButtonChecked, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "Record a Shortcut",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))

        if (uiState == RecorderUiState.CONSENT_REQUIRED) {
            Text(
                "Recording captures the taps and typing you perform in other apps so Shortcuts can create a replayable shortcut. Please review and acknowledge this before recording.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAcknowledgeDisclosure, modifier = Modifier.fillMaxWidth()) {
                Text("Acknowledge disclosure")
            }
            Spacer(Modifier.height(16.dp))
        }

        if (!serviceActive) {
            Text(
                "Recording needs the accessibility service to observe the steps you perform in other apps.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "On a sideloaded app, Android may block the toggle as a Restricted setting. Allow it first in App info > three dots > Allow restricted settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Android Accessibility Settings")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
                Text("Open App info")
            }
        }
    }
}
