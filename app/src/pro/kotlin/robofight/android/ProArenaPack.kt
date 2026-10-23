package robofight.android

import robofight.world.Arena
import robofight.world.Cell
import robofight.world.GRID
import robofight.world.Layout
import robofight.world.SPAWN_CLASH
import robofight.world.SPAWN_SEPARATE
import java.util.ArrayDeque
import java.util.HashSet
import kotlin.math.min

/**
 * ARENA PACK 1 (PLAN-PRO §5, CHALLENGE-CODES §7.1/§7.4, amendment A1) —
 * the paid app's content for "Opcode Arena: The Circuit".
 *
 * Lives ONLY in `src/pro` (PLAN-PRO §1.1): the free APK does not contain this
 * class at all — the compile-time tier gate is the flavor source set
 * (src/free carries the no-op ProArenas twin). Registration goes exclusively
 * through [Arena.registerPro] (hard range 0x10–0x1F) from ProArenas.registerAll.
 *
 * Families (§7.4 seed-derived):
 *   0x10 GEN-MAZE   nested 2-wide ring loops, seeded insets + seeded 2-wide
 *                   bridges punched strictly through each wall band's frame,
 *                   symmetric 2-cell corridor bumps, open central pocket. Loop
 *                   topology by construction: no recursive maze carving.
 *   0x11 GEN-ISLES  point-symmetric block isles on a 5×5 macro lattice with
 *                   guaranteed 1-cell channel rows/cols; reserved open corners
 *                   (SEPARATE spawns) and an always-open central 4×4 duel zone.
 * Both are pure `geometry = f(id, seed)` functions: SplitMix64 over the fight
 * seed only — no clock, no hidden RNG, no world-LCG consumption. A deterministic
 * seed-remap repair loop re-derives until the §7.2 invariants pass (PLAN-PRO §5.2).
 *
 * Curated (§5.1, denser and meaner than the free five):
 *   0x12 COLISEUM   double edge-wall ring + central cross — gate duels.
 *   0x13 PINWHEEL   rotational block arms + central slab — flanking lanes.
 *   0x14 TWIN WALLS two gated block walls, three lanes — lane commitment.
 *   0x15 LABYRINTH  two nested edge-wall circuits with corner-offset gates
 *                   + central slab — MAZE's meaner older brother.
 *
 * Every layout (generated or curated) is verified here against the §7.2
 * invariants: point symmetry, full connectivity (no sealed pockets), zero
 * dead ends, free spawns, CLASH adjacency. ProArenaPackTest sweeps thousands
 * of seeds per generator plus the §12.3 fight matrix.
 */
object ProArenaPack {

    /** Display-only grouping tag carried on every pack layout (Layout.pack). */
    const val PACK = "ARENA PACK 1"

    // =====================================================================
    // Pure seed stream — SplitMix64. The ONLY entropy is the fight seed.
    // =====================================================================

    private const val GOLDEN = -0x61C8864680B583B7L  // 0x9E3779B97F4A7C15 as signed Long

    private fun mix64(z0: Long): Long {
        var z = z0
        z = (z xor (z ushr 30)) * -0x41c9e5a9bcb14f4cL  // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b4a4b7f1aff7d55L  // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Local, pure PRNG stream seeded from (layout id, fight seed, repair attempt). */
    private class Stream(seedBits: Long) {
        private var s = seedBits
        fun next(): Long { s += GOLDEN; return mix64(s) }
        /** Uniform 0 until bound (bound > 0). */
        fun int(bound: Int): Int { val r = next() % bound; return if (r < 0) (r + bound).toInt() else r.toInt() }
    }

    private fun streamFor(id: Int, seed: Int, attempt: Int): Stream =
        Stream(mix64((seed.toLong() and 0xFFFFFFFFL) + GOLDEN * (id + 1) + attempt * 0x2545F4914F6CDD1DL))

    // =====================================================================
    // §7.2 invariant checker (mirrors ArenaTests' §12.1 checks).
    // =====================================================================

    /** True when [L] satisfies every §7.2 invariant (symmetry, connectivity, no dead ends, spawns). */
    fun isValid(L: Layout): Boolean = validateIssues(L).isEmpty()

    /** The §7.2 invariant report: empty list = valid; otherwise one line per violated invariant. */
    fun validateIssues(L: Layout): List<String> {
        val issues = ArrayList<String>()

        var sym = true
        for (y in 0 until GRID) for (x in 0 until GRID)
            if (L.isBlock(x, y) != L.isBlock(GRID - 1 - x, GRID - 1 - y)) sym = false
        for (y in 0 until GRID) for (x in 0 until GRID - 1)
            if (L.vertEdge[y * (GRID - 1) + x] != L.vertEdge[(GRID - 1 - y) * (GRID - 1) + (GRID - 2 - x)]) sym = false
        for (y in 0 until GRID - 1) for (x in 0 until GRID)
            if (L.horizEdge[y * GRID + x] != L.horizEdge[(GRID - 2 - y) * GRID + (GRID - 1 - x)]) sym = false
        if (!sym) issues.add("not point-symmetric")

        val free = L.freeCells()
        val seeds = free.filter { it.x == 0 || it.x == GRID - 1 || it.y == 0 || it.y == GRID - 1 }
        val seen = HashSet<Cell>()
        val queue = ArrayDeque<Cell>()
        for (s in seeds) if (seen.add(s)) queue.add(s)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = c.x + dx; val ny = c.y + dy
                val nb = Cell(nx, ny)
                if (L.isFree(nx, ny) && !L.wallBetween(c.x, c.y, nx, ny) && seen.add(nb)) queue.add(nb)
            }
        }
        if (seen.size != free.size) issues.add("not fully connected (reached ${seen.size}/${free.size})")

        for (c in free) {
            val k = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).count { (dx, dy) ->
                L.isFree(c.x + dx, c.y + dy) && !L.wallBetween(c.x, c.y, c.x + dx, c.y + dy)
            }
            if (k == 1) { issues.add("dead end at (${c.x},${c.y})"); break }
        }

        val sp = L.spawnCells(SPAWN_SEPARATE)
        if (!L.isFree(sp.first.x, sp.first.y) || !L.isFree(sp.second.x, sp.second.y))
            issues.add("SEPARATE spawn on block")
        val cl = L.spawnCells(SPAWN_CLASH)
        if (!L.isFree(cl.first.x, cl.first.y) || !L.isFree(cl.second.x, cl.second.y) ||
            cl.first.manhattan(cl.second) != 1) issues.add("CLASH spawn blocked or not adjacent")

        return issues
    }

    // =====================================================================
    // 0x10 GEN-MAZE — nested ring loops, seeded bridges. Deterministic.
    // =====================================================================

    fun genMaze(seed: Int): Layout {
        for (attempt in 0 until 32) {
            val L = deriveMaze(seed, attempt)
            if (isValid(L)) return L
        }
        error("GEN-MAZE: no valid derivation for seed $seed after 32 remaps")
    }

    private fun deriveMaze(seed: Int, attempt: Int): Layout {
        val st = streamFor(0x10, seed, attempt)
        val n = 5 + st.int(3)                      // inner ring inset 5..7
        val t = 1 + st.int(8 - n)                  // inner band n..n+t; n+t <= 8 keeps the CLASH
                                                   // cells (ring distance 9) free: valid by construction
        val m = 3 + st.int(n - 4)                  // middle ring inset 3..n-2, gap >= 2

        val b = BooleanArray(GRID * GRID)
        val free = fun(x: Int, y: Int): Boolean {
            val d = minOf(x, GRID - 1 - x, y, GRID - 1 - y)   // ring distance
            return d <= 1 || (d in m..m + 1) || (d in n..n + t) || d >= n + t + 1
        }
        for (y in 0 until GRID) for (x in 0 until GRID) if (!free(x, y)) b[y * GRID + x] = true

        // Bridges: full-depth gate rectangles punched through one wall band,
        // added as point-symmetric pairs (top+bottom or left+right), so symmetry
        // holds by construction. Offsets are constrained to the band's own frame
        // columns/rows (geometry-aware): every punch lands on real blocks, never
        // on free air, so each draw contributes distinct geometry instead of a
        // no-op convergence onto the few safe positions.
        fun bridge(lo: Int, hi: Int) {
            if (hi < lo) return
            val pairs = 1 + st.int(3)              // 1..3 bridge pairs per band
            val startVertical = st.int(2) == 0
            for (p in 0 until pairs) {
                val vertical = (p % 2 == 0) == startVertical   // sides alternate -> distinct punches
                val width = 2 + st.int(2)          // 2- or 3-wide gate — bigger variation per seed
                val span = 21 - 2 * lo - width     // gate positions fully inside frame [lo, GRID-1-lo]
                if (span <= 0) continue
                val off = lo + st.int(span)
                for (d in lo..hi) {
                    val cells = (0 until width).map { o ->
                        if (vertical) Cell(off + o, d) else Cell(d, off + o)
                    }
                    for (c in cells) {
                        b[c.y * GRID + c.x] = false
                        val mx = GRID - 1 - c.x; val my = GRID - 1 - c.y
                        b[my * GRID + mx] = false  // point mirror
                    }
                }
            }
        }
        bridge(2, m - 1)       // outer ring <-> middle ring
        bridge(m + 2, n - 1)   // middle ring <-> inner ring (empty band when n == m+2)

        // Bumps: seeded 2-cell block stubs hugging the corridor walls, added as
        // point-symmetric pairs. They sit strictly inside the corridor span (one
        // cell clear of the corridor corners) so every remaining free neighbor
        // keeps >= 2 open neighbors — a new, independent shape dimension on top
        // of the ring/bridge draws.
        if (st.int(100) < 65) {                    // outer flank: row m against the outer wall
            val x0 = m + 1 + st.int(17 - 2 * m)    // x0..x0+1 within [m+1, 17-m]
            for (o in 0 until 2) {
                b[m * GRID + (x0 + o)] = true
                b[(GRID - 1 - m) * GRID + (GRID - 1 - x0 - o)] = true
            }
        }
        if (n >= m + 3 && st.int(100) < 55) {      // inner flank: row m+1 against the inner wall
            val x0 = m + 2 + st.int(15 - 2 * m)    // x0..x0+1 within [m+2, 16-m]
            for (o in 0 until 2) {
                b[(m + 1) * GRID + (x0 + o)] = true
                b[(GRID - 2 - m) * GRID + (GRID - 1 - x0 - o)] = true
            }
        }

        return Layout(
            id = 0x10, name = "GEN-MAZE",
            blocks = b,
            vertEdge = BooleanArray((GRID - 1) * GRID),
            horizEdge = BooleanArray(GRID * (GRID - 1)),
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(9, 9), clashB = Cell(10, 9),
            pack = PACK,
        )
    }

    // =====================================================================
    // 0x11 GEN-ISLES — seeded isles on a macro lattice with channel rows/cols.
    // =====================================================================

    fun genIsles(seed: Int): Layout {
        for (attempt in 0 until 32) {
            val L = deriveIsles(seed, attempt)
            if (isValid(L)) return L
        }
        error("GEN-ISLES: no valid derivation for seed $seed after 32 remaps")
    }

    private fun deriveIsles(seed: Int, attempt: Int): Layout {
        val st = streamFor(0x11, seed, attempt)
        val b = BooleanArray(GRID * GRID)

        fun addIsle(mx: Int, my: Int, w: Int, h: Int, ox: Int, oy: Int) {
            for (dy in 0 until h) for (dx in 0 until w) {
                val c = Cell(4 * mx + ox + dx, 4 * my + oy + dy)
                b[c.y * GRID + c.x] = true
                val rx = GRID - 1 - c.x; val ry = GRID - 1 - c.y
                b[ry * GRID + rx] = true           // point mirror island
            }
        }

        // 5×5 macro lattice, point-symmetric pair iteration. Macro (2,2) — the
        // central 4×4 — is always empty (CLASH spawn zone, open duel floor);
        // macros (0,0)/(4,4) stay clear (SEPARATE spawn corners). Isles cover
        // macro-local cols/rows 0..2 only, so channel columns/rows 3,7,11,15,19
        // are always free — connectivity and neighbor counts hold by construction.
        for (my in 0 until 5) for (mx in 0 until 5) {
            if (mx > 4 - mx || (mx == 4 - mx && my >= 4 - my)) continue  // second half of each pair
            if (mx == 0 && my == 0) continue                               // reserved corner pair
            val edgeX = mx == 0 || mx == 4
            val edgeY = my == 0 || my == 4
            when (st.int(100)) {
                in 0 until 30 -> Unit                                      // empty macro
                in 30 until 70 -> {                                       // 2×2 isle, seeded offset
                    val ox = if (edgeY) 0 else st.int(2)
                    val oy = if (edgeX) 0 else st.int(2)
                    addIsle(mx, my, 2, 2, ox, oy)
                }
                else -> if (!edgeX && !edgeY) addIsle(mx, my, 3, 3, 0, 0) // 3×3 isle: interior only
                         else addIsle(mx, my, 2, 2, 0, 0)                 // edge fallback (deterministic)
            }
        }

        return Layout(
            id = 0x11, name = "GEN-ISLES",
            blocks = b,
            vertEdge = BooleanArray((GRID - 1) * GRID),
            horizEdge = BooleanArray(GRID * (GRID - 1)),
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(9, 10), clashB = Cell(10, 10),
            pack = PACK,
        )
    }

    // =====================================================================
    // 0x12 COLISEUM — double edge-wall ring + central cross. Gate duels.
    // =====================================================================

    private fun coliseum(): Layout {
        val b = BooleanArray(GRID * GRID)
        val v = BooleanArray((GRID - 1) * GRID)
        val h = BooleanArray(GRID * (GRID - 1))
        // Outer ring: boundary lines of box x2..17 × y2..17, 2-wide gates at the
        // middle of each side (x∈{9,10} top/bottom, y∈{9,10} left/right).
        for (x in 2..17) if (x !in 9..10) { h[1 * GRID + x] = true; h[17 * GRID + x] = true }
        for (y in 2..17) if (y !in 9..10) { v[y * (GRID - 1) + 1] = true; v[y * (GRID - 1) + 17] = true }
        // Inner ring: boundary of box x6..13 × y6..13, same centered gates.
        for (x in 6..13) if (x !in 9..10) { h[5 * GRID + x] = true; h[13 * GRID + x] = true }
        for (y in 6..13) if (y !in 9..10) { v[y * (GRID - 1) + 5] = true; v[y * (GRID - 1) + 13] = true }
        // Central cross: vertical bar x∈{9,10} y7..12 + horizontal bar y∈{9,10} x7..12.
        for (x in 9..10) for (y in 7..12) b[y * GRID + x] = true
        for (y in 9..10) for (x in 7..12) b[y * GRID + x] = true
        return Layout(
            id = 0x12, name = "COLISEUM",
            blocks = b, vertEdge = v, horizEdge = h,
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(6, 9), clashB = Cell(6, 10),   // inner west gate mouth
            pack = PACK,
        )
    }

    // =====================================================================
    // 0x13 PINWHEEL — rotational block arms + central slab. Flanking lanes.
    // =====================================================================

    private fun pinwheel(): Layout {
        val b = BooleanArray(GRID * GRID)
        fun rect(x0: Int, y0: Int, x1: Int, y1: Int) {
            for (y in y0..y1) for (x in x0..x1) {
                b[y * GRID + x] = true
                b[(GRID - 1 - y) * GRID + (GRID - 1 - x)] = true   // point mirror
            }
        }
        rect(6, 4, 9, 5)     // small top-left arm   (+ bottom-right mirror)
        rect(14, 6, 17, 9)   // big right sail       (+ left sail mirror)
        rect(9, 9, 10, 10)   // central slab (self-symmetric)
        return Layout(
            id = 0x13, name = "PINWHEEL",
            blocks = b,
            vertEdge = BooleanArray((GRID - 1) * GRID),
            horizEdge = BooleanArray(GRID * (GRID - 1)),
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(7, 9), clashB = Cell(7, 10),   // pocket west of the slab
            pack = PACK,
        )
    }

    // =====================================================================
    // 0x14 TWIN WALLS — two gated block walls, three lanes. Lane commitment.
    // =====================================================================

    private fun twinWalls(): Layout {
        val b = BooleanArray(GRID * GRID)
        for (y in 3..16) {
            if (y in 5..6 || y in 13..14) continue     // gates: the mirror pair is consistent
            b[y * GRID + 5] = true; b[y * GRID + 6] = true      // left wall
            b[y * GRID + 13] = true; b[y * GRID + 14] = true    // mirror wall
        }
        return Layout(
            id = 0x14, name = "TWIN WALLS",
            blocks = b,
            vertEdge = BooleanArray((GRID - 1) * GRID),
            horizEdge = BooleanArray(GRID * (GRID - 1)),
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(9, 9), clashB = Cell(10, 9),   // open center lane
            pack = PACK,
        )
    }

    // =====================================================================
    // 0x15 LABYRINTH — two nested edge-wall circuits, corner-offset gates,
    // central slab. MAZE's meaner older brother: longer loops.
    // =====================================================================

    private fun labyrinth(): Layout {
        val b = BooleanArray(GRID * GRID)
        val v = BooleanArray((GRID - 1) * GRID)
        val h = BooleanArray(GRID * (GRID - 1))
        // Outer circuit: boundary of box x2..17 × y2..17. Gates sit at the FAR
        // corners instead of the middles: top x∈{4,5} mirrors to bottom x∈{14,15},
        // left y∈{4,5} mirrors to right y∈{14,15} — long way around, every time.
        for (x in 2..17) if (x !in 4..5 && x !in 14..15) { h[1 * GRID + x] = true; h[17 * GRID + x] = true }
        for (y in 2..17) if (y !in 4..5 && y !in 14..15) { v[y * (GRID - 1) + 1] = true; v[y * (GRID - 1) + 17] = true }
        // Inner circuit: boundary of box x5..14 × y5..14, 2-wide gates at the
        // middles (x∈{9,10} top/bottom, y∈{9,10} left/right).
        for (x in 5..14) if (x !in 9..10) { h[4 * GRID + x] = true; h[14 * GRID + x] = true }
        for (y in 5..14) if (y !in 9..10) { v[y * (GRID - 1) + 4] = true; v[y * (GRID - 1) + 14] = true }
        // Central slab: solid 4×4 core x8..11 × y8..11.
        for (x in 8..11) for (y in 8..11) b[y * GRID + x] = true
        return Layout(
            id = 0x15, name = "LABYRINTH",
            blocks = b, vertEdge = v, horizEdge = h,
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(7, 9), clashB = Cell(7, 10),   // inner west gate mouth
            pack = PACK,
        )
    }

    // =====================================================================
    // Pack manifest (registration order).
    // =====================================================================

    /** Family preview entry for GEN-MAZE: seed-0 geometry + per-seed generator. */
    fun genMazeFamily(): Layout = genMaze(0).copy(generator = { genMaze(it) })

    /** Family preview entry for GEN-ISLES. */
    fun genIslesFamily(): Layout = genIsles(0).copy(generator = { genIsles(it) })

    /** Every Arena Pack 1 layout, in registry id order (preview instances). */
    fun previews(): List<Layout> = listOf(
        genMazeFamily(), genIslesFamily(), coliseum(), pinwheel(), twinWalls(), labyrinth(),
    )
}
