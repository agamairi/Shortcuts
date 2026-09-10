package com.shortcuts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "greeting_widget_bindings")
data class GreetingWidgetBinding(
    @PrimaryKey val widgetId: Int,
    val userName: String,
    val colorKey: String,
    val automationId: Int
)
