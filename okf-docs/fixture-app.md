---
title: E2E Fixture App
type: architecture
author: antigravity_gemini_3_8_flash
tags: [testing, e2e, fixtures]
---

# E2E Fixture App (`:fixtures`)

## Overview
The `:fixtures` Gradle module provides a lightweight, deterministic, and completely offline Android fixture app (`com.repeatkit.fixtures`, app label "RK Fixtures") designed specifically as a test target for RepeatKit's on-device record and replay end-to-end tests.

It contains no business logic, no internet access, and no network permissions. Its purpose is to present stable, known UI structures (classic Android Views, Jetpack Compose, long scrolling lists, dialogs, bottom sheets, menus, text input, delayed elements, multi-step navigation flows, WebViews, and bottom tabs) and record every user interaction through a structured logging contract.

## Event Logging Contract

Every user interaction with a fixture UI element calls:
```kotlin
FixtureLog.event(scenario: String, event: String, target: String, value: String? = null)
```

This helper performs two actions synchronously:
1. **Logcat JSON Output**: Emits a single-line JSON payload to logcat under the tag `FixtureEvent`:
   ```json
   {"scenario":"views_basic","event":"tap","target":"btn_save","seq":1}
   ```
   When `value` is present:
   ```json
   {"scenario":"text_views","event":"submit","target":"edit_search","value":"query","seq":2}
   ```
   - `seq`: Monotonically increasing `Int` per process, starting at 1.
   - `value`: Omitted from JSON when `null`.
2. **On-Screen Event Footer**: Appends a one-line human-readable entry (`#<seq> [<scenario>] <event> -> <target> (<value>)`) to the on-screen footer at the bottom of the active screen, retaining the last 5 entries.

### Event Types
- `open`: A scenario screen became visible (target = scenario or screen id).
- `tap`: Primary click / tap on a target element.
- `long_press`: Long click on a target element.
- `toggle`: Switch or checkbox state change (`value = "on"` or `"off"`).
- `submit`: IME action (e.g. search) or submit button pressed (`value = submitted text`).
- `text`: Final text value logged on focus loss or submission (never logged per keystroke).
- `select`: Choice from a dialog, bottom sheet, popup menu, or navigation tab (`target = option machine id`).
- `nav`: Screen transition triggered (`target = destination scenario or screen id`).
- `scroll_reached`: Sentinel row entered the visible area during scrolling (`target = row label`).

### Target Naming
All targets use short, stable machine IDs (e.g. `btn_save`, `row_45`, `opt_ok`) rather than dynamic or localized display text.

## Scenarios & Targets

### 1. `views_basic` ("Views basic")
Classic Android Views UI:
- `btn_save`: Button with text "Save" (`tap`).
- `btn_gear`: ImageButton with content description "Settings gear" and no text (`tap`).
- `icon_unlabeled`: ImageButton with no text and no content description (`tap`).
- `chk_remember`: CheckBox with text "Remember me" (`toggle`, value = `"on"` / `"off"`).
- `sw_wifi`: SwitchMaterial with text "Wi-Fi" (`toggle`, value = `"on"` / `"off"`).
- `txt_hold`: TextView with text "Hold me" (`tap` on click, `long_press` on long click).

### 2. `compose_basic` ("Compose basic")
Pure Jetpack Compose UI without resource IDs:
- `btn_save`: Button "Save" (`tap`).
- `btn_gear`: IconButton with content description "Settings gear" (`tap`).
- `icon_unlabeled`: 64dp clickable Box with no label or semantics (`tap`).
- `sw_wifi`: Row with label "Wi-Fi" and Switch (`toggle`, value = `"on"` / `"off"`).
- `txt_hold`: Text "Hold me" with `combinedClickable` (`tap` on click, `long_press` on long click).
- Uses `Modifier.testTag` without exposing `testTagsAsResourceId`.

### 3. `views_list` ("Views list")
A RecyclerView containing 60 rows (`1..60`):
- `row_i`: Tapping the row body (`tap`).
- `row_i_open`: Tapping the trailing "Open" button on row `i` (`tap`).
- `row_45`: Sentinel event `scroll_reached` logged the first time row 45 is bound.

### 4. `compose_list` ("Compose list")
A LazyColumn containing 60 rows (`1..60`):
- `row_i`: Tapping the row body (`tap`).
- `row_i_open`: Tapping the trailing "Open" button on row `i` (`tap`).
- `row_45`: Sentinel event `scroll_reached` logged via `LaunchedEffect` when row 45 enters composition.

### 5. `near_duplicates` ("Near duplicates")
Three vertically stacked Views buttons with similar labels to test selector disambiguation:
1. `btn_internet_sim`: Button "Internet & SIM" (`tap`).
2. `btn_internet`: Button "Internet" (`tap`).
3. `btn_internet_settings`: Button "Internet settings" (`tap`).

### 6. `text_views` ("Text input views")
Classic Android EditText views:
- `edit_search`: Single-line EditText with hint "Search" and IME action search (`submit` on IME action with search text, `text` on focus loss).
- `edit_message`: EditText with hint "Message" (`text` on focus loss).
- `btn_send`: Button "Send" (`submit` target `edit_message` with value, clears field).

### 7. `text_compose` ("Text input compose")
Compose text fields:
- `field_search`: OutlinedTextField with label "Search" and `ImeAction.Search` (`submit` on search action, `text` on focus loss).
- `field_message`: TextField with label "Message" (`text` on focus loss).
- `btn_send`: Button "Send" (`submit` target `field_message` with value, clears field).

### 8. `dialogs` ("Dialogs")
Dialog, sheet, and menu interactions:
- `btn_dialog`: Button "Show dialog" (`tap`). Opens AlertDialog "Confirm":
  - `opt_ok`: "OK" button (`select`).
  - `opt_cancel`: "Cancel" button (`select`).
- `btn_sheet`: Button "Show sheet" (`tap`). Opens Material BottomSheetDialog:
  - `opt_share`: "Share" row (`select`).
  - `opt_copy`: "Copy link" row (`select`).
  - `opt_delete`: "Delete" row (`select`).
- `btn_menu`: Button "Show menu" (`tap`). Opens PopupMenu:
  - `opt_rename`: "Rename" item (`select`).
  - `opt_archive`: "Archive" item (`select`).

### 9. `delayed` ("Delayed content")
Timed element appearance:
- Shows a `ProgressBar` for 2500 ms, then reveals Button "Continue" (`btn_continue`, `tap`).
- Tapping "Continue" logs `nav` target `step_two` and opens `DelayedStepTwoActivity`.
- `step_two`: Logs `open` on appearance. Contains Button "Done" (`btn_done`, `tap`) which finishes the activity.

### 10. `navigation` ("Navigation")
A 3-level Activity navigation flow:
1. `nav_home` (Home screen): Logs `open` target `nav_home`. Button "Profile" (`btn_profile`, `tap`) opens Profile.
2. `nav_profile` (Profile screen): Logs `open` target `nav_profile`. Button "Edit" (`btn_edit`, `tap`) opens Edit profile.
3. `nav_edit` (Edit profile screen): Logs `open` target `nav_edit`.
   - `edit_name`: EditText with hint "Name" (`text` on focus loss).
   - `btn_profile_save`: Button "Save" (`tap`) finishes back to Profile.

### 11. `webview` ("Web view")
WebView loading local bundled asset `file:///android_asset/fixture.html` with JavaScript enabled and `FixtureBridge` interface:
- `web_accept`: HTML button "Accept" (`tap`).
- `web_next`: HTML button "Next" (`tap`).
- `web_email`: HTML input with placeholder "Email" (`text` on change).

### 12. `tabs` ("Tabs")
Material BottomNavigationView with three tabs:
- `tab_feed`: Bottom navigation item "Feed" (`select`).
- `tab_search`: Bottom navigation item "Search" (`select`).
- `tab_profile`: Bottom navigation item "Profile" (`select`).
- Button "Like" updates target based on active tab: `btn_like_feed`, `btn_like_search`, `btn_like_profile` (`tap`).

## Launching the App

### Launcher Activity
Launch the main scenario selector list:
```bash
adb shell am start -n com.repeatkit.fixtures/.MainActivity
```

### Direct Scenario Launch
Launch a specific scenario directly via the `scenario` string extra:
```bash
adb shell am start -n com.repeatkit.fixtures/.MainActivity --es scenario compose_basic
```
Valid scenario arguments: `views_basic`, `compose_basic`, `views_list`, `compose_list`, `near_duplicates`, `text_views`, `text_compose`, `dialogs`, `delayed`, `navigation`, `webview`, `tabs`.
