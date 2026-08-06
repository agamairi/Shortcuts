package com.shortcuts.app.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shortcuts.app.data.Automation
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.util.AutomationVisuals
import com.shortcuts.app.viewmodel.AutomationViewModel
import com.shortcuts.app.widget.AutomationWidgetReceiver
import com.shortcuts.app.widget.CustomWidgetReceiver
import com.shortcuts.app.widget.GreetingWidgetReceiver
import com.shortcuts.app.widget.GridWidgetReceiver
import com.shortcuts.app.widget.ShortcutsListWidgetReceiver
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey

@Composable
fun DashboardScreen(
    viewModel: AutomationViewModel,
    onNavigateToManualBuilder: () -> Unit,
    onNavigateToAiBuilder: () -> Unit,
    onNavigateToCreateWidget: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onPinListWidget: (() -> Unit)? = null,
    onPinGridWidget: (() -> Unit)? = null,
    onPinGreetingWidget: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val errorState by viewModel.errorState.collectAsState()
    val context = LocalContext.current

    val pinListAction = onPinListWidget ?: {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, ShortcutsListWidgetReceiver::class.java)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(provider, null, null)
        }
    }

    val pinGridAction = onPinGridWidget ?: {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, GridWidgetReceiver::class.java)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(provider, null, null)
        }
    }

    val pinGreetingAction = onPinGreetingWidget ?: {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, GreetingWidgetReceiver::class.java)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(provider, null, null)
        }
    }

    val pinCustomAction = {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, CustomWidgetReceiver::class.java)
        if (appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(provider, null, null)
        }
    }

    DashboardScreenContent(
        uiState = uiState,
        errorState = errorState,
        onNavigateToManualBuilder = onNavigateToManualBuilder,
        onNavigateToAiBuilder = onNavigateToAiBuilder,
        onNavigateToCreateWidget = onNavigateToCreateWidget,
        onNavigateToSettings = onNavigateToSettings,
        onPinListWidget = pinListAction,
        onPinGridWidget = pinGridAction,
        onPinGreetingWidget = pinGreetingAction,
        onPinCustomWidget = pinCustomAction,
        onToggleActive = { viewModel.toggleActive(it) },
        onDelete = { viewModel.delete(it) },
        onClearError = { viewModel.clearError() },
        onPinToHomeScreen = {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, AutomationWidgetReceiver::class.java)
            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                appWidgetManager.requestPinAppWidget(provider, null, null)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreenContent(
    uiState: UiState<List<Automation>>,
    errorState: String? = null,
    onNavigateToManualBuilder: () -> Unit,
    onNavigateToAiBuilder: () -> Unit,
    onNavigateToCreateWidget: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onPinListWidget: () -> Unit = {},
    onPinGridWidget: () -> Unit = {},
    onPinGreetingWidget: () -> Unit = {},
    onPinCustomWidget: () -> Unit = {},
    onToggleActive: (Automation) -> Unit = {},
    onDelete: (Automation) -> Unit = {},
    onClearError: () -> Unit = {},
    onPinToHomeScreen: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showWidgetGallerySheet by remember { mutableStateOf(false) }

    LaunchedEffect(errorState, uiState) {
        val errorMessage = errorState ?: (uiState as? UiState.Error)?.message
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(
                message = errorMessage,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Short
            )
            onClearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            var menuExpanded by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.FlashOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Shortcuts Dashboard",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add Widget to Home Screen") },
                            onClick = {
                                menuExpanded = false
                                showWidgetGallerySheet = true
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                ExtendedFloatingActionButton(
                    text = { Text("AI Builder", style = MaterialTheme.typography.labelLarge) },
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = "AI Builder") },
                    onClick = onNavigateToAiBuilder,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                FloatingActionButton(
                    onClick = onNavigateToManualBuilder,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Manual Shortcut")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (uiState) {
                is UiState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading shortcuts...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is UiState.Success -> {
                    val automations = uiState.data
                    if (automations.isEmpty()) {
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 32.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "No shortcuts created yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Tap '+' to create a manual shortcut or 'AI Builder' to generate one with natural language.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(automations, key = { it.id }) { automation ->
                                AutomationItemCard(
                                    automation = automation,
                                    onToggleActive = { onToggleActive(automation) },
                                    onDelete = { onDelete(automation) },
                                    onPinToHomeScreen = onPinToHomeScreen
                                )
                            }
                        }
                    }
                }
                is UiState.Error -> {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Error: ${uiState.message}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }

    if (showWidgetGallerySheet) {
        WidgetGalleryBottomSheet(
            onDismiss = { showWidgetGallerySheet = false },
            onNavigateToCreateWidget = onNavigateToCreateWidget,
            onPinQuickShortcut = onPinToHomeScreen,
            onPinListWidget = onPinListWidget,
            onPinCustomWidget = onPinCustomWidget,
            onPinGridWidget = onPinGridWidget,
            onPinGreetingWidget = onPinGreetingWidget
        )
    }
}

@Composable
fun AutomationItemCard(
    automation: Automation,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit,
    onPinToHomeScreen: () -> Unit = {}
) {
    val colorKey = AutomationVisuals.colorForAutomation(automation.id)
    val iconKey = AutomationVisuals.iconForAutomation(automation.id)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colorKey.composeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconKey.composeIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = automation.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            text = "Trigger: ${automation.triggerType}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = automation.isActive,
                    onCheckedChange = { onToggleActive() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onPinToHomeScreen) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pin to Home Screen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Shortcut",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetGalleryBottomSheet(
    onDismiss: () -> Unit,
    onNavigateToCreateWidget: () -> Unit,
    onPinQuickShortcut: () -> Unit,
    onPinListWidget: () -> Unit,
    onPinCustomWidget: () -> Unit,
    onPinGridWidget: () -> Unit,
    onPinGreetingWidget: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Add Widget to Home Screen",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Choose a widget layout to add to your home screen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 1. Quick Shortcut Tile
                WidgetGalleryCard(
                    title = "Quick Shortcut Tile",
                    description = "Minimal single tile rendering a single bound automation.",
                    previewContent = {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(WidgetColorKey.BLUE.composeColor)
                                .padding(6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = WidgetIconKey.BOLT.composeIcon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Shortcut",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    onPin = {
                        onPinQuickShortcut()
                        onDismiss()
                    }
                )

                // 2. Shortcuts List Widget
                WidgetGalleryCard(
                    title = "Shortcuts List Widget",
                    description = "Multi-row scrollable Glance widget rendering up to 4 shortcuts.",
                    previewContent = {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .padding(6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceEvenly
                            ) {
                                repeat(3) { index ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(12.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                when (index) {
                                                    0 -> WidgetColorKey.BLUE.composeColor.copy(alpha = 0.8f)
                                                    1 -> WidgetColorKey.GREEN.composeColor.copy(alpha = 0.8f)
                                                    else -> WidgetColorKey.ORANGE.composeColor.copy(alpha = 0.8f)
                                                }
                                            )
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .height(3.dp)
                                                .fillMaxWidth(0.7f)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(Color.White)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onPin = {
                        onPinListWidget()
                        onDismiss()
                    }
                )

                // 3. Custom Widget
                WidgetGalleryCard(
                    title = "Custom Widget",
                    description = "User-styled single Glance tile supporting custom colors and icons. Design your own tile first in 'Create Your Own Widget', then pin it here.",
                    previewContent = {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(WidgetColorKey.PURPLE.composeColor)
                                .padding(6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = WidgetIconKey.STAR.composeIcon,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Custom",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    secondaryActionText = "Create Your Own Widget",
                    onSecondaryAction = {
                        onNavigateToCreateWidget()
                        onDismiss()
                    },
                    onPin = {
                        onPinCustomWidget()
                        onDismiss()
                    }
                )

                // 4. Shortcuts Grid Widget
                WidgetGalleryCard(
                    title = "Shortcuts Grid Widget",
                    description = "2-column grid Glance widget displaying up to 6 custom widget tiles. Design custom tiles first in 'Create Your Own Widget', then pin it here.",
                    previewContent = {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(WidgetColorKey.BLUE.composeColor)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(WidgetColorKey.GREEN.composeColor)
                                    )
                                }
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(WidgetColorKey.ORANGE.composeColor)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(WidgetColorKey.TEAL.composeColor)
                                    )
                                }
                            }
                        }
                    },
                    secondaryActionText = "Create Your Own Widget",
                    onSecondaryAction = {
                        onNavigateToCreateWidget()
                        onDismiss()
                    },
                    onPin = {
                        onPinGridWidget()
                        onDismiss()
                    }
                )

                // 5. Greeting Widget
                WidgetGalleryCard(
                    title = "Greeting Widget",
                    description = "Personalized, dynamic-content widget rendering a time-of-day-aware greeting with user name and shortcut button.",
                    previewContent = {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(WidgetColorKey.TEAL.composeColor)
                                .padding(6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Good Morning",
                                    color = Color.White,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White.copy(alpha = 0.3f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "▶ Run",
                                        color = Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    },
                    onPin = {
                        onPinGreetingWidget()
                        onDismiss()
                    }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun WidgetGalleryCard(
    title: String,
    description: String,
    previewContent: @Composable () -> Unit,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    onPin: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                previewContent()
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (secondaryActionText != null && onSecondaryAction != null) {
                    TextButton(onClick = onSecondaryAction) {
                        Text(secondaryActionText)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(onClick = onPin) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pin to Home Screen")
                }
            }
        }
    }
}
