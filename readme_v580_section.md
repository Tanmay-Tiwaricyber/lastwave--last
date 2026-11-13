## What's new in v5.8.0 — your ears are not the headphones' fault

**Settings → Audio & output → Hearing profile** (it is in the settings search too, under
*hearing*), or the same entry by name: **Hearing profile**.

Four minutes with a pair of headphones in a quiet room turns into a small gain curve that
LastWave adds to the EQ chain for the rest of the session — the bands you hear less well lifted
a little, the bands you hear fine left alone.

### What the test actually does

Eight bands — 250, 500, 1 k, 2 k, 3 k, 4 k, 6 k, 8 k Hz — measured one at a time, one ear at a
time, in the order 1 k first and then ascending. Each band runs a **modified Békésy staircase**:
the same method a clinical audiometer uses, minus the calibration.

- A 0.75 s tone, a **warble** of ±30 Hz at 5 Hz, with 35 ms raised-cosine fades, 48 kHz/16-bit,
  into **one channel**; the other channel holds exact zeros.
- Start at −26 dB below full scale. **Heard → 2 dB quieter. Not heard → 1 dB louder.** When the
  answer flips three times, the band is done: the estimate is the mean of the last three flip
  points, not the last level, so one slipped tap cannot move a band.
- No answer within six seconds counts as *not heard*, which is the one assumption the test makes
  without asking — a listener who nodded off must not walk away with a curve.
- A band where nothing was ever heard, even at the ceiling, is **left out** rather than recorded as
  "no loss". It would otherwise pull the whole profile's reference down and invent a correction on
  every other band.

The tones play through their own `AudioTrack`, not through the media player. That matters:
LastWave's parametric EQ and its DynamicsProcessing bands are attached to the player's audio
session, so the test signal is untouched by the very EQ it is about to calibrate. Calibrating a
curve through the curve would have been the elegant way to make this feature lie to you.

### From thresholds to filters

A phone has no calibrated output, so nothing here is in dB SPL and this is **not an audiogram**.
What the device can tell you is how much louder one band had to be than another to sound equally
loud to you today. So the curve is relative to your own quietest band:

| Step | Rule | Why |
|---|---|---|
| Loss | `threshold − quietest band`, floored at 0 | cuts can't come from a "better than average" band |
| Ignore | losses under **5 dB** | that is measurement scatter, not hearing |
| Gain | **half** the loss | the hearing-aid convention; full compensation sounds wrong within a day |
| Taper | ×0.5 above **4 kHz** | 6 dB of treble through a sealed ear-tip is a hiss you cannot unhear |
| Cap | **+6 dB** per band | a band you cannot hear at all is not an EQ problem |
| Shape | log-interpolated, then smoothed over ≈⅓ octave | the ear works in octaves; a single spiky band would waste two of the fitter's eight filters chasing it |
| Floor | never a cut | cutting the bands that are fine is how you get a telephone |

### How it blends with AutoEQ

AutoEQ corrects the *headphone*; this corrects the *listener*. They are the same kind of object —
a curve on the fitting grid — so they are **summed before the fit**, one least-squares pass over
eight peaking filters and two shelves, and the result is coerced into the window the app already
used (−15 dB to +12 dB). The preamp is then computed from the *combined* peak, which is what keeps
a +6 dB ear correction on top of a bass lift from turning into a request the limiter has to eat.

Three consequences worth knowing:

- **Your hand-edited filters are not rewritten.** If the AutoEQ profile is Custom, refitting it to
  pay for the hearing curve would silently change numbers you typed, so there the hearing peaks are
  appended instead — and if the chain's 20-filter budget is full, the *weakest* of them are dropped.
- **AutoEQ off still works.** With no supported headphone profile the curve runs alone, which is
  the case that matters most: someone with headphones nobody measured still has ears.
- **The raw thresholds are what get stored, one set per output device** (a `hearing_prefs`
  DataStore, JSON, no database change — Room stays at schema 17). The filters are re-derived on
  every read rather than saved, because the same ear loses a few dB of bass to a slightly open
  ear-tip, so a curve measured through one seal is the wrong curve through another. Re-running
  merges into that device's entry, which is why redoing one doubtful band does not cost you the
  other seven. If you have curves saved for more than one pair, the screen lists them and lets you
  switch; if the pair in your ears was never measured, nothing is quietly switched off — the line
  above the switcher tells you the curve came from somewhere else.

### What it does not do — read this part

- **It cannot measure your hearing.** No calibrated microphone, no known output level, no sound
  booth. A flat result means "no difference between bands on this phone", **not** "your hearing is
  normal".
- **It cannot split your ears.** The EQ chain holds one filter list applied to both channels
  (`ParametricEq` has no per-channel gain), so an asymmetric result is *reported* — "your ears
  differ by X dB, which an EQ cannot split" — and never corrected. A real asymmetry is a reason to
  see someone, not a reason to reach for a curve.
- **It cannot find a dead band.** If a frequency is inaudible at every level the app will play, the
  band is dropped. The correction is a compromise across the bands it *could* measure.
- **It refuses to run above 60 % device volume.** Not a hearing-protection figure — a measurement
  one. Near maximum output Android's limiter compresses the difference between staircase steps, and
  the test would be finding the limiter's knee instead of your ear's threshold.
- **The 6 kHz and 8 kHz bands are the least reliable** on any phone: the smallest change in
  ear-tip fit moves them a few dB. That is partly why the taper above 4 kHz exists.
- **Nothing here ran on hardware.** There is no device or emulator in this build environment. The
  maths, the caps, the fitter and the storage are covered by 19 JVM tests; **audibility, pacing,
  per-channel isolation on a given Bluetooth headset, and the audio-routing calls are not**, and
  never can be from here. Expect to have to run the test more than once, and expect the first
  attempt to be the one that teaches you how quiet the room needs to be.

### How it is checked

```
app/src/test/java/com/lastwave/app/data/hearing/HearingProfileTest.kt — 22 tests, 0 failed
```

A simulated listener with a fixed threshold per band is run through the real staircase, the real
curve maths, the real `PeqFitter` and the real biquad math, and the assertions are the ones that
matter: the estimate lands within 3 dB of the simulated mask in fewer than 25 presentations; a
40 dB loss still cannot become more than +6 dB; the curve never goes negative; treble gets less than
midrange for the same loss; the fitted filters track the requested curve to within 1 dB
(`rmsError < 1.0`); summed with an HD 600 profile the whole thing still fits inside −15…+12 dB with
a preamp that keeps the response under 0 dBFS; a hand-edited 18-filter profile keeps all 18 filters
untouched and takes only the two strongest hearing peaks; the warbled burst has no click at its
start, is 10 dB louder when asked for 10 dB, sits in the 970–1030 Hz band it claims, and is exactly
silent in the ear it was not sent to; and a profile survives a round trip through storage.

The full app suite after this feature: **1 127 tests, 7 failing** — the same seven that were already
failing before it (Discord presence constants, a mockk stub in `MoodsParserTest`, text-match and
artwork cases, `LyricsSanitizerTest`, `PlaylistSuggestionsFilterTest`, `AutoMixCamelotClassifierTest`).
One more, `AddonHttpTest`, failed once under build load and passes on its own.
