package robofight

import robofight.assembler.assemble
import robofight.world.Arena
import robofight.world.Bot
import robofight.world.DIR_E
import robofight.world.DIR_N
import robofight.world.DIR_S
import robofight.world.DIR_W
import robofight.world.Layout
import robofight.world.Simulator
import robofight.world.SPAWN_CLASH
import robofight.world.SPAWN_SCATTER
import robofight.world.SPAWN_SEPARATE
import robofight.world.scatterSpawn
import robofight.world.World
import robofight.world.GRID
import robofight.world.MAX_HP
import robofight.world.Presets

/**
 * Arena engine tests (CHALLENGE-CODES.md §12.1 + the Stage-1 engine contract):
 * layout geometry, wall behaviors (§7.5), timed mode (§6), per-slot HP (§6),
 * SCATTER spawns (§7.6), the binary firmware path (§4.3), and the §12.3
 * fight matrix. Shares the global check()/passed/failed of Tests.kt.
 *
 * Terrain is two kinds: full-cell **blocks** (obstacles) and **edge walls**
 * (line segments between cells). Both block MOVE, absorb projectiles, and
 * register in AHEAD.
 */
fun main() {
    println("== Arena engine tests (challenge codes) ==")
    testGeometry()
    testWallBehavior()
    testTimedMode()
    testScatter()
    testPerSlotHp()
    testBinaryFirmware()
    testFightMatrix()
    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}

/** The five arenas' obstacle (block) view (§7.3) — the generated layouts must
 *  match cell-for-cell. Edge walls are asserted directly in testGeometry(). */
private val blockAscii: Map<String, String> = mapOf(
    "OPEN" to ("....................\n").repeat(20),
    "BLOCKS" to """
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
        ......##....##......
        ......##....##......
        ....................
        ....................
        ....................
        ....................
        ......##....##......
        ......##....##......
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
    """.trimIndent(),
    // RING has no blocks — its square is made of edge walls (asserted in geometry).
    "RING" to ("....................\n").repeat(20),
    "CROSS" to """
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
        .........##.........
        .........##.........
        .........##.........
        ......########......
        ......########......
        .........##.........
        .........##.........
        .........##.........
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
    """.trimIndent(),
    // MAZE's only blocks are the center 4×4; the perimeter + cross are edge walls.
    "MAZE" to """
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
        ........####........
        ........####........
        ........####........
        ........####........
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
        ....................
    """.trimIndent(),
)

/** §12.1 — every invariant of §7.2, per arena (blocks + edge walls). */
private fun testGeometry() {
    println("\n[layout geometry — §12.1]")
    val expectedBlocks = mapOf("OPEN" to 0, "BLOCKS" to 16, "RING" to 0, "CROSS" to 28, "MAZE" to 16)
    val expectedEdges = mapOf("OPEN" to 0, "BLOCKS" to 0, "RING" to 24, "CROSS" to 0, "MAZE" to 96)
    val layouts = Arena.all()
    check("registry has 5 free arenas in id order", layouts.map { it.id } == listOf(0, 1, 2, 3, 4),
        "got ${layouts.map { it.id }}")

    for (L in layouts) {
        val n = L.name
        check("$n: block count matches the spec", L.blockCount() == expectedBlocks.getValue(n),
            "got ${L.blockCount()}")
        check("$n: edge-wall count matches the spec", L.edgeCount() == expectedEdges.getValue(n),
            "got ${L.edgeCount()}")
        check("$n: grid sizes (blocks GRID², vert (GRID-1)·GRID, horiz GRID·(GRID-1))",
            L.blocks.size == GRID * GRID && L.vertEdge.size == (GRID - 1) * GRID && L.horizEdge.size == GRID * (GRID - 1))
        check("$n: matches the spec block ASCII cell-for-cell", L.ascii().trimEnd() == blockAscii.getValue(n).trimIndent(),
            "\n" + L.ascii())
        // Point symmetry about (9.5, 9.5): blocks, vertical edges, horizontal edges.
        var sym = true
        for (y in 0 until GRID) for (x in 0 until GRID)
            if (L.isBlock(x, y) != L.isBlock(GRID - 1 - x, GRID - 1 - y)) sym = false
        for (y in 0 until GRID) for (x in 0 until GRID - 1)
            if (L.vertEdge[y * (GRID - 1) + x] != L.vertEdge[(GRID - 1 - y) * (GRID - 1) + (GRID - 2 - x)]) sym = false
        for (y in 0 until GRID - 1) for (x in 0 until GRID)
            if (L.horizEdge[y * GRID + x] != L.horizEdge[(GRID - 2 - y) * GRID + (GRID - 1 - x)]) sym = false
        check("$n: point-symmetric about the center", sym)
        // Fully connected: edge-aware 4-neighbor walk from the perimeter reaches every free cell.
        val free = L.freeCells()
        val seeds = free.filter { it.x == 0 || it.x == GRID - 1 || it.y == 0 || it.y == GRID - 1 }
        val seen = java.util.HashSet<robofight.world.Cell>()
        val queue = java.util.ArrayDeque<robofight.world.Cell>()
        for (s in seeds) if (seen.add(s)) queue.add(s)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = c.x + dx; val ny = c.y + dy
                val nb = robofight.world.Cell(nx, ny)
                if (L.isFree(nx, ny) && !L.wallBetween(c.x, c.y, nx, ny) && seen.add(nb)) queue.add(nb)
            }
        }
        check("$n: fully connected (no sealed pockets)", seen.size == free.size,
            "reached ${seen.size} of ${free.size} free cells")
        // No dead ends: no free cell has exactly one reachable (unwalled, non-block) neighbor.
        var deadEnds = 0
        for (c in free) {
            val k = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).count { (dx, dy) ->
                val nx = c.x + dx; val ny = c.y + dy
                L.isFree(nx, ny) && !L.wallBetween(c.x, c.y, nx, ny)
            }
            if (k == 1) deadEnds++
        }
        check("$n: no dead-end free cells", deadEnds == 0, "$deadEnds dead ends")
        // Spawns (§7.2.4): free cells, CLASH pair adjacent
        val sp = L.spawnCells(SPAWN_SEPARATE)
        check("$n: SEPARATE spawns on free cells", L.isFree(sp.first.x, sp.first.y) && L.isFree(sp.second.x, sp.second.y))
        val cl = L.spawnCells(SPAWN_CLASH)
        check("$n: CLASH spawns on free cells, adjacent",
            L.isFree(cl.first.x, cl.first.y) && L.isFree(cl.second.x, cl.second.y) &&
                cl.first.manhattan(cl.second) == 1)
    }
}

/** §7.5 — blocks and edge walls block MOVE (silent no-op), stop projectiles, and register in AHEAD. */
private fun testWallBehavior() {
    println("\n[wall behavior — §7.5]")
    // BLOCKS: the 2×2 block at (6,6) — the cell (6,8) sits south of it facing N.
    val w = World(Arena.byId(1)!!)
    val b = Bot("A", 'A', 2, 6, 8, DIR_N, "WAIT\n")
    w.add(b)
    check("AHEAD=1 facing a block", w.aheadBlocked(b) == 1)
    b.move()
    check("MOVE into a block is a silent no-op", b.px == 6 && b.py == 8)
    b.facing = DIR_S
    check("AHEAD=0 on a free cell", w.aheadBlocked(b) == 0)
    b.move()
    check("MOVE onto a free cell works", b.px == 6 && b.py == 9)

    // RING: a shot fired at the ring from inside is absorbed at the edge wall;
    // through a gate it flies. Both bots exist up front so the world never
    // "finishes" on a single survivor (which would make later tickOnce() calls
    // no-ops and freeze the projectiles).
    val wr = World(Arena.byId(2)!!)
    val ba = Bot("A", 'A', 2, 8, 7, DIR_N, "loop: WAIT\nJMP loop\n")
    val bg = Bot("B", 'B', 4, 9, 7, DIR_N, "loop: WAIT\nJMP loop\n")
    wr.add(ba)
    wr.add(bg)
    wr.spawnProjectile(ba, 8, 6, DIR_N)          // (8,6): the top edge wall is just above (rows 5|6, x=8)
    check("projectile spawns on a free cell", wr.projectiles.size == 1)
    wr.tickOnce()
    check("projectile absorbed by the ring edge wall", wr.projectiles.isEmpty())
    wr.spawnProjectile(bg, 9, 6, DIR_N)          // (9,6): x=9 is a gate (no edge wall between rows 5|6)
    wr.tickOnce()
    check("projectile passes through the gate", wr.projectiles.size == 1 && wr.projectiles[0].y == 5,
        "n=${wr.projectiles.size} ${wr.projectiles.firstOrNull()}")

    // Firing straight into a block: the shot never materializes.
    val wf = World(Arena.byId(1)!!)
    val bs = Bot("S", 'S', 2, 6, 8, DIR_N, "WAIT\n")
    wf.add(bs)
    wf.spawnProjectile(bs, 6, 7, DIR_N)          // (6,7) is a BLOCKS block
    check("projectile cannot spawn on a block cell", wf.projectiles.isEmpty())

    // Firing straight into an edge wall: the shot never materializes either.
    val we = World(Arena.byId(2)!!)
    val bw = Bot("W", 'W', 2, 8, 6, DIR_N, "WAIT\n")
    we.add(bw)
    bw.shoot()                                    // (8,5) ahead is separated by the top ring edge wall
    check("shot into an edge wall never materializes", we.projectiles.isEmpty())

    // OPEN is unchanged: no terrain anywhere, edge still blocks.
    val wo = World(Arena.byId(0)!!)
    val be = Bot("E", 'E', 2, 0, 5, DIR_W, "WAIT\n")
    wo.add(be)
    check("OPEN has no terrain", wo.layout.blockCount() == 0 && wo.layout.edgeCount() == 0)
    check("OPEN: arena edge still registers in AHEAD", wo.aheadBlocked(be) == 1)
}

/** §6 — timed mode: highest HP wins at the limit, exact tie = DRAW, KO still wins early. */
private fun testTimedMode() {
    println("\n[timed mode — §6]")
    // Attrition: no shots, different HP.
    val w = World(Arena.byId(0)!!)
    w.timeLimit = 100
    w.add(Bot("A", 'A', 2, 2, 2, DIR_E, "loop: WAIT\nJMP loop\n", hp = 150))
    w.add(Bot("B", 'B', 4, 10, 10, DIR_W, "loop: WAIT\nJMP loop\n", hp = 100))
    while (!w.finished) w.tickOnce()
    check("timed fight ends exactly at the limit", w.finished && w.tick == 100, "tick=${w.tick}")
    check("highest HP wins", w.winner?.name == "A", "winner=${w.winner?.name}")

    // Exact tie → DRAW (winner stays null).
    val wt = World(Arena.byId(0)!!)
    wt.timeLimit = 100
    wt.add(Bot("A", 'A', 2, 2, 2, DIR_E, "loop: WAIT\nJMP loop\n", hp = 100))
    wt.add(Bot("B", 'B', 4, 10, 10, DIR_W, "loop: WAIT\nJMP loop\n", hp = 100))
    while (!wt.finished) wt.tickOnce()
    check("HP tie at the limit is a DRAW", wt.finished && wt.tick == 100 && wt.winner == null,
        "tick=${wt.tick} winner=${wt.winner?.name}")

    // KO before the limit still wins a timed fight.
    val wk = World(Arena.byId(0)!!)
    wk.timeLimit = 100
    wk.add(Bot("A", 'A', 2, 5, 5, DIR_E, "loop: SHOOT\nJMP loop\n"))
    wk.add(Bot("B", 'B', 4, 6, 5, DIR_W, "loop: WAIT\nJMP loop\n", hp = 10))
    while (!wk.finished) wk.tickOnce()
    check("KO ends a timed fight early", wk.finished && wk.tick < 100 && wk.winner?.name == "A",
        "tick=${wk.tick} winner=${wk.winner?.name}")

    // Simulator pass-through.
    val wf = Simulator.runFight(
        listOf(Bot("A", 'A', 2, 2, 2, DIR_E, "WAIT\n", hp = 150), Bot("B", 'B', 4, 10, 10, DIR_W, "WAIT\n", hp = 100)),
        maxTicks = 100, layout = Arena.byId(4)!!, timeLimit = 100,
    )
    check("Simulator.runFight passes layout+timeLimit through", wf.finished && wf.tick == 100 && wf.winner?.name == "A",
        "tick=${wf.tick} winner=${wf.winner?.name}")
}

/** §7.6 — SCATTER is deterministic from the seed and draws far-apart free cells. */
private fun testScatter() {
    println("\n[scatter spawn — §7.6]")
    val maze = Arena.byId(4)!!
    val w1 = World(maze); w1.setSeed(42)
    val w2 = World(maze); w2.setSeed(42)
    val (a1, b1) = scatterSpawn(maze, w1::nextRand)
    val (a2, b2) = scatterSpawn(maze, w2::nextRand)
    check("SCATTER is deterministic from the seed", a1 == a2 && b1 == b2, "($a1,$b1) vs ($a2,$b2)")
    check("SCATTER draws free cells", maze.isFree(a1.x, a1.y) && maze.isFree(b1.x, b1.y))
    check("SCATTER draws distinct cells", a1 != b1)

    val open = Arena.byId(0)!!
    var minDist = Int.MAX_VALUE
    for (seed in 0 until 200) {
        val w = World(open); w.setSeed(seed)
        val (a, b) = scatterSpawn(open, w::nextRand)
        minDist = minOf(minDist, a.manhattan(b))
    }
    check("SCATTER keeps pairs >= 6 apart on OPEN (200 seeds)", minDist >= 6, "min dist=$minDist")
}

/** §6 — HP is an independent per-slot dial: 50/100/150/200. */
private fun testPerSlotHp() {
    println("\n[per-slot HP — §6]")
    val b50 = Bot("A", 'A', 2, 5, 5, DIR_E, "WAIT\n", hp = 50)
    check("HP=50 slot starts at 50", b50.hp == 50 && b50.startHp == 50)
    repeat(5) { b50.applyHit(10) }
    check("HP=50 slot dies after 5 hits", b50.hp == 0 && !b50.alive)
    val b200 = Bot("B", 'B', 4, 5, 5, DIR_E, "WAIT\n", hp = 200)
    check("HP=200 slot starts at 200 (not clamped)", b200.hp == 200)
    b200.applyHit(100)
    b200.reset(6, 6, DIR_S)
    check("reset restores the slot's configured HP", b200.hp == 200 && b200.alive)
    val default = Bot("C", 'C', 6, 5, 5, DIR_E, "WAIT\n")
    check("default HP stays 100 for existing callers", default.hp == MAX_HP)
}

/** §4.3 — the binary path: the assembler's output image, loaded verbatim, behaves identically. */
private fun testBinaryFirmware() {
    println("\n[binary firmware — §4.3]")
    val src = Presets.HUNTER
    val res = assemble(src)
    check("HUNTER assembles", res.ok)

    val ws = World(Arena.byId(0)!!)
    ws.add(Bot("SRC-A", 'H', 2, 2, 2, DIR_E, src))
    ws.add(Bot("SRC-B", 'T', 4, 15, 15, DIR_W, src))
    val wb = World(Arena.byId(0)!!)
    wb.add(Bot.fromBinary("BIN-A", 'H', 2, 2, 2, DIR_E, res.code))
    wb.add(Bot.fromBinary("BIN-B", 'T', 4, 15, 15, DIR_W, res.code))
    for (i in 0 until 500) {
        if (ws.finished || wb.finished) break
        ws.tickOnce()
        wb.tickOnce()
    }
    check("binary fight stays in lockstep with the source fight", ws.tick == wb.tick,
        "src tick=${ws.tick} bin tick=${wb.tick}")
    check("binary bots stay in the same cells",
        ws.bots[0].px == wb.bots[0].px && ws.bots[0].py == wb.bots[0].py &&
            ws.bots[1].px == wb.bots[1].px && ws.bots[1].py == wb.bots[1].py)
    check("binary fight has the same outcome",
        (ws.winner?.name ?: "DRAW") == (wb.winner?.name ?: "DRAW"))

    val img = res.code
    val restored = Bot.fromBinary("R", 'H', 2, 3, 3, DIR_E, img)
    restored.reset(4, 4, DIR_S)
    var same = true
    for (i in img.indices) if (restored.vm.mem[robofight.isa.ISA.PROG_BASE + i].toInt() and 0xFF != img[i].toInt() and 0xFF) { same = false; break }
    check("reset() of a binary bot reloads the binary image", same)

    check("non-instruction-multiple image rejected",
        runCatching { Bot.fromBinary("X", 'X', 2, 3, 3, DIR_E, ByteArray(4)) }.isFailure)
    check("oversized image rejected (program limit)",
        runCatching { Bot.fromBinary("X", 'X', 2, 3, 3, DIR_E, ByteArray(768 + 3)) }.isFailure)
}

/** §12.3 — every arena passes the 5×5 preset matrix, DUEL and MELEE: each
 *  fight resolves by KO or timed-out within the 2,000-tick budget (the
 *  1,000,000-tick cap must never end a shipped fight). */
private fun testFightMatrix() {
    println("\n[fight matrix — §12.3]")
    val fights = FightMatrix.runAll()
    check("matrix runs 250 fights (5 arenas × 2 modes × 25 pairs)", fights.size == 250, "got ${fights.size}")
    check("every fight ends within the ${FightMatrix.BUDGET}-tick budget (KO or timed-out)",
        fights.all { it.ticks <= FightMatrix.BUDGET },
        fights.filter { it.ticks > FightMatrix.BUDGET }
            .joinToString("\n") { "  ${it.arenaName} ${it.mode}: ${it.a} vs ${it.b} @ tick ${it.ticks}" })
    check("every fight has a verdict (KO, timed-out, or double-KO draw)",
        fights.all { it.winner.isNotEmpty() })
    val kos = fights.count { !it.timedOut }
    println("  matrix: KO=$kos timed-out=${fights.size - kos} (budget ${FightMatrix.BUDGET})")
}
