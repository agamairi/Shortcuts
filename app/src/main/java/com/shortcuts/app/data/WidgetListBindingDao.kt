package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface WidgetListBindingDao {
    @Query("SELECT * FROM widget_list_bindings WHERE widgetId = :widgetId")
    suspend fun getBinding(widgetId: Int): WidgetListBinding?

    @Upsert
    suspend fun upsertBinding(binding: WidgetListBinding)

    @Query("DELETE FROM widget_list_bindings WHERE widgetId = :widgetId")
    suspend fun deleteBinding(widgetId: Int)
}
