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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.shortcuts.app.ui.components.ReviewStepCard
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

// ============================================================================
// Screen entry point
// ============================================================================

private sealed interface ActionPickerTarget {
    data object Add : ActionPickerTarget
    data class FixUnresolved(val index: Int) : ActionPickerTarget
}

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
    var actionPickerTarget by remember { mutableStateOf<ActionPickerTarget?>(null) }
    var appPickerStepIndex by remember { mutableStateOf<Int?>(null) }
    val accessibilityOptedIn by AccessibilityAutomationOptIn.isAcknowledged(context)
        .collectAsState(initial = false)

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
        if (aiData.isSaved) onNavigateBack()
    }

    if (aiData.draft != null) {
        ReviewStepsScreen(
            aiData = aiData,
            prompt = prompt,
            onPromptChange = vm::updatePrompt,
            onGenerate = { vm.downloadModelAndGenerate(context) },
            onNavigateBack = {
                vm.clearError()
            },
            onSave = vm::saveGeneratedAutomation,
            onAppearanceChange = vm::updateAppearance,
            onTestRun = { vm.testRun(context) },
            onDelete = { idx -> vm.deleteStep(idx) },
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
            onNavigateBack = onNavigateBack,
            onPromptChange = vm::updatePrompt,
            onGenerate = { vm.downloadModelAndGenerate(context) },
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
                                text = if (isDownloading) "Downloading model..." else if (isGenerating) "Generating..." else "Add this step",
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
    onSave: () -> Unit,
    onAppearanceChange: (WidgetColorKey, WidgetIconKey) -> Unit,
    onTestRun: () -> Unit,
    onDelete: (Int) -> Unit,
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
            // Test run button — outlined, full width, 52dp height
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .border(
                            width = 1.5.dp,
                            color = palette.ink,
                            shape = RoundedCornerShape(26.dp)
                        )
                        .clickable(enabled = !aiData.isTestRunning) { onTestRun() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (aiData.isTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = palette.ink
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = palette.ink,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = if (aiData.isTestRunning) "Testing\u2026" else "Test run",
                            style = MaterialTheme.typography.bodyLarge, // 15sp SemiBold
                            color = palette.ink
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
                    // "Review steps" title
                    Text(
                        text = "Review steps",
                        style = MaterialTheme.typography.titleLarge, // 18sp Bold
                        color = palette.ink,
                        modifier = Modifier.weight(1f)
                    )
                    // Dark "Save" pill
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.ink)
                            .clickable { onSave() }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelLarge, // 14sp SemiBold
                            color = palette.ground
                        )
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
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
                ) {
                    items(WidgetIconKey.entries) { icon ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.ink.copy(alpha = 0.18f))
                                .then(if (icon == selectedIcon) Modifier.border(3.dp, palette.ink, CircleShape) else Modifier)
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
                ) {
                    items(WidgetColorKey.entries) { colorKey ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colorKey.composeColor)
                                .then(if (colorKey == selectedColor) Modifier.border(3.dp, palette.ink, CircleShape) else Modifier)
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
                    onDelete = { onDelete(index) },
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
