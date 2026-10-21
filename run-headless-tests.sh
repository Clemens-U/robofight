#!/usr/bin/env bash
# RoboFight headless verification — no Gradle, no network.
#
# Compiles engine main + test with the standalone kotlinc (same toolchain the
# offline APK build uses), then runs every suite via its main() entry point:
#   robofight.TestsKt            — core engine (assembler/VM/world/fights)
#   robofight.ArenaTestsKt       — five arenas: geometry, walls, fight matrix
#   robofight.CodecTestsKt       — challenge-code format (§12.2: CRC, base32,
#                                  corpus, fuzz, determinism, disasm round-trip)
#   robofight.ResponseChainTestKt— §8 response chain: A wins → response →
#                                  B wins → second response, all hops
#                                  replay deterministically
# Stage 5's acceptance gate re-runs this same script.
set -euo pipefail
cd "$(dirname "$0")"

# Resolve a JDK: $JAVA_HOME if set, else Android Studio's bundled JBR.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="C:/Program Files/Android/Android Studio/jbr/bin/java.exe"
fi
[ -x "$JAVA" ] || { echo "no JDK found (set JAVA_HOME)" >&2; exit 1; }

KOTLINC_LIB="C:/Users/Clemens/tools/kotlinc/lib"
GRADLE_CACHE=$(cygpath -m "$HOME/.gradle/caches/modules-2/files-2.1")
JUNIT_API=$(find "$GRADLE_CACHE/org.junit.jupiter/junit-jupiter-api" -name 'junit-jupiter-api-*.jar' ! -name '*sources*' | head -1)
JUNIT_COMMON=$(find "$GRADLE_CACHE/org.junit.platform/junit-platform-commons" -name 'junit-platform-commons-*.jar' ! -name '*sources*' | head -1)
OPENTEST=$(find "$GRADLE_CACHE/org.opentest4j/opentest4j" -name 'opentest4j-*.jar' ! -name '*sources*' | head -1)
for j in "$JUNIT_API" "$JUNIT_COMMON" "$OPENTEST"; do
  [ -n "$j" ] && [ -f "$j" ] || { echo "missing junit jar: $j" >&2; exit 1; }
done

case "$OSTYPE" in
  msys*|cygwin*|mingw*) CP_SEP=';' ;;
  *) CP_SEP=':' ;;
esac

OUT=build-headless
rm -rf "$OUT"
mkdir -p "$OUT"

echo "== compiling engine (main + test) with kotlinc =="
"$JAVA" -cp "$KOTLINC_LIB/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  $(find engine/src/main engine/src/test -name '*.kt') \
  -classpath "$JUNIT_API$CP_SEP$JUNIT_COMMON$CP_SEP$OPENTEST" \
  -jvm-target 11 -nowarn \
  -d "$OUT"

CP="$OUT$CP_SEP$KOTLINC_LIB/kotlin-stdlib.jar"

run_suite() {
  echo
  "$JAVA" -cp "$CP" "$1"
}

run_suite robofight.TestsKt
run_suite robofight.ArenaTestsKt
run_suite robofight.CodecTestsKt
run_suite robofight.FightMatrixKt
run_suite robofight.ResponseChainTestKt

echo
echo "== ALL HEADLESS SUITES GREEN =="
