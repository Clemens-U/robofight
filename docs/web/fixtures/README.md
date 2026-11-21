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
| `snapshots.json` | deliverable: `{"snapshots": [...]}`, 8 snapshots, labels `01-ref-t0`, `01-ref-t12`, `01-ref-final`, `01-never-t30`, `01-never-final`, `02-ref-t12`, `02-ref-t46`, `02-ref-final` (~26.5 KB) |
| `snapshots.extra.json` | bonus: 2 snapshots - `01-ref-t13` (multi-tick SHOOT in flight, `instruction_ticks_remaining = 2`) and `01-ref-t15` (live water packet `{x:3,y:2,life:19,dir:E}`) |
| `crosscheck.tsv` | committed evidence: the last `CrossCheck.kt` output, checked by `verify_crosscheck.py` |
| `SnapshotFixtures.kt` | the generator (`main(args)`, package `fixtures`; `args[0]` = output path, default `snapshots.json` in the working dir) |
| `CrossCheck.kt` | independent re-derivation: mid-run probes + finals via `FirelineRun.runWithGuard` (headless authority) vs `runStepByStep`; prints TSV |
| `verify_snapshots.py` | JSON validity, 20x20 grid shape, glyph whitelist, required keys |
| `verify_crosscheck.py` | compares every snapshot field against `crosscheck.tsv` |

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
| `ParityMatrix.kt` | the generator (`package fixtures`, `main(args)`; `args[0]` = output dir). Compiles against `:rf8-core` + `:fireline-engine` commonMain. Records: fingerprint matrix + 12-tick golden traces (`FirelineRun.eventTrace`), 64/32-bit arithmetic vectors, `WebInputLimits` probe. Asserts runner == step-by-step per row, and aborts if a firmware fails to assemble. |
| `parity_matrix.tsv` | 10 rows (5 authored missions x reference + failing firmware): probe tick + mid-run fingerprint, trace hash, final tick / status / failure reason / fingerprint, assembler bytes+instructions. Header comments carry `# commit` and `# engine v`. |
| `parity_traces.txt` | the 10 golden traces verbatim (12 lines each, 120 lines), with per-scenario `probe=` / `trace_hash=` headers. |
| `parity_arith.tsv` | 29 arithmetic vectors: engine LCG streams, FNV-1a parameters and hashes, plus Int/Long overflow, shift, remainder, truncation behaviors every container must reproduce. |
| `limit_probe.tsv` | legal vs oversized source, and `WebInputLimits.prepare` outcomes (oversized and over-cap tick budget refused **before** assembly). |
| `parity_fixture_gen.py` | reads the TSVs, refuses to write if they contradict each other (trace hash must equal the matrix `trace_hash`, 12 trace lines each, 16-hex digests, all firmware assembling), then writes the JSON fixtures and the pinned Kotlin fixture file. |
| `parity_matrix.json`, `parity_arith.json`, `parity_traces.json` | the same data for a browser container to check itself against. |
| `../fireline-engine/src/commonTest/kotlin/robofight/fireline/W2ParityFixtures.kt` | the pinned Kotlin fixtures (`internal` vals), generated - never hand-typed. |

## Build + run the W2 pipeline (from the repo root, bash)

```bash
JBR="C:/Program Files/Android/Android Studio/jbr/bin/java.exe"
KJ="C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar"
SL="C:/Program Files/Android/Android Studio/plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar"
CLS="$LOCALAPPDATA/Temp/flw2/cls"

"$JBR" -cp "$KJ" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  rf8-core/src/commonMain/kotlin/robofight/*/*.kt \
  fireline-engine/src/commonMain/kotlin/robofight/fireline/*.kt \
  docs/web/fixtures/ParityMatrix.kt -d "$CLS" -nowarn     # grep the output for 'error:'

"$JBR" -cp "$CLS;$SL" fixtures.ParityMatrixKt \
  "C:/Users/Clemens/Documents/github/robofight/docs/web/fixtures"

python3 docs/web/fixtures/parity_fixture_gen.py          # JSON + W2ParityFixtures.kt

gradle :fireline-engine:jvmTest :fireline-engine:wasmJsTest   # consumes the pinned fixtures
```

Two `fixtures.ParityMatrixKt` runs must produce byte-identical TSVs (run it twice and
compare `md5sum`); that is the determinism proof for the recorded values. Engine changes
that move a fingerprint require a `FIRELINE_ENGINE_VERSION` bump plus regeneration, and
the record in `docs/WEB-W2-RECORD.md` documents why.

## Fingerprint family used by these fixtures

`FirelineWorld.finalStateHash()` = FNV-1a 64 over `canonicalState()`, with the engine's
own `FNV_OFFSET_BASIS` (0xCBF29CE484222325, stored as a signed Long) and `FNV_PRIME`
(0x100000001B3) from `FirelineRules`; rendered with `toULong().toString(16).padStart(16, '0')`.
The golden-trace hashes reuse exactly those constants and that rendering, so the trace
digest and the state digest come from one documented family (FIRELINE-DESIGN.md §13.2).
