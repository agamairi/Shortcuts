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
    val userName: String? = null,
    /**
     * The layout the user explicitly chose, or null / [WidgetLayoutKey.AUTO] to keep the
     * size-adaptive behaviour. Stored as the enum name so an unknown value from a newer build
     * degrades to AUTO rather than crashing.
     */
    val layoutKey: String? = null
)

/** The shapes a placed widget can render in. */
enum class WidgetLayoutKey {
    /** Pick the layout from the widget's current size, as before. */
    AUTO,
    SINGLE,
    GRID,
    LIST;

    companion object {
        fun fromKeyOrAuto(value: String?): WidgetLayoutKey =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: AUTO
    }
}

enum class WidgetConfigSource {
    UNIFIED,
    AUTOMATION,
    LIST,
    CUSTOM,
    GRID,
    GREETING
}
