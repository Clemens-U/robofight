# PLAN-EDGE-WALLS: thin walls as real mechanics in FIRELINE

> Status: **E1, E1b, E2, E3, E4 and E5 of section 7 are built** (records:
> sections 10 and 11, `docs/EDGE-E3-RECORD.md`, `docs/EDGE-E4-RECORD.md`,
> section 13). DW3 decided (section 8); the other section 8 decisions are
> assumed and stay reversible. The mechanic is specced into
> `FIRELINE-DESIGN.md` 3.2/6.2/9/11.4 and into the frozen web layout lock as
> `docs/FIRELINE-WEB.md` 15.8, and it is scheduled against the web phases per
> DW6(a): E1/E2 ran on their own, E4 landed with the W3 console shell.
>
> What this is: give FIRELINE the same wall geometry the Arena already ships,
> which `DESIGN.md` calls **edge walls**, "line segments on the border *between*
> two adjacent cells" (`engine/src/main/kotlin/robofight/world/Arena.kt:15-18`).
> The model is copied, not invented: index conventions, helper names and blocked
> rules are taken from the shipped Arena so the two products cannot drift.
>
> Ground rules this obeys (`GAMEPLAY-IDEAS.md:16-29`): determinism as
> `(layout, rules, seed, firmware)`; mechanism free, content paid; new
> deterministic semantics bump `eng`.

---

## 1. What the Arena and The Circuit already do

Two terrain kinds, decided 2026-10-14 (`Arena.kt:12-18`):

| Kind | Geometry | Behavior |
|---|---|---|
| block | a whole cell | cannot be entered, stood on, or shot through; rendered as a solid cell |
| edge wall | the line segment between two 4-adjacent cells | blocks `MOVE`, absorbs projectiles, registers in `AHEAD`; rendered as a line |

Storage (`Arena.kt:32-48`), the part worth copying exactly:

| Array | Meaning | Index | Size |
|---|---|---|---|
| `blocks` | obstacle cell `(x,y)` | `y * GRID + x` | `GRID x GRID` |
| `vertEdge` | edge to the **RIGHT** of `(x,y)`, separating `(x,y)` from `(x+1,y)`; `x` in `0..GRID-2` | `y * (GRID - 1) + x` | `(GRID-1) x GRID` |
| `horizEdge` | edge **BELOW** `(x,y)`, separating `(x,y)` from `(x,y+1)`; `y` in `0..GRID-2` | `y * GRID + x` | `GRID x (GRID-1)` |

Rules:
- An edge never makes a cell non-free: `isFree` looks at blocks only
  (`Arena.kt:68-71`). Edges gate movement *between* cells.
- `wallBetween(ax,ay,bx,by)` resolves both directions from one array
  (`Arena.kt:74-80`), `stepBlocked = wallBetween(...) || isBlock(dest)`
  (`Arena.kt:84-85`).
- The outer border is an implicit wall and is not stored (`Arena.kt:20-21`).
- A blocked `MOVE` is a silent no-op (`engine/.../world/World.kt:168`), and
  `AHEAD` reads 1 for "wall or enemy" (`World.kt:283`).
- Authoring/debug form is a 2x-resolution picture, 41x41, `#` cell, `.` free,
  `|` and `-` edge lines (`Arena.kt:114-129`); tests assert the raw arrays, not
  the picture (`Arena.kt:117`).
- Rendering is line segments on top of the cell grid
  (`app/.../ArenaView.kt:69-72, 311-336`: vertical line at `x = px*cellSize`
  spanning `(py..py+1)*cellSize`, horizontal likewise, `strokeWidth =
  cellSize * 0.14` floored at 2 px, bright token `#24843D` against the dim
  block token).

## 2. Why FIRELINE cannot express it today

`FIRELINE-DESIGN.md:131-145` gives every cell exactly one terrain kind, and the
engine implements that literally:
- 6 terrain ids, one per cell (`fireline-engine/.../Layout.kt:16-23`), with
  `terrainPassable` and `terrainBurnable` as properties of the cell
  (`Layout.kt:57-62`).
- Authoring is 20 ASCII terrain rows plus 20 fire rows (`Layout.kt:179-249`), so
  one character can only ever mean one whole cell.
- There is no place to put a boundary: no edge array, no edge glyph, no edge
  gate in movement, packets, `AHEAD`, or validation.
- `IN TILE` answers the terrain id of a cell (`Layout.kt:14`), which is why thin
  walls must **not** become a 7th terrain id (section 3).

## 3. The mechanic, mapped to the code that has to change

An edge wall in FIRELINE gates the four things a cell wall already gates, and
nothing else.

| # | Gate | Today (cell wall) | Edge wall change | Site |
|---|---|---|---|---|
| 1 | Rover `MOVE` | `if (!terrainPassable(terrainAt(nx,ny))) return`, a silent no-op after the normal two-tick cost | also blocked when `layout.edgeBetween(x,y,nx,ny)`; still silent, still costs its ticks | `Rover.kt:74-85` |
| 2 | `AHEAD` (port 11) | 1 for boundary, wall, protected asset; civilians and fire do not block | 1 also when an edge wall lies between the rover cell and the cell ahead | `FirelineWorld.kt:469-475`, `FirelinePorts.kt:45` |
| 3 | Water packet spawn | boundary or wall on the facing cell, event `PACKET BLOCKED WALL x,y` | blocked when the edge to the facing cell is a wall; new event `PACKET BLOCKED EDGE x,y` | `FirelineWorld.kt:180-192` |
| 4 | Water packet travel | wall on the next cell, event `PACKET SPENT WALL x,y` | consumed when it hits an edge; new event `PACKET SPENT EDGE x,y` | `WaterPacket.kt:41-44` |
| 5 | Mission validation | flood fill over passable cells must reach every civilian, exit and starting fire (`Layout.kt:119-175`) | the flood fill must not cross an edge wall, so a mission whose exit is sealed by edges fails validation exactly as one sealed by wall cells does | `Layout.kt:157-175` |

Deliberately untouched:
- **No 7th terrain id, no new port, no ISA change.** `IN TILE` keeps reporting
  cell terrain; the only port whose value can change is `AHEAD`, and only for
  layouts that use edges. This is what keeps "one code dialect" true across the
  free app, The Circuit and the web console.
- The 16-step tick order (`FirelineWorld.kt:9-23`) is unchanged; gates 1 to 4
  sit inside steps that already exist.
- Fire spread, growth thresholds, exposure, heat, refill, contact, civilians and
  both RNG streams are unchanged, unless DW1 says otherwise.
- `TextGrid.glyphAt` stays the single glyph authority (`TextGrid.kt:25-31`).
  Edges are drawn, never glyphed, so no second glyph language appears.

## 4. Data model and authoring form

- `Layout` gains `val vertEdge: BooleanArray` and `val horizEdge: BooleanArray`
  sized and indexed exactly as section 1 (760 edges on a 20x20 grid), plus
  `edgeBetween(ax,ay,bx,by)` and `stepBlocked(ax,ay,bx,by)` with the Arena's
  semantics and names.
- `FirelineWorld` reads them through `layout` the way it reads terrain today, so
  `terrainRowMajor()` and `fireRowMajor()` (and therefore the canonical
  final-state hash) keep their current meaning.
- ASCII form stays 20 terrain rows plus 20 fire rows, and gains two **optional**
  blocks in the same style as the Arena picture: 20 rows of 19 characters for
  vertical edges (`|` present, `.` absent) and 19 rows of 20 characters for
  horizontal edges (`-` present, `.` absent). `Layout.decode` takes them as
  optional arguments defaulting to empty, so **every shipped mission source stays
  byte-identical**. `encodeTerrain` gains a matching `encodeEdges` and a
  round-trip test.
- Consumers (Android `FirelineGridView`, the web shell) read only those two
  arrays. No view may infer an edge from a cell.

## 5. Drawing a thin wall on a text grid

The text grid is the product's identity (`GAMEPLAY-IDEAS.md:269-274`), so the
wall must read as a line, not as a new symbol:
- Android: `FirelineGridView` already strokes its cell lattice
  (`view/FirelineGridView.kt:118-125`); wall segments draw on top of it with the
  Arena recipe (segment from one lattice intersection to the next,
  `strokeWidth = cell * 0.14` floored at 2 px) in the FIRELINE wall token, so a
  cell wall and an edge wall are visibly two different things.
- Web: inside the existing `<div class="grid">` of 400 `<span class="cell …">`
  nodes (`docs/web/gen_web_mockups.py:165-175`), a vertical edge is the right
  border of its left cell and a horizontal edge is the bottom border of its
  upper cell. That needs no extra nodes, so the layout verifier's "grid renders
  400 cells" gate keeps working; it gains a separate edge-count check.
- Both containers get the same legend entry (a short thick segment plus
  "wall, blocks the way"), so the reader learns one concept twice.
- Spec side: a new numbered visual amendment (next free number is **A-6**;
  `DESIGN-FIRELINE.md:470-484` lists A-1 to A-5) plus an amendment to
  `FIRELINE-DESIGN.md` 11.4 grid language and the web layout lock
  (`docs/FIRELINE-WEB.md` §15), because §15 is frozen for v1.

## 6. Determinism, version, and what breaks

- Existing missions declare no edges, so each new gate adds a condition that is
  always false for them. That is not a hand wave: it is provable by re-running
  the W2 parity matrix (`docs/web/fixtures/`, 10 rows, 12-tick golden traces,
  final hashes) and the committed anchors (`$47e28d84` mission 02 finish at tick
  102, `$a64c17a9` mission 01 at tick 60). If those do not move, the shipped
  content is bit-identical.
  Those two anchors are engine v1 values; they held for the E1 build and are
  superseded by section 11, where engine v2 finishes mission 02 at tick 102 with
  `d3ea47119fe3b101` and mission 01 `never shoots` at tick 60 with
  `7cbfe49993c17318`.
- Ground rule 4 (`GAMEPLAY-IDEAS.md:28-29`) says new deterministic semantics bump
  `eng`. In FIRELINE that means `FIRELINE_ENGINE_VERSION` 1 to 2
  (`FirelineRules.kt:21`), and the bump has teeth: `MissionDescriptor` refuses a
  descriptor whose `engineVersion` is not the engine's (`Mission.kt:66-67`), and
  saved run records plus per-mission BEST badges are keyed by engine version (the
  device-verified `BEST: SUCCESS TICK 102` line). See DW3.
- The W2 fixture set gets edge-wall rows (a mission that uses edges, probe ticks
  and digests recorded from the live engine, never typed), on `jvm` and `wasmJs`,
  which is the proof that the browser console and the Android app agree about
  walls.
- Firmware `.asm` import/export and the SAF roundtrip are unaffected; they carry
  firmware, not layout.

## 7. Work plan

| Step | Scope | Done when |
|---|---|---|
| E1 | Engine: edge arrays, `edgeBetween`/`stepBlocked`, decode + validate, gates 1 to 5, unit tests (`EdgeWallTest`: blocked MOVE stays a silent no-op with its tick cost, `AHEAD` 1 across an edge, packet blocked at spawn and consumed in flight, sealed-exit mission rejected, round-trip `encodeEdges`) | engine suites green on `jvm` and `wasmJs`, parity anchors unmoved |
| E2 | Determinism: new edge-wall rows through the W2 fixture pipeline (record, then pin), both targets | identical hashes across targets, fixture README updated |
| E1b | Version: cut DW3, `FIRELINE_ENGINE_VERSION` 1 to 2 (`FirelineRules.kt:21`), then re-baseline every fingerprint and saved-record key the bump moves. Numbered recipe in section 10.7 | the constant is 2, every committed fixture and every doc anchor equals a freshly generated value, and both apps read their v2 mission and BEST keys |
| E3 | Android: `FirelineGridView` segments, legend entry, amendment A-6; on-device check on `emulator-5554` | grid shows a wall line that is visibly not a wall cell; BEST badges behave as DW3 decided |
| E4 | Web: grid overlay in `:fireline-web` plus the same legend, `verify_layout_v1.py` gains the edge gate, browser measurement of the rendered edges | web and Android describe the same edges for the same mission |
| E5 | Content and specs: one free authored mission that teaches edges (mechanism free), one Circuit mission later (content paid); amend `FIRELINE-DESIGN.md` 3.2, 6.2, 9 and 11.4, plus `MANUAL.md` | docs and store copy describe what the engine actually does |

## 8. Decisions (five assumed, DW3 decided)

Asked of the user on 2026-11-21. DW1, DW2, DW4, DW5 and DW6 are **assumed for the
engine step** and they are all reversible: nothing in the assumed set changes a
shipped mission, a saved record, a published best, or `FIRELINE_ENGINE_VERSION`.
DW3 was answered by the user on 2026-11-21 and is recorded below as decided.

Assumed and proceeding: DW1 (a), DW2 (a), DW4 (a), DW5 (a), DW6 (a). Decided by
the user: DW3 (a).

| ID | Question | Options | Recommendation |
|---|---|---|---|
| DW1 | Do edge walls block fire? | (a) no, they gate rover, packets and `AHEAD` only; (b) yes, they are firebreaks and spread may not cross one | **(a)**. It keeps the whole fire model (section 4 of `FIRELINE-DESIGN.md`, its RNG streams and thresholds) untouched, and wall cells already never burn. A firebreak edge would be a second, larger semantics change. |
| DW2 | Authoring form for edges | (a) two optional edge blocks next to the 20x20 terrain rows; (b) one 41x41 picture block as `Arena.render()` does | **(a)**. Existing mission sources stay byte-identical and the terrain rows keep meaning terrain. |
| DW3 | Version policy | (a) bump `FIRELINE_ENGINE_VERSION` 1 to 2, and reset the engine-v1 mission/record stores with an explicit one-time notice; (b) stay v1 and mark only edge-using layouts with a layout flag; (c) develop behind an internal flag and cut the bump later with the shared "Walls Down" pack (`GAMEPLAY-IDEAS.md:216-220`, `PLAN-PRO.md` §6) | **DECIDED 2026-11-21 by the user: (a), a flat bump of `FIRELINE_ENGINE_VERSION` from 1 to 2 in this release.** Accepted because no player depends on the current saved records yet, so fingerprint churn and dark BEST stores are tolerable now, and any later bump must be handled with more care. The cost is measurable: the version is written into the canonical state text (`FirelineWorld.kt:564`) and folded into `MissionDescriptor.hashCode` (`Mission.kt:103`), so the bump moves every fingerprint in the repo, wall-free missions included. Step E1 neither cuts the bump nor touches the version gate at `Mission.kt:66-67`. Step E1b cuts it, and section 10.7 is its recipe. |
| DW4 | Retrofit the five shipped missions? | (a) no, author new content that uses edges; (b) retrofit existing layouts | **(a)**. Retrofitting invalidates every committed anchor, the store screenshots and every published best. |
| DW5 | Sensor breadth | (a) `AHEAD` only, Arena parity; (b) also a wall-aware range read (nearest edge ahead at distance n) | **(a)** for v1. Per-direction scanning is its own backlog idea (`GAMEPLAY-IDEAS.md:47-50`) and deserves its own decision. |
| DW6 | Sequence against the web phases | (a) run E1/E2 now on its own branch, then E4 as part of W3 once the web grid exists; (b) wait until W4 hosting ships | **(a)**. E1/E2 change no web file, the grid overlay (E4) needs the same §15 amendment whether it lands now or later, and the web grid step is already in flight. |

## 9. Out of scope here

Destructible edges (that is `GAMEPLAY-IDEAS.md:62-64`), gates, plates and puzzle
ports (the "Walls Down" pack), diagonal or partial-segment walls, walls thicker
than one cell boundary, sprites or any non-text rendering, and any change to the
`.asm`/SAF format.

## 10. Built in E1

Engine step E1 of section 7 landed 2026-11-21 on branch `codex/edge-walls-e1`
(HEAD `7b638e6`, off `codex/web-w3-console`). Scope is `:fireline-engine` only.
`FIRELINE_ENGINE_VERSION` stays 1 (`FirelineRules.kt:21`) and the version gate at
`Mission.kt:66-67` is untouched, so the step carries its own proof: every
committed fingerprint of a wall-free layout is where it was before (section 10.6).
The bump itself is step E1b (section 10.7).

### 10.1 Files

| File | Lines | Change |
|---|---|---|
| `fireline-engine/src/commonMain/kotlin/robofight/fireline/EdgeWalls.kt` | 172 | new. Edge constants, index rules, `wallBetweenEdges`, `edgeBlockedDir`, `edgesPresent`, `edgeCountOf`, the strict `decodeWallRows`, and `encodeVertRows`/`encodeHorizRows`. Each rule names the Arena line it mirrors. |
| `fireline-engine/src/commonMain/kotlin/robofight/fireline/Layout.kt` | 552 (+310, -13) | `vertEdge` and `horizEdge` on `Layout` (default all-open), `wallBetween`, `edgeBetween`, `edgeBlocked`, `stepBlocked`, `wallsPresent`, `edgeCount`, `wallVertRowMajor`, `wallHorizRowMajor`, the two optional wall blocks in `decode` and in the canonical text reader, `floodFillPassable(crossEdgeWalls)`, and the wall wording in `validate`. |
| `fireline-engine/src/commonMain/kotlin/robofight/fireline/FirelineWorld.kt` | 643 (+34, -4) | `edgeBetween` read path, packet spawn gate, `AHEAD` gate, renderer copies. |
| `fireline-engine/src/commonMain/kotlin/robofight/fireline/WaterPacket.kt` | 67 (+10, -1) | packet travel gate. |
| `fireline-engine/src/commonMain/kotlin/robofight/fireline/Rover.kt` | 140 (+4, -0) | MOVE gate. |
| `fireline-engine/src/commonTest/kotlin/robofight/fireline/EdgeWallsTest.kt` | 676 | new. 22 tests and one fixture mission source that carries walls. |

Untouched on purpose: `Mission.kt`, `canonicalState`, `FirelineResult`,
`Missions.kt` (328 lines, the five shipped sources, which is DW4 (a) in
mechanical form), and every file in `:app`, `:fireline-app`, `:fireline-web`,
`:engine`, `:rf8-core` and `docs/web/fixtures/`.

### 10.2 The frozen model as shipped

`EdgeWalls.kt` restates the section 1 storage for `FirelineRules.kt:25 GRID = 20`:

| Array | Meaning | Index | Slots | Arena source |
|---|---|---|---|---|
| `vertEdge` | edge to the RIGHT of `(x,y)`, `x` in `0..GRID-2` | `vertEdgeIndex(x, y) = y * 19 + x` | 380 | `Arena.kt:39-40` |
| `horizEdge` | edge BELOW `(x,y)`, `y` in `0..GRID-2` | `horizEdgeIndex(x, y) = y * 20 + x` | 380 | `Arena.kt:45-46` |
| both together | every edge of one 20x20 layout | `EDGE_SLOTS_TOTAL` | 760 | `Arena.kt:32-48` |

- `wallBetweenEdges(...)` is the four-branch normalisation of `Arena.kt:74-80`
  verbatim: one lookup per cardinal pair, in either direction, false for a
  diagonal pair, a repeated cell and a two-cell gap.
- `edgeBlockedDir(vertEdge, horizEdge, x, y, dir)` answers true for an
  out-of-grid target without touching an array, which is the implicit border of
  `Arena.kt:20-21`. No edge array has a slot for the border, so
  `wallsPresent()` stays false for a layout whose only wall is the grid border.
- `Layout.stepBlocked` composes `!inBounds || wallBetween || !terrainPassable`,
  the order `Arena.kt:84-85` uses.
- `terrainPassable` (`Layout.kt:88`) reads a terrain id only and never reads an
  edge array, so a wall never makes a cell non-free and a wall is never derivable
  from a terrain id (`Arena.kt:68-71`).
- `wallsPresent()` and `edgeCount()` mirror `Arena.kt:88`.

### 10.3 Canonical wall blocks as shipped

DW2 (a) is two optional sections that follow the fire rows and precede the items.
`Layout.toCanonicalText` emits them in this order:

```
MISSION 20
TERRAIN     20 rows of 20 glyphs   the terrain alphabet of section 3.2
FIRE        20 rows of 20 glyphs   intensity digits, . for none
WALL_VERT   20 rows of 19 glyphs   # wall, . open
WALL_HORIZ  19 rows of 20 glyphs   # wall, . open
ROVER x,y,dir                      items follow
```

- Both blocks are emitted only when `wallsPresent()` is true
  (`Layout.kt:324-329`). A wall-less mission text is byte-identical to the text
  the same layout produced before edge walls existed, and it round-trips
  unchanged.
- An absent block parses to all-open (`Layout.kt:406-410`), a duplicated block is
  refused (`Layout.kt:485-491`), and a wrong row count, a wrong row length or a
  character outside `#` and `.` fails the source with a named message
  (`EdgeWalls.kt:129-157`, the message style `Layout` already uses for terrain
  rows): `WALL_VERT rows 19, expected 20`, `WALL_HORIZ rows 18, expected 19`,
  `WALL_VERT row 3 has 18 chars, expected 19`,
  `WALL_VERT row 0 char '|' at x=0 is not a known wall glyph`.
- Deviation from section 4, recorded here: the shipped alphabet is `#` and `.`
  for both blocks, not `|` for vertical and `-` for horizontal. One alphabet
  keeps a wall block inside the glyph set a mission source already accepts, and
  the `|` and `-` picture stays an Arena authoring form (`Arena.kt:114-129`) that
  FIRELINE does not import. A `|` in a FIRELINE wall block is an error.
- `Layout.decode` takes `vertEdgeRows` and `horizEdgeRows` as optional arguments
  defaulting to empty (`Layout.kt:359-360`), which is why no shipped mission
  source changed by one byte.

### 10.4 Gates and the Arena behaviour each mirrors

| # | Gate | Site | Mirrors |
|---|---|---|---|
| 1 | rover MOVE | `Rover.kt:84` `if (world.edgeBetween(x, y, nx, ny)) return`, inside the two-tick MOVE completion that exists today | `World.kt:169` (`!w.layout.stepBlocked(px, py, nx, ny)`) over `Arena.kt:84-85`: a blocked MOVE is a silent no-op that still pays its ticks, `lastOp` still reads `MOVE (2/2)` and the tick counter still advances |
| 2 | `AHEAD`, port 11 | `FirelineWorld.kt:503` | `World.kt:288` (`if (layout.stepBlocked(b.px, b.py, nx, ny)) return 1`). One sentinel 1 for a thin wall, a wall cell, a protected asset and the border, so firmware sees one blocked value |
| 3 | packet spawn | `FirelineWorld.kt:207-210`, event `PACKET BLOCKED EDGE x,y` | `World.kt:359-363` "it can't spawn through an edge wall or into a block". The discharge still costs its water, heat, shot count and three ticks; only the packet is refused |
| 4 | packet travel | `WaterPacket.kt:41-48`, event `PACKET SPENT EDGE nx,ny` | `World.kt:432`, where a projectile that reaches an edge wall or a block is absorbed there. The blocked step consumes the packet at the edge instead of parking it on its current cell, which is also the shape `PACKET SPENT WALL` and `PACKET SPENT BOUNDARY` already have |
| 5 | mission validation | `Layout.kt:240-257` and `floodFillPassable(crossEdgeWalls)` at `Layout.kt:269-283` | `Arena.kt:74-80` as the traversal filter. The fill keeps its shipped order: FIFO `ArrayDeque`, directions visited in `0..3`, one `wallBetween` test per step |

Gate 3 tests the edge before the terrain wall, the evaluation order of
`Arena.kt:84-85`. Both choices are written at their call sites.

### 10.5 Tests

`EdgeWallsTest.kt`, 22 tests, asserted identically on `jvm` and `wasmJs`. The
fixture mission source lives in the test file: 20 terrain rows, 20 fire rows, a
`WALL_VERT` block whose row 2 carries a wall at char 5, and a `WALL_HORIZ` block
whose rows 1 and 2 carry a wall at char 2. Spawn `(2,2)` is sealed north and
south, open east and west, and the east lane along row 2 stops between `(5,2)`
and `(6,2)`.

| Test | Proves |
|---|---|
| `edgeModelRestatesArenaIndexingForGrid20` | 380 + 380 = 760 slots, both index rules, no slot for a border edge |
| `wallBetweenNormalisesACardinalPairToExactlyOneLookup` | four directions resolve, non-cardinal pairs answer false |
| `outerBorderIsImplicitAndNeverAnArraySlot` | the four out-of-grid reads are blocked and no array slot is consumed |
| `wallBlocksParseFromTheFixtureMissionSource` | decoded rows equal the fixture wall table, 3 walls |
| `aSourceWithoutWallBlocksParsesToTheSameLayoutAsBefore` | no block means all open, arrays equal the pre-E1 decode |
| `wallBlocksAreValidatedAsStrictlyAsTerrainRows` | row count, row length, alphabet, and a source that carries one block only |
| `canonicalTextEmitsBothWallBlocksOnlyWhenAWallExists` | section order, and a wall-less text 41 lines shorter |
| `fixtureMissionSourceRoundTripsThroughEncodeAndReparse` | a wall-carrying text re-encodes byte-identically |
| `canonicalTextRejectsAMalformedMissionSource` | an 18-row wall block, an unknown item, a spawn on the exit cell, an `R` inside the terrain rows |
| `blockedMoveAcrossAnEdgeWallIsASilentNoOp` | position, `lastOp`, tick, distance, heat, no event, and the same hash on a replay of the run |
| `anOpenEdgeBetweenTwoWalledNeighboursStillMoves` | the open edge between two sealed neighbours stays walkable |
| `aPacketSpawnedThroughAnEdgeWallIsRefusedButTheShotStillCosts` | 0 packets with water 90, heat 20, 1 shot, tick 3 |
| `aPacketIsAbsorbedAtAnEdgeWallAndCrossesTheSameEdgeWhenItIsOpen` | stopped at `(5,2)`, absorbed at the wall, and the same run without that wall reaches `(6,2)` |
| `aheadReportsOneSentinelForAnEdgeWallAWallCellAndTheBorder` | 1 across an edge wall, 0 across an open edge, equal to the wall-cell read and the border read |
| `validationRejectsAnExitSealedByEdgeWallsAndAcceptsItWithThatWallRemoved` | the existing message shape plus the suffix ` blocked by an edge wall`, valid again once that wall is removed |
| `aFullyEdgeSealedCellIsStillOccupiableAndStillReportsItsTerrain` | a cell walled on all four edges validates, stays passable, `IN TILE` still answers 0 and `AHEAD` still answers 1 |
| `wallsNeverMakeACellNonFreeAndAreNeverDerivedFromATerrainId` | terrain id and passability equal a wall-less copy for all 400 cells, a wall between two FLOOR cells blocks the step, and a wall cell stays blocked with every touching edge open |
| `wallArraysAreDefensiveCopiesOnLayoutAndWorld` | 380 + 380 copies, and mutating a copy cannot reach the layout |
| `everyShippedMissionLayoutCarriesNoWalls` | missions 01 to 05 report `wallsPresent() == false`, zero edges, and still validate |
| `everyShippedMissionSourceRoundTripsWithoutWallBlocks` | the five canonical texts carry no wall block and re-encode byte-identically |
| `missionAnchorsAreUnmovedByEdgeWalls` | the six fingerprints of section 10.6 |
| `edgeWallsDoNotEnterTheCanonicalFinalStateTextOfAWalllessLayout` | `canonicalState` has no wall line and `FIRELINE_ENGINE_VERSION` is still 1 |

### 10.6 Measured result

One close-out matrix, `--rerun-tasks --offline`, Gradle 9.6.0 on the Android
Studio JBR:

```
:rf8-core:jvmTest :rf8-core:wasmJsTest :fireline-engine:jvmTest :fireline-engine:wasmJsTest :fireline-web:wasmJsTest :app:testFreeDebugUnitTest :app:testProDebugUnitTest :fireline-app:testDebugUnitTest :fireline-app:assembleRelease -x lintVitalRelease
```

BUILD SUCCESSFUL. Counts read from the 50 result XMLs that build wrote;
`build/test-results/test/` directories are pre-KMP leftovers and were not read.

| Target | tests | failures | errors | skipped |
|---|---|---|---|---|
| `:rf8-core:jvmTest` | 15 | 0 | 0 | 0 |
| `:rf8-core:wasmJsNodeTest` | 15 | 0 | 0 | 0 |
| `:fireline-engine:jvmTest` | 186 | 0 | 0 | 0 |
| `:fireline-engine:wasmJsNodeTest` | 186 | 0 | 0 | 0 |
| `:fireline-web:wasmJsNodeTest` | 24 | 0 | 0 | 0 |
| `:app:testFreeDebugUnitTest` | 3 | 0 | 0 | 0 |
| `:app:testProDebugUnitTest` | 9 | 0 | 0 | 0 |
| `:fireline-app:testDebugUnitTest` | 39 | 0 | 0 | 0 |
| `:fireline-app:assembleRelease` | BUILD SUCCESSFUL | | | |
| total | 477 | 0 | 0 | 0 |

477 against 433 before the step: +22 tests on each engine target, no test lost.

Anchors asserted by `missionAnchorsAreUnmovedByEdgeWalls`, measured in the same
build (mission 02 `CORRIDOR` from its `referenceSource`, mission 01 `NOZZLE
CHECK` from its `never shoots` failing source):

| Run | tick | fingerprint | committed source |
|---|---|---|---|
| 02 reference | 12 | `113c566635ef3e13` | `docs/web/fixtures/snapshots.json` `02-ref-t12` |
| 02 reference | 30 | `5c7dd44cba32908d` | measured in this build, no committed row exists |
| 02 reference | 46 | `e9226853d9b3eb4d` | `docs/web/fixtures/snapshots.json` `02-ref-t46` |
| 02 reference, SUCCESS | 102 | `47e28d84c5d61220` | `docs/web/fixtures/snapshots.json` `02-ref-final`, `parity_matrix.json` `final_hash` |
| 01 never shoots | 30 | `0cacfa28f82b0dc7` | `docs/web/fixtures/snapshots.json` `01-never-t30` |
| 01 never shoots, FAILURE TIME_EXPIRED | 60 | `a64c17a9d85a6f75` | `docs/FIRELINE-WEB.md:608`, `parity_matrix.json`, `docs/web/fixtures/README.md` |

Every wall-free fingerprint is unmoved, which is the E1 clause of section 6.

Those six fingerprints were measured under engine v1 in the E1 build. Step E1b superseded
them on 2026-11-21 (section 11); the current values are in `docs/web/fixtures/parity_matrix.tsv`
and `docs/web/fixtures/snapshots.json`.

### 10.7 Step E1b, the re-baseline recipe

E1b is the only step in this plan that moves a fingerprint, so its order is
written down for the next bump too.

1. Set `FIRELINE_ENGINE_VERSION = 2` at `FirelineRules.kt:21`. Nothing else in
   `commonMain` changes: `MissionDescriptor` defaults to the constant
   (`Mission.kt:51`), the gate compares against it (`Mission.kt:66-67`), and
   `canonicalState` writes it (`FirelineWorld.kt:564`).
2. Run `:fireline-engine:jvmTest` and expect every W2, Step9 and Step10 hash
   assertion to fail. Read the actual values out of the failure messages; never
   type a hash.
3. Re-record the fixture TSVs from the live engine by running
   `docs/web/fixtures/ParityMatrix.kt` the way `docs/web/fixtures/README.md`
   describes, which rewrites `parity_matrix.tsv`, `parity_traces.txt`,
   `parity_arith.tsv` and `limit_probe.tsv`. That README command still names the
   pre-KMP source trees `rf8-core/src/main/kotlin` and
   `fireline-engine/src/main/kotlin`; E1b must pass the current
   `src/commonMain/kotlin` trees instead.
4. Run `python docs/web/fixtures/parity_fixture_gen.py`, which rewrites
   `parity_matrix.json`, `parity_arith.json`, `parity_traces.json` and
   `fireline-engine/src/commonTest/kotlin/robofight/fireline/W2ParityFixtures.kt`
   from those TSVs and refuses when the TSVs disagree with each other.
5. Regenerate `snapshots.json` and `snapshots.extra.json` with
   `fixtures.SnapshotFixturesKt`, regenerate `crosscheck.tsv` with
   `fixtures.CrossCheckKt`, then run `verify_snapshots.py` and
   `verify_crosscheck.py`.
6. Update the hardcoded expectations no generator writes: `W2ArithmeticParityTest.kt`,
   `W2GoldenTraceTest.kt`, `MissionSessionTest.kt`, and the anchor table in
   `EdgeWallsTest.kt`.
7. Update the web side: `fireline-web/src/wasmJsTest/kotlin/robofight/fireline/web/ConsoleModelTest.kt`
   and `ConsoleRenderTest.kt`, the anchor labels `ConsoleModel.kt` prints in
   `bestRunOf`, and the W1 self-check `fireline-engine/src/wasmJsMain/kotlin/robofight/fireline/W1HelloWasm.kt`,
   then `python docs/web/gen_web_mockups.py` and `python docs/web/verify_layout_v1.py`,
   then the snapshot meta lines in `docs/web/fl-web-*.html`. `gen_web_mockups.py`
   and the generated `fl-web-*.html` carry `47e28d84c5d61220` as displayed text, so
   the golden master moves with the bump and `verify_layout_v1.py` is the gate that
   proves the frozen layout still matches the regenerated master.
8. Update the doc anchors: `docs/FIRELINE-WEB.md` §13 to §15,
   `docs/WEB-W1-RECORD.md`, `docs/WEB-W2-RECORD.md`, `docs/WEB-W3-RECORD.md`,
   `PLAN-RF8-MULTIAPP.md` and `docs/web/fixtures/README.md`, and
   the app-side anchors the plan cites as `docs/FIRELINE-APP.md` section 9.6 and
   `docs/FIRELINE-ENGINE-V1.md`. Neither file is in this checkout, so E1b locates
   them before it claims to have updated them. Every record of a past run states
   that its fingerprints were measured under engine v1, so the records keep their
   history and only the current-state claims move.
9. Re-key the app stores: `fireline-app/src/main/kotlin/robofight/fireline/app/mission/RunStores.kt`
   writes the `best.<missionId>.<engineVersion>` key, and
   `fireline-app/src/main/kotlin/robofight/fireline/app/mission/MissionPickerModel.kt`,
   `MissionSurface.kt` and the badge labels read it. Add the one-time notice
   that engine v1 bests are gone, which is the DW3 (a) half of the decision.
10. Close out with the section 10.6 matrix plus the device check, and commit the
    constant, the regenerated fixtures and the updated anchors in one commit, so
    a fingerprint never disagrees with the document that cites it.

### 10.8 Not applicable in this checkout

Section 3 and section 6 name `FirelineLayoutRegistry.fromSource`,
`FirelinePresets.kt` mission ids, layout ids `0x00..0x07` and a generated layout
id `0x0007`. None of them exist here. `layoutId` appears only in the Arena
challenge code (`engine/src/main/kotlin/robofight/challenge/ChallengeFormat.kt`,
`engine/src/test/kotlin/robofight/RegistryGateTest.kt`), and
`fireline-engine/src/commonMain/kotlin/robofight/fireline/` carries `Missions.kt`
instead of `FirelinePresets.kt`. What E1 can prove about shipped content is what
section 10.5 proves: all five `Missions.kt` sources parse with
`wallsPresent() == false`, every one of their canonical texts re-encodes
byte-identically with no wall block, and the layout id decode rules in `Layout`
were not touched.

## 11. E1b: engine v2 (executed 2026-11-21 at HEAD `ed93d89`)

`FIRELINE_ENGINE_VERSION` is 2 (`FirelineRules.kt:21`). That single line is the whole
`commonMain` change of this step: `MissionDescriptor` defaults to the constant
(`Mission.kt:51`), the version gate compares against it (`Mission.kt:66-67`), and
`canonicalState` writes it (`FirelineWorld.kt:564`). No compatibility shim was added and no
engine v1 fingerprint was preserved: nobody plays FIRELINE yet, so the churn is accepted now,
and the care this step took with the fixture chain is the template for the next bump.

DW3 (a), the notice that engine v1 bests are gone, ships as the existing MISSION card copy
`no run recorded yet for engine v2` (`MissionSurface.kt:84`). No new UI element was added,
because `DESIGN-FIRELINE.md` is locked.

### 11.1 Fingerprints the bump moved (engine v1 -> engine v2)

Parity matrix rows (`docs/web/fixtures/parity_matrix.tsv`, probe tick 12):

| scenario | probe tick 12 v1 | probe tick 12 v2 | trace hash (unchanged) | final tick / status | final v1 | final v2 |
|---|---|---|---|---|---|---|
| 1-reference | `fa31bb2eced7a30c` | `f3dd62629ba58d1f` | `4f4118286b349619` | 17 SUCCESS | `198645dc2e7e9649` | `245faa6d10316e00` |
| 1-failing | `85461a84b158812d` | `df8ebe851b2c1670` | `9f496c19b6af9b46` | 60 FAILURE TIME_EXPIRED | `a64c17a9d85a6f75` | `7cbfe49993c17318` |
| 2-reference | `113c566635ef3e13` | `d0f26fd5c0b02efa` | `ceddf1a2fa7d7b10` | 102 SUCCESS | `47e28d84c5d61220` | `d3ea47119fe3b101` |
| 2-failing | `43054c601ddd8737` | `55814d5ffbe783d6` | `88b5c429de62c944` | 120 FAILURE TIME_EXPIRED | `095dc5eba29eef27` | `2e88bd6f1f4ed91e` |
| 3-reference | `df4571c82f169e1b` | `ba3c37b47d2133b6` | `28016f5459a44c4d` | 38 SUCCESS | `0cf3b083da3900fd` | `01663660e6731d0a` |
| 3-failing | `3153a509654849f5` | `aec31f0cbb71140c` | `d31f60893006df8f` | 120 FAILURE TIME_EXPIRED | `818eda729f5866d7` | `fee14c44c61f245a` |
| 4-reference | `7f2f344b8d0a52c4` | `84736ea77c8e8a67` | `9bafb261b891c1c0` | 147 SUCCESS | `dd669e7313a67d41` | `acf4656261a3c008` |
| 4-failing | `da58ac6a8b1a7e81` | `07fc73be7bf8f31e` | `99752e4c059a058e` | 500 FAILURE TIME_EXPIRED | `b2c14340a8f8a1de` | `51f0f7232dd88add` |
| 5-reference | `672a982213390d0c` | `426c4fdd8f395eef` | `317eca23f0149509` | 45 SUCCESS | `a7836db790e4a6a0` | `ba8e443d3c469c1d` |
| 5-failing | `47145b5826115f48` | `2b88919b27fcd1e1` | `def56526d10c1cf2` | 29 FAILURE ROVER_DESTROYED | `b7b8b80ea652ce1d` | `89bceb4241170566` |

Snapshot anchors (`docs/web/fixtures/snapshots.json`, `docs/web/fixtures/snapshots.extra.json`)
plus the one anchor that has no committed row:

| label | tick | v1 | v2 |
|---|---|---|---|
| `01-ref-t0` | 0 | `9333817203ef8d30` | `28838868ad3468ed` |
| `01-ref-t12` | 12 | `fa31bb2eced7a30c` | `f3dd62629ba58d1f` |
| `01-ref-t13` (bonus) | 13 | `b5824095228ab451` | `25492eecf7f255da` |
| `01-ref-t15` (bonus) | 15 | `982a627de76e457a` | `9c2908d69d70ba79` |
| `01-ref-final` | 17 | `198645dc2e7e9649` | `245faa6d10316e00` |
| `01-never-t30` | 30 | `0cacfa28f82b0dc7` | `b77a46e1e4308ad0` |
| `01-never-final` | 60 | `a64c17a9d85a6f75` | `7cbfe49993c17318` |
| `02-ref-t12` | 12 | `113c566635ef3e13` | `d0f26fd5c0b02efa` |
| `02-ref-t30` | 30 | `5c7dd44cba32908d` | `0d7fa9328d0ad4c4` |
| `02-ref-t46` | 46 | `e9226853d9b3eb4d` | `9b6e65741beac0ea` |
| `02-ref-final` | 102 | `47e28d84c5d61220` | `d3ea47119fe3b101` |

26 distinct values moved. `02-ref-t30` has no committed fixture row; both of its values were
measured in a build (`AnchorProbe.kt` in scratch, the value recorded in `EdgeWallsTest.kt` with
that provenance in its assertion message).

Unmoved by the bump: every final tick, status and failure reason in the matrix, every firmware
byte and instruction count, all ten golden trace hashes, all 29 arithmetic vectors in
`parity_arith.tsv`, and all five `limit_probe.tsv` rows. `parity_traces.txt` was rewritten
byte-identically (no git diff), and `parity_arith.tsv` plus `limit_probe.tsv` changed only in
their `# commit` and `# engine v2` header lines. The reason every mission moved, wall-free ones
included, is `canonicalState()`: it stamps `engine=2` into the hashed text.

### 11.2 Commands as executed, with real exit codes and counts

1. `:fireline-engine:jvmTest` right after the constant change: 186 tests, 5 failed
   (`EdgeWallsTest.missionAnchorsAreUnmovedByEdgeWalls`,
   `EdgeWallsTest.edgeWallsDoNotEnterTheCanonicalFinalStateTextOfAWalllessLayout`,
   `W2ArithmeticParityTest.engineNumericPrimitivesAreInRangeAndWrapWhereDocumented`,
   `W2ParityMatrixTest.everyMatrixRowReproducesItsFixedTickProbeFingerprint`,
   `W2ParityMatrixTest.everyMatrixRowReproducesItsRecordedOutcome`). All five named a v1 anchor
   or asserted `FIRELINE_ENGINE_VERSION == 1`.
2. Fixture harness (JBR + kotlinc, scratch class dirs, corrected `commonMain` trees):
   compile exit 0 with zero `error:` lines; `fixtures.ParityMatrixKt` run twice, exit 0 both
   times, printing `engine v2`, `rows=10 arith=29 limits=5`; the two runs' TSVs are
   byte-identical (`parity_matrix.tsv` md5 `b05740462883cbc547ad650622db64ed`,
   `parity_traces.txt` `449585cb4d1c49b549b15e2ca872bc2c`, `parity_arith.tsv`
   `76639b1c5a014db2f3712df798d5f7ae`, `limit_probe.tsv` `a5b8ecda94db87f2649f4ddcd37bd227`).
3. `python docs/web/fixtures/parity_fixture_gen.py`: exit 0, `engine v2, commit
   ed93d8926f9b0d6f9d193df458d104aa5518a7c8, rows 10, arith 29, limits 5, trace lines 120`,
   rewrote `parity_matrix.json`, `parity_arith.json`, `parity_traces.json` and
   `W2ParityFixtures.kt`.
4. `fixtures.SnapshotFixturesKt`: exit 0, 8 snapshots, 26549 bytes, plus 2 bonus snapshots;
   `fixtures.CrossCheckKt`: exit 0, 13 TSV lines.
5. `python docs/web/fixtures/verify_snapshots.py`: exit 0, `ALL CHECKS PASSED`
   (bytes 26549, 8 labels). `python docs/web/fixtures/verify_crosscheck.py`: exit 0,
   `CROSSCHECK PASSED` (7 probes x 13 fields, 3 finals x 9 fields, 3 ACTIVITY rows).
6. `python docs/web/gen_web_mockups.py`: exit 0, regenerated all five `fl-web-*.html` from the
   fixtures. `python docs/web/verify_layout_v1.py`: exit 0, `22 checks, 0 failed`. No layout
   gate failed, so no gate was weakened.

### 11.3 Current-state files versus historical records

Current state, rewritten to the v2 values: the whole fixture chain under `docs/web/fixtures/`
(`parity_matrix.tsv`, `parity_matrix.json`, `parity_traces.txt` (unchanged bytes),
`parity_traces.json`, `parity_arith.tsv`, `parity_arith.json`, `limit_probe.tsv`,
`snapshots.json`, `snapshots.extra.json`, `crosscheck.tsv`), the pinned
`W2ParityFixtures.kt`, `EdgeWallsTest.kt`, `W2ArithmeticParityTest.kt`, `W1HelloWasm.kt`,
`ConsoleModel.kt`, `ConsoleRender.kt`, `ConsoleModelTest.kt`, `ConsoleRenderTest.kt`,
both `MissionSamples.kt` defaults, `MissionSurface.kt`, the five `fl-web-*.html` mockups, and
`gen_web_mockups.py`, which now reads the mission 01 and mission 02 best-run fingerprints from
the fixture instead of hardcoding `47e28d84c5d61220`.

Historical records, kept at their measured v1 values with a dated note pointing at
`docs/web/fixtures/parity_matrix.tsv` and `docs/web/fixtures/snapshots.json`:
`docs/WEB-W1-RECORD.md`, `docs/WEB-W2-RECORD.md`, `docs/WEB-W3-RECORD.md`, `docs/FIRELINE-WEB.md`
section 14.1 (its sections 13 to 15, the frozen layout spec, were not touched), and the
milestone 7 entry of `PLAN-RF8-MULTIAPP.md`. Section 6 and section 10.6 of this plan carry the
same note. `docs/web/fixtures/README.md` keeps its v1 table marked superseded and adds the v2
table next to it.

### 11.4 Android half

`BestBadgeStore` keys are `best.<missionId>.<engineVersion>` (`RunStores.kt:94`) and
`MissionSurface.kt:52` asks with `FIRELINE_ENGINE_VERSION`, so the badge lineage follows the
constant with no migration code. The engine v1 entries are deliberately left on disk and are
not read: on `emulator-5554` after the bump they are still present
(`best.1.1` = `27e9407472339b7c`, `best.2.1` = `47e28d84c5d61220`) and no card shows them.
`MissionSurface` gained `emptyRunLabel` in its existing companion object (a second companion
object is a Kotlin error, which is why the first attempt failed to compile), and
`MissionPickerModelTest.emptyCardCopyNamesTheShippedEngineVersion` pins the copy to
`no run recorded yet for engine v$FIRELINE_ENGINE_VERSION`. `RunStoresTest` now reads the
shipped engine version for its badge lookups and states that a stored v1 badge is not shown for
engine v2.

### 11.5 Close-out matrix (2026-11-21, `--rerun-tasks`, counts read from result XMLs)

| target | tests | failures | errors | skipped |
|---|---|---|---|---|
| `:rf8-core:jvmTest` | 15 | 0 | 0 | 0 |
| `:rf8-core:wasmJsNodeTest` | 15 | 0 | 0 | 0 |
| `:fireline-engine:jvmTest` | 186 | 0 | 0 | 0 |
| `:fireline-engine:wasmJsNodeTest` | 186 | 0 | 0 | 0 |
| `:fireline-web:wasmJsNodeTest` | 24 | 0 | 0 | 0 |
| `:app:testFreeDebugUnitTest` | 3 | 0 | 0 | 0 |
| `:app:testProDebugUnitTest` | 9 | 0 | 0 | 0 |
| `:fireline-app:testDebugUnitTest` | 40 | 0 | 0 | 0 |
| `:fireline-app:assembleRelease`, `:fireline-app:assembleDebug` | BUILD SUCCESSFUL | | | |
| total | 478 | 0 | 0 | 0 |

478 against 477 for the E1 build: +1 test (`emptyCardCopyNamesTheShippedEngineVersion`), none
lost. `BUILD SUCCESSFUL in 28s`, 176 actionable tasks executed, gradle exit 0. Three consecutive
`--rerun-tasks` runs of this exact task list were green (`BUILD SUCCESSFUL in 36s`, `30s`, `28s`)
and produced the identical per-target counts above. Only result XMLs written by the last build
were counted; the 17 stale pre-KMP `build/test-results/test/` directories (`engine/build`,
`fireline-engine/build`, `fireline-app/build`, `app/build`) were excluded by mtime.

### 11.6 Device cross-check (`emulator-5554`, Medium_Phone, engine v2 debug APK)

`fireline-app-debug.apk` (1281631 bytes, built by `:fireline-app:assembleDebug` in the matrix
above) was installed with `install -r` over the existing engine v1 install, so the v1 store and
prefs survived. MISSION picker before the run: five cards, each reading
`no run recorded yet for engine v2`, and no BEST badge on any card. Mission 02 `CORRIDOR` was
opened with `CORRIDOR-REF.ASM` (375 B / 25 instructions on device, `SELECTED`), and `RUN` was
pressed once. RESULT reads `MISSION COMPLETE`, `MISSION 02 / CORRIDOR   TICK 102/120`,
`firmware 75 B / 25 instructions`, `fingerprint $d3ea4711`, and the event tail
`102 MOVE (2/2) | MISSION SUCCESS`. That equals the headless v2 fixture value for mission 02
completion (`d3ea47119fe3b101` at tick 102 in `parity_matrix.tsv` and `snapshots.json`; the app
shows the first 8 hex). The engine version gate never refused the mission. After the run the
picker shows `BEST: SUCCESS  TICK 102` on mission 02, the other four cards still read the engine
v2 empty-state copy, and the prefs hold `best.2.2` = `d3ea47119fe3b101` next to the untouched
`best.1.1` and `best.2.1`.
The same check was repeated after the close-out matrix: `install -r` of the `fireline-app-debug.apk`
that that build produced (1281631 bytes, `lastUpdateTime` moved to 22:11:05 device time), MISSION
picker read `no run recorded yet for engine v2` on missions 01, 03, 04, 05 and `BEST: SUCCESS
TICK 102` on mission 02, `OPEN RUN SETUP` on mission 02 opened at `TICK 0 / 120` with
`CORRIDOR-REF.ASM` `SELECTED` (`375 B 25 i`), one `RUN` press ended at `MISSION COMPLETE`,
`MISSION 02 / CORRIDOR   TICK 102/120`, `HP 100   HEAT 64   SHIELD 0`, `fingerprint $d3ea4711`,
tail `102 MOVE (2/2) | MISSION SUCCESS`, and the prefs again held exactly the three keys
`best.1.1`, `best.2.1`, `best.2.2`.

### 11.7 Deviations from the section 10.7 recipe

1. Step 2 says to read the new hashes out of the failure messages. The values were taken from
   the generator runs instead (step 3 and 4), which is the same live engine and the source the
   fixtures are built from. The failure messages were only used to confirm which assertions were
   stale.
2. Step 3 anticipated the corrected source trees: `rf8-core/src/commonMain/kotlin`,
   `fireline-engine/src/commonMain/kotlin/robofight/fireline/*.kt` plus
   `.../fireline/session/*.kt` (the harness imports the session layer), compiled into scratch
   class dirs, not the repo and not `/tmp` (the Windows JVM does not see `/tmp`). The classpath
   separator on this shell is `;`.
3. Step 7 names the mockup meta lines as a manual edit. They are generated text, so
   `gen_web_mockups.py` covers them; `gen_web_mockups.py` itself needed the change described in
   section 11.3 because it hardcoded the mission 02 badge.
4. Step 8 names `docs/FIRELINE-WEB.md` sections 13 to 15 and two absent docs. Sections 13 to 15
   are the frozen layout spec and contain no fingerprint to move, so they were not touched; the
   acceptance record in 14.1 carries the dated note. `docs/FIRELINE-APP.md` and
   `docs/FIRELINE-ENGINE-V1.md` do not exist in this checkout. The app-side anchor lives in the
   milestone 7 entry of `PLAN-RF8-MULTIAPP.md`.
5. Step 9 asks for a one-time notice. It is the existing card copy (section 11), not new chrome.
6. Step 10 ends with a commit. Nothing was committed here: the constant, the fixtures and the
   anchors are staged in the working tree for the owner to commit as one commit.
7. Two build failures happened on the way and are recorded rather than worked around:
   `MissionSurface.kt:42:15 Conflicting declarations` plus eleven `Unresolved reference
   'matchParent'` / `'wrapContent'` errors (a second companion object), fixed by moving the
   label into the existing companion object; and `ConsoleModelTest.kt:123:24` and
   `ConsoleModelTest.kt:259:24` `Unresolved reference 'd3ea4711'`, because a short anchor
   starting with a letter must be escaped as `\$d3ea4711` inside a Kotlin string template, fixed
   in three places (`123`, `183`, `259`). The third matrix run was green.

## 12. E2: wall determinism (record from the live engine, then pin, both targets)

Section 7 row E2 asks for new edge-wall rows through the W2 fixture pipeline, "identical hashes across targets, fixture README updated". It was executed on 2026-11-22 on the working tree at HEAD `4bd81cac5a5b529c325e0ff788229a1f6d0a04e5` (engine `FIRELINE_ENGINE_VERSION` 2, the E1b state). No authored mission content was added, nothing in `fireline-engine/src/commonMain` was changed, and nothing was committed.

### 12.1 The wall fixture set

Four layouts, each authored as canonical mission text (`FIRELINE LAYOUT 20` header, `TERRAIN` 20 rows x 20 glyphs, `FIRE` 20 rows x 20 glyphs, `WALL_VERT` 20 rows x 19 glyphs, `WALL_HORIZ` 19 rows x 20 glyphs, `ROVER 2,2,E`), built by `Layout.fromCanonicalText` and validated by `Mission.requireValid`. Common geometry: rover spawn (2,2) facing east, exit (17,2), objectives `REACH_EXIT` (4), time limit 60, starting water 100, wind 0x0, tick guard 200, survive until unset. `wall-lane-move` and `wall-open-lane` use the firmware `loop:  MOVE` / `JMP    loop` (6 bytes, 2 instructions); `wall-nozzle-seal` and `wall-lane-suppress` use `fire:  SHOOT` / `loop:  WAIT` / `JMP    loop` (9 bytes, 3 instructions).

| layout name (matrix scenario) | fixture id | seed | firmware (label, image) | tick probed | probe fingerprint | golden trace hash | final tick | final status | failure reason | final fingerprint | wall edges (`Layout.edgeCount`) | canonical text round trip |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| WALL LANE (`wall-lane-move`) | 101 | 6001 | lane move loop, 6 B / 2 insn | 12 | `4ce6f21b96b15e4f` | `88b5c429de62c944` | 60 | FAILURE | TIME_EXPIRED | `983902383703454a` | 3 | yes |
| SEALED NOZZLE (`wall-nozzle-seal`) | 102 | 6002 | single shoot, 9 B / 3 insn | 12 | `f72074b3a2cbae1e` | `b87628c5ec530355` | 60 | FAILURE | TIME_EXPIRED | `28621a2b7f6d5c29` | 1 | yes |
| WALL LANE SUPPRESS (`wall-lane-suppress`) | 103 | 6003 | single shoot, 9 B / 3 insn | 12 | `afb0ccd208010c01` | `e762879cfcc1a486` | 60 | FAILURE | TIME_EXPIRED | `c5c75948d2b9ec6f` | 3 | yes |
| OPEN LANE (`wall-open-lane`) | 104 | 6001 | lane move loop, 6 B / 2 insn | 12 | `c0769f7efd8bf8a6` | `88b5c429de62c944` | 44 | SUCCESS | (none) | `8204a0e863743898` | 2 | yes |

Wall state per layout, as recorded glyphs: `wall-lane-move` and `wall-open-lane` carry `WALL_VERT` row 2 char 5 (the vertical edge between (5,2) and (6,2); in `wall-open-lane` that glyph is `.`) plus `WALL_HORIZ` row 1 char 2 and row 2 char 2 (the two horizontal edges beside the spawn cell). `wall-nozzle-seal` carries only `WALL_VERT` row 2 char 2, the edge directly in front of the rover's nose. `wall-lane-suppress` uses the lane walls and additionally sets `FIRE` at (6,2) to 2, so a packet has to cross the lane gate.

Edge mechanic exercised, one per row, with the pinned trace as evidence:
1. `wall-lane-move`: the `Rover` MOVE gate. The rover never reaches (17,2), the run dies at `TIME_EXPIRED` on tick 60 with fingerprint `983902383703454a`.
2. `wall-nozzle-seal`: the packet spawn edge gate. Trace line `003 SHOOT (3/3) | PACKET BLOCKED EDGE 3,2`, no packet ever exists on the board.
3. `wall-lane-suppress`: absorption in flight. Trace lines `003 SHOOT (3/3) | PACKET -> 3,2` and `006 WAIT | PACKET SPENT EDGE 6,2`.
4. `wall-open-lane`: the control row. Same seed, same firmware, same rules as `wall-lane-move`, one wall glyph removed, and the run reaches the exit at tick 44 with fingerprint `8204a0e863743898`.

`wall-lane-move` and `wall-open-lane` layout texts are 1704 characters / 85 lines each (1789 characters in the TSV because `ParityMatrix.kt` escapes each newline as `\n`). They differ at exactly one character, offset 927, which is `WALL_VERT` section row 2, char 5: `#` in the blocked row, `.` in the open row. Decoded layout md5: `1deb9606befe73642e5d8825c3e3681d` (`wall-lane-move`), `baefc12181487b4217bc18948d5898bb` (`wall-nozzle-seal`), `94cde4f49a046764742521226ac02e2f` (`wall-lane-suppress`), `687560657e9fd43494d6aa6d4fcb2133` (`wall-open-lane`).

### 12.2 Recording the rows from the live engine, twice

Harness compile (scratch class dir, native forward-slash paths, `;` classpath separator):

```bash
"C:/Program Files/Android/Android Studio/jbr/bin/java.exe" -Xmx2g \
  -cp "C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn \
  -d "C:/Users/Clemens/AppData/Local/hermes/cache/scratch/flw2/cls" \
  -cp "C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar" \
  "C:/Users/Clemens/Documents/github/robofight/rf8-core/src/commonMain/kotlin" \
  "C:/Users/Clemens/Documents/github/robofight/fireline-engine/src/commonMain/kotlin" \
  "C:/Users/Clemens/Documents/github/robofight/docs/web/fixtures/ParityMatrix.kt"
```

exit 0, 0 `error:` lines in the compile log. Then the generator, run twice into scratch (`run4`, `run5`) and twice into `docs/web/fixtures` (runs A and B):

```bash
"$JBR" -cp "$CLS;$SL" fixtures.ParityMatrixKt "C:/Users/Clemens/Documents/github/robofight/docs/web/fixtures"
```

exit 0 on each of the four runs, stdout `engine v2`, `rows=14 arith=29 limits=5`. `diff -r` over the two repo runs printed no differences. md5 of every regenerated file, identical for both runs:

| file | md5 before this step (committed) | md5 after regeneration, both runs |
|---|---|---|
| `parity_matrix.tsv` | `b05740462883cbc547ad650622db64ed` | `2f60459c1c9f7b23e734d105a321f437` |
| `parity_traces.txt` | `449585cb4d1c49b549b15e2ca872bc2c` | `c50bcc35db606b51d7a59d35a68f71ce` |
| `parity_arith.tsv` | `76639b1c5a014db2f3712df798d5f7ae` | `38b395c49928b71f114e4381684a4dca` |
| `limit_probe.tsv` | `a5b8ecda94db87f2649f4ddcd37bd227` | `e0cabb03628a00d057443f8d5d6d935c` |

`parity_matrix.tsv` now has 2 provenance comment lines, one 27-column header line and 14 data rows (the 10 authored mission rows in their original order and values, then the 4 wall rows); `parity_traces.txt` has 14 blocks of 12 trace lines (168 trace lines, 182 lines with headers). A third generator run after the documentation edits (`runC`, exit 0, same `rows=14 arith=29 limits=5`) reproduced the four md5 values byte-for-byte against the files in `docs/web/fixtures`.

### 12.3 Pinning the recorded rows

`python3 docs/web/fixtures/parity_fixture_gen.py` (exit 0) printed `engine v2, commit 4bd81cac5a5b529c325e0ff788229a1f6d0a04e5, rows 14 (10 mission + 4 wall), arith 29, limits 5, trace lines 168` and wrote `parity_matrix.json` (md5 `a1ca0a1c19d55dd62d44bfa29b3d5b78`), `parity_traces.json` (`1c818b6fa34864910699f9ff697f8135`), `parity_arith.json` (`edeb02793553669cce7c6b7f0910cdb4`) and `W2ParityFixtures.kt` (`cacf6b7772d2b6062fbf3cecbfb7e558`, 342 lines).

The matrix schema grew from 15 to 27 columns; the 12 new columns are `seed`, `objectives`, `time_limit`, `survive_until`, `starting_water`, `wind_dir`, `wind_force`, `max_ticks`, `wall_count`, `layout_source`, `firmware_source`, `roundtrip`. No column was removed. The wall layout and firmware text live in the TSV (escaped), so the wall rows are pinned from generator output and no wall layout is typed into a test. `W2ParityFixtures.kt` gained `PARITY_EXPECTED_MISSION_ROWS` (10), `PARITY_EXPECTED_WALL_ROWS` (4), `PARITY_WALL_SCENARIOS`, the rule columns on `ParityFixtureRow`, and the maps `PARITY_WALL_LAYOUT_TEXTS` and `PARITY_WALL_FIRMWARE_TEXTS`. The generator refuses to write unless the recording is self-consistent: 14 rows in the 10 + 4 split, the wall scenario set matching, every `roundtrip` true, `final_tick <= max_ticks`, authored rows carrying no wall state and no layout text, wall rows carrying layout and firmware text whose `#` glyph count equals the engine's own `wall_count`, and section shapes `TERRAIN` 20x20, `FIRE` 20x20, `WALL_VERT` 20x19, `WALL_HORIZ` 19x20.

### 12.4 Two-target proof

`gradle --offline :fireline-engine:jvmTest` and `:fireline-engine:wasmJsTest` each exit 0 (the wasm run reports `BUILD SUCCESSFUL in 25s`, 24 actionable tasks, 8 executed, 16 up-to-date). Result XMLs: `fireline-engine/build/test-results/jvmTest` 19 files, 191 tests, 0 failures, 0 errors, 0 skipped; `fireline-engine/build/test-results/wasmJsNodeTest` 19 files, 191 tests, 0 failures, 0 errors, 0 skipped (186 per target before this step). Both targets read the same generated `W2ParityFixtures.kt`; the tests that assert the wall rows are, on `[jvm]` and on `[wasmJs, node]` alike:

1. `wallFixtureRowsArePinnedFromTheGenerator`: the four wall rows exist, `PARITY_WALL_LAYOUT_TEXTS` is keyed by all four scenarios, and the `#` glyphs in the pinned text equal `Layout.edgeCount()` of the reparsed layout.
2. `everyWallRowReproducesItsRecordedFingerprintOnThisTarget`: the engine re-run of each wall row must match the recorded probe fingerprint, final fingerprint, final tick, status and failure reason.
3. `wallLayoutCanonicalTextRoundTripsWithTheSameFingerprint`: `fromCanonicalText(toCanonicalText(layout))` byte-identical and its run fingerprint identical.
4. `theRemovedWallIsTheOnlyDifferenceBetweenTheBlockedAndOpenLaneRows`: one character in the pinned texts, different final fingerprints, different final ticks and statuses.
5. `wallEventsAppearInThePinnedGoldenTraces`: `PACKET BLOCKED EDGE 3,2` in `wall-nozzle-seal`, `PACKET SPENT EDGE 6,2` in `wall-lane-suppress`.

The pre-existing consumers were widened rather than weakened: `W2ParityMatrixTest` counts rows against `PARITY_EXPECTED_ROWS` with an explicit 10 mission / 4 wall split and maps `wall` to FAILURE and `wall-open` to SUCCESS, `W2GoldenTraceTest` iterates all 14 matrix rows. No assertion was deleted and no expected value was hand-typed.

### 12.5 Nothing else moved

`git diff --numstat -- fireline-engine/src/commonMain` is empty. The 10 pre-existing mission rows are identical before and after in every shared column, including the final fingerprints: `1-reference 245faa6d10316e00`, `1-failing 7cbfe49993c17318`, `2-reference d3ea47119fe3b101`, `2-failing 2e88bd6f1f4ed91e`, `3-reference 01663660e6731d0a`, `3-failing fee14c44c61f245a`, `4-reference acf4656261a3c008`, `4-failing 51f0f7232dd88add`, `5-reference ba8e443d3c469c1d`, `5-failing 89bceb4241170566`. The probe fingerprints, trace hashes, final ticks, statuses, failure reasons and assembler sizes are likewise identical; only new columns were appended and four rows were appended. `parity_arith.tsv`, `parity_arith.json` and `limit_probe.tsv` each changed by exactly one line, the provenance stamp `# commit ed93d8926f9b0d6f9d193df458d104aa5518a7c8` becoming `# commit 4bd81cac5a5b529c325e0ff788229a1f6d0a04e5`; the 29 arithmetic vectors and 5 limit probes are unchanged.

### 12.6 Close-out matrix and fixture gates

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
"C:/Users/Clemens/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle.bat" \
  --offline --rerun-tasks -Dorg.gradle.java.home="C:/Program Files/Android/Android Studio/jbr" \
  -Dorg.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvmargs=-Xmx1g \
  :rf8-core:jvmTest :rf8-core:wasmJsTest :fireline-engine:jvmTest :fireline-engine:wasmJsTest \
  :fireline-web:wasmJsTest :app:testFreeDebugUnitTest :app:testProDebugUnitTest \
  :fireline-app:testDebugUnitTest :fireline-app:assembleRelease -x lintVitalRelease
```

exit 0, `BUILD SUCCESSFUL in 31s`, `160 actionable tasks: 160 executed`, finished 2026-11-22T02:10:42+01:00. Result XMLs written by that build (52 files, mtimes 02:10:25 to 02:10:41, all after the build start marker at 02:10:08):

| module | result dir | tests | failures | errors | skipped | XML files |
|---|---|---|---|---|---|---|
| `:rf8-core` | `jvmTest` | 15 | 0 | 0 | 0 | 1 |
| `:rf8-core` | `wasmJsNodeTest` | 15 | 0 | 0 | 0 | 1 |
| `:fireline-engine` | `jvmTest` | 191 | 0 | 0 | 0 | 19 |
| `:fireline-engine` | `wasmJsNodeTest` | 191 | 0 | 0 | 0 | 19 |
| `:fireline-web` | `wasmJsNodeTest` | 24 | 0 | 0 | 0 | 3 |
| `:app` | `testFreeDebugUnitTest` | 3 | 0 | 0 | 0 | 1 |
| `:app` | `testProDebugUnitTest` | 9 | 0 | 0 | 0 | 3 |
| `:fireline-app` | `testDebugUnitTest` | 40 | 0 | 0 | 0 | 5 |

Total 488 tests, 0 failures, 0 errors, 0 skipped, against the 478 baseline of section 11.6: +5 per `:fireline-engine` target, exactly `W2WallParityTest`. The 17 stale pre-KMP `build/test-results/test/` XMLs sit in directories this target list does not run and none of them carries a mtime in the build window, so they are excluded by directory and by mtime. Fixture gates after the build: `python3 docs/web/fixtures/verify_snapshots.py` exit 0, last line `ALL CHECKS PASSED`; `python3 docs/web/fixtures/verify_crosscheck.py` exit 0, last line `CROSSCHECK PASSED`; `python3 docs/web/verify_layout_v1.py` exit 0, last line `---- 22 checks, 0 failed`.

### 12.7 Deviations from the section 7 row

1. The row says "new edge-wall rows through the W2 fixture pipeline". Executing it required widening the matrix schema by 12 columns (section 12.3), because the wall layouts are not authored missions: their canonical text exists in no source file, so it has to be carried by the recording itself or it would have to be typed by hand into a test, which the fixture provenance rules forbid.
2. The four wall layouts get fixture ids 101 to 104 and are not added to `Missions.kt`. `W2FixtureMissions.kt` rebuilds each wall mission from the pinned canonical text plus the recorded rule columns. Reason: section 7 assigns content to step E5, and the free/Circuit wall mission is a content decision.
3. The row says "identical hashes across targets". The fingerprints and trace hashes are identical on `[jvm]` and `[wasmJs, node]`, with one recorded nuance: `wall-lane-move` and `wall-open-lane` share the trace hash `88b5c429de62c944` with each other and with `2-failing`, because the 12-tick event trace records the instruction executed and not the position reached, and the MOVE gate does not add a trace event. The wall block is therefore proven by the divergent final fingerprints (`983902383703454a` at tick 60 against `8204a0e863743898` at tick 44) and by `EdgeWallsTest`, not by the trace text.
4. Count assertions that depend on the row count were updated as required: `parity_fixture_gen.py` (`EXPECTED_ROWS` 14 = 10 mission + 4 wall), `W2ParityMatrixTest` (row count plus the explicit split), `W2GoldenTraceTest` (one trace per matrix row, 14). The `:fireline-web` console gate keeps consuming only the 10 authored mission rows and its source comment now says so; asserting wall rows there would require authored catalog content.
5. Scratch placement: the class dir and the generator logs live in scratch (`C:/Users/Clemens/AppData/Local/hermes/cache/scratch/flw2/cls`, logs under the shell `$TMPDIR`, which resolves to `C:/Users/Clemens/AppData/Local/Temp`), never in the repo. Nothing was committed, matching the standing rule for this branch.

## 13. Record E5 (content): mission 06 `THIN WALL`, the teaching mission (2026-11-22)

Content step E5 of section 7 landed 2026-11-22 on branch `codex/edge-walls-e1` at commit
`dd60c20`, on top of the E2 commit `afec375`. Scope is `:fireline-engine` mission content,
`:fireline-app` and `:fireline-web` picker expectations, the fixture set, and the player-facing
docs; `FIRELINE_ENGINE_VERSION` stays 2.

Step E5 was pulled forward ahead of E3 and E4 by owner decision on 2026-11-21 (the ordering
question and its answer are recorded in §12.7 item 2). Reason: E3 (Android edge segments) and E4 (web edge overlay) would otherwise draw their first wall segments against the synthetic §12 layouts, which ship in no product. With a real sixth mission in the catalog, the wall becomes visible on both surfaces with no extra content work, and the renderers get a mission-shaped test target.

### 13.1 What was added

One authored mission, `Missions.kt:363` `val THIN_WALL` (id 6, name `THIN WALL`), appended to `Missions.ALL` (`Missions.kt:416`); the file header now reads "the six authored free missions" (`Missions.kt:4`). Design doc: `FIRELINE-DESIGN.md` §9.6 (`06 / THIN WALL`). Player-facing doc: `MANUAL.md` §8.4 note under the `MOVE` row (the no-op row now has a mission that drills it).

Layout, from `Layout.decode(...)` at `Missions.kt:367-382`:

- terrain: refill lanes at row 1 and row 3 for `x = 2..17`, refill cells at (4,2) west of the gate and (7,2) east of the fire, exit `E` at (17,2), rover spawn `R` at (2,2). `field(2, 2, ...)` writes cells directly, so authored row index is `y` and column index is `x`.
- fire: `fireAt` a single level 1 cell at (6,2).
- edge walls: `vertEdgeRows(2 to 5)` is the thin wall on the edge to the right of (5,2), the lane gate. `horizEdgeRows(1 to 2, 2 to 2)` is the edge below (2,1) plus the edge below (2,2), which seals the spawn cell to the north and to the south. `horizEdgeRows` is documented at `Missions.kt:60` as "the edge BELOW cell (column, row)", matching §4.
- no wall cells: `layout.terrain.count { it == TERRAIN_WALL } == 0`, asserted in §13.2. `layout.edgeCount() == 3`.
- rules: seed 6027, objectives `EXTINGUISH_ALL | REACH_EXIT` (bitmask 5), `timeLimit = 120`, `maxTicks = 300`, `startingWater = 100`, wind `W` x 0 (a direction with no force, so nothing in this mission is the wind's doing).
- why the lanes are refill: a refill cell never ignites, so the spread can only ever involve the two cells the gate separates, (6,2) and (5,2). Row 3 is simultaneously the firebreak and the bypass lane the reference solution shoots the lane from. The layout comment (`Missions.kt:356-362`) records that seed 6027 is the seed whose spread stream attempts the westward crossing of the walled edge at tick 15 and never relights the source cell after it is drowned.

Firmware: reference 108 bytes / 36 instructions (`Missions.kt:389-407`): three `MOVE` to (5,2), `IN AHEAD` plus `JZ straight` (the thin wall answers the 1 sentinel a wall cell would give, §6.2), `TURN R` into the row 3 bypass, one step to (5,3), `TURN L`/`MOVE` to (6,3), `TURN L` to face north, two `SHOOT` to drown (6,2) from below (the packet spawns on the target, no edge to cross), back west and `TURN R` to face north again, two `SHOOT` to drown (5,2) through the bypass, then `TURN R` and fourteen `MOVE` east along row 3 to (17,3), `TURN L` and one `MOVE` into the exit at (17,2). Documented failing firmware `spray through the thin wall` (15 bytes / 5 instructions, `Missions.kt:408-412`): `MOVE` x3 into the gated cell, then `SHOOT` in a loop at the closed lane.

### 13.2 The teaching claims are engine-pinned, not prose

`Step10AuthoredMissionsTest.thinWallGatesMovementAndWaterButNotFire` (new; `[jvm]` and `[wasmJs, node]`) asserts: id 6; objectives `EXTINGUISH_ALL | REACH_EXIT`; `timeLimit == 120` with the comment "same limit as mission 02: the route is a short walk"; `windForce == 0` ("the crossing here is the wall's, not the wind's"); zero wall cells; `edgeCount() == 3` naming the §12 `wall-lane-move` row as the shape it came from; `wallBetween(5, 2, 6, 2)` is the gate; `wallBetween(5, 2, 5, 3)` is false, the bypass south of the lane is open; `terrainAt(6, 2)` is floor and `terrainPassable` of it is true ("a thin wall never makes a cell non-free", §3/DW1); exactly one burning cell. Then two live runs:

1. An always-forward bot (`MOVE` x3 plus a `WAIT` loop) finishes with `rover.x == 5`, `rover.y == 2` and `distanceMoved == 3`: the blocked `MOVE` pays its 2 ticks without stepping, and the rover stops on the west side of the gate.
2. The documented failing firmware, driven tick by tick with an `onTick` hook over `world.lastEvents`, records `blockedShots >= 3` events starting `PACKET BLOCKED EDGE` (packets aimed at a thin wall are refused at spawn), one event containing `IGNITES 5,2` and `from 6,2` (fire spread westward across the walled edge, DW1), and `failureReason == ROVER_DESTROYED` ("the fire crossed the wall the water could not cross, which is the lesson").
3. The reference run: `RunStatus.SUCCESS`, `burningCells == 0`, `fireIntensitySum == 0`, inside the limit, firmware size in the 75..111 byte and 25..37 instruction bands of missions 01-05.

Two §10 tests were widened rather than duplicated (their count is unchanged at 22 in `EdgeWallsTest`): `shippedMissionLayoutsCarryNoWallsExceptTheTeachingMission` (missions 01-05 keep zero edges, mission 06 carries 3) and `missionSourcesRoundTripAndOnlyMission06CarriesWallBlocks` (every authored mission source round trips through `Layout.toCanonicalText`/`fromCanonicalText`, and the `WALL_VERT`/`WALL_HORIZ` blocks appear in exactly one mission's canonical text). `MissionPickerModelTest` gained `catalogIsTheSixAuthoredMissionsInOrder` (6 tests in that suite) and the picker tests now name six cards.

### 13.3 Fixture rows and provenance

`docs/web/fixtures/parity_matrix.tsv` grew from 14 to 16 rows: the two new rows are the authored mission 06 with its reference and its documented failing firmware. Generated by `fixtures.ParityMatrixKt` at HEAD `afec3755273df5d278d7349e2438979cd3d09bcb` with mission 06 in the working tree, the same provenance convention as §12.3 (the `# commit` header names the commit HEAD was at generation time; the rows land in the commit that follows).

| scenario | probe tick | probe fingerprint | golden trace hash | final tick | status / reason | final fingerprint | firmware | wall edges | round trip |
|---|---|---|---|---|---|---|---|---|---|
| `6-reference` | 12 | `a85a699d9f1ef675` | `5f7fd7153968d3c2` | 59 | SUCCESS / - | `61fe3d4c35449094` | 108 B / 36 insn | 3 | yes |
| `6-failing` | 12 | `a5e475326ab2e2af` | `aae04624e85d38cf` | 59 | FAILURE / ROVER_DESTROYED | `42bdbe754583cd57` | 15 B / 5 insn | 3 | yes |

The 12-tick trace of `6-failing` is the wall evidence in the trace itself: instruction 009 `SHOOT (3/3)` carries `PACKET BLOCKED EDGE 6,2`, the spawn refusal at the gate cell. In `6-reference` the first 12 ticks are the approach (six `MOVE` ticks, `IN AHEAD`, `JZ (skip)`, `TURN R`, two more `MOVE`, `TURN L`); the drowning shots and the fire crossing happen after the trace window, which is why §13.2 asserts them from live events instead.

Generated file digests after the step (md5): `parity_matrix.tsv` `f7b950f13df715bc651b955647fbd8d0`, `parity_traces.txt` `c53090daef81b1a457fd8d21b20fbc1f`, `parity_arith.tsv` `4344825cf9b453dfbfb3ebc8778463a1`, `limit_probe.tsv` `802dda7c0e6303a42da49326f0a50335`, `parity_matrix.json` `5aaa58eed5e566cd3ea5f16aae2d789b`, `parity_traces.json` `8a54593a03d43561cc7d7ceb128f291c`, `parity_arith.json` `ec26de578658f7b63ae385fbc8be1b7a`, `W2ParityFixtures.kt` `54d936cc384de4e3259b14a84a759eef`. Re-running `parity_fixture_gen.py` on the committed tree left all of `parity_matrix.json`, `parity_traces.json`, `parity_arith.json` and `W2ParityFixtures.kt` byte-identical (2026-11-22, generator line `engine v2, commit afec3755273df5d278d7349e2438979cd3d09bcb, rows 16 (12 mission + 4 wall), arith 29, limits 5, trace lines 192`), so the pinned Kotlin is reproducible from the recorded TSV and not hand-typed.

The generator grew an isolation gate for this step (`parity_fixture_gen.py:36-41`, `:183-185`, `:211-217`): the row split must be exactly 12 mission plus 4 wall, the scenarios carrying edge wall state among the authored rows must be exactly `6-reference` and `6-failing`, a mission 06 row with `wall_count` below 1 is refused, and any other authored row carrying wall state is refused.

### 13.4 Nothing else moved

`git diff --numstat -- fireline-engine/src/commonMain` is `98 3 Missions.kt` and that is the only entry: no rules, VM, world, packet, rover or `FirelineRules.kt` change, and `FIRELINE_ENGINE_VERSION` stays 2. The 14 pre-existing rows are identical before and after in every one of the 27 columns, cell by cell, including the mission 01-05 fingerprints `245faa6d10316e00`, `7cbfe49993c17318`, `d3ea47119fe3b101`, `2e88bd6f1f4ed91e`, `01663660e6731d0a`, `fee14c44c61f245a`, `acf4656261a3c008`, `51f0f7232dd88add`, `ba8e443d3c469c1d`, `89bceb4241170566`, their probe fingerprints, trace hashes and wall counts, and the four §12 wall rows `983902383703454a`, `28621a2b7f6d5c29`, `c5c75948d2b9ec6f`, `8204a0e863743898`. `parity_arith.tsv`, `limit_probe.tsv` and `parity_matrix.tsv` each changed their provenance header by exactly one line, `# commit 4bd81cac5a5b529c325e0ff788229a1f6d0a04e5` becoming `# commit afec3755273df5d278d7349e2438979cd3d09bcb`; the 29 arithmetic vectors and 5 limit probes are unchanged. `parity_matrix.json` grew by the two rows, `parity_traces.txt` from 182 to 208 lines (16 headers plus 192 trace lines), `snapshots.json` (`b44a2cb99070fdceac48c791727cb992`) and `crosscheck.tsv` (`9fa547511fade557a5d9eeb2c7df2f56`) are untouched (see §13.7 item 4).

### 13.5 Close-out matrix and fixture gates

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
"C:/Users/Clemens/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0/bin/gradle.bat" \
  --offline --rerun-tasks -Dorg.gradle.java.home="C:/Program Files/Android/Android Studio/jbr" \
  -Dorg.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvmargs=-Xmx1g \
  :rf8-core:jvmTest :rf8-core:wasmJsTest :fireline-engine:jvmTest :fireline-engine:wasmJsTest \
  :fireline-web:wasmJsTest :app:testFreeDebugUnitTest :app:testProDebugUnitTest \
  :fireline-app:testDebugUnitTest :fireline-app:assembleDebug :fireline-app:assembleRelease -x lintVitalRelease
```

exit 0, `BUILD SUCCESSFUL in 45s`, `176 actionable tasks: 176 executed` (the two `:fireline-app` assemble targets are here because §13.6 needs the debug APK). Result XMLs written by that build (53 files, mtimes 09:25:53 to 09:26:10 on 2026-11-22):

| module | result dir | tests | failures | errors | skipped | XML files |
|---|---|---|---|---|---|---|
| `:rf8-core` | `jvmTest` | 15 | 0 | 0 | 0 | 1 |
| `:rf8-core` | `wasmJsNodeTest` | 15 | 0 | 0 | 0 | 1 |
| `:fireline-engine` | `jvmTest` | 192 | 0 | 0 | 0 | 19 |
| `:fireline-engine` | `wasmJsNodeTest` | 192 | 0 | 0 | 0 | 19 |
| `:fireline-web` | `wasmJsNodeTest` | 24 | 0 | 0 | 0 | 3 |
| `:app` | `testFreeDebugUnitTest` | 3 | 0 | 0 | 0 | 1 |
| `:app` | `testProDebugUnitTest` | 9 | 0 | 0 | 0 | 3 |
| `:fireline-app` | `testDebugUnitTest` | 40 | 0 | 0 | 0 | 5 |

Total 490 tests, 0 failures, 0 errors, 0 skipped, against the 488 of §12.6: +1 per `:fireline-engine` target (`thinWallGatesMovementAndWaterButNotFire`); `Step10AuthoredMissionsTest` is 12 per target, `EdgeWallsTest` stays 22 per target. The two web suites name the new mission inside their existing tests rather than adding cases, so `:fireline-web` stays 24: `ConsoleModelTest` `Triple(59, "61fe3d4c", 108 to 36)` in the six-mission best-run table and `pickerRowsCarryTheCommittedEngineV2BestRunForAllSixMissions`, `ConsoleRenderTest` `renderedPickerCarriesAllSixMissionCardsWithTheirCommittedBestRunAnchors` with `$61fe3d4c` in the anchor list. The 17 stale pre-KMP `build/test-results/test/` XMLs sit in directories this target list does not run and carry no mtime in the window. Fixture gates after the build: `python3 docs/web/fixtures/verify_snapshots.py` exit 0 (`ALL CHECKS PASSED`), `python3 docs/web/fixtures/verify_crosscheck.py` exit 0 (`CROSSCHECK PASSED`), `python3 docs/web/verify_layout_v1.py` exit 0 (`---- 22 checks, 0 failed`).

### 13.6 Device check (emulator-5554, Medium_Phone)

The APK under test is the one the matrix above produced: `fireline-app/build/outputs/apk/debug/fireline-app-debug.apk` md5 `04debd9da66e7db6b1f93c7663d84027`, and the installed `base.apk` read back over adb carries the same md5, so the device is provably running the build under test. Launch component `robofight.fireline/.app.MainActivity` (resolve-activity), `versionName=0.1`, `flags=[ DEBUGGABLE ... ]`.

uiautomator dump of the MISSION tab (dump succeeded, `UI hierchary dumped to: /sdcard/pick2.xml`) lists six cards, and the sixth reads:

```
06  THIN WALL
The wall here is only a line between two cells. It stops your MOVE and it stops your water, but the fire crosses it. Read AHEAD, turn into the refill lane, drown the lane on both sides of the wall, then walk the bypass to the exit.
EXTINGUISH_ALL
REACH_EXIT
SEED 6027 (FIXED)  //  LIMIT 120 TICKS  //  WIND W x0  //  WATER 100
no run recorded yet for engine v2
```

Missions 01-05 keep their cards, mission 02 keeps `BEST: SUCCESS  TICK 102`, and the four previously recorded empty states read `no run recorded yet for engine v2`, which is §11.4's wording for the new engine. `shared_prefs/fireline_ui.xml` read back with `run-as` still holds `mission`, `best.2.2`, `best.2.1`, `best.1.1`, `last_run`, `firmware`: nothing was cleared or rewritten by this step.

Deliberate limit of this device proof: E3 has not landed, so the Android grid draws no edge segments yet. What was proven on device is the catalog and its card copy; the on-screen wall is an E3 acceptance item.

### 13.7 Deviations and open items

1. Order: E5 landed before E3 and E4 (§13 preamble). The §7 table's step order is kept as written; this record is the deviation note.
2. Mission 06 is not a copy of a §12 fixture. It reuses the `wall-lane-move` lane and gate, and then diverges: the fixture's blocked lane runs out its 60-tick limit (`983902383703454a` at tick 60), while the mission adds the bypass lane, the refill firebreaks, the exit at (17,2) and a 120-tick limit, and its reference finishes at tick 59. The §12 rows keep their own pinned values; nothing in this step re-tunes them.
3. The mission lane shape had to be re-tuned rather than lifted verbatim, which is why `wall-lane-move`'s tick-60 block does not appear in mission 06: the mission's rover needs a reachable bypass, and the `wall-lane-move` shape has none. Recorded here because a reader comparing §12.1 with §13.1 will see the same three walls and different outcomes.
4. `docs/web/fixtures/snapshots.json` still carries 8 snapshots for missions 01 and 02 only, and mission 06 has none. Decision: its snapshots arrive with E4, not before. Reason: a snapshot of a thin-wall mission is wall-blind today. `TextGrid` renders cells, and a thin wall is not a cell, so a mission 06 snapshot would show a wall-free grid for the mission that is named after its wall. The snapshot schema has to carry the `WALL_VERT` 20x19 and `WALL_HORIZ` 19x20 rows (engine `Layout.wallVertRowMajor`/`wallHorizRowMajor`) for the data to be honest, and E4 needs exactly that data to draw the web overlay. E4 therefore owns: the two wall fields in `SnapshotFixtures.kt`, the corresponding checks in `verify_snapshots.py` and `verify_crosscheck.py`, the mission 06 snapshot set, the console edge overlay, and the `verify_layout_v1.py` edge gate.
5. No challenge-code content. `CHALLENGE-CODES.md` is untouched: challenge codes are the Opcode Arena surface, and this is a FIRELINE mission.
6. Carried forward from §10.8, unresolved by this step: edge walls are static layout state and are not part of `canonicalState`, so two layouts that agree on terrain, fire, rover and packets but differ in edges hash identically. Harmless while every mission source is fixed in code (this step proves the mission's own canonical text round trips with its walls, `6-reference`/`6-failing` `roundtrip` = yes), and a decision before The Circuit ships variable arena layouts, most likely a `walls=` line in the canonical state. Not taken here because it would move every fingerprint again with no content to show for it.

---

## 14. E3 and E4 landed: both product surfaces draw the thin wall

Rendering step E3 landed 2026-11-25 on branch `codex/edge-walls-e1` (previous
HEAD `e5e7b1e`), scope `:fireline-app`: 4 tracked files changed
(`MissionRunSurface.kt` 46/4, `FirelineGridView.kt` 31/1) plus 4 new files
(`view/EdgeWallGeometry.kt`, `mission/GridLegend.kt` and their two test files).

Rendering step E4 landed the same day on top of E3, scope `:fireline-web` (4
tracked files: `console.html` 28/1, `ConsoleRender.kt` 1/1,
`WorldGrid.kt` 122/9, `WorldGridTest.kt` 154/4) and `docs/web` (the snapshot and
crosscheck fixture generators, the regenerated fixture data, the layout
verifier's 15.8 gate, the 5 frozen mockups) plus the dated layout amendment
`docs/FIRELINE-WEB.md` 15.8.

The full step records are `docs/EDGE-E3-RECORD.md` and
`docs/EDGE-E4-RECORD.md`: unit tests by name, the close-out matrix run with the
canonical §11.6 command (`BUILD SUCCESSFUL in 35s`, 178 actionable tasks, exit
0, 570 tests across 9 result directories, 0 failures), the fixture regeneration
digests, the device pixel measurement and the browser measurement.

The §7 acceptance items:

- **E3, "grid shows a wall line that is visibly not a wall cell"**: closed by 3
  wall-coloured canvas components (1 010 px in exactly 3 connected components)
  centred on shared cell borders, one cell side long, `0.120-0.140` of a cell
  thick, with the two cells each bar separates holding no filled wall block
  (`EDGE-E3-RECORD.md` §5). "BEST badges behave as DW3 decided": closed by
  `BEST: SUCCESS  TICK 59` on mission 06, `BEST: SUCCESS  TICK 102` untouched on
  mission 02, and four cards reading `no run recorded yet for engine v2`
  (`EDGE-E3-RECORD.md` §5).
- **E4, "web and Android describe the same edges for the same mission"**: closed
  across five channels (Android unit, Android device, web unit, the `WALL` /
  `wall_count` fixture rows, and the live browser) that all describe
  `VERTICAL(5,2)`, `HORIZONTAL(2,1)`, `HORIZONTAL(2,2)` for mission 06, and by
  the browser run finishing at `TICK 59` with fingerprint `61fe3d4c` against
  `parity_matrix.tsv` `6-reference` `59 SUCCESS 61fe3d4c35449094` and the device
  badge `BEST: SUCCESS  TICK 59` (`EDGE-E4-RECORD.md` §6, §7).

Deviations of these two steps, in full in the records' deviation sections: the
A-6 label collision resolved by annotation (`EDGE-E3-RECORD.md` 7.1), the legend
entry on its own row on both surfaces (7.2), the Android accessibility wall
counter that the plan did not ask for (7.3), the web's integer stroke rounding
(`EDGE-E4-RECORD.md` 9.1), the touch bump that had to move off `.wallseg`
because a rule may not read the custom property it declares (9.2, measured),
the fixed-size legend keys (9.3), the dropped telemetry counter (9.4, §15.8 now
states what the code does), the snapshot harness tick cap (9.5), and the
pre-existing 320px pair-floor overflow recorded but not fixed (9.6, §8).

The line "E3 has not landed, so the Android grid draws no edge segments yet"
(`§13.6`) was true when §13 was written and is not rewritten; this section is
what closed it.

Open items this step leaves: the §13.7 item 6 decision (edges are not in
`canonicalState`, to be taken before The Circuit ships variable arena layouts),
and the Circuit-side content step of §7's "one Circuit mission later".
