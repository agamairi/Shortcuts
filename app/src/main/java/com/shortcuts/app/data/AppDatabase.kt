package com.shortcuts.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.InvalidationTracker
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.shortcuts.app.widget.ShortcutWidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        Automation::class,
        WidgetBinding::class,
        WidgetListBinding::class,
        CustomWidgetTemplate::class,
        CustomWidgetBinding::class,
        GridWidgetBinding::class,
        GreetingWidgetBinding::class,
        WidgetConfig::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao
    abstract fun widgetBindingDao(): WidgetBindingDao
    abstract fun widgetListBindingDao(): WidgetListBindingDao
    abstract fun customWidgetTemplateDao(): CustomWidgetTemplateDao
    abstract fun customWidgetBindingDao(): CustomWidgetBindingDao
    abstract fun gridWidgetBindingDao(): GridWidgetBindingDao
    abstract fun greetingWidgetBindingDao(): GreetingWidgetBindingDao
    abstract fun widgetConfigDao(): WidgetConfigDao

    private fun installWidgetRefreshObserver(context: Context) {
        invalidationTracker.addObserver(object : InvalidationTracker.Observer("automations") {
            override fun onInvalidated(tables: Set<String>) {
                CoroutineScope(Dispatchers.IO).launch {
                    ShortcutWidgetUpdater.refreshAll(context)
                }
            }
        })
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `widget_bindings` (`widgetId` INTEGER NOT NULL, `automationId` INTEGER NOT NULL, PRIMARY KEY(`widgetId`))")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `widget_list_bindings` (`widgetId` INTEGER NOT NULL, `automationIdsJson` TEXT NOT NULL, PRIMARY KEY(`widgetId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `custom_widget_templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `label` TEXT NOT NULL, `colorKey` TEXT NOT NULL, `iconKey` TEXT NOT NULL, `automationId` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `custom_widget_bindings` (`widgetId` INTEGER NOT NULL, `templateId` INTEGER NOT NULL, PRIMARY KEY(`widgetId`))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `grid_widget_bindings` (`widgetId` INTEGER NOT NULL, `templateIdsJson` TEXT NOT NULL, PRIMARY KEY(`widgetId`))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `greeting_widget_bindings` (`widgetId` INTEGER NOT NULL, `userName` TEXT NOT NULL, `colorKey` TEXT NOT NULL, `automationId` INTEGER NOT NULL, PRIMARY KEY(`widgetId`))")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN colorKey TEXT")
                db.execSQL("ALTER TABLE automations ADD COLUMN iconKey TEXT")
            }
        }

        /**
         * Add the consolidated representation without touching the old tables. Keeping the
         * originals makes this migration recoverable and preserves a downgrade path.
         */
        /**
         * Adds the user's explicit widget layout choice. Additive and nullable: every existing
         * widget keeps rendering size-adaptively because a NULL layoutKey means AUTO.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE widget_configs ADD COLUMN layoutKey TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `widget_configs` (" +
                        "`widgetId` INTEGER NOT NULL, " +
                        "`sourceType` TEXT NOT NULL, " +
                        "`automationIdsJson` TEXT NOT NULL, " +
                        "`templateIdsJson` TEXT, " +
                        "`templateId` INTEGER, " +
                        "`label` TEXT, " +
                        "`colorKey` TEXT, " +
                        "`iconKey` TEXT, " +
                        "`userName` TEXT, " +
                        "PRIMARY KEY(`widgetId`, `sourceType`))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO widget_configs " +
                        "(widgetId, sourceType, automationIdsJson) " +
                        "SELECT widgetId, 'AUTOMATION', '[' || automationId || ']' FROM widget_bindings"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO widget_configs " +
                        "(widgetId, sourceType, automationIdsJson) " +
                        "SELECT widgetId, 'LIST', automationIdsJson FROM widget_list_bindings"
                )
                // A left join intentionally retains a binding even if an old template was
                // already missing; its templateId remains recoverable and renders as setup.
                db.execSQL(
                    "INSERT OR REPLACE INTO widget_configs " +
                        "(widgetId, sourceType, automationIdsJson, templateId, label, colorKey, iconKey) " +
                        "SELECT b.widgetId, 'CUSTOM', " +
                        "CASE WHEN t.automationId IS NULL THEN '[]' ELSE '[' || t.automationId || ']' END, " +
                        "b.templateId, t.label, t.colorKey, t.iconKey " +
                        "FROM custom_widget_bindings b " +
                        "LEFT JOIN custom_widget_templates t ON t.id = b.templateId"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO widget_configs " +
                        "(widgetId, sourceType, automationIdsJson, templateIdsJson) " +
                        "SELECT widgetId, 'GRID', '[]', templateIdsJson FROM grid_widget_bindings"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO widget_configs " +
                        "(widgetId, sourceType, automationIdsJson, colorKey, userName) " +
                        "SELECT widgetId, 'GREETING', '[' || automationId || ']', colorKey, userName " +
                        "FROM greeting_widget_bindings"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "automation_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .build()
                instance.installWidgetRefreshObserver(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
