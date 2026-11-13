# Building LastWave — Silent Programmer Edition

Toolchain: JDK 21, Android SDK (platform `android-37.0`, build-tools 36.0.0,
NDK 28.2.13676358, CMake 3.22.1). AGP 9.1.1 · Kotlin 2.4.10 · Gradle 9.3.1.

```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
export RELEASE_STORE_FILE=/absolute/path/to/silent-release-keystore.jks
export RELEASE_STORE_PASSWORD='…'
export RELEASE_KEY_ALIAS=tanmay_release
export RELEASE_KEY_PASSWORD='…'

./gradlew :app:assembleRelease          # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest        # unit tests (debug variant only)
```

Notes

- `RELEASE_STORE_FILE` must be an absolute path; the build refuses to sign a
  release with the debug key, and even `compileReleaseKotlin` needs the
  signing variables set.
- Memory: the Kotlin compiler and R8 each want roughly 3 GB of heap for this
  module. On a small machine add swap and pass
  `-Pkotlin.daemon.jvmargs=-Xmx3000m -Dorg.gradle.jvmargs=-Xmx3200m`.
- The Room schema is at version 17. `migration15To16` in
  `di/DatabaseModule.kt` creates `listen_events` and `wrapped_markers`
  (LastWave Wrapped, v5.4.0); `migration16To17` creates `melody_signatures`
  (sing to find, v5.7.0) — cached melodies, purely derived data, so an empty
  table only means the next "Read tunes" pass recomputes.
- v5.5.0 and v5.6.0 add no schema change (Room was still at 16), and neither does
  v5.8.0: the hearing profile lives in its own DataStore preferences file
  (`hearing_prefs`, key `hearing_state`, one JSON `HearingState`) rather than in
  Room, because it is one small blob per install and it has no queries — the
  app never searches profiles, it only reads the current one at startup.
  Deleting that preferences file is the whole reset.
- `./gradlew :app:testDebugUnitTest --tests '*HearingProfileTest*'` runs the 22
  hearing-profile tests. Everything in them is synthesised or simulated: a
  listener with a fixed threshold per band is driven through the real staircase
  (`ThresholdStaircase`), the real loss-to-curve maths (`HearingProfile`), the
  real biquad fitter (`PeqFitter`) and the real blend rules (`HearingBlender`),
  and the tone generator is checked against its own spectrum. No test here can
  say whether a human can hear the tone, whether a given headset keeps the
  unused channel silent, or whether the pacing feels right — those need a phone
  in a quiet room.
- `./gradlew :app:testDebugUnitTest --tests '*MelodyDspTest*'` runs the 12
  sing-to-find tests: an FFT checked against a direct transform, a sung line
  recovered from `PitchTracker`, a melody read out of a full arrangement by
  `MelodyTracker`, and 24 hummed variants of four songs (different keys,
octaves and tempos) ranked by `MelodyMatcher` against the set of arrangements
  the tracker could read at all — at least 20 of the 24 must find their own song. Everything is
  synthesised PCM in the test file — no fixtures, no microphone, no decoder.
  `./gradlew :app:testDebugUnitTest --tests '*Wrapped*'` runs all 43 recap
  tests. Two of them are render tests: `WrappedPosterRenderTest` writes every
  share poster to `app/build/wrapped-posters/`, and
  `WrappedStorySnapshotTest` draws each story chapter under Robolectric with
  the animation clock frozen to whatever you pass, writing frames to
  `app/build/wrapped-story/`. Both let the layouts be checked without a
  device; the story one is where the shipped preview image came from.
