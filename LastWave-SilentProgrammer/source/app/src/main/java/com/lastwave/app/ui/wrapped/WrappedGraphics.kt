package com.lastwave.app.ui.wrapped

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import coil.compose.AsyncImage
import com.lastwave.app.R
import com.lastwave.app.data.repository.ArchetypeAxis
import com.lastwave.app.data.repository.Badge
import com.lastwave.app.data.repository.WrappedEntry
import com.lastwave.app.ui.theme.PlexMono
import com.lastwave.app.ui.theme.PlexSans
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  Wrapped graphics (v5.6.0)
 * ────────────────────────────────────────────────────────────────────────────
 * Every picture in the story is drawn here from the listener's own numbers:
 * nothing is a stock illustration. Two clocks drive the motion and both are
 * plain lambdas read while drawing, so a moving picture never recomposes:
 *
 *  - `grow` / `t` runs 0 → 1 once, as a chapter arrives.
 *  - `time` is seconds since the story opened and never stops. With motion
 *    reduced it is frozen, which leaves every picture as a still.
 */

/** Typefaces for the story. Swappable so tests can load the fonts from disk. */
internal class WrappedType(
    val sans: FontFamily,
    val mono: FontFamily,
    val nativeMono: Typeface? = null,
)

internal val LocalWrappedType = staticCompositionLocalOf { WrappedType(PlexSans, PlexMono) }

@Composable
internal fun rememberNativeMono(): Typeface {
    val provided = LocalWrappedType.current.nativeMono
    val context = LocalContext.current
    return remember(provided) {
        provided
            ?: runCatching { ResourcesCompat.getFont(context, R.font.plex_mono_medium) }.getOrNull()
            ?: Typeface.MONOSPACE
    }
}

private const val TAU = 6.2831855f

/** Repeatable pseudo-random 0..1, so a picture is the same on every frame. */
internal fun rnd(i: Int, salt: Int): Float {
    val x = sin(i * 12.9898 + salt * 78.233) * 43758.5453
    return (x - floor(x)).toFloat()
}

/** Eases past 1 and settles back: used for things that "pop" in. */
internal fun backOut(p: Float): Float {
    val x = p.coerceIn(0f, 1f) - 1f
    return 1f + 2.7f * x * x * x + 1.7f * x * x
}

internal fun accentAt(index: Int): Color =
    WrappedPalette.Accents[Math.floorMod(index, WrappedPalette.Accents.size)]

// ── Backdrop ────────────────────────────────────────────────────────────────

internal enum class BackdropPattern { RIPPLE, RIBBONS, DOTS, STARS, RAYS, CONFETTI }

internal fun backdropFor(page: StoryPage): BackdropPattern = when (page) {
    StoryPage.INTRO, StoryPage.TOP_ARTIST, StoryPage.CLOCK -> BackdropPattern.RIPPLE
    StoryPage.TIME, StoryPage.RHYTHM, StoryPage.BEGINNING -> BackdropPattern.RIBBONS
    StoryPage.CALENDAR, StoryPage.HABITS, StoryPage.BADGES -> BackdropPattern.DOTS
    StoryPage.DISCOVERY, StoryPage.ARCHETYPE -> BackdropPattern.STARS
    StoryPage.TOP_TRACK, StoryPage.TOP_TRACKS, StoryPage.TOP_ARTISTS -> BackdropPattern.RAYS
    StoryPage.FINALE -> BackdropPattern.CONFETTI
}

/**
 * The moving wall behind a chapter: three slow colour clouds plus one pattern.
 * Kept faint on purpose — text sits directly on top of it.
 */
@Composable
internal fun StoryBackdrop(
    pattern: BackdropPattern,
    accent: Color,
    second: Color,
    time: () -> Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val s = time()
        val w = size.width
        val h = size.height
        for (i in 0 until 3) {
            val centre = Offset(
                w * (0.5f + 0.42f * sin(s * (0.13f + i * 0.04f) + i * 2.1f)),
                h * (0.24f + i * 0.2f + 0.10f * cos(s * (0.11f + i * 0.03f) + i * 1.3f)),
            )
            val radius = w * (0.9f - i * 0.1f)
            val alpha = when (i) {
                0 -> 0.30f
                1 -> 0.17f
                else -> 0.12f
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf((if (i == 1) second else accent).copy(alpha = alpha), Color.Transparent),
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
            )
        }
        when (pattern) {
            BackdropPattern.RIPPLE -> ripples(accent, s)
            BackdropPattern.RIBBONS -> ribbons(accent, s)
            BackdropPattern.DOTS -> dotField(accent, s)
            BackdropPattern.STARS -> starField(s)
            BackdropPattern.RAYS -> rays(accent, s)
            BackdropPattern.CONFETTI -> {
                rays(accent, s)
                confetti(s)
            }
        }
    }
}

private fun DrawScope.ripples(color: Color, s: Float) {
    val centre = Offset(size.width * 0.5f, size.height * 0.34f)
    val reach = size.width * 0.95f
    for (i in 0 until 5) {
        val f = (s * 0.11f + i / 5f) % 1f
        drawCircle(
            color = color.copy(alpha = (1f - f) * 0.20f),
            radius = reach * f,
            center = centre,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

private fun DrawScope.ribbons(color: Color, s: Float) {
    val w = size.width
    val h = size.height
    val step = w / 24f
    for (i in 0 until 4) {
        val base = h * (0.66f + i * 0.07f)
        val amp = h * (0.018f + i * 0.006f)
        val path = Path().apply {
            moveTo(0f, h)
            var x = 0f
            while (x <= w + step) {
                lineTo(x, base + amp * sin(x / w * TAU * (1.2f + i * 0.35f) + s * (0.6f + i * 0.17f) + i * 1.7f))
                x += step
            }
            lineTo(w, h)
            close()
        }
        drawPath(path, color.copy(alpha = 0.05f + i * 0.012f))
    }
}

private fun DrawScope.dotField(color: Color, s: Float) {
    val pitch = 30.dp.toPx()
    val radius = 1.3.dp.toPx()
    var y = pitch / 2f
    while (y < size.height) {
        var x = pitch / 2f
        while (x < size.width) {
            val wave = max(0f, sin(x * 0.011f + y * 0.007f - s * 1.1f))
            drawCircle(color.copy(alpha = 0.06f + 0.22f * wave * wave * wave), radius, Offset(x, y))
            x += pitch
        }
        y += pitch
    }
}

private fun DrawScope.starField(s: Float) {
    for (i in 0 until 70) {
        val twinkle = abs(sin(s * (0.5f + rnd(i, 3) * 1.4f) + rnd(i, 4) * TAU))
        drawCircle(
            color = Color.White.copy(alpha = 0.10f + 0.45f * twinkle),
            radius = (0.7f + rnd(i, 5) * 1.3f).dp.toPx(),
            center = Offset(rnd(i, 1) * size.width, rnd(i, 2) * size.height),
        )
    }
}

private fun DrawScope.rays(color: Color, s: Float) {
    val origin = Offset(size.width * 0.5f, size.height * 0.30f)
    val reach = size.maxDimension * 1.2f
    val count = 16
    val half = TAU / count * 0.22f
    rotate(degrees = s * 3f, pivot = origin) {
        for (i in 0 until count) {
            val a = TAU * i / count
            val path = Path().apply {
                moveTo(origin.x, origin.y)
                lineTo(origin.x + cos(a - half) * reach, origin.y + sin(a - half) * reach)
                lineTo(origin.x + cos(a + half) * reach, origin.y + sin(a + half) * reach)
                close()
            }
            drawPath(path, color.copy(alpha = 0.045f))
        }
    }
}

private fun DrawScope.confetti(s: Float) {
    val pw = 6.dp.toPx()
    val ph = 11.dp.toPx()
    for (i in 0 until 64) {
        val speed = 0.05f + rnd(i, 13) * 0.07f
        val fall = (rnd(i, 12) + s * speed) % 1.15f
        val x = rnd(i, 11) * size.width + sin(s * (0.8f + rnd(i, 14)) + i) * 16.dp.toPx()
        val y = fall * size.height - ph * 2f
        val spin = s * (60f + rnd(i, 15) * 140f) * (if (i % 2 == 0) 1f else -1f) + rnd(i, 16) * 360f
        rotate(degrees = spin, pivot = Offset(x, y)) {
            drawRect(
                color = accentAt(i).copy(alpha = 0.8f),
                topLeft = Offset(x - pw / 2f, y - ph / 2f),
                // Narrowing the width with the spin reads as a tumbling ribbon.
                size = Size(pw * (0.35f + 0.65f * abs(cos(s * 2.3f + i))), ph),
            )
        }
    }
}

// ── Small glyphs ────────────────────────────────────────────────────────────

internal fun DrawScope.moonGlyph(centre: Offset, r: Float, color: Color) {
    val full = Path().apply { addOval(Rect(centre, r)) }
    val bite = Path().apply { addOval(Rect(Offset(centre.x + r * 0.42f, centre.y - r * 0.28f), r * 0.86f)) }
    drawPath(Path.combine(PathOperation.Difference, full, bite), color)
}

internal fun DrawScope.sunGlyph(centre: Offset, r: Float, color: Color) {
    drawCircle(color, r * 0.46f, centre)
    for (i in 0 until 8) {
        val a = TAU * i / 8f
        drawLine(
            color = color,
            start = Offset(centre.x + cos(a) * r * 0.68f, centre.y + sin(a) * r * 0.68f),
            end = Offset(centre.x + cos(a) * r, centre.y + sin(a) * r),
            strokeWidth = r * 0.16f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.playGlyph(centre: Offset, r: Float, color: Color) {
    val path = Path().apply {
        moveTo(centre.x - r * 0.42f, centre.y - r * 0.62f)
        lineTo(centre.x + r * 0.70f, centre.y)
        lineTo(centre.x - r * 0.42f, centre.y + r * 0.62f)
        close()
    }
    drawPath(path, color)
}

// ── Artwork ─────────────────────────────────────────────────────────────────

/**
 * Cover art, with a drawn stand-in underneath. Local files and anything that
 * has not loaded yet show the stand-in rather than an empty square.
 */
@Composable
internal fun StoryArt(
    url: String?,
    accent: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
) {
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.55f).compositeOver(WrappedPalette.Zinc900), WrappedPalette.Zinc800),
                ),
            ),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val r = size.minDimension / 2f
            drawCircle(Color.White.copy(alpha = 0.10f), r * 0.60f, style = Stroke(r * 0.05f))
            drawCircle(Color.White.copy(alpha = 0.16f), r * 0.32f, style = Stroke(r * 0.05f))
            drawCircle(Color.White.copy(alpha = 0.30f), r * 0.08f)
        }
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** A record with the cover as its label, turning, inside a ring of level bars. */
@Composable
internal fun Vinyl(
    artUrl: String?,
    accent: Color,
    time: () -> Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 220.dp,
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = time()
            val r = size.minDimension / 2f
            val bars = 64
            for (i in 0 until bars) {
                val a = TAU * i / bars
                val level = 0.25f + 0.75f * abs(sin(s * 2.1f + i * 0.61f) * sin(s * 1.3f + i * 0.23f))
                val r0 = r * 0.84f
                val r1 = r0 + r * 0.15f * level
                drawLine(
                    color = accent.copy(alpha = 0.30f + 0.6f * level),
                    start = Offset(center.x + cos(a) * r0, center.y + sin(a) * r0),
                    end = Offset(center.x + cos(a) * r1, center.y + sin(a) * r1),
                    strokeWidth = r * 0.022f,
                    cap = StrokeCap.Round,
                )
            }
            val disc = r * 0.78f
            drawCircle(Color(0xFF0C0C0E), disc)
            for (g in 0 until 9) {
                drawCircle(Color.White.copy(alpha = 0.06f), disc * (0.54f + g * 0.05f), style = Stroke(1f))
            }
            // The light stays put while the record turns under it.
            drawCircle(
                brush = Brush.sweepGradient(
                    0.00f to Color.Transparent,
                    0.10f to Color.White.copy(alpha = 0.13f),
                    0.20f to Color.Transparent,
                    0.50f to Color.Transparent,
                    0.60f to Color.White.copy(alpha = 0.13f),
                    0.70f to Color.Transparent,
                    1.00f to Color.Transparent,
                    center = center,
                ),
                radius = disc,
            )
            drawCircle(accent.copy(alpha = 0.85f), disc, style = Stroke(r * 0.012f))
        }
        StoryArt(
            url = artUrl,
            accent = accent,
            modifier = Modifier
                .size(diameter * 0.37f)
                .graphicsLayer { rotationZ = time() * 45f },
            shape = CircleShape,
        )
        Box(
            Modifier
                .size(diameter * 0.035f)
                .clip(CircleShape)
                .background(WrappedPalette.Zinc950),
        )
    }
}

/** The artist at the centre, with small bodies circling on three orbits. */
@Composable
internal fun OrbitArt(
    artUrl: String?,
    accent: Color,
    time: () -> Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 230.dp,
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = time()
            val r = size.minDimension / 2f
            for (ring in 0 until 3) {
                val rr = r * (0.66f + ring * 0.15f)
                drawCircle(accent.copy(alpha = 0.30f - ring * 0.08f), rr, style = Stroke(1.dp.toPx()))
                val bodies = ring + 1
                for (b in 0 until bodies) {
                    val direction = if (ring % 2 == 0) 1f else -1f
                    val a = s * (0.55f - ring * 0.13f) * direction + TAU * b / bodies + ring * 1.1f
                    val p = Offset(center.x + cos(a) * rr, center.y + sin(a) * rr)
                    drawCircle(accentAt(ring + b + 1).copy(alpha = 0.25f), (7 - ring).dp.toPx() * 1.9f, p)
                    drawCircle(accentAt(ring + b + 1), (7 - ring).dp.toPx(), p)
                }
            }
        }
        StoryArt(
            url = artUrl,
            accent = accent,
            modifier = Modifier
                .size(diameter * 0.52f)
                .border(2.dp, accent, CircleShape)
                .padding(4.dp),
            shape = CircleShape,
        )
    }
}

// ── Amounts ─────────────────────────────────────────────────────────────────

/** What one dot stands for, chosen so the whole total fits in a small grid. */
internal fun dotUnit(totalMinutes: Long, maxDots: Int = 144): Pair<Long, String> {
    val units = listOf(
        1L to "1 MINUTE",
        5L to "5 MINUTES",
        15L to "15 MINUTES",
        60L to "1 HOUR",
        180L to "3 HOURS",
        360L to "6 HOURS",
        720L to "12 HOURS",
        1_440L to "1 DAY",
        4_320L to "3 DAYS",
        10_080L to "1 WEEK",
    )
    return units.firstOrNull { (totalMinutes + it.first - 1) / it.first <= maxDots } ?: units.last()
}

/** The total as a field of dots that light up one after another. */
@Composable
internal fun AmountDots(
    totalMinutes: Long,
    color: Color,
    idle: Color,
    modifier: Modifier = Modifier,
    grow: () -> Float = { 1f },
) {
    val columns = 18
    val unit = dotUnit(totalMinutes).first
    val count = ((totalMinutes + unit - 1) / unit).toInt().coerceIn(1, columns * 8)
    val rows = (count + columns - 1) / columns
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(columns.toFloat() / rows.toFloat())
            .clearAndSetSemantics { },
    ) {
        val pitch = size.width / columns
        val lit = count * grow().coerceIn(0f, 1f)
        for (i in 0 until rows * columns) {
            val centre = Offset((i % columns + 0.5f) * pitch, (i / columns + 0.5f) * pitch)
            if (i < count) {
                val a = (lit - i).coerceIn(0f, 1f)
                drawCircle(idle, pitch * 0.30f, centre)
                if (a > 0f) drawCircle(color.copy(alpha = a), pitch * (0.22f + 0.12f * a), centre)
            } else {
                drawCircle(idle.copy(alpha = idle.alpha * 0.45f), pitch * 0.16f, centre)
            }
        }
    }
}

/** A ring that fills to [fraction]; whatever is passed as [content] sits inside. */
@Composable
internal fun RingGauge(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    grow: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = size.minDimension * 0.085f
            val r = size.minDimension / 2f - stroke
            val box = Size(r * 2f, r * 2f)
            val topLeft = Offset(center.x - r, center.y - r)
            drawArc(track, 0f, 360f, false, topLeft, box, style = Stroke(stroke))
            val sweep = 360f * fraction.coerceIn(0f, 1f) * grow().coerceIn(0f, 1f)
            if (sweep > 0.5f) {
                rotate(-90f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to color.copy(alpha = 0.35f),
                            (sweep / 360f).coerceAtLeast(0.01f) to color,
                            1f to color,
                            center = center,
                        ),
                        startAngle = 0f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = box,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    val a = Math.toRadians(sweep.toDouble())
                    val tip = Offset(center.x + cos(a).toFloat() * r, center.y + sin(a).toFloat() * r)
                    drawCircle(color.copy(alpha = 0.25f), stroke * 1.1f, tip)
                    drawCircle(Color.White, stroke * 0.22f, tip)
                }
            }
        }
        content()
    }
}

// ── Time of day and week ────────────────────────────────────────────────────

/**
 * Twenty-four bars around a dial, midnight at the top. The thin outer band is
 * violet for night (8 pm – 5 am) and amber for day, matching the archetype's
 * Day / Night axis.
 */
@Composable
internal fun RadialClock(
    hours: List<Long>,
    color: Color,
    idle: Color,
    labelColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    grow: () -> Float = { 1f },
    time: (() -> Float)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val mono = rememberNativeMono()
    val labelArgb = labelColor.toArgb()
    val paint = remember(mono, labelArgb) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono
            this.color = labelArgb
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Box(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val g = grow().coerceIn(0f, 1f)
            val r = size.minDimension / 2f
            val peak = (hours.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
            val peakIndex = hours.indexOf(hours.maxOrNull() ?: 0L)
            val inner = r * 0.40f
            val reach = r * 0.36f
            val width = TAU * inner / 24f * 0.62f

            // Day / night band. Hour h starts at -90° + 15°·h.
            val bandR = r * 0.80f
            val band = Size(bandR * 2f, bandR * 2f)
            val bandTopLeft = Offset(center.x - bandR, center.y - bandR)
            // Night is 22:00 to 05:00, the same window the personality uses.
            drawArc(WrappedPalette.Violet.copy(alpha = 0.8f), -90f + 22 * 15f + 1.5f, 7 * 15f - 3f, false, bandTopLeft, band, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            drawArc(WrappedPalette.Amber.copy(alpha = 0.8f), -90f + 5 * 15f + 1.5f, 17 * 15f - 3f, false, bandTopLeft, band, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            // Hour ticks outside the band, every third one longer.
            for (h in 0 until 24) {
                val a = Math.toRadians(-90.0 + h * 15.0)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val r0 = r * 0.855f
                val r1 = r * if (h % 6 == 0) 0.905f else 0.885f
                drawLine(
                    color = labelColor.copy(alpha = if (h % 6 == 0) 0.55f else 0.25f),
                    start = Offset(center.x + ca * r0, center.y + sa * r0),
                    end = Offset(center.x + ca * r1, center.y + sa * r1),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            for (h in 0 until 24) {
                val v = (hours.getOrElse(h) { 0L } / peak).coerceIn(0f, 1f)
                val a = Math.toRadians(-90.0 + (h + 0.5) * 15.0)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val r1 = inner + if (v > 0f) reach * (0.10f + 0.90f * v * g) else reach * 0.05f
                drawLine(
                    color = when {
                        v <= 0f -> idle
                        h == peakIndex -> color
                        else -> color.copy(alpha = 0.35f + 0.45f * v)
                    },
                    start = Offset(center.x + ca * inner, center.y + sa * inner),
                    end = Offset(center.x + ca * r1, center.y + sa * r1),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }

            time?.let {
                val a = Math.toRadians(-90.0 + (it() * 30.0) % 360.0)
                drawLine(
                    color = labelColor.copy(alpha = 0.35f),
                    start = Offset(center.x + cos(a).toFloat() * inner * 0.9f, center.y + sin(a).toFloat() * inner * 0.9f),
                    end = Offset(center.x + cos(a).toFloat() * bandR, center.y + sin(a).toFloat() * bandR),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            paint.textSize = r * 0.105f
            val labelR = r * 0.965f
            val drop = paint.textSize * 0.36f
            val canvas = drawContext.canvas.nativeCanvas
            canvas.drawText("00", center.x, center.y - labelR + drop, paint)
            canvas.drawText("12", center.x, center.y + labelR + drop, paint)
            canvas.drawText("06", center.x + labelR, center.y + drop, paint)
            canvas.drawText("18", center.x - labelR, center.y + drop, paint)
        }
        content()
    }
}

/** Seven columns of stacked blocks, lit from the bottom like a level meter. */
@Composable
internal fun LevelMeter(
    values: List<Long>,
    labels: List<String>,
    color: Color,
    idle: Color,
    labelColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 124.dp,
    grow: () -> Float = { 1f },
) {
    val mono = rememberNativeMono()
    val labelArgb = labelColor.toArgb()
    val paint = remember(mono, labelArgb) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono
            this.color = labelArgb
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        val levels = 10
        val labelBand = 18.dp.toPx()
        val column = size.width / values.size.coerceAtLeast(1)
        val blockW = column * 0.66f
        val gap = 3.dp.toPx()
        val blockH = (size.height - labelBand - gap * (levels - 1)) / levels
        val peak = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val peakIndex = values.indexOf(values.maxOrNull() ?: 0L)
        val g = grow().coerceIn(0f, 1f)
        paint.textSize = 10.sp.toPx()
        values.forEachIndexed { i, value ->
            val target = if (value > 0L) ceil(value / peak * levels).coerceIn(1f, levels.toFloat()) else 0f
            val lit = target * g
            val x = i * column + (column - blockW) / 2f
            for (level in 0 until levels) {
                val y = size.height - labelBand - (level + 1) * blockH - level * gap
                val on = (lit - level).coerceIn(0f, 1f)
                drawRoundRect(idle, Offset(x, y), Size(blockW, blockH), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                if (on > 0f) {
                    val top = level == target.toInt() - 1
                    drawRoundRect(
                        color = color.copy(alpha = on * (if (top && i == peakIndex) 1f else if (top) 0.85f else 0.45f)),
                        topLeft = Offset(x, y),
                        size = Size(blockW, blockH),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                    )
                }
            }
            drawContext.canvas.nativeCanvas.drawText(
                labels.getOrElse(i) { "" },
                i * column + column / 2f,
                size.height - 3.dp.toPx(),
                paint,
            )
        }
    }
}

/** A smooth line through the values with a soft fill, drawn left to right. */
@Composable
internal fun AreaWave(
    values: List<Long>,
    labels: List<String>,
    color: Color,
    labelColor: Color,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
    grow: () -> Float = { 1f },
) {
    val mono = rememberNativeMono()
    val labelArgb = labelColor.toArgb()
    val paint = remember(mono, labelArgb) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono
            this.color = labelArgb
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        if (values.size < 2) return@Canvas
        val labelBand = 18.dp.toPx()
        // Room for the first and last month labels, which are centred on
        // their point and would otherwise be cut by the edge.
        val inset = 16.dp.toPx()
        val chartH = size.height - labelBand
        val peak = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val peakIndex = values.indexOf(values.maxOrNull() ?: 0L)
        val stepX = (size.width - inset * 2f) / (values.size - 1)
        val points = values.mapIndexed { i, v ->
            Offset(inset + i * stepX, chartH - 4.dp.toPx() - (v / peak) * (chartH - 12.dp.toPx()))
        }
        val line = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                val mid = (points[i - 1].x + points[i].x) / 2f
                cubicTo(mid, points[i - 1].y, mid, points[i].y, points[i].x, points[i].y)
            }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(points.last().x, chartH)
            lineTo(points.first().x, chartH)
            close()
        }
        clipRect(right = size.width * grow().coerceIn(0f, 1f)) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.38f), Color.Transparent), endY = chartH))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            if (peakIndex >= 0 && values[peakIndex] > 0L) {
                drawCircle(color.copy(alpha = 0.3f), 8.dp.toPx(), points[peakIndex])
                drawCircle(Color.White, 3.dp.toPx(), points[peakIndex])
            }
        }
        paint.textSize = 10.sp.toPx()
        points.forEachIndexed { i, p ->
            drawContext.canvas.nativeCanvas.drawText(labels.getOrElse(i) { "" }, p.x, size.height - 3.dp.toPx(), paint)
        }
    }
}

// ── Artists as bubbles ──────────────────────────────────────────────────────

/**
 * Packs circles of the given radii so they touch but never overlap, largest
 * first and as close to the first one as they will go. Returns the centres.
 */
internal fun packBubbles(radii: List<Float>, gap: Float = 0f): List<Offset> {
    val centres = ArrayList<Offset>(radii.size)
    for (k in radii.indices) {
        val rk = radii[k]
        when (k) {
            0 -> centres.add(Offset.Zero)
            1 -> {
                val d = radii[0] + rk + gap
                centres.add(Offset(d * 0.94f, -d * 0.34f))
            }
            else -> {
                var best: Offset? = null
                for (i in 0 until k) {
                    for (j in i + 1 until k) {
                        val ci = centres[i]
                        val cj = centres[j]
                        val d = (cj - ci).getDistance()
                        val a = radii[i] + rk + gap
                        val b = radii[j] + rk + gap
                        if (d <= 0f || d > a + b || d < abs(a - b)) continue
                        val along = (a * a - b * b + d * d) / (2f * d)
                        val off = sqrt(max(0f, a * a - along * along))
                        val ux = (cj.x - ci.x) / d
                        val uy = (cj.y - ci.y) / d
                        val base = Offset(ci.x + ux * along, ci.y + uy * along)
                        for (sign in listOf(1f, -1f)) {
                            val c = Offset(base.x - uy * off * sign, base.y + ux * off * sign)
                            val clear = (0 until k).all { m ->
                                (centres[m] - c).getDistance() >= radii[m] + rk + gap - 0.001f
                            }
                            if (clear && (best == null || c.getDistance() < best.getDistance())) best = c
                        }
                    }
                }
                centres.add(best ?: Offset(centres[k - 1].x + radii[k - 1] + rk + gap, centres[k - 1].y))
            }
        }
    }
    return centres
}

/** Top artists as touching circles, each sized by time, drifting slightly. */
@Composable
internal fun BubbleCluster(
    entries: List<WrappedEntry>,
    accent: Color,
    t: () -> Float,
    time: () -> Float,
    modifier: Modifier = Modifier,
    height: Dp = 232.dp,
) {
    if (entries.isEmpty()) return
    val top = entries[0].totalMs.coerceAtLeast(1L).toFloat()
    val radii = remember(entries) {
        entries.map { sqrt(it.totalMs.coerceAtLeast(0L) / top).coerceIn(0.42f, 1f) }
    }
    val centres = remember(radii) { packBubbles(radii, gap = 0.07f) }
    val density = LocalDensity.current
    val sans = LocalWrappedType.current.sans
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
    ) {
        val wPx = constraints.maxWidth.toFloat()
        // A little headroom so the drifting never clips at the edges.
        val hPx = constraints.maxHeight.toFloat() - with(density) { 14.dp.toPx() }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        centres.forEachIndexed { i, c ->
            minX = min(minX, c.x - radii[i])
            maxX = max(maxX, c.x + radii[i])
            minY = min(minY, c.y - radii[i])
            maxY = max(maxY, c.y + radii[i])
        }
        val scale = min(wPx / (maxX - minX), hPx / (maxY - minY))
        val shiftX = (wPx - (maxX - minX) * scale) / 2f - minX * scale
        val shiftY = (constraints.maxHeight - (maxY - minY) * scale) / 2f - minY * scale
        entries.forEachIndexed { i, entry ->
            val rPx = radii[i] * scale
            val left = (centres[i].x * scale + shiftX - rPx).roundToInt()
            val topPx = (centres[i].y * scale + shiftY - rPx).roundToInt()
            Box(
                Modifier
                    .offset { IntOffset(left, topPx) }
                    .size(with(density) { (rPx * 2f).toDp() })
                    .graphicsLayer {
                        val p = ((t() - 0.06f - i * 0.09f) / 0.36f).coerceIn(0f, 1f)
                        val e = backOut(p)
                        scaleX = e
                        scaleY = e
                        alpha = (p * 3f).coerceIn(0f, 1f)
                        translationY = sin(time() * 0.9f + i * 1.4f) * 5.dp.toPx()
                    },
            ) {
                StoryArt(entry.artworkUrl, accentAt(i + 1), Modifier.matchParentSize(), CircleShape)
                Box(
                    Modifier
                        .matchParentSize()
                        .border(if (i == 0) 2.5.dp else 1.5.dp, if (i == 0) accent else Color.White.copy(alpha = 0.35f), CircleShape),
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset { IntOffset((rPx * 0.40f).roundToInt(), (rPx * 0.40f).roundToInt()) }
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (i == 0) accent else WrappedPalette.Zinc800),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}",
                        fontFamily = sans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = if (i == 0) WrappedPalette.Zinc950 else Color.White,
                    )
                }
            }
        }
    }
}

// ── Discovery ───────────────────────────────────────────────────────────────

/** One star per new artist (up to 36), joined up as the chapter arrives. */
@Composable
internal fun Constellation(
    count: Int,
    color: Color,
    time: () -> Float,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    grow: () -> Float = { 1f },
) {
    val stars = count.coerceIn(3, 36)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
    ) {
        val s = time()
        val pad = 14.dp.toPx()
        // The stars ride a shallow wave rather than scattering over the whole
        // box: the joined line then reads as one constellation, not a tangle.
        val points = List(stars) { i ->
            val u = (i + 0.15f + rnd(i, 21) * 0.7f) / stars
            val v = 0.5f + 0.23f * sin(i * 0.85f + rnd(i, 25) * 0.5f) + (rnd(i, 22) - 0.5f) * 0.30f
            Offset(
                pad + u * (size.width - pad * 2f),
                pad + v.coerceIn(0.08f, 0.92f) * (size.height - pad * 2f),
            )
        }
        val drawn = (stars - 1) * grow().coerceIn(0f, 1f)
        for (i in 0 until stars - 1) {
            val part = (drawn - i).coerceIn(0f, 1f)
            if (part <= 0f) break
            val a = points[i]
            val b = points[i + 1]
            val end = Offset(a.x + (b.x - a.x) * part, a.y + (b.y - a.y) * part)
            drawLine(color.copy(alpha = 0.10f), a, end, 3.5.dp.toPx())
            drawLine(color.copy(alpha = 0.45f), a, end, 1.dp.toPx())
        }
        points.forEachIndexed { i, p ->
            val shown = (drawn + 1f - i).coerceIn(0f, 1f)
            if (shown <= 0f) return@forEachIndexed
            val twinkle = 0.6f + 0.4f * abs(sin(s * (0.8f + rnd(i, 23)) + i))
            val r = (1.8f + rnd(i, 24) * 2.2f).dp.toPx() * shown
            drawCircle(color.copy(alpha = 0.22f * twinkle), r * 2.6f, p)
            drawCircle(Color.White.copy(alpha = twinkle), r, p)
            if (i % 4 == 0) {
                val arm = r * 3.2f
                drawLine(Color.White.copy(alpha = 0.55f * twinkle), Offset(p.x - arm, p.y), Offset(p.x + arm, p.y), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.55f * twinkle), Offset(p.x, p.y - arm), Offset(p.x, p.y + arm), 1.dp.toPx())
            }
        }
    }
}

// ── Archetype ───────────────────────────────────────────────────────────────

/**
 * The four archetype axes as one shape. Each corner carries the letter of the
 * far end of its axis (N, L, R, F); the further the shape reaches toward a
 * corner, the more that trait applies.
 */
@Composable
internal fun AxisDiamond(
    axes: List<ArchetypeAxis>,
    color: Color,
    grid: Color,
    onColor: Color,
    offColor: Color,
    modifier: Modifier = Modifier,
    grow: () -> Float = { 1f },
) {
    if (axes.size < 4) return
    val mono = rememberNativeMono()
    val paint = remember(mono) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    val onArgb = onColor.toArgb()
    val offArgb = offColor.toArgb()
    Canvas(
        modifier
            .aspectRatio(1f)
            .clearAndSetSemantics { },
    ) {
        val g = grow().coerceIn(0f, 1f)
        val r = size.minDimension / 2f * 0.70f
        val directions = listOf(Offset(0f, -1f), Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f))
        for (ring in 1..3) {
            val rr = r * ring / 3f
            val path = Path().apply {
                directions.forEachIndexed { i, d ->
                    val p = Offset(center.x + d.x * rr, center.y + d.y * rr)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(path, grid, style = Stroke(1.dp.toPx()))
        }
        directions.forEach { d ->
            drawLine(grid, center, Offset(center.x + d.x * r, center.y + d.y * r), 1.dp.toPx())
        }
        val points = directions.mapIndexed { i, d ->
            val reach = r * (0.14f + 0.86f * axes[i].position.coerceIn(0f, 1f) * g)
            Offset(center.x + d.x * reach, center.y + d.y * reach)
        }
        val shape = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            close()
        }
        drawPath(shape, color.copy(alpha = 0.30f))
        drawPath(shape, color, style = Stroke(2.dp.toPx()))
        points.forEach { drawCircle(color, 3.dp.toPx(), it) }

        paint.textSize = size.minDimension * 0.105f
        val drop = paint.textSize * 0.36f
        val labelR = size.minDimension / 2f * 0.88f
        directions.forEachIndexed { i, d ->
            paint.color = if (axes[i].isRight) onArgb else offArgb
            drawContext.canvas.nativeCanvas.drawText(
                axes[i].rightLetter.toString(),
                center.x + d.x * labelR,
                center.y + d.y * labelR + drop,
                paint,
            )
        }
    }
}

/** A card with a three-colour edge and a band of light that keeps crossing it. */
@Composable
internal fun HoloCard(
    accent: Color,
    time: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.22f).compositeOver(WrappedPalette.Zinc900), WrappedPalette.Zinc900),
                ),
            )
            .drawWithContent {
                drawContent()
                val band = size.width * 0.28f
                val x = ((time() * 0.22f) % 1.7f - 0.35f) * size.width
                rotate(18f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent),
                            startX = x - band,
                            endX = x + band,
                        ),
                        topLeft = Offset(x - band, -size.height),
                        size = Size(band * 2f, size.height * 3f),
                    )
                }
            }
            .border(
                1.5.dp,
                Brush.linearGradient(listOf(accent, WrappedPalette.Cyan, WrappedPalette.Violet, accent)),
                shape,
            ),
        content = content,
    )
}

// ── Badges ──────────────────────────────────────────────────────────────────

private fun medalGlyph(id: String): String = when (id) {
    "hour_one" -> "1h"
    "full_day" -> "24h"
    "marathon" -> "3h"
    "week_streak" -> "7d"
    "month_streak" -> "30d"
    "all_week" -> "7/7"
    "on_loop" -> "\u00D710"
    "fifty_spins" -> "\u00D750"
    "wide_ears" -> "50"
    else -> ""
}

/**
 * A six-sided medal. Earned ones are filled, with light passing over them;
 * the rest stay dark with a ring showing how far along they are.
 */
@Composable
internal fun Medal(
    badge: Badge,
    accent: Color,
    idle: Color,
    outline: Color,
    mutedColor: Color,
    modifier: Modifier = Modifier,
    time: () -> Float = { 0f },
) {
    val mono = rememberNativeMono()
    val paint = remember(mono) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = mono
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
    }
    val inkArgb = WrappedPalette.Zinc950.toArgb()
    val mutedArgb = mutedColor.toArgb()
    val phase = remember(badge.id) { rnd(badge.id.hashCode() % 97, 31) }
    Canvas(
        modifier
            .aspectRatio(1f)
            .clearAndSetSemantics { },
    ) {
        val r = size.minDimension / 2f
        fun hexagon(radius: Float) = Path().apply {
            for (i in 0 until 6) {
                val a = TAU * i / 6f - TAU / 4f
                val p = Offset(center.x + cos(a) * radius, center.y + sin(a) * radius)
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        val body = hexagon(r * 0.86f)
        val glyphColor: Color
        if (badge.earned) {
            drawPath(hexagon(r * 0.99f), accent.copy(alpha = 0.22f))
            drawPath(
                body,
                Brush.linearGradient(
                    colors = listOf(Color.White.copy(alpha = 0.55f).compositeOver(accent), accent, Color.Black.copy(alpha = 0.30f).compositeOver(accent)),
                    start = Offset(center.x - r, center.y - r),
                    end = Offset(center.x + r, center.y + r),
                ),
            )
            clipPath(body) {
                val band = r * 0.5f
                val x = ((time() * 0.30f + phase) % 1.9f - 0.45f) * size.width
                rotate(24f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                            startX = x - band,
                            endX = x + band,
                        ),
                        topLeft = Offset(x - band, -size.height),
                        size = Size(band * 2f, size.height * 3f),
                    )
                }
            }
            drawPath(hexagon(r * 0.70f), Color.Black.copy(alpha = 0.16f), style = Stroke(1.dp.toPx()))
            glyphColor = WrappedPalette.Zinc950
            paint.color = inkArgb
        } else {
            drawPath(body, idle)
            drawPath(body, outline, style = Stroke(1.dp.toPx()))
            if (badge.fraction > 0f) {
                val ring = r * 0.97f
                drawArc(
                    color = accent.copy(alpha = 0.85f),
                    startAngle = -90f,
                    sweepAngle = 360f * badge.fraction,
                    useCenter = false,
                    topLeft = Offset(center.x - ring, center.y - ring),
                    size = Size(ring * 2f, ring * 2f),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            glyphColor = mutedColor
            paint.color = mutedArgb
        }
        when (badge.id) {
            "first_spin" -> playGlyph(center, r * 0.42f, glyphColor)
            "night_shift" -> moonGlyph(center, r * 0.40f, glyphColor)
            "early_bird" -> sunGlyph(center, r * 0.44f, glyphColor)
            else -> {
                val text = medalGlyph(badge.id)
                paint.textSize = r * (if (text.length >= 3) 0.50f else 0.62f)
                drawContext.canvas.nativeCanvas.drawText(text, center.x, center.y + paint.textSize * 0.36f, paint)
            }
        }
    }
}
