package fixtures

/*
 * Independent cross-check for snapshots.json (scratch tool, NOT repo source).
 *
 * Mid-run snapshots are re-derived with a fresh step-by-step world; the FINAL
 * states are derived through the headless authority FirelineRun.runWithGuard()
 * (the runner path, not the manual loop) and compared against the
 * runStepByStep() path, which is what the engine's determinism tests assert.
 * Output is TSV; verify_crosscheck.py compares it against snapshots.json.
 */

import robofight.assembler.assemble
import robofight.fireline.*

private fun probe(mission: Mission, source: String, tick: Int): String {
    val code = assemble(source).code
    val w = FirelineWorld(mission.descriptor(code))
    while (w.tick < tick && !w.finished) w.tickOnce()
    return listOf(
        w.tick, if (!w.finished) "RUNNING" else w.status!!.name, w.failureReason?.name ?: "-",
        w.rover.x, w.rover.y, w.rover.hp, w.rover.heat, w.rover.water,
        w.fireIntensitySum(), w.packetCount(), w.rover.shots, w.rover.distanceMoved,
        w.finalStateHash(),
        // E4: the wall rows the snapshot schema now carries (engine renderer copies).
        w.layout.wallVertRowMajor().count { it }, w.layout.wallHorizRowMajor().count { it }, w.layout.edgeCount(),
    ).joinToString("\t").toString()
}

/** The wall rows themselves, one TSV line per label: '#'-glyph rows, vert then horiz. */
private fun wallRows(mission: Mission, source: String, tick: Int): String {
    val code = assemble(source).code
    val w = FirelineWorld(mission.descriptor(code))
    while (w.tick < tick && !w.finished) w.tickOnce()
    val vert = w.layout.wallVertRowMajor()
    val horiz = w.layout.wallHorizRowMajor()
    val rows = ArrayList<String>()
    for (y in 0 until robofight.fireline.WALL_VERT_ROWS) {
        val sb = StringBuilder()
        for (x in 0 until robofight.fireline.VERT_EDGE_COLUMNS) {
            sb.append(if (vert[y * robofight.fireline.VERT_EDGE_COLUMNS + x]) '#' else '.')
        }
        rows.add(sb.toString())
    }
    for (y in 0 until robofight.fireline.WALL_HORIZ_ROWS) {
        val sb = StringBuilder()
        for (x in 0 until robofight.fireline.GRID) {
            sb.append(if (horiz[y * robofight.fireline.GRID + x]) '#' else '.')
        }
        rows.add(sb.toString())
    }
    return rows.joinToString(",")
}

/** Where does this run actually have live packets / multi-tick ops in flight? */
private fun activity(mission: Mission, source: String): String {
    val w = FirelineWorld(mission.descriptor(assemble(source).code))
    val packets = ArrayList<Int>()
    val inflight = ArrayList<Pair<Int, String>>()
    while (!w.finished) {
        w.tickOnce()
        if (w.packetCount() > 0) packets.add(w.tick)
        if (w.rover.vm.inProgress) inflight.add(w.tick to w.rover.vm.lastOp)
    }
    val samples = inflight.take(3).joinToString("; ") { (t, op) -> "tick$t:$op" }
    return "packets_on_ticks=${packets.joinToString(",")} " +
        "multi_tick_ticks=${inflight.size} samples=[$samples]"
}

fun main() {
    val nozzle = Missions.NOZZLE_CHECK
    val corridor = Missions.CORRIDOR
    val never = nozzle.failingSources[0].second
    val thinWall = Missions.THIN_WALL
    val thinWallFail = thinWall.failingSources[0].second

    val mid = listOf(
        "01-ref-t0" to Triple(nozzle, nozzle.referenceSource, 0),
        "01-ref-t12" to Triple(nozzle, nozzle.referenceSource, 12),
        "01-never-t30" to Triple(nozzle, never, 30),
        "02-ref-t12" to Triple(corridor, corridor.referenceSource, 12),
        "02-ref-t46" to Triple(corridor, corridor.referenceSource, 46),
        "01-ref-t15" to Triple(nozzle, nozzle.referenceSource, 15),
        "01-ref-t13" to Triple(nozzle, nozzle.referenceSource, 13),
        "06-ref-t0" to Triple(thinWall, thinWall.referenceSource, 0),
        "06-ref-t12" to Triple(thinWall, thinWall.referenceSource, 12),
        "06-ref-t24" to Triple(thinWall, thinWall.referenceSource, 24),
    )
    for ((label, t) in mid) println("PROBE\t$label\t" + probe(t.first, t.second, t.third))

    val finals = listOf(
        Triple("01-ref-final", nozzle, nozzle.referenceSource),
        Triple("01-never-final", nozzle, never),
        Triple("02-ref-final", corridor, corridor.referenceSource),
        Triple("06-ref-final", thinWall, thinWall.referenceSource),
        Triple("06-fail-final", thinWall, thinWallFail),
    )
    for ((label, m, src) in finals) {
        val a = FirelineRun.runWithGuard(m, m.descriptorForSource(src))   // authority path
        val b = FirelineRun.runStepByStep(m.descriptorForSource(src), m.maxTicks)
        println("FINAL\t$label\t${a.tick}\t${a.status}\t${a.failureReason ?: "-"}\t${a.roverHp}\t" +
            "${a.roverHeat}\t${a.waterRemaining}\t${a.fireIntensitySum}\t${a.burningCells}\t" +
            "${a.finalStateHash}\trunner==stepByStep:${a == b && a.finalStateHash == b.finalStateHash}\t" +
            "${m.layout.wallVertRowMajor().count { it }}\t${m.layout.wallHorizRowMajor().count { it }}\t${m.layout.edgeCount()}")
    }

    // E4: the wall row glyphs per label (identical across ticks: layout state is static).
    for ((label, t) in mid) println("WALL\t$label\t" + wallRows(t.first, t.second, t.third))
    println("WALL\t06-ref-final\t" + wallRows(thinWall, thinWall.referenceSource, 0))
    println("WALL\t06-fail-final\t" + wallRows(thinWall, thinWallFail, 0))

    println("ACTIVITY\t01-ref\t" + activity(nozzle, nozzle.referenceSource))
    println("ACTIVITY\t01-never\t" + activity(nozzle, never))
    println("ACTIVITY\t02-ref\t" + activity(corridor, corridor.referenceSource))
    println("ACTIVITY\t06-ref\t" + activity(thinWall, thinWall.referenceSource))
    println("ACTIVITY\t06-fail\t" + activity(thinWall, thinWallFail))
}
