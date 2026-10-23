package robofight.challenge

import robofight.challenge.ChallengeFormat.FightDescriptor
import robofight.world.Arena
import robofight.world.Bot
import robofight.world.DIR_E
import robofight.world.DIR_N
import robofight.world.DIR_S
import robofight.world.DIR_W
import robofight.world.SPAWN_SCATTER
import robofight.world.Palette
import robofight.world.World
import robofight.world.scatterSpawn
import kotlin.math.abs

/**
 * Deterministic execution of a parsed [FightDescriptor] (eng=1 path).
 *
 * THE single descriptor→World construction path: the headless codec tests
 * (HARD GATE A) and the app's `RunController.startDeterministic` (R2) both
 * go through [buildWorld], so "identical code → identical result" is proven
 * on the same code the app ships. Pure JVM, no Android.
 *
 * Construction order is fixed and documented because the SCATTER spawn
 * consumes the world LCG (§7.6 — the code's seed is the ONLY entropy):
 *
 *   1. `World(layout)`            — layout resolved via [robofight.world.Arena.playableLayout]:
 *                                   fixed arenas = the registry instance unchanged;
 *                                   seed-derived family entries (0x10–0x1F packs)
 *                                   = f(id, seed), a pure function consuming NO
 *                                   world LCG (deterministic, v1-format content)
 *   2. `world.setSeed(seed)`      — the code's seed
 *   3. `world.timeLimit = …`      — timed modes (§6), null for KO
 *   4. spawn resolution           — SEPARATE/CLASH fixed cells; SCATTER
 *                                   draws cells 100+ times via world LCG
 *   5. bot placement              — A first, then B; facing = direction
 *                                   TOWARD the opponent (fixed convention)
 *
 * Pinned slots load via `Bot.fromBinary` (the decoded firmware block, §4.1);
 * open slots receive a [Bot] built by the caller from locally assembled
 * firmware (the app assembles it; this module only places + runs it).
 */
object ChallengeRun {

    /**
     * Outcome of a finished fight, in the code's slot orientation
     * (identical encoding to `FightDescriptor.Result`):
     * [winnerSlot] 0=A · 1=B · 2=DRAW (both dead / nobody alive);
     * [ticks] the fight length (== `world.tick`); [loserEndHp] the HP of
     * the side that did not win (DRAW → slot A's HP, per §4.2 semantics).
     */
    data class Outcome(val winnerSlot: Int, val ticks: Int, val loserEndHp: Int)

    /**
     * Build the fight. [openA]/[openB] are non-null exactly for the slots
     * that are open in the descriptor (pinned slots ignore them). The open
     * bots are repositioned via `Bot.reset` — their firmware (source or
     * binary) is reloaded and the descriptor's per-slot HP becomes their
     * configured starting HP.
     *
     * @throws IllegalArgumentException on an unknown layout id or a
     * slot/firmware mismatch (open slot without a bot, etc.) — the parse
     * path already rejects malformed codes; this is a caller invariant.
     */
    fun buildWorld(desc: FightDescriptor, openA: Bot?, openB: Bot?): World {
        val layout = Arena.playableLayout(desc.layoutId, desc.seed)
            ?: throw IllegalArgumentException("unknown layout id 0x${desc.layoutId.toString(16)}")
        require((openA != null) == desc.slotAOpen) { "slot A open/pinned mismatch" }
        require((openB != null) == desc.slotBOpen) { "slot B open/pinned mismatch" }

        val w = World(layout)
        w.setSeed(desc.seed)
        w.timeLimit = desc.timeLimit

        // spawn (step 4 — the only LCG consumer, and only for SCATTER)
        val (ax, ay, bx, by) = when (desc.spawn) {
            SPAWN_SCATTER -> {
                val s = scatterSpawn(layout, w::nextRand)
                intArrayOf(s.first.x, s.first.y, s.second.x, s.second.y)
            }
            else -> {
                val (a, b) = layout.spawnCells(desc.spawn)
                intArrayOf(a.x, a.y, b.x, b.y)
            }
        }

        val dirA = facingToward(ax, ay, bx, by)
        val dirB = facingToward(bx, by, ax, ay)

        val a: Bot = if (desc.slotAOpen) {
            openA!!.reset(ax, ay, dirA, desc.hpA)
            openA
        } else Bot.fromBinary("A", 'A', Palette.RED, ax, ay, dirA, desc.fwA, desc.hpA)
        val b: Bot = if (desc.slotBOpen) {
            openB!!.reset(bx, by, dirB, desc.hpB)
            openB
        } else Bot.fromBinary("B", 'B', Palette.GREEN, bx, by, dirB, desc.fwB, desc.hpB)

        w.add(a); w.add(b)
        return w
    }

    /** Run to the end (1M-tick hard cap) and report the outcome. */
    fun run(desc: FightDescriptor, openA: Bot?, openB: Bot?): Outcome {
        val w = buildWorld(desc, openA, openB)
        while (!w.finished && w.tick < 1_000_000) w.tickOnce()
        val a = w.bots[0]
        val b = w.bots[1]
        val winnerSlot: Int
        val loserEndHp: Int
        when (w.winner) {
            a -> { winnerSlot = 0; loserEndHp = b.hp }
            b -> { winnerSlot = 1; loserEndHp = a.hp }
            else -> { winnerSlot = 2; loserEndHp = a.hp } // DRAW: slot A's HP
        }
        return Outcome(winnerSlot, w.tick, loserEndHp)
    }

    /**
     * Fixed facing convention: A and B face TOWARD each other from their
     * spawn cells. |dx| ≥ |dy| → E/W (dx > 0 → E), else S/N (dy > 0 → S).
     * Diagonal-adjacent and co-linear spawns are all covered; the SCATTER
     * generator never places both bots on the same cell.
     */
    fun facingToward(fx: Int, fy: Int, tx: Int, ty: Int): Int {
        val dx = tx - fx
        val dy = ty - fy
        return when {
            dx == 0 && dy == 0 -> DIR_E
            abs(dx) >= abs(dy) -> if (dx > 0) DIR_E else DIR_W
            else -> if (dy > 0) DIR_S else DIR_N
        }
    }
}
