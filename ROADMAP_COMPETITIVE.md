# Shortcuts competitive analysis and implementation roadmap

**Research date:** 2026-09-06  
**Scope:** shipping product and repository state on this date; Android competitors plus Apple Shortcuts as a UX benchmark  
**Repository reviewed:** `AGENTS.md`, `changelog.md`, every file in `okf-docs/`, `app/src/main/AndroidManifest.xml`, and the Kotlin source under `app/src/main/java/com/shortcuts/app/`

## Executive conclusion

Shortcuts is not yet a general-purpose automation engine. It is a **user-invoked, local shortcut runner with an unusually direct way to create cross-app UI macros**: record normal use, review the captured steps, and replay them later. The database field named `triggerType` records how a shortcut was authored; it is not an executable trigger. Today, only the dashboard and home-screen widgets start saved shortcuts.

That narrower product can be defensible. Tasker, MacroDroid, and Automate are dramatically broader, but their breadth makes them harder to learn. Shortcuts' best opportunity is not to reproduce their catalog. It is to own this promise:

> **Show it once. Save it as a shortcut. Run it locally.**

The recorder is a real workflow advantage, not a unique technical capability. MacroDroid can identify a UI control after the user taps a notification, Automate can generate an XPath through “Record interactions,” and AutoInput Actions v2 has a guided helper that builds several UI operations. However, the official material reviewed for those products describes configuring or generating UI actions one target at a time. This repository continuously captures accessibility click, text, scroll, and app-transition events during a normal cross-app session, then turns them into an editable sequence. That is materially less labor for the use cases where it works.

The strategic catch is reliability. UI replay is inherently weaker than a supported API or app intent. Shortcuts should first make recorded runs observable, diagnosable, and resilient; then add a small, policy-safe set of triggers and conditions. It should not race Tasker on action count or Zapier on cloud integrations.

## Evidence and confidence rules

- **Verified — repository:** directly established in the source reviewed on 2026-09-06.
- **Verified — official:** established by a vendor's current product page, documentation, help centre, developer documentation, or store listing.
- **Community:** useful context from a vendor-operated community, but not treated as a contractual product claim.
- **Inference:** a conclusion from the available evidence, explicitly labelled; it is not claimed as a vendor statement.
- Store prices are regional and mutable. Numeric prices below are the USD values visible to this research on 2026-09-06; “one-time purchase” is used without a number where the vendor does not publish a stable web price.

No competitor app was installed or exercised. Therefore, absence means **not found in the cited official material**, not proof that no build, plug-in, OEM variant, or hidden feature can do it.

Except where a claim is explicitly described as community evidence, citations point to first-party vendor documentation, support/help pages, developer documentation, or vendor-controlled store listings. MacroDroid's detailed documentation is hosted as a wiki on its vendor-operated forum; it is used here as product documentation, while user template content is treated as community evidence. Automate community pages and IFTTT user Applets establish that sharing/discovery exists, not that every uploaded workflow is supported or safe.

## 1. What Shortcuts actually does today

### Verified product inventory

| Capability | Repository finding | Correction or qualification |
|---|---|---|
| Record interaction sequences | `service/AutomationRecorder.kt` consumes window-state, clicked, text-change/selection, and scroll accessibility events. `service/RecorderSession.kt` collapses repeated text/scroll events, deduplicates clicks, and converts launcher transitions into app-launch steps. | **Verified.** This is event recording, not raw touch recording. Custom-drawn controls that emit no click event are invisible to the normal recorder. Password/sensitive fields are deliberately filtered. |
| Replay taps, text, scrolls, and app launches | `service/AutomationAccessibilityService.kt` resolves by view ID, text, content description, class/proximity and traversal, then uses a guarded coordinate fallback. | **Verified.** Coordinate replay is refused when the foreground package, full-screen geometry, rotation, scaling, or bounds are unsafe. That is good safety behaviour, but means some recorded macros will fail after layout/device changes. |
| “Mark a tap” | `service/TapMarkOverlay.kt` displays a full-screen accessibility overlay, captures one coordinate plus best-effort node metadata, saves the step, and dismisses. | **Verified, with an important correction.** It is a manual one-tap capture helper. It does **not** pass that same tap through to the underlying app. When no package/node metadata can be recovered, the resulting coordinate-only step can later be rejected by the replay safety checks. |
| Triggering | Dashboard cards execute immediately; `widget/` provides an adaptive Glance widget with Auto, Single, Grid, and List layouts, while legacy widget providers remain for compatibility. | **Verified.** There is no time, event, notification, location, connectivity, voice, webhook-inbound, or app-open trigger. `Automation.triggerType` is authoring provenance (`MANUAL`, `AI_GENERATED`, etc.), not trigger logic. |
| System controls | `ActionExecutorService.kt` supports flashlight, Do Not Disturb, volume, ring mode, auto-rotate, Wi-Fi, Bluetooth, airplane mode, and location. | The prompt understated the catalog but overstated direct control. Flashlight, audio/ringer, DND, and rotation can be direct subject to permission/device state. Modern Android forbids ordinary apps from directly toggling Wi-Fi and Bluetooth, so Shortcuts opens a system panel/settings or asks for confirmation; airplane mode and location also open settings. Android documents the [Wi-Fi restriction](https://developer.android.com/reference/android/net/wifi/WifiManager) and [Bluetooth restriction](https://developer.android.com/about/versions/13/behavior-changes-13). |
| HTTP/webhooks | Outbound GET/POST/PUT/DELETE, optional headers/body, per-action cleartext opt-in, and an encrypted bearer-token reference are implemented in `ActionExecutorService.kt`. HTTPS is the default. | **Verified.** It sends web requests; it does not receive webhooks. The runtime currently returns the HTTP status, not a response body that later steps can consume. Some older wording in repository documentation/changelog implying a surfaced truncated body is stale relative to the implementation. |
| Open apps | Launches an installed, launchable package. | **Verified.** It is not a general Android intent/broadcast editor and cannot call arbitrary app-defined operations. |
| SMS and phone | Uses `ACTION_SENDTO` to open an SMS composer with content and `ACTION_DIAL` to open the dialer. | **Verified.** It does not silently send a message or place a call. This is an appropriate safety and Play-policy boundary, not an incomplete implementation. Google Play heavily restricts [SMS and Call Log permissions](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en-GB). |
| Wait | A deterministic delay from 1 ms to 10 minutes is supported. | **Verified.** This is a fixed sleep, not “wait until this screen/control/state exists.” |
| On-device natural-language builder | `OnDeviceInferenceService.kt`, `planner/`, and `AiBuilderViewModel.kt` use a downloaded, SHA-256-verified FunctionGemma mobile-actions model through MediaPipe on CPU. The model file is roughly 271 MB and the download gate asks for about 285 MB free space. | **Verified and differentiated on privacy.** Its callable vocabulary is narrower than the manual runtime: six named system settings, app open, HTTP GET/POST, tap visible text, and type text. It does not currently expose all manual actions or all runtime HTTP options. It generates a draft for review; it should stay that way for Play-policy safety. |
| Builder/review UX | Manual, AI, and recorder flows all lead to editable/reorderable step cards; shortcuts have names, colours, and icons; runs can be tested before save. | **Verified.** There are no conditions, variables, loops, subroutines, trigger editor, import/export, templates, cloud sync, community, or run history. |

### Current architecture boundary

The persistent model is one Room row with metadata plus `actionsJson` (`data/Automation.kt`). The seven action types are `SYSTEM_TOGGLE`, `APP_INTENT`, `HTTP_REQUEST`, `UI_AUTOMATION`, `WAIT`, `SEND_MESSAGE`, and `DIAL_NUMBER`. Execution is a linear list in `service/ActionExecutorService.kt`; `continueOnError` is the only flow-control-like flag.

This explains most gaps below. Adding a trigger screen alone would not make Shortcuts an automation engine: it needs typed trigger/condition data, a run context, outputs, persistence, and a single execution entry point first.

One concrete inconsistency should be treated as a release blocker for later automation: `DashboardScreen.kt` invokes `ActionExecutorService` directly, while widget execution goes through `AutomationExecutionService`, which checks `isActive` and guards concurrent runs. As a result, “disabled” and concurrency semantics vary by launch surface.

## 2. Competitor baselines

These are the capabilities users are likely to bring as expectations, not a recommendation to copy every product.

### Tasker plus AutoInput

Tasker is the power-user ceiling: the current Play listing advertises [130+ events/states and 350+ actions](https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm&hl=en-US). Profiles connect contexts such as application, time, date, location, event, gesture, or state to tasks; the official guide documents [profiles, scenes, projects, import/export, backups, and community links](https://tasker.joaoapps.com/userguide/en/activity_main.html), [variables and arrays](https://tasker.joaoapps.com/userguide/en/variables.html), and [If/Else, For, Goto, conditions, and reusable tasks](https://tasker.joaoapps.com/userguide/en/flowcontrol.html). Its [action index](https://tasker.joaoapps.com/userguide/en/help/ah_index.html) spans device, app, network, files, media, JavaScript/Java, and plug-ins.

AutoInput is the closest UI-automation analogue. Its official Play listing says it can [simulate touch/write, read on-screen text, and react to screen events](https://play.google.com/store/apps/details?id=com.joaomgcd.autoinput). [Actions v2](https://joaoapps.com/autoinput-actions-v2-single-action-total-ui-automation/) adds a helper-guided sequence of app opens and clicks in a single Tasker action, and the [official changelog](https://joaoapps.com/autoinput/changelog/) documents UI queries/updates, gestures, coordinates, repetition, and on-demand accessibility enabling.

### MacroDroid

MacroDroid packages breadth in a comparatively approachable “trigger → actions → constraints” model. Its current listing claims [120+ triggers, 200+ actions, and 80+ constraints](https://play.google.com/store/apps/details?id=com.arlosoft.macrodroid&hl=en_US), with variables, arrays/dictionaries, scripting, loops, Tasker plug-ins, webhooks, and templates. The official wiki shows its broad [trigger catalog](https://www.macrodroidforum.com/wiki/index.php/Triggers) and constraints that can apply globally or around actions using [nested AND/OR/XOR/NOT](https://macrodroidforum.com/wiki/index.php?title=Constraints).

Its accessibility-based [UI Interaction action](https://www.macrodroidforum.com/wiki/index.php/Action%3A_UI_Interaction) supports text, view ID, X/Y coordinates, gestures, and an “Identify in app” flow in which the user selects a target after tapping a notification. The same page warns that UI interaction is fragile and unavailable with the screen off. [Action Blocks](https://macrodroidforum.com/wiki/index.php/Action_Blocks) provide reusable parameterized sequences.

### Automate by LlamaLab

Automate is a visual flowchart engine. Its current listing advertises [410+ blocks](https://play.google.com/store/apps/details?id=com.llamalab.automate&hl=en_US), including device events/actions, files, messaging, network/cloud storage, UI interaction, intents/broadcasts, shell commands, plug-ins, custom widgets, and a community. Its flow model supports decision paths, loops, concurrent “fibers,” and state persisted across process/device restarts ([flow documentation](https://www.llamalab.com/automate/doc/flow.html)); its [block catalog](https://www.llamalab.com/automate/doc/block/index.html) and [variables documentation](https://www.llamalab.com/automate/doc/variable.html) show the breadth.

Most relevant to Shortcuts, Automate's [Interact block](https://llamalab.com/automate/doc/block/interact.html) includes “Record interactions” to generate an XPath for a selected UI element. LlamaLab explicitly calls UI interaction a last resort, says generated paths may require editing, and warns that changing layouts/custom graphics can break it.

### Samsung Modes and Routines

Samsung's first-party feature offers approachable “If” and “Then” setup, recommendations/categories, modes, names/icons/colours, widgets, lock-screen access, Quick Settings, and Bixby/Google Assistant invocation. Samsung's official guide notes that [availability varies by Galaxy device and software version](https://www.samsung.com/us/support/answer/ANS10002538/). Examples include time, location, and app-use conditions plus privileged actions such as Wi-Fi and brightness ([Samsung support](https://www.samsung.com/ae/support/apps-services/how-to-set-up-and-use-the-bixby-routines-feature-on-your-galaxy-phone/)).

It benefits from Samsung system privileges that a Play-distributed app cannot assume. It is therefore a benchmark for onboarding and reliable device-setting automation, not a directly reproducible action catalog.

### Google's device routines

There are two distinct Google products worth separating:

1. Pixel “Rules” are intentionally tiny: location or connected Wi-Fi can change Do Not Disturb/ring/silent/vibrate state ([Pixel help](https://support.google.com/pixelphone/answer/2819577?hl=en)). Google states that location data used for Rules stays on-device ([Pixel Settings Services help](https://support.google.com/pixelphone/answer/12328784?hl=en-GB)).
2. Google Assistant/Google Home Routines automate Assistant and smart-home operations. Official help lists manual/voice, time, sunrise/sunset, alarm dismissal, device state, and presence starters, with device, media, information, reminder, communication, volume, and custom Assistant-command actions ([Google Assistant help](https://support.google.com/assistant/answer/7672035?hl=en-GB)). The advanced Google Home script editor, documented as a Public Preview, supports multiple starters, optional conditions, actions, delays, and Boolean logic in [its automation schema](https://developers.home.google.com/automations/schema/automations).

Google explicitly calls Home Routines convenience features rather than safety/security mechanisms. That reliability boundary matters for any Shortcuts marketing.

### IFTTT

IFTTT defines mainstream cloud automation as “If This, Then That”: select a service trigger and one or more service actions. Its official site advertises [more than 1,000 brands and services](https://ifttt.com/explore), and its developer model formalizes [triggers, queries, actions, and Applets](https://ifttt.com/docs). Paid tiers add multi-action Applets, webhooks, faster execution, queries, filter code, and multiple accounts; the [plans comparison](https://help.ifttt.com/hc/en-us/articles/360053706813-IFTTT-Plans-at-a-glance) is the clearest capability boundary. Community-published Applets supply discovery and social proof ([Applets help](https://help.ifttt.com/hc/en-us/articles/37000197879323-What-are-Applets-and-how-do-I-enable-them)).

### Zapier

Zapier is the business/cloud ceiling rather than a direct mobile rival. It currently documents [over 8,000 apps](https://help.zapier.com/hc/en-us/categories/8495901804429), two-step and multi-step Zaps, schedules, webhooks, filters, paths, formatting, global variables, versioning, alerts, replay, shared connections, and admin controls on its [pricing/feature page](https://zapier.com/pricing). Built-in Formatter, Paths, Filters, Delay, Looping, Sub-Zaps, Digests, and Storage are listed in its [task accounting documentation](https://zapier.com/pricing/rates). Its large [template library](https://zapier.com/templates) sets an expectation that useful examples precede a blank editor.

### Apple Shortcuts (UX benchmark only)

Apple Shortcuts exposes hundreds of system and participating-app actions in an approachable searchable editor ([Apple's app overview](https://www.apple.com/apps/)). Personal automations can be triggered by time, arrival, app open, and other event/travel/communication/setting categories, and may run after confirmation or without asking ([Apple Support](https://support.apple.com/en-ca/guide/shortcuts/apd690170742/ios)). Setting triggers include Wi-Fi, Bluetooth, Focus, battery level, charger, NFC, app open/close, and airplane mode ([Apple Support](https://support.apple.com/en-by/guide/shortcuts/apde31e9638b/ios)).

For composition, it provides action outputs as “Magic Variables” ([variables guide](https://support.apple.com/en-lamr/guide/shortcuts/apdd02c2780c/ios)), If/Repeat/menu/list operations, web APIs/JSON, prompts, clipboard, and app actions. It also has a curated [Gallery](https://support.apple.com/en-gb/guide/shortcuts/apdd018638ca/ios), iCloud/file sharing with import questions and validation ([sharing guide](https://support.apple.com/en-au/guide/shortcuts/apdf01f8c054/ios)), and invocation through Siri, widgets, the share sheet, Control Centre, Spotlight, hardware buttons, and Watch. App integrations are structured through [App Intents](https://developer.apple.com/documentation/appintents).

Platform differences are decisive: iOS does not grant Shortcuts a general license to imitate arbitrary touches across third-party apps. Apple gets breadth and reliability by having apps expose supported actions.

## 3. Feature gap matrix

Abbreviations: **T+AI** = Tasker plus AutoInput; **MD** = MacroDroid; **AUT** = Automate; **SAM** = Samsung Modes and Routines; **GOOG** = Pixel Rules and/or Google Home Routines as specified; **IFT** = IFTTT; **ZAP** = Zapier; **APL** = Apple Shortcuts.

### Triggers and launch surfaces

| Gap in Shortcuts | Competitor expectation and evidence | Product importance | Recommendation |
|---|---|---:|---|
| Time/day and recurring schedules | T+AI profiles, MD's trigger catalog, AUT's time/calendar blocks, SAM time conditions, Google Home time/sunrise starters, IFT scheduling, ZAP schedules, and APL personal automations all support this. See [Tasker](https://tasker.joaoapps.com/), [MD triggers](https://www.macrodroidforum.com/wiki/index.php/Triggers), [AUT blocks](https://www.llamalab.com/automate/doc/block/index.html), [Google](https://support.google.com/assistant/answer/7672035?hl=en-GB), and [Apple](https://support.apple.com/en-ca/guide/shortcuts/apd690170742/ios). | Very high | Build a deliberately inexact schedule first; make “exact to the minute” an explicit special-access option only if Play review supports the use case. |
| Device-state events | T+AI, MD, AUT, SAM, and APL cover charging/battery, connectivity, screen/device state, and many hardware events. APL's current list includes Wi-Fi, Bluetooth, battery, charger, NFC, app open/close, and Focus. | High | Start with event-driven charging, battery threshold, headset, Bluetooth connection, Wi-Fi network, and screen/unlock events that do not require continuous polling. |
| App foreground/open/close | T+AI, MD, AUT, SAM, and APL support app context/open/close. MD documents an [application launched/closed trigger](https://macrodroidforum.com/wiki/index.php/Trigger%3A_Application_Launched/Closed). | High for UI macros | Add only after the AccessibilityService declaration is re-evaluated. Monitoring app transitions while idle materially expands sensitive observation. A non-accessibility implementation may trade precision for lower policy risk. |
| Notification received/cleared/action | T+AI, MD, and AUT can react to notifications; MD documents filtering by app/text in its [notification trigger](https://www.macrodroidforum.com/wiki/index.php?title=Trigger%3A_Notification). Cloud tools react to service events with structured payloads. | High | Use a separately disclosed `NotificationListenerService`, per-app allowlist, redacted previews, local matching, and no default “all apps” access. |
| Location/presence | T+AI, MD, AUT, SAM, GOOG, IFT, and APL offer location or presence triggers in their applicable domains. | Medium | Defer. Android background location is policy-heavy and delayed; offer Wi-Fi/Bluetooth presence first. |
| Inbound webhook/cloud-service event | MD supports HTTP/webhook triggers; IFT and ZAP make service/webhook triggers core; Google Home accepts supported device events. Shortcuts only sends outbound HTTP. | Medium, segment-specific | Do not pretend an Android device is a reliable public server. If built, use an optional authenticated relay plus push delivery and make it a cloud-priced feature. |
| Voice, Quick Settings, dynamic shortcut, share sheet, notification action, hardware surface | T+AI/MD/AUT expose several; SAM supports assistants, Quick Settings, lock screen, and widgets; GOOG uses voice/manual; APL supports Siri, widget, share sheet, Control Centre, Spotlight, Action button, and Watch. | High and low-risk | Add Android dynamic shortcuts, Quick Settings tile, persistent-notification run action, share target, and an Assistant/App Action integration where current Google support permits it. These preserve user initiation. |
| NFC | MD, AUT, SAM variants, and APL support NFC-triggered routines. | Medium | A good later local trigger: explicit physical intent, no ambient surveillance. Needs tag setup, duplicate-debounce, and locked-device behaviour design. |

### Conditions, data, and control flow

| Gap in Shortcuts | Competitor expectation and evidence | Product importance | Recommendation |
|---|---|---:|---|
| Trigger-level conditions | MD makes constraints a first-class third section and supports nested Boolean groups; Samsung uses multiple “If” conditions; Google Home has optional conditions and AND/OR/NOT; Tasker profiles combine contexts. | Very high once triggers exist | Add a small typed set: time window/day, device unlocked, screen on, network, battery/charging, foreground package. Keep the first editor sentence-like. |
| Per-step If/Else | Tasker has If/Else and action conditions; MD has If/Then/Else plus constraints around individual actions; AUT uses YES/NO graph branches; ZAP has Filters/Paths; APL has If. | Very high | Build structured branches after run context/outputs. Do not encode them into the existing flat `Action` fields. |
| Variables and action outputs | T+AI, MD, AUT, ZAP, and APL all pass data between steps. Apple makes every action output discoverable as a Magic Variable; Zapier maps trigger/step fields into later actions. | Very high | Provide typed values and friendly tokens before exposing arbitrary expressions. HTTP status/body, trigger data, user input, current time, and prior step result should be first. |
| Templating/transformations | Tasker variables/functions, MD expressions, AUT functions, IFT queries/filter code, ZAP Formatter/code, and APL text/date/list/dictionary operations set the expectation. | High | Begin with `${token}` insertion plus a small formatter set; avoid an unrestricted scripting runtime initially. |
| Repeat/loops | T+AI, MD, AUT, ZAP, and APL support iteration. | Medium | Defer until cancellation, maximum iteration, time budget, and accessibility safety limits exist. A loop that taps a UI can cause harm quickly. |
| Reusable subflows/action blocks | Tasker's Perform Task, MD Action Blocks, AUT flow start/subflows, and ZAP Sub-Zaps reduce duplication. | Medium | Add “Run shortcut” after cycle detection and input/output contracts exist. It has more user value than a general Goto. |
| Parallelism/concurrency | AUT fibers and cloud workflow products can coordinate parallel work. | Low for this product | Deliberately omit UI-step parallelism. A single foreground Android UI has one state; concurrent touch macros are nondeterministic. Parallelize only independent network/native work much later. |

### Action vocabulary

| Gap in Shortcuts | Competitor expectation and evidence | Product importance | Recommendation |
|---|---|---:|---|
| User feedback and prompts | Competitors offer toast/notification/dialog/input/choice actions; APL documents prompts, alerts, notifications, and choose-from-list/menu in its [user guide](https://support.apple.com/guide/shortcuts-mac/welcome/mac). | High | Add Show notification, Toast, Confirm, Ask text/number, and Choose from list. These also unlock safer human-in-the-loop flows. |
| Media, clipboard, brightness, sound, vibration, screen | T+AI, MD, and AUT have broad device action catalogs; APL includes clipboard/media/system actions; SAM is strong in privileged settings. | High | Add public-API actions in coherent packs. Clearly label actions that open a system panel versus change a value directly. |
| General intents, deep links, broadcasts, share | Tasker, MD, and AUT expose intents/plug-ins; APL relies on URL schemes and App Intents. Shortcuts can only launch a package or its own fixed compose/dial intents. | High for extensibility | Add Open URL/deep link and Share text/file first. Add advanced explicit intents later with exported-component validation and warnings; avoid arbitrary implicit broadcasts by default. |
| File/content operations | T+AI, MD, AUT, ZAP, and APL can read/write/transform content in their platform scope. | Medium | Start with Storage Access Framework user-selected files and share-sheet input; do not request broad storage. |
| HTTP response data and robust API actions | T+AI, MD, AUT, IFT, ZAP, and APL can use outputs from web/API steps. Shortcuts has good request safety but discards the body as workflow data. | High | Surface status, selected headers, bounded body, parsed JSON keys, timeout, and retry policy into the run context. Keep secrets out of export/logs. |
| Calendar, reminders, contacts, messaging, email | All broad engines expose some mix; cloud products and APL gain especially rich app-supported actions. | Medium | Prefer user-visible intents and provider contracts. Add permissions only for concrete, high-demand recipes. Continue to require user confirmation for messages/calls. |
| Scripts/shell/root | Tasker, MD, and AUT support advanced scripting/shell options; ZAP supports code; APL supports web JavaScript. | Low/negative | Do not chase shell/root. A sandboxed expression/formatter language may eventually be justified; arbitrary shell is off-position, difficult to secure, and hostile to Play distribution. |
| Smart-home/cloud app actions | IFT (1,000+ services), ZAP (8,000+ apps), Google Home, APL App Intents, and Samsung's ecosystem make branded integrations feel normal. | Medium | Use HTTPS/webhooks, deep links, and plug-in interoperability as force multipliers. A bespoke OAuth connector catalog is a different company and cost structure. |

### Integrations and ecosystem

| Gap in Shortcuts | Competitor expectation and evidence | Product importance | Recommendation |
|---|---|---:|---|
| Plug-in/app action protocol | Tasker has a mature plug-in ecosystem; MD explicitly supports Tasker/Locale plug-ins; AUT supports Tasker plug-ins, intents, broadcasts, and content providers; APL gets structured actions through App Intents. | High leverage | Investigate acting as a Locale/Tasker plug-in **host** and exposing “Run Shortcuts shortcut” as a plug-in action. Validate current SDK/ecosystem compatibility before committing. |
| Branded SaaS connectors and managed OAuth | IFT and ZAP are built around managed service connections. | Low near-term | Treat as deliberate scope. One or two flagship integrations would not close the credibility gap; reliable OAuth, API drift, support, and security are recurring operational work. |
| Cross-device/cloud sync | Cloud tools are inherently cross-device; APL uses iCloud for shortcuts while personal automations remain device-specific; Tasker/MD/AUT have backup/export paths. | Medium | Add encrypted optional backup only after a portable schema and secret-stripping exist. Local operation must remain complete. |
| Developer-facing action surface | APL's App Intents give third-party apps a typed contract; Zapier/IFTTT have developer platforms. | Low near-term | First expose Android intents/shortcuts for other apps to invoke a saved shortcut with user-granted allowlists. A public SDK can follow proven demand. |

### Sharing and community

| Gap in Shortcuts | Competitor expectation and evidence | Product importance | Recommendation |
|---|---|---:|---|
| Export/import and backup | Tasker documents import/export/backups; AUT flows are shareable; APL supports file/iCloud export plus setup questions and validation. | High | Build a versioned local export first. Strip secrets and device-specific coordinates by default, show required apps/permissions, validate on import, and preserve stable step IDs. |
| Starter templates/recipes | MD's listing and template store, AUT's in-app community, IFT community Applets, ZAP's template catalog, Samsung recommendations, and Apple's curated Gallery all prevent blank-canvas paralysis. | Very high | Ship a small offline curated gallery with transparent requirements and success criteria. Templates should teach recording and supported native actions, not promise brittle third-party coordinates. |
| Public community/rating/discovery | MD and AUT have user communities; IFT users publish Applets; cloud tools and Apple provide discovery surfaces. | Medium/later | Defer a public marketplace until schema signing, moderation, malware/scam review, compatibility metadata, reporting, revocation, and privacy review exist. A shared macro can send data or tap destructive controls. |
| Collaborative/team administration | ZAP has shared folders/connections, roles, SSO, audit logs, and enterprise controls. | Not relevant now | Deliberately omit. This is a consumer/local product unless strategy changes. |

### UX and onboarding

| Gap in Shortcuts | Competitor expectation and evidence | Product importance | Recommendation |
|---|---|---:|---|
| Goal-first onboarding and suggestions | Samsung offers recommended routines/categories; Apple Gallery previews actions and setup; MD templates and ZAP templates start from an outcome. | Very high | Open with “Record something,” “Describe it,” “Build manually,” and 6–10 outcome recipes. Delay accessibility/model requests until the chosen path needs them. |
| Trigger–condition–action mental model | MD and Samsung use readable sentences; IFT is literally If This Then That; Apple uses stacked action cards and searchable actions. | Very high once triggers land | Preserve plain-language cards. Offer a simple mode first and an advanced branch/data editor on demand. |
| Permission/compatibility planner | Mature Android automators explain missing permissions and device restrictions per feature; Apple/Samsung benefit from system integration. | High | Before save/run, show exactly which services, special access, packages, network, and screen state a workflow needs, with one repair path per issue. Never ask for a bundle of speculative permissions. |
| Searchable action catalog and parameter discoverability | Tasker/MD/AUT have large searchable catalogs; Apple browses by category/app and makes variable tokens visible. | High as vocabulary grows | Add search, recent/favourite actions, capability badges (“direct,” “opens Settings,” “requires confirmation”), and variable-token insertion. |
| Explainable AI editing | Shortcuts already shows a reviewable draft, which is stronger than executing a prose request directly. Zapier Copilot and recent platform assistants raise expectations for conversational editing/troubleshooting. | Medium/high | Expand the model only after the runtime schema. Show which phrase produced each step, unresolved clauses, and deterministic validation; never let the model silently choose a destructive target. |
| Run state from widgets/launch surfaces | Apple widgets display progress/cancel and open the app when interaction is needed; Shortcuts widgets fire and rely mostly on notifications/toasts. | High | Standardize queued/running/succeeded/failed/cancelled state and expose it consistently in widget, dashboard, and notification. |

### Reliability, safety, and observability

| Gap in Shortcuts | Competitor expectation and evidence | Product importance | Recommendation |
|---|---|---:|---|
| Run history and per-step diagnostics | Tasker/MD/AUT expose operational logs/debugging; ZAP sells versions, replay, alerts, and observability in higher tiers. Shortcuts returns a transient result only. | Critical | Persist bounded, redacted run/step records with timestamps, duration, trigger, selector attempted, failure category, and suggested repair. Add exportable diagnostics with user review. |
| “Wait until” and assertions | Mature flow engines pause on conditions/events. AUT specifically recommends event-driven “When changed” blocks instead of polling ([FAQ](https://www.llamalab.com/automate/doc/faq.html)). Shortcuts only sleeps or waits a fixed internal selector timeout. | Critical for recorded UI | Add Wait for app/screen/node/text/disappearance, Assert screen, and per-step timeout. Use accessibility events with a deadline, not hot polling. |
| Retry/fallback policy | Cloud products retry/replay; UI automators expose alternative matching methods. | Critical | Store ordered selector alternatives and retry rules; distinguish “not found,” “wrong app,” “screen changed,” “gesture rejected,” and “permission unavailable.” Never retry a potentially destructive tap blindly. |
| Preflight and workflow health | Broad automators surface permissions; APL templates can ask setup questions. | Critical | Calculate health before run: accessibility enabled, required app installed/version changed, permission/special access, connectivity, screen unlocked, coordinate compatibility, secrets present. |
| Versioning and rollback | ZAP exposes workflow versions; sharing products validate/import structured artifacts. | Medium | Save a limited revision history for edits and imports. Make repair/re-record produce a new revision, not destroy the last working one. |
| Process/reboot resilience | AUT documents persisted flow/fiber state across restart. Shortcuts has recorder session persistence, but execution is not a durable workflow state machine. | High for future triggers | Persist execution cursor only for safe idempotent steps. Never resume an uncertain tap after process death; pause and ask. |
| Unified execution semantics | This is an internal Shortcuts gap: dashboard and widget use different entry paths and disabled/concurrent behaviour. | Critical | Route every surface and trigger through one orchestrator, with one queue/cancel/isActive policy. |
| Vendor/UI drift management | Automate explicitly calls screen interaction a last resort and warns that changing UI breaks XPath; MD warns of UI fragility. | Existential to the differentiator | Detect package/version/display changes, score selector confidence, make repair a one-step re-identification flow, and publish honest compatibility language. Do not market UI replay as guaranteed. |

## 4. Where Shortcuts is competitive today

### 4.1 Continuous recording is a genuine creation-flow advantage

Shortcuts records a **session**, not merely a coordinate. It observes supported accessibility events while the user performs the real task, captures app transitions, coalesces noisy scroll/text events, and produces a reviewable multi-step shortcut. It also saves view ID/text/content-description/class metadata when available and keeps coordinates as a guarded fallback.

The closest documented competitor flows are less direct:

- MacroDroid's “Identify in app” is a per-action target picker inside its [UI Interaction action](https://www.macrodroidforum.com/wiki/index.php/Action%3A_UI_Interaction).
- Automate's “Record interactions” generates an XPath for an individual [Interact block](https://llamalab.com/automate/doc/block/interact.html); its documentation says generated XPath may need editing.
- AutoInput Actions v2 offers a guided multi-operation helper and can keep several UI actions in [one Tasker action](https://joaoapps.com/autoinput-actions-v2-single-action-total-ui-automation/).

**Judgment:** official documentation supports calling Shortcuts' normal-use, cross-app session capture a meaningful UX differentiator. It does **not** support calling UI recording, target identification, accessibility gestures, or multi-step UI automation unique. A hands-on competitor test would be needed before making comparative marketing claims.

### 4.2 “Mark a tap” is valuable, but not unique

The fallback addresses a real Android problem: canvas/custom-rendered controls may not emit `TYPE_VIEW_CLICKED`. The user can deliberately mark that coordinate without abandoning the recording flow. That preserves momentum and makes the recorder more complete.

However, the interaction resembles MacroDroid's identify notification and AutoInput's helper. It also currently consumes the marking tap rather than activating the underlying control, and coordinate-only metadata can be insufficient for safe replay. The right claim is **“a recorder-integrated fallback”**, not a novel capture technology.

### 4.3 Replay safety is unusually thoughtful

The selector cascade plus refusal to replay coordinates in the wrong package, orientation, geometry, scale, or bounds shows more care than a simple coordinate macro. That is a competitive foundation. It reduces the risk of a stale shortcut pressing an unrelated destructive control.

It is not yet a reliability advantage users can fully experience because there is no health check, repair flow, wait-until primitive, or useful run history. A safe refusal without an actionable recovery path still feels like failure.

### 4.4 Local-first natural-language drafting is a credible differentiator

The optional model is downloaded, verified, and run on-device. The description is converted into a constrained function-call draft and reviewed before execution. No cloud inference is needed after download, and installed-app grounding is local.

This is competitive on privacy and operating cost. It is not yet competitive on vocabulary: the AI exposes fewer actions than the manual builder and none of the trigger/logic/data model expected from mature tools. Also, this research did not establish that no competitor has an AI builder; the defensible claim is **on-device/offline, constrained, review-first generation in this product**, not “the only AI automation builder.”

### 4.5 Widgets and approachable builders are competitive within the narrow scope

The unified Auto/Single/Grid/List widget, per-shortcut icon/colour, manual sentence-like builder, recorder review, and AI review make the current seven-action product easier to approach than a blank Tasker profile or Automate flowchart. This is competitive, not category-leading: Tasker now has a [Widget v2 visual editor](https://tasker.joaoapps.com/changes/changes6.4.html), Automate supports custom widgets, Samsung offers lock-screen/Quick Settings/widget invocation, and Apple provides polished widgets and many other run surfaces.

### 4.6 Privacy and human confirmation are strengths

Recorded data, shortcut definitions, and inference stay local; HTTP secrets use encrypted storage references; HTTP defaults to HTTPS; sensitive text is not recorded; SMS/calls open system confirmation UI. Those are good product decisions. They should be made visible in onboarding and export/run diagnostics rather than left as implementation details.

## 5. Missing feature versus deliberate scope decision

The competitors omit or constrain capabilities for rational reasons. “More automation” is not automatically better.

| Deliberate boundary | Who demonstrates it | Why it is reasonable | Decision for Shortcuts |
|---|---|---|---|
| No arbitrary cross-app touch automation in first-party routine systems | Samsung, Google, and Apple primarily use privileged device controls or supported app/device actions. Apple app integrations are explicitly exposed through [App Intents](https://developer.apple.com/documentation/appintents). | Supported contracts are safer, more semantic, more accessible, and less likely to break when pixels move. First-party privileges also cannot be copied by a Play app. | Keep UI replay as the differentiated escape hatch, but prefer a native intent/API action whenever one exists and label the difference. |
| Cloud products do not automate local pixels | IFTTT and Zapier automate authenticated service APIs and webhooks. | A cloud service cannot reliably know a phone's foreground UI; trying would create severe privacy, latency, and device-control risk. | Do not benchmark local action count against cloud connectors. Use them to benchmark trigger/action vocabulary, data mapping, templates, status, and error handling. |
| UI interaction is a last resort | LlamaLab says this directly in the [Interact documentation](https://llamalab.com/automate/doc/block/interact.html); MacroDroid documents screen-state and fragility limits for [UI Interaction](https://www.macrodroidforum.com/wiki/index.php/Action%3A_UI_Interaction). | UI hierarchy, text, coordinates, timing, and custom rendering change. Accessibility data may be absent. A failed tap can be ambiguous or harmful. | Make reliability/repair the first roadmap theme. Do not disguise coordinate macros as stable integrations. |
| No transparent ambient raw-touch recorder | Android's [`TouchInteractionController`](https://developer.android.com/reference/android/accessibilityservice/TouchInteractionController) only receives touchscreen motion when touch-exploration capability is enabled; touch exploration changes single-finger interaction, and delegation stops delivery for that interaction. Generic accessibility motion events can also be intercepted rather than delivered normally. | A recorder cannot simply observe every raw touch system-wide while leaving behaviour unchanged. Explore-by-touch is an accessibility interaction model, not an invisible analytics tap. Overlays that consume touch also change the task being recorded. | Do not pursue “record every touch invisibly.” Continue accessibility-event capture plus explicit Mark-a-tap, and improve the latter's metadata/repair UX. |
| No autonomous AI operating the UI | Google Play permits deterministic human-defined automation but prohibits Accessibility API use that autonomously initiates, plans, and executes actions/decisions ([AccessibilityService policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)). | An agent choosing and pressing arbitrary controls can cause purchases, disclosure, messages, or account changes outside a narrow understood purpose. | AI must create an inspectable static draft. Require explicit save/run, highlight unresolved/privileged/destructive steps, and resubmit the Play declaration whenever the use changes. |
| No silent SMS/calls | Shortcuts already uses compose/dial intents. Apple also prompts for sensitive operations in many contexts; Android/Play restrict direct SMS/Call Log permissions. | Prevents surprise charges, impersonation, spam, and accidental communication; avoids default-handler-only permissions. | Keep this boundary. “Auto-send” is not a roadmap gap. Add optional confirmation steps rather than bypasses. |
| No parallel UI macros | Mature graph/cloud engines may parallelize independent work, but a phone has one foreground UI. | Two routines cannot safely own the same screen or accessibility gesture stream. | Keep UI execution serialized. A clear queue/cancel policy is a feature, not a limitation. |
| No root/ADB/device-owner automation | Power-user Android tools sometimes offer shell/root/ADB capabilities; Samsung can use system privileges. | These paths are inaccessible to mainstream users, enlarge the security surface, and are difficult to distribute/support through Play. | Do not add them to the consumer app. If enterprise device-owner management ever becomes a strategy, make it a separate product/distribution decision. |
| No public marketplace yet | Apple validates shared files; mature communities have reporting/rating infrastructure. | A shared workflow can exfiltrate data through HTTP, impersonate UI, or contain device-specific destructive coordinates. | Local signed/versioned import and a curated offline gallery come first. Public uploads are a late trust-and-safety project, not a missing button. |
| No background location by default | Google and Apple can integrate presence deeply; Play-distributed apps face background-location review and OS throttling. Android says it must be critical, obvious, and receives limited background updates ([Android guidance](https://developer.android.com/develop/sensors-and-location/location/background?hl=en)); Play prefers foreground access ([Play policy guidance](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en)). | Permission burden and battery/privacy costs are disproportionate for an early trigger. | Prioritize connected Wi-Fi/Bluetooth as presence proxies. Treat geofencing as a later opt-in feature with a separate go/no-go policy review. |

## 6. Pricing and monetization benchmark

| Product | Current model | Verified detail and interpretation |
|---|---|---|
| Tasker | Paid one-time app; free trial outside Play | The US Play listing showed **US$4.49** on 2026-09-06 ([Play listing](https://play.google.com/store/apps/details?id=net.dinglisch.android.taskerm&hl=en-US)); the developer offers a [seven-day trial](https://tasker.joaoapps.com/download.html). A direct-purchase FAQ also describes non-expiring per-device licenses, with caveats ([official FAQ](https://tasker.joaoapps.com/userguide/en/faqs/faq-direct-purchase.html)). |
| AutoInput | Freemium plug-in: ads/rewarded unlock plus in-app purchases | The [official Play listing](https://play.google.com/store/apps/details?id=com.joaomgcd.autoinput) says it contains ads/in-app purchases and describes free rewarded-ad use. A stable web price for permanent/subscription options was not verified, so no numeric claim is made. Tasker may also be required for the relevant workflows. |
| MacroDroid | Free ad-supported tier; one-time Pro upgrade | The current [Play listing](https://play.google.com/store/apps/details?id=com.arlosoft.macrodroid&hl=en_US) limits free use to five macros and says Pro is a small one-time payment with no subscription. The exact price is regional/in-app and was not verified on the web. |
| Automate | Feature-complete free tier with capacity limit; one-time Premium IAP | Free permits at most 30 running blocks, with unlimited inactive flows; Premium is one in-app purchase owned by the purchasing Google account ([official Premium documentation](https://www.llamalab.com/automate/doc/premium.html)). A stable numeric price was not published in the page reviewed. |
| Samsung Modes and Routines | Bundled with supported Galaxy devices | Accessed from Galaxy Settings and availability varies by device/software ([Samsung support](https://www.samsung.com/us/support/answer/ANS10002538/)). There is no separately priced automation subscription. The hardware/ecosystem purchase is the monetization context. |
| Pixel Rules / Google Home Routines | Included Google/Android service | Pixel Rules are a system feature; Assistant/Home Routines are part of the Google Home/Assistant experience. No standalone routine subscription was found in official help. Hardware and third-party services can, of course, have their own prices. |
| IFTTT | Free plus recurring consumer subscriptions | On 2026-09-06 the [official plans page](https://ifttt.com/plans) showed Free at **$0** (2 Applets), Pro at **US$2.99/month billed annually** (20 Applets), and Pro+ at **US$8.99/month billed annually** (unlimited Applets), with capability/limit differences. Prices and annual discounts are mutable/regional. |
| Zapier | Free usage allowance plus task-metered subscriptions | On 2026-09-06 [official pricing](https://zapier.com/pricing) showed Free at **$0/month** with 100 tasks and two-step workflows; Professional from **US$19.99/month billed annually**, Team from **US$69/month**, Enterprise custom. Higher task tiers cost more and pay-per-task billing can apply. |
| Apple Shortcuts | Free/bundled Apple app and platform capability | Apple's [App Store listing](https://apps.apple.com/us/app/shortcuts/id1462947752?platform=ipad) lists it as free. Its value supports Apple hardware/platform retention rather than direct Shortcuts revenue. |

### Monetization implication for Shortcuts

The closest Android local competitors have trained users to expect either a low one-time purchase or a generous free tier plus lifetime unlock. IFTTT and Zapier justify subscriptions with continuously operated cloud infrastructure, connector maintenance, task processing, collaboration, and support. Shortcuts' local model and on-device inference do not naturally justify a mandatory subscription.

Recommended model:

1. **Free local core:** enough saved shortcuts and full-fidelity recording to prove the product; all safety/repair features; no advertising injected into recording or execution.
2. **One-time Pro unlock:** unlimited shortcuts, advanced conditions/variables, premium widget customization, template packs, export/import, and advanced local triggers. Do not meter runs.
3. **Optional subscription only when recurring server cost exists:** encrypted cross-device backup, authenticated inbound-webhook relay, and perhaps moderated community hosting. Clearly separate it from local Pro.
4. **On-device AI:** include in Pro or as a one-time add-on. The main variable cost is model distribution, not per-run inference. Offer manual/recorder creation without the large download.

Do not gate run history, permission clarity, accessibility disclosure, or safe failure recovery behind payment. Those are product integrity.

## 7. Prioritized implementation plan

### Sizing and sequencing

- **S:** localized work, normally a few engineering days plus tests.
- **M:** one coherent vertical slice, approximately one to two engineering weeks plus tests/polish.
- **L:** multi-layer or policy-sensitive epic, normally several weeks; split into independently releasable slices.

Sizes are relative, not commitments. Every feature must include JUnit/MockK unit coverage and Espresso/instrumented coverage appropriate to the UI/service boundary, consistent with the repository rules. New architectural decisions must update the relevant OKF document when implementation occurs.

### Phase 0 — make the differentiated core trustworthy

Do this before autonomous triggers. Otherwise background execution will amplify opaque failures.

#### P0.1 — Versioned workflow schema and runtime context — **L**

- **User problem:** the current flat `actionsJson` can represent only a linear list, has overloaded fields, and has no typed trigger, condition, output, stable step ID, or schema version.
- **Competitor expectation:** Apple/Tasker/MD/AUT/ZAP all pass typed values or variables between structured steps; trigger/condition/action are separate concepts.
- **Implementation location:** introduce versioned domain models under `data/workflow/`; migrate `data/Automation.kt` and `data/AppDatabase.kt`; add converters/migrations under `data/`; update `repository/AutomationRepository.kt`; make `service/ActionExecutorService.kt` execute `WorkflowStep` through a `RunContext`. Preserve a compatibility reader for current JSON. Update builders/components in `ui/screens/ManualBuilderScreen.kt`, `ui/screens/AiBuilderScreen.kt`, `ui/screens/RecorderScreen.kt`, and `ui/components/`.
- **Constraints/policy:** migrations must be lossless and rollback-tested. Export/log representations must never inline bearer secrets. Keep existing workflows runnable.
- **Dependencies:** none; this is the dependency for conditions, variables, triggers, import/export, and AI vocabulary.

#### P0.2 — One execution orchestrator, queue, cancellation, and consistent enablement — **M**

- **User problem:** dashboard and widget runs currently take different paths; `isActive` and concurrent-run behaviour differ.
- **Competitor expectation:** one visible active/inactive state and predictable run semantics from every surface.
- **Implementation location:** make `service/AutomationExecutionService.kt` the sole public run entry; move queue/run identity/cancel policy there; keep step dispatch in `service/ActionExecutorService.kt`; route `ui/screens/DashboardScreen.kt` and `widget/RunAutomationCallback.kt` through it; expose state through a repository/Flow consumed by dashboard, widget, and notification.
- **Constraints/policy:** UI automation must remain serialized. A user must be able to stop an ongoing foreground run. Foreground-service use must be user-initiated or perceptible, stoppable, and no longer than necessary under [Play's FGS policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-GB).
- **Dependencies:** P0.1 can proceed in parallel, but land a stable run ID contract before P0.3.

#### P0.3 — Redacted run history and actionable diagnostics — **M**

- **User problem:** a failed macro yields transient feedback without telling the user which selector/state failed or how to repair it.
- **Competitor expectation:** logs in local automators; histories, replay, versions, and alerts in Zapier.
- **Implementation location:** add `RunRecord`/`StepRunRecord` entities and DAOs under `data/run/` and `AppDatabase.kt`; instrument `AutomationExecutionService.kt`, `ActionExecutorService.kt`, and `AutomationAccessibilityService.kt`; add a run-detail screen under `ui/screens/`, state in `viewmodel/AutomationViewModel.kt`, and links from dashboard/notification.
- **Constraints/policy:** redact typed text, SMS bodies, tokens, headers, URLs/query values, notification content, and node text by default. Bound retention by count/age and provide clear/delete controls. Diagnostic export must require review.
- **Dependencies:** run IDs from P0.2; schema/context from P0.1 is preferred.

#### P0.4 — Preflight and workflow health — **M**

- **User problem:** users discover missing accessibility, permissions, apps, secrets, connectivity, or coordinate incompatibility only mid-run.
- **Competitor expectation:** mature tools expose requirements per action; Apple Gallery has setup steps/import questions.
- **Implementation location:** create `domain/validation/WorkflowPreflight.kt`; reuse permission checks from settings/services; inspect packages and display metadata; surface a health sheet in `DashboardScreen.kt`, builder save/test flows, `ShortcutWidgetConfigActivity.kt`, and execution notification.
- **Constraints/policy:** explain why each access is needed and request it just-in-time. Accessibility automation apps are not accessibility tools and require separate prominent disclosure, consent, and an accurate Play declaration ([policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)).
- **Dependencies:** P0.1 for typed requirements; useful independently for current actions.

#### P0.5 — Wait-until, assertions, selector confidence, and repair — **L**

- **User problem:** fixed delays and a hard-coded node wait cannot reliably handle variable load time or changed screens; safe failures have no repair loop.
- **Competitor expectation:** event/condition waiting in flow engines; multiple targeting modes in AutoInput/MD/AUT.
- **Implementation location:** extend the workflow step model with `WAIT_FOR` and `ASSERT`; implement event-driven deadlines and alternative selector sets in `service/AutomationAccessibilityService.kt`; capture confidence/alternatives in `AutomationRecorder.kt`, `RecorderSession.kt`, and `TapMarkOverlay.kt`; add “re-identify this target” from run detail and step cards.
- **Constraints/policy:** no hot polling; no automatic blind retry after an ambiguous or potentially destructive tap. Coordinate fallback must retain package/rotation/geometry checks. Mark-a-tap should explicitly report when it could not bind a safe package/selector.
- **Dependencies:** P0.1 and P0.3. Ship Wait for app/screen/node before more sophisticated repair scoring.

#### P0.6 — Correct in-product capability wording — **S**

- **User problem:** help/model examples and system-control labels can imply direct toggles or actions that Android actually routes through confirmation/settings; help content lags the seven action types.
- **Competitor expectation:** clear capability and permission boundaries.
- **Implementation location:** `ui/screens/HelpScreen.kt`, `ui/screens/SettingsScreen.kt`, `ui/screens/ManualBuilderScreen.kt`, AI examples in `planner/FunctionCallingPromptBuilder.kt`, and action descriptions in `util/ActionDescriber.kt`.
- **Constraints/policy:** use “Open Wi-Fi controls,” “Ask to turn on Bluetooth,” and “Compose message” where accurate. This is also essential to the AccessibilityService declaration's “narrow and clearly understood purpose.”
- **Dependencies:** none.

**Phase 0 exit criteria:** every launch surface uses the same orchestrator; an existing workflow survives migration; each run has a redacted step history; preflight finds known missing requirements; recorded UI steps can wait for and repair a target; instrumentation covers dashboard/widget parity and a representative cross-app failure path.

### Phase 1 — win the first ten minutes and expand user-initiated reach

#### P1.1 — Curated offline starter gallery and goal-first onboarding — **M**

- **User problem:** the blank editor does not teach what is reliable or why recording, manual, and AI creation differ.
- **Competitor expectation:** Samsung recommendations, Apple Gallery, MD/AUT communities, IFT/ZAP templates.
- **Implementation location:** add versioned recipe assets under `app/src/main/assets/recipes/`; repository/parser under `data/templates/`; gallery screen under `ui/screens/`; route from `MainActivity`/navigation and the empty dashboard. Each recipe declares apps, permissions, device limitations, and editable placeholders.
- **Constraints/policy:** do not bundle third-party coordinates as generally working templates. Prefer native actions and guided recording. No network/community permission is needed for v1.
- **Dependencies:** P0.1 import format and P0.4 preflight.

#### P1.2 — Quick Settings tile, dynamic app shortcut, notification launcher, and share target — **M**

- **User problem:** widgets are useful but costly to configure and unavailable in many moments; users expect shortcuts in system surfaces.
- **Competitor expectation:** MD/AUT/SAM/APL expose Quick Settings, shortcut, share, voice, or hardware surfaces.
- **Implementation location:** new services/receivers under `integration/system/`; declarations in `AndroidManifest.xml`; selection UI reusing `ShortcutWidgetConfigActivity.kt`; all starts call `AutomationExecutionService`. Add `ACTION_SEND` intake as a typed trigger input for manually selected shortcuts.
- **Constraints/policy:** preserve explicit user initiation. Avoid full-screen intents. Respect locked-device state and require unlock for UI replay or sensitive inputs.
- **Dependencies:** P0.2 and P0.4; P0.1 for share-sheet input tokens.

#### P1.3 — Versioned local export/import with secret and compatibility review — **M**

- **User problem:** users cannot back up, move, inspect, or share their work.
- **Competitor expectation:** Tasker import/export, AUT community flows, Apple file/iCloud sharing.
- **Implementation location:** serializer/validator under `data/portability/`; Storage Access Framework/share-sheet flows in `ui/screens/`; import preview showing apps, permissions, coordinates, URLs, and removed secrets; use P0.4 health checks after import.
- **Constraints/policy:** strip encrypted-secret references and recorded sensitive values; flag every external host and UI-automation step; reject unknown executable types safely; use canonical schema versions and integrity hashes. Do not auto-enable imported workflows.
- **Dependencies:** P0.1 and P0.4.

#### P1.4 — Safe native utility action pack — **M**

- **User problem:** many everyday outcomes require awkward UI replay even though Android provides supported APIs/intents.
- **Competitor expectation:** hundreds of device actions in T+AI/MD/AUT and friendly system actions in APL/SAM.
- **Implementation location:** add modular executors under `service/actions/` instead of growing `ActionExecutorService.kt`; add Open URL/deep link, Share text, copy text, vibrate, toast/notification, brightness panel/value where allowed, media transport where permitted, and screen-lock/global actions already supported by the accessibility parser but not exposed. Add cards/catalog entries in `ManualBuilderScreen.kt` and `ui/components/`.
- **Constraints/policy:** capability badges must distinguish direct, confirmation, and settings-panel actions. Clipboard behaviour and notification permission vary by Android version. Avoid broad storage and restricted settings hacks.
- **Dependencies:** P0.1 modular step contract and P0.4 requirement metadata.

#### P1.5 — HTTP response outputs and API ergonomics — **M**

- **User problem:** users can call a webhook but cannot branch on status or use response data.
- **Competitor expectation:** web/API outputs flow into later steps in Tasker/AUT/ZAP/APL.
- **Implementation location:** refactor HTTP into `service/actions/HttpActionExecutor.kt`; return typed status/selected headers/bounded body; add JSON-path selection and timeout/retry inputs to builder cards; store results in `RunContext`; expose auth/header/body editing clearly in `DraftStepCard.kt`/`ReviewStepCard.kt`.
- **Constraints/policy:** retain HTTPS default and encrypted secrets. Cap body size, redact logs, reject unsafe redirect/cleartext surprises, and do not retry non-idempotent requests by default.
- **Dependencies:** P0.1 and P0.3.

**Phase 1 exit criteria:** a new user can install, choose a trustworthy recipe or record a task, see requirements, run it from at least three non-widget system surfaces, and export/import it without leaking a secret.

### Phase 2 — add useful automation without becoming an ambient surveillance app

#### P2.1 — Typed trigger framework and trigger test harness — **L**

- **User problem:** `triggerType` suggests automation but no trigger can start a workflow.
- **Competitor expectation:** trigger → conditions → actions is the base vocabulary of Tasker, MD, Samsung, IFT, and Zapier.
- **Implementation location:** add `TriggerDefinition` entities/state under `data/trigger/`; `TriggerScheduler` and source adapters under `trigger/`; a single dispatch path into `AutomationExecutionService`; trigger editor in `ui/screens/`; enable/disable and “last fired” state in dashboard; test fakes under unit/androidTest packages.
- **Constraints/policy:** every trigger must be deterministic and human-defined. Show persistent status for ambient sources and a per-trigger off switch. Dedupe bursts and prevent recursive self-triggering.
- **Dependencies:** all Phase 0 foundations.

#### P2.2 — Inexact time/day schedules — **M**

- **User problem:** simple morning/evening routines cannot run automatically.
- **Competitor expectation:** universal across general automation competitors.
- **Implementation location:** a schedule adapter under `trigger/schedule/`, `BroadcastReceiver` declarations, boot/time-zone/time-change rescheduling, and editor/next-run preview.
- **Constraints/policy:** use inexact `AlarmManager`/WorkManager by default. Android says most apps should avoid exact alarms; exact scheduling needs special access and a genuinely precise user-facing purpose ([alarm guidance](https://developer.android.com/develop/background-work/services/alarms)). If UI steps require an unlocked foreground screen, notify and ask the user to continue rather than trying to defeat the lock screen.
- **Dependencies:** P2.1 and P0.4.

#### P2.3 — Event-driven device triggers — **L**

- **User problem:** no reaction to charging, battery, wired/Bluetooth devices, Wi-Fi association, screen/unlock, or headset state.
- **Competitor expectation:** core in Tasker/MD/AUT/SAM/APL.
- **Implementation location:** adapters under `trigger/device/` using supported callbacks/dynamic receivers; persist only configuration, not continuous raw event content; editor and current-state preview; manifest entries only where required.
- **Constraints/policy:** avoid periodic polling and respect modern implicit-broadcast/background-start restrictions. Some Wi-Fi identifiers require location-related access; offer generic connected/disconnected conditions when SSID access would add disproportionate permission burden.
- **Dependencies:** P2.1, P0.4, and condition gates P2.4.

#### P2.4 — Simple trigger conditions — **L**

- **User problem:** “when charging” is unsafe without “only at home, during this window, while unlocked.”
- **Competitor expectation:** MD constraints, Tasker contexts, Google/Samsung conditions.
- **Implementation location:** typed predicates under `domain/condition/`; evaluator against `RunContext`/device snapshot; nested UI limited initially to ALL/ANY with time/day, screen/unlock, charging/battery, connectivity, and foreground package if available.
- **Constraints/policy:** evaluate conditions before launching a foreground service or accessibility action. Explain false/unknown in run history. Do not silently treat unavailable permission as false.
- **Dependencies:** P0.1, P0.3, P2.1.

#### P2.5 — Notification trigger, privacy-first — **L**

- **User problem:** users cannot react to delivery, authentication, messaging, or monitoring notifications.
- **Competitor expectation:** Tasker/MD/AUT provide notification contexts; cloud tools provide analogous structured service events.
- **Implementation location:** dedicated `NotificationListenerService` under `trigger/notification/`; per-app/text/category filters; trigger editor with sample redaction; manifest service declaration; preflight and dedicated disclosure/consent; never route raw content into logs/templates by default.
- **Constraints/policy:** notification content is highly sensitive. Request separate special access only after an explicit recipe needs it; default to selected apps; keep matching local; provide pause/delete controls. Reassess Data Safety and Play disclosures.
- **Dependencies:** P2.1, P2.4, P0.3, P0.4.

**Phase 2 exit criteria:** deterministic schedules and selected device/notification events can launch a workflow through the same orchestrator; users can see why a trigger fired or was blocked; background sources do not poll continuously; every new sensitive access is independently disclosed and revocable.

### Phase 3 — composable workflows without exposing a programming language first

#### P3.1 — Friendly variables, trigger inputs, and token insertion — **L**

- **User problem:** every value is static; share-sheet content, HTTP responses, user input, trigger data, and prior results cannot feed later steps.
- **Competitor expectation:** Tasker/MD/AUT/ZAP/APL all pass values between steps.
- **Implementation location:** typed `Value`/`VariableRef` in `data/workflow/`; token resolution in `RunContext`; output contracts per `service/actions/` executor; variable picker in step cards; expose safe built-ins such as current time, trigger name/data, last result, and selected HTTP fields.
- **Constraints/policy:** distinguish missing/null/error; mark sensitive values and prevent them from history/export/UI previews. Avoid stringly typed expressions in serialized action fields.
- **Dependencies:** P0.1 and P1.5; P1.2/P2 triggers supply useful inputs.

#### P3.2 — If/Else and Stop with readable conditions — **L**

- **User problem:** a workflow cannot adapt to state or recover from a failed network/native step.
- **Competitor expectation:** universal in mature workflow products.
- **Implementation location:** composite step nodes under `data/workflow/`; interpreter in the orchestrator/runtime; stack/nested card editor in `ui/components/`; validation for unreachable/empty branches; history records chosen path.
- **Constraints/policy:** no AI-generated hidden branch. Preview the condition in plain language. UI-target absence can be a branch input, but a failed potentially destructive tap must not be treated as safely retryable.
- **Dependencies:** P3.1, P2.4 evaluator, P0.3.

#### P3.3 — Prompt, choose, confirm, and notification actions — **M**

- **User problem:** workflows cannot safely ask for missing data or request confirmation at a sensitive point.
- **Competitor expectation:** APL and the Android automation engines offer input/choice/dialog actions.
- **Implementation location:** action executors under `service/actions/user/`; notification/deep-link handoff for background runs; activities/sheets under `ui/runtime/`; outputs into P3.1 variables.
- **Constraints/policy:** a background workflow cannot freely launch intrusive UI. Use a visible notification that resumes after user action. Add expiry/cancel semantics and never put secret inputs in notification text.
- **Dependencies:** P0.2, P3.1.

#### P3.4 — Run another shortcut as a subflow — **M**

- **User problem:** users duplicate common sequences and cannot compose small reliable shortcuts.
- **Competitor expectation:** Tasker reusable tasks, MD Action Blocks, AUT subflows, ZAP Sub-Zaps.
- **Implementation location:** new typed action; orchestrator call stack; shortcut picker; optional input/output mapping; dependency graph and deletion warnings in repository/UI.
- **Constraints/policy:** detect cycles, cap depth, propagate cancellation, and make enabled/deleted dependency behaviour explicit. UI work remains serialized.
- **Dependencies:** P3.1 and P0.2.

#### P3.5 — Bounded repeat — **M**

- **User problem:** users cannot apply the same safe action to a finite list or repeat a known number of times.
- **Competitor expectation:** loops in Tasker/MD/AUT/ZAP/APL.
- **Implementation location:** composite repeat node; list/numeric input; interpreter counters; progress/cancel UI and run history.
- **Constraints/policy:** hard maximum iteration and wall-clock budgets; no unbounded “while true”; stronger confirmation/limits for UI gestures, messages, or non-idempotent HTTP actions.
- **Dependencies:** P3.1–P3.3 and reliable cancellation/history.

**Phase 3 exit criteria:** a non-programmer can insert a prior output, make one readable decision, ask for confirmation, reuse a shortcut, and repeat a bounded list while always being able to inspect and stop the run.

### Phase 4 — interoperability and selective premium infrastructure

#### P4.1 — Locale/Tasker plug-in compatibility spike, then host/expose — **S discovery + L implementation**

- **User problem:** Shortcuts cannot use the Android automation ecosystem and other tools cannot invoke its strongest recorder-built macros cleanly.
- **Competitor expectation:** MD and AUT advertise Tasker plug-in compatibility; Tasker has a large plug-in ecosystem.
- **Implementation location:** first document current Locale/Tasker protocol viability in `okf-docs/`; if viable, adapters under `integration/plugin/`, manifest components, action configuration bridge, result/variable mapping, and an externally invokable “Run saved shortcut” plug-in action guarded by user selection/allowlist.
- **Constraints/policy:** exported components must require explicit consent and validate caller/payload; plug-ins can introduce their own sensitive data and lifecycle failures. Do not promise compatibility before testing current maintained plug-ins.
- **Dependencies:** P0.1, P0.2, P3.1.

#### P4.2 — NFC trigger — **M**

- **User problem:** no intentional physical trigger for desks, cars, bedside, or equipment.
- **Competitor expectation:** MD/AUT/APL and some OEM routines support NFC.
- **Implementation location:** adapter under `trigger/nfc/`, tag enrollment, intent dispatch, lock-state preflight, duplicate debounce, manifest intent filters as appropriate.
- **Constraints/policy:** define behaviour when locked; do not place secret workflow data on the tag; require user confirmation for risky shortcuts.
- **Dependencies:** P2.1, P0.4.

#### P4.3 — Expand on-device AI to the proven schema — **L**

- **User problem:** natural-language creation cannot express much of the manual runtime, triggers, conditions, or variables.
- **Competitor expectation:** conversational builders increasingly draft complete workflows, but safe review remains essential for UI control.
- **Implementation location:** update `planner/FunctionCallingPromptBuilder.kt`, `FunctionCallParser.kt`, `DraftShortcut.kt`, `GroundingContext.kt`, and `AiBuilderViewModel.kt`; add schema-capability retrieval rather than one monolithic prompt; provide deterministic validators and clause-to-step explanation.
- **Constraints/policy:** the 1,024-token model context is tight; prefer staged intent classification and constrained forms. Never let generated output immediately operate accessibility. Require explicit review/save/run and highlight settings panels, external hosts, communication, imported data, and low-confidence UI targets to stay within [Play's deterministic automation boundary](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en).
- **Dependencies:** only after P0–P3 action/trigger contracts stabilize. Do not repeatedly churn the model prompt ahead of runtime capability.

#### P4.4 — Optional encrypted backup/sync — **L**

- **User problem:** local-only workflows are lost or tedious to move; device-specific coordinates complicate migration.
- **Competitor expectation:** cloud tools sync inherently; Apple shares via iCloud; local Android tools support backup/export.
- **Implementation location:** sync boundary atop `data/portability/`; encrypted artifact storage; conflict/revision UI; device compatibility scan and re-identification queue after restore.
- **Constraints/policy:** end-to-end encryption or a very clear alternative; keys and secret actions handled separately; deletion/export controls; no accessibility/notification content uploaded. This introduces an account, privacy, incident-response, and recurring-cost obligation.
- **Dependencies:** P1.3 and revision history from Phase 0/3.

#### P4.5 — Authenticated inbound-webhook relay — **L**

- **User problem:** cloud systems cannot trigger a local shortcut while the app/device is not directly reachable.
- **Competitor expectation:** IFTTT/ZAP and MD webhook triggers make a unique inbound URL feel normal.
- **Implementation location:** this is not only Android code: relay API, authenticated endpoint, abuse/rate limiting, push delivery, device registration, retry/expiry/idempotency, and status UI; Android receiver under `trigger/remote/` enters the same P2 trigger framework.
- **Constraints/policy:** remote requests must not silently unlock/control UI. Require per-workflow opt-in, signed payloads, replay protection, visible notification, and user confirmation for UI/sensitive steps. Background push delivery is not a real-time guarantee. This is subscription-eligible infrastructure.
- **Dependencies:** P2.1, P0.2–P0.4, account/sync security design.

#### P4.6 — Moderated community/templates service — **L**

- **User problem:** users cannot learn from or reuse others' solutions.
- **Competitor expectation:** MD/AUT/IFTTT/ZAP/APL all provide templates or discovery at varying levels.
- **Implementation location:** extend the safe P1 recipe format; signing and server-side static analysis; compatibility metadata; author/version/review/report/revoke; install preview; moderation/admin service.
- **Constraints/policy:** treat HTTP hosts, messages, settings changes, coordinates, accessibility steps, and imported intents as security-sensitive. Never auto-run or auto-enable an install. Establish takedown, abuse, and privacy operations before public uploads.
- **Dependencies:** P1.1/P1.3, P0.4, revisions, and a funded trust-and-safety operation.

### Explicitly deferred / no-go until evidence changes

| Item | Decision gate |
|---|---|
| Background geofencing | Build only after Wi-Fi/Bluetooth presence data shows material unmet demand and Play background-location eligibility is confirmed before implementation. |
| Exact-to-the-minute automation | Default to inexact. Request special alarm access only for a documented user-facing use case that meets Android/Play limits. |
| Image/OCR “find and tap” | Defer. It adds screen-capture consent, sensitive pixel processing, false matches, device variance, and destructive-tap risk. Validate demand and policy first. |
| Silent message/call | No-go for the consumer product. Keep system compose/dial confirmation. |
| Root, ADB, Shizuku, hidden APIs | No-go for the Play-focused mainstream product. Revisit only as a separate advanced distribution strategy. |
| Autonomous AI agent controlling AccessibilityService | No-go under the current product purpose and Play policy. AI drafts static human-reviewed workflows only. |
| Arbitrary parallel UI execution | No-go. A single foreground UI gets a serialized queue. |
| Bespoke catalog of hundreds of SaaS OAuth connectors | No-go for this roadmap. Interoperate through HTTP, intents/deep links, and the existing plug-in ecosystem first. |

## 8. Recommended product strategy and success measures

### Positioning

Lead with **recording and local replay**, not “automate anything.” A truthful short description would be:

> Record a task across Android apps, review the steps, and run it later from a widget or shortcut. Shortcuts works locally and uses accessibility only for the screen actions you define.

Avoid claims such as “reliable with every app,” “records every tap,” “toggles any setting,” or “AI controls your phone.” The code does not support them and Android deliberately prevents some of them.

### Build-order rationale

1. **Reliability creates retention.** More triggers merely cause existing failures when the user is not watching.
2. **Templates create activation.** They teach the trustworthy subset without requiring a broad action catalog.
3. **Manual system surfaces create frequency with low policy cost.** Quick Settings/share/dynamic shortcuts reinforce the current user-invoked product.
4. **A small trigger set creates the automation category.** Schedule/device/notification covers common demand while remaining deterministic.
5. **Variables and branches create depth.** Add them only after the runtime can explain execution.
6. **Interoperability creates breadth.** Plug-ins/webhooks are more leveraged than individually cloning hundreds of actions.
7. **Cloud/community comes last.** It changes the security, support, privacy, and monetization model.

### Suggested outcome metrics

- **Activation:** percentage of new users who save and successfully run one shortcut within the first session; split by Record/AI/Manual/template.
- **Recorder value:** median manual edits after recording; capture coverage by event type; Mark-a-tap invocation and successful repaired replay rate.
- **Reliability:** successful runs by action type and target app/version; selector versus coordinate success; median time from failure to repair; unsafe coordinate refusals (never optimize this safety number to zero).
- **Retention:** shortcuts run per weekly active user; percentage invoked outside the dashboard; percentage of saved shortcuts run again after seven days.
- **Automation safety:** trigger false-positive/duplicate rate; cancelled runs; confirmation rejection; workflows automatically disabled after repeated deterministic failure.
- **Privacy/policy:** accessibility consent completion after relevant explanation, notification/location access opt-in by explicit feature, diagnostic/export redaction incidents, Play declaration review status.
- **AI quality:** valid draft rate, unresolved-clause rate, user edits per generated step, and successful post-review runs. Do not use unreviewed execution rate as a goal.

## 9. Android and Play constraints that shape the roadmap

1. **Accessibility is permitted for narrow deterministic automation, not autonomous agency.** Google Play requires an accurate declaration plus prominent in-app disclosure and affirmative consent for automation apps, and prohibits autonomous initiation/planning/execution through AccessibilityService while allowing static human-defined rules ([policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)). Adding ambient triggers or broader AI behaviour changes the declared use and should trigger a policy review before code ships.
2. **Raw touch capture has interaction costs.** Android's touch controller requires touch-exploration capability to observe/control touchscreen interactions; touch exploration changes how a finger interacts with the device, and delegated interaction is passed through without continuing observation ([API reference](https://developer.android.com/reference/android/accessibilityservice/TouchInteractionController)). Accessibility `onMotionEvent` can also prevent configured event sources from reaching the rest of the system ([AccessibilityService reference](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.html)).
3. **Wi-Fi and Bluetooth direct toggles are unavailable to ordinary modern apps.** `WifiManager.setWifiEnabled` fails for apps targeting Android Q+ except privileged owner/system cases ([Wi-Fi API](https://developer.android.com/reference/android/net/wifi/WifiManager)); `BluetoothAdapter.enable/disable` fails for apps targeting Android 13+ except owner/system cases, with `ACTION_REQUEST_ENABLE` recommended for consent ([Android 13 changes](https://developer.android.com/about/versions/13/behavior-changes-13)). UI wording and template promises must reflect this.
4. **Background starts and foreground services are constrained.** Play requires Android 14+ FGS types to match a beneficial core, user-initiated or perceptible, stoppable, time-bounded use and to be declared/reviewed ([policy](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en-GB)). `specialUse` is not a blanket exemption. Scheduled/remote runs may need to notify and wait for user continuation rather than opening UI.
5. **Exact alarms are exceptional.** Android recommends inexact alarms for most apps; exact alarms require special permission/access and a genuinely precise function ([documentation](https://developer.android.com/develop/background-work/services/alarms)).
6. **Background location is both throttled and policy-reviewed.** Geofencing requires background access in applicable cases, updates can be delayed, and Play expects it to be critical and obvious ([Android](https://developer.android.com/develop/sensors-and-location/location/background?hl=en), [Play](https://support.google.com/googleplay/android-developer/answer/9799150?hl=en)).
7. **SMS/Call Log permissions are default-handler territory.** Shortcuts' existing compose/dial intents are the correct mainstream boundary ([Play policy](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en-GB)).
8. **Imported/community workflows are executable content.** Even without a scripting engine, a definition can send HTTP data, open apps, compose messages, or press controls. Versioning, preview, secret stripping, signing, host/app disclosure, disable-by-default, and revocation are baseline security requirements.

## 10. Bottom line

Shortcuts should not try to become a smaller Tasker, a local Zapier, or Android Apple Shortcuts. Those comparisons expose gaps the project cannot close by adding action tiles.

The credible path is:

1. make recorded UI shortcuts recoverable and explainable;
2. make the first successful shortcut dramatically easier through recipes and honest capability labels;
3. put user-invoked shortcuts in more Android surfaces;
4. add a focused deterministic trigger/condition layer;
5. add typed data and readable branches;
6. borrow ecosystem breadth through safe interoperability;
7. add paid cloud/community infrastructure only when it has a clear recurring cost and trust model.

If executed in that order, Shortcuts can be narrower than Tasker/MacroDroid/Automate and still preferable for a large class of users: people who can demonstrate a repetitive task but do not want to program an automation engine.
