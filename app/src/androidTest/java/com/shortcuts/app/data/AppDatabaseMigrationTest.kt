package com.shortcuts.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migration6To7_copiesEveryLegacyBindingWithoutDroppingLegacyTables() {
        helper.createDatabase(TEST_DATABASE, 6).apply {
            execSQL("INSERT INTO widget_bindings (widgetId, automationId) VALUES (101, 11)")
            execSQL("INSERT INTO widget_list_bindings (widgetId, automationIdsJson) VALUES (102, '[21,22]')")
            execSQL(
                "INSERT INTO custom_widget_templates (id, label, colorKey, iconKey, automationId) " +
                    "VALUES (31, 'Coffee', 'ORANGE', 'STAR', 32)"
            )
            execSQL("INSERT INTO custom_widget_bindings (widgetId, templateId) VALUES (103, 31)")
            execSQL(
                "INSERT INTO custom_widget_templates (id, label, colorKey, iconKey, automationId) " +
                    "VALUES (41, 'Lights', 'BLUE', 'BOLT', 61)"
            )
            execSQL(
                "INSERT INTO custom_widget_templates (id, label, colorKey, iconKey, automationId) " +
                    "VALUES (42, 'Focus', 'PURPLE', 'BELL', 62)"
            )
            execSQL("INSERT INTO grid_widget_bindings (widgetId, templateIdsJson) VALUES (104, '[41,42]')")
            execSQL(
                "INSERT INTO greeting_widget_bindings (widgetId, userName, colorKey, automationId) " +
                    "VALUES (105, 'Ada', 'TEAL', 51)"
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            7,
            true,
            AppDatabase.MIGRATION_6_7
        )
        try {
            val db = migratedDb
            val configCount = db.query("SELECT * FROM widget_configs").use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count++
                count
            }

            assertConfig(db, 101, "AUTOMATION", "[11]")
            assertConfig(db, 102, "LIST", "[21,22]")
            assertConfig(db, 103, "CUSTOM", "[32]", templateId = 31, label = "Coffee", colorKey = "ORANGE", iconKey = "STAR")
            assertConfig(db, 104, "GRID", "[]", templateIdsJson = "[41,42]")
            assertTemplateAutomation(db, 41, 61)
            assertTemplateAutomation(db, 42, 62)
            assertConfig(db, 105, "GREETING", "[51]", colorKey = "TEAL", userName = "Ada")
            assertEquals(5, configCount)

            // Migration 6 -> 7 is additive: recoverability depends on these tables surviving.
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toSet()
            }
            assertFalse("Legacy widget_bindings table was dropped", "widget_bindings" !in tables)
            assertFalse("Legacy widget_list_bindings table was dropped", "widget_list_bindings" !in tables)
            assertFalse("Legacy custom_widget_bindings table was dropped", "custom_widget_bindings" !in tables)
            assertFalse("Legacy grid_widget_bindings table was dropped", "grid_widget_bindings" !in tables)
            assertFalse("Legacy greeting_widget_bindings table was dropped", "greeting_widget_bindings" !in tables)
        } finally {
            migratedDb.close()
        }
    }

    private fun assertConfig(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        widgetId: Int,
        sourceType: String,
        automationIdsJson: String,
        templateIdsJson: String? = null,
        templateId: Int? = null,
        label: String? = null,
        colorKey: String? = null,
        iconKey: String? = null,
        userName: String? = null
    ) {
        db.query(
            "SELECT * FROM widget_configs WHERE widgetId = ? AND sourceType = ?",
            arrayOf(widgetId, sourceType)
        ).use { cursor ->
            assertEquals("Expected one migrated config for $widgetId:$sourceType", true, cursor.moveToFirst())
            assertEquals(automationIdsJson, cursor.getString(cursor.getColumnIndexOrThrow("automationIdsJson")))
            assertEquals(templateIdsJson, cursor.stringOrNull("templateIdsJson"))
            assertEquals(templateId, cursor.intOrNull("templateId"))
            assertEquals(label, cursor.stringOrNull("label"))
            assertEquals(colorKey, cursor.stringOrNull("colorKey"))
            assertEquals(iconKey, cursor.stringOrNull("iconKey"))
            assertEquals(userName, cursor.stringOrNull("userName"))
            assertFalse("Migration produced duplicate rows", cursor.moveToNext())
        }
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.intOrNull(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun assertTemplateAutomation(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        templateId: Int,
        automationId: Int
    ) {
        db.query(
            "SELECT automationId FROM custom_widget_templates WHERE id = ?",
            arrayOf(templateId)
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(automationId, cursor.getInt(0))
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-test"
    }
}
