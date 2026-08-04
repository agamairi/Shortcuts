package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations")
    fun getAllAutomations(): Flow<List<Automation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutomation(automation: Automation)

    @Update
    suspend fun updateAutomation(automation: Automation)

    @Delete
    suspend fun deleteAutomation(automation: Automation)
    
    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun getAutomationById(id: Int): Automation?
}
