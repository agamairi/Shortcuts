package com.shortcuts.app.widget

object AutomationIdResolver {
    suspend fun resolveAutomationId(
        explicitId: Int?,
        getBindingAutomationId: suspend () -> Int?
    ): Int? {
        return explicitId ?: getBindingAutomationId()
    }
}
