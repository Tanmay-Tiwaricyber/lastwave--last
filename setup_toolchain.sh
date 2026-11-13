#!/usr/bin/env bash
set -e
mkdir -p /home/user/.toolchain
cd /home/user/.toolchain

echo "=== JDK 21 ==="
if [ ! -d jdk21 ]; then
  curl -sSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p jdk21 && tar -xzf jdk.tar.gz -C jdk21 --strip-components=1 && rm jdk.tar.gz
fi
export JAVA_HOME=/home/user/.toolchain/jdk21
export PATH=$JAVA_HOME/bin:$PATH
java -version

echo "=== Android cmdline-tools ==="
if [ ! -d sdk/cmdline-tools/latest ]; then
  mkdir -p sdk/cmdline-tools
  curl -sSL -o cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  cd sdk/cmdline-tools && unzip -q ../../cmdline.zip && mv cmdline-tools latest && cd ../.. && rm -f cmdline.zip
fi
export ANDROID_HOME=/home/user/.toolchain/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

echo "=== sdk packages ==="
yes | sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true
yes | sdk/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" \
  "ndk;28.2.13676358" "cmake;3.22.1" 2>&1 | tail -5

echo "=== done ==="
ls sdk/platforms sdk/build-tools

# ── Added after the v5.4.0 build ────────────────────────────────────────────
# The 2 GB sandbox OOM-kills the Kotlin compiler; swap is mandatory.
if ! sudo /sbin/swapon --show | grep -q /swapfile; then
  sudo fallocate -l 9G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile >/dev/null && sudo swapon /swapfile
fi
chmod +x /home/user/LastWave-native/gradlew   # exec bit is lost across snapshots
# Build with: /home/user/gradle_run.sh <log> :app:assembleRelease
