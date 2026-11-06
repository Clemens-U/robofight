package robofight.fireline

/**
 * Static authored layout: terrain, starting fire intensities, rover spawn,
 * civilians (FIRELINE-DESIGN.md §3.1-§3.4, §9 validation contract).
 *
 * Terrain is immutable for the whole run: FIRELINE v1 has no destructible
 * terrain, so a different mission seed can change fire and RNG streams but can
 * NEVER alter authored static terrain (PLAN §8.4). Dynamic per-cell fire state
 * is owned by [FirelineWorld]; [Layout] only carries the authored starting
 * intensities.
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

/** §3.2: walls block rover, water and line drawing; protected assets are impassable. */
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
 * intensities, one rover spawn, civilians. Built either from explicit arrays
 * or from the ASCII authoring form (see [decode]).
 */
class Layout(
    val terrain: IntArray,
    val fire: IntArray,
    val roverX: Int,
    val roverY: Int,
    val roverDir: Int,
    val civilians: List<CivilianSpec>,
) {
    init {
        require(terrain.size == CELLS) { "layout terrain must have $CELLS cells (got ${terrain.size})" }
        require(fire.size == CELLS) { "layout fire must have $CELLS cells (got ${fire.size})" }
    }

    fun terrainAt(x: Int, y: Int): Int = terrain[cellIndex(x, y)]
    fun fireAt(x: Int, y: Int): Int = fire[cellIndex(x, y)]

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
     * Layout validation (§9 "Every authored mission requires ... validation"):
     * exactly one spawn, cells inside the grid, no civilian on a wall or a
     * protected asset, fire only on burnable terrain, ranges legal, and every
     * objective-relevant cell reachable from the spawn through passable cells.
     * Empty list = valid.
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
        // Reachability: spawn flood fill over passable cells must reach every
        // civilian, every exit and every starting fire cell.
        if (inBounds(roverX, roverY) && terrainPassable(terrainAt(roverX, roverY))) {
            val reached = floodFillPassable()
            for (c in civilians)
                if (!reached[cellIndex(c.x, c.y)]) problems.add("civilian ${c.id} unreachable from spawn: (${c.x},${c.y})")
            for ((x, y) in exitCells())
                if (!reached[cellIndex(x, y)]) problems.add("exit ($x,$y) unreachable from spawn")
            for (i in 0 until CELLS)
                if (fire[i] > 0 && !reached[i]) problems.add("starting fire at ${indexX(i)},${indexY(i)} unreachable from spawn")
        }
        return problems
    }

    private fun floodFillPassable(): BooleanArray {
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
                val ni = cellIndex(nx, ny)
                if (reached[ni] || !terrainPassable(terrain[ni])) continue
                reached[ni] = true
                stack.addLast(ni)
            }
        }
        return reached
    }

    override fun toString(): String = "Layout(spawn=$roverX,$roverY dir=$roverDir civilians=${civilians.size})"

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
         */
        fun decode(
            terrainRows: List<String>,
            fireRows: List<String>,
            roverDir: Int = DIR_E,
            civilians: List<CivilianSpec> = emptyList(),
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
            if (spawns != 1) problems.add("layout must contain exactly one '$GLYPH_ROVER' spawn (found $spawns)")
            val civs = if (civilians.isNotEmpty()) {
                if (decodedCiv.isNotEmpty()) problems.add("layout uses '$GLYPH_CIVILIAN' glyphs and an explicit civilian list")
                civilians
            } else decodedCiv

            if (problems.isNotEmpty()) throw IllegalArgumentException("invalid layout:\n" + problems.joinToString("\n"))
            return Layout(terrain, fire, spawnX, spawnY, roverDir, civs)
        }

        /** Render a layout back to the ASCII terrain form (round-trip check for authored content). */
        fun encodeTerrain(layout: Layout): List<String> =
            (0 until GRID).map { y -> (0 until GRID).map { x -> terrainGlyph(layout.terrainAt(x, y)) }.joinToString("") }
    }
}
