package com.shortcuts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_widget_templates")
data class CustomWidgetTemplate(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val colorKey: String,
    val iconKey: String,
    val automationId: Int
)
