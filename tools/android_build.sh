#!/usr/bin/env bash
# Build (and optionally install) the Kav APK using the userspace toolchain that
# tools/android_toolchain.sh lays down. No root, no system packages.
set -euo pipefail
export JAVA_HOME="$HOME/Android/jdk"
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
cd "$(dirname "$0")/../android"
exec ./gradlew "$@"
