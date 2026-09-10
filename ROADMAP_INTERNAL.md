# Shortcuts: Product and Engineering Roadmap

**Status:** Internal planning document  
**Research date:** 2026-09-06  
**Recommended product position:** *Show it once. Save it. Run it locally.*

## Executive recommendation

Shortcuts has a promising core: it can record cross-app interactions, convert them into an editable local script, replay them manually or from a widget, and augment that script with device, communication, and HTTP actions. The recent `[Unreleased]` work in `changelog.md` closed many serious correctness, privacy, lifecycle, and Play-policy defects. Those items are treated as complete and are not repeated here.

The app is not yet ready for a broad public release. The remaining problem is not mainly a shortage of action types. It is that the complete user journey is not yet dependable enough: some editor controls are inert, saves are not awaited, execution behavior differs by entry point, a failed run leaves little durable evidence, the current “Test run” can perform real side effects, and the app does not provide user-controlled backup. The app also lacks a launcher icon, substantive onboarding, localization readiness, and a continuously enforced release-quality test suite.

The recommended sequence is:

1. **Stabilize the product contract.** Make editing, saving, running, cancellation, and failure reporting consistent and trustworthy.
2. **Earn a public release.** Add onboarding, app accessibility, localization foundations, user-owned export/restore, model-download transparency, performance budgets, and complete Play assets/declarations.
3. **Build the category baseline deliberately.** Add organization, a small set of policy-safe triggers, conditions, variables, and a fuller action catalog on top of a versioned automation model.
4. **Differentiate around repairability and local intelligence.** Help users fix brittle recorded steps and use the on-device model to author or explain deterministic scripts—not to make autonomous runtime decisions.

Shortcuts should not try to match hundreds of Tasker, Automate, or MacroDroid capabilities before release. Its defensible wedge is a recorder-first workflow that is substantially easier to understand and repair. Reliability and legibility are therefore product features, not engineering cleanup.

## Research scope and evidence standard

This roadmap is based on a broad read of:

- `Agents.md`, `PRD.md`, and `changelog.md`, with every item under `[Unreleased]` treated as already done.
- The OKF material under `okf-docs/`.
- Production code under `app/src/main/java/com/shortcuts/app/`, including `data/`, `planner/`, `repository/`, `service/`, `ui/`, `util/`, `viewmodel/`, and `widget/`.
- Local and instrumented tests under `app/src/test/` and `app/src/androidTest/`.
- `AndroidManifest.xml`, Gradle configuration, and all relevant resources under `app/src/main/res/`.
- Current official Android and Google Play guidance, plus official product descriptions for MacroDroid, Automate, and Tasker.

No source code, build output, device state, or repository history was changed for this research. Per the task constraints, no Gradle or ADB command was run. Findings that require a device, Play Console, or external account are labelled **unverified** or **conditional** rather than stated as facts.

### Effort scale

| Size | Meaning |
|---|---|
| **S** | Contained change, usually one layer and a small test surface |
| **M** | Cross-layer work involving UI plus state/data/service changes |
| **L** | Foundational or multi-flow change involving schema, execution, migration, and substantial verification |

These are relative planning sizes, not calendar commitments.

## Product diagnosis

### What already works as a foundation

- A user can create a shortcut manually, record accessibility interactions, or ask the local AI planner to propose steps.
- The executor supports UI automation, waits, app launch, system toggles, HTTP requests, messages, and dialing.
- A unified Glance widget can launch saved shortcuts, and recording includes the Mark-a-tap overlay for apps that do not emit usable click events.
- Automations are stored locally in Room. The AI model is optional and downloaded rather than bundled in the base app.
- The recent audit work materially improved disclosure, sensitive-field handling, targeting safety, process-death behavior, foreground-service compliance, widget concurrency, and model-download integrity.
- The project has a meaningful local unit-test suite—approximately 354 `@Test` methods—but it does not yet exercise enough real Android behavior.

### The main gap in one sentence

Shortcuts can often perform an automation, but it does not yet give the user a complete, inspectable lifecycle for creating, validating, recovering, organizing, moving, and trusting that automation.

## Observed gaps by dimension

### 1. Feature completeness

#### Runtime triggers are effectively manual

The stored `triggerType` in `data/Automation.kt` describes how an automation was authored, not a configured runtime trigger. Execution is initiated from the dashboard or a home-screen widget. No scheduler, trigger entity, `AlarmManager`/WorkManager orchestration, geofence, NFC handler, notification listener, or app-launch trigger engine was found.

This leaves obvious user jobs unsupported: “run every weekday,” “run when charging,” “run when these headphones connect,” or “only run if I am on this network.” Competitors make trigger/action/constraint composition central: [MacroDroid describes triggers, actions, constraints, and more than 350 built-ins](https://macrodroid.com/helper/); [Automate exposes event triggers, conditions, loops, variables, and more than 400 blocks](https://llamalab.com/automate/); and [Tasker supports application, time, day, location, and event contexts](https://tasker.joaoapps.com/userguide_summary.html).

The answer is not to add every sensitive trigger at once. Exact alarms and background location have material permission, battery, and Play-policy implications. Android recommends inexact alarms for most scheduled work and reserves exact-alarm access for genuinely exact user-facing functions ([alarm guidance](https://developer.android.com/develop/background-work/services/alarms)); background location must be core to the app and justified to users and Play ([background-location guidance](https://developer.android.com/develop/sensors-and-location/location/background)). Time/day and low-risk device-state triggers should come first.

#### There is no compositional logic

`data/Automation.kt` stores a linear list of actions. There is no condition, if/else branch, loop, variable, step output, or reusable sub-shortcut. `continueOnError` is present in the action payload but is not a general flow-control system and is not exposed coherently in the editor.

This makes even modest real-world routines awkward. A webhook cannot feed its result into a later step; a shortcut cannot check whether Wi-Fi is already enabled; and a user cannot guard a fragile UI interaction with “continue only if this screen is visible.” Conditions will require a typed runtime context and stable step identities before UI work begins.

#### Organization and ownership features are absent

No user-facing duplication, folders, tags, favorites, search, import/export, or run-history model was found. The dashboard’s “Recent” filter reverses the DAO result even though `Automation` has no created/modified/last-run timestamps and `AutomationDao` does not specify an order. That label is therefore not a reliable recency view.

Android Auto Backup is enabled, but the database is excluded from both cloud backup and device-to-device transfer by `res/xml/full_backup_content.xml` and `res/xml/data_extraction_rules.xml`. That may be the correct privacy default, but without a user-controlled export/restore path, the user cannot reliably move or recover the shortcuts they invested time creating. Android’s backup behavior and transfer rules are documented in the [Auto Backup guidance](https://developer.android.com/identity/data/autobackup).

#### Action capabilities are deeper in code than in the editor

- The HTTP executor supports fields for headers, body, token, and cleartext policy, while the manual editor exposes essentially method and URL.
- `intentAction` exists in the action model, but app execution currently resolves a package launch intent rather than exposing deep links or custom intents.
- Recorded actions can include scrolling, while the manual UI automation editor offers only tap and type.
- There is no user-facing wait-until-target, long press, swipe, contact picker, or run-time input parameter.

This mismatch makes the app feel smaller than its runtime and encourages users to rely on recording even when a semantic action would be more reliable.

### 2. Reliability and trust

#### Execution semantics differ by launch surface

The dashboard calls `ActionExecutorService` directly, while widgets use `AutomationExecutionService`. Widget execution rejects inactive shortcuts and overlapping widget runs; dashboard execution does not consistently apply the same policy. All paths ultimately contend on a fair blocking `ReentrantLock`, so a dashboard run can wait rather than clearly reject or queue with visible state.

This should be one product operation—“run shortcut”—with one concurrency, activation, permission, progress, cancellation, and receipt contract, regardless of whether the request came from the dashboard, an editor, a widget, or a future trigger.

#### A failure is transient and difficult to repair

`ActionExecutorService` produces useful per-step results in memory, then stops on the first unhandled failure. Those results are not persisted. Dashboard users mostly receive a Toast; widget users may receive a failure notification. There is no run-detail screen, no durable run history, no “open failed step,” no resume-from-failure action, and no repair workflow.

The notification path can say “Tap to open settings,” but its pending intent opens the main activity rather than the specific settings intent associated with the failed capability. This is especially damaging because it promises a recovery action and then does not provide it.

#### “Test run” is not a dry run

Editor test actions invoke the real executor. They can toggle device state, send an HTTP request, start an app, or otherwise cause side effects. There is no preflight mode, step-through mode, or explicit side-effect confirmation. Calling this a test is misleading.

A true preflight can verify syntax, package availability, permissions, settings capabilities, URL policy, and whether stored targets are plausible, but it cannot safely prove that a tap or POST will succeed without performing it. The UI must distinguish **Check** from **Run now**.

#### Cancellation and retry policy are missing

The executor is synchronous, uses sleeps, and provides no cooperative pause/cancel mechanism. The foreground notification does not offer Stop. Target lookup polls for a bounded interval, but that is not an explicit retry policy. Blind retries would be unsafe for side-effecting actions such as POST, message, dial, or toggle. Retry must be action-aware and visible.

### 3. Onboarding, editing, and UX

#### There is no coherent first-run path

The app opens on the dashboard. No first-run route, guided empty state, sample shortcut, capability checklist, or interactive tutorial was found. The accessibility disclosure is appropriately explicit because of the recent audit, but enabling a sensitive system service is not the same as learning the product.

A new user needs to understand three concepts before success:

1. Record an interaction or compose semantic actions.
2. Review and correct the resulting steps.
3. Know when an app does not emit useful accessibility clicks and use Mark-a-tap instead.

Mark-a-tap appears during an active recording and may be suggested heuristically, but Help does not explain it. Its interaction is also non-obvious: the overlay records the location, dismisses, and the user must then tap the underlying app.

#### The three authoring experiences are inconsistent

- The manual editor supports append, edit, reorder, remove, and an actual run.
- Recorder review supports edit, reorder, and remove, but no insertion or safe check/run flow.
- The AI review exposes unresolved-step controls, but resolved cards do not wire their visible menu to edit/delete/reorder behavior. The visual Back action only clears an error rather than navigating, the name is not editable in the review UI, and the default name contains a literal escaped `$promptText`.

View-model methods exist for some AI editing operations, which suggests the UI is incomplete rather than the product intentionally omitting them. `DraftStepCard` and `ReviewStepCard` also appear to represent overlapping implementations.

Both the manual action list and recorder review use non-scrolling column structures in their central editor area. Long shortcuts can overflow. Saves are launched asynchronously and the UI navigates away immediately, so a persistence error may be invisible and the recording may already have been cleared.

#### Some navigation and state affordances are dead or misleading

- A `create_widget` route exists, but the dashboard does not wire its create-widget callback. The custom widget AI flow may therefore be unreachable from normal app navigation.
- A view-model method exists to toggle active state, but the dashboard does not expose it consistently. Users cannot clearly deactivate/reactivate a shortcut from the main surface, while dashboard execution can bypass the state.
- Help describes future automatic initiation and multiple legacy widget types that do not match the current product surface. It omits Mark-a-tap, test side effects, several action types, the model download, and failure recovery.

### 4. Accessibility of Shortcuts itself

This is a release-quality issue, especially for an app asking users to grant AccessibilityService access.

- Many custom controls are 22–44 dp rather than the recommended minimum 48 dp. Android’s Compose accessibility guidance specifies [at least 48 dp touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).
- Color and icon swatches have null content descriptions and lack selected/state semantics. Custom filters and clickable text do not consistently declare roles or selected state. No meaningful use of `semantics`, `stateDescription`, live regions, or headings was found.
- The palette’s tests enforce only a 3:1 white-text contrast threshold. Calculating the current widget palette against white shows six small-text failures below 4.5:1: GREEN (~3.06), ORANGE (~3.08), TEAL (~4.32), PINK (~4.35), CYAN (~3.51), and BLUE_GREY (~4.37). Android guidance uses [4.5:1 for small text and 3:1 for large text](https://developer.android.com/codelabs/basic-android-kotlin-compose-test-accessibility).
- Fixed-height controls and non-scrolling editors create a clipping risk at Android 14’s [up-to-200% nonlinear font scaling](https://developer.android.com/about/versions/14/features).
- There is no automated Compose accessibility-check dependency or an evidenced TalkBack/Switch Access test pass. Compose can run [automated accessibility checks in UI tests](https://developer.android.com/develop/ui/compose/accessibility/testing), though manual assistive-technology testing is still required.

### 5. Internationalization

There is only a default `values/` resource set. The codebase has roughly 49 entries in `strings.xml`, about 16 `stringResource()` call sites, and roughly 98 direct `Text("…")` sites, in addition to hardcoded notifications, Toasts, action labels, errors, XML text, and the manifest label.

Localization currently requires editing Kotlin and will expose concatenation, pluralization, truncation, and RTL problems late. Externalization should happen while the navigation and editor surfaces are being consolidated, not after more strings are added. The first milestone is localization-ready English plus pseudo-locale and RTL verification; shipping multiple translations can follow product validation.

### 6. Testing maturity and CI

The local test count is encouraging, but much of it verifies Kotlin logic around mocked Android APIs. `isReturnDefaultValues = true` further reduces the chance that unmocked Android interactions fail loudly. There is no Robolectric dependency.

The instrumented suite is extremely small—approximately ten tests across six files—and at least two screens are stale against the current UI:

- `AiBuilderScreenTest` expects copy such as “AI Shortcuts Builder” and “Download Model & Generate” that is no longer present.
- `ManualBuilderScreenTest` expects “Manual Builder,” “Shortcut Name,” and trigger chips that are no longer present.

Only database migrations 6→7 and 7→8 have dedicated tests; schemas exist from version 3 onward, leaving the full supported upgrade chain unclear. DAO tests mock interfaces instead of running Room queries. HTTP tests mock Retrofit/OkHttp behavior instead of exercising real request formation against a local server. There is no UI Automator coverage for system settings, widget placement/launch, or accessibility-service integration; no Macrobenchmark module; and no Baseline Profile.

The only GitHub workflow is tag/release-oriented. There is no pull-request gate for tests/lint, no managed-device lane, no dependency/security update automation, and no Play internal-track deployment. The release workflow is useful, but it detects failures too late.

Robolectric is worth adding, but it should not replace instrumentation. It will add high-value coverage for intents, notifications, services, receivers, Room, resources, and process recreation at low runtime cost. Managed-device instrumentation should own behaviors where Android framework fidelity matters: Compose navigation, accessibility semantics, widgets, permissions/settings handoffs, and service lifecycle.

### 7. Performance and resource use

#### Recorder persistence runs on the accessibility callback path

While recording, the session store serializes the growing action list with Gson and synchronously commits it to SharedPreferences for every appended event. Text changes can arrive per keystroke even when later collapsed. This creates work proportional to the full recording length on a latency-sensitive AccessibilityService callback.

The safer design is an in-memory recorder state machine with a debounced, ordered durability writer or append-only journal off the callback thread, plus a final atomic snapshot. It must preserve the recently fixed process-death guarantees.

#### Replay matching repeatedly traverses accessibility trees

Target lookup polls roughly every 150 ms for up to five seconds and can perform view-ID lookup, text lookup, and recursive candidate traversal. Some traversals are bounded, but a single indexed traversal with candidate deduplication would reduce node churn and make matching telemetry easier to interpret. Release builds also retain trace allocation/locking intended mainly for tests.

#### Widget invalidation is broad

An automation invalidation observer starts an unowned IO scope and refreshes all widget provider families. There is no clear debounce or invalidation-to-widget mapping. The five legacy providers also remain declared in the manifest, increasing maintenance and potentially presenting obsolete widget choices.

#### The local model is optional, but the cost is not disclosed early enough

The code reserves about 285 MB; the pinned Hugging Face repository currently presents the model file as approximately 284 MB and identifies a Gemma license ([model repository](https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm)). The initial Generate action can start download without a clear pre-download confirmation of current bytes, network choice, device compatibility, runtime memory/thermal expectations, source, or license. Settings can show and delete a downloaded model, and recent audit work added integrity/resume/timeout behavior; the missing layer is informed user choice and controlled model versioning.

### 8. Distribution and monetization readiness

#### A public listing cannot yet be assembled from the repository

- The application manifest has neither `android:icon` nor `android:roundIcon`, and no `mipmap` launcher assets were found. Android expects launcher/adaptive icon resources referenced from the manifest ([adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)).
- No Play listing copy, phone/tablet screenshots, feature graphic, or store icon source was found. Google Play requires a 512×512 app icon and 1024×500 feature graphic and requires screenshots for publishing ([preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en)).
- Versioning is manual (`versionCode 8`, `versionName 0.7.4`) and is not visibly tied to changelog promotion or migration verification.
- The package/application ID and generic “Shortcuts” name should be deliberately finalized before the first Play release; the application ID becomes the permanent identity of installed users.
- The privacy URL is hardcoded. Its public liveness and content were not verified in this environment and must be checked before submission.
- Five manifest receivers are labelled legacy. Whether they must remain for already-installed public widgets is unknown. If they have never shipped publicly, remove them before launch; if they have, create a staged compatibility/migration plan.

Targeting API 36 is already complete and meets the current Google Play requirement for new apps and updates as of August 31, 2026 ([target API requirements](https://developer.android.com/google/play/requirements/target-sdk)).

#### Play review needs product evidence, not only compliant code

Because this app uses AccessibilityService for automation, its listing and in-app behavior must keep the user-defined, deterministic script model explicit. Google Play permits deterministic, rule-based automation but prohibits a non-accessibility tool from autonomously initiating, planning, and executing actions; it also requires declaration, prominent disclosure/consent where applicable, and review evidence ([Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)). This makes the architecture boundary important: AI may propose a static plan, but the user should review it and runtime execution should not make autonomous decisions.

The release checklist also needs the [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en), privacy disclosures consistent with actual data handling ([User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)), and a foreground-service declaration including use-case explanation and demonstration video ([FGS declaration guidance](https://support.google.com/googleplay/android-developer/answer/13392821)). For personal developer accounts created after November 13, 2023, production access is **conditionally** subject to the closed-test requirement; the account’s status must be checked in Play Console ([testing requirement](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en-GB)).

#### Monetization is not implemented and should not drive the first release

No billing, entitlement, account, ad, or paywall implementation was found. That is acceptable now. Reliability, accessibility, onboarding, user-owned backup, and basic failure history should remain free because they are necessary to make the product safe and trustworthy.

The best initial commercial hypothesis is a **free beta followed by a one-time Pro unlock**, not ads and not an immediate subscription. Advanced triggers, conditions, variables, larger automation limits, and advanced widget customization are reasonable candidates. A subscription becomes defensible only if the product later incurs recurring value/cost through encrypted sync, a maintained template service, or cloud execution. If digital features are sold through a Play-distributed app, the current [Google Play Billing integration guidance](https://developer.android.com/google/play/billing/integrate) and [payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en) apply.

### 9. Architecture and maintainability

#### The persisted action model is a nullable property bag

`Action` contains fields for every action family, most nullable. Unknown future action types fall back to `WAIT` during conversion. This makes invalid combinations easy to construct, complicates editor validation, and can silently reinterpret a newer action on an older app. Before export/import, conditions, history, or more action types, introduce a versioned serialized envelope with typed payloads, stable step IDs, and explicit unsupported-action handling.

#### Large classes mix responsibilities

`AutomationAccessibilityService`, `Recorder`, `RecorderSession`, `ActionExecutorService`, and several Compose screens/view-models are large and span state machines, Android integration, persistence, matching, rendering, and error presentation. Manual, recorder, and AI builders duplicate step rendering and editing concepts. View-models are constructed manually in navigation, including overlapping AI view-model creation, rather than through a consistent factory/dependency graph.

The structural priority is not a broad rewrite. Extract the seams needed by the roadmap: typed action definitions, a capability registry, a single runner, a receipt store, a recorder persistence boundary, and shared editor components. Those abstractions directly enable visible user outcomes.

#### Dependency modernization needs a controlled lane

The project uses older build/UI/data dependencies, including AGP 8.2.1, Kotlin 1.9.22, an October 2023 Compose BOM, Glance 1.0.0, Room 2.6.1, and `security-crypto` 1.1.0-alpha06. AndroidX has since published newer stable versions, including newer [Glance](https://developer.android.com/jetpack/androidx/releases/glance), [Room](https://developer.android.com/jetpack/androidx/releases/room), and [Security](https://developer.android.com/jetpack/androidx/releases/security) releases; the Security Crypto APIs are now deprecated in favor of platform APIs/direct Keystore use ([Security release notes](https://developer.android.com/jetpack/androidx/releases/security)).

Do not blindly upgrade everything in the feature branch. Establish behavior tests first, move versions into a catalog, upgrade in small batches, and migrate secret storage to a maintained Android Keystore design with rotation and recovery tests. Follow the [AGP compatibility/release guidance](https://developer.android.com/build/releases/about-agp) rather than treating “latest” as a goal by itself.

## Target product contract

Every shortcut should have the following understandable lifecycle:

```text
Create/record
    → review and edit
    → preflight capabilities
    → run with visible progress and cancellation
    → receive a redacted step-by-step receipt
    → repair/retry or keep a successful version
    → organize, export, and restore it
    → optionally attach approved triggers and conditions
```

The technical dependency spine is:

```text
PR CI and behavior tests
    └─ versioned typed action model + stable step IDs
         ├─ shared editor and validation
         ├─ safe import/export
         ├─ conditions/variables
         └─ stable run receipts

single ShortcutRunner + capability registry
    ├─ consistent dashboard/widget/editor execution
    ├─ preflight, progress, cancellation, and retry policy
    ├─ run receipts and repair
    └─ future schedule/device/NFC triggers
```

Items on the left are prerequisites, not optional cleanup.

## Sequenced roadmap

### Phase 0 — Close the external-testing blockers

**Intent:** Make the current feature set internally coherent before recruiting testers. Do not add new trigger categories in this phase.

| Priority | Deliverable | Why it matters to the user | Primary files/areas | Size | Dependencies |
|---|---|---|---|---|---|
| **P0.1** | **Finish and unify editing.** Repair AI Back navigation, editable naming, resolved-step edit/delete/reorder, result presentation, and the literal `$promptText` name. Replace overlapping draft/review cards with a shared step editor. Make manual and recorder lists scrollable; allow recorder step insertion; expose accessible move up/down as the baseline reorder mechanism. | A shortcut the user cannot correct is disposable. The current AI review visually promises controls that do nothing, and long recorded/manual workflows can exceed the screen. | `ui/AiBuilderScreen.kt`, `ui/ManualBuilderScreen.kt`, recorder review UI, `viewmodel/AiBuilderViewModel.kt`, manual/recorder view-models, shared action-card components | **L** | Add editor state tests in P0.6 as work lands. Typed payload migration in Phase 1 should preserve this shared UI boundary. |
| **P0.2** | **Make saves transactional from the user’s perspective.** Await insert/update, show saving state, report persistence failures without clearing the draft, and navigate only after success. Cover process recreation during save. | “Saved” must mean durable. Losing a recording after navigation is one of the highest-cost failures because the original interaction may be difficult to reproduce. | `viewmodel/AutomationViewModel.kt`, `viewmodel/RecorderViewModel.kt`, `viewmodel/AiBuilderViewModel.kt`, repository/DAO, all builder screens | **M** | Can proceed with P0.1; receipt/error presentation conventions should be reused. |
| **P0.3** | **Create one execution gateway.** Introduce a `ShortcutRunner`/`RunRequest` contract used by dashboard, widget, editor, and later triggers. Apply the same active-state, one-run-at-a-time, permission, foreground-service, and error semantics everywhere. Expose active/inactive controls in the dashboard. Replace blocking lock behavior with an explicit reject-or-queue result. | The same shortcut should not behave differently depending on where it was launched. Users need to know whether a run started, queued, or was rejected. | `service/ActionExecutorService.kt`, `service/AutomationExecutionService.kt`, dashboard UI/view-model, widget callbacks/workers, editor test actions | **L** | **Prerequisite for P0.4, Phase 1 receipts, and every future trigger.** |
| **P0.4** | **Make validation and real execution honest.** Rename current “Test run” to “Run now.” Add a non-mutating preflight that checks action schema, required fields, app availability, required settings/permissions, URL policy, and known capability constraints. Confirm potentially irreversible actions. Add cooperative cancellation and a Stop notification action. | A test must not silently send a webhook or change device state. Users need a safe way to catch predictable failures and stop a bad run. | shared runner, action handlers, foreground notification, manual/AI editors, permission/settings navigation | **L** | Depends on P0.3 and begins the capability registry needed in Phase 1. |
| **P0.5** | **Resolve dead and misleading product surfaces.** Either wire the `create_widget` route into a supported flow or remove it from the navigation graph for now. Correct Help content, legacy widget claims, “eventually automatic” language, Mark-a-tap instructions, and action catalog. Ensure permission errors open the specific settings destination they name. | Dead controls and inaccurate help teach users not to trust the rest of the UI. | `ui/Navigation.kt`, dashboard, widget builder, Help screen, `service/AutomationExecutionService.kt`, string resources | **M** | Owner decision needed on whether the custom widget AI flow is a launch feature. |
| **P0.6** | **Turn tests into a pre-merge gate.** Add PR/push CI for lint and local tests. Repair stale AI/manual Compose instrumentation tests. Add targeted Robolectric coverage for service intents, notifications, receivers, resources, and process recreation; real Room DAO and full supported migration-chain tests; MockWebServer request tests; and a small managed-device lane at API 26 and API 36 for critical navigation/editor/widget launch paths. | Current test quantity can give false confidence because the Android-facing suite is stale and not continuously run. This protects every later refactor. | Gradle test config/dependencies, `.github/workflows/`, `app/src/test/`, `app/src/androidTest/`, exported Room schemas | **L** | Start immediately and require green gates before merging P0.3 onward. Does not require running connected tests on every developer edit. |
| **P0.7** | **Supply a real product identity and launcher asset.** Decide the durable product name and application ID, add adaptive/monochrome/round launcher icons and manifest references, and remove hardcoded app labels. | Users currently have no evidenced launcher icon, and changing identity after public installation is costly or impossible. | `AndroidManifest.xml`, `res/mipmap-*`, `res/drawable*`, `res/values/strings.xml`, Gradle namespace/application ID | **M** | **Must be decided before the first Play upload.** Branding decision is an owner dependency. |

#### Phase 0 exit gate

- Every builder can create, edit, reorder, save, reopen, and run a multi-step shortcut without dead controls or off-screen steps.
- A failed save retains the draft and gives an actionable error.
- All launch surfaces enter the same runner and produce an explicit started/rejected result.
- Users can preflight, start, observe, and cancel a run; the UI no longer calls real execution a dry test.
- The app has a real launcher icon and final pre-release identity.
- PR CI is green, stale UI assertions are removed, and critical flows run on the minimum and target API managed devices.

### Phase 1 — Must-have before the first public release

**Intent:** Turn a coherent beta into a trustworthy, recoverable, accessible product. These are public-release requirements, not post-launch polish.

| Priority | Deliverable | Why it matters to the user | Primary files/areas | Size | Dependencies |
|---|---|---|---|---|---|
| **P1.1** | **Version the automation format and type action payloads.** Add an envelope with format version, stable automation/step IDs, created/modified timestamps, and typed payloads. Unknown action types must render as unsupported—not silently become WAIT. Preserve v8 data with explicit Room/JSON migrations and fixtures. | Safe upgrades, repair, export, and richer actions all require the app to know exactly what each step is. Silent reinterpretation can turn an incompatibility into unexpected behavior. | `data/Automation.kt`, converters, Room entities/migrations/schema, action validation, repositories, test fixtures | **L** | **Prerequisite for P1.2, P1.4, and most Phase 2 features.** P0.6 migration tests must be active first. |
| **P1.2** | **Persist redacted run receipts and add a repair surface.** Store bounded run metadata and per-step outcome: source, timing, status, failure category, and safe diagnostic summary. Add a run-detail screen, notification deep link, “edit failed step,” and guarded “retry from here.” Never persist typed text, auth tokens, message contents, full accessibility-node content, or HTTP secrets. | Users can see what happened and fix the failing step instead of re-recording the entire shortcut. A bounded local history also makes support possible without invasive telemetry. | new receipt entity/DAO/repository, runner callbacks, notifications/deep links, run-history/detail UI, redaction utilities | **L** | Depends on P0.3 and P1.1. Retry classification must distinguish idempotent checks from side-effecting actions. |
| **P1.3** | **Add first-run and just-in-time onboarding.** Build a short path from empty dashboard to first success: choose Record or Build, understand disclosure, enable only the needed capability, record a small example, review, and run. Teach Mark-a-tap before and during recording, including the second underlying tap. Add a capability/status screen and contextual recovery links. | The first successful shortcut is currently left to discovery. A sensitive service needs unusually clear purpose, control, and troubleshooting. | navigation, dashboard empty state, recorder screens/overlay, Help, settings/capability status, sample data that the user explicitly chooses to keep | **L** | Reuse P0.4 capability registry and P1.2 error taxonomy. Sample must never run without confirmation. |
| **P1.4** | **Give users export, restore, and safe sharing.** Define a documented, versioned bundle. Export user-selected shortcuts; omit secrets by default and clearly label required permissions/apps. Import through preview/validation with ID-collision handling, unsupported-step warnings, and transactional rollback. Treat private backup and public sharing as distinct modes. | The database is intentionally excluded from Android backup. Users need ownership of work that may take hours to record and tune. | typed serialization, Storage Access Framework UI, repository transaction, import validator, redaction/secret store, migration tests | **L** | Depends on P1.1. Do not include HTTP tokens or typed secrets unless separately encrypted and explicitly selected. |
| **P1.5** | **Make the app’s own UI accessible.** Enforce 48 dp targets; provide role, selected state, labels, headings, and live-region semantics; fix all small-text contrast failures; make every screen usable at 200% font; preserve accessible move controls alongside any drag gesture. Test with TalkBack and Switch Access and enable automated Compose accessibility checks. | The product cannot credibly request accessibility access while excluding users who rely on assistive technology. | all Compose screens/components, widget palette, themes, XML layouts, Compose UI tests | **L** | Coordinate with P1.6 so accessible labels are externalized once. Compose dependency work may be needed for automated checks. |
| **P1.6** | **Make all user-facing language resource-driven.** Move screen copy, content descriptions, action names, errors, notifications, Toasts, XML text, and the app label into resources with formatted/plural variants. Add pseudo-locale and RTL CI checks; ship translations only after English copy stabilizes. | This prevents inaccessible concatenation and layout breakage, makes error language consistent, and turns localization into translation work rather than a future rewrite. | `res/values/strings.xml`, plural resources, every UI/service/widget text site, manifest/XML | **L** | Coordinate with P1.3/P1.5; complete before generating final screenshots. |
| **P1.7** | **Move recording work off the accessibility hot path.** Keep event interpretation light, update in-memory state, and persist through an ordered debounced journal/snapshot writer. Batch text-change persistence without losing process-death safety. Consolidate replay target discovery into one bounded/deduplicated traversal and gate test-only tracing in release. | Long recordings should not make other apps stutter or increase ANR/battery risk. Replay matching should spend its budget finding the target, not revisiting the same nodes. | `service/AutomationAccessibilityService.kt`, recorder/session store, target matcher, trace utility, coroutine scopes | **L** | Preserve all recently fixed privacy/process-death guarantees. Requires performance tests in P1.8. |
| **P1.8** | **Establish performance and battery budgets.** Add Macrobenchmark coverage for cold start, dashboard, editor, and widget launch; targeted timing/allocation tests for recorder callbacks and target matching; cold/warm AI inference measurement; and a controlled long-recording battery/thermal test. Add a Baseline Profile only after measuring real startup/jank bottlenecks. | Accessibility callbacks, foreground services, widgets, and a local model are the product’s resource risks. Budgets detect regressions before users interpret them as unreliability. | new benchmark module, recorder/matcher instrumentation, model diagnostics, CI artifact reporting | **M** | Depends partly on P1.7. Use [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) and measured [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile), not guessed optimizations. |
| **P1.9** | **Add informed model download and lifecycle controls.** Before download, show current bytes, free-space requirement, source/license, network state, and a Wi-Fi-only/continue choice. Pin a model revision plus metadata version, define upgrade/rollback behavior, check device/runtime compatibility, allow inference cancellation, and surface expected latency/thermal limits. | A ~284 MB download and local inference load should never surprise the user. Controlled versions prevent upstream replacement from stranding an otherwise valid checksum. | planner/model manager, AI setup UI, settings/storage UI, model metadata asset, download/inference services/tests | **M** | Keep AI optional. Depends on final model/license product decision, but not on triggers. |
| **P1.10** | **Complete Play distribution and release operations.** Produce listing copy, 512×512 icon, feature graphic, representative screenshots, privacy/support pages, Data safety answers, accessibility and foreground-service declarations/videos, and a tester-facing disclosure narrative. Add internal/closed Play track deployment, staged rollout/rollback, mapping artifacts, changelog-to-version procedure, and a migration smoke test. | Passing a local build is not the same as surviving review or safely updating installed users. The listing must make the sensitive capability and local-data promise understandable before install. | release workflow, Play metadata/assets, manifest, changelog/versioning, privacy/support site, release runbook | **L** | Depends on P0.7 and stable P1.3/P1.5/P1.6 UI. Conditional closed-test requirement depends on Play account status. |
| **P1.11** | **Modernize dependencies and secret storage in controlled batches.** Add a version catalog and update automation, then upgrade build tooling, Compose, Room, Glance, lifecycle/navigation, and networking with behavior tests per batch. Replace deprecated Security Crypto use with a direct Android Keystore design, including key loss/rotation/recovery tests. | A frozen 2023 stack increases future API and security migration risk. Secret-storage migration must not lock users out or expose webhook credentials. | root/app Gradle files, version catalog, CI dependency updates, secret store/repository, migration tests | **L** | P0.6 tests first. May be split across Phase 1; the Keystore migration and versions required by testing/API compatibility block release, cosmetic upgrades do not. |

#### Phase 1 public-release gate

- No silent failure: every completed or failed run creates a locally visible, redacted receipt and any notification opens the relevant detail/recovery destination.
- Import/export round-trips all supported actions; corrupted, newer, and secret-bearing bundles fail safely without partial writes.
- Every critical screen passes TalkBack and Switch Access review, 200% font review, small-text contrast checks, and automated accessibility checks with zero serious findings.
- Recorder callbacks perform no synchronous full-session disk commit; measured long-recording and target-matching budgets are recorded as regression baselines.
- The model cannot download without an informed size/network decision and has a pinned, attributable version.
- Fresh install, upgrade from every supported schema version, and restore/import flows pass on API 26 and API 36.
- Store assets, privacy page, Data safety, AccessibilityService declaration, foreground-service declaration/video, release notes, staged rollout, and rollback steps are complete.

### Phase 2 — Build the expected automation baseline

**Intent:** Add the capabilities users reasonably expect after the recorder-first product is stable. Each trigger should use the same runner, capability registry, receipt system, and activation controls.

| Priority | Deliverable | Why it matters to the user | Primary files/areas | Size | Dependencies |
|---|---|---|---|---|---|
| **P2.1** | **Organization and fast reuse.** Add real created/modified/last-run timestamps, deterministic sorting, search, duplicate, favorites, and tags or folders. Replace the current fake “Recent” behavior with a timestamp-backed view. | Once users have more than a handful of shortcuts, finding and adapting one is more valuable than creating from scratch. | Automation schema/DAO, dashboard/view-model, duplicate transaction, search index/query, import/export | **M** | P1.1 and P1.2. Choose tags or folders first; do not ship both without research. |
| **P2.2** | **Trigger framework plus time/day schedules.** Add a typed trigger entity, per-trigger enablement and capability state, boot/package-update reconciliation, time-zone/DST handling, missed-run policy, and run receipts that identify the trigger source. Use inexact alarms or WorkManager by default; request exact-alarm access only for an explicitly exact use case. | “Every weekday at 8” is the clearest category expectation and can be added without continuous sensitive observation. Users need predictable DST, reboot, and missed-run behavior. | new trigger data/repository, scheduler/receivers, runner, dashboard/editor trigger UI, manifest, tests | **L** | P0.3, P1.1, P1.2. **Prerequisite for all later runtime triggers.** |
| **P2.3** | **Low-risk device-event triggers.** Add charging/power, headset, and explicitly selected Bluetooth or network-state events one at a time. Show whether each condition is currently observable and why a permission is needed. Coalesce noisy broadcasts and prevent trigger loops. | These unlock useful hands-free routines without immediately adding location or notification surveillance. | trigger registry, broadcast receivers/connectivity integration, capability UI, runner loop protection, battery tests | **L** | P2.2 plus policy/API review for each trigger. Wi-Fi SSID visibility may require location-related permissions on some versions; do not promise it universally. |
| **P2.4** | **Guard conditions, then if/else.** Start with run guards such as time window, charging, screen state, network class, and “target app installed.” Then add typed if/else blocks and explicit step-output variables. Render the execution path in receipts. | Guards avoid predictable failures; branches make shortcuts adaptable without re-recording multiple variants. | typed action/flow model, expression evaluator, editor tree UI, runner context, receipts/tests | **L** | P1.1 and P1.2. Guards should ship before general branching. |
| **P2.5** | **Variables and bounded repetition.** Add user inputs, constants, step outputs, templates/interpolation, and bounded loops with a clear maximum-iteration/time budget. Sensitive variables must be typed and redacted. | Users can reuse one shortcut for different recipients, text, URLs, or counts without cloning it. Bounded loops enable repetition without creating runaway automation. | runtime context, typed value/secret system, editor/value pickers, HTTP outputs, loop executor, validation | **L** | P2.4. Never permit unbounded loops on the accessibility service. |
| **P2.6** | **Complete the semantic action catalog already implied by the code.** Expose HTTP headers/body/auth and response outputs; deep links and safe custom intents; manual scroll/swipe/long-press; wait-until target; contact picker; and clear platform/version limitations for toggles. Prefer semantic actions over coordinates when available. | Users can replace brittle recorded steps with explicit, portable operations and use the runtime capability that already partly exists. | action definitions/registry, executor handlers, editor fields/validation, intent/HTTP/contact integrations, Help | **L** | P1.1, capability registry, P2.5 for response outputs. Ship in small independently tested batches. |
| **P2.7** | **Advanced editing.** Support insert at any position, copy step/range, multi-select, accessible reorder, section collapse, recording replacement for one failed step, and undo/redo. Add a shortcut-level validation summary. | Repairing a 30-step workflow should take seconds, not require destructive re-recording or dozens of move taps. | shared editor state/reducer, recorder handoff, UI components, validation, state-restoration tests | **L** | Shared editor from P0.1 and stable IDs from P1.1. Drag-and-drop is optional; keyboard/TalkBack-accessible controls are required. |
| **P2.8** | **Faster explicit launch surfaces.** Add Quick Settings tile and Android app shortcuts; evaluate a share-target input flow for text/URLs. Keep widgets as a supported first-class surface and refresh only affected widget IDs. | These provide much of the convenience of automatic triggers while preserving explicit user initiation and a clear policy story. | tile service, shortcuts XML/API, share intent/activity, runner, widget repository/receivers | **M** | P0.3, P1.2. Share target depends on P2.5 input variables. |

#### Phase 2 gate

- Scheduled runs survive reboot, time-zone changes, and DST without duplicate execution; missed-run behavior is user-visible and tested.
- Triggered and manual runs have identical receipts, cancellation, and active-state semantics.
- Conditions and variables are typed, validated before run, redacted in receipts, and safe under export/import.
- The dashboard remains responsive and discoverable with a large synthetic library, and “Recent” has a documented definition.

### Phase 3 — Differentiate without weakening trust

**Intent:** Make Shortcuts uniquely good at building and repairing deterministic cross-app routines.

| Priority | Deliverable | Why it matters to the user | Primary files/areas | Size | Dependencies |
|---|---|---|---|---|---|
| **P3.1** | **Guided repair assistant.** When a target fails, explain whether the app changed, the element was absent, the screen differed, or permission was missing. Offer safe alternatives: choose another visible target, re-record only that step, switch to Mark-a-tap, add wait-until, or select a semantic action. Preserve the prior step as rollback. | Accessibility UI automation will always encounter app updates and timing differences. Making breakage repairable is a stronger differentiator than pretending it will never happen. | matcher diagnostics, run receipts, editor/recorder handoff, target-preview overlay, version metadata | **L** | P1.2, P2.6, P2.7. Must avoid storing sensitive screen content. |
| **P3.2** | **Local-language editing and explanation.** Let the on-device model propose edits such as “insert a two-second wait after opening Messages” or explain what a shortcut will do. Compile proposals into typed actions, show a diff, require review, validate deterministically, and never let the model choose new runtime actions autonomously. | This uses the model where it adds clarity and speed while keeping the user in control and preserving the Play-policy boundary. | planner grammar/schema, typed action compiler, diff/review UI, validation, model evaluation corpus | **L** | P1.1, shared editor, P1.9, P2.4–P2.6. |
| **P3.3** | **Curated local templates and safe sharing.** Ship a small reviewed template library and optionally accept shared bundles with capability/side-effect manifests, signature/version metadata, and prominent untrusted-source review. | Templates reduce first-value time and show what the product can do without requiring a risky open marketplace. | template assets/catalog, import validator, onboarding/dashboard, safety manifest, update mechanism | **M** | P1.4 and stable action/trigger versions. An open community gallery is a later product requiring moderation and abuse handling. |
| **P3.4** | **Optional encrypted sync.** If research shows multi-device demand, add end-to-end encrypted sync for user-selected shortcuts and receipts, explicit device/key recovery, conflict resolution, and deletion controls. Keep local-only mode fully functional. | Sync is a credible recurring-value feature, but it introduces account, privacy, support, and operating cost that the current local-first product avoids. | account/sync service, crypto/key recovery, repositories, Data safety/privacy docs, billing/entitlements | **L** | P1.1, P1.4, proven demand. This is the earliest point where subscription pricing becomes defensible. |
| **P3.5** | **Broaden sensitive triggers selectively.** Evaluate NFC, notification-content, location/geofence, and foreground-app triggers separately using opt-in demand, battery measurement, data minimization, and a new Play-policy review. | Some users will value these highly, but each expands observation scope and review risk. Shipping them as one generic “automation permissions” bundle would undermine trust. | trigger framework, NFC/notification/location/accessibility integrations, permission education, policies/tests | **L each** | P2.2 and product evidence. Notification listening uses a distinct sensitive service ([NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService.html)); location requires its own Play justification. |

## Explicit deferrals and non-goals

These should not be pulled into the pre-release scope unless new evidence changes the tradeoff:

- **Autonomous AI execution.** The model may author, edit, or explain a deterministic script that the user reviews; it should not decide new actions during a run. This protects predictability and the AccessibilityService policy boundary.
- **An open community marketplace.** Import/export and curated templates should prove the format and safety review first. Public submissions require moderation, abuse reporting, malicious webhook/intent controls, and compatibility policy.
- **Always-on app-launch, notification-content, or location observation in v1.** These broaden sensitive access and battery burden. Add only after demand and policy review.
- **Inbound cloud webhooks or remote execution.** They require accounts, authentication, reachability infrastructure, abuse protection, and a revised local-only privacy promise. Outbound HTTP actions do not imply this service.
- **Exact alarms by default.** Use inexact scheduling unless the user chooses an experience whose core value genuinely depends on exact timing.
- **Unbounded loops, arbitrary scripting, shell/root actions, or hidden background behavior.** They magnify runaway execution, security, and support risks.
- **Hundreds of integrations before product fit.** The recorder and semantic action repair path should be validated before chasing catalog size.
- **Ads or monetizing safety.** Do not paywall cancellation, receipts, recovery, accessibility, onboarding, or user-owned backup. Avoid ad SDKs in a product with sensitive local automation context.

## Cross-cutting engineering standards

### Execution safety classes

Every action definition should declare properties used by preflight, retry, UI, and receipts:

- Required Android capabilities and version limits.
- Whether it is deterministic, reversible, and idempotent.
- Whether it can expose or consume sensitive data.
- Whether retry is safe, conditionally safe with an idempotency key, or forbidden.
- Whether preflight can validate it fully, partially, or only structurally.
- Its timeout and cancellation behavior.

For example, a WAIT is safe to retry; opening an app is generally safe; a GET may be safe subject to endpoint semantics; a POST, message, dial, or toggle must not be blindly retried. The UI should never offer a generic retry button without this classification.

### Receipt privacy

Receipts should answer “what failed and what can I do?” without becoming a surveillance log. Persist action type, step ID, timing, package identifier where needed, normalized failure category, and redacted diagnostics. Do not persist typed text, message bodies, phone numbers, auth headers/tokens, full URLs with sensitive query data, accessibility text content, or screenshots by default. Diagnostic export must show a preview and require explicit selection.

### Schema and migration policy

- Maintain a versioned automation document format independently from the Room database version.
- Use stable IDs for actions, conditions, triggers, and variables.
- Reject unsupported required features explicitly and preserve unknown payloads only when safe for round-trip; never substitute a different executable action.
- Keep fixture bundles for every released format and migration tests from every publicly supported version.
- Make import and database migration transactional with rollback on validation failure.

### Observability without surveillance

Start with on-device receipts, benchmark artifacts, and an explicit user-exported diagnostic bundle. If crash or product analytics is later introduced, make it minimal, redacted, documented in Data safety, and opt-in where appropriate. Useful launch metrics can initially be gathered from a consented beta panel: first-shortcut completion, successful-run rate, top normalized failure categories, repair success, model download completion, and retention by explicit launch surface. Never collect recorded text or screen content.

## Release and validation plan

### Internal alpha

- Exercise at least one long workflow and failure case through manual, recorder, AI, dashboard, and widget entry points.
- Use representative classic View, Compose, and WebView target apps; do not claim general compatibility from one app family.
- Verify configuration changes and process recreation during editing, saving, recording, and running.
- Verify foreground notification, Stop, concurrent-run rejection, settings deep links, and inactive state on API 26 and API 36.
- Run TalkBack, Switch Access, 200% font, light/dark theme, and smallest supported screen checks.

### Closed beta

- Publish through a Play closed track with a staged cohort and a rollback procedure.
- Give testers a visible feedback/export-diagnostics path and a precise explanation of what the app records and stores.
- Track normalized success/failure categories locally or through consented aggregate telemetry; review every silent/unknown category as a release defect.
- Test model download on constrained storage, metered network, interruption/resume, checksum mismatch, device incompatibility, and delete/redownload.
- Decide legacy widget support based on actual shipped installs, not code comments.

### Public rollout

- Promote in stages after migration, crash/ANR, run success, cancellation, accessibility, and support findings meet an explicitly recorded threshold.
- Keep the first listing narrowly accurate: manual/recorded local automation plus widgets and listed semantic actions. Do not advertise schedules, automatic initiation, or universal third-party app reliability before they ship.
- Preserve a rollback-capable prior artifact and a database/document migration recovery path.
- Review AccessibilityService, Data safety, FGS, privacy, model attribution/license, and store copy on every material feature expansion.

## Monetization sequence

1. **Closed beta: free, no ads, no account.** Measure whether users create a second shortcut, reuse shortcuts, repair failures, and keep the model installed.
2. **Public v1: free core.** Include manual/record/AI proposal, dependable execution, cancellation, receipts, basic history, accessible UI, and import/export.
3. **Validate Pro packaging before implementation.** Candidate one-time Pro features: advanced triggers, conditions/variables, expanded limits, and advanced widget customization. Avoid crippling the free recorder or charging for trust/safety features.
4. **Consider subscription only with recurring service value.** Encrypted multi-device sync or a maintained hosted catalog could justify it; local execution alone does not create an obvious recurring cost/value story.
5. **Implement billing only after the product decision.** Include purchase restore, offline entitlement behavior, pending/grace/refund handling, and tests; keep entitlement checks out of time-sensitive accessibility callbacks.

## Decisions the owner must make

| Decision | Why it cannot be inferred from the repository | Latest responsible point |
|---|---|---|
| Final product name, visual identity, and application ID | “Shortcuts” is generic; brand/domain/trademark and prior Play registration are external facts. | Before Phase P0.7 and first Play upload |
| Whether any legacy widget provider has public installed users | Code labels five providers legacy, but repository history alone does not establish installed-base obligations. | Before removing or hiding legacy receivers in Phase 1 |
| Whether the custom widget AI builder is a supported product surface | A route and implementation exist, but normal navigation appears not to reach it. | P0.5 |
| Minimum supported automation/document version | Database schemas begin at version 3, while production upgrade commitments are not stated. | P0.6/P1.1 |
| Model redistribution/download license and supported-device promise | The remote repository identifies Gemma licensing, but product counsel/attribution and device support are external decisions. | P1.9 and store submission |
| Play account closed-testing obligation | It depends on account type/creation date and Play Console state. | Before P1.10 closed/public track planning |
| Free/Pro limits and willingness to pay | No retention, cohort, or pricing research is present. | After closed beta; before billing work |

## Risks to track explicitly

| Risk | Consequence | Mitigation |
|---|---|---|
| Third-party UI changes make recorded selectors stale | Runs fail after another app updates | Stable receipts, semantic actions, wait-until, step re-recording, curated compatibility tests, repair assistant |
| AI feature is perceived as autonomous AccessibilityService control | User distrust or Play rejection | Static typed proposal, visible diff/review, deterministic runtime, accurate listing/disclosure |
| Trigger breadth expands sensitive background observation | Policy rejection, battery drain, confusing permissions | Add triggers individually, just-in-time permissions, measured battery cost, explicit capability status, policy review |
| Import/share bundles contain secrets or malicious side effects | Credential exposure or surprising execution | Secret omission by default, preview, side-effect manifest, unsupported-step block, transactional import, no auto-run |
| Large model download/inference performs poorly on low-end devices | Failed setup, storage pressure, thermal/battery complaints | Informed download, compatibility check, cancel/delete, cold/warm benchmarks, optional feature |
| Schema modernization corrupts existing shortcuts | Loss of high-investment user work | Fixtures for every released format, transactional migration, export before risky upgrade, rollback tests |
| Dependency upgrade combines too many behavioral changes | Hard-to-diagnose regressions | Version catalog, small upgrade batches, PR CI, release notes, behavior tests before upgrade |
| Generic branding or application ID is changed after launch | Store confusion or forced new listing | Finalize identity before first public upload |

## Recommended first implementation slice

The first slice should be small enough to validate the architecture but useful enough to change the product:

1. Add PR CI and repair the stale Compose tests.
2. Create the single `ShortcutRunner` interface and route dashboard and widget launches through it without changing action behavior.
3. Return a structured started/rejected/completed result and make active-state/concurrency semantics identical.
4. Fix the permission notification’s settings destination and add a Stop action.
5. Route editor execution through the same interface and rename “Test run” to “Run now.”
6. Repair AI review controls and awaited saves.

This slice proves the execution seam needed for receipts, preflight, triggers, and repair while immediately removing inconsistent behavior. It should land before typed schema work so the schema migration has a stable consumer boundary.

## Source notes

### Repository evidence anchors

- `changelog.md` — completed `[Unreleased]` audit work excluded from this roadmap.
- `app/src/main/java/com/shortcuts/app/data/Automation.kt` — automation metadata, nullable action payload, trigger provenance.
- `app/src/main/java/com/shortcuts/app/data/AutomationDao.kt` — unordered automation query.
- `app/src/main/java/com/shortcuts/app/service/ActionExecutorService.kt` — synchronous linear execution, lock, result handling.
- `app/src/main/java/com/shortcuts/app/service/AutomationExecutionService.kt` — widget foreground execution and notification recovery path.
- `app/src/main/java/com/shortcuts/app/service/AutomationAccessibilityService.kt` and recorder/session files — event handling, persistence, replay matching.
- `app/src/main/java/com/shortcuts/app/ui/AiBuilderScreen.kt`, `ManualBuilderScreen.kt`, recorder UI, and `Navigation.kt` — editor and navigation findings.
- `app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt` and related view-models — save and editing state.
- `app/src/main/res/xml/full_backup_content.xml` and `data_extraction_rules.xml` — excluded automation database.
- `app/src/main/AndroidManifest.xml` and `app/src/main/res/` — services, receivers, launcher assets, resources, localization.
- `app/src/test/`, `app/src/androidTest/`, and `.github/workflows/release.yml` — test and delivery maturity.

### External reference set

- [Google Play Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Google Play Data safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
- [Google Play foreground-service declarations](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Android accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing)
- [Android Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Android alarms and exact-alarm guidance](https://developer.android.com/develop/background-work/services/alarms)
- [MacroDroid product documentation](https://macrodroid.com/helper/)
- [Automate product documentation](https://llamalab.com/automate/)
- [Tasker user guide](https://tasker.joaoapps.com/userguide_summary.html)

