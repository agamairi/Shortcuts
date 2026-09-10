# UX Fix Plan — 2026-08-06

This plan was produced from a real investigation session on a physical Pixel 6a
(Android 16 / API 36) connected over `adb`, not guesswork. Every finding below
was reproduced and captured (screenshots, logcat, crash traces, file inspection)
before being written down. If you're starting a fresh session to implement this,
read this whole file first — the diagnostic legwork is already done.

Four things to fix, in priority order. #1 and #2 are real bugs (the app is
broken for real users right now). #3 and #4 are the UX/visual asks.

---

## 1. CRASH: AI widget generation kills the app (P0 — blocking bug)

### Reproduction
Settings → (none needed) → Dashboard → overflow menu → "Add Widget to Home
Screen" → "Create Your Own Widget" → type any prompt → "Generate with AI".
Model downloads successfully (foreground service, progress bar works fine),
then the moment the model tries to initialize, the whole app process dies.

### Root cause (confirmed via `adb logcat -d -b crash`)
Native crash, not a Kotlin exception — **cannot be fixed with a try/catch**:

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000 (read)
Cause: null pointer dereference

backtrace:
  #00 libllm_inference_engine_jni.so
  #01 LlmInferenceEngine_CreateSession+156
  #02 Java_com_google_mediapipe_tasks_core_LlmTaskRunner_nativeCreateSession+232
  ...
  #16 mediapipe.tasks.genai.llminference.LlmInference.createFromOptions
  #20 com.shortcuts.app.service.OnDeviceInferenceService$initializeModel$2.invokeSuspend
```

The crash is inside MediaPipe's native GenAI engine, triggered from
`OnDeviceInferenceService.initializeModel()` → `LlmInference.createFromOptions()`.

### What I ruled out
- **Not a corrupted/truncated download.** Pulled the actual file off the device:
  `functiongemma.litertlm` is 284,426,240 bytes (~271MB) — a plausible size for
  a Q8-quantized small model, not an HTML error page or a partial download.
- **Not a token-limit mismatch.** The model repo name is
  `functiongemma-mobile-actions_q8_ekv1024` (external KV cache = 1024 tokens),
  and the code's `.setMaxTokens(1024)` already matches that exactly.

### What I found that's actually wrong
1. **`OnDeviceInferenceService.kt`'s `LlmInferenceOptions.builder()` never
   calls `.setPreferredBackend(...)`.** Every MediaPipe LLM Inference example
   in current docs sets this explicitly (`LlmInference.Backend.CPU` or `.GPU`).
   Leaving it unset lets the native layer guess, and guessing wrong is a
   documented source of native crashes in MediaPipe's GenAI task
   ([GitHub #5825](https://github.com/google-ai-edge/mediapipe/issues/5825),
   [#6001](https://github.com/google-ai-edge/mediapipe/issues/6001)).
   **First thing to try: explicitly set `.setPreferredBackend(LlmInference.Backend.CPU)`**
   — CPU is the safer/more universally-supported backend; GPU delegate
   initialization failures are a common crash source on real devices.
2. **`ModelDownloader.kt` has zero integrity verification**, despite
   `changelog.md`/`TEST_READY.md` explicitly claiming "Checksum verification
   for model file integrity" as a shipped, tested feature. Read the actual
   code — `downloadModel()` only checks `tempFile.length() > 0` before
   renaming it into place. This is a real gap between documentation and
   reality: if a future download gets subtly corrupted (partial write, CDN
   hiccup), the app will silently treat garbage bytes as a valid model and
   crash exactly like this, with no way to tell the user why. **Add real
   SHA-256 verification against a known-good hash** (get it from the
   [litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm](https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm)
   model card/repo on Hugging Face) before accepting the download.
3. **No graceful failure path.** Because this is a native SIGSEGV, Kotlin
   exception handling cannot catch it — the process dies unconditionally.
   The realistic mitigation isn't "catch the crash," it's "never call into
   the native layer with a bad model file or bad options in the first
   place" (fix #1 and #2 above), plus consider running model initialization
   in a way that a crash there doesn't necessarily need to take down the
   whole app (e.g., research whether MediaPipe's GenAI task can run in a
   separate process via `android:process` on a service, so a native crash
   there doesn't kill the main UI process — this is a bigger architectural
   change, lower priority than #1/#2, evaluate only if the backend fix
   doesn't resolve it).

### Suggested order of operations for the next session
1. Add `.setPreferredBackend(LlmInference.Backend.CPU)` to both
   `initializeModel()`'s options builder. Rebuild, reinstall, retry the exact
   repro above on a real device (emulator won't reproduce native/GPU issues
   reliably).
2. If it still crashes, get the actual SHA-256 of the Hugging Face file and
   add verification to `ModelDownloader.kt`; delete the on-device model via
   Settings, re-download, and check whether the hash matches. If it doesn't
   match on a clean download, the bug is server/redirect-handling related in
   `ModelDownloader`, not MediaPipe config.
3. If both of those check out and it *still* crashes, this is likely a
   genuine MediaPipe/device incompatibility — check the MediaPipe GenAI task
   library's supported-device list and consider pinning a different
   `tasks-genai` version (currently `0.10.11`, in `app/build.gradle.kts`).

---

## 2. BUG: "Almost all apps missing from Browse Apps" (P0 — blocking bug)

### Reproduction & proof
Manual Builder → Add Action → "Open an App" → "Choose" → app picker shows
**exactly 3 apps**: Google Play Store, Settings, and the Shortcuts app itself
— despite the test device clearly having many more apps installed (Gmail,
and others visible right in the status bar during testing).

### Root cause (confirmed by reading `AndroidManifest.xml`)
**There is no `<queries>` element anywhere in the manifest.** Since Android 11
(API 30), apps cannot see other installed apps via `PackageManager` (including
`queryIntentActivities`, which is exactly what
`ManualBuilderUtils.getInstalledLaunchableApps()` in `ManualBuilderScreen.kt`
calls) unless the querying app explicitly declares what it needs to see via
`<queries>` in its manifest, or holds the heavily-restricted
`QUERY_ALL_PACKAGES` permission (requires Play Store justification, not
appropriate here). This device runs Android 16 — package visibility is fully
enforced. The only 3 apps that showed up are ones Android exempts by default
(the querying app itself, and a small allowlist that includes the installer/
Play Store; Settings visibility varies by OEM).

### The fix
Add to `AndroidManifest.xml`, as a direct child of `<manifest>` (sibling to
`<application>`):

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

This exact pattern (`ACTION_MAIN` + `CATEGORY_LAUNCHER`) is specifically
allowlisted by Android's package-visibility system for legitimate "show me
all launchable apps" use cases like a launcher or an app picker — it does
**not** require `QUERY_ALL_PACKAGES` or any Play Store justification. This is
a one-element manifest fix. After adding it, `queryIntentActivities(Intent
(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)` will return the full list
of launchable apps again.

Verify the fix the same way I reproduced the bug: rebuild, install, Manual
Builder → Add Action → Open an App → Choose, and confirm real apps (Chrome,
Gmail, etc.) show up.

---

## 3. Richer widget types ("not just buttons")

### What I saw
Screenshotted the actual "Add Widget to Home Screen" gallery on-device. Every
one of the 5 widget preview cards is a flat colored square with one icon and
one word ("Shortcut", "Custom", "Good"/"Run"). This is an accurate
representation of what the real Glance widgets look like — they *are* just
flat colored buttons. The user's comparison point was the Pixel battery
widget / similar system widgets.

### The actual insight (not just "add more chrome")
Real system widgets (battery %, weather, at-a-glance) are compelling because
they show **live, glanceable information** — not because they're more
colorful. Our widgets currently show zero information; a Quick Shortcut tile
says "Shortcut" whether the automation ran once, ran an hour ago, or never
successfully ran at all. Two separate problems, both worth fixing:

**A. Visual richness of the existing 5 types** (lower effort, do first):
- Use Glance's `SizeMode.Responsive` so tiles adapt to the size the user
  resizes them to on their home screen (small: icon+label only; larger:
  icon+label+subtitle), instead of one fixed cramped layout regardless of
  widget size.
- Subtle depth: tonal elevation / a soft inner highlight instead of a flat
  fill color, consistent with whatever the new theme system (see #4) ends up
  using for surface/elevation roles.
- Larger, better-proportioned icon glyphs — current ones read as small and
  centered awkwardly in testing.

**B. Give widgets actual live content** (the real "battery widget" parity —
higher effort, higher payoff, do second):
- Add a `lastRunAt: Long?` / `lastRunSuccess: Boolean?` (nullable, additive —
  follow this project's existing non-destructive Room migration pattern, see
  `AppDatabase.kt`'s `MIGRATION_5_6` for the most recent example) to
  `Automation`, updated by `ActionExecutorService`/`RunAutomationCallback`
  after each execution.
- Quick Shortcut and Custom Widget tiles show a small secondary line: "Ran 2m
  ago" / "Tap to run" / a small status dot (success/fail), the same way a
  battery widget shows "62%" as live info, not just a static icon.
- This is a genuinely new, useful capability, not just decoration — evaluate
  scope/priority against #1/#2/#4 before committing to it in one pass; it's
  reasonable to ship 3A alone first and treat 3B as a fast follow.

### A new widget type worth considering (optional, only if time allows)
A **Status widget** — shows the live state of a `SYSTEM_TOGGLE`-type
automation (e.g., current Wi-Fi on/off) rather than being a stateless tap
target, closer in spirit to what the user is actually pointing at with
"Pixel battery widget."

---

## 4. Theme system (replace the hardcoded purple)

### What's actually there right now
`MainActivity.kt`:
```kotlin
val PremiumDarkColors = darkColorScheme(primary = Color(0xFFBB86FC), ...)
val PremiumLightColors = lightColorScheme(primary = Color(0xFF6200EE), ...)
```
`0xFF6200EE` is the stock Material Design "Purple 500" — the textbook
tutorial default color, hardcoded, no persistence, no user control anywhere.
Confirmed live on-device: FAB, AI Builder button, and sparkle icons are all
this purple.

### Design
Add a "Theme" section to the existing Settings screen (`SettingsScreen.kt`
already has AI Model / Accessibility / About sections — this is a 4th card,
consistent pattern):
- A row of accent color swatches to pick from (reuse the same interaction
  pattern as `CreateWidgetScreen.kt`'s existing 6-color picker — this app
  already has that exact UI built once, reuse it, don't reinvent).
- Selecting one regenerates the app's `ColorScheme` (light + dark variants)
  from that accent as the seed/primary color and persists the choice.

### Two real implementation-detail decisions to make (don't guess — decide
deliberately in the implementing session):
1. **How to derive a full Material3 tonal palette from one seed color.**
   `minSdk = 26`, so Android 12+'s system dynamic color
   (`dynamicLightColorScheme`/`dynamicDarkColorScheme`) isn't usable as the
   primary mechanism — most users on this app's supported OS range won't get
   it. Two real options:
   - **Simplest / lowest-risk:** precompute a small fixed set of complete
     light+dark `ColorScheme` pairs (one per accent option), same spirit as
     `WidgetColorKey` but each mapping to a full scheme, not just one color.
     No new dependency.
   - **More correct / more work:** use Google's `material-color-utilities`
     algorithm (the same one Compose Material3 uses internally for dynamic
     color) to algorithmically derive a full tonal palette from any
     arbitrary seed color at runtime. Needs a new dependency
     (`com.materialkolor:material-kolor` is a maintained Compose-friendly
     wrapper, or bind Google's library directly). Gives users true "any
     color" freedom instead of a fixed palette.
   Recommendation: start with the fixed-palette approach (matches this
   project's existing `WidgetColorKey`-style patterns, zero new
   dependencies, ships faster) — upgrade to algorithmic generation later if
   users want more than ~8 preset options.
2. **Persistence.** This project has Room but no key-value preferences store
   yet. Add `androidx.datastore:datastore-preferences` (small, standard,
   no migration complexity) rather than reaching for Room for a single
   preference value, or use plain `SharedPreferences` if you want zero new
   dependencies — either is fine, just pick one deliberately.

### Scope note
Apply the chosen theme via `ShortcutsTheme` in `MainActivity.kt` — that's the
single choke point already wrapping the whole app (`ShortcutsNavigation()`),
so this doesn't require touching every screen individually.

---

## Suggested implementation order for the next session

1. **Fix #2 (missing apps)** — one manifest element, near-zero risk, unblocks
   the core "control multiple apps from one shortcut" use case that started
   this whole conversation.
2. **Fix #1 (AI crash)** — try the `setPreferredBackend` fix first, verify on
   the real device before moving on; this is the highest-uncertainty item,
   budget real device-testing time for it, not just a code change + build.
3. **#4 (theming)** — self-contained, doesn't depend on #1-3, good to
   parallelize or do independently if using multiple delegated Antigravity
   passes again.
4. **#3A (visual richness)** — do after #4 lands, since tile styling should
   pull from whatever the new theme system produces (don't hardcode new
   colors into widgets right before adding a theme system that would then
   need to override them).
5. **#3B (live widget content)** — treat as a stretch goal / separate pass;
   real schema + execution-pipeline change, not pure UI.

## Verification approach that actually worked this session
A physical device connected via `adb` was essential — none of these four
issues (native crash, missing apps, "just buttons," hardcoded purple) were
things static code review alone reliably caught before. If a device is
available in the implementing session, keep using it: `adb install -r
app/build/outputs/apk/debug/app-debug.apk`, `adb logcat -d -b crash` after
any repro, `adb exec-out screencap -p` to actually look at what shipped.
