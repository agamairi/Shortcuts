package com.shortcuts.app.automation.android.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class RealHomeActivitiesTest {

    @Test
    fun `Settings' boot-time FallbackHome is not a launcher`() {
        val homes = listOf(
            "com.google.android.apps.nexuslauncher" to 0,
            "app.olauncher" to 0,
            "com.android.settings" to -1000
        )

        assertEquals(
            setOf("com.google.android.apps.nexuslauncher", "app.olauncher"),
            resolveHomePackages(realHomeActivities(homes))
        )
    }
}
