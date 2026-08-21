package com.shortcuts.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
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
    val targetText: String? = null,
    /** Additional selector captured from accessibility nodes; older saved actions simply omit it. */
    val targetContentDescription: String? = null,
    /** Class name narrows an otherwise ambiguous text/description match. */
    val targetClassName: String? = null,
    /** Screen point captured for a tap, used only when every semantic selector has disappeared. */
    val screenX: Int? = null,
    val screenY: Int? = null,
    /** Continue the chain after this action fails. Defaults to the safe, stop-on-error behavior. */
    val continueOnError: Boolean = false,
    /** Optional pause before the next action in a chain. */
    val delayMillis: Long? = null
)

enum class ActionType {
    SYSTEM_TOGGLE,
    APP_INTENT,
    HTTP_REQUEST,
    UI_AUTOMATION,
    /** A standalone, user-configured pause. Its duration is stored in [Action.delayMillis]. */
    WAIT,
    /** Opens the user's SMS app with a recipient and message ready to review and send. */
    SEND_MESSAGE,
    /** Opens the user's dialer with a number ready to call. */
    DIAL_NUMBER
}

class ActionConverter {
    // A future action enum from a newer app must never make an existing shortcut crash while
    // Room is being read. It becomes an invalid WAIT step, which reports a clear validation
    // error if run; an action with no type at all is dropped below.
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(ActionType::class.java, JsonDeserializer { json, _, _ ->
            runCatching { ActionType.valueOf(json.asString) }.getOrDefault(ActionType.WAIT)
        })
        .create()
    
    @TypeConverter
    fun fromActionList(actions: List<Action>): String {
        return gson.toJson(actions)
    }

    @TypeConverter
    fun toActionList(actionsString: String): List<Action> {
        val type = object : TypeToken<List<Action>>() {}.type
        return runCatching { gson.fromJson<List<Action>>(actionsString, type) }
            .getOrDefault(emptyList())
            .filterNot { runCatching { it.actionType.name }.isFailure }
    }
}
