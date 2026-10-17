# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **Chrome address-bar searches record the typed query and the submit.** Recording "open Chrome, tap the address bar, type, press Go" kept only the launch and the tap. Chrome's address bar never announces its text, so RepeatKit has to re-read it. But the Pixel launcher keeps a background window updating while Chrome is open, and the recorder took each update as a switch to the launcher. That reset the field being followed, so the text was never re-read. Updates from background windows of other apps no longer count as switching apps. The recording now has the query as typed (not the results URL Chrome shows afterwards) and a detected Go. On a Pixel 6a, recording and replay passed 10 of 10 runs typed on the on-screen keyboard and 5 of 5 typed with adb (it was 0 of 2 in 0.8.0).
- **Searches typed on the on-screen keyboard record the whole query.** In YouTube Music, typing with Gboard recorded a partial query ("lo") as a submitted search and often missed the real Search press. Gboard reports a window change on almost every keystroke, and each one saved the field as a separate step. The recording queue then fell seconds behind, and it judged old partial text against a screen that already showed the results. Now the field is saved only when the keyboard actually closes, and a submit is never detected while newer typed text is still waiting to be processed. Testing with adb-injected text had hidden this: it hides the keyboard, so these events never happened. On a Pixel 6a, recording and replay of a YouTube Music search typed on the on-screen keyboard passed 23 of 25 runs (0 of 2 before the fix), and 5 of 5 with adb. All 24 recordings that started were correct. One failure was YouTube Music not showing its Search button before recording started. The other was YouTube Music itself running out of memory while replay opened it.
- **A pause before pressing Search no longer loses the search.** A keyboard submit was only detected within 2 seconds of the last keystroke, so stopping to read suggestions first meant the Search press wasn't recorded. The 2 seconds now count from the last moment the field was seen still focused, holding the typed text, with the keyboard up.

### Added
- **The test harness can type like a person.** New `gtype` and `ime` scenario steps tap the on-screen keyboard's keys and its Search/Go key, read through RepeatKit Dev's own service (`rk-ime`), instead of injecting text with adb. `tools/e2e/rk_realapps.py` records and replays the real-app flows (Settings, Calculator, Chrome and YouTube Music search, with both typing methods) and checks the end screen.

## [0.8.0] - 2026-10-17

Recording and replay rebuilt (Engine v2), and home-screen widgets fixed. On a Pixel 6a (Android 16), v0.7.4 replayed 0 of 12 test-app scenarios; this release replays all 12, 24 of 24 runs on the final build. In real apps, Settings, Calculator and YouTube Music navigation record and replay reliably; see Known issues for searches. Widgets pin, load in a few seconds, run, and follow renames and deletes.

### Fixed
- **In-app searches record the whole search (verified in YouTube Music).** The keyboard was mistaken for a foreground app, and pressing Search/Go on a screen that shows results in place gives accessibility services no signal at all, so recordings stopped after the typed text. RepeatKit now keeps the real app as the target, and detects a submit when the field loses focus or the keyboard closes and the app's screen changes. It shows the step as a detected keyboard action. Chrome's address bar is still not captured reliably; see Known issues.
- **Settings shortcuts are recorded in the new format.** Android lists a boot-time placeholder inside Settings ("FallbackHome") as a home-screen app. The recorder therefore treated everything in Settings as the home screen, so Settings recordings were always saved in the old format, and Back was never detected inside Settings. Placeholder home activities are now ignored.
- **Apps with a personalised start screen replay.** After opening an app, replay required the start screen to look like it did during recording. Apps whose home screen changes every time (YouTube Music's feed, news apps) therefore failed at the very first step. The app's window must still match, but what it shows is now only a hint, as it already was for every later step.
- **Typed text is never credited to the wrong field.** Fields built with Jetpack Compose have no view ids. When the recorder couldn't tell which field was typed into, it took the first text field on screen, so text typed into a Message box could be recorded as typed into the Search box above it. It now uses the focused field, then the field holding the typed text, and records nothing rather than guess.
- **Typed text is kept when a field loses focus or the keyboard closes.** A tapped text field is now followed even if it does not report itself as editable until focused (Chrome's address bar does this). Its text is committed when focus or the keyboard goes away, before an app can replace it with something else, such as a results URL. This did not yet make Chrome address-bar searches record on a Pixel 6a; see Known issues.
- **Stopping a recording no longer quietly falls back to the old format in busy apps.** Every screen-content change (dozens a second in apps with animations) took a full screen snapshot on the recording queue. The queue fell so far behind that stopping timed out before the new-format workflow was built, and the old format was saved instead. Screen changes are now coalesced, and only examined when a typed field or app switch is waiting on one.
- **Recorded taps say what was tapped.** In the editor, a recorded tap on an icon button (Calculator's "7", a gear icon) read "Tap target", because only the element's text was shown and icon buttons have none. It now shows the text, else the accessibility description, else a readable form of the element's id.
- **A tap that opens a search field no longer fails its check on replay.** The screen after such a tap was captured only once typing had finished, showing suggestions or results. Replay checked for that screen right after the tap and never found it. The tap now checks that the field to type into is present.
- **Settings recordings no longer silently save in the old format when its window is still opening.** A temporarily unavailable or partially broken accessibility window snapshot could drop the Settings launch, leaving Engine v2 with no target application. Snapshot capture now retries short launch gaps, skips only broken windows, and preserves or reconstructs the app launch before compiling the following steps.
- **Recordings of apps that are slow to open no longer fall back to the old engine.** When an app was still drawing at the moment its launch was captured (YouTube Music, for example), the new recording format could not be built ("Launch target has no application window"). The shortcut was then quietly saved in the old format, which replays far less reliably. Worse, if the home screen was still showing, the launch step could have pointed at the launcher instead of the app. The launch step now uses the launched app's own first window, even if it appears a moment later.
- **Back presses during recording are no longer lost.** When Android hides an on-screen Back button or gesture from the recorder, RepeatKit now detects a quiet return to the previous app screen and records it as “Go back (detected)”. Replay also skips a detected Back when the app has already returned on its own, so it cannot navigate one screen too far.
- **Physical key presses while recording are credited to the right app.** To ignore keys pressed inside RepeatKit itself, the recorder checked which app owned the active window. Every tap while recording lands on RepeatKit's capture overlay first, which made the overlay the active window, so a physical keyboard's Enter or a hardware Back key was thrown away as RepeatKit's own. Key presses are now credited to the window with keyboard focus, which the overlay never has. The service also no longer looks at any window on a key press when nothing is being recorded. (The on-screen Back button and back gesture never reach accessibility services as key presses at all; see the inferred Back entry.)
- **Pressing Search/Send on the keyboard after typing now replays.** A field recorded while empty is identified by its placeholder ("Search"), because an empty Android field reports its placeholder as its text. Once replay had typed into it, the placeholder moved out of the text and replay refused the keyboard action with "Screen action could not be performed". Replay now recognises a typed-into field by its placeholder, the same way it finds the field in the first place.
- **E2E harness no longer fails a run over focus timing.** The fixture app logs a field's value when the field loses focus; whether that lands before or after the submit depends on keyboard focus, not on what was done. The event comparison now ignores that echo when the same field also submitted the same value, and still compares values that were typed but never submitted.
- **Replay taps on Compose buttons and list rows are no longer falsely rejected.** Buttons and interactive rows built with Jetpack Compose often place their text or icon content description on an inner child element while the click listener sits on the outer container. Replay now verifies that the live target's label matches the expected label across shallow child elements rather than demanding the outer container itself carry the text, allowing taps on Compose buttons and rows to proceed reliably.
- **Scrolling lists no longer fail live element verification.** When replay scrolls to reveal an off-screen item, it now validates the scroll action against the scrollable list container instead of failing because the container does not carry the target item's label.
- **Movement retries are strictly restricted to reversible steps.** Replay will never re-attempt an action rejected due to screen movement if the step is irreversible or marked best-effort, preventing unintended duplicate actions (such as repeated payments or form submissions).
- **Replay correctly detects Android Settings and other non-standard activity names.** Sub-screens in system Settings (such as `com.android.settings.SubSettings`) and other Android activities that don't end with "Activity" are now recognized as valid target screens instead of causing replay to wait and time out, while transient UI overlays (such as snackbars and menus) are still correctly excluded.
- **Editing recorded Engine v2 text and app launches updates replay expectations correctly.** Editing typed text in a recorded step now updates the postcondition expectations (`TextEquals`) to match the new text so replay verifies the new value. Changing a recorded launch step's app resets app-specific activity and fingerprint expectations so replay waits only for the new package, and picking an app shortcut or deep link maps to the proper deep-link or component target (or reports an error if unrepresentable).
- **Recorder review screen now locks unrepresentable fields and catches editing errors safely.** The recorder review screen now locks recorded tap targets identically to the manual builder, surfaces encoding errors gracefully as inline notices instead of crashing during Check, Run now, or Save, ensures confirmed runs execute on Engine v2 by passing the workflow through, and preserves step IDs when an app shortcut is chosen on a recorded step.
- **Typing and rapid recording input no longer stall the UI.** All Engine v2 capture bridge snapshotting, compiler event acceptance, and session persistence now run on a single ordered background queue instead of taking full-window snapshots synchronously on the main thread during typing and key events. Touch gestures and keyboard/window events remain strictly in causal order.
- **Taps are never dropped when accessibility snapshots are delayed.** When a pre-relay snapshot takes longer than the relay budget, the recorder falls back to a fast bounded live-tree hit-test at finger-down and uses the topmost application package so legacy recordings never drop the tap due to an empty package name.
- **Engine v2 recording compaction preserves application screens and row context.** Compacted capture sessions now pick the active application window rather than higher-layer system bars, preserve all row-context siblings within the target container regardless of class name, and precompute screen fingerprints before compaction to prevent structural hash drift on session restore without rewriting full session metadata on every event.
- **Keyboard submission inference hardened against accidental clears.** IME submission detection tracks text fields across snapshots using package, resource ID, and screen bounds rather than unstable snapshot-local counters, requires at least 2 characters before inferring a clear as a send action, suppresses inference if the user tapped a clear icon inside the field, and tags inferred actions with an `"INFERRED_IME_ACTION"` migration warning.
- **Accessibility service disconnects now cleanly cancel background recording finalizers.** When the accessibility service disconnects during recording, pending scroll finalizers are cancelled and incomplete Engine v2 bridge captures are discarded without compiling or leaving orphaned session files.
- **Replay now keeps each screen snapshot's element IDs in their proper snapshot.** If a list or screen changes just before an action, RepeatKit re-finds the selected control and refuses stale dispatches instead of tapping whichever row inherited an old ID.
- **Replay never taps a control twice after an uncertain result.** Once Android accepts a tap, a failed verification now stops that step rather than retrying it as a gesture; only a proven pre-delivery movement rejection can be retried.
- **Stopping a shortcut now cancels that shortcut's own coroutine.** Stop no longer interrupts an unrelated worker that happens to reuse a pooled thread.

### Changed
- **Shortcut safety and preflight for Engine v2.** `ShortcutRunner` and `ShortcutRunSafety` now inspect version 2 workflows via `WorkflowCodec`. Screen steps requiring accessibility are verified prior to admission, and irreversible screen steps properly request user confirmation.
- **Recording now catches every tap.** While recording, RepeatKit briefly holds each touch, notes exactly what you tapped, and passes it on to the app — so taps in apps built with Compose, Flutter or web views are no longer silently lost. Scrolling lists move once you lift your finger.

### Added
- **Lock-screen widget host support and keyguard unlock trampoline.** Widgets can now be placed on lock-screen widget hosts via `keyguard` category support. When tapping a shortcut on a locked device, harmless/read-only shortcuts run immediately in the background, while shortcuts with irreversible or outward-facing effects (such as sending messages, making calls, or sending mutating HTTP requests) prompt for biometric/device authentication via a transparent trampoline activity (`KeyguardManager.requestDismissKeyguard`) before executing.
- **Engine v2 replay integration and Android adapters.** Replays version 2 workflows with strict window selection, postcondition verification, and direct gesture/semantic dispatch through the accessibility subsystem. Unreleased v2 workflows branch to the new replay runtime while legacy shortcuts continue running through the existing execution engine. Includes deterministic app launch flags that reset target apps to their root screen, eliminating stale back stack resumes. Debug builds capture sanitized execution trace logs to `filesDir/debug/last_trace.jsonl`.
- **Engine v2 recording bridge.** Recording now simultaneously compiles resilient Engine v2 workflows alongside standard recordings. Each tap, long-press, swipe, text edit, keyboard submission, app switch, and navigation action captures structural UI selectors and screen fingerprints before execution. When Engine v2 is enabled, newly recorded shortcuts save in the richer v2 format while retaining fallback to standard recordings if steps are modified.
- **Side-by-side debug automation control.** The debug APK now installs as `RepeatKit Dev` with
  package ID `com.shortcuts.app.dev`, and an adb-only control activity can record, save, list,
  delete, dump, and run shortcuts while writing machine-readable receipts under private debug
  files. This control surface is excluded from release builds.
- **Test fixture app for on-device record/replay tests.** A separate `:fixtures` module (RK Fixtures) provides known screens — Views, Compose, long lists, dialogs, text fields, a WebView — that log every interaction, so recorder and replay reliability can be measured automatically.
- **On-device record/replay E2E harness.** Added a standard-library Python CLI that checks device readiness, preserves existing accessibility services, records and replays the fixture scenarios, compares FixtureEvent streams, reports recording-quality problems separately, and writes per-run evidence plus pass-rate summaries.
- **Live recording feedback without blocking the app underneath.** A small recording HUD now shows the current captured-step count while taps, scrolling, and keyboard input continue to go directly to the foreground app.
- **App shortcuts discovery.** Picking an app in the builder now also offers its long-press shortcuts, so you can start Chrome straight into a new incognito tab.
- **Recorder documentation update.** Updated `okf-docs/recorder.md` to detail `TapMarkOverlay` and `MarkTapReceiver` and document the Mark-a-tap overlay fallback mechanism alongside the passive accessibility event capture path.

### Fixed
- **Home-screen widgets now load reliably and support lock-screen hosts (D1–D8).**
  - **Instant rendering without stale "Tap to set up" (D1):** Widgets now reactively collect their configuration and shortcuts directly inside composition from a Room database Flow, ensuring newly placed, pinned, and edited widgets show their shortcuts immediately.
  - **Custom AI widget pinning preserves styling (D2):** Pinned custom widgets created in the app now carry their template ID through the launcher callback, correctly displaying custom colors, icons, and labels instead of falling back to default styles.
  - **Widget configuration preserves choices and order (D3):** Reopening widget configuration now pre-selects the existing shortcuts in their saved order and preserves the selected layout across screen rotations and process death.
  - **Fail-safe placeholder rendering (D4):** Protected all widget data resolution with error guards, preventing widgets from getting stuck on a blank loading view if a database query fails, and added a lightweight initial preview layout.
  - **Automatic widget synchronization and cleanup (D6):** Widgets automatically refresh whenever shortcuts or custom templates are modified or deleted, and deleted shortcut IDs are automatically pruned from widget configs.
  - **Grid widget label clipping (D7):** Adjusted 2x2 grid cell layout weights so shortcut labels and icons are never clipped or crowded.
  - **Defensive update frequency and placement resilience (D8):** Configured widget update interval to 30 minutes as a periodic recovery net, ensured the configuration screen sets cancel results upfront to prevent orphaned launcher tiles, and kept the configuration activity on the back stack when jumping to create a new shortcut.
  - **App upgrade refresh:** Automatically refreshes all active home-screen and lock-screen widgets when the app is installed or updated (`ACTION_MY_PACKAGE_REPLACED`).
- **Recording no longer freezes the screen while preparing a captured tap.** The recorder starts its pre-tap accessibility snapshot on a dedicated worker, relays within a strict post-lift budget, and only compiles or writes capture data after Android accepts the relay. Slow or missing snapshots fall back to the recorded coordinate instead of holding input.
- **Interrupted Engine v2 recordings now recover safely.** Capture events are bounded, append-only records with crash-safe atomic metadata snapshots; a truncated final record is recoverable, while real corruption is reported and safely falls back instead of silently producing a partial workflow.
- **Keyboard submission is no longer guessed from ordinary screen activity.** A v2 submission can only be inferred from a committed, still-focused field with an exact node identity that either changes to a different activity or clears in one event. Same-activity dialogs, progressive deletion, and legacy content/window changes no longer add a fake Enter step.
- **Editing recorded Engine v2 shortcuts now preserves your edits without silent data loss or engine downgrade.** Editing typed text in a recorded step now updates the recorded text input; changing the target app on an open-app step updates the launcher app; and adjusting wait durations updates step timing. Properties captured during recording that cannot be modified (target elements, selectors, action types, and coordinate overrides) are marked read-only with a "Recorded" lock indicator and clear note in the manual editor, preventing accidental edits from being silently dropped.
- **Post-recording review screen now keeps shortcuts in Engine v2 format.** Reviewing a freshly recorded Engine v2 shortcut previously saved through legacy JSON fallback, silently losing captured UI selectors. The review list now displays and edits the Engine v2 workflow directly, preserving Engine v2 format when deleting or reordering steps. Inferred keyboard actions (Search, Send, Go, Done) are clearly labeled as detected keyboard actions so they can be reviewed or removed before saving.
- **Engine v2 shortcuts now display, edit, and run correctly across the entire app.** Previously, shortcuts saved in the Engine v2 format showed "0 steps" on dashboard cards and widget pickers, appeared empty when opened in the manual editor, and saving from the editor would overwrite the recording. RepeatKit now projects Engine v2 steps into displayable and editable actions via `ShortcutSteps`, preserves existing v2 steps byte-identically when unedited, supports deleting, reordering, and adding actions in the editor while keeping v2 format, refuses to overwrite unreadable shortcuts, and runs v2 editor drafts through Engine v2.
- **Soft keyboard submissions now captured in Engine v2 workflows.** Keyboard actions (Gboard Search, Send, Go, Done) were previously lost during recording because IME action keys bypass key-event listeners and emit no accessibility click events; the capture bridge now reliably infers submissions when an activity/window opens or a field clears right after typing, while ignoring keyboard dismissals and suggestion updates.
- **Accessibility detection now follows the installed package.** The dev build no longer checks
  the release package name when deciding whether its own accessibility service is enabled.
- **Capture-and-relay recording now stays out of RepeatKit and system screens, while preserving fast follow-up taps and keyboard actions.** The recorder uses the installed app's actual package (including the Dev build), only filters semantic callbacks around its own relays, and keeps normal long presses from timing out early.
- **App-launch recording collapse is no longer silently disabled on some devices.** The mechanism that detects the launcher package could permanently fail silently on some devices, disabling the "open app" step collapse entirely with no retry. It now retries until successful, and logs a warning if it ever fails.
- **Recorded shortcuts now reflect what was actually done.** Typing was recorded as one bogus step per character, cursor-selection noise looked like extra edits, Enter and long-press were lost, and a launcher “Home” tap could survive beside the real app launch. The recorder now saves one final full-text step per field-editing session, records explicit Enter and long-press steps, ignores selection-only events, and removes the full launcher-tap burst when it resolves to opening an app.
- **Replay now tells you exactly when and where it could not continue.** Recorded long presses and Enter submissions are replayed alongside taps, typing, and scrolling; unsupported recorded screen actions now report a clear error instead of being ignored. When a replay stops or only partly completes, RepeatKit shows a non-blocking bottom pill with the failed step and reason—even when launched from a home-screen widget—while keeping a notification fallback if Accessibility Service is unavailable.
- **Fixes from a hands-on human-perspective device test.** A live walkthrough of the app (fresh install → record → save → run → AI describe → Settings) surfaced several trust and polish issues, now fixed:
  - A new manual shortcut no longer starts pre-populated with two phantom placeholder steps; it starts empty like the AI builder does.
  - The manual and AI builders now prompt to discard before Back silently loses unsaved steps or an in-progress AI draft, matching the recorder's existing discard-confirmation behavior.
  - The post-recording review screen now has "Check" and "Run now" controls, reusing the same preflight/`ShortcutRunner` wiring as the manual builder, so a freshly recorded shortcut can be validated or tried before saving.
  - Dashboard card taps now go through the same confirm-before-irreversible-action check as editor runs, instead of always executing instantly with no warning; shortcuts with no outward-facing actions still run in one tap.
  - Shortcut-failure notifications (e.g. "couldn't finish — Step 1: ...") now post on a heads-up-capable channel instead of silently landing in the notification shade's Silent section with no banner, sound, or vibration.
  - Settings' "Allow notifications" prompt now reflects the actual granted state instead of always showing an actionable button.
  - The icon/color picker in both builders now has a visible label and screen-reader description.
  - The AI builder's "Downloading model…" indicator now shows the real download percentage instead of a bare spinner.
  - Fixed a pluralization bug ("1 shortcuts · 0 on homescreen").
- **Correct natural-language descriptions for SCROLL actions.** `ActionDescriber.describeScreenAction()` now correctly formats SCROLL actions (e.g. "Scroll down on Feed" or "Scroll on the current screen") incorporating target and direction instead of mislabeling them as taps. Added regression unit tests in `ActionDescriberTest`.
- **Removed unused permissions from AndroidManifest.** Removed `SYSTEM_ALERT_WINDOW` (superseded by `TYPE_ACCESSIBILITY_OVERLAY`), `READ_CONTACTS` (unused by dial/SMS intent flows), and redundant legacy Wi-Fi permissions (`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`). Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` were also removed here, but had to be restored with `maxSdkVersion="30"` (see below) because Bluetooth shortcuts need them on Android 11 and older.
- **Corrected foreground service type for AutomationExecutionService.** Updated `AutomationExecutionService` in `AndroidManifest.xml` from `dataSync` to `specialUse` with a matching `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property tag to match `RecorderSessionService` and comply with Play Store policies.
- **In-context permission requesting.** Refactored `MainActivity` to eliminate global upfront permission popups at launch, requesting permissions in-context at point of feature use.
- **Tracked cleanup coroutine in ShortcutWidgetReceiver.** Used `BroadcastReceiver.goAsync()` and `pendingResult.finish()` in `ShortcutWidgetReceiver.onDeleted()` to prevent process termination during async widget cleanup.
- **Narrowed accessibility event subscriptions.** Refactored `accessibility_service_config.xml` to subscribe only to event types actually handled by `AutomationRecorder` (`typeWindowStateChanged`, `typeViewClicked`, `typeViewTextChanged`, `typeViewTextSelectionChanged`, `typeViewScrolled`) and removed `flagIncludeNotImportantViews`.
- **Updated architecture documentation.** Updated `okf-docs/architecture.md` to reflect Room DB version 8 schema and entities.
- **Coordinate fallback taps can now actually run.** The accessibility service now requests Android's gesture capability, which is required for recorded fallback taps to receive a completion or cancellation callback.
- **Shortcut runs no longer overlap.** Rapid widget taps and other simultaneous triggers now wait their turn, so one automation chain cannot cancel another chain's gesture or report its failure under the wrong shortcut.
- **Recording stops tell the truth.** Accessibility disconnections and backing out of a live recording no longer also announce that a shortcut was recorded; Back now closes the foreground recorder and tap-mark overlay instead of leaving them running invisibly.
- **Tap marking and replay are safer under changing screens.** A stale queued overlay can no longer appear after recording ends or is re-armed, and coordinate fallback refuses a tap when the recorded display size, density, or rotation no longer matches the current display.
- **Long-running accessibility sessions no longer retain unbounded diagnostics or node handles.** Replay tracing keeps a bounded recent history, and unused accessibility-tree nodes are released during searches, parent lookup, editable lookup, and scrolling.
- **Privacy and data handling fixes.** The recorder now automatically excludes password and sensitive text fields from being recorded to prevent capturing sensitive data. Recorded automation data is now explicitly excluded from Android Auto Backup to prevent sensitive information from being backed up to the cloud or transferred to other devices. Sensitive text injected during automation replay is now redacted in production logs, and ProGuard rules strip these logs from release builds. Target package validation has been added to UI actions to prevent automations from inadvertently interacting with unintended apps during replay.

### Fixed — release-readiness pass

A follow-up audit found several of the fixes above were incomplete, and one had
introduced a regression. This pass addresses those, plus the remaining blockers
to a Play Store release.

- **Backing out of a recording no longer throws your work away.** The previous fix stopped Back from leaving a recording running invisibly, but did it by discarding every captured step with no warning. Back now asks before discarding, and tells you how many steps are at stake.
- **The app can be submitted to Google Play again.** It targeted API 35; Play has required API 36 for new apps and updates since 31 August 2026.
- **Releases now produce a signed App Bundle.** The release workflow ran only a debug build with no test or lint gates, so no publishable artifact was ever produced. It now gates on lint and unit tests, then builds a signed bundle and APK.
- **Replay can no longer act in the wrong app.** App-identity checking previously covered only element matching, so a correctly-rejected match still fell through to a blind coordinate tap in whatever app happened to be in front. Coordinate and scroll fallbacks now refuse when the app or the screen geometry cannot be verified, and manually marked taps finally record which app they came from.
- **The accessibility service no longer reads screen content while idle.** It examined and logged event content even when no recording was active, only discarding it at the very end.
- **The permission disclosure can no longer be skipped.** A second, shorter consent path let you reach Android's accessibility settings without seeing the full disclosure first.
- **A recording interrupted by the system no longer resumes invisibly.** After the app was restarted by Android, recording could silently continue with no notification and no way to stop it.
- **Bluetooth shortcuts work again on Android 11 and older**, where the modern `BLUETOOTH_CONNECT` permission does not exist.
- **The AI model download survives Android 16's foreground-service time limit** instead of crashing the app, verifies the downloaded file against its published checksum, and resumes instead of restarting a 271 MB transfer from zero.
- **Deactivated shortcuts stay deactivated.** They previously ran anyway when triggered.
- **Repeatedly tapping a widget no longer queues up a pile of runs** that all fire later.
- **AI-generated typing and web-request steps work.** Typing steps carried no target and always failed; POST and PUT requests without a body were rejected outright.
- **Steps that type text are described correctly in review**, rather than being labelled as taps — the same bug as the recent scroll mislabelling, which only covered one of several names for a typing step.
- **Less sensitive information leaks into logs and notifications.** Fixed logs that printed recorded on-screen text and entire raw AI responses, and stopped web-request response bodies from appearing in notifications and on the lock screen.
- **Notification permission is requested before recording starts**, so the Stop and Mark-a-tap controls cannot end up hidden.
- **A privacy policy is now linked from Settings.**
- **Narrowing the accessibility service's data access now actually takes effect.** The broad "include not important views" flag was being re-applied at runtime, silently undoing it.
- **Various leaks closed:** accessibility node handles during element search and tap marking, a growing diagnostic buffer that retained on-screen text, duplicated AI progress collectors, the native inference engine, and widget cleanup that could be killed before it finished.

### Added — Phase 0 groundwork

- **The app has a launcher icon.** It previously had none at all and showed the default Android placeholder.
- **Long shortcuts are finally editable.** The manual builder and recorder review lists now scroll, so steps past the first screenful can actually be reached. Steps can be inserted at a chosen position instead of only appended, and reordering uses move-up/move-down controls that work with TalkBack and Switch Access.
- **The shortcut name can be edited in the AI review screen.**
- **A running shortcut can be stopped.** The notification now has a Stop action that interrupts between steps.
- **Continuous integration.** Lint and unit tests now run on every pull request and push, not just at release time.

### Fixed — Phase 0 groundwork

- **Saving is durable.** Every builder previously saved without waiting for the write to finish, so the app could report success and navigate away before anything was actually stored — and a failed save could discard the work silently. Saves now show progress, complete before navigating, and keep your draft with a real error message if they fail. This matters most for recordings, where losing one means performing the whole interaction again.
- **Running a shortcut behaves the same everywhere.** Dashboard and widget launches previously took separate paths that disagreed about whether a shortcut was active, what happened if one was already running, and what you were told. They now share one gateway, and a refusal always says why.
- **The AI review screen no longer lies about what it can do.** Back did nothing, the overflow menu on a step was decorative, and there was no way to edit, delete or reorder a generated step. All of those now work.
- **Generated shortcuts are named correctly** instead of being called the literal text "AI Shortcut: $promptText".
- **A permission failure opens the setting that would actually fix it**, rather than a generic app-details page.
- **Help no longer overstates the product.** It implied shortcuts eventually run automatically; the app has no time, location or event triggers today.
- **Removed a widget-creation screen that was unreachable** through normal navigation.

### Known issues
- **Chrome address-bar searches don't record the typed text yet.** The recording keeps "open Chrome, tap the address bar" but not the query or its submission, so the replay stops at the address bar.
- **In-app searches that show results on the same screen are not yet reliable.** In YouTube Music, a recorded search replayed correctly in 8 of 11 test runs, but only 1 of 3 on the final build. Some recordings kept a partial query or missed the Search press. Testing typed with adb, which hides the on-screen keyboard and moves focus differently from real typing, so real-world reliability is unknown.
- **Google Messages is untested on device.** The test phone's Messages app was blocked by a mandatory Play Store update.
- **Lock-screen widgets are untested on device.** The widget declares lock-screen support, and actions that need the phone unlocked ask for it, but the Pixel 6a's Android build has no lock-screen widget host to place one on.
- **After choosing a shortcut for an empty widget, you land in RepeatKit** instead of back on the home screen.
- **A tap made the instant a screen opens can be recorded but not received.** If the app isn't accepting input yet, it ignores the tap but RepeatKit still records it, so replay performs a tap that had no effect during recording.
- **Back is detected, not observed.** Android never tells accessibility services about the on-screen Back button or gesture, so Back is inferred from the app returning to its previous screen. If an app returns to the previous screen on its own more than 0.6 s after a tap, that's also recorded as "Go back (detected)". Replay then skips it when the app has already gone back, so it can't navigate one screen too far.

## [0.7.4] - 2026-08-22
### Added
- **Diagnostic logging for missing taps.** A new debug-only raw-event logger captures every accessibility event during a recording session, making it easier to diagnose cases where custom UI elements (like Messages contact lists) handle taps directly without firing the standard click events that the recorder relies on.
- **Broader event capture for diagnostics.** The recorder's accessibility configuration now opts into long-clicks, selection changes, and window content changes for diagnostic purposes, laying the groundwork for more reliable target capture.

### Fixed
- **Stopping a recording now confirms success.** When you stopped a recording session, the "Recording" notification simply vanished, leaving no way to get back to the app to save your steps. It now leaves behind a final, dismissible "Shortcut recorded" notification summarising what was captured, and tapping either the ongoing or the finished notification now correctly reopens the app so you can finish saving your shortcut.
- **Replaying a shortcut no longer hangs.** A shortcut whose target couldn't be found on screen could silently block for 90+ seconds instead of failing fast, because the retry loop budgeted its iteration count assuming each target search was near-instant — real accessibility-tree searches are not. It now checks elapsed time directly and fails within its intended ~5-second window, with a real error message instead of silence.
- Fixed a bug where `waitForScreenToSettle` was never actually called when executing automations in production, as the accessibility service parameter was missing. The service instance is now successfully retrieved as a fallback.
- **Tapping rapidly in custom lists no longer drops recorded steps.** A bug in the recorder's duplicate-click filter could accidentally lump together distinct taps (like selecting different contacts in a list quickly) if they lacked specific identifiers, and would continually extend its ignore-window if you kept tapping. Taps on different list items now correctly record as separate steps.

## [0.7.3] - 2026-08-21
### Fixed
- **Replayed shortcuts are much more reliable.** Replay now waits for the screen to actually settle before clicking or typing — trying the target immediately first (so an already-ready screen isn't slowed down), then giving a genuinely transitioning screen a bounded moment to settle, instead of either a blind fixed sleep or a wait that could burn its entire budget on a screen with harmless background activity (a live ticker, a typing indicator) and fail even though the target was there the whole time.
- **Improved target finding during replay.** Replay no longer selects the first element that blindly matches text. It now scores all potential candidates based on text match, class name, View ID, and proximity to the original tapped location, reliably clicking the correct target in lists and dynamic layouts.
- **Lightweight tracing for automations.** A diagnostic trace now keeps an in-memory record of all candidate nodes found for a target, which one was picked and its score, and how long a step waited for a screen to be ready.
- **Recording no longer saves noisy accessibility events as extra steps.** Repeated click callbacks on
  the same control are ignored, a swipe's many raw scroll callbacks become one step after the
  gesture settles, and upward/backward swipes now replay in the direction actually recorded.
  Keyboard, permission, resolver, and other transient system windows no longer masquerade as an
  app launch while recording.

### Known issues
- If the very first app touched during a recording is opened by something other than tapping its
  launcher icon (e.g. a notification), that open won't be captured as a step.

## [0.7.2] - 2026-08-21
### Changed
- **Merged the AI builder into a single screen.** The AI builder flow used to start with a slot-based template screen that could only branch into a separate free-text mode. The initial screen now combines the visual identity of the template layout with the real text input and quick-start example chips, removing the confusing two-screen split and dead ends.

### Fixed
- **Widget redraw was occasionally unreliable.** Sometimes, after configuring a widget or editing a shortcut, the widget on the home screen wouldn't visually update until the phone screen was turned off and on, or until several other apps were opened. This was caused by two compounding issues: first, if updating one widget failed (e.g. it pointed to a deleted shortcut), the system stopped updating the rest of the widgets in the batch. Second, some phone launchers aggressively cache the widget view and ignore background updates. We now update each widget individually so one broken widget can't block the rest, we log precisely which ones fail to help with future debugging, and we send a "nudge" to the launcher to force it to invalidate its cache and show the new widget right away.
- **Recorder app launches are now replayable.** Opening an app from the home screen was recorded as
  a brittle tap on that launcher's icon, which can move or differ between devices. It now records
  the app itself, so the shortcut opens the intended app by package name; simply going home or
  moving between screens within the same app does not add a fake step.
- **The recording controls no longer hide in Silent notifications.** The ongoing recorder
  notification and its Stop button now appear in the normal notification section while remaining
  free of sound and vibration.

## [0.7.1] - 2026-08-20
### Added
- **Choose a widget layout.** The widget setup screen showed three previews — Single tile, Grid of
  four, Scrolling list — that looked like options but had no click handler at all; the layout was
  only ever inferred from the widget's size. There is now a real **Layout** picker with
  **Automatic** (the old size-adaptive behaviour, still the default) plus the three explicit
  layouts, and an explicit choice stays put however you resize the widget. Tapping a preview
  selects that layout too. Stored via an additive Room migration 7→8; widgets placed before this
  keep adapting to their size.
- **Wait action.** "Wait" is now a step you can add like any other: pick 1, 2, 3, 5, 10, 15, 30 or 60
  seconds and the shortcut pauses there, so a following step can wait for a screen to finish
  loading. Previously a pause could only exist as an invisible property of another step, and the
  pause after the *last* step was skipped entirely.
- **Edit a shortcut.** The ⋮ menu on each shortcut now has an **Edit** item, which opens the builder
  preloaded with that shortcut's name, colour, icon and steps. Saving updates the existing shortcut
  rather than creating a duplicate, so any widget already pointing at it keeps working.
- Recording now captures **scrolls**. Previously only taps and typing were recorded, so a recording
  that involved scrolling could never replay correctly.

### Fixed
- **A pinned widget stayed stuck on "Tap to set up" forever.** Adding a widget from the app genuinely
  worked — Android bound the widget and the app wrote its config row — but the follow-up redraw asked
  Glance for the new widget's id before Glance knew about it. `getGlanceIdBy` *throws* in that window,
  the throw vanished inside the broadcast receiver's coroutine, and the widget never re-rendered. The
  refresh now retries briefly, falls back to redrawing every instance, and logs a failure instead of
  swallowing it. Placed widgets are also redrawn on app launch so ones already stuck recover.
- **"Place widget" appeared to do nothing.** Opening setup and pressing the button saved the
  configuration and then threw while redrawing the widget — before the code that closes the screen.
  So the button looked completely dead even though it had worked. The redraw is now best-effort and
  can never block the screen from closing, and any remaining failure surfaces as a message instead
  of silence.
- **Dropping a widget from the launcher's picker skipped setup.** The widget now declares itself
  `reconfigurable`, so the launcher initiates the setup flow when the widget is placed and the
  widget can be reconfigured later via its pencil icon.
- **The header always read "0 on homescreen".** The count was read once per change to the *shortcut
  list*, but adding a widget changes the widget table and not the shortcut list, so the count never
  refreshed. It now observes the widget table directly.
- **Recorded shortcuts were unusable on replay.** Replay looked for its target once, at the instant
  the step ran, and gave up if it wasn't there — but a replayed shortcut runs far faster than a
  person tapped it, so the target routinely hadn't appeared yet. Replay now waits up to 5 seconds
  for the target to show up.
- **Recording dropped steps.** Unlabelled taps were discarded outright, and the accessibility config
  throttled events (`notificationTimeout=100`) and never subscribed to scroll events. Each step now
  stores several selectors — text, content description, view id, class name, and the screen point
  actually tapped — and falls back through them at replay time, including a coordinate tap when
  every selector misses.
- **"This screen couldn't be automated" told you nothing.** A failed tap or type now says which
  target it was looking for and why it failed: not on screen, no readable screen, nothing
  scrollable, or found but unresponsive.
- **A normal run said far less than Test Run.** Running a shortcut from the dashboard reported only
  "Couldn't run 'X'", while Test Run named the failing step and the reason — backwards, since the
  dashboard is where shortcuts are actually used. Both now describe a failure in the same words,
  from one shared formatter.
- **Test Run could freeze the app.** It ran the shortcut on the main thread; with a Wait step that is
  an ANR. Execution now runs off the main thread.
- **Web requests reported success without evidence.** The HTTP status code and a truncated response
  body are now carried back from the request, so you can tell a webhook actually fired. The "Web
  Request" action also describes what it is for in plain language — smart-home scenes, IFTTT, your
  own server — instead of just "Call a URL or webhook".
- "Describe to AI" now accepts multiple successive prompts, allowing users to build up a shortcut
  turn by turn instead of starting over. Each turn is given the most recent steps as context so
  follow-ups like "then close it" resolve, without letting the prompt grow unbounded on a small
  on-device model.
- Backing out of a builder route (AI builder, manual builder, or recorder) now correctly discards
  that route's in-progress draft and cancels pending model downloads or executions. Builders now
  guarantee a clean start upon re-entry.

### Known issues
- The launcher widget-picker flow and the widget layout picker have not been verified on hardware
  for this release.

## [0.7.0] - 2026-08-20
### Fixed — execution correctness (the app's worst bugs)
- **Device toggles never toggled anything.** `ActionExecutorService.handleSystemToggle` compared the
  target to `"WIFI"` in uppercase while the AI emits lowercase, so EVERY toggle — including WiFi —
  fell through to a catch-all that opened the generic Settings app. The on/off `state` was parsed,
  stored, and then never read. Targets are now matched case-insensitively, `state` is honoured, and
  flashlight / Do Not Disturb / volume / ring mode perform real toggles. Verified on-device: a
  lowercase `wifi` shortcut now resolves to `SettingsPanelActivity`, not `SettingsHomepageActivity`.
- Where Android forbids a toggle (WiFi and Bluetooth since API 29/33, Airplane mode entirely), the
  app now says so in plain language instead of silently opening the wrong screen. An unrecognised
  target is an explicit failure rather than a catch-all.
- Widget taps discarded the execution result entirely. Execution now returns a per-step `StepResult`
  (Success / Failed / NeedsPermission / Skipped), runs in a foreground service so multi-step chains
  survive the tap, and reports which step failed and why.
- `AutomationWidget` ignored the user's chosen `colorKey`/`iconKey` and always drew a bolt.

### Fixed — the on-device AI pipeline
- The ~271 MB model was **closed and fully reloaded before every generation**, once per clause, so a
  three-clause prompt reloaded it three times. Now one long-lived `LlmInference` engine with a fresh
  `LlmInferenceSession` per generation, serialised behind a Mutex.
- Replaced the regex prompt splitter with a quote- and speech-verb-aware `PromptSegmenter`:
  "Text mom and tell her I'm running late" is one step again, "Play Simon and Garfunkel" is one step,
  "Turn on wifi and open Spotify" is two.
- Every clause now yields exactly one `DraftStep`; a clause the model cannot handle surfaces as
  `Unresolved` instead of being silently dropped (a 3-step request could previously save as 1).
- App references are grounded against the REAL installed-app list via `PackageManager` instead of the
  model recalling package names; ambiguous matches return null rather than launching the wrong app.
- `ClauseAligner` maps batched model output back to clauses by evidence, refusing to pair an action
  with a clause it has no evidence for.

### Added
- **Shortcut recorder.** Record taps and typing in other apps via the accessibility service, review
  the captured steps, and save them as a normal shortcut. Labels are derived from the node tree
  (view id → own text → descendants → ancestors), because the node that fires a click is usually an
  unlabelled container; unidentifiable taps appear as `UNRESOLVED` rather than vanishing.
- Slot-based ("madlib") AI builder and a matching manual builder — the template fixes the action
  type, so the model never chooses the function, which is the step it most often got wrong.
- Review-before-save editor with per-step plain-language descriptions, reordering, and Test Run.
- Messaging and calling via `ACTION_SENDTO` / `ACTION_DIAL` — no Play-restricted permissions.
- System / Light / Dark theme setting, applied immediately and persisted.

### Changed
- New visual design across the app: bundled Schibsted Grotesk (SIL OFL), a warm palette, and
  colour-coded shortcut tiles.
- Tile palette expanded from 6 to 14 colours and icons from 6 to 17, every colour verified at
  >= 3.0:1 contrast against white content. Orange was corrected from a FAILING 2.70:1 to 3.08:1.
  `WidgetColorKey` is now the single source of truth so app tiles and widgets cannot drift apart.
- Five widget types consolidated into one adaptive widget, with an additive Room migration 6→7 that
  keeps legacy tables intact; verified with an instrumented `MigrationTestHelper` test.
- Edge-to-edge insets handled app-wide (required by targetSdk 35 on Android 15+).
- `compileSdk` 36, `targetSdk` 35, release build minified with keep rules.
- Accessibility features gated behind an explicit opt-in and disclosure.

### Removed
- The `AppThemeAccent` accent-colour system, which clashed with the new palette.

### Known issues
- The Android 16 promoted-ongoing status bar chip does not render; the generated bitmap badge showing
  the live step count is used instead. `setShortCriticalText` alone does not promote a notification —
  `FLAG_PROMOTED_ONGOING` is set by the system — and the qualifying characteristics are unresolved.
- The recording notification is not re-posted when the app restarts with a session already active,
  so the Stop action is lost until recording is stopped from inside the app.
- Android revokes this app's accessibility service on force-stop, and ADB cannot re-grant it once the
  app is flagged under restricted settings; it must be granted from Settings.
- Test Run for UI-automation steps depends on the target app's layout and may not replay reliably.

## [0.6.0] - 2026-08-06
### Added
- Per-shortcut appearance customization and Grid/List view mode with sorting in `DashboardScreen` matching Apple Shortcuts' documented behavior (verified against support.apple.com/guide/shortcuts, not assumption):
  - **Manual Per-Shortcut Color and Icon Customization**: Added nullable `colorKey` and `iconKey` fields to `Automation` backed by Room DB migration `MIGRATION_5_6`. Tapping an automation's colored icon box opens a sheet displaying the 6-color swatch row and 6-icon grid from `CreateWidgetScreen`, calling `AutomationViewModel.updateAppearance` to persist custom styling while falling back to deterministic `AutomationVisuals` defaults if uncustomized.
  - **Grid / List View Mode Toggle**: Added a top-app-bar view mode toggle button switching between a 2-column `LazyVerticalGrid` (defaulting to grid view matching iOS) and `LazyColumn` list view.
  - **List View Sorting**: Added interactive `FilterChip` controls in list view supporting sorting by None (creation order), Name (A-Z), and Action Count, with toggleable ascending/descending order on re-selection. Pure sort logic extracted to `AutomationSorter`.
- Unit tests in `AutomationViewModelTest` for `updateAppearance` and new unit test suite `AutomationSorterTest` testing all 3 sort modes in both directions.
- Visual polish and visual widget-pinning gallery in `DashboardScreen`:
  - **Automation Item Visual Identity**: Redesigned `AutomationItemCard` to feature a deterministic colored rounded-square icon (`WidgetColorKey` and `WidgetIconKey`) based on automation ID, aligning with the visual language of the widget system while preserving all existing card controls.
  - **Visual Widget Gallery**: Replaced the plain-text overflow menu pinning items with a unified "Add Widget to Home Screen" entry point opening a visual `WidgetGalleryBottomSheet` displaying all 5 widget types (Quick Shortcut Tile, Shortcuts List, Custom Widget, Shortcuts Grid, Greeting Widget) with illustrative preview tiles, one-line descriptions from architecture documentation, direct pin actions, and secondary "Create Your Own Widget" links.
- Unit tests in `AutomationVisualsTest` verifying deterministic color/icon mapping and palette distribution, and updated `DashboardScreenTest` for the new gallery bottom sheet flow.
- New Settings screen (`SettingsScreen` + `SettingsViewModel`) reachable via a dedicated top-app-bar gear icon on the Dashboard:
  - **AI Model Management**: View model download state, start download, or delete model on disk (`deleteModel(context)`) with a confirmation dialog.
  - **Accessibility Service Status**: Live status check for `AutomationAccessibilityService` with single-tap navigation to Android Accessibility Settings.
  - **About & Documentation**: App version inspection and direct link to the new Help screen.
- New Help screen (`HelpScreen`) providing clear user documentation:
  - Definition of Shortcuts and automations.
  - Overview of all 4 action types ("Open an App", "Tap or Type on Screen", "Toggle a Setting", "Web Request") with one-line examples.
  - Explanation of all 5 home-screen widget types.
  - Concrete step-by-step example showing how a single Shortcut can chain actions across multiple apps (e.g. Alexa + Hue) in sequence using Accessibility.
- Redesigned action-adding UX in `ManualBuilderScreen`:
  - **Action Selection Bottom Sheet**: Replaced hardcoded default action creation with a `ModalBottomSheet` displaying friendly cards for all 4 action types ("Open an App", "Tap or Type on Screen", "Toggle a Setting", "Web Request") with icons and plain-language descriptions.
  - **App Picker**: Replaced raw package name text fields for `APP_INTENT` actions with an installed app picker (`AppPickerDialog`) querying launchable apps via `PackageManager`, displaying app icons and labels with real-time search filtering.
  - **Human-Readable Action Summaries**: Replaced raw `ActionType` enum names with one-line human-readable summaries (e.g. "Open Alexa", "Tap 'Living Room'") and expandable action cards.
  - **Accessibility Warning Banner**: Added a top-of-screen warning banner navigating directly to Settings if the Accessibility Service is disabled.
  - **Accessibility Utility**: Extracted Accessibility Service status checking logic into `AccessibilityStatusChecker` shared by `SettingsViewModel` and `ManualBuilderScreen`.
- Unit tests for `ManualBuilderScreenTest` covering app filtering/sorting, app label resolution, action summary formatting, and action type metadata.

## [0.5.0] - 2026-08-06
### Added
- Five home-screen widget types built on Jetpack Glance, all sharing one unified tap-to-run pipeline (`RunAutomationCallback` + `AutomationIdResolver`):
  - **Quick Shortcut** (`AutomationWidget`) — a single minimal tile bound to one automation.
  - **Shortcuts List** (`ShortcutsListWidget`) — up to 4 automations in one scrollable widget, each independently tappable.
  - **Custom Widget** (`CustomWidget`) — a single tile with a user-chosen color, icon, and label, bound to an automation via a saved `CustomWidgetTemplate`.
  - **Shortcuts Grid** (`GridWidget`) — up to 6 `CustomWidgetTemplate` tiles in a 2-column grid, for multiple personalized shortcuts in one widget.
  - **Greeting** (`GreetingWidget`) — a time-of-day-aware personalized greeting ("Good morning/afternoon/evening, {name}") with one tappable bound shortcut; the only widget with dynamic, context-based content.
- In-app "Create Your Own Widget" builder (`CreateWidgetScreen` + `CustomWidgetViewModel`), reachable from the Dashboard's overflow menu: pick a color, icon, and shortcut manually, or describe the widget in natural language and let the on-device FunctionGemma model (`OnDeviceInferenceService.generateWidgetSpecJson`) propose one.
- Room DB schema versions 2–5, each with a real non-destructive `Migration` (`MIGRATION_1_2` … `MIGRATION_4_5`) and schema export enabled (`app/schemas/`), backing the new `WidgetBinding`, `WidgetListBinding`, `CustomWidgetTemplate`, `CustomWidgetBinding`, `GridWidgetBinding`, and `GreetingWidgetBinding` entities.
- ~70 new unit tests covering every new DAO, the widget tap-resolution logic (`AutomationIdResolver`), widget-deletion cleanup (`WidgetCleanupHelper`), the greeting time-of-day boundaries (`GreetingTextHelper`), the AI widget-builder flow, and a new `AutomationRepositoryTest`.

### Fixed
- The AI Builder was silently non-functional in production: `AiBuilderViewModel` was never given a real `OnDeviceInferenceService`, so inference always failed after model download. Now wired properly in `ShortcutsNavigation()`, shared with the new widget-builder AI flow.
- `AppDatabase` no longer uses `fallbackToDestructiveMigration()` (which wiped the local DB on every schema change) — replaced with real, additive migrations throughout.
- `CustomWidgetViewModel.generateWithAi()` now gates on model-download completion (mirroring `AiBuilderViewModel`'s flow) instead of silently failing if the FunctionGemma model hadn't been downloaded yet.

## [0.4.0] - 2026-08-03
### Added
- End-to-End (E2E) testing framework and test suite readiness documentation (`TEST_READY.md`) covering Tier 1-4 test scenarios.
- Expanded unit & integration test coverage to 74 test cases across 11 test suites (100% pass rate).
- Open Knowledge Format (OKF) documentation system in `okf-docs/` with YAML frontmatter (`architecture.md`, `automation-schema.md`, `services.md`, `viewmodel-uistate.md`).
- Documented `ModelDownloaderService`, `AutomationAccessibilityService`, ViewModel/UiState architecture, Room DB schema, and action execution pipeline.
- Process integration and Git branch merging strategy.

## [0.3.0] - 2026-08-03
### Added
- Material You (M3 Expressive UI) design implementation including dynamic color scheme, M3 components, and responsive layouts.
- MVVM State Management architecture using `StateFlow` and immutable UI state wrappers across all primary screens (`AiBuilderViewModel`, `ModelDownloadViewModel`, `AutomationViewModel`, `SettingsViewModel`).
- Strict JSON parsing, error boundaries, and state recovery in `AiBuilderViewModel`.
- Comprehensive M3 UI State unit and integration tests including `Milestone3EmpiricalStressTest`.

## [0.2.0] - 2026-08-02
### Added
- `ModelDownloaderService` foreground service for asynchronous AI model downloads with status notifications and state tracking.
- `AutomationAccessibilityService` UI automation execution engine with node traversal, gesture simulation, text input, navigation, and robust recursion limit protection.

## [0.1.0] - 2026-08-01
### Added
- Project initialization and Git workflow setup.
- `AGENTS.md` guidelines and process rules for AI agents.
- Open Knowledge Format (OKF) structure and initial architecture specs in `okf-docs/`.
- Room Database schema, DAOs, and repository layer for Shortcuts & Automation workflows.
