package com.lastwave.app.data.update

import com.lastwave.app.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * The two HTTP operations an updater needs: ask GitHub what the latest release
 * is, and copy the APK down.
 *
 * Everything that could go wrong is named, because every one of these states
 * needs different words in the UI: 404 means "there is no repository there yet,
 * or it is private" (a *very* likely state for a fresh fork, and the reason the
 * row must not read like a crash), 403 means the anonymous quota is spent, a
 * redirect to a non-HTTPS host means the download is refused outright.
 *
 * The client is derived from the app's shared one with the disk cache removed:
 * GitHub serves release assets with cacheable headers, and letting OkHttp buffer
 * a 37 MB APK into an 8 MB metadata cache would evict every artwork entry to
 * hold a file that is already written to disk.
 */
@Singleton
class GitHubUpdateClient @Inject constructor(
    private val shared: OkHttpClient,
) {
    private val client: OkHttpClient by lazy {
        shared.newBuilder()
            .cache(null)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /**
     * `GET /releases/latest`, then a decision the UI can speak about.
     *
     * [wantsUncompressed] is passed down to the asset selection, not applied here,
     * so the one place that decides "which file is ours" stays
     * [UpdateRelease.selectAsset].
     */
    suspend fun fetchLatest(installedVersion: String, wantsUncompressed: Boolean = false): UpdateCheck =
        withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(UpdateConfig.LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent())
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> return@withContext UpdateCheck.RepositoryNotFound
                    response.code == 403 || response.code == 429 -> return@withContext UpdateCheck.RateLimited
                    !response.isSuccessful -> return@withContext UpdateCheck.Malformed
                    else -> response.body?.string()
                }
            }
        } catch (_: IOException) {
            return@withContext UpdateCheck.Offline
        } catch (_: IllegalStateException) {
            return@withContext UpdateCheck.Offline
        }
        val release = body?.let { UpdateRelease.parse(it, wantsUncompressed) }
            ?: return@withContext UpdateCheck.Malformed
        // A release with no APK on it is not "available": the row would promise a
        // download and then have nothing to download.
        val usable = release.asset != null
        if (UpdateRelease.isNewer(release.tag, installedVersion) && usable) {
            UpdateCheck.Available(release, body)
        } else {
            UpdateCheck.UpToDate(release, body)
        }
    }

    /**
     * Copy [asset] into [into], hashing as it goes.
     *
     * The digest is computed on the way to disk rather than in a second pass:
     * over a 37 MB file on a phone's slow internal storage, a second read is a
     * noticeable fraction of the whole download.
     */
    suspend fun download(asset: ReleaseAsset, into: File, onProgress: (Long, Long) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            val partial = File(into.parentFile, "${into.name}.part")
            val request = Request.Builder()
                .url(asset.downloadUrl)
                .header("User-Agent", userAgent())
                .get()
                .build()
            val call = client.newCall(request)
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext DownloadResult.Failed("The download host answered ${response.code}.")
                    }
                    val body: ResponseBody = response.body
                        ?: return@withContext DownloadResult.Failed("The download came back empty.")
                    val declared = response.headers["Content-Length"]?.toLongOrNull() ?: asset.size
                    partial.parentFile?.mkdirs()
                    body.byteStream().use { source ->
                        partial.outputStream().use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                sink.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                written += read
                                if (written % (1024 * 1024) < buffer.size) {
                                    onProgress(written, if (declared > 0) declared else asset.size)
                                }
                                if (!currentCoroutineContext().isActive) {
                                    throw IOException(CANCELLED_MARKER)
                                }
                            }
                        }
                    }
                    if (!partial.renameTo(into)) {
                        return@withContext DownloadResult.Failed("Could not move the download into place.")
                    }
                    onProgress(written, if (declared > 0) declared else written)
                    DownloadResult.Complete(
                        file = into,
                        bytes = written,
                        sha256 = digest.
                            digest().joinToString("") { "%02x".format(it) },
                        declaredBytes = if (declared > 0) declared else written,
                    )
                }
            } catch (error: IOException) {
                partial.delete()
                // Whichever came first — our own check between reads, or OkHttp
                // reacting to `call.cancel()` with an IOException of its own — the
                // user stopped this on purpose, and "cancelled" is the only honest
                // answer. Reporting a deliberate stop as a network failure invites a
                // retry of something that was just deliberately abandoned.
                if (call.isCanceled() || error.message == CANCELLED_MARKER) {
                    DownloadResult.Cancelled
                } else {
                    DownloadResult.Failed("The download stopped: ${error.message ?: "network error"}.")
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                partial.delete()
                into.delete()
                throw cancellation
            } finally {
                cancellation?.dispose()
            }
        }

    /** A tiny sidecar file, for an optional `CHECKSUMS.txt` attached to the release. */
    suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        val request = Request.Builder().url(url).header("User-Agent", userAgent()).get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.take(MAX_SIDECAR_CHARS) else null
            }
        }.getOrNull()
    }

    /** Anything left over from earlier attempts: 37 MB each, and nobody asks for them. */
    fun cleanUpStale(keep: File?, directory: File) {
        directory.listFiles()?.forEach { file ->
            if (file == keep) return@forEach
            if (file.name.endsWith(".apk") || file.name.endsWith(".part")) runCatching { file.delete() }
        }
    }

    private fun userAgent(): String =
        "LastWave/${BuildConfig.VERSION_NAME} (Android update check; ${UpdateConfig.OWNER}/${UpdateConfig.REPO})"
}

sealed interface DownloadResult {
    data class Complete(
        val file: File,
        val bytes: Long,
        val sha256: String?,
        val declaredBytes: Long,
    ) : DownloadResult

    data class Failed(val message: String) : DownloadResult
    data object Cancelled : DownloadResult
}


/** A hashes file is a few hundred bytes; never let a wrong URL stream a diskful. */
private const val MAX_SIDECAR_CHARS = 64 * 1024

/** Sentinel identifying *our* cancellation inside an [IOException] the caller cannot tell apart. */
private const val CANCELLED_MARKER = "cancelled"
