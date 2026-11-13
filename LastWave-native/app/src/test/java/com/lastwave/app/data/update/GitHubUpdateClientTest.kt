package com.lastwave.app.data.update

import com.lastwave.app.data.addon.LoopbackHttpServer
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater's two HTTP operations, driven against a real socket on 127.0.0.1.
 *
 * A fake client would test my imagination instead of the code. What actually goes
 * wrong on a download is on the wire: a body shorter than the declared
 * `Content-Length`, a 403 that is not JSON, a canceled read that would leave a
 * `.part` file behind to be mistaken for a finished download by the next launch.
 *
 * `fetchLatest` is pointed at the loopback server by rewriting the request host, so
 * the URL, the headers and the status mapping are production ones. `download` is
 * given the URL that came out of the JSON being served, which is also how a real
 * release works: the app never decides where to fetch, GitHub's payload does.
 */
class GitHubUpdateClientTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "lw-update-test-${System.nanoTime()}")

    private fun rewritingClient(target: String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(request.newBuilder().url(target + request.url.encodedPath).build())
        }
        .build()

    private fun plainClient(): OkHttpClient = OkHttpClient.Builder().build()

    /** A one-shot HTTP responder; every request is counted so "did it fetch again?" is testable. */
    private fun responder(
        status: Int = 200,
        body: ByteArray = ByteArray(0),
        declaredLength: Int? = null,
        chunked: Boolean = false,
        delayMs: Long = 0,
        counter: AtomicInteger = AtomicInteger(),
    ): Pair<LoopbackHttpServer, AtomicInteger> {
        val server = LoopbackHttpServer { socket: Socket ->
            counter.incrementAndGet()
            if (delayMs > 0) {
                // A response that has not started yet is the reliable way to be
                // mid-download when a test cancels.
                Thread.sleep(delayMs)
            }
            val out = socket.getOutputStream()
            val length = declaredLength ?: body.size
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(" X\r\n")
                append("Content-Type: application/octet-stream\r\n")
                if (chunked) {
                    append("Transfer-Encoding: chunked\r\n")
                } else {
                    append("Content-Length: ").append(length).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.US_ASCII))
            if (chunked) {
                var offset = 0
                while (offset < body.size) {
                    val size = minOf(8 * 1024, body.size - offset)
                    out.write(Integer.toHexString(size).toByteArray(Charsets.US_ASCII))
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.write(body, offset, size)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    offset += size
                }
                out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            } else if (status == 200) {
                out.write(body, 0, if (declaredLength == null) body.size else minOf(length, body.size))
            } else {
                out.write(body)
            }
            out.flush()
        }
        return server to counter
    }

    private fun releaseJson(tag: String, assetUrl: String, size: Long, digest: String?): String {
        val digestField = if (digest == null) "" else """"digest":"$digest","""
        return """
            {"tag_name":"$tag","name":"LastWave $tag","body":"notes","html_url":"https://github.com/x/y/releases/tag/$tag",
             "published_at":"2026-11-01T10:00:00Z","prerelease":false,"draft":false,
             "assets":[{"name":"LastWave-Silent-Programmer-$tag.apk","size":$size,$digestField
                        "browser_download_url":"$assetUrl"}]}
        """.trimIndent()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun payloadBytes(): ByteArray {
        val random = java.util.Random(7)
        return ByteArray(1024 * 1024).also { random.nextBytes(it) }
    }

    private fun newFile(name: String): File {
        tempDir.mkdirs()
        return File(tempDir, name)
    }

    private fun assertSameBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals("byte count", expected.size, actual.size)
        assertEquals("contents", sha256Hex(expected), sha256Hex(actual))
    }

    // ------------------------------------------------------------------ lookup

    @Test
    fun fetchLatest_readsARealPayloadFromTheWire() {
        val bytes = payloadBytes()
        // The asset URL has to point at the server holding the file, and that URL only
        // exists once it is listening — so the JSON is assembled per test.
        val files = responder(body = bytes).first
        val json = releaseJson("v5.9.0", files.url + "/app.apk", bytes.size.toLong(), "sha256:" + sha256Hex(bytes))
        val (api, apiHits) = responder(body = json.toByteArray(Charsets.UTF_8))
        try {
            val check = runBlocking { GitHubUpdateClient(rewritingClient(api.url)).fetchLatest("5.8.0") }
            assertTrue("expected Available, got $check", check is UpdateCheck.Available)
            check as UpdateCheck.Available
            val release = check.release
            assertEquals("v5.9.0", release.tag)
            assertEquals("LastWave-Silent-Programmer-v5.9.0.apk", release.asset?.name)
            assertEquals(bytes.size.toLong(), release.asset?.size)
            assertEquals(sha256Hex(bytes), release.asset?.sha256Hex)
            assertEquals(files.url + "/app.apk", release.asset?.downloadUrl)
            // The raw payload travels with the verdict: it is what gets cached and
            // re-parsed on the next cold start.
            assertTrue(check.raw.contains("v5.9.0"))
            assertEquals(1, apiHits.get())

            // And the URL it advertised is the URL that downloads the same bytes.
            val downloaded = runBlocking {
                GitHubUpdateClient(plainClient()).download(release.asset!!, newFile("fetched.apk")) { _, _ -> }
            }
            downloaded as DownloadResult.Complete
            assertSameBytes(bytes, downloaded.file.readBytes())
            downloaded.file.delete()
        } finally {
            api.close()
            files.close()
        }
    }

    @Test
    fun fetchLatest_mapsEveryFailureToItsOwnState() {
        val cases = listOf(
            // A fork whose repository is private or absent gets exactly this, and it
            // must never be rendered as "you are up to date".
            404 to UpdateCheck.RepositoryNotFound,
            403 to UpdateCheck.RateLimited,
            429 to UpdateCheck.RateLimited,
            500 to UpdateCheck.Malformed,
        )
        cases.forEach { (status, expected) ->
            val (server, _) = responder(status = status, body = """{"message":"nope"}""".toByteArray())
            try {
                val check = runBlocking { GitHubUpdateClient(rewritingClient(server.url)).fetchLatest("5.8.0") }
                assertEquals("HTTP $status", expected, check)
            } finally {
                server.close()
            }
        }
    }

    @Test
    fun fetchLatest_saysUpToDateWhenTheReleaseIsNotNewerOrHasNoApk() {
        val older = responder(body = releaseJson("v5.8.0", "http://127.0.0.1:1/a.apk", 10L, null).toByteArray())
        try {
            val check = runBlocking { GitHubUpdateClient(rewritingClient(older.first.url)).fetchLatest("5.8.0") }
            assertTrue("expected UpToDate, got $check", check is UpdateCheck.UpToDate)
        } finally {
            older.first.close()
        }

        // A release whose only asset is a source archive cannot be installed, so it
        // must not raise an offer with nothing behind it.
        val noApk = responder(
            body = """{"tag_name":"v9.9.9","assets":[{"name":"source.zip","size":10,"browser_download_url":"http://127.0.0.1:1/s.zip"}]}"""
                .toByteArray(),
        )
        try {
            val check = runBlocking { GitHubUpdateClient(rewritingClient(noApk.first.url)).fetchLatest("5.8.0") }
            assertTrue("expected UpToDate for a release with no APK, got $check", check is UpdateCheck.UpToDate)
        } finally {
            noApk.first.close()
        }
    }

    @Test
    fun fetchLatest_reportsGarbageAsGarbage() {
        val (server, _) = responder(body = """<html>Sign in to GitHub</html>""".toByteArray())
        try {
            val check = runBlocking { GitHubUpdateClient(rewritingClient(server.url)).fetchLatest("5.8.0") }
            assertEquals(UpdateCheck.Malformed, check)
        } finally {
            server.close()
        }
    }

    @Test
    fun fetchLatest_reportsOfflineWhenTheNetworkRefuses() {
        val (server, _) = responder(body = ByteArray(0))
        val url = server.url
        server.close()
        // Nothing is listening any more: a connection refused, which is what a
        // device on airplane mode produces, minus the radio.
        val client = GitHubUpdateClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    throw java.io.IOException("Failed to connect to " + chain.request().url.host)
                }
                .build(),
        )
        try {
            val check = runBlocking { client.fetchLatest("5.8.0") }
            assertEquals(UpdateCheck.Offline, check)
        } finally {
            assertTrue(url.isNotBlank())
        }
    }

    // ---------------------------------------------------------------- download

    @Test
    fun download_writesTheFileHashesItAndLeavesNoPartial() {
        val bytes = payloadBytes()
        val asset = ReleaseAsset(
            name = "LastWave-Silent-Programmer-v5.9.0.apk",
            downloadUrl = "http://127.0.0.1:1/a",
            size = bytes.size.toLong(),
            digest = "sha256:" + sha256Hex(bytes),
        )
        val (server, hits) = responder(body = bytes)
        val target = newFile(asset.name)
        val progress = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        try {
            val result = runBlocking {
                GitHubUpdateClient(plainClient())
                    .download(asset.copy(downloadUrl = server.url + "/app.apk"), target) { done, total ->
                        progress += done to total
                    }
            }
            assertTrue("expected Complete, got $result", result is DownloadResult.Complete)
            result as DownloadResult.Complete
            assertEquals(bytes.size.toLong(), result.bytes)
            assertEquals(bytes.size.toLong(), result.declaredBytes)
            assertEquals("the hash must be of the bytes that landed, not the ones promised", sha256Hex(bytes), result.sha256)
            assertSameBytes(bytes, result.file.readBytes())
            // Renamed into place, so a half-written file can never look finished.
            assertFalse(File(target.absolutePath + ".part").exists())
            assertEquals(1, hits.get())
            val done = progress.map { it.first }
            assertEquals("progress must never go backwards", done, done.sorted())
            assertEquals("the last progress step is the whole file", bytes.size.toLong(), done.last())
            assertTrue(progress.all { it.second == bytes.size.toLong() })
        } finally {
            server.close()
            target.delete()
        }
    }

    @Test
    fun download_countsUpAgainstTheDeclaredSizeWhenTheHostOmitsIt() {
        val bytes = payloadBytes()
        val asset = ReleaseAsset(name = "LastWave-5.9.0.apk", downloadUrl = "http://127.0.0.1:1/a", size = bytes.size.toLong())
        val (server, _) = responder(body = bytes, chunked = true)
        val target = newFile(asset.name)
        val progress = Collections.synchronizedList(mutableListOf<Long>())
        try {
            val result = runBlocking {
                GitHubUpdateClient(plainClient())
                    .download(asset.copy(downloadUrl = server.url + "/a"), target) { done, _ -> progress += done }
            }
            result as DownloadResult.Complete
            assertEquals(bytes.size.toLong(), result.bytes)
            // With no `Content-Length`, the release's own `size` is what the bar
            // divides by; a zero total would render as full or infinite.
            assertEquals(bytes.size.toLong(), progress.last())
            assertSameBytes(bytes, target.readBytes())
        } finally {
            server.close()
            target.delete()
        }
    }

    @Test
    fun download_refusesABodyShorterThanTheDeclaredLength() {
        val bytes = payloadBytes()
        val asset = ReleaseAsset(name = "LastWave-5.9.0.apk", downloadUrl = "http://127.0.0.1:1/a", size = bytes.size.toLong())
        val (server, _) = responder(body = bytes, declaredLength = bytes.size * 4)
        val target = newFile("truncated.apk")
        try {
            val result = runBlocking {
                GitHubUpdateClient(plainClient())
                    .download(asset.copy(downloadUrl = server.url + "/a"), target) { _, _ -> }
            }
            // Truncation never reaches the caller as a "Complete" it has to notice.
            assertTrue("expected Failed, got $result", result is DownloadResult.Failed)
            assertFalse("a truncated download must not sit where a finished one belongs", target.exists())
            assertFalse(File(target.absolutePath + ".part").exists())
        } finally {
            server.close()
        }
    }

    @Test
    fun download_reportsAHostErrorInsteadOfCreatingAFile() {
        val asset = ReleaseAsset(name = "LastWave-5.9.0.apk", downloadUrl = "http://127.0.0.1:1/a", size = 10L)
        val (server, _) = responder(status = 404, body = "gone".toByteArray())
        val target = newFile("missing.apk")
        try {
            val result = runBlocking {
                GitHubUpdateClient(plainClient())
                    .download(asset.copy(downloadUrl = server.url + "/a"), target) { _, _ -> }
            }
            assertTrue("expected Failed, got $result", result is DownloadResult.Failed)
            assertTrue((result as DownloadResult.Failed).message.contains("404"))
            assertFalse(target.exists())
        } finally {
            server.close()
        }
    }

    @Test
    fun download_leavesNothingBehindWhenCancelled() {
        val bytes = payloadBytes()
        val asset = ReleaseAsset(name = "LastWave-5.9.0.apk", downloadUrl = "http://127.0.0.1:1/a", size = bytes.size.toLong())
        val (server, _) = responder(body = bytes, delayMs = 1_200)
        val target = newFile("cancelled.apk")
        val started = CompletableDeferred<Unit>()
        val outcome = AtomicReference<DownloadResult?>()
        var thrown: Throwable? = null
        try {
            runBlocking {
                val client = GitHubUpdateClient(plainClient())
                val job: Job = launch(Dispatchers.IO) {
                    started.complete(Unit)
                    outcome.set(client.download(asset.copy(downloadUrl = server.url + "/a"), target) { _, _ -> })
                }
                started.await()
                delay(300)
                try {
                    job.cancelAndJoin()
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    thrown = cancellation
                }
            }
            // Whether the call reports `Cancelled` or lets the cancellation
            // propagate is an implementation detail of `withContext`. What it owes the
            // device is nothing on disk: a leftover file is how a updater later offers
            // a half-APK to the installer.
            assertTrue(
                "expected a cancellation, got outcome=${outcome.get()} thrown=$thrown",
                outcome.get() == null || outcome.get() == DownloadResult.Cancelled || thrown != null,
            )
            assertFalse(target.exists())
            assertFalse(File(target.absolutePath + ".part").exists())
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------------ extras

    @Test
    fun fetchText_refusesAPlainHttpUrl() {
        // The sidecar hash file is only read over TLS. Accepting http would hand the
        // verification decision to whoever owns the network on the way.
        val text = runBlocking { GitHubUpdateClient(plainClient()).fetchText("http://127.0.0.1:1/CHECKSUMS.txt") }
        assertNull(text)
    }

    @Test
    fun fetchText_readsAHashesFileFromAnHttpsUrl() {
        val body = "5f8bce95b234d1385d31f74811525ad4ee66ec2ad4203151f54ff317eb59c0dc  LastWave-Silent-Programmer-v5.9.0.apk\n".toByteArray()
        val (server, _) = responder(body = body)
        try {
            val client = GitHubUpdateClient(rewritingClient(server.url))
            val text = runBlocking { client.fetchText("https://raw.githubusercontent.com/x/y/main/CHECKSUMS.txt") }
            assertEquals(String(body), text)
            val map = UpdateRelease.parseChecksums(text!!)
            assertEquals(
                "5f8bce95b234d1385d31f74811525ad4ee66ec2ad4203151f54ff317eb59c0dc",
                map["LastWave-Silent-Programmer-v5.9.0.apk"],
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun cleanUpStale_keepsOnlyTheFileStillInUse() {
        val dir = File(tempDir, "updates").apply { mkdirs() }
        val keep = File(dir, "LastWave-keep.apk").apply { writeText("x") }
        val older = File(dir, "LastWave-5.7.0.apk").apply { writeText("x") }
        val partial = File(dir, "LastWave-5.8.0.apk.part").apply { writeText("x") }
        val unrelated = File(dir, "notes.txt").apply { writeText("x") }
        try {
            GitHubUpdateClient(plainClient()).cleanUpStale(keep = keep, directory = dir)
            assertTrue(keep.exists())
            assertFalse("an APK for an older release is 37 MB of nothing", older.exists())
            assertFalse("a half-finished download has no owner after a restart", partial.exists())
            assertTrue("only APKs and partials are ever deleted", unrelated.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
