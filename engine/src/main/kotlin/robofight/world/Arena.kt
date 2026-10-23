package robofight.world

import kotlin.math.abs

/**
 * Challenge-code arena registry (CHALLENGE-CODES.md §7).
 *
 * Grid: 20×20, `x` = column 0–19 (left→right), `y` = row 0–19 (top→bottom).
 * The coordinate rules in [Layout] are the implementation source of truth
 * (§7.3); each layout is verified by the §12.1 geometry tests.
 *
 * Two terrain kinds (decided 2026-10-14):
 *  - **blocks** (obstacles): full cells that cannot be entered, stood on, or
 *    shot through. Rendered as solid blocks.
 *  - **edge walls**: line segments on the border *between* two adjacent cells.
 *    They block `MOVE`, absorb projectiles, and register in `AHEAD` — the same
 *    rules a block applies, but at the cell edge rather than the whole cell.
 *    Rendered as lines.
 *
 * The arena's outer border is an implicit wall (movement is clamped to the
 * grid); it is not stored as an edge.
 */
data class Cell(val x: Int, val y: Int) {
    fun manhattan(other: Cell) = abs(x - other.x) + abs(y - other.y)
}

const val SPAWN_SEPARATE = 0
const val SPAWN_CLASH = 1
const val SPAWN_SCATTER = 2

/** One arena: id, name, obstacle blocks, edge walls, and its fixed spawn points (§7.3, §7.6). */
data class Layout(
    val id: Int,
    val name: String,
    /** Full-cell obstacles, index `y * GRID + x`, size GRID×GRID. */
    val blocks: BooleanArray,
    /**
     * Vertical wall edges: the edge to the RIGHT of cell `(x, y)`, separating
     * `(x, y)` from `(x+1, y)`. `x` in 0..GRID-2, `y` in 0..GRID-1, index
     * `y * (GRID - 1) + x`, size (GRID-1)×GRID.
     */
    val vertEdge: BooleanArray,
    /**
     * Horizontal wall edges: the edge BELOW cell `(x, y)`, separating
     * `(x, y)` from `(x, y+1)`. `x` in 0..GRID-1, `y` in 0..GRID-2, index
     * `y * GRID + x`, size GRID×(GRID-1).
     */
    val horizEdge: BooleanArray,
    val separateA: Cell, val separateB: Cell,
    val clashA: Cell,    val clashB: Cell,
) {
    /** True when the cell is an obstacle block. */
    fun isBlock(x: Int, y: Int): Boolean =
        x in 0 until GRID && y in 0 until GRID && blocks[y * GRID + x]

    /** True when the cell is enterable (in bounds and not a block). Edge walls do not
     *  make a cell non-free — they only gate movement *between* cells. */
    fun isFree(x: Int, y: Int): Boolean =
        x in 0 until GRID && y in 0 until GRID && !blocks[y * GRID + x]

    /** True when an edge-wall line lies between the two (assumed 4-adjacent) cells, in either direction. */
    fun wallBetween(ax: Int, ay: Int, bx: Int, by: Int): Boolean = when {
        by == ay && bx == ax + 1 -> vertEdge[ay * (GRID - 1) + ax]   // b is right of a
        by == ay && bx == ax - 1 -> vertEdge[ay * (GRID - 1) + bx]   // b is left  of a (edge right of b)
        bx == ax && by == ay + 1 -> horizEdge[ay * GRID + ax]        // b is below  a
        bx == ax && by == ay - 1 -> horizEdge[by * GRID + ax]        // b is above  a (edge below  b)
        else -> false
    }

    /** True when a step from `(ax,ay)` to the adjacent `(bx,by)` is blocked by the layout:
     *  an edge wall between them, or a block on the destination. (Out-of-bounds is the caller's.) */
    fun stepBlocked(ax: Int, ay: Int, bx: Int, by: Int): Boolean =
        wallBetween(ax, ay, bx, by) || isBlock(bx, by)

    fun blockCount(): Int = blocks.count { it }
    fun edgeCount(): Int = vertEdge.count { it } + horizEdge.count { it }

    /** All free (non-block) cells, row-major. */
    fun freeCells(): List<Cell> {
        val out = ArrayList<Cell>(GRID * GRID)
        for (y in 0 until GRID) for (x in 0 until GRID) if (isFree(x, y)) out.add(Cell(x, y))
        return out
    }

    /** Fixed spawn pair for [spawn]; SCATTER is resolved by [scatterSpawn]. */
    fun spawnCells(spawn: Int): Pair<Cell, Cell> = when (spawn) {
        SPAWN_SEPARATE -> separateA to separateB
        SPAWN_CLASH -> clashA to clashB
        else -> throw IllegalArgumentException("SCATTER is not a fixed spawn")
    }

    /** `#` = block cell, `.` = free — the obstacle view of the arena. */
    fun ascii(): String {
        val sb = StringBuilder()
        for (y in 0 until GRID) {
            for (x in 0 until GRID) sb.append(if (isBlock(x, y)) '#' else '.')
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * A 2×-resolution picture of the whole layout (41×41): `#` = block cell,
     * `.` = free cell, `|`/`-` = edge-wall lines, ` ` = open gutter. Used for
     * the spec and debugging; the tests assert on the raw arrays directly.
     */
    fun render(): String {
        val W = 2 * GRID + 1
        val g = Array(W) { CharArray(W) { ' ' } }
        for (y in 0 until GRID) for (x in 0 until GRID) {
            g[2 * y + 1][2 * x + 1] = if (isBlock(x, y)) '#' else '.'
            if (y > 0 && horizEdge[(y - 1) * GRID + x]) g[2 * y][2 * x + 1] = '-'
            if (y < GRID - 1 && horizEdge[y * GRID + x]) g[2 * y + 2][2 * x + 1] = '-'
            if (x > 0 && vertEdge[y * (GRID - 1) + (x - 1)]) g[2 * y + 1][2 * x] = '|'
            if (x < GRID - 1 && vertEdge[y * (GRID - 1) + x]) g[2 * y + 1][2 * x + 2] = '|'
        }
        return g.joinToString("\n") { it.concatToString() }
    }
}

/**
 * SCATTER spawn (§7.6): each bot draws a uniformly random free cell from the
 * fight seed; if the pair is closer than Manhattan distance 6, both redraw —
 * up to 8 tries, then accept. Fully deterministic from the seed, and the draw
 * order (A, then B, redraw both) is part of the determinism contract.
 */
fun scatterSpawn(layout: Layout, nextRand: () -> Int): Pair<Cell, Cell> {
    val free = layout.freeCells()
    check(free.isNotEmpty()) { "scatter spawn on a fully blocked arena" }
    val draw = { free[nextRand() % free.size] }
    var a = draw()
    var b = draw()
    var tries = 0
    while (a.manhattan(b) < 6 && tries < 8) {
        a = draw()
        b = draw()
        tries++
    }
    return a to b
}

/** The five free arenas, in registry id order (§7.1: 0x00–0x04). */
object Arena {

    private val emptyV = BooleanArray((GRID - 1) * GRID)
    private val emptyH = BooleanArray(GRID * (GRID - 1))
    private val emptyB = BooleanArray(GRID * GRID)

    val OPEN = open()
    val BLOCKS = blocks()
    val RING = ring()
    val CROSS = cross()
    val MAZE = maze()

    /**
     * Live registry: the five free layouts plus anything added via
     * [registerPro]. The registry is the ONLY tier gate (CHALLENGE-CODES.md
     * A1): presence here = playable; a registered-pro id that is *absent*
     * (0x10–0x1F) parses as importable-but-locked. The free app never
     * registers anything; the Pro app registers its packs at startup.
     */
    private val registry = ArrayList<Layout>().apply { addAll(listOf(OPEN, BLOCKS, RING, CROSS, MAZE)) }

    fun all(): List<Layout> = registry.toList()

    fun byId(id: Int): Layout? = registry.firstOrNull { it.id == id }

    /**
     * Register a Pro-pack arena. Hard gate: the id MUST lie in the reserved
     * Pro range 0x10–0x1F (§7.1); duplicates (including collisions with the
     * free ids) are rejected. No tier field — being in the registry IS the
     * tier gate, so [ChallengeFormat], [robofight.challenge.ChallengeRun]
     * and the app work unchanged on registered layouts.
     */
    fun registerPro(layout: Layout) {
        require(layout.id in 0x10..0x1F) {
            "pro arena id must be in 0x10..0x1F, got 0x${layout.id.toString(16)}"
        }
        require(byId(layout.id) == null) {
            "duplicate arena id 0x${layout.id.toString(16)} ('${layout.name}')"
        }
        registry.add(layout)
    }

    // ---- 0x00 OPEN — no terrain ------------------------------------------
    private fun open(): Layout = Layout(
        id = 0, name = "OPEN",
        blocks = emptyB,
        vertEdge = emptyV,
        horizEdge = emptyH,
        separateA = Cell(1, 1), separateB = Cell(18, 18),
        clashA = Cell(9, 10), clashB = Cell(10, 10),
    )

    // ---- 0x01 BLOCKS — 16 blocks, no edge walls --------------------------
    // Four 2×2 obstacle blocks, top-left corners at (6,6), (12,6), (6,12), (12,12).
    private fun blocks(): Layout {
        val b = BooleanArray(GRID * GRID)
        for (c in listOf(Cell(6, 6), Cell(12, 6), Cell(6, 12), Cell(12, 12)))
            for (dy in 0..1) for (dx in 0..1) b[(c.y + dy) * GRID + (c.x + dx)] = true
        return Layout(
            id = 1, name = "BLOCKS",
            blocks = b,
            vertEdge = emptyV,
            horizEdge = emptyH,
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(9, 9), clashB = Cell(10, 9),
        )
    }

    // ---- 0x02 RING — 24 edge walls, no blocks ----------------------------
    // The boundary lines of the 8×8 square x 6..13 × y 6..13, with a 2-wide
    // gate at the middle of each side: top/bottom at x ∈ {9,10}, left/right at
    // y ∈ {9,10}. The enclosed chamber is the full 8×8 (a line is 0-thick);
    // bullets die on the ring, so fights collapse into gate duels.
    private fun ring(): Layout {
        val v = BooleanArray((GRID - 1) * GRID)
        val h = BooleanArray(GRID * (GRID - 1))
        for (x in 6..13) if (x !in 9..10) {
            h[5 * GRID + x] = true    // top boundary   (edge above row 6)
            h[13 * GRID + x] = true   // bottom boundary (edge below row 13)
        }
        for (y in 6..13) if (y !in 9..10) {
            v[y * (GRID - 1) + 5] = true    // left boundary  (edge left of col 6)
            v[y * (GRID - 1) + 13] = true   // right boundary (edge right of col 13)
        }
        return Layout(
            id = 2, name = "RING",
            blocks = emptyB,
            vertEdge = v,
            horizEdge = h,
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(9, 9), clashB = Cell(10, 9),
        )
    }

    // ---- 0x03 CROSS — 28 blocks, no edge walls ---------------------------
    // A plus-shaped cover: vertical bar x ∈ {9,10}, y 6..13; horizontal bar
    // y ∈ {9,10}, x 6..13. Direct corner-to-corner fire lines are blocked;
    // shooting means committing to one of the four lanes around the arms.
    private fun cross(): Layout {
        val b = BooleanArray(GRID * GRID)
        for (x in 9..10) for (y in 6..13) b[y * GRID + x] = true
        for (y in 9..10) for (x in 6..13) b[y * GRID + x] = true
        return Layout(
            id = 3, name = "CROSS",
            blocks = b,
            vertEdge = emptyV,
            horizEdge = emptyH,
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(8, 7), clashB = Cell(8, 8),
        )
    }

    // ---- 0x04 MAZE — 96 edge walls + 16 blocks ---------------------------
    // (a) outer perimeter — the boundary lines of the 18×18 box x 1..18 ×
    //     y 1..18, 2-wide gates at the middle of each side (top/bottom
    //     x ∈ {9,10}, left/right y ∈ {9,10}); (b) solid 4×4 obstacle block
    //     x 8..11 × y 8..11; (c) horizontal wall lines at the center-side
    //     boundary of rows 4 and 15, x 5..14, 2-wide gap at x ∈ {9,10};
    //     (d) vertical wall lines at the center-side boundary of cols 4 and
    //     15, y 5..14, 2-wide gap at y ∈ {9,10}. Loop topology throughout —
    //     no dead ends, no sealed pockets. The prestige arena.
    private fun maze(): Layout {
        val b = BooleanArray(GRID * GRID)
        val v = BooleanArray((GRID - 1) * GRID)
        val h = BooleanArray(GRID * (GRID - 1))
        // (a) outer perimeter (boundary of the 18×18 box)
        for (x in 1..18) if (x !in 9..10) {
            h[0 * GRID + x] = true    // top    (edge above row 1)
            h[18 * GRID + x] = true   // bottom (edge below row 18)
        }
        for (y in 1..18) if (y !in 9..10) {
            v[y * (GRID - 1) + 0] = true    // left  (edge left of col 1)
            v[y * (GRID - 1) + 18] = true   // right (edge right of col 18)
        }
        // (b) center 4×4 block
        for (x in 8..11) for (y in 8..11) b[y * GRID + x] = true
        // (c) horizontal wall lines (boundary between rows 4|5 and 14|15)
        for (x in 5..14) if (x !in 9..10) {
            h[4 * GRID + x] = true
            h[14 * GRID + x] = true
        }
        // (d) vertical wall lines (boundary between cols 4|5 and 14|15)
        for (y in 5..14) if (y !in 9..10) {
            v[y * (GRID - 1) + 4] = true
            v[y * (GRID - 1) + 14] = true
        }
        return Layout(
            id = 4, name = "MAZE",
            blocks = b,
            vertEdge = v,
            horizEdge = h,
            separateA = Cell(2, 2), separateB = Cell(17, 17),
            clashA = Cell(7, 9), clashB = Cell(7, 10),
        )
    }
}
