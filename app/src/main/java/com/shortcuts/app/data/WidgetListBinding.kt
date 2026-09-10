package com.shortcuts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "widget_list_bindings")
data class WidgetListBinding(
    @PrimaryKey val widgetId: Int,
    val automationIdsJson: String
)
