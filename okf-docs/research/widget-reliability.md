# RepeatKit Widget Reliability & Lock-Screen Research Report

---

## Executive Summary & Severity-Ranked Defect Table

| Rank | Severity | Defect Summary | Primary File:Line |
| :--- | :--- | :--- | :--- |
| **D1** | **CRITICAL** | [`UnifiedShortcutWidget.provideGlance`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L88-L113) queries Room **once before `provideContent`** and captures immutable `val`s (`config`, `shortcuts`, `layout`). In Glance 1.0.0, `AppWidgetSession` stays alive in a WorkManager worker for **45–50 seconds**; calling `ShortcutWidget().update()` or `updateAll()` while the session is open **never re-invokes `provideGlance`**, making every refresh during that 45s window (including the post-config and post-pin refreshes) a no-op. | [UnifiedShortcutWidget.kt:88-113](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L88-L113), [ShortcutWidgetRefresh.kt:23-41](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetRefresh.kt#L23-L41) |
| **D2** | **CRITICAL** | In-app "Create Custom Widget" ([`CreateWidgetScreen.requestLauncherPin`](../../app/src/main/java/com/shortcuts/app/ui/screens/CreateWidgetScreen.kt#L451-L464)) calls `appWidgetManager.requestPinAppWidget(provider, null, null)` with a **`null` `successCallback`**, and [`CustomWidgetViewModel.saveWidget`](../../app/src/main/java/com/shortcuts/app/viewmodel/CustomWidgetViewModel.kt#L218-L225) discards the inserted `CustomWidgetTemplate` row ID. Widgets pinned from `CreateWidgetScreen` **never receive a `WidgetConfig` row** (landing on `"Tap to set up"`), and custom labels/colors/icons in `custom_widget_templates` are completely unreachable. | [CreateWidgetScreen.kt:451-464](../../app/src/main/java/com/shortcuts/app/ui/screens/CreateWidgetScreen.kt#L451-L464), [CustomWidgetViewModel.kt:205-232](../../app/src/main/java/com/shortcuts/app/viewmodel/CustomWidgetViewModel.kt#L205-L232) |
| **D3** | **HIGH** | [`ShortcutWidgetConfigActivity`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L180-L185) pre-selects **all** shortcuts (up to 6) in Room table order, ignores existing `WidgetConfig` when reconfiguring, and loses state on rotation/process death. Because [`UnifiedShortcutWidget`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L155-L159) renders `shortcuts.first()` for `SINGLE` and default `2x2` `AUTO` sizes, tapping a desired shortcut chip in the config screen **deselects** it (or leaves Shortcut #1 first), causing the widget to show the wrong shortcut. | [ShortcutWidgetConfigActivity.kt:180-185](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L180-L185), [UnifiedShortcutWidget.kt:154-160](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L154-L160) |
| **D4** | **HIGH** | [`UnifiedShortcutWidget.provideGlance`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L88-L138) executes unguarded `getAppWidgetId(id)` and N+1 sequential Room queries before `provideContent` without `try/catch` or `onCompositionError`, while [`shortcut_widget_info.xml`](../../app/src/main/res/xml/shortcut_widget_info.xml#L12) uses `@layout/glance_default_loading_layout`. Any exception or worker cancellation before first composition leaves the widget stuck on `glance_default_loading_layout` indefinitely. | [UnifiedShortcutWidget.kt:88-138](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L88-L138), [shortcut_widget_info.xml:12](../../app/src/main/res/xml/shortcut_widget_info.xml#L12) |
| **D5** | **HIGH** | Engine v2 workflows store a JSON object (`{"schemaVersion":2,...}`) in `Automation.actionsJson`, but [`ActionConverter.toActionList`](../../app/src/main/java/com/shortcuts/app/data/Automation.kt#L87-L93) expects a JSON array and catches `JsonSyntaxException`, returning `emptyList()`. This breaks step counts (`"0 steps"`) in [`ShortcutWidgetConfigActivity`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L509-L512) and [`DashboardScreen`](../../app/src/main/java/com/shortcuts/app/ui/screens/DashboardScreen.kt#L677-L680), **bypasses the irreversible-action safety confirmation** on Dashboard runs ([`DashboardScreen.kt:92-95`](../../app/src/main/java/com/shortcuts/app/ui/screens/DashboardScreen.kt#L92-L95)), and wipes steps when opening [`ManualBuilderScreen`](../../app/src/main/java/com/shortcuts/app/ui/screens/ManualBuilderScreen.kt#L380-L383). | [Automation.kt:87-93](../../app/src/main/java/com/shortcuts/app/data/Automation.kt#L87-L93), [ShortcutWidgetConfigActivity.kt:509-512](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L509-L512), [DashboardScreen.kt:92-95](../../app/src/main/java/com/shortcuts/app/ui/screens/DashboardScreen.kt#L92-L95) |
| **D6** | **MEDIUM-HIGH** | [`AppDatabase.installWidgetRefreshObserver`](../../app/src/main/java/com/shortcuts/app/data/AppDatabase.kt#L39-L47) only watches `"automations"` (ignoring `"widget_configs"` and `"custom_widget_templates"`), calls `ShortcutWidget().updateAll(context)` on unserialized `Dispatchers.IO` coroutines without `runCatching` or `updateAppWidgetOptions` nudge ([`ShortcutWidgetUpdater.kt:7-11`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetUpdater.kt#L7-L11)), and deleting an `Automation` does not prune its ID from `widget_configs.automationIdsJson`. | [AppDatabase.kt:39-47](../../app/src/main/java/com/shortcuts/app/data/AppDatabase.kt#L39-L47), [ShortcutWidgetUpdater.kt:7-11](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetUpdater.kt#L7-L11) |
| **D7** | **MEDIUM** | [`ShortcutGrid`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L226) constrains each cell to `height(52.dp)` while [`ShortcutTile(compact = true)`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L192-L218) requires ~`71.dp` (`12.dp` padding + `36.dp` icon + `5.dp` spacer + `18.dp` text), clipping or hiding shortcut labels in Grid widgets. [`ShortcutList`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L239) also omits stable `itemId`s in `LazyColumn`. | [UnifiedShortcutWidget.kt:192-265](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L192-L265) |
| **D8** | **MEDIUM** | [`shortcut_widget_info.xml`](../../app/src/main/res/xml/shortcut_widget_info.xml#L1-L13) omits `android:updatePeriodMillis` (defaulting to `0`, so the OS never periodically recovers stuck widgets), [`ShortcutWidgetConfigActivity.onCreate`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L84) omits `EXTRA_APPWIDGET_ID` in its initial `setResult(RESULT_CANCELED)`, and [`openManualBuilder()`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L163-L169) calls `finish()` with `RESULT_CANCELED`, aborting widget placement when the user taps "Create a shortcut". | [shortcut_widget_info.xml:1-13](../../app/src/main/res/xml/shortcut_widget_info.xml#L1-L13), [ShortcutWidgetConfigActivity.kt:84-97](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L84-L97) |

---

## 1. Why Home-Screen Widgets Stay on `glance_default_loading_layout`, Render Blank/Stale, or Show the Wrong Shortcut

### 1.1 `provideGlance` Work Done Before `provideContent` + Glance 1.0.0's 45-Second `AppWidgetSession` Trap (Defect D1)
Look at [`UnifiedShortcutWidget.provideGlance`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L88-L113):

```kotlin
// app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt:88-113
override suspend fun provideGlance(context: Context, id: GlanceId) {
    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
    val db = AppDatabase.getDatabase(context)
    val config = withContext(Dispatchers.IO) {
        db.widgetConfigDao().getConfig(appWidgetId, WIDGET_TYPE_UNIFIED)
    }
    val shortcuts = withContext(Dispatchers.IO) {
        resolveShortcuts(db, config)
    }
    val layout = WidgetLayoutKey.fromKey(config?.layoutKey)
    val configIntent = ShortcutWidgetConfigActivity.createIntent(context, appWidgetId)

    provideContent {
        GlanceTheme {
            WidgetSurface(shortcuts = shortcuts, layout = layout, configIntent = configIntent)
        }
    }
}
```

**Why this breaks in `androidx.glance:glance-appwidget:1.0.0`:**
1. When Android sends `ACTION_APPWIDGET_UPDATE` (immediately upon dropping a widget from the launcher or calling `requestPinAppWidget`), `GlanceAppWidgetReceiver` starts a WorkManager `SessionWorker` and opens an `AppWidgetSession` for `AppWidgetId(appWidgetId)`. That session remains open for **45–50 seconds** (`TimeoutOptions.initialTimeout = 45.seconds`).
2. `AppWidgetSession.provideGlance()` invokes `UnifiedShortcutWidget.provideGlance(context, id)` **exactly once when the session starts**. Inside `provideGlance()`, `provideContent { ... }` registers the composable lambda with the session's `Recomposer` and suspends via `awaitCancellation()` until the 45-second session closes.
3. While that 45-second `AppWidgetSession` is active, **calling `ShortcutWidget().update(context, glanceId)` or `ShortcutWidget().updateAll(context)` ([`ShortcutWidgetRefresh.kt:31, 53`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetRefresh.kt#L31)) does NOT re-call `provideGlance()`**! Instead, `AppWidgetSession.updateGlance()` sends `SessionEvent.UpdateGlance` into the running session's channel. In `AppWidgetSession.processEvent(UpdateGlance)`, Glance reloads `glanceState` from `widget.stateDefinition` (`PreferencesGlanceStateDefinition`) and triggers a Compose snapshot invalidation.
4. Because `UnifiedShortcutWidget` **never reads `currentState()`** and **never collects a Room `Flow` inside `provideContent { ... }`**, recomposition inside `provideContent` either does not happen at all or re-runs `WidgetSurface(shortcuts = shortcuts, ...)` with the **exact same immutable `val shortcuts` and `val layout` captured before `provideContent` when the session first opened**!
5. Even the launcher-nudge workaround in [`ShortcutWidgetRefresh.kt:37-39`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetRefresh.kt#L37-L39) (`appWidgetManager.updateAppWidgetOptions(appWidgetId, options)`) sends `ACTION_APPWIDGET_OPTIONS_CHANGED`, which Glance routes as `UpdateAppWidgetOptions` into the **same active 45-second `AppWidgetSession`**, updating `LocalSize` and recomposing `provideContent` with the **same stale `shortcuts = emptyList()`**.

**Concrete failure sequence during Pinning or Fast Configuration:**
- **Step 1 (`t = 0 ms`)**: User pins a widget from `DashboardScreen` (or drops it on the launcher). Pixel Launcher allocates `appWidgetId` and immediately dispatches `ACTION_APPWIDGET_UPDATE` to [`ShortcutWidgetReceiver`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetReceiver.kt#L11).
- **Step 2 (`t = 30 ms`)**: `SessionWorker` starts `AppWidgetSession` for `appWidgetId`. `provideGlance` runs *before* [`ShortcutWidgetPinReceiver`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetPinReceiver.kt#L19) (or before `ShortcutWidgetConfigActivity` finishes if the user configures within 45s). `db.widgetConfigDao().getConfig(appWidgetId, "UNIFIED")` returns `null`. `shortcuts` is captured as `emptyList()`. The widget renders `NeedsSetup` (`"Tap to set up"`).
- **Step 3 (`t = 150 ms` or `t = 5 s`)**: [`ShortcutWidgetPinReceiver.onReceive`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetPinReceiver.kt#L33-L36) (or [`ShortcutWidgetConfigActivity.saveSelection`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L131-L138)) writes the `WidgetConfig` row to Room and calls `refreshShortcutWidget(appContext, appWidgetId)`.
- **Step 4**: `refreshShortcutWidget` calls `ShortcutWidget().update(context, glanceId)` and `updateAppWidgetOptions(...)`. Both events hit the *already running* 45s `AppWidgetSession` where `shortcuts` is permanently bound to `emptyList()`.
- **Result**: The widget remains stuck on `"Tap to set up"` (or its previous config) for the remainder of the 45s session — and because `updatePeriodMillis` is `0`, nothing triggers a new session after the 45s worker expires unless the user re-opens `MainActivity` (`onStart` calls [`refreshAllShortcutWidgets`](../../app/src/main/java/com/shortcuts/app/ui/MainActivity.kt#L59-L63)).

---

### 1.2 Why Widgets Stay Stuck on `glance_default_loading_layout` (Defect D4)
[`shortcut_widget_info.xml:12`](../../app/src/main/res/xml/shortcut_widget_info.xml#L12) sets:
```xml
android:initialLayout="@layout/glance_default_loading_layout"
```
Android's `AppWidgetHostView` displays `initialLayout` from the moment the widget is bound until `AppWidgetManager.updateAppWidget(appWidgetId, remoteViews)` is called by Glance's `AppWidgetSession` after `provideContent` completes its first composition pass.

A widget stays stuck on `glance_default_loading_layout` whenever:
1. **Slow cold-start + N+1 Room queries before `provideContent`**:
   - In [`UnifiedShortcutWidget.kt:90-138`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L90-L138), `provideGlance` initializes the singleton Room database (`AppDatabase.getDatabase(context)` with 8 migrations), runs `getConfig`, runs `customWidgetTemplateDao().getById(...)`, and then loops over up to 6 `automationIds` executing **separate sequential `db.automationDao().getAutomationById(id)` queries** (`lines 122-129`) *before* `provideContent` is ever called.
2. **Overlapping `updateAll()` / `update()` calls cancelling `SessionWorker` mid-flight**:
   - When the user saves in `ShortcutWidgetConfigActivity` (`line 138`) and returns to [`MainActivity.onStart`](../../app/src/main/java/com/shortcuts/app/ui/MainActivity.kt#L60-L62), OR when `AppDatabase`'s `InvalidationTracker` ([`AppDatabase.kt:40-46`](../../app/src/main/java/com/shortcuts/app/data/AppDatabase.kt#L40-L46)) fires simultaneously with an explicit `refreshShortcutWidget`, multiple coroutines call `ShortcutWidget().update(...)` / `updateAll(...)` concurrently.
   - In Glance 1.0.0, `SessionWorker` uses `ExistingWorkPolicy.REPLACE` under the hood when sessions restart or time out; if a worker is cancelled before `provideContent` emits its first `RemoteViews`, the widget remains on `glance_default_loading_layout`.
3. **Uncaught exceptions in `provideGlance` and no `onCompositionError` override**:
   - [`UnifiedShortcutWidget.kt:89`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L89): `GlanceAppWidgetManager(context).getAppWidgetId(id)` throws `IllegalArgumentException` if `id` is not an `AppWidgetId`.
   - Neither `provideGlance` nor `resolveShortcuts` wraps DB/JSON operations in `runCatching`, and `UnifiedShortcutWidget` does not override `onCompositionError(...)`. When `provideGlance` throws before `provideContent`, Glance 1.0.0 logs the exception in `AppWidgetSession` and terminates the session without pushing a fallback `RemoteViews`, leaving `glance_default_loading_layout` on screen forever.
4. **`updatePeriodMillis` is omitted (`0`) in [`shortcut_widget_info.xml`](../../app/src/main/res/xml/shortcut_widget_info.xml#L1-L13)**:
   - Because `android:updatePeriodMillis` is not declared, it defaults to `0`. If the initial `SessionWorker` fails, is killed by Android 16's job quota / battery restrictions while backgrounded, or hits Glance 1.0.0's 45s session timeout before rendering, the OS never sends another `ACTION_APPWIDGET_UPDATE`.

---

### 1.3 Why Widgets Show the Wrong Shortcut (Defect D3)
In [`ShortcutWidgetConfigActivity.kt:180-185`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L180-L185):
```kotlin
var selectedIds by remember(shortcuts) {
    mutableStateOf(shortcuts.orEmpty().take(MAX_SHORTCUTS_PER_WIDGET).map { it.id }.toSet())
}
val selectedShortcuts = shortcuts.orEmpty().filter { it.id in selectedIds }
var selectedLayout by remember { mutableStateOf(WidgetLayoutKey.AUTO) }
```
And in [`ShortcutWidgetConfigActivity.kt:247-253`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L247-L253):
```kotlin
onToggleShortcut = { id ->
    selectedIds = if (id in selectedIds) {
        selectedIds - id
    } else if (selectedIds.size < MAX_SHORTCUTS_PER_WIDGET) {
        selectedIds + id
    } else {
        selectedIds
    }
}
```
And in [`UnifiedShortcutWidget.kt:153-160`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L153-L160):
```kotlin
when (layout) {
    WidgetLayoutKey.SINGLE -> ShortcutTile(shortcuts.first(), compact = true)
    WidgetLayoutKey.GRID -> ShortcutGrid(shortcuts.take(4))
    WidgetLayoutKey.LIST -> ShortcutList(shortcuts)
    WidgetLayoutKey.AUTO -> when {
        shortcuts.size == 1 || (size.width < 180.dp || size.height < 150.dp) ->
            ShortcutTile(shortcuts.first(), compact = true)
        shortcuts.size <= 4 && size.height < 210.dp ->
            ShortcutGrid(shortcuts.take(4))
        else -> ShortcutList(shortcuts)
    }
}
```
Look at what happens when a user has 3 shortcuts (`#1 Morning`, `#2 Work`, `#3 Gym`) and adds a widget:
1. **All 3 shortcuts start pre-selected** (`selectedIds = {1, 2, 3}`).
2. **Tapping `#3 Gym` DESELECTS `#3 Gym`!** Because `#3` was already in `selectedIds`, tapping its chip removes it (`selectedIds = {1, 2}`).
3. Even if the user doesn't tap `#3` (or taps `#3` twice so `{1, 2, 3}` are selected) and chooses `Single button` (`WidgetLayoutKey.SINGLE`) or keeps the default `Adaptive` (`WidgetLayoutKey.AUTO`) at the default `2x2` widget size (`110dp x 110dp` in [`shortcut_widget_info.xml:3-6`](../../app/src/main/res/xml/shortcut_widget_info.xml#L3-L6), where `size.width < 180.dp || size.height < 150.dp` is true on a Pixel 6a's 4x5/5x5 grid):
   - `selectedShortcuts = shortcuts.orEmpty().filter { it.id in selectedIds }` preserves **Room table order** (`SELECT * FROM automations`), NOT tap order.
   - `UnifiedShortcutWidget` renders `shortcuts.first()`, which is **always `#1 Morning`** unless the user manually deselects `#1 Morning` and `#2 Work` first!
4. **Reconfiguring an existing widget forgets its current configuration**:
   - [`shortcut_widget_info.xml:9`](../../app/src/main/res/xml/shortcut_widget_info.xml#L9) declares `android:widgetFeatures="reconfigurable"`.
   - When the user long-presses an existing widget on Pixel Launcher and taps the reconfigure (pencil) button, [`ShortcutWidgetConfigActivity`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L107-L118) only loads `automationDao.getAllAutomations().first()` and **never reads `widgetConfigDao.getConfig(appWidgetId, "UNIFIED")`**. It resets `selectedIds` back to the first 6 shortcuts and `selectedLayout` back to `AUTO`.
5. **Process death / rotation during configuration**:
   - `selectedIds` (`line 180`) and `selectedLayout` (`line 185`) use `remember` instead of `rememberSaveable`, so rotating the phone or backgrounding during configuration resets the user's choices.

---

### 1.4 Config-Activity Result Handling, `onDeleted` Cleanup, and Receiver `goAsync` Audit
- **`setResult(RESULT_CANCELED)` and `RESULT_OK` ordering**:
  - [`ShortcutWidgetConfigActivity.kt:84`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L84) calls `setResult(RESULT_CANCELED)` **without** attaching `Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)`. Per the Android `AppWidgetManager` contract, the cancel result intent should include `EXTRA_APPWIDGET_ID` so the launcher host is guaranteed to deallocate the tentative `appWidgetId` on back-press.
  - In [`ShortcutWidgetConfigActivity.confirmSelection`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L153-L161), `store.saveSelection(...)` (which writes Room and calls `refreshShortcutWidget`) completes inside `lifecycleScope.launch` before `setResult(RESULT_OK, result)` and `finish()`. That ordering is good, *except* that `refreshShortcutWidget` hits Defect D1 (the 45s `AppWidgetSession` opened when the launcher bound `appWidgetId` before launching the config activity is still open with `shortcuts = emptyList()`).
  - In [`ShortcutWidgetConfigActivity.openManualBuilder`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L163-L169), tapping "Create a shortcut" launches `MainActivity` and immediately calls `finish()` while the result is still `RESULT_CANCELED`, **aborting the widget creation** in the launcher.
- **`GlanceAppWidgetManager` ID mapping & widget state keying**:
  - [`ShortcutWidgetRefresh.kt:23-29`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetRefresh.kt#L23-L29) resolves `glanceId` by calling `manager.getGlanceIds(ShortcutWidget::class.java).firstOrNull { manager.getAppWidgetId(it) == appWidgetId }` and falling back to `manager.getGlanceIdBy(Intent().putExtra(EXTRA_APPWIDGET_ID, appWidgetId))`.
  - Room state in `widget_configs` ([`WidgetConfig.kt:13-22`](../../app/src/main/java/com/shortcuts/app/data/WidgetConfig.kt#L13-L22)) is keyed by `(widgetId = appWidgetId, widgetType = "UNIFIED")`, which is consistent between `ShortcutWidgetConfigActivity`, `ShortcutWidgetPinReceiver`, and `UnifiedShortcutWidget`.
- **`onDeleted` cleanup & `goAsync` usage**:
  - [`ShortcutWidgetReceiver.onDeleted`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetReceiver.kt#L15-L28) calls `super.onDeleted(context, appWidgetIds)` first and then uses `goAsync()` to delete only the `appWidgetIds` passed in that broadcast (`WidgetCleanupHelper.cleanupDeletedWidgets(db, appWidgetIds.toList())`). Note: in `BroadcastReceiver`, `super.onDeleted(...)` in `GlanceAppWidgetReceiver` already calls `goAsync()` internally! Calling `goAsync()` a second time in the same `onReceive` dispatch on Android can overwrite or conflict with the base receiver's `PendingResult`. Instead of calling `goAsync()` a second time after `super.onDeleted()`, `ShortcutWidgetReceiver.onDeleted` should launch cleanup on an application-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.

---

## 2. The Pin Flow (`requestPinAppWidget` + `ShortcutWidgetPinReceiver`)

RepeatKit has **two separate in-app widget pinning entry points**, and they behave very differently:

### Path A: Pinning from a Shortcut Card's `⋮ -> Add widget` Menu ([`DashboardScreen.kt:129-144`](../../app/src/main/java/com/shortcuts/app/ui/screens/DashboardScreen.kt#L129-L144))
- Calls [`ShortcutWidgetPinRequest.requestPin(context, shortcut.id)`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetPinRequest.kt#L12-L26):
  ```kotlin
  // app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetPinRequest.kt:18-25
  val callbackIntent = Intent(context, ShortcutWidgetPinReceiver::class.java).apply {
      putExtra(EXTRA_AUTOMATION_ID, automationId)
  }
  val successCallback = PendingIntent.getBroadcast(
      context,
      automationId,
      callbackIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
  )
  return appWidgetManager.requestPinAppWidget(provider, null, successCallback)
  ```
- When the user confirms the pin in Pixel Launcher's bottom sheet:
  1. Pixel Launcher binds the widget ID and immediately sends `ACTION_APPWIDGET_UPDATE` to [`ShortcutWidgetReceiver`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetReceiver.kt#L11).
  2. Pixel Launcher fires `successCallback` (`ShortcutWidgetPinReceiver`) with `AppWidgetManager.EXTRA_APPWIDGET_ID` populated (because `FLAG_MUTABLE` is set on `PendingIntent`).
  3. [`ShortcutWidgetPinReceiver.onReceive`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetPinReceiver.kt#L33-L36) writes `WidgetConfig(widgetId = appWidgetId, widgetType = "UNIFIED", automationId = automationId, layoutKey = "SINGLE")` to Room and calls `refreshShortcutWidget(appContext, appWidgetId)`.
- **Does it render without the user re-opening anything? NO — it is race-prone due to Defect D1!**
  - Because Step 1 (`ACTION_APPWIDGET_UPDATE`) races ahead of Step 3 (`ShortcutWidgetPinReceiver`'s Room insert), `UnifiedShortcutWidget.provideGlance` almost always reads `config == null` when opening the 45-second `AppWidgetSession` and captures `shortcuts = emptyList()`.
  - When Step 3 calls `refreshShortcutWidget(appContext, appWidgetId)` ~50–150ms later, `ShortcutWidget().update()` sends `UpdateGlance` to the *already open* 45-second session, which does **not** re-run `provideGlance()`.
  - Furthermore, if the user stays inside RepeatKit for a few seconds and then presses Home to view the widget, `MainActivity.onStart` is not called again until they return to the app after the 45-second session has expired.

### Path B: Pinning from the "Create Custom Widget" Screen ([`CreateWidgetScreen.kt:451-464`](../../app/src/main/java/com/shortcuts/app/ui/screens/CreateWidgetScreen.kt#L451-L464)) — **100% Broken (Defect D2)**
- Look at [`CreateWidgetScreen.kt:451-464`](../../app/src/main/java/com/shortcuts/app/ui/screens/CreateWidgetScreen.kt#L451-L464):
  ```kotlin
  // app/src/main/java/com/shortcuts/app/ui/screens/CreateWidgetScreen.kt:451-459
  private fun requestLauncherPin(context: Context): String {
      val appWidgetManager = AppWidgetManager.getInstance(context)
      if (!appWidgetManager.isRequestPinAppWidgetSupported) {
          return context.getString(R.string.widget_pin_not_supported_toast)
      }
      val provider = ComponentName(context, ShortcutWidgetReceiver::class.java)
      val requested = appWidgetManager.requestPinAppWidget(provider, null, null)
  ```
- And look at [`CustomWidgetViewModel.kt:205-232`](../../app/src/main/java/com/shortcuts/app/viewmodel/CustomWidgetViewModel.kt#L205-L232):
  ```kotlin
  // app/src/main/java/com/shortcuts/app/viewmodel/CustomWidgetViewModel.kt:218-226
  templateDao.insert(
      CustomWidgetTemplate(
          label = trimmedLabel,
          colorKey = current.colorKey.key,
          iconKey = current.iconKey.key,
          automationId = automationId
      )
  )
  _uiState.update { it.copy(isSaving = false, savedSuccess = true) }
  ```
- **Why this never works:**
  1. `requestLauncherPin` passes `null` for `successCallback` instead of calling `ShortcutWidgetPinRequest`.
  2. `CustomWidgetViewModel.saveWidget` inserts a `CustomWidgetTemplate` into `custom_widget_templates` and discards the returned `Long` row ID (`templateId`).
  3. When the user taps "Add to Home screen" in Pixel Launcher's pin sheet, Android places the widget with a new `appWidgetId`, never invokes `ShortcutWidgetPinReceiver`, and never launches `ShortcutWidgetConfigActivity`.
  4. **No `WidgetConfig` row is ever created for that `appWidgetId`.** The widget is guaranteed to land on the home screen displaying `"Tap to set up"`.
  5. Even if the user taps `"Tap to set up"` to open `ShortcutWidgetConfigActivity`, `ShortcutWidgetConfigActivity` never queries `custom_widget_templates` (`templateId`, `label`, `colorKey`, `iconKey`), so the custom label, color, and icon the user just designed in `CreateWidgetScreen` are completely orphaned in the database!

---

## 3. Shortcut Mutations (Rename / Delete / Re-color) & Engine v2 `actionsJson` Format

### 3.1 Do Widgets Refresh When a Shortcut is Renamed, Deleted, or Re-colored?
- **Database Observer**: [`AppDatabase.installWidgetRefreshObserver`](../../app/src/main/java/com/shortcuts/app/data/AppDatabase.kt#L39-L47) registers a Room `InvalidationTracker.Observer("automations")` that calls [`ShortcutWidgetUpdater.refreshAll(context)`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetUpdater.kt#L7-L11) (`ShortcutWidget().updateAll(context)`).
- **What works and what fails:**
  1. **Active 45s Glance session (Defect D1)**: If the user tapped a widget or configured it within the last 45 seconds and then edits/renames/deletes the shortcut in the app, `ShortcutWidget().updateAll(context)` sends `UpdateGlance` to the still-open `AppWidgetSession`, which **does not re-run `provideGlance`**, leaving the widget showing the old name/color or deleted shortcut!
  2. **Custom Widget Template overrides**: In [`UnifiedShortcutWidget.resolveShortcuts`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L130-L137), `label` prefers `config.label?.takeIf { it.isNotBlank() } ?: template?.label?.takeIf { it.isNotBlank() } ?: automation.name`. If a widget has a non-blank `config.label` or `template.label`, renaming `automation.name` will intentionally not override the custom widget label.
  3. **Shortcut Color**: `Automation` does not have a color column in Room ([`Automation.kt:9-23`](../../app/src/main/java/com/shortcuts/app/data/Automation.kt#L9-L23)); widget tile color comes from `config.colorKey ?: template?.colorKey` (defaulting to `TEAL`) in [`UnifiedShortcutWidget.kt:134`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L134).
  4. **Deleting a Shortcut (Defect D6)**:
     - [`AutomationViewModel.deleteAutomation`](../../app/src/main/java/com/shortcuts/app/viewmodel/AutomationViewModel.kt#L90-L119) deletes `widgetBindingDao.deleteForAutomation(id)` and `automationRepository.delete(automation)`, which cascades to `custom_widget_templates` via `ForeignKey.CASCADE` ([`CustomWidgetTemplate.kt:16`](../../app/src/main/java/com/shortcuts/app/data/CustomWidgetTemplate.kt#L16)), and sets `widget_configs.templateId = NULL` via `ForeignKey.SET_NULL` ([`WidgetConfig.kt:30`](../../app/src/main/java/com/shortcuts/app/data/WidgetConfig.kt#L30)).
     - However, `widget_configs` has **no foreign key on `automationId`** and stores multi-shortcut IDs in `automationIdsJson` (`"[1,2,3]"`). Deleting an `Automation` leaves stale IDs in `widget_configs.automationId` and `widget_configs.automationIdsJson`. While `resolveShortcuts` filters out missing automations (`db.automationDao().getAutomationById(id)` returns `null`), the stale IDs remain in `widget_configs`.

### 3.2 Engine v2 `actionsJson` (`{"schemaVersion":2,...}`) vs `ActionConverter` (Defect D5)
In debug builds ([`EngineV2.kt:21`](../../app/src/main/java/com/shortcuts/app/automation/model/EngineV2.kt#L21): `val enabled: Boolean = BuildConfig.DEBUG`), recorded shortcuts encode a `WorkflowV2` JSON **object** (`{"schemaVersion":2,"workflowId":...,"steps":[...]}`) into `Automation.actionsJson` via [`WorkflowCodec.encode`](../../app/src/main/java/com/shortcuts/app/automation/model/WorkflowCodec.kt#L35-L57) (called at [`RecorderScreen.kt:285-297`](../../app/src/main/java/com/shortcuts/app/ui/screens/RecorderScreen.kt#L285-L297)).

Meanwhile, [`ActionConverter.toActionList`](../../app/src/main/java/com/shortcuts/app/data/Automation.kt#L87-L93) attempts to parse a JSON **array** (`List<Action>`) and silently swallows `JsonSyntaxException`:
```kotlin
// app/src/main/java/com/shortcuts/app/data/Automation.kt:87-93
@TypeConverter
fun toActionList(actionsString: String): List<Action> {
    if (actionsString.isBlank()) return emptyList()
    val listType = object : TypeToken<List<Action>>() {}.type
    return try {
        gson.fromJson<List<Action>>(actionsString, listType) ?: emptyList()
    } catch (e: JsonSyntaxException) {
        emptyList()
    }
}
```
Here is the exact audit of every widget and UI code path that touches `actionsJson`:

1. **Widget Config Screen Step Count — BROKEN ([`ShortcutWidgetConfigActivity.kt:509-512`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L509-L512))**:
   ```kotlin
   private fun Automation.stepLabel(): String {
       val count = ActionConverter().toActionList(actionsJson).size
       return if (count == 1) "1 step" else "$count steps"
   }
   ```
   Used at [`ShortcutWidgetConfigActivity.kt:380`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L380) in `PreviewListCard`: every Engine v2 shortcut displays **`"0 steps"`** in the widget configuration preview!
2. **Dashboard Shortcut Card Step Count — BROKEN ([`DashboardScreen.kt:677-680`](../../app/src/main/java/com/shortcuts/app/ui/screens/DashboardScreen.kt#L677-L680))**:
   ```kotlin
   private fun pluralSteps(shortcut: Automation): String {
       val count = ActionConverter().toActionList(shortcut.actionsJson).size
       return if (count == 1) "1 step" else "$count steps"
   }
   ```
   Every Engine v2 shortcut displays **`"0 steps"`** on Dashboard grid tiles (`line 445`) and list rows (`line 474`).
3. **Dashboard Irreversible-Action Confirmation Check — BROKEN ([`DashboardScreen.kt:92-95`](../../app/src/main/java/com/shortcuts/app/ui/screens/DashboardScreen.kt#L92-L95))**:
   ```kotlin
   internal fun confirmationEffectsForAutomation(automation: Automation): List<String> {
       val actions = ActionConverter().toActionList(automation.actionsJson)
       return ShortcutRunSafety.confirmationEffects(actions)
   }
   ```
   Even though [`ShortcutRunSafety.confirmationEffects(actionsJson: String)`](../../app/src/main/java/com/shortcuts/app/service/ShortcutRunner.kt#L620-L632) already has a `String` overload that checks `WorkflowCodec.isV2Json(actionsJson)` and inspects `IrreversiblePolicy.CONFIRM_ALWAYS`, `DashboardScreen.kt:92-95` calls `ActionConverter().toActionList(...)` first and passes `emptyList()` to `confirmationEffects(List<Action>)`, **completely bypassing the safety confirmation dialog for Engine v2 shortcuts on the Dashboard**!
4. **Manual Builder Edit Mode — BROKEN ([`ManualBuilderScreen.kt:380-383`](../../app/src/main/java/com/shortcuts/app/ui/screens/ManualBuilderScreen.kt#L380-L383))**:
   - Decodes `existing.actionsJson` via `ActionConverter().toActionList(existing.actionsJson)`, loading 0 steps when editing an Engine v2 shortcut.
5. **Widget Tap Execution (`RunAutomationCallback` -> `ShortcutRunner` -> `AutomationExecutionService`) — SAFE**:
   - [`RunAutomationCallback.kt:51`](../../app/src/main/java/com/shortcuts/app/widget/RunAutomationCallback.kt#L51) calls [`ShortcutRunner.run(context, automation)`](../../app/src/main/java/com/shortcuts/app/service/ShortcutRunner.kt#L756-L760), which starts [`AutomationExecutionService`](../../app/src/main/java/com/shortcuts/app/service/AutomationExecutionService.kt#L88-L101). `AutomationExecutionService` calls `EngineV2RunRuntime.tryRun(automation.actionsJson, ...)` (`line 89`) before falling back to `ActionConverter().toActionList(automation.actionsJson)` (`line 101`).

---

## 4. Lock-Screen Widgets on Android 16 (Pixel 6a, Build `BP3A.250905.014`)

### 4.1 Does Android 16 QPR1 on a Pixel 6a Have a Lock-Screen Widget Host?
**Yes.** Build `BP3A.250905.014` is **Android 16 QPR1** (September 2025 feature drop).
- In Android 15, the Communal / Glanceable Hub lock-screen widget host (`com.android.systemui` communal host) was restricted to `Pixel Tablet`.
- In **Android 16 QPR1 (`BP3A.*`)**, Google enabled **Lock Screen Widgets ("Hub Mode" / Glanceable Hub)** on Pixel phones (Pixel 6a and newer) under **Settings → Display & touch → Lock screen → Widgets on lock screen** (accessible by swiping left on the lock screen or while charging/docked).
- On Android 16 QPR1 phones, SystemUI's communal widget host filters the widget picker to providers that declare `android:widgetCategory="keyguard"` (unless the user manually enables the hidden developer/advanced toggle *"Allow any widget on lock screen"*).
- Because [`shortcut_widget_info.xml:11`](../../app/src/main/res/xml/shortcut_widget_info.xml#L11) currently declares only `android:widgetCategory="home_screen"`, RepeatKit widgets **do not appear by default** in the Android 16 lock-screen widget picker.

### 4.2 What `shortcut_widget_info.xml` Needs for Lock-Screen Support
To support both home-screen and lock-screen placement natively:
1. **`android:widgetCategory="home_screen|keyguard"`** in [`shortcut_widget_info.xml:11`](../../app/src/main/res/xml/shortcut_widget_info.xml#L11).
2. **`android:initialKeyguardLayout="@layout/shortcut_widget_loading"`** (matching the branded initial layout so lock-screen loading never shows a bare spinner).
3. **Sizing on the Android 16 phone lock-screen hub**:
   - SystemUI's phone lock-screen hub arranges widgets in fixed-height cards (~`110dp`–`160dp` height, full-width or half-width slots).
   - `UnifiedShortcutWidget`'s `SizeMode.Responsive` breakpoints (`Size(110.dp, 110.dp)`, `Size(180.dp, 110.dp)`, `Size(250.dp, 180.dp)`) in [`UnifiedShortcutWidget.kt:82-86`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L82-L86) already cover these dimensions, **once Defect D7 (`ShortcutGrid` 52.dp clipping) is fixed**.

### 4.3 How a Widget Tap Behaves While Locked & Security Implications
This is the most important architectural distinction for lock-screen support:

1. **Unconfigured widget (`"Tap to set up"`)**:
   - [`NeedsSetup`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L172) uses `actionStartActivity(configIntent)`. When tapped on the lock screen, Android automatically prompts the user to unlock the keyguard before launching `ShortcutWidgetConfigActivity`.
2. **Configured widget (`ShortcutTile` / `ShortcutListRow`)**:
   - [`UnifiedShortcutWidget.kt:198, 245`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L198) uses `actionRunCallback<RunAutomationCallback>(...)`.
   - In Jetpack Glance, `actionRunCallback` sends a **Broadcast `PendingIntent`** to `ActionTrampolineReceiver` / `CallbackBroadcastReceiver` — it does **not** start an Activity!
   - Therefore, **Android does NOT prompt for keyguard unlock when a user taps a configured Glance widget on the lock screen.** [`RunAutomationCallback.onAction`](../../app/src/main/java/com/shortcuts/app/widget/RunAutomationCallback.kt#L19-L63) executes immediately while the phone is still locked!
3. **What happens when `RunAutomationCallback` runs while the device is locked (`KeyguardManager.isKeyguardLocked == true`)**:
   - **UI Automations (`ActionType.UI_AUTOMATION`, Engine v2 `UiStep`) & App Launches (`ActionType.APP_INTENT`, `ActionType.SEND_MESSAGE`, `ActionType.DIAL_NUMBER`) FAIL or run blindly behind the lock screen**:
     - Launching a normal Activity (`APP_INTENT`) while the keyguard is locked does not bring the target app in front of the lock screen; `RepeatKitAccessibilityService` sees the lock screen (`com.android.systemui`) instead of the target app package, so UI steps time out with `EXPECTED_SCREEN_TIMEOUT` or `WINDOW_NOT_FOUND`.
     - `Toast` feedback ([`RunAutomationCallback.kt:58`](../../app/src/main/java/com/shortcuts/app/widget/RunAutomationCallback.kt#L58), [`AutomationExecutionService.kt:191`](../../app/src/main/java/com/shortcuts/app/service/AutomationExecutionService.kt#L191)) is suppressed on the lock screen on modern Android.
   - **Security Vulnerability**:
     - Anyone who picks up the locked phone can swipe to the lock-screen widget and tap it to trigger `HTTP_REQUEST` webhooks (e.g., unlocking a smart door, triggering a home webhook), `SEND_MESSAGE`, `DIAL_NUMBER`, or `UI_AUTOMATION` without authenticating!
     - Only benign local hardware toggles (`ActionType.SYSTEM_TOGGLE` such as Flashlight, Ringer mode, or DND) make sense to execute without unlocking — and even then, any shortcut that opens an app, runs UI steps, makes HTTP requests, or dials/messages **must require keyguard dismissal first**.
4. **How to handle locked taps properly (`KeyguardDismissTrampolineActivity`)**:
   - In [`RunAutomationCallback.onAction`](../../app/src/main/java/com/shortcuts/app/widget/RunAutomationCallback.kt#L19), check `val km = context.getSystemService(KeyguardManager::class.java)` and `km?.isKeyguardLocked == true`.
   - If the device is locked and the shortcut requires unlock (any step other than an in-process `SYSTEM_TOGGLE`, or always if the user hasn't opted in), start a lightweight transparent `WidgetUnlockTrampolineActivity` (`FLAG_ACTIVITY_NEW_TASK`, `android:excludeFromRecents="true"`) that calls:
     ```kotlin
     keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
         override fun onDismissSucceeded() {
             ShortcutRunner.run(applicationContext, automation)
             finish()
         }
         override fun onDismissCancelled() = finish()
         override fun onDismissError() = finish()
     })
     ```
   - This gives the exact native UX users expect on Android 16: tapping a lock-screen shortcut widget immediately brings up the biometric/PIN prompt, and the moment the user authenticates, the automation runs on the unlocked screen.

---

## 5. Should RepeatKit Upgrade from Glance `1.0.0` to `1.1.x` on `targetSdk 36`?

**Yes — upgrading `androidx.glance:glance-appwidget` and `androidx.glance:glance-material3` from `1.0.0` to `1.1.1` in [`app/build.gradle.kts:106-107`](../../app/build.gradle.kts#L106-L107) is strongly recommended.**

### Why Upgrade to `1.1.1`:
1. **Fixes `SessionWorker` & Composition Error Handling (`onCompositionError`)**:
   - Glance `1.1.0`+ adds `GlanceAppWidget.onCompositionError(context, glanceId, appWidgetId, throwable)`, allowing RepeatKit to recover gracefully from any unhandled error instead of leaving the widget frozen on `@layout/glance_default_loading_layout`.
   - Includes upstream fixes for `SessionWorker` cancellation and timeout races when multiple widget updates arrive back-to-back.
2. **Fixes CVE-2024-7254 (`glance-appwidget-proto`)**:
   - Glance `1.1.1` specifically patches the high-severity protobuf parsing vulnerability in `glance-appwidget-proto`.
3. **Android 15/16 (`targetSdk 36`) RemoteViews Compatibility**:
   - Glance `1.1.x` updates internal `RemoteViews` layout generation and API modeling for Android 14–16.

### What Would Upgrading to `1.1.1` Break?
- **Nothing in RepeatKit's codebase.**
  - Glance `1.1.1` is compiled with Kotlin `1.9.x` (matching RepeatKit's Kotlin `1.9.22` and Compose Compiler `1.5.8` in [`build.gradle.kts:5`](../../build.gradle.kts#L5) and [`app/build.gradle.kts:63`](../../app/build.gradle.kts#L63)) and `minSdk 26`.
  - Every API RepeatKit uses (`GlanceAppWidget`, `GlanceAppWidgetReceiver`, `SizeMode.Responsive`, `provideGlance`, `provideContent`, `GlanceTheme`, `ActionCallback`, `actionRunCallback`, `actionStartActivity`, `LazyColumn`, `ColorProvider`) is 100% source-compatible in `1.1.1`.
- **Important Caveat**: Upgrading to `1.1.1` **does NOT by itself fix Defect D1**. Even in Glance `1.1.1`, `provideGlance` is only called once per 45s `AppWidgetSession`; you **must still** move the Room observation inside `provideContent` (via `collectAsState`) or use `GlanceStateDefinition` so `update()` recomposes with fresh DB data.

---

## 6. Concrete Code-Level Fixes for Each Ranked Defect

### Fix for D1 & D4 (`UnifiedShortcutWidget.kt` + `WidgetConfigDao.kt` + `AutomationDao.kt`): Reactive Room Flows Inside `provideContent`, Batch Query, and Error Fallback

1. **Add reactive `Flow` and batch queries to [`WidgetConfigDao.kt`](../../app/src/main/java/com/shortcuts/app/data/WidgetConfigDao.kt#L17-L21) and [`AutomationDao.kt`](../../app/src/main/java/com/shortcuts/app/data/AutomationDao.kt#L12-L16)**:
   ```kotlin
   // In WidgetConfigDao.kt:
   @Query("SELECT * FROM widget_configs WHERE widgetId = :widgetId AND widgetType = :widgetType LIMIT 1")
   fun observeConfig(widgetId: Int, widgetType: String): Flow<WidgetConfig?>

   // In AutomationDao.kt:
   @Query("SELECT * FROM automations WHERE id IN (:ids)")
   suspend fun getAutomationsByIds(ids: List<Int>): List<Automation>
   ```

2. **Make [`UnifiedShortcutWidget.provideGlance`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L88-L138) observe Room inside `provideContent` and guard all pre-`provideContent` calls**:
   ```kotlin
   // In UnifiedShortcutWidget.kt:
   private data class WidgetSnapshot(
       val shortcuts: List<ResolvedShortcut>,
       val layout: WidgetLayoutKey
   )

   private fun observeWidgetSnapshot(
       db: AppDatabase,
       appWidgetId: Int
   ): Flow<WidgetSnapshot> = combine(
       db.widgetConfigDao().observeConfig(appWidgetId, WIDGET_TYPE_UNIFIED),
       db.automationDao().getAllAutomations(),
       db.customWidgetTemplateDao().getAll()
   ) { config, _, _ ->
       val shortcuts = runCatching {
           withContext(Dispatchers.IO) { resolveShortcuts(db, config) }
       }.getOrDefault(emptyList())
       WidgetSnapshot(
           shortcuts = shortcuts,
           layout = WidgetLayoutKey.fromKey(config?.layoutKey)
       )
   }.distinctUntilChanged()

   override suspend fun provideGlance(context: Context, id: GlanceId) {
       val appWidgetId = runCatching {
           GlanceAppWidgetManager(context).getAppWidgetId(id)
       }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)

       val db = AppDatabase.getDatabase(context)
       val initialSnapshot = runCatching {
           withContext(Dispatchers.IO) {
               val config = db.widgetConfigDao().getConfig(appWidgetId, WIDGET_TYPE_UNIFIED)
               WidgetSnapshot(
                   shortcuts = resolveShortcuts(db, config),
                   layout = WidgetLayoutKey.fromKey(config?.layoutKey)
               )
           }
       }.getOrDefault(WidgetSnapshot(emptyList(), WidgetLayoutKey.AUTO))

       val configIntent = ShortcutWidgetConfigActivity.createIntent(context, appWidgetId)
       val snapshotFlow = remember { observeWidgetSnapshot(db, appWidgetId) } // or pass into provideContent

       provideContent {
           // Bump Glance state OR collect Room Flow directly inside provideContent:
           val flow = androidx.compose.runtime.remember(appWidgetId) {
               observeWidgetSnapshot(db, appWidgetId)
           }
           val snapshot by flow.collectAsState(initial = initialSnapshot)
           GlanceTheme {
               WidgetSurface(
                   shortcuts = snapshot.shortcuts,
                   layout = snapshot.layout,
                   configIntent = configIntent
               )
           }
       }
   }
   ```
   Also update [`ShortcutWidgetRefresh.kt`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetRefresh.kt#L23-L35) to write a timestamp to `updateAppWidgetState(context, glanceId) { prefs -> prefs[LAST_REFRESH_KEY] = System.currentTimeMillis() }` and read `currentState<Preferences>()[LAST_REFRESH_KEY]` inside `provideContent`. That guarantees Glance's `UpdateGlance` event **always** invalidates the composition even if a Flow emission hasn't arrived yet.

3. **Batch `resolveShortcuts` in [`UnifiedShortcutWidget.kt:115-138`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L115-L138)**:
   ```kotlin
   private suspend fun resolveShortcuts(
       db: AppDatabase,
       config: WidgetConfig?
   ): List<ResolvedShortcut> {
       if (config == null) return emptyList()
       val template = config.templateId?.let { db.customWidgetTemplateDao().getById(it) }
       val ids = AutomationIdResolver.resolveIds(config, template)
       if (ids.isEmpty()) return emptyList()
       val byId = db.automationDao().getAutomationsByIds(ids).associateBy { it.id }
       val orderedAutomations = ids.mapNotNull { byId[it] }
       if (orderedAutomations.isEmpty()) return emptyList()
       val isSingle = orderedAutomations.size == 1
       val colorKey = WidgetColorKey.fromKey(config.colorKey ?: template?.colorKey)
       return orderedAutomations.map { automation ->
           val label = if (isSingle) {
               config.label?.takeIf { it.isNotBlank() }
                   ?: template?.label?.takeIf { it.isNotBlank() }
                   ?: automation.name
           } else {
               automation.name
           }
           ResolvedShortcut(automation = automation, label = label, colorKey = colorKey)
       }
   }
   ```

---

### Fix for D2 (`CreateWidgetScreen.kt`, `CustomWidgetViewModel.kt`, `ShortcutWidgetPinRequest.kt`, `ShortcutWidgetPinReceiver.kt`): Wire Custom Widget Pinning End-to-End

1. **Capture the inserted `templateId` in [`CustomWidgetViewModel.kt:23-33, 218-226`](../../app/src/main/java/com/shortcuts/app/viewmodel/CustomWidgetViewModel.kt#L218-L226)**:
   - Add `val savedTemplateId: Long? = null` to `CustomWidgetBuilderData`.
   - In `saveWidget()`:
     ```kotlin
     val insertedId = templateDao.insert(
         CustomWidgetTemplate(
             label = trimmedLabel,
             colorKey = current.colorKey.key,
             iconKey = current.iconKey.key,
             automationId = automationId
         )
     )
     _uiState.update {
         it.copy(isSaving = false, savedSuccess = true, savedTemplateId = insertedId)
     }
     ```
2. **Extend [`ShortcutWidgetPinRequest.kt`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetPinRequest.kt#L11-L29) and [`ShortcutWidgetPinReceiver.kt`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetPinReceiver.kt#L19-L46) to support `templateId`, `label`, `colorKey`, and `iconKey`**:
   ```kotlin
   // In ShortcutWidgetPinRequest.kt:
   fun requestPin(
       context: Context,
       automationId: Int,
       templateId: Long? = null,
       label: String? = null,
       colorKey: String? = null,
       iconKey: String? = null
   ): Boolean {
       val appWidgetManager = AppWidgetManager.getInstance(context)
       if (!appWidgetManager.isRequestPinAppWidgetSupported) return false
       val provider = ComponentName(context, ShortcutWidgetReceiver::class.java)
       val callbackIntent = Intent(context, ShortcutWidgetPinReceiver::class.java).apply {
           putExtra(EXTRA_AUTOMATION_ID, automationId)
           templateId?.let { putExtra(EXTRA_TEMPLATE_ID, it) }
           label?.let { putExtra(EXTRA_LABEL, it) }
           colorKey?.let { putExtra(EXTRA_COLOR_KEY, it) }
           iconKey?.let { putExtra(EXTRA_ICON_KEY, it) }
       }
       val requestCode = (templateId?.toInt() ?: automationId)
       val successCallback = PendingIntent.getBroadcast(
           context,
           requestCode,
           callbackIntent,
           PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
       )
       return appWidgetManager.requestPinAppWidget(provider, null, successCallback)
   }
   ```
   And in [`ShortcutWidgetPinReceiver.kt:29-39`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetPinReceiver.kt#L29-L39), read those extras and pass them into `WidgetConfig(...)`:
   ```kotlin
   val templateId = intent.getLongExtra(ShortcutWidgetPinRequest.EXTRA_TEMPLATE_ID, -1L).takeIf { it > 0L }
   val label = intent.getStringExtra(ShortcutWidgetPinRequest.EXTRA_LABEL)
   val colorKey = intent.getStringExtra(ShortcutWidgetPinRequest.EXTRA_COLOR_KEY)
   val iconKey = intent.getStringExtra(ShortcutWidgetPinRequest.EXTRA_ICON_KEY)
   db.widgetConfigDao().upsert(
       WidgetConfig(
           widgetId = appWidgetId,
           widgetType = UnifiedShortcutWidget.WIDGET_TYPE_UNIFIED,
           templateId = templateId,
           label = label,
           colorKey = colorKey,
           iconKey = iconKey,
           automationId = automationId,
           automationIdsJson = AutomationIdResolver.encodeIds(listOf(automationId)),
           layoutKey = WidgetLayoutKey.SINGLE.key
       )
   )
   ```
3. **Call `ShortcutWidgetPinRequest.requestPin(...)` from [`CreateWidgetScreen.kt:69-75, 451-464`](../../app/src/main/java/com/shortcuts/app/ui/screens/CreateWidgetScreen.kt#L451-L464)** using `uiState.selectedAutomationId`, `uiState.savedTemplateId`, `uiState.label`, `uiState.colorKey.key`, and `uiState.iconKey.key`.

---

### Fix for D3 (`ShortcutWidgetConfigActivity.kt`): Ordered Selection, Load Existing Config on Reconfigure, Single-Select Radio Behavior for `SINGLE`, and `rememberSaveable`

In [`ShortcutWidgetConfigActivity.kt`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L107-L255):
1. **Load existing `WidgetConfig` in `ShortcutWidgetSelectionStore`**:
   ```kotlin
   suspend fun loadInitialConfig(appWidgetId: Int): WidgetConfig? =
       withContext(Dispatchers.IO) {
           widgetConfigDao.getConfig(appWidgetId, UnifiedShortcutWidget.WIDGET_TYPE_UNIFIED)
       }
   ```
2. **Store `selectedIds` as an ordered `List<Int>` in `rememberSaveable` and default to ONLY the first shortcut (`take(1)`) when creating a new widget**:
   ```kotlin
   var selectedIds by rememberSaveable(shortcuts, initialConfig) {
       val existingIds = initialConfig?.let { AutomationIdResolver.resolveIds(it, null) }.orEmpty()
       val validExisting = existingIds.filter { id -> shortcuts.orEmpty().any { it.id == id } }
       val defaultIds = validExisting.ifEmpty {
           shortcuts.orEmpty().take(1).map { it.id }
       }
       mutableStateOf(defaultIds)
   }
   var selectedLayout by rememberSaveable(initialConfig) {
       mutableStateOf(WidgetLayoutKey.fromKey(initialConfig?.layoutKey))
   }
   // Preserve the user's selection order so selectedShortcuts.first() is the chosen shortcut:
   val shortcutsById = remember(shortcuts) { shortcuts.orEmpty().associateBy { it.id } }
   val selectedShortcuts = remember(selectedIds, shortcutsById) {
       selectedIds.mapNotNull { shortcutsById[it] }
   }
   ```
3. **When `selectedLayout == WidgetLayoutKey.SINGLE`, make tapping a shortcut chip select that shortcut directly (or promote it to first)**:
   ```kotlin
   onToggleShortcut = { id ->
       selectedIds = when {
           selectedLayout == WidgetLayoutKey.SINGLE -> listOf(id)
           id in selectedIds -> selectedIds - id
           selectedIds.size < MAX_SHORTCUTS_PER_WIDGET -> selectedIds + id
           else -> selectedIds
       }
   }
   ```

---

### Fix for D5 (`Automation.kt`, `ShortcutWidgetConfigActivity.kt`, `DashboardScreen.kt`): Engine v2-Aware Step Count & Safety Helper

1. **Add an Engine v2-aware step counter on `Automation` in [`Automation.kt`](../../app/src/main/java/com/shortcuts/app/data/Automation.kt#L24-L32)**:
   ```kotlin
   fun Automation.stepCount(): Int {
       if (actionsJson.isBlank()) return 0
       if (com.shortcuts.app.automation.model.WorkflowCodec.isV2Json(actionsJson)) {
           return runCatching {
               com.shortcuts.app.automation.model.WorkflowCodec.decode(actionsJson).steps.size
           }.getOrDefault(0)
       }
       return ActionConverter().toActionList(actionsJson).size
   }
   ```
2. **Update [`ShortcutWidgetConfigActivity.kt:509-512`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L509-L512) and [`DashboardScreen.kt:677-680`](../../app/src/main/java/com/shortcuts/app/ui/screens/DashboardScreen.kt#L677-L680)** to call `stepCount()`.
3. **Fix [`DashboardScreen.kt:92-95`](../../app/src/main/java/com/shortcuts/app/ui/screens/DashboardScreen.kt#L92-L95) to call the v2-aware `ShortcutRunSafety.confirmationEffects(String)` overload**:
   ```kotlin
   internal fun confirmationEffectsForAutomation(automation: Automation): List<String> {
       return ShortcutRunSafety.confirmationEffects(automation.actionsJson)
   }
   ```

---

### Fix for D6 (`AppDatabase.kt` & `ShortcutWidgetRefresh.kt`): Observe `widget_configs` & `custom_widget_templates` and Serialize Refreshes

1. **In [`AppDatabase.kt:39-47`](../../app/src/main/java/com/shortcuts/app/data/AppDatabase.kt#L39-L47), observe all three widget-relevant tables and route through `refreshAllShortcutWidgets`**:
   ```kotlin
   private fun installWidgetRefreshObserver(context: Context, db: AppDatabase) {
       val observer = object : InvalidationTracker.Observer(
           "automations",
           "widget_configs",
           "custom_widget_templates"
       ) {
           override fun onInvalidated(tables: Set<String>) {
               CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                   refreshAllShortcutWidgets(context)
               }
           }
       }
       db.invalidationTracker.addObserver(observer)
   }
   ```
2. **In [`ShortcutWidgetRefresh.kt`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetRefresh.kt#L17-L57), guard refreshes with a `Mutex` so concurrent `refreshAllShortcutWidgets` and `refreshShortcutWidget` calls don't cancel each other's `SessionWorker`s, and nudge options in `refreshAllShortcutWidgets` too.**

---

### Fix for D7 (`UnifiedShortcutWidget.kt:220-265`): Fix Grid Tile Clipping and Add Stable `LazyColumn` Item IDs

1. **In [`UnifiedShortcutWidget.kt:220-235`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L220-L235), increase `ShortcutGrid` cell height or use `72.dp` so both the `36.dp` icon badge and `13.sp` label fit without clipping**:
   ```kotlin
   @Composable
   private fun ShortcutGrid(shortcuts: List<ResolvedShortcut>) {
       val rows = shortcuts.chunked(2)
       Column(modifier = GlanceModifier.fillMaxSize()) {
           for (row in rows) {
               Row(
                   modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                   verticalAlignment = Alignment.Vertical.CenterVertically
               ) {
                   for (shortcut in row) {
                       Box(
                           modifier = GlanceModifier
                               .defaultWeight()
                               .fillMaxHeight()
                               .padding(3.dp)
                       ) {
                           ShortcutTile(shortcut, compact = true)
                       }
                   }
                   if (row.size == 1) {
                       Spacer(modifier = GlanceModifier.defaultWeight())
                   }
               }
           }
       }
   }
   ```
2. **In [`UnifiedShortcutWidget.kt:239`](../../app/src/main/java/com/shortcuts/app/widget/UnifiedShortcutWidget.kt#L239), supply `itemId` to `LazyColumn.items`**:
   ```kotlin
   items(shortcuts, itemId = { it.automation.id.toLong() }) { shortcut ->
       ShortcutListRow(shortcut)
   }
   ```

---

### Fix for D8 (`shortcut_widget_info.xml` & `ShortcutWidgetConfigActivity.kt`): Branded Initial Layout, `updatePeriodMillis`, and Cancel Result Intent

1. **In [`shortcut_widget_info.xml`](../../app/src/main/res/xml/shortcut_widget_info.xml#L1-L13)**:
   ```xml
   <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
       android:minWidth="110dp"
       android:minHeight="110dp"
       android:targetCellWidth="2"
       android:targetCellHeight="2"
       android:minResizeWidth="110dp"
       android:minResizeHeight="56dp"
       android:resizeMode="horizontal|vertical"
       android:updatePeriodMillis="1800000"
       android:widgetCategory="home_screen|keyguard"
       android:widgetFeatures="reconfigurable|configuration_optional"
       android:PreviewLayout="@layout/shortcut_widget_preview"
       android:initialLayout="@layout/shortcut_widget_preview"
       android:initialKeyguardLayout="@layout/shortcut_widget_preview"
       android:configure="com.shortcuts.app.widget.ShortcutWidgetConfigActivity"
       android:description="@string/widget_single_description" />
   ```
   *(Note: Using `@layout/shortcut_widget_preview` or a dedicated `@layout/shortcut_widget_loading` dark card instead of `@layout/glance_default_loading_layout` eliminates the jarring system loading spinner, and `android:updatePeriodMillis="1800000"` [30 minutes, the Android minimum] ensures the OS periodically wakes and heals any widget whose worker was killed).*
2. **In [`ShortcutWidgetConfigActivity.kt:84-97`](../../app/src/main/java/com/shortcuts/app/widget/ShortcutWidgetConfigActivity.kt#L84-L97), include `EXTRA_APPWIDGET_ID` in the initial `RESULT_CANCELED` intent**:
   ```kotlin
   setResult(
       RESULT_CANCELED,
       Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
   )
   ```

---

## 7. On-Device Verification & Diagnostic Plan Using `adb` Only

Use these `adb` commands against the Pixel 6a (`com.shortcuts.app.dev` for debug builds) to diagnose and verify every widget state transition without touching UI test frameworks:

### Step 1: Inspect Active AppWidget Host Bindings & RemoteViews State
```sh
# 1. Check all bound RepeatKit appWidgetIds, provider info, options, and whether RemoteViews is still on glance_default_loading_layout:
adb shell dumpsys appwidget | grep -A 35 -B 5 "com.shortcuts.app.dev"
```
- **What to look for**:
  - Under `AppWidgetId[...]`: check `provider=ComponentInfo{com.shortcuts.app.dev/com.shortcuts.app.widget.ShortcutWidgetReceiver}`.
  - Check `views=RemoteViews[...]`: if it references `glance_default_loading_layout` (or `null`), Glance's `SessionWorker` has not emitted its first composition yet.

### Step 2: Inspect Glance `SessionWorker` Status in WorkManager & Logcat
```sh
# 2. Stream Glance session, WorkManager, and widget receiver logs in real time:
adb logcat -v time -s GlanceAppWidget:V AppWidgetSession:V SessionWorker:V WM-WorkerWrapper:V ShortcutWidget:V

# 3. Dump active/enqueued WorkManager jobs for com.shortcuts.app.dev in JobScheduler:
adb shell dumpsys jobscheduler | grep -A 25 "com.shortcuts.app.dev"
```
- **What to look for**:
  - Verify whether `SessionWorker` is currently running (holding the 45s `AppWidgetSession` open) when `ShortcutWidget().update()` is called.

### Step 3: Inspect Room Database (`widget_configs`, `custom_widget_templates`, `automations`) Directly
```sh
# 4. Query the app's SQLite tables directly via run-as:
adb shell "run-as com.shortcuts.app.dev sqlite3 databases/shortcuts_database 'SELECT * FROM widget_configs;'"
adb shell "run-as com.shortcuts.app.dev sqlite3 databases/shortcuts_database 'SELECT * FROM custom_widget_templates;'"
adb shell "run-as com.shortcuts.app.dev sqlite3 databases/shortcuts_database 'SELECT id, name, substr(actionsJson, 1, 60) FROM automations;'"
```
- **What to verify**:
  - After pinning from `DashboardScreen` (`Add widget`) or `CreateWidgetScreen` (`Create Custom Widget`), confirm a row exists in `widget_configs` whose `widgetId` matches the `appWidgetId` shown in `dumpsys appwidget`.
  - Confirm `automationIdsJson` preserves the exact selected shortcut order and `templateId`/`label`/`colorKey` are populated for custom widgets.

### Step 4: Force `APPWIDGET_UPDATE` Broadcasts and Test Cold-Process Recovery
```sh
# 5. Kill the app process to simulate process death, then broadcast ACTION_APPWIDGET_UPDATE for a specific appWidgetId (e.g., ID 42):
adb shell am force-stop com.shortcuts.app.dev
adb shell am broadcast -a android.appwidget.action.APPWIDGET_UPDATE \
  -n com.shortcuts.app.dev/com.shortcuts.app.widget.ShortcutWidgetReceiver \
  --eia appWidgetIds 42
```
- **What to verify**:
  - Within <1 second of the broadcast, `dumpsys appwidget` for ID `42` transitions from `initialLayout` to Glance's composed `RemoteViews` and renders the configured shortcut without requiring `MainActivity` to open.

### Step 5: Verify Lock-Screen (`keyguard`) Category & Locked-Device Tap Behavior on Android 16
```sh
# 6. Verify provider metadata reports widgetCategory=0x3 (HOME_SCREEN | KEYGUARD):
adb shell dumpsys appwidget | grep -A 15 "Provider{com.shortcuts.app.dev/com.shortcuts.app.widget.ShortcutWidgetReceiver}"

# 7. Lock the device screen and wake to the lock screen (Keyguard locked):
adb shell input keyevent KEYCODE_SLEEP
adb shell input keyevent KEYCODE_WAKEUP
adb shell dumpsys window | grep -E "mDreamingLockscreen|isStatusBarKeyguard|showing="
```
- **What to verify**:
  - `widgetCategory` in `dumpsys appwidget` is `0x3` (`home_screen|keyguard`).
  - Swiping to the lock-screen widget space on the Pixel 6a (`BP3A.250905.014`) lists RepeatKit in the lock-screen widget picker.
  - Tapping a widget while `KeyguardManager.isKeyguardLocked == true` prompts `requestDismissKeyguard` (for app/UI/HTTP automations) and executes the shortcut immediately after unlock.

---

## 8. Summary Recommendations

1. **Fix Home-Screen Reliability First (D1–D4)**:
   - Make `UnifiedShortcutWidget.provideContent` reactive to Room (`observeConfig` + `getAllAutomations` + `customWidgetTemplateDao.getAll()` via `collectAsState`, plus a `PreferencesGlanceStateDefinition` timestamp key bumped in `ShortcutWidgetRefresh`). This single architectural fix eliminates the 45-second Glance session blind spot where post-pin and post-config updates are ignored.
   - Connect `CreateWidgetScreen` to `ShortcutWidgetPinRequest` / `ShortcutWidgetPinReceiver` with `templateId`, `label`, `colorKey`, and `iconKey` so custom widgets actually bind their configuration when pinned.
   - Fix `ShortcutWidgetConfigActivity` so it loads existing `WidgetConfig` on reconfigure, defaults to selecting 1 shortcut instead of 6, preserves selection order in `rememberSaveable`, and uses single-select behavior when `SINGLE` layout is active.
2. **Fix Engine v2 `actionsJson` Decoding in UI/Widget Preview (D5)**:
   - Add an Engine v2-aware `Automation.stepCount()` helper and use `ShortcutRunSafety.confirmationEffects(automation.actionsJson)` on `DashboardScreen`.
3. **Upgrade Glance to `1.1.1` (Section 5)**:
   - Bump `androidx.glance:glance-appwidget` and `androidx.glance:glance-material3` to `1.1.1` in [`app/build.gradle.kts:106-107`](../../app/build.gradle.kts#L106-L107) and implement `onCompositionError`.
4. **Enable Lock-Screen Widgets Safely on Android 16 (Section 4)**:
   - Add `android:widgetCategory="home_screen|keyguard"` in [`shortcut_widget_info.xml:11`](../../app/src/main/res/xml/shortcut_widget_info.xml#L11), and gate `RunAutomationCallback` behind `KeyguardManager.isKeyguardLocked` + `KeyguardManager.requestDismissKeyguard(...)` via a lightweight trampoline Activity so UI/app/HTTP shortcuts unlock the device before running.
