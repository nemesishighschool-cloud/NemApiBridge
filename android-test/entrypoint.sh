#!/usr/bin/env bash
set -euo pipefail

readonly SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
readonly AVD_HOME="${ANDROID_AVD_HOME:-/opt/android-avd}"
readonly SYSTEM_IMAGE="system-images;android-35;google_apis_playstore;x86_64"
readonly AVD_NAME="api-bridge-deepseek"

Xvfb "$DISPLAY" -screen 0 1440x1600x24 -nolisten tcp &
sleep 2
x11vnc -display "$DISPLAY" -rfbport 5900 -localhost -forever -shared \
    -noxdamage -nopw &
websockify --web=/usr/share/novnc 6080 localhost:5900 &

mkdir -p "$SDK_ROOT/cmdline-tools" "$AVD_HOME"

if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
    archive="/tmp/android-commandlinetools.zip"
    curl --fail --location --silent --show-error \
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" \
        --output "$archive"
    unzip -q "$archive" -d "$SDK_ROOT/cmdline-tools"
    mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
    rm "$archive"
fi

export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"
for _ in {1..100}; do printf 'y\n'; done | sdkmanager --sdk_root="$SDK_ROOT" --licenses >/dev/null
sdkmanager --sdk_root="$SDK_ROOT" --install \
    "platform-tools" \
    "platforms;android-35" \
    "emulator" \
    "$SYSTEM_IMAGE"

if [[ ! -d "$AVD_HOME/$AVD_NAME.avd" ]]; then
    printf 'no\n' | avdmanager create avd \
        --name "$AVD_NAME" \
        --package "$SYSTEM_IMAGE" \
        --device pixel_6 \
        --force
fi

emulator -avd "$AVD_NAME" \
    -gpu swiftshader \
    -no-audio \
    -no-boot-anim \
    -no-snapshot \
    -no-metrics &
emulator_pid=$!

adb start-server
timeout 300 adb wait-for-device
deadline=$((SECONDS + 300))
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if (( SECONDS >= deadline )); then
        echo "Android emulator did not finish booting within five minutes." >&2
        exit 1
    fi
    sleep 2
done

echo "Android emulator is ready; noVNC is available through the private Codespaces port."
wait "$emulator_pid"
