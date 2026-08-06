package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface WidgetBindingDao {
    @Query("SELECT * FROM widget_bindings WHERE widgetId = :widgetId")
    suspend fun getBinding(widgetId: Int): WidgetBinding?

    @Upsert
    suspend fun upsertBinding(binding: WidgetBinding)

    @Query("DELETE FROM widget_bindings WHERE widgetId = :widgetId")
    suspend fun deleteBinding(widgetId: Int)
}
