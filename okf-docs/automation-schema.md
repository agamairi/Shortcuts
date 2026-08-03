---
title: Automation JSON Schema
type: context
author: Agent
tags: [schema, json, automation, AI]
---

# Automation JSON Schema

The `function-gemma` AI model and the app's internal representations use this standard JSON format to define an automation flow.

## Schema Definition
An automation is represented as an array of `Action` objects.

```json
{
  "automation_name": "Morning Routine",
  "trigger": "widget_click",
  "actions": [
    {
      "action_type": "SYSTEM_TOGGLE",
      "target": "WIFI",
      "state": "ON"
    },
    {
      "action_type": "APP_INTENT",
      "package_name": "com.spotify.music",
      "action": "android.intent.action.MAIN"
    },
    {
      "action_type": "HTTP_REQUEST",
      "url": "https://api.weather.com/v1/...",
      "method": "GET"
    },
    {
      "action_type": "UI_AUTOMATION",
      "description": "Click the play button",
      "target_node_id": "com.spotify.music:id/play_button"
    }
  ]
}
```

## Action Types
1. **SYSTEM_TOGGLE**: For toggling WiFi, Bluetooth, Flashlight.
2. **APP_INTENT**: For opening apps and broadcasting intents.
3. **HTTP_REQUEST**: For making API calls.
4. **UI_AUTOMATION**: For simulating clicks via the Accessibility Service.
