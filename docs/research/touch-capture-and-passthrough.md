# Research: Touch Capture and Passthrough

## Verdict on the Permission Hypothesis

**Both things are true, and the git history separates them cleanly: a real capability gap existed for the entire span of the six relay attempts, and a separate, still-standing architectural wall exists independent of it.**

`AccessibilityService.dispatchGesture` requires two things to function: the `android:canPerformGestures="true"` attribute in the accessibility service's XML config, and the user granting the service via Settings. `canPerformGestures` is **not** an `AndroidManifest.xml` `<uses-permission>` entry — it's a service-config attribute — but functionally it is exactly the kind of "the manifest side was missing something" gap it was remembered as.

Tracing `app/src/main/res/xml/accessibility_service_config.xml` through git history:

- The attribute was **absent** from the file's creation (`f36875e`, Aug 2) all the way through `5d9588a` (Sep 5, 11:30am) — the commit that ripped out the touch-relay overlay and replaced it with the current "Mark a tap" approach. **The six failed attempts referenced in that commit's own message all happened during this window, without `canPerformGestures`.**
- It was added 23 minutes later, in `4dfc0b4` (Sep 5, 11:53am), whose commit message states directly: *"Missing this capability meant dispatchGesture() silently failed system-wide — likely the root cause of extensive gesture-dispatch failures observed during testing."*
- That fix landed **after** the relay architecture had already been abandoned. It was never tested against the six failures it was diagnosed to explain.

So the original claim in an earlier draft of this document — "not a missing permission" — was checking only the *current* state of the config file and missed this timeline. It should be retracted.

What this does **not** settle: `canPerformGestures` being absent would make `dispatchGesture` fail on *every* call, including a single discrete tap — a total, system-wide failure. That's consistent with "nothing worked at all," which matches the symptom. But two separate constraints below are independent of this attribute and remain true with it present:
1. **No simultaneous capture and passthrough:** Window flags are mutually exclusive in their handling of touches. If a window has `FLAG_NOT_TOUCHABLE`, touches pass through to the app below, but the overlay captures nothing. Without this flag, the overlay consumes the touch entirely, preventing it from reaching the app below. There is no supported way to both capture a raw touch and seamlessly pass that exact touch down to the window underneath at the same time.
2. **`dispatchGesture` does not stream live touches:** It requires a complete, pre-specified `GestureDescription` (an immutable path of points with predefined timing and duration) submitted up front. It cannot relay a continuous, in-progress stream of `MotionEvent` updates in real-time.

**Practical conclusion:** with `canPerformGestures` now present, a touch-relay overlay would likely work again for the simple case it was probably tested with most (a single discrete tap, replayed via a pre-built `GestureDescription` shortly after capture). It would still fail the moment the user scrolled, dragged, or long-pressed with any live motion, because those need constraint (2) to not exist, and it does. **This is why the recommendation below stands regardless of the permission history**: the capability gap explains the total failure, not the deeper limitation that made the overall approach the wrong architecture to keep building on.

---

## Comparison of Automation Architectures

| Approach | Touch Fidelity | Works during live scroll/drag | Extra Setup Required | Store-Distributable (No extra user steps) | Verdict for RepeatKit |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Node-Action Automation** | None (Semantic action on node) | Yes (Triggers on node, not screen coord) | None (Uses Accessibility Service) | Yes | **Current & Correct Path** |
| **Shizuku / Raw Injection** | Pixel-perfect `MotionEvent` stream | Yes (True touch injection) | Yes (Shizuku install / ADB command) | No (Requires power-user setup) | Possible future opt-in power-user mode |
| **Instrumentation / UiAutomation** | High | Yes | Yes (Test harness / `adb shell` execution) | No (Cannot run as regular app) | Not viable |

---

## Architecture 1: Node-Action Automation (Tasker, Automate)

### How it works
This is the industry standard for normal automation apps. Instead of capturing raw touches and attempting to replay them pixel-by-pixel, these apps rely entirely on the Android Accessibility framework. They resolve a target UI element as an `AccessibilityNodeInfo` (by text, ID, or content description) and invoke semantic actions (like `ACTION_CLICK` or `ACTION_SCROLL_FORWARD`) directly on that node.

This sidesteps the need for touch capture and overlays entirely during normal execution. This is fundamentally the same approach RepeatKit already uses in its passive accessibility-event recorder.

### Code Sample (Kotlin)
```kotlin
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class SemanticAutomationRunner(private val service: AccessibilityService) {

    /**
     * Finds a node by its view ID and performs a click action on it.
     */
    fun clickNodeById(viewId: String): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        
        val matchedNodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        if (matchedNodes.isNullOrEmpty()) {
            rootNode.recycle()
            return false
        }

        // Use the first matched node
        val targetNode = matchedNodes[0]
        
        // Perform the semantic click action directly on the node
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        
        // Clean up
        for (node in matchedNodes) {
            node.recycle()
        }
        rootNode.recycle()
        
        return success
    }
}
```

### Pros / Cons for RepeatKit
* **Pros:** Standard, reliable, Play-Store compliant, requires no extra permissions beyond the accessibility service the user already granted. Works across screen sizes and orientations because it targets UI structure, not absolute coordinates.
* **Cons:** Apps that use custom drawing (like heavy Jetpack Compose screens without accessibility semantics, or games) are opaque to this method, requiring fallbacks like RepeatKit's `TapMarkOverlay`.

### Worked example: cache-cleaning apps (e.g. 1Tap Cleaner) use this exact technique — on a much narrower problem

Apps like 1Tap Cleaner that bulk-clear app caches via Settings' "App info" screen are **not** capturing or replaying a user's raw touches at all. They are pre-programmed instances of this same node-action pattern, applied to exactly one first-party target: Android's own Settings app.

Their fixed sequence, written once by the developer (not recorded from a live user):
1. Launch `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for package X.
2. Find the "Storage & cache" / "Clear cache" node by text or resource id.
3. `performAction(ACTION_CLICK)` on it.
4. Go back, advance to package X+1, repeat.

This works reliably for one reason: **Settings is a single, first-party screen that exists in a known, bounded set of shapes** (stock AOSP plus a handful of OEM skins — Samsung One UI, etc.), so the developer can hand-write and maintain selectors for that bounded set. It still breaks on unfamiliar OEM skins or major Android version UI changes — a known, recurring complaint in reviews of this class of app — because it has no way to *learn* a new screen's layout; it only knows the ones it was coded for.

**RepeatKit's problem is categorically harder, not solved by a better technique.** A cache cleaner automates one app it never has to identify selectors for at runtime. RepeatKit has to work across *any* third-party app the user chooses — apps whose UI structure is unknown until the user opens them. That unbounded-target problem is exactly why recording exists: it's how RepeatKit learns the selectors for a screen it has never seen, instead of having them hand-written in advance. The node-action *execution* technique is identical to 1Tap Cleaner's; the generalization problem behind it is not.

---

## Architecture 2: Shizuku / Raw Input Injection

### How it works
For apps that require absolute, pixel-perfect record and replay of a raw touch stream (e.g., auto-clickers for games), the standard accessibility API is insufficient. To inject raw `MotionEvent`s directly into the system, an app needs the `android.permission.INJECT_EVENTS` permission.

This is a `signature|privileged` permission that a normal app cannot obtain just by declaring it in the manifest. It must be granted via an ADB shell command (`adb shell pm grant <pkg> android.permission.INJECT_EVENTS`) or via root. Shizuku acts as a bridge, allowing the app to run code with shell-level privileges (via a bound binder service), providing a handle to the system's `InputManager` to inject events.

### Code Sample (Kotlin)
```kotlin
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

class ShizukuInputInjector {

    /**
     * Injects a raw tap at the specified screen coordinates using Shizuku privileges.
     */
    fun injectRawTap(x: Float, y: Float) {
        try {
            // Get a privileged InputManager handle via Shizuku (conceptual example)
            val inputManager = InputManager::class.java.getDeclaredMethod("getInstance").invoke(null) as InputManager
            val injectInputEventMethod = InputManager::class.java.getDeclaredMethod(
                "injectInputEvent", 
                MotionEvent::class.java, 
                Int::class.javaPrimitiveType
            )

            val downTime = SystemClock.uptimeMillis()
            
            // 1. Inject ACTION_DOWN
            val downEvent = MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            injectInputEventMethod.invoke(inputManager, downEvent, 0) // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            downEvent.recycle()

            // 2. Inject ACTION_UP
            val upEvent = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis() + 50, MotionEvent.ACTION_UP, x, y, 0
            ).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            injectInputEventMethod.invoke(inputManager, upEvent, 0)
            upEvent.recycle()

        } catch (e: Exception) {
            e.printStackTrace()
            // Handle lack of Shizuku permission or reflection failure
        }
    }
}
```

### Pros / Cons for RepeatKit
* **Pros:** True raw touch injection; bypasses the `AccessibilityNodeInfo` limitations entirely. Captures and replays exactly what happened on screen.
* **Cons:** Requires a heavy external dependency (Shizuku) and an arduous setup process for the user (ADB pairing or root). It cannot be distributed as a simple install-and-go Play Store app.

---

## Architecture 3: Instrumentation / UI Automator

### How it works
Frameworks like Espresso and UI Automator are used for automated testing. They have deep hooks into the system to inject events and read the screen. However, `UiAutomation` is only available to apps running under instrumentation (i.e., started via `adb shell am instrument`).

### Code Sample (Kotlin)
```kotlin
import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor

class InstrumentationRunner {

    /**
     * Executes a raw shell command to inject a tap using UiAutomation.
     * Note: This only works in a test context.
     */
    fun executeShellTap(x: Int, y: Int) {
        val uiAutomation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        
        // Execute a shell command directly using the UiAutomation privileges
        uiAutomation.executeShellCommand("input tap $x $y").use { parcelFileDescriptor ->
            // Wait for command to finish by consuming the stream
            ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptor).readBytes()
        }
    }
}
```

### Pros / Cons for RepeatKit
* **Pros:** Powerful system-level access provided natively by Android for testing.
* **Cons:** **Completely unviable for production.** A standard app cannot elevate itself to an instrumentation context at runtime. It requires the app to be launched from a developer shell, making it entirely useless for an end-user consumer application.

---

## Closing Recommendation

**RepeatKit's current direction is exactly correct.**

The architecture RepeatKit already uses — relying on `AccessibilityNodeInfo.performAction` for standard app interactions, and falling back to the opt-in `TapMarkOverlay` ("mark a tap") when the accessibility tree is opaque — is the proven, industry-standard approach for Play-Store-distributable automation apps. 

The six failed attempts at real-time touch capture and passthrough via `dispatchGesture` were battling a genuine capability gap (`canPerformGestures` was absent) *and* fundamental Android windowing limitations that gap doesn't touch. The gap is now closed; the windowing limitations are not fixable, so the relay architecture remains the wrong path forward even with the fix in place.

### Future Considerations
If there is strong demand for a "Power User" mode that can truly record and replay raw, continuous touch gestures (e.g., for games or deeply custom UIs), **Shizuku-based injection** (Architecture 2) is the only technically viable path. 

Implementing this would require:
1. Adding the Shizuku library as a dependency.
2. Building an opt-in, first-run permission flow to guide the user through setting up Shizuku via wireless ADB.
3. Ensuring graceful degradation: the app must fall back cleanly to the existing Accessibility Service approach if Shizuku is not installed, not running, or denied. 

This should be treated as a distinct future feature scope, not a bugfix to the current implementation. The current accessibility-based architecture is fundamentally sound for the app's stated goals.
