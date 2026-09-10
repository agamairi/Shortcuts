package com.shortcuts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "grid_widget_bindings")
data class GridWidgetBinding(
    @PrimaryKey val widgetId: Int,
    val templateIdsJson: String
)
