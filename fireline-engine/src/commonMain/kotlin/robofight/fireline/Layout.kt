package robofight.fireline

/**
 * Static authored layout: terrain, starting fire intensities, rover spawn,
 * civilians (FIRELINE-DESIGN.md §3.1-§3.4, §9 validation contract) and the thin
 * edge walls of PLAN-EDGE-WALLS.md §4.
 *
 * Terrain is immutable for the whole run: FIRELINE v1 has no destructible
 * terrain, so a different mission seed can change fire and RNG streams but can
 * NEVER alter authored static terrain (PLAN §8.4). Dynamic per-cell fire state
 * is owned by [FirelineWorld]; [Layout] only carries the authored starting
 * intensities.
 *
 * Edge walls are the Arena's two-terrain-kind model (PLAN-EDGE-WALLS.md §1,
 * `Arena.kt:12-18`): a wall cell occupies a whole cell, an edge wall is the line
 * segment between two 4-adjacent cells. The arrays, index rules and helpers are
 * the Arena's, restated for FIRELINE constants in [EdgeWalls]. A wall is never
 * derivable from a terrain id and never makes a cell non-free
 * (`Arena.kt:68-71`, [terrainPassable]).
 */

// Terrain identifiers returned by `IN TILE` (§3.2). A civilian never changes
// the terrain id of its cell.
const val TERRAIN_FLOOR = 0
const val TERRAIN_WALL = 1
const val TERRAIN_FUEL = 2
const val TERRAIN_REFILL = 3
const val TERRAIN_EXIT = 4
const val TERRAIN_PROTECTED = 5

const val TERRAIN_COUNT = 6

/** Authoring glyphs (§3.2). */
const val GLYPH_FLOOR = '.'
const val GLYPH_WALL = '#'
const val GLYPH_FUEL = 'F'
const val GLYPH_REFILL = 'W'
const val GLYPH_EXIT = 'E'
const val GLYPH_PROTECTED = 'P'

/** Authoring-only markers: rover spawn and a civilian standing on floor. */
const val GLYPH_ROVER = 'R'
const val GLYPH_CIVILIAN = 'C'

/** Sections and items of the canonical mission text (PLAN-EDGE-WALLS.md §4). */
const val CANONICAL_HEADER = "FIRELINE LAYOUT"
const val SECTION_TERRAIN = "TERRAIN"
const val SECTION_FIRE = "FIRE"
const val ITEM_ROVER = "ROVER"
const val ITEM_CIVILIAN = "CIVILIAN"

/** Terrain glyphs a canonical terrain section may use; `R` is not one of them. */
const val TERRAIN_GLYPHS = ".#FWEPC"

fun terrainName(t: Int): String = when (t) {
    TERRAIN_FLOOR -> "floor"
    TERRAIN_WALL -> "wall"
    TERRAIN_FUEL -> "fuel"
    TERRAIN_REFILL -> "refill"
    TERRAIN_EXIT -> "exit"
    TERRAIN_PROTECTED -> "protected asset"
    else -> "invalid($t)"
}

fun terrainGlyph(t: Int): Char = when (t) {
    TERRAIN_FLOOR -> GLYPH_FLOOR
    TERRAIN_WALL -> GLYPH_WALL
    TERRAIN_FUEL -> GLYPH_FUEL
    TERRAIN_REFILL -> GLYPH_REFILL
    TERRAIN_EXIT -> GLYPH_EXIT
    TERRAIN_PROTECTED -> GLYPH_PROTECTED
    else -> '?'
}

/** Inverse of the [decode] fire table: 0 = '.', 25/50/75/100 = '1'..'4', anything else '?'. */
fun fireLevelGlyph(intensity: Int): Char = when (intensity) {
    0 -> GLYPH_FLOOR
    FIRE_LEVELS[0] -> '1'
    FIRE_LEVELS[1] -> '2'
    FIRE_LEVELS[2] -> '3'
    FIRE_LEVELS[3] -> '4'
    else -> '?'
}

/** §3.2: walls block rover, water and line drawing; protected assets are impassable.
 *  Reads the terrain id of a cell ONLY: an edge wall never changes this answer
 *  (PLAN-EDGE-WALLS.md §3 "no 7th terrain id", mirroring `Arena.kt:68-71`). */
fun terrainPassable(t: Int): Boolean = t != TERRAIN_WALL && t != TERRAIN_PROTECTED

/** §3.2: refill is the only passable non-burnable terrain; wall never burns. */
fun terrainBurnable(t: Int): Boolean =
    t == TERRAIN_FLOOR || t == TERRAIN_FUEL || t == TERRAIN_EXIT || t == TERRAIN_PROTECTED

/** Row-major cell index. */
fun cellIndex(x: Int, y: Int): Int = y * GRID + x
fun indexX(i: Int): Int = i % GRID
fun indexY(i: Int): Int = i / GRID
fun inBounds(x: Int, y: Int): Boolean = x in 0 until GRID && y in 0 until GRID

/** A civilian's authored definition (mission-local stable ID, §3.4). */
data class CivilianSpec(
    val id: Int,
    val x: Int,
    val y: Int,
    val hp: Int = MAX_HP,
    val required: Boolean = true,
)

/**
 * Immutable authored layout content: 400 terrain cells, 400 starting fire
 * intensities, one rover spawn, civilians, and 760 thin edge walls. Built either
 * from explicit arrays, from the ASCII authoring form (see [decode]) or from the
 * canonical mission text (see [toCanonicalText] / [fromCanonicalText]).
 *
 * Both wall arrays default to all-open, so a layout or mission source that
 * declares no walls behaves exactly as it did before edge walls existed
 * (PLAN-EDGE-WALLS.md §6: "each new gate adds a condition that is always false
 * for them").
 */
class Layout(
    val terrain: IntArray,
    val fire: IntArray,
    val roverX: Int,
    val roverY: Int,
    val roverDir: Int,
    val civilians: List<CivilianSpec>,
    /**
     * Vertical wall edges: the edge to the RIGHT of cell `(x, y)`, separating
     * `(x, y)` from `(x+1, y)`. `x` in 0..GRID-2, `y` in 0..GRID-1, index
     * `y * (GRID - 1) + x`, size (GRID-1)xGRID = 380 (`Arena.kt:37-42`).
     */
    val vertEdge: BooleanArray = openVertEdges(),
    /**
     * Horizontal wall edges: the edge BELOW cell `(x, y)`, separating
     * `(x, y)` from `(x, y+1)`. `x` in 0..GRID-1, `y` in 0..GRID-2, index
     * `y * GRID + x`, size GRIDx(GRID-1) = 380 (`Arena.kt:43-48`).
     */
    val horizEdge: BooleanArray = openHorizEdges(),
) {
    init {
        require(terrain.size == CELLS) { "layout terrain must have $CELLS cells (got ${terrain.size})" }
        require(fire.size == CELLS) { "layout fire must have $CELLS cells (got ${fire.size})" }
        require(vertEdge.size == VERT_EDGE_SLOTS) { "layout wall edges must have $VERT_EDGE_SLOTS vertical edges (got ${vertEdge.size})" }
        require(horizEdge.size == HORIZ_EDGE_SLOTS) { "layout wall edges must have $HORIZ_EDGE_SLOTS horizontal edges (got ${horizEdge.size})" }
    }

    fun terrainAt(x: Int, y: Int): Int = terrain[cellIndex(x, y)]
    fun fireAt(x: Int, y: Int): Int = fire[cellIndex(x, y)]

    /**
     * True when an edge wall lies between the two (assumed 4-adjacent) cells, in
     * either direction: [EdgeWalls] restating `Arena.kt:74-80`. PLAN-EDGE-WALLS.md
     * §3 gate 1 names the same predicate at its call site [edgeBetween].
     */
    fun wallBetween(ax: Int, ay: Int, bx: Int, by: Int): Boolean =
        wallBetweenEdges(vertEdge, horizEdge, ax, ay, bx, by)

    /** Name PLAN-EDGE-WALLS.md §3 gate 1 uses for [wallBetween]. */
    fun edgeBetween(ax: Int, ay: Int, bx: Int, by: Int): Boolean = wallBetween(ax, ay, bx, by)

    /**
     * True when stepping out of the in-grid cell `(x, y)` in direction [dir] is
     * blocked by an edge wall or by the implicit outer border (`Arena.kt:20-21`:
     * the border is never an array slot). Terrain is not part of this answer.
     */
    fun edgeBlocked(x: Int, y: Int, dir: Int): Boolean = edgeBlockedDir(vertEdge, horizEdge, x, y, dir)

    /**
     * True when a step from `(ax,ay)` to `(bx,by)` is blocked by the layout: an
     * edge wall between them, or an impassable destination cell. `Arena.kt:84-85`
     * composes it as `wallBetween(...) || isBlock(dest)` and leaves out-of-bounds
     * to the caller (`Arena.kt:82-83`; `World.kt:169` guards the bounds itself).
     * FIRELINE folds the implicit border in here so one call answers a direction
     * instead of a proven neighbour.
     */
    fun stepBlocked(ax: Int, ay: Int, bx: Int, by: Int): Boolean =
        !inBounds(bx, by) || wallBetween(ax, ay, bx, by) || !terrainPassable(terrainAt(bx, by))

    /** True when this layout carries at least one edge wall (`Arena.kt:88`). */
    fun wallsPresent(): Boolean = edgesPresent(vertEdge, horizEdge)

    /** Number of edge walls in this layout (`Arena.kt:88`). */
    fun edgeCount(): Int = edgeCountOf(vertEdge, horizEdge)

    /** Row-major list of exit cells (a mission normally has exactly one). */
    fun exitCells(): List<Pair<Int, Int>> =
        (0 until CELLS).filter { terrain[it] == TERRAIN_EXIT }.map { indexX(it) to indexY(it) }

    /** Row-major list of protected-asset cells. */
    fun protectedCells(): List<Pair<Int, Int>> =
        (0 until CELLS).filter { terrain[it] == TERRAIN_PROTECTED }.map { indexX(it) to indexY(it) }

    /** Row-major terrain view, used by the canonical final-state hash. */
    fun terrainRowMajor(): IntArray = terrain.copyOf()
    fun fireRowMajor(): IntArray = fire.copyOf()

    /**
     * Row-major defensive copies of the two wall arrays. They serve the
     * renderers of PLAN-EDGE-WALLS.md steps E3/E4 and, since the reconcile
     * merge (PLAN-RECONCILE-MAIN-01.md §8), the canonical final-state hash
     * too: [verticalEdgesRowMajor] and [horizontalEdgesRowMajor] are the
     * inputs `FirelineWorld.canonicalState` stamps, in main's shape and
     * naming. [wallVertRowMajor] / [wallHorizRowMajor] are the same arrays
     * under this branch's names.
     */
    fun wallVertRowMajor(): BooleanArray = vertEdge.copyOf()
    fun wallHorizRowMajor(): BooleanArray = horizEdge.copyOf()
    fun verticalEdgesRowMajor(): BooleanArray = vertEdge.copyOf()
    fun horizontalEdgesRowMajor(): BooleanArray = horizEdge.copyOf()

    /**
     * Layout validation (§9 "Every authored mission requires ... validation"):
     * exactly one spawn, cells inside the grid, no civilian on a wall or a
     * protected asset, fire only on burnable terrain, ranges legal, and every
     * objective-relevant cell reachable from the spawn through passable cells.
     * Empty list = valid.
     *
     * The flood fill does not cross an edge wall (PLAN-EDGE-WALLS.md §3 gate 5),
     * so a mission whose exit is sealed by edges fails validation exactly as one
     * sealed by wall cells does. When a target was reachable with the walls
     * ignored but is unreachable with them, the message says an edge wall blocked
     * it instead of reusing the terrain-only wording.
     */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (!inBounds(roverX, roverY)) problems.add("rover spawn outside grid: ($roverX,$roverY)")
        if (roverDir !in 0..3) problems.add("rover facing $roverDir outside 0..3")
        if (inBounds(roverX, roverY) && !terrainPassable(terrainAt(roverX, roverY)))
            problems.add("rover spawn is not passable: ${terrainName(terrainAt(roverX, roverY))}")
        val seenCiv = HashSet<Int>()
        for (c in civilians) {
            if (!inBounds(c.x, c.y)) { problems.add("civilian ${c.id} outside grid: (${c.x},${c.y})"); continue }
            val t = terrainAt(c.x, c.y)
            if (!terrainPassable(t)) problems.add("civilian ${c.id} stands on impassable ${terrainName(t)}: (${c.x},${c.y})")
            if (c.hp !in 0..MAX_HP) problems.add("civilian ${c.id} hp ${c.hp} outside 0..$MAX_HP")
            if (!seenCiv.add(cellIndex(c.x, c.y))) problems.add("two civilians on cell (${c.x},${c.y})")
        }
        for (i in 0 until CELLS) {
            if (terrain[i] !in 0 until TERRAIN_COUNT) problems.add("cell ${indexX(i)},${indexY(i)} terrain ${terrain[i]} invalid")
            if (fire[i] !in 0..FIRE_MAX) problems.add("cell ${indexX(i)},${indexY(i)} fire ${fire[i]} outside 0..$FIRE_MAX")
            if (fire[i] > 0 && !terrainBurnable(terrain[i]))
                problems.add("cell ${indexX(i)},${indexY(i)} starts burning but ${terrainName(terrain[i])} is not burnable")
        }
        // Reachability: spawn flood fill over passable cells, not crossing an edge
        // wall, must reach every civilian, every exit and every starting fire.
        if (inBounds(roverX, roverY) && terrainPassable(terrainAt(roverX, roverY))) {
            val reached = floodFillPassable(crossEdgeWalls = false)
            val reachedIgnoringWalls = if (wallsPresent()) floodFillPassable(crossEdgeWalls = true) else reached
            fun wallSuffix(i: Int): String = if (reachedIgnoringWalls[i]) " blocked by an edge wall" else ""
            for (c in civilians)
                if (!reached[cellIndex(c.x, c.y)])
                    problems.add("civilian ${c.id} unreachable from spawn: (${c.x},${c.y})${wallSuffix(cellIndex(c.x, c.y))}")
            for ((x, y) in exitCells())
                if (!reached[cellIndex(x, y)])
                    problems.add("exit ($x,$y) unreachable from spawn${wallSuffix(cellIndex(x, y))}")
            for (i in 0 until CELLS)
                // Fires the rover can never stand on (protected assets are
                // impassable) are protected by surviving, not by reaching them,
                // so they are exempt from the reachability requirement.
                if (fire[i] > 0 && !reached[i] && terrainPassable(terrain[i]))
                    problems.add("starting fire at ${indexX(i)},${indexY(i)} unreachable from spawn${wallSuffix(i)}")
        }
        return problems
    }

    /**
     * Spawn flood fill over passable cells. The traversal order is unchanged from
     * before edge walls existed (FIFO ArrayDeque, directions in `0..3` order), so
     * the same source validates the same way with and without the wall gate.
     * [crossEdgeWalls] = true ignores edge walls and is used only to word the
     * "blocked by an edge wall" message.
     */
    private fun floodFillPassable(crossEdgeWalls: Boolean): BooleanArray {
        val reached = BooleanArray(CELLS)
        val stack = ArrayDeque<Int>()
        stack.addLast(cellIndex(roverX, roverY))
        reached[cellIndex(roverX, roverY)] = true
        while (stack.isNotEmpty()) {
            val i = stack.removeFirst()
            val x = indexX(i); val y = indexY(i)
            for (d in 0..3) {
                val nx = x + dirDx(d); val ny = y + dirDy(d)
                if (!inBounds(nx, ny)) continue
                if (!crossEdgeWalls && wallBetween(x, y, nx, ny)) continue   // gate 5 (`Arena.kt:74-80`)
                val ni = cellIndex(nx, ny)
                if (reached[ni] || !terrainPassable(terrain[ni])) continue
                reached[ni] = true
                stack.addLast(ni)
            }
        }
        return reached
    }

    /**
     * Canonical mission text (PLAN-EDGE-WALLS.md §4): the 20 terrain rows and 20
     * fire rows every mission source already has, then the two OPTIONAL wall
     * blocks, then the rover item and the civilian items.
     *
     * ```text
     * FIRELINE LAYOUT 20
     * TERRAIN      20 rows of 20 glyphs  . # F W E P C
     * FIRE         20 rows of 20 levels  . 1 2 3 4
     * WALL_VERT    20 rows of 19 glyphs  # wall, . open   (emitted only when a wall exists)
     * WALL_HORIZ   19 rows of 20 glyphs  # wall, . open   (emitted only when a wall exists)
     * ROVER x,y,dir
     * CIVILIAN id,x,y,hp,1|0            (one line per civilian)
     * ```
     *
     * Both blocks are emitted only when at least one wall exists, so a wall-less
     * mission text is byte-identical with and without edge walls and round-trips
     * unchanged. The rover spawn lives in its own item and not as an `R` glyph,
     * because the terrain section states terrain only.
     */
    fun toCanonicalText(): String {
        for (i in 0 until CELLS)
            require(fireLevelGlyph(fire[i]) != '?') {
                "canonical text needs authored fire levels; cell ${indexX(i)},${indexY(i)} has intensity ${fire[i]}"
            }
        require(terrainAt(roverX, roverY) == TERRAIN_FLOOR) {
            "canonical text places the rover spawn on a floor cell (${roverX},${roverY} is ${terrainName(terrainAt(roverX, roverY))})"
        }
        val out = ArrayList<String>()
        out.add("$CANONICAL_HEADER $GRID")
        out.add(SECTION_TERRAIN)
        out.addAll(encodeTerrain(this))
        out.add(SECTION_FIRE)
        out.addAll(encodeFireRows(this))
        if (wallsPresent()) {
            out.add(SECTION_WALL_VERT)
            out.addAll(encodeVertRows(vertEdge))
            out.add(SECTION_WALL_HORIZ)
            out.addAll(encodeHorizRows(horizEdge))
        }
        out.add("$ITEM_ROVER $roverX,$roverY,$roverDir")
        for (c in civilians) out.add("$ITEM_CIVILIAN ${c.id},${c.x},${c.y},${c.hp},${if (c.required) 1 else 0}")
        return out.joinToString("\n") + "\n"
    }

    override fun toString(): String = "Layout(spawn=$roverX,$roverY dir=$roverDir civilians=${civilians.size} walls=${edgeCount()})"

    companion object {
        /**
         * ASCII authoring form (§9 "ASCII source representation ... in version
         * control"): 20 rows of 20 terrain glyphs (`.#FWEP`, plus the
         * authoring-only markers `R` rover spawn on floor and `C` civilian on
         * floor), and 20 rows of fire levels where `.`/`0` = 0, `1` = 25,
         * `2` = 50, `3` = 75, `4` = 100.
         *
         * Civilians marked with `C` get stable ids in row-major order starting
         * at 1 and full HP; pass [civilians] explicitly (and no `C` glyphs) to
         * author custom HP or non-required civilians.
         *
         * The two wall blocks of PLAN-EDGE-WALLS.md §4 (DW2 a) are optional
         * arguments: an empty list means the block is absent and every edge of
         * that kind is open. A block that IS present is validated as strictly as
         * the terrain rows (row count, row length, alphabet).
         */
        fun decode(
            terrainRows: List<String>,
            fireRows: List<String>,
            roverDir: Int = DIR_E,
            civilians: List<CivilianSpec> = emptyList(),
            vertEdgeRows: List<String> = emptyList(),
            horizEdgeRows: List<String> = emptyList(),
        ): Layout {
            val problems = ArrayList<String>()
            val terrain = IntArray(CELLS)
            val fire = IntArray(CELLS)
            val decodedCiv = ArrayList<CivilianSpec>()
            var spawnX = -1; var spawnY = -1; var spawns = 0
            if (terrainRows.size != GRID) problems.add("terrain rows ${terrainRows.size}, expected $GRID")
            if (fireRows.size != GRID) problems.add("fire rows ${fireRows.size}, expected $GRID")
            for (y in 0 until minOf(terrainRows.size, GRID)) {
                val row = terrainRows[y]
                if (row.length != GRID) problems.add("terrain row $y has ${row.length} chars, expected $GRID")
                for (x in 0 until minOf(row.length, GRID)) {
                    when (row[x]) {
                        GLYPH_FLOOR -> terrain[cellIndex(x, y)] = TERRAIN_FLOOR
                        GLYPH_WALL -> terrain[cellIndex(x, y)] = TERRAIN_WALL
                        GLYPH_FUEL -> terrain[cellIndex(x, y)] = TERRAIN_FUEL
                        GLYPH_REFILL -> terrain[cellIndex(x, y)] = TERRAIN_REFILL
                        GLYPH_EXIT -> terrain[cellIndex(x, y)] = TERRAIN_EXIT
                        GLYPH_PROTECTED -> terrain[cellIndex(x, y)] = TERRAIN_PROTECTED
                        GLYPH_ROVER -> {
                            terrain[cellIndex(x, y)] = TERRAIN_FLOOR
                            spawnX = x; spawnY = y; spawns++
                        }
                        GLYPH_CIVILIAN -> {
                            terrain[cellIndex(x, y)] = TERRAIN_FLOOR
                            decodedCiv.add(CivilianSpec(decodedCiv.size + 1, x, y))
                        }
                        else -> problems.add("terrain row $y char '${row[x]}' at x=$x is not a known glyph")
                    }
                }
            }
            for (y in 0 until minOf(fireRows.size, GRID)) {
                val row = fireRows[y]
                if (row.length != GRID) problems.add("fire row $y has ${row.length} chars, expected $GRID")
                for (x in 0 until minOf(row.length, GRID)) {
                    fire[cellIndex(x, y)] = when (row[x]) {
                        '.', '0' -> 0
                        '1' -> FIRE_LEVELS[0]
                        '2' -> FIRE_LEVELS[1]
                        '3' -> FIRE_LEVELS[2]
                        '4' -> FIRE_LEVELS[3]
                        else -> { problems.add("fire row $y char '${row[x]}' at x=$x is not a known level"); 0 }
                    }
                }
            }
            // Optional wall blocks (PLAN-EDGE-WALLS.md §4): an absent block is all open.
            val vertEdge = if (vertEdgeRows.isEmpty()) openVertEdges()
                else decodeWallRows(vertEdgeRows, SECTION_WALL_VERT, WALL_VERT_ROWS, VERT_EDGE_COLUMNS, problems)
            val horizEdge = if (horizEdgeRows.isEmpty()) openHorizEdges()
                else decodeWallRows(horizEdgeRows, SECTION_WALL_HORIZ, WALL_HORIZ_ROWS, GRID, problems)
            if (spawns != 1) problems.add("layout must contain exactly one '$GLYPH_ROVER' spawn (found $spawns)")
            val civs = if (civilians.isNotEmpty()) {
                if (decodedCiv.isNotEmpty()) problems.add("layout uses '$GLYPH_CIVILIAN' glyphs and an explicit civilian list")
                civilians
            } else decodedCiv

            if (problems.isNotEmpty()) throw IllegalArgumentException("invalid layout:\n" + problems.joinToString("\n"))
            return Layout(terrain, fire, spawnX, spawnY, roverDir, civs, vertEdge, horizEdge)
        }

        /** Render a layout back to the ASCII terrain form (round-trip check for authored content). */
        fun encodeTerrain(layout: Layout): List<String> =
            (0 until GRID).map { y -> (0 until GRID).map { x -> terrainGlyph(layout.terrainAt(x, y)) }.joinToString("") }

        /** Render a layout back to the ASCII fire form (inverse of the [decode] fire table). */
        fun encodeFireRows(layout: Layout): List<String> =
            (0 until GRID).map { y -> (0 until GRID).map { x -> fireLevelGlyph(layout.fireAt(x, y)) }.joinToString("") }

        /**
         * The two wall blocks of a layout as the canonical text emits them:
         * `WALL_VERT` 20 rows of 19 characters and `WALL_HORIZ` 19 rows of 20
         * characters, `#` wall and `.` open (PLAN-EDGE-WALLS.md §4).
         */
        fun encodeEdges(layout: Layout): Pair<List<String>, List<String>> =
            encodeVertRows(layout.vertEdge) to encodeHorizRows(layout.horizEdge)

        /** Parse one canonical mission text back into a [Layout] (inverse of [toCanonicalText]). */
        fun fromCanonicalText(text: String): Layout {
            val problems = ArrayList<String>()
            val lines = text.split('\n').map { it.trimEnd('\r') }
            var terrainRows: List<String>? = null
            var fireRows: List<String>? = null
            var vertRows: List<String>? = null
            var horizRows: List<String>? = null
            var spawnX = -1; var spawnY = -1; var spawnDir = -1; var spawns = 0
            val civs = ArrayList<CivilianSpec>()
            var lineIndex = 0

            /**
             * Read exactly [rowsExpected] body lines of a section. A section never
             * swallows the line that follows it: a known section header or item
             * keyword ends the read, so a block that is too short reports
             * "$section rows n, expected m" instead of stealing the next item.
             */
            fun takeRows(section: String, rowsExpected: Int): List<String> {
                val rows = ArrayList<String>()
                while (rows.size < rowsExpected && lineIndex < lines.size) {
                    val next = lines[lineIndex].trim()
                    if (next == SECTION_TERRAIN || next == SECTION_FIRE ||
                        next == SECTION_WALL_VERT || next == SECTION_WALL_HORIZ ||
                        next == "$ITEM_ROVER" || next == "$ITEM_CIVILIAN" ||
                        next.startsWith("$ITEM_ROVER ") || next.startsWith("$ITEM_CIVILIAN ")) break
                    rows.add(lines[lineIndex++])
                }
                if (rows.size != rowsExpected) problems.add("$section rows ${rows.size}, expected $rowsExpected")
                return rows
            }

            val header = if (lines.isNotEmpty()) lines[0].trim() else ""
            if (header != "$CANONICAL_HEADER $GRID") problems.add("first line must be '$CANONICAL_HEADER $GRID' (got '$header')")
            lineIndex = 1
            while (lineIndex < lines.size) {
                val line = lines[lineIndex].trim()
                lineIndex++
                if (line.isEmpty()) continue
                when {
                    line == SECTION_TERRAIN -> {
                        if (terrainRows != null) problems.add("duplicate $SECTION_TERRAIN section")
                        terrainRows = takeRows(SECTION_TERRAIN, GRID)
                    }
                    line == SECTION_FIRE -> {
                        if (fireRows != null) problems.add("duplicate $SECTION_FIRE section")
                        fireRows = takeRows(SECTION_FIRE, GRID)
                    }
                    line == SECTION_WALL_VERT -> {
                        if (vertRows != null) problems.add("duplicate $SECTION_WALL_VERT section")
                        vertRows = takeRows(SECTION_WALL_VERT, WALL_VERT_ROWS)
                    }
                    line == SECTION_WALL_HORIZ -> {
                        if (horizRows != null) problems.add("duplicate $SECTION_WALL_HORIZ section")
                        horizRows = takeRows(SECTION_WALL_HORIZ, WALL_HORIZ_ROWS)
                    }
                    line.startsWith("$ITEM_ROVER ") -> {
                        val parts = line.substring(ITEM_ROVER.length + 1).split(',')
                        if (spawns != 0) problems.add("duplicate $ITEM_ROVER item")
                        else if (parts.size != 3) problems.add("$ITEM_ROVER item must be 'ROVER x,y,dir' (got '$line')")
                        else {
                            val nums = parts.map { it.trim().toIntOrNull() }
                            if (nums.any { it == null }) problems.add("$ITEM_ROVER item needs integers (got '$line')")
                            else { spawnX = nums[0]!!; spawnY = nums[1]!!; spawnDir = nums[2]!!; spawns++ }
                        }
                    }
                    line.startsWith("$ITEM_CIVILIAN ") -> {
                        val parts = line.substring(ITEM_CIVILIAN.length + 1).split(',')
                        if (parts.size != 5) problems.add("$ITEM_CIVILIAN item must be 'CIVILIAN id,x,y,hp,1|0' (got '$line')")
                        else {
                            val nums = parts.map { it.trim().toIntOrNull() }
                            if (nums.any { it == null }) problems.add("$ITEM_CIVILIAN item needs integers (got '$line')")
                            else civs.add(CivilianSpec(nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!, nums[4]!! == 1))
                        }
                    }
                    else -> problems.add("unknown section or item: '$line'")
                }
            }
            if (terrainRows == null) problems.add("missing $SECTION_TERRAIN section")
            if (fireRows == null) problems.add("missing $SECTION_FIRE section")
            if (spawns != 1) problems.add("missing or duplicated $ITEM_ROVER item (found $spawns)")

            if (terrainRows != null) {
                val rows = terrainRows
                for (y in 0 until rows.size) {
                    val row = rows[y]
                    for (x in 0 until row.length) {
                        if (row[x] == GLYPH_ROVER)
                            problems.add("terrain row $y char 'R' at x=$x is not a known glyph: the spawn lives in the $ITEM_ROVER item")
                        else if (!TERRAIN_GLYPHS.contains(row[x]))
                            problems.add("terrain row $y char '${row[x]}' at x=$x is not a known glyph")
                    }
                }
                if (spawns == 1 && inBounds(spawnX, spawnY) && rows[spawnY].length == GRID &&
                    rows[spawnY][spawnX] != GLYPH_FLOOR)
                    problems.add("rover spawn cell ($spawnX,$spawnY) is not floor in the $SECTION_TERRAIN section")
            }
            if (spawns == 1) {
                if (!inBounds(spawnX, spawnY)) problems.add("rover spawn outside grid: ($spawnX,$spawnY)")
                if (spawnDir !in 0..3) problems.add("rover facing $spawnDir outside 0..3")
            }
            if (civs.any { it.hp !in 0..MAX_HP }) problems.add("civilian hp outside 0..$MAX_HP")

            if (problems.isNotEmpty()) throw IllegalArgumentException("invalid mission text:\n" + problems.joinToString("\n"))

            // The terrain section states terrain only, so the spawn marker is
            // re-injected before the ASCII decode runs (decode needs exactly one).
            val grid = terrainRows!!.map { it.toCharArray() }
            grid[spawnY][spawnX] = GLYPH_ROVER
            return decode(
                grid.map { it.concatToString() }, fireRows!!, spawnDir, civs,
                vertRows ?: emptyList(), horizRows ?: emptyList(),
            )
        }
    }
}
