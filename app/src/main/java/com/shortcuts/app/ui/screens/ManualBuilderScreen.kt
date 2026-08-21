package com.shortcuts.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.shortcuts.app.widget.WidgetIconKey

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.util.AccessibilityStatusChecker
import com.shortcuts.app.util.ActionDescriber
import com.shortcuts.app.viewmodel.AutomationViewModel
import com.shortcuts.app.planner.PackageManagerInstalledAppSource
import com.shortcuts.app.ui.theme.LocalShortcutsPalette
import com.shortcuts.app.ui.theme.TileColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map

private val Context.accessibilityOptInDataStore by preferencesDataStore(name = "accessibility_opt_in")

/** Persisted acknowledgement required before UI-automation actions enter either action catalog. */
object AccessibilityAutomationOptIn {
    private val acknowledgedKey = booleanPreferencesKey("user_acknowledged_ui_automation")

    fun isAcknowledged(context: Context) = context.accessibilityOptInDataStore.data
        .map { preferences -> preferences[acknowledgedKey] ?: false }

    suspend fun acknowledge(context: Context) {
        context.accessibilityOptInDataStore.edit { it[acknowledgedKey] = true }
    }
}

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val iconDrawable: Drawable? = null
)

/** Sentinel meaning "the builder is creating a new shortcut", not editing an existing one. */
const val NEW_SHORTCUT_ID: Int = -1

data class ActionTypeMetadata(
    val type: ActionType,
    val title: String,
    val description: String,
    val icon: ImageVector
)

object ManualBuilderUtils {
    val ACTION_TYPE_METADATA = listOf(
        ActionTypeMetadata(
            type = ActionType.APP_INTENT,
            title = "Open an App",
            description = "Launch another app, like Alexa or a lighting app",
            icon = Icons.Filled.Apps
        ),
        ActionTypeMetadata(
            type = ActionType.UI_AUTOMATION,
            title = "Tap or Type on Screen",
            description = "Interact with whatever app is currently open",
            icon = Icons.Filled.TouchApp
        ),
        ActionTypeMetadata(
            type = ActionType.SYSTEM_TOGGLE,
            title = "Toggle a Setting",
            description = "Quickly switch a system setting like Wi-Fi",
            icon = Icons.Filled.ToggleOn
        ),
        ActionTypeMetadata(
            type = ActionType.WAIT,
            title = "Wait",
            description = "Pause before the next step, to let a screen finish loading",
            icon = Icons.Filled.Timer
        ),
        ActionTypeMetadata(
            type = ActionType.HTTP_REQUEST,
            title = "Web Request",
            description = "Send data to a webhook — a smart-home scene, IFTTT, or your own server",
            icon = Icons.Filled.Language
        ),
        ActionTypeMetadata(
            type = ActionType.SEND_MESSAGE,
            title = "Send a Message",
            description = "Open your SMS app with a message ready to send",
            icon = Icons.Filled.Sms
        ),
        ActionTypeMetadata(
            type = ActionType.DIAL_NUMBER,
            title = "Call",
            description = "Open the dialer with a number ready to call",
            icon = Icons.Filled.Call
        )
    )

    /** UI automation stays out of the catalog until the dedicated disclosure is acknowledged. */
    fun actionCatalog(accessibilityOptedIn: Boolean): List<ActionTypeMetadata> =
        ACTION_TYPE_METADATA.filter { it.type != ActionType.UI_AUTOMATION || accessibilityOptedIn }

    fun getMetadataForType(type: ActionType): ActionTypeMetadata {
        return ACTION_TYPE_METADATA.first { it.type == type }
    }

    fun createDefaultAction(type: ActionType): Action {
        return when (type) {
            ActionType.APP_INTENT -> Action(actionType = ActionType.APP_INTENT, packageName = "")
            ActionType.UI_AUTOMATION -> Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "")
            ActionType.SYSTEM_TOGGLE -> Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI", state = "TOGGLE")
            ActionType.HTTP_REQUEST -> Action(actionType = ActionType.HTTP_REQUEST, url = "", method = "GET")
            ActionType.WAIT -> Action(actionType = ActionType.WAIT, delayMillis = 3_000L)
            ActionType.SEND_MESSAGE -> Action(actionType = ActionType.SEND_MESSAGE, target = "", textInput = "")
            ActionType.DIAL_NUMBER -> Action(actionType = ActionType.DIAL_NUMBER, target = "")
        }
    }

    fun getInstalledLaunchableApps(context: Context): List<InstalledAppInfo> {
        return try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            resolveInfos.mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val label = appInfo.loadLabel(pm).toString()
                val pkg = appInfo.packageName
                val icon = try { appInfo.loadIcon(pm) } catch (e: Exception) { null }
                InstalledAppInfo(label = label, packageName = pkg, iconDrawable = icon)
            }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun filterAndSortApps(apps: List<InstalledAppInfo>, query: String): List<InstalledAppInfo> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return apps.sortedBy { it.label.lowercase() }
        return apps.filter {
            it.label.contains(trimmed, ignoreCase = true) ||
            it.packageName.contains(trimmed, ignoreCase = true)
        }.sortedBy { it.label.lowercase() }
    }

    fun getAppLabel(apps: List<InstalledAppInfo>, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return apps.find { it.packageName == packageName }?.label
    }

    /** Pure persistence shape used by the manual sentence builder and its JVM tests. */
    fun buildManualAutomation(name: String, actions: List<Action>, colorKey: String, iconKey: String = "BOLT"): Automation {
        return Automation(
            name = name.trim(),
            actionsJson = ActionConverter().fromActionList(actions),
            triggerType = "MANUAL",
            colorKey = colorKey,
            iconKey = iconKey
        )
    }

    fun getActionSummary(action: Action, appLabelLookup: (String) -> String? = { null }): String {
        return when (action.actionType) {
            ActionType.APP_INTENT -> {
                val pkg = action.packageName
                if (!pkg.isNullOrBlank()) {
                    val label = appLabelLookup(pkg) ?: pkg
                    "Open $label"
                } else {
                    "Open an App"
                }
            }
            ActionType.UI_AUTOMATION -> {
                val target = action.targetText?.ifBlank { null }
                    ?: action.targetNodeId?.ifBlank { null }
                    ?: action.target?.ifBlank { null }
                val isTypeMode = action.uiActionType?.trim()?.uppercase() == "TYPE_TEXT"
                when {
                    isTypeMode && !action.textInput.isNullOrBlank() && target != null ->
                        "Type '${action.textInput}' into '$target'"
                    isTypeMode && !action.textInput.isNullOrBlank() ->
                        "Type '${action.textInput}'"
                    target != null -> "Tap '$target'"
                    else -> "Tap or Type on Screen"
                }
            }
            ActionType.SYSTEM_TOGGLE -> {
                val rawTarget = action.target?.ifBlank { null } ?: "Setting"
                val formattedTarget = when (rawTarget.uppercase()) {
                    "WIFI" -> "Wi-Fi"
                    "BLUETOOTH" -> "Bluetooth"
                    else -> rawTarget
                }
                val state = action.state?.ifBlank { null } ?: ""
                if (state.isNotEmpty()) {
                    "Toggle $formattedTarget $state"
                } else {
                    "Toggle $formattedTarget"
                }
            }
            ActionType.HTTP_REQUEST -> {
                val url = action.url?.ifBlank { null }
                if (url != null) {
                    "Web request to $url"
                } else {
                    "Web Request"
                }
            }
            ActionType.WAIT -> "Wait ${ActionDescriber.formatWaitDuration(action.delayMillis)}"
            ActionType.SEND_MESSAGE -> action.target?.takeIf { it.isNotBlank() }
                ?.let { "Message $it" } ?: "Send a Message"
            ActionType.DIAL_NUMBER -> action.target?.takeIf { it.isNotBlank() }
                ?.let { "Call $it" } ?: "Call"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualBuilderScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (() -> Unit)? = null,
    viewModel: AutomationViewModel? = null,
    onSaveAutomation: ((Automation) -> Unit)? = null,
    /** Id of the shortcut being edited, or [NEW_SHORTCUT_ID] when building a new one. */
    editingAutomationId: Int = NEW_SHORTCUT_ID
) {
    val context = LocalContext.current
    val palette = LocalShortcutsPalette.current
    var shortcutName by remember { mutableStateOf("New shortcut") }
    var selectedColor by remember { mutableStateOf(TileColors.Teal) }
    val actions = remember {
        mutableStateListOf(
            Action(actionType = ActionType.SYSTEM_TOGGLE, target = "flashlight", state = "on"),
            Action(actionType = ActionType.APP_INTENT, packageName = null)
        )
    }
    var isSaving by remember { mutableStateOf(false) }
    var nameEditorOpen by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<ManualSlotPicker?>(null) }
    val installedApps = remember(context) {
        PackageManagerInstalledAppSource(context.packageManager).launchableApps()
            .map { InstalledAppInfo(it.userVisibleLabel, it.packageName) }
            .sortedBy { it.label.lowercase() }
    }

    
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    
    val accessibilityAcknowledged by AccessibilityAutomationOptIn.isAcknowledged(context).collectAsState(initial = false)
    var isActionPickerOpen by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    var selectedIcon by remember { mutableStateOf(WidgetIconKey.BOLT) }

    // Edit mode: replace the starter steps with the saved shortcut's own. Keyed on the id so
    // re-entering the builder for a different shortcut reloads rather than showing the last one.
    LaunchedEffect(editingAutomationId) {
        if (editingAutomationId == NEW_SHORTCUT_ID || viewModel == null) return@LaunchedEffect
        val existing = viewModel.getAutomationById(editingAutomationId) ?: return@LaunchedEffect
        shortcutName = existing.name
        existing.colorKey
            ?.let { key -> com.shortcuts.app.widget.WidgetColorKey.entries.firstOrNull { it.name == key } }
            ?.let { selectedColor = it.composeColor }
        existing.iconKey
            ?.let { key -> WidgetIconKey.entries.firstOrNull { it.name == key } }
            ?.let { selectedIcon = it }
        val saved = runCatching { ActionConverter().toActionList(existing.actionsJson) }
            .getOrDefault(emptyList())
        if (saved.isNotEmpty()) {
            actions.clear()
            actions.addAll(saved)
        }
    }

    if (showDisclosure) {
        AccessibilityDisclosureScreen {
            showDisclosure = false
        }
        return
    }


    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = selectedColor
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onNavigateBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ArrowBack, "Back", tint = palette.tileContent, modifier = Modifier.size(22.dp))
                }
                Box(
                    Modifier.height(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                        .background(palette.tileContent.copy(alpha = .22f)).clickable { nameEditorOpen = true }
                        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(shortcutName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                        Icon(Icons.Filled.Edit, "Edit shortcut name", tint = palette.tileContent, modifier = Modifier.size(13.dp))
                    }
                }
                Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(palette.tileContent).clickable {
                    saveManualAutomation(context, shortcutName, actions.toList(), selectedColor, selectedIcon.name, viewModel, onSaveAutomation, onNavigateBack, snackbarHostState, coroutineScope, editingAutomationId) { isSaving = it }
                }, contentAlignment = Alignment.Center) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), color = selectedColor) else Icon(Icons.Filled.Check, "Save shortcut", tint = selectedColor, modifier = Modifier.size(20.dp))
                }
            }

            Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.size(108.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(30.dp)).background(palette.tileContent.copy(alpha = .18f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(17.dp)).background(palette.tileContent), contentAlignment = Alignment.Center) {
                        Icon(selectedIcon.composeIcon, null, tint = selectedColor, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${actions.size} ${if (actions.size == 1) "step" else "steps"}", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                }
            }

            Column(Modifier.weight(1f).padding(start = 24.dp, end = 24.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                actions.forEachIndexed { index, action ->
                    ManualSentenceRow(
                        index = index,
                        totalActions = actions.size,
                        action = action,
                        installedApps = installedApps,
                        onOpenPicker = { picker = it },
                        onMoveUp = { if (index > 0) { val act = actions.removeAt(index); actions.add(index - 1, act) } },
                        onMoveDown = { if (index < actions.size - 1) { val act = actions.removeAt(index); actions.add(index + 1, act) } },
                        onRemove = { if (actions.size > 1) actions.removeAt(index) }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clickable { isActionPickerOpen = true }) {
                    Spacer(Modifier.width(22.dp))
                    Row(
                        Modifier
                            .height(44.dp)
                            .drawBehind {
                                val strokeWidth = 1.5.dp.toPx()
                                drawRoundRect(
                                    color = palette.tileContent.copy(alpha = .55f),
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
                        Icon(Icons.Filled.Add, null, tint = palette.tileContent, modifier = Modifier.size(16.dp))
                        Text("Add a step", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    }
                }
            }

            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
            ) {
                items(WidgetIconKey.entries) { icon ->
                    Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(palette.tileContent.copy(alpha=0.18f)).then(if (icon == selectedIcon) Modifier.border(3.dp, palette.tileContent, androidx.compose.foundation.shape.CircleShape) else Modifier).clickable { selectedIcon = icon }, contentAlignment = Alignment.Center) {
                        Icon(icon.composeIcon, null, tint = palette.tileContent, modifier = Modifier.size(20.dp))
                    }
                }
            }
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
            ) {
                items(com.shortcuts.app.widget.WidgetColorKey.entries.toTypedArray()) { colorKey ->
                    val color = colorKey.composeColor
                    Box(Modifier.size(38.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color).then(if (color == selectedColor) Modifier.border(3.dp, palette.tileContent, androidx.compose.foundation.shape.CircleShape) else Modifier).clickable { selectedColor = color })
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth().height(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp)).border(1.5.dp, palette.tileContent.copy(alpha = .6f), androidx.compose.foundation.shape.RoundedCornerShape(26.dp)).clickable {
                    coroutineScope.launch {
                        // executeActions blocks — a Wait step sleeps the calling thread — so it must
                        // never run on the composition's Main scope, or Test Run would ANR the app.
                        val result = withContext(Dispatchers.IO) {
                            com.shortcuts.app.service.ActionExecutorService(context).executeActions(actions.toList(), shortcutName)
                        }
                        val firstIncompleteIdx = result.steps.indexOfFirst { it !is com.shortcuts.app.service.StepResult.Success }
                        if (firstIncompleteIdx == -1) {
                            snackbarHostState.showSnackbar("All ${actions.size} steps completed successfully!")
                        } else {
                            val failedStep = result.steps[firstIncompleteIdx]
                            val action = actions[firstIncompleteIdx]
                            val summary = ManualBuilderUtils.getActionSummary(action) { ManualBuilderUtils.getAppLabel(installedApps, it) }
                            
                            when (failedStep) {
                                is com.shortcuts.app.service.StepResult.Failed -> {
                                    snackbarHostState.showSnackbar("Step ${firstIncompleteIdx + 1} ($summary) failed: ${failedStep.userMessage}")
                                }
                                is com.shortcuts.app.service.StepResult.NeedsPermission -> {
                                    val snackbarResult = snackbarHostState.showSnackbar(
                                        message = "Step ${firstIncompleteIdx + 1} ($summary) needs ${failedStep.permission} permission",
                                        actionLabel = "Grant",
                                        duration = SnackbarDuration.Long
                                    )
                                    if (snackbarResult == SnackbarResult.ActionPerformed && failedStep.settingsIntent != null) {
                                        context.startActivity(failedStep.settingsIntent)
                                    }
                                }
                                is com.shortcuts.app.service.StepResult.Skipped -> {
                                    snackbarHostState.showSnackbar("Step ${firstIncompleteIdx + 1} ($summary) was skipped: ${failedStep.why}")
                                }
                                else -> {}
                            }
                        }
                    }
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.PlayArrow, null, tint = palette.tileContent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp)); Text("Test run", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                }
            }
        }
    }
    if (nameEditorOpen) AlertDialog(onDismissRequest = { nameEditorOpen = false }, title = { Text("Shortcut name") }, text = { OutlinedTextField(shortcutName, { shortcutName = it }, singleLine = true) }, confirmButton = { TextButton({ nameEditorOpen = false }) { Text("Done") } })
    if (isActionPickerOpen) {
        AddActionBottomSheet(
            actionTypes = ManualBuilderUtils.ACTION_TYPE_METADATA,
            onActionTypeSelected = { type ->
                if (type == ActionType.UI_AUTOMATION && !accessibilityAcknowledged) {
                    showDisclosure = true
                } else {
                    actions += ManualBuilderUtils.createDefaultAction(type)
                }
            },
            onDismiss = { isActionPickerOpen = false }
        )
    }

    var textInputState by remember { mutableStateOf("") }
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
                        if (index in actions.indices) {
                            val action = actions[index]
                            actions[index] = if (currentPicker.isSecondaryText) {
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
                if (index in actions.indices) {
                    val action = actions[index]
                    actions[index] = when (currentPicker.kind) {
                        ManualSlotKind.STATE -> action.copy(state = picked)
                        ManualSlotKind.CONTROL -> action.copy(target = picked)
                        ManualSlotKind.APP -> action.copy(packageName = picked)
                        ManualSlotKind.HTTP_METHOD -> action.copy(method = picked)
                        ManualSlotKind.UI_ACTION_TYPE -> action.copy(uiActionType = picked)
                        ManualSlotKind.DURATION -> action.copy(delayMillis = picked.toLongOrNull()?.times(1000))
                        else -> action
                    }
                }
                picker = null
            }
        }
    }
}

enum class ManualSlotKind { STATE, CONTROL, APP, HTTP_METHOD, UI_ACTION_TYPE, DURATION, TEXT }
data class ManualSlotPicker(val stepIndex: Int, val kind: ManualSlotKind, val label: String = "", val initialValue: String = "", val isSecondaryText: Boolean = false)

@Composable fun ManualSentenceRow(index: Int, totalActions: Int, action: Action, installedApps: List<InstalledAppInfo>, onOpenPicker: (ManualSlotPicker) -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onRemove: () -> Unit) {
    val palette = LocalShortcutsPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text("${index + 1}", modifier = Modifier.width(22.dp).padding(top = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.tileContent.copy(alpha = .6f))
        
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            when (action.actionType) {
                ActionType.SYSTEM_TOGGLE -> {
                    Text("Turn ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(action.state?.lowercase() ?: "on") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.STATE)) }
                    Text(" the ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(SUPPORTED_DEVICE_CONTROLS.firstOrNull { it.id == action.target }?.displayLabel ?: "flashlight") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.CONTROL)) }
                }
                ActionType.WAIT -> {
                    Text("Wait for ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(ActionDescriber.formatWaitDuration(action.delayMillis)) { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.DURATION)) }
                }
                ActionType.APP_INTENT -> {
                    Text("Open ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(ManualBuilderUtils.getAppLabel(installedApps, action.packageName) ?: "an app") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.APP)) }
                }
                ActionType.HTTP_REQUEST -> {
                    Text("Send a ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(action.method ?: "GET") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.HTTP_METHOD)) }
                    Text(" request to ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(action.url?.takeIf { it.isNotBlank() } ?: "url") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.TEXT, "URL", action.url ?: "")) }
                }
                ActionType.SEND_MESSAGE -> {
                    Text("Text ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(action.target?.takeIf { it.isNotBlank() } ?: "contact") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.TEXT, "Phone number", action.target ?: "")) }
                    Text(" saying ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(action.textInput?.takeIf { it.isNotBlank() } ?: "message") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.TEXT, "Message", action.textInput ?: "", true)) }
                }
                ActionType.DIAL_NUMBER -> {
                    Text("Call ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    DottedSlot(action.target?.takeIf { it.isNotBlank() } ?: "number") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.TEXT, "Phone number", action.target ?: "")) }
                }
                ActionType.UI_AUTOMATION -> {
                    val isTypeMode = action.uiActionType?.trim()?.uppercase() == "TYPE_TEXT"
                    DottedSlot(if (isTypeMode) "Type" else "Tap") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.UI_ACTION_TYPE)) }
                    Text(" ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    if (isTypeMode) {
                        DottedSlot(action.textInput?.takeIf { it.isNotBlank() } ?: "text") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.TEXT, "Text to type", action.textInput ?: "", true)) }
                        Text(" into ", fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
                    }
                    DottedSlot(action.targetText?.takeIf { it.isNotBlank() } ?: "target") { onOpenPicker(ManualSlotPicker(index, ManualSlotKind.TEXT, "Target text", action.targetText ?: "")) }
                }
            }
        }
        var menuExpanded by remember { mutableStateOf(false) }
        Box {
            Icon(Icons.Filled.MoreVert, "More options", tint = palette.tileContent.copy(alpha = .65f), modifier = Modifier.size(26.dp).padding(top = 4.dp).clickable { menuExpanded = true })
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Move up") }, onClick = { menuExpanded = false; onMoveUp() }, enabled = index > 0)
                DropdownMenuItem(text = { Text("Move down") }, onClick = { menuExpanded = false; onMoveDown() }, enabled = index < totalActions - 1)
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menuExpanded = false; onRemove() })
            }
        }
    }
}

@Composable fun DottedSlot(text: String, onClick: () -> Unit) {
    val palette = LocalShortcutsPalette.current
    Text(text, modifier = Modifier.clickable(onClick = onClick).drawBehind { drawLine(palette.tileContent.copy(alpha = .75f), Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), strokeWidth = 2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()), 0f)) }, fontSize = 21.sp, lineHeight = 31.5.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, color = palette.tileContent)
}

@Composable fun ManualSlotPickerDialog(picker: ManualSlotPicker, apps: List<InstalledAppInfo>, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val choices = when (picker.kind) { 
        ManualSlotKind.STATE -> ToggleStateOption.entries.map { it.value }
        ManualSlotKind.CONTROL -> SUPPORTED_DEVICE_CONTROLS.map { it.id }
        ManualSlotKind.APP -> apps.map { it.packageName }
        ManualSlotKind.HTTP_METHOD -> listOf("GET", "POST", "PUT", "DELETE")
        ManualSlotKind.UI_ACTION_TYPE -> listOf("TAP", "TYPE_TEXT")
        ManualSlotKind.DURATION -> listOf("1", "2", "3", "5", "10", "15", "30", "60")
        else -> emptyList()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { 
        Text(when (picker.kind) { 
            ManualSlotKind.STATE -> "Choose a state"
            ManualSlotKind.CONTROL -> "Choose a device control"
            ManualSlotKind.APP -> "Choose an app"
            ManualSlotKind.HTTP_METHOD -> "Choose HTTP method"
            ManualSlotKind.UI_ACTION_TYPE -> "Choose action type"
            ManualSlotKind.DURATION -> "How long should this shortcut wait?"
            else -> ""
        }) 
    }, text = { 
        LazyColumn { 
            items(choices) { value -> 
                Text(
                    value.let { raw -> 
                        when (picker.kind) { 
                            ManualSlotKind.CONTROL -> SUPPORTED_DEVICE_CONTROLS.first { it.id == raw }.displayLabel
                            ManualSlotKind.APP -> apps.first { it.packageName == raw }.label
                            ManualSlotKind.STATE -> raw 
                            ManualSlotKind.HTTP_METHOD -> raw
                            ManualSlotKind.UI_ACTION_TYPE -> if (raw == "TAP") "Tap" else "Type"
                            ManualSlotKind.DURATION -> ActionDescriber.formatWaitDuration(raw.toLongOrNull()?.times(1000))
                            else -> raw
                        } 
                    }, 
                    modifier = Modifier.fillMaxWidth().clickable { onPicked(value) }.padding(vertical = 14.dp)
                ) 
            } 
        } 
    }, confirmButton = {})
}

private fun saveManualAutomation(context: Context, name: String, actions: List<Action>, color: androidx.compose.ui.graphics.Color, iconKey: String, viewModel: AutomationViewModel?, onSave: ((Automation) -> Unit)?, onBack: () -> Unit, snackbar: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope, editingAutomationId: Int = NEW_SHORTCUT_ID, setSaving: (Boolean) -> Unit) {
    if (name.isBlank() || actions.isEmpty()) { scope.launch { snackbar.showSnackbar(if (name.isBlank()) "Shortcut name cannot be empty" else "Please add at least one step") }; return }
    setSaving(true)
    val safe = actions.map { com.shortcuts.app.service.ActionExecutorService.prepareActionForPersistence(context, it) }
    if (safe.any { it == null }) { setSaving(false); scope.launch { snackbar.showSnackbar("The web token couldn't be secured. Your shortcut was not saved.") }; return }
    val colorKey = com.shortcuts.app.widget.WidgetColorKey.entries.firstOrNull { it.composeColor == color }?.name ?: "TEAL"
    val built = ManualBuilderUtils.buildManualAutomation(name, safe.filterNotNull(), colorKey, iconKey)
    // Carrying the id over turns the Room insert into a replace, so editing updates the existing
    // shortcut — and every widget already bound to that id — instead of creating a duplicate.
    val isEdit = editingAutomationId != NEW_SHORTCUT_ID
    val automation = if (isEdit) built.copy(id = editingAutomationId) else built
    when {
        onSave != null -> onSave.invoke(automation)
        isEdit -> viewModel?.update(automation)
        else -> viewModel?.insert(automation)
    }
    setSaving(false); onBack()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddActionBottomSheet(
    actionTypes: List<ActionTypeMetadata>,
    onActionTypeSelected: (ActionType) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Add Action",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose what type of step to add to your shortcut",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                actionTypes.forEach { meta ->
                    ElevatedCard(
                        onClick = {
                            onActionTypeSelected(meta.type)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = meta.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = meta.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = meta.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerDialog(
    installedApps: List<InstalledAppInfo>,
    onAppSelected: (InstalledAppInfo) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(installedApps, searchQuery) {
        ManualBuilderUtils.filterAndSortApps(installedApps, searchQuery)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Select an App",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search installed apps...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (installedApps.isEmpty()) "No launchable apps found on device" else "No matching apps found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(filteredApps, key = { _, app -> app.packageName }) { _, app ->
                        Surface(
                            onClick = {
                                onAppSelected(app)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val bitmap = remember(app.iconDrawable) {
                                    app.iconDrawable?.run {
                                        try { toBitmap().asImageBitmap() } catch (e: Exception) { null }
                                    }
                                }
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = app.label,
                                        modifier = Modifier.size(36.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Apps,
                                        contentDescription = app.label,
                                        modifier = Modifier.size(36.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
