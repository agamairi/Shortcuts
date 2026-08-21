package com.shortcuts.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shortcuts.app.service.AutomationRecorder
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.repository.AutomationRepository
import com.shortcuts.app.service.OnDeviceInferenceService
import com.shortcuts.app.ui.screens.AiBuilderScreen
import com.shortcuts.app.ui.screens.CreateWidgetScreen
import com.shortcuts.app.ui.screens.DashboardScreen
import com.shortcuts.app.ui.screens.HelpScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.shortcuts.app.ui.screens.ManualBuilderScreen
import com.shortcuts.app.ui.screens.NEW_SHORTCUT_ID
import com.shortcuts.app.ui.screens.RecorderScreen
import com.shortcuts.app.ui.screens.SettingsScreen
import com.shortcuts.app.viewmodel.AiBuilderViewModel
import com.shortcuts.app.viewmodel.AutomationViewModel
import com.shortcuts.app.viewmodel.CustomWidgetViewModel
import com.shortcuts.app.viewmodel.SettingsViewModel


class RecorderCleanupViewModel : ViewModel() {
    override fun onCleared() {
        super.onCleared()
        AutomationRecorder.clearRecording()
    }
}

@Composable
fun ShortcutsNavigation(startDestination: String = "dashboard") {
    val navController = rememberNavController()
    val context = LocalContext.current

    val repository = remember(context) {
        val db = AppDatabase.getDatabase(context)
        AutomationRepository(db.automationDao(), db.widgetConfigDao())
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

    val settingsViewModel = remember {
        SettingsViewModel()
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("dashboard") {
            DashboardScreen(
                viewModel = automationViewModel,
                onNavigateToManualBuilder = { navController.navigate("manual_builder") },
                onNavigateToEditShortcut = { id -> navController.navigate("manual_builder?automationId=$id") },
                onNavigateToRecorder = { navController.navigate(recordButtonDestination()) },
                onNavigateToAiBuilder = { navController.navigate("ai_builder") },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable(
            route = "manual_builder?automationId={automationId}",
            arguments = listOf(
                navArgument("automationId") {
                    type = NavType.IntType
                    defaultValue = NEW_SHORTCUT_ID
                }
            )
        ) { backStackEntry ->
            ManualBuilderScreen(
                viewModel = automationViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate("settings") },
                editingAutomationId = backStackEntry.arguments
                    ?.getInt("automationId") ?: NEW_SHORTCUT_ID
            )
        }
        composable("ai_builder") { backStackEntry ->
            val scopedAiViewModel: AiBuilderViewModel = viewModel(
                viewModelStoreOwner = backStackEntry,
                factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return AiBuilderViewModel(repository = repository, inferenceService = inferenceService) as T
                    }
                }
            )
            AiBuilderScreen(
                viewModel = scopedAiViewModel,
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
        composable("settings") {
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToHelp = { navController.navigate("help") }
            )
        }
        composable("help") {
            HelpScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(RECORDER_ROUTE) { backStackEntry ->
            viewModel<RecorderCleanupViewModel>(viewModelStoreOwner = backStackEntry)
            RecorderScreen(
                viewModel = automationViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
