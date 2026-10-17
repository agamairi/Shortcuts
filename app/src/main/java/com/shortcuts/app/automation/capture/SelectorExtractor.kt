package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.tree.sameClassSiblingIndex

import com.shortcuts.app.automation.core.fingerprint.ScreenFingerprinter
import com.shortcuts.app.automation.core.geometry.NormalizedGeometry
import com.shortcuts.app.automation.core.text.TextNormalizer
import com.shortcuts.app.automation.core.tree.DerivedLabel
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.NodeRoles
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.labelOf
import com.shortcuts.app.automation.model.AncestorSelector
import com.shortcuts.app.automation.model.CapturedPoint
import com.shortcuts.app.automation.model.CoordinateSpace
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.LocatorStrategy
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.RowContext
import com.shortcuts.app.automation.model.TextTokenizer
import com.shortcuts.app.automation.model.WindowSelector
import kotlin.math.roundToInt

/**
 * Selector plus capture-only provenance. [labelSourceNodeId] is deliberately kept separate from
 * [targetNodeId]: a labelled descendant may describe an unlabelled actionable parent, but its
 * class and bounds must never be mistaken for those of the parent.
 */
data class SelectorExtraction(
    val selector: ElementSelector,
    val targetNodeId: Int,
    val labelSourceNodeId: Int?
)

class SelectorExtractor(
    private val screenFingerprinter: ScreenFingerprinter,
    private val textTokenizer: TextTokenizer
) {
    fun extract(
        hitNode: NodeSnapshot,
        window: WindowSnapshot,
        touchPoint: IntPoint?,
        snapshot: WindowSetSnapshot
    ): SelectorExtraction {
        require(hitNode.windowId == window.windowId) { "Hit node does not belong to the supplied window" }
        val derivedLabel = window.labelOf(hitNode, touchPoint)
        val labelSource = derivedLabel.sourceNodeId?.let { window.node(it) } ?: hitNode
        return SelectorExtraction(
            selector = buildSelector(hitNode, labelSource, derivedLabel, window, touchPoint, snapshot),
            targetNodeId = hitNode.nodeId,
            labelSourceNodeId = derivedLabel.sourceNodeId
        )
    }

    fun extract(
        hitNode: NodeSnapshot,
        window: WindowSnapshot,
        touchPoint: com.shortcuts.app.automation.model.TimedPoint?,
        snapshot: WindowSetSnapshot
    ): SelectorExtraction = extract(hitNode, window, touchPoint?.displayPx, snapshot)

    fun elementSelector(
        hitNode: NodeSnapshot,
        window: WindowSnapshot,
        touchPoint: IntPoint?,
        snapshot: WindowSetSnapshot
    ): ElementSelector = extract(hitNode, window, touchPoint, snapshot).selector

    fun windowSelector(
        window: WindowSnapshot,
        snapshot: WindowSetSnapshot,
        activityClass: String?,
        precomputedFingerprint: com.shortcuts.app.automation.model.ScreenFingerprint? = null
    ): WindowSelector {
        val displayBounds = IntRect(0, 0, snapshot.displayWidth, snapshot.displayHeight)
        val normalizedTitle = TextNormalizer.normalize(window.title)
        return WindowSelector(
            packageName = window.packageName,
            observedActivityClass = activityClass,
            windowType = window.type,
            titleToken = normalizedTitle?.let(textTokenizer::tokenize),
            boundsOnDisplay = NormalizedGeometry.normalize(window.boundsInScreen, displayBounds),
            displayId = 0,
            fingerprint = precomputedFingerprint ?: screenFingerprinter.fingerprint(window, activityClass)
        )
    }

    private fun buildSelector(
        target: NodeSnapshot,
        labelSource: NodeSnapshot,
        derivedLabel: com.shortcuts.app.automation.core.tree.DerivedLabel,
        window: WindowSnapshot,
        touchPoint: IntPoint?,
        snapshot: WindowSetSnapshot
    ): ElementSelector {
        val strategies = mutableListOf<LocatorStrategy>()
        addIdentityStrategy(strategies, target.resourceId)

        if (target.editable) {
            // Fix 2: For editable nodes NEVER emit the current value as a TEXT locator;
            // use the hint (hintText, or the text when the node is showing its hint),
            // the content description, the label from labelOf (Compose TextField label child),
            // and the resource id.
            TextNormalizer.normalize(target.contentDescription)?.let {
                addUnique(strategies, LocatorKind.CONTENT_DESCRIPTION_EXACT, it, textStability(it))
            }
            if (!target.password) {
                val hint = TextNormalizer.normalize(target.hintText)
                    ?: if (target.hintText != null && target.text == target.hintText) TextNormalizer.normalize(target.text) else null
                hint?.let {
                    addUnique(strategies, LocatorKind.TEXT_EXACT, it, textStability(it))
                }

                if (derivedLabel.sourceNodeId != null && derivedLabel.sourceNodeId != target.nodeId) {
                    derivedLabel.text?.let {
                        addUnique(strategies, LocatorKind.TEXT_EXACT, it, textStability(it))
                    }
                    derivedLabel.contentDescription?.let {
                        addUnique(strategies, LocatorKind.CONTENT_DESCRIPTION_EXACT, it, textStability(it))
                    }
                }
            }
            testTag(target.resourceId)?.let {
                addUnique(strategies, LocatorKind.TEST_TAG_EXACT, it, LocatorStability.STABLE)
            }
            if (derivedLabel.sourceNodeId != null && derivedLabel.sourceNodeId != target.nodeId) {
                val labelChild = window.node(derivedLabel.sourceNodeId)
                testTag(labelChild?.resourceId)?.let {
                    addUnique(strategies, LocatorKind.TEST_TAG_EXACT, it, LocatorStability.STABLE)
                }
            }
        } else {
            addLabelStrategies(strategies, labelSource, target.password)
        }

        val scrollAncestor = window.ancestors(target).firstOrNull(NodeSnapshot::scrollable)
        return ElementSelector(
            strategies = strategies.mapIndexed { rank, strategy -> strategy.copy(rank = rank) },
            expectedClass = target.className,
            expectedRole = NodeRoles.roleOf(target),
            ancestorChain = window.ancestors(target).take(MAX_ANCESTORS).map { ancestor ->
                AncestorSelector(
                    resourceId = TextNormalizer.normalize(ancestor.resourceId),
                    className = ancestor.className,
                    role = NodeRoles.roleOf(ancestor),
                    siblingIndex = siblingIndex(ancestor, window)
                )
            },
            siblingIndex = siblingIndex(target, window),
            rowContext = rowContext(target, labelSource, window),
            boundsInWindow = NormalizedGeometry.normalize(target.boundsInScreen, window.boundsInScreen),
            rawPoint = touchPoint?.let { capturedPoint(it, target, window, snapshot) },
            requiredActions = requiredActions(target),
            scrollContainer = scrollAncestor?.let { shallowSelector(it, window) }
        )
    }

    private fun shallowSelector(node: NodeSnapshot, window: WindowSnapshot): ElementSelector {
        val strategies = mutableListOf<LocatorStrategy>()
        addIdentityStrategy(strategies, node.resourceId)
        val derivedLabel = window.labelOf(node)
        val labelSource = derivedLabel.sourceNodeId?.let { window.node(it) } ?: node
        addLabelStrategies(strategies, labelSource, node.password)
        return ElementSelector(
            strategies = strategies.mapIndexed { rank, strategy -> strategy.copy(rank = rank) },
            expectedClass = node.className,
            expectedRole = NodeRoles.roleOf(node),
            ancestorChain = emptyList(),
            siblingIndex = siblingIndex(node, window),
            rowContext = null,
            boundsInWindow = NormalizedGeometry.normalize(node.boundsInScreen, window.boundsInScreen),
            rawPoint = null,
            requiredActions = requiredActions(node),
            scrollContainer = null
        )
    }

    private fun addIdentityStrategy(strategies: MutableList<LocatorStrategy>, rawResourceId: String?) {
        val value = TextNormalizer.normalize(rawResourceId) ?: return
        val tag = testTag(value)
        if (tag != null) {
            addUnique(strategies, LocatorKind.TEST_TAG_EXACT, tag, LocatorStability.STABLE)
        } else {
            addUnique(strategies, LocatorKind.RESOURCE_ID_EXACT, value, resourceIdStability(value))
        }
    }

    private fun addLabelStrategies(
        strategies: MutableList<LocatorStrategy>,
        source: NodeSnapshot,
        sensitive: Boolean
    ) {
        TextNormalizer.normalize(source.contentDescription)?.let {
            addUnique(strategies, LocatorKind.CONTENT_DESCRIPTION_EXACT, it, textStability(it))
        }
        if (!sensitive) {
            TextNormalizer.normalize(source.text)?.let {
                addUnique(strategies, LocatorKind.TEXT_EXACT, it, textStability(it))
            }
        }
        testTag(source.resourceId)?.let {
            addUnique(strategies, LocatorKind.TEST_TAG_EXACT, it, LocatorStability.STABLE)
        }
    }

    private fun addUnique(
        strategies: MutableList<LocatorStrategy>,
        kind: LocatorKind,
        value: String,
        stability: LocatorStability
    ) {
        if (strategies.none { it.kind == kind && it.value == value }) {
            strategies += LocatorStrategy(strategies.size, kind, value, stability)
        }
    }

    private fun resourceIdStability(resourceId: String): LocatorStability {
        val terminal = resourceId.substringAfterLast('/').substringAfterLast(':')
        return if (GENERATED_ID.matches(terminal) || LONG_DIGIT_RUN.containsMatchIn(terminal)) {
            LocatorStability.LIKELY_STABLE
        } else {
            LocatorStability.STABLE
        }
    }

    private fun textStability(value: String): LocatorStability =
        if (value.length > MAX_STABLE_TEXT_LENGTH || looksDynamic(value)) {
            LocatorStability.VOLATILE
        } else {
            LocatorStability.STABLE
        }

    private fun looksDynamic(value: String): Boolean = DYNAMIC_TEXT_PATTERNS.any { it.containsMatchIn(value) }

    private fun testTag(resourceId: String?): String? {
        val value = TextNormalizer.normalize(resourceId) ?: return null
        return value.takeUnless(STANDARD_RESOURCE_ID::matches)
    }

    private fun siblingIndex(node: NodeSnapshot, window: WindowSnapshot): Int? =
        window.sameClassSiblingIndex(node)

    private fun rowContext(
        target: NodeSnapshot,
        labelSource: NodeSnapshot,
        window: WindowSnapshot
    ): RowContext? {
        val lineage = listOf(target) + window.ancestors(target)
        val metadataRow = lineage.firstOrNull { it.collectionRow != null || it.collectionColumn != null }
        val scrollContainer = lineage.firstOrNull(NodeSnapshot::scrollable)
        val listRow = scrollContainer?.let { container -> directChildBelow(target, container, window) }
        val row = metadataRow ?: listRow ?: return null
        val excludedLabels = sequenceOf(target, labelSource)
            .flatMap { sequenceOf(it.text, it.contentDescription) }
            .mapNotNull(TextNormalizer::normalize)
            .toSet()
        val contextNodes = listOf(row) + window.descendants(row)
        val texts = contextNodes.asSequence()
            .filterNot { it.nodeId == target.nodeId || it.nodeId == labelSource.nodeId }
            .mapNotNull { TextNormalizer.normalize(it.text) }
            .filterNot(excludedLabels::contains)
            .distinct()
            .take(MAX_ROW_LABELS)
            .toList()
        val descriptions = contextNodes.asSequence()
            .filterNot { it.nodeId == target.nodeId || it.nodeId == labelSource.nodeId }
            .mapNotNull { TextNormalizer.normalize(it.contentDescription) }
            .filterNot(excludedLabels::contains)
            .distinct()
            .take(MAX_ROW_LABELS)
            .toList()
        val container = scrollContainer ?: window.parent(row)
        return RowContext(
            containerClass = container?.className,
            containerResourceId = TextNormalizer.normalize(container?.resourceId),
            siblingText = texts,
            siblingContentDescriptions = descriptions,
            collectionRow = metadataRow?.collectionRow,
            collectionColumn = metadataRow?.collectionColumn
        )
    }

    private fun directChildBelow(
        target: NodeSnapshot,
        ancestor: NodeSnapshot,
        window: WindowSnapshot
    ): NodeSnapshot? {
        var current = target
        var parent = window.parent(current)
        while (parent != null && parent.nodeId != ancestor.nodeId) {
            current = parent
            parent = window.parent(current)
        }
        return current.takeIf { parent?.nodeId == ancestor.nodeId }
    }

    private fun capturedPoint(
        point: IntPoint,
        target: NodeSnapshot,
        window: WindowSnapshot,
        snapshot: WindowSetSnapshot
    ): CapturedPoint = CapturedPoint(
        displayPx = point,
        pointInWindow = NormalizedGeometry.normalize(point.x, point.y, window.boundsInScreen),
        pointInNode = target.boundsInScreen.takeIf { it.width > 0 && it.height > 0 }
            ?.let { NormalizedGeometry.normalize(point.x, point.y, it) },
        displayWidthPx = snapshot.displayWidth,
        displayHeightPx = snapshot.displayHeight,
        rotation = snapshot.rotation,
        densityDpi = (snapshot.density * DENSITY_DEFAULT_DPI).roundToInt(),
        coordinateSpace = CoordinateSpace.WINDOW_NORMALIZED
    )

    private fun requiredActions(node: NodeSnapshot): Set<NodeAction> = buildSet {
        addAll(node.actions)
        if (node.clickable) add(NodeAction.CLICK)
        if (node.longClickable) add(NodeAction.LONG_CLICK)
        if (node.editable) add(NodeAction.SET_TEXT)
        if (node.focusable) add(NodeAction.FOCUS)
        if (node.scrollable) {
            add(NodeAction.SCROLL_FORWARD)
            add(NodeAction.SCROLL_BACKWARD)
        }
    }

    companion object {
        private const val MAX_ANCESTORS = 6
        private const val MAX_ROW_LABELS = 6
        private const val MAX_STABLE_TEXT_LENGTH = 60
        private const val DENSITY_DEFAULT_DPI = 160f
        private val STANDARD_RESOURCE_ID = Regex("^(?:[^:]+:)?id/.+$")
        private val GENERATED_ID = Regex("^[a-z]{1,2}\\d*$", RegexOption.IGNORE_CASE)
        private val LONG_DIGIT_RUN = Regex("\\d{4,}")
        private val DYNAMIC_TEXT_PATTERNS = listOf(
            Regex("(?:[$€£¥]\\s*\\d|\\d[.,]\\d{2}\\s*(?:USD|CAD|EUR|GBP))", RegexOption.IGNORE_CASE),
            Regex("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b"),
            Regex("\\b(?:\\d{1,4}[-/.]){1,2}\\d{1,4}\\b"),
            Regex("(?:^|\\s|\\()\\d+(?:\\s|$|\\))"),
            Regex("\\b\\d+\\s*(?:items?|messages?|notifications?|minutes?|hours?|days?|results?)\\b", RegexOption.IGNORE_CASE)
        )
    }
}
