package com.shortcuts.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import com.shortcuts.app.widget.WidgetIconKey
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.planner.PackageManagerInstalledAppSource
import com.shortcuts.app.service.ShortcutPreflightReport
import com.shortcuts.app.service.ShortcutRunSafety
import com.shortcuts.app.service.ShortcutRunner
import com.shortcuts.app.service.settingsIntent
import com.shortcuts.app.service.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.PlayArrow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    viewModel: AutomationViewModel?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    // Fix #5: Show the full AccessibilityDisclosureScreen inline, mirroring SettingsScreen's pattern.
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureScreen(onNavigateBack = { showAccessibilityDisclosure = false })
        return
    }
    // Fix #6: Request POST_NOTIFICATIONS before recording starts (API 33+).
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Recording proceeds regardless of the result; in-app controls remain available. */ }
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
    var isSaving by remember { mutableStateOf(false) }
    var isActionPickerOpen by remember { mutableStateOf(false) }
    var targetInsertIndex by remember { mutableStateOf<Int?>(null) }
    var preflightReport by remember { mutableStateOf<ShortcutPreflightReport?>(null) }
    var pendingEditorRun by remember { mutableStateOf<PendingEditorRun?>(null) }
    
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

    // Fix #1: Track whether to show the discard confirmation dialog.
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val pendingStepCount = recordedActionsFlow.size

    val handleBackPress = {
        if (isRecording && pendingStepCount > 0) {
            // Stop the foreground service and disarm the overlay immediately (the original
            // fix's safety purpose), but DON'T clear the captured steps yet — show a
            // confirmation dialog so the user can choose to keep them as a reviewable draft.
            AutomationRecorder.stopRecording(context)
            showDiscardConfirmation = true
        } else if (isRecording) {
            // Recording with zero steps: safe to discard outright.
            AutomationRecorder.discardRecording(context)
            onNavigateBack()
        } else {
            onNavigateBack()
        }
    }

    // The recorder owns a foreground service and can own a full-screen accessibility overlay.
    // Do not let the system Back gesture make those live resources invisible to the user.
    BackHandler(enabled = isRecording || showDiscardConfirmation) {
        handleBackPress()
    }

    // Fix #1: Discard confirmation dialog — shown after the service/overlay are already stopped.
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = {
                // Dismissing keeps the draft; user can review or re-navigate.
                showDiscardConfirmation = false
            },
            title = { Text("Discard recorded steps?") },
            text = {
                Text("You have $pendingStepCount recorded step${if (pendingStepCount != 1) "s" else ""}. Discard them, or keep them for review?")
            },
            confirmButton = {
                TextButton(onClick = {
                    AutomationRecorder.clearRecording()
                    showDiscardConfirmation = false
                    onNavigateBack()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Keep the draft — stops the dialog and returns to REVIEW state.
                    showDiscardConfirmation = false
                }) { Text("Keep for review") }
            }
        )
    }

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
                            handleBackPress()
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
                                if (isSaving) return@clickable
                                if (shortcutName.isBlank()) {
                                    scope.launch { snackbarHostState.showSnackbar("Shortcut name cannot be empty") }
                                    return@clickable
                                }
                                if (editableActions.isEmpty()) {
                                    scope.launch { snackbarHostState.showSnackbar("No steps to save") }
                                    return@clickable
                                }
                                isSaving = true
                                scope.launch {
                                    try {
                                        val safe = editableActions.map { com.shortcuts.app.service.ActionExecutorService.prepareActionForPersistence(context, it) }
                                        if (safe.any { it == null }) {
                                            isSaving = false
                                            snackbarHostState.showSnackbar("The web token couldn't be secured. Your shortcut was not saved.")
                                            return@launch
                                        }
                                        val automation = ManualBuilderUtils.buildManualAutomation(
                                            shortcutName,
                                            safe.filterNotNull(),
                                            selectedColor.name,
                                            selectedIcon.name
                                        )
                                        val saveResult = viewModel?.insert(automation) ?: Result.success(Unit)

                                        if (saveResult.isSuccess) {
                                            AutomationRecorder.clearRecording()
                                            isSaving = false
                                            onNavigateBack()
                                        } else {
                                            isSaving = false
                                            val errorMsg = saveResult.exceptionOrNull()?.localizedMessage
                                                ?: viewModel?.errorState?.value
                                                ?: "Failed to save recording"
                                            snackbarHostState.showSnackbar("Save failed: $errorMsg. Your recorded draft has been kept.")
                                        }
                                    } catch (e: Exception) {
                                        isSaving = false
                                        snackbarHostState.showSnackbar("Save failed: ${e.localizedMessage ?: "Unknown error"}. Your recorded draft has been kept.")
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = selectedColor.composeColor)
                        } else {
                            Icon(Icons.Filled.Check, "Save shortcut", tint = selectedColor.composeColor, modifier = Modifier.size(20.dp))
                        }
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
                    onShowDisclosure = {
                        showAccessibilityDisclosure = true
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
                            // Fix #6: Request notification permission before recording so the
                            // foreground-service notification (with Stop / Mark-a-tap controls)
                            // is visible. On API < 33 or if already granted this is a no-op.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
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
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            com.shortcuts.app.service.TapMarkOverlay.arm(
                                com.shortcuts.app.service.AutomationAccessibilityService.instance
                            )
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.ink)
                    ) {
                        Text("Mark a tap")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use this when an app doesn't register taps automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            } else {
                // Review and Save
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 24.dp, end = 24.dp, top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    recordingStatusMessage?.let { message ->
                        item {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.danger
                            )
                        }
                    }
                    itemsIndexed(editableActions) { index, action ->
                        ManualSentenceRow(
                            index = index,
                            totalActions = editableActions.size,
                            action = action,
                            installedApps = installedApps,
                            onOpenPicker = { picker = it },
                            onActionChange = { editableActions[index] = it },
                            onInsertAbove = {
                                targetInsertIndex = index
                                isActionPickerOpen = true
                            },
                            onInsertBelow = {
                                targetInsertIndex = index + 1
                                isActionPickerOpen = true
                            },
                            onMoveUp = { RecorderListOperations.moveUp(editableActions, index) },
                            onMoveDown = { RecorderListOperations.moveDown(editableActions, index) },
                            onRemove = { RecorderListOperations.remove(editableActions, index) }
                        )
                    }
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.clickable {
                                targetInsertIndex = editableActions.size
                                isActionPickerOpen = true
                            }
                        ) {
                            Spacer(Modifier.width(22.dp))
                            Row(
                                Modifier
                                    .height(44.dp)
                                    .drawBehind {
                                        val strokeWidth = 1.5.dp.toPx()
                                        drawRoundRect(
                                            color = palette.ink.copy(alpha = .55f),
                                            cornerRadius = CornerRadius(22.dp.toPx()),
                                            style = Stroke(
                                                width = strokeWidth,
                                                pathEffect = PathEffect.dashPathEffect(
                                                    floatArrayOf(4.dp.toPx(), 3.dp.toPx())
                                                )
                                            )
                                        )
                                    }
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Filled.Add, null, tint = palette.ink, modifier = Modifier.size(16.dp))
                                Text("Add a step", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
                            }
                        }
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
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.weight(1f).height(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                            .border(1.5.dp, palette.ink.copy(alpha = .6f), androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                            .clickable {
                                val snapshot = editableActions.toList()
                                scope.launch {
                                    preflightReport = withContext(Dispatchers.IO) {
                                        ShortcutRunner(context.applicationContext).preflight(snapshot)
                                    }
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Filled.Check, null, tint = palette.ink, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp)); Text("Check", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
                    }
                    Row(
                        Modifier.weight(1f).height(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                            .background(palette.ink)
                            .clickable {
                                val snapshot = editableActions.toList()
                                val name = shortcutName
                                scope.launch {
                                    val report = withContext(Dispatchers.IO) {
                                        ShortcutRunner(context.applicationContext).preflight(snapshot)
                                    }
                                    if (!report.canRun) {
                                        preflightReport = report
                                        return@launch
                                    }
                                    val effects = ShortcutRunSafety.confirmationEffects(snapshot)
                                    if (effects.isEmpty()) {
                                        snackbarHostState.showSnackbar(
                                            runEditorDraftThroughGateway(context, name, snapshot).userMessage()
                                        )
                                    } else {
                                        pendingEditorRun = PendingEditorRun(name, snapshot, effects)
                                    }
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = selectedColor.composeColor, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp)); Text("Run now", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = selectedColor.composeColor)
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
            ManualSlotPickerDialog(currentPicker, installedApps, onDismiss = { picker = null }, onPicked = { picked ->
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
            }, onAppActionPicked = { pickedAction ->
                val index = currentPicker.stepIndex
                if (index in editableActions.indices) {
                    editableActions[index] = pickedAction
                }
                picker = null
            })
        }
    }

    if (isActionPickerOpen) {
        AddActionBottomSheet(
            actionTypes = ManualBuilderUtils.ACTION_TYPE_METADATA,
            onActionTypeSelected = { type ->
                val newAction = ManualBuilderUtils.createDefaultAction(type)
                val insertAt = (targetInsertIndex ?: editableActions.size).coerceIn(0, editableActions.size)
                editableActions.add(insertAt, newAction)
                targetInsertIndex = null
            },
            onDismiss = {
                isActionPickerOpen = false
                targetInsertIndex = null
            }
        )
    }

    preflightReport?.let { report ->
        ShortcutPreflightDialog(
            report = report,
            onDismiss = { preflightReport = null },
            onOpenSettings = { resolution ->
                runCatching { context.startActivity(resolution.settingsIntent(context)) }
                    .onFailure { scope.launch { snackbarHostState.showSnackbar("Android settings could not be opened for this requirement.") } }
            }
        )
    }
    pendingEditorRun?.let { pending ->
        EditorRunConfirmationDialog(
            effects = pending.effects,
            onDismiss = { pendingEditorRun = null },
            onConfirm = {
                pendingEditorRun = null
                scope.launch {
                    snackbarHostState.showSnackbar(
                        runEditorDraftThroughGateway(
                            context,
                            pending.shortcutName,
                            pending.actions,
                            externalEffectsConfirmed = true
                        ).userMessage()
                    )
                }
            }
        )
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

    fun insert(list: MutableList<Action>, index: Int, action: Action) {
        val safeIndex = index.coerceIn(0, list.size)
        list.add(safeIndex, action)
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
    onShowDisclosure: () -> Unit,
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
                "Recording uses the accessibility service to observe the steps you perform in other apps. " +
                    "Please review the disclosure and give consent before continuing.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onShowDisclosure, modifier = Modifier.fillMaxWidth()) {
                Text("Review disclosure")
            }
        } else {
            // CONSENT given but SERVICE_NOT_ENABLED
            Text(
                "Recording needs the accessibility service to observe the steps you perform in other apps.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "On a sideloaded app, Android may block the toggle as a Restricted setting. Allow it first in App info \u003e three dots \u003e Allow restricted settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onShowDisclosure, modifier = Modifier.fillMaxWidth()) {
                Text("Enable accessibility service")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
                Text("Open App info")
            }
        }
    }
}
