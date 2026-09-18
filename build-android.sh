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
KEYSTORE=robofight.keystore
KS_PASS=robofight123
ALIAS=robofight

# clean
rm -rf build
mkdir -p build

echo "[1/7] aapt2 compile res…"
"$AAPT2" compile --dir app/src/main/res -o build/res.zip

echo "[2/7] aapt2 link (base.apk)…"
"$AAPT2" link -o build/base.apk \
  --manifest app/src/main/AndroidManifest.xml \
  -I "$ANDROID_JAR" \
  build/res.zip

echo "[3/7] kotlinc (engine + app → app-cls)…"
"$JAVA" -cp "$KOTLINC_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  engine/src/robofight/isa/ISA.kt \
  engine/src/robofight/assembler/Assembler.kt \
  engine/src/robofight/vm/Vm.kt \
  engine/src/robofight/world/World.kt \
  engine/src/robofight/world/TextGrid.kt \
  engine/src/robofight/world/Presets.kt \
  engine/src/robofight/world/Simulator.kt \
  app/src/main/kotlin/robofight/android/BotSlot.kt \
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

echo "[6/7] inject classes.dex + MYBOT.asm → stage → zipalign"
"$PY" - <<'PYEOF'
import shutil, zipfile
shutil.copyfile("build/base.apk", "build/staged.apk")
with zipfile.ZipFile("build/staged.apk", "a") as z:
    z.write("build/classes.dex", "classes.dex")
    z.write("app/src/main/assets/MYBOT.asm", "assets/MYBOT.asm")
names = zipfile.ZipFile("build/staged.apk").namelist()
assert "classes.dex" in names and "assets/MYBOT.asm" in names
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
