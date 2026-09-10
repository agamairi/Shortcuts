package com.shortcuts.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortcuts.app.R
import com.shortcuts.app.planner.DraftStep
import com.shortcuts.app.planner.InstalledApp
import com.shortcuts.app.planner.PackageManagerInstalledAppSource
import com.shortcuts.app.ui.components.AddStepCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.text.BasicTextField
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.ui.theme.TileColors
import com.shortcuts.app.util.ActionDescriber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.MoreVert
import com.shortcuts.app.service.StepResult
import com.shortcuts.app.service.ShortcutIssueCategory
import com.shortcuts.app.service.ShortcutRunner
import com.shortcuts.app.service.ShortcutPreflightIssue
import com.shortcuts.app.service.ShortcutPreflightReport
import com.shortcuts.app.service.ShortcutRunSafety
import com.shortcuts.app.service.settingsIntent
import com.shortcuts.app.service.userMessage
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.ui.theme.LightPalette
import com.shortcuts.app.ui.theme.LocalShortcutsPalette
import com.shortcuts.app.ui.theme.SchibstedGrotesk
import com.shortcuts.app.viewmodel.AiBuilderData
import com.shortcuts.app.viewmodel.AiBuilderViewModel
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey
import com.shortcuts.app.widget.resolveWidgetColor
import com.shortcuts.app.widget.resolveWidgetColorKey
import com.shortcuts.app.widget.resolveWidgetIconKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
// Screen entry point
// ============================================================================

private sealed interface ActionPickerTarget {
    data object Add : ActionPickerTarget
    data class FixUnresolved(val index: Int) : ActionPickerTarget
}

/** Leaving is destructive only after a generated draft or an active generation has begun. */
internal fun aiBuilderHasUnsavedChanges(
    data: AiBuilderData,
    generationRequested: Boolean = false
): Boolean = data.draft != null || data.isGenerating || data.downloadProgress != null || generationRequested

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
    val coroutineScope = rememberCoroutineScope()
    val aiData = (uiState as? UiState.Success)?.data ?: AiBuilderData(prompt = prompt)
    val errorMessage = (uiState as? UiState.Error)?.message
    var actionPickerTarget by remember { mutableStateOf<ActionPickerTarget?>(null) }
    var appPickerStepIndex by remember { mutableStateOf<Int?>(null) }
    var preflightReport by remember { mutableStateOf<ShortcutPreflightReport?>(null) }
    var pendingEditorRun by remember { mutableStateOf<PendingEditorRun?>(null) }
    var isEditorRunStarting by remember { mutableStateOf(false) }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    var generationRequested by remember { mutableStateOf(false) }
    val accessibilityOptedIn by AccessibilityAutomationOptIn.isAcknowledged(context)
        .collectAsState(initial = false)

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            generationRequested = false
            snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
            )
            vm.clearError()
        }
    }
    LaunchedEffect(aiData.isSaved) {
        if (aiData.isSaved) onNavigateBack()
    }

    val hasUnsavedChanges = aiBuilderHasUnsavedChanges(aiData, generationRequested)
    val handleBackPress = {
        if (hasUnsavedChanges) showDiscardConfirmation = true else onNavigateBack()
    }

    androidx.activity.compose.BackHandler(enabled = hasUnsavedChanges || showDiscardConfirmation) {
        if (showDiscardConfirmation) {
            showDiscardConfirmation = false
        } else {
            handleBackPress()
        }
    }

    if (showDiscardConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard draft?") },
            text = { Text("Your generated steps will be lost. Discard them, or keep editing?") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDiscardConfirmation = false
                    onNavigateBack()
                }) { Text("Discard") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") }
            }
        )
    }

    if (aiData.draft != null) {
        ReviewStepsScreen(
            aiData = aiData,
            prompt = prompt,
            onPromptChange = vm::updatePrompt,
            onGenerate = {
                generationRequested = true
                vm.downloadModelAndGenerate(context)
            },
            onNavigateBack = handleBackPress,
            onNameChange = vm::updateShortcutName,
            onSave = vm::saveGeneratedAutomation,
            onAppearanceChange = vm::updateAppearance,
            onCheck = {
                aiData.draft?.let { draft ->
                    val unresolvedIndex = draft.steps.indexOfFirst { it is DraftStep.Unresolved }
                    val actions = draft.steps.filterIsInstance<DraftStep.Resolved>().map { it.action }
                    coroutineScope.launch {
                        val report = withContext(Dispatchers.IO) {
                            ShortcutRunner(context.applicationContext).preflight(actions)
                        }
                        preflightReport = if (unresolvedIndex >= 0) {
                            report.copy(
                                issues = listOf(
                                    ShortcutPreflightIssue(
                                        actionIndex = unresolvedIndex,
                                        category = ShortcutIssueCategory.UNSUPPORTED_ACTION,
                                        userMessage = "Fix or remove this unresolved AI step before running the shortcut."
                                    )
                                ) + report.issues
                            )
                        } else {
                            report
                        }
                    }
                }
            },
            onRunNow = {
                aiData.draft?.let { draft ->
                    val unresolvedIndex = draft.steps.indexOfFirst { it is DraftStep.Unresolved }
                    val actions = draft.steps.filterIsInstance<DraftStep.Resolved>().map { it.action }
                    val name = aiData.shortcutName
                    coroutineScope.launch {
                        val report = withContext(Dispatchers.IO) {
                            ShortcutRunner(context.applicationContext).preflight(actions)
                        }
                        if (unresolvedIndex >= 0) {
                            preflightReport = report.copy(
                                issues = listOf(
                                    ShortcutPreflightIssue(
                                        actionIndex = unresolvedIndex,
                                        category = ShortcutIssueCategory.UNSUPPORTED_ACTION,
                                        userMessage = "Fix or remove this unresolved AI step before running the shortcut."
                                    )
                                ) + report.issues
                            )
                        } else if (!report.canRun) {
                            preflightReport = report
                        } else {
                            val effects = ShortcutRunSafety.confirmationEffects(actions)
                            if (effects.isEmpty()) {
                                isEditorRunStarting = true
                                try {
                                    snackbarHostState.showSnackbar(
                                        runEditorDraftThroughGateway(context, name, actions).userMessage()
                                    )
                                } finally {
                                    isEditorRunStarting = false
                                }
                            } else {
                                pendingEditorRun = PendingEditorRun(name, actions, effects)
                            }
                        }
                    }
                }
            },
            isRunStarting = isEditorRunStarting,
            onDelete = { idx -> vm.deleteStep(idx) },
            onMoveUp = { idx -> vm.moveStep(idx, idx - 1) },
            onMoveDown = { idx -> vm.moveStep(idx, idx + 1) },
            onChooseApp = { idx -> appPickerStepIndex = idx },
            onAddStep = { actionPickerTarget = ActionPickerTarget.Add },
            onFixUnresolved = { idx -> actionPickerTarget = ActionPickerTarget.FixUnresolved(idx) },
            onOpenSettings = { intent ->
                try { context.startActivity(intent) }
                catch (_: Exception) {
                    vm.triggerError("Android settings could not be opened for this permission.")
                }
            },
            snackbarHostState = snackbarHostState
        )
    } else {
        val sessionTint = resolveWidgetColor(aiData.tileColorKey, WidgetColorKey.PURPLE)
        InitialBuilderScreen(
            aiData = aiData,
            tint = sessionTint,
            prompt = prompt,
            onNavigateBack = handleBackPress,
            onPromptChange = vm::updatePrompt,
            onGenerate = {
                generationRequested = true
                vm.downloadModelAndGenerate(context)
            },
            snackbarHostState = snackbarHostState
        )
    }

    val installedApps = remember(context) {
        ManualBuilderUtils.getInstalledLaunchableApps(context)
    }

    actionPickerTarget?.let { target ->
        AddActionBottomSheet(
            actionTypes = ManualBuilderUtils.actionCatalog(accessibilityOptedIn),
            onActionTypeSelected = { type ->
                val action = ManualBuilderUtils.createDefaultAction(type)
                when (target) {
                    ActionPickerTarget.Add -> vm.addStep(action)
                    is ActionPickerTarget.FixUnresolved -> vm.replaceUnresolvedStep(target.index, action)
                }
                actionPickerTarget = null
            },
            onDismiss = { actionPickerTarget = null }
        )
    }

    appPickerStepIndex?.let { index ->
        AppPickerDialog(
            installedApps = installedApps,
            onAppSelected = { app ->
                val action = (aiData.draft?.steps?.getOrNull(index) as? DraftStep.Resolved)?.action
                if (action != null) vm.updateStep(index, action.copy(packageName = app.packageName))
                appPickerStepIndex = null
            },
            onDismiss = { appPickerStepIndex = null }
        )
    }

    preflightReport?.let { report ->
        ShortcutPreflightDialog(
            report = report,
            onDismiss = { preflightReport = null },
            onOpenSettings = { resolution ->
                runCatching { context.startActivity(resolution.settingsIntent(context)) }
                    .onFailure { coroutineScope.launch { snackbarHostState.showSnackbar("Android settings could not be opened for this requirement.") } }
            }
        )
    }
    pendingEditorRun?.let { pending ->
        EditorRunConfirmationDialog(
            effects = pending.effects,
            onDismiss = { pendingEditorRun = null },
            onConfirm = {
                pendingEditorRun = null
                isEditorRunStarting = true
                coroutineScope.launch {
                    try {
                        snackbarHostState.showSnackbar(
                            runEditorDraftThroughGateway(
                                context,
                                pending.shortcutName,
                                pending.actions,
                                externalEffectsConfirmed = true
                            ).userMessage()
                        )
                    } finally {
                        isEditorRunStarting = false
                    }
                }
            }
        )
    }
}

// ============================================================================
// Initial Builder Screen (Combined Madlib/Describe)
// ============================================================================

@Composable
private fun InitialBuilderScreen(
    aiData: AiBuilderData,
    tint: Color,
    prompt: String,
    onNavigateBack: () -> Unit,
    onPromptChange: (String) -> Unit,
    onGenerate: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val isGenerating = aiData.isGenerating
    val isDownloading = aiData.downloadProgress != null
    val canGenerate = prompt.isNotBlank() && !isDownloading && !isGenerating

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tint)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
        ) {
            // ----- Top bar: back + title (no save) -----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { onNavigateBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = "New shortcut",
                    style = TextStyle(
                        fontFamily = SchibstedGrotesk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = Color.White.copy(alpha = 0.92f)
                )
                // Invisible spacer to keep title visually centred
                Box(modifier = Modifier.size(44.dp))
            }

            androidx.compose.foundation.rememberScrollState().let { scrollState ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    // ----- Preview tile (centre) -----
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .size(168.dp)
                                .clip(RoundedCornerShape(40.dp))
                                .background(Color.White.copy(alpha = 0.18f)),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "No steps yet",
                                style = TextStyle(
                                    fontFamily = SchibstedGrotesk,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                ),
                                color = Color.White
                            )
                        }
                    }

                    // ----- Page dots -----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                    ) {
                        repeat(6) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.4f))
                            )
                        }
                    }

                    // ----- Real text input field -----
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 28.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(18.dp)
                            .defaultMinSize(minHeight = 80.dp)
                    ) {
                        if (prompt.isEmpty()) {
                            Text(
                                text = "e.g., Turn on Wi-Fi...",
                                style = TextStyle(
                                    fontFamily = SchibstedGrotesk,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 19.sp,
                                    lineHeight = (19 * 1.45f).sp
                                ),
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = prompt,
                            onValueChange = onPromptChange,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontFamily = SchibstedGrotesk,
                                fontWeight = FontWeight.Medium,
                                fontSize = 19.sp,
                                lineHeight = (19 * 1.45f).sp,
                                color = Color.White
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White)
                        )
                    }

                    // ----- Example chips -----
                    val examples = listOf("Turn on Wi-Fi", "Text Mum that I'm running late", "Send a POST request to ifttt.com", "Open Chrome")
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 12.dp),
                        contentPadding = PaddingValues(horizontal = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(examples) { example ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .clickable { onPromptChange(example) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp)
                            ) {
                                Text(
                                    text = example,
                                    style = TextStyle(
                                        fontFamily = SchibstedGrotesk,
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 13.5f.sp
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.weight(1f))

                    // ----- Generate button (White pill) -----
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp, top = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(if (canGenerate) Color.White else Color.White.copy(alpha = 0.5f))
                                .clickable(enabled = canGenerate) { onGenerate() }
                                .padding(horizontal = 22.dp)
                                .height(48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            if (isGenerating || isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = LightPalette.ink,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = LightPalette.ink,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = if (isDownloading) "Downloading model… ${aiData.downloadProgress}%" else if (isGenerating) "Generating..." else "Add this step",
                                style = TextStyle(
                                    fontFamily = SchibstedGrotesk,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                ),
                                color = LightPalette.ink
                            )
                        }
                    }

                    // ----- Experimental AI notice -----
                    Row(
                        modifier = Modifier
                            .padding(start = 20.dp, end = 20.dp, bottom = 22.dp, top = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.16f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(17.dp).padding(top = 1.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.ai_builder_experimental_notice_title),
                                style = TextStyle(
                                    fontFamily = SchibstedGrotesk,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5f.sp
                                ),
                                color = Color.White.copy(alpha = 0.92f)
                            )
                            Text(
                                text = "AI shortcuts are experimental and often get steps wrong. You\u2019ll review every step before it saves.",
                                style = TextStyle(
                                    fontFamily = SchibstedGrotesk,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 12.5f.sp,
                                    lineHeight = 18.sp
                                ),
                                color = Color.White.copy(alpha = 0.92f)
                            )
                        }
                    }
                }
            }
        }
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
@Composable
private fun ReviewStepsScreen(
    aiData: AiBuilderData,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onNavigateBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onAppearanceChange: (WidgetColorKey, WidgetIconKey) -> Unit,
    onCheck: () -> Unit,
    onRunNow: () -> Unit,
    isRunStarting: Boolean,
    onDelete: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onChooseApp: (Int) -> Unit,
    onAddStep: () -> Unit,
    onFixUnresolved: (Int) -> Unit,
    onOpenSettings: (Intent) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val palette = LocalShortcutsPalette.current
    val draft = aiData.draft ?: return
    val selectedColor = resolveWidgetColorKey(aiData.tileColorKey)
    val selectedIcon = resolveWidgetIconKey(aiData.tileIconKey)
    
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = palette.ground,
        bottomBar = {
            Column {
                if (aiData.isGenerating) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = palette.ink)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = onPromptChange,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Add another step...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = palette.ink,
                                unfocusedIndicatorColor = palette.ink.copy(alpha = 0.5f),
                                cursorColor = palette.ink,
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (prompt.isNotBlank()) palette.ink else palette.ink.copy(alpha = 0.3f))
                                .clickable(enabled = prompt.isNotBlank()) { onGenerate() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = "Generate",
                                tint = selectedColor.composeColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            // Check is side-effect free; Run now is intentionally explicit and uses ShortcutRunner.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .border(
                            width = 1.5.dp,
                            color = palette.ink,
                            shape = RoundedCornerShape(26.dp)
                        )
                        .clickable(enabled = !isRunStarting) { onCheck() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = palette.ink,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Check",
                            style = MaterialTheme.typography.bodyLarge, // 15sp SemiBold
                            color = palette.ink
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(palette.ink)
                        .clickable(enabled = !isRunStarting) { onRunNow() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isRunStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = palette.ground
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = palette.ground,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = if (isRunStarting) "Starting\u2026" else "Run now",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.ground
                        )
                    }
                }
        }
                }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // ----- Header -----
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Back arrow (no background)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable { onNavigateBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = palette.ink,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // Editable shortcut name
                    BasicTextField(
                        value = aiData.shortcutName,
                        onValueChange = { onNameChange(it) },
                        textStyle = MaterialTheme.typography.titleLarge.copy(color = palette.ink),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        cursorBrush = SolidColor(palette.ink),
                        decorationBox = { innerTextField ->
                            if (aiData.shortcutName.isEmpty()) {
                                Text(
                                    text = "Shortcut name",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = palette.ink.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        }
                    )
                    // Dark "Save" pill
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.ink)
                            .clickable(enabled = !aiData.isSaving) { onSave() }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (aiData.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = palette.ground, strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = "Save",
                                style = MaterialTheme.typography.labelLarge, // 14sp SemiBold
                                color = palette.ground
                            )
                        }
                    }
                }
            }

            // ----- Sub-line: original request -----
            item {
                Text(
                    text = "From \u201c${draft.originalPrompt}\u201d \u00b7 check each step",
                    style = MaterialTheme.typography.bodyMedium, // 13sp
                    color = palette.inkMuted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 2.dp)
                )
            }

            // Appearance pickers deliberately mirror the manual builder: horizontally
            // scrollable circular choices with a 3dp selection ring.
            item {
                Text(
                    "Icon & color",
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.ink
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
                        .semantics { contentDescription = "Choose shortcut icon" }
                ) {
                    items(WidgetIconKey.entries) { icon ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.ink.copy(alpha = 0.18f))
                                .then(if (icon == selectedIcon) Modifier.border(3.dp, palette.ink, CircleShape) else Modifier)
                                .semantics { contentDescription = "Shortcut icon: ${icon.name.lowercase()}" }
                                .clickable { onAppearanceChange(selectedColor, icon) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon.composeIcon, null, tint = palette.ink, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                        .semantics { contentDescription = "Choose shortcut color" }
                ) {
                    items(WidgetColorKey.entries) { colorKey ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colorKey.composeColor)
                                .then(if (colorKey == selectedColor) Modifier.border(3.dp, palette.ink, CircleShape) else Modifier)
                                .semantics { contentDescription = "Shortcut color: ${colorKey.name.lowercase()}" }
                                .clickable { onAppearanceChange(colorKey, selectedIcon) }
                        )
                    }
                }
            }

            // ----- Step cards -----
            itemsIndexed(draft.steps) { index, step ->
                ReviewStepCard(
                    index = index,
                    totalSteps = draft.steps.size,
                    step = step,
                    result = aiData.stepResults?.getOrNull(index),
                    onChooseApp = {
                        if (step is DraftStep.Unresolved) {
                            onFixUnresolved(index)
                        } else {
                            onChooseApp(index)
                        }
                    },
                    onEdit = { onFixUnresolved(index) },
                    onDelete = { onDelete(index) },
                    onMoveUp = { onMoveUp(index) },
                    onMoveDown = { onMoveDown(index) },
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                )
            }

            // ----- Add a step row -----
            item {
                AddStepCard(
                    onClick = onAddStep,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                )
            }
        }
    }
}


// ---------------------------------------------------------------------------
// Inlined ReviewStepCard with working controls
// ---------------------------------------------------------------------------

private val CardShape = RoundedCornerShape(20.dp)
private val ChipShape = RoundedCornerShape(13.dp)
private val WarnPanelShape = RoundedCornerShape(13.dp)
private val DeleteBtnShape = RoundedCornerShape(14.dp)
private val FixBtnShape = RoundedCornerShape(14.dp)

@Composable
fun ReviewStepCard(
    index: Int,
    totalSteps: Int,
    step: DraftStep,
    result: StepResult?,
    onChooseApp: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOpenSettings: (Intent) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalShortcutsPalette.current
    when (step) {
        is DraftStep.Resolved -> ResolvedReviewCard(
            index = index,
            totalSteps = totalSteps,
            step = step,
            onChooseApp = onChooseApp,
            onEdit = onEdit,
            onDelete = onDelete,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            result = result,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
        is DraftStep.Unresolved -> UnresolvedReviewCard(
            step = step,
            onChooseApp = onChooseApp,
            onDelete = onDelete,
            modifier = modifier
        )
    }
}

@Composable
private fun ResolvedReviewCard(
    index: Int,
    totalSteps: Int,
    step: DraftStep.Resolved,
    onChooseApp: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    result: StepResult?,
    onOpenSettings: (Intent) -> Unit,
    modifier: Modifier
) {
    val palette = LocalShortcutsPalette.current
    val chipColor = chipColorForAction(step.action)
    val restrictionNote = restrictedToggleNote(step.action)
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(palette.surface)
            .border(width = 1.dp, color = palette.outline, shape = CardShape)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(ChipShape)
                    .background(chipColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconForAction(step.action),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ActionDescriber.describe(step.action),
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "from \u201c${step.sourceText}\u201d",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkFaint
                )
            }
            Spacer(Modifier.width(4.dp))
            Box {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More options",
                    tint = palette.inkMuted,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { menuExpanded = true }
                )
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit step") },
                        onClick = { menuExpanded = false; onEdit() }
                    )
                    if (index > 0) {
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            onClick = { menuExpanded = false; onMoveUp() }
                        )
                    }
                    if (index < totalSteps - 1) {
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            onClick = { menuExpanded = false; onMoveDown() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }

        if (restrictionNote != null) {
            Spacer(Modifier.height(11.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.outline)
            )
            Spacer(Modifier.height(11.dp))
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = palette.inkFaint,
                    modifier = Modifier.size(15.dp).padding(top = 1.dp)
                )
                Text(
                    text = restrictionNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkMuted
                )
            }
        }
    }
}

@Composable
private fun UnresolvedReviewCard(
    step: DraftStep.Unresolved,
    onChooseApp: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier
) {
    val palette = LocalShortcutsPalette.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(palette.surface)
            .border(width = 1.5.dp, color = palette.warn, shape = CardShape)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(ChipShape)
                    .background(palette.surfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = palette.inkFaint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Couldn\u2019t work this out",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "from \u201c${step.sourceText}\u201d",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkFaint
                )
            }
        }

        Spacer(Modifier.height(11.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WarnPanelShape)
                .background(palette.warnGround)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = step.reason,
                style = MaterialTheme.typography.bodySmall,
                color = palette.warnInk
            )
        }

        Spacer(Modifier.height(11.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(FixBtnShape)
                    .background(palette.ink)
                    .clickable { onChooseApp() },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = null,
                        tint = palette.ground,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Choose an app",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.ground
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(DeleteBtnShape)
                    .border(width = 1.dp, color = palette.outline, shape = DeleteBtnShape)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete step",
                    tint = palette.danger,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun chipColorForAction(action: Action): Color = when (action.actionType) {
    ActionType.APP_INTENT     -> TileColors.Blue
    ActionType.SYSTEM_TOGGLE  -> TileColors.Teal
    ActionType.HTTP_REQUEST   -> TileColors.Orange
    ActionType.SEND_MESSAGE   -> TileColors.Green
    ActionType.DIAL_NUMBER    -> TileColors.Green
    ActionType.UI_AUTOMATION  -> TileColors.Purple
    ActionType.WAIT           -> TileColors.BlueGrey
}

@Composable
private fun iconForAction(action: Action): androidx.compose.ui.graphics.vector.ImageVector =
    when (action.actionType) {
        ActionType.APP_INTENT    -> Icons.Filled.Apps
        ActionType.SYSTEM_TOGGLE -> Icons.Filled.MoreVert
        ActionType.HTTP_REQUEST  -> Icons.Filled.Info
        ActionType.SEND_MESSAGE  -> Icons.Filled.Add
        ActionType.DIAL_NUMBER   -> Icons.Filled.Add
        ActionType.UI_AUTOMATION -> Icons.Filled.Add
        ActionType.WAIT          -> Icons.Filled.Info
    }

private fun restrictedToggleNote(action: Action): String? {
    if (action.actionType != ActionType.SYSTEM_TOGGLE) return null
    return when (action.target?.trim()?.lowercase()?.replace("_", "")) {
        "wifi"        -> "Android won\u2019t let apps switch Wi\u2011Fi on directly \u2014 this opens the Wi\u2011Fi panel for you."
        "bluetooth"   -> "Android requires you to confirm Bluetooth changes in its system prompt."
        "airplanemode" -> "Android doesn\u2019t allow apps to change Airplane mode \u2014 this opens its settings instead."
        "location"    -> "Android doesn\u2019t allow apps to change Location directly \u2014 this opens Location settings instead."
        else          -> null
    }
}
