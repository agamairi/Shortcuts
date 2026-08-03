package com.shortcuts.app.repository

import com.shortcuts.app.data.Automation
import com.shortcuts.app.data.AutomationDao
import kotlinx.coroutines.flow.Flow

class AutomationRepository(private val automationDao: AutomationDao) {

    val allAutomations: Flow<List<Automation>> = automationDao.getAllAutomations()

    suspend fun insert(automation: Automation) {
        automationDao.insertAutomation(automation)
    }

    suspend fun getAutomationById(id: Int): Automation? {
        return automationDao.getAutomationById(id)
    }
}
