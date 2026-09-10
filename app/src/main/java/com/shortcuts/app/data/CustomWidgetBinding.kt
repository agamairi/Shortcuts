package com.shortcuts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_widget_bindings")
data class CustomWidgetBinding(
    @PrimaryKey val widgetId: Int,
    val templateId: Int
)
