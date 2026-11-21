# WEB-W3 record: shared mission session layer, on-device regression, and one storage defect

Branch: `codex/web-w3-console` (from `codex/web-w2-parity` HEAD `ffafe02`).
Date: 2026-11-20 to 2026-11-21. Plan: `docs/FIRELINE-WEB.md` §9 W3, gates §4 G4 and G5.

**Status of this record: W3 steps 1 to 4 are DONE.** Step 1 lifted the mission session
layer out of `:fireline-app` into the shared engine so the browser console and the MISSION
screen cannot drift apart. Step 2 proved on a device that the shipped Android behaviour is
unchanged, against the committed W2 fixtures, and it found one pre-existing defect that is
now fixed. Step 3 added the `:fireline-web` wasm module scaffold (data layer plus its fixture
gate). Step 4 added the console shell: a pure render layer, the wasm entry point and a harness
page, verified in Chrome against the built wasm. The matrix stands at 425 tests, zero failures.
Still open for W3: the 20x20 world grid and its symbol legend, the editor, the `localStorage`
library, `.asm` import/export and `#run=` permalinks (gate G5).

Record status 2026-11-21: every fingerprint in this record was measured under engine v1.
`FIRELINE_ENGINE_VERSION` is 2 (`FirelineRules.kt:21`) since the edge-wall step E1b
(`PLAN-EDGE-WALLS.md` section 11), so the device values in the table below are historical. The
stored keys `best.1.1`, `best.2.1` and the `last_run` record stay on disk and are not read by
engine v2 (`RunStores.kt:94`). Current values: `docs/web/fixtures/parity_matrix.tsv` and
`docs/web/fixtures/snapshots.json`.

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

## 7. Step 4: the console shell (render layer, wasm entry point, harness page)

Files (all in `:fireline-web`): `ConsoleRender.kt` 332 lines (pure render layer),
`Main.kt` 90 lines (wasm entry point, DOM wiring only), `console.html` 202 lines (harness page,
frozen chrome inline, loads the built `.mjs`), `ConsoleRenderTest.kt` 257 lines (8 wasm tests),
and `ConsoleModel.kt` grown by 82 lines (349 total) for `sourceLines`, `readoutPorts`,
`objectiveChips`, `runStats`, `opTicksLeft`, `vmFault`.

`ConsoleRender.renderConsoleHtml(model, runOrNull)` returns the console HTML as a String. Its
band cell lists are byte-equal to `VM_CELLS` / `TELEMETRY_CELLS` in `docs/web/verify_layout_v1.py`
and a test re-asserts them; the escape rule matches `escape()` in `docs/web/gen_web_mockups.py`
(`&` first, then `<`, `>`, `"`); the `next:`/`exec:` preview, `TICK n / limit` and
`ROVER at x n y n facing L` reuse the shipped `MissionRunSurface` wording. The entry point
contains no mission logic: it assigns the string to containers and wires
`fl-btn-run|step|reset` and the five card buttons to `ConsoleRun.run()/step()/reset()`.

Wording fact found while doing this: the frozen golden master uses two different mission
labels, `MISSION 02://CORRIDOR` in the console titlebar (`gen_web_mockups.py:675`) and
`MISSION 02 / CORRIDOR` in the `FL:> LIVE MONITOR` prompt line. Both are in
`fl-web-5-playable-landing.html`, so the shell renders both, in those two places. That is not a
divergence to fix, it is the frozen layout's own pair.

Three failures appeared first; all three were test-authoring errors except one real wording
gap. The diagnosis method was to dump the actual rendered HTML from inside the wasm test:
asserting `ROVER at x` against raw markup fails because label and value are separate `<td>`s
(assert against tag-stripped text instead); the titlebar was genuinely missing the frozen
`://` colon (fixed in the render); and an inverted `assertFalse(html.contains("class=\"btn"))`
matched the correct buttons (assert instead: no `<a class="btn"` and exactly three
`<button type="button" class="btn …">`). One copy divergence was fixed too: an empty event log
now renders `no event on this tick`, matching the mockup's fallback verbatim.

Verification done here (own browser session against a local http server that serves `.wasm` as
`application/wasm`, because `file://` refuses the dynamic import of the `.mjs`): `#fl-shell`
renders 8 children; prompt reads `FL:> LIVE MONITOR // MISSION 02 / CORRIDOR // TICK 0`; three
real clicks on `fl-btn-step` give `... TICK 3` with the log head `003 MOVE (2/2) |`,
`002 MOVE (1/2) |`, `001 TURN R |`; `RUN` gives `MISSION SUCCESS` and `fingerprint $47e28d84`;
the picker renders 5 `article.mcard` titled `MISSION 01 / NOZZLE CHECK` .. `MISSION 05 / HOT
ZONE` carrying `$198645dc`, `$47e28d84`, `$0cf3b083`, `$dd669e73`, `$a7836db7`; at a 755 px
viewport the firmware pane measures 232 px (the frozen `21 * 11 + 1` narrow-width lock) with
`scrollWidth == clientWidth`, i.e. no horizontal overflow.

What this step does not have yet, and what the next step must add: the 20x20 world grid and its
symbol legend (`§15.2` item 2, `verify_layout_v1.py` checks "15.6 grid renders 400 cells";
`document.querySelectorAll('.cell').length` is 0 in the current shell), the `SPEED`/`PANIC` row,
the `LINK`/`COPY`/`DOWNLOAD .ASM` share block, the editor, the `localStorage` library and
`#run=` permalinks (gate G5).

Correction to a wrong premise from the shell step: it reported the grid as blocked because
"`FirelineWorld.terrain`/`fire` are private and nothing exposes per-cell state". That is not
true. The engine already exposes `terrainAt(x,y)`, `fireAt(x,y)`, `terrainRowMajor()`,
`fireRowMajor()` (defensive copies) and the shared glyph authority `TextGrid.glyphAt(world,x,y)`
plus `TextGrid.render(world)` (`fireline-engine/.../TextGrid.kt:25,32`), and the Android grid
view already builds its cells exactly that way (`fireline-app/.../view/FirelineGridView.kt:131,
158` reading `w.terrainAt`/`w.fireAt`/`TextGrid.glyphAt`). The web grid is therefore web-layer
work on the existing shared API, no engine change needed.

Useful toolchain fact: in Kotlin/Wasm a Kotlin lambda passed into a `js(...)` snippet marshals
to a JS event handler (proven in Chrome and in Node), so the shell needs neither a browser
facade library nor any npm package. And `file://` cannot load the module (Chrome refuses the
dynamic import); the console needs a server that maps `.wasm` to `application/wasm` and `.mjs`
to `text/javascript`, which is what `docs/DEPLOY-IIS-WASM.md` already requires for hosting:
`Content-Type: application/wasm` is mandatory for streaming instantiation, and that doc records
the exact browser failure when the file is served as `application/octet-stream`.

## 8. Step 5: the 20x20 world grid and the symbol legend

`WorldGrid.kt` (new, 164 lines) renders the world column of `§15.2` item 2 in the frozen order
`WIND` line, grid, legend, `EVENT LOG`, inside `ConsoleRender.renderConsoleHtml`
(`ConsoleRender.kt` `worldColumn`, now 331 lines). No engine change and no missing accessor: the
glyph authority is `TextGrid.glyphAt(world,x,y)` (layer order rover > civilian > packet > fire >
terrain) with `TextGrid.fireGlyph(intensity)` for the fire bands, and the colour classes come from
`FirelineWorld.terrainAt(x,y)` / `fireAt(x,y)` - the same reads and the same branch order
`FirelineGridView.drawCell` uses (`fireline-app/.../FirelineGridView.kt:128-205`), so browser and
Android describe the same cell with the same symbol. `ConsoleRun` gained one forwarding read,
`currentWorld` (`MissionSession.currentWorld`); `Main.kt` and `console.html` did not change - the
`.grid`, `.cell`, `.ruler`, `.nose` and `.legend` chrome CSS was already carried verbatim from the
golden master.

Markup is copied from the executable source of the frozen layout (`grid_html()` and `LEGEND` in
`docs/web/gen_web_mockups.py`): `corner` + 20 column rulers + 20 row rulers,
`<span class="cell CLASS" STYLE>GLYPH</span>`, `<i class="nose">caret</i>` in the rover cell, the
idle-floor `font-size:8px;opacity:.75` and burning `font-weight:bold` styles, and the ten legend
items byte-for-byte (same classes, wording and HTML entities). Grid geometry is stated as
arithmetic over `GRID` (`GRID_ROW_COUNT = GRID + 1` for the 21st ruler row,
`DESKTOP_GRID_HEIGHT_PX = 21 * 15 + 2 = 317`, `NARROW_PANE_HEIGHT_PX = 21 * 11 + 1 = 232`) instead
of magic numbers; the pixel values themselves live in the frozen chrome CSS.

Tests: `WorldGridTest.kt` (new, 400 lines, 8 tests, `wasmJsTest` in Node) asserts 400
`class="cell ` nodes in the world column, the wind/grid/legend/event-log index order, the legend
once and never in the firmware column or a band, the rover cell at
`telemetry().roverY * 20 + roverX` carrying `TextGrid.ROVER_GLYPH` plus the caret of
`roverFacing`, the STEP sequence moving the rover cell to the new telemetry coordinate, terrain
cells `(3,2)`, `(9,9)`, `(8,10)`, `(11,10)` and a scanned floor cell rendering what
`TextGrid.glyphAt` plus the engine's public `terrainGlyph` produce for the same coordinates, and every burning cell
rendering `TextGrid.fireGlyph(world.fireAt(x,y))`. Committed anchors used: `snapshots.json`
`02-ref-t46` (rover 6,10 facing E, pocket fire intensity 70 at 9,10 -> glyph 3), `§14.1` grid
parity (400 cells, 26 walls, 2 refills, 1 exit, 1 rover, 1 burning cell at tick 46), and
`Step1LayoutRoverPortsTest.kt` for the layout-decode discipline (authored level '1' = 25).

Deliberate deviation, stated because it changed an existing green assertion: the first shell step
put a `kv` row `TICK n / limit` + `ROVER at x n y n facing L` in the world column as the grid's
placeholder. `§15.2` item 2 has no such row, so the grid replaced it, and
`ConsoleRenderTest.mission02RenderAtTick30ShowsTheCommittedStepByStepReadout` now asserts the tick
in the golden master's own wording (`tick 30/120` on the objectives line, plus the existing `TICK`
band cell) instead of the Android MISSION wording `TICK 30 / 120`. The mission 02 STEP test also
documents that `ISA.cost` gives TURN 1 tick and MOVE 2 ticks with the world effect on the final
tick, so STEP 1 of CORRIDOR-REF turns the rover in place and STEP 3 moves it - "one STEP moves the
rover" is not true for this mission's authored first op, and the test asserts facing-change and
position-change separately instead of pretending.

Chrome verification (`:fireline-web:wasmJsNodeDevelopmentLibraryDistribution`, served over the
local http server that maps `.wasm` to `application/wasm`, `console.html` at a 1312 px viewport,
then at 420 px):
- `document.querySelectorAll('.cell').length` = 400 (was 0); 1 `.grid`, 1 `.legend`, 40 `.ruler`.
- World column children in order: `wind`, `grid`, `legend`, `h4` (EVENT LOG), `events`.
- Rover cell before STEP: index 42 (`2 * 20 + 2`), text `R▸`. After 1 STEP: index 42, `R▾` (TURN R,
  facing caret changed with `roverFacing`). After 2 STEPS: index 42, `R▾` (MOVE (1/2) in flight).
  After 3 STEPS: index 62 (`3 * 20 + 2`), `R▾` - the moved rover sits at the new coordinate.
- Legend visible (10 `<b>` items, full mockup wording incl. `1..24 / 25..49 / 50..74 / 75..100` and
  the layer-order line).
- Grid at 1312 px: 332 x 317 px (`gridTemplateColumns` = 30px + 20 x 15px, 21 rows of 15px, cell
  box 15.0 x 15.0), firmware pane 316 px, grid-bottom minus pane-bottom = 0.14 px (flush pairing
  within 2 px, `§15.3`), world and firmware columns share top 513.28 px, RUN/STEP/RESET 74.0 /
  74.0 / 80.4 x 49.0 px (>= 49 px gate).
- Grid at 420 px: 246 x 233 px, 11 px cells, 24 px ruler column, pane 232 px (`21 * 11 + 1`),
  400 cells still.
- No horizontal overflow at either width: `scrollWidth == clientWidth` (1297 == 1297 at 1312 px,
  405 == 405 at 420 px).
- Cosmetic note: the idle-floor dot renders `#4a382e` on the `#2a1e1a` grid backdrop and is almost
  invisible at 15 px. That is the mockup's own look and the documented `§14.2` empty-cell-dot
  exemption (1.46, decorative); it was not retuned.

Close-out matrix (`--rerun-tasks`, `BUILD SUCCESSFUL`, 160 tasks, counts read from the per-target
XMLs): `:rf8-core:jvmTest` 15/0/0, `:rf8-core:wasmJsTest` 15/0/0, `:fireline-engine:jvmTest`
164/0/0, `:fireline-engine:wasmJsTest` 164/0/0, `:fireline-web:wasmJsTest` 24/0/0 (8 `ConsoleModel`
+ 8 `ConsoleRender` + 8 `WorldGrid`), `:app:testFreeDebugUnitTest` 3/0/0,
`:app:testProDebugUnitTest` 9/0/0, `:fireline-app:testDebugUnitTest` 39/0/0,
`:fireline-app:assembleRelease -x lintVitalRelease` green. Total 433 tests, 0 failures, 0 errors
(425 before this step plus the 8 new grid tests). `docs/web/verify_layout_v1.py` still reports
22 checks, 0 failed against the unchanged golden master.

## 9. Next step

Close the remaining frozen-layout gaps in `:fireline-web`: the editor, the `localStorage` library,
`.asm` import/export and `#run=` permalinks, plus the `SPEED`/`PANIC` row and the
`LINK`/`COPY`/`DOWNLOAD .ASM` control-block entries. Gate G5 (`.asm` roundtrip against
`:fireline-app`) and G4 (the tick-30 and tick-102 field values, proven on device and now also in
the browser) are the W3 acceptance pair; W4 hosting comes after.
