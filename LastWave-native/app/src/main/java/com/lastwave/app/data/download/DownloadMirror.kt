package com.lastwave.app.data.download

/**
 * Which source a download should come from.
 *
 * The app can already fetch a song's audio from more than one place, and which one
 * answers changes what the file *is*: the add-on servers carry lossless and Hi-Res
 * renditions YouTube does not have, and YouTube answers for songs no add-on indexes.
 * Until now that choice was only implicit — a fixed cascade inside
 * [TrackDownloadManager] — so a song that came out as AAC because one server timed
 * out was invisible. A named mirror per download makes it visible, and pickable.
 *
 * Everything here is pure and string-based on purpose: what a mirror means, which
 * rows the sheet shows, and what each row says about itself are decisions a unit
 * test can pin. [TrackDownloadManager] then only has to obey them.
 */
data class DownloadMirrorSource(
    /** Stable id, persisted in preferences. Never renamed. */
    val id: String,
    /** What the row is called. */
    val label: String,
    /** One line under the label: what choosing this does to the file. */
    val detail: String,
    val enabled: Boolean,
    /** Why a row is greyed out, in the words the row shows. */
    val disabledReason: String? = null,
)

/**
 * The three ids plus one row per configured add-on server. Ids for add-on mirrors are
 * `addon:<configId>` so a pick survives a rename of the server's display name.
 */
object DownloadMirrorPolicy {

    const val AUTO = "auto"
    const val YOUTUBE = "youtube"
    const val ADDON_PREFIX = "addon:"

    /**
     * The rows a picker should show, in order.
     *
     * Auto and YouTube are always there because they are always possible. Add-on
     * servers appear as their own mirrors — pinning a download to one server is the
     * point of running several — and a configured-but-disabled server still gets a
     * row, greyed out with the reason, because hiding it would make the switch the
     * user flipped look broken.
     */
    fun sources(addons: List<AddonMirror>): List<DownloadMirrorSource> {
        val rows = mutableListOf(
            DownloadMirrorSource(
                id = AUTO,
                label = "Auto (every source)",
                detail = "Hi-Res tiers first, then YouTube. Never picks a lower quality than it can verify.",
                enabled = true,
            ),
            DownloadMirrorSource(
                id = YOUTUBE,
                label = "YouTube Music",
                detail = "128-256 kbps AAC / Opus, exactly as streamed. Never upgrades, always answers.",
                enabled = true,
            ),
        )
        addons.forEach { addon ->
            rows += DownloadMirrorSource(
                id = ADDON_PREFIX + addon.id,
                label = addon.name,
                detail = if (addon.enabled) {
                    "Pinned to ${addon.host}. If this server has no copy, YouTube serves it instead."
                } else {
                    "This server is switched off in Settings \u2192 Modules."
                },
                enabled = addon.enabled,
                disabledReason = if (addon.enabled) null else "Switched off",
            )
        }
        if (addons.isEmpty()) {
            rows += DownloadMirrorSource(
                id = "addon:none",
                label = "Add-on server",
                detail = "Add-ons in Settings \u2192 Modules give lossless and Hi-Res mirrors. None is configured.",
                enabled = false,
                disabledReason = "No add-on configured",
            )
        }
        return rows
    }

    /** A stored id that no longer names anything usable (a deleted server) falls back. */
    fun resolveOrNull(id: String?, addons: List<AddonMirror>): String? {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == AUTO) return null
        if (trimmed == YOUTUBE) return YOUTUBE
        if (trimmed.startsWith(ADDON_PREFIX)) {
            val configId = trimmed.removePrefix(ADDON_PREFIX)
            val live = addons.firstOrNull { it.id == configId } ?: return null
            return if (live.enabled) ADDON_PREFIX + configId else null
        }
        return null
    }

    /** True when this pick means "YouTube only", the strict path the manager already has. */
    fun isYouTubeOnly(id: String?): Boolean = resolveOrNull(id, emptyList()) == YOUTUBE

    /** Which add-on server to pin the search to, or null for every enabled one. */
    fun pinnedAddonId(id: String?, addons: List<AddonMirror>): String? =
        resolveOrNull(id, addons)?.takeIf { it.startsWith(ADDON_PREFIX) }?.removePrefix(ADDON_PREFIX)

    /** What the row and the notification call this pick. */
    fun labelFor(id: String?, addons: List<AddonMirror>): String {
        val resolved = resolveOrNull(id, addons) ?: return "Auto"
        return when {
            resolved == YOUTUBE -> "YouTube Music"
            resolved.startsWith(ADDON_PREFIX) ->
                addons.firstOrNull { ADDON_PREFIX + it.id == resolved }?.name ?: "Add-on server"
            else -> "Auto"
        }
    }

    /**
     * What the completion notice says served the file.
     *
     * "via <mirror>" is the whole point of the feature: a song that fell back to
     * YouTube because a server timed out is a different file, and the user should not
     * have to guess afterwards which one they got.
     */
    fun servedByLabel(
        pickedId: String?,
        servedBy: ServedBy,
        addons: List<AddonMirror>,
        servedHost: String? = null,
    ): String {
        val pinned = pinnedAddonId(pickedId, addons)
        return when (servedBy) {
            ServedBy.YOUTUBE -> "YouTube Music"
            ServedBy.ADDON -> {
                // The host in the URL the stream was resolved from is the only record
                // of who actually answered when "auto" is allowed to try several
                // servers, so it wins over the pinned id — and over the generic label,
                // which would leave "an add-on server somewhere" as the answer.
                addons.firstOrNull { it.host.isNotBlank() && it.host == servedHost }?.name
                    ?: addons.firstOrNull { it.id == pinned }?.name
                    ?: servedHost?.takeIf { it.isNotBlank() }
                    ?: "Add-on server"
            }
            ServedBy.PROVIDER_MODULE -> "Provider module"
            ServedBy.UNKNOWN -> labelFor(pickedId, addons)
        }
    }

    /** Which stage of the resolution chain actually produced the bytes. */
    enum class ServedBy {
        ADDON,
        PROVIDER_MODULE,
        YOUTUBE,
        UNKNOWN,
    }
}

/**
 * The configured add-on servers, as mirrors.
 *
 * Both the download cascade and the two pickers build their list through this, so
 * a server cannot be "available" in Settings and missing from the sheet: the name
 * falls back to the host because a server with no display name is otherwise
 * indistinguishable from its twin.
 */
fun addonMirrorsOf(addons: List<com.lastwave.app.data.local.HttpAddonConfig>): List<AddonMirror> =
    addons.map { config ->
        AddonMirror(
            id = config.id,
            name = config.name.ifBlank { addonHostLabel(config.url) },
            host = addonHostLabel(config.url),
            enabled = config.enabled,
        )
    }

/** One configured add-on server, as far as mirror choice is concerned. */
data class AddonMirror(
    val id: String,
    val name: String,
    val host: String,
    val enabled: Boolean,
)

/**
 * `https://tidal.example.org:8443/a/token/manifest` → `tidal.example.org`.
 *
 * A row has to name the machine it is asking, because two add-ons with the same
 * display name are otherwise indistinguishable — and the port only shows when it is
 * not the default, since `host:443` is noise.
 */
fun addonHostLabel(url: String): String {
    val afterScheme = url.substringAfter("://", url).substringBefore('/')
    val host = afterScheme.substringBefore('@').let { if (it.isBlank()) afterScheme else it }
    val authority = afterScheme.substringAfterLast('@')
    val noPort = authority.substringBefore(':')
    val port = authority.substringAfter(':', "")
    return if (port.isNotBlank() && port != "443" && port != "80") "$noPort:$port" else noPort
}
