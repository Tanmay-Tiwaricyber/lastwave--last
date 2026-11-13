package com.lastwave.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lastwave.app.data.update.UpdateInfo
import com.lastwave.app.data.update.UpdatePhase
import com.lastwave.app.data.update.UpdateRelease

/**
 * The update entry: one row in Settings → About, and the dialog it opens.
 *
 * Two surfaces read the same [UpdateInfo] — this dialog and the banner on the
 * home shell — so all the wording that matters lives in the data layer
 * (`statusLine`, `actionLabel`) and this file only lays it out. The reason is
 * narrow and deliberate: the sentence describing whether a downloaded APK was
 * verified is a security statement, and security statements belong next to the
 * code that decided them, where a test can pin them.
 *
 * What the dialog has to make visible, because an updater that hides these is the
 * kind people blame the app for later:
 *  - the size of what is about to be downloaded;
 *  - exactly which checks passed, including the case where the release recorded no
 *    digest at all and so the bytes were never compared to a hash;
 *  - that the *system* installer is what actually performs the install, and that
 *    Android's "install unknown apps" grant is needed once;
 *  - the GitHub page, so a user who does not trust the in-app path can always
 *    download it themselves instead.
 */
@Composable
internal fun UpdateSettingsEntry(
    info: UpdateInfo,
    isHighlighted: Boolean,
    position: GroupPosition = GroupPosition.SINGLE,
    onCheck: () -> Unit,
    onDownloadOrInstall: () -> Unit,
    onOpenRelease: () -> Unit,
    onDismissVersion: () -> Unit,
    onDeleteDownload: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    SettingsActionCard(
        icon = Icons.Filled.SystemUpdate,
        iconContainer = if (info.isUpdateAvailable) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        iconTint = if (info.isUpdateAvailable) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        title = if (info.isUpdateAvailable) {
            "Update to v${info.latestVersion}"
        } else {
            "Updates"
        },
        subtitle = info.statusLine(),
        onClick = { showDialog = true },
        position = position,
        isHighlighted = isHighlighted,
    )

    if (!showDialog) return

    val busy = info.phase == UpdatePhase.CHECKING || info.phase == UpdatePhase.DOWNLOADING
    AlertDialog(
        onDismissRequest = { if (!busy) showDialog = false },
        shape = RoundedCornerShape(28.dp),
        title = {
            Text(
                text = when {
                    info.phase == UpdatePhase.READY -> "Ready to install"
                    info.isUpdateAvailable -> "LastWave v${info.latestVersion}"
                    info.phase == UpdatePhase.UP_TO_DATE -> "You are up to date"
                    else -> "Updates"
                },
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(info.statusLine(), style = MaterialTheme.typography.bodyMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Installed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "v${info.currentVersion.ifBlank { "?" }}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (info.latestVersion.isNotBlank() && info.latestVersion != info.currentVersion) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Newest release",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "v${info.latestVersion}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (info.downloadFileName != null) {
                    Text(
                        text = "${info.downloadFileName} · " +
                            UpdateRelease.formatBytes(info.totalBytes) +
                            if (info.downloadedFile != null) " · already downloaded" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                info.progress?.let { fraction ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${UpdateRelease.formatBytes(info.downloadedBytes)} of " +
                                UpdateRelease.formatBytes(info.totalBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Spelled out rather than assumed: what was checked, and what was
                // not. A dialog that only ever says "verified" would be worth nothing.
                info.verification?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                info.message?.let { problem ->
                    Text(
                        text = problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (info.phase == UpdatePhase.READY && !info.canInstall) {
                    Text(
                        text = "Android also needs permission to install apps from LastWave. " +
                            "Tapping Install opens that switch; allow it, then tap Install once more.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (info.releaseNotes.isNotBlank()) {
                    HorizontalDivider()
                    Text(
                        text = "What is in it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = info.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 190.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Check on startup", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Once per release, at most every 12 hours.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = info.autoCheck, onCheckedChange = onAutoCheckChange)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    when (info.phase) {
                        UpdatePhase.AVAILABLE, UpdatePhase.READY -> onDownloadOrInstall()
                        else -> onCheck()
                    }
                },
            ) {
                Text(info.actionLabel())
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (info.phase == UpdatePhase.READY) {
                    TextButton(
                        onClick = {
                            onDeleteDownload()
                            showDialog = false
                        },
                    ) {
                        Text("Delete file")
                    }
                }
                if (info.isUpdateAvailable && info.phase != UpdatePhase.DOWNLOADING) {
                    TextButton(
                        onClick = {
                            onDismissVersion()
                            showDialog = false
                        },
                    ) {
                        Text("Not now")
                    }
                }
                TextButton(onClick = onOpenRelease) {
                    Text("GitHub")
                }
            }
        },
    )
}

/**
 * The startup banner: a thin strip over the home shell, not a dialog, because a
 * 37 MB download is a thing to decide about while doing something else, and an
 * app that interrupts a session to ask has trained the user to tap through it.
 *
 * Lives here rather than in the shell file so the shell keeps its layout code in
 * one place; the strip only appears when there is something to say and a × keeps
 * it away until the *next* release.
 */
@Composable
fun UpdateBanner(
    info: UpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = info.isUpdateAvailable &&
        !info.isDismissed &&
        info.phase != UpdatePhase.UP_TO_DATE &&
        info.downloadUrl != null
    var dismissedByUser by remember(info.latestVersion) { mutableStateOf(false) }
    if (!visible || dismissedByUser) return

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "v${info.latestVersion} is available",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = info.statusLine(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onUpdate,
                enabled = info.phase != UpdatePhase.CHECKING && info.phase != UpdatePhase.DOWNLOADING,
            ) {
                Text(if (info.phase == UpdatePhase.READY) "Install" else "Get")
            }
            IconButton(
                onClick = {
                    dismissedByUser = true
                    onDismiss()
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Not now", modifier = Modifier.size(18.dp))
            }
        }
    }
}

