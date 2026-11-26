package robofight.fireline

/**
 * W2 parity fixtures (docs/FIRELINE-WEB.md §9 W2, gates G2/G3/G6) plus the edge
 * wall fixtures of `PLAN-EDGE-WALLS.md` §7 step E2: the recorded fingerprint
 * matrix, golden event traces, 64/32-bit arithmetic vectors and the web
 * input-limit probe, asserted identically by `:fireline-engine` on `jvm` and `wasmJs`.
 *
 * GENERATED - never typed by hand. `docs/web/fixtures/ParityMatrix.kt` records every
 * value from live engine state on the JVM (engine v2, commit afec3755273df5d278d7349e2438979cd3d09bcb),
 * `docs/web/fixtures/parity_fixture_gen.py` wrote this file from that output.
 * Raw form of the same data: `parity_matrix.tsv`, `parity_traces.txt`,
 * `parity_arith.tsv`, `limit_probe.tsv`; JSON form for a browser container:
 * `parity_matrix.json`, `parity_traces.json`, `parity_arith.json`. Recipe:
 * `docs/web/fixtures/README.md`.
 *
 * A fingerprint here that moves without a `FIRELINE_ENGINE_VERSION` bump is a
 * determinism defect; the same row moving on one target only is an arithmetic or
 * container defect (§10 risk: Long/Int drift on wasmJs).
 *
 * PROVENANCE OF THE CURRENT COLUMNS: the 2026-11-26 reconcile merge
 * (PLAN-RECONCILE-MAIN-01.md §8) put main's blocked-edge semantics and main's
 * canonical-state edge-map stamping into this branch's engine while
 * `FIRELINE_ENGINE_VERSION` stays 2 for this increment. The probe/final hash
 * columns and the 6-failing outcome (TIME_EXPIRED at 120 instead of
 * ROVER_DESTROYED at 59) were re-recorded from the merged engine by a headless
 * kotlinc twin running exactly this pipeline (MeasureMain, scratch tree,
 * 2026-11-26). Every `traceHash` and every pinned trace line is unchanged: the
 * merge touches state stamping and water range, not the instruction stream.
 * The raw TSV/JSON under docs/web/fixtures and the version bump to 4 follow at
 * the anchor re-baseline, where the generator runs on the merged engine.
 */

internal const val PARITY_GENERATOR: String = "docs/web/fixtures/ParityMatrix.kt"
internal const val PARITY_ENGINE_VERSION: Int = 2
internal const val PARITY_SOURCE_COMMIT: String = "afec3755273df5d278d7349e2438979cd3d09bcb"
internal const val PARITY_PROBE_TICK: Int = 12
internal const val PARITY_EXPECTED_ROWS: Int = 16
internal const val PARITY_EXPECTED_MISSION_ROWS: Int = 12
internal const val PARITY_EXPECTED_WALL_ROWS: Int = 4
internal val PARITY_WALL_SCENARIOS: List<String> = listOf("wall-lane-move", "wall-nozzle-seal", "wall-lane-suppress", "wall-open-lane")

/**
 * One matrix row: one authored mission x one firmware variant, or one E2 wall
 * fixture layout x its firmware. The rule columns are what a fingerprint depends
 * on; `wallCount` is the engine's own `Layout.edgeCount` and `roundtrip` is the
 * measured canonical mission text round trip. Wall layout and firmware text are
 * pinned in PARITY_WALL_LAYOUT_TEXTS / PARITY_WALL_FIRMWARE_TEXTS below.
 */
internal data class ParityFixtureRow(
    val scenario: String,
    val missionId: Int,
    val missionName: String,
    val kind: String,
    val firmwareLabel: String,
    val probeTick: Int,
    val probeHash: String,
    val traceHash: String,
    val finalTick: Int,
    val status: String,
    val failureReason: String,
    val finalHash: String,
    val asmBytes: Int,
    val asmInsns: Int,
    val seed: Int,
    val objectives: Int,
    val timeLimit: Int?,
    val surviveUntil: Int?,
    val startingWater: Int,
    val windDir: Int,
    val windForce: Int,
    val maxTicks: Int,
    val wallCount: Int,
    val roundtrip: Boolean,
)

internal val PARITY_FIXTURE_ROWS: List<ParityFixtureRow> = listOf(
    ParityFixtureRow("1-reference", 1, "NOZZLE CHECK", "reference", "reference", 12, "bd325536d7b9c7c3", "4f4118286b349619", 17, "SUCCESS", "-", "af4580153d61b1b0", 102, 34, 1001, 1, 60, null, 100, 0, 0, 200, 0, true),
    ParityFixtureRow("1-failing", 1, "NOZZLE CHECK", "failing", "never shoots", 12, "e9302d9d0bc14ec4", "9f496c19b6af9b46", 60, "FAILURE", "TIME_EXPIRED", "c35cd239631ff200", 6, 2, 1001, 1, 60, null, 100, 0, 0, 200, 0, true),
    ParityFixtureRow("2-reference", 2, "CORRIDOR", "reference", "reference", 12, "894f4fbeb771c162", "ceddf1a2fa7d7b10", 102, "SUCCESS", "-", "d604c2a00e2b5de5", 75, 25, 2002, 5, 120, null, 100, 0, 0, 300, 0, true),
    ParityFixtureRow("2-failing", 2, "CORRIDOR", "failing", "naive always forward", 12, "2cf9d069d384e856", "88b5c429de62c944", 120, "FAILURE", "TIME_EXPIRED", "253c0923a8f05716", 6, 2, 2002, 5, 120, null, 100, 0, 0, 300, 0, true),
    ParityFixtureRow("3-reference", 3, "FIRST RESPONSE", "reference", "reference", 12, "35a762c2fee57856", "28016f5459a44c4d", 38, "SUCCESS", "-", "4d31436beaf3b6f2", 72, 24, 3003, 6, 120, null, 100, 0, 0, 300, 0, true),
    ParityFixtureRow("3-failing", 3, "FIRST RESPONSE", "failing", "skips the second civilian", 12, "17bfb41771c585dc", "d31f60893006df8f", 120, "FAILURE", "TIME_EXPIRED", "14406434564a81de", 45, 15, 3003, 6, 120, null, 100, 0, 0, 300, 0, true),
    ParityFixtureRow("4-reference", 4, "DOWNWIND", "reference", "reference", 12, "c9d405dfa8e8f757", "9bafb261b891c1c0", 147, "SUCCESS", "-", "f753254251a9a2ac", 105, 35, 4004, 7, 500, null, 100, 2, 2, 700, 0, true),
    ParityFixtureRow("4-failing", 4, "DOWNWIND", "failing", "ignores the burning line", 12, "8553c6ceba0ad2ae", "99752e4c059a058e", 500, "FAILURE", "TIME_EXPIRED", "53dfd86ba42cc775", 9, 3, 4004, 7, 500, null, 100, 2, 2, 700, 0, true),
    ParityFixtureRow("5-reference", 5, "HOT ZONE", "reference", "reference", 12, "7546cf4da3d5a66f", "317eca23f0149509", 45, "SUCCESS", "-", "60626fb4fbbf35a9", 111, 37, 5005, 22, 200, null, 60, 1, 0, 400, 0, true),
    ParityFixtureRow("5-failing", 5, "HOT ZONE", "failing", "no shield, straight through", 12, "c6ccd31690245419", "def56526d10c1cf2", 29, "FAILURE", "ROVER_DESTROYED", "cff3b92d4677a226", 6, 2, 5005, 22, 200, null, 60, 1, 0, 400, 0, true),
    ParityFixtureRow("6-reference", 6, "THIN WALL", "reference", "reference", 12, "535194c657ec67bc", "5f7fd7153968d3c2", 59, "SUCCESS", "-", "f18720e337c5d9fc", 108, 36, 6027, 5, 120, null, 100, 0, 0, 300, 3, true),
    ParityFixtureRow("6-failing", 6, "THIN WALL", "failing", "spray through the thin wall", 12, "af58dd4b469871ae", "aae04624e85d38cf", 120, "FAILURE", "TIME_EXPIRED", "eb1f32cf5f83ed0f", 15, 5, 6027, 5, 120, null, 100, 0, 0, 300, 3, true),
    ParityFixtureRow("wall-lane-move", 101, "WALL LANE", "wall", "lane move loop", 12, "ef26fe23202fe0b6", "88b5c429de62c944", 60, "FAILURE", "TIME_EXPIRED", "e53cc91a7afc2957", 6, 2, 6001, 4, 60, null, 100, 0, 0, 200, 3, true),
    ParityFixtureRow("wall-nozzle-seal", 102, "SEALED NOZZLE", "wall", "single shoot", 12, "515c98beac49d34d", "b87628c5ec530355", 60, "FAILURE", "TIME_EXPIRED", "d4b68c352bb30e0e", 9, 3, 6002, 4, 60, null, 100, 0, 0, 200, 1, true),
    ParityFixtureRow("wall-lane-suppress", 103, "WALL LANE SUPPRESS", "wall", "single shoot", 12, "cc2ed4624c1a1658", "e762879cfcc1a486", 60, "FAILURE", "TIME_EXPIRED", "22fc9570c4018b34", 9, 3, 6003, 4, 60, null, 100, 0, 0, 200, 3, true),
    ParityFixtureRow("wall-open-lane", 104, "OPEN LANE", "wall-open", "lane move loop", 12, "e435299b0965de5e", "88b5c429de62c944", 44, "SUCCESS", "-", "0281179a451a7f6c", 6, 2, 6001, 4, 60, null, 100, 0, 0, 200, 2, true),
)

/**
 * E2 wall fixtures: the canonical mission text of each wall-carrying layout,
 * pinned exactly as the engine emitted it (TERRAIN 20x20, FIRE 20x20,
 * WALL_VERT 20x19, WALL_HORIZ 19x20, ROVER). No wall layout is re-typed here.
 */
internal val PARITY_WALL_LAYOUT_TEXTS: Map<String, String> = mapOf(
    "wall-lane-move" to "FIRELINE LAYOUT 20\nTERRAIN\n....................\n....................\n.................E..\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nFIRE\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nWALL_VERT\n...................\n...................\n.....#.............\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\nWALL_HORIZ\n....................\n..#.................\n..#.................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nROVER 2,2,2\n",
    "wall-nozzle-seal" to "FIRELINE LAYOUT 20\nTERRAIN\n....................\n....................\n.................E..\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nFIRE\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nWALL_VERT\n...................\n...................\n..#................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\nWALL_HORIZ\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nROVER 2,2,2\n",
    "wall-lane-suppress" to "FIRELINE LAYOUT 20\nTERRAIN\n....................\n....................\n.................E..\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nFIRE\n....................\n....................\n......2.............\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nWALL_VERT\n...................\n...................\n.....#.............\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\nWALL_HORIZ\n....................\n..#.................\n..#.................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nROVER 2,2,2\n",
    "wall-open-lane" to "FIRELINE LAYOUT 20\nTERRAIN\n....................\n....................\n.................E..\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nFIRE\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nWALL_VERT\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\n...................\nWALL_HORIZ\n....................\n..#.................\n..#.................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\n....................\nROVER 2,2,2\n",
)

/** The firmware source each wall fixture row ran, as recorded. */
internal val PARITY_WALL_FIRMWARE_TEXTS: Map<String, String> = mapOf(
    "wall-lane-move" to "loop:  MOVE\n       JMP    loop",
    "wall-nozzle-seal" to "fire:  SHOOT\nloop:  WAIT\n       JMP    loop",
    "wall-lane-suppress" to "fire:  SHOOT\nloop:  WAIT\n       JMP    loop",
    "wall-open-lane" to "loop:  MOVE\n       JMP    loop",
)

/** Gate G2: `FirelineRun.eventTrace(descriptor, probeTick)` verbatim, per scenario. */
internal val PARITY_GOLDEN_TRACES: Map<String, List<String>> = mapOf(
    "1-reference" to listOf(
        "001 IN port18 | ",
        "002 JNZ → 265 | ",
        "003 SHOOT (1/3) | ",
        "004 SHOOT (2/3) | ",
        "005 SHOOT (3/3) | PACKET -> 3,2",
        "006 IN port18 | ",
        "007 JNZ → 277 | FIRE -25 at 5,2 -> 30",
        "008 SHOOT (1/3) | ",
        "009 SHOOT (2/3) | ",
        "010 SHOOT (3/3) | PACKET -> 3,2",
        "011 IN port18 | ",
        "012 JNZ → 289 | FIRE -25 at 5,2 -> 10",
    ),
    "1-failing" to listOf(
        "001 WAIT | ",
        "002 JMP 256 | ",
        "003 WAIT | ",
        "004 JMP 256 | ",
        "005 WAIT | ",
        "006 JMP 256 | ",
        "007 WAIT | ",
        "008 JMP 256 | ",
        "009 WAIT | ",
        "010 JMP 256 | ",
        "011 WAIT | ",
        "012 JMP 256 | ",
    ),
    "2-reference" to listOf(
        "001 TURN R | ",
        "002 MOVE (1/2) | ",
        "003 MOVE (2/2) | ",
        "004 MOVE (1/2) | ",
        "005 MOVE (2/2) | ",
        "006 MOVE (1/2) | ",
        "007 MOVE (2/2) | ",
        "008 MOVE (1/2) | ",
        "009 MOVE (2/2) | ",
        "010 MOVE (1/2) | ",
        "011 MOVE (2/2) | ",
        "012 MOVE (1/2) | ",
    ),
    "2-failing" to listOf(
        "001 MOVE (1/2) | ",
        "002 MOVE (2/2) | ",
        "003 JMP 256 | ",
        "004 MOVE (1/2) | ",
        "005 MOVE (2/2) | ",
        "006 JMP 256 | ",
        "007 MOVE (1/2) | ",
        "008 MOVE (2/2) | ",
        "009 JMP 256 | ",
        "010 MOVE (1/2) | ",
        "011 MOVE (2/2) | ",
        "012 JMP 256 | ",
    ),
    "3-reference" to listOf(
        "001 MOVE (1/2) | ",
        "002 MOVE (2/2) | ",
        "003 MOVE (1/2) | ",
        "004 MOVE (2/2) | ",
        "005 MOVE (1/2) | ",
        "006 MOVE (2/2) | CIVILIAN #1 RESCUED at 5,2",
        "007 MOVE (1/2) | ",
        "008 MOVE (2/2) | ",
        "009 MOVE (1/2) | ",
        "010 MOVE (2/2) | FIRE IGNITES 12,5 floor from 12,4 dir=S chance=60<64 · FIRE IGNITES 2,16 floor from 3,16 dir=W chance=48<64",
        "011 MOVE (1/2) | ",
        "012 MOVE (2/2) | ",
    ),
    "3-failing" to listOf(
        "001 MOVE (1/2) | ",
        "002 MOVE (2/2) | ",
        "003 MOVE (1/2) | ",
        "004 MOVE (2/2) | ",
        "005 MOVE (1/2) | ",
        "006 MOVE (2/2) | CIVILIAN #1 RESCUED at 5,2",
        "007 TURN R | ",
        "008 MOVE (1/2) | ",
        "009 MOVE (2/2) | ",
        "010 MOVE (1/2) | FIRE IGNITES 12,5 floor from 12,4 dir=S chance=60<64 · FIRE IGNITES 2,16 floor from 3,16 dir=W chance=48<64",
        "011 MOVE (2/2) | ",
        "012 MOVE (1/2) | ",
    ),
    "4-reference" to listOf(
        "001 IN port25 | ",
        "002 IN port26 | ",
        "003 MOVE (1/2) | ",
        "004 MOVE (2/2) | ",
        "005 IN port18 | ",
        "006 JZ (skip) | ",
        "007 SHOOT (1/3) | ",
        "008 SHOOT (2/3) | ",
        "009 SHOOT (3/3) | PACKET -> 4,2 · FIRE -25 at 4,2 -> 10 · REFILL +10",
        "010 JMP 265 | ",
        "011 IN port18 | ",
        "012 JZ (skip) | ",
    ),
    "4-failing" to listOf(
        "001 TURN R | ",
        "002 MOVE (1/2) | ",
        "003 MOVE (2/2) | ",
        "004 JMP 256 | ",
        "005 TURN R | ",
        "006 MOVE (1/2) | ",
        "007 MOVE (2/2) | ",
        "008 JMP 256 | ",
        "009 TURN R | ",
        "010 MOVE (1/2) | ",
        "011 MOVE (2/2) | ",
        "012 JMP 256 | ",
    ),
    "5-reference" to listOf(
        "001 SHIELD (1/2) | ",
        "002 SHIELD (2/2) | SHIELD ACTIVE 5",
        "003 MOVE (1/2) | ",
        "004 MOVE (2/2) | SHIELD BLOCKS FIRE 100 AT 2,2",
        "005 MOVE (1/2) | SHIELD BLOCKS FIRE 100 AT 2,2 · FIRE IGNITES 1,2 floor from 2,2 dir=W chance=10<64",
        "006 MOVE (2/2) | SHIELD BLOCKS FIRE 100 AT 3,2",
        "007 MOVE (1/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "008 MOVE (2/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "009 MOVE (1/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "010 MOVE (2/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "011 SHIELD (1/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "012 SHIELD (2/2) | SHIELD ACTIVE 5 · SHIELD BLOCKS FIRE 100 AT 5,2",
    ),
    "5-failing" to listOf(
        "001 MOVE (1/2) | ",
        "002 MOVE (2/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "003 JMP 256 | ROVER FIRE DAMAGE -4 (fire 100)",
        "004 MOVE (1/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "005 MOVE (2/2) | ROVER FIRE DAMAGE -4 (fire 100) · FIRE IGNITES 1,2 floor from 2,2 dir=W chance=10<64",
        "006 JMP 256 | ROVER FIRE DAMAGE -4 (fire 100)",
        "007 MOVE (1/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "008 MOVE (2/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "009 JMP 256 | ROVER FIRE DAMAGE -4 (fire 100)",
        "010 MOVE (1/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "011 MOVE (2/2) | ROVER FIRE DAMAGE -4 (fire 100)",
        "012 JMP 256 | ROVER FIRE DAMAGE -4 (fire 100)",
    ),
    "6-reference" to listOf(
        "001 MOVE (1/2) | ",
        "002 MOVE (2/2) | ",
        "003 MOVE (1/2) | ",
        "004 MOVE (2/2) | ",
        "005 MOVE (1/2) | ",
        "006 MOVE (2/2) | ",
        "007 IN port11 | ",
        "008 JZ (skip) | ",
        "009 TURN R | ",
        "010 MOVE (1/2) | ",
        "011 MOVE (2/2) | ",
        "012 TURN L | ",
    ),
    "6-failing" to listOf(
        "001 MOVE (1/2) | ",
        "002 MOVE (2/2) | ",
        "003 MOVE (1/2) | ",
        "004 MOVE (2/2) | ",
        "005 MOVE (1/2) | ",
        "006 MOVE (2/2) | ",
        "007 SHOOT (1/3) | ",
        "008 SHOOT (2/3) | ",
        "009 SHOOT (3/3) | PACKET BLOCKED EDGE 6,2",
        "010 JMP 265 | ",
        "011 SHOOT (1/3) | ",
        "012 SHOOT (2/3) | ",
    ),
    "wall-lane-move" to listOf(
        "001 MOVE (1/2) | ",
        "002 MOVE (2/2) | ",
        "003 JMP 256 | ",
        "004 MOVE (1/2) | ",
        "005 MOVE (2/2) | ",
        "006 JMP 256 | ",
        "007 MOVE (1/2) | ",
        "008 MOVE (2/2) | ",
        "009 JMP 256 | ",
        "010 MOVE (1/2) | ",
        "011 MOVE (2/2) | ",
        "012 JMP 256 | ",
    ),
    "wall-nozzle-seal" to listOf(
        "001 SHOOT (1/3) | ",
        "002 SHOOT (2/3) | ",
        "003 SHOOT (3/3) | PACKET BLOCKED EDGE 3,2",
        "004 WAIT | ",
        "005 JMP 259 | ",
        "006 WAIT | ",
        "007 JMP 259 | ",
        "008 WAIT | ",
        "009 JMP 259 | ",
        "010 WAIT | ",
        "011 JMP 259 | ",
        "012 WAIT | ",
    ),
    "wall-lane-suppress" to listOf(
        "001 SHOOT (1/3) | ",
        "002 SHOOT (2/3) | ",
        "003 SHOOT (3/3) | PACKET -> 3,2",
        "004 WAIT | ",
        "005 JMP 259 | ",
        "006 WAIT | PACKET SPENT EDGE 6,2",
        "007 JMP 259 | ",
        "008 WAIT | ",
        "009 JMP 259 | ",
        "010 WAIT | ",
        "011 JMP 259 | ",
        "012 WAIT | ",
    ),
    "wall-open-lane" to listOf(
        "001 MOVE (1/2) | ",
        "002 MOVE (2/2) | ",
        "003 JMP 256 | ",
        "004 MOVE (1/2) | ",
        "005 MOVE (2/2) | ",
        "006 JMP 256 | ",
        "007 MOVE (1/2) | ",
        "008 MOVE (2/2) | ",
        "009 JMP 256 | ",
        "010 MOVE (1/2) | ",
        "011 MOVE (2/2) | ",
        "012 JMP 256 | ",
    ),
)

/** Gate G4: 64/32-bit behavior vectors every container must reproduce (JVM values). */
internal val PARITY_ARITH_VECTORS: Map<String, String> = mapOf(
    "rng_sensor_1001" to "246,76,90,12,45,147,235,196,15,182,175,95",
    "rng_spread_2002" to "23,251,115,67,45,77,31,123,141,10,251,215",
    "rng_layout_3003" to "239,200,206,122,221,60,82,26,237,103,151,87",
    "rng_next_of_wrap" to "1043980748",
    "rng_byte_of_min" to "255",
    "fnv_offset_basis" to "cbf29ce484222325",
    "fnv_prime" to "00000100000001b3",
    "fnv_empty" to "cbf29ce484222325",
    "fnv_ascii" to "3736f7f99b7f1537",
    "fnv_high_utf16" to "6551d71a6dd5c7af",
    "fnv_long_text" to "58c984db59ee9365",
    "int_add_overflow" to "-2147483648",
    "int_sub_underflow" to "2147483647",
    "int_mul_overflow" to "0",
    "mod_neg_dividend" to "-1",
    "mod_neg_divisor" to "1",
    "mod_both_neg" to "-1",
    "div_trunc_neg" to "-3",
    "shl_sign_bit" to "-2147483648",
    "neg_shl31" to "-2147483648",
    "neg_arith_shr1" to "-1",
    "neg_logic_shr1" to "2147483647",
    "long_mul_wrap" to "-4",
    "long_to_int_truncate" to "-60876",
    "int_to_long_sign_extend" to "-1",
    "long_signed_max" to "9223372036854775807",
    "long_signed_min" to "-9223372036854775808",
    "long_unsigned_shr1" to "7fffffffffffffff",
    "mask_and_signed" to "2147483647",
)

/** Gate G6: `WebInputLimits` probe outcomes, recorded on the JVM. */
internal val PARITY_LIMIT_PROBE: Map<String, String> = mapOf(
    "legal" to "reference | 34 | 605 | -",
    "oversized" to "generated | 1206 | 14460 | source has 1206 lines, limit is 1200 | source is 14460 bytes, limit is 8192",
    "prepare_legal" to "reference | - | - | accepted maxTicks=200",
    "prepare_oversized" to "generated | - | - | rejected before assembly: source has 1206 lines, limit is 1200 | source is 14460 bytes, limit is 8192",
    "prepare_ticks_over_cap" to "reference | - | - | REJECTED [requested tick budget 1000001 exceeds the engine cap 1000000]",
)
