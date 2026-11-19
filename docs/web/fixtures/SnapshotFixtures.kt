package fixtures

/*
 * Headless FIRELINE snapshot fixture generator (scratch tool, NOT repo source).
 *
 * Drives the pure-Kotlin engine (:rf8-core + :fireline-engine) tick by tick
 * through FirelineWorld.tickOnce() and dumps engine-read snapshot fixtures as
 * JSON for the FIRELINE mockups. Every value comes from engine state:
 * terrain/fire/packets/rover/vm/ports/civilians/events + world.finalStateHash()
 * (the same FNV-1a fingerprint the Step9/Step10 determinism tests assert).
 *
 * Purity rule: the sensor port RAND (10) is NEVER read, because
 * FirelineWorld.sensor(10) advances the sensor RNG stream and would change the
 * fingerprint of the snapshot being captured.
 */

import robofight.assembler.MNEMONICS
import robofight.assembler.assemble
import robofight.disasm.Disasm
import robofight.fireline.*
import java.io.File

private val OUT_DEFAULT = "snapshots.json"

// ---- tiny JSON writer (no JSON dependency in the engine) ----
private fun esc(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u%04X".format(c.code)) else append(c)
        }
    }
}
private fun q(s: String): String = "\"" + esc(s) + "\""
private fun arr(xs: List<String>): String = "[" + xs.joinToString(", ") + "]"
private fun obj(fields: List<Pair<String, String>>): String =
    fields.joinToString(", ", "{", "}") { (k, v) -> q(k) + ": " + v }
private fun bool(b: Boolean): String = if (b) "true" else "false"

// ---- direction / objective / mission-bit readable forms (engine tables) ----
private val OBJECTIVE_BITS = listOf(
    Objectives.EXTINGUISH_ALL to "EXTINGUISH_ALL",
    Objectives.RESCUE_ALL to "RESCUE_ALL",
    Objectives.REACH_EXIT to "REACH_EXIT",
    Objectives.SURVIVE_UNTIL to "SURVIVE_UNTIL",
    Objectives.PROTECT_ASSETS to "PROTECT_ASSETS",
)
private val MISSION_BITS = listOf(
    MISSION_BIT_CIVILIANS_RESCUED to "CIVILIANS_RESCUED",
    MISSION_BIT_FIRE_OUT to "FIRE_OUT",
    MISSION_BIT_ON_EXIT to "ON_EXIT",
    MISSION_BIT_SURVIVED to "SURVIVED",
    MISSION_BIT_CIVILIAN_LOST to "CIVILIAN_LOST",
    MISSION_BIT_ASSET_LOST to "ASSET_LOST",
    MISSION_BIT_OBJECTIVES_MET to "OBJECTIVES_MET",
    MISSION_BIT_FINISHED to "FINISHED",
)

/**
 * Official FIRELINE-profile + base body ports. Port 10 (RAND) is deliberately
 * absent: reading it draws from the sensor RNG stream.
 */
private val PORTS = listOf(
    0 to "HP", 1 to "DIR", 2 to "RX", 3 to "RY", 4 to "DIST", 5 to "ANGLE", 6 to "FIRE",
    7 to "SHIELD", 8 to "HEAT", 9 to "ENEMY_HP", 11 to "AHEAD", 12 to "PROFILE",
    13 to "PROFILE_REV", 14 to "CAPS",
    16 to "FIRE_DIST", 17 to "FIRE_ANGLE", 18 to "FIRE_LEVEL", 19 to "CIV_DIST",
    20 to "CIV_ANGLE", 21 to "CIV_COUNT", 22 to "WATER", 23 to "LOCAL_FIRE", 24 to "SMOKE",
    25 to "WIND_DIR", 26 to "WIND_FORCE", 27 to "EXIT_DIST", 28 to "EXIT_ANGLE",
    29 to "TILE", 30 to "MISSION",
)

/**
 * instruction index -> label name, rebuilt with the RF-8 assembler's own
 * label-splitting semantics (label before ':' that is a bare identifier,
 * instructions counted in program order). The assembler exposes the source-line
 * map (AsmResult.lineOf) but not labels, so this mirrors its pass-1 rule.
 */
private fun labelsByInsn(source: String): Map<Int, String> {
    val map = LinkedHashMap<Int, String>()
    var insn = 0
    for (raw in source.split("\n")) {
        val noComment = raw.substringBefore(';').trim()
        if (noComment.isEmpty()) continue
        var body = noComment
        val colon = noComment.indexOf(':')
        if (colon > 0) {
            val pre = noComment.substring(0, colon).trim()
            if (pre.isNotEmpty() && pre.all { it.isLetterOrDigit() || it == '_' }) {
                if (!map.containsKey(insn)) map[insn] = pre
                body = noComment.substring(colon + 1).trim()
            }
        }
        val first = body.split(Regex("[\\s,]+")).firstOrNull()?.uppercase()
        if (first != null && first in MNEMONICS) insn++
    }
    return map
}

private fun labelAt(labels: Map<Int, String>, insn: Int): String {
    labels[insn]?.let { return it }
    var best = -1
    for (k in labels.keys) if (k < insn && k > best) best = k
    return if (best >= 0) "${labels[best]}+${insn - best}" else "(entry)"
}

/** Vm exposes its multi-tick countdown only through lastOp "NAME (elapsed/total)". */
private val OP_COUNTER = Regex("\\((\\d+)/(\\d+)\\)")
private fun ticksRemaining(lastOp: String): Int? {
    val m = OP_COUNTER.find(lastOp) ?: return null
    return m.groupValues[2].toInt() - m.groupValues[1].toInt()
}

private fun terrainGrid(world: FirelineWorld): List<String> {
    val layout = world.layout
    val civCells = HashSet<Int>()
    for (c in layout.civilians) civCells.add(cellIndex(c.x, c.y))
    return (0 until GRID).map { y ->
        StringBuilder().apply {
            for (x in 0 until GRID) {
                val ch = when {
                    layout.roverX == x && layout.roverY == y -> GLYPH_ROVER
                    civCells.contains(cellIndex(x, y)) -> GLYPH_CIVILIAN
                    else -> terrainGlyph(layout.terrainAt(x, y))
                }
                append(ch)
            }
        }.toString()
    }
}

/** Engine's own live-intensity glyph mapping (TextGrid.fireGlyph). */
private fun fireGrid(world: FirelineWorld): List<String> =
    (0 until GRID).map { y ->
        StringBuilder().apply {
            for (x in 0 until GRID) append(TextGrid.fireGlyph(world.fireAt(x, y)) ?: GLYPH_FLOOR)
        }.toString()
    }

/** Per-objective satisfaction at this tick, judged by the engine's own §7.3 tests. */
private fun objectivesDone(world: FirelineWorld): List<String> {
    val m = world.descriptor.objectives
    val out = ArrayList<String>()
    if (Objectives.has(m, Objectives.EXTINGUISH_ALL) && world.fireAllOut()) out.add("EXTINGUISH_ALL")
    if (Objectives.has(m, Objectives.RESCUE_ALL) && world.allRequiredRescued()) out.add("RESCUE_ALL")
    if (Objectives.has(m, Objectives.REACH_EXIT) && world.roverOnExit()) out.add("REACH_EXIT")
    if (Objectives.has(m, Objectives.SURVIVE_UNTIL) &&
        world.survivalElapsed() && world.rover.alive && world.assetsIntactObjective()) out.add("SURVIVE_UNTIL")
    if (Objectives.has(m, Objectives.PROTECT_ASSETS) && world.assetsIntactObjective()) out.add("PROTECT_ASSETS")
    return out
}

private fun snapshot(
    world: FirelineWorld,
    mission: Mission,
    label: String,
    source: String,
    labels: Map<Int, String>,
    lineOf: IntArray,
    instructionCount: Int,
    history: List<Pair<Int, List<String>>>,
): String {
    val d = world.descriptor
    val rover = world.rover
    val vm = rover.vm
    val insns = Disasm.disassemble(d.firmware)
    val mon = vm.monitorInsn
    val insnText = if (mon in insns.indices) insns[mon].text else null
    val pcLine = if (mon in lineOf.indices) lineOf[mon] + 1 else null
    val status = if (!world.finished) "RUNNING" else world.status!!.name
    val halt = when {
        world.failureReason != null -> world.failureReason!!.name
        world.finished -> "MISSION_SUCCESS"
        else -> null
    }

    val objectivesReadable = OBJECTIVE_BITS.filter { d.objectives and it.first != 0 }.map { it.second }
    val bits = world.missionBits()
    val bitReadable = MISSION_BITS.filter { bits and it.first != 0 }.map { it.second }

    val fireCells = ArrayList<String>()
    for (y in 0 until GRID) for (x in 0 until GRID) {
        val f = world.fireAt(x, y)
        if (f > 0) fireCells.add(obj(listOf("x" to x.toString(), "y" to y.toString(), "intensity" to f.toString())))
    }

    val packets = world.packetsOrdered().map { p ->
        obj(listOf(
            "x" to p.x.toString(), "y" to p.y.toString(), "life" to p.ticksLeft.toString(),
            "dir" to q(TextGrid.dirName(p.dir)), "spawn_tick" to p.spawnTick.toString(),
        ))
    }

    val civilians = world.civiliansOrdered().map { c ->
        obj(listOf(
            "id" to c.id.toString(), "x" to c.x.toString(), "y" to c.y.toString(),
            "hp" to c.hp.toString(), "state" to q(c.status.name), "required" to bool(c.required),
        ))
    }

    val recent = history.takeLast(6).map { (t, e) ->
        obj(listOf("tick" to t.toString(), "events" to arr(e.map { q(it) })))
    }

    val fields = listOf(
        "label" to q(label),
        "mission_id" to mission.id.toString(),
        "mission_name" to q(mission.name),
        "briefing" to q(mission.briefing),
        "seed" to d.seed.toString(),
        "wind_dir" to q(TextGrid.dirName(d.windDir)),
        "wind_force" to d.windForce.toString(),
        "objectives_readable" to arr(objectivesReadable.map { q(it) }),
        "objectives_done" to arr(objectivesDone(world).map { q(it) }),
        "time_limit" to (d.timeLimit?.toString() ?: "null"),
        "tick" to world.tick.toString(),
        "tick_limit" to mission.maxTicks.toString(),
        "status" to q(status),
        "failure_reason" to (world.failureReason?.let { q(it.name) } ?: "null"),
        "halt_reason" to (halt?.let { q(it) } ?: "null"),
        "firmware_bytes" to d.firmware.size.toString(),
        "terrain" to arr(terrainGrid(world).map { q(it) }),
        "fire" to arr(fireGrid(world).map { q(it) }),
        "fire_cells" to arr(fireCells),
        "fire_intensity_total" to world.fireIntensitySum().toString(),
        "packets" to arr(packets),
        "rover" to obj(listOf(
            "x" to rover.x.toString(), "y" to rover.y.toString(),
            "dir" to q(TextGrid.dirName(rover.facing)), "hp" to rover.hp.toString(),
            "heat" to rover.heat.toString(), "water" to rover.water.toString(),
            "shield_ticks" to rover.shieldTicksRemaining.toString(),
        )),
        "stats" to obj(listOf(
            "shots" to rover.shots.toString(),
            "moves" to rover.distanceMoved.toString(),
            "dry_shots" to rover.shotsDry.toString(),
            "shields" to rover.shieldsRaised.toString(),
            "water_used" to rover.waterUsed.toString(),
            "burning_cells" to world.fireCellsBurning().toString(),
            "civilians_rescued" to world.civiliansRescued().toString(),
            "civilians_waiting" to world.civiliansWaiting().toString(),
            "civilians_lost" to world.civiliansLost().toString(),
            "assets_intact" to world.assetsIntact().toString(),
            "assets_lost" to world.assetsLost().toString(),
        )),
        "vm" to obj(listOf(
            "registers" to obj(listOf(
                "a" to vm.a.toString(), "x" to vm.x.toString(), "y" to vm.y.toString(), "sp" to vm.sp.toString(),
            )),
            "flags" to obj(listOf(
                "z" to bool(vm.flags and robofight.vm.Vm.FLAG_Z != 0),
                "n" to bool(vm.flags and robofight.vm.Vm.FLAG_N != 0),
                "c" to bool(vm.flags and robofight.vm.Vm.FLAG_C != 0),
            )),
            "pc" to vm.pc.toString(),
            "pc_hex" to q("0x%03X".format(vm.pc)),
            "pc_insn" to mon.toString(),
            "pc_line_number" to (pcLine?.toString() ?: "null"),
            "pc_label" to q(labelAt(labels, mon)),
            "instruction" to (insnText?.let { q(it) } ?: "null"),
            "last_op" to q(vm.lastOp),
            "instruction_ticks_remaining" to
                (if (vm.inProgress) ticksRemaining(vm.lastOp)?.toString() ?: "null" else "null"),
            "instruction_count" to instructionCount.toString(),
            "fault" to bool(vm.fault),
            "source" to q(source),
        )),
        "ports" to obj(PORTS.map { (n, name) -> name to world.sensor(n).toString() }),
        "mission_bits" to obj(listOf(
            "value" to bits.toString(),
            "readable" to arr(bitReadable.map { q(it) }),
        )),
        "civilians" to arr(civilians),
        "events" to arr(world.lastEvents.map { q(it) }),
        "recent_events" to arr(recent),
        "fingerprint" to q(world.finalStateHash()),
    )
    return obj(fields)
}

/**
 * Run one mission with one firmware source, snapshot the requested ticks
 * (tick -1 = the final tick) and return (label, json) pairs in the requested
 * label order. Each STEP is exactly one FirelineWorld.tickOnce().
 */
private fun scenario(
    mission: Mission,
    source: String,
    captures: List<Pair<Int, String>>,
): List<Pair<String, String>> {
    val asm = assemble(source)
    require(asm.ok) { "firmware did not assemble: ${asm.errors}" }
    val descriptor = mission.descriptor(asm.code)
    val world = FirelineWorld(descriptor)
    val labels = labelsByInsn(source)
    val history = ArrayList<Pair<Int, List<String>>>()
    val out = LinkedHashMap<String, String>()   // label -> json, requested order

    fun capture(label: String) {
        out[label] = snapshot(world, mission, label, source, labels, asm.lineOf, asm.instructionCount, history)
    }

    captures.firstOrNull { it.first == 0 }?.let { capture(it.second) }
    val wanted = captures.filter { it.first > 0 }

    var guard = 0
    while (!world.finished && guard < mission.maxTicks) {
        world.tickOnce()
        guard++
        history.add(world.tick to world.lastEvents)
        val hit = wanted.firstOrNull { it.first == world.tick }
        if (hit != null && !out.containsKey(hit.second)) capture(hit.second)
    }
    require(world.finished) { "${mission.name} did not terminate inside guard ${mission.maxTicks}" }

    val r = world.snapshot()
    val tail = if (r.failureReason != null) "FAILURE/${r.failureReason}" else "SUCCESS"
    captures.firstOrNull { it.first == -1 }?.let { capture(it.second) }
    require(out.size == captures.size) {
        "labels never captured: ${captures.map { it.second }.filter { !out.containsKey(it) }}"
    }

    println("[${mission.name}] finished tick=${r.tick} status=${r.status} reason=${r.failureReason} " +
        "firmware=${r.firmwareBytes}B/${r.firmwareInstructions}insn labels=${out.keys} hash=${r.finalStateHash}")
    return out.toList()
}

fun main(args: Array<String>) {
    val out = File(if (args.isNotEmpty()) args[0] else OUT_DEFAULT)
    val nozzle = Missions.NOZZLE_CHECK
    val corridor = Missions.CORRIDOR
    val never = nozzle.failingSources[0]

    val snapshots = ArrayList<String>()
    snapshots += scenario(nozzle, nozzle.referenceSource, listOf(0 to "01-ref-t0", 12 to "01-ref-t12", -1 to "01-ref-final")).map { it.second }
    snapshots += scenario(nozzle, never.second, listOf(30 to "01-never-t30", -1 to "01-never-final")).map { it.second }
    snapshots += scenario(corridor, corridor.referenceSource, listOf(12 to "02-ref-t12", 46 to "02-ref-t46", -1 to "02-ref-final")).map { it.second }

    val json = "{\n  \"snapshots\": [\n    " + snapshots.joinToString(",\n    ") + "\n  ]\n}\n"
    out.writeText(json)
    println("wrote ${out.absolutePath} : ${snapshots.size} snapshots, ${json.length} bytes")

    // Bonus fixtures (NOT part of the specified 8): 01-ref-t13 has a multi-tick SHOOT
    // in flight (instruction_ticks_remaining = 2), 01-ref-t15 has a live water packet.
    val bonus = scenario(nozzle, nozzle.referenceSource, listOf(13 to "01-ref-t13", 15 to "01-ref-t15")).map { it.second }
    val bonusJson = "{\n  \"snapshots\": [\n    " + bonus.joinToString(",\n    ") + "\n  ]\n}\n"
    val parent = out.absoluteFile.parentFile
    val bonusPath = File(parent, "snapshots.extra.json")
    bonusPath.writeText(bonusJson)
    println("wrote ${bonusPath.absolutePath} : ${bonus.size} bonus snapshots")
}
