package fixtures

import robofight.assembler.assemble
import robofight.fireline.CANONICAL_HEADER
import robofight.fireline.DIR_E
import robofight.fireline.EDGE_GLYPH_OPEN
import robofight.fireline.EDGE_GLYPH_WALL
import robofight.fireline.FIRELINE_ENGINE_VERSION
import robofight.fireline.FNV_OFFSET_BASIS
import robofight.fireline.FNV_PRIME
import robofight.fireline.FirelineRun
import robofight.fireline.FirelineWorld
import robofight.fireline.GLYPH_EXIT
import robofight.fireline.GLYPH_FLOOR
import robofight.fireline.GRID
import robofight.fireline.ITEM_ROVER
import robofight.fireline.LcgStream
import robofight.fireline.Layout
import robofight.fireline.MAX_TICKS
import robofight.fireline.Mission
import robofight.fireline.Missions
import robofight.fireline.Objectives
import robofight.fireline.SECTION_FIRE
import robofight.fireline.SECTION_TERRAIN
import robofight.fireline.SECTION_WALL_HORIZ
import robofight.fireline.SECTION_WALL_VERT
import robofight.fireline.VERT_EDGE_COLUMNS
import robofight.fireline.WALL_HORIZ_ROWS
import robofight.fireline.WALL_VERT_ROWS
import robofight.fireline.WebInputLimits

/**
 * W2 parity harness (docs/FIRELINE-WEB.md §9 W2, gates G2/G3/G6) plus the
 * edge-wall fixture rows of `PLAN-EDGE-WALLS.md` §7 step E2.
 *
 * Emits the recorded data that `:fireline-engine` commonTest pins as fixtures:
 *
 *  1. `parity_matrix.tsv` - one row per mission x firmware variant (reference +
 *     the documented failing variant) for the five authored missions, then one
 *     row per E2 wall fixture layout: probe tick + mid-run fingerprint, final
 *     tick / status / failure reason / fingerprint, the golden event trace of
 *     the first [probe] ticks (`FirelineRun.eventTrace`, §13.2 item 4), the
 *     mission rules the fingerprint depends on, and for the wall rows the
 *     engine's own canonical mission text plus its measured round trip.
 *  2. `parity_arith.tsv` - the 64/32-bit arithmetic vectors the wasm parity
 *     audit pins: the engine's own LCG stream, the FNV-1a 64 hash constants, and
 *     the wrap / shift / remainder behaviors every container must reproduce.
 *  3. `limit_probe.tsv` - a legal source vs an oversized source, the oversized
 *     one rejected by `WebInputLimits` WITHOUT assembling or allocating.
 *
 * Everything here is read from live engine state, never typed by hand: the
 * fixtures the tests assert are generated from this output. The only authored
 * inputs are the wall layout mission sources and their firmware, which are
 * inputs to the engine, not measurements of it.
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

// ==================== E2 wall fixtures (PLAN-EDGE-WALLS.md §7 step E2) ====================
//
// Four wall-carrying layouts, authored as canonical mission text in the shape
// `Layout.toCanonicalText` emits: `FIRELINE LAYOUT 20`, TERRAIN 20x20, FIRE
// 20x20, WALL_VERT 20x19, WALL_HORIZ 19x20, then `ROVER x,y,dir`. The geometry
// reuses the E1 fixture of `EdgeWallsTest.kt`: spawn (2,2) facing east, the lane
// gate WALL_VERT row 2 char 5 (the edge between (5,2) and (6,2)), and the spawn
// cell's north and south edges WALL_HORIZ rows 1 and 2 char 2. Every layout is a
// REACH_EXIT mission, so its exit reachability validation (gate 5) must pass.
// No authored mission is added to `Missions.kt`: ids 101 to 104 are fixture ids
// only and step E5 owns shipped wall content.

/** Spawn and exit of every wall fixture layout (the E1 fixture geometry). */
private const val WALL_SPAWN_X = 2
private const val WALL_SPAWN_Y = 2
private const val WALL_EXIT_X = 17
private const val WALL_EXIT_Y = 2

/** Lane gate of the E1 fixture: WALL_VERT row 2 char 5, the edge right of (5,2). */
private val LANE_VERT_WALLS: Map<Int, Set<Int>> = mapOf(WALL_SPAWN_Y to setOf(5))

/** Spawn cell's north and south edges: WALL_HORIZ rows 1 and 2, char 2. */
private val LANE_HORIZ_WALLS: Map<Int, Set<Int>> = mapOf(1 to setOf(2), 2 to setOf(2))

/** Sealed nozzle layout's single wall: the edge RIGHT of the spawn (2,2). */
private val SEALED_VERT_WALLS: Map<Int, Set<Int>> = mapOf(WALL_SPAWN_Y to setOf(WALL_SPAWN_X))

/** Burning cell one step beyond the lane gate of the suppress layout: intensity glyph 2 (50). */
private val SUPPRESS_FIRE_EDITS: List<Triple<Int, Int, Char>> = listOf(Triple(6, 2, '2'))

/** Firmware of the two lane rows: one MOVE per three ticks (`EdgeWallsTest.kt` MOVE_LOOP). */
private const val MOVE_LOOP_FIRMWARE = "loop:  MOVE\n       JMP    loop"

/** Firmware of the two packet rows: one SHOOT (three ticks), then idle (`EdgeWallsTest.kt` SHOOT_ONCE). */
private const val SHOOT_ONCE_FIRMWARE = "fire:  SHOOT\nloop:  WAIT\n       JMP    loop"

private fun vertRowsOf(walls: Map<Int, Set<Int>>): List<String> = (0 until WALL_VERT_ROWS).map { y ->
    (0 until VERT_EDGE_COLUMNS).map { x ->
        if (walls[y]?.contains(x) == true) EDGE_GLYPH_WALL else EDGE_GLYPH_OPEN
    }.joinToString("")
}

private fun horizRowsOf(walls: Map<Int, Set<Int>>): List<String> = (0 until WALL_HORIZ_ROWS).map { y ->
    (0 until GRID).map { x ->
        if (walls[y]?.contains(x) == true) EDGE_GLYPH_WALL else EDGE_GLYPH_OPEN
    }.joinToString("")
}

/** Terrain rows: all floor, one exit cell at (WALL_EXIT_X, WALL_EXIT_Y). */
private fun wallTerrainRows(): List<String> = (0 until GRID).map { y ->
    (0 until GRID).map { x ->
        if (x == WALL_EXIT_X && y == WALL_EXIT_Y) GLYPH_EXIT else GLYPH_FLOOR
    }.joinToString("")
}

/** Fire rows: nothing burning unless an (x, y, level glyph) edit says otherwise. */
private fun wallFireRows(edits: List<Triple<Int, Int, Char>>): List<String> = (0 until GRID).map { y ->
    (0 until GRID).map { x ->
        edits.firstOrNull { it.first == x && it.second == y }?.third ?: GLYPH_FLOOR
    }.joinToString("")
}

/** One wall-carrying mission source in canonical text form (PLAN-EDGE-WALLS.md §4 order). */
private fun wallMissionSource(
    vertWalls: Map<Int, Set<Int>>,
    horizWalls: Map<Int, Set<Int>>,
    fireEdits: List<Triple<Int, Int, Char>>,
    roverDir: Int,
): String = buildString {
    append("$CANONICAL_HEADER $GRID\n")
    append("$SECTION_TERRAIN\n").append(wallTerrainRows().joinToString("\n")).append('\n')
    append("$SECTION_FIRE\n").append(wallFireRows(fireEdits).joinToString("\n")).append('\n')
    append("$SECTION_WALL_VERT\n").append(vertRowsOf(vertWalls).joinToString("\n")).append('\n')
    append("$SECTION_WALL_HORIZ\n").append(horizRowsOf(horizWalls).joinToString("\n")).append('\n')
    append("$ITEM_ROVER $WALL_SPAWN_X,$WALL_SPAWN_Y,$roverDir\n")
}

/** One wall fixture: a layout source plus the firmware and seed that probe it. */
private class WallFixture(
    val scenario: String,
    val kind: String,
    val missionId: Int,
    val name: String,
    val layoutSource: String,
    val firmwareLabel: String,
    val firmwareSource: String,
    val seed: Int,
)

/** Build one fixture mission; `requireValid` makes layout validation a harness gate. */
private fun wallMissionOf(fixture: WallFixture): Mission = Mission(
    id = fixture.missionId,
    name = fixture.name,
    briefing = "fixture wall layout (PLAN-EDGE-WALLS.md §7 step E2, not shipped content)",
    layout = Layout.fromCanonicalText(fixture.layoutSource),
    seed = fixture.seed,
    objectives = Objectives.REACH_EXIT,
    timeLimit = 60,
    referenceSource = fixture.firmwareSource,
    failingSources = emptyList(),
    maxTicks = 200,
).requireValid()

/** The four E2 wall rows (recorded values in PLAN-EDGE-WALLS.md §12). */
private fun wallFixtures(): List<WallFixture> = listOf(
    WallFixture(
        scenario = "wall-lane-move", kind = "wall", missionId = 101, name = "WALL LANE",
        layoutSource = wallMissionSource(LANE_VERT_WALLS, LANE_HORIZ_WALLS, emptyList(), DIR_E),
        firmwareLabel = "lane move loop", firmwareSource = MOVE_LOOP_FIRMWARE, seed = 6001,
    ),
    WallFixture(
        scenario = "wall-nozzle-seal", kind = "wall", missionId = 102, name = "SEALED NOZZLE",
        layoutSource = wallMissionSource(SEALED_VERT_WALLS, emptyMap(), emptyList(), DIR_E),
        firmwareLabel = "single shoot", firmwareSource = SHOOT_ONCE_FIRMWARE, seed = 6002,
    ),
    WallFixture(
        scenario = "wall-lane-suppress", kind = "wall", missionId = 103, name = "WALL LANE SUPPRESS",
        layoutSource = wallMissionSource(LANE_VERT_WALLS, LANE_HORIZ_WALLS, SUPPRESS_FIRE_EDITS, DIR_E),
        firmwareLabel = "single shoot", firmwareSource = SHOOT_ONCE_FIRMWARE, seed = 6003,
    ),
    WallFixture(
        scenario = "wall-open-lane", kind = "wall-open", missionId = 104, name = "OPEN LANE",
        layoutSource = wallMissionSource(emptyMap(), LANE_HORIZ_WALLS, emptyList(), DIR_E),
        firmwareLabel = "lane move loop", firmwareSource = MOVE_LOOP_FIRMWARE, seed = 6001,
    ),
)

fun main(args: Array<String>) {
    val outDir = (args.firstOrNull() ?: ".").trimEnd('\\', '/')

    // ---- 1. fingerprint matrix + golden traces (G2, G3) -------------------------
    val matrix = ArrayList<String>()
    val columns = listOf(
        "scenario", "mission", "name", "kind", "firmware_label", "probe_tick", "probe_hash", "trace_hash",
        "final_tick", "status", "failure_reason", "final_hash", "asm_ok", "asm_bytes", "asm_insns",
        "seed", "objectives", "time_limit", "survive_until", "starting_water", "wind_dir", "wind_force",
        "max_ticks", "wall_count", "layout_source", "firmware_source", "roundtrip",
    )
    val layoutSourceCol = columns.indexOf("layout_source")
    matrix.add(columns.joinToString("\t"))
    val traceBlock = StringBuilder()

    /**
     * Record one scenario row from live engine state. Every value the row carries
     * is read back from the engine after the run, never copied from the inputs.
     * The authored inputs (layout text, firmware text) are carried only for the
     * wall fixtures; authored missions keep "-" because their sources are already
     * committed in `Missions.kt`.
     */
    fun emitRow(
        mission: Mission,
        scenario: String,
        kind: String,
        label: String,
        source: String,
        layoutSource: String,
        firmwareText: String,
    ) {
        val asm = assemble(source)
        if (!asm.ok) {
            println("ASSEMBLE FAIL $scenario: " +
                asm.errors.joinToString(" | ") { "line ${it.line}: ${it.msg}" })
        }
        check(asm.ok) { "firmware did not assemble: $scenario" }
        val descriptor = mission.descriptor(asm.code)

        val finalRes = FirelineRun.runWithGuard(mission, descriptor)
        val stepped = FirelineRun.runStepByStep(descriptor, mission.maxTicks)
        check(finalRes == stepped && finalRes.finalStateHash == stepped.finalStateHash) {
            "runner and step-by-step disagree for $scenario"
        }

        // Probe: step-by-step to a fixed tick (or the run's own final tick if
        // the mission ends earlier), then read the world's own fingerprint.
        val probe = minOf(PROBE_TICK, finalRes.tick)
        val world = FirelineWorld(descriptor)
        repeat(probe) { world.tickOnce() }
        check(world.tick == probe) { "probe tick mismatch for $scenario" }
        val probeHash = world.finalStateHash()

        val trace = FirelineRun.eventTrace(descriptor, probe)
        val traceText = trace.joinToString("\n")
        val traceHash = fnvOf(traceText)

        // Canonical mission text round trip, measured on the live layout.
        val layout = mission.layout
        val canonical = layout.toCanonicalText()
        val reparsed = Layout.fromCanonicalText(canonical)
        val roundtrip = canonical == reparsed.toCanonicalText() &&
            layout.terrainRowMajor().contentEquals(reparsed.terrainRowMajor()) &&
            layout.fireRowMajor().contentEquals(reparsed.fireRowMajor()) &&
            layout.wallVertRowMajor().contentEquals(reparsed.wallVertRowMajor()) &&
            layout.wallHorizRowMajor().contentEquals(reparsed.wallHorizRowMajor()) &&
            layout.roverX == reparsed.roverX && layout.roverY == reparsed.roverY &&
            layout.roverDir == reparsed.roverDir && layout.civilians.size == reparsed.civilians.size
        check(roundtrip) { "canonical mission text round trip differs for $scenario" }
        if (layoutSource != "-") {
            check(layoutSource == canonical) {
                "the authored wall layout source is not the canonical text the engine emits for $scenario"
            }
        }

        matrix.add(
            listOf(
                scenario, mission.id.toString(), mission.name, kind,
                label, probe.toString(), probeHash, traceHash, finalRes.tick.toString(),
                finalRes.status.toString(), finalRes.failureReason?.toString() ?: "-",
                finalRes.finalStateHash, asm.ok.toString(), asm.code.size.toString(),
                (asm.code.size / 3).toString(),
                mission.seed.toString(), mission.objectives.toString(),
                mission.timeLimit?.toString() ?: "-", mission.surviveUntil?.toString() ?: "-",
                mission.startingWater.toString(), mission.windDir.toString(), mission.windForce.toString(),
                mission.maxTicks.toString(), layout.edgeCount().toString(),
                esc(layoutSource), esc(firmwareText), roundtrip.toString(),
            ).joinToString("\t")
        )
        traceBlock.append("# $scenario $label probe=$probe trace_hash=$traceHash\n")
        traceBlock.append(traceText).append('\n')
    }

    for (m in Missions.ALL) {
        val rows = ArrayList<Row>()
        rows.add(probeOf(m, "reference", "reference", m.referenceSource))
        for ((label, source, _) in m.failingSources) rows.add(probeOf(m, "failing", label, source))
        for (row in rows) {
            // Authored mission layouts and firmware are committed in `Missions.kt`,
            // so the layout text column stays "-" for them. It carries the engine
            // canonical text only for the wall fixtures, which no source file ships.
            emitRow(row.mission, "${row.mission.id}-${row.kind}", row.kind, row.label, row.source, "-", "-")
        }
    }

    for (fixture in wallFixtures()) {
        emitRow(
            wallMissionOf(fixture), fixture.scenario, fixture.kind, fixture.firmwareLabel,
            fixture.firmwareSource, fixture.layoutSource, fixture.firmwareSource,
        )
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
    matrix.forEachIndexed { index, line ->
        if (index == 0) {
            println(line)
        } else {
            val fields = line.split("\t").toMutableList()
            if (fields.size > layoutSourceCol && fields[layoutSourceCol].length > 1) {
                fields[layoutSourceCol] = "<canonical mission text ${fields[layoutSourceCol].length} chars>"
            }
            println(fields.joinToString("\t"))
        }
    }
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
