#!/usr/bin/env bash
# ============================================================================
#  RoboFight M3 — offline Android build (no Gradle)
#  Pipeline: aapt2 → kotlinc → jar → d8 → inject → zipalign → apksigner
#
#  Requirements (all already installed on this host):
#    - JDK (Android Studio JBR)
#    - kotlinc (kotlin-compiler.jar)
#    - Android SDK (aapt2, d8, apksigner, zipalign, android.jar)
#    - python3 (for injecting entries into the APK; zip is not on PATH)
#
#  Output: robofight.apk (signed, installable on API 24+)
#
#  Run from the repo root:
#    bash build-android.sh
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

# ---- toolchain (Windows-native paths for the native tools) ----
JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
JAVA="$JAVA_HOME/bin/java.exe"
KOTLINC_JAR='C:\Users\Clemens\tools\kotlinc\lib\kotlin-compiler.jar'
KSTD='C:\Users\Clemens\tools\kotlinc\lib\kotlin-stdlib.jar'
JAR="$JAVA_HOME/bin/jar.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"
SDK='C:/Users/Clemens/AppData/Local/Android/Sdk'
AAPT2="$SDK/build-tools/36.0.0/aapt2.exe"
ANDROID_JAR="$SDK/platforms/android-37.0/android.jar"
D8="$SDK/build-tools/36.0.0/lib/d8.jar"
APKSIGNER="$SDK/build-tools/36.0.0/lib/apksigner.jar"
ZIPALIGN="$SDK/build-tools/36.0.0/zipalign.exe"
PY=/c/Users/Clemens/AppData/Local/hermes/hermes-agent/venv/Scripts/python
KEYSTORE="${ROBOFIGHT_KEYSTORE:-robofight.keystore}"
ALIAS="${ROBOFIGHT_ALIAS:-robofight}"
KEYSTORE_PASS_FILE="${ROBOFIGHT_KEYSTORE_PASS_FILE:-keystore.pass}"

# ---- keystore password (SECRET — never hardcoded in this public repo) ----
# Resolve it from the environment first, then from a local git-ignored file.
# A second / CI system with neither gets an immediate, loud failure here
# (before the long offline build starts) telling it to set ROBOFIGHT_KS_PASS.
if [ -n "${ROBOFIGHT_KS_PASS:-}" ]; then
  KS_PASS="$ROBOFIGHT_KS_PASS"
elif [ -f "$KEYSTORE_PASS_FILE" ]; then
  KS_PASS="$(head -n 1 "$KEYSTORE_PASS_FILE" | tr -d '\r\n')"
fi
if [ -z "${KS_PASS:-}" ]; then
  echo "ERROR: keystore password is missing (ROBOFIGHT_KS_PASS is not set)." >&2
  echo "  This secret is intentionally NOT stored in the (public) repo." >&2
  echo "  Set it for this build, then re-run:" >&2
  echo "    export ROBOFIGHT_KS_PASS='<store password>'" >&2
  echo "  …or place it in a git-ignored local file:  $KEYSTORE_PASS_FILE" >&2
  echo "  (You also need the keystore file itself present at: $KEYSTORE)" >&2
  exit 1
fi

# clean
rm -rf build
mkdir -p build

echo "[1/7] aapt2 compile res…"
"$AAPT2" compile --dir app/src/main/res -o build/res.zip

echo "[2/7] aapt2 link (base.apk)…"
# Standalone aapt2 still requires a manifest package, while AGP 9 rejects the
# legacy package attribute in the source manifest. Add it only to a temporary
# manifest used by this offline pipeline.
sed 's/<manifest /<manifest package="robofight.android" /' \
  app/src/main/AndroidManifest.xml > build/AndroidManifest.xml
"$AAPT2" link -o build/base.apk \
  --manifest build/AndroidManifest.xml \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  -I "$ANDROID_JAR" \
  build/res.zip

echo "[3/7] kotlinc (engine + app → app-cls)…"
"$JAVA" -cp "$KOTLINC_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  engine/src/main/kotlin/robofight/isa/ISA.kt \
  engine/src/main/kotlin/robofight/assembler/Assembler.kt \
  engine/src/main/kotlin/robofight/vm/Vm.kt \
  engine/src/main/kotlin/robofight/world/World.kt \
  engine/src/main/kotlin/robofight/world/TextGrid.kt \
  engine/src/main/kotlin/robofight/world/Presets.kt \
  engine/src/main/kotlin/robofight/world/Simulator.kt \
  app/src/main/kotlin/robofight/android/BotSlot.kt \
  app/src/main/kotlin/robofight/android/BotFiles.kt \
  app/src/main/kotlin/robofight/android/Firmware.kt \
  app/src/main/kotlin/robofight/android/RunController.kt \
  app/src/main/kotlin/robofight/android/ArenaView.kt \
  app/src/main/kotlin/robofight/android/MainActivity.kt \
  -classpath "$ANDROID_JAR" \
  -jvm-target 11 \
  -d build/app-cls

echo "[4/7] package classes → app.jar"
(cd build/app-cls && "$JAR" cf ../app.jar .)

echo "[5/7] d8 dex (engine + kotlin-stdlib → classes.dex)…"
# d8 (R8) is noisy about newer Kotlin metadata — that's a warning, not an error.
# Capture full output to a log so a real failure is still diagnosable.
"$JAVA" -cp "$D8" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" \
  --output build \
  build/app.jar "$KSTD" >build/d8.log 2>&1 || {
    echo "d8 failed — last 30 lines of build/d8.log:"
    tail -30 build/d8.log
    exit 1
  }
test -f build/classes.dex || { echo "d8 produced no classes.dex"; exit 1; }
echo "   classes.dex: $(du -h build/classes.dex | cut -f1)"

echo "[6/7] inject classes.dex + assets + firmware → stage → zipalign"
"$PY" - <<'PYEOF'
import os, shutil, zipfile
shutil.copyfile("build/base.apk", "build/staged.apk")
with zipfile.ZipFile("build/staged.apk", "a") as z:
    z.write("build/classes.dex", "classes.dex")
    # regular app assets (MYBOT.asm, fonts, …)
    for root, _, files in os.walk("app/src/main/assets"):
        for f in files:
            src = os.path.join(root, f)
            arc = os.path.relpath(src, "app/src/main/assets").replace(os.sep, "/")
            z.write(src, "assets/" + arc)
    # repo firmware/ dir → assets/firmware/ (seed source for preset files;
    # the app reads them at first launch and stores them in the SQLite db)
    if os.path.isdir("firmware"):
        for f in sorted(os.listdir("firmware")):
            if f.endswith(".asm"):
                z.write(os.path.join("firmware", f), "assets/firmware/" + f)
names = zipfile.ZipFile("build/staged.apk").namelist()
assert "classes.dex" in names and "assets/MYBOT.asm" in names
assert any(n.startswith("assets/fonts/") for n in names), "font asset missing"
assert sum(1 for n in names if n.startswith("assets/firmware/") and n.endswith(".asm")) >= 5, "firmware assets missing"
print("   entries:", names)
PYEOF
"$ZIPALIGN" -f 4 build/staged.apk build/aligned.apk

echo "[7/7] apksigner sign"
if [ ! -f "$KEYSTORE" ]; then
  "$KEYTOOL" -genkeypair \
    -keystore "$KEYSTORE" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=RoboFight, OU=Dev, O=RoboFight, L=Berlin, ST=Berlin, C=DE"
fi
"$JAVA" -cp "$APKSIGNER" com.android.apksigner.ApkSignerTool sign \
  --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass pass:"$KS_PASS" --key-pass pass:"$KS_PASS" \
  --out robofight.apk build/aligned.apk

echo "verify…"
"$JAVA" -cp "$APKSIGNER" com.android.apksigner.ApkSignerTool verify --verbose robofight.apk

echo
echo "BUILD OK → robofight.apk"
ls -la robofight.apk
