package com.shortcuts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "automations")
data class Automation(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val actionsJson: String, // Serialized array of Actions
    val isActive: Boolean = true,
    val triggerType: String = "MANUAL",
    val colorKey: String? = null,
    val iconKey: String? = null
)

data class Action(
    val actionType: ActionType,
    val target: String? = null,
    val state: String? = null,
    val packageName: String? = null,
    val intentAction: String? = null,
    val url: String? = null,
    val method: String? = null,
    val targetNodeId: String? = null,
    val textInput: String? = null,
    val uiActionType: String? = null,
    val globalAction: String? = null,
    val scrollDirection: String? = null,
    val targetText: String? = null
)

enum class ActionType {
    SYSTEM_TOGGLE,
    APP_INTENT,
    HTTP_REQUEST,
    UI_AUTOMATION
}

class ActionConverter {
    private val gson = Gson()
    
    @TypeConverter
    fun fromActionList(actions: List<Action>): String {
        return gson.toJson(actions)
    }

    @TypeConverter
    fun toActionList(actionsString: String): List<Action> {
        val type = object : TypeToken<List<Action>>() {}.type
        return gson.fromJson(actionsString, type)
    }
}
