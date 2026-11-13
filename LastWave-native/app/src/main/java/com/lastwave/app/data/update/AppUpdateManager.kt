package com.lastwave.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lastwave.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Self-update from this fork's own GitHub releases: look up the newest release,
 * offer it, download it, verify it, hand it to the system installer.
 *
 * The upstream project shipped an updater and the Silent Programmer build had it
 * cut, because it pointed at somebody else's repository. It comes back here
 * pointed at `Tanmay-Tiwaricyber/LastWave`, and with a verification chain the
 * original never had:
 *
 *  1. the byte count must match what the release recorded;
 *  2. the SHA-256 must match GitHub's own recorded `digest` for that asset — the
 *     reason a release carrying *nothing but* an APK is still verifiable
 *     byte-for-byte — or the matching line of a `CHECKSUMS.txt` asset, if one is
 *     attached;
 *  3. the file must parse as an APK whose **versionCode is higher** than this
 *     build's, which is what catches a mislabelled or stale upload;
 *  4. the APK's signing certificate must be the certificate this app is
 *     installed with. That is the one check that establishes authorship. When the
 *     platform will not report the archive's signer the download is not silently
 *     waved through: the dialog says the signature was not checked, and
 *     `PackageInstaller`, which refuses a re-signed update outright, stays the
 *     enforcement.
 *
 * A failed lookup is never presented as "up to date". A fork whose repository is
 * missing or private produces a 404, and the honest sentence for that is "no
 * published release was found".
 */
@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GitHubUpdateClient,
    private val archiveInspector: ApkArchiveInspector,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store: DataStore<Preferences> = context.applicationContext.updateDataStore
    private val data = store.data.catch { emit(emptyPreferences()) }

    private val _updateInfo = MutableStateFlow(UpdateInfo(currentVersion = getCurrentVersion()))
    val updateInfo: StateFlow<UpdateInfo> = _updateInfo.asStateFlow()

    private var release: ReleaseInfo? = null
    private var downloaded: File? = null
    private var dismissed: String = ""
    private var autoCheck: Boolean = true
    private var lastCheckAt: Long = 0L

    @Volatile
    private var busy = false

    /** Guard so a settings write during boot does not queue a second startup check. */
    @Volatile
    private var startupScheduled = false

    /**
     * Set as soon as the user touches the startup toggle. The cold-start settings read
     * below pushes `autoCheck` into the UI state once, and that read is real file I/O:
     * a switch flipped in the same instant would be shown snapping back to its old
     * position — a toggle that appears not to work until the next launch. The stored
     * value is already correct, so the late read must not overwrite the row.
     */
    @Volatile
    private var autoCheckTouched = false

    init {
        scope.launch {
            var restored = false
            data.collect { prefs ->
                dismissed = prefs[KEY_DISMISSED].orEmpty()
                autoCheck = prefs[KEY_AUTO_CHECK] != false
                lastCheckAt = prefs[KEY_LAST_CHECK] ?: 0L
                if (restored) return@collect
                restored = true
                if (!autoCheckTouched) {
                    _updateInfo.value = _updateInfo.value.copy(autoCheck = autoCheck)
                }
                val cached = prefs[KEY_RELEASE_BODY]?.let { body ->
                    UpdateRelease.parse(body, isUncompressedBuild())?.let { parsed ->
                        // Pin the asset that was chosen when the lookup happened: the
                        // selection rules could have changed with the app, and a
                        // half-downloaded file under one name must not be offered as
                        // another.
                        val pinned = prefs[KEY_RELEASE_ASSET]?.let { name ->
                            parsed.allAssets.firstOrNull { it.name == name }
                        }
                        if (pinned != null && pinned != parsed.asset) parsed.copy(asset = pinned) else parsed
                    }
                }
                // Re-running the parser is also what keeps a cached release honest:
                // if the app was updated by other means in the meantime, the tag is
                // no longer newer and nothing is offered.
                if (cached != null && cached.asset != null &&
                    UpdateRelease.isNewer(cached.tag, getCurrentVersion())
                ) {
                    release = cached
                    downloaded = matchingDownload(cached)
                    // A `.part` left by an attempt that died (process kill, disk
                    // full, reboot) has no owner: nothing resumes it, and it is up to
                    // 37 MB sitting in the app's private directory, which survives an
                    // in-place update. So anything that is not the file this release
                    // maps to goes now, once, before the user is asked to care.
                    client.cleanUpStale(keep = downloaded, directory = downloadDir())
                    adopt(cached, fromCache = true)
                } else if (cached != null) {
                    // The cached release is no longer ahead of us, which after an
                    // in-app update means it just installed. The APK in
                    // `filesDir/updates` survives that update — 37 MB the app has no
                    // further use for — and the stale payload would keep describing a
                    // version the user is already running, so both go.
                    release = null
                    downloaded = null
                    client.cleanUpStale(keep = null, directory = downloadDir())
                    store.edit {
                        it.remove(KEY_RELEASE_BODY)
                        it.remove(KEY_RELEASE_ASSET)
                    }
                }
                // A few seconds of grace: the first frames of a cold start belong to
                // the player, the database and the feed, not to GitHub. Launched
                // separately, and from inside the collector, because `store.data`
                // never completes — code placed after this collector in this
                // coroutine would never run at all, which is exactly why the startup
                // check was silently dead until the launch test caught it: the toggle
                // was stored, shown and read, and nothing ever happened on boot.
                if (!startupScheduled && autoCheck && isStale()) {
                    startupScheduled = true
                    scope.launch {
                        delay(STARTUP_DELAY_MS)
                        checkForUpdate(isSilent = true)
                    }
                }
            }
        }
    }

    /** True when it is worth asking GitHub again. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        now - lastCheckAt >= UpdateConfig.MIN_CHECK_INTERVAL_MS

    /**
     * Builds that ship the APK uncompressed (no zip alignment requirement for the
     * install-time compression step) need the matching asset, because installing a
     * compressed build over an uncompressed one needs a full uninstall.
     */
    fun isUncompressedBuild(): Boolean {
        val buildType = runCatching { BuildConfig.BUILD_TYPE }.getOrDefault("")
        return buildType.contains("uncompressed", ignoreCase = true) ||
            buildType.contains("raw", ignoreCase = true)
    }

    fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun installedVersionCode(): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    // ------------------------------------------------------------------ lookup

    /**
     * Ask GitHub what the latest release is.
     *
     * @param isSilent the startup path. A silent check may offer an update, but it
     *   never produces a message the user has to dismiss: a network blip during
     *   boot is not their problem, while a button that answers nothing is.
     */
    fun checkForUpdate(isSilent: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            _updateInfo.value = _updateInfo.value.copy(
                isChecking = true,
                phase = UpdatePhase.CHECKING,
                message = if (isSilent) _updateInfo.value.message else null,
            )
            val installed = getCurrentVersion()
            val outcome = runCatching { client.fetchLatest(installed, isUncompressedBuild()) }
                .getOrElse { error ->
                    Log.w(TAG, "update check threw", error)
                    UpdateCheck.Malformed
                }
            when (outcome) {
                is UpdateCheck.Available -> {
                    release = outcome.release
                    downloaded = matchingDownload(outcome.release)
                    persist(outcome.raw, outcome.release.asset?.name)
                    adopt(outcome.release, fromCache = false)
                }

                is UpdateCheck.UpToDate -> {
                    release = outcome.release
                    persist(outcome.raw, outcome.release.asset?.name)
                    downloaded = null
                    _updateInfo.value = _updateInfo.value.copy(
                        isChecking = false,
                        isUpdateAvailable = false,
                        phase = UpdatePhase.UP_TO_DATE,
                        latestVersion = outcome.release.displayVersion,
                        releaseNotes = outcome.release.notes,
                        releaseUrl = outcome.release.pageUrl,
                        downloadUrl = null,
                        downloadFileName = null,
                        downloadedFile = null,
                        verification = null,
                        isDismissed = false,
                        message = null,
                    )
                    noteCheckTime()
                }

                UpdateCheck.RepositoryNotFound -> fail(
                    isSilent,
                    "No published release was found for ${UpdateConfig.OWNER}/${UpdateConfig.REPO}. " +
                        "The repository has to be public and its latest release has to hold the APK.",
                )

                UpdateCheck.RateLimited -> fail(
                    isSilent,
                    "GitHub's anonymous API limit is used up on this network. Try again in an hour.",
                )

                UpdateCheck.Offline -> fail(isSilent, "No connection to GitHub right now.")

                UpdateCheck.Malformed -> fail(isSilent, "GitHub answered in a shape this build does not understand.")
            }
            busy = false
        }
    }

    private suspend fun persist(raw: String, assetName: String?) {
        if (raw.isBlank()) return
        store.edit {
            it[KEY_RELEASE_BODY] = raw
            if (assetName != null) it[KEY_RELEASE_ASSET] = assetName else it.remove(KEY_RELEASE_ASSET)
        }
    }

    /**
     * Make a release visible in the UI. Separate from the lookup so a release
     * restored from the cache on a cold start and one just fetched from GitHub take
     * exactly the same path and cannot drift apart.
     */
    private fun adopt(release: ReleaseInfo, fromCache: Boolean) {
        val ready = downloaded?.exists() == true
        val asset = release.asset
        _updateInfo.value = _updateInfo.value.copy(
            isChecking = false,
            isUpdateAvailable = true,
            phase = if (ready) UpdatePhase.READY else UpdatePhase.AVAILABLE,
            latestVersion = release.displayVersion,
            releaseNotes = release.notes,
            releaseUrl = release.pageUrl,
            downloadUrl = asset?.downloadUrl,
            downloadFileName = asset?.name,
            downloadedFile = if (ready) downloaded?.absolutePath else null,
            totalBytes = asset?.size ?: 0L,
            downloadedBytes = if (ready) downloaded?.length() ?: 0L else 0L,
            isDismissed = isSuppressed(release),
            canInstall = canRequestInstalls(),
            message = when {
                asset == null ->
                    "Release ${release.displayVersion} has no installable APK attached to it."

                fromCache && !ready -> "Release ${release.displayVersion} is waiting to be downloaded."
                else -> null
            },
        )
    }

    private fun isSuppressed(release: ReleaseInfo): Boolean =
        dismissed.isNotBlank() && dismissed != UpdateConfig.DISMISS_LATEST &&
            UpdateRelease.sameVersion(dismissed, release.tag)

    private fun noteCheckTime() {
        lastCheckAt = System.currentTimeMillis()
        scope.launch { store.edit { it[KEY_LAST_CHECK] = lastCheckAt } }
    }

    private fun fail(isSilent: Boolean, message: String?) {
        val current = _updateInfo.value
        // A silent check that failed says nothing: the user was not doing anything,
        // and a red sentence at app start over a dead Wi-Fi is just noise. A failed
        // check that they asked for answers, even with bad news.
        val shown = when {
            !isSilent -> message
            current.isUpdateAvailable -> current.message
            else -> null
        }
        _updateInfo.value = current.copy(
            isChecking = false,
            // A failure must not erase an offer that is still valid: the release
            // was already fetched, a dead follow-up check changes nothing about it.
            phase = if (current.phase == UpdatePhase.READY || current.phase == UpdatePhase.DOWNLOADING) {
                current.phase
            } else {
                UpdatePhase.FAILED
            },
            message = shown,
        )
        noteCheckTime()
    }

    // ---------------------------------------------------------------- download

    /**
     * Start the download, or install straight away when the file is already here
     * from an earlier attempt. This is what the Settings button and the startup
     * banner's "Update" both call, so the two can never disagree about what a tap
     * means.
     */
    fun openUpdate(context: Context) {
        val current = _updateInfo.value
        val file = downloaded
        if (current.phase == UpdatePhase.READY && file?.exists() == true) {
            install(context)
            return
        }
        val asset = release?.asset
        if (asset == null) {
            _updateInfo.value = current.copy(
                message = "Nothing to download yet: check for an update first.",
            )
            return
        }
        if (current.phase == UpdatePhase.DOWNLOADING || busy) return
        busy = true
        val target = File(downloadDir(), UpdateRelease.safeFileName(asset.name, release?.tag.orEmpty()))
        _updateInfo.value = current.copy(
            phase = UpdatePhase.DOWNLOADING,
            downloadedBytes = 0L,
            totalBytes = asset.size,
            verification = null,
            message = null,
        )
        scope.launch {
            // Anything that is not a returned verdict must still leave the row
            // usable. The client turns I/O trouble into `Failed`; what escapes to
            // here is the unexpected kind, and a row frozen on "Downloading…"
            // is the one outcome a user cannot act on.
            val result = runCatching {
                client.download(asset, target) { done, total ->
                    _updateInfo.value = _updateInfo.value.copy(downloadedBytes = done, totalBytes = total)
                }
            }.getOrElse { error ->
                busy = false
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.w(TAG, "update download threw", error)
                _updateInfo.value = _updateInfo.value.copy(
                    phase = UpdatePhase.AVAILABLE,
                    message = "The download stopped: ${error.message ?: "unexpected error"}.",
                )
                return@launch
            }
            when (result) {
                is DownloadResult.Complete -> onDownloaded(asset, result)

                is DownloadResult.Failed -> {
                    busy = false
                    _updateInfo.value = _updateInfo.value.copy(
                        phase = UpdatePhase.AVAILABLE,
                        message = result.message,
                    )
                }

                DownloadResult.Cancelled -> {
                    busy = false
                    _updateInfo.value = _updateInfo.value.copy(
                        phase = UpdatePhase.AVAILABLE,
                        message = "Download cancelled.",
                    )
                }
            }
        }
    }

    private suspend fun onDownloaded(asset: ReleaseAsset, result: DownloadResult.Complete) {
        val expectedSha = expectedDigest(asset)
        val facts = inspectApk(result.file)
        val expectedBytes = if (result.declaredBytes > 0L) result.declaredBytes else asset.size
        val verdict = UpdateRelease.verify(
            downloadedBytes = result.bytes,
            expectedBytes = expectedBytes,
            actualSha256 = result.sha256,
            expectedSha256 = expectedSha,
            archiveReadable = facts.readable,
            archiveVersionCode = facts.versionCode,
            installedVersionCode = installedVersionCode(),
            sameSigner = facts.signerMatches || !facts.signerReadable,
        )
        busy = false
        if (verdict != Verification.Passed) {
            Log.w(TAG, "refusing downloaded APK: ${verdict.name}")
            runCatching { result.file.delete() }
            downloaded = null
            _updateInfo.value = _updateInfo.value.copy(
                phase = UpdatePhase.AVAILABLE,
                downloadedFile = null,
                downloadedBytes = 0L,
                verification = null,
                canInstall = false,
                message = "Not installed: ${verdict.reason}.",
            )
            return
        }
        downloaded = result.file
        _updateInfo.value = _updateInfo.value.copy(
            phase = UpdatePhase.READY,
            downloadedBytes = result.bytes,
            totalBytes = if (expectedBytes > 0L) expectedBytes else result.bytes,
            downloadedFile = result.file.absolutePath,
            verification = describe(facts, expectedSha != null),
            message = null,
            canInstall = canRequestInstalls(),
        )
    }

    /**
     * What the dialog is allowed to claim. Saying "verified" when GitHub recorded
     * no digest, or when the archive's signer could not be read, would be the kind
     * of confident lie this whole file exists to prevent.
     */
    private fun describe(facts: ApkFacts, digestMatched: Boolean): String = when {
        digestMatched && facts.signerReadable ->
            "Verified: size, SHA-256 and signing key all match the release."

        digestMatched ->
            "Verified: size and SHA-256 match the release. Android would not report the signing key, so that one check was left to the installer."

        facts.signerReadable ->
            "Size and signing key match. This release recorded no digest, so the bytes were not compared against a hash."

        else ->
            "Size and versionCode match. This release recorded no digest, and the signing key could not be read — the installer is the last line here."
    }

    /** GitHub's recorded `digest` for the asset, or the matching line in a `CHECKSUMS.txt`. */
    private suspend fun expectedDigest(asset: ReleaseAsset): String? {
        asset.sha256Hex?.let { return it }
        val url = release?.checksumsUrl ?: return null
        val text = runCatching { client.fetchText(url) }.getOrNull() ?: return null
        return UpdateRelease.parseChecksums(text)[asset.name]
    }

    /**
     * Read the downloaded APK the way the platform reads it.
     *
     * Delegated to [ApkArchiveInspector] rather than calling `PackageManager`
     * here, because the most important decision in this file — install or refuse —
     * would otherwise be untestable: no JVM test can hand a real signed APK to
     * Android's archive parser. `getPackageArchiveInfo` with signing info is also
     * not answered the same way by every OEM build, so the seam reports "could not
     * tell" apart from "differs": the first is a caveat in the UI, the second is a
     * refusal.
     */
    private fun inspectApk(file: File): ApkFacts = runCatching { archiveInspector.inspect(file) }
        .getOrDefault(ApkFacts.Unreadable)

    // ---------------------------------------------------------------- install

    /**
     * Hand the file to the system installer, asking for the one special access
     * this needs when it is missing. Firing the intent without
     * `REQUEST_INSTALL_PACKAGES` opens nothing and leaves the user with an
     * Android error they cannot act on, so the missing grant is its own state.
     */
    fun install(context: Context) {
        val file = downloaded?.takeIf { it.exists() } ?: run {
            _updateInfo.value = _updateInfo.value.copy(
                phase = if (release?.asset != null) UpdatePhase.AVAILABLE else _updateInfo.value.phase,
                downloadedFile = null,
                message = "The downloaded file is gone; download it again.",
            )
            return
        }
        if (!canRequestInstalls()) {
            _updateInfo.value = _updateInfo.value.copy(
                phase = UpdatePhase.READY,
                canInstall = false,
                message = "LastWave needs permission to install apps. Allow it here, then tap Install again.",
            )
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w(TAG, "unknown-sources settings screen unavailable", it) }
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }.getOrElse { error ->
            Log.w(TAG, "FileProvider refused the APK", error)
            _updateInfo.value = _updateInfo.value.copy(
                message = "The downloaded file could not be shared with the installer.",
            )
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { error ->
                Log.w(TAG, "installer refused to start", error)
                _updateInfo.value = _updateInfo.value.copy(
                    message = "The system installer did not open. The release is at " +
                        "${release?.pageUrl ?: UpdateConfig.LATEST_RELEASE_PAGE}",
                )
            }
    }

    fun canRequestInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * "Not for this version." Remembered against the release tag, so the next
     * release is offered again; `DISMISS_LATEST` (or an empty version, which is
     * what the banner's × passes) means "whatever is newest right now".
     */
    fun dismissUpdate(version: String) {
        // Store whichever form names the release best: callers pass what the UI
        // shows (`latestVersion`, no `v`), and the tag is what a later lookup
        // compares against. Keeping the tag when it is the same release means the
        // suppression still matches after a restart.
        val currentTag = release?.tag
        val target = when {
            version.isBlank() || version == UpdateConfig.DISMISS_LATEST ->
                currentTag ?: UpdateConfig.DISMISS_LATEST

            currentTag != null && UpdateRelease.sameVersion(version, currentTag) -> currentTag
            else -> version
        }
        dismissed = target
        scope.launch { store.edit { it[KEY_DISMISSED] = dismissed } }
        _updateInfo.value = _updateInfo.value.copy(isDismissed = true)
    }

    fun setAutoCheck(enabled: Boolean) {
        autoCheckTouched = true
        autoCheck = enabled
        _updateInfo.value = _updateInfo.value.copy(autoCheck = enabled)
        scope.launch { store.edit { it[KEY_AUTO_CHECK] = enabled } }
        if (enabled && isStale()) checkForUpdate(isSilent = true)
    }

    /**
     * The APK stays on disk so a cancelled install can be retried without
     * re-downloading 37 MB. Deleted when the update is installed over, or when the
     * user asks.
     */
    fun deleteDownload() {
        downloaded?.let { file -> runCatching { file.delete() } }
        downloaded = null
        client.cleanUpStale(keep = null, downloadDir())
        val current = _updateInfo.value
        _updateInfo.value = current.copy(
            phase = if (current.isUpdateAvailable) UpdatePhase.AVAILABLE else current.phase,
            downloadedFile = null,
            downloadedBytes = 0L,
            verification = null,
        )
    }

    /** For the dialog's "see the release on GitHub" link. */
    fun openReleasePage(context: Context) {
        val url = release?.pageUrl ?: UpdateConfig.LATEST_RELEASE_PAGE
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "no browser for the release page", it) }
    }

    private fun downloadDir(): File =
        File(context.applicationContext.filesDir, "updates").apply { mkdirs() }

    /** A file already on disk for this exact release counts as a finished download. */
    private fun matchingDownload(release: ReleaseInfo): File? {
        val asset = release.asset ?: return null
        val candidate = File(downloadDir(), UpdateRelease.safeFileName(asset.name, release.tag))
        if (!candidate.exists()) return null
        val expected = asset.size
        if (expected > 0L && candidate.length() != expected) {
            // Half a file from an interrupted attempt: say nothing, download it
            // again, and never offer a truncated APK to the installer.
            runCatching { candidate.delete() }
            return null
        }
        return candidate
    }

    internal companion object {
        const val TAG = "AppUpdateManager"
        const val STARTUP_DELAY_MS = 4_000L
        val KEY_DISMISSED = stringPreferencesKey("dismissed_version")
        val KEY_AUTO_CHECK = booleanPreferencesKey("auto_check")
        val KEY_LAST_CHECK = longPreferencesKey("last_check_ms")
        val KEY_RELEASE_BODY = stringPreferencesKey("latest_release_body")
        val KEY_RELEASE_ASSET = stringPreferencesKey("latest_release_asset")
    }
}

internal val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_prefs")
