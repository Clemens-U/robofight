# PLAN-EDGE-WALLS: thin walls as real mechanics in FIRELINE

> Status: **proposal, decisions OPEN** (section 8). Not yet specced into
> `FIRELINE-DESIGN.md`, not yet scheduled against the web phases.
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
| E3 | Android: `FirelineGridView` segments, legend entry, amendment A-6; on-device check on `emulator-5554` | grid shows a wall line that is visibly not a wall cell; BEST badges behave as DW3 decided |
| E4 | Web: grid overlay in `:fireline-web` plus the same legend, `verify_layout_v1.py` gains the edge gate, browser measurement of the rendered edges | web and Android describe the same edges for the same mission |
| E5 | Content and specs: one free authored mission that teaches edges (mechanism free), one Circuit mission later (content paid); amend `FIRELINE-DESIGN.md` 3.2, 6.2, 9 and 11.4, plus `MANUAL.md` | docs and store copy describe what the engine actually does |

## 8. Decisions (OPEN, with recommendation)

Asked of the user on 2026-11-21; no answer within the window. The defaults below
are therefore **assumed for the engine step only**, and they are all reversible:
nothing in the assumed set changes a shipped mission, a saved record, a published
best, or `FIRELINE_ENGINE_VERSION`. The bump (DW3) is the one decision with
user-visible consequences, so it stays un-cut until the user picks it.

Assumed and proceeding: DW1 (a), DW2 (a), DW4 (a), DW5 (a), DW6 (a). Deferred to
the user: DW3.

| ID | Question | Options | Recommendation |
|---|---|---|---|
| DW1 | Do edge walls block fire? | (a) no, they gate rover, packets and `AHEAD` only; (b) yes, they are firebreaks and spread may not cross one | **(a)**. It keeps the whole fire model (section 4 of `FIRELINE-DESIGN.md`, its RNG streams and thresholds) untouched, and wall cells already never burn. A firebreak edge would be a second, larger semantics change. |
| DW2 | Authoring form for edges | (a) two optional edge blocks next to the 20x20 terrain rows; (b) one 41x41 picture block as `Arena.render()` does | **(a)**. Existing mission sources stay byte-identical and the terrain rows keep meaning terrain. |
| DW3 | Version policy | (a) bump `FIRELINE_ENGINE_VERSION` 1 to 2, and reset the engine-v1 mission/record stores with an explicit one-time notice; (b) stay v1 and mark only edge-using layouts with a layout flag; (c) develop behind an internal flag and cut the bump later with the shared "Walls Down" pack (`GAMEPLAY-IDEAS.md:216-220`, `PLAN-PRO.md` §6) | **(c) now, (a) at ship.** Ground rule 4 is not negotiable, and a bump wipes saved bests, so bump once, in the same cut as the other new escape semantics, rather than twice in a month. |
| DW4 | Retrofit the five shipped missions? | (a) no, author new content that uses edges; (b) retrofit existing layouts | **(a)**. Retrofitting invalidates every committed anchor, the store screenshots and every published best. |
| DW5 | Sensor breadth | (a) `AHEAD` only, Arena parity; (b) also a wall-aware range read (nearest edge ahead at distance n) | **(a)** for v1. Per-direction scanning is its own backlog idea (`GAMEPLAY-IDEAS.md:47-50`) and deserves its own decision. |
| DW6 | Sequence against the web phases | (a) run E1/E2 now on its own branch, then E4 as part of W3 once the web grid exists; (b) wait until W4 hosting ships | **(a)**. E1/E2 change no web file, the grid overlay (E4) needs the same §15 amendment whether it lands now or later, and the web grid step is already in flight. |

## 9. Out of scope here

Destructible edges (that is `GAMEPLAY-IDEAS.md:62-64`), gates, plates and puzzle
ports (the "Walls Down" pack), diagonal or partial-segment walls, walls thicker
than one cell boundary, sprites or any non-text rendering, and any change to the
`.asm`/SAF format.
