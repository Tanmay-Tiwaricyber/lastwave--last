package com.lastwave.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * ─────────────────────────────────────────────────────────────────────────────
 *  "Silent Programmer" design scheme (v5.3.0)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Port of the design language used on txt4nm4y.vercel.app:
 *
 *   • Strict zinc monochrome surface stack (zinc-950 → zinc-50) instead of a
 *     tinted Material You tonal ramp — the UI reads as paper/ink, and color is
 *     used only as a deliberate accent.
 *   • A single green accent (emerald-600 #00a544 light / green-400 #05df72
 *     dark) for "live"/primary affordances, exactly like the site's status dot
 *     and link hovers.
 *   • Hairline `ring-1` borders: every container is defined by its
 *     [ColorScheme.outlineVariant] stroke, not by elevation or tint.
 *   • No elevation, no tonal diffusion — flat surfaces + 1px borders.
 *
 * Material role mapping used throughout the app:
 *   background / surface              → page (zinc-50 / zinc-950)
 *   surfaceContainerLowest..Highest   → card, muted, raised (zinc-100..zinc-800)
 *   onSurface                         → zinc-950 / zinc-50  (ink)
 *   onSurfaceVariant                  → zinc-500 / zinc-400 (muted foreground)
 *   outline                           → zinc-400 / zinc-600  (strong hairline)
 *   outlineVariant                    → zinc-200 / zinc-800  (default hairline)
 */
object SpSchemeBuilder {

    // ── zinc scale ────────────────────────────────────────────────────────────
    private val Zinc50 = Color(0xFFFAFAFA)
    private val Zinc100 = Color(0xFFF4F4F5)
    private val Zinc200 = Color(0xFFE4E4E7)
    private val Zinc300 = Color(0xFFD4D4D8)
    private val Zinc400 = Color(0xFF9F9FA9)
    private val Zinc500 = Color(0xFF71717B)
    private val Zinc600 = Color(0xFF52525C)
    private val Zinc700 = Color(0xFF3F3F46)
    private val Zinc800 = Color(0xFF27272A)
    private val Zinc900 = Color(0xFF18181B)
    private val Zinc950 = Color(0xFF09090B)

    // ── accents ───────────────────────────────────────────────────────────────
    private val Green400 = Color(0xFF05DF72)
    private val Green500 = Color(0xFF00C758)
    private val Green600 = Color(0xFF00A544)
    private val Green700 = Color(0xFF007956)
    private val Green950 = Color(0xFF032B21)

    private val Blue400 = Color(0xFF51A2FF)
    private val Blue500 = Color(0xFF3080FF)
    private val Blue600 = Color(0xFF155DFC)

    private val Red400 = Color(0xFFFF6467)
    private val Red500 = Color(0xFFFB2C36)
    private val Red600 = Color(0xFFE7000B)
    private val Red900 = Color(0xFF82181A)

    /**
     * Dark: zinc-950 page, zinc-900 cards, hairlines at zinc-800,
     * green-400 as the accent. Matches the site's `dark:` classes.
     */
    fun buildSilentProgrammerDarkScheme(amoled: Boolean, accent: Color = Green400): ColorScheme {
        val page = if (amoled) Color.Black else Zinc950
        val card = if (amoled) Color(0xFF050505) else Zinc900
        val muted = if (amoled) Color(0xFF0A0A0A) else Zinc800
        val raised = if (amoled) Color(0xFF101010) else Zinc700

        return darkColorScheme(
            primary = accent,
            onPrimary = Zinc950,
            primaryContainer = accent.copy(alpha = 0.18f).compositeOver(Zinc900),
            onPrimaryContainer = accent,
            inversePrimary = Green700,

            secondary = Zinc300,
            onSecondary = Zinc950,
            secondaryContainer = Zinc800,
            onSecondaryContainer = Zinc100,

            tertiary = Blue400,
            onTertiary = Zinc950,
            tertiaryContainer = Color(0xFF10233F),
            onTertiaryContainer = Blue400,

            error = Red400,
            onError = Zinc950,
            errorContainer = Red900,
            onErrorContainer = Color(0xFFFFDAD6),

            background = page,
            onBackground = Zinc50,
            surface = page,
            onSurface = Zinc50,
            surfaceVariant = Zinc800,
            onSurfaceVariant = Zinc400,

            surfaceTint = Color.Transparent,
            surfaceContainerLowest = page,
            surfaceContainerLow = card,
            surfaceContainer = card,
            surfaceContainerHigh = muted,
            surfaceContainerHighest = raised,

            // `ring-1` hairlines — the defining visual of the reference design.
            outline = Zinc600,
            outlineVariant = Zinc800,

            inverseSurface = Zinc100,
            inverseOnSurface = Zinc950,
            scrim = Color.Black,
        )
    }

    /**
     * Light: zinc-50 page, white cards, hairlines at zinc-200,
     * emerald-600 as the accent (green-400 is invisible on white).
     */
    fun buildSilentProgrammerLightScheme(accent: Color = Green600): ColorScheme = lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = 0.14f).compositeOver(Color.White),
        onPrimaryContainer = accent,
        inversePrimary = Green400,

        secondary = Zinc700,
        onSecondary = Zinc50,
        secondaryContainer = Zinc100,
        onSecondaryContainer = Zinc900,

        tertiary = Blue600,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFDBEAFE),
        onTertiaryContainer = Color(0xFF1D4ED8),

        error = Red600,
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF991B1B),

        background = Zinc50,
        onBackground = Zinc950,
        surface = Zinc50,
        onSurface = Zinc950,
        surfaceVariant = Zinc100,
        onSurfaceVariant = Zinc500,

        surfaceTint = Color.Transparent,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainer = Zinc50,
        surfaceContainerHigh = Zinc100,
        surfaceContainerHighest = Zinc200,

        outline = Zinc300,
        outlineVariant = Zinc200,

        inverseSurface = Zinc900,
        inverseOnSurface = Zinc50,
        scrim = Color.Black,
    )

    // ── Groundwork: themeable accent (not exposed in Settings yet) ─────────────────────────────────────
    // The reference design uses exactly one accent colour. Rather than hard-
    // coding green, the accent is parameterised so Settings → Accent can
    // recolour the skin (Spider Verse red, Ocean blue, Violet, Pink) while
    // keeping the zinc surfaces, hairlines and type untouched.
    private val accents: Map<String, Pair<Color, Color>> = mapOf(
        // hex seed → (dark accent, light accent); light is a darker tone so it
        // stays legible on white, mirroring the site's green-400/green-600 pair.
        "#05df72" to (Green400 to Green600),   // Emerald (default)
        "#f43f5e" to (Color(0xFFFB7185) to Color(0xFFE11D48)), // Rose / "Spider-Verse"
        "#3b82f6" to (Color(0xFF60A5FA) to Color(0xFF2563EB)), // Ocean
        "#8b5cf6" to (Color(0xFFA78BFA) to Color(0xFF7C3AED)), // Violet
        "#f59e0b" to (Color(0xFFFBBF24) to Color(0xFFD97706)), // Amber
        "#22d3ee" to (Color(0xFF22D3EE) to Color(0xFF0891B2)), // Cyan
    )

    /** Accent pair for a seed hex; falls back to emerald when unknown. */
    fun accentPair(seedHex: String?): Pair<Color, Color> {
        val key = seedHex?.lowercase()?.trim()
        return accents[key] ?: accents.entries.first { it.key == "#05df72" }.value
    }

    /** Seeds a future Settings → Appearance → Accent picker would offer. */
    val accentSeeds: List<String> = accents.keys.toList()

    fun darkSchemeForSeed(amoled: Boolean, accentSeed: String?): ColorScheme {
        val (accent, _) = accentPair(accentSeed)
        return buildSilentProgrammerDarkScheme(amoled, accent)
    }

    fun lightSchemeForSeed(accentSeed: String?): ColorScheme {
        val (_, accentLight) = accentPair(accentSeed)
        return buildSilentProgrammerLightScheme(accentLight)
    }

    /** Hairline stroke used by every "ring-1" container in the new UI. */
    fun hairlineFor(isDark: Boolean): Color = if (isDark) Zinc800 else Zinc200

    /** Slightly stronger hairline for hover/pressed/emphasis states. */
    fun hairlineStrongFor(isDark: Boolean): Color = if (isDark) Zinc700 else Zinc300

    /** Muted foreground (`text-muted-foreground`) for secondary copy. */
    fun mutedForegroundFor(isDark: Boolean): Color = if (isDark) Zinc400 else Zinc500
}
