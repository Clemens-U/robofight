package robofight.fireline

/**
 * W2 parity fixtures (docs/FIRELINE-WEB.md §9 W2, gates G2/G3/G6): the recorded
 * fingerprint matrix, golden event traces, 64/32-bit arithmetic vectors and the web
 * input-limit probe, asserted identically by `:fireline-engine` on `jvm` and `wasmJs`.
 *
 * GENERATED - never typed by hand. `docs/web/fixtures/ParityMatrix.kt` records every
 * value from live engine state on the JVM (engine v1, commit d66ef5f8214506120857fd15aaa80c1042e9d4cc),
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
internal const val PARITY_ENGINE_VERSION: Int = 1
internal const val PARITY_SOURCE_COMMIT: String = "d66ef5f8214506120857fd15aaa80c1042e9d4cc"
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
    ParityFixtureRow("1-reference", 1, "NOZZLE CHECK", "reference", "reference", 12, "fa31bb2eced7a30c", "4f4118286b349619", 17, "SUCCESS", "-", "198645dc2e7e9649", 102, 34),
    ParityFixtureRow("1-failing", 1, "NOZZLE CHECK", "failing", "never shoots", 12, "85461a84b158812d", "9f496c19b6af9b46", 60, "FAILURE", "TIME_EXPIRED", "a64c17a9d85a6f75", 6, 2),
    ParityFixtureRow("2-reference", 2, "CORRIDOR", "reference", "reference", 12, "113c566635ef3e13", "ceddf1a2fa7d7b10", 102, "SUCCESS", "-", "47e28d84c5d61220", 75, 25),
    ParityFixtureRow("2-failing", 2, "CORRIDOR", "failing", "naive always forward", 12, "43054c601ddd8737", "88b5c429de62c944", 120, "FAILURE", "TIME_EXPIRED", "095dc5eba29eef27", 6, 2),
    ParityFixtureRow("3-reference", 3, "FIRST RESPONSE", "reference", "reference", 12, "df4571c82f169e1b", "28016f5459a44c4d", 38, "SUCCESS", "-", "0cf3b083da3900fd", 72, 24),
    ParityFixtureRow("3-failing", 3, "FIRST RESPONSE", "failing", "skips the second civilian", 12, "3153a509654849f5", "d31f60893006df8f", 120, "FAILURE", "TIME_EXPIRED", "818eda729f5866d7", 45, 15),
    ParityFixtureRow("4-reference", 4, "DOWNWIND", "reference", "reference", 12, "7f2f344b8d0a52c4", "9bafb261b891c1c0", 147, "SUCCESS", "-", "dd669e7313a67d41", 105, 35),
    ParityFixtureRow("4-failing", 4, "DOWNWIND", "failing", "ignores the burning line", 12, "da58ac6a8b1a7e81", "99752e4c059a058e", 500, "FAILURE", "TIME_EXPIRED", "b2c14340a8f8a1de", 9, 3),
    ParityFixtureRow("5-reference", 5, "HOT ZONE", "reference", "reference", 12, "672a982213390d0c", "317eca23f0149509", 45, "SUCCESS", "-", "a7836db790e4a6a0", 111, 37),
    ParityFixtureRow("5-failing", 5, "HOT ZONE", "failing", "no shield, straight through", 12, "47145b5826115f48", "def56526d10c1cf2", 29, "FAILURE", "ROVER_DESTROYED", "b7b8b80ea652ce1d", 6, 2),
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
