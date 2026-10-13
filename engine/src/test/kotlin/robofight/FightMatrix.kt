package robofight

import robofight.world.Arena
import robofight.world.Bot
import robofight.world.Cell
import robofight.world.DIR_E
import robofight.world.DIR_N
import robofight.world.DIR_S
import robofight.world.DIR_W
import robofight.world.Presets
import robofight.world.SPAWN_CLASH
import robofight.world.SPAWN_SEPARATE
import robofight.world.World
import kotlin.math.abs

/**
 * §12.3 acceptance matrix (Stage-1 release gate). For EVERY arena, the
 * 5-preset × 5-preset ordered matrix (25 pairs) in both DUEL (SEPARATE) and
 * MELEE (CLASH) — 50 fights per arena, 250 total.
 *
 * Verdict per the spec: a fight "resolves (KO or timed-out, never the
 * 1,000,000-tick ceiling)". The matrix therefore runs each fight in TIMED
 * mode with [BUDGET] = 2,000 ticks (decision recorded in PLAN-CHALLENGE-CODES.md,
 * 2026-10-13): every fight ends by tick 2,000 at the latest — by KO, or by
 * the time limit (highest HP wins, exact tie = DRAW). The gate: no fight may
 * run past the budget, i.e. the 1,000,000-tick cap is never the thing that
 * ends a shipped fight.
 *
 * The matrix result ships with the release (§7.2.6): a per-arena KO /
 * timed-out report plus a known-stalemate appendix (the pairs that reach the
 * time limit — preset behavior, not arena defects: the HUNTER orbit, SNAKE's
 * non-aiming, HUNTER's corner pacing, CHAOS's slow convergence, TURTLE's
 * shield self-burn). Arena quality is read from the KO rate per arena.
 */
object FightMatrix {
    const val BUDGET = 2000

    data class Fight(
        val arenaName: String,
        val mode: String,        // DUEL | MELEE
        val a: String,
        val b: String,
        val ticks: Int,
        val winner: String,      // bot name, or "DRAW" (timed-out tie)
        val timedOut: Boolean,   // ended by the time limit (not by KO)
    ) {
        val outcome: String get() = if (timedOut) "TIME" else "KO"
    }

    fun runAll(onFight: ((Fight) -> Unit)? = null): List<Fight> {
        val out = ArrayList<Fight>(250)
        for (layout in Arena.all()) {
            for ((mode, spawn) in listOf("DUEL" to SPAWN_SEPARATE, "MELEE" to SPAWN_CLASH)) {
                for (pa in Presets.all()) for (pb in Presets.all()) {
                    val (ca, cb) = layout.spawnCells(spawn)
                    val w = World(layout)
                    w.timeLimit = BUDGET
                    w.add(Bot(pa.name, pa.glyph, pa.color, ca.x, ca.y, faceToward(ca, cb), pa.firmware))
                    w.add(Bot(pb.name, pb.glyph, pb.color, cb.x, cb.y, faceToward(cb, ca), pb.firmware))
                    while (!w.finished) w.tickOnce()
                    require(w.tick <= BUDGET) {
                        "${layout.name} $mode ${pa.name} vs ${pb.name} ran to tick ${w.tick} > $BUDGET"
                    }
                    // tick < BUDGET  -> a KO (or a rare double-KO: both bots die
                    //                   on the same tick, e.g. identical
                    //                   TURTLE shields overheating together —
                    //                   winner stays null = DRAW)
                    // tick == BUDGET -> timed out (a KO landing exactly on the
                    //                   limit tick is indistinguishable and
                    //                   counted as timed out, conservatively)
                    val timedOut = w.tick >= BUDGET
                    val f = Fight(layout.name, mode, pa.name, pb.name, w.tick,
                        w.winner?.name ?: "DRAW", timedOut)
                    out.add(f)
                    onFight?.invoke(f)
                }
            }
        }
        return out
    }

    /** Facing toward a cell along the dominant axis (mirrors World.turnToward). */
    private fun faceToward(from: Cell, to: Cell): Int {
        val dx = to.x - from.x
        val dy = to.y - from.y
        if (abs(dx) >= abs(dy)) return if (dx >= 0) DIR_E else DIR_W
        return if (dy >= 0) DIR_S else DIR_N
    }

    /** Standalone headless runner: prints the per-arena report + stalemate appendix. */
    fun report(): String {
        val fights = runAll()
        val sb = StringBuilder()
        sb.append("§12.3 matrix — 250 fights, KO or timed-out, budget ").append(BUDGET).append(" ticks\n")
        for (layout in Arena.all()) {
            val per = fights.filter { it.arenaName == layout.name }
            val kos = per.count { !it.timedOut }
            val times = per.size - kos
            val byWinner = per.groupingBy { it.winner }.eachCount()
                .toSortedMap().map { (k, v) -> "$k=$v" }.joinToString(" ")
            sb.append(layout.name).append(": ").append(per.size).append(" fights · KO=")
                .append(kos).append(" timed-out=").append(times).append(" · ")
                .append(byWinner).append('\n')
        }
        val timedOut = fights.filter { it.timedOut }
        sb.append("TOTAL ").append(fights.size).append(" fights: KO=")
            .append(fights.count { !it.timedOut }).append(", timed-out=").append(timedOut.size)
            .append(" (all within budget ").append(BUDGET).append(" ticks)\n")
        sb.append("Known-stalemate appendix (pairs that reach the time limit):\n")
        var last = ""
        for (f in timedOut) {
            val label = "${f.arenaName} ${f.mode}: ${f.a} vs ${f.b}"
            if (label != last) {
                sb.append("  ").append(label).append(" -> ").append(f.winner).append('\n')
                last = label
            }
        }
        return sb.toString()
    }
}

fun main() {
    print(FightMatrix.report())
}
