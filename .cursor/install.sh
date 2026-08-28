#!/usr/bin/env bash
# Idempotent Cloud Agent bootstrap for the VesperaHelper Android app.
# Installs the Android SDK (once), wires it up for Gradle, and warms the build.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK_ROOT"

# Pinned versions matching app/build.gradle (compileSdk/build-tools 35) and the
# Gradle 8.9 wrapper.
CMDLINE_TOOLS_VERSION="13114758"
PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"

mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "Installing Android command-line tools ($CMDLINE_TOOLS_VERSION)..."
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# Accept licenses and install the packages the build needs (no-ops when present).
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --install "platform-tools" "$PLATFORM" "$BUILD_TOOLS" >/dev/null

# Point Gradle at the SDK (local.properties is gitignored and per-machine).
if ! grep -qs "^sdk.dir=" local.properties 2>/dev/null; then
  echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties
fi

# Warm the Gradle wrapper + dependency/build caches so they land in the snapshot.
./gradlew --no-daemon assembleDebug

echo "VesperaHelper environment ready. Debug APK:"
ls -1 app/build/outputs/apk/debug/*.apk
