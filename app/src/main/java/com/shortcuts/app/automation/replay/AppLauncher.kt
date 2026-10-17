package com.shortcuts.app.automation.replay

import com.shortcuts.app.automation.model.LaunchAppStep

interface AppLauncher {
    suspend fun launch(step: LaunchAppStep): LaunchDispatchResult
}

sealed interface LaunchDispatchResult {
    data object Accepted : LaunchDispatchResult
    data class Rejected(val reason: String) : LaunchDispatchResult
}
