#!/bin/sh
# Compiles the Android-free core with the Kotlin compiler bundled in Android Studio and runs the tests.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
STUDIO="${STUDIO:-/Applications/Android Studio.app/Contents}"
export JAVA_HOME="${JAVA_HOME:-$STUDIO/jbr/Contents/Home}"
KOTLINC="${KOTLINC:-$STUDIO/plugins/Kotlin/kotlinc/bin/kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
sh "$KOTLINC" "$HERE"/../android/src/main/java/com/skycop/sim/core/*.kt "$HERE/CoreTests.kt" -include-runtime -nowarn -d "$OUT/tests.jar"
"$JAVA_HOME/bin/java" -jar "$OUT/tests.jar"
