package com.shortcuts.app.automation.android

import android.accessibilityservice.AccessibilityService
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.automation.android.replay.AndroidUiEnvironment
import com.shortcuts.app.automation.android.replay.AndroidUiEventHub
import com.shortcuts.app.automation.core.matching.elementSelector
import com.shortcuts.app.automation.core.tree.labelOf
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.LocatorStrategy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidUiEnvironmentIdentityTest {

    private val adapter: AccessibilitySnapshotAdapter = mockk(relaxed = true)
    private val service: AccessibilityService = mockk(relaxed = true)
    private lateinit var environment: AndroidUiEnvironment

    @Before
    fun setUp() {
        AndroidUiEventHub.resetForTesting()
        environment = AndroidUiEnvironment(adapter, service)
    }

    @After
    fun tearDown() {
        AndroidUiEventHub.resetForTesting()
    }

    private fun mockLiveNode(
        className: String,
        bounds: com.shortcuts.app.automation.core.tree.IntRect = r(100, 100, 500, 300),
        packageName: String = "com.example.app",
        resourceId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        children: List<AccessibilityNodeInfo> = emptyList()
    ): AccessibilityNodeInfo {
        val mock = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mock.className } returns className
        every { mock.packageName } returns packageName
        every { mock.viewIdResourceName } returns resourceId
        every { mock.text } returns text
        every { mock.contentDescription } returns contentDescription
        every { mock.getBoundsInScreen(any()) } answers {
            val r = firstArg<Rect>()
            r.left = bounds.left
            r.top = bounds.top
            r.right = bounds.right
            r.bottom = bounds.bottom
        }
        every { mock.childCount } returns children.size
        for (i in children.indices) {
            every { mock.getChild(i) } answers { children[i] }
        }
        return mock
    }

    @Test
    fun `Compose-style clickable View with child TextView Save is accepted for tap`() {
        // Snapshot tree: Clickable View with child TextView containing "Save"
        val window = window("com.example.app") {
            node("android.view.View", id = 1, bounds = r(100, 100, 500, 300), clickable = true) {
                node("android.widget.TextView", id = 2, text = "Save", bounds = r(120, 120, 480, 280))
            }
        }
        val expectedNode = window.node(1)!!
        val derivedLabel = window.labelOf(expectedNode)
        assertEquals("Save", derivedLabel.text)

        // Live tree: Clickable View has text = null; its child TextView has text = "Save"
        val liveChild = mockLiveNode(
            className = "android.widget.TextView",
            bounds = r(120, 120, 480, 280),
            text = "Save"
        )
        val liveContainer = mockLiveNode(
            className = "android.view.View",
            bounds = r(100, 100, 500, 300),
            text = null,
            children = listOf(liveChild)
        )

        val selector = elementSelector(
            strategies = listOf(
                LocatorStrategy(0, LocatorKind.TEXT_EXACT, "Save", LocatorStability.STABLE)
            )
        )

        val rejection = environment.validateLiveIdentity(
            liveNode = liveContainer,
            expectedNode = expectedNode,
            selector = selector,
            isScroll = false,
            derivedLabel = derivedLabel
        )

        assertNull("Tap on composite descendant-labelled node must be accepted", rejection)
    }

    @Test
    fun `Compose-style IconButton View with child View contentDescription Settings gear is accepted for tap`() {
        // Snapshot tree: IconButton View with child View carrying content description
        val window = window("com.example.app") {
            node("android.view.View", id = 1, bounds = r(100, 100, 200, 200), clickable = true) {
                node("android.view.View", id = 2, contentDescription = "Settings gear", bounds = r(110, 110, 190, 190))
            }
        }
        val expectedNode = window.node(1)!!
        val derivedLabel = window.labelOf(expectedNode)
        assertEquals("Settings gear", derivedLabel.contentDescription)

        // Live tree: IconButton View has contentDescription = null; child carries "Settings gear"
        val liveChild = mockLiveNode(
            className = "android.view.View",
            bounds = r(110, 110, 190, 190),
            contentDescription = "Settings gear"
        )
        val liveButton = mockLiveNode(
            className = "android.view.View",
            bounds = r(100, 100, 200, 200),
            contentDescription = null,
            children = listOf(liveChild)
        )

        val selector = elementSelector(
            strategies = listOf(
                LocatorStrategy(0, LocatorKind.CONTENT_DESCRIPTION_EXACT, "Settings gear", LocatorStability.STABLE)
            )
        )

        val rejection = environment.validateLiveIdentity(
            liveNode = liveButton,
            expectedNode = expectedNode,
            selector = selector,
            isScroll = false,
            derivedLabel = derivedLabel
        )

        assertNull("Tap on IconButton with child content description must be accepted", rejection)
    }

    @Test
    fun `scroll on container is accepted with no label check`() {
        val window = window("com.example.app") {
            node("androidx.recyclerview.widget.RecyclerView", id = 10, bounds = r(0, 0, 1080, 2000), scrollable = true)
        }
        val expectedContainer = window.node(10)!!

        val liveContainer = mockLiveNode(
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = r(0, 0, 1080, 2000),
            text = null
        )

        // The step might carry child element's selector
        val childSelector = elementSelector(
            strategies = listOf(
                LocatorStrategy(0, LocatorKind.TEXT_EXACT, "Off-screen Item", LocatorStability.STABLE)
            )
        )

        val rejection = environment.validateLiveIdentity(
            liveNode = liveContainer,
            expectedNode = expectedContainer,
            selector = childSelector,
            isScroll = true,
            derivedLabel = null
        )

        assertNull("Scroll on container must be accepted without label checks", rejection)
    }

    @Test
    fun `scroll on wrong element at container position is rejected`() {
        val window = window("com.example.app") {
            node("androidx.recyclerview.widget.RecyclerView", id = 10, bounds = r(0, 0, 1080, 2000), scrollable = true)
        }
        val expectedContainer = window.node(10)!!

        // A different element (e.g. TextView) now occupies the container's bounds
        val wrongElement = mockLiveNode(
            className = "android.widget.TextView",
            bounds = r(0, 0, 1080, 2000)
        )

        val rejection = environment.validateLiveIdentity(
            liveNode = wrongElement,
            expectedNode = expectedContainer,
            isScroll = true
        )

        assertNotNull(rejection)
        assertEquals("Class mismatch for snapshot target", rejection!!.reason)
    }

    @Test
    fun `composite node is rejected when child label changed`() {
        val window = window("com.example.app") {
            node("android.view.View", id = 1, bounds = r(100, 100, 500, 300), clickable = true) {
                node("android.widget.TextView", id = 2, text = "Save", bounds = r(120, 120, 480, 280))
            }
        }
        val expectedNode = window.node(1)!!
        val derivedLabel = window.labelOf(expectedNode)

        // Live tree text changed to "Delete"
        val liveChild = mockLiveNode(
            className = "android.widget.TextView",
            bounds = r(120, 120, 480, 280),
            text = "Delete"
        )
        val liveContainer = mockLiveNode(
            className = "android.view.View",
            bounds = r(100, 100, 500, 300),
            children = listOf(liveChild)
        )

        val rejection = environment.validateLiveIdentity(
            liveNode = liveContainer,
            expectedNode = expectedNode,
            isScroll = false,
            derivedLabel = derivedLabel
        )

        assertNotNull(rejection)
        assertEquals("Selector identity changed for snapshot target", rejection!!.reason)
    }

    @Test
    fun `AndroidUiEventHub recognizes non-standard activity names and rejects non-activities`() {
        assertTrue(AndroidUiEventHub.isLikelyActivityClass("com.android.settings.Settings"))
        assertTrue(AndroidUiEventHub.isLikelyActivityClass("com.android.settings.SubSettings"))
        assertTrue(AndroidUiEventHub.isLikelyActivityClass("com.example.MainActivity"))

        // Obvious non-activities
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("android.widget.PopupWindow\$PopupDecorView"))
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("com.google.android.material.snackbar.Snackbar\$SnackbarLayout"))
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("android.widget.Toast\$TN"))
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("androidx.appcompat.app.AlertDialog"))
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("com.google.android.material.bottomsheet.BottomSheetDialog"))
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("androidx.appcompat.view.menu.MenuPopupHelper"))

        // Framework views
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("android.widget.Button"))
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("android.view.View"))
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("androidx.compose.ui.platform.ComposeView"))
        assertFalse(AndroidUiEventHub.isLikelyActivityClass("NoPackageClass"))
    }

    @Test
    fun `AndroidUiEventHub confirms with PackageManager and caches results`() {
        val pm = mockk<PackageManager>()
        AndroidUiEventHub.packageManager = pm

        every { pm.getActivityInfo(any(), any<Int>()) } returns mockk<ActivityInfo>()

        // Real activity passes PM check
        assertTrue(AndroidUiEventHub.isLikelyActivity("com.example.app", "com.example.app.CustomScreen"))
        // Subsequent call uses cache
        assertTrue(AndroidUiEventHub.isLikelyActivity("com.example.app", "com.example.app.CustomScreen"))
        verify(exactly = 1) { pm.getActivityInfo(any(), any<Int>()) }

        every { pm.getActivityInfo(any(), any<Int>()) } throws PackageManager.NameNotFoundException()

        // Non-activity fails PM check and is cached as false
        assertFalse(AndroidUiEventHub.isLikelyActivity("com.example.app", "com.example.app.SomeRandomView"))
        assertFalse(AndroidUiEventHub.isLikelyActivity("com.example.app", "com.example.app.SomeRandomView"))
        verify(exactly = 2) { pm.getActivityInfo(any(), any<Int>()) }
    }

    @Test
    fun `AndroidUiEventHub onAccessibilityEvent records real activities and ignores non-activities`() {
        val pm = mockk<PackageManager>()
        AndroidUiEventHub.packageManager = pm

        every { pm.getActivityInfo(any(), any<Int>()) } returns mockk<ActivityInfo>()

        val realEvent = mockk<AccessibilityEvent>(relaxed = true)
        every { realEvent.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { realEvent.packageName } returns "com.android.settings"
        every { realEvent.className } returns "com.android.settings.Settings"

        AndroidUiEventHub.onAccessibilityEvent(realEvent)
        assertEquals("com.android.settings.Settings", AndroidUiEventHub.currentActivityClass("com.android.settings"))

        // Transient non-activity popup event does NOT overwrite currentActivityClass
        val popupEvent = mockk<AccessibilityEvent>(relaxed = true)
        every { popupEvent.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { popupEvent.packageName } returns "com.android.settings"
        every { popupEvent.className } returns "android.widget.PopupWindow\$PopupDecorView"

        AndroidUiEventHub.onAccessibilityEvent(popupEvent)
        assertEquals("com.android.settings.Settings", AndroidUiEventHub.currentActivityClass("com.android.settings"))

        // Snackbar event does NOT overwrite
        val snackbarEvent = mockk<AccessibilityEvent>(relaxed = true)
        every { snackbarEvent.eventType } returns AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        every { snackbarEvent.packageName } returns "com.android.settings"
        every { snackbarEvent.className } returns "com.google.android.material.snackbar.Snackbar\$SnackbarLayout"

        AndroidUiEventHub.onAccessibilityEvent(snackbarEvent)
        assertEquals("com.android.settings.Settings", AndroidUiEventHub.currentActivityClass("com.android.settings"))
    }

    @Test
    fun `typed-into field recorded by its hint is accepted for keyboard submit`() {
        // Recorded while empty, so the field's text was its hint "Search". Replay has since typed into it.
        val window = window("com.example.app") {
            node(
                "android.widget.EditText",
                id = 1,
                resourceId = "com.example.app:id/edit_search",
                text = "rk test",
                hintText = "Search",
                editable = true,
                bounds = r(100, 100, 500, 300)
            )
        }
        val expectedNode = window.node(1)!!
        val liveField = mockLiveNode(
            className = "android.widget.EditText",
            resourceId = "com.example.app:id/edit_search",
            text = "rk test"
        )
        every { liveField.isEditable } returns true
        every { liveField.hintText } returns "Search"

        val selector = elementSelector(
            strategies = listOf(
                LocatorStrategy(0, LocatorKind.RESOURCE_ID_EXACT, "com.example.app:id/edit_search", LocatorStability.STABLE),
                LocatorStrategy(1, LocatorKind.TEXT_EXACT, "Search", LocatorStability.STABLE)
            )
        )

        val rejection = environment.validateLiveIdentity(
            liveNode = liveField,
            expectedNode = expectedNode,
            selector = selector,
            derivedLabel = window.labelOf(expectedNode)
        )

        assertNull("Typed-into field must still be recognised by its hint", rejection)
    }

    @Test
    fun `non-editable node is not recognised by a hint`() {
        val window = window("com.example.app") {
            node("android.widget.TextView", id = 1, text = "Other", bounds = r(100, 100, 500, 300))
        }
        val expectedNode = window.node(1)!!
        val liveNode = mockLiveNode(className = "android.widget.TextView", text = "Other")
        every { liveNode.isEditable } returns false
        every { liveNode.hintText } returns "Search"

        val selector = elementSelector(
            strategies = listOf(LocatorStrategy(0, LocatorKind.TEXT_EXACT, "Search", LocatorStability.STABLE))
        )

        val rejection = environment.validateLiveIdentity(
            liveNode = liveNode,
            expectedNode = expectedNode,
            selector = selector,
            derivedLabel = null
        )

        assertNotNull(rejection)
    }
}
