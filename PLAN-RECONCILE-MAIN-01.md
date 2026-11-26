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
| `fireline-engine/src/commonMain/.../WaterPacket.kt` | main's `PACKET SPENT EDGE` absorption plus 5-cell range vs this branch's refusal at spawn plus 20-step life |
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
| 3. catalog and free tier | decided | three free missions |
| 4. engine version | decided | bump to 4 after the merge and re-baseline every anchor and fixture with `PLAN-EDGE-WALLS.md` §10.7 |
| 5. canonical state | decided | adopt main's approach: both edge maps stay in `canonicalState`, which closes §13.7 item 6 of `PLAN-EDGE-WALLS.md` |
| 1. fire vs an edge wall | open | owner asked for measured mission impact before deciding |
| 2. packet range | open | owner asked for measured mission impact before deciding |

### What item 3 means concretely

main's shipped free set is mission ids 1, 2 and 4 (`NOZZLE CHECK`, `CORRIDOR`, `DOWNWIND`), asserted by
`MissionPickerModelTest.freeCatalogContainsOnlyMissionsOneTwoAndFourWithStableIds`, where ids 3 and 5
stay in the engine catalog but are not offered (`availableById(3)` and `availableById(5)` return null).
`STORE-LISTINGS.md` §6 describes exactly those three missions and states that FIRELINE has no ads,
subscriptions, in-app purchases or accounts, and `fireline-app` has no product flavors on either line
(source sets `main` and `test` only). So three missions is the whole product today: missions 3, 5 and 06
would be authored and unobtainable unless a paid path or a progress unlock is added. That follow-up
choice is open, and it decides whether the reconciliation revives 03 and 05, retires them, or gates them.
