#!/usr/bin/env bash
# Shared by local builds, PR validation and signed APK builds.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
if [[ ! -f "$ANDROID_JAR" && -n "$SDK" && -f "$SDK/platforms/android-37/android.jar" ]]; then
  ANDROID_JAR="$SDK/platforms/android-37/android.jar"
fi
R8_JAR="${R8_JAR:-$SDK/build-tools/35.0.0/lib/d8.jar}"
OUT="${OUT:-$ROOT/etax/engine/build/dex}"
WORK="${WORK:-$ROOT/etax/engine/build/work}"
ANDROID_JAR="$ANDROID_JAR" R8_JAR="$R8_JAR" OUT="$OUT" WORK="$WORK" \
  sh "$ROOT/etax/engine/build_engine.sh"

BUILD="$(sed -n 's/.*static final String BUILD = "\([^"]*\)".*/\1/p' "$ROOT/etax/engine/src/VDrive.java")"
[[ -n "$BUILD" ]] || { echo "Cannot read engine BUILD" >&2; exit 1; }
ASSETS="$ROOT/app/src/main/assets"
mkdir -p "$ASSETS/eta-engine"
cp "$OUT/classes.dex" "$ASSETS/vdrive.dex"
printf '%s' "$BUILD" > "$ASSETS/vdrive.version"
for script in vd.sh install_vdrive.sh check_pair.sh; do
  cp "$ROOT/etax/engine/$script" "$ASSETS/eta-engine/$script"
done
echo "Embedded engine: $BUILD"
sha256sum "$ASSETS/vdrive.dex"
