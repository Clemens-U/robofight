# M3 — Native Android (Option A) — HANDOFF

> Status: **decided, not started.** This doc is the handoff for the new session.
> Date: 2026-09-18. Read DESIGN.md for the locked game design; this is only the M3 build plan.

## 1. Decision (locked this session)
**Pure Android.** Drop LibGDX and the desktop target entirely. Build the game as a
native Kotlin Android app (Canvas rendering + native text input). `engine/` is pure
Kotlin and is **reused unchanged** — it is the whole game brain (ISA, assembler, VM,
world, TextGrid, 5 presets, 20/20 tests). M1 is done and verified (`robofight.DemoKt`
runs headless). M2 desktop CLI stays as an optional dev sandbox; it is NOT the product.

**Why A over LibGDX (B):**
- The game is a **character grid** — `Canvas.drawText(char,x,y,paint)` + colored rects
  is a near-perfect fit. LibGDX's batch/sprite machinery buys nothing here.
- Native `EditText`/IME gives a **real editor + touch keyboard for free**. (The LibGDX
  `App.kt` hand-rolls ~200 lines of cursor/scroll/line-edit and still has no IME — that
  is where B hurt most.)
- **No broken dependency.** `libs/gdx-1.14.2.jar` is a hand-assembled *partial* LibGDX
  (missing `utils/Arrays`, `utils/Os`, `utils/SharedLibraryLoader`, `utils/Poolable`)
  patched by shim classes in `core/src/com/badlogic/gdx/utils/`. A deletes all of it.

## 2. What to delete (LibGDX desktop residue)
- `core/` (the `RoboFightApp` LibGDX app + the two shims) — replaced by `app/`.
- `libs/` — all gdx/lwjgl jars (desktop-only).
- `desktop/` — keep only if you want the M2 CLI sandbox; not part of the Android build.
- `build/core`, `build/desktop` — stale.
- KEEP: `engine/` (the core), `firmware/*.asm`, `DESIGN.md`, `PLAN-M3-ANDROID.md`.

## 3. Build toolchain (already installed — use it, do NOT add Gradle)
- **JDK:** `C:\Program Files\Android\Android Studio\jbr\bin\java` (Java 25, JRE).
  On this host `terminal` runs git-bash; pass **Windows-style** paths to native tools
  (MSYS `/c/...` paths break native arg parsing).
- **kotlinc:** `C:\Users\Clemens\tools\kotlinc\bin\kotlinc` (needs `java` on PATH).
  stdlib: `C:\Users\Clemens\tools\kotlinc\lib\kotlin-stdlib.jar`.
- **Android SDK:** `C:\Users\Clemens\AppData\Local\Android\Sdk`
  - compile: `platforms/android-37.0/android.jar`
  - tools: `build-tools/36.0.0/` → `aapt2`, `aapt`, `d8.bat`, `apksigner.bat`, `zipalign`
  - `platform-tools/adb` for install.
- **No system image** is installed → **emulator can't run**. Test by installing to a
  real device via `adb`, or install a system image first.
- **Network is a curated Maven mirror** (serves gson/slf4j; 404s all of `com/badlogic*`
  and likely `com.android.tools`). So **do not rely on downloading the Android Gradle
  Plugin.** Build offline with the SDK tools (recipe below).

## 4. Target app layout (Option A)
```
robofight/
  engine/                     (pure Kotlin — UNCHANGED, the game brain)
  firmware/                   (*.asm presets + MYBOT.asm — UNCHANGED)
  app/                        (NEW native Android module)
    src/main/AndroidManifest.xml
    src/main/kotlin/robofight/android/
        MainActivity.kt        (lifecycle, wires engine ↔ view)
        ArenaView.kt           (SurfaceView/View onDraw: TextGrid → glyphs, NES palette)
        EditorScreen.kt        (native EditText / IME → firmware source)
        RunController.kt       (assemble() → World → tick loop → stats)
    res/ (values, strings, colors)
  build-android.bat           (offline build: aapt2 → kotlinc → d8 → apksigner)
```
Reuses: `robofight.assembler.assemble`, `robofight.world.*` (World, Bot, Presets,
TextGrid, Palette) — the exact same API `App.kt` already calls. The 8-bit look (NES
palette in `Palette.HEX`, glyph set in DESIGN.md §3) is unchanged.

## 5. Offline build recipe (proven toolchain pieces; assemble the script)
```
SDK=C:/Users/Clemens/AppData/Local/Android/Sdk
BT=$SDK/build-tools/36.0.0
export PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH"   # gives `java`
KSTD=C:/Users/Clemens/tools/kotlinc/lib/kotlin-stdlib.jar

1. aapt2  compile each res file            -> out/compiled
2. aapt2  link -o app.unsigned.apk --manifest AndroidManifest.xml
              -I $SDK/platforms/android-37.0/android.jar   (produces R.jar)
3. kotlinc engine/src app/src -classpath android.jar:engine-cls -d app-cls
4. d8     app-cls + kotlin-stdlib  --lib android.jar  -> classes.dex
5. add classes.dex into app.unsigned.apk
6. zipalign -f 4 app.unsigned.apk app.aligned.apk
7. apksigner sign --ks robofight.keystore app.aligned.apk robofight.apk
8. adb install robofight.apk
```
(Gradle is the *preferred* long-term path per DESIGN.md §8, but only once a machine
with real Maven egress for `com.android.tools` is available. Ship A without it first.)

## 6. Acceptance criteria (done = verified, not described)
- [ ] `robofight.apk` builds offline and is signed.
- [ ] Installs on a device/emulator; opens to the editor screen.
- [ ] Load a preset (e.g. HUNTER) + MYBOT, press RUN → arena ticks, glyphs render
      (H/T/*, NES colors), stats bar updates.
- [ ] Assembler error path: bad line highlights + message (reuse `assemble()` errors).
- [ ] `engine/` unit tests (20/20) still pass headless — engine untouched.

## 7. Open for the new session to decide (low stakes)
- Package id / app label (default `robofight` / "ROBOFIGHT").
- minSdk (default 24) vs target/compile 37.
- Keep `desktop/` M2 CLI as a dev sandbox, or delete it.
