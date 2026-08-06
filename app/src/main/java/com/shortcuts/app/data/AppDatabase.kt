package com.shortcuts.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Automation::class,
        WidgetBinding::class,
        WidgetListBinding::class,
        CustomWidgetTemplate::class,
        CustomWidgetBinding::class,
        GridWidgetBinding::class,
        GreetingWidgetBinding::class
    ],
    version = 6,
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "automation_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
