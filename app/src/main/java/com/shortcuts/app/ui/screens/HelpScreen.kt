package com.shortcuts.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.HelpCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("How It Works", style = MaterialTheme.typography.titleLarge) },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: What is a Shortcut
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.HelpCenter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "What is a Shortcut?",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "A Shortcut in this app is an automation: a saved sequence of actions you can run manually, trigger from a home-screen widget, or eventually initiate automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Section 2: Action Types
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Action Types",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    ActionTypeHelpItem(
                        title = "1. Open an App",
                        description = "Launches another Android application on your device.",
                        example = "Example: Open Spotify or open Amazon Alexa."
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ActionTypeHelpItem(
                        title = "2. Tap or Type on Screen",
                        description = "Uses Accessibility to interact with whatever app is currently open (requires enabling the Accessibility Service in Settings first).",
                        example = "Example: Tap a 'Play' button or type text into a search bar."
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ActionTypeHelpItem(
                        title = "3. Toggle a Setting",
                        description = "Quickly toggles device system settings.",
                        example = "Example: Turn Wi-Fi or Bluetooth on/off."
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ActionTypeHelpItem(
                        title = "4. Web Request",
                        description = "Calls an HTTP URL or webhook endpoint.",
                        example = "Example: Send a GET/POST request to a smart home server."
                    )
                }
            }

            // Section 3: Widget Types
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Widgets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Home Screen Widget Types",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    WidgetTypeHelpItem(
                        name = "Quick Shortcut",
                        description = "A minimal single tile that runs one bound shortcut."
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    WidgetTypeHelpItem(
                        name = "Shortcuts List",
                        description = "A multi-row scrollable list rendering up to 4 shortcuts."
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    WidgetTypeHelpItem(
                        name = "Custom Widget",
                        description = "A user-styled single tile with custom background color and icon."
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    WidgetTypeHelpItem(
                        name = "Shortcuts Grid",
                        description = "A 2-column grid displaying up to 6 custom widget tiles."
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    WidgetTypeHelpItem(
                        name = "Greeting Widget",
                        description = "A dynamic widget rendering a time-of-day greeting with an inner shortcut button."
                    )
                }
            }

            // Section 4: Worked Example
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Example: Control Smart Lights Across Multiple Apps",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "A single Shortcut CAN chain \"Open an App\" + \"Tap or Type on Screen\" actions across MULTIPLE different apps in one sequence! Note that this requires the Accessibility Service to be enabled first in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    StepHelpItem(
                        stepNumber = 1,
                        stepTitle = "Open Alexa App",
                        detail = "Add an 'Open an App' action and select the Amazon Alexa app."
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    StepHelpItem(
                        stepNumber = 2,
                        stepTitle = "Tap Light Routine in Alexa",
                        detail = "Add a 'Tap or Type on Screen' action specifying the button text 'Turn Off Living Room'."
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    StepHelpItem(
                        stepNumber = 3,
                        stepTitle = "Open Hue App",
                        detail = "Add a second 'Open an App' action targeting the Philips Hue app."
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    StepHelpItem(
                        stepNumber = 4,
                        stepTitle = "Tap Night Mode in Hue",
                        detail = "Add another 'Tap or Type on Screen' action specifying the target text 'Night Mode'."
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "When you tap this shortcut or widget, the app automatically executes the entire multi-app sequence for you!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionTypeHelpItem(
    title: String,
    description: String,
    example: String
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = example,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WidgetTypeHelpItem(
    name: String,
    description: String
) {
    Row {
        Text(
            text = "• $name: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepHelpItem(
    stepNumber: Int,
    stepTitle: String,
    detail: String
) {
    Column {
        Text(
            text = "Step $stepNumber: $stepTitle",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
