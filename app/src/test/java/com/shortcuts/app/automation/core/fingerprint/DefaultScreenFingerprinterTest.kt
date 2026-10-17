package com.shortcuts.app.automation.core.fingerprint

import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultScreenFingerprinterTest {

    private val fingerprinter = DefaultScreenFingerprinter()

    @Test
    fun `same screen with a few list rows changed must stay at or above 0_80`() {
        val windowBefore = window("com.example.app", id = 1, bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1, bounds = r(0, 0, 1000, 2000)) {
                // Toolbar in top 25% (0..500)
                node("android.widget.Toolbar", id = 2, resourceId = "com.example.app:id/toolbar", bounds = r(0, 0, 1000, 150)) {
                    node("android.widget.TextView", id = 3, resourceId = "com.example.app:id/title", text = "Messages", bounds = r(50, 40, 400, 110))
                }
                // List container
                node("androidx.recyclerview.widget.RecyclerView", id = 4, resourceId = "com.example.app:id/list", bounds = r(0, 150, 1000, 2000)) {
                    node("android.widget.LinearLayout", id = 10, resourceId = "com.example.app:id/row", bounds = r(0, 150, 1000, 300)) {
                        node("android.widget.TextView", id = 11, resourceId = "com.example.app:id/sender", text = "Alice", bounds = r(20, 160, 300, 220))
                        node("android.widget.TextView", id = 12, resourceId = "com.example.app:id/snippet", text = "Hello there!", bounds = r(20, 230, 800, 280))
                    }
                    node("android.widget.LinearLayout", id = 20, resourceId = "com.example.app:id/row", bounds = r(0, 300, 1000, 450)) {
                        node("android.widget.TextView", id = 21, resourceId = "com.example.app:id/sender", text = "Bob", bounds = r(20, 310, 300, 370))
                        node("android.widget.TextView", id = 22, resourceId = "com.example.app:id/snippet", text = "Are you available?", bounds = r(20, 380, 800, 430))
                    }
                    node("android.widget.LinearLayout", id = 30, resourceId = "com.example.app:id/row", bounds = r(0, 450, 1000, 600)) {
                        node("android.widget.TextView", id = 31, resourceId = "com.example.app:id/sender", text = "Charlie", bounds = r(20, 460, 300, 520))
                        node("android.widget.TextView", id = 32, resourceId = "com.example.app:id/snippet", text = "See you tomorrow", bounds = r(20, 530, 800, 580))
                    }
                }
            }
        }

        // Window after: Bob and Charlie messages changed to Dave and Eve
        val windowAfter = window("com.example.app", id = 1, bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1, bounds = r(0, 0, 1000, 2000)) {
                node("android.widget.Toolbar", id = 2, resourceId = "com.example.app:id/toolbar", bounds = r(0, 0, 1000, 150)) {
                    node("android.widget.TextView", id = 3, resourceId = "com.example.app:id/title", text = "Messages", bounds = r(50, 40, 400, 110))
                }
                node("androidx.recyclerview.widget.RecyclerView", id = 4, resourceId = "com.example.app:id/list", bounds = r(0, 150, 1000, 2000)) {
                    node("android.widget.LinearLayout", id = 10, resourceId = "com.example.app:id/row", bounds = r(0, 150, 1000, 300)) {
                        node("android.widget.TextView", id = 11, resourceId = "com.example.app:id/sender", text = "Alice", bounds = r(20, 160, 300, 220))
                        node("android.widget.TextView", id = 12, resourceId = "com.example.app:id/snippet", text = "Hello there!", bounds = r(20, 230, 800, 280))
                    }
                    node("android.widget.LinearLayout", id = 20, resourceId = "com.example.app:id/row", bounds = r(0, 300, 1000, 450)) {
                        node("android.widget.TextView", id = 21, resourceId = "com.example.app:id/sender", text = "Dave", bounds = r(20, 310, 300, 370))
                        node("android.widget.TextView", id = 22, resourceId = "com.example.app:id/snippet", text = "Lunch at 12?", bounds = r(20, 380, 800, 430))
                    }
                    node("android.widget.LinearLayout", id = 30, resourceId = "com.example.app:id/row", bounds = r(0, 450, 1000, 600)) {
                        node("android.widget.TextView", id = 31, resourceId = "com.example.app:id/sender", text = "Eve", bounds = r(20, 460, 300, 520))
                        node("android.widget.TextView", id = 32, resourceId = "com.example.app:id/snippet", text = "Got the files", bounds = r(20, 530, 800, 580))
                    }
                }
            }
        }

        val fp1 = fingerprinter.fingerprint(windowBefore, "com.example.app.InboxActivity")
        val fp2 = fingerprinter.fingerprint(windowAfter, "com.example.app.InboxActivity")

        val similarity = fingerprinter.similarity(fp1, fp2)
        assertTrue("Expected similarity >= 0.80 but was $similarity", similarity >= 0.80)
    }

    @Test
    fun `a different screen of the same app must fall below 0_80`() {
        val inboxWindow = window("com.example.app", id = 1, bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.FrameLayout", id = 1, bounds = r(0, 0, 1000, 2000)) {
                node("android.widget.Toolbar", id = 2, resourceId = "com.example.app:id/toolbar", bounds = r(0, 0, 1000, 150)) {
                    node("android.widget.TextView", id = 3, resourceId = "com.example.app:id/title", text = "Inbox", bounds = r(50, 40, 400, 110))
                }
                node("androidx.recyclerview.widget.RecyclerView", id = 4, resourceId = "com.example.app:id/message_list", bounds = r(0, 150, 1000, 2000))
            }
        }

        val settingsWindow = window("com.example.app", id = 2, bounds = r(0, 0, 1000, 2000)) {
            node("android.widget.ScrollView", id = 10, bounds = r(0, 0, 1000, 2000)) {
                node("android.widget.LinearLayout", id = 11, bounds = r(0, 0, 1000, 2000)) {
                    node("android.widget.TextView", id = 12, resourceId = "com.example.app:id/header", text = "Account Settings", bounds = r(20, 50, 500, 120))
                    node("android.widget.Switch", id = 13, resourceId = "com.example.app:id/notifications_toggle", bounds = r(20, 150, 900, 250))
                    node("android.widget.Button", id = 14, resourceId = "com.example.app:id/logout_button", text = "Log out", bounds = r(20, 300, 400, 400))
                }
            }
        }

        val fpInbox = fingerprinter.fingerprint(inboxWindow, "com.example.app.InboxActivity")
        val fpSettings = fingerprinter.fingerprint(settingsWindow, "com.example.app.SettingsActivity")

        val similarity = fingerprinter.similarity(fpInbox, fpSettings)
        assertTrue("Expected similarity < 0.80 for different screens, but was $similarity", similarity < 0.80)
    }

    @Test
    fun `different package returns 0_0 similarity`() {
        val windowA = window("com.example.appA", id = 1) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.TextView", id = 2, text = "Hello")
            }
        }
        val windowB = window("com.example.appB", id = 2) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.TextView", id = 2, text = "Hello")
            }
        }

        val fpA = fingerprinter.fingerprint(windowA, "com.example.appA.MainActivity")
        val fpB = fingerprinter.fingerprint(windowB, "com.example.appA.MainActivity")

        assertEquals(0.0, fingerprinter.similarity(fpA, fpB), 0.001)
    }

    @Test
    fun `fingerprint is deterministic and repeatable`() {
        val win = window("com.example.app", id = 1) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.Toolbar", id = 2, resourceId = "com.example.app:id/toolbar") {
                    node("android.widget.TextView", id = 3, resourceId = "com.example.app:id/title", text = "Profile")
                }
            }
        }

        val fp1 = fingerprinter.fingerprint(win, "ProfileActivity")
        val fp2 = fingerprinter.fingerprint(win, "ProfileActivity")

        assertEquals(fp1.structuralTokens, fp2.structuralTokens)
        assertEquals(fp1.landmarkTokens, fp2.landmarkTokens)
        assertEquals(fp1.structureHash, fp2.structureHash)
        assertEquals(1.0, fingerprinter.similarity(fp1, fp2), 0.0001)
    }

    @Test
    fun `invisible nodes are excluded from fingerprint tokens`() {
        val win = window("com.example.app", id = 1) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.TextView", id = 2, text = "Visible", visible = true)
                node("android.widget.TextView", id = 3, text = "Hidden", visible = false)
            }
        }

        val fp = fingerprinter.fingerprint(win, "MainActivity")
        val hiddenTextHash = DefaultScreenFingerprinter.fnv1a64("text:Hidden")
        assertFalse(fp.landmarkTokens.contains(hiddenTextHash))
    }
}
