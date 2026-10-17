package com.shortcuts.app.automation.android.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class BackNavigationInferenceTest {
    private val pkg = "com.example.app"

    @Test fun `quiet direct pop infers Back`() {
        val inference = seededHomeProfileEdit()

        val result = inference.onActivityChanged(pkg, "com.example.app.ProfileActivity", 2_000)

        assertNotNull(result)
        assertEquals("com.example.app.EditActivity", result!!.fromActivityClass)
        assertEquals("com.example.app.ProfileActivity", result.toActivityClass)
    }

    @Test fun `pop soon after tap does not infer`() {
        val inference = seededHomeProfileEdit()
        inference.onInteraction(1_800)

        assertNull(inference.onActivityChanged(pkg, "com.example.app.ProfileActivity", 2_000))
    }

    @Test fun `forward navigation never infers`() {
        val inference = BackNavigationInference()
        inference.onActivityChanged(pkg, "com.example.app.HomeActivity", 1_000)
        inference.onActivityChanged(pkg, "com.example.app.ProfileActivity", 2_000)

        assertNull(inference.onActivityChanged(pkg, "com.example.app.EditActivity", 3_000))
    }

    @Test fun `cross package change never infers`() {
        val inference = seededHomeProfileEdit()

        assertNull(inference.onActivityChanged("com.example.other", "com.example.other.HomeActivity", 2_000))
    }

    @Test fun `unknown activity never infers`() {
        val inference = seededHomeProfileEdit()

        assertNull(inference.onActivityChanged(pkg, null, 2_000))
    }

    @Test fun `delivered Back key suppresses inference`() {
        val inference = seededHomeProfileEdit()
        inference.onKeyDeliveredGlobalAction("BACK", 1_900)

        assertNull(inference.onActivityChanged(pkg, "com.example.app.ProfileActivity", 2_000))
    }

    @Test fun `non inferred pop still updates history`() {
        val inference = seededHomeProfileEdit()
        inference.onInteraction(1_900)
        assertNull(inference.onActivityChanged(pkg, "com.example.app.ProfileActivity", 2_000))

        assertEquals(listOf("com.example.app.HomeActivity", "com.example.app.ProfileActivity"), inference.historyForTest(pkg))
    }

    @Test fun `Save finish is ignored and later Back is inferred once`() {
        val inference = seededHomeProfileEdit()
        inference.onInteraction(1_800) // Tap Save; Edit finishes 200ms later.
        assertNull(inference.onActivityChanged(pkg, "com.example.app.ProfileActivity", 2_000))

        assertNotNull(inference.onActivityChanged(pkg, "com.example.app.HomeActivity", 2_900))
        assertEquals(listOf("com.example.app.HomeActivity"), inference.historyForTest(pkg))
    }

    private fun seededHomeProfileEdit(): BackNavigationInference = BackNavigationInference().also {
        it.onActivityChanged(pkg, "com.example.app.HomeActivity", 0)
        it.onActivityChanged(pkg, "com.example.app.ProfileActivity", 500)
        it.onActivityChanged(pkg, "com.example.app.EditActivity", 1_000)
    }
}
