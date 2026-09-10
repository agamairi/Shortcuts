package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface GridWidgetBindingDao {
    @Query("SELECT * FROM grid_widget_bindings WHERE widgetId = :widgetId")
    suspend fun getBinding(widgetId: Int): GridWidgetBinding?

    @Upsert
    suspend fun upsertBinding(binding: GridWidgetBinding)

    @Query("DELETE FROM grid_widget_bindings WHERE widgetId = :widgetId")
    suspend fun deleteBinding(widgetId: Int)
}
