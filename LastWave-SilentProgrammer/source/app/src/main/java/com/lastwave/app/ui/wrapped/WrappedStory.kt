package com.lastwave.app.ui.wrapped

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.abs
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lastwave.app.data.repository.Badge
import com.lastwave.app.data.repository.WrappedCalculator
import com.lastwave.app.data.repository.WrappedDates
import com.lastwave.app.data.repository.WrappedEntry
import com.lastwave.app.data.repository.WrappedInsightsBuilder
import com.lastwave.app.data.repository.WrappedReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ────────────────────────────────────────────────────────────────────────────
 *  Wrapped story mode (v5.5.0; pictures and motion in v5.6.0)
 * ────────────────────────────────────────────────────────────────────────────
 * A full-screen, tap-through telling of the recap: one idea per chapter, each
 * with its own picture drawn from the listener's numbers and a moving
 * backdrop behind it.
 *
 * Controls: tap the right side to go on, the left edge to go back, hold to
 * pause. There are also visible previous / next / pause buttons, so nothing
 * depends on knowing the gesture. With motion reduced the story does not
 * advance by itself and every picture is a still.
 *
 * The surface is always near-black regardless of app theme, so a screenshot
 * of any chapter matches the share posters.
 */

internal enum class StoryPage {
    INTRO, TIME, TOP_TRACK, TOP_TRACKS, TOP_ARTIST, TOP_ARTISTS, CALENDAR, CLOCK,
    RHYTHM, HABITS, BEGINNING, DISCOVERY, ARCHETYPE, BADGES, FINALE,
}

/** Chapters are only included when there is something true to say in them. */
internal fun storyPagesFor(report: WrappedReport): List<StoryPage> {
    val insights = report.insights
    return buildList {
        add(StoryPage.INTRO)
        add(StoryPage.TIME)
        if (report.topTracks.isNotEmpty()) add(StoryPage.TOP_TRACK)
        if (report.topTracks.size >= 3) add(StoryPage.TOP_TRACKS)
        if (insights.topArtist != null) add(StoryPage.TOP_ARTIST)
        if (report.topArtists.size >= 3) add(StoryPage.TOP_ARTISTS)
        if (insights.days.isNotEmpty() && insights.months.isNotEmpty()) add(StoryPage.CALENDAR)
        if (report.minutesByHour.sum() > 0L) add(StoryPage.CLOCK)
        if (report.activeDays >= 3) add(StoryPage.RHYTHM)
        if (report.totalPlays >= 5 && insights.completionPercent != null) add(StoryPage.HABITS)
        if (insights.firstTrack != null) add(StoryPage.BEGINNING)
        if ((insights.newArtists ?: 0) > 0) add(StoryPage.DISCOVERY)
        if (insights.archetype.axes.isNotEmpty()) add(StoryPage.ARCHETYPE)
        if (insights.badges.isNotEmpty()) add(StoryPage.BADGES)
        add(StoryPage.FINALE)
    }
}

private const val PAGE_MS = 8_500
private const val ENTER_MS = 1_700

/** Where the backdrop clock rests when motion is reduced. */
private const val STILL_TIME = 2.4f

private val storySans: FontFamily
    @Composable @ReadOnlyComposable get() = LocalWrappedType.current.sans

private val storyMono: FontFamily
    @Composable @ReadOnlyComposable get() = LocalWrappedType.current.mono

@Composable
internal fun WrappedStory(
    report: WrappedReport,
    reduceMotion: Boolean,
    sharing: Boolean,
    onShare: (WrappedPoster.Style) -> Unit,
    onPlayTop: () -> Unit,
    onClose: () -> Unit,
) {
    val pages = remember(report) { storyPagesFor(report) }
    var index by rememberSaveable(report.window.period.label) { mutableIntStateOf(0) }
    val current = index.coerceIn(0, pages.lastIndex)

    var held by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var userPaused by remember { mutableStateOf(false) }
    val paused = held || userPaused || reduceMotion

    val haptics = LocalHapticFeedback.current
    val go by rememberUpdatedState<(Int) -> Unit> { delta ->
        val next = (index + delta).coerceIn(0, pages.lastIndex)
        if (next != index) {
            index = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Auto-advance timer. `progressPage` records which chapter the timer
    // belongs to, so pausing and resuming continues instead of restarting.
    val progress = remember { Animatable(0f) }
    var progressPage by remember { mutableIntStateOf(-1) }
    LaunchedEffect(current, paused) {
        if (progressPage != current) {
            progress.snapTo(0f)
            progressPage = current
        }
        if (paused) return@LaunchedEffect
        val remaining = ((1f - progress.value) * PAGE_MS).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        if (current < pages.lastIndex) index = current + 1
    }

    // The backdrop clock: seconds since the story opened. Written once per
    // frame and only ever read while drawing.
    val clock = remember { mutableFloatStateOf(if (reduceMotion) STILL_TIME else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            clock.floatValue = STILL_TIME
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) clock.floatValue += ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
            }
        }
    }
    val time: () -> Float = { clock.floatValue }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WrappedPalette.Zinc950)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                    onTap = { offset -> go(if (offset.x < size.width * 0.28f) -1 else 1) },
                )
            }
            // A horizontal drag drags the chapter itself along, then either
            // commits the page or springs back.
            .pointerInput(pages.size) {
                val slop = 12.dp.toPx()
                val width = size.width.toFloat()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dx = change.position.x - down.position.x
                        if (!dragging && abs(dx) > slop) dragging = true
                        if (dragging) {
                            change.consume()
                            dragX = dx.coerceIn(-width, width)
                        }
                    }
                    if (dragging) {
                        val edge = width * 0.16f
                        when {
                            dx < -edge -> go(1)
                            dx > edge -> go(-1)
                        }
                    }
                    dragX = 0f
                }
            },
    ) {
        AnimatedContent(
            targetState = current,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = dragX
                    alpha = 1f - (abs(dragX) / size.width).coerceIn(0f, 1f) * 0.35f
                },
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val direction = if (targetState > initialState) 1 else -1
                    (
                        fadeIn(tween(280, delayMillis = 70)) +
                            scaleIn(tween(360), initialScale = 0.94f) +
                            slideInHorizontally(tween(360)) { it / 7 * direction }
                        ) togetherWith (
                        fadeOut(tween(200)) +
                            scaleOut(tween(260), targetScale = 1.05f) +
                            slideOutHorizontally(tween(260)) { -it / 9 * direction }
                        )
                }
            },
            label = "chapter",
        ) { pageIndex ->
            val safeIndex = pageIndex.coerceIn(0, pages.lastIndex)
            // Each chapter owns its entrance, so the one leaving stays put
            // while the one arriving builds up.
            val enter = remember { Animatable(if (reduceMotion) 1f else 0f) }
            LaunchedEffect(Unit) {
                if (!reduceMotion) enter.animateTo(1f, tween(ENTER_MS, easing = LinearEasing))
            }
            StoryChapterFrame(
                page = pages[safeIndex],
                report = report,
                accent = accentAt(safeIndex),
                second = accentAt(safeIndex + 2),
                t = { enter.value },
                time = time,
                sharing = sharing,
                onShare = onShare,
                onPlayTop = onPlayTop,
                onClose = onClose,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // Progress segments, one per chapter.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pages.indices.forEach { i ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                            .drawBehind {
                                val filled = when {
                                    i < current -> 1f
                                    i == current -> if (reduceMotion) 1f else progress.value
                                    else -> 0f
                                }
                                drawRect(Color.White, size = Size(size.width * filled, size.height))
                            },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "LASTWAVE  \u00B7  ${report.window.period.label.uppercase()}",
                    modifier = Modifier.weight(1f),
                    fontFamily = storyMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 1.4.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                )
                if (!reduceMotion) {
                    IconButton(onClick = { userPaused = !userPaused }) {
                        Icon(
                            if (userPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (userPaused) "Resume story" else "Pause story",
                            tint = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close story", tint = Color.White.copy(alpha = 0.85f))
                }
            }

            Spacer(Modifier.weight(1f))

            // Visible previous / next, so the gesture is never the only way.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { go(-1) }, enabled = current > 0) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Previous chapter",
                        tint = Color.White.copy(alpha = if (current > 0) 0.8f else 0.25f),
                    )
                }
                Text(
                    "${current + 1} / ${pages.size}",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontFamily = storyMono,
                    fontSize = 11.sp,
                    color = WrappedPalette.Zinc500,
                )
                IconButton(onClick = { go(1) }, enabled = current < pages.lastIndex) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next chapter",
                        tint = Color.White.copy(alpha = if (current < pages.lastIndex) 0.8f else 0.25f),
                    )
                }
            }
            // Keeps the controls clear of the floating mini player.
            Spacer(Modifier.height(84.dp))
        }
    }
}

/**
 * One chapter: its backdrop and its content, inset to leave room for the
 * progress bar above and the controls below. [t] is the chapter's entrance
 * (0 → 1) and [time] the never-ending backdrop clock in seconds.
 */
@Composable
internal fun StoryChapterFrame(
    page: StoryPage,
    report: WrappedReport,
    accent: Color,
    second: Color,
    t: () -> Float,
    time: () -> Float,
    sharing: Boolean,
    onShare: (WrappedPoster.Style) -> Unit,
    onPlayTop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        StoryBackdrop(backdropFor(page), accent, second, time, Modifier.matchParentSize())
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 70.dp, bottom = 136.dp),
        ) {
            when (page) {
                StoryPage.INTRO -> IntroPage(report, accent, t, time)
                StoryPage.TIME -> TimePage(report, accent, t)
                StoryPage.TOP_TRACK -> TopTrackPage(report, accent, t, time)
                StoryPage.TOP_TRACKS -> TopTracksPage(report.topTracks, accent, t)
                StoryPage.TOP_ARTIST -> TopArtistPage(report, accent, t, time)
                StoryPage.TOP_ARTISTS -> TopArtistsPage(report.topArtists, accent, t, time)
                StoryPage.CALENDAR -> CalendarPage(report, accent, t)
                StoryPage.CLOCK -> ClockPage(report, accent, t, time)
                StoryPage.RHYTHM -> RhythmPage(report, accent, t)
                StoryPage.HABITS -> HabitsPage(report, accent, t)
                StoryPage.BEGINNING -> BeginningPage(report, accent, t)
                StoryPage.DISCOVERY -> DiscoveryPage(report, accent, t, time)
                StoryPage.ARCHETYPE -> ArchetypePage(report, accent, t, time)
                StoryPage.BADGES -> BadgesPage(report, accent, t, time)
                StoryPage.FINALE -> FinalePage(report, accent, t, time, sharing, onShare, onPlayTop, onClose)
            }
        }
    }
}

// ── Chapters ────────────────────────────────────────────────────────────────

@Composable
private fun IntroPage(report: WrappedReport, accent: Color, t: () -> Float, time: () -> Float) {
    PageFrame(report.window.period.storyTitle, accent, t) {
        SoundFingerprint(
            hours = report.minutesByHour,
            weekdays = report.insights.minutesByWeekday,
            color = accent,
            idle = WrappedPalette.Zinc700,
            modifier = Modifier
                .size(232.dp)
                .align(Alignment.CenterHorizontally)
                .pop(t, 0),
            grow = { (t() / 0.8f).coerceIn(0f, 1f) },
            pulse = time,
        )
        Spacer(Modifier.height(26.dp))
        Headline("Your sound fingerprint", Modifier.reveal(t, 3))
        Spacer(Modifier.height(10.dp))
        Body(
            "Twenty-four spokes for the hours you listen, seven arcs for the days. " +
                "It is drawn from your own listening, so nobody else has this shape.",
            Modifier.reveal(t, 4),
        )
        Spacer(Modifier.height(22.dp))
        Hint("TAP TO CONTINUE  \u00B7  HOLD TO PAUSE", Modifier.reveal(t, 6))
    }
}

@Composable
private fun TimePage(report: WrappedReport, accent: Color, t: () -> Float) {
    val insights = report.insights
    val minutes = report.totalMinutes
    val showHours = minutes >= 6_000
    PageFrame("Time listened", accent, t) {
        CountUp(target = if (showHours) minutes / 60 else minutes, t = t, fontSize = 88.sp)
        Text(
            if (showHours) "HOURS" else "MINUTES",
            fontFamily = storyMono,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            letterSpacing = 3.sp,
            color = accent,
            modifier = Modifier.reveal(t, 2),
        )
        Spacer(Modifier.height(18.dp))
        AmountDots(
            totalMinutes = minutes,
            color = accent,
            idle = WrappedPalette.Zinc800,
            modifier = Modifier.reveal(t, 2),
            grow = { ((t() - 0.15f) / 0.7f).coerceIn(0f, 1f) },
        )
        Spacer(Modifier.height(8.dp))
        Hint("EACH DOT  \u00B7  ${dotUnit(minutes).second}", Modifier.reveal(t, 3))
        Spacer(Modifier.height(8.dp))
        StatRow("END TO END", WrappedInsightsBuilder.nonStop(minutes), Modifier.reveal(t, 4))
        if (report.activeDays > 0) {
            StatRow(
                "PER DAY YOU LISTENED",
                WrappedCalculator.formatDuration(minutes / report.activeDays),
                Modifier.reveal(t, 5),
            )
        }
        val previous = insights.previousMinutes
        val previousLabel = insights.previousLabel
        if (previous != null && previousLabel != null && previous > 0L) {
            val line = if (insights.periodComplete) {
                val delta = ((minutes - previous) * 100 / previous).toInt()
                if (delta >= 0) "+$delta%" else "$delta%"
            } else {
                "${WrappedPoster.formatThousands(previous)} min"
            }
            StatRow(
                if (insights.periodComplete) "VS ${previousLabel.uppercase()}" else "${previousLabel.uppercase()} TOTAL",
                line,
                Modifier.reveal(t, 6),
            )
        } else {
            StatRow("PLAYS", WrappedPoster.formatThousands(report.totalPlays.toLong()), Modifier.reveal(t, 6))
        }
    }
}

@Composable
private fun TopTrackPage(report: WrappedReport, accent: Color, t: () -> Float, time: () -> Float) {
    val top = report.topTracks.first()
    PageFrame("Your number one track", accent, t) {
        Vinyl(
            artUrl = top.artworkUrl,
            accent = accent,
            time = time,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .pop(t, 0),
            diameter = 212.dp,
        )
        Spacer(Modifier.height(14.dp))
        Headline(top.title, Modifier.reveal(t, 2), maxLines = 2, fontSize = 28.sp)
        if (top.subtitle.isNotBlank()) Body(top.subtitle, Modifier.reveal(t, 3), maxLines = 1)
        Spacer(Modifier.height(8.dp))
        StatRow("PLAYS", WrappedPoster.formatThousands(top.plays.toLong()), Modifier.reveal(t, 4))
        StatRow("TIME", WrappedCalculator.formatDuration(top.totalMs / 60_000L), Modifier.reveal(t, 5))
        StatRow("SHARE OF YOUR LISTENING", "${report.topTrackSharePercent}%", Modifier.reveal(t, 6))
    }
}

@Composable
private fun TopTracksPage(entries: List<WrappedEntry>, accent: Color, t: () -> Float) {
    val top = entries.firstOrNull()?.totalMs?.coerceAtLeast(1L)?.toFloat() ?: 1f
    PageFrame("On repeat", accent, t) {
        entries.take(5).forEachIndexed { i, entry ->
            val share = (entry.totalMs / top).coerceIn(0.04f, 1f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .reveal(t, i + 1)
                    .drawBehind {
                        // A bar behind the row, as long as this track's share of the top one.
                        val g = ((t() - 0.12f - i * 0.09f) / 0.5f).coerceIn(0f, 1f)
                        val eased = 1f - (1f - g) * (1f - g)
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(accent.copy(alpha = if (i == 0) 0.34f else 0.20f), accent.copy(alpha = 0.04f)),
                                endX = size.width * share,
                            ),
                            size = Size(size.width * share * eased, size.height),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${i + 1}. ${entry.title}, ${entry.subtitle}"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "0${i + 1}",
                    modifier = Modifier.width(30.dp),
                    fontFamily = storyMono,
                    fontSize = 14.sp,
                    color = if (i == 0) accent else WrappedPalette.Zinc400,
                )
                StoryArt(entry.artworkUrl, accentAt(i + 1), Modifier.size(if (i == 0) 58.dp else 48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        fontFamily = storySans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (i == 0) 21.sp else 17.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.subtitle.isNotBlank()) {
                        Text(
                            entry.subtitle,
                            fontFamily = storySans,
                            fontSize = 14.sp,
                            color = WrappedPalette.Zinc400,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    WrappedPoster.shortDuration(entry.totalMs),
                    fontFamily = storyMono,
                    fontSize = 12.sp,
                    color = WrappedPalette.Zinc400,
                )
            }
        }
    }
}

@Composable
private fun TopArtistPage(report: WrappedReport, accent: Color, t: () -> Float, time: () -> Float) {
    val artist = report.insights.topArtist ?: return
    PageFrame("Your number one artist", accent, t) {
        OrbitArt(
            artUrl = artist.artworkUrl,
            accent = accent,
            time = time,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .pop(t, 0),
            diameter = 216.dp,
        )
        Spacer(Modifier.height(14.dp))
        Headline(artist.name, Modifier.reveal(t, 2), maxLines = 2, fontSize = 30.sp)
        Body("${artist.sharePercent}% of everything you played.", Modifier.reveal(t, 3), maxLines = 1)
        Spacer(Modifier.height(8.dp))
        StatRow("TIME TOGETHER", WrappedCalculator.formatDuration(artist.minutes), Modifier.reveal(t, 4))
        StatRow(
            "DAYS YOU PLAYED THEM",
            WrappedPoster.formatThousands(artist.daysListened.toLong()),
            Modifier.reveal(t, 5),
        )
        artist.topTrack?.let { StatRow("YOUR FAVOURITE", it, Modifier.reveal(t, 6)) }
    }
}

@Composable
private fun TopArtistsPage(entries: List<WrappedEntry>, accent: Color, t: () -> Float, time: () -> Float) {
    val shown = entries.take(5)
    PageFrame("Top artists", accent, t) {
        BubbleCluster(shown, accent, t, time)
        Spacer(Modifier.height(10.dp))
        shown.forEachIndexed { i, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .reveal(t, i + 3)
                    .semantics(mergeDescendants = true) { contentDescription = "${i + 1}. ${entry.title}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "0${i + 1}",
                    modifier = Modifier.width(32.dp),
                    fontFamily = storyMono,
                    fontSize = 13.sp,
                    color = if (i == 0) accent else WrappedPalette.Zinc500,
                )
                Text(
                    entry.title,
                    modifier = Modifier.weight(1f),
                    fontFamily = storySans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (i == 0) 19.sp else 16.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    WrappedPoster.shortDuration(entry.totalMs),
                    fontFamily = storyMono,
                    fontSize = 12.sp,
                    color = WrappedPalette.Zinc400,
                )
            }
        }
    }
}

@Composable
private fun CalendarPage(report: WrappedReport, accent: Color, t: () -> Float) {
    val insights = report.insights
    val single = insights.months.size == 1
    PageFrame("${report.window.period.label} in days", accent, t) {
        MonthHeatmap(
            months = insights.months.map { it.grid },
            minutesByDay = insights.minutesByDay(),
            maxMinutes = insights.maxDayMinutes,
            color = accent,
            empty = WrappedPalette.Zinc800.copy(alpha = 0.8f),
            labelColor = WrappedPalette.Zinc400,
            columns = if (single) 1 else 4,
            description = "Calendar of listening: ${report.activeDays} days active",
            modifier = Modifier
                .fillMaxWidth(if (single) 0.72f else 1f)
                .align(Alignment.CenterHorizontally)
                .reveal(t, 1),
            reveal = { ((t() - 0.12f) / 0.75f).coerceIn(0f, 1f) },
        )
        Spacer(Modifier.height(18.dp))
        Body("Every square is a day. Brighter means more music.", Modifier.reveal(t, 2))
        Spacer(Modifier.height(8.dp))
        StatRow("DAYS ACTIVE", WrappedPoster.formatThousands(report.activeDays.toLong()), Modifier.reveal(t, 3))
        val streak = insights.longestStreak
        StatRow(
            "LONGEST STREAK",
            if (streak != null && report.longestStreakDays > 1) {
                "${report.longestStreakDays}d  \u00B7  ${WrappedDates.shortLabel(streak.first)} \u2192 ${WrappedDates.shortLabel(streak.last)}"
            } else {
                "${report.longestStreakDays}d"
            },
            Modifier.reveal(t, 4),
        )
        StatRow("STREAK RIGHT NOW", "${report.currentStreakDays}d", Modifier.reveal(t, 5))
    }
}

@Composable
private fun ClockPage(report: WrappedReport, accent: Color, t: () -> Float, time: () -> Float) {
    val insights = report.insights
    val hours = report.minutesByHour
    val peakHour = hours.indexOf(hours.maxOrNull() ?: 0L).coerceAtLeast(0)
    val peakLabel = "${peakHour.toString().padStart(2, '0')}:00"
    PageFrame("Listening clock", accent, t) {
        RadialClock(
            hours = hours,
            color = accent,
            idle = WrappedPalette.Zinc700,
            labelColor = WrappedPalette.Zinc400,
            description = "Listening by hour, peaking around $peakHour o'clock",
            modifier = Modifier
                .size(232.dp)
                .align(Alignment.CenterHorizontally)
                .pop(t, 0),
            grow = { ((t() - 0.15f) / 0.6f).coerceIn(0f, 1f) },
            time = time,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(peakLabel, fontFamily = storyMono, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Color.White)
                Text("PEAK", fontFamily = storyMono, fontSize = 9.sp, letterSpacing = 1.4.sp, color = WrappedPalette.Zinc400)
            }
        }
        Spacer(Modifier.height(14.dp))
        Headline(report.listenerPersonality, Modifier.reveal(t, 3), fontSize = 28.sp)
        Body(report.personalityLine, Modifier.reveal(t, 4), maxLines = 2)
        Spacer(Modifier.height(6.dp))
        insights.nightAnthem?.let { StatRow("AFTER-MIDNIGHT ANTHEM", it.title, Modifier.reveal(t, 5)) }
        insights.morningAnthem?.let { StatRow("MORNING ANTHEM", it.title, Modifier.reveal(t, 6)) }
    }
}

@Composable
private fun RhythmPage(report: WrappedReport, accent: Color, t: () -> Float) {
    val insights = report.insights
    val topWeekday = insights.topWeekday
    PageFrame("Your rhythm", accent, t) {
        if (topWeekday != null) {
            Headline("${WrappedDates.WEEKDAYS_LONG[topWeekday]}s are yours.", Modifier.reveal(t, 1), fontSize = 30.sp)
            Spacer(Modifier.height(16.dp))
        }
        LevelMeter(
            values = insights.minutesByWeekday,
            labels = WrappedDates.WEEKDAYS_SHORT.map { it.uppercase() },
            color = accent,
            idle = WrappedPalette.Zinc800.copy(alpha = 0.8f),
            labelColor = WrappedPalette.Zinc400,
            description = "Listening by weekday",
            modifier = Modifier.reveal(t, 2),
            grow = { ((t() - 0.2f) / 0.55f).coerceIn(0f, 1f) },
        )
        if (insights.months.size == 12) {
            Spacer(Modifier.height(16.dp))
            AreaWave(
                values = insights.months.map { it.minutes },
                labels = insights.months.map { it.grid.shortLabel.take(1) },
                color = accent,
                labelColor = WrappedPalette.Zinc400,
                description = "Listening by month",
                modifier = Modifier.reveal(t, 3),
                grow = { ((t() - 0.3f) / 0.6f).coerceIn(0f, 1f) },
            )
        }
        Spacer(Modifier.height(8.dp))
        insights.biggestDay?.let { day ->
            StatRow(
                "BIGGEST DAY",
                "${WrappedDates.label(day.dayIndex)}  \u00B7  ${WrappedCalculator.formatDuration(day.minutes)}",
                Modifier.reveal(t, 5),
            )
            day.topTrack?.let { StatRow("MOSTLY", it, Modifier.reveal(t, 6)) }
        }
    }
}

@Composable
private fun HabitsPage(report: WrappedReport, accent: Color, t: () -> Float) {
    val insights = report.insights
    val completion = insights.completionPercent ?: return
    PageFrame("Habits", accent, t) {
        RingGauge(
            fraction = completion / 100f,
            color = accent,
            track = WrappedPalette.Zinc800,
            modifier = Modifier
                .size(196.dp)
                .align(Alignment.CenterHorizontally)
                .pop(t, 0),
            grow = { val p = (t() / 0.6f).coerceIn(0f, 1f); 1f - (1f - p) * (1f - p) * (1f - p) },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUp(target = completion.toLong(), t = t, fontSize = 60.sp)
                    Text(
                        "%",
                        fontFamily = storySans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                        color = accent,
                        modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                    )
                }
                Text("FINISHED", fontFamily = storyMono, fontSize = 10.sp, letterSpacing = 1.6.sp, color = WrappedPalette.Zinc400)
            }
        }
        Spacer(Modifier.height(14.dp))
        Body("of the songs you started, you played to the end.", Modifier.reveal(t, 3), maxLines = 2)
        Spacer(Modifier.height(6.dp))
        insights.binge?.let {
            StatRow(
                "MOST PLAYS IN ONE DAY",
                "${it.plays}\u00D7  ${it.title}",
                Modifier.reveal(t, 4),
            )
        }
        insights.neverSkipped?.let {
            StatRow("NEVER SKIPPED", "${it.plays}\u00D7  ${it.title}", Modifier.reveal(t, 5))
        }
        insights.mostSkipped?.let {
            StatRow("SKIPPED MOST", "${it.skips} of ${it.plays}  ${it.title}", Modifier.reveal(t, 6))
        }
    }
}

@Composable
private fun BeginningPage(report: WrappedReport, accent: Color, t: () -> Float) {
    val first = report.insights.firstTrack ?: return
    val date = remember(first.playedAtMillis) {
        SimpleDateFormat("d MMM yyyy  \u00B7  HH:mm", Locale.getDefault()).format(Date(first.playedAtMillis))
    }
    PageFrame("Where it started", accent, t) {
        Body("The first thing you played in this recap:", Modifier.reveal(t, 1))
        Spacer(Modifier.height(22.dp))
        // A print pinned at an angle, dropped into place.
        Column(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .graphicsLayer {
                    val p = ((t() - 0.1f) / 0.4f).coerceIn(0f, 1f)
                    val e = backOut(p)
                    rotationZ = -4f - (1f - e) * 12f
                    translationY = -(1f - e) * 70.dp.toPx()
                    alpha = (p * 3f).coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFF4F4F5))
                .padding(start = 10.dp, end = 10.dp, top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StoryArt(first.artworkUrl, accent, Modifier.size(176.dp), RoundedCornerShape(2.dp))
            Text(
                date.uppercase(),
                fontFamily = storyMono,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
                color = WrappedPalette.Zinc700,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Headline(first.title, Modifier.reveal(t, 4), maxLines = 2, fontSize = 28.sp)
        Body(first.artist, Modifier.reveal(t, 5), maxLines = 1)
    }
}

@Composable
private fun DiscoveryPage(report: WrappedReport, accent: Color, t: () -> Float, time: () -> Float) {
    val insights = report.insights
    val count = insights.newArtists ?: return
    PageFrame("Discovery", accent, t) {
        CountUp(target = count.toLong(), t = t, fontSize = 88.sp)
        Body(
            if (count == 1) "artist you had never played before." else "artists you had never played before.",
            Modifier.reveal(t, 2),
        )
        Spacer(Modifier.height(10.dp))
        Constellation(
            count = count,
            color = accent,
            time = time,
            modifier = Modifier.reveal(t, 2),
            grow = { ((t() - 0.2f) / 0.7f).coerceIn(0f, 1f) },
        )
        Hint(if (count > 36) "36 OF $count SHOWN  \u00B7  ONE STAR EACH" else "ONE STAR FOR EACH", Modifier.reveal(t, 3))
        Spacer(Modifier.height(8.dp))
        insights.topNewArtist?.let {
            StatRow("THE ONE THAT STUCK", it.title, Modifier.reveal(t, 4))
            StatRow("TIME WITH THEM", WrappedCalculator.formatDuration(it.totalMs / 60_000L), Modifier.reveal(t, 5))
        }
        if (report.distinctArtists > 0) {
            StatRow("NEW VS ALL ARTISTS", "$count of ${report.distinctArtists}", Modifier.reveal(t, 6))
        }
    }
}

@Composable
private fun ArchetypePage(report: WrappedReport, accent: Color, t: () -> Float, time: () -> Float) {
    val archetype = report.insights.archetype
    PageFrame("Your archetype", accent, t) {
        HoloCard(
            accent = accent,
            time = time,
            modifier = Modifier
                .fillMaxWidth()
                .pop(t, 0),
        ) {
            Row(
                modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        archetype.spacedCode,
                        fontFamily = storyMono,
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp,
                        letterSpacing = 1.5.sp,
                        color = accent,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    Headline(archetype.name, maxLines = 2, fontSize = 30.sp)
                    Spacer(Modifier.height(10.dp))
                    Hint("1 OF 16 TYPES")
                }
                AxisDiamond(
                    axes = archetype.axes,
                    color = accent,
                    grid = Color.White.copy(alpha = 0.14f),
                    onColor = Color.White,
                    offColor = WrappedPalette.Zinc500,
                    modifier = Modifier.size(128.dp),
                    grow = { ((t() - 0.3f) / 0.5f).coerceIn(0f, 1f) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Body(archetype.tagline, Modifier.reveal(t, 3), maxLines = 3)
        Spacer(Modifier.height(10.dp))
        archetype.axes.forEachIndexed { i, axis ->
            AxisMeter(
                axis = axis,
                color = accent,
                track = WrappedPalette.Zinc700,
                textColor = Color.White,
                mutedColor = WrappedPalette.Zinc400,
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .reveal(t, 4),
                grow = { ((t() - 0.35f - i * 0.05f) / 0.4f).coerceIn(0f, 1f) },
            )
        }
        if (report.totalPlays < 20) {
            Spacer(Modifier.height(10.dp))
            Hint("AN EARLY READ  \u00B7  IT SHARPENS AS YOU LISTEN", Modifier.reveal(t, 6))
        }
    }
}

@Composable
private fun BadgesPage(report: WrappedReport, accent: Color, t: () -> Float, time: () -> Float) {
    val insights = report.insights
    PageFrame("Badges", accent, t) {
        Headline("${insights.badgesEarned} of ${insights.badges.size} earned", Modifier.reveal(t, 1), fontSize = 28.sp)
        Spacer(Modifier.height(14.dp))
        MedalGrid(
            badges = insights.badges,
            accent = accent,
            idle = WrappedPalette.Zinc900,
            outline = WrappedPalette.Zinc700,
            textColor = Color.White,
            mutedColor = WrappedPalette.Zinc400,
            t = t,
            time = time,
        )
    }
}

/** Badges as a four-wide grid of medals, each popping in after the last. */
@Composable
internal fun MedalGrid(
    badges: List<Badge>,
    accent: Color,
    idle: Color,
    outline: Color,
    textColor: Color,
    mutedColor: Color,
    t: () -> Float = { 1f },
    time: () -> Float = { 0f },
) {
    val sans = storySans
    val mono = storyMono
    badges.chunked(4).forEachIndexed { rowIndex, row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEachIndexed { column, badge ->
                val order = rowIndex * 4 + column
                val state = if (badge.earned) "earned" else "${badge.progress} of ${badge.goal}"
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics { contentDescription = "${badge.title}, ${badge.description}, $state" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Medal(
                        badge = badge,
                        accent = accent,
                        idle = idle,
                        outline = outline,
                        mutedColor = mutedColor,
                        modifier = Modifier
                            .size(58.dp)
                            .graphicsLayer {
                                val p = ((t() - 0.10f - order * 0.045f) / 0.32f).coerceIn(0f, 1f)
                                val e = backOut(p)
                                scaleX = e
                                scaleY = e
                                alpha = (p * 3f).coerceIn(0f, 1f)
                            },
                        time = time,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        badge.title,
                        fontFamily = sans,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        textAlign = TextAlign.Center,
                        color = if (badge.earned) textColor else mutedColor,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (badge.earned) {
                            "EARNED"
                        } else {
                            "${WrappedPoster.formatThousands(badge.progress)}/${WrappedPoster.formatThousands(badge.goal)}"
                        },
                        fontFamily = mono,
                        fontSize = 9.sp,
                        letterSpacing = 0.6.sp,
                        color = if (badge.earned) accent else mutedColor,
                        maxLines = 1,
                    )
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun FinalePage(
    report: WrappedReport,
    accent: Color,
    t: () -> Float,
    time: () -> Float,
    sharing: Boolean,
    onShare: (WrappedPoster.Style) -> Unit,
    onPlayTop: () -> Unit,
    onClose: () -> Unit,
) {
    val insights = report.insights
    PageFrame("That's a wrap", accent, t) {
        SoundFingerprint(
            hours = report.minutesByHour,
            weekdays = insights.minutesByWeekday,
            color = accent,
            idle = WrappedPalette.Zinc700,
            modifier = Modifier
                .size(112.dp)
                .pop(t, 0),
            pulse = time,
        )
        Spacer(Modifier.height(16.dp))
        Headline(
            insights.archetype.name.takeIf { insights.archetype.axes.isNotEmpty() } ?: report.listenerPersonality,
            Modifier.reveal(t, 1),
            fontSize = 36.sp,
        )
        Spacer(Modifier.height(8.dp))
        Body(
            "${WrappedPoster.formatThousands(report.totalMinutes)} minutes, " +
                "${WrappedPoster.formatThousands(report.distinctArtists.toLong())} artists, " +
                "${WrappedPoster.formatThousands(report.activeDays.toLong())} days.",
            Modifier.reveal(t, 2),
        )
        Spacer(Modifier.height(22.dp))
        if (report.topTracks.isNotEmpty()) {
            Button(
                onClick = onPlayTop,
                modifier = Modifier
                    .fillMaxWidth()
                    .reveal(t, 3),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = WrappedPalette.Zinc950),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Play my top tracks", fontFamily = storySans, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
        }
        Hint("SHARE AS", Modifier.reveal(t, 4))
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .reveal(t, 4),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WrappedPoster.Style.entries.filter { WrappedPoster.supports(it, report) }.forEach { style ->
                OutlinedButton(
                    onClick = { onShare(style) },
                    enabled = !sharing,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = WrappedPalette.Zinc950.copy(alpha = 0.55f),
                        contentColor = Color.White,
                    ),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                ) {
                    Text(posterStyleLabel(style), fontFamily = storyMono, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .reveal(t, 5),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = WrappedPalette.Zinc950.copy(alpha = 0.55f),
                contentColor = Color.White,
            ),
        ) {
            Text("See every number", fontFamily = storySans)
        }
    }
}

internal fun posterStyleLabel(style: WrappedPoster.Style): String = when (style) {
    WrappedPoster.Style.SUMMARY -> "Summary"
    WrappedPoster.Style.RECEIPT -> "Receipt"
    WrappedPoster.Style.CALENDAR -> "Year grid"
}

// ── Building blocks ─────────────────────────────────────────────────────────

/**
 * Staged entrance: element [order] fades up a little after the one before.
 * [t] is read in the layer block, so the animation does not recompose.
 */
private fun Modifier.reveal(t: () -> Float, order: Int): Modifier = graphicsLayer {
    val p = ((t() - order * 0.085f) / 0.34f).coerceIn(0f, 1f)
    val eased = 1f - (1f - p) * (1f - p)
    alpha = eased
    translationY = (1f - eased) * 16.dp.toPx()
}

/** Entrance for a chapter's picture: grows in slightly past full size, then settles. */
private fun Modifier.pop(t: () -> Float, order: Int): Modifier = graphicsLayer {
    val p = ((t() - order * 0.085f) / 0.42f).coerceIn(0f, 1f)
    val e = 0.72f + 0.28f * backOut(p)
    scaleX = e
    scaleY = e
    alpha = (p * 3f).coerceIn(0f, 1f)
}

@Composable
private fun PageFrame(
    eyebrow: String,
    accent: Color,
    t: () -> Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            eyebrow.uppercase(),
            fontFamily = storyMono,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 1.8.sp,
            color = accent,
            modifier = Modifier.reveal(t, 0),
        )
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun Headline(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 3,
    fontSize: TextUnit = 32.sp,
) {
    Text(
        text,
        modifier = modifier,
        fontFamily = storySans,
        fontWeight = FontWeight.SemiBold,
        fontSize = fontSize,
        lineHeight = fontSize * 1.12f,
        letterSpacing = (-0.5).sp,
        color = Color.White,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, maxLines: Int = 4) {
    Text(
        text,
        modifier = modifier,
        fontFamily = storySans,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        color = Color.White.copy(alpha = 0.72f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontFamily = storyMono,
        fontSize = 10.sp,
        letterSpacing = 1.4.sp,
        color = WrappedPalette.Zinc400,
    )
}

/** A number that counts up over the first half of the chapter's entrance. */
@Composable
private fun CountUp(target: Long, t: () -> Float, fontSize: TextUnit) {
    val p = (t() / 0.55f).coerceIn(0f, 1f)
    val eased = 1f - (1f - p) * (1f - p) * (1f - p)
    val shown = if (p >= 1f) target else (target * eased.toDouble()).toLong()
    // Five or more characters ("5,999") would run off a narrow screen at full size.
    val size = if (WrappedPoster.formatThousands(target).length >= 5) fontSize * 0.8f else fontSize
    Text(
        WrappedPoster.formatThousands(shown),
        fontFamily = storySans,
        fontWeight = FontWeight.SemiBold,
        fontSize = size,
        lineHeight = size,
        letterSpacing = (-2).sp,
        color = Color.White,
        maxLines = 1,
        softWrap = false,
        // Screen readers get the final value, not every frame of the count.
        modifier = Modifier.semantics { contentDescription = WrappedPoster.formatThousands(target) },
    )
}

@Composable
private fun StatRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                fontFamily = storyMono,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = WrappedPalette.Zinc400,
                maxLines = 1,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                value,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontFamily = storySans,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.12f)),
        )
    }
}
