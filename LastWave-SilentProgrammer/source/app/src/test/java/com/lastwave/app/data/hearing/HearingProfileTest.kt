package com.lastwave.app.data.hearing

import com.google.common.truth.Truth.assertThat
import com.lastwave.app.data.autoeq.ParametricEqText
import com.lastwave.app.data.autoeq.PeqFitter
import com.lastwave.app.data.autoeq.PeqFilter
import com.lastwave.app.data.autoeq.PeqFilterType
import com.lastwave.app.data.autoeq.PeqMath
import com.lastwave.app.data.autoeq.PeqSettings
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The maths of the hearing self-test, on the JVM.
 *
 * What a test like this can and cannot prove is the same line the release notes
 * draw: the arithmetic — relative losses, the half-gain rule, the caps, the
 * staircase's convergence, how answers become a profile, and how the result fits
 * into the EQ's biquad budget — is all here. Whether a human can hear a -58 dBFS
 * warbled tone through a particular earbud, and whether a phone really keeps the
 * other channel silent, needs a device and ears.
 */
class HearingProfileTest {

    private val hd600 = """
        Preamp: -6.3 dB
        Filter 1: ON LSC Fc 105 Hz Gain 6.5 dB Q 0.70
        Filter 2: ON PK Fc 125 Hz Gain -2.7 dB Q 0.55
        Filter 3: ON PK Fc 8445 Hz Gain 3.3 dB Q 1.61
        Filter 4: ON PK Fc 522 Hz Gain 0.7 dB Q 1.02
        Filter 5: ON PK Fc 1298 Hz Gain -1.2 dB Q 2.14
        Filter 6: ON HSC Fc 10000 Hz Gain -3.1 dB Q 0.70
        Filter 7: ON PK Fc 3158 Hz Gain -1.8 dB Q 3.67
        Filter 8: ON PK Fc 2166 Hz Gain 0.9 dB Q 3.32
        Filter 9: ON PK Fc 6639 Hz Gain 2.2 dB Q 5.82
        Filter 10: ON PK Fc 5433 Hz Gain -1.2 dB Q 5.70
    """.trimIndent()

    /** Both ears equally sensitive, with [lossDb] of loss at each of [affectedHz]. */
    private fun profileWithLoss(vararg affectedHz: Double, lossDb: Double = 20.0): HearingProfile =
        HearingProfile(
            bands = HearingProfile.FREQUENCIES.map { frequency ->
                HearingBand(
                    frequencyHz = frequency,
                    leftThresholdDb = if (affectedHz.any { it == frequency }) lossDb else 0.0,
                    rightThresholdDb = if (affectedHz.any { it == frequency }) lossDb else 0.0,
                )
            },
        )

    private fun peak(curve: DoubleArray): Double = curve.maxOrNull() ?: 0.0

    private fun trough(curve: DoubleArray): Double = curve.minOrNull() ?: 0.0

    private fun DoubleArray.at(frequency: Double): Double {
        val grid = PeqMath.LOG_GRID
        var best = 0
        var bestDelta = Double.MAX_VALUE
        grid.forEachIndexed { index, value ->
            val delta = abs(ln(value) - ln(frequency))
            if (delta < bestDelta) {
                bestDelta = delta
                best = index
            }
        }
        return this[best]
    }

    @Test
    fun lossesAreMeasuredAgainstTheQuietestBand() {
        val flat = profileWithLoss(lossDb = 0.0)
        assertThat(flat.losses().map { it.meanLossDb }).containsExactly(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val losses = profileWithLoss(4_000.0, lossDb = 18.0).losses().associate { it.frequencyHz to it.meanLossDb }
        assertThat(losses[4_000.0]).isWithin(1e-9).of(18.0)
        assertThat(losses[250.0]).isWithin(1e-9).of(0.0)

        // A band the ear hears *better* than another cannot turn into a cut: the
        // reference is the quietest threshold, so losses are all zero or positive.
        val uneven = HearingProfile(
            bands = listOf(
                HearingBand(250.0, 10.0, 10.0),
                HearingBand(500.0, 0.0, 0.0),
                HearingBand(1_000.0, 4.0, 4.0),
                HearingBand(2_000.0, 6.0, 6.0),
            ),
        )
        assertThat(uneven.losses().map { it.meanLossDb }).containsExactly(10.0, 0.0, 4.0, 6.0).inOrder()
        assertThat(uneven.losses().all { it.leftDb >= 0.0 && it.rightDb >= 0.0 }).isTrue()
    }

    @Test
    fun theCurveIsHalfTheLossCappedAndNeverACut() {
        // 8 dB of loss at 1 kHz: the half-gain rule gives 4 dB, comfortably under
        // every cap.
        val curve = profileWithLoss(1_000.0, lossDb = 8.0).curve()
        assertThat(peak(curve)).isWithin(0.6).of(4.0)
        assertThat(trough(curve)).isAtLeast(0.0)

        // 40 dB of loss must not become +20 dB of gain. The cap is the point.
        val capped = profileWithLoss(1_000.0, lossDb = 40.0).curve()
        assertThat(peak(capped)).isAtMost(HearingProfile.MAX_BOOST_DB + 1e-9)

        // A few dB of scatter is not hearing, and is ignored entirely.
        val tiny = profileWithLoss(1_000.0, lossDb = HearingProfile.MIN_LOSS_DB - 2.0).curve()
        assertThat(peak(tiny)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun trebleCorrectionIsTaperedWhereHarshnessStarts() {
        val mid = peak(profileWithLoss(2_000.0, lossDb = 20.0).curve())
        val treble = peak(profileWithLoss(8_000.0, lossDb = 20.0).curve())
        // Same loss, a smaller lift at 8 kHz, because pushing treble hard through a
        // sealed ear-tip is how you get a hiss you cannot unhear.
        assertThat(treble).isLessThan(mid)
        assertThat(treble).isWithin(1.0).of(20.0 * HearingProfile.HALF_GAIN_RULE * HearingProfile.HIGH_FREQUENCY_TAPER)
    }

    @Test
    fun strengthScalesTheCorrectionAndTheCapStillHolds() {
        val profile = profileWithLoss(1_000.0, 2_000.0, lossDb = 10.0)
        val whole = peak(profile.curve(strength = 1.0))
        val half = peak(profile.curve(strength = 0.5))
        assertThat(half).isWithin(0.3).of(whole / 2.0)
        assertThat(peak(profile.curve(strength = 100.0))).isAtMost(HearingProfile.MAX_BOOST_DB + 1e-9)
    }

    @Test
    fun theCurveFitsTheEightsAndShelvesTheFitterOffers() {
        val desired = profileWithLoss(2_000.0, 4_000.0, lossDb = 22.0).curve()
        val fitted = PeqFitter.fit(desired)
        assertThat(fitted.filters).hasSize(10)
        // The whole point of the shared grid: what the ear asked for and what ten
        // biquads can do live within a decibel of each other.
        assertThat(PeqFitter.rmsError(fitted, desired)).isLessThan(1.0)
        assertThat(fitted.preampDb).isAtMost(0.0)
        // No boost is promised that the filters do not actually deliver.
        val applied = PeqMath.LOG_GRID.maxOf { PeqMath.filtersDb(fitted.filters, it) }
        assertThat(applied).isAtMost(HearingProfile.MAX_BOOST_DB + 3.0)
    }

    @Test
    fun aHeadphoneProfileAndAnEarCorrectionStackInsideTheWindow() {
        val base = ParametricEqText.parse(hd600)!!
        val curve = profileWithLoss(2_000.0, 4_000.0, 6_000.0, lossDb = 24.0).curve()
        val summed = HearingBlender.desiredCurve(base = base, hearingCurve = curve)
        assertThat(trough(summed)).isAtLeast(HearingBlender.MIN_DB)
        assertThat(peak(summed)).isAtMost(HearingBlender.MAX_DB)

        val fitted = HearingBlender.fit(summed)
        assertThat(PeqFitter.rmsError(fitted, summed)).isLessThan(1.2)
        // The preamp is what keeps the sum from clipping: AutoEQ's rule subtracts
        // the largest boost of the *combined* curve, so nothing exceeds 0 dBFS.
        val peakResponse = PeqMath.LOG_GRID.maxOf { PeqMath.responseDb(fitted, it) }
        assertThat(peakResponse).isAtMost(0.2)

        // And a measured band really did come up relative to the bare profile. At
        // 4 kHz specifically, because the curve is only as wide as the bands the
        // test measured: between them it interpolates, so 3 kHz, which was fine,
        // stays fine instead of getting a lift it did not ask for.
        val before = PeqMath.responseDb(base, 4_000.0)
        val after = PeqMath.responseDb(fitted, 4_000.0)
        assertThat(after - before).isGreaterThan(4.0)
        assertThat(abs(curve.at(1_000.0) - curve.at(2_000.0))).isGreaterThan(1.0)
    }

    @Test
    fun nothingIsFittedWhenThereIsNothingToApply() {
        val grid = PeqMath.LOG_GRID
        val alone = HearingBlender.desiredCurve(base = null, hearingCurve = DoubleArray(grid.size) { 3.0 })
        assertThat(peak(alone)).isWithin(1e-9).of(3.0)
        // No profile, no adjustments, no curve: the grid is flat, so the caller
        // keeps the stored settings instead of paying for a pointless refit.
        val flat = HearingBlender.desiredCurve(base = null)
        assertThat(peak(flat)).isWithin(1e-9).of(0.0)
        assertThat(trough(flat)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun aHandEditedProfileKeepsItsNumbersAndTheWeakestPeaksGetDropped() {
        val manual = PeqSettings(
            preampDb = 0.0,
            filters = (0 until 18).map { PeqFilter(PeqFilterType.PEAKING, 100.0 * (it + 1), 1.0, 0.7) },
        )
        val hearing = PeqFitter.fit(profileWithLoss(2_000.0, 4_000.0, lossDb = 18.0).curve())
        val merged = HearingBlender.appendHearing(manual, hearing)
        // 18 manual filters plus two of the ten hearing peaks: the native chain
        // holds 20 and no more.
        assertThat(merged.filters).hasSize(PeqSettings.MAX_FILTERS)
        assertThat(merged.filters.take(18)).isEqualTo(manual.filters)
        assertThat(merged.preampDb).isAtMost(0.0)
        // The survivors are the biggest corrections, not the first ones.
        val added = merged.filters.drop(18).map { abs(it.gainDb) }
        val strongestTwo = hearing.filters.filter { it.enabled }
            .sortedByDescending { abs(it.gainDb) }
            .take(2)
            .map { abs(it.gainDb) }
        assertThat(added).containsExactlyElementsIn(strongestTwo)

        val full = PeqSettings(0.0, (0 until PeqSettings.MAX_FILTERS).map { PeqFilter(PeqFilterType.PEAKING, 100.0 * (it + 1), 1.0, 0.7) })
        assertThat(HearingBlender.appendHearing(full, hearing)).isEqualTo(full)
        assertThat(HearingBlender.appendHearing(manual, PeqSettings())).isEqualTo(manual)
    }

    @Test
    fun aPartialProfileIsRefusedAndSaysSo() {
        val thin = HearingProfile(bands = listOf(HearingBand(250.0, 0.0, 0.0), HearingBand(1_000.0, 3.0, 3.0)))
        assertThat(thin.isUsable).isFalse()
        assertThat(thin.summary()).isEqualTo("Not enough bands measured")
        assertThat(profileWithLoss(4_000.0, lossDb = 16.0).isUsable).isTrue()
        assertThat(profileWithLoss().summary()).startsWith("Nothing to correct")
        val trebleHeavy = profileWithLoss(2_000.0, 3_000.0, 4_000.0, 6_000.0, 8_000.0, lossDb = 30.0)
        assertThat(trebleHeavy.summary()).contains("high-frequency")
        assertThat(trebleHeavy.bandSplit().second).isGreaterThan(trebleHeavy.bandSplit().first)
    }

    @Test
    fun asymmetricEarsAreReportedRatherThanCorrected() {
        val uneven = HearingProfile(
            bands = HearingProfile.FREQUENCIES.map { frequency ->
                HearingBand(frequency, leftThresholdDb = 0.0, rightThresholdDb = 14.0)
            },
        )
        assertThat(uneven.asymmetryDb).isWithin(0.2).of(14.0)
        assertThat(uneven.summary()).contains("cannot split")
        // What the curve sees is the mean of the two ears, so a one-sided loss
        // becomes a compromise lift on both rather than a fix for one. That is the
        // honest limit of a mono filter list, and why the message above exists.
        val compromise = HearingProfile(
            bands = HearingProfile.FREQUENCIES.map { HearingBand(it, 7.0, 7.0) },
        )
        assertThat(peak(uneven.curve())).isWithin(1e-9).of(peak(compromise.curve()))
        // Balanced ears report no asymmetry at all.
        assertThat(profileWithLoss(4_000.0, lossDb = 20.0).asymmetryDb).isWithin(1e-9).of(0.0)
    }

    @Test
    fun theStaircaseFindsAPredictableThresholdInAFewSteps() {
        val mask = -40.0
        val staircase = ThresholdStaircase()
        var guard = 0
        while (!staircase.finished && guard++ < 100) {
            staircase.respond(heard = staircase.levelDb >= mask)
        }
        assertThat(staircase.finished).isTrue()
        assertThat(staircase.thresholdDb).isWithin(3.0).of(mask)
        assertThat(staircase.isConfident).isTrue()
        assertThat(staircase.spreadDb).isLessThan(2.0)
        assertThat(staircase.reversalCount).isEqualTo(HearingTestSession.REVERSALS)
        // Pacing matters as much as accuracy: 40 presentations per band per ear is
        // already minutes for the whole test, and this ear needed far fewer.
        assertThat(staircase.responses).isLessThan(25)
        // Down by two, up by one. That asymmetry is why the estimate lands on the
        // last level that was heard instead of overshooting it by a whole step.
        assertThat(staircase.trace.take(3)).containsExactly(-26.0, -28.0, -30.0).inOrder()
    }

    @Test
    fun anEarThatHearsNothingIsMarkedInsteadOfGuessedAt() {
        val staircase = ThresholdStaircase()
        var guard = 0
        while (!staircase.finished && guard++ < 100) staircase.respond(heard = false)
        assertThat(staircase.noResponse).isTrue()
        assertThat(staircase.isConfident).isFalse()
        // It climbed to the ceiling rather than reporting its start level as a
        // threshold, and the ceiling is what thresholdDb falls back to.
        assertThat(staircase.thresholdDb).isEqualTo(HearingTestSession.CEILING_DB)
        // It stopped one step short of the ceiling presentation, having used up
        // the whole range: 18 presentations, not an early bail-out.
        assertThat(staircase.trace.last()).isAtLeast(HearingTestSession.CEILING_DB - 1.0)
        assertThat(staircase.responses).isAtLeast(15)
    }

    @Test
    fun anInconsistentListenerStillTerminatesInBounds() {
        val random = Random(7)
        val staircase = ThresholdStaircase()
        var guard = 0
        while (!staircase.finished && guard++ < 200) {
            staircase.respond(heard = random.nextBoolean())
        }
        assertThat(staircase.finished).isTrue()
        // The two things a self-test must guarantee even when the listener is
        // nodding off or tapping twice: it stops, and it never reports a level
        // outside the range it was allowed to play.
        assertThat(staircase.responses).isAtMost(40)
        assertThat(staircase.thresholdDb).isAtLeast(HearingTestSession.FLOOR_DB)
        assertThat(staircase.thresholdDb).isAtMost(HearingTestSession.CEILING_DB)
    }

    @Test
    fun aSimulatedRunBecomesAProfileAndDropsWhatWasNotHeard() {
        val session = HearingTestSession()
        // Thresholds in dB below full scale; the two 6 kHz and 8 kHz masks sit
        // above the test's ceiling, so no level the app is allowed to play is
        // audible and those bands must be reported as untested.
        val masks = mapOf(
            250.0 to -46.0,
            500.0 to -44.0,
            1_000.0 to -42.0,
            2_000.0 to -38.0,
            3_000.0 to -34.0,
            4_000.0 to -30.0,
            6_000.0 to -6.0,
            8_000.0 to -4.0,
        )
        var presentations = 0
        while (session.beginPresentation() != null && presentations < 5_000) {
            val step = session.current()!!
            session.answer(step.levelDb >= masks.getValue(step.frequencyHz))
            presentations++
        }
        assertThat(session.isComplete).isTrue()
        val profile = session.profile()
        assertThat(profile.isUsable).isTrue()
        assertThat(profile.bands.map { it.frequencyHz })
            .containsExactly(250.0, 500.0, 1_000.0, 2_000.0, 3_000.0, 4_000.0)
        // Both ears were run against the same mask, so they agree.
        assertThat(profile.bands.all { abs(it.leftThresholdDb - it.rightThresholdDb) < 2.5 }).isTrue()
        // And the correction rises into the treble, which is what the masks said.
        val curve = profile.curve()
        assertThat(curve.at(4_000.0)).isGreaterThan(curve.at(1_000.0))
        assertThat(session.readings().count { it.noResponse }).isAtLeast(4)
        assertThat(session.readings().filter { it.noResponse }.map { it.label() }.first()).contains("never heard")
    }

    @Test
    fun skippingABandLeavesItOutRatherThanZeroingIt() {
        val session = HearingTestSession(plan = listOf(HearingTestSession.Target(Ear.LEFT, 1_000.0)))
        assertThat(session.beginPresentation()).isNotNull()
        session.skip()
        assertThat(session.isComplete).isTrue()
        assertThat(session.profile().bands).isEmpty()
        // A skipped band is not "no loss", which would flatten the reference and
        // invent corrections on every other band: it is absent.
        assertThat(session.readings().single().abandoned).isTrue()
    }

    @Test
    fun aProfileRoundTripsThroughStorage() {
        val profile = profileWithLoss(4_000.0, 6_000.0, lossDb = 17.0).copy(
            takenAtMillis = 1_700_000_000_000,
            deviceLabel = "Some Buds",
            formFactor = "in-ear",
        )
        val state = HearingState(profile = profile, enabled = true, strength = 0.7)
        val json = Json { encodeDefaults = true }
        val raw = json.encodeToString(HearingState.serializer(), state)
        val restored = json.decodeFromString(HearingState.serializer(), raw)
        assertThat(restored).isEqualTo(state)
        assertThat(peak(restored.profile!!.curve(strength = 0.7)))
            .isWithin(1e-9).of(peak(profile.curve(strength = 0.7)))
        // Old rows without a profile still decode.
        assertThat(json.decodeFromString(HearingState.serializer(), "{}").profile).isNull()
    }

    @Test
    fun rerunningOneBandKeepsTheRest() {
        val original = profileWithLoss(4_000.0, lossDb = 20.0)
        val single = HearingProfile(
            bands = listOf(HearingBand(4_000.0, 2.0, 2.0), HearingBand(6_000.0, 5.0, 5.0)),
            deviceLabel = "wired",
        )
        val merged = HearingProfile.combine(original, single)
        assertThat(merged.bands.map { it.frequencyHz })
            .containsExactly(*HearingProfile.FREQUENCIES.toTypedArray()).inOrder()
        assertThat(merged.bands.first { it.frequencyHz == 4_000.0 }.leftThresholdDb).isWithin(1e-9).of(2.0)
        assertThat(merged.bands.first { it.frequencyHz == 250.0 }.leftThresholdDb).isWithin(1e-9).of(0.0)
        assertThat(HearingProfile.combine(null, single)).isEqualTo(single)
    }

    @Test
    fun aTestToneIsOneSidedFadedAndAtTheFrequencyItClaims() {
        val mono = HearingTones.burst(1_000.0, -26.0, durationSec = 0.75, ear = null)
        // A sine's RMS sits 3 dB under its peak; this checks the dBFS mapping,
        // which is what makes "one step is 2 dB" mean 2 dB.
        assertThat(HearingTones.rmsDbFs(mono)).isWithin(1.0).of(-26.0 - 3.01)
        // The energy sits inside the warble band, not at some harmonic or an
        // octave away. It deliberately does NOT peak at exactly 1 kHz: a swept
        // burst puts most of its energy at the ends of the swing, which is how it
        // averages a standing wave in the canal out instead of exciting one.
        assertThat(abs(HearingTones.dominantFrequencyHz(mono, 940.0, 1_060.0) - 1_000.0)).isLessThan(45.0)
        val probes = (0..200).map { 900.0 + it.toDouble() }
        val strengths = probes.map { it to HearingTones.toneStrengthAt(mono, it) }
        val weighted = strengths.sumOf { it.first * it.second }
        val total = strengths.sumOf { it.second }
        assertThat(abs(weighted / total - 1_000.0)).isLessThan(6.0)
        // A click at the start would be its own wideband stimulus, heard when the
        // tone was not: the burst has to begin and end at silence.
        assertThat(abs(mono[0].toInt())).isAtMost(2)
        assertThat(abs(mono[mono.size - 1].toInt())).isAtMost(2)

        val left = HearingTones.burst(2_000.0, -20.0, durationSec = 0.4, ear = Ear.LEFT)
        assertThat(HearingTones.channelEnergy(left, channel = 0)).isGreaterThan(0.0)
        assertThat(HearingTones.channelEnergy(left, channel = 1)).isWithin(1e-9).of(0.0)
        val right = HearingTones.burst(2_000.0, -20.0, durationSec = 0.4, ear = Ear.RIGHT)
        assertThat(HearingTones.channelEnergy(right, channel = 0)).isWithin(1e-9).of(0.0)
        assertThat(HearingTones.channelEnergy(right, channel = 1)).isGreaterThan(0.0)

        // Louder must mean louder by exactly the amount asked for, or the
        // staircase's steps are not 2 dB.
        val quiet = HearingTones.rmsDbFs(HearingTones.burst(1_000.0, -40.0, durationSec = 0.4, ear = null))
        val loud = HearingTones.rmsDbFs(HearingTones.burst(1_000.0, -30.0, durationSec = 0.4, ear = null))
        assertThat(loud - quiet).isWithin(0.5).of(10.0)
        // Never a full-scale peak, even at the test's ceiling: no clipping in the
        // middle of the band being measured.
        val ceiling = HearingTones.burst(1_000.0, HearingTestSession.CEILING_DB, durationSec = 0.4, ear = null)
        assertThat(ceiling.max().toInt()).isLessThan(Short.MAX_VALUE.toInt())
    }

    @Test
    fun theWarbleSpreadsEnergySoACanalStandingWaveCannotPickIt() {
        val warbled = HearingTones.burst(1_000.0, -20.0, durationSec = 0.75, ear = null)
        val steady = steadyTone(1_000.0, -20.0, 0.75)
        // A steady sine of the same level puts nearly all its energy on the exact
        // frequency; the warbled test tone puts a fraction of it there. If the
        // warble ever stops working, this is where it shows up.
        val centreWarbled = HearingTones.toneStrengthAt(warbled, 1_000.0)
        val centreSteady = HearingTones.toneStrengthAt(steady, 1_000.0)
        assertThat(centreWarbled).isLessThan(centreSteady * 0.5)
        val peakHz = HearingTones.dominantFrequencyHz(warbled, 940.0, 1_060.0, stepHz = 4.0)
        assertThat(peakHz).isAtLeast(970.0)
        assertThat(peakHz).isAtMost(1_030.0)
    }

    @Test
    fun eachDeviceKeepsItsOwnCurveAndTheActiveOneFollows() {
        val buds = profileWithLoss(4_000.0, lossDb = 20.0).copy(deviceLabel = "Pixel Buds", formFactor = "in-ear")
        val saved = HearingArchive.save(HearingState(), buds, enable = true, takenAtMillis = 1_000L)
        assertThat(saved.activeKey).isEqualTo("pixel buds")
        assertThat(saved.profile?.deviceLabel).isEqualTo("Pixel Buds")
        assertThat(saved.devices).hasSize(1)
        assertThat(saved.enabled).isTrue()

        // Re-running one band on the same device updates that device's entry and
        // keeps the other seven, which is the point of merging rather than saving.
        val single = HearingProfile(
            bands = listOf(HearingBand(4_000.0, 6.0, 6.0)),
            deviceLabel = "Pixel Buds",
        )
        val reread = HearingArchive.save(saved, single, enable = true, takenAtMillis = 2_000L)
        assertThat(reread.profile?.bands).hasSize(8)
        assertThat(reread.devices).hasSize(1)
        assertThat(reread.profile?.bands?.first { it.frequencyHz == 4_000.0 }?.leftThresholdDb)
            .isWithin(1e-9).of(6.0)

        // A second pair gets its own row; switching to it is a lookup, not a re-fit.
        val xm5 = profileWithLoss(2_000.0, lossDb = 12.0).copy(deviceLabel = "Sony XM5")
        val two = HearingArchive.save(reread, xm5, enable = true, takenAtMillis = 3_000L)
        assertThat(two.devices).hasSize(2)
        assertThat(two.activeKey).isEqualTo("sony xm5")
        assertThat(two.profile?.bands?.first()?.frequencyHz).isEqualTo(250.0)
        assertThat(two.profile?.peakLossDb).isGreaterThan(0.0)

        val back = HearingArchive.use(two, "pixel buds")
        assertThat(back.activeKey).isEqualTo("pixel buds")
        assertThat(back.profile?.deviceLabel).isEqualTo("Pixel Buds")
        assertThat(HearingArchive.use(two, "nope")).isEqualTo(two)

        val entries = HearingArchive.entries(back)
        // Ordered by the most recent measurement, not by which one is active:
        // "use this curve" is a lookup, and the list should show what is freshest.
        assertThat(entries.map { it.label }).containsExactly("sony xm5", "pixel buds").inOrder()
        assertThat(entries.map { it.bandCount }).containsExactly(8, 8).inOrder()
        assertThat(entries.filter { it.isActive }.map { it.label }).containsExactly("pixel buds")
        assertThat(entries.all { it.usable }).isTrue()

        // Forgetting the active device falls back to the newest one left.
        val afterForget = HearingArchive.forget(back, "pixel buds")
        assertThat(afterForget.devices).hasSize(1)
        assertThat(afterForget.activeKey).isEqualTo("sony xm5")
        assertThat(afterForget.profile?.deviceLabel).isEqualTo("Sony XM5")
        assertThat(HearingArchive.forget(afterForget, "sony xm5")).isEqualTo(HearingState())
    }

    @Test
    fun labelsThatDifferOnlyInCaseOrSpacingAreOneDevice() {
        val a = profileWithLoss(4_000.0, lossDb = 20.0).copy(deviceLabel = "Pixel Buds")
        val b = profileWithLoss(4_000.0, lossDb = 21.0).copy(deviceLabel = "  pixel   BUDS ")
        val state = HearingArchive.save(HearingArchive.save(HearingState(), a, true, 1L), b, true, 2L)
        assertThat(state.devices).hasSize(1)
        // The freshest reading wins on the band that was re-run.
        assertThat(state.profile?.bands?.first { it.frequencyHz == 4_000.0 }?.leftThresholdDb)
            .isWithin(1e-9).of(21.0)
        // No device name at all still stores a curve, under its own key.
        val unlabelled = HearingArchive.save(HearingState(), a.copy(deviceLabel = ""), true, 1L)
        assertThat(unlabelled.activeKey).isEqualTo(HearingArchive.ANY_DEVICE)
        assertThat(HearingArchive.displayLabel(unlabelled.activeKey)).isEqualTo("any device")
        assertThat(HearingArchive.entries(unlabelled).single().label).isEqualTo("any device")
    }

    @Test
    fun aDeviceNeverMeasuredIsWarnedAboutNotQuietlyDropped() {
        val state = HearingArchive.save(
            HearingState(),
            profileWithLoss(4_000.0, lossDb = 20.0).copy(deviceLabel = "Pixel Buds"),
            enable = true,
            takenAtMillis = 1L,
        )
        assertThat(HearingArchive.isMismatched(state, "Sony XM5")).isTrue()
        assertThat(HearingArchive.isMismatched(state, "Pixel Buds")).isFalse()
        assertThat(HearingArchive.isMismatched(state, "  pixel   buds ")).isFalse()
        // An unknown current device (the dialog has not polled yet) must not nag.
        assertThat(HearingArchive.isMismatched(state, "")).isFalse()
        // And switching devices on the output changes nothing about the correction:
        // the curve keeps applying, the UI just says where it came from.
        assertThat(HearingArchive.isMismatched(state.copy(enabled = false), "Sony XM5")).isFalse()
        assertThat(state.profile?.bands).hasSize(8)
    }

    private fun steadyTone(frequencyHz: Double, levelDbFs: Double, durationSec: Double): ShortArray {
        val frames = (durationSec * HearingTones.SAMPLE_RATE).toInt()
        val amplitude = 10.0.pow(levelDbFs / 20.0) * Short.MAX_VALUE
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val value = (amplitude * kotlin.math.sin(2.0 * PI * frequencyHz * f / HearingTones.SAMPLE_RATE))
                .toInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out[f * 2] = value
            out[f * 2 + 1] = value
        }
        return out
    }

}
