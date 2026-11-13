package com.lastwave.app.data.lossless

import com.lastwave.app.data.local.HttpAddonConfig
import com.lastwave.app.data.local.SettingsPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * What "pin this download to one add-on server" actually does to the credential list
 * the search runs over — the mechanism under the Download mirror setting.
 *
 * No network and no Android here: [LosslessMusicApi.getAllEnabledCredentials] is a
 * read of three preference flows plus a URL normalisation, so the whole contract can
 * be pinned on the JVM. That contract is what the mirror picker promises, and each
 * case below is a way the promise could quietly break:
 *
 *  - pinning must narrow to that one server, not merely try it first;
 *  - a pin naming a server that is gone, switched off, or half-configured must yield
 *    *nothing* rather than falling through to the legacy single-server setting —
 *    answering "from this mirror" with some other machine is the exact failure the
 *    feature exists to prevent, and the download cascade (which does fall back, and
 *    says which source served the file) is the place fallback belongs;
 *  - no pin must keep meaning every enabled server, so existing behaviour is intact.
 */
class LosslessCredentialPinningTest {

    private fun apiWith(
        addons: List<HttpAddonConfig>,
        legacyUrl: String? = "https://legacy.example.org",
        legacyEnabled: Boolean = true,
    ): LosslessMusicApi {
        val prefs = mockk<SettingsPreferences>(relaxed = true)
        every { prefs.httpAddons } returns MutableStateFlow(addons)
        every { prefs.addonUrl } returns MutableStateFlow(legacyUrl)
        every { prefs.addonEnabled } returns MutableStateFlow(legacyEnabled)
        return LosslessMusicApi(
            okHttpClient = mockk(relaxed = true),
            moduleManager = mockk(relaxed = true),
            nativeSecrets = mockk<NativeSecrets>(relaxed = true),
            settingsPreferences = prefs,
            hybridServer = mockk(relaxed = true),
        )
    }

    /** Mocked classes are transformed in place; leave no trace for the next test class. */
    @After
    fun unmock() {
        io.mockk.unmockkAll()
    }

    private val two = listOf(
        HttpAddonConfig(id = "a1", url = "https://tidal.example.org", name = "Tidal home"),
        HttpAddonConfig(id = "a2", url = "https://qobuz.example.org", name = "Qobuz VPS", enabled = false),
    )

    @Test
    fun noPinStillMeansEveryEnabledServer() {
        val creds = runBlocking { apiWith(two).getAllEnabledCredentials() }

        assertEquals("the switched-off server must not be asked", 1, creds.size)
        assertEquals("https://tidal.example.org", creds.single().baseUrl)
        assertTrue("every add-on credential is flagged as an add-on", creds.all { it.isAddon })
    }

    @Test
    fun aPinNarrowsToThatOneServer() {
        val creds = runBlocking { apiWith(two).getAllEnabledCredentials("a1") }

        assertEquals(1, creds.size)
        assertEquals("https://tidal.example.org", creds.single().baseUrl)
    }

    @Test
    fun aPinOnADisabledServerYieldsNothingInsteadOfAnotherServer() {
        // The tempting bug: "no enabled server with that id" reads like "no pin", and
        // the legacy single-server setting then answers. The file would come from a
        // machine the user never picked, while the row says they picked one.
        val creds = runBlocking { apiWith(two).getAllEnabledCredentials("a2") }

        assertTrue("expected no credentials for a switched-off pin, got $creds", creds.isEmpty())
    }

    @Test
    fun aPinOnADeletedServerYieldsNothing() {
        val creds = runBlocking { apiWith(two).getAllEnabledCredentials("gone") }

        assertTrue(creds.isEmpty())
    }

    @Test
    fun aPinOnAServerWithNoUrlYieldsNothing() {
        val halfConfigured = listOf(HttpAddonConfig(id = "h1", url = "", name = "Not filled in"))

        assertTrue(runBlocking { apiWith(halfConfigured).getAllEnabledCredentials("h1") }.isEmpty())
    }

    @Test
    fun unpinnedFallsBackToTheLegacyServerOnlyWhenNoAddonIsConfigured() {
        // Without any add-on rows the legacy single-server setting is still the source
        // of truth — that is how older installs keep working. With rows present but
        // all switched off, the same fall-through applies, and that is deliberate: no
        // pin was requested, so nothing was contradicted.
        val legacy = runBlocking { apiWith(emptyList()).getAllEnabledCredentials() }
        assertEquals("https://legacy.example.org", legacy.single().baseUrl)

        val switchedOffRows = runBlocking { apiWith(two.take(1).map { it.copy(enabled = false) }).getAllEnabledCredentials() }
        assertEquals("https://legacy.example.org", switchedOffRows.single().baseUrl)
    }
}
