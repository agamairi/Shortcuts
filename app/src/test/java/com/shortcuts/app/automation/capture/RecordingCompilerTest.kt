package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.LaunchPolicy
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NodeBooleanState
import com.shortcuts.app.automation.model.RevealDirection
import com.shortcuts.app.automation.model.StepSafety
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.VerificationPostcondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCompilerTest {
    @Test
    fun `interaction already inside app synthesizes reset launch first and keeps double tap`() {
        val app = screen(APP, "Increment")
        val before = snapshot(app, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("workflow", before))
        compiler.accept(interaction(before, down = 1_100, up = 1_150))
        compiler.accept(interaction(before, down = 1_250, up = 1_300))
        compiler.updateScreen(before.copy(capturedAtUptimeMs = 1_400))

        val workflow = compiler.finish()

        assertEquals(3, workflow.steps.size)
        assertEquals(LaunchPolicy.RESET_TO_ROOT, (workflow.steps[0] as LaunchAppStep).policy)
        assertTrue((workflow.steps[1] as UiStep).operation == UiOperation.TAP)
        assertTrue((workflow.steps[2] as UiStep).operation == UiOperation.TAP)
        assertNotEquals(workflow.steps[1].id, workflow.steps[2].id)
        assertEquals(420, workflow.recordedDevice.densityDpi)
        assertEquals("Google", workflow.recordedDevice.manufacturer)
    }

    @Test
    fun `long press and click emitted by the same physical gesture compile once`() {
        val app = screen(APP, "Menu")
        val before = snapshot(app, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("workflow", before))
        compiler.accept(interaction(before, kind = GestureKind.LONG_PRESS, down = 1_100, up = 1_700))
        compiler.accept(interaction(before, kind = GestureKind.TAP, down = 1_100, up = 1_710))

        val steps = compiler.finish().steps

        assertEquals(2, steps.size)
        assertTrue((steps[1] as UiStep).operation == UiOperation.LONG_PRESS)
    }

    @Test
    fun `app still drawing at launch takes its window from the first later event`() {
        val home = snapshot(screen(LAUNCHER, "Settings"), 1_000)
        // The launch is captured before the app has a window: the snapshot still shows the home screen.
        val stillHome = snapshot(screen(LAUNCHER, "Settings"), 1_300)
        val settings = snapshot(screen(APP, "Network & internet"), 2_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("workflow", home))
        compiler.accept(interaction(home, down = 1_050))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "SettingsActivity", stillHome, 1_300))
        compiler.accept(interaction(settings, down = 2_000))

        val steps = compiler.finish().steps

        val launch = steps.first() as LaunchAppStep
        assertEquals(APP, launch.expectedWindow.packageName)
        assertEquals(APP, (launch.target as com.shortcuts.app.automation.model.LaunchTarget.Launcher).packageName)
        assertEquals(LaunchPolicy.RESET_TO_ROOT, launch.policy)
    }

    @Test
    fun `launch snapshot with no app window still compiles the declared target`() {
        val home = snapshot(screen(LAUNCHER, "Settings"), 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("workflow", home))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "SettingsActivity", home, 1_300))

        val launch = compiler.finish().steps.single() as LaunchAppStep

        assertEquals(APP, launch.expectedWindow.packageName)
        assertEquals(APP, (launch.target as com.shortcuts.app.automation.model.LaunchTarget.Launcher).packageName)
    }

    @Test
    fun `first same-package window state still drafts the required launch`() {
        val app = snapshot(screen(APP, "About phone"), 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("workflow", app))
        compiler.accept(CapturedAppSwitch(APP, APP, "com.android.settings.SubSettings", app, 1_100))

        val launch = compiler.finish().steps.single() as LaunchAppStep

        assertEquals(APP, launch.expectedWindow.packageName)
    }

    @Test
    fun `settings launch missed at transition is recovered before its swipe and tap`() {
        val settingsPackage = "com.android.settings"
        val home = snapshot(screen(LAUNCHER, "Settings"), 1_000)
        val stillHome = snapshot(screen(LAUNCHER, "Settings"), 1_300)
        val settingsList = snapshot(screen(settingsPackage, "About phone", nodeId = 2), 2_000)
        val about = snapshot(screen(settingsPackage, "Device details", nodeId = 3), 3_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("settings-about", home))
        compiler.accept(
            CapturedAppSwitch(
                LAUNCHER,
                settingsPackage,
                "com.android.settings.homepage.SettingsHomepageActivity",
                stillHome,
                1_300
            )
        )
        compiler.accept(
            interaction(
                settingsList,
                kind = GestureKind.SWIPE,
                down = 2_000,
                up = 2_360,
                start = IntPoint(500, 1_500),
                end = IntPoint(500, 500)
            )
        )
        compiler.accept(interaction(settingsList, down = 3_000, up = 3_050))
        compiler.accept(
            CapturedAppSwitch(
                settingsPackage,
                settingsPackage,
                "com.android.settings.SubSettings",
                about,
                3_400
            )
        )
        compiler.updateScreen(about)

        val steps = compiler.finish().steps

        assertTrue(steps.first() is LaunchAppStep)
        assertEquals(settingsPackage, (steps.first() as LaunchAppStep).expectedWindow.packageName)
        assertTrue(steps.drop(1).filterIsInstance<UiStep>().isNotEmpty())
    }

    @Test
    fun `launcher gestures are omitted and following app switch is reset launch`() {
        val home = snapshot(screen(LAUNCHER, "Settings"), 1_000)
        val settings = snapshot(screen(APP, "Network & internet"), 1_300)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("workflow", home))
        compiler.accept(interaction(home, down = 1_050))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "SettingsActivity", settings, 1_300))

        val steps = compiler.finish().steps

        assertEquals(1, steps.size)
        val launch = steps.single() as LaunchAppStep
        assertEquals(APP, launch.expectedWindow.packageName)
        assertEquals("SettingsActivity", launch.expectedWindow.observedActivityClass)
        assertEquals(LaunchPolicy.RESET_TO_ROOT, launch.policy)
    }

    @Test
    fun `any installed home app counts as a launcher, not only the first one found`() {
        val secondLauncher = "app.olauncher"
        val home = snapshot(screen(secondLauncher, "Settings"), 1_000)
        val settings = snapshot(screen(APP, "Network & internet"), 1_300)
        var id = 0
        val compiler = RecordingCompiler(
            launcherPackageNames = setOf(LAUNCHER, secondLauncher),
            recorderPackageName = RECORDER,
            screenFingerprinter = fakeFingerprinter,
            textTokenizer = fakeTokenizer,
            idGenerator = { "step-${++id}" },
            deviceMetadata = RecordingDeviceMetadata("Google", "Pixel", 36),
            imePackageNames = setOf("example.ime")
        )
        compiler.start(CaptureScreenContext("workflow", home))
        compiler.accept(interaction(home, down = 1_050))
        compiler.accept(CapturedAppSwitch(secondLauncher, APP, "SettingsActivity", settings, 1_300))

        val launch = compiler.finish().steps.single() as LaunchAppStep
        assertEquals(APP, launch.expectedWindow.packageName)
        assertEquals(LaunchPolicy.RESET_TO_ROOT, launch.policy)
    }

    @Test
    fun `tap-caused cross-app switch is verified as a window change and is not a launch`() {
        val source = snapshot(screen(APP, "Open link"), 1_000)
        val browser = snapshot(screen(OTHER_APP, "Article"), 1_500)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("workflow", source))
        compiler.accept(interaction(source, down = 1_100, up = 1_160))
        compiler.accept(CapturedAppSwitch(APP, OTHER_APP, "BrowserActivity", browser, 1_500))
        compiler.updateScreen(browser)

        val steps = compiler.finish().steps

        assertEquals(2, steps.size)
        assertEquals(1, steps.filterIsInstance<LaunchAppStep>().size)
        val tap = steps[1] as UiStep
        val postcondition = tap.verification.postconditions.single() as VerificationPostcondition.ExpectedWindow
        assertEquals(OTHER_APP, postcondition.selector.packageName)
        assertEquals(OTHER_APP, tap.expectedAfter!!.packageName)
    }

    @Test
    fun `notification switch creates resume launch while SystemUI interaction is omitted`() {
        val home = snapshot(screen(LAUNCHER, "App"), 500)
        val source = snapshot(screen(APP, "Inbox"), 1_000)
        val systemUi = snapshot(screen("com.android.systemui", "Notification"), 1_500)
        val target = snapshot(screen(OTHER_APP, "Message"), 2_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("notification", home))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "InboxActivity", source, 1_000))
        compiler.accept(interaction(systemUi, down = 1_600))
        compiler.accept(CapturedAppSwitch("com.android.systemui", OTHER_APP, "MessageActivity", target, 2_000))

        val steps = compiler.finish().steps

        assertEquals(2, steps.size)
        assertEquals(
            listOf(LaunchPolicy.RESET_TO_ROOT, LaunchPolicy.RESUME_EXISTING_TASK),
            steps.filterIsInstance<LaunchAppStep>().map { it.policy }
        )
        assertTrue(steps.none { it is UiStep })
    }

    @Test
    fun `chooser selection remains navigation taps and does not invent app launches`() {
        val source = snapshot(screen(APP, "Share"), 1_000)
        val chooser = snapshot(screen("android", "Browser"), 1_500)
        val browser = snapshot(screen(OTHER_APP, "Article"), 2_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("chooser", source))
        compiler.accept(interaction(source, down = 1_100, up = 1_160))
        compiler.accept(CapturedAppSwitch(APP, "android", "ResolverActivity", chooser, 1_500))
        compiler.accept(interaction(chooser, down = 1_600, up = 1_660))
        compiler.accept(CapturedAppSwitch("android", OTHER_APP, "BrowserActivity", browser, 2_000))

        val steps = compiler.finish().steps

        assertEquals(1, steps.filterIsInstance<LaunchAppStep>().size)
        assertEquals(listOf(APP, "android"), steps.filterIsInstance<UiStep>().map { it.expectedWindow.packageName })
        assertTrue(
            steps.filterIsInstance<UiStep>().all {
                it.verification.postconditions.single() is VerificationPostcondition.ExpectedWindow
            }
        )
    }

    @Test
    fun `Recents switch and same-app Home return use resume policy`() {
        val home = snapshot(screen(LAUNCHER, "App"), 900)
        val app = snapshot(screen(APP, "Inbox"), 1_000)
        val other = snapshot(screen(OTHER_APP, "Browser"), 2_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("workflow", home))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "InboxActivity", app, 1_000))
        compiler.accept(CapturedGlobalAction("RECENTS", app, 1_500))
        compiler.accept(CapturedAppSwitch(APP, OTHER_APP, "BrowserActivity", other, 2_000))

        val firstWorkflow = compiler.finish()
        assertEquals(
            listOf(LaunchPolicy.RESET_TO_ROOT, LaunchPolicy.RESUME_EXISTING_TASK),
            firstWorkflow.steps.filterIsInstance<LaunchAppStep>().map { it.policy }
        )
        assertTrue((firstWorkflow.steps[1] as UiStep).operation is UiOperation.GLOBAL_ACTION)

        val secondCompiler = compiler()
        secondCompiler.start(CaptureScreenContext("return", home))
        secondCompiler.accept(CapturedAppSwitch(LAUNCHER, APP, "InboxActivity", app, 1_000))
        secondCompiler.accept(CapturedGlobalAction("HOME", app, 1_500))
        secondCompiler.accept(CapturedAppSwitch(APP, LAUNCHER, null, home.copy(capturedAtUptimeMs = 1_600), 1_600))
        secondCompiler.accept(CapturedAppSwitch(LAUNCHER, APP, "InboxActivity", app.copy(capturedAtUptimeMs = 2_000), 2_000))

        val secondWorkflow = secondCompiler.finish()
        assertEquals(
            listOf(LaunchPolicy.RESET_TO_ROOT, LaunchPolicy.RESUME_EXISTING_TASK),
            secondWorkflow.steps.filterIsInstance<LaunchAppStep>().map { it.policy }
        )
        assertEquals(3, secondWorkflow.steps.size)
    }

    @Test
    fun `launcher icon tap after Home resets the returning app`() {
        val home = snapshot(screen(LAUNCHER, "App"), 900)
        val app = snapshot(screen(APP, "Inbox"), 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("return", home))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "InboxActivity", app, 1_000))
        compiler.accept(CapturedGlobalAction("HOME", app, 1_500))
        compiler.accept(CapturedAppSwitch(APP, LAUNCHER, null, home.copy(capturedAtUptimeMs = 1_600), 1_600))
        compiler.accept(interaction(home.copy(capturedAtUptimeMs = 1_700), down = 1_700))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "InboxActivity", app.copy(capturedAtUptimeMs = 2_000), 2_000))

        val launches = compiler.finish().steps.filterIsInstance<LaunchAppStep>()

        assertEquals(listOf(LaunchPolicy.RESET_TO_ROOT, LaunchPolicy.RESET_TO_ROOT), launches.map { it.policy })
    }

    @Test
    fun `two id-less Compose fields remain separate and IME exists only when explicitly captured`() {
        val form = window(APP) {
            node("android.view.View", id = 1) {
                node(
                    "androidx.compose.ui.platform.ComposeView",
                    id = 2,
                    bounds = r(0, 100, 1_000, 1_500)
                ) {
                    node("TextField", id = 3, bounds = r(100, 200, 900, 350), editable = true, actions = setOf(NodeAction.SET_TEXT))
                    node("TextField", id = 4, bounds = r(100, 450, 900, 600), editable = true, actions = setOf(NodeAction.SET_TEXT))
                }
            }
        }
        val before = snapshot(form, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("form", before))
        compiler.accept(CapturedTextCommit(form.node(3)!!, "Ada", form, 1_200))
        compiler.accept(CapturedTextCommit(form.node(4)!!, "Lovelace", form, 1_500))
        compiler.updateScreen(before.copy(capturedAtUptimeMs = 1_600))

        val steps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(2, steps.size)
        assertEquals(listOf(UiOperation.SET_TEXT("Ada"), UiOperation.SET_TEXT("Lovelace")), steps.map { it.operation })
        assertNotEquals(steps[0].element!!.siblingIndex, steps[1].element!!.siblingIndex)
        assertNotEquals(steps[0].element!!.boundsInWindow, steps[1].element!!.boundsInWindow)
        assertFalse(steps.any { it.operation is UiOperation.IME_ACTION })
        assertTrue(steps.all { it.verification.postconditions.single() is VerificationPostcondition.TextEquals })
    }

    @Test
    fun `focusing tap remains before text commit and unchanged screen verifies next target confidence`() {
        val form = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.EditText",
                    id = 2,
                    resourceId = "example.app:id/name",
                    bounds = r(100, 200, 900, 350),
                    editable = true,
                    clickable = true,
                    actions = setOf(NodeAction.CLICK, NodeAction.SET_TEXT)
                )
            }
        }
        val before = snapshot(form, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("focus", before))
        compiler.accept(interaction(before, down = 1_100))
        compiler.accept(CapturedTextCommit(form.node(2)!!, "Ada", form, 1_500))

        val steps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(listOf(UiOperation.TAP, UiOperation.SET_TEXT("Ada")), steps.map { it.operation })
        val nextTarget = steps.first().verification.postconditions.single() as
            VerificationPostcondition.NextTargetConfidence
        assertEquals(steps.last().element, nextTarget.selector)
    }

    @Test
    fun `explicit IME action targets focused field while ordinary content changes invent nothing`() {
        val form = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.EditText",
                    id = 2,
                    text = "query",
                    bounds = r(100, 200, 900, 350),
                    editable = true,
                    focused = true,
                    actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER)
                )
            }
        }
        val before = snapshot(form, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("search", before))
        compiler.accept(interaction(before, down = 1_100))
        compiler.updateScreen(snapshot(form.copy(title = "suggestions changed"), 1_400))

        val withoutIme = compiler.finish().steps.filterIsInstance<UiStep>()
        assertEquals(1, withoutIme.size)
        assertFalse(withoutIme.any { it.operation is UiOperation.IME_ACTION })

        val explicitCompiler = compiler()
        explicitCompiler.start(CaptureScreenContext("search", before))
        explicitCompiler.accept(CapturedImeAction(form.node(2)!!, com.shortcuts.app.automation.model.ImeActionKind.SEARCH, form, 1_500))
        val explicit = explicitCompiler.finish().steps.filterIsInstance<UiStep>().single()
        assertEquals(
            UiOperation.IME_ACTION(com.shortcuts.app.automation.model.ImeActionKind.SEARCH),
            explicit.operation
        )
        assertEquals(2, explicit.element!!.targetNodeIdForTest(form))
    }

    @Test
    fun `password input is absent and warning is attached to field tap`() {
        val passwordWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.EditText",
                    id = 2,
                    resourceId = "example.app:id/password",
                    text = "super-secret",
                    hintText = "Password",
                    bounds = r(100, 200, 900, 350),
                    password = true,
                    editable = true,
                    clickable = true,
                    actions = setOf(NodeAction.CLICK, NodeAction.SET_TEXT)
                )
            }
        }
        val before = snapshot(passwordWindow, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("password", before))
        compiler.accept(interaction(before, down = 1_100))
        compiler.accept(CapturedTextCommit(passwordWindow.node(2)!!, "super-secret", passwordWindow, 1_500))

        val step = compiler.finish().steps.filterIsInstance<UiStep>().single()

        assertTrue(step.operation == UiOperation.TAP)
        assertEquals(listOf(RecordingCompiler.SENSITIVE_TEXT_WARNING), step.migrationWarnings)
        assertFalse(step.element!!.strategies.any { it.kind == LocatorKind.TEXT_EXACT })
        assertFalse(step.element!!.strategies.any { it.value.contains("super-secret") })
    }

    @Test
    fun `standalone swipe preserves direction path duration and scroll container`() {
        val list = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node("androidx.recyclerview.widget.RecyclerView", id = 2, scrollable = true, bounds = r(0, 100, 1_000, 1_800)) {
                    node("android.widget.TextView", id = 3, text = "Visible row", bounds = r(50, 900, 950, 1_100))
                }
            }
        }
        val before = snapshot(list, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("swipe", before))
        compiler.accept(
            interaction(
                before,
                nodeId = 3,
                kind = GestureKind.SWIPE,
                down = 1_100,
                up = 1_550,
                start = IntPoint(500, 1_400),
                end = IntPoint(500, 500)
            )
        )
        compiler.updateScreen(before.copy(capturedAtUptimeMs = 1_700))

        val step = compiler.finish().steps.filterIsInstance<UiStep>().single()

        assertTrue(step.operation == UiOperation.SWIPE)
        assertEquals(450L, step.gesture!!.durationMs)
        assertTrue(step.gesture!!.points.first().pointInWindow!!.y > step.gesture!!.points.last().pointInWindow!!.y)
        assertEquals("androidx.recyclerview.widget.RecyclerView", step.element!!.expectedClass)
        assertNull(step.reveal)
    }

    @Test
    fun `consecutive forward swipes collapse into reveal on newly visible target`() {
        val beforeList = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node("androidx.recyclerview.widget.RecyclerView", id = 2, scrollable = true) {
                    node("android.widget.TextView", id = 3, text = "Old row", bounds = r(50, 900, 950, 1_100))
                }
            }
        }
        val revealedList = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node("androidx.recyclerview.widget.RecyclerView", id = 2, scrollable = true) {
                    node("android.widget.Button", id = 9, text = "Developer options", bounds = r(50, 900, 950, 1_100), clickable = true)
                }
            }
        }
        val first = snapshot(beforeList, 1_000)
        val second = snapshot(beforeList, 1_500)
        val revealed = snapshot(revealedList, 2_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("reveal", first))
        compiler.accept(interaction(first, nodeId = 3, kind = GestureKind.SWIPE, down = 1_100, up = 1_400, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))
        compiler.accept(interaction(second, nodeId = 3, kind = GestureKind.SWIPE, down = 1_550, up = 1_850, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))
        compiler.accept(interaction(revealed, nodeId = 9, down = 2_050))

        val uiSteps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(1, uiSteps.size)
        assertTrue(uiSteps.single().operation == UiOperation.TAP)
        assertEquals(RevealDirection.FORWARD, uiSteps.single().reveal!!.direction)
    }

    @Test
    fun `trailing Home is dropped but nontrailing global action is retained`() {
        val app = snapshot(screen(APP, "Page"), 1_000)
        val home = snapshot(screen(LAUNCHER, "Home"), 1_500)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("home", app))
        compiler.accept(interaction(app, down = 1_100))
        compiler.accept(CapturedGlobalAction("HOME", app, 1_400))
        compiler.accept(CapturedAppSwitch(APP, LAUNCHER, null, home, 1_500))
        compiler.updateScreen(home)

        val steps = compiler.finish().steps

        assertEquals(2, steps.size)
        assertFalse(steps.filterIsInstance<UiStep>().any { it.operation == UiOperation.GLOBAL_ACTION("HOME") })
    }

    @Test
    fun `Back in the middle remains an explicit global step`() {
        val first = snapshot(screen(APP, "Details"), 1_000)
        val second = snapshot(screen(APP, "List"), 1_500)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("back", first))
        compiler.accept(CapturedGlobalAction("BACK", first, 1_100))
        compiler.accept(interaction(second, down = 1_600))

        val uiSteps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(2, uiSteps.size)
        assertEquals(UiOperation.GLOBAL_ACTION("BACK"), uiSteps.first().operation)
        assertTrue(uiSteps.last().operation == UiOperation.TAP)
    }

    @Test
    fun `inferred Back keeps departing screen as expected window and warning`() {
        val home = snapshot(screen(APP, "Home"), 1_000)
        val profile = snapshot(screen(APP, "Profile"), 2_000)
        val edit = snapshot(screen(APP, "Edit"), 3_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("inferred-back", home))
        compiler.accept(CapturedAppSwitch(null, APP, "com.example.HomeActivity", home, 1_000))
        compiler.accept(CapturedAppSwitch(APP, APP, "com.example.ProfileActivity", profile, 2_000))
        compiler.accept(CapturedAppSwitch(APP, APP, "com.example.EditActivity", edit, 3_000))
        compiler.accept(CapturedGlobalAction("BACK", edit, 4_000, inferred = true))
        compiler.accept(CapturedAppSwitch(APP, APP, "com.example.ProfileActivity", profile, 4_000))

        val step = compiler.finish().steps.filterIsInstance<UiStep>().single()

        assertEquals(UiOperation.GLOBAL_ACTION("BACK"), step.operation)
        assertEquals("com.example.EditActivity", step.expectedWindow.observedActivityClass)
        assertEquals("INFERRED_BACK", step.migrationWarnings.single())
    }

    @Test
    fun `verification prefers boolean state and destructive labels are irreversible`() {
        val unchecked = snapshot(screen(APP, "Delete", checked = false), 1_000)
        val checked = snapshot(screen(APP, "Delete", checked = true), 1_500)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("state", unchecked))
        compiler.accept(interaction(unchecked, down = 1_100))
        compiler.updateScreen(checked)

        val step = compiler.finish().steps.filterIsInstance<UiStep>().single()

        assertEquals(StepSafety.IRREVERSIBLE, step.safety)
        val state = step.verification.postconditions.single() as VerificationPostcondition.BooleanStateChanged
        assertEquals(NodeBooleanState.CHECKED, state.state)
        assertEquals(true, state.expected)
        assertTrue(step.verification.allowBestEffort.not())
    }

    private fun safetyOfTapOn(window: com.shortcuts.app.automation.core.tree.WindowSnapshot): StepSafety {
        val before = snapshot(window, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("safety", before))
        compiler.accept(interaction(before, down = 1_100))
        return compiler.finish().steps.filterIsInstance<UiStep>().single().safety
    }

    @Test
    fun `icon-only send button is irreversible because of its resource id`() {
        assertEquals(StepSafety.IRREVERSIBLE, safetyOfTapOn(screen(APP, "", resourceId = "com.app:id/btnSendMessage")))
    }

    @Test
    fun `place order and transfer labels are irreversible`() {
        assertEquals(StepSafety.IRREVERSIBLE, safetyOfTapOn(screen(APP, "Place order")))
        assertEquals(StepSafety.IRREVERSIBLE, safetyOfTapOn(screen(APP, "Transfer money")))
        assertEquals(StepSafety.IRREVERSIBLE, safetyOfTapOn(screen(APP, "Publish")))
    }

    @Test
    fun `a tap with no label and no id is best effort, an ordinary label is reversible`() {
        assertEquals(StepSafety.BEST_EFFORT, safetyOfTapOn(screen(APP, "")))
        assertEquals(StepSafety.REVERSIBLE, safetyOfTapOn(screen(APP, "Open")))
        assertEquals(StepSafety.REVERSIBLE, safetyOfTapOn(screen(APP, "Settings", resourceId = "com.app:id/settings_row")))
    }

    @Test
    fun `keyboard send is irreversible and keyboard search is not`() {
        val form = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.EditText",
                    id = 2,
                    text = "hi",
                    bounds = r(100, 200, 900, 350),
                    editable = true,
                    focused = true,
                    actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER)
                )
            }
        }
        fun imeSafety(kind: com.shortcuts.app.automation.model.ImeActionKind): StepSafety {
            val before = snapshot(form, 1_000)
            val compiler = compiler()
            compiler.start(CaptureScreenContext("ime", before))
            compiler.accept(CapturedImeAction(form.node(2)!!, kind, form, 1_500))
            return compiler.finish().steps.filterIsInstance<UiStep>().single().safety
        }
        assertEquals(StepSafety.IRREVERSIBLE, imeSafety(com.shortcuts.app.automation.model.ImeActionKind.SEND))
        assertEquals(StepSafety.IRREVERSIBLE, imeSafety(com.shortcuts.app.automation.model.ImeActionKind.UNSPECIFIED))
        assertEquals(StepSafety.REVERSIBLE, imeSafety(com.shortcuts.app.automation.model.ImeActionKind.SEARCH))
    }

    @Test
    fun `captured delay uses previous item gap`() {
        val home = snapshot(screen(LAUNCHER, "App"), 500)
        val app = snapshot(screen(APP, "Page"), 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("timing", home))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "PageActivity", app, 1_000))
        compiler.accept(interaction(app, down = 1_725, up = 1_800))

        val steps = compiler.finish().steps

        assertNull(steps[0].capturedDelayFromPreviousMs)
        assertEquals(725L, steps[1].capturedDelayFromPreviousMs)
    }

    @Test
    fun `full Settings recording compiles to launch and exactly two taps with screen postconditions`() {
        val home = snapshot(screen(LAUNCHER, "Settings"), 500)
        val settingsRoot = snapshot(screen(APP, "Network & internet"), 1_000)
        val network = snapshot(screen(APP, "Internet"), 2_000)
        val internet = snapshot(screen(APP, "Network preferences"), 3_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("settings", home))
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "Settings", settingsRoot, 1_000))
        compiler.accept(interaction(settingsRoot, down = 1_100))
        compiler.accept(interaction(network, down = 2_100))
        compiler.updateScreen(internet)

        val steps = compiler.finish().steps

        assertEquals(3, steps.size)
        val launch = steps[0] as LaunchAppStep
        val firstTap = steps[1] as UiStep
        val secondTap = steps[2] as UiStep
        assertEquals(LaunchPolicy.RESET_TO_ROOT, launch.policy)
        assertEquals("Network & internet", firstTap.element!!.strategies.single { it.kind == LocatorKind.TEXT_EXACT }.value)
        assertEquals("Internet", secondTap.element!!.strategies.single { it.kind == LocatorKind.TEXT_EXACT }.value)
        assertTrue(firstTap.verification.postconditions.single() is VerificationPostcondition.ExpectedScreen)
        assertTrue(secondTap.verification.postconditions.single() is VerificationPostcondition.ExpectedScreen)
        assertEquals(network.windows.first().packageName, firstTap.expectedAfter!!.packageName)
        assertEquals(internet.windows.first().packageName, secondTap.expectedAfter!!.packageName)
        assertTrue(secondTap.verification.allowBestEffort)
    }

    @Test
    fun `regression test - swipes collapse into reveal policy on next interaction unconditionally and inherit scrollContainer`() {
        val listWindow = { rowId: Int, rowText: String ->
            window(APP) {
                node("android.widget.FrameLayout", id = 1) {
                    node("androidx.recyclerview.widget.RecyclerView", id = 2, scrollable = true, bounds = r(0, 100, 1_000, 1_800)) {
                        node("android.widget.TextView", id = rowId, text = rowText, bounds = r(50, 900, 950, 1_100))
                    }
                }
            }
        }
        val targetWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.Button", id = 99, text = "Open", bounds = r(50, 900, 950, 1_100), clickable = true)
            }
        }

        val snap1 = snapshot(listWindow(10, "Item 10"), 1_000)
        val snap2 = snapshot(listWindow(21, "Item 21"), 1_400)
        val snap3 = snapshot(listWindow(32, "Item 32"), 1_800)
        val snap4 = snapshot(listWindow(43, "Item 43"), 2_200)
        val targetSnap = snapshot(targetWindow, 2_600)

        val compiler = compiler()
        compiler.start(CaptureScreenContext("scroll_list", snap1))
        compiler.accept(interaction(snap1, nodeId = 10, kind = GestureKind.SWIPE, down = 1_050, up = 1_300, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))
        compiler.accept(interaction(snap2, nodeId = 21, kind = GestureKind.SWIPE, down = 1_450, up = 1_700, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))
        compiler.accept(interaction(snap3, nodeId = 32, kind = GestureKind.SWIPE, down = 1_850, up = 2_100, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))
        compiler.accept(interaction(snap4, nodeId = 43, kind = GestureKind.SWIPE, down = 2_250, up = 2_500, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))
        compiler.accept(interaction(targetSnap, nodeId = 99, kind = GestureKind.TAP, down = 2_650, up = 2_700))

        val uiSteps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(1, uiSteps.size)
        val tapStep = uiSteps.single()
        assertEquals(UiOperation.TAP, tapStep.operation)
        assertEquals(RevealDirection.FORWARD, tapStep.reveal!!.direction)
        assertNotNull(tapStep.element!!.scrollContainer)
        assertEquals("androidx.recyclerview.widget.RecyclerView", tapStep.element!!.scrollContainer!!.expectedClass)
    }

    @Test
    fun `regression test - multiple trailing swipes merge into one standalone SWIPE step targeting scroll container`() {
        val list = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node("androidx.recyclerview.widget.RecyclerView", id = 2, scrollable = true, bounds = r(0, 100, 1_000, 1_800)) {
                    node("android.widget.TextView", id = 5, text = "Item 5", bounds = r(50, 500, 950, 700))
                    node("android.widget.TextView", id = 10, text = "Item 10", bounds = r(50, 700, 950, 900))
                }
            }
        }
        val snap1 = snapshot(list, 1_000)
        val snap2 = snapshot(list, 1_500)
        val snap3 = snapshot(list, 2_000)

        val compiler = compiler()
        compiler.start(CaptureScreenContext("trailing_swipes", snap1))
        compiler.accept(interaction(snap1, nodeId = 5, kind = GestureKind.SWIPE, down = 1_100, up = 1_400, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))
        compiler.accept(interaction(snap2, nodeId = 10, kind = GestureKind.SWIPE, down = 1_550, up = 1_850, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))
        compiler.accept(interaction(snap3, nodeId = 10, kind = GestureKind.SWIPE, down = 2_050, up = 2_350, start = IntPoint(500, 1_400), end = IntPoint(500, 500)))

        val uiSteps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(1, uiSteps.size)
        val swipeStep = uiSteps.single()
        assertEquals(UiOperation.SWIPE, swipeStep.operation)
        assertEquals("androidx.recyclerview.widget.RecyclerView", swipeStep.element!!.expectedClass)
    }

    @Test
    fun `regression test - dialog class does not become observedActivityClass and retains last real activity`() {
        val mainWin = screen(APP, "Main Button")
        val mainSnap = snapshot(mainWin, 1_000)
        val dialogWin = screen(APP, "Dialog OK", nodeId = 20)
        val dialogSnap = snapshot(dialogWin, 2_000)

        val compiler = compiler()
        compiler.start(CaptureScreenContext("dialog_test", mainSnap))
        // 1. AppSwitch to real activity
        compiler.accept(CapturedAppSwitch(LAUNCHER, APP, "com.example.app.MainActivity", mainSnap, 1_000))
        // 2. Tap to open dialog
        compiler.accept(interaction(mainSnap, down = 1_100))
        // 3. Dialog opens with className AlertDialog
        compiler.accept(CapturedAppSwitch(APP, APP, "androidx.appcompat.app.AlertDialog", dialogSnap, 1_500))
        // 4. Tap OK inside dialog
        compiler.accept(interaction(dialogSnap, nodeId = 20, down = 2_100))

        val steps = compiler.finish().steps
        val tapInDialog = steps.filterIsInstance<UiStep>().last()

        assertEquals("com.example.app.MainActivity", tapInDialog.expectedWindow.observedActivityClass)
    }

    @Test
    fun `regression test - text commit matching field label or hint or empty text is dropped`() {
        val form = window(APP) {
            node("android.view.View", id = 1) {
                node("androidx.compose.ui.platform.ComposeView", id = 2) {
                    node("TextField", id = 3, bounds = r(100, 200, 900, 350), editable = true, hintText = "Message", actions = setOf(NodeAction.SET_TEXT)) {
                        node("TextView", id = 4, text = "Message", bounds = r(110, 210, 300, 300))
                    }
                }
            }
        }
        val before = snapshot(form, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("spurious_text", before))
        compiler.accept(interaction(before, nodeId = 3, down = 1_100))
        // Empty text commit -> dropped
        compiler.accept(CapturedTextCommit(form.node(3)!!, "", form, 1_200))
        // Spurious text commit matching label "Message" -> dropped
        compiler.accept(CapturedTextCommit(form.node(3)!!, "Message", form, 1_300))
        // Real text commit -> kept
        compiler.accept(CapturedTextCommit(form.node(3)!!, "hello", form, 1_500))

        val uiSteps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(2, uiSteps.size)
        assertEquals(UiOperation.TAP, uiSteps[0].operation)
        assertEquals(UiOperation.SET_TEXT("hello"), uiSteps[1].operation)
    }

    @Test
    fun `regression test - consecutive commits to the same field collapse into the last commit`() {
        val form = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.EditText", id = 2, bounds = r(100, 200, 900, 350), editable = true, actions = setOf(NodeAction.SET_TEXT))
            }
        }
        val before = snapshot(form, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("collapse_text", before))
        compiler.accept(interaction(before, nodeId = 2, down = 1_100))
        compiler.accept(CapturedTextCommit(form.node(2)!!, "hel", form, 1_200))
        compiler.accept(CapturedTextCommit(form.node(2)!!, "hello", form, 1_400))
        compiler.accept(CapturedTextCommit(form.node(2)!!, "hello world", form, 1_600))

        val uiSteps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(2, uiSteps.size)
        assertEquals(UiOperation.TAP, uiSteps[0].operation)
        assertEquals(UiOperation.SET_TEXT("hello world"), uiSteps[1].operation)
    }

    @Test
    fun `regression test - SET_TEXT selector is built from field before typing to reflect empty hint`() {
        val emptyFieldWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.EditText",
                    id = 2,
                    text = null,
                    hintText = "Search shortcuts",
                    contentDescription = "Search shortcuts",
                    bounds = r(100, 200, 900, 350),
                    editable = true,
                    clickable = true,
                    actions = setOf(NodeAction.CLICK, NodeAction.SET_TEXT)
                ) {
                    node("android.widget.TextView", id = 3, text = "Search shortcuts", bounds = r(110, 210, 500, 300))
                }
            }
        }
        val typedFieldWindow = window(APP) {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    "android.widget.EditText",
                    id = 2,
                    text = "my query",
                    hintText = null,
                    bounds = r(100, 200, 900, 350),
                    editable = true,
                    clickable = true,
                    actions = setOf(NodeAction.CLICK, NodeAction.SET_TEXT)
                )
            }
        }
        val before = snapshot(emptyFieldWindow, 1_000)
        val compiler = compiler()
        compiler.start(CaptureScreenContext("pre_typing_selector", before))
        compiler.accept(interaction(before, nodeId = 2, down = 1_100))
        compiler.accept(CapturedTextCommit(typedFieldWindow.node(2)!!, "my query", typedFieldWindow, 1_500))

        val uiSteps = compiler.finish().steps.filterIsInstance<UiStep>()

        assertEquals(2, uiSteps.size)
        val setTextStep = uiSteps[1]
        assertEquals(UiOperation.SET_TEXT("my query"), setTextStep.operation)

        // The selector should reflect the empty field's hint "Search shortcuts", NOT the typed text "my query"
        assertTrue(
            setTextStep.element!!.strategies.any { strategy ->
                strategy.value == "Search shortcuts"
            }
        )
        assertFalse(
            setTextStep.element!!.strategies.any { strategy ->
                strategy.value == "my query"
            }
        )
    }

    /** The assertion remains structural without adding a capture-only node id to the model. */
    private fun com.shortcuts.app.automation.model.ElementSelector.targetNodeIdForTest(
        window: com.shortcuts.app.automation.core.tree.WindowSnapshot
    ): Int? = window.nodes.values.firstOrNull { node ->
        node.className == expectedClass &&
            boundsInWindow == com.shortcuts.app.automation.core.geometry.NormalizedGeometry.normalize(
                node.boundsInScreen,
                window.boundsInScreen
            )
    }?.nodeId
}
