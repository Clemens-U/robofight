# FIRELINE snapshot fixtures (docs harness, not app source)

Engine-generated snapshot fixtures for the FIRELINE web mockups. Every field is
read from live `FirelineWorld` state after exactly N `tickOnce()` calls (one call =
one world tick = the app's STEP). Fingerprint = `FirelineWorld.finalStateHash()`
(FNV-1a 64 over `canonicalState()`) - the same function the Step9/Step10
determinism tests assert. These `.kt` files are a docs harness: they live outside
any Gradle source set, so they never enter an app build. No Gradle, no system
Java: Android Studio JBR + kotlinc.

## Files
| file | role |
|---|---|
| `snapshots.json` | deliverable: `{"snapshots": [...]}`, 8 snapshots, labels `01-ref-t0`, `01-ref-t12`, `01-ref-final`, `01-never-t30`, `01-never-final`, `02-ref-t12`, `02-ref-t46`, `02-ref-final` (~26.5 KB). Missions 01 and 02 only; mission 06 has no snapshot yet (`PLAN-EDGE-WALLS.md` §13.7 item 4) |
| `snapshots.extra.json` | bonus: 2 snapshots - `01-ref-t13` (multi-tick SHOOT in flight, `instruction_ticks_remaining = 2`) and `01-ref-t15` (live water packet `{x:3,y:2,life:19,dir:E}`) |
| `crosscheck.tsv` | committed evidence: the last `CrossCheck.kt` output, checked by `verify_crosscheck.py` |
| `SnapshotFixtures.kt` | the generator (`main(args)`, package `fixtures`; `args[0]` = output path, default `snapshots.json` in the working dir) |
| `CrossCheck.kt` | independent re-derivation: mid-run probes + finals via `FirelineRun.runWithGuard` (headless authority) vs `runStepByStep`; prints TSV |
| `verify_snapshots.py` | JSON validity, 20x20 grid shape, glyph whitelist, required keys |
| `verify_crosscheck.py` | compares every snapshot field against `crosscheck.tsv` |
| `permalink_sources.tsv`, `permalink_fixtures.json`, `PermalinkSources.kt`, `permalink_fixture_gen.py`, `W3PermalinkFixtures.kt` | the W3 `FLW1-` permalink pipeline (see "W3 permalink fixtures" below) |

## Build + run (from the repo root, bash; compiled classes go to scratch)

```bash
cd <repo root>
JBR="C:/Program Files/Android/Android Studio/jbr/bin/java.exe"
KJ="C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar"
SL="C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar"
FIX="$PWD/docs/web/fixtures"
CLS="C:/Users/Clemens/AppData/Local/hermes/cache/scratch/flfixture/cls"   # scratch dir, never inside the repo

# Source trees are the Kotlin Multiplatform ones (`src/commonMain`) since the
# 2026-11-20 KMP move; an earlier revision of this recipe named `src/main`,
# which no longer exists. The `session` subpackage is its own compile input.
"$JBR" -cp "$KJ" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  rf8-core/src/commonMain/kotlin/robofight/*/*.kt \
  fireline-engine/src/commonMain/kotlin/robofight/fireline/*.kt \
  fireline-engine/src/commonMain/kotlin/robofight/fireline/session/*.kt \
  "$FIX/SnapshotFixtures.kt" "$FIX/CrossCheck.kt" \
  -d "$CLS" -nowarn            # ignore "malformed kotlin.Metadata" warnings; grep stderr for 'error:'

"$JBR" -cp "$CLS;$SL" fixtures.SnapshotFixturesKt "C:/Users/Clemens/Documents/github/robofight/docs/web/fixtures/snapshots.json"   # native C:/ path, writes snapshots.json + snapshots.extra.json
"$JBR" -cp "$CLS;$SL" fixtures.CrossCheckKt > "$FIX/crosscheck.tsv"
python "$FIX/verify_snapshots.py"
python "$FIX/verify_crosscheck.py"
python docs/web/gen_web_mockups.py     # mockups read the committed fixtures
python docs/web/verify_layout_v1.py    # frozen layout gates (docs/FIRELINE-WEB.md §15), 22 checks
```

Pass native `C:/...` forward-slash paths to `java`/`kotlinc`; MSYS `/c/...` paths
break into `\c\` and fail.

## Verified results (2026-11-19, engine v1; superseded 2026-11-21 by engine v2 below)

The three fingerprints and the five interim values in this section were measured under
engine v1. They are kept as measured because they are the record of that build; engine v2
moved all of them (`PLAN-EDGE-WALLS.md` section 11).

| scenario | final tick | outcome | fingerprint |
|---|---|---|---|
| 01 NOZZLE CHECK, reference firmware (102 B / 34 insn) | 17 | SUCCESS | `198645dc2e7e9649` |
| 01 NOZZLE CHECK, `never shoots` (6 B / 2 insn) | 60 = timeLimit | FAILURE / TIME_EXPIRED | `a64c17a9d85a6f75` |
| 02 CORRIDOR, reference firmware (75 B / 25 insn) | 102 | SUCCESS | `47e28d84c5d61220` |

Interim fingerprints: `01-ref-t0` `9333817203ef8d30`, `01-ref-t12` `fa31bb2eced7a30c`,
`01-never-t30` `0cacfa28f82b0dc7`, `02-ref-t12` `113c566635ef3e13`, `02-ref-t46` `e9226853d9b3eb4d`.
Two generator runs produced byte-identical JSON (`801b48308c3c3f003cafd417faca49b0`).

## Verified results (2026-11-21, engine v2, commit `ed93d89`)

Engine v2 is the edge-wall semantics step E1b (`PLAN-EDGE-WALLS.md` section 11):
`FIRELINE_ENGINE_VERSION` 2 (`FirelineRules.kt:21`), stamped into `canonicalState()`
(`FirelineWorld.kt:564`). All values below were re-recorded from live engine state at commit
`ed93d8926f9b0d6f9d193df458d104aa5518a7c8`; the `parity_matrix.tsv`, `parity_arith.tsv` and
`limit_probe.tsv` headers carry `# commit ed93d8926f9b0d6f9d193df458d104aa5518a7c8` and
`# engine v2`. All ten golden trace hashes, the 29 arithmetic vectors and the five limit probe
rows are unchanged from engine v1; only state fingerprints moved.

| scenario | final tick | outcome | fingerprint |
|---|---|---|---|
| 01 NOZZLE CHECK, reference firmware (102 B / 34 insn) | 17 | SUCCESS | `245faa6d10316e00` |
| 01 NOZZLE CHECK, `never shoots` (6 B / 2 insn) | 60 = timeLimit | FAILURE / TIME_EXPIRED | `7cbfe49993c17318` |
| 02 CORRIDOR, reference firmware (75 B / 25 insn) | 102 | SUCCESS | `d3ea47119fe3b101` |

Interim fingerprints: `01-ref-t0` `28838868ad3468ed`, `01-ref-t12` `f3dd62629ba58d1f`,
`01-never-t30` `b77a46e1e4308ad0`, `02-ref-t12` `d0f26fd5c0b02efa`, `02-ref-t46` `9b6e65741beac0ea`.
Two `fixtures.ParityMatrixKt` runs on 2026-11-21 produced byte-identical TSVs
(`parity_matrix.tsv` md5 `b05740462883cbc547ad650622db64ed`, `parity_traces.txt`
`449585cb4d1c49b549b15e2ca872bc2c`, `parity_arith.tsv` `76639b1c5a014db2f3712df798d5f7ae`,
`limit_probe.tsv` `a5b8ecda94db87f2649f4ddcd37bd227`).

### E2 wall fixture rows (2026-11-22, engine v2, commit `4bd81ca`)

The same generator now also records four wall-carrying layouts (`PLAN-EDGE-WALLS.md` §7 step
E2). They are fixture layouts, not authored missions: their canonical mission text is pinned
in `W2ParityFixtures.kt` because no source file ships it. Every value below came out of
`fixtures.ParityMatrixKt` at commit `4bd81cac5a5b529c325e0ff788229a1f6d0a04e5`, the `# commit`
tag of `parity_matrix.tsv`.

| layout (scenario) | fixture id | firmware label (image) | seed | probe tick | probe fingerprint | golden trace hash | final tick | status / reason | final fingerprint | wall edges | round trip |
|---|---|---|---|---|---|---|---|---|---|---|---|
| WALL LANE (wall-lane-move) | 101 | lane move loop, 6 B / 2 insn | 6001 | 12 | `4ce6f21b96b15e4f` | `88b5c429de62c944` | 60 | FAILURE / TIME_EXPIRED | `983902383703454a` | 3 | yes |
| SEALED NOZZLE (wall-nozzle-seal) | 102 | single shoot, 9 B / 3 insn | 6002 | 12 | `f72074b3a2cbae1e` | `b87628c5ec530355` | 60 | FAILURE / TIME_EXPIRED | `28621a2b7f6d5c29` | 1 | yes |
| WALL LANE SUPPRESS (wall-lane-suppress) | 103 | single shoot, 9 B / 3 insn | 6003 | 12 | `afb0ccd208010c01` | `e762879cfcc1a486` | 60 | FAILURE / TIME_EXPIRED | `c5c75948d2b9ec6f` | 3 | yes |
| OPEN LANE (wall-open-lane) | 104 | lane move loop, 6 B / 2 insn | 6001 | 12 | `c0769f7efd8bf8a6` | `88b5c429de62c944` | 44 | SUCCESS / - | `8204a0e863743898` | 2 | yes |

Mission rules behind every wall row, recorded as columns: objectives `REACH_EXIT` (4), time
limit 60, survive until unset, starting water 100, wind 0x0, tick guard 200, rover spawn
(2,2) facing east, exit (17,2). `wall-open-lane` is `wall-lane-move` with one edge wall
removed: the two pinned layout texts (1704 characters, 85 lines each) differ in exactly one
character, the `#` at `WALL_VERT` row 2 char 5 becoming `.`; same seed, same firmware, same
rules, and the outcome moves from FAILURE / TIME_EXPIRED at tick 60 to SUCCESS at tick 44.

Two `fixtures.ParityMatrixKt` runs on 2026-11-22 produced byte-identical outputs
(`parity_matrix.tsv` md5 `2f60459c1c9f7b23e734d105a321f437`, `parity_traces.txt`
`c50bcc35db606b51d7a59d35a68f71ce`, `parity_arith.tsv` `38b395c49928b71f114e4381684a4dca`,
`limit_probe.tsv` `e0cabb03628a00d057443f8d5d6d935c`), and `parity_fixture_gen.py` reported
`engine v2, commit 4bd81cac5a5b529c325e0ff788229a1f6d0a04e5, rows 14 (10 mission + 4 wall),
arith 29, limits 5, trace lines 168`. The ten authored mission fingerprints in the table above
are unchanged by the wall rows: the same generator at commit `4bd81ca` reproduces the
`ed93d89` values in every shared column.

### Mission 06 `THIN WALL` rows (2026-11-22, engine v2, `PLAN-EDGE-WALLS.md` §13)

The authored catalog is six missions. `Missions.kt:363` gained mission 06 `THIN WALL` (seed
6027), the first authored mission that carries thin edge walls, and the matrix grew from 14 rows
to 16. The same generator, run at commit `afec3755273df5d278d7349e2438979cd3d09bcb` with the
mission in the working tree, recorded:

| scenario | final tick | status / reason | final fingerprint | probe fingerprint | golden trace hash | firmware | wall edges | round trip |
|---|---|---|---|---|---|---|---|---|
| `6-reference` | 59 | SUCCESS / - | `61fe3d4c35449094` | `a85a699d9f1ef675` | `5f7fd7153968d3c2` | 108 B / 36 insn | 3 | yes |
| `6-failing` (spray through the thin wall) | 59 | FAILURE / ROVER_DESTROYED | `42bdbe754583cd57` | `a5e475326ab2e2af` | `aae04624e85d38cf` | 15 B / 5 insn | 3 | yes |

Mission rules behind both rows, recorded as columns: objectives `EXTINGUISH_ALL|REACH_EXIT` (5),
time limit 120, survive until unset, starting water 100, wind `W` x0, tick guard 300, rover spawn
(2,2) facing east, exit (17,2), zero wall cells and three edge walls: the gate on the edge right
of (5,2) plus the north and south edges of the spawn cell. The mission carries no `WALL_VERT` or
`WALL_HORIZ` text in the TSV because its sources are committed in `Missions.kt`, like every other
authored row; the engine's own `wall_count` column reads 3.

Isolation: the 14 rows recorded above are identical in all 27 columns, cell by cell, after these
two rows were appended (`PLAN-EDGE-WALLS.md` §13.4). Generator output after this step
(`rows 16 (12 mission + 4 wall), arith 29, limits 5, trace lines 192`): `parity_matrix.tsv` md5
`f7b950f13df715bc651b955647fbd8d0`, `parity_traces.txt` `c53090daef81b1a457fd8d21b20fbc1f`
(208 lines), `parity_arith.tsv` `4344825cf9b453dfbfb3ebc8778463a1`, `limit_probe.tsv`
`802dda7c0e6303a42da49326f0a50335`; `parity_fixture_gen.py` re-run on the committed tree leaves
`parity_matrix.json`, `parity_traces.json`, `parity_arith.json` and `W2ParityFixtures.kt`
(`54d936cc384de4e3259b14a84a759eef`) byte-identical.

`snapshots.json` still carries 8 snapshots for missions 01 and 02 and mission 06 has none. A
snapshot of a thin-wall mission would be wall-blind today: `TextGrid` renders cells, a thin wall
is not a cell, and the snapshot schema carries no `WALL_VERT`/`WALL_HORIZ` rows yet. That work is
owned by `PLAN-EDGE-WALLS.md` §7 step E4 (§13.7 item 4), together with the console edge overlay.

## W3 permalink fixtures (2026-11-22, engine v2, `docs/WEB-W3-RECORD.md` §10)

`docs/FIRELINE-WEB.md` §8 defines the `FLW1-` share link: payload
`version(1) mission_id(4 BE) seed_override(4 BE) name_len(2 BE) name firmware_source`, gzip
(stored deflate, `MTIME 0`, `XFL 0`, `OS 3`, CRC32 and length little-endian), encoded as
`FLW1-` + base64url without padding. Same pipeline shape as the W2 parity fixtures, from generators
only, never hand-typed:

```bash
cd <repo root>
CLS="C:/Users/Clemens/AppData/Local/hermes/cache/scratch/flfixture/cls"   # compiled by the recipe above, which includes PermalinkSources.kt
"$JBR" -cp "$CLS;$SL" fixtures.PermalinkSourcesKt > docs/web/fixtures/permalink_sources.tsv
printf '# commit %s\n' "$(git rev-parse HEAD)" | cat - docs/web/fixtures/permalink_sources.tsv > "$TMPDIR/pl_tsv" && mv "$TMPDIR/pl_tsv" docs/web/fixtures/permalink_sources.tsv
python docs/web/fixtures/permalink_fixture_gen.py --check
```

The TSV records the six authored missions' reference firmware plus failing variants (13 data rows)
straight from the live engine catalog, and its first line must carry `# commit <40 hex>` provenance -
`permalink_fixture_gen.py` refuses without it. The generator builds every link with its own
gzip/stored-deflate code, decompresses each one with the independent Python `gzip` module, and writes
`permalink_fixtures.json` plus the Kotlin pin `W3PermalinkFixtures.kt`. The cross-proof runs the other
way in `:fireline-web:wasmJsTest`: `Permalink.encode` must produce exactly these links and
`Permalink.decode` must return these payloads byte for byte.

`--check` on the committed tree at commit `9f16765d666189196aee07fb0021ec8fccfbf699` printed
`PERMALINK FIXTURES REPRODUCIBLE: 13 scenarios, gzip cross-verified`, with
`permalink_fixtures.json` and `W3PermalinkFixtures.kt` left byte-identical (13 scenarios:
`1-reference`, `1-failing-never-shoots`, `2-reference`, `2-failing-spray-everything`, `3-reference`,
`3-failing-turn-only`, `4-reference`, `4-failing-run-south`, `5-reference`, `5-failing-no-shoot`,
`6-reference`, `6-failing-spray-through-thin-wall`, `2-reference-custom-seed`).

## Re-baseline (2026-11-27, engine v4, commit `0e2f957`)

Engine v4 is the main-layout reconciliation (`PLAN-RECONCILE-MAIN-01.md` §10 step 7), carried out
with the `PLAN-EDGE-WALLS.md` §10.7 re-baseline recipe: `FIRELINE_ENGINE_VERSION` 4
(`FirelineRules.kt:21`), stamped into `canonicalState()` (`FirelineWorld.kt:568`) and into every
stored best-run key. `canonicalState()` writes the version, so every state fingerprint moved —
wall-free missions included. Every value below was re-recorded from live engine state at commit
`0e2f95764f5c00ed7fa236e5569e08593726f7e6`; the `parity_matrix.tsv`, `parity_arith.tsv`,
`limit_probe.tsv` and `crosscheck.tsv` headers carry that commit and `# engine v4`.

`parity_arith.tsv` (29 vectors) and `limit_probe.tsv` (5 rows) are unchanged row for row from the
engine v2 record: `diff` against the committed files shows only the two header comment lines
(`# commit`, `# engine v`) moved, because probe arithmetic and the input limits do not feed a state
fingerprint. `crosscheck.tsv` was rewritten in full.

The twelve authored rows after the merge (main's layouts, mission 06 redesigned as `GO AROUND`):

| scenario | final tick | status / reason | final fingerprint | probe hash @12 | golden trace hash | firmware | wall edges | round trip |
|---|---|---|---|---|---|---|---|---|
| `1-reference` NOZZLE CHECK | 17 | SUCCESS / - | `aa3d630d5d30d9ce` | `38ee6d4751d1b6ad` | `4f4118286b349619` | 102 B / 34 insn | 2 | yes |
| `1-failing` never shoots | 60 = timeLimit | FAILURE / TIME_EXPIRED | `82a952554d427d7a` | `d80d9d76ffa7ec06` | `9f496c19b6af9b46` | 6 B / 2 insn | 2 | yes |
| `2-reference` CORRIDOR | 102 | SUCCESS / - | `5cd373d7f4a15c81` | `698f7ba3f6e52462` | `ceddf1a2fa7d7b10` | 75 B / 25 insn | 25 | yes |
| `2-failing` | 120 = timeLimit | FAILURE / TIME_EXPIRED | `139a2344d3843462` | `425b78a9b0225e26` | `88b5c429de62c944` | 6 B / 2 insn | 25 | yes |
| `3-reference` FIRST RESPONSE | 38 | SUCCESS / - | `a62d2c5976fa1887` | `2ba4e4c4a67f6609` | `28016f5459a44c4d` | 72 B / 24 insn | 11 | yes |
| `3-failing` | 120 = timeLimit | FAILURE / TIME_EXPIRED | `74b189adc1525cf1` | `28850fd4c6af65c3` | `d31f60893006df8f` | 45 B / 15 insn | 11 | yes |
| `4-reference` DOWNWIND | 147 | SUCCESS / - | `40990811d10e1466` | `571843835add4635` | `9bafb261b891c1c0` | 105 B / 35 insn | 18 | yes |
| `4-failing` | 500 = guard | FAILURE / TIME_EXPIRED | `69525e5a96cbda83` | `4100f998e7bba5e8` | `99752e4c059a058e` | 9 B / 3 insn | 18 | yes |
| `5-reference` HOT ZONE | 45 | SUCCESS / - | `e6b157b12efba137` | `c2390c0dd6737da1` | `317eca23f0149509` | 111 B / 37 insn | 40 | yes |
| `5-failing` | 29 | FAILURE / ROVER_DESTROYED | `0c4aa7a4161dbb34` | `6478656bb70ec65f` | `def56526d10c1cf2` | 6 B / 2 insn | 40 | yes |
| `6-reference` GO AROUND | 49 | SUCCESS / - | `3a958e3503fe0741` | `108606110907a1bd` | `46f9fc7672a993d9` | 87 B / 29 insn | 13 | yes |
| `6-failing` spray at the wall | 90 = timeLimit | FAILURE / TIME_EXPIRED | `55d1dabb5ff672d3` | `230a69b24265e722` | `4720fbe644a1f4ca` | 27 B / 9 insn | 13 | yes |

The four `PLAN-EDGE-WALLS.md` §7 step E2 wall scenarios keep their layout text in the TSV:
`wall-lane-move` 60 FAILURE `b7229fe2bf1af671` (3 edges), `wall-nozzle-seal` 60 FAILURE
`e240c51bb7459cbc` (1), `wall-lane-suppress` 60 FAILURE `692bdf48709d5c2a` (3), `wall-open-lane`
44 SUCCESS `43d7b2ebb1e937c6` (2). The thin-edge catalog sums to 109 authored segments over the six
missions (2 + 25 + 11 + 18 + 40 + 13, from the `wall_count` column — the engine's own
`Layout.edgeCount` per mission; the twelve authored matrix rows double-count it as 227).

`snapshots.json` now carries 13 snapshots: the eight for missions 01 and 02 plus five for mission 06
— `06-ref-t0` `a0a873689ad09e72`, `06-ref-t12` `108606110907a1bd`, `06-ref-t24` `83fdc912760a579f`,
`06-ref-final` tick 49 `3a958e3503fe0741` (SUCCESS), `06-fail-final` tick 90 `55d1dabb5ff672d3`
(FAILURE / TIME_EXPIRED) — closing `PLAN-EDGE-WALLS.md` §7 step E4 (§13.7 item 4). The schema grew
the two wall rows the old snapshot was blind to: `wallVertRowMajor` and `wallHorizRowMajor`
(201 entries each, row-major like `WorldGrid.edgeWalls`), plus `terrainWallCount` and
`terrainWallCoords`; mission 06 measures 13 vertical edges at column 9 for rows 0..12 and 2 terrain
wall cells. `verify_snapshots.py` reports `ALL CHECKS PASSED` over 13 snapshots and
`verify_crosscheck.py` reports `CROSSCHECK PASSED` (mission 06 rows cross-checked 39 wall rows
each). Regenerated outputs at this step (md5): `parity_matrix.tsv` `c88d9d914a325f7dea184d438dd00e41`
(16 rows), `parity_traces.txt` `ddc696cce973242f363abfdcc6b9865d` (208 lines), `parity_arith.tsv`
`00c49473db6b3cabf6be988579d90509`, `limit_probe.tsv` `97415d93dcc8ffec9e13244cdcdf7930`,
`crosscheck.tsv` `73c79e5eebc773537c916974eb72a92f`, `snapshots.json` `ef00de58c04bd2d52ef71856e37ba37b`.

## Field notes / legends

- `terrain`: 20x20 authoring glyphs - `.` floor, `#` wall, `F` fuel, `W` refill, `E` exit,
  `P` protected asset, `C` civilian cell, `R` rover spawn cell (spawn/civilian are
  authoring markers over floor, as in `Layout.decode`).
- `fire`: 20x20 using the engine's own live-intensity glyph mapping
  (`TextGrid.fireGlyph`): `.` = 0, `1` = 1..24, `2` = 25..49, `3` = 50..74, `4` = 75..100.
  Exact intensities per burning cell are in `fire_cells` (`{x,y,intensity}`) and the
  total in `fire_intensity_total`. Note this is the display band mapping, not the
  authoring level mapping (`1`=25...`4`=100) used in the layout source rows.
- `vm.pc` / `pc_hex` / `pc_insn`: the RF-8 program counter (program base `0x100`) and the
  instruction index the execution monitor highlights (`Vm.monitorInsn` - the in-progress
  op during a multi-tick instruction, otherwise the next instruction at PC).
- `vm.instruction` is the disassembled text at that index (`robofight.disasm.Disasm`);
  `vm.last_op` is the engine's own verbatim rendering of the tick just executed.
- `vm.pc_line_number` is 1-based, from the assembler's own source map (`AsmResult.lineOf`).
- `vm.pc_label` is derived from the firmware source using the assembler's pass-1 label
  rule (the assembler exposes line numbers but no label map). `walk+3` means "3
  instructions after label `walk`", `(entry)` means "before the first label".
- `vm.instruction_ticks_remaining` is non-null only when a multi-tick op is mid-flight
  (`Vm.inProgress`); the VM exposes its countdown only through `lastOp` `"SHOOT (2/3)"`,
  so remaining = total - elapsed. In the 8 required snapshots no multi-tick op happens to
  be in flight at those exact ticks; `01-ref-t15` in `snapshots.extra.json` does.
- `ports`: values from `FirelineWorld.sensor(n)`. Port 10 `RAND` is deliberately NOT read -
  reading it draws from the sensor RNG stream and would change the fingerprint.
- `events` is `world.lastEvents` verbatim for that tick (empty when the tick emitted
  nothing); `recent_events` carries the last 6 ticks' events with their tick numbers.
- Live water packets in mission 01 exist only on ticks 5, 6, 10, 11, 15, 16; in mission 02
  the pocket cell sits directly ahead of the firing position, so every packet is consumed
  on its spawn tick (`packets` is legitimately empty for all mission-02 snapshots).

---

# W2 parity fixtures (cross-target gates G2/G3/G4/G6)

Separate from the mockup snapshots above: these are the fixtures `:fireline-engine`
`commonTest` asserts **on both `jvm` and `wasmJs`**, so a browser container inherits the
engine's determinism instead of re-deriving it (docs/FIRELINE-WEB.md §4, §9 W2).

| file | role |
|---|---|
| `ParityMatrix.kt` | the generator (`package fixtures`, `main(args)`; `args[0]` = output dir). Compiles against `:rf8-core` + `:fireline-engine` commonMain. Records: fingerprint matrix + 12-tick golden traces (`FirelineRun.eventTrace`), 64/32-bit arithmetic vectors, `WebInputLimits` probe. Asserts runner == step-by-step per row, and aborts if a firmware fails to assemble. Since E2 it also carries the four wall-carrying layouts as canonical mission text (`FIRELINE LAYOUT 20` header, `TERRAIN` 20x20, `FIRE` 20x20, `WALL_VERT` 20 rows x 19 glyphs, `WALL_HORIZ` 19 rows x 20 glyphs, `ROVER x,y,dir`), builds them through `Layout.fromCanonicalText` + `Mission.requireValid`, checks `fromCanonicalText(toCanonicalText(layout))` byte identity per row, and refuses to emit a row whose authored text is not the canonical text the engine emits. |
| `parity_matrix.tsv` | 16 rows: 12 authored mission rows (6 missions x reference + failing firmware) plus the four E2 wall fixture rows of `PLAN-EDGE-WALLS.md` §7 step E2. Columns: probe tick + mid-run fingerprint, trace hash, final tick / status / failure reason / fingerprint, assembler bytes+instructions, the mission rules the fingerprint depends on (seed, objectives, time limit, survive until, starting water, wind dir x force, tick guard), `wall_count` (the engine's own `Layout.edgeCount`), the recorded layout and firmware text for the wall rows (`-` for authored rows, whose sources are committed in `Missions.kt`), and the measured canonical text `roundtrip`. Header comments carry `# commit` and `# engine v`. |
| `parity_traces.txt` | the 16 golden traces verbatim (12 lines each, 192 trace lines, 208 lines including the per-scenario `probe=` / `trace_hash=` headers). |
| `parity_arith.tsv` | 29 arithmetic vectors: engine LCG streams, FNV-1a parameters and hashes, plus Int/Long overflow, shift, remainder, truncation behaviors every container must reproduce. |
| `limit_probe.tsv` | legal vs oversized source, and `WebInputLimits.prepare` outcomes (oversized and over-cap tick budget refused **before** assembly). |
| `parity_fixture_gen.py` | reads the TSVs, refuses to write if they contradict each other (16 rows in the expected 12 mission + 4 wall split, trace hash must equal the matrix `trace_hash`, 12 trace lines each, 16-hex digests, all firmware assembling, every `roundtrip` true, authored rows carry no wall state except the two mission 06 rows which must carry `wall_count` at least 1, wall rows carry layout and firmware text whose `#` glyph count equals the engine `wall_count` and whose section shapes are `TERRAIN` 20x20, `FIRE` 20x20, `WALL_VERT` 20x19, `WALL_HORIZ` 19x20), then writes the JSON fixtures and the pinned Kotlin fixture file. |
| `parity_matrix.json`, `parity_arith.json`, `parity_traces.json` | the same data for a browser container to check itself against. Each wall row also carries decoded `layoutText` and `firmwareText` next to the escaped TSV columns. |
| `../fireline-engine/src/commonTest/kotlin/robofight/fireline/W2ParityFixtures.kt` | the pinned Kotlin fixtures (`internal` vals), generated - never hand-typed: `PARITY_FIXTURE_ROWS` (16 rows incl. the rule columns), `PARITY_WALL_LAYOUT_TEXTS`, `PARITY_WALL_FIRMWARE_TEXTS`, `PARITY_GOLDEN_TRACES`, `PARITY_ARITH_VECTORS`, `PARITY_LIMIT_PROBE`, plus the row and scenario counts. |
| `../fireline-engine/src/commonTest/kotlin/robofight/fireline/W2FixtureMissions.kt` | hand-written helper (not generated): rebuilds the mission each row describes, from `Missions.byId` for authored rows and from the pinned canonical layout text plus the recorded rule columns for wall rows, so one fixture set drives both targets. |
| `../fireline-engine/src/commonTest/kotlin/robofight/fireline/W2WallParityTest.kt` | the E2 wall gate (5 tests): wall rows are pinned and their glyph count equals `Layout.edgeCount`, every wall row reproduces its recorded probe and final fingerprint when recomputed from the pinned text, the canonical text round trip keeps the fingerprint byte-identically, the removed wall is the only difference between `wall-lane-move` and `wall-open-lane`, and the wall gates appear in the pinned golden traces. |

## Build + run the W2 pipeline (from the repo root, bash)

```bash
JBR="C:/Program Files/Android/Android Studio/jbr/bin/java.exe"
KJ="C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar"
SL="C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar"
CLS="C:/Users/Clemens/AppData/Local/hermes/cache/scratch/flw2/cls"   # scratch dir, never inside the repo

"$JBR" -Xmx2g -cp "$KJ" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -d "$CLS" -cp "$SL" \
  "C:/Users/Clemens/Documents/github/robofight/rf8-core/src/commonMain/kotlin" \
  "C:/Users/Clemens/Documents/github/robofight/fireline-engine/src/commonMain/kotlin" \
  "C:/Users/Clemens/Documents/github/robofight/docs/web/fixtures/ParityMatrix.kt"   # grep the log for 'error:'

"$JBR" -cp "$CLS;$SL" fixtures.ParityMatrixKt \
  "C:/Users/Clemens/Documents/github/robofight/docs/web/fixtures"

python3 docs/web/fixtures/parity_fixture_gen.py          # JSON + W2ParityFixtures.kt

gradle :fireline-engine:jvmTest :fireline-engine:wasmJsTest   # consumes the pinned fixtures
```

Whole source trees may be passed as directory arguments instead of the `*/*.kt` globs (the
form above is what the E2 runs used); the classpath separator on this shell is `;`.

Two `fixtures.ParityMatrixKt` runs must produce byte-identical TSVs (run it twice and
compare `md5sum`); that is the determinism proof for the recorded values. Engine changes
that move a fingerprint require a `FIRELINE_ENGINE_VERSION` bump plus regeneration, and
the record in `docs/WEB-W2-RECORD.md` documents why. `ParityMatrix.kt` stamps `# commit`
with the live `git rev-parse HEAD` and `# engine v` with `FIRELINE_ENGINE_VERSION`, so a
regeneration always names the state it measured.

## Fingerprint family used by these fixtures

`FirelineWorld.finalStateHash()` = FNV-1a 64 over `canonicalState()`, with the engine's
own `FNV_OFFSET_BASIS` (0xCBF29CE484222325, stored as a signed Long) and `FNV_PRIME`
(0x100000001B3) from `FirelineRules`; rendered with `toULong().toString(16).padStart(16, '0')`.
The golden-trace hashes reuse exactly those constants and that rendering, so the trace
digest and the state digest come from one documented family (FIRELINE-DESIGN.md §13.2).
