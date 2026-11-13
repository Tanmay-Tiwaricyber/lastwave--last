package com.lastwave.app.ui.match

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.match.MelodyIndex
import com.lastwave.app.playback.melody.MelodyMatcher

/**
 * "Sing to find": hum a line, get the song (v5.7.0).
 *
 * The screen is a microphone, a meter, and a list. The meter exists because the
 * single most common failure is that the phone heard nothing, and a person who
 * sees a flat meter stops singing too quietly rather than wondering why the app
 * is wrong. Below the button, the notes the app actually heard are spelled out:
 * when the answer is wrong, seeing the transcription says far more than "no
 * match" does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SingToFindScreen(
    onBack: () -> Unit = {},
    viewModel: SingToFindViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionResult(granted)
        if (granted) viewModel.startListening()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sing to find") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.phase == SingToFindViewModel.Phase.RESULT || state.results.isNotEmpty()) {
                        IconButton(onClick = { viewModel.reset() }) {
                            Icon(Icons.Filled.Stop, contentDescription = "Clear")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Hum or sing eight notes or so of the part you remember. " +
                    "LastWave compares the tune with the songs on this phone \u2014 downloads and imported audio \u2014 " +
                    "and starts the one that fits, from the phrase you sang.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.permissionMissing) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Microphone access needed", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Nothing is recorded until you press the button, and the audio never leaves the phone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { permission.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("Allow microphone")
                        }
                    }
                }
            }

            ListeningControls(
                state = state,
                onStart = {
                    if (state.permissionMissing) permission.launch(Manifest.permission.RECORD_AUDIO) else viewModel.startListening()
                },
                onStop = { viewModel.stopListening() },
                onIndex = { viewModel.indexLibrary() },
            )

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.heardNotes.isNotEmpty()) {
                Text(
                    text = "Heard: ${state.heardNotes.joinToString(" \u00B7 ")}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            when (state.phase) {
                SingToFindViewModel.Phase.THINKING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Comparing with ${state.indexedSongs} songs\u2026", style = MaterialTheme.typography.bodySmall)
                }
                else -> Unit
            }

            if (state.results.isNotEmpty()) {
                Text(
                    text = verdictLabel(state.verdict, state.autoPlayed),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(state.results, key = { it.track.id }) { result ->
                        MatchCard(
                            result = result,
                            leading = state.results.firstOrNull() == result,
                            autoPlayed = state.autoPlayed,
                            onPlay = { viewModel.play(result) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ListeningControls(
    state: SingToFindViewModel.UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onIndex: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listening = state.phase == SingToFindViewModel.Phase.LISTENING
    val indexing = state.phase == SingToFindViewModel.Phase.INDEXING
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size((72f + 46f * state.level).dp)
                    .clip(CircleShape)
                    .background(
                        if (listening) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            ) {
                Icon(
                    imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
            if (listening) {
                LinearProgressIndicator(
                    progress = { (state.elapsedSeconds / 12f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "%.1f s of 12 \u00B7 tap to stop".format(state.elapsedSeconds),
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    text = if (indexing) "Reading the tunes of your library" else "Tap to sing",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (listening) {
                    Button(onClick = onStop) { Text("Stop") }
                } else if (!indexing) {
                    Button(onClick = onStart, enabled = !state.permissionMissing) { Text("Sing") }
                }
                if (!listening && !indexing) {
                    OutlinedButton(onClick = onIndex, enabled = !state.permissionMissing) {
                        Text(if (state.indexedSongs == 0) "Read tunes" else "Re-read tunes")
                    }
                }
            }
            if (indexing) {
                val progress = state.index
                if (progress != null && progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${progress.done}/${progress.total} \u00B7 ${progress.currentTitle}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (!listening && !indexing && state.indexedSongs > 0) {
                Text(
                    text = "${state.indexedSongs} songs with a tune read",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MatchCard(
    result: MelodyIndex.Result,
    leading: Boolean,
    autoPlayed: Boolean,
    onPlay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (leading) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = result.track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = result.track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "%d%% match \u00B7 ${result.match.matchedNotes}/${result.match.totalNotes} notes \u00B7 at ${time(result.match.startSeconds)}".format(
                        result.percent,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (leading && autoPlayed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leading && autoPlayed) {
                    Text(
                        text = "playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                        .padding(0.dp),
                ) {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.Unspecified)
                    }
                }
            }
        }
    }
}

private fun verdictLabel(verdict: MelodyMatcher.Verdict, autoPlayed: Boolean): String = when (verdict) {
    MelodyMatcher.Verdict.SURE -> if (autoPlayed) "Found it" else "Most likely"
    MelodyMatcher.Verdict.CLOSE -> "Nearly \u2014 pick one"
    MelodyMatcher.Verdict.UNCERTAIN -> "Best guesses"
    MelodyMatcher.Verdict.NOTHING -> "Nothing found"
}

private fun time(seconds: Float): String {
    val whole = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(whole / 60, whole % 60)
}
