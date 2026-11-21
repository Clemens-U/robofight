package robofight.fireline

/**
 * W2 parity fixtures (docs/FIRELINE-WEB.md §9 W2, gates G2/G3/G6): the recorded
 * fingerprint matrix, golden event traces, 64/32-bit arithmetic vectors and the web
 * input-limit probe, asserted identically by `:fireline-engine` on `jvm` and `wasmJs`.
 *
 * GENERATED - never typed by hand. `docs/web/fixtures/ParityMatrix.kt` records every
 * value from live engine state on the JVM (engine v2, commit ed93d8926f9b0d6f9d193df458d104aa5518a7c8),
 * `docs/web/fixtures/parity_fixture_gen.py` wrote this file from that output.
 * Raw form of the same data: `parity_matrix.tsv`, `parity_traces.txt`,
 * `parity_arith.tsv`, `limit_probe.tsv`; JSON form for a browser container:
 * `parity_matrix.json`, `parity_traces.json`, `parity_arith.json`. Recipe:
 * `docs/web/fixtures/README.md`.
 *
 * A fingerprint here that moves without a `FIRELINE_ENGINE_VERSION` bump is a
 * determinism defect; the same row moving on one target only is an arithmetic or
 * container defect (§10 risk: Long/Int drift on wasmJs).
 */

internal const val PARITY_GENERATOR: String = "docs/web/fixtures/ParityMatrix.kt"
internal const val PARITY_ENGINE_VERSION: Int = 2
internal const val PARITY_SOURCE_COMMIT: String = "ed93d8926f9b0d6f9d193df458d104aa5518a7c8"
internal const val PARITY_PROBE_TICK: Int = 12
internal const val PARITY_EXPECTED_ROWS: Int = 10

/** One matrix row: one authored mission x one firmware variant. */
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
)

internal val PARITY_FIXTURE_ROWS: List<ParityFixtureRow> = listOf(
    ParityFixtureRow("1-reference", 1, "NOZZLE CHECK", "reference", "reference", 12, "f3dd62629ba58d1f", "4f4118286b349619", 17, "SUCCESS", "-", "245faa6d10316e00", 102, 34),
    ParityFixtureRow("1-failing", 1, "NOZZLE CHECK", "failing", "never shoots", 12, "df8ebe851b2c1670", "9f496c19b6af9b46", 60, "FAILURE", "TIME_EXPIRED", "7cbfe49993c17318", 6, 2),
    ParityFixtureRow("2-reference", 2, "CORRIDOR", "reference", "reference", 12, "d0f26fd5c0b02efa", "ceddf1a2fa7d7b10", 102, "SUCCESS", "-", "d3ea47119fe3b101", 75, 25),
    ParityFixtureRow("2-failing", 2, "CORRIDOR", "failing", "naive always forward", 12, "55814d5ffbe783d6", "88b5c429de62c944", 120, "FAILURE", "TIME_EXPIRED", "2e88bd6f1f4ed91e", 6, 2),
    ParityFixtureRow("3-reference", 3, "FIRST RESPONSE", "reference", "reference", 12, "ba3c37b47d2133b6", "28016f5459a44c4d", 38, "SUCCESS", "-", "01663660e6731d0a", 72, 24),
    ParityFixtureRow("3-failing", 3, "FIRST RESPONSE", "failing", "skips the second civilian", 12, "aec31f0cbb71140c", "d31f60893006df8f", 120, "FAILURE", "TIME_EXPIRED", "fee14c44c61f245a", 45, 15),
    ParityFixtureRow("4-reference", 4, "DOWNWIND", "reference", "reference", 12, "84736ea77c8e8a67", "9bafb261b891c1c0", 147, "SUCCESS", "-", "acf4656261a3c008", 105, 35),
    ParityFixtureRow("4-failing", 4, "DOWNWIND", "failing", "ignores the burning line", 12, "07fc73be7bf8f31e", "99752e4c059a058e", 500, "FAILURE", "TIME_EXPIRED", "51f0f7232dd88add", 9, 3),
    ParityFixtureRow("5-reference", 5, "HOT ZONE", "reference", "reference", 12, "426c4fdd8f395eef", "317eca23f0149509", 45, "SUCCESS", "-", "ba8e443d3c469c1d", 111, 37),
    ParityFixtureRow("5-failing", 5, "HOT ZONE", "failing", "no shield, straight through", 12, "2b88919b27fcd1e1", "def56526d10c1cf2", 29, "FAILURE", "ROVER_DESTROYED", "89bceb4241170566", 6, 2),
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
