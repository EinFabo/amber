#!/usr/bin/env bash
# Installs a minimal Android SDK and builds the debug APK.
#
# Only needed on a machine without Android Studio - a normal dev box already has an SDK and
# just runs ./gradlew :app:assembleDebug. Both downloads come from dl.google.com, so this
# fails in sandboxes where that host is not allowed; that is a network policy to change, not
# a script to work around.
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CMDLINE_TOOLS_VERSION="11076708"   # cmdline-tools 12.0, pinned on purpose
PLATFORM="platforms;android-35"
BUILD_TOOLS="build-tools;35.0.0"

echo "==> SDK root: $SDK_ROOT"
mkdir -p "$SDK_ROOT/cmdline-tools"

if [ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Fetching command line tools"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

echo "==> Accepting licences"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

echo "==> Installing $PLATFORM and $BUILD_TOOLS"
"$SDKMANAGER" "platform-tools" "$PLATFORM" "$BUILD_TOOLS"

# settings.gradle.kts includes :app only when it can find an SDK.
echo "sdk.dir=$SDK_ROOT" > "$(dirname "$0")/../local.properties"

cd "$(dirname "$0")/.."
echo "==> Building"
./gradlew :core:test :app:assembleDebug

echo
echo "==> APK:"
ls -lh app/build/outputs/apk/debug/*.apk
