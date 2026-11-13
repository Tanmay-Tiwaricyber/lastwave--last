package com.lastwave.app.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lastwave.app.ui.theme.HairlineWidth
import com.lastwave.app.ui.theme.SpEyebrowStyle

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  Sleep timer picker (v5.4.0)
 * ────────────────────────────────────────────────────────────────────────────
 * Three modes in one hairline-styled sheet, matching the Silent Programmer
 * design language: a mono eyebrow, flat option rows with 1px rings, and a
 * single accent check on the active choice.
 *
 *  • Time    — 15 / 30 / 45 / 60 / 90 minutes
 *  • Tracks  — 5 / 10 / 15 / 25 tracks
 *  • Queue   — stop when the current queue ends (no endless radio)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    minutesRemainingMs: Long?,
    tracksRemaining: Int?,
    endOfQueue: Boolean,
    onPickMinutes: (Int) -> Unit,
    onPickTracks: (Int) -> Unit,
    onPickEndOfQueue: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeMinutes = minutesRemainingMs?.let { ((it + 59_999L) / 60_000L).toInt() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(HairlineWidth, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Sleep timer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "playback stops by itself",
                        style = SpEyebrowStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.padding(top = 16.dp))
            Text("TIME", style = SpEyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
            OptionGrid(
                options = listOf(15, 30, 45, 60, 90),
                selected = activeMinutes,
                labelOf = { "$it min" },
                onPick = onPickMinutes,
            )

            Spacer(Modifier.padding(top = 14.dp))
            Text("TRACKS", style = SpEyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
            OptionGrid(
                options = listOf(5, 10, 15, 25),
                selected = tracksRemaining,
                labelOf = { "$it tracks" },
                onPick = onPickTracks,
            )

            Spacer(Modifier.padding(top = 14.dp))
            Text("QUEUE", style = SpEyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
            OptionRow(
                title = "Stop at end of queue",
                subtitle = "no endless radio after the last queued track",
                selected = endOfQueue,
                onPick = onPickEndOfQueue,
            )

            if (minutesRemainingMs != null || tracksRemaining != null || endOfQueue) {
                Spacer(Modifier.padding(top = 12.dp))
                OptionRow(
                    title = "Turn off",
                    subtitle = "cancel the running sleep timer",
                    selected = false,
                    onPick = onCancel,
                )
            }
        }
    }
}

@Composable
private fun OptionGrid(
    options: List<Int>,
    selected: Int?,
    labelOf: (Int) -> String,
    onPick: (Int) -> Unit,
) {
    // Wrap-free two-row grid: five chips read fine split 3 + 2.
    options.chunked(3).forEach { rowChunk ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowChunk.forEach { value ->
                val isSelected = selected == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                        )
                        .border(
                            HairlineWidth,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onPick(value) }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        labelOf(value),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            repeat(3 - rowChunk.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                HairlineWidth,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
