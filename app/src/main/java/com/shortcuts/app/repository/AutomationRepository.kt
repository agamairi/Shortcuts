package com.shortcuts.app.repository

import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import kotlinx.coroutines.flow.Flow

open class AutomationRepository(private val automationDao: AutomationDao) {

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
}
