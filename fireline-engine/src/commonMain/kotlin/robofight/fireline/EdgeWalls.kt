package robofight.fireline

/**
 * Thin edge walls for FIRELINE (PLAN-EDGE-WALLS.md §1, §4, §5 gate model).
 *
 * The model is copied from the shipped Arena, not invented, so the two products
 * cannot drift. Every rule below names the Arena source line it mirrors:
 *
 *  - `Arena.kt:32-48` storage: a `vertEdge` array for the edge to the RIGHT of
 *    a cell and a `horizEdge` array for the edge BELOW a cell. FIRELINE is a
 *    20x20 grid (`FirelineRules.kt:25 GRID = 20`), so a FIRELINE layout carries
 *    380 vertical edges + 380 horizontal edges = 760 edges.
 *  - `Arena.kt:68-71` "an edge never makes a cell non-free": `isFree` looks at
 *    blocks only. In FIRELINE the equivalent is [terrainPassable]
 *    (`Layout.kt:58`), which reads the terrain id of a cell and never reads an
 *    edge array. A wall gates movement *between* cells, never occupancy *of* a
 *    cell, and a wall is never derivable from a terrain id.
 *  - `Arena.kt:74-80` `wallBetween(ax,ay,bx,by)` normalises a cardinal
 *    neighbour pair in either direction to exactly one array lookup.
 *  - `Arena.kt:84-85` `stepBlocked = wallBetween(...) || isBlock(dest)`.
 *  - `Arena.kt:20-21` the outer border is an implicit wall and is not stored:
 *    an out-of-grid target is blocked by the border rule, never by an array
 *    slot, and no edge array has a slot for the border.
 *
 * PLAN-EDGE-WALLS.md §4 decision DW2 (a): the ASCII authoring form keeps its
 * 20 terrain rows and 20 fire rows and gains two optional wall blocks
 * [SECTION_WALL_VERT] (20 rows of 19 characters) and [SECTION_WALL_HORIZ]
 * (19 rows of 20 characters), so every shipped mission source stays
 * byte-identical. DW1 (a): walls gate rover MOVE, water packets and `AHEAD`;
 * they do not gate fire.
 */

// ---- Sizes and index rules (mirror `Arena.kt:32-48` with GRID = 20) ----

/** Vertical edges are indexed for `x` in 0..GRID-2: `Arena.kt:39-40`. */
const val VERT_EDGE_COLUMNS = GRID - 1

/** Vertical edge slots: `(GRID-1) x GRID` = 19 x 20 = 380: `Arena.kt:40`. */
const val VERT_EDGE_SLOTS = (GRID - 1) * GRID

/** Horizontal edge slots: `GRID x (GRID-1)` = 20 x 19 = 380: `Arena.kt:46`. */
const val HORIZ_EDGE_SLOTS = GRID * (GRID - 1)

/** Every edge of one 20x20 layout: 380 + 380 = 760: `Arena.kt:32-48`. */
const val EDGE_SLOTS_TOTAL = VERT_EDGE_SLOTS + HORIZ_EDGE_SLOTS

/** Authoring alphabet of the two wall blocks (PLAN-EDGE-WALLS.md §4): `#` wall, `.` open. */
const val EDGE_GLYPH_WALL: Char = GLYPH_WALL
const val EDGE_GLYPH_OPEN: Char = GLYPH_FLOOR

/** Wall block names used by the canonical mission text (PLAN-EDGE-WALLS.md §4). */
const val SECTION_WALL_VERT = "WALL_VERT"
const val SECTION_WALL_HORIZ = "WALL_HORIZ"

/** Row counts of the two wall blocks: 20 rows of 19, 19 rows of 20. */
const val WALL_VERT_ROWS = GRID
const val WALL_HORIZ_ROWS = GRID - 1

/** Index of the edge to the RIGHT of `(x, y)`: `Arena.kt:40` `y * (GRID - 1) + x`. */
fun vertEdgeIndex(x: Int, y: Int): Int = y * VERT_EDGE_COLUMNS + x

/** Index of the edge BELOW `(x, y)`: `Arena.kt:46` `y * GRID + x`. */
fun horizEdgeIndex(x: Int, y: Int): Int = y * GRID + x

/** Legal key of a vertical edge slot: `x` in 0..GRID-2, `y` in 0..GRID-1 (`Arena.kt:39-40`). */
fun vertEdgeInBounds(x: Int, y: Int): Boolean = x in 0 until VERT_EDGE_COLUMNS && y in 0 until GRID

/** Legal key of a horizontal edge slot: `x` in 0..GRID-1, `y` in 0..GRID-2 (`Arena.kt:45-46`). */
fun horizEdgeInBounds(x: Int, y: Int): Boolean = x in 0 until GRID && y in 0 until (GRID - 1)

/** All-open vertical edges: a source without a `WALL_VERT` block means no wall (`Arena.kt:42` defaults). */
fun openVertEdges(): BooleanArray = BooleanArray(VERT_EDGE_SLOTS)

/** All-open horizontal edges: a source without a `WALL_HORIZ` block means no wall. */
fun openHorizEdges(): BooleanArray = BooleanArray(HORIZ_EDGE_SLOTS)

/** True when at least one edge of the pair is a wall (`Arena.kt:88 edgeCount` used as a predicate). */
fun edgesPresent(vertEdge: BooleanArray, horizEdge: BooleanArray): Boolean =
    vertEdge.any { it } || horizEdge.any { it }

/** Number of walls in the pair (`Arena.kt:88`). */
fun edgeCountOf(vertEdge: BooleanArray, horizEdge: BooleanArray): Int =
    vertEdge.count { it } + horizEdge.count { it }

/**
 * True when an edge-wall line lies between the two (assumed 4-adjacent) cells,
 * in either direction. Verbatim shape of `Arena.kt:74-80`: one normalised
 * lookup per neighbour pair, `false` for anything that is not a cardinal pair
 * (out-of-grid, diagonal, or the same cell).
 */
fun wallBetweenEdges(
    vertEdge: BooleanArray,
    horizEdge: BooleanArray,
    ax: Int, ay: Int, bx: Int, by: Int,
): Boolean = when {
    by == ay && bx == ax + 1 -> vertEdge[vertEdgeIndex(ax, ay)]   // b is right of a  (Arena.kt:75)
    by == ay && bx == ax - 1 -> vertEdge[vertEdgeIndex(bx, by)]   // b is left of a   (Arena.kt:76)
    bx == ax && by == ay + 1 -> horizEdge[horizEdgeIndex(ax, ay)] // b is below a     (Arena.kt:77)
    bx == ax && by == ay - 1 -> horizEdge[horizEdgeIndex(bx, by)] // b is above a     (Arena.kt:78)
    else -> false                                                 // not a cardinal pair (Arena.kt:79)
}

/**
 * True when stepping from the in-grid cell `(x, y)` in direction [dir] is
 * blocked by an edge wall or by the implicit outer border. The border rule is
 * `Arena.kt:20-21`: an out-of-grid target is blocked by the border, never by an
 * array slot, so this returns true without touching an array. Terrain blocks
 * are NOT part of this predicate; the caller adds them the way `Arena.kt:84-85`
 * composes `stepBlocked = wallBetween(...) || isBlock(dest)`.
 */
fun edgeBlockedDir(
    vertEdge: BooleanArray,
    horizEdge: BooleanArray,
    x: Int, y: Int, dir: Int,
): Boolean {
    val nx = x + dirDx(dir)
    val ny = y + dirDy(dir)
    if (!inBounds(nx, ny)) return true                     // implicit outer border (Arena.kt:20-21)
    return wallBetweenEdges(vertEdge, horizEdge, x, y, nx, ny)
}

/**
 * Strict decode of one wall block: exactly [rowsExpected] rows of exactly
 * [colsExpected] characters, alphabet `#` (wall) and `.` (open). Row-length and
 * alphabet problems are reported in the same style [Layout.decode] uses for its
 * terrain rows (`Layout.kt:202-206`, `Layout.kt:223`), so a bad wall block
 * fails the mission source instead of being silently clipped.
 */
internal fun decodeWallRows(
    rows: List<String>,
    label: String,
    rowsExpected: Int,
    colsExpected: Int,
    problems: MutableList<String>,
): BooleanArray {
    val edges = BooleanArray(rowsExpected * colsExpected)
    if (rows.size != rowsExpected) {
        problems.add("$label rows ${rows.size}, expected $rowsExpected")
        return edges
    }
    for (y in 0 until rowsExpected) {
        val row = rows[y]
        if (row.length != colsExpected) {
            problems.add("$label row $y has ${row.length} chars, expected $colsExpected")
            continue
        }
        for (x in 0 until colsExpected) {
            when (row[x]) {
                EDGE_GLYPH_WALL -> edges[y * colsExpected + x] = true
                EDGE_GLYPH_OPEN -> edges[y * colsExpected + x] = false
                else -> problems.add("$label row $y char '${row[x]}' at x=$x is not a known wall glyph")
            }
        }
    }
    return edges
}

/** Render one edge array back to its wall block rows (`#` wall, `.` open). */
internal fun encodeWallRows(edges: BooleanArray, rowsExpected: Int, colsExpected: Int): List<String> =
    (0 until rowsExpected).map { y ->
        (0 until colsExpected).map { x ->
            if (edges[y * colsExpected + x]) EDGE_GLYPH_WALL else EDGE_GLYPH_OPEN
        }.joinToString("")
    }

/** `WALL_VERT` rows for a layout: 20 rows of 19 characters (PLAN-EDGE-WALLS.md §4). */
internal fun encodeVertRows(vertEdge: BooleanArray): List<String> =
    encodeWallRows(vertEdge, WALL_VERT_ROWS, VERT_EDGE_COLUMNS)

/** `WALL_HORIZ` rows for a layout: 19 rows of 20 characters (PLAN-EDGE-WALLS.md §4). */
internal fun encodeHorizRows(horizEdge: BooleanArray): List<String> =
    encodeWallRows(horizEdge, WALL_HORIZ_ROWS, GRID)
