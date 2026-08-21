---
title: AI Planner Pipeline
type: architecture
author: antigravity_gemini_3_1_pro
tags: [ai, inference, llm, mediapipe, gemma, planner]
---

# AI Planner Pipeline

## Overview
The AI Planner converts natural language prompts into executable UI automation and system intent actions on-device using a MediaPipe `LlmInference` engine executing `function-gemma`.

## Pipeline Execution Flow
The process flows sequentially from user text to a grounded list of actions:

1. **`PromptSegmenter`**: The raw text prompt is split into individual clauses using speech-verb and quote-aware boundaries (e.g., "Turn on wifi and open Spotify" becomes two clauses).
2. **`FunctionCallingPromptBuilder`**: Formats the segmented clauses into the specific strict schema format expected by the `function-gemma` model to enforce JSON output.
3. **`OnDeviceInferenceService`**: A long-lived MediaPipe `LlmInference` engine wrapped behind a Mutex. It handles generation using a fresh `LlmInferenceSession` per inference request, preventing the engine from being reloaded on every clause.
4. **`FunctionCallParser`**: Extracts and validates the raw JSON from the model's markdown-wrapped output.
5. **`ClauseAligner`**: Maps batched model output back to the original clauses by looking at evidence. It refuses to pair an action with a clause it lacks evidence for, ensuring accuracy.
6. **`DraftShortcut`**: Each clause now yields exactly one `DraftStep`. A clause the model cannot handle surfaces as `Unresolved` instead of being silently dropped.
7. **`GroundingContext`**: Context used by the AI engine to ground its generated actions in reality. Specifically, app references are grounded against the REAL installed-app list via `PackageManager`. Ambiguous matches return null rather than launching the wrong app.

## Multi-Turn Conversations
The `AiBuilderViewModel` supports follow-up turns after the first generated draft. The initial prompt is entered on `InitialBuilderScreen`; once `draft` is non-null, `AiBuilderScreen` switches to `ReviewStepsScreen`, whose inline “Add another step...” row is the UI that accepts later prompts ([`AiBuilderScreen.kt:135-170`](../app/src/main/java/com/shortcuts/app/ui/screens/AiBuilderScreen.kt), [`AiBuilderScreen.kt:520-560`](../app/src/main/java/com/shortcuts/app/ui/screens/AiBuilderScreen.kt)).

For every request, `performInference` appends its new `DraftStep` values to the existing draft and clears the prompt ([`AiBuilderViewModel.kt:188-204`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt), [`AiBuilderViewModel.kt:217-250`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt)). Before inference, `contextualizedPrompt` prefixes the new request with up to `MAX_CONTEXT_STEPS` (currently four) prior step source texts, allowing follow-ups such as “then close it” while keeping the context bounded for the on-device model ([`AiBuilderViewModel.kt:261-272`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt), [`AiBuilderViewModel.kt:554-559`](../app/src/main/java/com/shortcuts/app/viewmodel/AiBuilderViewModel.kt)).
