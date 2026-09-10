package com.shortcuts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "widget_bindings")
data class WidgetBinding(
    @PrimaryKey val widgetId: Int,
    val automationId: Int
)
