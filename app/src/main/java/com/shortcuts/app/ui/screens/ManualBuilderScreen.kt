package com.shortcuts.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.util.AccessibilityStatusChecker
import com.shortcuts.app.viewmodel.AutomationViewModel
import kotlinx.coroutines.launch

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val iconDrawable: Drawable? = null
)

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
            type = ActionType.HTTP_REQUEST,
            title = "Web Request",
            description = "Call a URL or webhook",
            icon = Icons.Filled.Language
        )
    )

    fun getMetadataForType(type: ActionType): ActionTypeMetadata {
        return ACTION_TYPE_METADATA.first { it.type == type }
    }

    fun createDefaultAction(type: ActionType): Action {
        return when (type) {
            ActionType.APP_INTENT -> Action(actionType = ActionType.APP_INTENT, packageName = "")
            ActionType.UI_AUTOMATION -> Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "")
            ActionType.SYSTEM_TOGGLE -> Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI", state = "TOGGLE")
            ActionType.HTTP_REQUEST -> Action(actionType = ActionType.HTTP_REQUEST, url = "", method = "GET")
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
                if (target != null) {
                    "Tap '$target'"
                } else {
                    "Tap or Type on Screen"
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualBuilderScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: (() -> Unit)? = null,
    viewModel: AutomationViewModel? = null,
    onSaveAutomation: ((Automation) -> Unit)? = null
) {
    val context = LocalContext.current
    var shortcutName by remember { mutableStateOf("") }
    var selectedTrigger by remember { mutableStateOf("MANUAL") }
    val actions = remember { mutableStateListOf<Action>() }
    var isSaving by remember { mutableStateOf(false) }

    var showAddActionSheet by remember { mutableStateOf(false) }
    var appPickerTargetIndex by remember { mutableStateOf<Int?>(null) }
    var isAccessibilityBannerDismissed by remember { mutableStateOf(false) }

    val isAccessibilityEnabled = remember(context) {
        AccessibilityStatusChecker.isAccessibilityEnabled(context)
    }

    val installedApps = remember(context) {
        ManualBuilderUtils.getInstalledLaunchableApps(context)
    }

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
            // Accessibility Warning Banner
            if (!isAccessibilityEnabled && !isAccessibilityBannerDismissed) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Tap-on-screen actions need the Accessibility Service turned on",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (onNavigateToSettings != null) {
                            TextButton(
                                onClick = { onNavigateToSettings() }
                            ) {
                                Text("Settings", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                            }
                        }
                        IconButton(
                            onClick = { isAccessibilityBannerDismissed = true }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

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
                    onClick = { showAddActionSheet = true }
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
                        installedApps = installedApps,
                        onOpenAppPicker = { appPickerTargetIndex = index },
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

    if (showAddActionSheet) {
        AddActionBottomSheet(
            onActionTypeSelected = { type ->
                actions.add(ManualBuilderUtils.createDefaultAction(type))
            },
            onDismiss = { showAddActionSheet = false }
        )
    }

    appPickerTargetIndex?.let { index ->
        if (index in actions.indices) {
            AppPickerDialog(
                installedApps = installedApps,
                onAppSelected = { selectedApp ->
                    val currentAction = actions[index]
                    actions[index] = currentAction.copy(packageName = selectedApp.packageName)
                },
                onDismiss = { appPickerTargetIndex = null }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddActionBottomSheet(
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
                ManualBuilderUtils.ACTION_TYPE_METADATA.forEach { meta ->
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionEditorCard(
    index: Int,
    totalActions: Int,
    action: Action,
    installedApps: List<InstalledAppInfo>,
    onOpenAppPicker: () -> Unit,
    onUpdateAction: (Action) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(true) }
    val currentMeta = remember(action.actionType) { ManualBuilderUtils.getMetadataForType(action.actionType) }
    val summary = remember(action, installedApps) {
        ManualBuilderUtils.getActionSummary(action) { pkg -> ManualBuilderUtils.getAppLabel(installedApps, pkg) }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Summary & Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = currentMeta.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "${index + 1}. $summary",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMoveUp, enabled = index > 0) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Move Up")
                    }
                    IconButton(onClick = onMoveDown, enabled = index < totalActions - 1) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Move Down")
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove Action", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { isExpanded = !isExpanded }) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand"
                        )
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(12.dp))

                // Friendly Action Type Chips
                Text(
                    text = "Action Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ManualBuilderUtils.ACTION_TYPE_METADATA.forEach { meta ->
                        FilterChip(
                            selected = action.actionType == meta.type,
                            onClick = { onUpdateAction(action.copy(actionType = meta.type)) },
                            label = { Text(meta.title, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(
                                    imageVector = meta.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Type-specific editors
                when (action.actionType) {
                    ActionType.APP_INTENT -> {
                        val selectedAppLabel = remember(action.packageName, installedApps) {
                            ManualBuilderUtils.getAppLabel(installedApps, action.packageName)
                        }
                        var isManualInput by remember { mutableStateOf(false) }

                        Column {
                            Text(
                                text = "App to Open",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            if (!isManualInput) {
                                Surface(
                                    onClick = onOpenAppPicker,
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Apps,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Column {
                                                val pkgName = action.packageName.orEmpty()
                                                if (pkgName.isNotBlank()) {
                                                    Text(
                                                        text = selectedAppLabel ?: pkgName,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                                    )
                                                    if (selectedAppLabel != null) {
                                                        Text(
                                                            text = pkgName,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                } else {
                                                    Text(
                                                        text = "Tap to select an app...",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                        TextButton(onClick = onOpenAppPicker) {
                                            Text("Choose")
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                TextButton(
                                    onClick = { isManualInput = true },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Enter package name manually", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                OutlinedTextField(
                                    value = action.packageName ?: "",
                                    onValueChange = { onUpdateAction(action.copy(packageName = it)) },
                                    label = { Text("Package Name") },
                                    placeholder = { Text("com.spotify.music") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                TextButton(
                                    onClick = { isManualInput = false },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Back to App Picker", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    ActionType.UI_AUTOMATION -> {
                        OutlinedTextField(
                            value = action.targetNodeId ?: "",
                            onValueChange = { onUpdateAction(action.copy(targetNodeId = it)) },
                            label = { Text("Text or button to tap") },
                            placeholder = { Text("e.g. Living Room or Red") },
                            supportingText = {
                                Text("Enter the exact text you see on screen, e.g. 'Living Room' or 'Red'")
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    ActionType.SYSTEM_TOGGLE -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = action.target ?: "WIFI",
                                onValueChange = { onUpdateAction(action.copy(target = it)) },
                                label = { Text("Target (e.g. WIFI)") },
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = action.state ?: "TOGGLE",
                                onValueChange = { onUpdateAction(action.copy(state = it)) },
                                label = { Text("State (ON/OFF/TOGGLE)") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    ActionType.HTTP_REQUEST -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = action.url ?: "",
                                onValueChange = { onUpdateAction(action.copy(url = it)) },
                                label = { Text("URL") },
                                placeholder = { Text("https://api.example.com") },
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
                }
            }
        }
    }
}
