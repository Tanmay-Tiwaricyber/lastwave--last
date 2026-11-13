package com.lastwave.app.ui.wrapped

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.data.repository.ArchetypeAxis
import com.lastwave.app.data.repository.MonthGrid
import com.lastwave.app.data.repository.WrappedInsightsBuilder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Charts shared by the Wrapped story and the detail page (v5.5.0).
 *
 * Every chart takes its colours as parameters: the story is always drawn on
 * near-black so screenshots match the share posters, while the detail page
 * follows the app theme.
 */

/** Fixed colours for the always-dark story surface. */
internal object WrappedPalette {
    val Zinc950 = Color(0xFF09090B)
    val Zinc900 = Color(0xFF18181B)
    val Zinc800 = Color(0xFF27272A)
    val Zinc700 = Color(0xFF3F3F46)
    val Zinc500 = Color(0xFF71717B)
    val Zinc400 = Color(0xFF9F9FA9)

    val Emerald = Color(0xFF05DF72)
    val Rose = Color(0xFFFB7185)
    val Ocean = Color(0xFF60A5FA)
    val Violet = Color(0xFFA78BFA)
    val Amber = Color(0xFFFBBF24)
    val Cyan = Color(0xFF22D3EE)

    /** One accent per chapter, cycled. */
    val Accents = listOf(Emerald, Cyan, Violet, Rose, Amber, Ocean)
}

/**
 * The "sound fingerprint": 24 spokes for the hours of the day and a ring of
 * seven arcs for the weekdays, each sized by how much was played. Mirrors
 * `WrappedPoster.drawFingerprint` so the app and the shared image agree.
 *
 * [grow] runs 0..1 and is read during drawing, so animating it does not
 * recompose anything.
 */
@Composable
internal fun SoundFingerprint(
    hours: List<Long>,
    weekdays: List<Long>,
    color: Color,
    idle: Color,
    modifier: Modifier = Modifier,
    grow: () -> Float = { 1f },
    /** Seconds; when given, the spokes breathe like a level meter. */
    pulse: (() -> Float)? = null,
) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val g = grow().coerceIn(0f, 1f)
        val beat = pulse?.invoke()
        val radius = size.minDimension / 2f * 0.96f
        val c = center

        val maxHour = (hours.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val stroke = radius * 0.055f
        for (h in 0 until 24) {
            val v = (hours.getOrElse(h) { 0L } / maxHour).coerceIn(0f, 1f)
            val angle = Math.toRadians(-90.0 + h * 15.0)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val r0 = radius * 0.42f
            val breathe = if (beat != null && v > 0f) 0.035f * sin(beat * 2.2f + h * 0.7f) else 0f
            val r1 = r0 + radius * (0.08f + 0.50f * v * g + breathe)
            drawLine(
                color = if (v > 0f) color.copy(alpha = 0.35f + 0.65f * v) else idle,
                start = Offset(c.x + cosA * r0, c.y + sinA * r0),
                end = Offset(c.x + cosA * r1, c.y + sinA * r1),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }

        val maxDay = (weekdays.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val ringRadius = radius * 0.29f
        val sweep = 360f / 7f
        for (d in 0 until 7) {
            val w = (weekdays.getOrElse(d) { 0L } / maxDay).coerceIn(0f, 1f)
            drawArc(
                color = if (w > 0f) color.copy(alpha = 0.3f + 0.7f * w) else idle,
                startAngle = -90f + d * sweep + 4f,
                sweepAngle = (sweep - 8f) * g.coerceAtLeast(0.05f),
                useCenter = false,
                topLeft = Offset(c.x - ringRadius, c.y - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = (radius * (0.02f + 0.07f * w)).coerceAtLeast(2f)),
            )
        }
        drawCircle(color = color, radius = radius * 0.07f, center = c)
    }
}

/**
 * Small month calendars, Monday-first, each day shaded by minutes played —
 * a commit graph for listening. Twelve months lay out as a [columns]-wide
 * grid; a single month fills the width.
 */
@Composable
internal fun MonthHeatmap(
    months: List<MonthGrid>,
    minutesByDay: Map<Long, Long>,
    maxMinutes: Long,
    color: Color,
    empty: Color,
    labelColor: Color,
    columns: Int,
    description: String,
    modifier: Modifier = Modifier,
    /** 0 → 1: the days light up in date order as this rises. */
    reveal: () -> Float = { 1f },
) {
    if (months.isEmpty()) return
    val cols = columns.coerceIn(1, months.size)
    val rows = (months.size + cols - 1) / cols

    // Measured in "cells": cell 1.0, gap 0.2, so a month is 8.2 wide and
    // 1.6 (label) + 7.0 (six weeks) tall.
    val blockW = 8.2f
    val blockH = 8.6f
    val gapX = 1.4f
    val gapY = 1.0f
    val widthUnits = cols * blockW + (cols - 1) * gapX
    val heightUnits = rows * blockH + (rows - 1) * gapY

    val mono = rememberNativeMono()
    val labelArgb = labelColor.toArgb()
    val labelPaint = remember(mono, labelArgb) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = labelArgb
            typeface = mono
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(widthUnits / heightUnits)
            .semantics { contentDescription = description },
    ) {
        val u = size.width / widthUnits
        val shown = reveal().coerceIn(0f, 1f) * 1.2f
        labelPaint.textSize = u * 1.0f
        months.forEachIndexed { i, grid ->
            val ox = (i % cols) * (blockW + gapX) * u
            val oy = (i / cols) * (blockH + gapY) * u
            drawContext.canvas.nativeCanvas.drawText(grid.shortLabel.uppercase(), ox, oy + u * 1.0f, labelPaint)
            for (d in 0 until grid.daysInMonth) {
                val slot = grid.leadingBlanks + d
                val x = ox + (slot % 7) * 1.2f * u
                val y = oy + 1.6f * u + (slot / 7) * 1.2f * u
                val level = WrappedInsightsBuilder.intensity(minutesByDay[grid.firstDayIndex + d] ?: 0L, maxMinutes)
                drawRoundRect(empty, Offset(x, y), Size(u, u), CornerRadius(u * 0.22f, u * 0.22f))
                if (level > 0) {
                    // Position in the whole picture, 0..1, so the wave runs January → December.
                    val order = (i + d / 31f) / months.size
                    val lit = ((shown - order) / 0.2f).coerceIn(0f, 1f)
                    val strength = when (level) {
                        1 -> 0.30f
                        2 -> 0.50f
                        3 -> 0.75f
                        else -> 1f
                    }
                    if (lit > 0f) {
                        drawRoundRect(
                            color = color.copy(alpha = strength * lit),
                            topLeft = Offset(x, y),
                            size = Size(u, u),
                            cornerRadius = CornerRadius(u * 0.22f, u * 0.22f),
                        )
                    }
                }
            }
        }
    }
}

/** One archetype axis: two labels with a marker showing where you land. */
@Composable
internal fun AxisMeter(
    axis: ArchetypeAxis,
    color: Color,
    track: Color,
    textColor: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
    grow: () -> Float = { 1f },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${axis.left} or ${axis.right}: ${axis.winner}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            axis.left,
            modifier = Modifier.width(76.dp),
            fontFamily = LocalWrappedType.current.mono,
            fontSize = 11.sp,
            fontWeight = if (!axis.isRight) FontWeight.Medium else FontWeight.Normal,
            color = if (!axis.isRight) textColor else mutedColor,
            maxLines = 1,
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(18.dp),
        ) {
            val y = size.height / 2f
            drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(track, Offset(size.width / 2f, y - 4.dp.toPx()), Offset(size.width / 2f, y + 4.dp.toPx()), strokeWidth = 1.dp.toPx())
            val p = 0.5f + (axis.position - 0.5f) * grow().coerceIn(0f, 1f)
            drawCircle(color, radius = 6.dp.toPx(), center = Offset(size.width * p.coerceIn(0.04f, 0.96f), y))
        }
        Text(
            axis.right,
            modifier = Modifier.width(76.dp),
            textAlign = TextAlign.End,
            fontFamily = LocalWrappedType.current.mono,
            fontSize = 11.sp,
            fontWeight = if (axis.isRight) FontWeight.Medium else FontWeight.Normal,
            color = if (axis.isRight) textColor else mutedColor,
            maxLines = 1,
        )
    }
}
