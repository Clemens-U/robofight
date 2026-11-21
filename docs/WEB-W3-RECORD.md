# WEB-W3 record: shared mission session layer, on-device regression, and one storage defect

Branch: `codex/web-w3-console` (from `codex/web-w2-parity` HEAD `ffafe02`).
Date: 2026-11-20 to 2026-11-21. Plan: `docs/FIRELINE-WEB.md` §9 W3, gates §4 G4 and G5.

**Status of this record: W3 steps 1 and 2 are DONE.** Step 1 lifted the mission session
layer out of `:fireline-app` into the shared engine so the browser console and the MISSION
screen cannot drift apart. Step 2 proved on a device that the shipped Android behaviour is
unchanged, against the committed W2 fixtures, and it found one pre-existing defect that is
now fixed. The `:fireline-web` module scaffold (step 3) is in place: a wasmJs-only data layer plus an
8-test wasm gate pinned to the committed fixtures. The interactive console shell is the next
step, not this one.

---

## 1. Step 1: the session layer moved into the shared engine (commit `29a313f`)

`MissionSession`, `TelemetrySnapshot`, `StepOutcome`, `ResultModel` and `RunSummary` lived in
`fireline-app/src/main/kotlin/robofight/fireline/app/mission/`. They were Android-free apart
from five `String.format` calls, so the web console would have needed a second implementation
of the same rules. They now live in `robofight.fireline.session` in
`fireline-engine/src/commonMain`, which `jvm` and `wasmJs` both compile.

- `git mv` kept the history: `MissionSession.kt`, `ResultModel.kt`, `RunSummary.kt` and the
  matching tests moved; the six Android users (`MainActivity`, `MissionPickerModel`,
  `MissionRunSurface`, `ResultSurface`, `RunStores`, `RunStoresTest`) gained imports only.
- The five `String.format("%03d", ...)` and `String.format("$%03X", ...)` calls became
  `robofight.isa.decPad` and `robofight.isa.hexPad` (the same helpers W1 used for
  output-identical formatting). `MissionSessionTest.sessionLogAndHexPaddingKeepTheirShape`
  pins the resulting shapes, because the format calls were the only thing guaranteeing the
  padding: event-log lines keep the `001 `/`003 ` three-digit tick and the ` | ` separator,
  and `pcHex`/`spHex` keep the uppercase three-digit `$` form.
- `MissionSamples` is duplicated (engine `commonTest` copy is canonical, app `src/test` copy
  mirrors it) because Kotlin `internal` test fixtures are module-scoped. Both files name the
  canonical one. That is the drift-watch item of this step.

## 2. Step 2: on-device regression, FIRELINE app only, `emulator-5554`

Device: AVD `Medium_Phone` headless, `Physical density: 420` (1 dp = 2.625 px). Build:
`:fireline-app:assembleDebug` (debug-signed, no signing step) from the W3.1 tree, installed
after a clean uninstall. Every expectation below was read from the committed fixtures in
`docs/web/fixtures/` before the run, never from what the device produced.

| Check | Device showed | Committed fixture | Match |
| --- | --- | --- | --- |
| Mission list | `01 NOZZLE CHECK` .. `05 HOT ZONE`, five cards with objectives, seeds, limits, wind | `Missions.ALL`, five authored missions | yes |
| Mission metadata | `04` = `SEED 4004 // LIMIT 500 TICKS // WIND E x2`, `05` = `WIND N x0 // WATER 60`, `02` = `SEED 2002 // LIMIT 120 TICKS` | `Missions.kt` authored values | yes |
| SAF import | `imported CORRIDOR-REF.ASM`, store row `375 B  25 i` | fixture source, 375 bytes / 25 lines | yes |
| Tick 12 | `TICK 12 / 120`, `A 0 ... SP $0BF PC $115`, `last: MOVE (1/2)`, `FIRE_DIST 10`, `FIRE_LEVEL 35`, grid desc `rover at x 2 y 7 facing S ... fire distance 10` | `02-ref-t12`: pc 277 (`0x115`), sp 191, a 0, `MOVE (1/2)`, FIRE_DIST 10, FIRE_LEVEL 35, rover 2,7 S | yes, every field |
| Tick 46 | `A 4 ... SP $0BF PC $11E`, `last: JMP 286`, `next: IN FIRE_DIST @line 11`, `FIRE_DIST 3`, `FIRE_LEVEL 70`, grid desc `rover at x 6 y 10 facing E ... fire distance 3` | `02-ref-t46`: pc 286 (`0x11E`), a 4, `JMP 286`, next insn at line 11, FIRE_DIST 3, FIRE_LEVEL 70, rover 6,10 E | yes, every field |
| Finish | `MISSION COMPLETE`, `MISSION 02 / CORRIDOR   TICK 102/120`, `firmware 75 B / 25 instructions`, `fingerprint $47e28d84` | `2-reference`: tick 102, SUCCESS, `47e28d84c5d61220` (app renders the first 8 hex) | yes |
| Failure path | `NEVER.ASM` byte-equal to the engine's mission 01 `never shoots` source (`loop:  WAIT` / `JMP    loop`), run result `FAILURE: TIME_EXPIRED`, `MISSION 01 / NOZZLE CHECK   TICK 60/60`, `firmware 6 B / 2 instructions`, `fingerprint $a64c17a9` | `1-failing`: tick 60, TIME_EXPIRED, `a64c17a9d85a6f75`, 6 B / 2 insns | yes |
| Event-log shape | `101 MOVE (1/2) | `, `102 MOVE (2/2) | MISSION SUCCESS`, `060 JMP 256 | MISSION FAILURE TIME_EXPIRED` | three-digit padded tick, ` | ` separator | yes |
| SAF export roundtrip | export as `CORRIDOR-RT21.ASM`, pulled 375 B, `cmp` byte-identical with the imported file (md5 `e80d319c4361b40d7be27971433065fe` both) | gate G5 store/export fidelity | yes |
| Accessibility | grid node content description `MISSION GRID running mission 02 tick 12 of 120 rover at x 2 y 7 facing S hp 100 heat 0 water 100 fire distance 10 civilians 0`; tab and control nodes carry their own descriptions | live state in the description | yes |
| Touch targets | `RUN`/`STEP`/`RESET` = 126 px tall = 48.0 dp at density 420; grid panel 380.2 dp square | 48 dp minimum | yes |
| Persisted records | `BEST: FAILURE TIME_EXPIRED  TICK 60` (mission 01), `BEST: SUCCESS  TICK 102` (mission 02); stored `last_run` hash `a64c17a9d85a6f75`, stored `best.2.1` hash `47e28d84c5d61220` | the two committed fingerprints, stored in full 16-hex form | yes |

No Circuit/pro-flavour APK was installed or driven; the check targets the FIRELINE app only.

## 3. The defect the device check found, and its fix

Symptom after relaunch: every mission card said `no run recorded yet for engine v1` and the
RESULT tab was empty, even though `shared_prefs/fireline_ui.xml` contained `best.1.1`,
`best.2.1` and `last_run` with the right numbers (read with `run-as`, the debug build is
debuggable).

Cause: `RunSummary.encode()` ends every line with `\n`, so the stored string ends with a
newline. Android's preferences writer pretty-prints such a value and puts the *next*
element's indentation inside the string, so the app reads the value back with a trailing
whitespace-only line (`&#10;    </string>` in the XML). `RunSummary.decode()` splits at newlines
and refuses any line without `=`, so the padding made the whole record decode to null. The
single-line keys (`firmware`, `mission`) were unaffected, which is why firmware and mission
selection persisted while the run records did not.

This was not introduced by the lift: `git diff a389508 HEAD` over `RunSummary.kt` shows only
the package and import lines, and the pre-lift file has the same `if (eq <= 0) return null`
line. The device check is what surfaced it.

Fix (`fireline-engine/src/commonMain/.../session/RunSummary.kt`): `decode` now skips lines that
are entirely whitespace (storage padding is not content) and still returns null for anything
that is not a `key=value` line. Three new engine tests cover it, on `jvm` and `wasmJs`: the
trailing-indent artifact decodes back to the original summary, an indented blank line between
records is skipped, and `best badge` / a leading-space `status=` line are still rejected.

Re-verified on the same device with the rebuilt APK: the two best badges and the full RESULT
panel came back after a cold restart, including `fingerprint $a64c17a9`.

Why this matters for W3: `RunSummary.encode`/`decode` is the format the web console will use
for its library and `#run=` permalinks. `localStorage` does not add the Android indentation,
but the tolerant decoder is now shared, so the browser cannot be stricter than the app.

## 4. Close-out matrix

`:rf8-core` 15 jvm + 15 wasm, `:fireline-engine` 164 jvm + 164 wasm (was 161 each before the
decode tests), `:app` 3 free + 9 pro, `:fireline-app` 39. Total 409 tests, 0 failures, 0
errors, with `--rerun-tasks`. `:fireline-app:assembleRelease` needs `-x lintVitalRelease`
offline (the lint artifact is not in the mirror). Release APK 945,944 B, debug APK 1,371,870 B.
After step 3 the same matrix plus `:fireline-web:wasmJsTest` gives 417 tests (160 executed
tasks), still zero failures and zero errors.

## 5. Method notes worth keeping

- A `terminal` call that times out does not stop the command it started. The interrupted W3.1
  device attempt left a queued STEP loop running on the device; on the next attempt the app
  showed a session at tick 17 that this agent had not started. Always check
  `pm list packages -f`, `firstInstallTime`/`lastUpdateTime` and the `DEBUGGABLE` flag before
  believing which build you are driving, and uninstall rather than `install -r` when a
  previous run left state behind.
- `adb install` needs a native `C:/...` path; an MSYS `/c/...` path fails with
  `failed to stat`.
- SAF driving recipe (DocumentsUI): open-document picker rows appear as
  `android:id/title` text nodes; tap the row. Create-document picker exposes the name field as
  an `EditText` with `resource-id=android:id/title` plus a `SAVE` button
  (`android:id/button1`); clear with `KEYCODE_MOVE_END` plus `KEYCODE_DEL` and type with
  `input text`.
- The emulator clock ran about two hours behind the host clock, so file timestamps in
  `/sdcard/Download` are not comparable with host build times.
- uiautomator reports Compose label nodes at their text bounds, so card action labels measure
  51 px (19.4 dp) there while the run-bar buttons measure their real 126 px (48.0 dp). The
  run-bar numbers are the ones the 48 dp gate uses.

## 6. Step 3: the `:fireline-web` wasm module scaffold

New module `:fireline-web` (`fireline-web/build.gradle.kts`, `settings.gradle.kts` include),
`wasmJs` only: it has no `jvm` target because nothing on the JVM consumes it, and it uses
`binaries.library()` because there is no wasm `main()` yet (the shell entry point is the next
step). Artifacts come from `:fireline-web:wasmJsNodeDevelopmentLibraryDistribution`:
`build/dist/wasmJs/developmentLibrary/opcode-arena-fireline-web.wasm` 1,283,685 B (unoptimized
dev library, plus 12,118 B `.uninstantiated.mjs`, 214 B `.mjs`, 5,150 B `custom-formatters.js`).
Host constraint, not a defect: `build`/`assemble` of any wasm module fails here at
`kotlinWasmBinaryenSetup` because `com.github.webassembly:binaryen` is unreachable offline;
`:fireline-engine:assemble` fails identically.

`ConsoleModel.kt` holds the presentation-free layer: `MissionRow` picker cards built from
`Missions.ALL` (id, title, briefing, objective labels in engine order, seed, `timeLimit`,
`tickLimit`, wind label and force, starting water, best-run label), and `ConsoleRun`, a driver
that owns nothing: every accessor either forwards to `MissionSession` or formats what the
engine produced (`logTail` forwards `session.fullLogNewestFirst()`, `fingerprintHex` is
`ResultModel.fingerprintShort`, `run()` is defined as repeated `session.step()`). No DOM, HTML
or JS interop in this file, which is exactly what §13 asks for.

`ConsoleModelTest.kt` (wasmJs, 8 tests, 84 assertions) cites its fixture provenance in every
assertion message. Values observed in wasm:

| Case | Live wasm value | Fixture source |
| --- | --- | --- |
| Mission 02 reference run | tick 102, `SUCCESS`, last `MOVE (2/2)`, `$47e28d84` (full `47e28d84c5d61220`), FIRE_DIST 0, FIRE_LEVEL 0, 75 B / 25 insns, log tail `102 MOVE (2/2) | MISSION SUCCESS` | `snapshots.json 02-ref-final`, matrix row `2-reference` |
| Mission 01 `never shoots` | tick 60, `FAILURE` / `TIME_EXPIRED`, last `JMP 256`, `$a64c17a9` (`a64c17a9d85a6f75`), 6 B / 2 insns, `a 0`, SP `$0BF`, PC `$100` | `snapshots.json 01-never-final`, matrix row `1-failing` |
| Mission 02 stopped at tick 30 | last `MOVE (1/2)`, `A 6`, SP `$0BF` (191), PC `$12D` (301), FIRE_DIST 6, FIRE_LEVEL 55, log line `030 MOVE (1/2) | ` from three independent paths | `docs/WEB-W1-RECORD.md` §9 `STEP x 30` row (see correction 1) |
| Mission 02 stopped at tick 46 | last `JMP 286`, `A 4`, PC `$11E` (286), SP `$0BF`, FIRE_DIST 3, FIRE_LEVEL 70, state hash `e9226853d9b3eb4d` | `snapshots.json 02-ref-t46` (fully fixture-pinned mid-run point) |
| All five picker best labels | ticks 17 / 102 / 38 / 147 / 45, `$198645dc`, `$47e28d84`, `$0cf3b083`, `$dd669e73`, `$a7836db7`, firmware 102 B/34, 75/25, 72/24, 105/35, 111/37 | matrix rows `1..5-reference` |
| Driver equivalence | `ConsoleRun.run()` equals `FirelineRun.runWithGuard` for all five missions, reference and every documented failing firmware, with the authored failure reasons; `reset()` replays to 102 / `$47e28d84` | live engine, cross-checked against the headless authority path |

Two corrections that save the next step from a wrong premise:

1. **Tick 30 is not fixture-pinned.** `parity_traces` covers ticks 1 through 12 only, so the
   committed tick-30 anchor is the `STEP x 30` row of `docs/WEB-W1-RECORD.md` §9: `A 6`,
   `PC $12D`, `FIRE_DIST 6`, `FIRE_LEVEL 55`. A plan line claiming `A 0` / `PC 285` at tick 30
   is wrong, and 285 cannot even be a mission-02 address (mission 02 addresses are 256 + 3k, so
   283 or 286). Use tick 46 when a mid-run assertion must be pinned to `snapshots.json`.
2. **The console's method names are not `MissionSession`'s.** `MissionSession` exposes
   properties `status`, `tick`, `failureReason`, `finished`, `guardReached` and functions
   `step()`, `reset()`, `telemetry()`, `result()`, `summary()`, `currentEvents()`,
   `fullLogNewestFirst()`; the engine status types are `RunStatus` and `FailureReason`, there is
   no `MissionStatus` enum and no `MissionSession.logLine`/`fingerprintHex`. The shell must use
   `ConsoleRun`/`ResultModel` for those names.

Gaps this scaffold leaves for the shell step: `ConsoleModel.bestRunOf` computes the *authored
reference* best headlessly at card build time (five full runs), which is a placeholder for the
user's own stored best runs once the `localStorage` library exists; there is no wasm `main()`
or DOM yet; and `:fireline-web` needs `implementation(project(":rf8-core"))` written out
because `:fireline-engine` depends on it as `implementation`, not `api`.

## 7. Next step

The interactive console shell in `:fireline-web`: wasm entry point plus mission picker, editor,
run surface, `.asm` import/export, the `localStorage` library and `#run=` share permalinks, all
on the session layer this record lifted. Gate G5 (`.asm` roundtrip against `:fireline-app`) and
G4 (the tick-30 and tick-102 field values, now also proven on device) are the W3 acceptance
pair; W4 hosting comes after.
