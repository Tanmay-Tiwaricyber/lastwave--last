package com.lastwave.app.ui.wrapped

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.lastwave.app.data.repository.WrappedDates
import com.lastwave.app.data.repository.WrappedInsightsBuilder
import com.lastwave.app.data.repository.WrappedReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  Wrapped share posters (v5.4.0; three styles since v5.5.0)
 * ────────────────────────────────────────────────────────────────────────────
 * Draws the recap onto a 1080×1920 PNG with plain [Canvas] calls rather than a
 * Compose graphics-layer capture. That keeps the export deterministic — no
 * device density, font-scale or GPU differences leak into a shared image — and
 * it runs off the composition thread.
 *
 * Layout was proofed against a pixel-accurate render before the numbers were
 * committed here, so the baselines below are deliberate, not guesses.
 */
object WrappedPoster {

    const val WIDTH = 1080
    const val HEIGHT = 1920

    private const val PAD = 96f
    private const val ZINC_950 = 0xFF09090B.toInt()
    private const val ZINC_900 = 0xFF18181B.toInt()
    private const val ZINC_800 = 0xFF27272A.toInt()
    private const val ZINC_700 = 0xFF3F3F46.toInt()
    private const val ZINC_500 = 0xFF71717B.toInt()
    private const val ZINC_400 = 0xFF9F9FA9.toInt()
    private const val GREEN_400 = 0xFF05DF72.toInt()

    /** The three share designs (v5.5.0). */
    enum class Style { SUMMARY, RECEIPT, CALENDAR }

    /** The year grid needs twelve months to make sense as a picture. */
    fun supports(style: Style, report: WrappedReport): Boolean = when (style) {
        Style.CALENDAR -> report.insights.months.size == 12
        else -> true
    }

    /**
     * Render the recap. [covers] is optional: when the caller could resolve
     * artwork for a track (keyed by videoId or title) it is composited into the
     * "on repeat" row, otherwise a zinc placeholder keeps the grid intact.
     */
    fun render(
        report: WrappedReport,
        typefaces: Typefaces = Typefaces.SYSTEM,
        covers: Map<String, Bitmap> = emptyMap(),
        style: Style = Style.SUMMARY,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(ZINC_950)
        drawBackdrop(c)
        when (style) {
            Style.SUMMARY -> {
                drawHeader(c, report, typefaces, "Wrapped", "A recap of what you actually played.")
                drawFingerprint(
                    c, WIDTH - PAD - 116f, 236f, 116f,
                    report.minutesByHour, report.insights.minutesByWeekday,
                )
                drawHeadline(c, report, typefaces)
                drawStatGrid(c, report, typefaces)
                drawTopTracks(c, report, typefaces, covers)
                drawPersonality(c, report, typefaces)
            }
            Style.RECEIPT -> drawReceipt(c, report, typefaces)
            Style.CALENDAR -> {
                drawHeader(c, report, typefaces, "In days", "Every square is a day. Brighter means more music.")
                drawCalendar(c, report, typefaces)
            }
        }
        drawFooter(c, report, typefaces)
        return bmp
    }

    // ── Sections ────────────────────────────────────────────────────────────

    private fun drawBackdrop(c: Canvas) {
        val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, WIDTH.toFloat(), HEIGHT * 0.62f,
                intArrayOf(0x2E05DF72, 0x1405DF72, 0x0009090B),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT * 0.62f, wash)

        // Vertical rails at the margins: the site's border-edge motif without
        // ever cutting through type.
        val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x0EFFFFFF
            strokeWidth = 2f
        }
        c.drawLine(PAD - 28f, 120f, PAD - 28f, 1800f, rail)
        c.drawLine(WIDTH - PAD + 28f, 120f, WIDTH - PAD + 28f, 1800f, rail)
    }

    private fun drawHeader(c: Canvas, report: WrappedReport, tf: Typefaces, title: String, subtitle: String) {
        val eyebrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 34f
            color = GREEN_400
            letterSpacing = 0.12f
        }
        c.drawText("LASTWAVE  ·  ${report.window.period.label.uppercase()}", PAD, 170f, eyebrow)

        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sansBold
            textSize = 78f
            color = Color.WHITE
            letterSpacing = -0.02f
        }
        c.drawText(title, PAD, 272f, brand)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 30f
            color = ZINC_400
        }
        c.drawText(subtitle, PAD, 328f, sub)
    }

    private fun drawHeadline(c: Canvas, report: WrappedReport, tf: Typefaces) {
        val minutes = report.totalMinutes
        val showHours = minutes >= 6000
        val big = formatThousands(if (showHours) minutes / 60 else minutes)
        val unit = if (showHours) "HOURS" else "MINUTES"

        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sansBold
            textSize = 250f
            color = Color.WHITE
            letterSpacing = -0.04f
            isSubpixelText = true
        }
        c.drawText(big, PAD - 6f, 600f, numberPaint)

        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 40f
            color = GREEN_400
            letterSpacing = 0.18f
        }
        c.drawText("$unit LISTENED", PAD, 668f, unitPaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sans
            textSize = 40f
            color = ZINC_400
        }
        drawWrappedText(c, headlineLine(report), PAD, 742f, WIDTH - PAD * 2, linePaint, maxLines = 2)
    }

    private fun drawStatGrid(c: Canvas, report: WrappedReport, tf: Typefaces) {
        val top = 840f
        val gap = 22f
        val cellW = (WIDTH - PAD * 2 - 24f) / 2f
        val cellH = 156f
        val cells = buildList {
            add("PLAYS" to formatThousands(report.totalPlays.toLong()))
            add("ARTISTS" to formatThousands(report.distinctArtists.toLong()))
            add("DAYS ACTIVE" to formatThousands(report.activeDays.toLong()))
            add("LONGEST STREAK" to "${report.longestStreakDays}d")
        }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ZINC_900 }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ZINC_800
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 24f
            color = ZINC_500
            letterSpacing = 0.1f
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sansBold
            textSize = 66f
            color = Color.WHITE
            letterSpacing = -0.02f
        }
        cells.forEachIndexed { index, (label, value) ->
            val left = PAD + (index % 2) * (cellW + 24f)
            val topY = top + (index / 2) * (cellH + gap)
            val rect = RectF(left, topY, left + cellW, topY + cellH)
            c.drawRoundRect(rect, 26f, 26f, bg)
            c.drawRoundRect(rect, 26f, 26f, ring)
            c.drawText(label, left + 34f, topY + 48f, labelPaint)
            c.drawText(value, left + 34f, topY + 120f, valuePaint)
        }
    }

    private fun drawTopTracks(
        c: Canvas,
        report: WrappedReport,
        tf: Typefaces,
        covers: Map<String, Bitmap>,
    ) {
        val sectionTop = 1246f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 28f
            color = ZINC_500
            letterSpacing = 0.12f
        }
        c.drawText("ON REPEAT", PAD, sectionTop, title)

        val tracks = report.topTracks.take(3)
        if (tracks.isEmpty()) {
            val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf.sans
                textSize = 38f
                color = ZINC_400
            }
            c.drawText("Not enough listening yet — play something.", PAD, sectionTop + 80f, empty)
            return
        }

        val rankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 32f
        }
        val artBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ZINC_800 }
        val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sansBold
            textSize = 40f
            color = Color.WHITE
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sans
            textSize = 30f
            color = ZINC_400
        }
        val minsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 28f
            color = ZINC_500
            textAlign = Paint.Align.RIGHT
        }

        var y = sectionTop + 44f
        tracks.forEachIndexed { i, entry ->
            rankPaint.color = if (i == 0) GREEN_400 else ZINC_500
            c.drawText("0${i + 1}", PAD, y + 40f, rankPaint)

            val art = RectF(PAD + 66f, y + 2f, PAD + 142f, y + 78f)
            val cover = covers[entry.videoId ?: entry.title]
            if (cover != null) {
                val save = c.save()
                c.clipPath(Path().apply { addRoundRect(art, 14f, 14f, Path.Direction.CW) })
                c.drawBitmap(cover, null, art, artPaint)
                c.restoreToCount(save)
            } else {
                c.drawRoundRect(art, 14f, 14f, artBg)
            }

            // Text stops short of the time on the right, however long the title is.
            val time = "${formatThousands(entry.totalMs / 60_000L)}m"
            val textWidth = WIDTH - PAD * 2 - 172f - minsPaint.measureText(time) - 32f
            c.drawText(clip(entry.title, namePaint, textWidth), PAD + 172f, y + 34f, namePaint)
            c.drawText(clip(entry.subtitle, artistPaint, textWidth), PAD + 172f, y + 68f, artistPaint)
            c.drawText(time, WIDTH - PAD, y + 54f, minsPaint)
            y += 100f
        }
    }

    private fun drawPersonality(c: Canvas, report: WrappedReport, tf: Typefaces) {
        val y = 1636f
        val pill = RectF(PAD, y, WIDTH - PAD, y + 136f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1A05DF72 }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x4005DF72
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        c.drawRoundRect(pill, 26f, 26f, fill)
        c.drawRoundRect(pill, 26f, 26f, ring)

        val archetype = report.insights.archetype
        val hasArchetype = archetype.axes.isNotEmpty()

        val kicker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 24f
            color = GREEN_400
            letterSpacing = 0.14f
        }
        c.drawText(if (hasArchetype) "YOUR ARCHETYPE" else "YOU ARE A", PAD + 36f, y + 50f, kicker)

        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sansBold
            textSize = 52f
            color = Color.WHITE
        }
        c.drawText(if (hasArchetype) archetype.name else report.listenerPersonality, PAD + 36f, y + 106f, name)

        if (hasArchetype) {
            val code = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = tf.mono
                textSize = 34f
                color = GREEN_400
                letterSpacing = 0.06f
                textAlign = Paint.Align.RIGHT
            }
            c.drawText(archetype.code.toList().joinToString("·"), WIDTH - PAD - 36f, y + 102f, code)
        }
    }

    private fun drawFooter(c: Canvas, report: WrappedReport, tf: Typefaces) {
        val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ZINC_800 }
        c.drawRect(PAD, 1812f, WIDTH - PAD, 1814f, hair)

        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sansBold
            textSize = 34f
            color = Color.WHITE
        }
        c.drawText("LastWave", PAD, 1878f, mark)

        val by = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 24f
            color = ZINC_500
            letterSpacing = 0.02f
        }
        c.drawText("BY SILENT PROGRAMMER", PAD + 176f, 1872f, by)

        val stamp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 24f
            color = ZINC_500
            textAlign = Paint.Align.RIGHT
        }
        val now = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date())
        c.drawText(now.uppercase(), WIDTH - PAD, 1872f, stamp)
    }

    /**
     * The "sound fingerprint": 24 spokes for the hours of the day and a ring of
     * seven arcs for the weekdays, sized by how much was played in each. It is
     * drawn from the listener's own numbers, so no two look alike.
     */
    private fun drawFingerprint(
        c: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        hours: List<Long>,
        weekdays: List<Long>,
    ) {
        val spoke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = radius * 0.055f
        }
        val maxHour = (hours.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        for (h in 0 until 24) {
            val v = (hours.getOrElse(h) { 0L } / maxHour).coerceIn(0f, 1f)
            val angle = Math.toRadians(-90.0 + h * 15.0)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val r0 = radius * 0.42f
            val r1 = r0 + radius * (0.08f + 0.50f * v)
            spoke.color = if (v > 0f) withAlpha(GREEN_400, 0.35f + 0.65f * v) else ZINC_700
            c.drawLine(cx + cosA * r0, cy + sinA * r0, cx + cosA * r1, cy + sinA * r1, spoke)
        }

        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val maxDay = (weekdays.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val ringRadius = radius * 0.29f
        val box = RectF(cx - ringRadius, cy - ringRadius, cx + ringRadius, cy + ringRadius)
        val sweep = 360f / 7f
        for (d in 0 until 7) {
            val w = (weekdays.getOrElse(d) { 0L } / maxDay).coerceIn(0f, 1f)
            ring.color = if (w > 0f) withAlpha(GREEN_400, 0.3f + 0.7f * w) else ZINC_700
            ring.strokeWidth = (radius * (0.02f + 0.07f * w)).coerceAtLeast(2f)
            c.drawArc(box, -90f + d * sweep + 4f, sweep - 8f, false, ring)
        }

        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GREEN_400 }
        c.drawCircle(cx, cy, radius * 0.07f, dot)
    }

    /** A terminal-style receipt: every number on one mono-spaced card. */
    private fun drawReceipt(c: Canvas, report: WrappedReport, tf: Typefaces) {
        val insights = report.insights
        val right = WIDTH - PAD

        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.monoRegular
            textSize = 34f
        }
        val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 34f
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.monoRegular
            textSize = 26f
        }

        // Prompt line
        strong.color = GREEN_400
        c.drawText("$ ", PAD, 170f, strong)
        val promptX = PAD + strong.measureText("$ ")
        strong.color = Color.WHITE
        val flag = report.window.period.label.lowercase().replace(' ', '-')
        c.drawText(clip("lastwave wrapped --$flag", strong, right - promptX), promptX, 170f, strong)
        small.color = ZINC_500
        val now = SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date()).lowercase()
        c.drawText("# generated on this device · $now", PAD, 226f, small)

        // Totals with dotted leaders
        val rows = buildList {
            add("MINUTES" to formatThousands(report.totalMinutes))
            add("PLAYS" to formatThousands(report.totalPlays.toLong()))
            add("TRACKS" to formatThousands(report.distinctTracks.toLong()))
            add("ARTISTS" to formatThousands(report.distinctArtists.toLong()))
            add("DAYS ACTIVE" to formatThousands(report.activeDays.toLong()))
            add("LONGEST STREAK" to "${report.longestStreakDays}d")
            insights.completionPercent?.let { add("FINISHED" to "$it%") }
        }
        val dotWidth = body.measureText(".")
        var y = 330f
        for ((label, value) in rows) {
            body.color = ZINC_400
            body.textAlign = Paint.Align.LEFT
            c.drawText(label, PAD, y, body)
            strong.color = Color.WHITE
            strong.textAlign = Paint.Align.RIGHT
            c.drawText(value, right, y, strong)
            val from = PAD + body.measureText(label) + dotWidth
            val to = right - strong.measureText(value) - dotWidth
            val dots = ((to - from) / dotWidth).toInt()
            if (dots > 0) {
                body.color = ZINC_700
                body.textAlign = Paint.Align.RIGHT
                c.drawText(".".repeat(dots), to, y, body)
            }
            y += 54f
        }
        body.textAlign = Paint.Align.LEFT
        strong.textAlign = Paint.Align.LEFT

        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 34f
            color = GREEN_400
            letterSpacing = 0.04f
        }

        // Top tracks: "01 Title · Artist            57m"
        c.drawText("## TOP TRACKS", PAD, 800f, heading)
        y = 864f
        report.topTracks.take(5).forEachIndexed { i, entry ->
            val time = shortDuration(entry.totalMs)
            body.color = if (i == 0) GREEN_400 else ZINC_500
            c.drawText("0${i + 1}", PAD, y, body)
            body.color = ZINC_500
            body.textAlign = Paint.Align.RIGHT
            c.drawText(time, right, y, body)
            body.textAlign = Paint.Align.LEFT
            val maxWidth = right - PAD - 70f - body.measureText(time) - 30f
            val title = clip(entry.title, strong, maxWidth)
            strong.color = Color.WHITE
            c.drawText(title, PAD + 70f, y, strong)
            val used = strong.measureText(title)
            if (entry.subtitle.isNotBlank() && used + body.measureText(" · Ab…") < maxWidth) {
                c.drawText(clip(" · ${entry.subtitle}", body, maxWidth - used), PAD + 70f + used, y, body)
            }
            y += 54f
        }

        c.drawText("## TOP ARTISTS", PAD, 1180f, heading)
        y = 1244f
        report.topArtists.take(5).forEachIndexed { i, entry ->
            val time = shortDuration(entry.totalMs)
            body.color = if (i == 0) GREEN_400 else ZINC_500
            c.drawText("0${i + 1}", PAD, y, body)
            body.color = ZINC_500
            body.textAlign = Paint.Align.RIGHT
            c.drawText(time, right, y, body)
            body.textAlign = Paint.Align.LEFT
            strong.color = Color.WHITE
            c.drawText(clip(entry.title, strong, right - PAD - 70f - body.measureText(time) - 30f), PAD + 70f, y, strong)
            y += 54f
        }

        val archetype = insights.archetype
        c.drawText("## ARCHETYPE", PAD, 1560f, heading)
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sansBold
            textSize = 56f
            color = Color.WHITE
        }
        c.drawText(archetype.name, PAD, 1632f, name)
        if (archetype.axes.isNotEmpty()) {
            strong.color = GREEN_400
            strong.textAlign = Paint.Align.RIGHT
            c.drawText("[${archetype.code.toList().joinToString("·")}]", right, 1628f, strong)
            strong.textAlign = Paint.Align.LEFT
        }
        small.color = ZINC_400
        drawWrappedText(c, archetype.tagline, PAD, 1690f, right - PAD, small, maxLines = 2)
    }

    /** Twelve small month calendars, each day shaded by minutes played. */
    private fun drawCalendar(c: Canvas, report: WrappedReport, tf: Typefaces) {
        val insights = report.insights
        val minutesByDay = insights.minutesByDay()
        val maxMinutes = insights.maxDayMinutes

        val cell = 30f
        val pitch = 36f
        val blockWidth = 7 * pitch - (pitch - cell)
        val columnGap = (WIDTH - PAD * 2 - 3 * blockWidth) / 2f
        val labelHeight = 40f
        val blockHeight = labelHeight + 6 * pitch
        val rowGap = 34f
        val top = 430f

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 24f
            color = ZINC_500
            letterSpacing = 0.1f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()

        insights.months.take(12).forEachIndexed { i, month ->
            val grid = month.grid
            val ox = PAD + (i % 3) * (blockWidth + columnGap)
            val oy = top + (i / 3) * (blockHeight + rowGap)
            c.drawText(grid.shortLabel.uppercase(), ox, oy + 26f, label)
            for (d in 0 until grid.daysInMonth) {
                val slot = grid.leadingBlanks + d
                val x = ox + (slot % 7) * pitch
                val y = oy + labelHeight + (slot / 7) * pitch
                val level = WrappedInsightsBuilder.intensity(minutesByDay[grid.firstDayIndex + d] ?: 0L, maxMinutes)
                fill.color = when (level) {
                    0 -> ZINC_900
                    1 -> withAlpha(GREEN_400, 0.30f)
                    2 -> withAlpha(GREEN_400, 0.50f)
                    3 -> withAlpha(GREEN_400, 0.75f)
                    else -> GREEN_400
                }
                rect.set(x, y, x + cell, y + cell)
                c.drawRoundRect(rect, 7f, 7f, fill)
            }
        }

        val bestDay = insights.topWeekday?.let { WrappedDates.WEEKDAYS_SHORT[it] } ?: "—"
        val stats = listOf(
            "DAYS ACTIVE" to formatThousands(report.activeDays.toLong()),
            "LONGEST STREAK" to "${report.longestStreakDays}d",
            "BEST DAY" to bestDay,
        )
        val y = 1606f
        val cellWidth = (WIDTH - PAD * 2 - 2 * 20f) / 3f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ZINC_900 }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ZINC_800
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val statLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.mono
            textSize = 21f
            color = ZINC_500
            letterSpacing = 0.1f
        }
        val statValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf.sansBold
            textSize = 52f
            color = Color.WHITE
        }
        stats.forEachIndexed { i, (name, value) ->
            val left = PAD + i * (cellWidth + 20f)
            rect.set(left, y, left + cellWidth, y + 136f)
            c.drawRoundRect(rect, 24f, 24f, bg)
            c.drawRoundRect(rect, 24f, 24f, ring)
            c.drawText(name, left + 26f, y + 44f, statLabel)
            c.drawText(value, left + 26f, y + 106f, statValue)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun headlineLine(report: WrappedReport): String {
        val topArtist = report.topArtists.firstOrNull()?.title
        return when {
            report.totalPlays == 0 || topArtist == null ->
                "Your recap starts with the next track you play."
            report.topTrackSharePercent >= 25 && report.topTracks.isNotEmpty() ->
                "${report.topTracks.first().title} alone took ${report.topTrackSharePercent}% of your listening time."
            else ->
                "$topArtist led the way — ${formatThousands(report.totalPlays.toLong())} plays across ${report.distinctArtists} artists."
        }
    }

    private fun drawWrappedText(
        c: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint,
        maxLines: Int,
    ) {
        val words = text.split(' ')
        var line = StringBuilder()
        var lineY = y
        var drawn = 0
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth) {
                c.drawText(line.toString(), x, lineY, paint)
                drawn++
                if (drawn >= maxLines) return
                line = StringBuilder(word)
                lineY += paint.textSize * 1.3f
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) c.drawText(line.toString(), x, lineY, paint)
    }

    /** Ellipsizes [text] until it fits [maxWidth]. */
    private fun clip(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var cut = text
        while (cut.length > 1 && paint.measureText("$cut…") > maxWidth) {
            cut = cut.dropLast(1)
        }
        return "$cut…"
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    /** "57m", "12h 4m" — compact enough for a table column. */
    fun shortDuration(ms: Long): String {
        val minutes = ms / 60_000L
        return if (minutes < 60L) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
    }

    fun formatThousands(value: Long): String {
        val s = abs(value).toString()
        val out = StringBuilder()
        s.reversed().forEachIndexed { i, ch ->
            if (i > 0 && i % 3 == 0) out.append(',')
            out.append(ch)
        }
        return (if (value < 0) "-" else "") + out.reverse().toString()
    }

    /**
     * Bundled fonts when the caller can supply them, system equivalents when
     * not — the renderer never needs a Context, so it stays unit-testable.
     */
    data class Typefaces(
        val sans: Typeface,
        val sansBold: Typeface,
        val mono: Typeface,
        /** Lighter mono for long runs of text; defaults to [mono]. */
        val monoRegular: Typeface = mono,
    ) {
        companion object {
            val SYSTEM = Typefaces(
                sans = Typeface.create("sans-serif", Typeface.NORMAL),
                sansBold = Typeface.create("sans-serif", Typeface.BOLD),
                mono = Typeface.create("monospace", Typeface.NORMAL),
            )
        }
    }
}
