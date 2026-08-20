package com.shortcuts.app.ui.screens

import com.shortcuts.app.data.ActionType
import com.shortcuts.app.planner.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for the madlib slot model — pure Kotlin, no Android/Compose dependencies.
 *
 * Covers:
 * 1. Each template exposes only slot values its slot types allow.
 * 2. A device-control slot NEVER offers a control the executor does not implement.
 * 3. "Inspire me" always produces a fully-populated, valid combination.
 * 4. Confirming a completed template builds an Automation whose actions match the slots.
 */
class MadlibSlotModelTest {

    // -----------------------------------------------------------------------
    // 1. Templates expose only allowed slot types
    // -----------------------------------------------------------------------

    @Test
    fun `APP_AND_SETTING first slot is DeviceControlSlot`() {
        val state = defaultMadlibState(MadlibTemplate.APP_AND_SETTING)
        assertTrue(
            "First slot of APP_AND_SETTING must be DeviceControlSlot",
            state.firstSlot is MadlibSlot.DeviceControlSlot
        )
    }

    @Test
    fun `APP_AND_SETTING second slot is App slot`() {
        val state = defaultMadlibState(MadlibTemplate.APP_AND_SETTING)
        assertTrue(
            "Second slot of APP_AND_SETTING must be App slot",
            state.secondSlot is MadlibSlot.App
        )
    }

    @Test
    fun `QUICK_TOGGLE first slot is StateSlot`() {
        val state = defaultMadlibState(MadlibTemplate.QUICK_TOGGLE)
        assertTrue(state.firstSlot is MadlibSlot.StateSlot)
    }

    @Test
    fun `QUICK_TOGGLE second slot is DeviceControlSlot`() {
        val state = defaultMadlibState(MadlibTemplate.QUICK_TOGGLE)
        assertTrue(state.secondSlot is MadlibSlot.DeviceControlSlot)
    }

    @Test
    fun `MESSAGE_SOMEONE both slots are TextSlots`() {
        val state = defaultMadlibState(MadlibTemplate.MESSAGE_SOMEONE)
        assertTrue(state.firstSlot is MadlibSlot.TextSlot)
        assertTrue(state.secondSlot is MadlibSlot.TextSlot)
    }

    @Test
    fun `WEBHOOK first slot is TextSlot with GET and POST`() {
        val state = defaultMadlibState(MadlibTemplate.WEBHOOK)
        val first = state.firstSlot
        assertTrue(first is MadlibSlot.TextSlot)
        val options = (first as MadlibSlot.TextSlot).options
        assertTrue("GET must be an option", "GET" in options)
        assertTrue("POST must be an option", "POST" in options)
        // No other HTTP verbs invented beyond GET and POST
        assertEquals(2, options.size)
    }

    // -----------------------------------------------------------------------
    // 2. Device-control slot NEVER offers a control the executor does not implement
    // -----------------------------------------------------------------------

    /**
     * The executor's handleSystemToggle handles exactly these normalised IDs.
     * If a new one is added to the executor it should be added to SUPPORTED_DEVICE_CONTROLS,
     * and if one is removed from the executor it should be removed from there.
     */
    private val executorHandledTargets = setOf(
        "flashlight", "torch",           // executor: "flashlight", "torch"
        "donotdisturb", "dnd",           // executor: "donotdisturb", "dnd"
        "volume",
        "ringmode", "ringer",
        "autorotate", "rotation",
        "wifi",
        "bluetooth",
        "airplanemode",
        "location"
    )

    @Test
    fun `all SUPPORTED_DEVICE_CONTROLS are handled by the executor`() {
        for (control in SUPPORTED_DEVICE_CONTROLS) {
            assertTrue(
                "Control '${control.id}' is in SUPPORTED_DEVICE_CONTROLS but the executor does not handle it",
                control.id in executorHandledTargets
            )
        }
    }

    @Test
    fun `DeviceControlSlot only offers supported controls`() {
        val slot = MadlibSlot.DeviceControlSlot()
        for (control in slot.controls) {
            assertTrue(
                "DeviceControlSlot offers '${control.id}' which is not handled by the executor",
                control.id in executorHandledTargets
            )
        }
    }

    @Test
    fun `cycling DeviceControlSlot stays within supported controls`() {
        var slot: MadlibSlot = MadlibSlot.DeviceControlSlot()
        val seen = mutableSetOf<String>()
        val iterations = SUPPORTED_DEVICE_CONTROLS.size * 2
        repeat(iterations) {
            val ds = slot as MadlibSlot.DeviceControlSlot
            seen.add(ds.controlId)
            slot = slot.next()
        }
        for (id in seen) {
            assertTrue(
                "Cycling produced '$id' which is not in SUPPORTED_DEVICE_CONTROLS",
                id in SUPPORTED_DEVICE_CONTROLS.map { it.id }
            )
        }
    }

    @Test
    fun `StateSlot only offers on, off, toggle`() {
        val slot = MadlibSlot.StateSlot()
        val validValues = setOf("on", "off", "toggle")
        for (state in slot.states) {
            assertTrue(
                "'${state.value}' is not a valid executor toggle state",
                state.value in validValues
            )
        }
        assertEquals(3, slot.states.size)
    }

    // -----------------------------------------------------------------------
    // 3. "Inspire me" always produces a fully-populated, valid combination
    // -----------------------------------------------------------------------

    @Test
    fun `inspireMe on QUICK_TOGGLE produces valid state and isComplete`() {
        val apps = listOf(InstalledApp("Spotify", "com.spotify.music", true))
        val state = defaultMadlibState(MadlibTemplate.QUICK_TOGGLE, apps)
        val inspired = state.withRandomSlots(Random(42))
        assertTrue("Inspired QUICK_TOGGLE must be complete", inspired.isComplete)
    }

    @Test
    fun `inspireMe on APP_AND_SETTING with apps produces isComplete`() {
        val apps = listOf(
            InstalledApp("Spotify", "com.spotify.music", true),
            InstalledApp("Chrome", "com.android.chrome", false)
        )
        val state = defaultMadlibState(MadlibTemplate.APP_AND_SETTING, apps)
        val inspired = state.withRandomSlots(Random(7))
        // isComplete requires a non-null packageName, which is true when apps is non-empty
        assertTrue("Inspired APP_AND_SETTING must be complete when apps are loaded", inspired.isComplete)
    }

    @Test
    fun `inspireMe on MESSAGE_SOMEONE always produces isComplete`() {
        val state = defaultMadlibState(MadlibTemplate.MESSAGE_SOMEONE)
        // The slots have hard-coded options so isComplete is always true after inspire
        val inspired = state.withRandomSlots(Random(99))
        assertTrue(inspired.isComplete)
    }

    @Test
    fun `inspireMe on WEBHOOK always produces isComplete`() {
        val state = defaultMadlibState(MadlibTemplate.WEBHOOK)
        val inspired = state.withRandomSlots(Random(1))
        assertTrue(inspired.isComplete)
    }

    @Test
    fun `inspireMe slot values are within the allowed set`() {
        val apps = listOf(InstalledApp("Maps", "com.google.maps", false))
        for (template in MadlibTemplate.values()) {
            val state = defaultMadlibState(template, apps)
            val inspired = state.withRandomSlots()
            // Slot indices must be within bounds — withRandomSlots must not produce out-of-range
            val firstSlot = inspired.firstSlot
            val secondSlot = inspired.secondSlot
            val firstCount = when (firstSlot) {
                is MadlibSlot.DeviceControlSlot -> firstSlot.controls.size
                is MadlibSlot.App -> maxOf(1, firstSlot.apps.size)
                is MadlibSlot.StateSlot -> firstSlot.states.size
                is MadlibSlot.TextSlot -> maxOf(1, firstSlot.options.size)
            }
            val secondCount = when (secondSlot) {
                is MadlibSlot.DeviceControlSlot -> secondSlot.controls.size
                is MadlibSlot.App -> maxOf(1, secondSlot.apps.size)
                is MadlibSlot.StateSlot -> secondSlot.states.size
                is MadlibSlot.TextSlot -> maxOf(1, secondSlot.options.size)
            }
            assertTrue(firstSlot.selectedIndex in 0 until firstCount)
            assertTrue(secondSlot.selectedIndex in 0 until secondCount)
        }
    }

    // -----------------------------------------------------------------------
    // 4. Confirming a completed template builds an Automation matching the slots
    // -----------------------------------------------------------------------

    @Test
    fun `QUICK_TOGGLE on wifi builds SYSTEM_TOGGLE action with correct target and state`() {
        val state = defaultMadlibState(MadlibTemplate.QUICK_TOGGLE).let { base ->
            // select "wifi" in the second slot
            val wifiIndex = SUPPORTED_DEVICE_CONTROLS.indexOfFirst { it.id == "wifi" }
            assertTrue(wifiIndex >= 0)
            base.copy(secondSlot = (base.secondSlot as MadlibSlot.DeviceControlSlot).withIndex(wifiIndex))
        }.let { base ->
            // select "on" in the first slot
            val onIndex = ToggleStateOption.values().indexOfFirst { it.value == "on" }
            base.copy(firstSlot = (base.firstSlot as MadlibSlot.StateSlot).withIndex(onIndex))
        }

        assertTrue(state.isComplete)
        val automation = state.buildAutomation()
        assertNotNull(automation)
        val actions = com.shortcuts.app.data.ActionConverter().toActionList(automation!!.actionsJson)
        assertEquals(1, actions.size)
        val action = actions[0]
        assertEquals(ActionType.SYSTEM_TOGGLE, action.actionType)
        assertEquals("wifi", action.target)
        assertEquals("on", action.state)
    }

    @Test
    fun `APP_AND_SETTING builds two actions of correct types`() {
        val apps = listOf(InstalledApp("Chrome", "com.android.chrome", false))
        val state = defaultMadlibState(MadlibTemplate.APP_AND_SETTING, apps)
        assertTrue(state.isComplete)
        val automation = state.buildAutomation()
        assertNotNull(automation)
        val actions = com.shortcuts.app.data.ActionConverter().toActionList(automation!!.actionsJson)
        assertEquals(2, actions.size)
        assertEquals(ActionType.SYSTEM_TOGGLE, actions[0].actionType)
        assertEquals(ActionType.APP_INTENT, actions[1].actionType)
        assertEquals("com.android.chrome", actions[1].packageName)
    }

    @Test
    fun `APP_AND_SETTING without apps is not complete`() {
        val state = defaultMadlibState(MadlibTemplate.APP_AND_SETTING, emptyList())
        assertFalse("APP_AND_SETTING without apps should not be complete", state.isComplete)
        assertNull(state.buildAutomation())
    }

    @Test
    fun `MESSAGE_SOMEONE builds SEND_MESSAGE action with correct recipient and body`() {
        val state = defaultMadlibState(MadlibTemplate.MESSAGE_SOMEONE)
        // Both TextSlots have default options populated
        assertTrue(state.isComplete)
        val automation = state.buildAutomation()
        assertNotNull(automation)
        val actions = com.shortcuts.app.data.ActionConverter().toActionList(automation!!.actionsJson)
        assertEquals(1, actions.size)
        assertEquals(ActionType.SEND_MESSAGE, actions[0].actionType)
        assertNotNull(actions[0].target)   // recipient
        assertNotNull(actions[0].textInput) // message body
    }

    @Test
    fun `WEBHOOK builds HTTP_REQUEST action with correct method and HTTPS url`() {
        val state = defaultMadlibState(MadlibTemplate.WEBHOOK)
        assertTrue(state.isComplete)
        val automation = state.buildAutomation()
        assertNotNull(automation)
        val actions = com.shortcuts.app.data.ActionConverter().toActionList(automation!!.actionsJson)
        assertEquals(1, actions.size)
        val action = actions[0]
        assertEquals(ActionType.HTTP_REQUEST, action.actionType)
        // method should be uppercase GET or POST
        assertTrue(action.method in listOf("GET", "POST"))
        // url must be HTTPS (the model enforces this)
        assertTrue(action.url?.startsWith("https://") == true)
    }

    @Test
    fun `automation triggerType is TEMPLATE`() {
        val state = defaultMadlibState(MadlibTemplate.QUICK_TOGGLE)
        val automation = state.buildAutomation()
        assertNotNull(automation)
        assertEquals("TEMPLATE", automation!!.triggerType)
    }

    @Test
    fun `advancing slot cycles through all options and wraps`() {
        var slot: MadlibSlot = MadlibSlot.DeviceControlSlot()
        val total = SUPPORTED_DEVICE_CONTROLS.size
        // Advance total times should wrap back to index 0
        repeat(total) { slot = slot.next() }
        assertEquals(0, slot.selectedIndex)
    }

    @Test
    fun `withIndex clamps out of range values`() {
        val slot = MadlibSlot.DeviceControlSlot()
        val clamped = slot.withIndex(1000)
        assertTrue(clamped.selectedIndex in 0 until SUPPORTED_DEVICE_CONTROLS.size)
    }
}
