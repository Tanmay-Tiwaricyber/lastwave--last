#!/bin/bash
# Two gradle runs, two logs (gradle_run.sh truncates its own log). Poll the last line
# of /home/user/vapk.log for ALLDONE; test results are in /home/user/vtests.log.
bash /home/user/gradle_run.sh /home/user/vtests.log :app:testDebugUnitTest \
  --tests "com.lastwave.app.data.update.*" --tests "com.lastwave.app.data.download.*"
bash /home/user/gradle_run.sh /home/user/vapk.log :app:assembleDebug
echo "ALLDONE tests=$(grep -c '^e: ' /home/user/vtests.log) apk=$(grep -m1 'BUILD ' /home/user/vapk.log)" >> /home/user/vapk.log
