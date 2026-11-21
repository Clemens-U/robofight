# WEB-W2 record: determinism parity fixtures and the arithmetic audit

Branch: `codex/web-w2-parity` (from `codex/web-w1-kmp-wasm` HEAD `d66ef5f8`, which is
`main` + the W1 KMP/wasm toolchain plus `DESIGN-FIRELINE.md`).
Date: 2026-11-20. Plan: `docs/FIRELINE-WEB.md` §9 W2, gates §4 G1, G2, G3, G6.

**Verdict: gate met.** The fingerprint matrix, the golden event traces, the 64/32-bit
arithmetic vectors and the web input-limit refusals are recorded from live engine state,
pinned as fixtures, and asserted identically on `jvm` and `wasmJs`: 19 new tests, 146
engine tests on each target, zero failures across the close-out matrix.

Record status 2026-11-21: every fingerprint in this record was measured under engine v1.
`FIRELINE_ENGINE_VERSION` is 2 (`FirelineRules.kt:21`) since the edge-wall step E1b
(`PLAN-EDGE-WALLS.md` section 11), which moves every state fingerprint while leaving the ten
golden trace hashes, the 29 arithmetic vectors and the limit probe rows unchanged. The values
measured here are kept as measured; the current ones are in `docs/web/fixtures/parity_matrix.tsv`
and `docs/web/fixtures/snapshots.json`.

---

## 1. What W2 had to prove

From `docs/FIRELINE-WEB.md` §4:

| gate | requirement | status |
|---|---|---|
| G1 | one engine build, one source of truth: the web container runs the same `:rf8-core` + `:fireline-engine` sources the Android app runs | met (KMP `commonMain`, W1; no fork, no reimplementation) |
| G2 | golden event traces (FIRELINE-DESIGN.md §13.2 item 4) pinned and reproduced | met, 10 scenarios x 12 ticks |
| G3 | fingerprint matrix: every authored mission x reference and failing firmware reproduces tick, status, failure reason and fingerprint | met, 10 rows |
| G6 | source-size, line-count and tick-budget ceilings are enforced *before* assembling or allocating | met, `WebInputLimits` + 9 tests |
| §10 risk row | "`Long` / integer arithmetic drift on wasmJs or js" | audited statically and pinned as 29 vectors asserted on both targets |

## 2. What was built

| path | role |
|---|---|
| `fireline-engine/src/commonMain/kotlin/robofight/fireline/WebInputLimits.kt` | new engine input gate: `sourceProblems`, `tickProblems`, `prepare(mission, source, requestedMaxTicks)` returning `RunRequest.Accepted` (assembled descriptor + effective tick budget) or `RunRequest.Rejected(problems)`. Ceilings: 1200 source lines, 8192 UTF-8 source bytes, tick budget capped at `MAX_TICKS` (1 000 000) and clamped to the mission guard. |
| `docs/web/fixtures/ParityMatrix.kt` | the generator: walks `Missions.ALL` x (reference + every `failingSources` variant), asserts runner == step-by-step per row, records probe fingerprint at tick 12, the 12-tick `FirelineRun.eventTrace`, the full-run outcome, the assembler image size, 29 arithmetic vectors and 5 input-limit probes. Writes the TSV fixtures with `# commit` / `# engine v` provenance headers. |
| `docs/web/fixtures/parity_fixture_gen.py` | the pinning step: refuses to write when the recorded TSVs contradict each other (trace hash must equal the matrix `trace_hash`, exactly 12 trace lines per scenario, 16-hex digests, every firmware assembling, a real commit sha), then writes the JSON fixtures and the pinned Kotlin fixture file. |
| `docs/web/fixtures/parity_matrix.tsv`, `parity_traces.txt`, `parity_arith.tsv`, `limit_probe.tsv` | raw recorded data (13 + 130 + 32 + 8 lines). |
| `docs/web/fixtures/parity_matrix.json`, `parity_traces.json`, `parity_arith.json` | the same data for a browser container to check itself against (W3). |
| `fireline-engine/src/commonTest/kotlin/robofight/fireline/W2ParityFixtures.kt` | generated Kotlin fixtures (`internal` vals): `PARITY_FIXTURE_ROWS`, `PARITY_GOLDEN_TRACES`, `PARITY_ARITH_VECTORS`, `PARITY_LIMIT_PROBE`, plus `PARITY_SOURCE_COMMIT` / `PARITY_ENGINE_VERSION` provenance. |
| `W2ParityMatrixTest.kt` (5 tests), `W2GoldenTraceTest.kt` (3), `W2ArithmeticParityTest.kt` (2), `W2WebInputLimitsTest.kt` (9) | the suites that re-derive everything at run time on every target: 19 tests. |
| `fireline-app/.../files/FirmwareSource.kt` | the Android store's ceilings now reference `WebInputLimits.MAX_SOURCE_LINES` / `MAX_SOURCE_BYTES`, so Android and web refuse the same documents (one limit set, two front doors). |
| `fireline-app/.../files/FirmwareStoreRulesTest.kt` | new test `documentCeilingsAreTheEngineWebInputCeilings` proving that identity, plus a cross-check that the engine gate accepts what the store accepts. |

## 3. Recorded fingerprint matrix (G3)

Commit `d66ef5f8214506120857fd15aaa80c1042e9d4cc`, engine v1, probe tick 12 for every
scenario. `probe` is `FirelineWorld.finalStateHash()` after exactly 12 `tickOnce()` calls
(the app's STEP); `trace` is the FNV-1a 64 digest of the 12 golden trace lines; `final` is
the full guarded run (`FirelineRun.runWithGuard`).

| scenario | firmware | probe | trace | final tick / status / reason | final fingerprint | program |
|---|---|---|---|---|---|---|
| 1 NOZZLE CHECK | reference | `fa31bb2eced7a30c` | `4f4118286b349619` | 17 SUCCESS | `198645dc2e7e9649` | 102 B / 34 insn |
| 1 NOZZLE CHECK | never shoots | `85461a84b158812d` | `9f496c19b6af9b46` | 60 FAILURE TIME_EXPIRED | `a64c17a9d85a6f75` | 6 B / 2 insn |
| 2 CORRIDOR | reference | `113c566635ef3e13` | `ceddf1a2fa7d7b10` | 102 SUCCESS | `47e28d84c5d61220` | 75 B / 25 insn |
| 2 CORRIDOR | naive always forward | `43054c601ddd8737` | `88b5c429de62c944` | 120 FAILURE TIME_EXPIRED | `095dc5eba29eef27` | 6 B / 2 insn |
| 3 FIRST RESPONSE | reference | `df4571c82f169e1b` | `28016f5459a44c4d` | 38 SUCCESS | `0cf3b083da3900fd` | 72 B / 24 insn |
| 3 FIRST RESPONSE | skips the second civilian | `3153a509654849f5` | `d31f60893006df8f` | 120 FAILURE TIME_EXPIRED | `818eda729f5866d7` | 45 B / 15 insn |
| 4 DOWNWIND | reference | `7f2f344b8d0a52c4` | `9bafb261b891c1c0` | 147 SUCCESS | `dd669e7313a67d41` | 105 B / 35 insn |
| 4 DOWNWIND | ignores the burning line | `da58ac6a8b1a7e81` | `99752e4c059a058e` | 500 FAILURE TIME_EXPIRED | `b2c14340a8f8a1de` | 9 B / 3 insn |
| 5 HOT ZONE | reference | `672a982213390d0c` | `317eca23f0149509` | 45 SUCCESS | `a7836db790e4a6a0` | 111 B / 37 insn |
| 5 HOT ZONE | no shield, straight through | `47145b5826115f48` | `def56526d10c1cf2` | 29 FAILURE ROVER_DESTROYED | `b7b8b80ea652ce1d` | 6 B / 2 insn |

Anchor continuity with the shipped app: mission 01 `never shoots` reproduces
`a64c17a9d85a6f75` and mission 02 reference reproduces `47e28d84c5d61220`, both of which
are the fingerprints the Android emulator regression (Milestones 7-9) and the W1 wasm run
produced. A web container that reproduces this table reproduces the Android app.

## 4. Golden event traces (G2)

`FirelineRun.eventTrace(descriptor, 12)` per scenario, 10 x 12 = 120 lines, recorded in
`parity_traces.txt` and pinned verbatim in `PARITY_GOLDEN_TRACES`. House format per line:
tick padded to 3, the engine's own `lastOp` rendering, ` | `, the tick's `lastEvents`.
Example (`1-reference`):

```
001 IN port18 | 
002 JNZ → 265 | 
003 SHOOT (1/3) | 
004 SHOOT (2/3) | 
005 SHOOT (3/3) | PACKET -> 3,2
006 IN port18 | 
007 JNZ → 277 | FIRE -25 at 5,2 -> 30
```

`W2GoldenTraceTest` asserts the pinned lines, the pinned trace digests, the live trace
digests and the house format (tick prefix width, separator) on every target.

## 5. Arithmetic audit (the §10 risk row)

Two halves: a static scan of what the engine actually uses, and pinned vectors that any
target must reproduce.

Static scan of the 19 `commonMain` files of `:rf8-core` + `:fireline-engine` (code lines
only, comments excluded; reproducible with
`grep -rnE '<pattern>' rf8-core/src/commonMain fireline-engine/src/commonMain --include='*.kt'`):

| construct | count | meaning for wasm parity |
|---|---|---|
| `Float`, `Double` | 0 | no floating point anywhere in rules, RNG or fingerprint |
| `Math.*`, `sqrt`, `sin`, `cos`, `hypot` | 0 | no transcendental drift possible |
| `kotlin.random` / `Random` | 0 | determinism never depends on a platform RNG |
| `floorDiv`, `rem` | 0 | division/remainder semantics reduce to `/` and `%` only |
| `ushr` | 0 | no logical-shift sign ambiguity in the engine |
| `Long` constants and types | 4 sites | `LcgStream.MULTIPLIER` / `INCREMENT` (`FirelineRng.kt:33,34`) and the two FNV parameters (`FirelineRules.kt:86,87`) |
| `toLong()` | 2 sites | the LCG step (`FirelineRng.kt:45`) and the FNV loop (`FirelineWorld.kt:569`) |
| `toULong()` | 1 site | the fingerprint hex rendering (`FirelineWorld.kt:570`) |
| `toInt()` | 21 sites | i64 -> i32 narrowing after masking (LCG, ISA field packing) |
| `toUInt()` | 1 site | `TextFormat.hexPad` rendering only |
| `shl` / `shr` | 12 / 7 sites | ISA encoding and bit unpacking, all on `Int` |
| `and` / `or` / `xor` | 118 sites | masking and FNV |
| `%` | 10 sites | one sign-sensitive spot, normalized explicitly: `Rover.kt:88` `((facing + delta) % 4 + 4) % 4`; the rest are non-negative by construction |
| `/` | 23 sites | `Int` division, truncating toward zero |

So all 64-bit arithmetic in the engine is exactly two loops: the LCG step (mask to 31 bits,
`Long` multiply, mask again, `toInt()`) and the FNV-1a loop (`Long` xor and multiply wrap).
Both are documented as intentional two's-complement wrap behavior (`FirelineRng.kt:19`
states the 31-bit mask equals wrapping to 32 bits first), and both are pinned:

| vector group | examples | pinned value |
|---|---|---|
| engine LCG streams | `rng_sensor_1001` (12 bytes) | `246,76,90,12,45,147,235,196,15,182,175,95` |
| | `rng_spread_2002`, `rng_layout_3003` | recorded in `parity_arith.tsv` |
| LCG wrap/edge behavior | `rng_next_of_wrap` (`nextOf(0x7FFFFFFF)`), `rng_byte_of_min` (`byteOf(-1)`) | `1043980748`, `255` |
| FNV parameters | `fnv_offset_basis`, `fnv_prime` | `cbf29ce484222325`, `00000100000001b3` |
| FNV hashes | `fnv_empty`, `fnv_ascii`, `fnv_high_utf16` (`«Ω€`), `fnv_long_text` (8800 chars) | `cbf29ce484222325`, `3736f7f99b7f1537`, `6551d71a6dd5c7af`, `58c984db59ee9365` |
| 32-bit wrap | `int_add_overflow`, `int_sub_underflow`, `int_mul_overflow` (`65536*65536`) | `-2147483648`, `2147483647`, `0` |
| remainder sign rules | `mod_neg_dividend` `-7%3`, `mod_neg_divisor` `7%-3`, `mod_both_neg` `-7%-3` | `-1`, `1`, `-1` |
| division truncation | `div_trunc_neg` `-7/2` | `-3` |
| shifts | `shl_sign_bit` `1 shl 31`, `neg_shl31`, `neg_arith_shr1` `-1 shr 1`, `neg_logic_shr1` `-1 ushr 1` | `-2147483648`, `-2147483648`, `-1`, `2147483647` |
| 64-bit wrap and narrowing | `long_mul_wrap` (`0x3FFF…FF * 4`), `long_to_int_truncate` (`0xFFFFFFFF1234L.toInt()`), `int_to_long_sign_extend`, `long_signed_max/min`, `long_unsigned_shr1` (`-1L ushr 1`), `mask_and_signed` | `-4`, `-60876`, `-1`, `9223372036854775807`, `-9223372036854775808`, `7fffffffffffffff`, `2147483647` |

`W2ArithmeticParityTest` recomputes all 29 vectors on the running target and compares them
with `PARITY_ARITH_VECTORS`, and separately checks the structural claims (Int wraps with no
promotion, `Long` -> `Int` keeps the low 32 bits signed, LCG state stays a positive 31-bit
`Int`, LCG byte extraction is always 0..255). On `wasmJsNodeTest` all 29 match, which is
the direct answer to the risk row: wasm's i64 multiply wrap, i32 shift and remainder sign
rules, and `Int` truncation behave exactly like the JVM for every construct the engine uses.

## 6. Web input limits (G6)

`WebInputLimits.prepare()` is the only entry a browser container should use: it refuses a
document before assembling it or building a world, and reports assembler errors as problems
instead of producing a run. Recorded probe (`limit_probe.tsv`, asserted behaviorally by
`W2WebInputLimitsTest`):

| probe | recorded outcome |
|---|---|
| `legal` | reference source, 34 lines / 605 bytes, no problems |
| `oversized` | `source has 1206 lines, limit is 1200` and `source is 14460 bytes, limit is 8192` |
| `prepare_legal` | `accepted maxTicks=200` (mission 01 guard) |
| `prepare_oversized` | `rejected before assembly: source has 1206 lines, limit is 1200 | source is 14460 bytes, limit is 8192` |
| `prepare_ticks_over_cap` | `REJECTED [requested tick budget 1000001 exceeds the engine cap 1000000]` |

The suites also prove: an assembler-legal source that only breaks the byte ceiling is
refused before assembly (a 8193-byte comment line plus one `WAIT`); an over-cap tick budget
is refused before assembly; a request may ask for fewer ticks (50) but never more than the
mission guard; empty, blank, non-ASCII, NUL and CR-containing sources are refused; assembler
errors come back as `line 1: ...` problems. The empty program case is a second guard layer:
`Mission.validate()` also refuses `firmware image must be a multiple of 3 bytes (got 0)`.

Android alignment: `FirmwareSource` keeps its own message wording for the store UI but its
numeric ceilings are `WebInputLimits` constants, and `FirmwareStoreRulesTest` asserts the
identity so the two front doors cannot drift apart.

## 7. Verification log (all on 2026-11-20)

Generator determinism, two independent JVM runs of `fixtures.ParityMatrixKt`:

```
run1: d72433b9b983fb0e3d98ac3ec38af2d0 parity_matrix.tsv
run1: 70e97537fa24d2f4564afefdd29784fd parity_arith.tsv
run1: bfe351142dbe4b40627060808083fd9b limit_probe.tsv
run1: 449585cb4d1c49b549b15e2ca872bc2c parity_traces.txt
run2: identical md5 for all four files
```

`parity_fixture_gen.py` reports `rows 10, arith 29, limits 5, trace lines 120` and refuses
to write if any recorded value disagrees with another file.

Close-out matrix (`gradle --rerun-tasks`, `BUILD SUCCESSFUL`, `JAVA_HOME` = Android Studio
JBR; full log `w2-gate-5.log`):

| module | task | tests | failures | errors |
|---|---|---|---|---|
| `:rf8-core` | `jvmTest` | 15 | 0 | 0 |
| `:rf8-core` | `wasmJsTest` (node) | 15 | 0 | 0 |
| `:fireline-engine` | `jvmTest` | 146 | 0 | 0 |
| `:fireline-engine` | `wasmJsTest` (node) | 146 | 0 | 0 |
| `:engine` (Arena) | `test` | 15 | 0 | 0 |
| `:app` | `testFreeDebugUnitTest` | 3 | 0 | 0 |
| `:app` | `testProDebugUnitTest` | 9 | 0 | 0 |
| `:fireline-app` | `testDebugUnitTest` | 53 | 0 | 0 |
| total | | 392 | 0 | 0 |

`:fireline-engine` went 127 -> 146 tests on both targets: the 127 pre-W2 suites plus the 19
W2 tests, and the W2 suites run identically on `jvmTest` and `wasmJsNodeTest`
(matrix 5, trace 3, arithmetic 2, limits 9 per target). `:engine` (Arena) is still a plain
Kotlin/JVM module, so its task is `test`, not `jvmTest`.

Targets and scope decision: `js` is deliberately not enabled in W2. The audit's vectors and
every fingerprint test live in `commonTest`, so the day a `js` target is added (the W1
record's fallback option) the same 19 tests assert js arithmetic with no new test code.
Enabling `js` now would pull the Kotlin/JS npm toolchain into a build whose Maven egress is
a curated mirror (W1 record), for a target the plan only wants as a fallback.

## 8. Defects and lessons found in W2

1. The first trace digest recording used a hand-typed FNV offset basis
   (`-7412745227649687435`, hex `9920a37d6777e075`) instead of the engine's
   `FNV_OFFSET_BASIS`. It produced a *self-consistent but wrong* family: the generator and
   the test agreed with each other while disagreeing with the engine. Caught by
   `parity_fixture_gen.py`'s consistency gate (trace digest must equal the matrix column)
   and by comparing `fnv_offset_basis` with `FirelineRules`. Rule: derive constants from the
   engine source, never retype them.
2. `Charsets.UTF_8` is not available in Kotlin `commonMain` for the `wasmJs` target
   (`compileKotlinWasmJs` failed). Fix: `String.encodeToByteArray()` (UTF-8 by definition in
   the common stdlib), used for the byte ceiling on all targets.
3. A pinned-fixture generator that emits Kotlin must be structurally validated, not just
   data-validated: a missing `)` in the generator's trace section produced a syntax error
   whose first symptom was unrelated `Unresolved reference` noise in three suites.
4. Stale `build/test-results/test/*` directories from the pre-KMP layout still sit on disk;
   reading test totals from the directory tree without checking which tasks actually ran
   overcounts (127 + 15 stale). Count from the tasks Gradle executed.

## 9. What W3 inherits

- The pinned fixtures and the four W2 suites are the web console's acceptance floor: any
  console behavior must reproduce this matrix on `wasmJs`, and the JSON fixtures let the
  page check itself in the browser.
- The web run path is `WebInputLimits.prepare()` then `FirelineRun` with
  `accepted.maxTicks`; nothing else may assemble or allocate user text.
- Fingerprint family, ceilings and matrix are stamped `engineVersion = 1`. Moving a pinned
  fingerprint requires a `FIRELINE_ENGINE_VERSION` bump (FIRELINE-DESIGN.md §13.2), a
  regenerated fixture set, and a note in this record explaining why the numbers moved.
- W3 gate G4 stays the emulator sequence anchor: tick 30 field values, mission 02 finishing
  at tick 102 with `$47e28d84`, plus the G5 `.asm` roundtrip against `:fireline-app`.
