package com.lastwave.app.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import com.lastwave.app.data.addon.LoopbackHttpServer
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Update button, end to end: look up → offer → download → verify → hand off to
 * the installer. Real loopback HTTP, real files on disk, only the two things a JVM
 * cannot provide replaced — the package manager's verdict on the archive, and
 * Android's "install unknown apps" grant.
 *
 * What that leaves unproven, and it is worth saying: whether Android's
 * `PackageInstaller` accepts the file at the other end of the intent needs a
 * device. `UpdateInstallerContractTest` pins the manifest half of that handoff —
 * the permission, the FileProvider authority and the shared path — because that is
 * the half that fails silently.
 *
 * DataStore is one singleton per process, so the tests use distinct release tags
 * rather than a shared fake preference reset; a tag nobody else uses cannot pick up
 * another test's dismissal or cached release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppUpdateManagerTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    /** The archive verdict [ApkArchiveInspector] hands back, changed per test. */
    private class FakeArchive(@Volatile var facts: ApkFacts) : ApkArchiveInspector {
        @Volatile
        var inspectedPath: String? = null

        override fun inspect(file: File): ApkFacts {
            inspectedPath = file.absolutePath
            return facts
        }

        override fun installedSignature(): ByteArray? = byteArrayOf(1, 2, 3)
        override fun installedVersionCode(): Long = 38L
    }

    private var archive = FakeArchive(PASSED)
    private val apiHits = AtomicInteger()
    private val fileHits = AtomicInteger()
    /**
     * The two servers live for the whole test and only their *answers* change.
     *
     * Re-binding a port between phases looks harmless and is not: the manager under
     * test was handed the old URL already, so a re-serve makes the next lookup hit a
     * closed socket, and the failure it then reports — "No connection to GitHub" —
     * reads like a product bug instead of a fixture bug. So `serve*` swaps a volatile
     * responder and never touches the listening socket.
     */
    @Volatile
    private var apiResponder: (Socket) -> Unit = { write(it, 200, "application/json", "{}".toByteArray()) }
    @Volatile
    private var fileResponder: (Socket) -> Unit = { write(it, 200, "application/octet-stream", ByteArray(0)) }
    private val apiServer: LoopbackHttpServer by lazy {
        LoopbackHttpServer { socket -> apiHits.incrementAndGet(); apiResponder(socket) }
    }
    private val fileServer: LoopbackHttpServer by lazy {
        LoopbackHttpServer { socket -> fileHits.incrementAndGet(); fileResponder(socket) }
    }
    private val managers = mutableListOf<AppUpdateManager>()

    /** One body for every test: what the release promises is what the server sends. */
    private val payload: ByteArray by lazy { ByteArray(64 * 1024) { (it % 251).toByte() } }
    private val payloadSha: String by lazy { sha256Hex(payload) }

    private companion object {
        val PASSED = ApkFacts(readable = true, versionCode = 99L, signerReadable = true, signerMatches = true)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun write(socket: Socket, status: Int, contentType: String, body: ByteArray) {
        val head = "HTTP/1.1 $status X\r\nContent-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().write(body)
        socket.getOutputStream().flush()
    }

    /**
     * Answer `/releases/latest` with a release holding one APK, and serve that APK
     * from a second loopback server — which is how GitHub works too (the JSON names
     * a different host for the bytes).
     */
    private fun serveRelease(tag: String, digest: String? = payloadSha, size: Long = payload.size.toLong()) {
        fileResponder = { socket -> write(socket, 200, "application/octet-stream", payload) }
        val digestField = if (digest == null) "" else "\"digest\":\"sha256:$digest\","
        val json = """
            {"tag_name":"$tag","name":"LastWave $tag","body":"Hearing profile EQ.",
             "html_url":"https://github.com/Tanmay-Tiwaricyber/LastWave/releases/tag/$tag",
             "published_at":"2026-11-09T10:00:00Z","prerelease":false,"draft":false,
             "assets":[{"name":"LastWave-Silent-Programmer-$tag.apk","size":$size,$digestField
                        "browser_download_url":"${fileServer.url}/LastWave-Silent-Programmer-$tag.apk"}]}
        """.trimIndent()
        apiResponder = { socket -> write(socket, 200, "application/json", json.toByteArray(Charsets.UTF_8)) }
    }

    private fun serveStatus(status: Int, body: String = "{}") {
        apiResponder = { socket -> write(socket, status, "application/json", body.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Build the manager under test.
     *
     * GitHub answers the release lookup on `api.github.com` and hands out the bytes
     * from a different host, so the rewrite keeps that split: a `/repos/...` path
     * goes to the JSON server, everything else to the file server. Pointing both at
     * one server is how a test ends up "downloading" the release JSON, then blaming
     * the verifier for the digest mismatch.
     */
    private fun manager(): AppUpdateManager {
        val api = apiServer
        val files = fileServer
        val shared = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url
                val origin = if (files == null || url.encodedPath.startsWith("/repos/")) api.url else files.url
                chain.proceed(chain.request().newBuilder().url(origin + url.encodedPath).build())
            }
            .build()
        return AppUpdateManager(context, GitHubUpdateClient(shared), archive).also { managers += it }
    }

    /**
     * The manager's settings live in real DataStore, which is one singleton per
     * process: without this, whichever test ran first leaves a cached release, a
     * dismissal and a check timestamp behind for everyone else, and the startup
     * checker fires whenever it likes. So every test starts from a known store —
     * and the one test that wants the startup path turns it back on itself.
     */
    private fun quietStore() {
        runBlocking {
            context.updateDataStore.edit { prefs ->
                prefs.clear()
                prefs[AppUpdateManager.KEY_AUTO_CHECK] = false
                prefs[AppUpdateManager.KEY_LAST_CHECK] = System.currentTimeMillis()
            }
        }
    }

    /** The manager works on its own scope, so states are awaited, never assumed. */
    private fun awaitUntil(what: String, predicate: (UpdateInfo) -> Boolean): UpdateInfo {
        val target = managers.last()
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val info = target.updateInfo.value
            if (predicate(info)) return info
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what; state is ${target.updateInfo.value}")
    }

    /**
     * Wait for a *stored* value, not a hoped-for one.
     *
     * Every setter here writes preferences from a coroutine, so a test that
     * constructs the next manager straight away is racing an async write — and the
     * failure it then reports ("the dismissal was forgotten") reads like a product
     * bug. The durable fact is also the fact worth pinning: a toggle that only lives
     * in memory is not a setting.
     */
    private fun awaitStored(what: String, predicate: (androidx.datastore.preferences.core.Preferences) -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val prefs = runBlocking { context.updateDataStore.data.first() }
            if (predicate(prefs)) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what to be stored")
    }

    /** Report an installed version the way a device does — `Config.NONE` has none. */
    private fun pretendInstalled(versionName: String, versionCode: Int) {
        val pm = context.packageManager
        val info = PackageInfo().apply {
            packageName = context.packageName
            this.versionName = versionName
            @Suppress("DEPRECATION")
            this.versionCode = versionCode
            applicationInfo = ApplicationInfo().apply { packageName = context.packageName }
            requestedPermissions = arrayOf("android.permission.INTERNET")
        }
        runCatching { shadowOf(pm).installPackage(info) }
        val read = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        assertEquals("the test needs the package manager to report a version name", versionName, read)
    }

    @Before
    fun setUp() {
        pretendInstalled(versionName = "5.8.0", versionCode = 38)
        quietStore()
    }

    @After
    fun tearDown() {
        runCatching { apiServer.close() }
        runCatching { fileServer.close() }
        File(context.filesDir, "updates").deleteRecursively()
    }

    private fun offeredAndDownloaded(tag: String): Pair<AppUpdateManager, UpdateInfo> {
        serveRelease(tag)
        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        awaitUntil("an offer for $tag") { it.phase == UpdatePhase.AVAILABLE && it.latestVersion == tag.removePrefix("v") }
        manager.openUpdate(context)
        return manager to awaitUntil("a verified download") { it.phase == UpdatePhase.READY }
    }

    // ---------------------------------------------------------------- the button

    @Test
    fun updateButton_downloadsVerifiesAndBecomesReadyToInstall() {
        serveRelease("v6.1.0")
        val manager = manager()

        manager.checkForUpdate(isSilent = false)
        val offered = awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }
        assertEquals("6.1.0", offered.latestVersion)
        assertEquals("v6.1.0 is available", offered.statusLine())
        assertEquals("Download", offered.actionLabel())
        assertNotNull(offered.downloadUrl)
        assertEquals(payload.size.toLong(), offered.totalBytes)
        assertNull("nothing is on disk before the tap", offered.downloadedFile)
        assertEquals("one lookup per check", 1, apiHits.get())
        assertEquals("no download before the tap", 0, fileHits.get())

        manager.openUpdate(context)
        val ready = awaitUntil("a verified download") { it.phase == UpdatePhase.READY }

        assertEquals(payload.size.toLong(), ready.downloadedBytes)
        assertEquals(payload.size.toLong(), ready.totalBytes)
        // Byte accounting all the way through: the release's declared size is what the
        // row reports as the total, and the counted bytes reach it. `progress` — the
        // percent line — is deliberately null once the phase is READY ("Verified and
        // ready to install" replaces it), which `UpdateReleaseTest` pins; whether a
        // live percentage *animates* on screen is a device question, not a logic one.
        assertEquals(payload.size.toLong(), ready.downloadedBytes)
        assertEquals(payload.size.toLong(), ready.totalBytes)
        assertNull(ready.progress)
        assertEquals("Install", ready.actionLabel())
        // The file about to be installed is the file that was written, byte-identical
        // to what the server held.
        val onDisk = File(ready.downloadedFile!!)
        assertTrue(onDisk.isFile)
        assertEquals(payload.size.toLong(), onDisk.length())
        assertEquals(payloadSha, sha256Hex(onDisk.readBytes()))
        // And that file is the one that was verified, not some other path.
        assertEquals(onDisk.absolutePath, archive.inspectedPath)
        assertTrue(ready.verification?.contains("SHA-256") == true)
        assertNull("a clean run has nothing to complain about", ready.message)
        assertEquals(1, fileHits.get())
    }

    @Test
    fun updateButton_saysSoWhenTheReleaseRecordedNoDigest() {
        // This fork's releases hold one APK and nothing else. A payload without a
        // digest must produce a plain statement of what was checked, never the bare
        // word "verified".
        serveRelease("v6.2.0", digest = null)
        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }
        manager.openUpdate(context)

        val ready = awaitUntil("a verified download") { it.phase == UpdatePhase.READY }
        assertTrue("expected the no-digest caveat, got ${ready.verification}", ready.verification?.contains("no digest") == true)
    }

    @Test
    fun updateButton_refusesAnApkWhoseDigestDisagrees() {
        serveRelease("v6.3.0", digest = "0".repeat(64))
        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }

        manager.openUpdate(context)
        val refused = awaitUntil("a refusal") { it.phase == UpdatePhase.AVAILABLE && it.message != null }

        assertTrue(refused.message!!.startsWith("Not installed:"))
        assertTrue("the reason must name the hash: ${refused.message}", refused.message!!.contains("SHA-256"))
        assertNull("a refused file is never offered for install", refused.downloadedFile)
        val leftovers = File(context.filesDir, "updates").listFiles()?.filter { it.name.endsWith(".apk") }.orEmpty()
        assertTrue("the refused APK should be deleted, found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun updateButton_refusesAnArchiveThePlatformCannotRead() {
        serveRelease("v6.4.0")
        archive = FakeArchive(ApkFacts.Unreadable)
        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }
        manager.openUpdate(context)

        val refused = awaitUntil("a refusal") { it.message?.contains("Not installed") == true }
        assertTrue(refused.message!!, refused.message!!.contains("not a readable APK"))
    }

    @Test
    fun updateButton_refusesAnApkThatIsNotNewerThanThisBuild() {
        // A release tagged newer whose archive carries this build's versionCode is a
        // re-upload of the old file: installing it would change nothing, while the
        // dialog would claim the update happened.
        serveRelease("v6.5.0")
        archive = FakeArchive(ApkFacts(readable = true, versionCode = 38L, signerReadable = true, signerMatches = true))
        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }
        manager.openUpdate(context)

        val refused = awaitUntil("a refusal") { it.message?.contains("Not installed") == true }
        assertTrue(refused.message!!, refused.message!!.contains("not newer than the version you are running"))
    }

    @Test
    fun updateButton_refusesAnApkSignedWithSomeoneElsesKey() {
        serveRelease("v6.6.0")
        archive = FakeArchive(ApkFacts(readable = true, versionCode = 99L, signerReadable = true, signerMatches = false))
        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }
        manager.openUpdate(context)

        val refused = awaitUntil("a refusal") { it.message?.contains("Not installed") == true }
        assertTrue(refused.message!!, refused.message!!.contains("signed with this app's key"))
    }

    @Test
    fun updateButton_leavesTheRowUsableWhenTheDownloadDies() {
        // The file server accepts connections and hangs up: an IOException mid-body,
        // which is the shape of a dropped connection on a train.
        fileResponder = { socket ->
            // A header that promises almost a megabyte, no body, then hang up: the
            // shape of a connection dropped on a train.
            socket.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Length: 999999\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII),
            )
            socket.getOutputStream().flush()
            socket.close()
        }
        val json = """
            {"tag_name":"v6.7.0","assets":[{"name":"LastWave-Silent-Programmer-v6.7.0.apk","size":${payload.size},
             "browser_download_url":"${fileServer.url}/a.apk"}]}
        """.trimIndent()
        apiResponder = { socket -> write(socket, 200, "application/json", json.toByteArray(Charsets.UTF_8)) }

        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }
        manager.openUpdate(context)

        val broken = awaitUntil("a failure the user can act on") { it.phase == UpdatePhase.AVAILABLE && it.message != null }
        assertTrue(broken.message!!, broken.message!!.contains("download stopped"))
        assertEquals("Download", broken.actionLabel())
        assertNull(broken.downloadedFile)
    }

    // ----------------------------------------------------------------- failures

    @Test
    fun missingRepositoryIsReportedAsThat() {
        serveStatus(404, """{"message":"Not Found"}""")
        val manager = manager()
        manager.checkForUpdate(isSilent = false)

        val failed = awaitUntil("a failure") { it.phase == UpdatePhase.FAILED }
        assertFalse("a dead lookup must never read as an available update", failed.isUpdateAvailable)
        assertTrue(
            failed.message!!,
            failed.message!!.contains("No published release was found for Tanmay-Tiwaricyber/LastWave"),
        )
        assertTrue("it also says what to do about it: ${failed.message}", failed.message!!.contains("public"))
        assertEquals(failed.message, failed.statusLine())
    }

    @Test
    fun aSilentFailureSaysNothingButStillCountsAsChecked() {
        serveStatus(403, """{"message":"rate limited"}""")
        val manager = manager()
        manager.checkForUpdate(isSilent = true)

        val state = awaitUntil("the check to finish") { !it.isChecking && it.phase == UpdatePhase.FAILED }
        assertNull("a startup check must not leave a message to dismiss", state.message)
        // The 12-hour window applies after failures too, so a private or missing
        // repository cannot make the app hammer GitHub on every launch.
        assertFalse(manager.isStale())
    }

    @Test
    fun aManualCheckReportsTheRateLimitOutLoud() {
        serveStatus(403, """{"message":"rate limited"}""")
        val manager = manager()
        manager.checkForUpdate(isSilent = false)

        val failed = awaitUntil("a failure") { it.phase == UpdatePhase.FAILED }
        assertTrue(failed.message!!, failed.message!!.contains("anonymous API limit"))
    }

    @Test
    fun aReleaseWithNoApkSaysSoInsteadOfPromisingADownload() {
        apiResponder = { socket ->
            val json = """
                {"tag_name":"v6.8.0","body":"oops","assets":[{"name":"source.zip","size":10,
                 "browser_download_url":"http://127.0.0.1:1/source.zip"}]}
            """.trimIndent()
            write(socket, 200, "application/json", json.toByteArray(Charsets.UTF_8))
        }
        val manager = manager()
        manager.checkForUpdate(isSilent = false)

        // The client refuses to call a release with no installable asset "available",
        // so the row says which version it looked at and shows nothing installable.
        // `!isChecking` is true before the check even starts (the initial state is not
        // checking), so wait for the lookup to have *landed* instead.
        val state = awaitUntil("the check to settle") {
            !it.isChecking && (it.phase != UpdatePhase.IDLE || it.latestVersion.isNotEmpty())
        }
        assertEquals(UpdatePhase.UP_TO_DATE, state.phase)
        assertFalse(state.isUpdateAvailable)
        assertNull(state.downloadUrl)
    }

    // ------------------------------------------------------------- the installer

    @Test
    fun installButton_opensAndroidsUnknownAppsSettingWhenTheGrantIsMissing() {
        val (manager, ready) = offeredAndDownloaded("v6.9.0")
        assertTrue(ready.phase == UpdatePhase.READY)

        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        assertFalse(manager.canRequestInstalls())
        manager.install(context)

        val started: Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("the user has to be taken somewhere actionable", started)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started!!.action)
        assertEquals("package:${context.packageName}", started.data?.toString())

        val state = manager.updateInfo.value
        assertEquals(UpdatePhase.READY, state.phase)
        assertFalse(state.canInstall)
        assertTrue(state.message!!, state.message!!.contains("permission to install apps"))
    }

    @Test
    fun installButton_handsTheDownloadedFileToTheProviderWhenTheGrantIsPresent() {
        val (manager, ready) = offeredAndDownloaded("v7.0.0")
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(manager.canRequestInstalls())

        // Robolectric registers no FileProvider (Config.NONE has no manifest), so this
        // asserts the branch the manager owns: with the grant present it does not send
        // the user to settings, it goes for the provider with the file it verified.
        // Either the share worked, or the state says it could not — both are answers.
        manager.install(context)

        val state = manager.updateInfo.value
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertTrue(
            "must not be redirected to the settings screen when the grant is present: $started",
            started?.action != Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        )
        val sharedOrRefused = state.message?.contains("could not be shared with the installer") == true ||
            started?.action == Intent.ACTION_VIEW
        assertTrue("expected the provider handoff to be attempted, state=$state", sharedOrRefused)
        assertEquals("the verified file stays put either way", ready.downloadedFile, state.downloadedFile)
    }

    @Test
    fun installButton_saysSoWhenTheFileVanished() {
        val (manager, ready) = offeredAndDownloaded("v7.1.0")
        File(ready.downloadedFile!!).delete()

        manager.install(context)
        val state = manager.updateInfo.value
        assertTrue(state.message!!, state.message!!.contains("downloaded file is gone"))
        assertEquals(UpdatePhase.AVAILABLE, state.phase)
    }

    @Test
    fun tappingUpdateWhileTheFileIsHereInstallsInsteadOfReDownloading() {
        val (manager, ready) = offeredAndDownloaded("v7.2.0")
        val filesBefore = fileHits.get()

        // This is what the banner's button does on a cold start after a cancelled
        // install: one tap, straight to the installer, no second 37 MB.
        manager.openUpdate(context)

        assertEquals(filesBefore, fileHits.get())
        assertEquals(UpdatePhase.READY, manager.updateInfo.value.phase)
        assertEquals(ready.downloadedFile, manager.updateInfo.value.downloadedFile)
    }

    // ------------------------------------------------------------- cache & files

    @Test
    fun aFinishedDownloadIsOfferedAgainWithoutFetchingItTwice() {
        val (first, ready) = offeredAndDownloaded("v7.3.0")
        assertTrue(File(ready.downloadedFile!!).exists())
        val apiBefore = apiHits.get()
        val downloads = fileHits.get()

        // A fresh manager is a cold start: same release, same file on disk, and no
        // reason to move 64 KB twice.

        val second = manager()
        val restored = awaitUntil("the cached release to be restored") { it.phase == UpdatePhase.READY }
        assertEquals(ready.downloadedFile, restored.downloadedFile)
        assertEquals("the APK must not be downloaded a second time", downloads, fileHits.get())
        assertEquals("and the lookup is not repeated inside the freshness window", apiBefore, apiHits.get())
    }

    @Test
    fun stalePartialsAreSweptOnLaunch() {
        val (manager, ready) = offeredAndDownloaded("v7.4.0")
        val keep = File(ready.downloadedFile!!)
        val dir = keep.parentFile!!
        val orphan = File(dir, "LastWave-Silent-Programmer-v0.0.1.apk.part").apply { writeText("half a file") }
        assertTrue(orphan.exists())

        // A `.part` is what an interrupted attempt leaves behind. Nothing resumes it,
        // and it survives an in-place update, so the next launch deletes it — and only
        // it.
        val restarted = manager()
        awaitUntil("the launch to settle") { it.phase == UpdatePhase.READY }
        assertFalse("orphan .part should be swept", orphan.exists())
        assertTrue("the file this release maps to must survive the sweep", keep.exists())
    }

    @Test
    fun aCachedReleaseNobodyInstalledIsForgottenAfterTheAppMovedOn() {
        val (_, ready) = offeredAndDownloaded("v7.5.0")
        assertTrue(File(ready.downloadedFile!!).exists())

        // Pretend the user updated by other means to a newer version than the cached
        // release: the stale payload and its 37 MB of APK both go, rather than the app
        // describing a version they are already running.
        pretendInstalled(versionName = "99.0.0", versionCode = 999)
        val restarted = manager()
        // Await the deletion itself. "the phase moved or the file field cleared" is
        // true of a brand-new manager before its first settings read, so it proves
        // nothing and would pass on a build that never swept the cache at all.
        awaitUntil("the superseded download to be deleted") { !File(ready.downloadedFile!!).exists() }

        assertFalse("a superseded download should be deleted", File(ready.downloadedFile!!).exists())
        assertNull(restarted.updateInfo.value.downloadedFile)
        assertEquals(UpdatePhase.IDLE, restarted.updateInfo.value.phase)
    }

    @Test
    fun deleteDownloadFreesTheFileAndReturnsToTheOffer() {
        val (manager, ready) = offeredAndDownloaded("v7.6.0")
        manager.deleteDownload()

        val after = manager.updateInfo.value
        assertFalse(File(ready.downloadedFile!!).exists())
        assertEquals(UpdatePhase.AVAILABLE, after.phase)
        assertNull(after.downloadedFile)
        assertNull(after.verification)
        assertEquals("Download", after.actionLabel())
    }

    // ------------------------------------------------------------- dismissal

    @Test
    fun dismissalIsRememberedPerReleaseAndOutlivesTheLaunch() {
        serveRelease("v7.7.0")
        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        val offered = awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }
        assertFalse(offered.isDismissed)

        // The UI passes what it displays (`latestVersion`, no `v`) while suppression is
        // remembered against the tag (`v7.7.0`). Comparing those two without
        // normalising is how "Not now" appears to work until the next cold start.
        manager.dismissUpdate(offered.latestVersion)
        assertTrue(manager.updateInfo.value.isDismissed)
        awaitStored("the dismissal") { it[AppUpdateManager.KEY_DISMISSED] == "v7.7.0" }

        val restarted = manager()
        val restored = awaitUntil("the offer to come back from cache") { it.latestVersion == "7.7.0" }
        assertTrue(restored.isDismissed)
        // A dismissal silences the nag, not the fact: the banner goes away and the
        // Settings row still names the release that is sitting there. Asserting the
        // exact sentence here would pin copy, not behaviour, so it asserts the part
        // that has to survive — which version the row is talking about.
        assertTrue(restored.statusLine(), restored.statusLine().contains("7.7.0"))
        assertNull(restarted.updateInfo.value.downloadedFile)
    }

    // ------------------------------------------------------------- the startup path

    @Test
    fun aReleaseIsCheckedOnLaunchWithoutAnyoneTapping() {
        serveRelease("v8.1.0")
        // Fresh install: the toggle has never been touched, so it defaults on, and
        // there is no recorded check to wait out. This test is the regression guard
        // for the version of this file where the startup check sat *after* the
        // settings collector in a coroutine that never returns from `collect` — the
        // row was written, the toggle was stored, and GitHub was never asked.
        runBlocking {
            context.updateDataStore.edit { prefs ->
                prefs.clear()
                prefs[AppUpdateManager.KEY_LAST_CHECK] = 0L
            }
        }

        manager()
        val offered = awaitUntil("a startup offer") {
            it.phase == UpdatePhase.AVAILABLE && it.latestVersion == "8.1.0"
        }

        assertEquals(1, apiHits.get())
        assertEquals("Download", offered.actionLabel())
        assertNull("a silent check leaves nothing to dismiss", offered.message)
        assertFalse("the banner is what a startup check shows", offered.isDismissed)
    }

    @Test
    fun aCheckFromThisSessionIsNotRepeatedOnTheNextLaunch() {
        serveRelease("v8.2.0")
        // `quietStore` already recorded a check at "now" with autoCheck on. GitHub
        // gets asked once per 12 hours, not once per cold start, or the feature
        // becomes a rate-limit generator for anyone who restarts the app often.
        runBlocking {
            context.updateDataStore.edit { prefs -> prefs[AppUpdateManager.KEY_AUTO_CHECK] = true }
        }

        manager()
        // Longer than the grace period plus the network round trip this test never
        // wants to happen; an early assertion would pass even with the bug.
        Thread.sleep(6_000L)

        assertEquals("a check inside the window must not re-run", 0, apiHits.get())
        assertEquals(UpdatePhase.IDLE, manager().updateInfo.value.phase)
    }

    @Test
    fun aNewerReleaseIsOfferedAgainAfterADismissal() {
        serveRelease("v7.8.0")
        val manager = manager()
        manager.checkForUpdate(isSilent = false)
        awaitUntil("an offer") { it.phase == UpdatePhase.AVAILABLE }
        manager.dismissUpdate("7.8.0")
        assertTrue(manager.updateInfo.value.isDismissed)

        // Same manager, newer release: dismissing 7.8.0 says nothing about 7.9.0.
        serveRelease("v7.9.0")
        manager.checkForUpdate(isSilent = false)
        val next = awaitUntil("the newer release") { it.latestVersion == "7.9.0" }
        assertFalse("a dismissal is per version, not forever", next.isDismissed)
    }

    @Test
    fun autoCheckToggleIsStoredAndReadBack() {
        serveRelease("v8.0.0")
        val manager = manager()
        manager.setAutoCheck(true)
        awaitStored("auto-check on") { it[AppUpdateManager.KEY_AUTO_CHECK] == true }
        assertTrue("the row must follow the switch, not lag a launch behind", awaitUntil("the toggle on") { it.autoCheck }.autoCheck)

        manager.setAutoCheck(false)
        awaitStored("auto-check off") { it[AppUpdateManager.KEY_AUTO_CHECK] == false }
        assertFalse(manager.updateInfo.value.autoCheck)

        // The stored answer is `false`, which is *not* the field default (`true`), so a
        // manager that shows false has demonstrably read preferences rather than
        // inherited a fresh object's initial value.
        val restarted = manager()
        assertFalse(
            "a switch the user turned off stays off across a launch",
            awaitUntil("prefs to reload") { !it.autoCheck }.autoCheck,
        )
    }
}
