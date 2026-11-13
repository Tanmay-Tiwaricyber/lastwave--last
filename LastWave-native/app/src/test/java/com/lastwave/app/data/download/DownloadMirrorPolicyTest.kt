package com.lastwave.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind "download mirror", with no Android and no network.
 *
 * What these pin is the part a device cannot check for you: that a pick survives,
 * that a dead pick degrades instead of failing, and that the sentence the completion
 * notice shows names the server that answered. Those three are the difference between
 * a mirror setting and a mirror setting that lies.
 */
class DownloadMirrorPolicyTest {

    private fun addon(
        id: String,
        name: String = "Tidal self-host",
        host: String = "tidal.example.org",
        enabled: Boolean = true,
    ) = AddonMirror(id = id, name = name, host = host, enabled = enabled)

    private val two = listOf(
        addon("a1", "Tidal home", "tidal.example.org"),
        addon("a2", "Qobuz VPS", "qobuz.something.net", enabled = false),
    )

    // ------------------------------------------------------------------ the rows

    @Test
    fun sourcesAlwaysOfferAutoAndYouTubeAndPutThemFirst() {
        val rows = DownloadMirrorPolicy.sources(two)

        assertEquals(listOf("auto", "youtube", "addon:a1", "addon:a2"), rows.map { it.id })
        assertTrue(rows.all { it.label.isNotBlank() && it.detail.isNotBlank() })
    }

    @Test
    fun aSwitchedOffServerStillGetsARowAndSaysWhy() {
        // Hiding it would make the switch the user flipped in Modules look broken; a
        // greyed row with a reason is the difference between "off" and "missing".
        val off = DownloadMirrorPolicy.sources(two).first { it.id == "addon:a2" }

        assertFalse(off.enabled)
        assertEquals("Switched off", off.disabledReason)
        assertTrue(off.detail, off.detail.contains("Modules"))
    }

    @Test
    fun noAddonsStillExplainsTheEmptySlotInsteadOfHidingIt() {
        val rows = DownloadMirrorPolicy.sources(emptyList())

        assertEquals(3, rows.size)
        val placeholder = rows.last()
        assertEquals("addon:none", placeholder.id)
        assertFalse(placeholder.enabled)
        assertEquals("No add-on configured", placeholder.disabledReason)
    }

    @Test
    fun aPlaceholderRowIsNotPickyableButIsStillLabelled() {
        // `addon:none` exists to be read, not chosen; if it ever resolved to a pin the
        // downloader would go looking for a server nobody configured.
        assertNull(DownloadMirrorPolicy.pinnedAddonId("addon:none", emptyList()))
        assertEquals("Auto", DownloadMirrorPolicy.labelFor("addon:none", emptyList()))
    }

    // ------------------------------------------------------------- what a pick means

    @Test
    fun autoPinsNothing() {
        assertNull(DownloadMirrorPolicy.pinnedAddonId(DownloadMirrorPolicy.AUTO, two))
        assertFalse(DownloadMirrorPolicy.isYouTubeOnly(DownloadMirrorPolicy.AUTO))
        assertNull(DownloadMirrorPolicy.pinnedAddonId(null, two))
        assertNull(DownloadMirrorPolicy.pinnedAddonId("", two))
        assertNull(DownloadMirrorPolicy.pinnedAddonId("  ", two))
    }

    @Test
    fun youtubeIsStrictAndNeedsNoConfiguredServer() {
        assertTrue(DownloadMirrorPolicy.isYouTubeOnly(DownloadMirrorPolicy.YOUTUBE))
        assertTrue(DownloadMirrorPolicy.isYouTubeOnly(" youtube "))
        assertFalse(DownloadMirrorPolicy.isYouTubeOnly("addon:a1"))
    }

    @Test
    fun aLiveAddonIsPinnedByIdAndItsLabelFollowsARename() {
        // The id is what gets persisted, so renaming the server in Modules changes the
        // words shown, never which server is asked.
        assertEquals("a1", DownloadMirrorPolicy.pinnedAddonId("addon:a1", two))
        assertEquals("Tidal home", DownloadMirrorPolicy.labelFor("addon:a1", two))
        val renamed = two.map { if (it.id == "a1") it.copy(name = "Tidal NAS") else it }
        assertEquals("a1", DownloadMirrorPolicy.pinnedAddonId("addon:a1", renamed))
        assertEquals("Tidal NAS", DownloadMirrorPolicy.labelFor("addon:a1", renamed))
    }

    @Test
    fun aDisabledOrDeletedServerDegradesToAutoRatherThanFailing() {
        // Two ways a stored pick stops being valid — the user switched the server off,
        // or removed it — and neither may turn into "download failed".
        assertNull(DownloadMirrorPolicy.pinnedAddonId("addon:a2", two))
        assertNull(DownloadMirrorPolicy.pinnedAddonId("addon:gone", two))
        assertEquals("Auto", DownloadMirrorPolicy.labelFor("addon:gone", two))
    }

    @Test
    fun junkInPreferencesReadsAsAuto() {
        listOf("addon:", "youtube ", "ADDON:a1", "whatever", "///").forEach { raw ->
            assertNull(raw, DownloadMirrorPolicy.pinnedAddonId(raw, two))
            if (raw != "youtube ") {
                assertFalse(raw, DownloadMirrorPolicy.isYouTubeOnly(raw))
            }
        }
    }

    // ---------------------------------------------------- what the notice says after

    @Test
    fun aYouTubeFallbackIsNamedAsYouTubeEvenWhenAMirrorWasPicked() {
        // The user asked for the Tidal server; the file came from YouTube. Saying
        // "via Tidal home" here would be the exact lie this label exists to prevent.
        val label = DownloadMirrorPolicy.servedByLabel(
            "addon:a1",
            DownloadMirrorPolicy.ServedBy.YOUTUBE,
            two,
        )

        assertEquals("YouTube Music", label)
    }

    @Test
    fun anAddonHitNamesTheServerThatAnsweredByHost() {
        val label = DownloadMirrorPolicy.servedByLabel(
            pickedId = DownloadMirrorPolicy.AUTO,
            servedBy = DownloadMirrorPolicy.ServedBy.ADDON,
            addons = two,
            servedHost = "qobuz.something.net",
        )

        assertEquals("Qobuz VPS", label)
    }

    @Test
    fun anUnknownServerHostIsReportedAsThatHostRatherThanSwallowed() {
        // A module can hand back a URL from a host that is not in the config at all.
        // "Add-on server" would be a shrug; the host is at least checkable.
        val label = DownloadMirrorPolicy.servedByLabel(
            DownloadMirrorPolicy.AUTO,
            DownloadMirrorPolicy.ServedBy.ADDON,
            two,
            "mystery.host",
        )

        assertEquals("mystery.host", label)
    }

    @Test
    fun aPinnedServerThatServedIsNamedEvenWithoutAHostMatch() {
        val label = DownloadMirrorPolicy.servedByLabel(
            "addon:a1",
            DownloadMirrorPolicy.ServedBy.ADDON,
            two,
            servedHost = null,
        )

        assertEquals("Tidal home", label)
    }

    @Test
    fun moduleSourcedFilesSayModule() {
        assertEquals(
            "Provider module",
            DownloadMirrorPolicy.servedByLabel(
                DownloadMirrorPolicy.AUTO,
                DownloadMirrorPolicy.ServedBy.PROVIDER_MODULE,
                two,
            ),
        )
    }

    @Test
    fun nothingAdoptedFallsBackToThePickSoTheRowNeverGoesBlank() {
        assertEquals(
            "Auto",
            DownloadMirrorPolicy.servedByLabel(
                DownloadMirrorPolicy.AUTO,
                DownloadMirrorPolicy.ServedBy.UNKNOWN,
                two,
            ),
        )
        assertEquals(
            "Tidal home",
            DownloadMirrorPolicy.servedByLabel(
                "addon:a1",
                DownloadMirrorPolicy.ServedBy.UNKNOWN,
                two,
            ),
        )
    }

    // ------------------------------------------------------------------ host labels

    @Test
    fun hostsAreReadableAndOnlyCarryAPortWhenItIsUnusual() {
        assertEquals("tidal.example.org", addonHostLabel("https://tidal.example.org/search/1"))
        assertEquals("tidal.example.org:8443", addonHostLabel("https://tidal.example.org:8443/search/1"))
        assertEquals("no-scheme.example.org", addonHostLabel("no-scheme.example.org/a"))
        assertEquals("example.org", addonHostLabel("http://example.org:80/a"))
    }

    @Test
    fun basicAuthInAnAddonUrlNeverLeaksIntoALabel() {
        // Tokens and user:pass pairs live in these URLs. A row that shows the raw URL
        // would put a credential on the screen — and into a screenshot.
        assertEquals("host.tld", addonHostLabel("https://user:hunter2@host.tld/a/b"))
    }

    @Test
    fun anUnparsableUrlYieldsAHostFreeLabelWithoutThrowing() {
        assertEquals("", addonHostLabel(""))
        assertEquals("garbage", addonHostLabel("garbage"))
    }

    // ------------------------------------------------- the config-to-mirror mapping

    @Test
    fun mirrorsKeepTheirIdsAndEnabledFlagAndFallBackToTheHostForAName() {
        val mirrors = addonMirrorsOf(
            listOf(
                com.lastwave.app.data.local.HttpAddonConfig(id = "x1", url = "https://a.example.org/api", name = "", enabled = true),
                com.lastwave.app.data.local.HttpAddonConfig(id = "x2", url = "https://b.example.org", name = "Home", enabled = false),
            ),
        )

        assertEquals(listOf("x1", "x2"), mirrors.map { it.id })
        assertEquals(listOf(true, false), mirrors.map { it.enabled })
        assertEquals("a.example.org", mirrors[0].name)
        assertEquals("Home", mirrors[1].name)
        assertEquals(
            listOf("addon:x1", "addon:x2"),
            DownloadMirrorPolicy.sources(mirrors).drop(2).map { it.id },
        )
    }

    @Test
    fun aBlankUrlConfigIsStillShownAndStaysUnpickableInPractice() {
        // The row list is about visibility; the downloader's own `enabled && url`
        // filter is what keeps a half-configured server from being asked. The policy
        // must not invent an id for it either.
        val mirrors = addonMirrorsOf(
            listOf(com.lastwave.app.data.local.HttpAddonConfig(id = "e1", url = "", name = "Empty", enabled = true)),
        )

        assertEquals(1, mirrors.size)
        assertEquals("", mirrors[0].host)
        assertEquals("Empty", mirrors[0].name)
    }
}
