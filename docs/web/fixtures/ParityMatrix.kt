package fixtures

import robofight.assembler.assemble
import robofight.fireline.FIRELINE_ENGINE_VERSION
import robofight.fireline.FNV_OFFSET_BASIS
import robofight.fireline.FNV_PRIME
import robofight.fireline.FirelineRun
import robofight.fireline.FirelineWorld
import robofight.fireline.LcgStream
import robofight.fireline.MAX_TICKS
import robofight.fireline.Mission
import robofight.fireline.Missions
import robofight.fireline.WebInputLimits

/**
 * W2 parity harness (docs/FIRELINE-WEB.md §9 W2, gates G2/G3/G6).
 *
 * Emits the recorded data that `:fireline-engine` commonTest pins as fixtures:
 *
 *  1. `parity_matrix.tsv` - one row per mission x firmware variant (reference +
 *     the documented failing variant): probe tick + mid-run fingerprint, final
 *     tick / status / failure reason / fingerprint, and the golden event trace of
 *     the first [probe] ticks (`FirelineRun.eventTrace`, §13.2 item 4).
 *  2. `parity_arith.tsv` - the 64/32-bit arithmetic vectors the wasm parity
 *     audit pins: the engine's own LCG stream, the FNV-1a 64 hash constants, and
 *     the wrap / shift / remainder behaviors every container must reproduce.
 *  3. `limit_probe.tsv` - a legal source vs an oversized source, the oversized
 *     one rejected by `WebInputLimits` WITHOUT assembling or allocating.
 *
 * Everything here is read from live engine state, never typed by hand: the
 * fixtures the tests assert are generated from this output.
 *
 * Run (see docs/web/fixtures/README.md): kotlinc over `:rf8-core` +
 * `:fireline-engine` commonMain sources plus this file, then
 * `fixtures.ParityMatrixKt <outputDir>`.
 */

private const val PROBE_TICK = 12

private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

private const val HEX_DIGITS = "0123456789abcdef"

private fun hex(v: Long): String = buildString {
    for (shift in 60 downTo 0 step 4) append(HEX_DIGITS[((v shr shift) and 0xF).toInt()])
}

/** One scenario row: reference or failing firmware of one authored mission. */
private class Row(
    val mission: Mission,
    val kind: String,
    val label: String,
    val source: String,
)

private fun probeOf(mission: Mission, kind: String, label: String, source: String): Row =
    Row(mission, kind, label, source)

fun main(args: Array<String>) {
    val outDir = (args.firstOrNull() ?: ".").trimEnd('\\', '/')

    // ---- 1. fingerprint matrix + golden traces (G2, G3) -------------------------
    val matrix = ArrayList<String>()
    matrix.add("scenario\tmission\tname\tkind\tfirmware_label\tprobe_tick\tprobe_hash\ttrace_hash\t" +
        "final_tick\tstatus\tfailure_reason\tfinal_hash\tasm_ok\tasm_bytes\tasm_insns")
    val traceBlock = StringBuilder()

    for (m in Missions.ALL) {
        val rows = ArrayList<Row>()
        rows.add(probeOf(m, "reference", "reference", m.referenceSource))
        for ((label, source, _) in m.failingSources) rows.add(probeOf(m, "failing", label, source))

        for (row in rows) {
            val asm = assemble(row.source)
            if (!asm.ok) {
                println("ASSEMBLE FAIL ${row.mission.id}/${row.label}: " +
                    asm.errors.joinToString(" | ") { "line ${it.line}: ${it.msg}" })
            }
            check(asm.ok) { "firmware did not assemble: ${row.label}" }
            val descriptor = row.mission.descriptor(asm.code)

            val finalRes = FirelineRun.runWithGuard(row.mission, descriptor)
            val stepped = FirelineRun.runStepByStep(descriptor, row.mission.maxTicks)
            check(finalRes == stepped && finalRes.finalStateHash == stepped.finalStateHash) {
                "runner and step-by-step disagree for ${row.mission.id}/${row.label}"
            }

            // Probe: step-by-step to a fixed tick (or the run's own final tick if
            // the mission ends earlier), then read the world's own fingerprint.
            val probe = minOf(PROBE_TICK, finalRes.tick)
            val world = FirelineWorld(descriptor)
            repeat(probe) { world.tickOnce() }
            check(world.tick == probe) { "probe tick mismatch for ${row.mission.id}/${row.label}" }
            val probeHash = world.finalStateHash()

            val trace = FirelineRun.eventTrace(descriptor, probe)
            val traceText = trace.joinToString("\n")
            val traceHash = fnvOf(traceText)

            matrix.add(
                listOf(
                    "${row.mission.id}-${row.kind}", row.mission.id.toString(), row.mission.name, row.kind,
                    row.label, probe.toString(), probeHash, traceHash, finalRes.tick.toString(),
                    finalRes.status.toString(), finalRes.failureReason?.toString() ?: "-",
                    finalRes.finalStateHash, asm.ok.toString(), asm.code.size.toString(),
                    (asm.code.size / 3).toString(),
                ).joinToString("\t")
            )
            traceBlock.append("# ${row.mission.id}-${row.kind} ${row.label} probe=$probe trace_hash=$traceHash\n")
            traceBlock.append(traceText).append('\n')
        }
    }

    // ---- 2. arithmetic vectors (Long/Int semantics audit) ----------------------
    val arith = ArrayList<String>()
    arith.add("probe\tvalue")
    fun put(name: String, value: String) { arith.add("$name\t$value") }

    fun fnv(text: String): String = fnvOf(text)

    for ((seed, saltName) in listOf(1001 to "sensor", 2002 to "spread", 3003 to "layout")) {
        val stream = streamOf(seed, saltName)
        put("rng_${saltName}_$seed", (0 until 12).joinToString(",") { stream.nextByte().toString() })
    }
    put("rng_next_of_wrap", LcgStream.nextOf(0x7FFFFFFF).toString())
    put("rng_byte_of_min", LcgStream.byteOf(-1).toString())

    put("fnv_offset_basis", hex(FNV_OFFSET_BASIS))
    put("fnv_prime", hex(0x100000001b3L))
    put("fnv_empty", fnv(""))
    put("fnv_ascii", fnv("OPCODE FIRELINE // W2 parity probe"))
    put("fnv_high_utf16", fnv("«Ω€"))
    put("fnv_long_text", fnv("FIRELINE W2 parity probe ".repeat(400)))

    put("int_add_overflow", (Int.MAX_VALUE + 1).toString())
    put("int_sub_underflow", (Int.MIN_VALUE - 1).toString())
    put("int_mul_overflow", (65536 * 65536).toString())
    put("mod_neg_dividend", (-7 % 3).toString())
    put("mod_neg_divisor", (7 % -3).toString())
    put("mod_both_neg", (-7 % -3).toString())
    put("div_trunc_neg", (-7 / 2).toString())
    put("shl_sign_bit", (1 shl 31).toString())
    put("neg_shl31", (-1 shl 31).toString())
    put("neg_arith_shr1", (-1 shr 1).toString())
    put("neg_logic_shr1", (-1 ushr 1).toString())
    put("long_mul_wrap", (0x3FFFFFFFFFFFFFFFL * 4L).toString())
    put("long_to_int_truncate", (0xFFFFFFFF1234L.toInt()).toString())
    put("int_to_long_sign_extend", ((-1).toLong()).toString())
    put("long_signed_max", Long.MAX_VALUE.toString())
    put("long_signed_min", Long.MIN_VALUE.toString())
    put("long_unsigned_shr1", hex((-1L) ushr 1))
    put("mask_and_signed", (0x7FFFFFFF and (-1)).toString())

    // ---- 3. web input limit probe (G6) -----------------------------------------
    val limits = ArrayList<String>()
    limits.add("probe\tsource_kind\tlines\tbytes\tproblems")
    val legal = Missions.NOZZLE_CHECK.referenceSource
    val huge = "       WAIT\n".repeat(WebInputLimits.MAX_SOURCE_LINES + 5)
    limits.add("legal\treference\t${legal.split('\n').size}\t${legal.toByteArray().size}\t-")
    val rejected = WebInputLimits.sourceProblems(huge)
    limits.add("oversized\tgenerated\t${huge.split('\n').size}\t${huge.toByteArray().size}\t${rejected.joinToString(" | ")}")
    val preparedLegal = WebInputLimits.prepare(Missions.NOZZLE_CHECK, legal, Missions.NOZZLE_CHECK.maxTicks)
    val preparedHuge = WebInputLimits.prepare(Missions.NOZZLE_CHECK, huge, Missions.NOZZLE_CHECK.maxTicks)
    val preparedOverTicks = WebInputLimits.prepare(Missions.NOZZLE_CHECK, legal, MAX_TICKS + 1)
    limits.add("prepare_legal\treference\t-\t-\t${preparedLegal.acceptedOrNull()?.let { "accepted maxTicks=${it.maxTicks}" } ?: "REJECTED ${preparedLegal.problems}"}")
    limits.add("prepare_oversized\tgenerated\t-\t-\t${preparedHuge.acceptedOrNull()?.let { "ACCEPTED (BUG)" } ?: "rejected before assembly: ${preparedHuge.problems.joinToString(" | ")}"}")
    limits.add("prepare_ticks_over_cap\treference\t-\t-\t${preparedOverTicks.acceptedOrNull()?.let { "accepted maxTicks=${it.maxTicks}" } ?: "REJECTED ${preparedOverTicks.problems}"}")

    val commit = commitOf()
    val engineTag = "# engine v$FIRELINE_ENGINE_VERSION"
    val commitTag = "# commit $commit"
    java.io.File(outDir, "parity_matrix.tsv")
        .writeText((listOf(commitTag, engineTag) + matrix).joinToString("\n") + "\n")
    java.io.File(outDir, "parity_traces.txt").writeText(traceBlock.toString())
    java.io.File(outDir, "parity_arith.tsv")
        .writeText((listOf(commitTag, engineTag) + arith).joinToString("\n") + "\n")
    java.io.File(outDir, "limit_probe.tsv")
        .writeText((listOf(commitTag, engineTag) + limits).joinToString("\n") + "\n")

    println("engine v$FIRELINE_ENGINE_VERSION")
    println("rows=${matrix.size - 1} arith=${arith.size - 1} limits=${limits.size - 1}")
    println(matrix.joinToString("\n"))
    println()
    println(limits.joinToString("\n"))
}

// ---- shared helpers (also used by the pinned commonTest fixtures) -------------
/** Provenance tag for the recorded fixtures: HEAD of the checkout that ran the harness. */
private fun commitOf(): String = try {
    val p = ProcessBuilder("git", "rev-parse", "HEAD").start()
    val line = p.inputStream.bufferedReader().readLine()?.trim()
    p.waitFor()
    if (line != null && line.length == 40) line else "unknown"
} catch (e: Exception) {
    "unknown"
}

// The fingerprint family is the engine's own: FNV_OFFSET_BASIS / FNV_PRIME from
// `FirelineRules` (canonical FNV-1a 64 parameters, offset basis 0xCBF29CE484222325
// stored as a signed Long), exactly as `FirelineWorld.finalStateHash()` uses them.

private fun fnvLong(text: String): Long {
    var h = FNV_OFFSET_BASIS
    for (c in text) h = (h xor c.code.toLong()) * FNV_PRIME
    return h
}

private fun fnvOf(text: String): String = hex(fnvLong(text))

private fun streamOf(seed: Int, saltName: String): LcgStream = when (saltName) {
    "sensor" -> LcgStream.forSensor(seed)
    "spread" -> LcgStream.forSpread(seed)
    else -> LcgStream.forLayout(seed)
}
