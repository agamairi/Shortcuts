---
title: "Shortcuts v1.0 — AI-Powered Shortcuts for Android (PRD)"
type: prd
author: claude-opus-5
date: 2026-08-19
status: approved-for-implementation
tags: [prd, android, shortcuts, on-device-ai, glance, widgets, accessibility, play-store]
---

# Shortcuts v1.0 — Product Requirements Document

## 0. One-line intent

**An Android app that does what Apple Shortcuts does — build one-tap automations and put them on your homescreen — where the AI turns plain English into a shortcut you can then see, verify, and edit.**

---

## 1. Why this document exists

v0.6.0 is a functional skeleton with five widget types, an on-device LLM, and a Room database — but the three things a user actually touches are broken:

1. Homescreen widgets run something other than what the user configured.
2. AI shortcut creation stalls, hallucinates, or produces one-step results for multi-step requests.
3. Nothing tells the user when a shortcut fails.

This PRD defines the target product and the specific engineering work to get there. It is written to be executed by delegated coding agents; every requirement is stated to be independently verifiable.

---

## 2. Confirmed product decisions

These were decided by the product owner and are **not open for re-litigation** by implementers:

| Decision | Choice | Consequence for implementers |
|---|---|---|
| AI brain | **On-device only** (FunctionGemma via MediaPipe). No cloud API. | Architecture must NOT assume the model can plan. See §4. |
| Distribution | **Play Store, eventually** | targetSdk 35; accessibility features gated + disclosed; no restricted permissions. See §8. |
| Widgets | **Consolidate five types into one**, migrating existing placed widgets | Room migration 6→7 + legacy receiver shims. See §6. |
| Capability scope | Device toggles, app launching & deep links, messaging & calls, HTTP/webhooks + multi-step chains | All four must work end-to-end and be covered by tests. See §5. |

### 2.1 Stated constraint — read this before designing anything

FunctionGemma (`mobile-actions_q8_ekv1024`) emits **exactly one function call per generation**. It is not a planner. It cannot decompose "text mom I'm late and turn on DND" by itself.

The product owner chose on-device anyway, for privacy and offline operation. That is a legitimate trade and this PRD commits to it fully. **The design consequence is non-negotiable: the app must not depend on model intelligence for correctness.** Multi-step decomposition, entity grounding, and error recovery are all handled by deterministic Kotlin code around the model, and the user always gets a review step before a shortcut is saved.

The product principle that follows:

> **The AI proposes. The user confirms. The editor is the product.**

Apple Shortcuts has no AI at all and is beloved — because its editor is excellent. A great editor plus a modest model beats a weak editor plus a great model.

---

## 3. Root-cause analysis of current failures

Each root cause below is a concrete, located defect. Implementers should verify each one before fixing it.

### 3.1 CRITICAL — System toggles never toggle anything
`ActionExecutorService.handleSystemToggle()` (`app/src/main/java/com/shortcuts/app/service/ActionExecutorService.kt:47`)

```kotlin
val intent = if (action.target == "WIFI") { ... Settings.Panel.ACTION_WIFI ... }
             else { Intent(Settings.ACTION_SETTINGS) }   // <-- everything else lands here
```

Two compounding bugs:
- The model emits **lowercase** (`wifi`, `bluetooth`, `flashlight`, `do_not_disturb`) per the prompt in `OnDeviceInferenceService.buildFunctionCallingPrompt()`. `"wifi" != "WIFI"`, so **every** toggle — including WiFi — falls into the `else` branch and opens the **generic Settings app**.
- `action.state` ("on"/"off") is parsed, stored, and then **never read**. Even the WiFi path only opens a panel.

**This is the primary cause of "the widget does something else entirely."** Every device-toggle shortcut opens Android Settings.

### 3.2 CRITICAL — Five widget types, indistinguishable in the picker
`AutomationWidget`, `CustomWidget`, `GridWidget`, `GreetingWidget`, `ShortcutsListWidget` — each with its own receiver, its own config activity, and its own Room binding table. The homescreen picker shows five similar entries. Picking a different one than intended yields entirely different behavior with no indication why.

### 3.3 HIGH — Configured color/icon silently ignored
`AutomationWidget.kt:118` hardcodes `ImageProvider(R.drawable.ic_widget_bolt)` and never reads `automation.colorKey` / `automation.iconKey`, despite migration 5→6 adding those columns specifically. Only `CustomWidget` honors them.

### 3.4 HIGH — Total absence of execution feedback
`RunAutomationCallback.onAction()` (`RunAutomationCallback.kt:52`) calls `executor.executeActions(actions)` and **discards the returned Boolean**. `executeActions` itself collapses N results into one Boolean, losing which step failed. Tap a widget, nothing happens, no explanation.

### 3.5 CRITICAL — Full LLM reload on every generation
`OnDeviceInferenceService.reinitializeModel()` (`OnDeviceInferenceService.kt:24`) calls `close()` then `initializeModel()` before **every** `generateResponse`. A ~1 GB q8 model is re-read from disk each time. `AiBuilderViewModel.performInference` loops per clause, so a three-clause prompt triggers three full reloads — minutes of apparent hang.

The documented JNI crash it works around is real, but the fix is wrong: MediaPipe's supported pattern is one long-lived `LlmInference` engine with a **fresh `LlmInferenceSession` per generation**. Recycle the session, not the engine.

### 3.6 HIGH — App launching relies on model recall
`open_app{package_name}` asks the model to produce an Android package name from memory. Hallucinated or non-installed package → `getLaunchIntentForPackage()` returns `null` → `handleAppIntent` returns `false` silently. The app never consults `PackageManager` for what is actually installed.

### 3.7 HIGH — Multi-step is faked with a regex
`AiBuilderViewModel.splitIntoSteps()` splits on `,` `;` `and` `then` `after that`. It is not quote- or entity-aware:
- "Text mom **and** tell her I'm running late" → two broken fragments.
- "Play Simon **and** Garfunkel" → two broken fragments.

Failed steps are skipped with `continue`, so a 3-step request can silently save as 1 step.

### 3.8 MEDIUM — Widget spec generation is ungrounded
`generateWidgetSpecJson()` sends a bare "output strict JSON" instruction to a model fine-tuned for function-call output. It will ramble; the parser will fail.

### 3.9 MEDIUM — Platform and policy debt
- `compileSdk`/`targetSdk` = **34**. Play requires 35+ for new submissions.
- Manifest theme is `@android:style/Theme.Material.Light.NoActionBar` while the app renders Material 3 — system dialogs clash.
- No runtime permission flow for `BLUETOOTH_CONNECT` (required API 31+), `POST_NOTIFICATIONS` (33+), or camera/torch.
- `Action` is a 13-field all-nullable bag — a union type impersonating a struct. Unrepresentable states are representable everywhere.

---

## 4. Target architecture

### 4.1 The pipeline that replaces "prompt → model → save"

```
User prompt
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 1. SEGMENTER            deterministic Kotlin, quote-aware   │
│    Splits into atomic intents. Never splits inside quoted    │
│    text or after a speech verb (tell/say/text/message …).    │
└─────────────────────────────────────────────────────────────┘
    │  ["text mom I'm running late", "turn on DND"]
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. GROUNDING CONTEXT    built from the real device          │
│    • Installed apps  ← PackageManager (label + package)      │
│    • Contacts        ← ContactsContract (only if permitted)  │
│    • Action catalog  ← §4.2                                  │
│    Injected into the prompt so the model SELECTS from real   │
│    options instead of RECALLING from training data.          │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. INFERENCE            one segment → one function call      │
│    Long-lived LlmInference engine, fresh session per call.   │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. RESOLVER             deterministic repair layer           │
│    • Fuzzy-match app name against the REAL installed list    │
│    • Resolve contact name against REAL contacts              │
│    • Coerce/normalize enum values (case-insensitive)         │
│    • Mark unresolvable slots as NEEDS_INPUT (never guess)    │
└─────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. VALIDATOR            typed schema + capability check      │
│    Rejects malformed params. Flags steps whose required      │
│    permission or capability is unavailable on this device.   │
└─────────────────────────────────────────────────────────────┘
    │
    ▼  DraftShortcut(steps, perStepConfidence, unresolvedSlots)
┌─────────────────────────────────────────────────────────────┐
│ 6. REVIEW EDITOR        ★ THE PRODUCT ★                      │
│    Every step shown as an editable card. Low-confidence and  │
│    NEEDS_INPUT steps are highlighted. User can edit, delete, │
│    reorder, add steps manually, and TEST RUN before saving.  │
└─────────────────────────────────────────────────────────────┘
    │
    ▼  Save → Room → place widget
```

**Failure policy:** a segment that produces nothing becomes a visible `UnresolvedStep` placeholder in the editor. It is **never** silently dropped (current `continue` behavior). The user always sees that the AI missed something.

### 4.2 The Action Catalog

Replace the 13-nullable-field `Action` bag with a registry of typed actions. Each entry declares:

```kotlin
interface ShortcutAction {
    val id: ActionId                    // stable, serialized
    val displayName: String
    val category: ActionCategory
    val icon: IconKey
    val parameters: List<ParamSpec>     // typed: Text, Enum, AppRef, ContactRef, Url, Duration
    val requirements: List<Requirement> // permissions / capabilities / accessibility
    suspend fun execute(ctx: Context, args: ActionArgs): StepResult
}
```

Why this matters beyond cleanliness:
- The AI's job becomes **constrained selection over a known catalog**, not free-form generation. This is exactly how you get usable output from a small model.
- The manual editor renders itself from `parameters` — no per-action UI code.
- The validator checks `requirements` before save, so users learn a step won't work *before* it's on their homescreen.
- Persisted as versioned discriminated JSON; `ActionConverter` gains a schema version and a v1→v2 upgrade path.

### 4.3 Execution engine

Replace `Boolean` with a real result type:

```kotlin
sealed interface StepResult {
    data object Success : StepResult
    data class Failed(val reason: FailureReason, val userMessage: String) : StepResult
    data class NeedsPermission(val permission: String) : StepResult
    data class Skipped(val why: String) : StepResult
}
data class RunResult(val steps: List<StepResult>, val shortcutName: String) {
    val allSucceeded: Boolean
    val firstFailure: StepResult.Failed?
}
```

Requirements:
- **R-EXEC-1** Execution runs in a foreground service so multi-step chains survive the widget-tap process lifecycle.
- **R-EXEC-2** Every widget tap produces user-visible feedback: success = brief toast/haptic; failure = notification naming **which step** failed and **why**, with a tap-through to fix it in the app.
- **R-EXEC-3** Chain semantics: on step failure, stop by default and report. (`continueOnError` is a per-step flag, default false.)
- **R-EXEC-4** Steps support an optional inter-step delay.

---

## 5. Capability requirements

All four categories must work end-to-end with tests. Android platform reality is stated honestly per capability — implementers must not promise what the OS forbids.

### 5.1 Device toggles

| Toggle | Real mechanism | Status |
|---|---|---|
| Flashlight | `CameraManager.setTorchMode()` | ✅ Fully programmable, both directions |
| Do Not Disturb | `NotificationManager.setInterruptionFilter()` + policy-access grant | ✅ Programmable after one-time grant |
| Volume / ring mode | `AudioManager` | ✅ Programmable |
| Media playback | `MediaController` / media button intents | ✅ Programmable |
| Auto-rotate | `Settings.System.ACCELEROMETER_ROTATION` + `WRITE_SETTINGS` grant | ⚠️ Needs special-access grant |
| WiFi | Android 10+ **forbids** programmatic toggle. `Settings.Panel.ACTION_WIFI` | ⚠️ Opens panel; one extra tap |
| Bluetooth | Android 13+ **forbids** programmatic enable. `ACTION_REQUEST_ENABLE` / panel | ⚠️ Opens system prompt |
| Airplane mode | **Impossible** without system signature since Android 4.2 | ❌ Panel only |

- **R-TOGGLE-1** Fix the case-sensitivity and `state` bugs from §3.1. Match case-insensitively; honor on/off/toggle.
- **R-TOGGLE-2** For toggles the OS forbids, the UI must say so **at build time**, in the editor, in plain language ("Android doesn't let apps turn WiFi on directly — this will open the WiFi panel for you"). Never silently degrade to opening generic Settings.
- **R-TOGGLE-3** Ship a **Quick Settings Tile** (`TileService`) as the policy-safe path for one-tap access.

### 5.2 App launching & deep links
- **R-APP-1** All app-targeting actions resolve against `PackageManager`'s real installed list. Never trust a model-produced package name.
- **R-APP-2** Fuzzy label matching ("spotify" → `com.spotify.music`), with an in-editor app picker as the authoritative fallback.
- **R-APP-3** Support deep links / custom URIs as a first-class action (Spotify playlist, Maps navigation, a specific chat).
- **R-APP-4** If a shortcut references an uninstalled app, flag it in the editor and in the dashboard — not at tap time.

### 5.3 Messaging & calls — policy-safe design
- **R-MSG-1** **Do NOT request `SEND_SMS`.** It is a Play-restricted permission requiring a declaration form and is a rejection risk (§2 decision: Play Store eventually).
- **R-MSG-2** Use `Intent.ACTION_SENDTO` with the recipient and body **pre-filled**, so the user's own SMS app opens and they tap send. This is policy-safe, requires no restricted permission, and is the correct UX for an automation that sends messages on someone's behalf.
- **R-MSG-3** Calls use `ACTION_DIAL` (pre-filled dialer, no `CALL_PHONE` permission) by default.
- **R-MSG-4** `READ_CONTACTS` is requested **only** when the user adds a messaging/call action, with an in-context rationale. The app must remain fully functional if denied (manual number entry).

### 5.4 HTTP / webhooks & multi-step chains
- **R-HTTP-1** Migrate raw `HttpURLConnection` to OkHttp/Retrofit (already a dependency). Support custom headers, body, and auth tokens.
- **R-HTTP-2** Secrets (API keys, webhook tokens) stored via `EncryptedSharedPreferences` — never in the Room `actionsJson` blob in plaintext.
- **R-HTTP-3** Enforce HTTPS by default; require explicit per-action opt-in for cleartext.
- **R-HTTP-4** Chains support sequencing, per-step delays, and the `StepResult` error semantics of R-EXEC-3.

---

## 6. Widget consolidation

**Target:** one `ShortcutWidget` that adapts to its placed size via `SizeMode.Responsive`:

| Size | Rendering |
|---|---|
| 1×1 / 2×2 | Single tile — icon, label, user's chosen color |
| 4×2 | 2×N grid of shortcut tiles |
| 4×4+ | Scrollable list with names and icons |

Requirements:
- **R-WIDGET-1** New unified `widget_configs` Room table. Room migration **6→7** maps all five legacy binding tables into it, preserving every currently-placed widget's behavior. Exported schema `7.json` committed.
- **R-WIDGET-2** Legacy receivers (`AutomationWidgetReceiver`, `CustomWidgetReceiver`, `GridWidgetReceiver`, `GreetingWidgetReceiver`, `ShortcutsListWidgetReceiver`) **remain declared in the manifest** — removing a receiver orphans widgets already on the user's homescreen. Each becomes a thin shim rendering through the unified renderer against the migrated config. Their `android:label` is prefixed `(Legacy)` so the picker is unambiguous during the transition.
- **R-WIDGET-3** The widget honors `colorKey` and `iconKey` in **all** render paths (fixes §3.3).
- **R-WIDGET-4** Unconfigured widgets show an explicit "Tap to set up" state that opens config — never a blank or misleading tile.
- **R-WIDGET-5** Widget state refreshes when its underlying shortcut is renamed, recolored, or deleted.
- **R-WIDGET-6** Deleting a shortcut that is bound to a placed widget warns the user first, and the widget degrades to the "needs setup" state rather than silently doing nothing.

---

## 7. UX requirements

- **R-UX-1 Review-before-save is mandatory.** AI generation lands in the editor, never straight into the database. Low-confidence and unresolved steps are visually flagged.
- **R-UX-2 Test Run.** Every shortcut is runnable from the editor before saving, showing per-step pass/fail. This is the single highest-value feature for a weak-model product — it converts "AI got it wrong" into "I fixed it in five seconds."
- **R-UX-3 Progressive disclosure of AI cost.** The 1 GB model download must be explained before it starts, resumable, cancellable, show real progress and remaining size, and never block manual shortcut building.
- **R-UX-4 Manual building is a first-class path,** not a fallback. A user who never downloads the model must be able to build every shortcut type by hand.
- **R-UX-5 Honest capability messaging.** Where Android forbids something (§5.1), say so at build time in plain language.
- **R-UX-6 Run history.** A per-shortcut log of recent runs with per-step outcomes, for debugging failures.

---

## 8. Play Store readiness

- **R-PLAY-1** `compileSdk` and `targetSdk` → **35**.
- **R-PLAY-2** **Accessibility gating.** The `AutomationAccessibilityService` powers UI automation, which is the highest-risk area for Play review. Required:
  - UI-automation actions are hidden from the catalog unless the user explicitly opts in.
  - A dedicated disclosure screen explains what the service does, what data it accesses, and that it is used for user-initiated automation only.
  - The app is fully functional with the service disabled.
  - `accessibility_service_config.xml` requests the **narrowest** event types and flags sufficient for the feature.
- **R-PLAY-3** No restricted permissions: no `SEND_SMS`, no `CALL_PHONE`, no `QUERY_ALL_PACKAGES` (the existing scoped `<queries>` element is correct — keep it).
- **R-PLAY-4** Runtime permission flows with in-context rationale for `POST_NOTIFICATIONS` (33+), `BLUETOOTH_CONNECT` (31+), `READ_CONTACTS`, and camera/torch.
- **R-PLAY-5** Manifest theme → a Material 3 app theme; remove `@android:style/Theme.Material.Light.NoActionBar`.
- **R-PLAY-6** Release build: enable minify + resource shrinking with correct keep rules for Room, Glance, Gson, and MediaPipe. Replace the debug signing config on release with a real one.
- **R-PLAY-7** A Data Safety–ready statement: all inference is on-device; no user content leaves the device except explicit user-authored HTTP actions.

---

## 9. Testing requirements

Non-negotiable, per `Agents.md` (MVVM, JUnit/MockK, Espresso):

- **R-TEST-1** Unit tests: segmenter (incl. quote/speech-verb cases from §3.7), resolver fuzzy matching, validator, every action's parameter schema, `StepResult` chain semantics.
- **R-TEST-2** Room migration tests for **6→7** proving every legacy binding table migrates without data loss.
- **R-TEST-3** Instrumented tests: widget render at each size class, config flow, tap→execute→feedback.
- **R-TEST-4** Regression tests pinning each §3 defect: lowercase `wifi` toggles WiFi (not generic Settings); `state` is honored; color/icon render; failures surface to the user.
- **R-TEST-5** `./gradlew assembleDebug testDebugUnitTest` green at every merge.

---

## 10. Definition of done

v1.0 ships when a user can:

1. Type "text mom I'm running late and turn on do not disturb" and get a **two-step** shortcut with the message body intact and the contact resolved against their real contacts.
2. See both steps in an editor, correct anything wrong, and **test-run** before saving.
3. Place **one** widget on the homescreen, with the color and icon they chose, and have it run exactly those two steps.
4. See a clear message when a step fails, naming which step and why.
5. Build all of the above **by hand**, without ever downloading the AI model.
6. Toggle the flashlight and DND for real — and be told plainly, in the editor, that WiFi and Bluetooth will open a system panel because Android requires it.

---

## 11. Out of scope for v1.0

Location/time/event triggers; shortcut sharing/import/export; home automation integrations beyond generic HTTP; Wear OS; widget theming beyond the color/icon palette; cloud sync.

---

## 12. Delivery phases

| Phase | Content | Gate |
|---|---|---|
| **P0 — Stop the bleeding** | §3.1 toggle bugs, §3.4 feedback, §3.3 color/icon, §3.5 model reload | Existing widgets do what they say |
| **P1 — Foundation** | Action Catalog (§4.2), `StepResult` engine (§4.3), schema v2 + migration | Tests green, no behavior regressions |
| **P2 — Widget unification** | §6 in full, migration 6→7 | Placed widgets survive upgrade |
| **P3 — AI pipeline** | §4.1 segmenter → grounding → resolver → validator | Multi-step prompts produce multi-step drafts |
| **P4 — Editor** | §7 review editor, test run, run history | R-UX-1/2 satisfied |
| **P5 — Capabilities** | §5 all four categories complete | End-to-end tests per category |
| **P6 — Play readiness** | §8 in full | Release build passes pre-launch report |

Per `Agents.md`: all work on feature branches off `development`, `changelog.md` updated before each merge, and **no commits or pushes Mon–Fri 09:00–17:00** — record proposed commits in `git_commits.md` instead.
