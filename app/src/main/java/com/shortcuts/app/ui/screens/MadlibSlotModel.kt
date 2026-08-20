package com.shortcuts.app.ui.screens

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.planner.InstalledApp
import com.google.gson.Gson

// ---------------------------------------------------------------------------
// Authoritative list of device controls the executor actually implements.
// Derived from ActionExecutorService.handleSystemToggle's `when` branches.
// DO NOT add controls here that do not appear in that `when`.
// ---------------------------------------------------------------------------

/** Every system-toggle target the executor's handleSystemToggle actually handles. */
val SUPPORTED_DEVICE_CONTROLS: List<DeviceControl> = listOf(
    DeviceControl("flashlight", "Flashlight"),
    DeviceControl("donotdisturb", "Do Not Disturb"),
    DeviceControl("volume", "Volume"),
    DeviceControl("ringmode", "Ring Mode"),
    DeviceControl("autorotate", "Auto-Rotate"),
    DeviceControl("wifi", "Wi-Fi"),
    DeviceControl("bluetooth", "Bluetooth"),
    DeviceControl("airplanemode", "Airplane Mode"),
    DeviceControl("location", "Location")
)

/** A device toggle the executor can handle — (normalizedId, displayLabel). */
data class DeviceControl(
    /** Normalized key accepted by the executor — lowercase, no underscores. */
    val id: String,
    val displayLabel: String
)

/** The three states accepted by ActionExecutorService.normalizedToggleState. */
enum class ToggleStateOption(val value: String, val displayLabel: String) {
    ON("on", "On"),
    OFF("off", "Off"),
    TOGGLE("toggle", "Toggle")
}

// ---------------------------------------------------------------------------
// Slot types — each slot carries the set of values it may take.
// ---------------------------------------------------------------------------

/**
 * A typed slot in a madlib sentence.
 * Sealed so templates can't accidentally mix incompatible options.
 */
sealed interface MadlibSlot {
    val displayLabel: String

    /** Selected index into the appropriate options list for this slot type. */
    val selectedIndex: Int

    /** The display label of the currently selected value. */
    val currentLabel: String

    /** Cycle to the next valid value. */
    fun next(): MadlibSlot

    /** Set to an arbitrary valid index (clamped). */
    fun withIndex(index: Int): MadlibSlot

    /**
     * App slot — values are drawn from the real installed-apps list provided at runtime.
     * If the list hasn't been loaded yet the slot shows a placeholder.
     */
    data class App(
        override val displayLabel: String = "an app",
        override val selectedIndex: Int = 0,
        val apps: List<InstalledApp> = emptyList()
    ) : MadlibSlot {
        override val currentLabel: String
            get() = apps.getOrNull(selectedIndex)?.userVisibleLabel ?: displayLabel

        override fun next(): MadlibSlot = copy(
            selectedIndex = if (apps.isEmpty()) 0 else (selectedIndex + 1) % apps.size
        )

        override fun withIndex(index: Int): MadlibSlot = copy(
            selectedIndex = if (apps.isEmpty()) 0 else index.coerceIn(0, apps.size - 1)
        )

        /** The resolved package name, or null when no apps are loaded. */
        val packageName: String?
            get() = apps.getOrNull(selectedIndex)?.packageName
    }

    /**
     * Device-control slot — only controls from [SUPPORTED_DEVICE_CONTROLS] are offered.
     */
    data class DeviceControlSlot(
        override val displayLabel: String = "a setting",
        override val selectedIndex: Int = 0,
        val controls: List<DeviceControl> = SUPPORTED_DEVICE_CONTROLS
    ) : MadlibSlot {
        override val currentLabel: String
            get() = controls.getOrNull(selectedIndex)?.displayLabel ?: displayLabel

        override fun next(): MadlibSlot = copy(
            selectedIndex = (selectedIndex + 1) % controls.size
        )

        override fun withIndex(index: Int): MadlibSlot = copy(
            selectedIndex = index.coerceIn(0, controls.size - 1)
        )

        val controlId: String
            get() = controls.getOrNull(selectedIndex)?.id ?: controls.first().id
    }

    /** State slot — on / off / toggle. */
    data class StateSlot(
        override val displayLabel: String = "on",
        override val selectedIndex: Int = 0,
        val states: List<ToggleStateOption> = ToggleStateOption.values().toList()
    ) : MadlibSlot {
        override val currentLabel: String
            get() = states.getOrNull(selectedIndex)?.displayLabel ?: displayLabel

        override fun next(): MadlibSlot = copy(
            selectedIndex = (selectedIndex + 1) % states.size
        )

        override fun withIndex(index: Int): MadlibSlot = copy(
            selectedIndex = index.coerceIn(0, states.size - 1)
        )

        val stateValue: String
            get() = states.getOrNull(selectedIndex)?.value ?: "toggle"
    }

    /** Free-text slot — for Webhook / Message templates where the value is typed. */
    data class TextSlot(
        override val displayLabel: String = "a value",
        override val selectedIndex: Int = 0,
        val options: List<String> = emptyList(),
        val typedValue: String = ""
    ) : MadlibSlot {
        override val currentLabel: String
            get() = if (typedValue.isNotBlank()) typedValue
            else options.getOrNull(selectedIndex) ?: displayLabel

        override fun next(): MadlibSlot = if (options.isEmpty()) this else copy(
            selectedIndex = (selectedIndex + 1) % options.size
        )

        override fun withIndex(index: Int): MadlibSlot = copy(
            selectedIndex = if (options.isEmpty()) 0 else index.coerceIn(0, options.size - 1)
        )
    }
}

// ---------------------------------------------------------------------------
// Templates — each defines a sentence structure and its slot types.
// ---------------------------------------------------------------------------

enum class MadlibTemplate(val displayName: String) {
    APP_AND_SETTING("App + setting"),
    QUICK_TOGGLE("Quick toggle"),
    MESSAGE_SOMEONE("Message someone"),
    WEBHOOK("Webhook")
}

/**
 * A single madlib sentence instance: the template it belongs to, the current
 * slot values, and methods to build an [Automation] from those values.
 *
 * @param template  Which template shape is active.
 * @param firstSlot The first tappable slot in the sentence.
 * @param secondSlot The second tappable slot in the sentence.
 * @param installedApps Apps to populate [MadlibSlot.App] slots with.
 */
data class MadlibState(
    val template: MadlibTemplate = MadlibTemplate.APP_AND_SETTING,
    val firstSlot: MadlibSlot = MadlibSlot.DeviceControlSlot(),
    val secondSlot: MadlibSlot = MadlibSlot.App(),
    val installedApps: List<InstalledApp> = emptyList()
) {
    /** Prose parts of the sentence that surround the slots. */
    val leadIn: String get() = sentenceShape.leadIn
    val midText: String get() = sentenceShape.mid
    val trailText: String get() = sentenceShape.trail

    /** How many steps the confirmed automation will contain (preview label). */
    val stepCount: Int
        get() = when (template) {
            MadlibTemplate.APP_AND_SETTING -> 2
            MadlibTemplate.QUICK_TOGGLE    -> 1
            MadlibTemplate.MESSAGE_SOMEONE -> 1
            MadlibTemplate.WEBHOOK         -> 1
        }

    val previewStepsLabel: String
        get() = if (stepCount == 1) "1 step" else "$stepCount steps"

    /** True when every slot has a usable value and the automation can be confirmed. */
    val isComplete: Boolean
        get() = when (template) {
            MadlibTemplate.APP_AND_SETTING ->
                (firstSlot as? MadlibSlot.DeviceControlSlot) != null &&
                (secondSlot as? MadlibSlot.App)?.packageName != null
            MadlibTemplate.QUICK_TOGGLE ->
                (firstSlot as? MadlibSlot.StateSlot) != null &&
                (secondSlot as? MadlibSlot.DeviceControlSlot) != null
            MadlibTemplate.MESSAGE_SOMEONE ->
                (firstSlot as? MadlibSlot.TextSlot)?.currentLabel?.isNotBlank() == true &&
                (secondSlot as? MadlibSlot.TextSlot)?.currentLabel?.isNotBlank() == true
            MadlibTemplate.WEBHOOK ->
                (firstSlot as? MadlibSlot.TextSlot)?.currentLabel?.isNotBlank() == true &&
                (secondSlot as? MadlibSlot.TextSlot)?.currentLabel?.isNotBlank() == true
        }

    /**
     * Builds the [Automation] the confirmed template represents.
     * Returns null if the slot combination is not complete.
     */
    fun buildAutomation(): Automation? {
        if (!isComplete) return null
        val gson = Gson()
        val actions = buildActions() ?: return null
        val name = buildName()
        return Automation(
            name = name,
            actionsJson = gson.toJson(actions),
            triggerType = "TEMPLATE"
        )
    }

    private fun buildActions(): List<Action>? {
        return when (template) {
            MadlibTemplate.APP_AND_SETTING -> {
                val control = (firstSlot as? MadlibSlot.DeviceControlSlot) ?: return null
                val app = (secondSlot as? MadlibSlot.App) ?: return null
                val pkg = app.packageName ?: return null
                listOf(
                    Action(
                        actionType = ActionType.SYSTEM_TOGGLE,
                        target = control.controlId,
                        state = "on"
                    ),
                    Action(
                        actionType = ActionType.APP_INTENT,
                        packageName = pkg
                    )
                )
            }
            MadlibTemplate.QUICK_TOGGLE -> {
                val state = (firstSlot as? MadlibSlot.StateSlot) ?: return null
                val control = (secondSlot as? MadlibSlot.DeviceControlSlot) ?: return null
                listOf(
                    Action(
                        actionType = ActionType.SYSTEM_TOGGLE,
                        target = control.controlId,
                        state = state.stateValue
                    )
                )
            }
            MadlibTemplate.MESSAGE_SOMEONE -> {
                val recipient = (firstSlot as? MadlibSlot.TextSlot) ?: return null
                val message = (secondSlot as? MadlibSlot.TextSlot) ?: return null
                listOf(
                    Action(
                        actionType = ActionType.SEND_MESSAGE,
                        target = recipient.currentLabel,
                        textInput = message.currentLabel
                    )
                )
            }
            MadlibTemplate.WEBHOOK -> {
                val method = (firstSlot as? MadlibSlot.TextSlot) ?: return null
                val url = (secondSlot as? MadlibSlot.TextSlot) ?: return null
                listOf(
                    Action(
                        actionType = ActionType.HTTP_REQUEST,
                        method = method.currentLabel.uppercase(),
                        url = "https://${url.currentLabel.removePrefix("https://").removePrefix("http://")}"
                    )
                )
            }
        }
    }

    private fun buildName(): String = when (template) {
        MadlibTemplate.APP_AND_SETTING ->
            "Turn on ${firstSlot.currentLabel}, open ${secondSlot.currentLabel}"
        MadlibTemplate.QUICK_TOGGLE ->
            "${firstSlot.currentLabel.replaceFirstChar { it.uppercase() }} ${secondSlot.currentLabel}"
        MadlibTemplate.MESSAGE_SOMEONE ->
            "Message ${firstSlot.currentLabel}"
        MadlibTemplate.WEBHOOK ->
            "${firstSlot.currentLabel} ${secondSlot.currentLabel}"
    }

    private val sentenceShape: SentenceShape get() = when (template) {
        MadlibTemplate.APP_AND_SETTING ->
            SentenceShape("Turn on", "then open", "")
        MadlibTemplate.QUICK_TOGGLE ->
            SentenceShape("Turn", "the", "")
        MadlibTemplate.MESSAGE_SOMEONE ->
            SentenceShape("Text", "saying", "")
        MadlibTemplate.WEBHOOK ->
            SentenceShape("Send a", "request to", "")
    }
}

private data class SentenceShape(val leadIn: String, val mid: String, val trail: String)

// ---------------------------------------------------------------------------
// Factory: build the initial MadlibState for a given template.
// ---------------------------------------------------------------------------

/**
 * Constructs a fresh [MadlibState] for [template] pre-populated with [installedApps].
 * Index values start at 0 (first item in each slot).
 */
fun defaultMadlibState(
    template: MadlibTemplate = MadlibTemplate.APP_AND_SETTING,
    installedApps: List<InstalledApp> = emptyList()
): MadlibState = when (template) {
    MadlibTemplate.APP_AND_SETTING -> MadlibState(
        template = template,
        firstSlot = MadlibSlot.DeviceControlSlot(),
        secondSlot = MadlibSlot.App(apps = installedApps),
        installedApps = installedApps
    )
    MadlibTemplate.QUICK_TOGGLE -> MadlibState(
        template = template,
        firstSlot = MadlibSlot.StateSlot(),
        secondSlot = MadlibSlot.DeviceControlSlot(),
        installedApps = installedApps
    )
    MadlibTemplate.MESSAGE_SOMEONE -> MadlibState(
        template = template,
        firstSlot = MadlibSlot.TextSlot(
            displayLabel = "a contact",
            options = listOf("Mum", "Dad", "Priya")
        ),
        secondSlot = MadlibSlot.TextSlot(
            displayLabel = "a message",
            options = listOf("running late", "on my way", "call me")
        ),
        installedApps = installedApps
    )
    MadlibTemplate.WEBHOOK -> MadlibState(
        template = template,
        firstSlot = MadlibSlot.TextSlot(
            displayLabel = "GET",
            options = listOf("GET", "POST")
        ),
        secondSlot = MadlibSlot.TextSlot(
            displayLabel = "a web address",
            options = listOf("home-assistant.local", "ifttt.com/trigger")
        ),
        installedApps = installedApps
    )
}

/**
 * "Inspire me" — returns a [MadlibState] with every slot set to a random valid index.
 * Always produces a complete state so [MadlibState.isComplete] will be true (assuming
 * apps are loaded for APP_AND_SETTING / MESSAGE_SOMEONE / WEBHOOK which have options).
 */
fun MadlibState.withRandomSlots(random: kotlin.random.Random = kotlin.random.Random): MadlibState {
    fun randomIndex(slot: MadlibSlot): Int = when (slot) {
        is MadlibSlot.App -> if (slot.apps.isEmpty()) 0 else random.nextInt(slot.apps.size)
        is MadlibSlot.DeviceControlSlot -> random.nextInt(slot.controls.size)
        is MadlibSlot.StateSlot -> random.nextInt(slot.states.size)
        is MadlibSlot.TextSlot -> if (slot.options.isEmpty()) 0 else random.nextInt(slot.options.size)
    }
    return copy(
        firstSlot = firstSlot.withIndex(randomIndex(firstSlot)),
        secondSlot = secondSlot.withIndex(randomIndex(secondSlot))
    )
}
