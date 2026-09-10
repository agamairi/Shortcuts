package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomWidgetTemplateDao {
    @Insert
    suspend fun insert(template: CustomWidgetTemplate): Long

    @Query("SELECT * FROM custom_widget_templates")
    fun getAll(): Flow<List<CustomWidgetTemplate>>

    @Query("SELECT * FROM custom_widget_templates WHERE id = :id")
    suspend fun getById(id: Int): CustomWidgetTemplate?

    @Delete
    suspend fun delete(template: CustomWidgetTemplate)
}
