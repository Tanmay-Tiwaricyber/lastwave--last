package com.lastwave.app.data.update

import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where LastWave looks for new builds, and the arithmetic around them.
 *
 * A fork needs to change exactly two strings in [UpdateConfig]. Everything else
 * here is pure — no Context, no OkHttp, no [android.content.pm.PackageManager]
 * — so the parts that decide whether a user should be prompted, and whether a
 * downloaded file is safe to hand to the installer, can be tested on a JVM.
 */
object UpdateConfig {

    const val OWNER = "Tanmay-Tiwaricyber"

    /**
     * GitHub resolves owner and repository names case-insensitively, so the API
     * call still works if the repository ends up named `Lastwave`.
     */
    const val REPO = "LastWave"

    const val LATEST_RELEASE_API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    const val LATEST_RELEASE_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"

    /**
     * Unauthenticated GitHub API traffic is limited to 60 lookups per hour per
     * IP address. One per app launch is nothing, but a user who keeps force
     * quitting and reopening would run out and then get a 403 that looks like a
     * broken app, so results are considered fresh for this long.
     */
    const val MIN_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

    /** Do not nag again about a version the user already waved away. */
    const val DISMISS_LATEST = "latest"
}

/** One file attached to a GitHub release. */
@Serializable
data class ReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0L,
    /**
     * GitHub records `sha256:<hex>` for uploaded assets since 2024, which is why
     * a release holding nothing but the APK can still be verified byte for byte.
     * Null on older payloads and on instances that do not compute it.
     */
    val digest: String? = null,
) {
    /** The hex half of `digest`, normalised, or null when it is not sha256. */
    val isApk: Boolean get() = name.endsWith(".apk", ignoreCase = true)

    /** The hex half of `digest`, normalised, or null when it is not a sha256. */
    val sha256Hex: String? get() = UpdateRelease.parseDigest(digest)
}

/** A release, narrowed to what an updater can act on. */
data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val asset: ReleaseAsset?,
    val assetNames: List<String> = emptyList(),
    /**
     * Everything attached, in GitHub's order. The Settings dialog lists these and
     * the cache restore needs them: the file chosen on the live lookup is written
     * down by name, and re-finding it by name beats trusting that the selection
     * rules still agree across an app upgrade.
     */
    val allAssets: List<ReleaseAsset> = emptyList(),
    /**
     * A `CHECKSUMS.txt` attached to the same release, when there is one. GitHub's
     * own per-asset `digest` covers the normal case; this exists so a release can
     * also carry hashes for files the platform does not record one for.
     */
    val checksumsUrl: String? = null,
) {
    /** `v5.9.0` → `5.9.0`: what to show in a row, and what to compare with. */
    val displayVersion: String get() = tag.removePrefix("v").removePrefix("V")
}

/**
 * What the Settings row, the banner and the dialog render. One flat state
 * rather than a sealed hierarchy: three screens read it, and a data class with
 * defaults let the fork's existing callers keep compiling when the updater came
 * back on.
 */
data class UpdateInfo(
    val isChecking: Boolean = false,
    val isUpdateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = "",
    val releaseNotes: String = "",
    val releaseUrl: String = "",
    val downloadUrl: String? = null,
    val downloadFileName: String? = null,
    val isDismissed: Boolean = false,
    val message: String? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val autoCheck: Boolean = true,
    val downloadedFile: String? = null,
    /** False when Android has not granted "install unknown apps" yet. */
    val canInstall: Boolean = false,
    /** Plain words about what was verified, so the claim is visible, not implied. */
    val verification: String? = null,
) {
    /** 0f..1f while downloading, null when there is no meaningful fraction. */
    val progress: Float?
        get() = if (phase != UpdatePhase.DOWNLOADING || totalBytes <= 0L) {
            null
        } else {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * One line for the Settings row and the banner: what is true right now,
     * including the reason when the answer was "nothing, because the check failed".
     *
     * Lives in the data layer rather than in a composable so the wording a user
     * reads about their own security state is covered by unit tests, and so the
     * two surfaces cannot drift into describing the same state differently.
     */
    fun statusLine(): String = when (phase) {
        UpdatePhase.CHECKING -> "Checking GitHub…"
        UpdatePhase.DOWNLOADING -> progress?.let { "Downloading… ${(it * 100f).roundToInt()}%" }
            ?: "Downloading…"
        // The file is on disk and verified: that is the sentence worth showing, not
        // a second "available" that hides the fact that nothing more needs downloading.
        UpdatePhase.READY -> "Verified and ready to install"
        UpdatePhase.AVAILABLE -> message ?: "v$latestVersion is available"
        UpdatePhase.UP_TO_DATE -> if (currentVersion.isNotBlank()) {
            "v$currentVersion is the newest release"
        } else {
            "This is the newest release"
        }
        UpdatePhase.FAILED -> message ?: "The check did not finish"
        UpdatePhase.IDLE -> if (latestVersion.isNotBlank()) "v$latestVersion is available" else "Not checked yet"
    }

    /** The button's label, which is the only place a user learns what happens next. */
    fun actionLabel(): String = when (phase) {
        UpdatePhase.CHECKING -> "Checking\u2026"
        UpdatePhase.DOWNLOADING -> "Downloading\u2026"
        UpdatePhase.READY -> "Install"
        UpdatePhase.AVAILABLE -> "Download"
        UpdatePhase.UP_TO_DATE -> "Check again"
        else -> "Check for update"
    }
}

/** Where the updater is, from the point of view of the two screens that render it. */
enum class UpdatePhase {
    /** Nothing has been looked up in this process yet. */
    IDLE,

    CHECKING,

    /** A newer release is known; the APK is not on this device yet. */
    AVAILABLE,

    DOWNLOADING,

    /** The APK is on disk, verified, and one tap from the system installer. */
    READY,

    UP_TO_DATE,

    /** The lookup failed and the reason is in [UpdateInfo.message]. */
    FAILED,
}

/** Result of a lookup, kept separate from "an update exists" so failures can be named. */
sealed interface UpdateCheck {
    /**
     * A release newer than this build exists.
     *
     * @param raw the exact JSON GitHub answered with. It is what gets cached, so
     *   a cold start re-derives the same decision through the same parser instead
     *   of trusting a summary written by different code.
     */
    data class Available(val release: ReleaseInfo, val raw: String = "") : UpdateCheck

    /** GitHub answered, and this build is the newest one. */
    data class UpToDate(val release: ReleaseInfo, val raw: String = "") : UpdateCheck

    /** 404: no repository, a private one, or no published release yet. */
    data object RepositoryNotFound : UpdateCheck

    /** 403/429: the anonymous API quota for this IP is spent. */
    data object RateLimited : UpdateCheck

    /** No network, or the request failed outright. */
    data object Offline : UpdateCheck

    /** Answered, but not in the shape expected. */
    data object Malformed : UpdateCheck
}

/** The verdict on a downloaded APK, before the system installer is asked to touch it. */
enum class Verification(val reason: String) {
    Passed("ready to install"),
    Empty("the download came back with no bytes"),
    SizeMismatch("the download is a different size than the release says"),
    DigestMismatch("the download's SHA-256 does not match what GitHub recorded"),
    Unreadable("the file is not a readable APK"),
    NotThisApp("that APK is not signed with this app's key \u2014 do not install it"),
    NotNewer("that APK is not newer than the version you are running"),
    ;
}

/**
 * Parsing, choosing and verifying. All of it static, all of it testable.
 */
object UpdateRelease {

    private val HEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * The whole GitHub release payload → the parts that matter. Null when unparseable.
     *
     * [wantsUncompressed] is this build's own packaging: an uncompressed APK cannot
     * be installed over a compressed one without a full uninstall, so the variant
     * has to match, not merely be bigger.
     */
    fun parse(body: String, wantsUncompressed: Boolean = false): ReleaseInfo? = runCatching {
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        val dto = json.decodeFromString(GitHubReleaseDto.serializer(), body)
        if (dto.tag_name.isBlank()) return null
        val assets = dto.assets.filter { it.name.isNotBlank() && it.downloadUrl.isNotBlank() }
        ReleaseInfo(
            tag = dto.tag_name,
            title = dto.name.ifBlank { dto.tag_name },
            notes = dto.body.trim(),
            pageUrl = dto.html_url.ifBlank { UpdateConfig.LATEST_RELEASE_PAGE },
            publishedAt = dto.published_at,
            prerelease = dto.prerelease || dto.draft,
            asset = selectAsset(assets, wantsUncompressed),
            assetNames = assets.map { it.name },
            allAssets = assets,
            checksumsUrl = assets
                .firstOrNull { it.name.equals("CHECKSUMS.txt", ignoreCase = true) }
                ?.downloadUrl,
        )
    }.getOrNull()

    /**
     * The APK to install, from everything attached to the release.
     *
     * Deliberately narrow: only a file ending in `.apk` that looks like ours (the
     * `LastWave` or `Silent Programmer` name) is ever offered for install. Source
     * archives, checksum sidecars and somebody else's APK uploaded to the same
     * release are ignored, because the alternative — "first asset that ends in
     * .apk" — is how an updater ends up installing a stranger.
     *
     * Among the candidates: the packaging variant this build needs, then the
     * universal build, then the largest file (a per-ABI slice is smaller than the
     * combined APK, and this fork ships one APK for every device).
     */
    fun selectAsset(assets: List<ReleaseAsset>, wantsUncompressed: Boolean = false): ReleaseAsset? {
        val apks = assets.filter { it.isApk && looksLikeOurs(it.name) && isRunnableHere(it.name) }
        if (apks.isEmpty()) return null
        return apks.maxWithOrNull(
            compareBy<ReleaseAsset> { if (isUncompressed(it.name) == wantsUncompressed) 1 else 0 }
                .thenBy { if (isUniversal(it.name)) 1 else 0 }
                .thenBy { it.size },
        )
    }

    fun looksLikeOurs(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("lastwave") ||
            lower.contains("last-wave") ||
            lower.contains("silent-programmer") ||
            lower.contains("silent_programmer")
    }

    /**
     * Whether this device can run the variant at all. Earlier releases of the fork
     * carried an Android 7 slice and an OEM-tuned build; minSdk is 29 now and a
     * normal device must never pick one of those, because "the installer refused a
     * downgrade" is a miserable thing to discover after a 37 MB download.
     */
    fun isRunnableHere(name: String): Boolean =
        LEGACY_MARKERS.none { marker -> name.lowercase().contains(marker) }

    fun isUniversal(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("universal") || lower.contains("all-abi") || lower.contains("multi")
    }

    fun isUncompressed(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("uncompressed") || lower.contains("-raw") || lower.contains("no-compress")
    }

    /**
     * `android7` and friends are written into the file name by the release script,
     * so name matching is the only signal available before the file is downloaded.
     */
    private val LEGACY_MARKERS = listOf("android7", "android-7", "legacy", "vivo", "oem-")

    /**
     * `37863756` → `36.1 MB`. A 37 MB download is worth naming before it starts on
     * a metered connection, and a raw byte count is not something a person reads.
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "$bytes B"
        } else {
            String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
        }
    }

    /**
     * Numeric per-segment compare, because `5.10.0 > 5.9.0` is the whole reason
     * this function is not a string compare. A tag is allowed its `v`, and any
     * `-beta`/`+build` suffix is dropped: an updater that treats `5.9.0-rc1` as
     * newer than `5.9.0` will offer a pre-release over the stable build that
     * replaced it.
     */
    fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val remote = segments(remoteTag)
        val local = segments(localVersion)
        if (remote.isEmpty() || local.isEmpty()) return false
        val length = maxOf(remote.size, local.size)
        for (i in 0 until length) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }

    /**
     * `v5.9.0`, `V5.9.0`, `5.9.0` → `5.9.0`.
     *
     * A version is written with a `v` by `git tag` and without one by
     * `versionName`, and both forms end up in comparisons: the release tag from
     * GitHub, and the string the UI shows. Comparing them without normalising is
     * how "Don't show again" appears to work until the next launch.
     */
    fun normVersion(version: String): String =
        version.trim().removePrefix("v").removePrefix("V").trim()

    /** Whether two version strings name the same release. */
    fun sameVersion(a: String, b: String): Boolean =
        a.isNotBlank() && b.isNotBlank() && normVersion(a) == normVersion(b)

    /** `v5.9.0-rc1` → `[5, 9, 0]`. */
    fun segments(version: String): List<Int> = version
        .removePrefix("v").removePrefix("V")
        .substringBefore('-').substringBefore('+')
        .split('.')
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }

    /** `sha256:0A BF` → `0abf` lowercase, or null unless it is a 64-char hex sha256. */
    fun parseDigest(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        val hex = trimmed.substringAfter(':', trimmed).filter { !it.isWhitespace() }
        return if (HEX.matches(hex)) hex else null
    }

    /**
     * A `CHECKSUMS.txt` → file name to hash, newest line winning.
     *
     * Tolerant of both shapes people actually upload: `<hash>  <name>` as
     * `sha256sum` and this project's release notes write it, `<name> <hash>` as
     * GitHub's own checksum files write it, the `*` binary-mode marker, and a
     * directory prefix on the name.
     */
    fun parseChecksums(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size < 2) return@forEach
            val first = parseDigest(parts.first())
            val last = parseDigest(parts.last())
            val (hash, name) = when {
                first != null -> first to parts.drop(1).joinToString(" ")
                last != null -> last to parts.dropLast(1).joinToString(" ")
                else -> return@forEach
            }
            out[assetNameOf(name)] = hash
        }
        return out
    }

    /**
     * `./dist/Foo.apk`, `*Foo.apk`, `"Foo.apk"`, `Foo.apk` → `Foo.apk`: what a
     * release asset is actually called.
     *
     * The `*` is GNU coreutils' binary-mode marker and the quotes are what some
     * generators wrap around names containing spaces. Both describe the file, not
     * its name, and a lookup that failed on them would report a good download as
     * "digest does not match" — which is the worst possible false alarm here.
     */
    private fun assetNameOf(raw: String): String = raw
        .trim()
        .trim('"')
        .removePrefix("*")
        .removePrefix("./")
        .substringAfterLast('/')
        .substringAfterLast('\\')

    /**
     * Every check that can be run before the installer is invoked, in the order
     * that costs least. `archiveReadable`/`archiveVersionCode`/`sameSigner` come
     * from [android.content.pm.PackageManager.getPackageArchiveInfo] on device;
     * passing them in keeps the decision testable.
     */
    fun verify(
        downloadedBytes: Long,
        expectedBytes: Long,
        actualSha256: String?,
        expectedSha256: String?,
        archiveReadable: Boolean,
        archiveVersionCode: Long,
        installedVersionCode: Long,
        sameSigner: Boolean,
    ): Verification = when {
        downloadedBytes <= 0L -> Verification.Empty
        !archiveReadable -> Verification.Unreadable
        expectedBytes > 0L && downloadedBytes != expectedBytes -> Verification.SizeMismatch
        expectedSha256 != null && actualSha256?.lowercase() != expectedSha256.lowercase() -> Verification.DigestMismatch
        !sameSigner -> Verification.NotThisApp
        archiveVersionCode <= installedVersionCode -> Verification.NotNewer
        else -> Verification.Passed
    }

    /** Streaming SHA-256, lowercase hex. Null if the file cannot be read. */
    fun sha256Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** The file name to write an APK to: no slashes, no `..`, no spaces. */
    fun safeFileName(name: String, tag: String): String {
        val cleaned = name.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        if (cleaned.isNotBlank() && cleaned.endsWith(".apk", ignoreCase = true)) return cleaned
        val fallback = "LastWave-${tag.removePrefix("v").ifBlank { "update" }}.apk"
        return fallback.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }
}

/** Wire shapes of `GET /repos/{owner}/{repo}/releases/latest`. */
@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tag_name: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("html_url") val html_url: String = "",
    @SerialName("published_at") val published_at: String = "",
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("assets") val assets: List<ReleaseAsset> = emptyList(),
)
