package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations")
    fun getAllAutomations(): Flow<List<Automation>>

    @Insert
    suspend fun insertAutomation(automation: Automation)
    
    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getAutomationById(id: Int): Automation?
}
