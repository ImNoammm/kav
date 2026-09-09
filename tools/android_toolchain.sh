#!/usr/bin/env bash
# Fetch a JDK and the Android SDK into ~/Android. No root; rm -rf ~/Android undoes it.
set -euo pipefail

ROOT="$HOME/Android"; SDK="$ROOT/Sdk"; JDK="$ROOT/jdk"
API=35
BUILD_TOOLS=35.0.0

mkdir -p "$ROOT"

step(){ printf '\n=== %s ===\n' "$*"; }

step "JDK 21"
if [ ! -x "$JDK/bin/javac" ]; then
  curl -fL --retry 3 -o /tmp/kav-jdk.tgz \
    "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  rm -rf /tmp/kav-jdkx && mkdir -p /tmp/kav-jdkx
  tar -xzf /tmp/kav-jdk.tgz -C /tmp/kav-jdkx
  rm -rf "$JDK"; mv /tmp/kav-jdkx/jdk-* "$JDK"
  rm -rf /tmp/kav-jdk.tgz /tmp/kav-jdkx
fi
export JAVA_HOME="$JDK"; export PATH="$JAVA_HOME/bin:$PATH"
java -version 2>&1 | head -2

step "Android command-line tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -fL --retry 3 -o /tmp/kav-cmdline.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  rm -rf /tmp/kav-cmdx && mkdir -p /tmp/kav-cmdx
  unzip -q /tmp/kav-cmdline.zip -d /tmp/kav-cmdx
  mkdir -p "$SDK/cmdline-tools"; rm -rf "$SDK/cmdline-tools/latest"
  mv /tmp/kav-cmdx/cmdline-tools "$SDK/cmdline-tools/latest"
  rm -rf /tmp/kav-cmdline.zip /tmp/kav-cmdx
fi
export ANDROID_HOME="$SDK"; export ANDROID_SDK_ROOT="$SDK"
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
"$SDKM" --version

step "licences"
yes 2>/dev/null | "$SDKM" --licenses >/dev/null 2>&1 || true

step "SDK packages"
"$SDKM" --install \
  "platform-tools" "platforms;android-$API" "build-tools;$BUILD_TOOLS"

step "done"
echo "JAVA_HOME=$JDK"
echo "ANDROID_HOME=$SDK"
du -sh "$ROOT" 2>/dev/null || true
