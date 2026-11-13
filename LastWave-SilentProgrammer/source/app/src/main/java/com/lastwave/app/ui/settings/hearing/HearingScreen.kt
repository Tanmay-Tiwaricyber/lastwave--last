package com.lastwave.app.ui.settings.hearing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.hearing.HearingProfile
import com.lastwave.app.data.hearing.HearingTestRunner
import com.lastwave.app.data.hearing.HearingTestSession
import kotlin.math.ln

/**
 * The hearing-profile screen: run the test, read the numbers, decide.
 *
 * The wording here is part of the feature. Every claim about what the curve is
 * (a relative balance between bands, not a clinical result) is stated in the UI
 * as well as the docs, because a screen that presents a phone measurement as an
 * audiogram would be the failure mode of this whole idea.
 */
@Composable
fun HearingDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            HearingScreen(onBack = onDismiss)
        }
    }
}

/** One-line status for the Settings row. */
@Composable
fun hearingSummary(viewModel: HearingViewModel = hiltViewModel()): String {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.profile
    return when {
        profile == null || !profile.isUsable -> "Not tested \u2022 takes about 4 minutes"
        enabled -> "On \u2022 ${profile.summary()}"
        else -> "Off \u2022 ${profile.summary()}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HearingScreen(onBack: () -> Unit) {
    val viewModel: HearingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val readings by viewModel.readings.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val curve by viewModel.curve.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val devices by viewModel.savedDevices.collectAsStateWithLifecycle()
    val running = ui.phase == HearingTestRunner.Phase.TONE || ui.phase == HearingTestRunner.Phase.LISTENING

    // Volume and routing are only true for a moment, so keep them fresh while the
    // dialog is reading rather than showing what they were when it opened.
    LaunchedEffect(ui.phase) {
        if (!running || ui.phase == HearingTestRunner.Phase.STOPPED) {
            while (true) {
                viewModel.refreshEnvironment()
                delay(1_500)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hearing profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val accepted = pending
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                when {
                    running -> RunningCard(ui = ui, viewModel = viewModel)
                    accepted != null -> ResultCard(accepted, readings, viewModel = viewModel)
                    else -> IntroCard(state.profile, ui, viewModel = viewModel)
                }
            }
            val saved = state.profile
            if (!running && saved != null && saved.isUsable) {
                item {
                    ProfileCard(
                        profile = saved,
                        curve = curve,
                        enabled = state.enabled,
                        strength = state.strength,
                        filterCount = filters.filters.count { it.enabled },
                        preampDb = filters.preampDb,
                        readings = readings,
                        devices = devices,
                        currentDevice = ui.routedTo,
                        activeKey = state.activeKey,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroCard(profile: HearingProfile?, ui: HearingTestRunner.Ui, viewModel: HearingViewModel) {
    SectionCard {
        Text("Measure your own correction", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "LastWave plays a quiet tone at each frequency, in one ear at a time, and asks " +
                "whether you heard it. Bands you hear less well are lifted a little; bands " +
                "that are fine are left alone. About four minutes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        Bullet("Use headphones, in a quiet room, at low volume. Music is paused for you.")
        Bullet("This is not a hearing test in the medical sense. No calibrated level, no " +
            "audiogram \u2014 it measures the difference between bands on this phone, through " +
            "these ear-tips.")
        Bullet("If a band is genuinely gone the app will not find it; it corrects within a " +
            "few dB and caps the lift at +6 dB per band.")
        Bullet("If you have sudden or one-sided hearing loss, or tinnitus, see a doctor " +
            "before adjusting anything.")
    }
    SectionCard {
        Text("Before you start", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (ui.hasHeadphones) "Output: ${ui.routedTo.ifBlank { "headphones" }}" else "No headphones found",
                style = MaterialTheme.typography.bodyMedium,
                color = if (ui.hasHeadphones) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Device volume ${ui.volumePercent}%" +
                if (ui.volumePercent <= HearingTestRunner.SAFE_VOLUME_PERCENT) " \u2022 fine" else " \u2022 too loud to test",
            style = MaterialTheme.typography.bodyMedium,
            color = if (ui.volumePercent <= HearingTestRunner.SAFE_VOLUME_PERCENT) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        if (ui.message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                ui.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.phase == HearingTestRunner.Phase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = viewModel::startTest, modifier = Modifier.fillMaxWidth()) {
            Text(if (profile != null && profile.isUsable) "Run the test again" else "Start the test")
        }
    }
}

@Composable
private fun RunningCard(ui: HearingTestRunner.Ui, viewModel: HearingViewModel) {
    val step = ui.step
    SectionCard {
        Text(
            text = if (ui.phase == HearingTestRunner.Phase.TONE) "Tone playing\u2026" else "Did you hear that?",
            style = MaterialTheme.typography.titleMedium,
        )
        if (step != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatHz(step.frequencyHz)} \u2022 ${if (step.ear == com.lastwave.app.data.hearing.Ear.LEFT) "left ear" else "right ear"}" +
                    " \u2022 step ${step.index + 1} of ${step.total}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { HearingTestSession.progressOf(step.index, step.total) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { viewModel.answer(true) }, enabled = ui.canAnswer, modifier = Modifier.weight(1f)) {
                Text("Heard it")
            }
            OutlinedButton(onClick = { viewModel.answer(false) }, enabled = ui.canAnswer, modifier = Modifier.weight(1f)) {
                Text("Not heard")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = viewModel::replay, enabled = ui.canReplay, modifier = Modifier.weight(1f)) {
                Text("Play again")
            }
            TextButton(onClick = viewModel::retryBand, modifier = Modifier.weight(1f)) { Text("Redo band") }
            TextButton(onClick = viewModel::skipBand, modifier = Modifier.weight(1f)) { Text("Skip") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = viewModel::stopTest, modifier = Modifier.weight(1f)) { Text("Stop (keeps what was measured)") }
        }
        if (ui.message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(ui.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultCard(profile: HearingProfile, readings: List<HearingTestSession.Reading>, viewModel: HearingViewModel) {
    SectionCard {
        Text("What the test found", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            profile.summary(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (profile.asymmetryDb > 6.0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Your two ears differ by ${"%.1f".format(profile.asymmetryDb)} dB on average. A " +
                    "single EQ cannot split channels, so nothing here tries to. A difference " +
                    "that size is worth showing to a professional.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(8.dp))
        Text("Thresholds (relative, lower = heard more easily)", style = MaterialTheme.typography.labelMedium)
        profile.bands.forEach { band ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(formatHz(band.frequencyHz), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    "L ${"%.1f".format(band.leftThresholdDb)}   R ${"%.1f".format(band.rightThresholdDb)}   " +
                        "loss ${"%.1f".format(band.meanThresholdDb - (profile.bands.minOf { it.meanThresholdDb }))} dB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val dropped = HearingProfile.FREQUENCIES.size - profile.bands.size
        if (dropped > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "$dropped band${if (dropped == 1) "" else "s"} could not be measured and were left out " +
                    "\u2014 not counted as fine hearing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (readings.any { it.noResponse }) {
            Spacer(Modifier.height(4.dp))
            Text(
                readings.filter { it.noResponse }.joinToString(" \u2022 ") { it.label() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { viewModel.acceptProfile(enable = true) }, modifier = Modifier.fillMaxWidth()) {
            Text("Save and use")
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.acceptProfile(enable = false) }, modifier = Modifier.weight(1f)) {
                Text("Save only")
            }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = viewModel::discardRun, modifier = Modifier.weight(1f)) { Text("Discard") }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: HearingProfile,
    curve: DoubleArray,
    enabled: Boolean,
    strength: Double,
    filterCount: Int,
    preampDb: Double,
    readings: List<HearingTestSession.Reading>,
    devices: List<com.lastwave.app.data.hearing.HearingArchive.Entry>,
    currentDevice: String,
    activeKey: String,
    viewModel: HearingViewModel,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Correction", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${profile.bands.size} bands \u2022 ${filterCount} filters \u2022 preamp " +
                        "${"%.1f".format(preampDb)} dB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
        }
        Spacer(Modifier.height(10.dp))
        CurveChart(curve)
        Spacer(Modifier.height(6.dp))
        Text("Strength ${"%.0f".format(strength * 100)}%", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = strength.toFloat(),
            onValueChange = { viewModel.setStrength(it.toDouble()) },
            valueRange = 0f..1f,
        )
        Text(
            "Half-gain is the hearing-aid convention: give back about half of what is " +
                "missing. Full compensation sounds wrong by the end of the day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            profile.summary(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (profile.deviceLabel.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Measured on ${profile.deviceLabel}" +
                    if (profile.formFactor.isNotBlank()) " (${profile.formFactor})" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        // The seal matters more than most people expect: the same ear loses a few
        // dB of bass to a slightly open tip. Nothing is switched off over it, but
        // you should know the curve in front of you came from somewhere else.
        if (currentDevice.isNotBlank() &&
            com.lastwave.app.data.hearing.HearingArchive.labelKey(profile.deviceLabel) !=
            com.lastwave.app.data.hearing.HearingArchive.labelKey(currentDevice)
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "You are on $currentDevice. Re-run it here if you use these more often — " +
                    "a curve measured through one seal is the wrong curve through another.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (devices.size > 1) {
            Spacer(Modifier.height(8.dp))
            Text("Saved curves", style = MaterialTheme.typography.labelMedium)
            devices.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${entry.label} \u00B7 ${entry.bandCount} bands",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (!entry.isActive) {
                        TextButton(onClick = { viewModel.useDevice(entry.key) }) { Text("Use") }
                    }
                    TextButton(onClick = { viewModel.forgetDevice(entry.key) }) { Text("Forget") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = viewModel::startTest, modifier = Modifier.weight(1f)) { Text("Re-run") }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = viewModel::clearProfile, modifier = Modifier.weight(1f)) { Text("Delete profile") }
        }
        if (readings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(6.dp))
            Text("Thresholds (dB below full scale, relative)", style = MaterialTheme.typography.labelMedium)
            readings.chunked(2).forEach { pair ->
                Text(pair.joinToString("   ") { it.label() }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Gain added per frequency, on the same log axis the EQ is fitted on. */
@Composable
private fun CurveChart(curve: DoubleArray) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val frequencies = remember { com.lastwave.app.data.autoeq.PeqMath.LOG_GRID }
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val logMin = ln(20.0)
        val logMax = ln(20_000.0)
        val top = 8.0
        fun x(f: Double) = ((ln(f) - logMin) / (logMax - logMin) * size.width).toFloat()
        fun y(db: Double) = size.height - (db.toFloat() / top.toFloat()) * size.height
        listOf(0.0, 3.0, 6.0).forEach { db ->
            drawLine(grid, Offset(0f, y(db)), Offset(size.width, y(db)), strokeWidth = 1.dp.toPx())
        }
        listOf(50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0).forEach { f ->
            drawLine(grid, Offset(x(f), 0f), Offset(x(f), size.height), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        var started = false
        frequencies.forEachIndexed { index, f ->
            val value = curve.getOrElse(index) { 0.0 }.coerceIn(0.0, top)
            val point = Offset(x(f), y(value))
            if (!started) {
                path.moveTo(point.x, point.y)
                started = true
            } else {
                path.lineTo(point.x, point.y)
            }
        }
        drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        // Band markers: the test's own resolution, so a sparse curve looks sparse.
        profileFrequencies().forEach { f ->
            val value = curve.getOrElse(indexOfGrid(f)) { 0.0 }.coerceIn(0.0, top)
            drawCircle(line, radius = 2.6.dp.toPx(), center = Offset(x(f), y(value)))
        }
    }
}

private fun profileFrequencies(): List<Double> = HearingProfile.FREQUENCIES.toList()

private fun indexOfGrid(frequency: Double): Int {
    val grid = com.lastwave.app.data.autoeq.PeqMath.LOG_GRID
    var best = 0
    var bestDelta = Double.MAX_VALUE
    grid.forEachIndexed { index, value ->
        val delta = kotlin.math.abs(ln(value) - ln(frequency))
        if (delta < bestDelta) {
            bestDelta = delta
            best = index
        }
    }
    return best
}

private fun formatHz(hz: Double): String =
    if (hz >= 1000.0) "${("${"%.2f".format(hz / 1000.0)}".trimEnd('0').trimEnd('.'))} kHz" else "${hz.toInt()} Hz"

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("\u2022  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
