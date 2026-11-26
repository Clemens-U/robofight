# PLAN-RECONCILE-MAIN-01: the web line and the released FIRELINE 0.1 line

Branch `codex/reconcile-main-01`, opened 2026-11-26 at commit `c198f44` (identical to
`codex/edge-walls-e1` at opening, so this branch carries the whole web programme: W1 KMP/wasm,
W2 parity fixtures, W3 console with records 1-19, engine E1 edge walls, E1b version bump, E2 wall
fixtures, E5 mission 06 `THIN WALL`, and the E3/E4 renderers).

This document is a conflict map, not a resolution. It records what a real merge of `origin/main`
into this branch does, so that the reconciliation is a set of owner decisions instead of a
surprise during a merge.

## 1. How this map was measured

```bash
git worktree add "C:/Users/Clemens/AppData/Local/hermes/cache/scratch/reconcile-wt" codex/reconcile-main-01
git merge --no-commit --no-ff origin/main     # 15 conflicts, 60 files auto-merged
git status --porcelain                        # conflict inventory below
git merge --abort                             # nothing was committed from the merge attempt
```

Measured 2026-11-26 against `origin/main` = `e4696cb` ("Add Opcode Arena landing page concepts")
and `origin/codex/reconcile-main-01` = `c198f44`. Merge base: `8e6b4b8` (Milestone 9). Since that
base, main carries 14 commits this branch does not have, and this branch carries 35 commits main
does not have. The working tree of `codex/edge-walls-e1` and this branch were left clean; the
merge attempt was aborted.

## 2. The two lines are not the same FIRELINE

| | `origin/main` `e4696cb` | this branch `c198f44` |
|---|---|---|
| engine version | `FIRELINE_ENGINE_VERSION = 3` | `FIRELINE_ENGINE_VERSION = 2` |
| engine source tree | `fireline-engine/src/main` + `src/test` (12 + 12 files, JVM only) | `src/commonMain` + `src/commonTest` (KMP, wasmJs target) |
| web module | absent (`:fireline-web` does not exist) | `:fireline-web` wasm console plus `docs/`, `docs/web/fixtures/`, mockups |
| authored missions | 5 (`NOZZLE_CHECK`, `CORRIDOR`, `FIRST_RESPONSE`, `DOWNWIND`, `HOT_ZONE`), all five revised, main's own test `allAuthoredMissionsUseThinWallsWithoutChangingTheirGridSize` puts thin walls in every one | 6, missions 01-05 carry no edges, `06 THIN WALL` carries three (`PLAN-EDGE-WALLS.md` §13) |
| free picker | "The three free missions in catalog order" | all six (`Missions.ALL.map`) |
| packet range | `WATER_RANGE = 5`, `PACKET_LIFE = WATER_RANGE` | `PACKET_LIFE = 20`, no `WATER_RANGE` |
| fire vs an edge wall | spread is blocked (`wallsBlockSpreadButKeepDrawCountAndTargetSensorsUnchanged`) | spread crosses it (decision DW1, the teaching point of mission 06: `IGNITES 5,2` `from 6,2`) |
| edge walls in the deterministic hash | yes: `canonicalState` appends `verticalEdges=` and `horizontalEdges=` row-major strings | no: `wallVertRowMajor()` / `wallHorizRowMajor()` exist for renderers only (open item recorded in `PLAN-EDGE-WALLS.md` §13.7 item 6) |
| release state | `fireline-app` `versionCode = 2`, `versionName = "0.1"`, release signing config (keystore git-ignored), Play screenshots, feature graphic, first-use starter firmware, launcher art | `versionCode = 1`, no signing config |

main-side workload since the merge base, over `fireline-engine`, `fireline-app`,
`FIRELINE-DESIGN.md`, `MANUAL.md`: 32 files, 855 insertions, 268 deletions. Biggest single
Biggest single change: `MissionRunSurface.kt`, 175 changed lines. New engine test file:
`ThinWallsTest.kt` (153 lines, 7 test cases).

Both lines implemented thin walls independently and the geometry agrees (main sizes `vertEdge` as
`GRID * (GRID - 1)` = 380, this branch sizes `VERT_EDGE_SLOTS` = 380), but the rules do not agree.

## 3. Conflict inventory (15 conflicts, 60 files auto-merged)

Auto-merged cleanly: 38 added files, 22 modified files. `docs/`, `docs/web/fixtures/`,
`:fireline-web`, and `PLAN-EDGE-WALLS.md` exist only on this side and conflict with nothing.

Content conflicts (13):

| file | what is actually in conflict |
|---|---|
| `.gitignore` | both sides added ignore lines |
| `FIRELINE-DESIGN.md` | both sides restated the mechanic spec; main revised all five mission layouts, this branch documents the DW1 decisions in §9.6 |
| `fireline-app/.../mission/MissionPickerModel.kt` | three free missions vs six catalog missions |
| `fireline-app/.../mission/MissionRunSurface.kt` | main's play-interface rework vs this branch's E3 edge-segment drawing and telemetry fix |
| `fireline-app/.../mission/MissionSurface.kt` | main's simplified selection vs this branch's six-card catalog assumptions |
| `fireline-app/.../view/FirelineGridView.kt` | main's grid revision vs this branch's edge-segment renderer |
| `fireline-app/src/test/.../FirmwareStoreRulesTest.kt` | main's simplified firmware selection rules vs this branch's store expectations |
| `fireline-app/src/test/.../MissionPickerModelTest.kt` | three-mission assertions vs `catalogIsTheSixAuthoredMissionsInOrder` |
| `fireline-engine/src/commonMain/.../FirelineRules.kt` | engine version 3 plus `WATER_RANGE` against version 2 plus `PACKET_LIFE = 20` |
| `fireline-engine/src/commonMain/.../FirelineWorld.kt` | main's spawn refusal plus spread blocking plus canonical wall lines, against this branch's DW1 spread-crossing and renderer-only wall copies |
| `fireline-engine/src/commonMain/.../Rover.kt` | both block `MOVE` across an edge; different surrounding code |
| `fireline-engine/src/commonMain/.../WaterPacket.kt` | both lines block a packet mid-flight (`WaterPacket.process` checks `wallBetween`) and both use `PACKET SPENT EDGE`, main printed with a source arrow (`$x,$y -> $nx,$ny`, line 43) and this branch without (`$nx,$ny`, line 47); spawn refusal is `PACKET BLOCKED EDGE` on both, main with the arrow (line 189), this branch bare (line 208). The only real difference is range: `WATER_RANGE = 5` there, `PACKET_LIFE = 20` here |
| `fireline-engine/src/commonMain/.../session/MissionSession.kt` | this branch's shared session layer (E-series, W3.1) against main's app-side session |

Structural conflicts (2):

| file | git's own words |
|---|---|
| `fireline-engine/src/commonTest/.../ThinWallsTest.kt` | `CONFLICT (file location)`: added in `origin/main` inside a directory that was renamed in HEAD, git suggests moving it to `src/commonTest/...` |
| `fireline-engine/src/main/.../Layout.kt` | `CONFLICT (modify/delete)`: deleted in HEAD (moved to `src/commonMain/.../Layout.kt`), modified in main, main's version left in tree. Rename detection paired the other engine files with their `commonMain` paths, but not this one, so main's +48 lines of wall code in `Layout.kt` has to be ported by hand |

Read the structural pair as the real shape of the task: the KMP move is recognized for most files,
and two files need a manual decision about where they live and which implementation survives.

## 4. Decisions to take before resolving anything

1. Edge semantics. main blocks fire spread across a wall; this branch crosses it by owner decision
   DW1. This is not a merge detail, it is a rule. Mission 06 `THIN WALL` only exists as written if
   DW1 survives; if main's rule survives, mission 06 must be redesigned or dropped.
2. Packet range. `WATER_RANGE = 5` (main, already in a released app) or `PACKET_LIFE = 20` (this
   branch). Every mission solution and every fingerprint depends on this.
3. Mission catalog and free tier. 5 missions with 3 free, or 6 missions with 6 free.
4. Engine version and re-baseline. Either adopt main's 3 and treat this branch's engine work as
   subsumed, or bump to 4 and re-baseline with the numbered procedure in
   `PLAN-EDGE-WALLS.md` §10.7. A bump changes every fingerprint, including wall-free missions.
5. Canonical state. main already hashes both edge maps into `canonicalState`. That closes the open
   item this branch recorded in §13.7 item 6, at the cost of re-hashing every stored best run.
   Recommendation: adopt main's approach, because shipping code already does it and the paid tier
   will ship variable arena layouts.
6. Source tree. `src/commonMain` everywhere (needed for the wasm console, so main's 12 engine files
   and 12 test files move) or keep main's JVM-only tree and drop the web line from main.
7. Which `Layout.kt` survives. Two independent implementations of the same 380 + 380 geometry,
   different accessor names (`verticalEdgesRowMajor` vs `wallVertRowMajor`) and different mission
   text encodings (main's own wall lines vs this branch's `WALL_VERT` / `WALL_HORIZ` blocks that
   the fixture pipeline and `verify_crosscheck.py` depend on).
8. Which wall test suite survives. `ThinWallsTest` (7 cases, spread-block semantics, canonical hash
   coverage) against `EdgeWallsTest` (22 cases, DW1 semantics plus mission-source `WALL_VERT` /
   `WALL_HORIZ` parsing plus validation plus the wall-free anchor-unchanged gate). One suite should
   absorb the other's coverage, not both run against different rules.
9. Design authority. `FIRELINE-DESIGN.md` conflicts as content. `DESIGN-FIRELINE.md` (the locked
   visual spec) does not conflict because main never touched it.

## 5. Recommended order of work

1. Owner answers items 1, 2, 3, 5 and 9 first. They are product decisions, and items 4 and 8 follow
   from them mechanically.
2. Redo the merge on this branch with `--no-ff --no-commit`, resolve engine core first
   (`FirelineRules`, `FirelineWorld`, `WaterPacket`, `Rover`), then `Layout.kt`, then the session
   layer, then the app surfaces, then the tests.
3. Move main's engine sources into `commonMain` / `commonTest` in the same merge commit so no
   commit in the middle of the reconciliation has two engine trees.
4. Re-baseline: regenerate `parity_matrix.tsv`, `parity_traces.txt`, `parity_arith.tsv`,
   `limit_probe.tsv`, `snapshots.json`, `crosscheck.tsv` from the live engine, then
   `parity_fixture_gen.py`, then `docs/web/gen_web_mockups.py`, then the three verify scripts.
   Never hand-type a hash.
5. Close-out matrix (`:rf8-core`, `:fireline-engine` both targets, `:fireline-web`, `:app` free and
   pro, `:fireline-app` tests plus both assemble targets, `--rerun-tasks`), then the device
   regression on `emulator-5554` for the mission anchors that the store copy and the RESULT card
   depend on.
6. Keep main's release state intact through the merge: `versionCode`, signing config, Play assets
   and the starter firmware must survive the merge untouched, and any mission-count change has to
   reach `STORE-LISTINGS.md` and the store screenshots in the same step.
7. Only then merge into `main`. Until step 5 is green, main stays as the released 0.1 line.

## 6. Reproduce the map

```bash
git fetch origin main
git worktree add "C:/Users/Clemens/AppData/Local/hermes/cache/scratch/reconcile-wt" codex/reconcile-main-01
cd "C:/Users/Clemens/AppData/Local/hermes/cache/scratch/reconcile-wt"
git merge --no-commit --no-ff origin/main
git status --porcelain
git diff --name-only --diff-filter=U
git merge --abort
git worktree remove "C:/Users/Clemens/AppData/Local/hermes/cache/scratch/reconcile-wt"
```

## 7. Owner decision log (2026-11-26)

| item | status | decision |
|---|---|---|
| 1. fire vs an edge wall | decided | main's semantics: an edge wall blocks rover `MOVE`, blocks fire spread, and stops a water packet (spawn refusal `PACKET BLOCKED EDGE`, mid-flight consumption in `WaterPacket.process`). `PLAN-EDGE-WALLS.md` §9.6 decision DW1 is superseded |
| 2. packet range | decided | main's constants: `WATER_RANGE = 5` with `PACKET_LIFE = WATER_RANGE` |
| 3. catalog and free tier | decided | shape (b): three missions free, the rest unlocked by progress in the same app, no billing |
| 4. engine version | decided | bump to 4 after the merge and re-baseline every anchor and fixture with `PLAN-EDGE-WALLS.md` §10.7 |
| 5. canonical state | decided | adopt main's approach: both edge maps stay in `canonicalState`, which closes §13.7 item 6 of `PLAN-EDGE-WALLS.md` |

### What item 3 means concretely

main's shipped free set is mission ids 1, 2 and 4 (`NOZZLE CHECK`, `CORRIDOR`, `DOWNWIND`), asserted by
`MissionPickerModelTest.freeCatalogContainsOnlyMissionsOneTwoAndFourWithStableIds`, where ids 3 and 5
stay in the engine catalog but are not offered (`availableById(3)` and `availableById(5)` return null).
`STORE-LISTINGS.md` §6 describes exactly those three missions and states that FIRELINE has no ads,
subscriptions, in-app purchases or accounts, and `fireline-app` has no product flavors on either line
(source sets `main` and `test` only). So three missions is the whole product today: missions 3, 5 and 06
would be authored and unobtainable unless a paid path or a progress unlock is added. That follow-up
choice is open, and it decides whether the reconciliation revives 03 and 05, retires them, or gates them.

## 8. The resolved ruleset (2026-11-26, all five items decided)

The merged engine takes main's mechanics on every contested point.

| rule | resolved value | where it comes from |
|---|---|---|
| edge wall vs rover `MOVE` | blocked | both lines already agreed |
| edge wall vs fire spread | blocked (`if (layout.wallBetween(x, y, nx, ny)) continue` in `runFireSpread`) | main |
| edge wall vs water | blocked at spawn (`PACKET BLOCKED EDGE x,y -> nx,ny`) and consumed mid-flight (`WaterPacket.process` line 42) | main |
| edge wall vs `AHEAD` | reports 1 through an edge wall | both lines already agreed (main `FirelineWorld` line 479, this branch line 503) |
| edge wall vs heat | crosses, rover damage still requires adjacency | unchanged on both lines |
| packet range | `WATER_RANGE = 5`, `PACKET_LIFE = WATER_RANGE` | main |
| canonical state | both edge maps hashed, row-major | main |
| engine version | 4 | owner decision |
| free tier | three missions free, the rest unlocked by progress | owner decision |

Consequences, in the order they have to be handled:

1. DW1 is superseded. Measured 2026-11-26 (see §9): mission 06 `THIN WALL` has exactly one crossing ignition
   (`FIRE IGNITES 5,2 floor from 6,2 dir=W chance=37<64` at tick 15) and it still finishes under the blocked
   rule with the same reference firmware at the same tick 59 (HP 100, water 100, waterUsed 40), but the final
   hash moves from `61fe3d4c35449094` to `781ff2b441b18b0d` and the lesson becomes vacuous: the two drown shots
   aimed at (5,2) find no fire, the packets fly 4 steps to the boundary at (5,0) and expire. Mission 06
   therefore does not need rescuing to be winnable, it needs redesigning to teach something the blocked rule
   actually delivers. Deleting its three edge walls makes it FAIL (TIME_EXPIRED at 120, burning 2), so the walls
   already carry real load under either rule.
2. `PACKET_LIFE` 20 to 5 changes no authored solution. Measured 2026-11-26 (see §9): with `PACKET_LIFE = 5` the
   HEAD twin reproduces all six reference rows byte for byte, and with `PACKET_LIFE = 20` the main twin
   reproduces all five of main's reference rows byte for byte (the longest measured packet flight is 3 steps, in
   mission 01 on both lines). What does move is the probe surface: the `limit_probe.tsv` rows
   `WATER_RANGE_BUDGET` and `PACKET_LIFE_SEMANTICS` must be replaced from the merged engine, not adjusted by
   hand, and mission design loses the long shot, so every future target must sit within 5 cells of a firing
   position.
3. Canonical state now includes both edge maps, so every stored best-run fingerprint changes even for
   missions without walls. The store copy and the RESULT card wording do not change, only their numbers.
4. `EdgeWallsTest` must be rewritten to the resolved rules and absorb main's `ThinWallsTest` coverage.
   Running both suites against different rules is not an option.
5. Because main's mechanics win, the merge resolution is mechanical: take main's side for every contested
   rule block in `FirelineRules`, `FirelineWorld`, `WaterPacket`, `Rover` and `Layout`, take this branch's
   side for structure (`commonMain`, the shared session layer, `:fireline-web`, `docs/`, fixtures) and for
   the app surfaces only where they implement that shared session.
6. What this branch contributes after the merge is the KMP engine with a wasm target, the shared session
   layer, the browser console, the fixture and verification pipeline, the grid renderers, and one
   redesigned wall mission.

## 9. Measured rule impact (2026-11-26, headless kotlinc twins)

Twin fidelity: the HEAD twin reproduces all six reference rows of `docs/web/fixtures/parity_matrix.tsv` exactly,
probe tick 12 hashes, finish ticks 17, 102, 38, 147, 45, 59 and their final hashes. The variants below therefore
ran against an engine that matches the committed fixtures. Scratch tree
`C:/Users/Clemens/AppData/Local/hermes/cache/scratch/edge_walls_e5` with `build.sh`, `head/`, `main/`,
`variants/head_w5`, `variants/head_blocked`, `variants/main_life20`, `variants/main_open` and the run logs in
`out/`. No Gradle task ran in that session; the test-case counts quoted in this document are static grep counts.

### HEAD's six missions at `PACKET_LIFE = 5`

| mission | finish | thin edge walls | longest packet flight | at range 5 |
|---|---|---|---|---|
| 01 NOZZLE CHECK | tick 17 SUCCESS `245faa6d10316e00` | 0 | 3 | identical tick and hash |
| 02 CORRIDOR | tick 102 SUCCESS `d3ea47119fe3b101` | 0 | 1 | identical |
| 03 FIRST RESPONSE | tick 38 SUCCESS `01663660e6731d0a` | 0 | 0 packets fired | identical |
| 04 DOWNWIND | tick 147 SUCCESS `acf4656261a3c008` | 0 | 1 | identical |
| 05 HOT ZONE | tick 45 SUCCESS `ba8e443d3c469c1d` | 0 | 0 packets fired | identical |
| 06 THIN WALL | tick 59 SUCCESS `61fe3d4c35449094` | 3 | 1 | identical |

The range decision costs nothing in shipped content. It removes the long shot from the design vocabulary.

### main's five missions if spread crossed an edge instead (the DW1 rule applied to main)

| mission | thin edge walls | main as shipped | with crossing spread |
|---|---|---|---|
| 01 NOZZLE CHECK | 2 | tick 17 SUCCESS `cb5930328878ce13` | FAIL: cross ignition at tick 15 from (5,2) to (5,3), TIME_EXPIRED at 60, burning 2 |
| 02 CORRIDOR | 25 | tick 102 SUCCESS `9b29d6f318a738c8` | FAIL: cross ignitions at ticks 25 and 100 around the pocket, TIME_EXPIRED at 120, burning 5 |
| 03 FIRST RESPONSE | 11 | tick 38 SUCCESS `6ef0d5b85fd5b770` | identical, no fire adjacent to its walls |
| 04 DOWNWIND | 18 | tick 147 SUCCESS `238fe25103ef099b` | FAIL: 4 cross ignitions at ticks 10, 35, 95 and 110, CIVILIAN_LOST at 155, burning 47 |
| 05 HOT ZONE | 40 | tick 45 SUCCESS `ad1043b1d323218e` | survives at tick 45 but diverges: 10 cross ignitions, burning 14 to 33, hash `3df41251fe14609b` |

Three of main's five shipped missions only work because a thin wall stops fire, and one of them loses a civilian
if the wall stops blocking. That is the measured case for the owner decision.

### Mission content decision, still open

main's missions 01 to 05 carry 2, 25, 11, 18 and 40 thin edge walls. This branch's missions 01 to 05 carry none.
Under the resolved rules main's set is the coherent one, because it already teaches the wall mechanic under the
rules that now win. Recommendation: keep main's five mission definitions and add a redesigned 06. Keeping this
branch's wall-free 01 to 05 would leave the wall mechanic taught by a single mission and would discard the only
missions in the repo that prove the blocking rule matters.

### Corrections to earlier statements in this document and in chat

- Mission 06 finishes at tick 59 with `61fe3d4c35449094` (fixture row `6-reference`). It is not unfinished at 60.
- Mission 06 has no fuel cells at all; its lane rails are refill cells, and the crossing ignition lit a FLOOR
  cell at tick 15 (`FIRE IGNITES 5,2 floor from 6,2 dir=W chance=37<64`), not at tick 28.
- Both lines print `PACKET BLOCKED EDGE` at spawn refusal and `PACKET SPENT EDGE` mid-flight. Neither line has a
  `PACKET REFUSED EDGE` or `MOVE BLOCKED EDGE` event; a blocked `MOVE` is a silent no-op through
  `layout.stepBlocked` and still pays 2 ticks.
- Test-case counts in this document (22 for `EdgeWallsTest`, 7 for `ThinWallsTest`) are static `@Test` counts,
  not test-run results.

## 10. Decisions closed (2026-11-26) and the execution order

All owner decisions are now settled:

- Edge walls keep main's semantics (decision 1), `WATER_RANGE = 5` with `PACKET_LIFE = WATER_RANGE` (decision 2),
  three free missions with a progress unlock (decision 3), engine version 4, and `canonicalState` hashes the edge
  grids.
- Content: keep main's five mission definitions, because they already carry thin walls under the resolved rules,
  and add a redesigned mission 06 as the capstone rather than dropping it.
- Mission 06 brief: the objective sits behind a wall line the rover cannot shoot through, so the naive shot produces
  `PACKET SPENT EDGE` and wastes water and ticks; the only solution walks around the gate and fires from the far
  side; a bypass lane keeps it solvable; the refill sits outside the sealed lane so time applies the pressure; the
  teaching text names the protective side of the same wall, a cell behind the wall stays unlit.
- Catalog and unlock: mission ids 1, 2 and 3 are free, ids 4, 5 and 6 unlock on a success in the previous mission in
  catalog order.

Execution order. Each increment is committed and test green before the next one starts. The merge runs in the linked
worktree `C:/Users/Clemens/Documents/github/robofight-reconcile` on `codex/reconcile-main-01`; the main checkout
stays on `codex/edge-walls-e1`.

1. Port main's engine rules onto this branch's `commonMain` engine. The diff is small and exact: `WATER_RANGE = 5`
   plus `PACKET_LIFE = WATER_RANGE` (main's `FirelineRules.kt` lines 74 to 75); a `wallBetween` guard in
   `runFireSpread`'s neighbour loop and any other `wallBetween` guard main has that this branch lacks; the
   `canonicalState()` lines `verticalEdges=` and `horizontalEdges=` inserted after `terrain=` using
   `verticalEdgesRowMajor()` and `horizontalEdgesRowMajor()` (main's lines 549 to 550). `PACKET_FIRE_REDUCE = 25`
   keeps its name and value here. `WaterPacket` already blocks a packet mid-flight on this branch, so main's packet
   semantics need no port.
2. Rewrite `EdgeWallsTest` to the blocked semantics and absorb main's `ThinWallsTest` cases (its two `wallBetween`
   cases, its spawn refusal case, and its packet consumed at a wall case). Main's `src/main` engine test tree goes
   away after the port so only `commonTest` remains.
3. Port main's five mission definitions from `src/main/.../Missions.kt` into `commonMain/.../Missions.kt` with their
   thin wall blocks (2, 25, 11, 18 and 40 edges), keeping the slot after 05 for the redesigned capstone.
4. Author mission 06 under the brief above: layout, teaching text, reference firmware and a failing control
   firmware, with anchors measured on the headless twin and a `FailingReferenceCompletesWithPenalty` fixture row.
5. App side: take main's `MissionRunSurface`, `MissionSurface`, `FirelineGridView` and firmware store rules, then
   re-apply this branch's `MissionSession` extraction, the telemetry fix (telemetry only from
   `MissionSession.runOutcome()`, never a raw tick 0 read) and the E3 edge-segment rendering.
6. `MissionPickerModel`: ids 1 to 3 free, 4 to 6 unlocked by a success in the previous mission read from
   `RunStores.bestSummary`, with `MissionPickerModelTest` and `FirmwareStoreRulesTest` updated to match.
7. Re-baseline every anchor from the merged engine per `docs/DETERMINISM.md` §6: `parity_matrix.tsv`,
   `probe_fixture.tsv`, `limit_probe.tsv`, the mission mockups, the mission rows in `docs/FIRELINE-WEB.md`, then
   bump `FIRELINE_ENGINE_VERSION` to 4 in its own commit.
8. Close-out matrix on `:fireline-engine` jvm and wasm plus wasmJs, and `:fireline-app` unit and instrumentation,
   then the device regression on `emulator-5554` with edge segments visible on mission 06.
