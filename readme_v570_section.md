## What's new in v5.7.0 — sing a line, get the song

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
