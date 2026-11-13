package com.lastwave.app.data.update

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The decisions an updater makes about a stranger's file, without a device: which
 * asset is ours, whether it is newer, whether what arrived is what was published.
 *
 * The payloads below are the real shape of `GET /repos/{owner}/{repo}/releases/latest`
 * (asset `digest`, `browser_download_url`, `size`, top-level `prerelease`/`draft`),
 * because the interesting failures are all about fields a hand-simplified test
 * would drop: a missing `digest`, an extra `.sha256` sidecar, a source archive that
 * ends in something other than `.apk`.
 */
class UpdateReleaseTest {

    private fun asset(
        name: String,
        size: Long = 1_000L,
        digest: String? = null,
        url: String = "https://objects.example.com/$name",
    ) = ReleaseAsset(name = name, downloadUrl = url, size = size, digest = digest)

    private fun payload(
        tag: String = "v5.9.0",
        body: String = "",
        assets: String,
    ) = """
        {
          "tag_name": "$tag",
          "name": "LastWave $tag",
          "body": "$body",
          "html_url": "https://github.com/Tanmay-Tiwaricyber/LastWave/releases/tag/$tag",
          "published_at": "2026-11-01T10:00:00Z",
          "prerelease": false,
          "draft": false,
          "assets": [$assets]
        }
    """.trimIndent()

    // ------------------------------------------------------------------ parse

    @Test
    fun parse_readsARealReleasePayload() {
        val release = UpdateRelease.parse(
            payload(
                tag = "v5.9.0",
                body = "Hearing profile EQ.",
                assets = """
                    {"name":"LastWave-Silent-Programmer-v5.9.0.apk","size":37863756,
                     "digest":"sha256:5f8bce95b234d1385d31f74811525ad4ee66ec2ad4203151f54ff317eb59c0dc",
                     "browser_download_url":"https://github.com/x/y/releases/download/v5.9.0/a.apk"},
                    {"name":"Source code (zip)","size":9999,
                     "browser_download_url":"https://github.com/x/y/archive/refs/heads/main.zip"}
                """.trimIndent(),
            ),
        )
        assertThat(release).isNotNull()
        assertThat(release!!.tag).isEqualTo("v5.9.0")
        assertThat(release.displayVersion).isEqualTo("5.9.0")
        assertThat(release.notes).isEqualTo("Hearing profile EQ.")
        assertThat(release.prerelease).isFalse()
        assertThat(release.asset?.name).isEqualTo("LastWave-Silent-Programmer-v5.9.0.apk")
        assertThat(release.asset?.size).isEqualTo(37_863_756L)
        assertThat(release.asset?.sha256Hex)
            .isEqualTo("5f8bce95b234d1385d31f74811525ad4ee66ec2ad4203151f54ff317eb59c0dc")
        // Both names are visible to the dialog, which is how a user notices a wrong upload.
        assertThat(release.assetNames).containsExactly(
            "LastWave-Silent-Programmer-v5.9.0.apk",
            "Source code (zip)",
        )
    }

    @Test
    fun parse_findsAnOptionalChecksumsSidecar() {
        val release = UpdateRelease.parse(
            payload(
                assets = """
                    {"name":"LastWave-Silent-Programmer-v5.9.0.apk","size":10,"browser_download_url":"https://a/apk"},
                    {"name":"CHECKSUMS.txt","size":200,"browser_download_url":"https://a/CHECKSUMS.txt"}
                """.trimIndent(),
            ),
        )
        assertThat(release?.checksumsUrl).isEqualTo("https://a/CHECKSUMS.txt")
    }

    @Test
    fun parse_withoutSidecars_hasNoChecksumsUrl() {
        val release = UpdateRelease.parse(
            payload(assets = """{"name":"LastWave-v5.9.0.apk","size":10,"browser_download_url":"https://a/apk"}"""),
        )
        assertThat(release?.checksumsUrl).isNull()
    }

    @Test
    fun parse_rejectsGarbage() {
        assertThat(UpdateRelease.parse("not json at all")).isNull()
        assertThat(UpdateRelease.parse("""{"message":"Not Found"}""")).isNull()
        assertThat(UpdateRelease.parse("""{"tag_name":"","assets":[]}""")).isNull()
        // Unknown fields are GitHub adding features, not a broken release.
        assertThat(
            UpdateRelease.parse("""{"tag_name":"v5.9.0","assets":[],"reactions":{"total_count":3}}"""),
        ).isNotNull()
    }

    // -------------------------------------------------------------- selection

    @Test
    fun selectAsset_prefersTheUniversalBuild() {
        val chosen = UpdateRelease.selectAsset(
            listOf(
                asset("LastWave-v5.9.0-arm64-v8a.apk", size = 20_000L),
                asset("LastWave-v5.9.0-universal.apk", size = 37_000L),
            ),
        )
        assertThat(chosen?.name).isEqualTo("LastWave-v5.9.0-universal.apk")
    }

    @Test
    fun selectAsset_ranksPackagingAboveUniversality() {
        // The trade this makes: `universal-uncompressed` is bigger and covers more
        // ABIs, but installing an uncompressed APK over a compressed install needs a
        // full uninstall, which would take the user's database and downloads with
        // it. A per-ABI slice of the matching packaging keeps the update in place.
        val assets = listOf(
            asset("LastWave-v5.9.0-arm64-v8a.apk", size = 20_000L),
            asset("LastWave-v5.9.0-universal-uncompressed.apk", size = 41_000L),
        )
        assertThat(UpdateRelease.selectAsset(assets, wantsUncompressed = false)?.name)
            .isEqualTo("LastWave-v5.9.0-arm64-v8a.apk")
        assertThat(UpdateRelease.selectAsset(assets, wantsUncompressed = true)?.name)
            .isEqualTo("LastWave-v5.9.0-universal-uncompressed.apk")
    }

    @Test
    fun selectAsset_amongEquivalentSlices_takesTheLargest() {
        assertThat(
            UpdateRelease.selectAsset(
                listOf(
                    asset("LastWave-v5.9.0-armeabi-v7a.apk", size = 18_000L),
                    asset("LastWave-v5.9.0-arm64-v8a.apk", size = 24_000L),
                ),
            )?.name,
        ).isEqualTo("LastWave-v5.9.0-arm64-v8a.apk")
    }

    @Test
    fun selectAsset_matchesPackagingToWhatIsInstalled() {
        val assets = listOf(
            asset("LastWave-v5.9.0-universal.apk", size = 37_000L),
            asset("LastWave-v5.9.0-universal-raw.apk", size = 41_000L),
        )
        assertThat(UpdateRelease.selectAsset(assets, wantsUncompressed = false)?.name)
            .isEqualTo("LastWave-v5.9.0-universal.apk")
        assertThat(UpdateRelease.selectAsset(assets, wantsUncompressed = true)?.name)
            .isEqualTo("LastWave-v5.9.0-universal-raw.apk")
    }

    @Test
    fun selectAsset_neverOffersSomeoneElsesApk() {
        // The trap this guards: a release where the APK upload failed and a
        // third-party build, or an unrelated tool, is the only `.apk` attached.
        assertThat(
            UpdateRelease.selectAsset(
                listOf(
                    asset("RandomTool.apk", size = 900L),
                    asset("CHECKSUMS.txt"),
                ),
            ),
        ).isNull()
    }

    @Test
    fun selectAsset_ignoresArchivesAndSidecars() {
        assertThat(
            UpdateRelease.selectAsset(
                listOf(
                    asset("LastWave-source.zip"),
                    asset("LastWave-v5.9.0.apk.sha256"),
                    asset("LastWave-v5.9.0.apk.asc"),
                ),
            ),
        ).isNull()
    }

    @Test
    fun selectAsset_skipsVariantsThisBuildCannotUse() {
        assertThat(
            UpdateRelease.selectAsset(
                listOf(asset("LastWave-v5.9.0-android7.apk", size = 50_000L), asset("LastWave-v5.9.0-universal.apk", size = 40_000L)),
            )?.name,
        ).isEqualTo("LastWave-v5.9.0-universal.apk")
        assertThat(
            UpdateRelease.selectAsset(listOf(asset("LastWave-v5.9.0-vivo-origin.apk"))),
        ).isNull()
    }

    // ------------------------------------------------------------- versioning

    @Test
    fun isNewer_comparesNumbersNotStrings() {
        assertThat(UpdateRelease.isNewer("v5.10.0", "5.9.0")).isTrue()
        assertThat(UpdateRelease.isNewer("5.9.0", "5.10.0")).isFalse()
        assertThat(UpdateRelease.isNewer("v5.9.0", "5.9.0")).isFalse()
        assertThat(UpdateRelease.isNewer("6.0.0", "5.99.99")).isTrue()
    }

    @Test
    fun isNewer_doesNotOfferAPreReleaseOverTheStableThatReplacedIt() {
        assertThat(UpdateRelease.isNewer("v5.9.0-rc1", "5.9.0")).isFalse()
        assertThat(UpdateRelease.isNewer("v5.9.0+build7", "5.9.0")).isFalse()
        assertThat(UpdateRelease.isNewer("v5.9.0-rc1", "5.8.0")).isTrue()
    }

    @Test
    fun isNewer_refusesToGuessAboutUnparseableTags() {
        // "latest" is what a redirect page can look like; treating it as a version
        // would offer an update forever.
        assertThat(UpdateRelease.isNewer("latest", "5.8.0")).isFalse()
        assertThat(UpdateRelease.isNewer("", "5.8.0")).isFalse()
        assertThat(UpdateRelease.isNewer("v5.9.0", "")).isFalse()
    }

    @Test
    fun sameVersion_reunitesTheGitTagFormWithTheVersionNameForm() {
        // `git tag` writes `v5.9.0`, `versionName` is `5.9.0`, and the UI shows the
        // second while dismissal is remembered against the first.
        assertThat(UpdateRelease.sameVersion("v5.9.0", "5.9.0")).isTrue()
        assertThat(UpdateRelease.sameVersion("V5.9.0", " 5.9.0 ")).isTrue()
        assertThat(UpdateRelease.sameVersion("5.9.0", "5.9.1")).isFalse()
        // "latest" is a redirect alias, never a version: matching it against
        // anything would suppress the wrong release.
        assertThat(UpdateRelease.sameVersion("latest", "5.9.0")).isFalse()
        assertThat(UpdateRelease.sameVersion("", "5.9.0")).isFalse()
        assertThat(UpdateRelease.sameVersion("5.9.0", "")).isFalse()
    }

    @Test
    fun segments_stripsThePartsThatAreNotVersionNumbers() {
        assertThat(UpdateRelease.segments("v5.9.0-rc1")).containsExactly(5, 9, 0)
        assertThat(UpdateRelease.segments("5.8.0-beta2")).containsExactly(5, 8, 0)
        assertThat(UpdateRelease.segments("nope")).isEmpty()
    }

    // --------------------------------------------------------------- digests

    @Test
    fun parseDigest_normalisesTheRecordedGitHubHash() {
        val hex = "5f8bce95b234d1385d31f74811525ad4ee66ec2ad4203151f54ff317eb59c0dc"
        assertThat(UpdateRelease.parseDigest("sha256:$hex")).isEqualTo(hex)
        assertThat(UpdateRelease.parseDigest("  SHA256:$hex  ")).isEqualTo(hex)
        assertThat(UpdateRelease.parseDigest(hex)).isEqualTo(hex)
        // A sha512 is not a sha256: comparing them would be a guaranteed mismatch,
        // and reporting that as tampering would be a lie.
        assertThat(UpdateRelease.parseDigest("sha512:$hex$hex")).isNull()
        assertThat(UpdateRelease.parseDigest("sha256:deadbeef")).isNull()
        assertThat(UpdateRelease.parseDigest(null)).isNull()
    }

    @Test
    fun parseChecksums_readsBothDirectionsAndBothMarkers() {
        val hex = "5f8bce95b234d1385d31f74811525ad4ee66ec2ad4203151f54ff317eb59c0dc"
        val other = "cee8f53a101c0ba50a204fd39681b627ab062ca4f6f9cc85033344d9f02dcca8"
        val parsed = UpdateRelease.parseChecksums(
            """
            $hex  LastWave-Silent-Programmer-v5.8.0.apk
            $hex  *Binary.apk
            "LastWave Silent Programmer v5.7.0.apk" $other
            ./dist/Third.apk $hex
            a line with no hash at all
            """.trimIndent(),
        )
        assertThat(parsed["LastWave-Silent-Programmer-v5.8.0.apk"]).isEqualTo(hex)
        assertThat(parsed["Binary.apk"]).isEqualTo(hex)
        assertThat(parsed["LastWave Silent Programmer v5.7.0.apk"]).isEqualTo(other)
        assertThat(parsed["Third.apk"]).isEqualTo(hex)
        assertThat(parsed).doesNotContainKey("a")
    }

    @Test
    fun sha256Of_hashesAFileAndSaysNullWhenItCannot() {
        val file = File.createTempFile("lastwave-update", ".bin")
        file.writeText("abc")
        // SHA-256("abc"), the standard test vector.
        assertThat(UpdateRelease.sha256Of(file))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(UpdateRelease.sha256Of(File(file.absolutePath + "-missing"))).isNull()
        file.delete()
    }

    // ------------------------------------------------------------- filenames

    @Test
    fun safeFileName_refusesToBeGivenAPath() {
        val hostile = UpdateRelease.safeFileName("../../sdcard/Android/x.apk", "v5.9.0")
        assertThat(hostile).doesNotContain("/")
        assertThat(File("/data/user/0/com.lastwave.app/files/updates", hostile).parent)
            .isEqualTo("/data/user/0/com.lastwave.app/files/updates")
        assertThat(UpdateRelease.safeFileName("LastWave v5.9.0 (1).apk", "v5.9.0"))
            .isEqualTo("LastWavev5.9.01.apk")
        // A name with no .apk on it is replaced by the release-tag fallback rather
        // than downloaded to a file the installer could not open.
        assertThat(UpdateRelease.safeFileName("release-download", "v5.9.0"))
            .isEqualTo("LastWave-5.9.0.apk")
        assertThat(UpdateRelease.safeFileName("weird", "")).isEqualTo("LastWave-update.apk")
    }

    // ------------------------------------------------------------- verifying

    private val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun verify(
        bytes: Long = 100L,
        expectedBytes: Long = 100L,
        actual: String? = hex,
        expected: String? = hex,
        readable: Boolean = true,
        code: Long = 39L,
        installedCode: Long = 38L,
        sameSigner: Boolean = true,
    ) = UpdateRelease.verify(
        downloadedBytes = bytes,
        expectedBytes = expectedBytes,
        actualSha256 = actual,
        expectedSha256 = expected,
        archiveReadable = readable,
        archiveVersionCode = code,
        installedVersionCode = installedCode,
        sameSigner = sameSigner,
    )

    @Test
    fun verify_passesWhenEverythingAgrees() {
        assertThat(verify()).isEqualTo(Verification.Passed)
    }

    @Test
    fun verify_anApkOnlyReleaseStillPassesOnSizeAndSigner() {
        // The state this fork's releases are in: no CHECKSUMS.txt, and if GitHub
        // recorded no digest either, the size, the archive and the key are all
        // there is. Refusing to install at all would be worse than saying so.
        assertThat(verify(expected = null)).isEqualTo(Verification.Passed)
    }

    @Test
    fun verify_refusesADifferentHash() {
        assertThat(verify(actual = "0".repeat(64))).isEqualTo(Verification.DigestMismatch)
        // A truncated download is caught before the hash is even compared: it
        // cannot match, and the useful sentence is "short", not "tampered with".
        assertThat(verify(bytes = 99L)).isEqualTo(Verification.SizeMismatch)
        assertThat(verify(bytes = 0L)).isEqualTo(Verification.Empty)
    }

    @Test
    fun verify_refusesAFileThatIsNotOneOfOurs() {
        assertThat(verify(readable = false)).isEqualTo(Verification.Unreadable)
        assertThat(verify(sameSigner = false)).isEqualTo(Verification.NotThisApp)
        // A release tagged newer whose archive carries this build's versionCode is
        // somebody re-uploading the old file: installing it would change nothing.
        assertThat(verify(code = 38L)).isEqualTo(Verification.NotNewer)
    }

    @Test
    fun verify_anUnrecordedSizeCannotProduceAMismatch() {
        // GitHub reports `size` for every asset, but a hand-made payload or a proxy
        // can lose it; with nothing to compare, the other checks still decide.
        assertThat(verify(bytes = 123L, expectedBytes = 0L)).isEqualTo(Verification.Passed)
    }

    @Test
    fun everyRefusalSaysWhatFailed() {
        // The dialog shows these sentences; a refusal with an empty reason is a
        // download that vanished for no stated reason.
        Verification.values().forEach { verdict ->
            if (verdict != Verification.Passed) {
                assertThat(verdict.reason).isNotEmpty()
            }
        }
    }

    // ------------------------------------------------------------- ui state

    @Test
    fun updateInfo_reportsProgressOnlyWhileDownloading() {
        val downloading = UpdateInfo(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 25L, totalBytes = 100L)
        assertThat(downloading.progress).isWithin(0.001f).of(0.25f)
        assertThat(downloading.copy(phase = UpdatePhase.READY).progress).isNull()
        // A host that answers without Content-Length must not produce a division by
        // zero or a bar stuck at 100%.
        assertThat(downloading.copy(totalBytes = 0L).progress).isNull()
    }

    @Test
    fun actionLabel_tellsTheUserWhatTheNextTapDoes() {
        assertThat(UpdateInfo().actionLabel()).isEqualTo("Check for update")
        assertThat(UpdateInfo(phase = UpdatePhase.CHECKING).actionLabel()).isEqualTo("Checking\u2026")
        assertThat(UpdateInfo(phase = UpdatePhase.AVAILABLE).actionLabel()).isEqualTo("Download")
        assertThat(UpdateInfo(phase = UpdatePhase.DOWNLOADING).actionLabel()).isEqualTo("Downloading\u2026")
        assertThat(UpdateInfo(phase = UpdatePhase.READY).actionLabel()).isEqualTo("Install")
        assertThat(UpdateInfo(phase = UpdatePhase.UP_TO_DATE).actionLabel()).isEqualTo("Check again")
    }

    @Test
    fun statusLine_saysWhatIsTrueForEachPhase() {
        assertThat(UpdateInfo(phase = UpdatePhase.CHECKING).statusLine())
            .isEqualTo("Checking GitHub…")
        assertThat(UpdateInfo(phase = UpdatePhase.AVAILABLE, latestVersion = "5.9.0").statusLine())
            .isEqualTo("v5.9.0 is available")
        // A release with nothing installable attached must not read like a normal
        // offer: the manager sets this message and the row has to show it.
        assertThat(
            UpdateInfo(
                phase = UpdatePhase.AVAILABLE,
                latestVersion = "5.9.0",
                message = "Release 5.9.0 has no installable APK attached to it.",
            ).statusLine(),
        ).isEqualTo("Release 5.9.0 has no installable APK attached to it.")
        assertThat(UpdateInfo(phase = UpdatePhase.READY).statusLine())
            .isEqualTo("Verified and ready to install")
        assertThat(UpdateInfo(phase = UpdatePhase.UP_TO_DATE, currentVersion = "5.8.0").statusLine())
            .isEqualTo("v5.8.0 is the newest release")
        assertThat(UpdateInfo(phase = UpdatePhase.FAILED, message = "No connection to GitHub right now.").statusLine())
            .isEqualTo("No connection to GitHub right now.")
        // A failed check with no message of its own must not silently render as
        // "up to date", which is the sentence every broken updater falls back to.
        assertThat(UpdateInfo(phase = UpdatePhase.FAILED).statusLine()).isEqualTo("The check did not finish")
        assertThat(UpdateInfo(phase = UpdatePhase.DOWNLOADING, downloadedBytes = 50L, totalBytes = 200L).statusLine())
            .isEqualTo("Downloading… 25%")
    }

    @Test
    fun formatBytes_readsLikeASentence() {
        assertThat(UpdateRelease.formatBytes(37_863_756L)).isEqualTo("36.1 MB")
        assertThat(UpdateRelease.formatBytes(512L)).isEqualTo("512 B")
        assertThat(UpdateRelease.formatBytes(1_048_576L)).isEqualTo("1.0 MB")
        // An asset that recorded no size must not advertise "0.0 MB" as the cost of
        // a download nobody measured.
        assertThat(UpdateRelease.formatBytes(0L)).isEqualTo("0 B")
    }

    @Test
    fun releaseAsset_isAnApkByExtensionOnly() {
        assertThat(asset("Foo.apk").isApk).isTrue()
        assertThat(asset("Foo.apk.sha256").isApk).isFalse()
        assertThat(asset("FOO.APK").isApk).isTrue()
    }
}
