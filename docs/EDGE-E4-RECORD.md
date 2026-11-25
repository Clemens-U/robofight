# EDGE E4 RECORD: the web console draws thin edge walls

Step E4 of `PLAN-EDGE-WALLS.md` §7 landed 2026-11-25 on branch
`codex/edge-walls-e1` on top of the E3 landing (`docs/EDGE-E3-RECORD.md`, whose
§5 and §7 are written here). Scope is `:fireline-web` (4 tracked files) and
`docs/web` (fixture generators, fixture data, the layout verifier, the frozen
mockups). `fireline-engine`, `engine`, `rf8-core` and `app` are untouched by
this step, and no parity, permalink or limit fixture moved: `git diff --numstat`
over `fireline-engine engine rf8-core app
docs/web/fixtures/parity_matrix.tsv docs/web/fixtures/parity_matrix.json
docs/web/fixtures/parity_traces.txt docs/web/fixtures/parity_traces.json
docs/web/fixtures/parity_arith.tsv docs/web/fixtures/parity_arith.json
docs/web/fixtures/limit_probe.tsv docs/web/fixtures/W2ParityFixtures.kt
docs/web/fixtures/permalink_fixtures.json docs/web/fixtures/permalink_sources.tsv`
lists nothing. The visual rule is `docs/FIRELINE-WEB.md` **15.8** (the dated
amendment to the frozen §15 layout lock) and `DESIGN-FIRELINE.md` amendment
**A-6** (`docs/EDGE-E3-RECORD.md` §7.1).

## 1. What changed

| File | Lines | Change |
|---|---|---|
| `fireline-web/src/wasmJsMain/kotlin/robofight/fireline/web/WorldGrid.kt` | +122, -9 | `EdgeWallSpec`, `WALL_STROKE_MIN_PX = 2` / `WALL_STROKE_MAX_PX = 4` and `wallStrokePx(cellPx)` (`:107-113`), the border arithmetic `cellLeftBorderPx` / `cellRightBorderPx` / `cellTopBorderPx` / `cellBottomBorderPx` (`:128-131`), `edgeWalls(world)` (`:140`) reading only the engine's renderer copies `wallVertRowMajor()` (20 x 19) then `wallHorizRowMajor()` (19 x 20) in row-major order, `wallSegmentsHtml(world, cellPx)` (`:232`), and `gridHtml(world, worldColumnPx)` appending the overlay after the 400 cells. The legend (`LEGEND_HTML`, `:303-305`) gained the 11th item `<b><span class="edgebar"></span> thin wall, blocks the way</b>`. |
| `fireline-web/src/wasmJsMain/kotlin/robofight/fireline/web/ConsoleRender.kt` | +1, -1 | the grid call now hands the measured world column to the renderer: `WorldGrid.gridHtml(run.currentWorld, worldColumnPx)`, so anchors and stroke are computed from the same `--cell` token the shell wrote. |
| `fireline-web/console.html` | +28, -1 | the 15.8 CSS: `.grid` becomes `position:relative` (`:107-118`), `.wallseg` / `.wallseg.v` / `.wallseg.h` as absolutely positioned, `pointer-events:none` overlay children sized from `--wall-stroke` and `--cell`, `.legend .edgebar` as the bar key (`:124-126`), and the `pointer: coarse` bump inside the existing 15.4/15.2b touch block (`:374-384`). |
| `fireline-web/src/wasmJsTest/kotlin/robofight/fireline/web/WorldGridTest.kt` | +154, -4 | 4 new tests (§2), on top of the 9 frozen W3 tests. |
| `docs/web/fixtures/SnapshotFixtures.kt` | +38 | the snapshot schema gains `WALL_VERT` (20 rows x 19 glyphs) and `WALL_HORIZ` (19 rows x 20 glyphs), generated from the engine copies, plus `wall_count`. |
| `docs/web/fixtures/CrossCheck.kt` | +43, -1 | the crosscheck emits a `WALL` row per snapshot: the two wall grids and the wall count, so the parity gate compares walls and not only cells. |
| `docs/web/fixtures/snapshots.json`, `snapshots.extra.json`, `crosscheck.tsv` | regenerated | 8 pre-existing snapshots (missions 01, 02) stay cell-by-cell identical; 5 mission 06 snapshots join (§4). |
| `docs/web/fixtures/verify_snapshots.py`, `verify_crosscheck.py` | +40/-2, +21/-2 | the wall grids are re-derived from the engine and compared, and `wall_count` is checked against `layout.edgeCount()`. |
| `docs/web/verify_layout_v1.py` | +53 | `shell_css_gate_for_15_8()`: 10 new checks (§4), the 400-cell gate untouched. |
| `docs/web/gen_web_mockups.py` + the 5 mockups | +4, and 4-5 lines each | the frozen legend gains the bar key (11 items), the mission list gains 06 THIN WALL, the snapshot count line reads `(+10 more)`. No mockup grid draws a bar (they render wall-free engine snapshots). |
| `docs/FIRELINE-WEB.md` | +37 | §15.8, the dated amendment quoted by the verifier. |

The data flow is the E1 rule, not a new one: the overlay comes from the
engine's own renderer copies (`FirelineWorld.layout`), the same arrays Android
strokes with `EdgeWallGeometry` and the fixtures record as `WALL_VERT` /
`WALL_HORIZ`. No view infers an edge out of a cell (`PLAN-EDGE-WALLS.md` §4).

## 2. Pinned proofs (unit, wasmJs)

`WorldGridTest` (13 tests, all green; the 4 new ones):

- `thinWallStrokeIsTheAndroidRatioRoundedToWholeEvenPixels` - `wallStrokePx`
  equals 2 at cells 11/15/17 and 4 at 18/21/24/27/28/34 (the `cell * 0.14` value
  rounded half-up, then to the nearest even, tie up), and for every cell in
  `[NARROW_CELL_PX, CELL_MAX_PX]` the stroke is even, inside [2, 4], and below
  half a cell, so the two cell interiors a bar separates stay open.
- `thinWallOverlayDrawsExactlyTheEnginesEdgeSlotsForMission06` - mission 06
  carries 3 edges (the `parity_matrix.tsv` `6-reference` `wall_count`), the
  overlay list is exactly `[v 5,2, h 2,1, h 2,2]` in the two copies' row-major
  order, the rendered `data-edge` keys equal those slots, each `left`/`top`
  equals the border arithmetic for the 320px column's 14px cell, the grid still
  renders 400 cell nodes, no cell carries a `wallseg` class, and the pair hands
  over `--wall-stroke:<stroke>px`.
- `wallFreeMissionsRenderTheByteIdenticalWallFreeGrid` - for missions 01..05 both
  wall copies are all-false, `edgeWalls` is empty, `wallSegmentsHtml` is the
  empty string, no `wallseg` appears anywhere in the rendered console, and the
  frozen 400-cell grid is intact.
- `thinWallAnchorsLandOnTheBorderTheTwoCellsShare` - at 11px, 21px and 34px cells:
  `v 5,2` left is `6 * cell` (the right border of (5,2), the left border of
  (6,2)) and top `3 * cell`; `h 2,1` and `h 2,2` start at `2 * cell` and sit at
  `3 * cell` / `4 * cell` (the bottom borders of rows 1 and 2); the same numbers
  read as cell boxes, the pair's inline `--cell` equals the requested cell, and
  none of the four straddled cells is a wall cell (mission 06 has 0
  `TERRAIN_WALL` cells, the E2 pin).

## 3. Nothing else moved

`git diff --numstat` for this step's `:fireline-web` half lists exactly the four
tracked files of §1 (28/1, 1/1, 122/9, 154/4). The engine-side isolation proof
is the empty diff quoted in the header. The pre-existing 8 snapshot fingerprints
in `docs/web/fixtures/snapshots.json` are unchanged cell by cell:
`28838868ad3468ed`, `f3dd62629ba58d1f`, `245faa6d10316e00`, `b77a46e1e4308ad0`,
`7cbfe49993c17318`, `d0f26fd5c0b02efa`, `9b6e65741beac0ea`,
`d3ea47119fe3b101` - i.e. the `PLAN-EDGE-WALLS.md` §6 anchors (mission 02 finish
at tick 102 with `d3ea47119fe3b101`, mission 01 `never shoots` at tick 60 with
`7cbfe49993c17318`) still hold after the fixture schema gained two wall fields.
No mission source, no firmware, no permalink fixture and no parity row changed.

## 4. Fixture gates

Regeneration digests (md5, before -> after). Only the three fixture data files
moved, and only by adding mission 06 rows:

```
old: b44a2cb99070fdceac48c791727cb992 docs/web/fixtures/snapshots.json        (8 snapshots)
new: b895039fcee572a4186870299fa67e81 docs/web/fixtures/snapshots.json       (13 snapshots)
old: 43cd3b5934f0ffc74aaa8b7d4be1fd03 docs/web/fixtures/snapshots.extra.json
new: 7e6181d1d1dbf95f718588092f472981 docs/web/fixtures/snapshots.extra.json
old: 78e3df5f2ebcd830ec6a2433e8fc0c3e docs/web/fixtures/crosscheck.tsv      (13 rows)
new: 5e18bf99abf8b2d3cbf129c2ee897b61 docs/web/fixtures/crosscheck.tsv      (32 rows)
```

`python3 docs/web/fixtures/verify_snapshots.py` exits 0 (`ALL CHECKS PASSED`);
its mission 06 rows, verbatim:

```
06-ref-t0      tick=  0/300  RUNNING reason=None         rover=(2,2,E) ... fire= 25 burning=1 walls=3 pkts=0 insn='MOVE'   pc=0x100 line=1  label=(entry)      hash=c8c9af857db44cf5
06-ref-t12     tick= 12/300  RUNNING reason=None         rover=(5,3,E) ... fire= 35 burning=1 walls=3 pkts=0 insn='MOVE'   pc=0x118 line=10 label=straight+2  hash=a85a699d9f1ef675
06-ref-t24     tick= 24/300  RUNNING reason=None         rover=(5,3,W) ... fire= 30 burning=1 walls=3 pkts=0 insn='TURN R' pc=0x12A line=16 label=straight+8  hash=f7377c4511a6b05b
06-ref-final   tick= 59/300  SUCCESS reason=None         rover=(17,2,N) ... fire=  0 burning=0 walls=3 pkts=0 insn='WAIT'  pc=0x160 line=34 label=straight+26 hash=61fe3d4c35449094 obj_done=['EXTINGUISH_ALL', 'REACH_EXIT']
06-fail-final  tick= 59/300  FAILURE reason=ROVER_DESTROYED rover=(5,2,E) hp=0 ... fire=145 burning=2 walls=3 pkts=0 insn='SHOOT' pc=0x10C line=4 label=loop hash=42bdbe754583cd57
```

`python3 docs/web/fixtures/verify_crosscheck.py` exits 0 (`CROSSCHECK PASSED`)
and carries the wall row the E4 overlay must agree with:

```
WALL 06-ref-t0  ...................,..(19 dots)..,.....#.............,...   (WALL_VERT: row 2 char 5 set)
                                                     ,..#.................,..#.................,...  (WALL_HORIZ: rows 1 and 2 char 2 set)
```

i.e. exactly the three slots the overlay draws and the Android test pins:
`VERTICAL(5,2)`, `HORIZONTAL(2,1)`, `HORIZONTAL(2,2)`.

`python3 docs/web/verify_layout_v1.py` exits 0 with **55 checks, 0 failed** (45
before this step). The 10 added checks, with their own evidence strings from
that run:

```
PASS 15.8 the grid is the position context for the overlay
PASS 15.8 a wall segment is an absolute overlay child, unclickable (.wallseg{position:absolute;background:var(--wall);pointer-events:none)
PASS 15.8 a vertical segment is stroke-wide and one cell tall (.wallseg.v{width:var(--wall-stroke, 2px);height:var(--cell, 11px);transform:translate(-50%)
PASS 15.8 a horizontal segment is one cell wide and stroke tall (.wallseg.h{height:var(--wall-stroke, 2px);width:var(--cell, 11px);transform:translate(0,-5)
PASS 15.8 the stroke is a render-layer token handed over by the shell
PASS 15.8 the touch gate raises the stroke floor to 4px (.pair{--wall-stroke-touch:4px} .wallseg{--wall-stroke:var(--wall-stroke-touch, var(--wall-stroke))} .legend .edgebar{height:max(4px, calc(var(--cell, 11px) * 0.185))})
PASS 15.8 the legend keys the thin wall as a bar (.legend .edgebar{display:inline-block;width:18px;height:4px;background:var(--wal)
PASS 15.8 the frozen legend carries the thin-wall item
PASS 15.8 the mockup CSS keys the bar
PASS 15.8 the thin wall gets a bar, not a new glyph (legend items: 11)
```

The 400-cell gate of 15.5 and the §15.3 box model are untouched: the overlay
adds no cell node and no grid track.

## 5. Close-out matrix

One `--offline --rerun-tasks` build (Gradle 9.6.0, JBR 25) on 2026-11-25 with
the canonical `PLAN-EDGE-WALLS.md` §11.6 matrix form:

```
./gradlew --offline --rerun-tasks \
  :engine:test :rf8-core:jvmTest :rf8-core:wasmJsTest \
  :fireline-engine:jvmTest :fireline-engine:wasmJsTest \
  :fireline-web:wasmJsTest :app:testFreeDebugUnitTest :app:testProDebugUnitTest \
  :fireline-app:testDebugUnitTest :fireline-app:assembleDebug :fireline-app:assembleRelease -x lintVitalRelease
```

`BUILD SUCCESSFUL in 35s`, `178 actionable tasks: 178 executed`, exit 0. Read
from the result XMLs:

| module | result dir | tests | failures | errors | skipped | XML files |
|---|---|---|---|---|---|---|
| `:fireline-engine` | `jvmTest` | 192 | 0 | 0 | 0 | 19 |
| `:fireline-engine` | `wasmJsNodeTest` | 192 | 0 | 0 | 0 | 19 |
| `:rf8-core` | `jvmTest` | 15 | 0 | 0 | 0 | 1 |
| `:rf8-core` | `wasmJsNodeTest` | 15 | 0 | 0 | 0 | 1 |
| `:engine` | `test` | 15 | 0 | 0 | 0 | 6 |
| `:app` | `testFreeDebugUnitTest` | 3 | 0 | 0 | 0 | 1 |
| `:app` | `testProDebugUnitTest` | 9 | 0 | 0 | 0 | 3 |
| `:fireline-app` | `testDebugUnitTest` | 52 | 0 | 0 | 0 | 7 |
| `:fireline-web` | `wasmJsNodeTest` | 61 | 0 | 0 | 0 | 7 |

Against the §13.5 matrix: `:fireline-web` 57 -> 61, exactly the 4 new
`WorldGridTest` cases; `:fireline-app` 52 matches `docs/EDGE-E3-RECORD.md` §4;
engine, rf8-core and app counts are the E1b values. `jvmTest` is not a task of
`:fireline-web` in this repo (the gate-form caveat of `docs/WEB-W3-RECORD.md`
§11), so the web target measured here is `wasmJsNodeTest`, and the E1b parity
proof for walls stays the two `:fireline-engine` rows (192 = 192 on both
targets). The stale pre-KMP `engine/build/test-results/test/` directory is
counted once, as in E3. The APK this build produced,
`fireline-app/build/outputs/apk/debug/fireline-app-debug.apk`, is 1 288 807
bytes, md5 `c41e2e3edaffa37de3245018477dd9d7`, identical to the APK the E3
device check installed, which is why no device run was repeated for E4.

## 6. Cross-surface agreement (the E4 acceptance line)

The acceptance line in `PLAN-EDGE-WALLS.md` §7 is "web and Android describe the
same edges for the same mission". Three independent channels agree:

| channel | mission 06 edges / result |
|---|---|
| Android unit (`EdgeWallGeometryTest`) | `VERTICAL(5,2)`, `HORIZONTAL(2,1)`, `HORIZONTAL(2,2)`, total 3 for all of `Missions.ALL` |
| Android device (`EDGE-E3-RECORD.md` §5) | 3 wall-coloured components on the canvas at those borders; card `06 THIN WALL`, `BEST: SUCCESS  TICK 59`; accessibility line `thin walls 3` |
| Web unit (`WorldGridTest`) | `v 5,2`, `h 2,1`, `h 2,2`, `edgeCount() == 3`, 400 cells unchanged |
| Fixture (`crosscheck.tsv` `WALL` row, `parity_matrix.tsv` `6-reference`) | `wall_count` 3, final `SUCCESS` at tick 59 with `61fe3d4c35449094` |
| Browser (§7) | 3 `wallseg` nodes at exactly those three borders; run finished at tick 59 reading fingerprint `61fe3d4c` |

## 7. Browser measurement (mission 06 rendered from a share link)

Setup: `http://127.0.0.1:8093/fireline-web/console.html#run=FLW1-...` with the
committed `6-reference` permalink fixture
(`docs/web/fixtures/permalink_fixtures.json`, `link_length` 759). Every viewport
was measured on a fresh load (reload, then `Emulation.setDeviceMetricsOverride`,
then a `resize` dispatch, because the shell measures the world column on
layout). The console opened with `FIRMWARE: THIN-WALL-REF.ASM` and
`MISSION 06 / THIN WALL`.

Anchors are quoted grid-relative: a segment's painted box minus the grid box,
minus the 1px grid border, so `x` is a distance inside the 22 x 21 cell lattice.

| viewport | `--cell` | `--wall-stroke` | grid box (=`22c+2 x 21c+2`) | `v 5,2` inline left/top | `v 5,2` painted x/y/w/h | `h 2,1` | `h 2,2` |
|---|---|---|---|---|---|---|---|
| 320 | 11px | 2px | 244 x 233 | 66 / 33 | 65 / 33 / 2 x 11 | 22 / 33, 11 x 2 | 22 / 44, 11 x 2 |
| 390 | 13px | 2px | 288 x 275 | 78 / 39 | 77 / 39 / 2 x 13 | 26 / 39, 13 x 2 | 26 / 52, 13 x 2 |
| 768 | 29px | 4px | 640 x 611 | 174 / 87 | 172 / 87 / 4 x 29 | 58 / 87, 29 x 4 | 58 / 116, 29 x 4 |
| 1024 | 20px | 4px | 442 x 422 | 120 / 60 | 118 / 60 / 4 x 20 | 40 / 60, 20 x 4 | 40 / 80, 20 x 4 |
| 1312 | 26px | 4px | 574 x 548 | 156 / 78 | 154 / 78 / 4 x 26 | 52 / 78, 26 x 4 | 52 / 104, 26 x 4 |
| 1370 | 27px | 4px | 596 x 569 | 162 / 81 | 160 / 81 / 4 x 27 | 54 / 81, 27 x 4 | 54 / 108, 27 x 4 |

The bars are exactly one cell long, sit on lattice lines (every anchor is an
integer multiple of `cell`), and are `2px` at the narrow cells (the 2px floor)
and `4px` at the wide cells (the 4px cap), i.e. the `wallStrokePx` table of §2
read back in CSS.

Cell alignment, the "on a border, never in a cell" claim measured against the
cell nodes themselves (mission 06 at tick 4, rover parked against the gate):

| viewport | rover cell box (grid-relative) | `v 5,2` left | cell (5,2) left | cell (2,2) / (2,3) | `h 2,2` top |
|---|---|---|---|---|---|
| 320 | 66 / 33, w 11 | 66 | 77 | 44 / 44 | 44 |
| 390 | 78 / 39, w 13 | 78 | 91 | 52 / 52 | 52 |
| 768 | 174 / 87, w 29 | 174 | 203 | 116 / 116 | 116 |
| 1024 | 120 / 60, w 20 | 120 | 140 | 80 / 80 | 80 |
| 1312 | 156 / 78, w 26 | 156 | 182 | 104 / 104 | 104 |
| 1370 | 162 / 81, w 27 | 162 | 189 | 108 / 108 | 108 |

The rover cell (6,2) starts exactly where the vertical bar is (bar left = cell
left = `6 * cell`), the cell to the left of the bar starts one cell further
(`5 * cell`), the bar's width (2 or 4px) is what separates them, and the bar's
length is the cell side. Mission 06 drew no `class="cell wall"` node in any
viewport (0 `TERRAIN_WALL` cells, the E2 pin) and no cell ever carried a
`wallseg` class. The grid box equals `22 * cell + 2` x `21 * cell + 2` at every
width with 400 cell nodes, so 15.3 and the 400-cell gate are untouched.

Run outcome measured in the browser after `RUN` at 50 ticks/s: `TICK 59`,
`fingerprint 61fe3d4c`, the same 3 bars still on the same borders after the
run, against `parity_matrix.tsv` `6-reference` `59 SUCCESS 61fe3d4c35449094`
and the E3 device badge `BEST: SUCCESS  TICK 59`.

Legend, read out of the DOM (`document.querySelectorAll('.legend b')`): 11
items, the second being `thin wall, blocks the way` with a drawn
`<span class="edgebar"></span>` (18 x 4 px) instead of a glyph; the first item
is still `<span class="wall">█</span> wall`. A vision pass over the scrolled
legend screenshot distinguishes them in words: the `wall` key is a full-height
block, the `thin wall` key is "a filled rectangle that is wider than it is tall
... a thin horizontal bar", and its symbol is "a solid drawn bar (a rectangle),
not a text glyph like █". A second vision pass over the 1312px run screen
reported the bars as drawn lines on grid lines, and a third confirmed no glyph
is used for the thin wall.

Touch gate. `Emulation.setEmulatedMedia` with a `pointer` feature does not drive
`window.matchMedia('(pointer: coarse)')` in this harness (`coarse: false` at
every emulated setting), so the gate was measured by replaying the shipped
`@media (pointer: coarse)` block's own rules unconditionally, from the live
`CSSStyleSheet` (rule index 188-189), and comparing before/after:

| viewport | as shipped (fine pointer) | same rules replayed (touch) |
|---|---|---|
| 320 | `--cell 11px`, stroke 2px, bar 2 x 11 | stroke 4px, bar **4** x 11, legend bar 4 |
| 390 | `--cell 13px`, stroke 2px, bar 2 x 13 | stroke 4px, bar **4** x 13, legend bar 4 |
| 768 | `--cell 29px`, stroke 4px, bar 4 x 29 | stroke 4px, bar 4 x 29, legend bar **5.36** |
| 1312 | `--cell 26px`, stroke 4px, bar 4 x 26 | stroke 4px, bar 4 x 26, legend bar **4.8** |

(the shipped coarse rules, as read from the sheet: `.pair { --wall-stroke-touch:
4px; } .wallseg { --wall-stroke: var(--wall-stroke-touch, var(--wall-stroke)); }
.legend .edgebar { height: max(4px, calc(var(--cell, 11px) * 0.185)); }`)

Wall-free control. Mission 01 at 320px rendered `wallseg` count 0 and kept the
identical cell markup, which is the "existing missions declare no edges" rule
measured on the live page rather than only in the unit test.

## 8. What this proof does NOT cover

It measures mission 06 and the mission 01 control in one Chromium build at six
widths. It does not measure a real touch device (`pointer: coarse` had to be
forced by replaying the media block, §7), it does not measure a mission that
carries both a wall cell and a wall edge (no authored mission has both; the
mapping rule is covered by `aFilledWallCellProducesNoSegment` and
`thinWallAnchorsLandOnTheBorderTheTwoCellsShare`), and it does not cover the
Arena (`DESIGN.md`) renderer, which already shipped this mechanic.

The horizontal overflow at 320px (`scrollWidth 350 > clientWidth 320`) was
measured on the wall-free mission 01 control as well, i.e. it is the §15 pair
floor `minmax(320px, 1.1fr)` at a 320px viewport, not something the overlay
introduced. It is recorded here and left as it is: fixing the pair floor is a
§15 layout decision, not an E4 one.

## 9. Deviations

1. Integer stroke on the web. Android strokes `cell * 0.14` as a float (6.99 px
   at a 49.9 px cell, `EDGE-E3-RECORD.md` §5). CSS lengths are painted from a
   token that must survive `translate(-50%,0)` centring, so the web recipe
   rounds the same product in two integer steps (half-up, then nearest even, tie
   up) and clamps to [2, 4]. The two surfaces therefore differ by sub-pixel
   rounding only; the ratio, the floor, the cap and the one-cell length are
   identical (§2, §7).
2. The coarse bump had to move off `.wallseg`. The first attempt,
   `.wallseg{--wall-stroke:max(4px, var(--wall-stroke))}`, was wrong: a rule
   that reads the custom property it declares on the same element references
   itself and is invalid at computed-value time. Measured with the rule
   inserted into the live sheet at 320px: computed `--wall-stroke` came back
   empty and the bar stayed 2px, i.e. the touch floor silently did nothing. The
   landed rule declares the bump on the pair (`.pair{--wall-stroke-touch:4px}`,
   where `--cell` is already inline) and lets `.wallseg` prefer it with the
   inline token as fallback; §7's replay table shows 2px -> 4px with that form.
   History is annotated, not rewritten: the wrong rule and its measurement both
   stay in this record and in the `console.html` comment.
3. The legend bar is keyed by a fixed 18 x 4 px rectangle on a fine pointer, not
   by the `--cell` token, so the key does not change size with the viewport; the
   touch block gives it the Android `cell * 0.185` ratio under the same 4px
   floor (§7 table). The Android swatch is a fixed 24 x 3 dp for the same
   reason (`GridLegend`, `EDGE-E3-RECORD.md` §1).
4. No telemetry counter. An earlier draft of §15.8 promised a `walls` telemetry
   row and a `WALL <n>` runout line. `PLAN-EDGE-WALLS.md` §7 E4 does not ask for
   one and the shipped console does not have one, so §15.8 now states what the
   code does (readouts unchanged; the layout's identity - `layoutDigest`,
   `layoutVersion`, `edgeCount`, the two wall copies - stays engine-side, where
   the fixtures read it). The Android accessibility line ` thin walls N` remains
   the only wall counter on either surface (`EDGE-E3-RECORD.md` §7.3).
5. Snapshot fixture cap. The generated snapshot rows print `tick= 59/300`
   because the snapshot harness caps runs at 300 ticks; mission 06's own limit is
   120 ticks, as the console shows (`tick 37/120`). The cap is the harness's, the
   tick and hashes are the engine's.
6. Screenshot artifacts. The browser screenshots were written to the agent's
   scratch directory (`e4_console_1312.png`, `e4_console_390.png`,
   `e4_legend.png`, `e4_touch_320.png` ... `e4_touch_1312.png`,
   `e4_coarse_*.png`) and are not committed; the record quotes the measurements
   and the DOM dumps they came from, and the sweep table is reproducible from
   the committed permalink fixture.
