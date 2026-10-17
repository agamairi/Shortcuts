package com.shortcuts.app.automation.integration

import com.shortcuts.app.automation.core.fingerprint.DefaultScreenFingerprinter
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintStabilityTest {

    private val fingerprinter = DefaultScreenFingerprinter()

    @Test
    fun `same screen remains stable through the plausible replay changes`() {
        val cases = listOf(
            "list scrolled by three rows" to (repeatedOpenList(0).windows.single() to repeatedOpenList(3).windows.single()),
            "settings extra row" to (settingsRootSnapshot(false).windows.single() to settingsRootSnapshot(true).windows.single()),
            "compose inset shift" to (composeScreen().windows.single() to composeScreenShifted(40).windows.single()),
            "accessibility overlay" to (overlayScreen(false).windows.single() to overlayScreen(true).windows.last())
        )

        cases.forEach { (name, pair) ->
            val before = fingerprinter.fingerprint(pair.first, "com.example.same.Screen")
            val after = fingerprinter.fingerprint(pair.second, "com.example.same.Screen")
            val similarity = fingerprinter.similarity(before, after)
            assertTrue("$name changed fingerprint too much: $similarity", similarity >= 0.80)
        }
    }

    @Test
    fun `different settings screens remain distinguishable within the same package`() {
        val settings = settingsRootSnapshot().windows.single()
        val network = settingsNetworkWindow()
        val rootFingerprint = fingerprinter.fingerprint(settings, "com.android.settings.SettingsActivity")
        val networkFingerprint = fingerprinter.fingerprint(network, "com.android.settings.NetworkDashboardActivity")

        val similarity = fingerprinter.similarity(rootFingerprint, networkFingerprint)
        assertTrue("Settings root and Network screen look too similar: $similarity", similarity < 0.80)
    }

    private fun settingsNetworkWindow(): WindowSnapshot = com.shortcuts.app.automation.core.tree.window(
        "com.android.settings",
        id = 2,
        bounds = com.shortcuts.app.automation.core.tree.r(0, 0, 1_000, 2_000)
    ) {
        node("android.widget.FrameLayout", id = 1, bounds = com.shortcuts.app.automation.core.tree.r(0, 0, 1_000, 2_000)) {
            node(
                "android.widget.Toolbar",
                id = 2,
                resourceId = "com.android.settings:id/action_bar",
                bounds = com.shortcuts.app.automation.core.tree.r(0, 0, 1_000, 150)
            ) {
                node(
                    "android.widget.TextView",
                    id = 3,
                    resourceId = "com.android.settings:id/action_bar_title",
                    text = "Network & internet",
                    bounds = com.shortcuts.app.automation.core.tree.r(40, 40, 700, 110)
                )
            }
            node("android.widget.ScrollView", id = 10, bounds = com.shortcuts.app.automation.core.tree.r(0, 150, 1_000, 2_000), scrollable = true) {
                node("android.widget.LinearLayout", id = 11, bounds = com.shortcuts.app.automation.core.tree.r(0, 150, 1_000, 2_000)) {
                    node("android.widget.TextView", id = 12, text = "Wi-Fi", bounds = com.shortcuts.app.automation.core.tree.r(40, 230, 800, 300))
                    node("android.widget.TextView", id = 13, text = "Mobile network", bounds = com.shortcuts.app.automation.core.tree.r(40, 400, 800, 470))
                    node("android.widget.TextView", id = 14, text = "Hotspot & tethering", bounds = com.shortcuts.app.automation.core.tree.r(40, 570, 800, 640))
                    node("android.widget.TextView", id = 15, text = "VPN", bounds = com.shortcuts.app.automation.core.tree.r(40, 740, 800, 810))
                }
            }
        }
    }
}

