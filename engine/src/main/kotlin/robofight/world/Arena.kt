package robofight.world

import kotlin.math.abs

/**
 * Challenge-code arena registry (CHALLENGE-CODES.md §7).
 *
 * Grid: 20×20, `x` = column 0–19 (left→right), `y` = row 0–19 (top→bottom).
 * The coordinate rules in [Arena] are the implementation source of truth
 * (§7.3); each layout is verified by the §12.1 geometry tests.
 */
data class Cell(val x: Int, val y: Int) {
    fun manhattan(other: Cell) = abs(x - other.x) + abs(y - other.y)
}

const val SPAWN_SEPARATE = 0
const val SPAWN_CLASH = 1
const val SPAWN_SCATTER = 2

/** One arena: id, name, wall grid, and its fixed spawn points (§7.3, §7.6). */
data class Layout(
    val id: Int,
    val name: String,
    val walls: BooleanArray,          // GRID×GRID, index y * GRID + x
    val separateA: Cell, val separateB: Cell,
    val clashA: Cell,    val clashB: Cell,
) {
    fun isWall(x: Int, y: Int): Boolean =
        x in 0 until GRID && y in 0 until GRID && walls[y * GRID + x]

    fun isFree(x: Int, y: Int): Boolean =
        x in 0 until GRID && y in 0 until GRID && !walls[y * GRID + x]

    fun wallCount(): Int = walls.count { it }

    /** All free cells, row-major. */
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

    /** `#` = wall, `.` = free — the spec's ASCII context (§7.3). */
    fun ascii(): String {
        val sb = StringBuilder()
        for (y in 0 until GRID) {
            for (x in 0 until GRID) sb.append(if (isWall(x, y)) '#' else '.')
            sb.append('\n')
        }
        return sb.toString()
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
    check(free.isNotEmpty()) { "scatter spawn on a fully walled arena" }
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

    val OPEN = open()
    val BLOCKS = blocks()
    val RING = ring()
    val CROSS = cross()
    val MAZE = maze()

    fun all(): List<Layout> = listOf(OPEN, BLOCKS, RING, CROSS, MAZE)

    fun byId(id: Int): Layout? = all().firstOrNull { it.id == id }

    // ---- 0x00 OPEN — 0 walls ---------------------------------------------
    private fun open(): Layout = Layout(
        id = 0, name = "OPEN",
        walls = BooleanArray(GRID * GRID),
        separateA = Cell(1, 1), separateB = Cell(18, 18),
        clashA = Cell(9, 10), clashB = Cell(10, 10),
    )

    // ---- 0x01 BLOCKS — 16 walls ------------------------------------------
    // Four 2×2 wall blocks, top-left corners at (6,6), (12,6), (6,12), (12,12).
    private fun blocks(): Layout {
        val w = BooleanArray(GRID * GRID)
        for ((cx, cy) in listOf(Cell(6, 6), Cell(12, 6), Cell(6, 12), Cell(12, 12)))
            for (dy in 0..1) for (dx in 0..1) w[(cy + dy) * GRID + (cx + dx)] = true
        return Layout(
            id = 1, name = "BLOCKS",
            walls = w,
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(9, 9), clashB = Cell(10, 9),
        )
    }

    // ---- 0x02 RING — 20 walls --------------------------------------------
    // Perimeter of x 6..13 × y 6..13 with a 2-wide gate at the middle of each
    // side: top/bottom at x ∈ {9,10}, left/right at y ∈ {9,10}.
    private fun ring(): Layout {
        val w = BooleanArray(GRID * GRID)
        for (x in 6..13) for (y in 6..13) {
            val onPerimeter = x in 6..13 && (y == 6 || y == 13) || y in 6..13 && (x == 6 || x == 13)
            if (!onPerimeter) continue
            val gate = (x in 9..10 && (y == 6 || y == 13)) || (y in 9..10 && (x == 6 || x == 13))
            if (!gate) w[y * GRID + x] = true
        }
        return Layout(
            id = 2, name = "RING",
            walls = w,
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(9, 9), clashB = Cell(10, 9),
        )
    }

    // ---- 0x03 CROSS — 28 walls -------------------------------------------
    // Vertical bar x ∈ {9,10}, y 6..13; horizontal bar y ∈ {9,10}, x 6..13.
    private fun cross(): Layout {
        val w = BooleanArray(GRID * GRID)
        for (x in 9..10) for (y in 6..13) w[y * GRID + x] = true
        for (y in 9..10) for (x in 6..13) w[y * GRID + x] = true
        return Layout(
            id = 3, name = "CROSS",
            walls = w,
            separateA = Cell(1, 1), separateB = Cell(18, 18),
            clashA = Cell(8, 7), clashB = Cell(8, 8),
        )
    }

    // ---- 0x04 MAZE — 108 walls -------------------------------------------
    // (a) outer ring x 1..18 × y 1..18 with 2-wide gates at the middle of each
    // side; (b) solid 4×4 center block x 8..11 × y 8..11; (c) horizontal
    // walls y = 4 and y = 15, x 5..14, 2-wide gap at x ∈ {9,10}; (d) vertical
    // walls x = 4 and x = 15, y 5..14, 2-wide gap at y ∈ {9,10}.
    private fun maze(): Layout {
        val w = BooleanArray(GRID * GRID)
        // (a) outer perimeter ring
        for (x in 1..18) for (y in 1..18) {
            val onPerimeter = (x == 1 || x == 18) || (y == 1 || y == 18)
            if (!onPerimeter) continue
            val gate = (x in 9..10 && (y == 1 || y == 18)) || (y in 9..10 && (x == 1 || x == 18))
            if (!gate) w[y * GRID + x] = true
        }
        // (b) center block
        for (x in 8..11) for (y in 8..11) w[y * GRID + x] = true
        // (c) horizontal walls
        for (y in intArrayOf(4, 15)) for (x in 5..14) if (x !in 9..10) w[y * GRID + x] = true
        // (d) vertical walls
        for (x in intArrayOf(4, 15)) for (y in 5..14) if (y !in 9..10) w[y * GRID + x] = true
        return Layout(
            id = 4, name = "MAZE",
            walls = w,
            separateA = Cell(2, 2), separateB = Cell(17, 17),
            clashA = Cell(7, 9), clashB = Cell(7, 10),
        )
    }
}
