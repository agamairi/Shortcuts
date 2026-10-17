package com.shortcuts.app.automation.core.fingerprint

import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.ScreenFingerprint

/**
 * Summarises "which screen is this" without retaining raw text, so a step can wait for the
 * screen it was recorded on and verify that an action moved to the expected next screen.
 * Capture produces fingerprints; replay compares them.
 */
interface ScreenFingerprinter {
    fun fingerprint(window: WindowSnapshot, activityClass: String?): ScreenFingerprint

    /** 1.0 for the same screen, 0.0 for unrelated screens. Different packages are always 0.0. */
    fun similarity(a: ScreenFingerprint, b: ScreenFingerprint): Double
}
