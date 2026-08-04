---
title: Automation & Action JSON Schema Specification
type: context
author: teamwork_preview_worker_m4
tags: [schema, json, automation, room, action-converter]
---

# Automation & Action JSON Schema Specification

## Overview
The `Shortcuts` application uses a standardized JSON schema for defining automation workflows. This schema is shared across AI prompt generation (`AiBuilderViewModel`), manual creation (`ManualBuilderScreen`), Room DB persistence (`ActionConverter`), and execution engines (`AutomationAccessibilityService` & `ActionExecutorService`).

---

## 1. Action Entity Fields

Each `Action` object inside an automation workflow contains the following fields:

| Field Name | Type | Description | Applicable Action Types |
|---|---|---|---|
| `actionType` | Enum (`ActionType`) | The primary category of action (`SYSTEM_TOGGLE`, `APP_INTENT`, `HTTP_REQUEST`, `UI_AUTOMATION`) | All |
| `target` | String? | Target identifier (e.g. "WIFI", "BLUETOOTH", button text) | `SYSTEM_TOGGLE`, `UI_AUTOMATION` |
| `state` | String? | Desired toggle state ("ON", "OFF") | `SYSTEM_TOGGLE` |
| `packageName` | String? | Target Android package name (e.g. `com.spotify.music`) | `APP_INTENT` |
| `intentAction` | String? | Android Intent Action string (e.g. `android.intent.action.MAIN`) | `APP_INTENT` |
| `url` | String? | HTTP target URL | `HTTP_REQUEST` |
| `method` | String? | HTTP method (`GET`, `POST`, `PUT`, `DELETE`) | `HTTP_REQUEST` |
| `targetNodeId` | String? | View resource ID for accessibility targeting | `UI_AUTOMATION` |
| `targetText` | String? | Target View text content | `UI_AUTOMATION` |
| `textInput` | String? | Text payload to inject into editable fields | `UI_AUTOMATION` |
| `uiActionType` | String? | Sub-type of UI action (`CLICK`, `LONG_CLICK`, `SCROLL`, `TEXT_INPUT`, `GLOBAL`) | `UI_AUTOMATION` |
| `globalAction` | String? | System global action ("GLOBAL_ACTION_BACK", "GLOBAL_ACTION_HOME", "GLOBAL_ACTION_RECENTS") | `UI_AUTOMATION` |
| `scrollDirection` | String? | Direction for scroll actions ("UP", "DOWN", "LEFT", "RIGHT") | `UI_AUTOMATION` |

---

## 2. Action Types & Example JSON

### Example Array Serialized in `actionsJson`
```json
[
  {
    "actionType": "SYSTEM_TOGGLE",
    "target": "WIFI",
    "state": "ON"
  },
  {
    "actionType": "APP_INTENT",
    "packageName": "com.spotify.music",
    "intentAction": "android.intent.action.MAIN"
  },
  {
    "actionType": "HTTP_REQUEST",
    "url": "https://api.example.com/trigger",
    "method": "POST"
  },
  {
    "actionType": "UI_AUTOMATION",
    "uiActionType": "CLICK",
    "targetText": "Play"
  },
  {
    "actionType": "UI_AUTOMATION",
    "uiActionType": "GLOBAL",
    "globalAction": "GLOBAL_ACTION_HOME"
  }
]
```

---

## 3. Database Type Converter (`ActionConverter`)
The Room database stores the array of actions as a serialized JSON string in the `Automation.actionsJson` column using `ActionConverter` (Gson):

```kotlin
class ActionConverter {
    private val gson = Gson()

    @TypeConverter
    fun fromActionList(actions: List<Action>): String = gson.toJson(actions)

    @TypeConverter
    fun toActionList(actionsString: String): List<Action> {
        val type = object : TypeToken<List<Action>>() {}.type
        return gson.fromJson(actionsString, type)
    }
}
```
