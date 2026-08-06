package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface GreetingWidgetBindingDao {
    @Query("SELECT * FROM greeting_widget_bindings WHERE widgetId = :widgetId")
    suspend fun getBinding(widgetId: Int): GreetingWidgetBinding?

    @Upsert
    suspend fun upsertBinding(binding: GreetingWidgetBinding)

    @Query("DELETE FROM greeting_widget_bindings WHERE widgetId = :widgetId")
    suspend fun deleteBinding(widgetId: Int)
}
