#!/usr/bin/env bash
# Builds GoogleTvBridge.apk (see AndroidManifest.xml), signed with Free My TV's key. Same tools as ../../build.sh.
# Install on a Philco after removing Google TV: adb uninstall com.google.android.videos; adb install GoogleTvBridge.apk
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Set ANDROID_HOME (see the README)."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
CPSEP=:; BAT=; case "$(uname -s 2>/dev/null)" in MINGW*|MSYS*|CYGWIN*) CPSEP=";"; BAT=.bat ;; esac
rm -rf out && mkdir -p out/classes
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 33
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ" -encoding UTF-8 -d out/classes \
  $(find src -name '*.java')
"$BT/d8$BAT" --release --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')
if command -v zip >/dev/null; then (cd out && zip -qj base.apk classes.dex)
else "${JAVA_HOME:+$JAVA_HOME/bin/}jar" -u -M -f out/base.apk -C out classes.dex; fi
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk
"$BT/apksigner$BAT" sign --ks ../../freemytv.keystore --ks-pass pass:freemytv --key-pass pass:freemytv \
  --out GoogleTvBridge.apk out/aligned.apk
echo "Built GoogleTvBridge.apk"
