package com.shortcuts.app.repository

import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import com.shortcuts.app.data.WidgetConfigDao
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

open class AutomationRepository(
    private val automationDao: AutomationDao,
    private val widgetConfigDao: WidgetConfigDao? = null
) {

    open val allAutomations: Flow<List<Automation>> = automationDao.getAllAutomations()

    open suspend fun insert(automation: Automation) {
        automationDao.insertAutomation(automation)
    }

    open suspend fun update(automation: Automation) {
        automationDao.updateAutomation(automation)
    }

    open suspend fun delete(automation: Automation) {
        automationDao.deleteAutomation(automation)
    }

    open suspend fun toggleActive(automation: Automation) {
        val updated = automation.copy(isActive = !automation.isActive)
        automationDao.updateAutomation(updated)
    }

    open suspend fun getAutomationById(id: Int): Automation? {
        return automationDao.getAutomationById(id)
    }

    /**
     * Returns the number of placed homescreen widgets that reference [automationId].
     *
     * The DAO uses broad LIKE-based patterns to catch the automation ID wherever it appears
     * inside the [com.shortcuts.app.data.WidgetConfig.automationIdsJson] column.  To eliminate
     * false positives (e.g., id=1 matching "10" or "21"), we parse the JSON array in Kotlin
     * and only count rows where the id genuinely appears as an element.
     *
     * Returns 0 if [widgetConfigDao] is null (not injected, e.g., in legacy tests).
     */
    open suspend fun countWidgetsReferencingAutomation(automationId: Int): Int {
        val dao = widgetConfigDao ?: return 0
        val candidates = dao.getConfigsReferencingAutomation(automationId)
        return candidates.count { config ->
            runCatching {
                val arr = JSONArray(config.automationIdsJson)
                (0 until arr.length()).any { i -> arr.getInt(i) == automationId }
            }.getOrDefault(false)
        }
    }
}
