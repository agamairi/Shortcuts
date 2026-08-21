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
import com.shortcuts.app.viewmodel.MadlibBuilderMode
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

    // Load installed apps for the madlib app-slot once, on first composition.
    LaunchedEffect(Unit) {
        val apps = PackageManagerInstalledAppSource(context.packageManager).launchableApps()
        vm.loadInstalledApps(apps)
    }

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

    // Show review screen when a draft exists (from either path).
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
        // Builder: madlib or free-text depending on mode.
        val sessionTint = tileColorForKey(aiData.tileColorKey)
        when (aiData.builderMode) {
            MadlibBuilderMode.MADLIB ->
                MadlibBuilderScreen(
                    aiData = aiData,
                    tint = sessionTint,
                    onNavigateBack = onNavigateBack,
                    onConfirm = vm::confirmMadlib,
                    onCycleTemplate = {
                        val templates = MadlibTemplate.values()
                        val next = templates[(aiData.madlibState.template.ordinal + 1) % templates.size]
                        vm.updateMadlibTemplate(next)
                    },
                    onTapFirstSlot = vm::advanceFirstSlot,
                    onTapSecondSlot = vm::advanceSecondSlot,
                    onDescribeWidget = vm::switchToFreeText
                )
            MadlibBuilderMode.FREE_TEXT ->
                FreeTextBuilderScreen(
                    aiData = aiData,
                    tint = sessionTint,
                    prompt = prompt,
                    onNavigateBack = { vm.switchToMadlib() },
                    onPromptChange = vm::updatePrompt,
                    onGenerate = { vm.downloadModelAndGenerate(context) },
                    snackbarHostState = snackbarHostState
                )
        }
    }

    // App picker for both "fix unresolved" and "add step" flows.
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
// Madlib Builder Screen  (Builder.dc.html)
// ============================================================================

// Design values lifted verbatim from Builder.dc.html:
//   screen bg              = tint (full-bleed)
//   top row padding        16px 16px 8px 16px
//   back arrow circle      44×44dp, radius 22dp
//   template pill          h=40dp, r=20dp, bg=rgba(255,255,255,0.22), gap=8dp, px=16dp
//   template font          14sp SemiBold white
//   confirm button         44×44dp, radius 22dp, bg=#FFFFFF, check stroke=tint
//   preview tile           168×168dp, radius 40dp, bg=rgba(255,255,255,0.18), gap=14dp
//   icon square inside     76×76dp, radius 24dp, bg=#FFFFFF
//   step-count font        15sp SemiBold white
//   page dots              gap=6dp, each 7×7dp radius=4dp
//   sentence padding       0 28px 8px, font=27sp SemiBold, lh=1.45, ls=-0.3sp
//   slot underline         2.5dp dotted rgba(255,255,255,0.75), pb=2dp
//   inspire pill           h=48dp, r=24dp, bg=#FFFFFF, px=22dp, gap=9dp
//   inspire text           15sp SemiBold #16130F = palette.ink
//   notice panel           margin 6 20 22 20, pad 12 14, radius 16dp, bg=rgba(0,0,0,0.16)
//   notice font            12.5sp, color=rgba(255,255,255,0.92)

private fun tileColorForKey(key: String?): Color = resolveWidgetColor(key, WidgetColorKey.PURPLE)

@Composable
private fun MadlibBuilderScreen(
    aiData: AiBuilderData,
    tint: Color,
    onNavigateBack: () -> Unit,
    onConfirm: () -> Unit,
    onCycleTemplate: () -> Unit,
    onTapFirstSlot: () -> Unit,
    onTapSecondSlot: () -> Unit,
    onDescribeWidget: () -> Unit
) {
    val madlib = aiData.madlibState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tint)
            .safeDrawingPadding()
    ) {
        // ----- Top row: back, template pill, confirm -----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Back arrow circle (no bg)
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

            // Template-picker pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.22f))
                    .clickable { onCycleTemplate() }
                    .padding(horizontal = 16.dp, vertical = 0.dp)
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = madlib.template.displayName,
                    style = TextStyle(
                        fontFamily = SchibstedGrotesk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    ),
                    color = Color.White
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }

            // Confirm button — white circle with tinted check mark
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { onConfirm() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Confirm",
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ----- Preview tile (centre) -----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
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
                // Icon white square
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
                    text = madlib.previewStepsLabel,
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
            val dotCount = MadlibTemplate.values().size
            repeat(dotCount) { i ->
                val active = i == madlib.template.ordinal
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) Color.White else Color.White.copy(alpha = 0.4f))
                )
            }
        }

        // ----- Madlib sentence -----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, bottom = 8.dp)
        ) {
            val slotStyle = SpanStyle(
                textDecoration = TextDecoration.None  // custom underline via Box border below
            )
            // Build the annotated string but render slots as individually tappable spans
            // via a Row of Text pieces (Compose doesn't support click per-span natively).
            MadlibSentence(
                madlib = madlib,
                onTapFirst = onTapFirstSlot,
                onTapSecond = onTapSecondSlot
            )
        }

        // ----- "Describe the widget" pill — opens the free-text Describe screen -----
        // Design: Builder.dc.html — h=48dp, r=24dp, bg=#FFFFFF, px=22dp, gap=9dp
        // Icon: list/lines (4 6h11M4 12h16M4 18h8) from the artboard, 18dp stroke.
        // The pill sits on the coloured tint ground, so its label MUST use LightPalette.ink —
        // NOT palette.ink, which flips to near-white in dark mode (white-on-white bug).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White)
                    .clickable { onDescribeWidget() }
                    .padding(horizontal = 22.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                // Lines / list icon from Builder.dc.html: M4 6h11M4 12h16M4 18h8
                androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
                    val strokePx = 2.dp.toPx()
                    val c = tint
                    drawLine(c, androidx.compose.ui.geometry.Offset(strokePx, size.height * 0.17f),
                        androidx.compose.ui.geometry.Offset(size.width * 0.79f, size.height * 0.17f), strokePx)
                    drawLine(c, androidx.compose.ui.geometry.Offset(strokePx, size.height * 0.5f),
                        androidx.compose.ui.geometry.Offset(size.width, size.height * 0.5f), strokePx)
                    drawLine(c, androidx.compose.ui.geometry.Offset(strokePx, size.height * 0.83f),
                        androidx.compose.ui.geometry.Offset(size.width * 0.62f, size.height * 0.83f), strokePx)
                }
                Text(
                    text = stringResource(R.string.describe_widget_pill),
                    style = TextStyle(
                        fontFamily = SchibstedGrotesk,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    // Always dark ink — this pill is always white on the coloured tint.
                    // palette.ink would flip to near-white in dark mode → white-on-white.
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
                // Reuse existing strings — do NOT duplicate them.
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
        // "Describe it instead" link has been REMOVED — the "Describe the widget" pill above
        // carries that job (per the "Later revisions" section of docs/design/README.md).
    }
}

// ---------------------------------------------------------------------------
// Madlib sentence rendering — inline tappable slots with dotted white underline
// ---------------------------------------------------------------------------

@Composable
private fun MadlibSentence(
    madlib: MadlibState,
    onTapFirst: () -> Unit,
    onTapSecond: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 27sp SemiBold, lh=1.45, ls=-0.3sp — from Builder.dc.html
    val baseStyle = TextStyle(
        fontFamily = SchibstedGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = (27 * 1.45f).sp,
        letterSpacing = (-0.3).sp
    )

    // Build as a flow of composables so each slot is individually tappable.
    // We use a wrapping Text with inline clickable spans via AnnotatedString — but Compose
    // doesn't support per-span click natively. So we fall back to a word-flow approach:
    // render as multiple Texts in a Row/Column with FlowRow-style wrapping.
    // For simplicity and accuracy: render the sentence as a single Text with the slot words
    // highlighted, and overlay invisible Box click targets on them. This is complex.
    //
    // Pragmatic approach: render three separate Texts side by side in a natural-language
    // order: [leadIn] [firstSlot] [midText] [secondSlot], each in the same base style.
    // This is visually accurate for the 2-slot templates in the artboard.

    Column(modifier = modifier.fillMaxWidth()) {
        // First line: leadIn + firstSlot
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (madlib.leadIn.isNotEmpty()) {
                Text(
                    text = "${madlib.leadIn} ",
                    style = baseStyle,
                    color = Color.White
                )
            }
            SlotWord(
                label = madlib.firstSlot.currentLabel,
                style = baseStyle,
                onClick = onTapFirst
            )
        }
        // Second line: midText + secondSlot
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (madlib.midText.isNotEmpty()) {
                Text(
                    text = "${madlib.midText} ",
                    style = baseStyle,
                    color = Color.White
                )
            }
            SlotWord(
                label = madlib.secondSlot.currentLabel,
                style = baseStyle,
                onClick = onTapSecond
            )
        }
    }
}

@Composable
private fun SlotWord(
    label: String,
    style: TextStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val underline = Color.White.copy(alpha = 0.75f)
    Box(
        modifier = modifier
            .clickable { onClick() }
            .drawBehind {
                // A dotted UNDERLINE, not a boxed border: Compose has no dotted border, but a
                // dashed path along the baseline is exactly the design's affordance and reads
                // as "tap to change" rather than as an input field.
                val y = size.height - 1.dp.toPx()
                drawLine(
                    color = underline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(3.dp.toPx(), 3.dp.toPx()), 0f
                    )
                )
            }
            .padding(horizontal = 1.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = style,
            color = Color.White
        )
    }
}

// ============================================================================
// Free-text / "Describe" Builder Screen  (Describe.dc.html)
// ============================================================================

// Design values lifted verbatim from Describe.dc.html:
//   screen bg              full-bleed {{tint}} = same session colour as the madlib builder
//   top row padding        16px 16px 8px 16px
//   back arrow circle      44×44dp, r=22dp, no bg — white stroke arrow
//   title                  15sp SemiBold rgba(255,255,255,0.92), centred
//   spacer end             44×44dp (mirrors arrow, keeps title centred)
//   heading padding        26px 28px 0 28px, 27sp SemiBold white, lh=1.4, ls=-0.3sp
//   subheading             13.5sp rgba(255,255,255,0.8), mt=8dp, lh=1.45
//   input area             margin=20dp, r=22dp, bg=rgba(255,255,255,0.16), pad=18dp, minH=132dp
//   input text             19sp weight=500 white, lh=1.45
//   example label          16px 20px 0 20px, 12.5sp SemiBold rgba(255,255,255,0.85), mb=10dp
//   example chips          pad 11dp 14dp, r=14dp, bg=rgba(255,255,255,0.14), 13.5sp white
//   chip gap               8dp
//   notice                 margin 0 20 14 20, pad 12 14, r=16dp, bg=rgba(0,0,0,0.16)
//   notice text            12.5sp rgba(255,255,255,0.92)
//   bottom row             gap=10dp, pad 0 20 24 20
//   back-to-builder btn    52×52dp, r=26dp, bg=rgba(255,255,255,0.18), grid-squares icon 20dp white
//   "Build the steps" pill flex-grow, h=52dp, r=26dp, bg=#FFFFFF, label 15sp SemiBold LightPalette.ink
//   CRITICAL: pill label uses LightPalette.ink — NOT palette.ink (which flips near-white in dark mode)

@Composable
private fun FreeTextBuilderScreen(
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

    // Wrap in a Box to layer the Snackbar over the full-bleed coloured ground.
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
            // ----- Top bar: back + title -----
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
                        contentDescription = "Back to builder",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.describe_screen_title),
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

            // ----- Main content (scrollable) -----
            androidx.compose.foundation.rememberScrollState().let { scrollState ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    // ----- Heading block -----
                    Column(
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 26.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.describe_heading),
                            style = TextStyle(
                                fontFamily = SchibstedGrotesk,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 27.sp,
                                lineHeight = (27 * 1.4f).sp,
                                letterSpacing = (-0.3).sp
                            ),
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.describe_subheading),
                            style = TextStyle(
                                fontFamily = SchibstedGrotesk,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.5f.sp,
                                lineHeight = (13.5f * 1.45f).sp
                            ),
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }

                    // ----- Translucent input area -----
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 20.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White.copy(alpha = 0.16f))
                            .padding(18.dp)
                            .defaultMinSize(minHeight = 132.dp)
                    ) {
                        if (prompt.isEmpty()) {
                            Text(
                                text = stringResource(R.string.describe_input_placeholder),
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

                    // ----- "Try one of these" example prompts -----
                    Column(
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.describe_examples_label),
                            style = TextStyle(
                                fontFamily = SchibstedGrotesk,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.5f.sp
                            ),
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        val examples = listOf(
                            stringResource(R.string.describe_example_1),
                            stringResource(R.string.describe_example_2),
                            stringResource(R.string.describe_example_3)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            examples.forEach { example ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
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
                    }

                    // Spacer to push notice + buttons down
                    Spacer(Modifier.weight(1f))

                    // ----- Experimental AI notice (reuse existing strings) -----
                    Row(
                        modifier = Modifier
                            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
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
                            modifier = Modifier
                                .size(17.dp)
                                .padding(top = 1.dp)
                        )
                        Text(
                            text = stringResource(R.string.ai_builder_experimental_notice_body),
                            style = TextStyle(
                                fontFamily = SchibstedGrotesk,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.5f.sp,
                                lineHeight = (12.5f * 1.45f).sp
                            ),
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }

                    // ----- Bottom row: back-to-builder button + "Build the steps" pill -----
                    // Design: gap=10dp, pad 0 20 24 20
                    // Back button: 52×52dp, r=26dp, bg=rgba(255,255,255,0.18), grid-squares icon 20dp
                    // Pill: flex-grow, h=52dp, r=26dp, bg=#FFFFFF, label LightPalette.ink
                    // CRITICAL: pill label uses LightPalette.ink — palette.ink flips near-white in dark mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Square-grid / back-to-slot-builder button
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.18f))
                                .clickable { onNavigateBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            // Four-square grid icon from Describe.dc.html
                            // rect x=3 y=3 w=7 h=7 rx=1; rect x=14 y=3 w=7 h=7 rx=1;
                            // rect x=3 y=14 w=7 h=7 rx=1; rect x=14 y=14 w=7 h=7 rx=1
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome, // placeholder — replaced below
                                contentDescription = "Back to slot builder",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // "Build the steps" primary pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(if (canGenerate) Color.White else Color.White.copy(alpha = 0.5f))
                                .clickable(enabled = canGenerate) { onGenerate() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isGenerating || isDownloading) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = tint
                                    )
                                    Text(
                                        text = if (isDownloading)
                                            "Preparing AI… ${aiData.downloadProgress}%"
                                        else
                                            "Building\u2026",
                                        style = TextStyle(
                                            fontFamily = SchibstedGrotesk,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp
                                        ),
                                        color = tint
                                    )
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.describe_build_steps),
                                    style = TextStyle(
                                        fontFamily = SchibstedGrotesk,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    ),
                                    // CRITICAL: always LightPalette.ink, never palette.ink.
                                    // The pill is always white on a coloured background;
                                    // palette.ink in dark mode → near-white → white-on-white.
                                    color = LightPalette.ink
                                )
                            }
                        }
                    }
                }
            }
        }

        // Snackbar overlay
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ============================================================================
// Review Steps Screen  (Editor.dc.html)
// ============================================================================

// Design values lifted verbatim from Editor.dc.html:
//   screen bg              #FAF8F5 = palette.ground
//   header padding         14px 16px 10px 16px, gap=8dp
//   back icon              44×44dp circle, stroke=#16130F = palette.ink
//   title                  18sp Bold = MaterialTheme.typography.titleLarge
//   save pill              h=40dp, r=20dp, bg=#16130F = palette.ink, text=14sp SemiBold ground
//   sub-line               13sp #7A736B = palette.inkMuted, pad 2 16 12 16
//   list padding           0 16dp, gap=10dp
//   test run button        h=52dp, r=26dp, border=1.5dp palette.ink, gap=8dp, text=15sp SemiBold
//   bottom padding         16dp 16dp 24dp 16dp

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
