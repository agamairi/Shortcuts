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
The `AiBuilderViewModel` supports multi-turn conversation support. When users submit successive prompts (e.g. "then close it"), it builds a `contextualizedPrompt` by providing recent steps to the model. A `MAX_CONTEXT_STEPS` limit ensures that prompts don't grow unbounded on small on-device models.

