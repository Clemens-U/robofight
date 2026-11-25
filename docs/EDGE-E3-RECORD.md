# EDGE E3 RECORD: the Android grid draws thin edge walls

Step E3 of `PLAN-EDGE-WALLS.md` §7 landed 2026-11-25 on branch
`codex/edge-walls-e1` (previous HEAD `e5e7b1e272e12bb9d84fa9b0b04bb1640889a40e`).
Scope is `:fireline-app` only. `:fireline-engine`, `FIRELINE_ENGINE_VERSION` (2)
and every committed fingerprint are untouched; the proof is in "Nothing else
moved" below. The visual rule applied is `DESIGN-FIRELINE.md` amendment **A-6**
(now marked APPLIED in `DESIGN-FIRELINE.md` §9), which is the drawing recipe of
`PLAN-EDGE-WALLS.md` §5: a wall segment from one lattice intersection to the
next, `strokeWidth = cell * 0.14` floored at 2 px, in the existing wall token,
never a glyph and never a cell.

## 1. What changed

| File | Lines | Change |
|---|---|---|
| `fireline-app/src/main/kotlin/robofight/fireline/app/view/EdgeWallGeometry.kt` | 102 | new. The renderer-side mapping engine edge slots to canvas segments: `WallEdgeOrientation`, `WallEdgeSegment`, `WallEdgeLine`, `segmentsOf(vert, horiz)`, `lineOf(segment, cellPx)`, `strokeWidthPx(cellPx)` with `STROKE_FACTOR 0.14f` and `STROKE_FLOOR_PX 2f`. Refuses renderer copies of the wrong size (`"WALL_VERT renderer copy has N slots, expected 380"`). Vertical slot `(x,y)` is the edge RIGHT of `(x,y)`: `x = (col + 1) * cell`, spanning `row..row + 1`; horizontal slot is the edge BELOW `(x,y)`: `y = (row + 1) * cell`, spanning `col..col + 1`. |
| `fireline-app/src/main/kotlin/robofight/fireline/app/mission/GridLegend.kt` | 40 | new. `GLYPHS` (the glyph line moved out of `MissionRunSurface` unchanged), `THIN_WALL_LABEL`, `THIN_WALL_NOTE = "wall, blocks the way"`, `THIN_WALL_ENTRY`, swatch `24` x `3` dp, `REFERENCE_CELL_DP = 19`. |
| `fireline-app/src/main/kotlin/robofight/fireline/app/view/FirelineGridView.kt` | +31, -1 | `edgeWallPaint` as `Paint.Style.STROKE` with `Paint.Cap.SQUARE` (`:86-89`); after the lattice lines and under the frame border, `EdgeWallGeometry.segmentsOf(w.wallVertRowMajor(), w.wallHorizRowMajor())` stroked in `theme.wall` at `EdgeWallGeometry.strokeWidthPx(cell)` (`:139-151`). Cell interiors stay open. |
| `fireline-app/src/main/kotlin/robofight/fireline/app/mission/MissionRunSurface.kt` | +46, -4 | legend line now `GridLegend.GLYPHS` (`:118`) plus `thinWallLegendRow()` on its own row (`:122`, built at `:419-434`: a 24 x 3 dp swatch with `contentDescription = GridLegend.THIN_WALL_LABEL`, then `GridLegend.THIN_WALL_ENTRY`); `thinWallCount()` (`:331`) delegates to `EdgeWallGeometry.segmentsOf(...)` so the accessibility line can never disagree with what the canvas strokes, and the grid `contentDescription` gains `" thin walls N"` (`:348`). |
| `fireline-app/src/test/kotlin/robofight/fireline/app/view/EdgeWallGeometryTest.kt` | 171 | new, 9 tests (names in §4). |
| `fireline-app/src/test/kotlin/robofight/fireline/app/mission/GridLegendTest.kt` | 45 | new, 3 tests. |

The stroke recipe is inherited, not invented: Arena strokes the same mechanic
(`Arena.kt:39-48` edge arrays, `ArenaView.kt:323-336`, `EdgeWalls.kt:59-63`), and
the data comes from the engine's own renderer copies
(`FirelineWorld.kt:124-125` `wallVertRowMajor()` / `wallHorizRowMajor()`), never
from a view inferring an edge out of a cell (`PLAN-EDGE-WALLS.md` §4).

## 2. Pinned proofs (unit, jvm)

`EdgeWallGeometryTest` (9 tests, all green):

- `mission06MapsToExactlyTheThreeEdgeSlotsTheEngineCarries` - mission 06 yields
  `VERTICAL(5,2)`, `HORIZONTAL(2,1)`, `HORIZONTAL(2,2)`, in that order.
- `mission06HasNoWallCellSoItsWallsCanOnlyComeFromTheEdgeArrays` - mission 06 has
  0 `TERRAIN_WALL` cells and floor cells at (5,2) and (6,2), so on screen the
  three walls can only come from the edge arrays.
- `everySegmentSitsOnTheBorderItsTwoCellsShare` - every endpoint satisfies
  `x % cell == 0` and `y % cell == 0` (lattice intersections only).
- `aSegmentIsOneCellSideLongAndNeverFillsACell` - length is exactly one cell.
- `segmentCountIsTheEngineEdgeCountForEveryAuthoredMission` -
  `segmentsOf().size == layout.edgeCount()` for all of `Missions.ALL`, total 3.
- `wallFreeMissionsDrawNoSegments`, `aFilledWallCellProducesNoSegment`,
  `strokeWidthFollowsTheArenaRecipeAndFloorsAtTwoPixels`,
  `rendererCopiesOfTheWrongSizeAreRefused`.

`GridLegendTest` (3 tests, all green):

- `glyphLegendLineIsUnchangedAndCarriesNoThinWallGlyph` - the glyph line is
  byte-identical to the pre-E3 string and contains no "THIN".
- `thinWallEntryUsesThePlannedWording` - `THIN_WALL_ENTRY` equals
  `THIN WALL   wall, blocks the way`.
- `thinWallSwatchMatchesTheStrokeTheGridDraws` - the 3 dp swatch height is within
  [stroke, stroke + 1] of `strokeWidthPx(19 dp)` (the reference phone cell) and
  the swatch is wider than it is tall.

## 3. Nothing else moved

`git diff --numstat` for the E3 half of the landing commit lists exactly four
tracked files (`MissionRunSurface.kt` 46/4, `FirelineGridView.kt` 31/1) plus the
four new `:fireline-app` files; no other tracked file belongs to `:fireline-app`.
`git diff --numstat -- fireline-engine engine rf8-core app
docs/web/fixtures/parity_matrix.tsv docs/web/fixtures/parity_matrix.json
docs/web/fixtures/parity_traces.txt docs/web/fixtures/parity_traces.json
docs/web/fixtures/parity_arith.tsv docs/web/fixtures/parity_arith.json
docs/web/fixtures/limit_probe.tsv docs/web/fixtures/W2ParityFixtures.kt
docs/web/fixtures/permalink_fixtures.json docs/web/fixtures/permalink_sources.tsv`
is empty at this landing (12 files, 0 changed), so no engine rule, VM, world,
rover, packet, mission source or parity fixture moved. `FIRELINE_ENGINE_VERSION`
is not in any diff, and every committed fingerprint is where it was (the E4
record's regeneration proves the 8 pre-existing snapshot fingerprints are
unchanged, cell by cell).

## 4. Close-out matrix

One `--rerun-tasks --offline` build (Gradle 9.6.0, JBR 25) on 2026-11-25,
`BUILD SUCCESSFUL in 34s`, `176 actionable tasks: 176 executed`, exit 0. The
matrix command, its full result table and the fixture-gate lines are written in
`docs/EDGE-E4-RECORD.md` §4 and §5; the `:fireline-app` rows belong to this step:

| module | result dir | tests | failures | errors | skipped | XML files |
|---|---|---|---|---|---|---|
| `:fireline-app` | `testDebugUnitTest` | 52 | 0 | 0 | 0 | 7 |

Against the 40 of `PLAN-EDGE-WALLS.md` §13.5: +12, exactly `EdgeWallGeometryTest`
(9) and `GridLegendTest` (3). The two suites that grew are new files; no existing
`:fireline-app` test changed its expectation. The stale pre-KMP
`engine/build/test-results/test/` directory is excluded by directory (the target
list runs `:engine:test` into it, and its 15 tests are counted there, not twice).

## 5. Device check (emulator-5554, Medium_Phone 1080x2400 @420dpi)

The APK under test is the debug output of the matrix build:
`fireline-app/build/outputs/apk/debug/fireline-app-debug.apk`, 1 288 807 bytes,
md5 `c41e2e3edaffa37de3245018477dd9d7`; the installed
`/data/app/~~xkziT9saOLG3EOqYo17DlA==/robofight.fireline-drbzByiF_4PKIyEm7d7BNg==/base.apk`
reads back `c41e2e3edaffa37de3245018477dd9d7`, so the device provably runs the
build under test. `versionName=0.1`, `flags=[ DEBUGGABLE ... ]`,
`lastUpdateTime=2026-11-25 08:43:22`.

MISSION tab dump (`UI hierchary dumped to: /sdcard/e3-pick.xml`), mission 06 card
and the BEST badges DW3 decided:

```
06  THIN WALL                                                   [52,2053][351,2077]
BEST: SUCCESS  TICK 59                                          [632,2059][1028,2077]
OPEN RUN SETUP                                                  [52,2303][388,2311]
```

`BEST: SUCCESS  TICK 102` (`[614,653][1028,671]`, mission 02) and four cards
reading `no run recorded yet for engine v2` (`[52,493][1028,518]`,
`[52,1177][1028,1202]`, `[52,1538][1028,1563]`, `[52,1899][1028,1924]`), i.e. the
v2 record stores behave exactly as `PLAN-EDGE-WALLS.md` §11.4 and §13.6 recorded
them. Nothing was cleared or rewritten.

Mission 06 run screen at tick 0 (dump `/sdcard/e3-s.xml`), the two new legend
lines and the grid's accessibility line:

```
1-4 FIRE   R ROVER   * PACKET   C CIV   E EXIT   W REFILL   F FUEL   P ASSET   # WALL   . FLOOR   [41,1620][896,1641]
THIN WALL   wall, blocks the way                                                                  [104,1648][407,1669]
MISSION GRID running mission 06 tick 0 of 120 rover at x 2 y 2 facing E hp 100 heat 0 water 100
  fire distance 4 civilians 0 thin walls 3                                                        [41,622][1039,1620]
```

Wall-free control, mission 02 (dump `/sdcard/e3-m2.xml`): the legend row is still
present and the count is 0, which is the "existing missions declare no edges"
rule on the accessibility channel:

```
MISSION GRID running mission 02 tick 0 of 120 ... civilians 0 thin walls 0
THIN WALL   wall, blocks the way
MISSION 02 / CORRIDOR
```

Pixel measurement of the canvas (the grid canvas has no text nodes, so the wall
itself is proven from the screencap, not from the dump). `e3-02-setup.png`
(1080 x 2400): the square grid occupies `[41,622]-[1039,1620]`, so
`cell = 998 / 20 = 49.9 px` (19 dp at 420 dpi). Pixels equal to `theme.wall`
`#8a6d5e` inside that region: 1 010 px in exactly 3 connected components, and
nothing else in the region carries that color:

| component | bbox px | w x h px | as fractions of cell | engine slot |
|---|---|---|---|---|
| vertical | (337,719)-(343,774) | 7 x 56 | stroke 0.140 cell, length 1.121 cell | `VERTICAL(5,2)` |
| horizontal | (138,719)-(193,724) | 56 x 6 | length 1.121 cell, stroke 0.120 cell | `HORIZONTAL(2,1)` |
| horizontal | (138,769)-(193,774) | 56 x 6 | length 1.121 cell, stroke 0.120 cell | `HORIZONTAL(2,2)` |

Antialiasing makes the stroke 6-7 px where the recipe asks for
`0.14 * 49.9 = 6.99 px`, and the measured length is one cell side plus the half
stroke the SQUARE cap adds at each end. The centers land on the shared borders,
not inside a cell: `HORIZONTAL(2,1)` center y 99.5 px = 2 cells,
`HORIZONTAL(2,2)` center y 149.5 px = 3 cells, `VERTICAL(5,2)` center x 300 px =
6 cells (cell-relative, 0.5 px inside the segment). A vision pass over the
3x crop of the spawn region agrees in words: bars ON the grid lines above and
below the `R`, each spanning that cell's width, and "the cell directly above R
and the cell directly below R are dark cells ... they do not contain any filled
brown square block. The brown color belongs only to the thin horizontal bars on
the boundary lines".

The E5 acceptance line "the on-screen wall is an E3 acceptance item"
(`PLAN-EDGE-WALLS.md` §13.6) is closed by these three components.

## 6. What this device proof does NOT cover

It proves mission 06's grid at tick 0 and mission 02's wall-free control. It does
not play mission 06 to a win on device (the engine-side playthrough is pinned by
`parity_matrix.tsv` `6-reference` `61fe3d4c35449094` and was replayed in the
browser, `docs/EDGE-E4-RECORD.md` §7), it does not measure landscape, and it
does not test a wall cell drawn next to a wall segment (no authored mission has
both; `aFilledWallCellProducesNoSegment` covers the mapping rule instead).

## 7. Deviations

1. Amendment number. `PLAN-EDGE-WALLS.md` §5 assumed A-6 was free. The palette
   audit at `DESIGN-FIRELINE.md:252` had used the label "Mitigation A-6" for what
   §9 numbers as A-5. Resolved by annotation, not rewrite: §9 now carries A-6 as
   the thin-edge-wall amendment and `DESIGN-FIRELINE.md:252-257` carries a dated
   correction naming the collision.
2. Legend placement. The plan says "both containers get the same legend entry".
   The Android entry sits on its OWN row under the glyph line (`MissionRunSurface`
   renders it via `thinWallLegendRow()`), not inline in the glyph line, so the
   glyph line stays byte-identical and assertable (`GridLegendTest`). The web
   entry is a separate `<b>` item in the same legend block (`WorldGrid.LEGEND_HTML`),
   the same decision on both surfaces.
3. Telemetry channel. The plan did not ask for an accessibility count. It was
   added because a canvas has no text nodes: without ` thin walls N` there is no
   way to read the wall count off the device other than pixels. The count comes
   from the same `EdgeWallGeometry.segmentsOf` the view strokes with.
