package com.shortcuts.app.planner

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClauseAlignerTest {
    private val aligner = ClauseAligner { packageName ->
        if (packageName == "com.android.chrome") "Chrome" else null
    }

    @Test
    fun `one returned action is assigned to its second clause rather than positionally`() {
        val chrome = app("com.android.chrome")

        val aligned = aligner.align(listOf("turn on wifi", "open Chrome"), listOf(chrome))

        assertNull(aligned[0])
        assertEquals(chrome, aligned[1])
    }

    @Test
    fun `two returned actions align to the first and third of three clauses`() {
        val wifi = toggle("wifi")
        val flashlight = toggle("flashlight")

        val aligned = aligner.align(
            listOf("turn on wifi", "open Chrome", "turn on the flashlight"),
            listOf(wifi, flashlight)
        )

        assertEquals(wifi, aligned[0])
        assertNull(aligned[1])
        assertEquals(flashlight, aligned[2])
    }

    @Test
    fun `aligned output preserves clause order when model emits calls in another order`() {
        val wifi = toggle("wifi")
        val chrome = app("com.android.chrome")

        val aligned = aligner.align(
            listOf("turn on wifi", "open Chrome"),
            listOf(chrome, wifi)
        )

        assertEquals(listOf(wifi, chrome), aligned)
    }

    @Test
    fun `an action with no clause evidence remains unassigned`() {
        val bluetooth = toggle("bluetooth")

        val aligned = aligner.align(listOf("open Chrome", "turn on wifi"), listOf(bluetooth))

        assertEquals(listOf(null, null), aligned)
    }

    @Test
    fun `a tie between clauses leaves the action unassigned`() {
        val wifi = toggle("wifi")

        val aligned = aligner.align(listOf("turn on wifi", "enable wi-fi"), listOf(wifi))

        assertEquals(listOf(null, null), aligned)
    }

    private fun toggle(target: String) = Action(
        actionType = ActionType.SYSTEM_TOGGLE,
        target = target,
        state = "on"
    )

    private fun app(packageName: String) = Action(
        actionType = ActionType.APP_INTENT,
        packageName = packageName
    )
}
