package com.shortcuts.app.data

import androidx.room.Entity

/**
 * The one source of truth for an AppWidget configuration.
 *
 * [sourceType] deliberately remains part of the key. Android normally assigns a unique
 * appWidgetId for every provider instance, but retaining the source makes the 6 -> 7
 * migration lossless even for an inconsistent legacy database containing colliding ids.
 */
@Entity(tableName = "widget_configs", primaryKeys = ["widgetId", "sourceType"])
data class WidgetConfig(
    val widgetId: Int,
    val sourceType: String,
    val automationIdsJson: String = "[]",
    val templateIdsJson: String? = null,
    val templateId: Int? = null,
    val label: String? = null,
    val colorKey: String? = null,
    val iconKey: String? = null,
    val userName: String? = null
)

enum class WidgetConfigSource {
    UNIFIED,
    AUTOMATION,
    LIST,
    CUSTOM,
    GRID,
    GREETING
}
