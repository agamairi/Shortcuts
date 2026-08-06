package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CustomWidgetBindingDao {
    @Query("SELECT * FROM custom_widget_bindings WHERE widgetId = :widgetId")
    suspend fun getBinding(widgetId: Int): CustomWidgetBinding?

    @Upsert
    suspend fun upsertBinding(binding: CustomWidgetBinding)

    @Query("DELETE FROM custom_widget_bindings WHERE widgetId = :widgetId")
    suspend fun deleteBinding(widgetId: Int)
}
