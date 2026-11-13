# LastWave — Silent Programmer Edition

**v5.8.0** — **A four-minute tone test that turns your ears into an EQ curve.**
Run it in a quiet room with any headphones: LastWave finds how much louder each
frequency has to be for *you* to hear it, and lifts the bands that came out
quiet — half-gain, capped, blended into AutoEQ rather than replacing it. No
microphone, no cloud, nothing clinical about it: it corrects the difference
between bands, which is exactly what an EQ can act on. The previous release,
**v5.7.0**, added sing-to-find: hum eight notes and it finds which of your own
songs you meant, read on the device from audio you already have — no server, no
upload, no fingerprint database. Built on the v5.3.0 redesign ported from
[txt4nm4y.vercel.app](https://txt4nm4y.vercel.app). All developer / credit
information reads **Tanmay aka Silent Programmer**.

## Files

| File | What it is |
|---|---|
| **`LastWave-Silent-Programmer-v5.8.0.apk`** | Latest release APK (37 863 756 bytes, signed) |
| `hearing-preview-v5.8.0.html` | The hearing profile's three states — before, during, result |
| `LastWave-Silent-Programmer-v5.7.0.apk` | Previous build, kept as a fallback |
| `sing-to-find-preview-v5.7.0.html` | Sing-to-find's three states — ready, listening, found |
| `wrapped-story-v5.6.0.png` | All 15 story chapters, rendered by the app's own Compose code with sample data |
| `wrapped-posters-v5.5.0.png` | The three share posters, also rendered by the app's drawing code |
| `design-preview-v5.3.0.html` | Interactive mockup of the redesigned UI |
| `CHECKSUMS.txt` | SHA-256 of every APK |
| `silent-release-keystore.jks` | Signing key (alias `tanmay_release`) |
| `source/` | Full source tree + `BUILDING.md` |

## APK details

- **Package:** `com.lastwave.app` · **Version:** 5.8.0 (versionCode 38)
- **Min Android:** 10 (SDK 29) · **Target:** 35 · **Compile:** 37
- **ABIs:** arm64-v8a, armeabi-v7a, x86, x86_64
- **Signed (v3):** `CN=Tanmay, OU=Silent Programmer, O=Silent Programmer, L=Kanpur, C=IN`
  — same key as every earlier build, so it installs **over** them and keeps your data.
- **Permissions:** unchanged by this release. The hearing test needs no microphone
  and no new permission: it plays tones and reads two taps.

SHA-256 `5f8bce95b234d1385d31f74811525ad4ee66ec2ad4203151f54ff317eb59c0dc` — the previous build `v5.7.0` keeps
SHA-256 `cee8f53a101c0ba50a204fd39681b627ab062ca4f6f9cc85033344d9f02dcca8`. Older builds listed here for
reference, their APKs not shipped in this bundle: `v5.6.0`
`020bcf470b004b0f29528e696d87acee6358179cd75e48ec69d587f4a9b88cd8`, `v5.5.0`
`1eee8a5071c4282ad22f28e0a47500f4f93d35abe8c92d63eaf1d92c035a2d35`. Everything here is signed
with the same key, so any of them installs over the others and keeps your data.

Verified before shipping: `zipalign -c 4` clean, `apksigner verify` on the v3 signature with
certificate digest `22fc135d…` identical to v5.7.0's, and `aapt2 dump badging` reporting
`versionCode='38' versionName='5.8.0'`.

---

## What's new in v5.8.0 — your ears are not the headphones' fault

**Settings → Audio & output → Hearing profile.** It is also in the settings search, under
“hearing”.

Four minutes with a pair of headphones in a quiet room turns into a small gain curve that
LastWave adds to its EQ chain and keeps using until you switch it off — the bands you hear less
well lifted a little, the bands you hear fine left alone.

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
  maths, the caps, the fitter and the storage are covered by 22 JVM tests; **audibility, pacing,
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

---

## What was new in v5.7.0 — sing a line, get the song

**Settings → Data → Voice search → Sing to find**

Hum eight notes into the phone and LastWave plays the song you meant, starting
at the phrase you sang. That is the whole screen: a microphone button, a meter,
and a list.

### What it searches, and what it does not

It searches **the audio on this device** — your downloads and imported music
files — not YouTube's catalogue. A real "any song on the internet from a hum"
needs a fingerprint service with a recorded fingerprint of millions of tracks
behind it (Shazam's whole product is that database); nothing like it exists for
a third-party app to call, and LastWave sends no audio anywhere. So the matching
happens at home, against what you actually have:

- First time, tap **Read tunes**: the app decodes each song to PCM and writes
  down its melody — one pass per song, order of a second or two of CPU each
  (measured on a desktop JVM, not on your phone), cached in the app's
  database, so only the first search after a download costs anything.
  Songs with no singable line (talk, some instrumentals) are recorded as
  "nothing found" so they are never re-decoded.
- A song you have not downloaded will not be found. Say so plainly: this is a
  library search, and the screen tells you how many songs have been read.
- **Microphone permission** is new, and used only while the button is held. The
  audio is turned into a list of note lengths on the device, then dropped —
  nothing is stored, nothing is uploaded.

### How it decides

Singing a song is not whistling a recording, so nothing in this comparison works
on raw sound. What gets compared is the *tune*: the notes you sang, as a
starting pitch and the semitone steps between them, each with how long it was
held.

1. **Your voice** is tracked at 16 kHz with a YIN pitch follower — autocorrelation
   with parabolic peak refinement, a harmonic check, and an octave rule that
   prefers the line a person is actually singing over the one an octave below it.
   The trace is median-filtered, then cut into notes; runs under ~0.13 s are
   slides and consonants, not notes.
2. **Each song** is read from its decoded PCM the harder way, because a mix is
   not one voice. Per frame, the spectrum loses a running floor across
   frequency *and* across time — the temporal half is what matters, since a bass
   line and a held pad sit in the same bins for a whole bar while a melody
   moves, so subtracting what sustains is what leaves the tune behind. Harmonic
   stacks are then scored on a 2-cent log grid (divided by how many harmonics
   each candidate can collect, so a low candidate cannot win on count alone),
   the pitch is walked back onto the notes the tune can visit with a Viterbi
   pass, and the result is cut into notes the same way.
3. **Alignment** is a Needleman–Wunsch walk over the two interval sequences: the
   phrase may start and end anywhere in the song, a song note skipped costs less
   than a sung note unmatched, a step off by one semitone is nearly free and one
   off by five is as bad as any other five. Cost per sung note turns into the
   percentage you see through `exp(-cost / 2.4)`.
4. A **pitch-class histogram** (twelve bins, octave-folded) prunes the library
   to a shortlist first, so a 2,000-song library costs milliseconds rather than
   a few seconds.

Key, octave and tempo are all thrown away on purpose: it is your intervals the
app listens to, which is why singing a song higher than the record, or slower,
or both, still finds it.

**It plays the answer on its own** only when all three hold: a match above 86 %,
*every* note you sang accounted for, and a clear lead over the runner-up. That
sounds strict and it is — anything looser started confidently playing the wrong
song in testing. Otherwise the list stays on screen and you tap.

Below the button the app prints the notes it heard (`A4 · C5 · B4 …`). When the
answer is wrong, seeing the transcription at least tells you whether it heard the
tune and mis-picked the song, or never caught the tune at all.

### How this build was checked

1,105 JVM unit tests were run. The melody work is covered by 12 new tests in
`MelodyDspTest`, all passing, and the 43 Wrapped tests still pass. Seven tests
in unrelated suites were already red before this feature (a mockk setup quirk in
`MoodsParserTest`, an assertion still expecting the pre-rebrand GitHub URL in
`DiscordPresenceTest`, and similar in lyrics/artwork/playlist helpers); they are
untouched here, and none of them exercise melody, matching or database code.

| Test | What it pins down |
|---|---|
| `theFftAgreesWithADirectTransform` | the hand-rolled radix-2 FFT matches a naive DFT bin for bin |
| `aViterbiPassRemovesSpikesButKeepsTheTune` | three wild frames are corrected, the melody is not flattened |
| `aSungLineComesOutOfTheVoiceTrackerAsItsOwnTune` | 8 synthesised sung notes come back as those 8 notes, ±1.2 semitones |
| `notesShorterThanAHandfulOfFramesAreIgnored` | a 2-frame blip is not a note |
| `aTuneIsReadOutOfAMixWithABassUnderIt` | the melody is recovered from full arrangements (melody + bass a twelfth below + two chord pads + noise ticks) for at least 6 of 8 |
| `intervalsForgetTheKeyAndRememberTheShape` | transposing a tune leaves the step sequence identical |
| `aHummedTuneFindsItsOwnSongAmongEight` | 24 sung variants (±key, ±octave, ±tempo) rank their own song first — at least 20, and the misses are printed |
| `aTuneNobodyHasIsNotReportedAsAMatch` | tunes that are *not* in the library are never announced as a hit |
| `aPhraseFromTheMiddleOfASongStillFindsIt` | singing notes 4–7 of a tune still matches, and reports the right start time |
| `aShortOrEmptyTuneIsRefused` | two notes is not enough to identify anything, and the app says so |
| `signaturesRoundTripThroughTheirBytes` | what is cached in the database is what the matcher uses |
| `whiteningLeavesASustainedPadBehind` | a chord alone yields no tune; the same chord with a moving line does |

The 24-variant ranking test is the one worth reading: it is the difference
between a demo and a feature. The test prints its own misses, and when there are
any they are the same trick — a line sung up an octave *and* a few semitones off
— where a human listener would also hesitate; the right song is still in the
list, just not at the top.

### Under the hood

- `playback/melody/PitchTracker.kt` — YIN follower (existed; gains a `VOICE` config and a
  tunable band prior)
- `playback/melody/MelodyTracker.kt` — new: FFT, harmonic-stack salience with a temporal
  floor, Viterbi contour walk, note segmentation, interval extraction
- `playback/melody/MelodyMatcher.kt` — rewritten: interval-sequence Needleman–Wunsch,
  pitch-class prefilter, verdict rules, byte codec
- `data/match/MelodyIndex.kt` — new: decodes each song once through `CapturingAudioSink`,
  computes and caches its tune, ranks a hummed phrase
- `data/local/db/MelodySignatureEntity.kt` — new table; **database schema 16 → 17**,
  one `CREATE TABLE`, purely derived data (deleting it just means the next "Read
  tunes" pass redoes the work)
- `ui/match/SingToFindViewModel.kt` + `SingToFindScreen.kt` — new screen; `AudioRecord` at
  16 kHz with a smoothed level meter and a 12-second cap
- `AndroidManifest.xml` — adds `RECORD_AUDIO`; microphone is `required="false"` so
  the app still installs on hardware without one
- Settings → Data gains a **Voice search** section; navigation gains route
  `sing_to_find`

### Not tested on a real device

Same caveat as every previous build, and it matters more here than it did for
Wrapped: **no microphone, no emulator is available in this sandbox**, so
`AudioRecord`, the permission dialog, playback hand-off and the seek to the
matched phrase have never run on hardware. What *is* verified is the part that
does the thinking — pitch tracking, melody extraction from a mix, and the
matching — on synthesised audio, in JUnit, on the JVM. Expect the first real
hum to work or fail on capture and levels, not on the matcher.

### Good to know

- **Only downloaded / imported audio can be found.** Streaming-only songs have
  no file to read a melody from, and the app will not decode a stream just to
  index it.
- Tap **Read tunes** after downloading more music. New songs are invisible to
  search until then (the button only ever processes what is missing).
- Singing with words works better than humming through a phone lying flat on a
  table; hold it near, like a walkie-talkie.
- A wrong answer with the right `Heard:` notes means the song is genuinely not
  in what has been read. Wrong notes means the take was too quiet or too short.
- The whole feature is offline. Turning on the microphone does not open a
  network call, and no audio is written to disk.

---

## What's new in v5.6.0 — every chapter draws itself

**Settings → Data → Listening recap → LastWave Wrapped**

Same 15 chapters, same numbers, same rules about when a chapter appears — but
nothing in it is a plain bar chart any more. Each chapter now paints a picture
made from your own listening, and each one moves.
`wrapped-story-v5.6.0.png` is the whole story, frame by frame.

### What each chapter draws

| Chapter | The picture |
|---|---|
| Sound fingerprint | Your own radial signature: 24 spokes for the hours, 7 arcs for the weekdays. The spokes breathe with the clock. |
| Time listened | One dot per chunk of minutes — the app picks the unit (1 minute up to 1 week, never more than 144 dots) and the dots light up in order |
| Number one track | A vinyl record that turns under a fixed light, your artwork in the label, with a 64-bar equalizer ring pulsing around the rim |
| On repeat | Top 5 tracks on bars sized by play count, arriving one row at a time |
| Number one artist | A small solar system: one planet per fact, on its own orbit, moving |
| Top artists | Five bubbles, radius proportional to time together, packed so they never overlap, drifting apart as they settle |
| In days | Calendar heatmap that lights up day by day, January to December |
| Listening clock | A 24-hour clock face: bars radiating from the centre, an amber day band, a violet band for 10 pm – 5 am (the same window the "night owl" label uses), 24 hour ticks and a thin hand sweeping the face |
| Your rhythm | A level meter for the weekdays (stacked pills, so you can see how the hours add up) and a smoothed area wave for the months with your peak marked |
| Habits | A ring gauge for the share of songs you finished, with the number inside it |
| Where it started | A tilted print of the first track's cover, captioned with the date and time |
| Discovery | A night sky with one star per new artist (up to 36), joined line by line as the chapter arrives, twinkling out of step |
| Archetype | A holographic card with a sheen that travels across it: your four-letter code, your type name, and the four axes drawn as one diamond |
| Badges | Hexagonal medals — earned ones filled and catching the light, unearned ones dark with a ring showing progress |
| That's a wrap | Confetti over your archetype, your fingerprint again, and the play / share / detail buttons |

### Motion

- Each chapter has its own **backdrop**: three large drifting colour clouds plus
  a pattern — ripples, ribbons, a dot grid, stars, rays, confetti on the last
  page. The pattern is chosen from the chapter, so a calendar page feels
  different from a night-sky page.
- Nothing is a video or a GIF. One clock runs for the whole story and the
  pictures read it while they draw, so a spinning record or a breathing
  fingerprint costs no recomposition.
- Entrances are staggered: the artwork springs in with a slight overshoot, then
  each line of text and each stat row reveals in turn; numbers count up (longer
  numbers count slower so they stay readable).
- **Swipe** left or right to change chapter: the page follows your finger and
  dims as you drag, and past a sixth of the screen the drag turns the page. Tap the left edge to go
  back, the right side to go on, hold anywhere to pause (the progress segment
  under the top bar is the chapter timer). Previous / next / pause buttons and an
  "n / 15" counter are there for when you do not want to gesture. Haptics tick
  on each chapter change.
- If Android's **Remove animations** setting is on, the story is a set of
  stills: no auto-advance, no drift, nothing moving.

### The detail page got the same treatment

The scrolling page behind "See every number" now uses the same artwork instead
of plain bars: level meter for weekdays, area wave for months, radial clock for
hours, ring gauge for finished songs, the axis diamond on the archetype card and
the medal grid for badges.

### How this build was checked

- **43 unit tests pass**: 32 for the recap maths, calendar, streaks, archetype
  and badges, 3 that render every poster style, and **8 new ones that render the
  story itself**.
- The story tests drive the real composables under Robolectric with native
  canvas drawing, freeze the animation clock and the chapter entrance at chosen
  values, check that every chapter of a full year, a single month and a
  first-evening log all paint ink on screen, and write the PNGs that
  `wrapped-story-v5.6.0.png` is made from.
- Two geometry tests back the artwork with maths: the bubble packing can never
  overlap, and the dot field never needs more than 144 dots for any plausible
  amount of listening.
- The APK was built with R8, signed, and its signature, version and contents
  inspected.

### Not tested on a real device

There is no phone or emulator here. The story screen, its animations, the swipe
and hold gestures, the album artwork (the renders above use flat colour tiles,
the app loads real covers through Coil), "Play my top tracks" and the share
sheet have **not** been run on hardware. The logic is tested and the pixels are
rendered by the same code your phone will run, but nobody has tapped through it
on a phone. Keep the v5.5.0 APK until you have tried this one, and tell me what
looks off.

### Good to know

- **No data change.** v5.6.0 reads the same listening log as v5.5.0 (database
  v16), so your history carries straight over and nothing is re-collected.
- Cover art only appears where the app has a local path or URL for it; with no
  artwork the picture falls back to a colour disc, never a broken image.
- Still no "top genre": the app has no genre data.
- The new Wrapped text is English only.

---

## What was new in v5.5.0 — Wrapped, rebuilt

**Settings → Data → Listening recap → LastWave Wrapped**

### Story mode
Wrapped opened as a full-screen story. Tap the right side to go on, the
left edge to go back, hold to pause; there are also on-screen previous / next
/ pause buttons. (v5.6.0 added swipe and replaced the charts with artwork.) Numbers count up and each line arrives in turn. A chapter
only appears when there is something true to put in it, so a new install gets
a shorter story than a year of listening does.

| Chapter | What it shows |
|---|---|
| Sound fingerprint | A shape drawn from your listening: 24 spokes for the hours, 7 arcs for the weekdays |
| Time listened | Minutes or hours, how long that is end to end, average per listening day, change against the previous year or month |
| Number one track | Artwork, plays, time, share of all your listening |
| On repeat | Top 5 tracks |
| Number one artist | Time together, days you played them, your favourite track of theirs |
| Top artists | Top 5 artists |
| In days | A calendar heatmap of every day, longest streak with its dates, current streak |
| Listening clock | 24-hour chart, peak hour, after-midnight anthem, morning anthem |
| Your rhythm | Favourite weekday, weekday and month charts, biggest day and what you played on it |
| Habits | Share of songs you finished, most plays of one track in one day, the track you never skip, the one you skip most |
| Where it started | The first track of the period, with date and time |
| Discovery | Artists you had never played before, and the one that stuck |
| Archetype | See below |
| Badges | See below |
| That's a wrap | Play your top tracks, pick a poster to share, open the detail page |

### Listener archetype
Four readings of how you listen, each giving one letter:

| Axis | Measured from |
|---|---|
| **D**ay / **N**ight | Share of minutes between 8 pm and 5 am |
| **E**xplorer / **L**oyalist | How much of your time the top five artists take |
| **W**anderer / **R**epeater | Average plays per track |
| **S**kipper / **F**inisher | How often a started song is finished |

The letters make a code such as `N · L · R · F` and a name such as *Midnight
Devotee*, *Daylight Nomad*, *Midnight Curator* or *Daylight Collector* — 16
combinations. Each axis is shown as a meter, so you can see how close you are
to the other side.

### Badges
Twelve, each with progress toward it: First spin, Hour one, Full day,
Marathon (3 hours in a day), Seven straight, Thirty straight, Every weekday,
On loop (one track 10 times in a day), Fifty spins, Wide ears (50 artists),
Night shift, Early bird. They count what you did in the period you are
looking at.

### Monthly recaps
Alongside each year, "Last 12 months" and "All time", the chips now include
every month since you started (up to the last twelve), so a recap does not have to wait for December.

### Three share posters
1080×1920, picked on the last story chapter or at the bottom of the detail page:

- **Summary** — the headline number, stats, top tracks, your fingerprint and archetype.
- **Receipt** — everything as a terminal printout: `$ lastwave wrapped --2026`.
- **Year grid** — twelve month calendars shaded by listening. Offered for
  year, last-12-months and all-time recaps, not for a single month.

`wrapped-posters-v5.5.0.png` shows all three.

### Play my top tracks
Queues up to 50 of the period's top tracks, most-played first.

### Detail page
Everything in the story is also on one scrolling page that follows the app
theme, for reading at your own pace and for screen readers.

### Good to know
- **Your existing Wrapped data carries over.** v5.5.0 reads the same listening
  log v5.4.0 started; there is no database change in this version.
- Wrapped still only knows about listening since you installed v5.4.0.
- "Discovery" and the comparison with the previous year or month are hidden
  until the log reaches back far enough to make them true. On a fresh log
  every artist would count as new, so the chapter is left out.
- The archetype needs very little data to produce a result, so with fewer than
  20 plays the story labels it an early read.
- "Play my top tracks" can only queue tracks that were streamed. Local files
  have no stream id in the log and are skipped.
- If Android's "Remove animations" setting is on, the story does not move or
  advance by itself.
- The story is always dark, whatever the app theme, so it matches the posters.
- The new Wrapped text is in English only.
- Still no "top genre": the app has no genre data.

---

## What was new in v5.4.0

### LastWave Wrapped
**Settings → Data → Listening recap → LastWave Wrapped**

- Minutes/hours listened, plays, distinct tracks and artists, days active,
  longest and current streak.
- Top 5 tracks, top 5 artists, top 3 albums — ranked by time actually listened.
- A 24-hour **listening clock** with a personality (Night owl, Morning person,
  Afternoon driver, Evening listener, All-day listener).
- Switch between each calendar year, the last 12 months, and all time.
- **Share recap** renders a 1080×1920 poster and opens the Android share sheet.
- Everything is computed on the phone from your own listening. Nothing is uploaded.

**Good to know**

- Wrapped starts counting **from the moment you install v5.4.0**. Earlier
  listening was only stored as running totals with no dates, so it cannot be
  split by year — the screen says so until you have played something.
- Listens shorter than 5 seconds and podcast episodes are not counted.
- There is no "top genre" card: the app has no genre data for tracks, and I
  would rather leave it out than invent one.
- Days and streaks follow your phone's time zone (a 1 am listen counts for
  that calendar day, not the previous one).

### Sleep timer
The timer button in the full player now opens a picker:

- **Time** — 15 / 30 / 45 / 60 / 90 minutes
- **Tracks** — stop after 5 / 10 / 15 / 25 more tracks
- **Stop at end of queue** — no endless radio after the last queued track
- **Turn off**

### Under the hood
- Database v15 → v16 (two new tables). The migration is additive; existing
  data is untouched.
- Unit tests for the recap maths (`WrappedCalculatorTest`).

---

## What changed in v5.3.0 — the redesign

The reference site's visual system was reverse-engineered from its CSS
(IBM Plex Sans + IBM Plex Mono, a zinc monochrome token set, `ring-1` hairline
borders, `rounded-lg` corners, one green accent) and rebuilt as a native
Compose theme.

### New design system
| Reference design token | LastWave implementation |
|---|---|
| zinc-950/900/800 surfaces | `SpSchemeBuilder.buildSilentProgrammerDarkScheme()` |
| zinc-50/white surfaces | `SpSchemeBuilder.buildSilentProgrammerLightScheme()` |
| emerald accent (`green-400` dark / `green-600` light) | `colorScheme.primary` |
| `text-muted-foreground` (zinc-500/400) | `colorScheme.onSurfaceVariant` |
| `border` / `ring-1` hairlines | `colorScheme.outlineVariant` + `HairlineWidth` |
| `rounded-md/lg/xl` (6–16 dp) | `SilentProgrammerShapes` |
| IBM Plex Sans / Plex Mono | bundled in `res/font/`, `SilentProgrammerTypography` |
| mono uppercase eyebrows | `SpEyebrowStyle` |

### Redesigned surfaces
- **Theme engine** — new scheme pair wired into `ThemeRepository`; replaces the
  tinted Material You ramp. New toggle: **Settings → Appearance → Silent Programmer UI**
  (on by default, mutually exclusive with the lime signature theme).
- **Typography** — the whole type scale is now IBM Plex Sans with the site's tight
  negative tracking; labels/captions switch to IBM Plex Mono.
- **About card** — rebuilt as the portfolio's identity card: hairline-ringed
  avatar, "LastWave / @silent_programmer", mono version pill, a live emerald
  status dot above the developer credit.
- **Settings rows** — flat surfaces, hairline rings, muted icon chips, tighter
  16 dp corners; section headers became uppercase mono eyebrows.
- **Home hero** — greeting in tight display type, hairline rule, mono date line
  with a pulsing accent dot (mirrors the portfolio's name → rule → status hero).
- **Shelves** — semi-bold Plex section titles, "See all" chips became outline pills.
- **Navigation dock** — elevation removed, replaced by a 1px top rule; selection
  is a quiet accent-washed pill with an accent icon.
- **Hero cards** — 16 dp corners, hairline border, neutral scrim instead of the
  colored gradient wash.

### Carried over from v5.2.0
- All developer / credit info → **Tanmay aka Silent Programmer**
- In-app update system fully removed (no GitHub checks, no update banner/card)

**Note:** the Jam sync backend URL (`wss://jam.clashprojects.qd.je/ws`) is kept
on purpose — it's a working service endpoint, not a credit. Swapping it would
break Jam.

## Install

`adb install -r LastWave-Silent-Programmer-v5.7.0.apk` — or copy to the phone and
allow "install unknown apps". If any earlier Silent Programmer build is
installed, it upgrades in place (same signing key) and keeps your data. An older upstream LastWave must be uninstalled first
(different signature).

Keep `silent-release-keystore.jks` safe — every future update must be signed
with it.

Licensed GPL-3.0, like the upstream project it is built from.
