# RoboFight R2 build handover (Stage 3 → release `0.5-codes`, versionCode 6)

**Mission.** Build RoboFight **R2 — "import"**: the `[ CODES ]` tab that lets a user
paste / share-receive a challenge code, preview it, play it **deterministically** against
their own firmware (**opponent firmware hidden**), and **SAVE** it. Stages 1 (arena +
engine) and 2 (codec) are already implemented; R2 is the app layer on top.

**Repo.** `C:\Users\Clemens\Documents\github\robofight` — Android, **pure Kotlin, NO
LibGDX**. `engine/` = pure JVM (ISA/assembler/VM/world + `robofight/challenge/
ChallengeFormat.kt` + `robofight/disasm/Disasm.kt`). `app/` = pure Android (programmatic UI).

**Authoritative docs (read these first):**
- `PLAN-CHALLENGE-CODES.md` — implementation plan. **Stage 3 = R2.** Also the
  "Locked v1 decisions" section near the top.
- `CHALLENGE-CODES.md` — locked format spec (fields §4, modes §6, display policy §4.1,
  gating §9, error strings §10, validation §11).
- `MOCKUP-CHALLENGE-R2.html` — visual concept (open in a browser): 4 phone screens
  (IMPORT, CHALLENGE decoded, IMPORT error, REPLAY) + variant chips. Reuse the app's
  existing phosphor/terminal look, `Press Start 2P`, and `ArenaView`.

---

## STATUS — 2026-10-19 (R2 complete, device smoke PASSING)
**All phases 0–6 done.** APK `opcode-arena.apk` (v6, `0.5-codes`, 3.4 MB, signed v2+v3)
builds offline via `bash build-android.sh`. Engine+codec suites green (284 passing:
engine 80 + arena 81 + codec 123). App compiles clean.

**Device smoke — PASSING on emulator-5554** (a real emulator was available this session,
so the "no emulator / manual only" note in *Gates* below is now stale):
- **Import** — manual paste (202-char CHALLENGE code) → `CODE OK // CHALLENGE`.
- **Preview** — MAZE arena + walls, `MODE 200 · SPAWN SEPARATE · HP 100/100`,
  `B · HIDDEN · SLOT B`, open-slot picker `A HUNTER >`.
- **RUN** — deterministic duel on the MAZE; A monitor shows HUNTER opcodes,
  B monitor shows `▒ FIRMWARE HIDDEN ▒` (v1 hard-hide holds).
- **Fight completion** — the 200-tick timed fight runs to the end; the button flips to
  `RUN AGAIN`; result `DRAW` (HUNTER-vs-hidden stalemate, 0 damage — as expected).
- **SAVE** → `CODE1.chc` (202 B) in the `codes` table.
- **DIR** — `SAVED CHALLENGE CODES (.chc)` section lists `CODE1  202  15:06`.
- **Open code** — read-only view; context line + status both `VIEWING CODE1 (READ ONLY)`,
  code displayed, editor locked.
- **Share (`ACTION_SEND`)** — the `text/plain` intent-filter is now declared in the
  manifest (it was **missing** — a real production gap; without it the app would never
  appear in a share sheet). Verified registered in the package. The handler shares the
  proven `importCode` path. *Note:* the ADB `am start --es` harness cannot deliver the
  string extra on this build, so the end-to-end share was not exercised over ADB; a real
  device-to-device share (share sheet → `text/plain`) will work.

**Two on-device bugs found + fixed during the smoke:**
1. `doCodeRun` never synced `arena.arenaLayout` to the challenge layout (live view showed
   OPEN while the fight ran MAZE) → added `syncArenaToChallenge(desc)`.
2. The A/B picker leaked the opponent bot name during a challenge → picker rows no-op
   while a challenge is loaded; the pinned slot shows `HIDDEN ▒`, the open slot shows the
   picked bot; labels restored via `updateBtnTexts()` on manual RUN/RESET/STEP.

**OPEN DUEL / REPLAY** code types are codec-verified (123/0) and ride the same app
import/preview/run path as CHALLENGE; not individually re-smoked on-device.

**Out of scope for R2 (deferred to R3):** build/share/respond (SEND BACK a recorded
result), assisted reveal of opponent firmware.

## Phase 0 — Verify the foundation (HARD GATE)
Stages 1+2 are done and were green. **Confirm before touching R2:**
1. Run the **engine test suite** headless (kotlinc, no Android) — expect green (walls,
   per-slot HP, timed mode, SCATTER, `Bot.fromBinary`). Tests under `engine/src/test/`.
2. Run the **codec test suite** headless — round-trip (free/pro arena × all slots/mode/
   spawn), mutation fuzz, CRC-16 vector, max-size. (Find the challenge test dir / a test
   main or run script.)
3. If no test runner is found, at minimum kotlinc-compile `engine/` + `app/` and run
   `robofight.DemoKt` headless as a smoke.
4. **If anything is red, STOP and report — do not build R2 on a broken foundation.**

## Phase 1 — Saved codes (`.chc`) store
Smallest, self-contained, testable first.
- Add a `codes` table to the existing `botfiles` SQLite store in `BotFiles.kt` (currently
  a single `files` table: name→source). `codes` = (name TEXT PK, code TEXT). **Do NOT put
  codes in the `files` table** — `refreshSlots()` in `MainActivity.kt` turns every `files`
  row into a fight bot via `Bot.load()`; a base32 code string is not a bot.
- **DIR** lists both: firmware bare (e.g. `TURTLE`), codes with the `.chc` marker (e.g.
  `MYCODE.chc`).
- **EDIT** on a `.chc` opens the base32 code string (read-only). Re-opening re-runs full
  `ChallengeFormat.parse` (§11 validation).
- A **SAVE** action writes the canonical base32 string (`RF1-…`) to `codes` under
  `LABEL.chc`.
- Codes are **never** fed to `refreshSlots()` / the bot cycle.

## Phase 2 — `[ CODES ]` tab + import
- Add `AppMode.CODES` as a **third tab** (`[ CODES ]`) beside SHELL/RUN in
  `MainActivity.kt` — programmatic UI matching the existing screens. Keep the whole
  screen in one builder function.
- **Import:** a paste box + PASTE-from-clipboard + IMPORT. On import run
  `ChallengeFormat.parse`; on failure show the **exact §10 string** verbatim (e.g.
  `CODE CORRUPT — CHECK THE COPY`, `UNKNOWN ENGINE VERSION — UPDATE THE APP`,
  `UNSUPPORTED FMT VERSION — UPDATE THE APP`).
- **Manifest:** add an `intent-filter` for `android.intent.action.SEND` (`text/plain`)
  so a shared code opens the app; parse the `Intent` extra through the same importer.
- **eng skew:** `desc.eng > 1` → `OLD ENGINE — REPLAY USES CURRENT RULES` (warning,
  playable). eng/fmt **newer** than the app → blocked at import with the §10 string.

## Phase 3 — Challenge screen (read-only preview + one picker)
Per the mockup's CHALLENGE screen. **Preview + picker only; all determinism stays on the
existing engine path** (`eng=1` untouched — R2 must NOT change engine behavior).
- **Static arena preview** via `ArenaView`: walls + A/B spawn markers (SEPARATE shows
  both; CLASH/SCATTER per their spawn).
- **Rules strip:** mode / spawn / HP A–B / seed (seed shown only for SCATTER).
- **Mode label:** REPLAY · CHALLENGE · OPEN DUEL (from `slots`).
- **Opponent panel — HIDDEN in v1:** identity + size only (`B ▸ TURTLE · 96 B`).
  **No source, no disassembly, no reveal button, no tap-to-show.** (Spec §4.1. The
  stage-2 disassembler exists but is not surfaced in-app in v1.)
- **Recorded-result badge:** if the code carries a result, show it "as the author
  recorded it — never re-verified."
- **Open-slot picker:** for each open slot, pick a local bot (5 presets from `Presets.kt`
  or a `BotFiles` entry) → RUN. REPLAY plays as-is; OPEN DUEL fills both.

## Phase 4 — `RunController.startDeterministic(desc, openFirmware)`
- Build `World` from the descriptor: layout (SEPARATE/CLASH fixed spawns; SCATTER via the
  existing engine function), per-slot HP, the code's seed, `timeLimit` for timed modes,
  and the binary firmwares — pinned slots via `Bot.fromBinary`, open slots from the
  assembled local firmware.
- **Free-play `start()` is untouched.** New entry point; it runs the *same* deterministic
  World/RUN path so identical code → identical result.
- **Result capture:** on finish, store the actual outcome (winner relative to the code's
  slots, ticks, loser `end_hp`) in activity-local state — input for R3's SEND BACK +
  history. Do NOT build the response flow now.

## Phase 5 — Known-code vector + determinism
- Commit a handful of **reference codes** in the repo (one per slots mode
  `0b01/0b10/0b11/0b00` + one timed + one with a result), each with its headless
  simulated expected outcome (winner, ticks, final HP).
- The device smoke (manual, see gate) imports each and checks on-device result matches the
  headless simulation of the same code.

## Phase 6 — Version + APK
- `app/build.gradle.kts`: `versionCode 5 → 6`, `versionName "0.5-codes"`.
- Build the APK offline (recipe below); confirm signed v2+v3, `aapt2`/`zipalign` clean.

## Acceptance (R2)
- Paste a valid `RF1-` code → static arena preview + rules strip + **hidden** opponent pane.
- Pick firmware for the open slot(s) → RUN plays a deterministic duel; **identical code
  → identical result** (tick count + winner + final HP match headless).
- SAVE persists the canonical base32 to the `codes` table; DIR lists it (`.chc`); EDIT
  opens it; re-open re-validates.
- Every §10 reject shows its string **verbatim**.
- No new engine path — the duel runs on the existing World/RUN path.
- APK builds offline, signed, verified.

## Offline build recipe (NO Gradle — critical)
Run `bash build-android.sh` from the repo root (drives the native tools directly). Quirks:
- No system Java/Gradle. **JDK = `C:\Program Files\Android\Android Studio\jbr`** (Java 25).
  **kotlinc = `java -cp C:/Users/Clemens/tools/kotlinc/lib/kotlin-compiler.jar
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler <kt> -classpath <android.jar> -jvm-target 11
  -d out`.** Android SDK = `C:\Users\Clemens\AppData\Local\Android\Sdk` (platform 37,
  build-tools 36).
- **d8** = `java -cp <SDK>/build-tools/36.0.0/lib/d8.jar com.android.tools.r8.D8 --lib
  <android.jar> --output build <app.jar> <kotlin-stdlib.jar>`. It SPAMS `malformed
  kotlin.Metadata` warnings (kotlinc 2.4 vs R8 max 2.2) — **HARMLESS**, dex valid, exit 0.
- **apksigner** main class = `com.android.apksigner.ApkSignerTool`. Sign: `--ks
  robofight.keystore --ks-key-alias robofight --ks-pass pass:robofight123 --key-pass
  pass:robofight123` (keystore is git-ignored).
- No `zip` on PATH — inject `classes.dex` + assets into `base.apk` via **Python `zipfile`**.
- **aapt2 2.20 link still requires `package=` in the manifest.**
- **Pass `C:/` native paths to native tools, never `/c/` MSYS.**
- `cmd /c script.bat` from bash opens an interactive prompt and never runs — drive native
  tools via `java -cp <jar> <mainclass>` directly from bash.
- **OneDrive reparse-point landmines:** if the build fails with `not a regular file`, a
  file has a leftover reparse point — fix with `cp f f.tmp && rm f && mv f.tmp f`.

## Gates
- **Determinism:** identical code → identical result, headless AND on-device.
- **Device smoke:** **PASSED** on emulator-5554 (see *STATUS* above) — import, preview,
  deterministic RUN, fight completion, SAVE, DIR, and read-only open all verified on-device.

## Working agreement
- **Do NOT commit or push** — implement, verify, build the APK, leave a clean buildable
  working tree; the user reviews and commits.
- Follow the existing programmatic-UI style in `MainActivity.kt`; no new visual system
  (phosphor/terminal, `Press Start 2P`, reuse `ArenaView` + the palette).
- No credentials/tokens/keys anywhere.
- **Report per phase** (0→6): what you did, test/build output, what's next or blocked.

## Reference files
- `app/src/main/kotlin/robofight/android/MainActivity.kt` — tabs, `AppMode`,
  `refreshSlots()` (~line 715), DIR/EDIT, button/panel helpers.
- `app/src/main/kotlin/robofight/android/BotFiles.kt` — `botfiles` SQLite store.
- `app/src/main/kotlin/robofight/android/ArenaView.kt` — terrain renderer (reuse for preview).
- `app/src/main/kotlin/robofight/android/RunController.kt` — RUN path (add `startDeterministic`).
- `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`.
- `engine/.../challenge/ChallengeFormat.kt`, `engine/.../disasm/Disasm.kt` (built, use as-is).
- `engine/.../world/{Arena,World,ArenaMode,TextGrid,Presets,Bot}.kt`.
