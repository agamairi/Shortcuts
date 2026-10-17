package com.shortcuts.app.automation.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.internal.Streams
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.shortcuts.app.data.ActionConverter

class WorkflowCodec(
    private val migrator: LegacyWorkflowMigrator = LegacyWorkflowMigrator()
) {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(StrictEnumTypeAdapterFactory())
        .registerTypeAdapterFactory(
            DiscriminatedTypeAdapterFactory.of(Step::class.java)
                .subtype("launch_app", LaunchAppStep::class.java)
                .subtype("ui", UiStep::class.java)
                .subtype("wait", WaitStep::class.java)
                .subtype("legacy_action", LegacyActionStep::class.java)
        )
        .registerTypeAdapterFactory(
            DiscriminatedTypeAdapterFactory.of(LaunchTarget::class.java)
                .subtype("launcher", LaunchTarget.Launcher::class.java)
                .subtype("component", LaunchTarget.Component::class.java)
                .subtype("deep_link", LaunchTarget.DeepLink::class.java)
        )
        .registerTypeAdapterFactory(
            DiscriminatedTypeAdapterFactory.of(VerificationPostcondition::class.java)
                .subtype("expected_screen", VerificationPostcondition.ExpectedScreen::class.java)
                .subtype("expected_window", VerificationPostcondition.ExpectedWindow::class.java)
                .subtype("expected_activity", VerificationPostcondition.ExpectedActivity::class.java)
                .subtype("target_presence", VerificationPostcondition.TargetPresence::class.java)
                .subtype("boolean_state_changed", VerificationPostcondition.BooleanStateChanged::class.java)
                .subtype("range_value_changed", VerificationPostcondition.RangeValueChanged::class.java)
                .subtype("text_equals", VerificationPostcondition.TextEquals::class.java)
                .subtype("focus_moved", VerificationPostcondition.FocusMoved::class.java)
                .subtype("ime_visibility", VerificationPostcondition.ImeVisibility::class.java)
                .subtype("next_target_confidence", VerificationPostcondition.NextTargetConfidence::class.java)
        )
        .registerTypeHierarchyAdapter(UiOperation::class.java, UiOperationTypeAdapter())
        .disableHtmlEscaping()
        .create()

    fun encode(workflow: WorkflowV2): String {
        require(workflow.schemaVersion == WorkflowV2.SCHEMA_VERSION) {
            "WorkflowCodec only encodes schema version ${WorkflowV2.SCHEMA_VERSION}"
        }
        return gson.toJson(workflow)
    }

    fun decode(json: String): DecodeResult {
        val root = try {
            JsonParser.parseString(json)
        } catch (error: Exception) {
            return failure(DecodeFailureCode.MALFORMED_JSON, error)
        }

        return try {
            when {
                root.isJsonObject -> decodeV2(root.asJsonObject)
                root.isJsonArray -> decodeLegacy(json)
                else -> DecodeResult.Failure(
                    DecodeFailureCode.INVALID_ROOT,
                    "$",
                    "Workflow JSON must be a versioned object or a legacy action array"
                )
            }
        } catch (error: Exception) {
            failure(DecodeFailureCode.INVALID_VALUE, error)
        }
    }

    private fun decodeV2(root: JsonObject): DecodeResult {
        val versionElement = root.get("schemaVersion")
            ?: return DecodeResult.Failure(
                DecodeFailureCode.MISSING_SCHEMA_VERSION,
                "$.schemaVersion",
                "Missing required schemaVersion"
            )
        val version = versionElement
            .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString
            ?.toIntOrNull()
        if (version == null) {
            return DecodeResult.Failure(
                DecodeFailureCode.INVALID_VALUE,
                "$.schemaVersion",
                "schemaVersion must be an integer"
            )
        }
        if (version != WorkflowV2.SCHEMA_VERSION) {
            return DecodeResult.Failure(
                DecodeFailureCode.UNSUPPORTED_SCHEMA_VERSION,
                "$.schemaVersion",
                "Unsupported workflow schema version $version"
            )
        }

        requirePrimitive(root, "workflowId", "$.workflowId")
        requireObject(root, "recordedDevice", "$.recordedDevice")
        val steps = root.get("steps")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: throw JsonParseException("steps is required at path $.steps")
        steps.forEachIndexed { index, element ->
            if (!element.isJsonObject) throw JsonParseException("step must be an object at path $.steps[$index]")
            requirePrimitive(element.asJsonObject, "type", "$.steps[$index].type")
            requirePrimitive(element.asJsonObject, "id", "$.steps[$index].id")
        }

        val workflow = gson.fromJson(root, WorkflowV2::class.java)
        validateRequiredFields(workflow)
        return DecodeResult.Success(workflow, migratedFromLegacy = false, warnings = emptyList())
    }

    private fun requirePrimitive(objectValue: JsonObject, name: String, path: String) {
        if (objectValue.get(name)?.isJsonPrimitive != true) {
            throw JsonParseException("$name is required at path $path")
        }
    }

    private fun requireObject(objectValue: JsonObject, name: String, path: String) {
        if (objectValue.get(name)?.isJsonObject != true) {
            throw JsonParseException("$name is required at path $path")
        }
    }

    private fun decodeLegacy(json: String): DecodeResult {
        val actions = ActionConverter().toActionList(json)
        val migration = migrator.migrate(actions)
        return DecodeResult.Success(
            workflow = migration.workflow,
            migratedFromLegacy = true,
            warnings = migration.warnings
        )
    }

    private fun validateRequiredFields(workflow: WorkflowV2) {
        if (workflow.workflowId.isBlank()) throw JsonParseException("workflowId is required at path $.workflowId")
        workflow.steps.forEachIndexed { index, step ->
            if (step.id.isBlank()) throw JsonParseException("id is required at path $.steps[$index].id")
        }
    }

    private fun failure(code: DecodeFailureCode, error: Exception): DecodeResult.Failure {
        val message = error.message ?: error::class.java.simpleName
        return DecodeResult.Failure(code, extractPath(message), message)
    }

    private fun extractPath(message: String): String {
        val match = Regex("(?:at path |path )(\\$[^\\s,;]*)").find(message)
        return match?.groupValues?.get(1) ?: "$"
    }
}

sealed interface DecodeResult {
    data class Success(
        val workflow: WorkflowV2,
        val migratedFromLegacy: Boolean,
        val warnings: List<String>
    ) : DecodeResult

    data class Failure(
        val code: DecodeFailureCode,
        val path: String,
        val message: String
    ) : DecodeResult
}

enum class DecodeFailureCode {
    MALFORMED_JSON,
    INVALID_ROOT,
    MISSING_SCHEMA_VERSION,
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_VALUE
}

private class UiOperationTypeAdapter : TypeAdapter<UiOperation>() {
    override fun write(out: JsonWriter, value: UiOperation?) {
        if (value == null) {
            out.nullValue()
            return
        }
        out.beginObject()
        when (value) {
            UiOperation.TAP -> out.name("type").value("tap")
            UiOperation.LONG_PRESS -> out.name("type").value("long_press")
            UiOperation.SWIPE -> out.name("type").value("swipe")
            is UiOperation.SET_TEXT -> {
                out.name("type").value("set_text")
                out.name("text").value(value.text)
            }
            is UiOperation.IME_ACTION -> {
                out.name("type").value("ime_action")
                out.name("kind").value(value.kind.name)
            }
            is UiOperation.GLOBAL_ACTION -> {
                out.name("type").value("global_action")
                out.name("kind").value(value.kind)
            }
        }
        out.endObject()
    }

    override fun read(reader: JsonReader): UiOperation? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        val path = reader.path
        val objectValue = JsonParser.parseReader(reader).asJsonObject
        val type = objectValue.get("type")?.takeUnless { it.isJsonNull }?.asString
            ?: throw JsonParseException("Missing operation type at path $path.type")
        return when (type) {
            "tap" -> UiOperation.TAP
            "long_press" -> UiOperation.LONG_PRESS
            "swipe" -> UiOperation.SWIPE
            "set_text" -> UiOperation.SET_TEXT(requiredString(objectValue, "text", path))
            "ime_action" -> UiOperation.IME_ACTION(
                strictEnum<ImeActionKind>(requiredString(objectValue, "kind", path), "$path.kind")
            )
            "global_action" -> UiOperation.GLOBAL_ACTION(requiredString(objectValue, "kind", path))
            else -> throw JsonParseException("Unknown UiOperation type '$type' at path $path.type")
        }
    }

    private fun requiredString(value: JsonObject, name: String, path: String): String =
        value.get(name)?.takeUnless { it.isJsonNull }?.asString
            ?: throw JsonParseException("Missing $name at path $path.$name")

    private inline fun <reified T : Enum<T>> strictEnum(value: String, path: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw JsonParseException("Unknown ${T::class.java.simpleName} '$value' at path $path")
}

private class StrictEnumTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        if (!raw.isEnum) return null
        val constants = raw.enumConstants.associateBy { (it as Enum<*>).name }
        @Suppress("UNCHECKED_CAST")
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T?) {
                if (value == null) out.nullValue() else out.value((value as Enum<*>).name)
            }

            override fun read(reader: JsonReader): T? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                val path = reader.path
                val value = reader.nextString()
                return constants[value] as? T
                    ?: throw JsonParseException("Unknown ${raw.simpleName} '$value' at path $path")
            }
        }
    }
}

private class DiscriminatedTypeAdapterFactory<T> private constructor(
    private val baseType: Class<T>
) : TypeAdapterFactory {
    private val labelsToSubtypes = linkedMapOf<String, Class<out T>>()
    private val subtypesToLabels = linkedMapOf<Class<out T>, String>()

    fun subtype(label: String, subtype: Class<out T>): DiscriminatedTypeAdapterFactory<T> = apply {
        require(label !in labelsToSubtypes && subtype !in subtypesToLabels)
        labelsToSubtypes[label] = subtype
        subtypesToLabels[subtype] = label
    }

    override fun <R> create(gson: Gson, type: TypeToken<R>): TypeAdapter<R>? {
        if (type.rawType != baseType) return null
        val labelDelegates = labelsToSubtypes.mapValues { (_, subtype) ->
            @Suppress("UNCHECKED_CAST")
            gson.getDelegateAdapter(this, TypeToken.get(subtype)) as TypeAdapter<out T>
        }
        val subtypeDelegates = labelsToSubtypes.entries.associate { (label, subtype) ->
            subtype to labelDelegates.getValue(label)
        }

        @Suppress("UNCHECKED_CAST")
        return object : TypeAdapter<R>() {
            override fun write(out: JsonWriter, value: R?) {
                if (value == null) {
                    out.nullValue()
                    return
                }
                val runtimeClass = value.javaClass as Class<out T>
                val label = subtypesToLabels[runtimeClass]
                    ?: throw JsonParseException("Unregistered ${baseType.simpleName} subtype ${runtimeClass.name}")
                @Suppress("UNCHECKED_CAST")
                val delegate = subtypeDelegates.getValue(runtimeClass) as TypeAdapter<R>
                val body = delegate.toJsonTree(value).asJsonObject
                val result = JsonObject().apply {
                    addProperty("type", label)
                    body.entrySet().forEach { (name, element) -> add(name, element) }
                }
                Streams.write(result, out)
            }

            override fun read(reader: JsonReader): R? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                val path = reader.path
                val body = Streams.parse(reader).asJsonObject
                val labelElement = body.remove("type")
                    ?: throw JsonParseException("Missing ${baseType.simpleName} type at path $path.type")
                val label = labelElement.asString
                val delegate = labelDelegates[label]
                    ?: throw JsonParseException("Unknown ${baseType.simpleName} type '$label' at path $path.type")
                @Suppress("UNCHECKED_CAST")
                return delegate.fromJsonTree(body) as R
            }
        }
    }

    companion object {
        fun <T> of(baseType: Class<T>) = DiscriminatedTypeAdapterFactory(baseType)
    }
}
