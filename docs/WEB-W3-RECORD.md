# WEB-W3 record: shared mission session layer, on-device regression, and one storage defect

Branch: `codex/web-w3-console` (from `codex/web-w2-parity` HEAD `ffafe02`).
Date: 2026-11-20 to 2026-11-22. Plan: `docs/FIRELINE-WEB.md` §9 W3, gates §4 G1 to G6.

**Round 16 CLOSED (opened 2026-11-23, closed 2026-11-24): grid cell scaling fix (owner report
"the cells are reaching into the firmware area"). Root cause, rejected attempts and the shipped
render-layer fix are in section 16. Verified on the shipped build 2026-11-24: Chrome browser gate
green at viewports 1950/1400/1180/920/901/586 (16.4, 16.5), `verify_layout_v1` 39 checks 0 failed,
full matrix rerun 538 tests 0 failures/errors/skipped, §15.3 amendment recorded in
`docs/FIRELINE-WEB.md`. Caveat found on the way: the short gate form `./gradlew test wasmJsTest`
silently skips the KMP `jvmTest` side; name the tasks explicitly.**

**Round 17 CLOSED (2026-11-24): shell chrome cleanup (owner-directed from a page screenshot).
RELEASE STATUS block removed from the shell, firmware library controls compacted, native file
button replaced by an `IMPORT .ASM` chip. Section 17.**

**Status of this record: W3 IS CLOSED (steps 1 to 8; section 10 is the gate close-out).** Step 1
lifted the mission session layer out of `:fireline-app` into the shared engine so the browser
console and the MISSION screen cannot drift apart. Step 2 proved on a device that the shipped
Android behaviour is unchanged, against the committed W2 fixtures, and it found one pre-existing
defect that is now fixed. Step 3 added the `:fireline-web` wasm module scaffold (data layer plus its
fixture gate). Step 4 added the console shell: a pure render layer, the wasm entry point and a
harness page, verified in Chrome against the built wasm. Step 5 added the 20x20 world grid and its
symbol legend. Steps 6 to 8 added the editor, the `localStorage` library, `.asm` import/export and
`#run=` permalinks with the `SPEED`/`PANIC` row and the share block, and then measured gates G1 to
G6 (section 10). The matrix stands at 534 tests, zero failures.

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
- Autoplay races a scripted STEP loop in the browser: the console starts a mission pacing timer the
  moment it opens, so a capture that presses `RESET` and then steps can land on a tick the timer
  advanced. Stop the pacing first (`RUN` chip toggles it off), then `RESET`, then drive
  `fl-btn-step`. The first tick-30 capture was contaminated this way (it showed `A 0` at a completed
  run) and was replaced by the paused-timer capture in section 10.3.
- `PermalinkSources.kt` writes the TSV rows only. The `# commit <sha>` provenance line is added by
  the recipe (`printf '# commit %s\n' "$(git rev-parse HEAD)" | cat - <tsv> > ... && mv ...`);
  redirecting the record step straight over the file silently drops the header and
  `permalink_fixture_gen.py --check` then refuses with "carries no '# commit <sha>' provenance
  header". That is the intended fail-closed behaviour, not a generator bug.

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
`://` colon (fixed in the render); and an inverted `assertFalse(html.contains("class="btn"))`
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

## 10. Steps 6 to 8 close-out: editor, library, import/export, permalinks, and gates G1-G6

Date 2026-11-22, branch `codex/edge-walls-e1` (HEAD `9f16765` at the start of this step). This is
the step that closes W3: the shell of section 7 gained the `SPEED`/`PANIC` row, the editor, the
`localStorage` library, `.asm` import and export, `#run=` permalinks and the share block, and the
W3 acceptance gates G1 to G6 were then measured rather than assumed.

**Verdict: W3 gates met.** 53 `:fireline-web` wasmJs tests green (50 after the shell steps, plus
the 3 interop tests of section 11), the browser console reproduces the committed tick-30, tick-46
and tick-102 field values of the same firmware the Android app keeps in its own store, and the
browser's own share link decodes back to those exact bytes (md5 `e80d319c4361b40d7be27971433065fe`
on both sides).

### 10.1 What was added

| path | role |
|---|---|
| `fireline-web/src/wasmJsMain/.../WebLibrary.kt` | §8 browser firmware store: namespaced key `fireline.web.v1`, `storeNames` on the Android store contract (`NAME.ASM`, uppercase, `[A-Z0-9][A-Z0-9_-]*`, max 24 chars before the extension), a line codec that round-trips arbitrary sources plus the selected entry, and `normalizeName` shared by SAVE and IMPORT |
| `fireline-web/src/wasmJsMain/.../Permalink.kt` | §8 share-link codec `FLW1-` + base64url(gzip(payload)), payload `version mission_id seed_override name_len name source`; `encode`, `decode` with refusal reasons, `fragmentFor` (`#run=` form) |
| `fireline-web/src/wasmJsMain/.../ConsoleRender.kt` | presentation-only additions: `SPEED` chips and the selected chip, `PANIC` (`fl-btn-panic` / `fl-btn-panic-on`), share row (`LINK`/`COPY`/`DOWNLOAD .ASM`), editor block that replaces the one `#fl-source` pane instead of adding a second scrollable box, library block with per-entry LOAD/DELETE ids, import and library mission selects |
| `fireline-web/src/wasmJsMain/.../Main.kt` | the only DOM owner: pacing timer, EDIT/APPLY/SAVE, `fl-import-file` reading, `DOWNLOAD .ASM` via a blob URL, COPY (fragment first, clipboard second), permalink open on load, library persistence |
| `fireline-web/console.html` | harness page: `@media (pointer: coarse)` touch-target rules (49 px minimum on chips, links, mission-card buttons, selects, the file input and the name field) and the import file input |
| `docs/web/fixtures/PermalinkSources.kt`, `permalink_fixture_gen.py`, `permalink_sources.tsv`, `permalink_fixtures.json`, `W3PermalinkFixtures.kt` | the permalink fixtures: sources recorded from live engine text, links built by the generator's own gzip framing and re-verified with Python's independent `gzip` module, then pinned as Kotlin constants (13 scenarios) |
| `fireline-web/src/wasmJsTest/.../W3RenderTest.kt` (10), `W3LibraryDraftTest.kt` (8), `W3PermalinkTest.kt` (8), `W3InteropTest.kt` (3) | the gate tests |

### 10.2 Gate table

| gate | requirement (`docs/FIRELINE-WEB.md` §4) | how it was measured here | status |
|---|---|---|---|
| G1 | one engine, one source of truth | `ConsoleModelTest.consoleRunEqualsHeadlessAuthorityForEveryAuthoredFirmware` compares the console run with `FirelineRun.runWithGuard` for all six missions and every authored failing firmware, on wasmJs; `:fireline-engine:jvmTest` / `wasmJsTest` re-run from the same `commonMain`; the permalink fixtures re-derive byte-identically from their committed TSV (`PERMALINK FIXTURES REPRODUCIBLE: 13 scenarios, gzip cross-verified`, recipe in `docs/web/fixtures/README.md`) | met |
| G2 | golden event traces pinned | unchanged W2 fixtures re-asserted (`parity_traces.json`, 13 scenarios x 12 ticks) | met |
| G3 | fingerprint matrix pinned | unchanged W2/W4 fixtures re-asserted (`parity_matrix.json`, 13 rows, engine 2) | met |
| G4 | the emulator STEP sequence reproduces on web: same tick-30 field values, mission 02 finishing at tick 102 | live Chrome STEP sequence (section 10.3) against the STEP path driven on `emulator-5554` (section 10.4b), ticks 12/30/46/102 field for field | met (with the engine-v2 note below) |
| G5 | `.asm` roundtrip against `:fireline-app` | store bytes pulled out of the installed app, imported into the browser, and the browser's own share link decoded back to them, section 10.4 | met |
| G6 | limits enforced before allocating | `WebInputLimits` refusals in wasm tests, plus the live browser control with a 1 202-line picked file; touch targets, motion and reading semantics measured on the built console (section 10.5) | met |

Note on the G4 fingerprint text: §2/§4 still print the engine-v1 value `$47e28d84`
(`docs/FIRELINE-WEB.md:40, 231`), and section 2 of this record cites it as the device value. The
committed matrix is engine 2 since the edge-wall step E1b (`PLAN-EDGE-WALLS.md` §11.1 is the
re-baseline), where mission 02 reference is `d3ea47119fe3b101`. The measurements below were taken
against a fresh HEAD build on both sides and equal the committed engine-2 anchors; the v1 values
are historical, exactly as the record-status note at the top of this file says. `W3PermalinkTest`'s
header comment was corrected to stop quoting the v1 digest as if it were the live anchor.

### 10.3 G4: the same STEP sequence in a real browser

`console.html` served by a local server that maps `.wasm` to `application/wasm` and `.mjs` to
`text/javascript` (`python -m http.server` serves `.mjs` as `text/plain` and the browser then
refuses the module import). Measurement recipe, because the first attempt was contaminated: the
console autoplays a freshly opened mission, so the authoritative capture stops the pacing timer
first (`RUN` chip), presses `RESET` (prompt returns to `TICK 0`), and only then drives
`fl-btn-step` tick by tick. One STEP is always exactly one `ConsoleRun.step()`; the timer only
chooses how often that call happens.

| tick | browser showed | committed anchor | match |
|---|---|---|---|
| 12 | `A 0 X 0 Y 0 SP $0BF PC $115`, `LAST OP MOVE (1/2)`, `OP TICKS LEFT1`, `TICK 12`; telemetry `FIRE_DIST 10`, `FIRE_ANGLE 3`, `FIRE_LEVEL 35`, `EXIT_DIST 12`, `EXIT_ANGLE 3`, `CIV_ANGLE 255` | `snapshots.json` `02-ref-t12` (pc 277 = `$115`, sp 191, `MOVE (1/2)`, FIRE_DIST 10, FIRE_LEVEL 35) | yes, every field |
| 30 | `A 6 X 0 Y 0 SP $0BF PC $12D`, `LAST OP MOVE (1/2)`, `FLAGS Z0 N0 C0`, `OP TICKS LEFT1`, `TICK 30`; telemetry `FIRE_DIST 6`, `FIRE_ANGLE 0`, `FIRE_LEVEL 55`, `LOCAL_FIRE 0`, `SMOKE 0`, `EXIT_DIST 8`, `WIND_DIR 0`, `WIND_FORCE 0` | `WEB-W1-RECORD` §9 STEP x 30 (`A 6`, `PC $12D`, `last MOVE (1/2)`, `FIRE_DIST 6`, `FIRE_LEVEL 55`), asserted in wasm by `ConsoleModelTest.mission02Tick30StopMatchesTheCommittedStepByStepState` | yes, every field |
| 46 | `A 4 ... SP $0BF PC $11E`, `LAST OP JMP 286`, `OP TICKS LEFT0`, `FIRE_DIST 3`, `FIRE_LEVEL 70`, `EXIT_DIST 5` | `snapshots.json` `02-ref-t46` (pc 286 = `$11E`, a 4, `JMP 286`, FIRE_DIST 3, FIRE_LEVEL 70) | yes, every field |
| 102 | prompt `FL:> LIVE MONITOR // MISSION 02 / CORRIDOR // TICK 102`, `LAST OP MOVE (2/2)`, `VM FAULT no`, telemetry `FIRE_DIST 0`, `FIRE_LEVEL 0` | `parity_matrix.json` `2-reference`: tick 102, SUCCESS, `d3ea47119fe3b101`; `snapshots.json` `02-ref-final` (rover 11,10, `last_op MOVE (2/2)`, PC `$145`) | yes |
| 0 after APPLY | `applied MY-LOOP.ASM (mission 2)`, `TICK 0`, note `assembler: 0 errors, 6 bytes, 3 source lines.` | editor path re-opens a fresh session (the 375-byte authored run is untouched) | yes |

Event-log lines in the same capture read `039 JMP 286 | `, `040 IN port16 | `, `041 JZ (skip) | ` -
the three-digit padded tick and the ` | ` separator the shared session layer is pinned to
(`MissionSessionTest.sessionLogAndHexPaddingKeepTheirShape`). A `RUN` press on a running session
completes it (prompt reached `TICK 102`), which is the RUN path the W1 record measured on device as
equal to the STEP path.

Other live-browser behaviour recorded in the same session, because §8 promises it: SAVE writes
`fireline.web.v1` and the library survives a reload (`MY-LOOP.ASM` back with LOAD/DELETE buttons
`fl-btn-library-load-MY-LOOP.ASM`, `fl-btn-library-delete-MY-LOOP.ASM`); COPY leaves the link in
the address bar as a `#run=` fragment; the grid renders 400 cells and `#fl-source` is the only
scrollable pane (editor replaces it rather than adding a second one).

Touch and overflow check on the same page under `pointer: coarse` emulation (CDP touch emulation,
390 x 844, `matchMedia("(pointer: coarse)").matches` confirmed true): 31 interactive nodes, minimum
measured height 49 px, zero nodes below 44 px, `scrollWidth == clientWidth` (390 == 390, no
horizontal overflow), 31 nodes at the desktop width with the pre-existing 20 px chips back
(`pointer: coarse` is a genuine device query, not a viewport proxy), and the single scrollable pane
`fl-source` in both modes. At desktop width the frozen `11px 1fr / auto 1fr` pairing of `§15.3` is
untouched: the new rules only add `min-height` under coarse pointers.

### 10.4 G5: the roundtrip against `:fireline-app`

Both sides ran engine 2 from the same HEAD (the debug APK was rebuilt from this tree and
reinstalled on `emulator-5554` before the store was read).

1. The app's own store was read straight out of the installed debug app's database
   (`adb exec-out run-as robofight.fireline cat databases/fireline.db`, table `files`): row 1
   `CORRIDOR-REF.ASM`, 375 characters, md5 `e80d319c4361b40d7be27971433065fe` (row 2
   `CORRIDOR-RT21.ASM`, the section 2 export, same md5).
2. Those bytes were picked into the browser through the real `fl-import-file` input
   (`DataTransfer` + one `change` event, i.e. the same handler a picked file reaches). The status
   line reads `imported CORRIDOR-REF.ASM into mission 2`, the source pane renders 25 lines, and the
   assembler note reads `assembler: 0 errors, 75 bytes, 25 source lines.` - identical to the
   authored run. The tick-12, tick-30 and tick-46 bands of section 10.3 were then measured on this
   imported run, so the values are the app's bytes in the browser, not a lookalike.
3. COPY on that run put a 579-character `FLW1-` link in the address bar. Python's independent
   `gzip` module decompressed it to mission 2, seed override 0, name `CORRIDOR-REF.ASM` and a
   375-character source whose md5 equals the store row. Byte-for-byte, the captured link equals the
   pinned generator link for scenario `2-reference`, so the browser encoder, the Python generator
   and the app store describe one firmware.
4. `W3InteropTest` pins that loop as a test: `androidStoreBytesImportThroughTheWebGateAndReachThe
   MatrixAnchor` (normalization to the store name form, gate refusal list empty, tick 30 readout,
   tick 102 SUCCESS, fingerprint `d3ea47119fe3b101`) and `browserShareLinkDecodesToTheAndroidStore
   BytesAndReEncodesByteForByte` (decode to the store bytes, re-encode to the same string, reopen
   the link to the same anchor). The Android half of the roundtrip (SAF import/export, `cmp`
   byte-identical, same md5) is section 2 of this record and section 12a of the plan.

### 10.4b G4 in the Android container, and the two containers agreeing

The same STEP sequence was then driven on `emulator-5554` (`Medium_Phone`, density 420) against a
build of this tree. Identity first: the installed `robofight.fireline` `base.apk` md5
`04debd9da66e7db6b1f93c7663d84027` equals `fireline-app/build/outputs/apk/debug/fireline-app-debug.apk`,
and that APK was assembled from a HEAD whose engine files are identical to this commit's (this W3
close-out touched only `fireline-web` and `docs`), so the readouts below are this tree's engine in
this tree's app, not a leftover install. Run setup was opened on mission 02 with `CORRIDOR-REF.ASM`
(`SEED 2002 (FIXED)`), left PAUSED, and advanced by tapping STEP once per tick (the STEP path, not
autoplay), each tick boundary confirmed from a fresh dump through both channels, the grid
`content-desc` `running mission 02 tick N of 120` and the `TICK N / 120` chip. The device readouts:

- tick 12: `TICK 12 / 120 // A 0 X 0 Y 0 SP $0BF PC $115 // last: MOVE (1/2) // FIRE_DIST 10 //
  FIRE_LEVEL 35`
- tick 30: `TICK 30 / 120 // A 6 X 0 Y 0 SP $0BF PC $12D // last: MOVE (1/2) // FIRE_DIST 6 //
  FIRE_LEVEL 55`
- tick 46: `TICK 46 / 120 // A 4 X 0 Y 0 SP $0BF PC $11E // last: JMP 286 // FIRE_DIST 3 //
  FIRE_LEVEL 70`
- completion: the result surface header reads `MISSION COMPLETE` (the engine's own event line for
  success, `MISSION SUCCESS`, is the last log line `102 MOVE (2/2) | MISSION SUCCESS`) at
  `TICK 102/120` with `HP 100`, `HEAT 64`, `SHIELD 0`, `firmware 75 B / 25 instructions` and state
  fingerprint `$d3ea4711`. The mission card afterwards reads `BEST: SUCCESS  TICK 102`. The mission
  cards on the list screen carry the `engine v2` line.

Device-only fields at the same checkpoints (no web counterpart, no conflict): the grid surface's
`content-desc` carries `rover at x 2 y 7 facing S` at tick 12, `x 3 y 10 facing E` at tick 30 and
`x 6 y 10 facing E` at tick 46; the source window shows `exec: MOVE @line 15` mid-move at tick 30
and `next: IN FIRE_DIST @line 11` at tick 46; `HEAT 0` on the run bands at ticks 12 to 46 and
`HEAT 64` at the finish, because heat accrues in the firing leg after tick 46; the result card adds
`water used 50`, `shots 5`, `burning cells 0` and the objective chips `EXTINGUISH_ALL`, `REACH_EXIT`;
the untouched mission cards read `no run recorded yet for engine v2`, the store's engine marker.

Every field equals the browser capture in section 10.3, and the device readouts equal the committed
anchors (`snapshots.json` `02-ref-t12` / `02-ref-t46`, `parity_matrix.json` `2-reference`): same PC,
same SP, same `A`, same last instruction, same telemetry. That is gate G4 in its original wording -
the emulator STEP sequence reproduced in the browser, tick for tick and field for field - measured
from both containers on one HEAD build.

### 10.5 G6 in the browser, and the two defects this step found

The import control refused a 1 202-line picked file with the engine's own text
(`TOO-BIG refused: source has 1202 lines, limit is 1200`) and left the library untouched; the wasm
side asserts the byte ceiling (`limit is 8192`) and the non-ASCII refusal with the same reason
strings before any session or store write (`W3InteropTest.oversizedAndHostileImportsAreRefusedWith
EngineReasons`, `W3PermalinkTest.oversizedSourceIsRefusedBeforeAnyAllocation`).

Defect 1 (wasm codegen, cost the most time here): `ConsoleRender.button()` was written as one
`+` chain containing an `if` expression, and the Kotlin 2.3.20 wasm backend emitted a truncated tag
for the active case - `class="chip btn on</span></div></div>` with no `id` and no label (84
characters where 112 belong). The failure first looked like a bad test assertion because Gradle
also reported every task `UP-TO-DATE` after source edits (only `:fireline-web:clean` forced a real
recompile). `button()` is now a statement-form function with a `val cls` and an explicit `return`,
with a comment naming the miscompile so nobody "simplifies" it back into one chain.

Defect 2 (§8 promise, not a crash): COPY used `document.execCommand("copy")` through a throwaway
textarea, which a browser may refuse; the reported status said `clipboard refused by the browser`
while the address bar still carried no fragment, so the user had no link at all. `copyShareLink()`
now writes the fragment into the page's own address first (`setFragment`) and then attempts the
clipboard, reporting `clipboard refused by the browser, link is in the address bar` when the
clipboard is denied. The payload stays in the fragment either way, so §8's no-server-copy promise
holds.

G6 measurements (touch targets, motion, reading semantics), taken on the built console
(`:fireline-web:wasmJsNodeDevelopmentLibraryDistribution`, re-served and re-loaded):

- Control sizes at a phone-class viewport (393 x 851 CSS px, `deviceScaleFactor` 1, mobile
  emulation, Chrome DevTools `Emulation.setDeviceMetricsOverride`): `RUN`, `STEP`, `RESET`, `EDIT`,
  the three `SPEED` chips, `PANIC`, `COPY`, `DOWNLOAD`, `SAVE LIBRARY` and `DELETE LIBRARY` all
  measure 49.0 CSS px tall. At `dpr` 1, 1 CSS px = 1 dp, so the touch targets are 49 dp against the
  48 dp gate. The rule behind them is CSS, not viewport math: `.btn { font-size: 13px;
  letter-spacing: .12em; padding: .85rem 1.3rem; min-width: 74px }`, which measures 49 x 74 px at a
  770 px viewport too - a wider viewport keeps the same CSS box and therefore a physically larger
  target.
- Motion: the console stylesheet declares no `transition` and no `animation` (measured by walking
  every CSS rule in the page), so there is nothing for `prefers-reduced-motion` to suppress; the
  gate is met by having no motion in the first place rather than by a reduced variant.
- Reading semantics: `lang="en"`, a viewport meta tag, every form control reached by a name (the
  import file input and import mission select sit inside their visible `<label>`s; the library
  target select gained `aria-label="load library entry into mission"` because its visible helper
  text sits outside the control), and the event list carries `aria-label="EVENT LOG"` matching its
  own heading. The world readout is a 400-cell grid plus the visible symbol legend, which names
  every glyph the grid uses (`wall`, `rover + facing`, the four fire bands, `water packet`, `refill`,
  `exit`, `civilian`, `fuel`, `protected asset`), so the legend is the accessible description of the
  grid.
- Honest gap, recorded rather than papered over: the `FL:> ... TICK n` prompt is not an `aria-live`
  region, deliberately, because during autoplay it would re-announce every 20 to 50 ms and drown any
  other announcement. A screen reader reaches the same numbers through the RF-8 STATE and TELEMETRY
  band cells on demand. Auto-announcing run state in a way that does not spam is a W4 polish item,
  not a W3 gate.

### 10.6 What did not move, and the fixture provenance of this step

`git diff --numstat HEAD` over `fireline-engine`, `rf8-core`, `fireline-app`, `app`, `engine` and the
tracked fixture files (`parity_matrix.json`, `parity_traces.json`, `snapshots.json`) returns an empty
set: this W3 close-out changed seven tracked files, all in `fireline-web` or `docs` (`docs/FIRELINE-WEB.md`
+11/-7, `docs/WEB-W3-RECORD.md` +228/-11, `docs/web/fixtures/README.md` +32/-0, `fireline-web/console.html`
+41/-0, `ConsoleModel.kt` +22/-0, `ConsoleRender.kt` +284/-23, `Main.kt` +405/-15), plus
eleven new files (the `Permalink.kt` and `WebLibrary.kt` data layer, the four W3 wasm test files plus
the generated `W3PermalinkFixtures.kt`, and the four permalink fixture files). The engine is
untouched: `FIRELINE_ENGINE_VERSION` stays 2 and every committed anchor
(`parity_matrix.json`, `parity_traces.json`, `snapshots.json`) is re-asserted unchanged, not re-recorded.

Permalink fixture provenance follows the house rule: the record step ran at HEAD
`9f16765d666189196aee07fb0021ec8fccfbf699` (the previous commit), `permalink_sources.tsv` carries that
sha in its `# commit` header, and the generated `permalink_fixtures.json` and `W3PermalinkFixtures.kt`
land in the commit that follows. `permalink_fixture_gen.py --check` on that tree left both generated
files byte-identical.

### 10.7 W3 close-out status

`:fireline-web:wasmJsTest` 53 tests, 0 failures, 0 errors: `ConsoleModelTest` 8, `ConsoleRenderTest`
8, `WorldGridTest` 8, `W3RenderTest` 10, `W3LibraryDraftTest` 8, `W3PermalinkTest` 8,
`W3InteropTest` 3. Close-out matrix on this tree with `--rerun-tasks` (106 tasks executed,
`BUILD SUCCESSFUL`, counts read from the per-target result XMLs): `:rf8-core:jvmTest` 15/0/0,
`:rf8-core:wasmJsTest` 15/0/0, `:fireline-engine:jvmTest` 192/0/0, `:fireline-engine:wasmJsTest`
192/0/0, `:fireline-web:wasmJsTest` 53/0/0, `:fireline-app:testDebugUnitTest` 40/0/0,
`:app:testFreeDebugUnitTest` 3/0/0, `:app:testProDebugUnitTest` 9/0/0, `:engine:test` 15/0/0,
`:fireline-app:assembleRelease -x lintVitalRelease` green. Total 534 tests, 0 failures, 0 errors
(433 at the section 8 matrix, plus 29 new `:fireline-web` tests in this W3 close-out and 28 engine
tests carried by the wall scenarios and the store-rule suites since).

W3 is closed with this step: the browser console is a usable FIRELINE console that drives the
shared `MissionSession`, keeps its own firmware library, imports and exports the same `.ASM` bytes
the Android app keeps, and opens a run from a share link. W4 (landing page and hosting) is
deliberately not touched here.

## 11. Dated shell amendment: three regions move (2026-11-23)

The owner viewed the console in Edge on a real window and directed three layout moves. They are
the shell deviation written up as `docs/FIRELINE-WEB.md` 15.2a, which is the binding exception to
15.2 and 15.3 for the wasm console only. No numeric value was invented for the shell: each moved
region inherits the width of the column it now lives in.

Measured in the harness for amendments 11 a-c (dev server, fresh wasm, viewport 770x471, mission 02
selected). At this
width the 900px media rule has already collapsed the pair grid to one column, so all three regions
share the content column; the alignment result is the same at 900px and above, where the pair grid
is live and each region keeps its own column's width:

| region | box | x / width | follows |
|---|---|---|---|
| world column (`pairworld`) | 51 / 653px | after the mission rail |
| 20x20 grid | 51 / 246px, height 233px | inside the world column |
| status line (`statusbar`) | 51 / 653px, y 824 | grid bottom 814, same column, 0.6rem gap, legend below it |
| firmware column (`pairfirm`) | 51 / 653px | source pane, controls, assembler note |
| RF-8 STATE band | 51 / 653px, y 1549 | assembler note bottom 1549, flush left and right with the firmware field |
| TELEMETRY band | 37 / 681px | full shell band width |
| share LINK block | 37 / 681px, y 1963 | TELEMETRY bottom 1950, 13px band gap as in 15.3 |

`git diff --name-only` over `fireline-engine`, `rf8-core`, `fireline-app`, `app`, `engine` is empty
for this step; `FIRELINE_ENGINE_VERSION` stays 2. Five tracked files change: `docs/FIRELINE-WEB.md`
(15.2a plus the 15.4 "only place" row), `fireline-web/console.html` (two CSS rules), `ConsoleRender.kt`,
`ConsoleRenderTest.kt`, `W3RenderTest.kt`.

### 11.1 The dev harness server, two requirements

`python -m http.server` cannot serve this page. It reports `.mjs` as `text/plain`, and Edge/Chrome
refuse a module import with a non-JavaScript MIME type: every request logs 200 while `#fl-shell`
stays empty, which reads as "the page renders nothing to do". A second trap is caching: with only
`Last-Modified`, a rebuilt wasm can be replayed from the browser's disk cache, so the old layout
looks like a failed edit (this session observed a 2026-11-22 module on a 2026-11-23 build). The
harness server therefore maps `.mjs` to `text/javascript` and `.wasm` to `application/wasm` and
answers 200s with `Cache-Control: no-store`. Verify with
`curl -sI <url>/build/compileSync/wasmJs/main/developmentLibrary/kotlin/opcode-arena-fireline-web.mjs`.

### 11.2 The wasm builder defect this step hit again

The first rebuild of the amended shell dropped the function-call operands at the tail of the long
`+` chains that build `pairfirm` and `pairworld`: `stateBandMovesIntoTheFirmwareColumnUnderTheControls`
failed with the STATE band absent from the firmware column while `linkBlock()`, added in the same
step, rendered correctly. `build/classes/kotlin/wasmJs/main/default/ir/strings.knt` contained the new
literals and the layout strings, so the front end was fine and only the emitted output was truncated.
This is the class already recorded in 10.5 (the truncated `class="chip btn on</span></div>` tag), and a
full `--rerun-tasks --no-build-cache` build after `rm -rf fireline-web/build` did not clear it. The fix
is structural, not a rebuild: `firmwareColumn()` and `worldColumn()` now append to a `StringBuilder`
statement by statement instead of folding one `+` chain. Shell builders must not end a `+` chain with a
function call.

`firmwareColumn()` and `worldColumn()` are the two shell builders written this way; the tests anchor on
the band positions themselves (`indexOf("<div class="fields"><h4>" + band title)`) rather than on a
guessed column slice, because a slice boundary drawn at the first `</div><div class="fields">` sits
inside the control block and produced a false negative.

### 11.3 The same session's follow-up: three more moves (11 d, 11 e, 11 f)

The owner looked at the amended console and asked for three more changes, recorded in
`docs/FIRELINE-WEB.md` 15.2a items 4-6:

- **11 d**: the `next:` / `exec:` preview line is removed from the shell. The instruction in
  flight is already shown by the `>` marker in the firmware pane, the `LAST OP` and
  `OP TICKS LEFT` cells of the STATE band and the prompt line's event, so the line was a
  fourth copy of one fact. `fl-next` is no longer rendered and no handler referenced it.
- **11 e**: the assembler note moved to the end of the firmware column, under the RF-8 STATE
  band, so the column reads firmware, controls, state, verdict.
- **11 f**: the EVENT LOG section (heading, `<ul>`, its 8 newest lines) is gone. Its slot at
  the end of the world column is taken by a bordered pacing block `.fields.pacing` that holds
  the `SPEED 1 / 5 / 20 / 50 ticks per second` choices and `PANIC uncapped, no speed limit`.
  Wording, ids, the `chip on` look and the 49 dp touch targets are unchanged; the CSS moved
  from `.consolecontrols .inforow`/`.chip` to `.fields.pacing .inforow`/`.chip` so only the
  container changed. `fl-events`, `eventLog()` and `EVENT_LOG_LINES` were deleted, not hidden.

Consequence found by the tests, not by reading: the failure reason name was on screen only as
the last log line. `ConsoleRenderTest.mission01NeverShootsRenderCarriesTimeExpiredAndItsAnchor`
failed once the log was gone, i.e. `TIME_EXPIRED` had no second carrier. It is now asserted at
the model (`run.failureReason()`) and at the `bad` objective chips, and the spec records that
the page no longer prints the reason name; the reason is still in the share link.

Tests moved with the shell: `W3RenderTest` gained `nextPreviewAndEventLogAreGoneAndPacingTakesTheLogSlot`
(absence of `fl-next`, `<ul class="events">` and the heading; exactly one `.fields.pacing`, inside the
world column, under the grid, before `pairfirm`, with the §15.4 wording and one PANIC button);
`WorldGridTest.worldColumnRendersWindThenGridThenLegendThenEventLog` became `...ThenPacing`;
`ConsoleRenderTest` log-line assertions now assert `run.logTail(1)` on the model, and its
single-occurrence gate asserts the assembler note under the state band and the single control block
inside the firmware region.

Measured in the same harness after the follow-up (fresh wasm, 1 426 100 B, viewport 770x471, mission 02 open).
No region invents a width: grid 51 / 246 wide (grid table), status line 51 / 653, pacing block 51 / 653
with all four speed chips 49 tall, control block 51 / 653 h49 (`RUN`/`STEP`/`RESET`/`EDIT` 49 tall),
state band 51 / 653, assembler note 51 / 653, TELEMETRY band and LINK band 37 / 681 each.
DOM order on the page: grid 581, status line 824, legend 901, pacing block 959, control block 1373,
RF-8 STATE band 1422, assembler note 1615, TELEMETRY 1700, LINK 1892. No `fl-next`, no
`<ul class="events">`, no `EVENT LOG` text anywhere in the shell (39 779 chars of shell HTML), no
horizontal overflow.

### 11.4 Matrix for this step

Full matrix on this tree with `--rerun-tasks --no-build-cache` (`BUILD SUCCESSFUL`, `RC=0`, counts
read from the per-target result XMLs): 536 tests for amendment 11 a-c, and 537 for 11 a-f,
0 failures, 0 errors. `:fireline-web` wasmJsTest 56 per target (ConsoleModel 8, ConsoleRender 8,
WorldGrid 8, W3Render 13, W3LibraryDraft 8, W3Permalink 8, W3Interop 3).


## 12. Same session, third shell amendment: the grid square becomes the layout anchor (2026-11-23)

Owner-directed (`docs/FIRELINE-WEB.md` 15.2a items 7 and 8). The pair grid was
`minmax(0,1fr) + minmax(320px,1.1fr)` while the grid table stayed a fixed 15px lattice inside the
left column, so the grid was neither half the width nor matched to the firmware column.

### 12.1 The anchor and what derives from it

`--col = max(320px, (100vw - 74.56px - 19.2px) / 2)` is the pair column: half of the console
body's content width (74.56px is the chrome chain: body margin 2x18, page padding 2x19.2, console
border 2, consolebody padding 2x12.8) minus half of the 1.2rem gap. `--cell = clamp(11px, 34px,
(col - 5px) / 21)`, `--grid-h = 21 * --cell + 2px`, `--pane-h = max(180px, --grid-h - 354px)`,
where 354px is the chrome under the pane in the firmware column (h4 25 + margin 8 + run row 34 +
gap 8 + pacing 56 + gap 6.4 + band 179 + band margin 12.8 + note 56 + note margin 8). The pair
grid is two `minmax(0,1fr)` tracks, the grid fills its column, and the pane takes the grid height
minus its chrome, so the two columns end at the same y.

### 12.2 Measured in the harness (window 1400x1000, viewport 1370, mission 02 open)

body content 1272; pair tracks 613.422px each; grid 54 / 613 x 635; world column 54 / 613;
firmware column 687 / 613; grid width / body content = 0.482 (the column, i.e. half the available
width minus half the gap); firmware column / body content = 0.482 (equal); pane 281 = grid height
minus the 354px chrome; world content 794 = box 794, firmware content 794 = box 794, column bottom
delta 0px; speed/PANIC chips 28px, run buttons 34px; pacing row border 0px, background transparent;
no horizontal overflow, no column overflow.

Collapsed mode (window 800x600, viewport 770, single column pair): grid 233 x 233 with the 11px
floor, columns 653, pane 232 from the 900px media rule, no overflow. The anchor rule is a
two-column rule and does not apply when the pair grid collapses; recorded rather than hidden.

### 12.3 Iteration trail (what did not work, so it is not repeated)

- `max-width:50%` on `.pairworld` shrinks the column to half of its grid track (613 -> 307), it is
  not half of the body: a percentage there is resolved against the track, not the page. Reverted.
- Sizing the columns from a fixed `--grid-h` plus magic constants for the status line and the
  legend clipped the legend (`scrollHeight 897` against a 816px box) because the legend's wrap
  height changes with the column width. Reverted; the pane formula carries the alignment instead.
- `Emulation.setDeviceMetricsOverride` does not reflow this harness (`innerWidth` changes, media
  queries and layout do not). `Browser.setWindowBounds` on the browser window does.

### 12.4 Static gate and tests

`docs/web/verify_layout_v1.py` gained `shell_css_gate_for_15_2a()`: 9 checks on the harness CSS
(equal half tracks, the `--col` anchor, cells derived from it, the pane formula, no inline pane
height, unboxed pacing row, 28px chips, 34px run buttons, 49px touch override). The frozen mockup
checks are untouched: 31 checks, 0 failed.

Tests moved with the shell: `ConsoleRender.sourcePane`/`editorPane` no longer emit an inline
`--src-height` (`SOURCE_PANE_HEIGHT_PX` stays the frozen 15px reference the mockups use),
`WorldGridTest.worldColumnRendersWindThenGridThenStatusLineThenLegend` replaces the pacing marker
(the world column is wind/grid/status line/legend), `W3RenderTest` gained
`nextPreviewAndEventLogAreGoneAndPacingIsAPlainRowInTheFirmwareColumn` (unboxed `.pacing`, inside
the firmware column, under the run row, above the STATE band, still exactly two readout bands) and
its editor assertion now requires the absence of `--src-height:`.

Full matrix on this tree with `--rerun-tasks --no-build-cache` (`BUILD SUCCESSFUL`, `RC=0`, counts
from the per-target result XMLs): 537 tests, 0 failures, 0 errors, same totals as 11.4. Engine
modules show no diff; `FIRELINE_ENGINE_VERSION` stays 2.

## 13. Same session, fourth shell amendment: the two run rows start at the same line (2026-11-23)

Owner-directed (`docs/FIRELINE-WEB.md` 15.2a item 7a): align the top edge of the RF-8 STATE band
with the top edge of the VITALS/STATE status line across the two columns, and resize the firmware
area accordingly.

### 13.1 The rule

The pane height is no longer chosen to end the firmware column at the world column's bottom; it is
chosen so the two readout blocks begin at the same y. The offset is identical on both sides:
`--row-offset: 100px`. Below the grid's bottom edge the world column has 100px before the status
line (its 12.8px margin-top, plus the 17px firmware heading and its 8px margin that lift the
firmware column's content, minus the 8px that collapses between the pane and the run row). Below
the pane the firmware column has 100px before the STATE band (4px collapsed gap + 34px run row +
8px gap + 56px pacing row + 0px collapsed gap). Hence `--pane-h = max(180px, --grid-h - 100px)`.

### 13.2 Measured in the harness (window 1400x1000, viewport 1370, pair content 1246, mission 02 open)

VIEW mode: grid 613 x 635, pane 535 (was 281), status line top 669px below the pair's top edge,
STATE band top 669px, **top delta 1px** (subpixel rounding); no horizontal or column overflow.
Consequences on the same measurement: the world column's last block (58px legend) ends 794px below
the pair top while the firmware column's last block (56px assembler note) ends 917px, i.e. the
firmware column is ~123px taller; in EDIT mode the editor name row adds 56px of chrome above the
band, so the band sits 56px lower there. Both are recorded consequences of putting the symbol
legend in the world column (11 b) and the run/pacing rows in the firmware column (11 f), not
properties of the new rule.

### 13.3 Iteration trail

- The first attempt used 108px, derived by adding the pane-to-run gap as 12px. Measured band top
  landed 7px above the status line top; the real gap is 4px because `margin-bottom:.5rem` on the
  pane collapses with `margin-top:.7rem` on the control block. Corrected to 100px.
- The harness page's CSS is inline in `fireline-web/console.html`. Editing it and re-navigating to
  the same URL served a cached stylesheet (`getComputedStyle(pair)` still reported the retired
  `--pane-h` with 354px). A cache-busting query (`console.html?cb=...`) is required to measure a
  CSS-only change.
- `docs/web/verify_layout_v1.py` check for the pane formula now asserts `--row-offset:100px` and
  the new `--pane-h`; the retired `--firm-chrome` and `--world-extra` declarations were removed
  from the harness CSS (31 checks, 0 failed).


## 14. W3 shell layout amendment 14 (2026-11-23): the pacing row is one line, and every shell gap is one of two tokens

Owner request, verbatim (with a screenshot of the old row): "Change the "SPEED" row. Always in
one line, like: SPEED [  1 ]  [  5 ] [ 20 ] [ 50 ] ticks per second ### And again, take care of
the visible gaps between rows, blocks, section etc." The screenshot showed the failure mode: the
`5 SELECTED` label was much wider than the other chips, `50` wrapped onto a second line, and the
PANIC wording sat beside the toggle on that same wrapped line.

### 14.1 What the row is now

`ConsoleRender.pacingBlock()` emits one row, `DIV.pacing > DIV.inforow`, whose children are a
fixed sequence of cells: `B.speedlabel` (SPEED), the four `speedChip()` cells, `SPAN.speedunit`
(`#fl-speed-unit`, "ticks per second"), `SPAN.gap`, `B.paniclabel` (PANIC), `SPAN.panichint`
("uncapped, no speed limit", the §15.4 wording kept in the markup), the PANIC button, and
`SPAN.gap.fill` (`#fl-pacing-fill`, the slack absorber). The console still renders exactly two
readout bands (`.fields`), the pacing row is not one of them, and the row stays between the run
row and the RF-8 STATE band.

`speedChip()` changed shape: the selected choice is a button cell again
(`<button class="chip btn on" aria-pressed="true">5<small>SELECTED</small></button>`) instead of a
wide `SPAN.chip.on`, so all four cells share one width and the choice stays clickable. §15.4's
SELECTED wording survives in the markup and in `aria-pressed`; the CSS hides the `small` element
visually (`clip-path:inset(50%)`) and the yellow fill is what the eye reads as the selection.

The row is a nowrap flex line, not a grid: labels, unit and hint are rigid (`white-space:nowrap`,
`flex:0 0 auto`), the four cells are equal (`flex:1 1 42px`, clamped `min-width:30px`,
`max-width:64px`), `.pacing .gap` absorbs the slack. Below 700px the cells clamp at 26px; below
600px the PANIC hint text is dropped so the rigid cells cannot overflow the column. `@media
(pointer: coarse)` still raises the chips to 49px.

### 14.2 Iteration trail (what failed first)

- First attempt was a CSS grid row (`grid-auto-flow:column`, then
  `grid-template-columns:auto repeat(4, minmax(30px,1fr)) auto 1fr auto auto minmax(52px,auto)`).
  A `1fr` chip track gives every chip the track's minimum content width, and `5 SELECTED` is
  74px+ while a bare `20` cell is 35px: the chips rendered uneven (30/73/35/35) and the rigid
  cells pushed the hint and the toggle past the column. At viewport 1370 the page overflowed,
  `scrollWidth 1489` against `clientWidth 1355`, offenders `SPAN.panichint` (173px) and
  `BUTTON#fl-btn-panic` (74px). A grid of content-sized tracks plus a filler track works, but the
  equal-cell flex form is shorter and keeps the four cells provably equal, so the row is flex.
- `.pacing .chip.btn` had two declarations after the rewrite (the layout cell properties and the
  old visual properties). The verifier's `rule(".pacing .chip.btn{")` reads the first one only, so
  it reported the chip's `min-height:28px` as missing. The two declarations were merged into one
  rule, and the redundant `.pacing .chip.on` selector was dropped.

### 14.3 Alignment consequence (fixes the record 13 constant)

`--row-offset` was derived while the pacing row was 56px tall (two lines). With the row at one
28px line the constant was 16px too generous: the STATE band sat 16px ABOVE the status line. It is
now `--row-offset:84px`, measured rather than derived (margin collapse between the pane, run row,
pacing row and band means the arithmetic of declarations does not equal the measured chrome).
Re-measured at viewport 1370 (window 1400x1000, mission 02 open, `?cb=` cache-bust): grid 613x635,
pane 551, `band.top - status.top = 0px`. The record 13 values (row-offset 100px, pane 535, delta
1px) stand as history for the pre-amendment markup.

### 14.4 Measurements (one-line rule and gap rhythm)

Chip widths, row height and line count per real window size (window resized through CDP
`Browser.setWindowBounds`, never `Emulation.setDeviceMetricsOverride`; Chrome clamps this window at
586px, so 586 is the narrowest measurable case):

| window | viewport | chip widths | pacing lines | row height | row width vs column | page overflow |
|---|---|---|---|---|---|---|
| 1400 | 1370 | 36, 36, 36, 36 (+PANIC 36) | 1 | 28px | 613 vs 613 | none (1355/1355) |
| 900 | 870 | 60, 60, 60, 60 (+60) | 1 | 28px | 753 vs 753 | none (855/855) |
| 700 | 670 | 26, 26, 26, 26 (+26) | 1 | 28px | 553 vs 553 | none (655/655) |
| 586 | 556 | 26, 26, 26, 26 (+26) | 1 | 28px | 439 vs 439 | none (541/541) |

Gap audit at viewport 1370 and 870, after `--gap-major:12.8px` / `--gap-related:8px` were declared
on `body` and used for the block gaps (7 major and 3 related usages in `console.html`): pane to run
row 13px, run row to pacing row 8px, pacing row to STATE band 13px, band to assembler note 13px,
grid to status line 13px, status line to symbol legend 13px, objectives to pair 8px. Every measured
shell gap is one of the two tokens (13 or 8 rounded px). The assembler note measures 56px in the
two-column layout and 93px in the collapsed layout because the note text wraps; it stays last in
the firmware column and does not affect the band alignment, which is anchored above it.

### 14.5 Gate status

`speedRowCarriesTheGoldenMasterChoicesAndOneSelectedChip` and
`nextPreviewAndEventLogAreGoneAndPacingIsAPlainRowInTheFirmwareColumn` now assert the new shape:
one `SPEED` label cell, one `ticks per second` unit cell, the `PANIC` label plus the `uncapped, no
speed limit` hint cell, exactly one `inforow`, exactly four speed cells and one filler cell, the
fixed cell ORDER asserted by ascending index, exactly one `SELECTED</small>`, exactly one
`aria-pressed="true"`, and no selection mark while PANIC is on. The toggle test
(`panicRowCarriesTheGoldenMasterWordingAndToggles`) was updated for the split label/hint cells.
`docs/web/verify_layout_v1.py` gained five checks (nowrap row, equal cells, hidden SELECTED wording,
no narrow wrap rule, the two gap tokens) and asserts `--row-offset:84px`: 35 checks, 0 failed.
Full matrix `check --rerun-tasks --no-build-cache`: see 14.6.

### 14.6 Matrix status and open items

Gate run for this amendment (`--rerun-tasks --no-build-cache`, per-target result XMLs): 537 tests,
0 failures, 0 errors, 0 skipped = rf8-core 15+15, fireline-engine 192+192, fireline-web 56
(ConsoleModel 8, ConsoleRender 8, WorldGrid 8, W3Render 13, W3LibraryDraft 8, W3Permalink 8,
W3Interop 3), app 3+9, fireline-app 40, engine 15. `docs/web/verify_layout_v1.py`: 35 checks,
0 failed. Engine modules untouched (`git status` shows only `fireline-web/console.html`,
`ConsoleRender.kt`, `W3RenderTest.kt` and the two docs files).
`check` is NOT a usable gate on this host: it drags in `lintAnalyzeFreeDebug` /
`lintAnalyzeDebug`, which fail offline. Name the test tasks instead.

### 14.7 Open items carried forward

- The one-line rule is proven from 586px upward; below Chrome's minimum window width it was not
  measurable on this host. Below 600px the PANIC hint text is hidden, a documented exception.
- EDIT mode is still unaligned (the editor name row and the editor error line add 56-93px of chrome
  above the band). Recorded in 13 and still open.
- The mockups and `docs/web/gen_web_mockups.py` keep the §15.4 slash-list pacing wording; only the
  wasm shell changed. §15.6 item 1 is met by the mockups, not the shell (standing open item).



## 15. W3 shell layout amendment 15 (2026-11-23): PANIC gets its own line, the titlebar is gone

Owner request, verbatim (two screenshots, one of the pacing row, one of the console header):
"Almost perfect. (1) The "PANIC" row needs a line break to start at a new line. (2) The top
"MISSION 02://CORRIDOR" header is not required. Remove it completely."

### 15.1 What changed

- `pacingBlock()` now emits two rows inside `DIV.pacing`: `DIV.inforow` (SPEED label, the four
  choice cells, the unit label, the slack filler `#fl-pacing-fill`) and
  `DIV.inforow.pacingpanic` (PANIC label, `uncapped, no speed limit`, the toggle, an inert gap).
  Each row is nowrap and each was proven to be one line (record 14's rule stands per row); the gap
  between the two rows is `--gap-related` (8px measured).
- `consolePanel()` no longer renders the titlebar (`MISSION nn//NAME` + `T+0000nnnn`) and
  `console.html` no longer carries `.titlebar` rules. `mhead` (mission label + briefing), the
  objline tick chip and the status line `TICK n` already carry those facts. `decPad` stays in use
  (source pane line numbers, library option labels).

### 15.2 Alignment had to be re-measured again

Adding a 28px pacing line plus an 8px gap pushed the STATE band 36px down, and removing the 38px
titlebar does not change any gap inside the pair. A probe sweep over `--row-offset` in the live page
(`document.querySelector('.pair').style.setProperty('--row-offset', N)` then reading
`band.top - statusline.top`) came out exactly linear: 100:20, 104:16, 108:12, 112:8, 116:4,
**120:0**, 124:-4, 128:-8. So `--row-offset:120px` (was 84px) and `--pane-h` follows it. Re-measured
in the shell at viewport 1370: `align = 0px`, rows 28px + 28px, both 613px wide (the firmware
column is 613px).

### 15.3 Measurements after the amendment

| window | viewport | SPEED row | PANIC row | chips | row lines | overflow |
|---|---|---|---|---|---|---|
| 1400 | 1370 | 28px, 613px | 28px, 613px | 64,64,64,64 (+64) | 1 + 1 | none (1355/1355) |
| 900 | 870 | 28px, 753px | 28px, 753px | 64,64,64,64 (+64) | 1 + 1 | none (855/855) |
| 700 | 670 | 28px, 553px | 28px, 553px | 64,64,64,64 (+64) | 1 + 1 | none (655/655) |
| 620 | 590 | 28px, 473px | 28px, 473px | 64,64,64,64 (+64) | 1 + 1 | none |
| 586 | 556 | 28px, 439px | 28px, 439px | 61,61,61,61 (+64) | 1 + 1 | none (541/541) |

Cell extents inside the 439px column at viewport 556 (the tightest case): SPEED line ends at x439
(label 0-36, cells 43-104, 113-174, 184-244, 254-315, unit 324-439, filler 0 wide); PANIC line ends
at x107 (label 0-36, toggle 43-107, hint hidden below 600px). No row spills past its column at any
measured width. Gap audit at every width: pane->run 13, run->SPEED 8, SPEED->PANIC 8,
PANIC->band 13, band->note 13, grid->status 13, status->legend 13, obj->pair 8. The one gap not
carried by the tokens is `mhead->objline` = 10px, inherited unchanged from the master's
`.mhead{padding:0 0 .5rem;margin-bottom:.6rem}`; left as the master's own measure.

### 15.4 Stale-artifact trap (this is why the first measurement lied)

After the Kotlin edit and a `wasmJsTest` run, the browser still rendered the OLD shell (one pacing
row, a titlebar, `.titlebar` count 1). The build was fine (`strings.knt` IR carried `pacingpanic`,
`compileSync` wasm re-written at 17:27) and the dev server sends `Cache-Control: no-store`, but the
page kept importing a module that no longer matched the file. Recovery: start the static server on a
NEW port with the same `docs`-independent root (`fireline-web/`) and navigate there; the fresh page
rendered `titlebar els 0` and the two rows. Rule: when the DOM contradicts a green build, prove the
artifact (file timestamp + IR literal) and then re-fetch from a fresh origin before touching the
markup again.

### 15.5 Gate status

`ConsoleRenderTest` now asserts the removal (`MISSION 01://NOZZLE CHECK` absent, no
`<div class="titlebar">`, no `T+0000`, `MISSION 01 / NOZZLE CHECK` present in `mhead`).
`W3RenderTest` asserts two rows: exactly one `<div class="inforow">` (SPEED) plus one
`<div class="inforow pacingpanic">` (PANIC), the SPEED cell order by ascending index, no PANIC
token on the SPEED line and no speed cell on the PANIC line; the toggle test reads the panic row by
its class. `docs/web/verify_layout_v1.py` gained the two checks (own panic row, no `.titlebar` rule)
and asserts `--row-offset:120px`: 37 checks, 0 failed. Full matrix (`--rerun-tasks --no-build-cache`,
per-target XMLs): 537 tests, 0 failures, 0 errors, 0 skipped, identical in shape to record 14's
matrix (app 12 / engine 15 / fireline-app 40 / fireline-engine 384 / fireline-web 56 / rf8-core 30),
log `$TMPDIR/w3_rec15_matrix.log`, `--rerun-tasks --no-build-cache`, `RC=0`.

### 15.6 What is still open

- `--row-offset:120px` is measured at viewport 1370 only; the equality holds as long as the 180px
  pane floor is not active. Below 900px the pair collapses to one column and the top-edge rule does
  not apply (the two rows are still one line each).
- EDIT mode: unmeasured after the titlebar removal, still open from record 13.
- The TELEMETRY band's second row (12 cells across a 10-cell track) leaves a wide empty middle
  between `WIND_DIR` and `WIND_FORCE`. It comes from the golden master's band geometry, not from
  this amendment; flagged for the owner, not changed.




## 16. W3 shell layout amendment 16 (2026-11-23): the grid scaled itself out of its column

Owner request, verbatim (one screenshot of mission 02 CORRIDOR at 1370px):
"There is a scaling bug on drawing the grid cells. Visible on the screenshot. The cells are
reaching into the firmware area on the right side."

### 16.1 What the screenshot showed

Probe of the served page at viewport 1370: the pair is 1346px wide, so each column is 613.422px
and the firmware column starts at x 663.4. `.grid` reported 748.6px of tracks inside a 611.4px
box, i.e. its content ran to x ~791, 128px past the column edge and straight through the firmware
column. Cell edges measured 28.281px, so 22 tracks x 28.281 + 2 = 622px of nominal box against a
748.6px track sum: the tracks were no longer in agreement with the box they were drawn in.

### 16.2 Why record 15's `--cell` scaling broke (the CSS half is not trustworthy)

Record 15's scaling rule was `--cell: max(320px, calc((var(--cw) - 19.2px) / 2))` written on the
`PAIR` element, with the grid reading `repeat(20, var(--cell))` plus its own `1fr` label column.
Chrome 142 resolves a custom property twice here, and the two resolutions differ:

| where the value was read | value at viewport 1370 |
|---|---|
| `getComputedStyle(document.documentElement).getPropertyValue('--cell')` (written on the pair) | 305.6px |
| the same `--cell` inside `.grid`'s track sizing (`gridTemplateColumns` computed) | 28.281px |
| `--cw` as seen by `.grid` (`max-width` computed on the grid) | 626.4px |
| the pair column that `--cw` was supposed to describe (`.pairworld` clientWidth) | 613.422px |

A length expression inside a grid's own track/box sizing is re-resolved against the grid's own
box rather than the column it sits in, so the expression fed back into itself: bigger tracks make
a bigger box make a bigger `--cw` make bigger tracks. Nothing in the CSS half of the shell can be
trusted to hold 22 tracks inside a column, because the number the tracks want and the number the
box has are computed from different reference boxes.

Attempts made and rejected, all measured on the live page: `%` tracks inside a percentage-sized
grid (125px tracks in a 288px box, 2750px overflow); `cqw` with and without `container-type`
(ignored, the tracks stayed at their fallbacks); `vw` tracks with a declared box (tracks resolved
to viewport fractions inside a 152px box); a JS pass that wrote raw px onto the grid (worked,
748px in a 748px box) but cannot stay applied because the wasm console rewrites the shell subtree
on every render and an observer aimed at `#fl-shell` before the shell exists never fires.

### 16.3 The fix: the scaling is arithmetic in the render layer

- `WorldGrid.cellSizePx(worldColumnPx)`: `((column - 2) / 22).coerceIn(11, 34)`. 22 is the track
  count (`LABEL_TRACK_CELLS 2 + GRID 20`), 2 is the border, 11 is the §15.5 narrow floor, 34 is
  the ceiling the pair's own `--cell` clamp already had. Integer division rounds down, so a cell
  never rounds up out of its column.
- `WorldGrid.cellStyleAttr(worldColumnPx)`: one inline `style` written on the PAIR element,
  carrying only `--cell:Npx` and `--grid-h:21N+2px` in raw px (`N` = the measured cell). That
  token is everything the layout receives; one number decides the tracks, the box, the rows and
  the firmware pane height, and every downstream value is a plain token substitution that Chrome
  cannot re-resolve against another box.
- The CSS `.grid` rule builds its whole geometry from that one token: tracks
  `calc(2 * var(--cell, 11px)) repeat(20, var(--cell, 11px))`, rows `repeat(21, var(--cell, 11px))`,
  box `width/max-width:calc(22 * var(--cell, 11px) + 2px)` and `height:calc(21 * var(--cell, 11px) + 2px)`.
  Tracks and box are then literally the same arithmetic term in `cell`, so they cannot disagree.
  The earlier `max-width:var(--grid-h,317px)` clamp is dropped: clamping the grid's WIDTH to a
  HEIGHT-derived token was itself a box/track disagreement (at the scaled cell 27px the box wants
  596px against a 569px clamp).
- `Main.kt` measures the laid-out world column (`currentWorldColumnPx()`, the `clientWidth` of
  the previous render's `.pairworld`) and passes it through `ConsoleRender.renderConsoleHtml`
  into `worldColumn` and the pair's inline style. Rendering is two-pass: the first render of an
  empty container has no measurement (CSS fallback `--cell:11px`), and a synchronous `relayout()`
  re-renders once with the measured column (rAF callbacks did not fire reliably through the wasm
  interop, and the relayout pass never arms a follow-up, so the loop terminates).
  `relayoutIfChanged()` makes a tick render free: it only re-renders when the measured column
  implies a different cell than the one the last render was built for.
  `watchLayoutBreakpoint()` re-lays out on the pair's 900px breakpoint flip and on window resize.
  Interop trap recorded the hard way: a `js()` snippet may only refer to its own function
  parameters (a borrowed identifier compiles but throws a JS ReferenceError that arrives as an
  opaque `WebAssembly.Exception`), and a `matchMedia` handler must arrive as an explicit `js()`
  parameter.
- `console.html`: the scaling comment now points at the pair's inline style; the CSS token
  fallback `11px` covers the first render, and the 900px media query pins `--cell:11px` for the
  stacked narrow pair. The measured-layout script that the earlier probes used is removed (it
  cannot beat the render loop).

### 16.4 Measurements after the fix

Re-measured 2026-11-24 against the shipped dev-library distribution (`build/compileSync/wasmJs/main/
developmentLibrary/kotlin/`, rebuilt 09:48), served from the repo root on a fresh port (fresh port
defeats Chrome's URL-keyed ES module cache). Window bounds set to target + 30px chrome offset; the
viewport column below is the page's measured `window.innerWidth`. Same six viewports as the 2026-11-23
probe; every number reproduced (gate script `rec16_gate_final.py`, rows in `rec16_measure.json` /
`rec16_measure_final.jsonl` under the Hermes scratch cache).

| viewport | world col | cell | pair inline style | cell px | grid box W | track sum | into-firmware gap (neg = stays inside col) | cells | gate |
|---|---|---|---|---|---|---|---|---|---|
| 1950 | 613 | 27 | `--cell:27px;--grid-h:569px;` | 27.00 | 596.00 | 594.0 | -17.42 | 400 | PASS |
| 1400 | 613 | 27 | `--cell:27px;--grid-h:569px;` | 27.00 | 596.00 | 594.0 | -17.42 | 400 | PASS |
| 1180 | 522 | 23 | `--cell:23px;--grid-h:485px;` | 23.00 | 508.00 | 506.0 | -13.92 | 400 | PASS |
| 920 | 392 | 17 | `--cell:17px;--grid-h:359px;` | 17.00 | 376.00 | 374.0 | -15.92 | 400 | PASS |
| 901 | 382 | 17 | `--cell:17px;--grid-h:359px;` | 17.00 | 376.00 | 374.0 | -6.42 | 400 | PASS |
| 586 | 469 | 21 | `--cell:21px;--grid-h:443px;` | 21.00 | 464.00 | 462.0 | -5.03 | 400 | PASS |

The cell column is exactly `cellSizePx(world col)` in every row ((613-2)/22 = 27, (522-2)/22 = 23,
(392-2)/22 = 17, (382-2)/22 = 17, (469-2)/22 = 21 with integer division), and the grid box is
`22 * cell + 2` wide and `21 * cell + 2` tall in every row. At 586 the pair is stacked (§15.5) and the
inline measured cell (21px) overrides the media query's `11px` fallback; the column still measures
469px, so the grid still fits its own column. 586 is the narrowest width measurable on this host
(Chrome window clamp).

### 16.5 Gate status

Browser gate (the §15.3 battery in `rec16_gate_final.py`, run per viewport): 400 cells; cells square
(|w - h| <= 0.5px); cell >= the 11px §15.5 floor; the computed track list is exactly 21 tracks, one
`2 * cell` label track plus 20 `cell` tracks; 21 rows all `cell`; |track sum - grid box W| <= 2px and
|row sum - grid box H| <= 2px (the 2px is the border); the box arithmetic `22c + 2 x 21c + 2` holds;
grid box <= world column + 2px; the grid stays inside its column (the into-firmware gap is negative in
every row, 5.03..17.42px of slack, versus the pre-fix +128px crossing); no left-edge crossing; zero
horizontal document overflow (`scrollWidth == clientWidth`); no titlebar. **Result: 6 viewports, 6 PASS,
0 failures.**

- `docs/web/verify_layout_v1.py`: 39 checks, 0 failed (2026-11-24, against the shipped `console.html`;
  the two new checks are "grid box is the same cell term as its tracks" and "no height-derived clamp on
  the grid width").
- Full matrix rerun 2026-11-24, `--rerun-tasks --no-build-cache`, explicit task list: **538 tests,
  0 failures, 0 errors, 0 skipped** (62 fresh result XMLs) = engine 15, rf8-core 15 jvm + 15 wasmJs,
  fireline-engine 192 jvm + 192 wasmJs, fireline-web 57 (WorldGrid 9 after the new scaling test,
  ConsoleRender 8, ConsoleModel 8, W3Render 13, W3LibraryDraft 8, W3Permalink 8, W3Interop 3),
  fireline-app 40, app 3 + 9. 537 (record 15) + 1 new scaling test = 538.
  Gate-form caveat recorded here: the short form `./gradlew test wasmJsTest` does NOT run the KMP
  `jvmTest` side (`test` is not a task on `:rf8-core`/`:fireline-engine`), so it measures 331, not
  538; the matrix above uses the explicit task names from the `robofight-web-wasm` skill.
- Spec amendment: `docs/FIRELINE-WEB.md` 15.3, the world-grid row now carries the measured-column
  derivation `cell = clamp((columnPx - 2) / 22, 11, 34)` written inline on the pair element, and the
  box `22 * cell + 2 x 21 * cell + 2` as the same term as its tracks.


## 17. W3 shell chrome cleanup 17 (2026-11-24): the release block out, the library compact

Owner request, from a screenshot of the FIRMWARE LIBRARY section: "Remove the 'FL:> RELEASE
STATUS' block and prettify the firmware section (decrease the button sizes, etc.)." The
screenshot's symptoms: a bordered release panel sitting between the mission picker and the
status4 strip; library buttons at the old 49px desktop scale; and the native file input
rendering OS-language chrome ("Datei auswählen", "Keine Datei ausgewählt") inside the shell.

### 17.1 What changed

- **RELEASE STATUS removed.** The static `<div class="cta">` is gone from `console.html`, and the
  `.cta` / `.cta strong` rules and the `.cta a` coarse-gate selector with it. 15.2 item 8 is
  amended in `docs/FIRELINE-WEB.md` as the new dated deviation 15.2b 1; the 15.1 golden master
  keeps the block (the frozen mockups are not edited). No test asserted the block.
- **Library compact (15.2b 2).** Chips (SAVE CURRENT / DELETE LIBRARY) 13px font + 49px
  min-height -> 12px font + 30px; entry buttons (LOAD / DELETE minis) 13px + 49px -> 12px + 26px;
  selects 13px + .4rem padding -> 12px + .3rem; `.libentry` padding .5rem -> .4rem. The
  `pointer:coarse` block gained `.librarycontrols .chip.btn`, `.librarycontrols
  .importlabel.file` and `.libentry .mini` at min-height:49px, so the 48dp web touch gate keeps
  holding for exactly the controls that shrank.
- **File input.** `ConsoleRender` now marks the import label `importlabel file`; the label chip
  carries the shell wording `IMPORT .ASM`, and the input is visually hidden
  (`position:absolute;width:1px;height:1px;clip-path:inset(50%)`). The input keeps its id, so the
  `Main.kt` import wiring and the W3RenderTest `type="file" id=` assertion are untouched; the
  picker opens on the label click and the input stays keyboard-focusable (hover/focus paint the
  chip border yellow). This kills the OS-language chrome (German on this host) without any
  localization machinery.
- Comment fix: the `console.html` scaling comment still named `WorldGrid.gridStyleAttr`; corrected
  to `cellStyleAttr` written on the pair element (record 16 shipped form).

### 17.2 Gate status

- Full matrix 2026-11-24 (`--rerun-tasks --no-build-cache`, explicit task list): **538 tests,
  0 failures, 0 errors, 0 skipped** (62 fresh result XMLs; counts identical to record 16).
- `docs/web/verify_layout_v1.py`: **45 checks, 0 failed** (39 + the six new 15.2b checks: no
  RELEASE STATUS block, no `.cta` styling, 30px chips, hidden file input behind the chip, 26px
  entry buttons, coarse gate covers the library controls).
- Live Chrome measurement (dev-library distribution rebuilt 11:05, served on a new port,
  `?cb=rec17view`): `.cta` count 0; `RELEASE STATUS` absent from the page text; SAVE CURRENT and
  the IMPORT .ASM chip 124.9 x 30.2 / 100.4 x 30.2px; selects 27.6px; entry minis 50 x 26px; the
  hidden file input measures 1 x 1px; all library wording English. Screenshot review confirmed one
  uniform button family with no native file chrome.
- Known accepted looseness (not a gate): the control row flex-wraps `DELETE LIBRARY` to a second
  line only below the width where the row no longer fits; at desktop content width all five
  controls share one row.

## 18. Firmware target 18 (2026-11-25): one select instead of two, any firmware into any mission

Owner user tests on the live console page (screenshot of the FIRMWARE LIBRARY row: a `load into`
select under `SAVE CURRENT` / `IMPORT .ASM` / `into <select>` / `DELETE LIBRARY`) asked what the
`load into` line is for and whether the row above already covers it. Answer after tracing: the two
selects were the two halves of the same question (which mission an IMPORT is checked and opened in,
which mission a library LOAD opens in), a duplicate the owner never saw as two decisions. They are
merged into ONE firmware target, plus the owner rule "any firmware should be able to load into any
mission".

### 18.1 What changed

- **One firmware target (15.2c 1).** `ConsoleRender` renders exactly one `<select>`,
  `#fl-firmware-target`, labelled `firmware target`, with an `aria-label` (`load or import firmware
  into mission`) because the visible label sits outside the control. `#fl-import-mission` and
  `#fl-library-target` are gone, and with them `ConsoleUiState.importMissionId` and
  `libraryTargetMissionId`; one field `firmwareMissionId` carries the target.
- **Target defaults to the open mission.** `Main.kt` writes `firmwareMissionId` on every mission
  open: the picker (`openMission`), a `#run=` permalink (`openFromPermalink`), a library LOAD, an
  IMPORT, and `SAVE CURRENT` (which stores the open run's firmware, so its own label and its stored
  mission cannot disagree). Opening mission 06 makes 06 the target with no second choice.
- **The target is named at the button:** `LOAD 06`, `SAVE CURRENT INTO 06` (15.2c 1). The label
  wording `firmware target` is one constant (`ConsoleRender.FIRMWARE_TARGET_LABEL`) in the shell and
  the render layer.
- **Any firmware loads into any mission (15.2c 2, owner rule).** `ConsoleModel.draftFirmware` gates
  the SOURCE (`WebLibrary.sourceProblems` -> `WebInputLimits`, then the real RF-8 assembler inside
  `MissionSession`) and checks nothing mission-local. It now also reports an engine refusal as
  `DraftResult.Rejected` instead of throwing, so a bad program shows its reason on the status line
  instead of breaking the shell (`draftFirmware` is a pure draft function under 15.1; the headless
  path stays authoritative for what a run is, 12a D1).
- **Scale and touch (15.2c 3).** The merged select keeps the 12px control scale; the
  `pointer:coarse` block gained an explicit `.librarycontrols select,.librarytarget select{min-height:49px}`
  (the blanket `select{min-height:49px}` already covered it; naming it keeps the gate honest if the
  blanket rule ever moves).
- **Tests:** `W3RenderTest` +3 (`singleFirmwareTargetSelectOffersEveryAuthoredMission`,
  `libraryButtonsNameTheMissionTheyActOn`, `everyOpenMissionSelectsItselfAsFirmwareTarget`),
  `ConsoleModelTest` +1 (`everyAuthoredFirmwareDraftsIntoEveryAuthoredMission`, all 36 authored
  mission pairs). Two test-side bugs found while landing them: an assertion demanding
  `<div class="librarytarget">firmware target</div>` (a label cannot be a self-closing div around
  its own select) and an assertion message that cast the draft to `Rejected` even when the draft was
  `Accepted`, which threw `ClassCastException` with an empty wasm message and masked the real result.
  A third, unrelated failure in the same round was the stale-artifact class again: a `BUILD FAILED`
  in `compileTestKotlinWasmJs` (one unresolved reference) left the previous run's result XMLs in
  place, and the two "failures" read out of those dead XMLs. Read the log before reading the XMLs.

### 18.2 Gate status

- Full matrix 2026-11-25 (`--rerun-tasks --no-build-cache`, explicit task list): **557 tests,
  0 failures, 0 errors, 0 skipped** (57 fresh result XMLs) = rf8-core 15+15, engine 15,
  fireline-engine 187+187, fireline-web 63 (ConsoleModel 9, ConsoleRender 8, WorldGrid 13,
  W3Render 15, W3LibraryDraft 8, W3Permalink 8, W3Interop 3), app 3+9, fireline-app 52, plus
  `:fireline-app:assembleDebug` and `assembleRelease -x lintVitalRelease` green. 550 + 7 new tests.
- `docs/web/verify_layout_v1.py`: **58 checks, 0 failed** (55 + three 15.2c checks: merged select at
  the 12px scale, the coarse gate naming the merged select, no superseded select id left in the
  shell). `docs/web/fixtures/verify_snapshots.py` and `verify_crosscheck.py`: RC 0. The 15.2c gate
  comment first pointed at a file name (`docs/WEB-W3-RECORD-18.md`) that this repo does not use;
  corrected to `docs/WEB-W3-RECORD.md` 18.
- Live Chrome measurement (dev-library distribution rebuilt 14:36, served on a NEW port 8823 with
  `?cb=rec18…`): `document.querySelectorAll('select').length` = 1; both legacy ids report `gone`;
  `#fl-card-open-6` -> prompt `MISSION 06 / THIN WALL`, target value 6 (`06 THIN WALL` selected),
  `SAVE CURRENT INTO 06`; pressing it wrote `THIN-WALL-REF.ASM` (status `saved THIN-WALL-REF.ASM in
  this browser`) with buttons `LOAD 06 | DELETE`; switching the one target to 02 renamed the buttons
  to `LOAD 02` / `SAVE CURRENT INTO 02`, and `LOAD 02` opened **mission 06's thin-wall firmware in
  mission 02** (source pane shows its 37 lines, status `loaded THIN-WALL-REF.ASM into mission 2`):
  fingerprint `$765da43f` at tick 2, `$541a8e14` at tick 120, `SUCCESS` at tick 102 with fingerprint
  `$d3ea4711` = mission 02's authored reference. The same firmware loaded into mission 03 the same
  way. The library survived a reload (`THIN-WALL-REF.ASM`, target `02 CORRIDOR`).
- Measurements: target select 27.6px tall, 12px font (desktop 1312px and at 420px); chips
  `SAVE CURRENT INTO 02` 194 x 30.2, `IMPORT .ASM` 100.4 x 30.2, `DELETE LIBRARY` 142.2 x 30.2;
  entry minis 26px; `scrollWidth == clientWidth` at 1312, 420 and 393px. Screenshot review: one
  select labelled `firmware target`, one compact button row, no native OS file chrome.
- Limitation (recorded, not claimed): this host's browser harness cannot flip `pointer: coarse`.
  `setDeviceMetricsOverride(mobile=True)` and `setEmulatedMedia(features=[pointer=coarse,
  hover=none])` both left `matchMedia('(pointer: coarse)').matches` false, so the 49dp target could
  not be measured live. Proof instead: the coarse `@media` block read out of the live CSSOM carries
  `.librarycontrols select, .librarytarget select { min-height: 49px; }` (12 rules in the block),
  its specificity (0,2,1) outranks the desktop-only select rule (0,1,1), and the static gate asserts
  the declaration. Desktop boxes are unchanged, so the target only grows on a real touch device.
- Superseded wording check: the compiled IR strings table carries `firmware target`,
  `fl-firmware-target` and `SAVE CURRENT INTO`, and zero occurrences of `load into`.
- Spec: `docs/FIRELINE-WEB.md` gains §15.2c (dated shell deviation, 2026-11-25) with items 1-3 above;
  15.2b stays as the record-17 deviation.
