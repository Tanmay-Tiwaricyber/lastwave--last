package com.lastwave.app.data.hearing

/**
 * The archive of profiles, one per output device, and the rules for moving
 * between them.
 *
 * Pure state, no Context and no DataStore, which is what makes the rules
 * testable: the reason this feature stores thresholds per device at all is that
 * the same ear loses a few dB of bass to a slightly open ear-tip, so a curve
 * measured through one seal is the wrong curve through another. The answer is
 * not to correct less; it is to remember which seal each curve was found with.
 *
 * Two deliberate asymmetries:
 *
 *  - Saving *merges* into that device's profile instead of replacing it, so
 *    re-running a single doubtful band does not cost the other seven.
 *  - Nothing is silently skipped when the current output is a device that was
 *    never measured. The active curve keeps applying, and the screen says it was
 *    measured elsewhere — a correction you can hear is worth more than a
 *    conservative silence, as long as you know which one you are getting.
 */
object HearingArchive {

    /** Key for a profile taken with no particular device in mind. */
    const val ANY_DEVICE = ""

    /** One saved profile, as the switcher lists it. */
    data class Entry(
        val key: String,
        val label: String,
        val bandCount: Int,
        val takenAtMillis: Long,
        val isActive: Boolean,
    ) {
        val usable: Boolean get() = bandCount >= HearingProfile.MIN_BANDS
    }

    /** "Pixel Buds Pro  " and "pixel buds pro" are one device, not two rows. */
    fun labelKey(label: String): String {
        val trimmed = label.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isBlank()) return ANY_DEVICE
        return trimmed.lowercase()
    }

    /** The label to display for a key. */
    fun displayLabel(key: String): String = if (key == ANY_DEVICE) "any device" else key

    fun save(state: HearingState, profile: HearingProfile, enable: Boolean, takenAtMillis: Long): HearingState {
        val key = labelKey(profile.deviceLabel)
        val merged = HearingProfile.combine(state.devices[key], profile)
            .copy(takenAtMillis = takenAtMillis, deviceLabel = profile.deviceLabel.trim(), formFactor = profile.formFactor)
        return state.copy(
            profile = merged,
            devices = state.devices + (key to merged),
            activeKey = key,
            enabled = enable,
        )
    }

    /** Point the correction at another saved device; a no-op when there is no such entry. */
    fun use(state: HearingState, key: String): HearingState {
        val profile = state.devices[key] ?: return state
        return state.copy(profile = profile, activeKey = key, enabled = profile.isUsable && state.enabled)
    }

    fun forget(state: HearingState, key: String): HearingState {
        val remaining = state.devices - key
        return if (remaining.isEmpty()) {
            HearingState()
        } else {
            val nextKey = remaining.keys.firstOrNull { it == state.activeKey } ?: remaining.keys.last()
            state.copy(devices = remaining, profile = remaining[nextKey], activeKey = nextKey)
        }
    }

    /** Most recently measured first, which is the order a switcher wants. */
    fun entries(state: HearingState): List<Entry> = state.devices.entries
        .map { (key, profile) ->
            Entry(
                key = key,
                label = displayLabel(key),
                bandCount = profile.bands.size,
                takenAtMillis = profile.takenAtMillis,
                isActive = key == state.activeKey,
            )
        }
        .sortedWith(compareByDescending<Entry> { it.takenAtMillis }.thenBy { it.label })

    /** True when the device being listened on now is not the one that was measured. */
    fun isMismatched(state: HearingState, currentDeviceLabel: String): Boolean {
        val profile = state.profile ?: return false
        if (profile.deviceLabel.isBlank() || currentDeviceLabel.isBlank()) return false
        if (!state.enabled) return false
        return labelKey(profile.deviceLabel) != labelKey(currentDeviceLabel)
    }
}
