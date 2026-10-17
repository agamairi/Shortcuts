---
title: "Recovery plan — making record & replay actually work"
type: plan
author: claude_code_orchestrator
tags: [plan, roadmap, engine-v2, recorder, replay, reliability, e2e]
---

# Recovery plan — making record & replay actually work

*Started 2026-10-17. Owner verdict on v0.7.4: "not usable even by me — recording is wonky, works less than half the time, replay can't recreate what was recorded."*

The goal is unchanged: **Apple Shortcuts on Android, plus UI automation.** You build a shortcut either by recording yourself using other apps, or by stacking actions the Shortcuts way. Recording is the selling point, so it has to be the most reliable part of the app. Today it is the least reliable.

---

## 1. Why it fails today (short version)

Two independent investigations agree. Full evidence with file:line references is in [`engine-v2-spec.md`](engine-v2-spec.md) Part 1 (26 ranked defects) and [`research/touch-capture.md`](research/touch-capture.md).

**Recording misses steps because it only hears about taps that apps choose to announce.** The recorder waits for Android's "view clicked" accessibility event. Apps built with Jetpack Compose, Flutter, React Native and web views never send it for a finger tap. That now covers most Google apps and many others, so those taps silently vanish. "Mark a tap" was a manual workaround, and it records a different flow from the one you actually perform.

**Replay fails, or hits the wrong thing, because its contract is too weak:**

| # | Defect | What you see |
|---|---|---|
| 1 | "Open app" brings the app back to wherever you last left it | Step 2 looks for a button on the wrong screen |
| 2 | Many recordings have no "open app" step at all | Replay starts tapping inside whatever app is in front |
| 3 | Targets match by partial text with no confidence floor | "Internet" taps "Internet & SIM"; identical "Delete" rows are picked by tree order |
| 4 | The click goes to the nearest clickable ancestor (up to 25 levels up), and is never checked | Taps a whole row instead of the switch; reports success when nothing happened |
| 5 | Only the "active window" is searched | Dialogs, popups and overlays (your Dynamic Island app) break lookup |
| 6 | Waits are fixed sleeps, not "wait for the screen this step expects" | Too fast on slow loads, too slow on busy screens |
| 7 | A scroll replays one page-scroll on the first scrollable thing | Can't reach the row you scrolled to |
| 8 | Two text fields without IDs are treated as one | Only the last one gets typed |

None of these can be fixed with one more patch. Every layer needs a stronger model: **what was tapped, in which window, on which screen, and how to know the tap worked.**

---

## 2. Decisions (owner, 2026-10-17)

| Question | Decision |
|---|---|
| How to capture taps | **Capture layer by default.** While recording, a transparent RepeatKit overlay catches each touch, records exactly what is under the finger, then passes the same gesture to the app. Cost: ~0.1 s per tap, and lists scroll only after you lift your finger. |
| Which shortcuts must work first | **All four:** Settings navigation, messaging (type & send), media/playlists, browser & search. |
| Builder / Shortcuts-style side | **Engine first, builder after.** The editor is rebuilt around the new step model once replay is measurably reliable. |
| Shizuku "power mode" | **Later, optional extra.** Designed for, not built yet. |

---

## 3. How we'll know it works — the reliability gate

Nothing ships on "it worked when I tried it". Every claim is measured on the Pixel 6a by an automated harness:

- **Fixture app** (`:fixtures`, "RK Fixtures"): 12 known screens built to be hard. Views, Compose without view ids, long lists of identical rows, near-duplicate labels, dialogs, sheets, menus, text fields with Search/Send, delayed content, multi-screen navigation, a WebView, tabs. Every interaction is logged.
- **Harness** (`tools/e2e/rk_e2e.py`): plays the human with `adb input` while RepeatKit Dev records, saves the shortcut, resets the app, and replays through the real widget path. It then diffs what the fixture received during recording against what it received during replay. Recording faults and replay faults are scored separately.
- **Real-app suites**: the four categories above, as scripted flows on your phone's real apps.

**Bar for "usable" (from the spec):**

- ≥ 95 % pass overall and ≥ 90 % per scenario, over ≥ 600 runs (30 scenarios × 20).
- **Zero wrong-target actions.** Stopping with a clear reason is acceptable; tapping the wrong thing is not.
- Every failure names the step and the reason.

The first measurement is a **baseline of today's engine (v0.7.4)**, so every later change is compared against real numbers.

### Scoreboard (fixture suite, Pixel 6a, 12 scenarios)

| Build | Pass | Recording issues | Notes |
|---|---|---|---|
| v0.7.4 as shipped (2026-10-17) | **0 / 12** | 7 / 12 | Every replay died at step 1: no "open app" step was ever recorded, because the recorder picked the wrong home app on a phone with four launchers. Compose taps were never recorded (Compose basic: 1 of 6 actions). |
| + capture layer, launcher fix, overlay-app fix (still v0.7.4 replay) | **9 / 12** | 1 / 12 | Every Compose tap now recorded. The 3 failures are replay limits Engine v2 addresses: clicking a non-clickable label, Compose text fields without ids, scrolling to a far row. |
| Engine v2 end to end, first integration (record → v2 workflow → v2 replay) | **3 / 12** | 1 / 12 | The new engine ran on the phone for the first time. Failures traced to four capture↔match contract mismatches (Compose labels live on a child node; text fields identified by the typed value; swipes saved as standalone steps; a dialog class recorded as the activity) and three over-strict replay rules (fails at once instead of waiting for delayed content; the expected-screen fingerprint used as a hard gate; scroll progress judged on structure). |
| Engine v2 + contract and strict-rule fixes | **5 / 12 → 8 / 12 → 11 / 12** | — | Successive single-run passes as the shared label/sibling/role helpers, requested-action dispatch and live-identity checks landed. The last failure was a keyboard Search on a field identified by its placeholder. |
| Engine v2, placeholder identity fix — **12 scenarios × 5 runs** | **59 / 60 (98.3 %)** | 5 / 60 | 11 of 12 scenarios 5/5; text_views 4/5. The failure was in the *recording*: the harness's injected Enter landed on the Message field instead of Search, with no RepeatKit action in between; replay reproduced the recording exactly. The 5 recording issues are all navigation: the final Back press was never recorded. Root cause: the on-screen Back button, the back gesture and adb-injected keys never reach `onKeyEvent` at all; Back is now *inferred* (`BackNavigationInference`) and navigation records "Go back (detected)" and replays it (verified 2/2). |
| Final 0.8.0 build — **12 scenarios × 2 runs** | **24 / 24 (100 %)** | 0 / 24 | After: harness reads the screen through RepeatKit's service (uiautomator was unbinding it), coalesced screen checks, id-less fields credited correctly, Settings' FallbackHome not a launcher, soft start-screen check after launch. text_views/text_compose re-run 2/2 after the last capture change. The harness's injected Enter still occasionally misses the field after a pause (reproduced with no recording running), so text_views can flake for harness reasons. |

### Real apps (Pixel 6a, Android 16, 2026-10-17)

Harmless flows only (navigation, search, typing; nothing sent, played or changed). Script: record like a person (RepeatKit → Home → launcher icon), save, return the app to its start state, replay from Home, check the end screen.

| Flow | Result | Notes |
|---|---|---|
| Settings → Battery | **2 / 2** (new format) | Earlier "passes" were legacy fallbacks: Settings' boot-time FallbackHome counted as a launcher, so Settings never compiled in the new format. |
| Settings → scroll → About phone | **2 / 2** (new format) | Failed earlier: snapshots dropped during launch (caused by the harness's `uiautomator dump` unbinding the service) → no launch step → legacy fallback. |
| Calculator 7 + 8 = | **2 / 2** (new format) | |
| YouTube Music → Library | **2 / 2** (new format) | Failed earlier: launch step had no app window (app still drawing) → legacy fallback; then the launch step demanded the personalised feed look as recorded. |
| YouTube Music → Search "lofi beats" → keyboard Search | 0.8.0: **8 / 11** since the tap-check fix; **1 / 3** on the final build. After the search-capture fix: see below. | 0.8.0: flaky. Typed through Gboard's keys it was worse than adb suggested (0 / 2): partial query plus early submit. Fixed on `fix/engine-v2-search-capture`. |
| Chrome → address bar → "repeatkit android" → Enter | 0.8.0: **0 / 2**. After the search-capture fix: see below. | 0.8.0: typed text never captured, only the tap. Fixed on `fix/engine-v2-search-capture`. |
| Google Messages | not run | Blocked by a mandatory Play Store update on the test phone. |

### Search capture fix (Pixel 6a, 2026-10-17, branch `fix/engine-v2-search-capture`)

The two 0.8.0 search gaps, re-measured on the final build of the fix with `tools/e2e/rk_realapps.py`. `_gboard` flows type by tapping Gboard's keys and press its action key (how people type); the others use `adb shell input text` and an injected Enter. Every run is counted.

| Flow | Result | Recorded workflow |
|---|---|---|
| Chrome → address bar → "repeatkit android" → Go (Gboard) | **10 / 10** | launch, tap `url_bar`, `set_text("repeatkit android")`, detected Search, in every run |
| Chrome, same flow (adb text + Enter) | **5 / 5** | same |
| YouTube Music → Search → "lofi beats" → Search (Gboard) | **23 / 25** (9/10, 4/5, 10/10) | launch, tap Search, `set_text("lofi beats")`, detected Search, in all 24 runs that got to record |
| YouTube Music, same flow (adb text + Enter) | **5 / 5** | same |
| Regression: Settings → About, Settings → Battery, Calculator 7+8=, YouTube Music → Library | **8 / 8** (2 each) | |
| Fixture suite, 12 scenarios × 2 | **24 / 24** | 0 recording issues |

The two YouTube Music failures were not recording or replay faults. In one, before anything was recorded, YouTube Music did not show its Search button within 30 s; the script now keeps a screenshot and screen dump when a run fails early. In the other, YouTube Music crashed with an `OutOfMemoryError` in its own process while replay was opening it, after about 45 relaunches of the same process (the test loop never force-stops it). The 10 runs after that crash all passed.

What was wrong (found with a temporary on-device probe of what the service sees):

1. **Chrome:** `url_bar` sends no text-change and no selection events, so its text can only be re-read with `findFocus`. Re-reading started on the tap, but the Pixel launcher keeps a system window updating while Chrome is open, and the recorder took each update as a switch to the launcher, which dropped the field. Background windows of other apps no longer count as app switches.
2. **Typing on the keyboard:** Gboard reports a window change on nearly every keystroke, and each one saved the field. One search became about a dozen commits, the capture queue fell 2–3 s behind, and an old "lo" commit was judged against a live screen that already showed results. Now the field is saved only when the keyboard closes, and a submit is never inferred while newer text is still queued. adb typing hides Gboard, which is why the earlier tests looked better than real use.
3. **Pausing before Search:** the 2 s submit window ran from the last keystroke (Chrome's Go came 3.7 s after it in these runs). It now runs from the last time the field was seen focused with the typed text and the keyboard up.

### Widgets (home screen, Pixel Launcher)

Pinned from the dashboard ("Add to homescreen" → launcher sheet): showed the shortcut ~3.5 s after "Add", never stuck on "Tap to set up". Tapping it ran the shortcut (Calculator showed 15). Renaming the shortcut updated the widget (~6 s incl. navigation). Deleting it warned "used by 1 homescreen widget" and the widget fell back to "Tap to set up"; choosing a shortcut there re-filled it. Lock screen: the provider declares `keyguard` (category 3) with an initial keyguard layout, but this Android build has no lock-screen widget host, so lock-screen placement is untested on device.

Caveats: the gate needs ≥ 600 runs (30 scenarios × 20); this is 12 × 5. The event diff ignores screen-open events, which is why the missing Back did not fail navigation. The WebView fixture's JavaScript bridge was broken (fixed on 2026-10-17), so earlier WebView "passes" only compared the opening tap.

---

## 4. Phases

### Phase 0 — Make reliability measurable *(built; awaiting on-device baseline)*

| Piece | Status |
|---|---|
| **RepeatKit Dev** build (`com.shortcuts.app.dev`) installs next to the real app, so testing never touches your real shortcuts | Built, installed |
| Debug-only remote control: start/stop/save/run/dump over `adb` (`okf-docs/debug-control.md`) | Built, installed |
| Accessibility "is it on?" check fixed so the two installs can't confuse each other | Fixed, tested |
| Fixture app (`okf-docs/fixture-app.md`) | Built, installed |
| Harness (`okf-docs/e2e-harness.md`) | Built, unit-tested; first device run pending |
| **Baseline scoreboard for v0.7.4** | Done — see the scoreboard above |

### Phase 1 — Capture layer *(prototype in progress)*

A production-quality capture overlay, wired into today's recorder first. That lets the harness measure the recording gain on its own, before the new replay engine lands. Then the five platform risks from the research are checked on the Pixel:

1. The relayed tap must never land on our own overlay.
2. The keyboard area stays native: the overlay shrinks above it.
3. Swipe velocity survives the relay, for both RecyclerView and Compose lists.
4. 3-button navigation, the status bar and the notification shade stay reachable.
5. Permission dialogs and "touch obscured" protections are not tripped.

**Exit:** recorded step lists match what the human did in ≥ 98 % of fixture runs, in both Views and Compose screens.

### Phase 2 — Engine v2 core *(foundation in progress)*

These are the spec's work packages ([`engine-v2-spec.md`](engine-v2-spec.md) Part 2 §7). Each is JVM-tested against fake accessibility trees before it touches the device.

| WP | What | Depends on |
|---|---|---|
| 1 | Versioned step/selector model, file format, legacy migration, core interfaces, fake-tree test kit | — *(running)* |
| 2 | Strict matcher: exact locators, scoring with contradictions, confidence floor 75, ambiguity margin 12, screen fingerprints | 1 |
| 3 | Recording compiler: turns captured touches + text + app switches into clean steps; rich selectors (ancestors, row context, sibling index) | 1 |
| 4 | Replay state machine: pick window → wait for expected screen → locate → scroll-to-find → act → **verify** | 1, 2 |
| 5 | Android adapters: tree snapshots, gesture dispatch, deterministic app launch (reset to the app's start screen) | 1, 4 |
| 6 | Run diagnostics: per-step trace, in-app "Replay history", export — no personal text stored | 1, 4 |

### Phase 3 — Cutover

- WP7: the recorder and the widget/run path switch to Engine v2 behind a flag.
- Old shortcuts are converted on load.
- The old matcher, replay executor and "Mark a tap" are deleted.

**Exit:** the reliability gate passes on the fixture suite with v2.

### Phase 4 — Real apps

- Run the four categories on your Pixel: Settings, Messages/WhatsApp, Spotify/YouTube Music, Chrome.
- Fix loop: harness failure → trace → targeted fix → re-run.
- Add your own flows as you name them.

**Exit:** the gate passes on the real-app suite.

### Phase 5 — The Shortcuts feel (editor rebuilt on v2 steps)

- Each recorded step shown as a card: "Tap **Wi-Fi** in Settings", with a cropped screenshot of what you tapped (taken at record time).
- "Test this step", "Re-record this step", and a repair view when replay can't find a target.
- Replay history with the failing step highlighted.
- Recording and Shortcuts-style actions mix in one editor.

### Phase 6 — Breadth

- Shortcuts-style action library and composition: variables, ask-for-input, conditions, repeat.
- Triggers.
- Optional Shizuku power mode.

### Phase 7 — Beta readiness

- Closed beta on a few devices.
- Play listing paperwork.
- Pricing decision.

---

## 5. Who does what

The orchestrator (Claude Code) reads, diagnoses, writes briefs, creates every branch/worktree, verifies independently, runs everything on the phone, and integrates. Delegated agents never commit, push, stash or touch the phone.

| Role | Executors | Used for |
|---|---|---|
| Research & design | Antigravity **Gemini 4 Argon High** (read-only plan mode), Codex **SOL** | Platform research, root-cause audits, specs, pre-merge spec-conformance reviews |
| Complex implementation | Codex **SOL**, Codex **Terra** | Foundation/contracts (WP1), capture layer, replay state machine (WP4), integration (WP7) |
| Workers | Codex **Luna**, Antigravity **Gemini 3.8 Flash** | Implementation against settled specs: WP2, WP3, WP5, WP6, scenarios, docs. Flash can run Gradle itself, so it takes the Android-adapter work. |
| Discretionary | Antigravity **Claude Opus 5.5 / Sonnet 5.5** | Second-opinion reviews and tricky refactors, once the connector is restarted |

Up to three writers run at once, each in its own worktree with an explicit file partition.

**Session so far:**

- **Done:** Argon (capture research), SOL (audit + spec), Flash (fixture app), Luna ×2 (Dev build + debug control; harness).
- **Running:** SOL (WP1 foundation), Terra (capture layer).

---

## 6. Safety rules

- Your phone is your daily driver. All testing uses **RepeatKit Dev**, never the real `com.shortcuts.app`. No `connected*` Gradle tasks without a database backup and your OK.
- Agents never change secure system settings on the phone. You switch accessibility services on yourself.
- Nothing is pushed. Branches stay local until you review them.

---

## 7. Open risks

- **Apps that resist automation entirely**: banking apps, games, secure windows. The engine should detect and say so rather than flail.
- **Restoring an app's start screen**: some apps restore their own navigation even after a task reset. The expected-screen check makes that a clear failure, not a wrong tap; per-app launch hints may be needed.
- **Keyboard edge cases**: floating/split keyboards and IMEs that don't report their bounds accurately (risk 2 above).
- **Toolchain age**: AGP 8.2.1, Kotlin 1.9.22, Compose BOM 2023.10. Fine for now; a separate modernisation lane later.
- **Pre-existing test failure**: `AutomationAccessibilityServiceReplayTest` fails on `main` already (the coordinate-first path NPEs under the JVM stub). It covers code Engine v2 replaces.
