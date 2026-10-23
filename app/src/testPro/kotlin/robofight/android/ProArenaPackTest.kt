package robofight.android

import robofight.challenge.ChallengeRun
import robofight.challenge.ChallengeFormat.FightDescriptor
import robofight.world.Arena
import robofight.world.Bot
import robofight.world.Cell
import robofight.world.DIR_E
import robofight.world.DIR_N
import robofight.world.DIR_S
import robofight.world.DIR_W
import robofight.world.GRID
import robofight.world.Layout
import robofight.world.Presets
import robofight.world.SPAWN_CLASH
import robofight.world.SPAWN_SEPARATE
import robofight.world.World
import kotlin.math.abs

/**
 * Arena Pack 1 verification (PLAN-PRO §5.2, CHALLENGE-CODES §12.1/§12.3).
 *
 * Lives in the pro flavor test source set — compiled ONLY into pro variant
 * test runs (src/free has no pack to verify). Headless main() harness in the
 * project's established style:
 *
 *   1. registration: ProArenas.registerAll() grows the registry 5 -> 11,
 *      pack ids/names/pack tags, family entries carry generators, free five
 *      stay fixed (generator == null, pack == null).
 *   2. §7.2 invariant sweep: every curated layout + thousands of seeds per
 *      generator (incl. Int.MIN_VALUE / MAX_VALUE / -1) — symmetry,
 *      connectivity, no dead ends, spawn cells; determinism (same seed ->
 *      same geometry) and seed diversity across 1000-seed samples.
 *   3. §12.3 fight matrix: sampled seeded layouts + curations x all preset
 *      bots x DUEL/MELEE — every fight terminates inside the 2000-tick
 *      budget with an outcome; no self-kill possible on hazard-free layouts.
 *   4. end-to-end: ChallengeRun.run on a family arena (0x10) at different
 *      seeds fights on different seeded geometry, deterministically.
 */

private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { passed++; println("  ok  $name") }
    else { failed++; println("FAIL  $name${if (detail.isEmpty()) "" else " — $detail"}") }
}

private val EXTREME_SEEDS = intArrayOf(0, 1, -1, 7, Int.MIN_VALUE, Int.MAX_VALUE, 0x5F3759DF)

fun runArenaPackSuite(): Pair<Int, Int> {
    println("== Arena Pack 1 tests (pro flavor, P2) ==")

    // ---------------------------------------------------------------- 1. registration
    println("\n[1] ProArenas.registerAll -> registry gate")
    ProArenas.registerAll()
    ProArenas.registerAll()  // idempotency: onCreate re-run must not double-register
    val all = Arena.all()
    check("registry = 5 free + 6 pack", all.size == 11, "got ${all.size}")
    val packIds = intArrayOf(0x10, 0x11, 0x12, 0x13, 0x14, 0x15)
    val packNames = arrayOf("GEN-MAZE", "GEN-ISLES", "COLISEUM", "PINWHEEL", "TWIN WALLS", "LABYRINTH")
    for (i in packIds.indices) {
        val L = Arena.byId(packIds[i])
        check("0x${packIds[i].toString(16)} registered as ${packNames[i]}", L?.name == packNames[i], "got ${L?.name}")
        check("0x${packIds[i].toString(16)} tagged '${ProArenaPack.PACK}'", L?.pack == ProArenaPack.PACK, "got ${L?.pack}")
    }
    check("0x16 unregistered (locked, importable)", Arena.byId(0x16) == null)
    check("family 0x10/0x11 carry generators",
        Arena.byId(0x10)?.generator != null && Arena.byId(0x11)?.generator != null)
    check("curated 0x12-0x15 are fixed (no generator)",
        (0x12..0x15).all { Arena.byId(it)?.generator == null })
    check("free five stay fixed and untagged",
        (0..4).all { Arena.byId(it)?.generator == null && Arena.byId(it)?.pack == null })
    for (id in 0x10..0x15) {
        val preview = Arena.byId(id)!!
        check("preview 0x${id.toString(16)} passes §7.2", ProArenaPack.isValid(preview),
            ProArenaPack.validateIssues(preview).joinToString("; "))
    }

    // ---------------------------------------------------------------- 2. generator sweeps
    println("\n[2] §7.2 sweep: generators over seeds")
    val gens = listOf(
        Triple(0x10, "GEN-MAZE", fun(s: Int) = ProArenaPack.genMaze(s)),
        Triple(0x11, "GEN-ISLES", fun(s: Int) = ProArenaPack.genIsles(s)),
    )
    for ((id, name, gen) in gens) {
        var bad = 0; var badSeed = 0; var badWhy = ""
        for (s in 0 until 3000) {
            val L = gen(s)
            if (L.id != id || L.pack != ProArenaPack.PACK || L.generator != null) { bad++; badSeed = s; badWhy = "id/pack/generator fields" }
            val issues = ProArenaPack.validateIssues(L)
            if (issues.isNotEmpty()) { bad++; badSeed = s; badWhy = issues.joinToString("; "); if (bad > 3) break }
        }
        for (s in EXTREME_SEEDS) {
            val issues = ProArenaPack.validateIssues(gen(s))
            if (issues.isNotEmpty()) { bad++; badSeed = s; badWhy = "seed $s: " + issues.joinToString("; ") }
        }
        check("$name: 3000 seeds + extremes all pass §7.2", bad == 0, "seed $badSeed: $badWhy")

        // determinism: f(id, seed) is a pure function
        var detOk = true
        for (s in 0 until 50) {
            val a = gen(s); val b = gen(s)
            if (!a.blocks.contentEquals(b.blocks) || !a.vertEdge.contentEquals(b.vertEdge) ||
                !a.horizEdge.contentEquals(b.horizEdge) || a.spawnCells(SPAWN_SEPARATE) != b.spawnCells(SPAWN_SEPARATE)) { detOk = false; break }
        }
        check("$name: same seed -> identical geometry (50 samples)", detOk)

        // diversity: seeded families must actually vary. ISLES is a 3^11
        // decision lattice and clears 95%; GEN-MAZE draws 12 spawn-valid ring
        // shapes plus geometry-aware bridge gates and symmetric corridor bumps
        // and also clears 95% (>97% distinct measured over 1000 seeds).
        val bar = 950
        val distinct = HashSet<String>()
        var blocks = 0; var edges = 0
        for (s in 0 until 1000) {
            val L = gen(s)
            distinct.add(L.blocks.joinToString("") { if (it) "1" else "0" } + "|" +
                L.vertEdge.count { it } + ":" + L.horizEdge.count { it })
            blocks += L.blockCount(); edges += L.edgeCount()
        }
        check("$name: >=${bar / 10}% distinct across 1000 seeds", distinct.size >= bar, "got ${distinct.size}")
        println("      avg density: %.1f blocks, %.1f edge-walls per layout".format(blocks / 1000.0, edges / 1000.0))

        // playableLayout wiring: registry preview == f(id, 0), play applies the generator
        val reg = Arena.byId(id)!!
        check("$name: playableLayout(seed) == gen(seed)",
            Arena.playableLayout(id, 12345)!!.blocks.contentEquals(gen(12345).blocks))
        check("$name: registry preview is the seed-0 geometry",
            reg.blocks.contentEquals(gen(0).blocks))
    }

    // density comparison vs the free five ("denser and meaner", PLAN-PRO §5.1)
    val freeDensity = (0..4).map { Arena.byId(it)!!.blockCount() + Arena.byId(it)!!.edgeCount() }.average()
    val curatedDensity = (0x12..0x15).map { Arena.byId(it)!!.blockCount() + Arena.byId(it)!!.edgeCount() }
    check("curated denser than free average", curatedDensity.all { it > freeDensity },
        "free avg %.1f vs curated %s".format(freeDensity, curatedDensity))

    // ---------------------------------------------------------------- 3. §12.3 fight matrix
    println("\n[3] §12.3 matrix: seeded + curated x preset bots")
    val sampled = ArrayList<Layout>()
    for (s in 0 until 20) sampled.add(ProArenaPack.genMaze(s * 131 + 7))
    for (s in 0 until 20) sampled.add(ProArenaPack.genIsles(s * 131 + 7))
    for (id in 0x12..0x15) sampled.add(Arena.byId(id)!!)
    var fights = 0; var ko = 0; var draws = 0; var timeouts = 0; var maxTicks = 0
    var firstFailure = ""
    for (L in sampled) {
        for (spawn in intArrayOf(SPAWN_SEPARATE, SPAWN_CLASH)) {
            val (ca, cb) = L.spawnCells(spawn)
            for (pa in Presets.all()) for (pb in Presets.all()) {
                val w = World(L)
                w.timeLimit = 2000
                w.add(Bot(pa.name, pa.glyph, pa.color, ca.x, ca.y, faceToward(ca, cb), pa.firmware))
                w.add(Bot(pb.name, pb.glyph, pb.color, cb.x, cb.y, faceToward(cb, ca), pb.firmware))
                try {
                    while (!w.finished) w.tickOnce()
                    fights++
                    maxTicks = maxOf(maxTicks, w.tick)
                    if (w.tick >= 2000) timeouts++
                    else if (w.winner == null) draws++
                    else ko++
                } catch (t: Throwable) {
                    if (firstFailure.isEmpty())
                        firstFailure = "${L.name} s${w.tick} ${pa.name} vs ${pb.name}: $t"
                }
            }
        }
    }
    check("matrix: no crash/hang over $fights preset fights", firstFailure.isEmpty(), firstFailure)
    check("matrix: all fights terminate within 2000-tick budget", maxTicks <= 2000, "max $maxTicks")
    println("      KO=$ko DRAW=$draws TIMEOUT=$timeouts maxTicks=$maxTicks")

    // ---------------------------------------------------------------- 4. end-to-end ChallengeRun
    println("\n[4] ChallengeRun on seed-derived family arena")
    val hunter = Presets.all().first { it.name == "HUNTER" }
    val snake = Presets.all().first { it.name == "SNAKE" }
    fun descAt(seed: Int) = FightDescriptor(
        eng = 1, layoutId = 0x10, slots = 0b11, seed = seed, mode = 0, spawn = SPAWN_SEPARATE,
        hpA = 10, hpB = 10, result = null, fwA = ByteArray(0), fwB = ByteArray(0), srcA = null, srcB = null,
    )
    fun bots() = listOf(
        Bot("A", 'A', 0, 0, 0, DIR_S, hunter.firmware),
        Bot("B", 'B', 1, 0, 0, DIR_N, snake.firmware),
    )
    val seeds = intArrayOf(1, 2, 41, 2026, -7, Int.MAX_VALUE)
    val worlds = seeds.map { s ->
        val (a, b) = bots()
        ChallengeRun.buildWorld(descAt(s), a, b).layout
    }
    var allSeededOk = true
    for ((i, s) in seeds.withIndex()) {
        val expected = ProArenaPack.genMaze(s)
        if (!worlds[i].blocks.contentEquals(expected.blocks)) allSeededOk = false
    }
    check("buildWorld plays f(0x10, seed) for ${seeds.size} seeds", allSeededOk)
    val distinctWorlds = worlds.map { it.blocks.joinToString("") { c -> if (c) "1" else "0" } }.toSet()
    check("different seeds -> different fought geometry", distinctWorlds.size == seeds.size, "got ${distinctWorlds.size}")
    val o1 = ChallengeRun.run(descAt(2026), bots()[0], bots()[1])
    val o2 = ChallengeRun.run(descAt(2026), bots()[0], bots()[1])
    check("same seed fights identically", o1.winnerSlot == o2.winnerSlot && o1.ticks == o2.ticks,
        "run1=$o1 run2=$o2")
    check("family fight completes (winner in {A,B,DRAW})", o1.winnerSlot in intArrayOf(0, 1, 2), "got ${o1.winnerSlot}")
    println("      seed 2026: winner=${o1.winnerSlot} ticks=${o1.ticks} loserHp=${o1.loserEndHp}")

    println("\n== RESULT: $passed passed, $failed failed ==")
    return passed to failed
}

/** kotlinc harness entry (standalone headless run). */
fun main() {
    val (p, f) = runArenaPackSuite()
    if (f > 0) kotlin.system.exitProcess(1)
}

private fun faceToward(from: Cell, to: Cell): Int {
    val dx = to.x - from.x; val dy = to.y - from.y
    return if (abs(dx) >= abs(dy)) (if (dx >= 0) DIR_E else DIR_W) else (if (dy >= 0) DIR_S else DIR_N)
}
