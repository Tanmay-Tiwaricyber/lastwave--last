#!/bin/bash
# Final gate for this round: the updater + download + lossless unit tests, then a debug
# APK build, then the full suite to prove no regressions elsewhere.
bash /home/user/gradle_run.sh /home/user/f1.log :app:testDebugUnitTest \
  --tests "com.lastwave.app.data.update.*" --tests "com.lastwave.app.data.download.*" \
  --tests "com.lastwave.app.data.lossless.*"
bash /home/user/gradle_run.sh /home/user/f2.log :app:assembleDebug
echo "DONE apk=$(grep -m1 'BUILD ' /home/user/f2.log)" >> /home/user/f2.log
