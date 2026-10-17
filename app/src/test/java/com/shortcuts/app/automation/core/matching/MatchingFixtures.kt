package com.shortcuts.app.automation.core.matching

import com.shortcuts.app.automation.model.AncestorSelector
import com.shortcuts.app.automation.model.CapturedPoint
import com.shortcuts.app.automation.model.CoordinateSpace
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.LocatorStrategy
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.NodeRole
import com.shortcuts.app.automation.model.NormalizedPoint
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RowContext
import com.shortcuts.app.automation.model.ScreenFingerprint
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType

fun elementSelector(
    strategies: List<LocatorStrategy> = emptyList(),
    expectedClass: String? = null,
    expectedRole: NodeRole? = null,
    ancestorChain: List<AncestorSelector> = emptyList(),
    siblingIndex: Int? = null,
    rowContext: RowContext? = null,
    boundsInWindow: NormalizedRect = NormalizedRect(0f, 0f, 1f, 1f),
    rawPoint: CapturedPoint? = null,
    requiredActions: Set<NodeAction> = emptySet(),
    scrollContainer: ElementSelector? = null
): ElementSelector = ElementSelector(
    strategies = strategies,
    expectedClass = expectedClass,
    expectedRole = expectedRole,
    ancestorChain = ancestorChain,
    siblingIndex = siblingIndex,
    rowContext = rowContext,
    boundsInWindow = boundsInWindow,
    rawPoint = rawPoint,
    requiredActions = requiredActions,
    scrollContainer = scrollContainer
)

fun windowSelector(
    packageName: String = "com.example.app",
    observedActivityClass: String? = null,
    windowType: WindowType = WindowType.APPLICATION,
    titleToken: String? = null,
    boundsOnDisplay: NormalizedRect = NormalizedRect(0f, 0f, 1f, 1f),
    displayId: Int = 0,
    fingerprint: ScreenFingerprint? = null
): WindowSelector = WindowSelector(
    packageName = packageName,
    observedActivityClass = observedActivityClass,
    windowType = windowType,
    titleToken = titleToken,
    boundsOnDisplay = boundsOnDisplay,
    displayId = displayId,
    fingerprint = fingerprint
)

fun idStrategy(
    value: String,
    stability: LocatorStability = LocatorStability.STABLE,
    rank: Int = 0
): LocatorStrategy = LocatorStrategy(
    rank = rank,
    kind = LocatorKind.RESOURCE_ID_EXACT,
    value = value,
    stability = stability
)

fun textStrategy(
    value: String,
    stability: LocatorStability = LocatorStability.STABLE,
    rank: Int = 0
): LocatorStrategy = LocatorStrategy(
    rank = rank,
    kind = LocatorKind.TEXT_EXACT,
    value = value,
    stability = stability
)

fun descStrategy(
    value: String,
    stability: LocatorStability = LocatorStability.STABLE,
    rank: Int = 0
): LocatorStrategy = LocatorStrategy(
    rank = rank,
    kind = LocatorKind.CONTENT_DESCRIPTION_EXACT,
    value = value,
    stability = stability
)

fun dummyCapturedPoint(
    normX: Float = 0.2f,
    normY: Float = 0.15f
): CapturedPoint = CapturedPoint(
    displayPx = IntPoint((normX * 1000).toInt(), (normY * 2000).toInt()),
    pointInWindow = NormalizedPoint(normX, normY),
    pointInNode = NormalizedPoint(0.5f, 0.5f),
    displayWidthPx = 1000,
    displayHeightPx = 2000,
    rotation = 0,
    densityDpi = 420,
    coordinateSpace = CoordinateSpace.WINDOW_NORMALIZED
)
