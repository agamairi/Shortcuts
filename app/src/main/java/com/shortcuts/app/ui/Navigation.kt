package com.shortcuts.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.ui.screens.AiBuilderScreen
import com.shortcuts.app.ui.screens.CreateWidgetScreen
import com.shortcuts.app.ui.screens.DashboardScreen
import com.shortcuts.app.ui.screens.ManualBuilderScreen
import com.shortcuts.app.viewmodel.AiBuilderViewModel
import com.shortcuts.app.viewmodel.AutomationViewModel
import com.shortcuts.app.viewmodel.CustomWidgetViewModel

@Composable
fun ShortcutsNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val repository = remember(context) {
        val dao = AppDatabase.getDatabase(context).automationDao()
        AutomationRepository(dao)
    }

    val templateDao = remember(context) {
        AppDatabase.getDatabase(context).customWidgetTemplateDao()
    }

    val inferenceService = remember(context) {
        OnDeviceInferenceService(context)
    }

    val automationViewModel = remember(repository) {
        AutomationViewModel(repository)
    }

    val aiBuilderViewModel = remember(repository, inferenceService) {
        AiBuilderViewModel(repository = repository, inferenceService = inferenceService)
    }

    val customWidgetViewModel = remember(repository, templateDao, inferenceService) {
        CustomWidgetViewModel(repository = repository, templateDao = templateDao, inferenceService = inferenceService)
    }

    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                viewModel = automationViewModel,
                onNavigateToManualBuilder = { navController.navigate("manual_builder") },
                onNavigateToAiBuilder = { navController.navigate("ai_builder") },
                onNavigateToCreateWidget = { navController.navigate("create_widget") }
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
        composable("create_widget") {
            CreateWidgetScreen(
                viewModel = customWidgetViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAiBuilder = { navController.navigate("ai_builder") }
            )
        }
    }
}
