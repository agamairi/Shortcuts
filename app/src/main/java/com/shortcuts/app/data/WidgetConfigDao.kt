package com.shortcuts.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface WidgetConfigDao {
    @Query("SELECT * FROM widget_configs WHERE widgetId = :widgetId AND sourceType = :sourceType")
    suspend fun getConfig(widgetId: Int, sourceType: String): WidgetConfig?

    @Query("SELECT * FROM widget_configs")
    suspend fun getAllConfigs(): List<WidgetConfig>

    @Upsert
    suspend fun upsertConfig(config: WidgetConfig)

    @Query("DELETE FROM widget_configs WHERE widgetId = :widgetId AND sourceType = :sourceType")
    suspend fun deleteConfig(widgetId: Int, sourceType: String)

    /**
     * Returns all configs whose [WidgetConfig.automationIdsJson] column contains [automationId].
     *
     * The LIKE pattern `%"<id>"%` matches the id surrounded by double-quotes as it appears
     * in a JSON array (e.g. `[1,2,3]` serialised as `[1,2,3]` by Room's default Int[] handling,
     * but the project stores the raw JSON so we also match `"42"` within a string array).
     * Because JSON arrays store integers without quotes (e.g. `[1,42,7]`), we also match
     * the comma/bracket-delimited number via a second pattern.
     *
     * Caller is responsible for post-filtering in Kotlin if exact semantics are required
     * (see [AutomationRepository.countWidgetsReferencingAutomation]).
     */
    @Query("""
        SELECT * FROM widget_configs
        WHERE automationIdsJson LIKE '%"' || :automationId || '"%'
           OR automationIdsJson LIKE '%[' || :automationId || ']%'
           OR automationIdsJson LIKE '%[' || :automationId || ',%'
           OR automationIdsJson LIKE '%,' || :automationId || ']%'
           OR automationIdsJson LIKE '%,' || :automationId || ',%'
    """)
    suspend fun getConfigsReferencingAutomation(automationId: Int): List<WidgetConfig>
}
