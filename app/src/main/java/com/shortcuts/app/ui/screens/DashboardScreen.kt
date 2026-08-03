package com.shortcuts.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToManualBuilder: () -> Unit,
    onNavigateToAiBuilder: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Shortcuts") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                ExtendedFloatingActionButton(
                    text = { Text("AI Builder") },
                    icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = "AI") },
                    onClick = onNavigateToAiBuilder,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                FloatingActionButton(onClick = onNavigateToManualBuilder) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Manual")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No shortcuts created yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
