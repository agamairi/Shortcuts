package com.shortcuts.app.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.planner.DraftStep
import com.shortcuts.app.service.StepResult
import com.shortcuts.app.ui.theme.LocalShortcutsPalette
import com.shortcuts.app.ui.theme.TileColors
import com.shortcuts.app.util.ActionDescriber

// Design token lifted from Editor.dc.html:
//   card background  #FFFFFF = palette.surface
//   card border      1px solid #E8E2DA = palette.outline
//   card radius      20dp
//   icon chip size   40dp, radius 13dp
//   title fontSize   16sp / SemiBold = MaterialTheme.typography.titleSmall
//   muted line       12.5sp = MaterialTheme.typography.bodySmall
//   hairline         1px solid #F2EDE6 (surfaceMuted-ish; use palette.outline)
//   warn border      1.5px solid #E8A33D = palette.warn
//   warn panel       #FDF6EA = palette.warnGround, radius 13dp
//   warn text        12.5sp #7A5A1E = palette.warnInk
//   delete button    44dp×44dp, radius 14dp, border 1px palette.outline, danger stroke

private val CardShape = RoundedCornerShape(20.dp)
private val ChipShape = RoundedCornerShape(13.dp)
private val WarnPanelShape = RoundedCornerShape(13.dp)
private val DeleteBtnShape = RoundedCornerShape(14.dp)
private val FixBtnShape = RoundedCornerShape(14.dp)

/**
 * Artboard-faithful card for one [DraftStep] in the Review-Steps screen.
 *
 * - Resolved step: white card, coloured icon chip, description, muted source line, overflow
 *   affordance. If the action has a platform restriction note it appears separated by a hairline.
 * - Unresolved step: amber-bordered warning card with AI-suggestion panel and one-tap fix.
 */
@Composable
fun ReviewStepCard(
    index: Int,
    totalSteps: Int,
    step: DraftStep,
    result: StepResult?,
    onChooseApp: () -> Unit,
    onDelete: () -> Unit,
    onOpenSettings: (Intent) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalShortcutsPalette.current
    when (step) {
        is DraftStep.Resolved -> ResolvedReviewCard(
            step = step,
            onChooseApp = onChooseApp,
            onDelete = onDelete,
            result = result,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
        is DraftStep.Unresolved -> UnresolvedReviewCard(
            step = step,
            onChooseApp = onChooseApp,
            onDelete = onDelete,
            modifier = modifier
        )
    }
}

@Composable
private fun ResolvedReviewCard(
    step: DraftStep.Resolved,
    onChooseApp: () -> Unit,
    onDelete: () -> Unit,
    result: StepResult?,
    onOpenSettings: (Intent) -> Unit,
    modifier: Modifier
) {
    val palette = LocalShortcutsPalette.current
    val chipColor = chipColorForAction(step.action)
    val restrictionNote = restrictedToggleNote(step.action)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(palette.surface)
            .border(width = 1.dp, color = palette.outline, shape = CardShape)
            .padding(14.dp)
    ) {
        // Icon chip + description + overflow
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 40dp icon chip
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(ChipShape)
                    .background(chipColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconForAction(step.action),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ActionDescriber.describe(step.action),
                    style = MaterialTheme.typography.titleSmall, // 16sp SemiBold
                    color = palette.ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "from \u201c${step.sourceText}\u201d",
                    style = MaterialTheme.typography.bodySmall, // 12.5sp
                    color = palette.inkFaint
                )
            }
            Spacer(Modifier.width(4.dp))
            // Overflow affordance (three vertical dots)
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = palette.outlineDashed,
                modifier = Modifier.size(18.dp)
            )
        }

        // Platform restriction footnote (hairline + info row) — exact artboard pattern
        if (restrictionNote != null) {
            Spacer(Modifier.height(11.dp))
            // hairline separator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.outline)
            )
            Spacer(Modifier.height(11.dp))
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = palette.inkFaint,
                    modifier = Modifier.size(15.dp).padding(top = 1.dp)
                )
                Text(
                    text = restrictionNote,
                    style = MaterialTheme.typography.bodySmall, // 12.5sp
                    color = palette.inkMuted
                )
            }
        }
    }
}

@Composable
private fun UnresolvedReviewCard(
    step: DraftStep.Unresolved,
    onChooseApp: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier
) {
    val palette = LocalShortcutsPalette.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(palette.surface)
            .border(width = 1.5.dp, color = palette.warn, shape = CardShape)
            .padding(14.dp)
    ) {
        // Header row: muted warning icon chip + title + source
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Muted chip (same pattern as artboard — grey chip, warning triangle)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(ChipShape)
                    .background(palette.surfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = palette.inkFaint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Couldn\u2019t work this out",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "from \u201c${step.sourceText}\u201d",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkFaint
                )
            }
        }

        // Amber warning panel  — palette.warnGround background
        Spacer(Modifier.height(11.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(WarnPanelShape)
                .background(palette.warnGround)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = step.reason,
                style = MaterialTheme.typography.bodySmall,
                color = palette.warnInk
            )
        }

        // Action row: "Choose an app" + delete
        Spacer(Modifier.height(11.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "Choose an app" fill button (dark, 44dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(FixBtnShape)
                    .background(palette.ink)
                    .clickable { onChooseApp() },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = null,
                        tint = palette.ground,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Choose an app",
                        style = MaterialTheme.typography.labelLarge, // 14sp SemiBold
                        color = palette.ground
                    )
                }
            }
            // Delete button — 44×44dp, outlined, danger trash icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(DeleteBtnShape)
                    .border(width = 1.dp, color = palette.outline, shape = DeleteBtnShape)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete step",
                    tint = palette.danger,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Dashed "Add a step" card row — artboard: dashed #D6CEC3 border, muted + icon, 20dp radius.
 */
@Composable
fun AddStepCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalShortcutsPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(palette.surface)
            .border(
                width = 1.dp,
                brush = SolidColor(palette.outlineDashed),
                shape = CardShape
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(ChipShape)
                .background(palette.surfaceMuted),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = palette.inkMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = "Add a step",
            style = MaterialTheme.typography.bodyLarge, // 15sp SemiBold
            color = palette.inkMuted
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Returns the chip fill colour matching the action type (uses TileColors for consistency). */
@Composable
private fun chipColorForAction(action: Action): Color = when (action.actionType) {
    ActionType.APP_INTENT     -> TileColors.Blue
    ActionType.SYSTEM_TOGGLE  -> TileColors.Teal
    ActionType.HTTP_REQUEST   -> TileColors.Orange
    ActionType.SEND_MESSAGE   -> TileColors.Green
    ActionType.DIAL_NUMBER    -> TileColors.Green
    ActionType.UI_AUTOMATION  -> TileColors.Purple
}

@Composable
private fun iconForAction(action: Action): androidx.compose.ui.graphics.vector.ImageVector =
    when (action.actionType) {
        ActionType.APP_INTENT    -> Icons.Filled.Apps
        ActionType.SYSTEM_TOGGLE -> Icons.Filled.MoreVert // generic; chip is coloured enough
        ActionType.HTTP_REQUEST  -> Icons.Filled.Info
        ActionType.SEND_MESSAGE  -> Icons.Filled.Add      // placeholder for message icon
        ActionType.DIAL_NUMBER   -> Icons.Filled.Add
        ActionType.UI_AUTOMATION -> Icons.Filled.Add
    }

/**
 * The exact restriction text used in [ActionExecutorService] for system-toggle actions
 * that open a settings panel instead of toggling directly.
 * Mirrors RestrictedToggleNote in the old DraftStepCard but follows the artboard's exact wording.
 */
private fun restrictedToggleNote(action: Action): String? {
    if (action.actionType != ActionType.SYSTEM_TOGGLE) return null
    return when (action.target?.trim()?.lowercase()?.replace("_", "")) {
        "wifi"        -> "Android won\u2019t let apps switch Wi\u2011Fi on directly \u2014 this opens the Wi\u2011Fi panel for you."
        "bluetooth"   -> "Android requires you to confirm Bluetooth changes in its system prompt."
        "airplanemode" -> "Android doesn\u2019t allow apps to change Airplane mode \u2014 this opens its settings instead."
        "location"    -> "Android doesn\u2019t allow apps to change Location directly \u2014 this opens Location settings instead."
        else          -> null
    }
}
