package com.shortcuts.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.ui.screens.AiBuilderScreen
import com.shortcuts.app.ui.screens.DashboardScreen
import com.shortcuts.app.ui.screens.ManualBuilderScreen
import com.shortcuts.app.viewmodel.AiBuilderViewModel
import com.shortcuts.app.viewmodel.AutomationViewModel

@Composable
fun ShortcutsNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val repository = remember(context) {
        val dao = AppDatabase.getDatabase(context).automationDao()
        AutomationRepository(dao)
    }

    val automationViewModel = remember(repository) {
        AutomationViewModel(repository)
    }

    val aiBuilderViewModel = remember(repository) {
        AiBuilderViewModel(repository = repository)
    }

    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                viewModel = automationViewModel,
                onNavigateToManualBuilder = { navController.navigate("manual_builder") },
                onNavigateToAiBuilder = { navController.navigate("ai_builder") }
            )
        }
        composable("manual_builder") {
            ManualBuilderScreen(
                viewModel = automationViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable("ai_builder") {
            AiBuilderScreen(
                viewModel = aiBuilderViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
