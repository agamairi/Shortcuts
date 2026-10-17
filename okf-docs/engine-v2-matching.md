---
title: "Engine v2 matching, window selection, and screen fingerprinting"
type: architecture
author: antigravity_gemini_3_8_flash
tags: [engine-v2, matching, window-selection, fingerprint, pure-kotlin, architecture]
---

# Engine v2 Matching, Window Selection, and Screen Fingerprinting

Engine v2 Work Package 2 establishes the strict element matcher, window matcher, and screen fingerprinter. All components are pure Kotlin with zero `android.*` platform imports, designed to run deterministically in both JVM unit tests and production on-device replay pipelines.

---

## 1. Window Selection (`WindowMatcher` & `WindowDecision`)

Window selection implements spec §2.A to identify the exact window for interaction, avoiding accidental selection of overlays, keyboards, or unrelated popups.

### Selection Rules

1. **Overlay & IME Filtering:** `WindowType.ACCESSIBILITY_OVERLAY` and `WindowType.INPUT_METHOD` windows are excluded unless the `WindowSelector` explicitly expects that type.
2. **Package Hard Filter:** Only windows matching `WindowSelector.packageName` are evaluated.
3. **Sole Candidate Selection (Fix 5a):** If exactly one eligible window exists for the expected package with the expected window type, select it immediately (confidence floor waived; records `soleCandidate = true` in `WindowScoreBreakdown`). The element matcher then decides element identity.
4. **Dialog Over Activity (Fix 5c):** When multiple windows of the same package contain the recorded point (e.g., a dialog over an activity), the topmost dialog window wins over the activity underneath without triggering ambiguity or failing the confidence floor.
5. **Applicable Evidence Scoring:** Remaining windows are scored across criteria, normalizing only over evidence recorded in the selector:
   $$\text{windowConfidence} = \frac{\text{matchedWeight}}{\text{applicableWeight}}$$

### Scoring Table

| Evidence Criterion | Max Points | Applicable Condition | Match Condition |
|---|---|---|---|
| **Activity Class** | 30 | `selector.observedActivityClass != null` AND simple name ends with `"Activity"` (Fix 5b) | `currentActivityClass == selector.observedActivityClass` |
| **Window Type** | 15 | `selector.windowType != WindowType.UNKNOWN` | `window.type == selector.windowType` |
| **Salted Title Token** | 10 | `selector.titleToken != null` | `titleTokenizer(window.title) == selector.titleToken` |
| **Normalized Bounds** | 10 | Display bounds known | Coordinates match within 5% tolerance or IoU $\ge 0.80$ |
| **Screen Fingerprint** | 35 | `selector.fingerprint != null` | $35.0 \times \text{fingerprinter.similarity(recorded, observed)}$ |

Dialog classes (e.g. `androidx.appcompat.app.AlertDialog`) recorded in `observedActivityClass` do not count toward activity weight.

### Decision Thresholds

- **Confidence Floor:** Best window must reach $\text{confidence} \ge 0.80$ (waived for sole candidates and topmost dialog winners).
- **Runner-up Margin:** Margin over the second candidate must be $\ge 0.15$ (waived when the runner-up is an activity covered by a dialog winner).
- **Z-Order (Layer):** Used as a tie-breaker for deterministic sorting and for selecting topmost dialog windows; never replaces semantic identity.

### Sealed Decision (`WindowDecision`)

```kotlin
sealed interface WindowDecision {
    data class Selected(val window: WindowSnapshot, val confidence: Double, val breakdown: WindowScoreBreakdown) : WindowDecision
    data class NotFound(val observedSummary: String) : WindowDecision
    data class Ambiguous(val best: ScoredWindow, val second: ScoredWindow) : WindowDecision
}
```

---

## 2. Screen Fingerprinting (`DefaultScreenFingerprinter`)

Screen fingerprints provide screen identity without persisting raw text, allowing wait steps and postcondition verifications to confirm app navigation state.

### Token Construction

- **Structural Tokens (`Set<Long>`):** 64-bit deterministic FNV-1a hashes of `(className, resourceId, depthBucket)` for all visible nodes (`visibleToUser == true`). Depth is bucketed into coarse ranges ($0, 1, 2, 3..5, 6..9, 10+$) to prevent layout container insertions from altering tokens.
- **Landmark Tokens (`Set<Long>`):** 64-bit deterministic FNV-1a hashes of normalized text and content descriptions from visible nodes that look like stable landmarks:
  - Short text ($\le 40$ chars) located in the top 25% of the window ($Y \le \text{window.top} + 0.25 \times \text{height}$).
  - Resource IDs containing `"title"`, `"toolbar"`, or `"tab"`.
  - Raw strings are never stored in `ScreenFingerprint`.
- **Structure Hash (`Long`):** Deterministic 64-bit FNV-1a hash over the sorted set of structural tokens.

### Similarity Formula

Different packages always return $0.0$. Same package:
$$\text{similarity} = \min\left(1.0, 0.6 \times \text{Jaccard}_{\text{structural}} + 0.4 \times \text{Jaccard}_{\text{landmark}} + \text{bonus}\right)$$
where $\text{bonus} = 0.1$ when `activityClass` matches and both are non-null.

- **Same screen with changed list items:** $\text{similarity} \ge 0.80$.
- **Different screens within the same app:** $\text{similarity} < 0.80$.

---

## 3. Strict Element Matching (`SelectorMatcher` & `CandidateScorer`)

Element matching implements spec §2.C/§2.D: candidate generation by exact anchors, multi-factor corroboration, contradiction penalties, and deterministic ambiguity rejection.

### Candidate Generation & Hard Filters

Nodes are evaluated only if they pass all hard filters:
1. `node.windowId == window.windowId && node.packageName == window.packageName`
2. `node.visibleToUser == true`
3. `node.enabled == true`
4. Non-empty bounds intersecting the visible window (`bounds.width > 0 && bounds.height > 0 && boundsIntersect(window)`)
5. Editable state for text actions (`SET_TEXT`, `IME_ACTION`)
6. Scrollable state when matching scroll containers
7. Required action supported on the node (unless `allowGestureFallback == true`)

Candidates are generated strictly through exact matching anchors:
- Exact resource ID equality (or test tag / web DOM ID)
- Exact normalized text equality (`TextNormalizer.sameText` against node's own text, its `labelOf` derived label, or its `hintText` for editable nodes)
- Exact normalized content description equality (`TextNormalizer.sameText` against node's own desc or its `labelOf` derived desc)
- Exact test tag / web DOM ID equality
- $\ge 80\%$ ancestor-chain match + terminal class
- Exact row context + terminal class
- Window-normalized coordinate proximity ($\text{dist} \le 0.25$)

Substring matching is disallowed, and resource IDs are never compared against text.

### Label Derivation & Compose Buttons (Fix 1 & 2)

In Jetpack Compose, actionable nodes (e.g. `Button`) often appear as bare `android.view.View` nodes without their own text; the visual label resides on a child `TextView` ("Save", "Search").
- **Shared `NodeLabels.kt`:** Both capture (`SelectorExtractor`) and matching (`CandidateScorer`, `SelectorMatcher`) share `WindowSnapshot.labelOf(node, touchPoint): DerivedLabel(text, contentDescription, sourceNodeId)`.
- **Merged-Semantics Duplicates Collapse (Bug 1 Fix):** When a candidate in the candidate set is the label source (`derivedLabel.sourceNodeId`) of another candidate that is an actionable ancestor (clickable/long-clickable/editable or has `CLICK`), the label-source child is dropped from the candidate set so the actionable parent is uniquely matched without ambiguity (e.g., Settings gear `View` child vs clickable `View` parent; Save `TextView` vs clickable `View` parent).
- **Compose Floating Labels (Bug 2 Fix):** For editable nodes (`node.editable`), `labelOf` accepts descendant labels whose bounds intersect the node's bounds or lie within 48 px above its top edge (floating label on focused `OutlinedTextField`), still preferring fully-inside ones. The strict bounding rule (`isInside`) is kept for non-editable nodes.
- **Actionable Ancestor Candidate Generation (Bug 3 Fix):** Candidate generation directly includes every actionable ancestor (up to 3 levels) of nodes whose own text/description equals the locator value when `labelOf(ancestor)` yields that value. This guarantees the actionable parent receives the exact `TEXT_EXACT` or `CONTENT_DESCRIPTION_EXACT` anchor even if no other anchor fires.
- **Editable Text Fields (Fix 2):** For editable nodes (`target.editable`), `SelectorExtractor` never records the typed text value as a `TEXT_EXACT` locator. Instead, it extracts the hint (`hintText`, or text when showing the hint), content description, Compose TextField label child (via `labelOf`), and resource ID / test tag. At replay, `CandidateScorer` matches `TEXT_EXACT` against the editable candidate's `hintText` and its `derivedLabel.text`.

### Scoring Table

Scoring begins with the **strongest matching anchor**:

| Anchor | Points |
|---|---|
| Exact full resource ID | 70 |
| Exact content description | 65 |
| Exact normalized text | 60 |
| $\ge 80\%$ ancestor-chain match + terminal class | 55 |
| Exact row context + terminal class | 55 |
| Coordinate proximity alone ($\text{dist} \le 0.25$) | 35 |

#### Corroborations (Cap at 100)

- Exact class/role match: **+8**
- Ancestor-chain similarity: **+$\text{round}(8 \times \text{similarity})$**
- Row-context Jaccard similarity:
  - Outside collections: **+$\text{round}(8 \times \text{similarity})$**
  - In collections: **+12** when Jaccard similarity is 1.0 (decisive row context), otherwise **+$\text{round}(8 \times \text{similarity})$**
- Exact sibling index: **+5** (ignored / 0 for candidates in collections)
- Bounds proximity: **+$\text{round}(6 \times (1 - \min(1, \text{dist} / 0.25)))$** (retained in collections — Fix 4)
- Required action supported: **+5** (including `NodeAction.FOCUS` for focusable nodes)
- Each secondary exact text/desc locator: **+4**
- **Unique Exact Anchor (Fix 3):** **+10** bonus awarded when exactly ONE candidate in the selected window passes hard filters with an exact ID, text, desc, or derived label anchor. Recorded in `ScoreBreakdown.uniqueExactAnchor = true` and `uniqueExactAnchorCorroboration = 10`.

#### Contradiction Penalties

- Different non-null resource ID: **−35**
- Mismatch of a locator marked `STABLE`: **−15 each**
- Class/role mismatch: **−15**
- Ancestor similarity $< 0.5$: **−15**
- Row-context similarity $< 0.5$:
  - Outside collections: **−10**
  - In collections (when rowContext is present on selector): **−20**
- Candidate center outside recorded bounds expanded by 15%: **−10** (not applied in collections)

$$\text{finalScore} = \min(100, \text{rawScore}) - \text{penalties}$$

### List Context Rules (`inCollection`, Fix 4)

Element matching applies distinct scoring regimes to scrolling/collection content versus static layouts:

1. **Collection Definition:** A candidate is defined as `inCollection` if:
   - The candidate node or one of its ancestors is a direct child of a scrollable node, OR
   - The candidate node or one of its ancestors has `collectionRow != null` or `collectionColumn != null`, OR
   - The selector has a non-null `scrollContainer` or `rowContext`.
2. **Collection Regime Changes (Fix 4):**
   - **Bounds Proximity Retained:** Plain scrollable pages (Compose `verticalScroll`, `ScrollView` forms) keep the bounds-proximity corroboration (+6 when close) to ensure unique controls reach the 75 confidence floor.
   - **Position Penalty Dropped:** The "centre outside recorded bounds expanded 15%" contradiction penalty (−10) is NOT applied in collections.
   - **Sibling Index Ignored:** Sibling index (+5 corroboration and siblingIndex comparison) is ignored for the node itself and for every ancestor at or below the list item.
   - **Decisive Row Context:** Jaccard similarity of 1.0 awards **+12** corroboration (instead of +8). When rowContext is present on the selector, a candidate whose row-context similarity is $< 0.5$ receives a **−20** contradiction penalty (instead of −10).
3. **Outside Collections:** Standard baseline scoring is retained exactly.

### Decision Thresholds

Candidates are sorted deterministically by `score` descending, then `nodeId` ascending:
1. $\text{best.score} < 75 \implies \mathbf{LowConfidence}$
2. $\text{second.score} \ge 75 \text{ and } (\text{best.score} - \text{second.score}) < 12 \implies \mathbf{Ambiguous}$
3. Otherwise $\implies \mathbf{Accepted}$

### Score Reproducibility (`ScoreBreakdown`)

Every candidate contains a complete `ScoreBreakdown` data structure with named fields for every anchor, corroboration, and penalty, as well as a boolean `inCollection` indicating whether the list-context regime applied. This allows execution traces to explain and reproduce match scores down to the single point.
