---
title: Open Knowledge Format (OKF) Documentation Index
type: reference
author: antigravity_gemini_3_1_pro
tags: [readme, index, okf, documentation]
---

# Open Knowledge Format (OKF) Documentation

This folder contains the authoritative architectural documentation for the Shortcuts Android application, written in the Open Knowledge Format (OKF).

## Document Index

- [Architecture (`architecture.md`)](architecture.md) - System overview, Room database schema, and high-level component diagrams.
- [Services (`services.md`)](services.md) - Background service infrastructure including the execution engine and accessibility capabilities.
- [Automation Schema (`automation-schema.md`)](automation-schema.md) - JSON structure and definitions for the `Action` and `ActionType` entities.
- [ViewModel UI State (`viewmodel-uistate.md`)](viewmodel-uistate.md) - Unidirectional data flow and MVVM patterns across Compose screens.
- [Theme Mode (`theme-mode-preferences.md`)](theme-mode-preferences.md) - Jetpack DataStore implementation for persisting system/light/dark modes.
- [Recorder (`recorder.md`)](recorder.md) - The accessibility-based session recording pipeline and multi-selector capture logic.
- [AI Planner (`ai-planner.md`)](ai-planner.md) - MediaPipe LlmInference generation pipeline, function calling format, and clause alignment.
- [Widgets (`widgets.md`)](widgets.md) - Jetpack Glance providers, Pin flow, and the unified adaptive widget consolidation.
- [Navigation & Screens (`navigation-and-screens.md`)](navigation-and-screens.md) - Compose Navigation routes, screens, and ViewModel scoping.
- [Execution Engine (`execution-engine.md`)](execution-engine.md) - `AutomationExecutionService` action routing, per-step results, and UI replay retry/fallback mechanisms.

## OKF Conventions

Every document must begin with YAML frontmatter following this format:

```yaml
---
title: <Title>
type: <architecture|schema|reference|context>
author: <your agent name>
tags: [comma, separated, tags]
---
```

**When to add vs update:**
- **Update** an existing document when the core logic, schema, or system it describes changes (e.g., adding a new field to `Action`).
- **Add** a new document when a conceptually distinct system or feature is introduced that would bloat an existing file (e.g., introducing the `Recorder` or a new background service).
