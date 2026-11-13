package com.lastwave.app.ui.wrapped

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lastwave.app.R
import com.lastwave.app.data.repository.WrappedCalculator
import com.lastwave.app.data.repository.WrappedDates
import com.lastwave.app.data.repository.WrappedEntry
import com.lastwave.app.data.repository.WrappedInsightsBuilder
import com.lastwave.app.data.repository.WrappedPeriod
import com.lastwave.app.data.repository.WrappedReport
import com.lastwave.app.ui.theme.PlexMono

/**
 * LastWave Wrapped — a recap built entirely from on-device listening.
 *
 * Two ways in (v5.5.0): a tap-through **story** that tells the recap one idea
 * at a time, and this **detail page**, where every number stays on screen for
 * as long as the listener wants and reads top to bottom with TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrappedScreen(
    onBack: () -> Unit,
    viewModel: WrappedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val shareFailed = stringResource(R.string.wrapped_share_failed)

    // "Remove animations" in system accessibility settings sets this to 0.
    val systemReduceMotion = remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val reduceMotion = systemReduceMotion || state.reduceMotion

    var showStory by rememberSaveable { mutableStateOf(false) }
    var storyOffered by rememberSaveable { mutableStateOf(false) }
    val report = state.report
    val hasReport = report != null && !report.isEmpty

    // Open on the story the first time there is something to tell.
    LaunchedEffect(hasReport) {
        if (hasReport && !storyOffered) {
            storyOffered = true
            showStory = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                WrappedViewModel.Event.ShareFailed -> snackbar.showSnackbar(shareFailed)
                WrappedViewModel.Event.NothingToPlay ->
                    snackbar.showSnackbar("None of your top tracks can be queued from here")
                is WrappedViewModel.Event.Playing ->
                    snackbar.showSnackbar("Playing your top ${event.count} tracks")
            }
        }
    }

    if (showStory && report != null && hasReport) {
        BackHandler { showStory = false }
        WrappedStory(
            report = report,
            reduceMotion = reduceMotion,
            sharing = state.sharing,
            onShare = { viewModel.share(context, it) },
            onPlayTop = {
                viewModel.playTop()
                showStory = false
            },
            onClose = { showStory = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wrapped_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.storage_back),
                        )
                    }
                },
                actions = {
                    if (hasReport) {
                        IconButton(
                            onClick = { viewModel.share(context, WrappedPoster.Style.SUMMARY) },
                            enabled = !state.sharing,
                        ) {
                            if (state.sharing) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.wrapped_share))
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 120.dp),
        ) {
            PeriodChips(
                periods = state.periods,
                selected = state.selected,
                onSelect = viewModel::select,
            )
            when {
                report == null && state.loading -> LoadingBlock()
                report == null || report.isEmpty -> EmptyBlock()
                else -> ReportBody(
                    report = report,
                    fellBack = state.fellBackToAllTime,
                    requested = state.selected,
                    sharing = state.sharing,
                    onOpenStory = { showStory = true },
                    onShare = { viewModel.share(context, it) },
                    onPlayTop = viewModel::playTop,
                )
            }
        }
    }
}

// ── Period selector ─────────────────────────────────────────────────────────

@Composable
private fun PeriodChips(
    periods: List<WrappedPeriod>,
    selected: WrappedPeriod,
    onSelect: (WrappedPeriod) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        periods.forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = { Text(period.label, fontFamily = PlexMono, fontSize = 12.sp) },
            )
        }
    }
}

// ── States ──────────────────────────────────────────────────────────────────

@Composable
private fun LoadingBlock() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow(stringResource(R.string.wrapped_empty_eyebrow))
        Text(
            stringResource(R.string.wrapped_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.wrapped_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Report ──────────────────────────────────────────────────────────────────

@Composable
private fun ReportBody(
    report: WrappedReport,
    fellBack: Boolean,
    requested: WrappedPeriod,
    sharing: Boolean,
    onOpenStory: () -> Unit,
    onShare: (WrappedPoster.Style) -> Unit,
    onPlayTop: () -> Unit,
) {
    val insights = report.insights
    val accent = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (fellBack) {
            Text(
                stringResource(R.string.wrapped_fallback_note, requested.label),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        StoryCard(report, onOpenStory)
        Hero(report)
        StatGrid(report)

        if (report.topTracks.isNotEmpty()) {
            RankedSection(stringResource(R.string.wrapped_top_tracks), report.topTracks, roundArt = false)
        }
        if (report.topArtists.isNotEmpty()) {
            RankedSection(stringResource(R.string.wrapped_top_artists), report.topArtists, roundArt = true)
        }
        if (report.topAlbums.isNotEmpty()) {
            RankedSection(stringResource(R.string.wrapped_top_albums), report.topAlbums, roundArt = false)
        }

        // Calendar
        if (insights.days.isNotEmpty() && insights.months.isNotEmpty()) {
            Section("${report.window.period.label} in days") {
                val single = insights.months.size == 1
                MonthHeatmap(
                    months = insights.months.map { it.grid },
                    minutesByDay = insights.minutesByDay(),
                    maxMinutes = insights.maxDayMinutes,
                    color = accent,
                    empty = idle,
                    labelColor = muted,
                    columns = if (single) 1 else 4,
                    description = "Calendar of listening: ${report.activeDays} days active",
                    modifier = Modifier.fillMaxWidth(if (single) 0.7f else 1f),
                )
                Spacer(Modifier.height(10.dp))
                val streak = insights.longestStreak
                if (streak != null && report.longestStreakDays > 1) {
                    KeyValue(
                        "Longest streak",
                        "${report.longestStreakDays}d  ·  ${WrappedDates.shortLabel(streak.first)} → ${WrappedDates.shortLabel(streak.last)}",
                    )
                }
                insights.biggestDay?.let { day ->
                    KeyValue(
                        "Biggest day",
                        "${WrappedDates.label(day.dayIndex)}  ·  ${WrappedCalculator.formatDuration(day.minutes)}",
                    )
                    day.topTrack?.let { KeyValue("Mostly", it) }
                }
            }
        }

        // Rhythm
        if (report.activeDays >= 2) {
            Section("Your rhythm") {
                insights.topWeekday?.let {
                    Text(
                        "${WrappedDates.WEEKDAYS_LONG[it]}s are yours.",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                LevelMeter(
                    values = insights.minutesByWeekday,
                    labels = WrappedDates.WEEKDAYS_SHORT.map { it.uppercase() },
                    color = accent,
                    idle = idle,
                    labelColor = muted,
                    description = "Listening by weekday",
                    height = 112.dp,
                )
                if (insights.months.size == 12) {
                    Spacer(Modifier.height(18.dp))
                    AreaWave(
                        values = insights.months.map { it.minutes },
                        labels = insights.months.map { it.grid.shortLabel.take(1) },
                        color = accent,
                        labelColor = muted,
                        description = "Listening by month",
                    )
                }
            }
        }

        // Clock
        if (report.minutesByHour.sum() > 0L) {
            val hours = report.minutesByHour
            val peakHour = hours.indexOf(hours.maxOrNull() ?: 0L).coerceAtLeast(0)
            Section(stringResource(R.string.wrapped_clock_title)) {
                Text(
                    report.listenerPersonality,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(report.personalityLine, style = MaterialTheme.typography.bodyMedium, color = muted)
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RadialClock(
                        hours = hours,
                        color = accent,
                        idle = idle,
                        labelColor = muted,
                        description = stringResource(
                            R.string.wrapped_clock_summary,
                            report.listenerPersonality,
                            "${peakHour.toString().padStart(2, '0')}:00",
                        ),
                        modifier = Modifier.size(224.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${peakHour.toString().padStart(2, '0')}:00",
                                fontFamily = PlexMono,
                                fontWeight = FontWeight.Medium,
                                fontSize = 19.sp,
                            )
                            Text("PEAK", fontFamily = PlexMono, fontSize = 9.sp, letterSpacing = 1.4.sp, color = muted)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                insights.nightAnthem?.let { KeyValue("After-midnight anthem", it.title) }
                insights.morningAnthem?.let { KeyValue("Morning anthem", it.title) }
            }
        }

        // Habits
        val completion = insights.completionPercent
        if (completion != null && report.totalPlays >= 5) {
            Section("Habits") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RingGauge(
                        fraction = completion / 100f,
                        color = accent,
                        track = idle,
                        modifier = Modifier.size(104.dp),
                    ) {
                        Text(
                            "$completion%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(18.dp))
                    Text(
                        "of the songs you started, you played to the end.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = muted,
                    )
                }
                Spacer(Modifier.height(6.dp))
                insights.binge?.let {
                    KeyValue("Most plays in one day", "${it.plays}× on ${WrappedDates.shortLabel(it.dayIndex)}  ·  ${it.title}")
                }
                insights.neverSkipped?.let { KeyValue("Never skipped", "${it.plays}×  ${it.title}") }
                insights.mostSkipped?.let { KeyValue("Skipped most", "${it.skips} of ${it.plays}  ${it.title}") }
            }
        }

        // Discovery — only when the log reaches back before this period.
        val newArtists = insights.newArtists
        if (newArtists != null && newArtists > 0) {
            Section("Discovery") {
                KeyValue("Artists new to you", "$newArtists of ${report.distinctArtists}")
                insights.topNewArtist?.let { KeyValue("The one that stuck", it.title) }
            }
        }

        // Archetype
        val archetype = insights.archetype
        if (archetype.axes.isNotEmpty()) {
            Section("Your archetype") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            archetype.spacedCode,
                            fontFamily = PlexMono,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            letterSpacing = 1.5.sp,
                            color = accent,
                        )
                        Text(
                            archetype.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    AxisDiamond(
                        axes = archetype.axes,
                        color = accent,
                        grid = MaterialTheme.colorScheme.outlineVariant,
                        onColor = MaterialTheme.colorScheme.onSurface,
                        offColor = muted,
                        modifier = Modifier.size(112.dp),
                    )
                }
                Text(archetype.tagline, style = MaterialTheme.typography.bodyMedium, color = muted)
                Spacer(Modifier.height(12.dp))
                archetype.axes.forEach { axis ->
                    AxisMeter(
                        axis = axis,
                        color = accent,
                        track = MaterialTheme.colorScheme.outlineVariant,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        mutedColor = muted,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }

        // Badges
        if (insights.badges.isNotEmpty()) {
            Section("Badges  ·  ${insights.badgesEarned} of ${insights.badges.size}") {
                MedalGrid(
                    badges = insights.badges,
                    accent = accent,
                    idle = MaterialTheme.colorScheme.surfaceContainerHigh,
                    outline = MaterialTheme.colorScheme.outlineVariant,
                    textColor = MaterialTheme.colorScheme.onSurface,
                    mutedColor = muted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Badges count what you did in ${report.window.period.label}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
        }

        // Actions
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (report.topTracks.isNotEmpty()) {
                Button(onClick = onPlayTop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play my top tracks")
                }
            }
            Eyebrow("Share as")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WrappedPoster.Style.entries.filter { WrappedPoster.supports(it, report) }.forEach { style ->
                    OutlinedButton(
                        onClick = { onShare(style) },
                        enabled = !sharing,
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                    ) {
                        Text(posterStyleLabel(style), fontFamily = PlexMono, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }

        Text(
            stringResource(R.string.wrapped_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

/** The way into story mode, with the listener's own fingerprint on it. */
@Composable
private fun StoryCard(report: WrappedReport, onOpen: () -> Unit) {
    val chapters = remember(report) { storyPagesFor(report).size }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = "Play your story", onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Story  ·  $chapters chapters")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Play your story",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    report.window.period.storyTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SoundFingerprint(
                hours = report.minutesByHour,
                weekdays = report.insights.minutesByWeekday,
                color = MaterialTheme.colorScheme.primary,
                idle = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}

@Composable
private fun Hero(report: WrappedReport) {
    val insights = report.insights
    val minutes = report.totalMinutes
    val showHours = minutes >= 6000
    val big = WrappedPoster.formatThousands(if (showHours) minutes / 60 else minutes)
    val unit = stringResource(if (showHours) R.string.wrapped_hours_listened else R.string.wrapped_minutes_listened)

    Column(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$big $unit" },
    ) {
        Eyebrow(report.window.period.storyTitle)
        Text(
            big,
            style = TextStyle(
                fontFamily = MaterialTheme.typography.displayLarge.fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 96.sp,
                lineHeight = 100.sp,
                letterSpacing = (-3).sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Text(
            unit.uppercase(),
            fontFamily = PlexMono,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            letterSpacing = 2.5.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        val perDay = if (report.activeDays > 0) {
            "  ·  ${WrappedCalculator.formatDuration(minutes / report.activeDays)} a day you listened"
        } else {
            ""
        }
        Text(
            "${WrappedInsightsBuilder.nonStop(minutes)} end to end$perDay",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val previous = insights.previousMinutes
        val previousLabel = insights.previousLabel
        if (previous != null && previousLabel != null && previous > 0L) {
            val line = if (insights.periodComplete) {
                val delta = ((minutes - previous) * 100 / previous).toInt()
                "${if (delta >= 0) "+$delta" else "$delta"}% compared with $previousLabel"
            } else {
                "$previousLabel finished at ${WrappedPoster.formatThousands(previous)} min"
            }
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatGrid(report: WrappedReport) {
    val cells = listOf(
        stringResource(R.string.wrapped_stat_plays) to WrappedPoster.formatThousands(report.totalPlays.toLong()),
        stringResource(R.string.wrapped_stat_tracks) to WrappedPoster.formatThousands(report.distinctTracks.toLong()),
        stringResource(R.string.wrapped_stat_artists) to WrappedPoster.formatThousands(report.distinctArtists.toLong()),
        stringResource(R.string.wrapped_stat_days) to WrappedPoster.formatThousands(report.activeDays.toLong()),
        stringResource(R.string.wrapped_stat_longest_streak) to
            stringResource(R.string.wrapped_days_short, report.longestStreakDays),
        stringResource(R.string.wrapped_stat_current_streak) to
            stringResource(R.string.wrapped_days_short, report.currentStreakDays),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value) ->
                    StatCell(label, value, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$label, $value" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                label.uppercase(),
                fontFamily = PlexMono,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RankedSection(title: String, entries: List<WrappedEntry>, roundArt: Boolean) {
    Column {
        Eyebrow(title)
        Spacer(Modifier.height(6.dp))
        entries.forEachIndexed { index, entry ->
            if (index > 0) Hairline()
            RankedRow(index + 1, entry, roundArt)
        }
    }
}

@Composable
private fun RankedRow(rank: Int, entry: WrappedEntry, roundArt: Boolean) {
    val time = WrappedCalculator.formatDuration(entry.totalMs / 60_000L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$rank. ${entry.title}, ${entry.subtitle}, $time"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rank.toString().padStart(2, '0'),
            fontFamily = PlexMono,
            fontSize = 13.sp,
            color = if (rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp),
        )
        val artShape = if (roundArt) RoundedCornerShape(50) else RoundedCornerShape(8.dp)
        Box(
            Modifier
                .size(44.dp)
                .clip(artShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (!entry.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = entry.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.subtitle.isNotBlank()) {
                Text(
                    entry.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            time,
            fontFamily = PlexMono,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Primitives ──────────────────────────────────────────────────────────────

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Eyebrow(title)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                value,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Hairline()
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text.uppercase(),
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
