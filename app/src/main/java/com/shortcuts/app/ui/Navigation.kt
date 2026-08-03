package com.shortcuts.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shortcuts.app.ui.screens.AiBuilderScreen
import com.shortcuts.app.ui.screens.DashboardScreen
import com.shortcuts.app.ui.screens.ManualBuilderScreen

@Composable
fun ShortcutsNavigation() {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                onNavigateToManualBuilder = { navController.navigate("manual_builder") },
                onNavigateToAiBuilder = { navController.navigate("ai_builder") }
            )
        }
        composable("manual_builder") {
            ManualBuilderScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("ai_builder") {
            AiBuilderScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
