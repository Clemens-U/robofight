package robofight.fireline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLAN-EDGE-WALLS.md step E1: thin edge walls in the FIRELINE engine.
 *
 * Every expectation here comes from a named fixture (the fixture wall table, the
 * fixture terrain rows, or a committed anchor in `docs/web/fixtures/`), never
 * from a number typed by hand. The Arena behaviours mirrored are cited at each
 * gate:
 *  - a blocked MOVE is a silent no-op that still costs its ticks (`World.kt:169`),
 *  - a shot that would spawn through an edge wall never materializes
 *    (`World.kt:359-363`),
 *  - a projectile that runs into an edge wall is absorbed there (`World.kt:432`),
 *  - `AHEAD` answers the single blocked sentinel 1 (`World.kt:288`),
 *  - an edge never makes a cell non-free and the outer border is implicit
 *    (`Arena.kt:20-21`, `Arena.kt:68-71`).
 */
class EdgeWallsTest {

    // ==================== fixture mission source ====================

    private companion object {
        const val SPAWN_X = 2
        const val SPAWN_Y = 2
        const val EXIT_X = 17
        const val EXIT_Y = 2

        /**
         * `WALL_VERT` walls of the fixture source, keyed `row y -> x` for the edge
         * to the RIGHT of cell `(x, y)` (`Arena.kt:37-42`). Row 2 char 5 is the
         * lane gate between (5,2) and (6,2): the rover lane runs east along row 2
         * and stops there.
         */
        val FIXTURE_VERT_WALLS: Map<Int, Set<Int>> = mapOf(2 to setOf(5))

        /**
         * `WALL_HORIZ` walls of the fixture source, keyed `row y -> x` for the edge
         * BELOW cell `(x, y)` (`Arena.kt:43-48`). Row 1 char 2 seals (2,2) from
         * (2,1); row 2 char 2 seals (2,2) from (2,3). The spawn cell is walled on
         * two sides and open on two.
         */
        val FIXTURE_HORIZ_WALLS: Map<Int, Set<Int>> = mapOf(1 to setOf(2), 2 to setOf(2))

        const val MOVE_LOOP = "loop:  MOVE\n       JMP    loop"

        /** One SHOOT (three ticks), then idle forever: exactly one packet. */
        const val SHOOT_ONCE = "fire:  SHOOT\nloop:  WAIT\n       JMP    loop"
    }

    /** ASCII authoring form: terrain rows carry the `R` spawn marker. */
    private val fixtureTerrainRows: List<String> = editRows(
        openTerrainRows(SPAWN_X, SPAWN_Y),
        listOf(Triple(EXIT_X, EXIT_Y, GLYPH_EXIT)),
    )

    /**
     * The same terrain for the canonical text form, where the terrain section
     * states terrain only and the spawn lives in the `ROVER` item
     * (`Layout.fromCanonicalText`).
     */
    private val fixtureTerrainSection: List<String> = fixtureTerrainRows.map { it.replace(GLYPH_ROVER, GLYPH_FLOOR) }

    private fun vertRowsOf(walls: Map<Int, Set<Int>>): List<String> = (0 until WALL_VERT_ROWS).map { y ->
        (0 until VERT_EDGE_COLUMNS).map { x ->
            if (walls[y]?.contains(x) == true) EDGE_GLYPH_WALL else EDGE_GLYPH_OPEN
        }.joinToString("")
    }

    private fun horizRowsOf(walls: Map<Int, Set<Int>>): List<String> = (0 until WALL_HORIZ_ROWS).map { y ->
        (0 until GRID).map { x ->
            if (walls[y]?.contains(x) == true) EDGE_GLYPH_WALL else EDGE_GLYPH_OPEN
        }.joinToString("")
    }

    /** x positions of `#` in one encoded wall row. */
    private fun wallsIn(row: String): Set<Int> = (0 until row.length).filter { row[it] == EDGE_GLYPH_WALL }.toSet()

    private fun withVert(walls: Map<Int, Set<Int>>, y: Int, x: Int): Map<Int, Set<Int>> {
        val m = walls.toMutableMap()
        m[y] = (m[y] ?: emptySet()) + x
        return m
    }

    private fun withHoriz(walls: Map<Int, Set<Int>>, y: Int, x: Int): Map<Int, Set<Int>> {
        val m = walls.toMutableMap()
        m[y] = (m[y] ?: emptySet()) + x
        return m
    }

    private fun withoutVert(walls: Map<Int, Set<Int>>, y: Int, x: Int): Map<Int, Set<Int>> {
        val m = walls.toMutableMap()
        val rest = (m[y] ?: emptySet()) - x
        if (rest.isEmpty()) m.remove(y) else m[y] = rest
        return m
    }

    /** Fixture wall table plus the wall on the spawn cell's east edge. */
    private val eastOfSpawn: Map<Int, Set<Int>> = withVert(FIXTURE_VERT_WALLS, SPAWN_Y, SPAWN_X)

    /** The fixture mission source: 20 terrain rows, 20 fire rows, both wall blocks. */
    private fun fixtureSource(
        vertWalls: Map<Int, Set<Int>> = FIXTURE_VERT_WALLS,
        horizWalls: Map<Int, Set<Int>> = FIXTURE_HORIZ_WALLS,
        dir: Int = DIR_E,
    ): String = buildString {
        append("$CANONICAL_HEADER $GRID\n")
        append("$SECTION_TERRAIN\n").append(fixtureTerrainSection.joinToString("\n")).append('\n')
        append("$SECTION_FIRE\n").append(noFireRows().joinToString("\n")).append('\n')
        append("$SECTION_WALL_VERT\n").append(vertRowsOf(vertWalls).joinToString("\n")).append('\n')
        append("$SECTION_WALL_HORIZ\n").append(horizRowsOf(horizWalls).joinToString("\n")).append('\n')
        append("$ITEM_ROVER $SPAWN_X,$SPAWN_Y,$dir\n")
    }

    private fun fixtureLayout(
        vertWalls: Map<Int, Set<Int>> = FIXTURE_VERT_WALLS,
        horizWalls: Map<Int, Set<Int>> = FIXTURE_HORIZ_WALLS,
        dir: Int = DIR_E,
    ): Layout = Layout.fromCanonicalText(fixtureSource(vertWalls, horizWalls, dir))

    private fun worldOfFixture(layout: Layout, firmware: String = IDLE_FIRMWARE): FirelineWorld =
        FirelineWorld(descriptorOf(layout = layout, firmwareSource = firmware))

    /** Count of the walls the fixture table declares; every wall count cites it. */
    private fun fixtureWallCount(
        vertWalls: Map<Int, Set<Int>> = FIXTURE_VERT_WALLS,
        horizWalls: Map<Int, Set<Int>> = FIXTURE_HORIZ_WALLS,
    ): Int = vertWalls.values.sumOf { it.size } + horizWalls.values.sumOf { it.size }

    private fun assertSameLayoutArrays(a: Layout, b: Layout, context: String) {
        assertTrue(a.terrainRowMajor().contentEquals(b.terrainRowMajor()), "$context: terrain arrays differ")
        assertTrue(a.fireRowMajor().contentEquals(b.fireRowMajor()), "$context: fire arrays differ")
        assertTrue(a.wallVertRowMajor().contentEquals(b.wallVertRowMajor()), "$context: WALL_VERT arrays differ")
        assertTrue(a.wallHorizRowMajor().contentEquals(b.wallHorizRowMajor()), "$context: WALL_HORIZ arrays differ")
        assertEquals(a.roverX to a.roverY, b.roverX to b.roverY, "$context: rover spawn")
        assertEquals(a.roverDir, b.roverDir, "$context: rover facing")
        assertEquals(a.civilians, b.civilians, "$context: civilians")
    }

    // ==================== 1. the frozen model (EdgeWalls.kt) ====================

    @Test
    fun edgeModelRestatesArenaIndexingForGrid20() {
        assertEquals(GRID, 20, "FirelineRules.kt:25 GRID = 20")
        assertEquals(VERT_EDGE_COLUMNS, GRID - 1, "vertical edges exist for x in 0..GRID-2 (Arena.kt:39-40)")
        assertEquals(VERT_EDGE_SLOTS, (GRID - 1) * GRID, "vertical edge slots (GRID-1) x GRID (Arena.kt:40)")
        assertEquals(VERT_EDGE_SLOTS, 380, "380 vertical edges on a 20x20 grid")
        assertEquals(HORIZ_EDGE_SLOTS, GRID * (GRID - 1), "horizontal edge slots GRID x (GRID-1) (Arena.kt:46)")
        assertEquals(HORIZ_EDGE_SLOTS, 380, "380 horizontal edges on a 20x20 grid")
        assertEquals(EDGE_SLOTS_TOTAL, VERT_EDGE_SLOTS + HORIZ_EDGE_SLOTS, "760 edges per layout")
        assertEquals(EDGE_SLOTS_TOTAL, 760, "760 edges per layout")
        // Index rules verbatim: y * (GRID-1) + x and y * GRID + x (Arena.kt:40, Arena.kt:46).
        assertEquals(19 * 19 + 18, vertEdgeIndex(VERT_EDGE_COLUMNS - 1, GRID - 1), "last vertical edge key stays inside the array")
        assertEquals(18 * 20 + 19, horizEdgeIndex(GRID - 1, GRID - 2), "last horizontal edge key stays inside the array")
        assertTrue(vertEdgeInBounds(18, 19) && !vertEdgeInBounds(19, 19), "no vertical slot for x = GRID-1 (Arena.kt:39-40)")
        assertTrue(horizEdgeInBounds(19, 18) && !horizEdgeInBounds(19, 19), "no horizontal slot for y = GRID-1 (Arena.kt:45-46)")
        val openV = openVertEdges()
        val openH = openHorizEdges()
        assertEquals(VERT_EDGE_SLOTS, openV.size, "all-open vertical array has 380 slots")
        assertEquals(HORIZ_EDGE_SLOTS, openH.size, "all-open horizontal array has 380 slots")
        assertFalse(edgesPresent(openV, openH), "an all-open pair reports no walls (Arena.kt:88)")
        assertEquals(0, edgeCountOf(openV, openH), "an all-open pair has zero walls (Arena.kt:88)")
    }

    @Test
    fun wallBetweenNormalisesACardinalPairToExactlyOneLookup() {
        val v = openVertEdges()
        val h = openHorizEdges()
        v[vertEdgeIndex(5, 7)] = true          // edge RIGHT of (5,7), separating (5,7) from (6,7)
        h[horizEdgeIndex(5, 7)] = true         // edge BELOW (5,7), separating (5,7) from (5,8)
        assertTrue(wallBetweenEdges(v, h, 5, 7, 6, 7), "b right of a (Arena.kt:75)")
        assertTrue(wallBetweenEdges(v, h, 6, 7, 5, 7), "b left of a resolves to the edge right of b (Arena.kt:76)")
        assertTrue(wallBetweenEdges(v, h, 5, 7, 5, 8), "b below a (Arena.kt:77)")
        assertTrue(wallBetweenEdges(v, h, 5, 8, 5, 7), "b above a resolves to the edge below b (Arena.kt:78)")
        assertFalse(wallBetweenEdges(v, h, 5, 7, 6, 8), "a diagonal pair has no edge (Arena.kt:79)")
        assertFalse(wallBetweenEdges(v, h, 5, 7, 5, 7), "the same cell has no edge")
        assertFalse(wallBetweenEdges(v, h, 5, 7, 7, 7), "a two-cell gap has no edge")
        assertFalse(wallBetweenEdges(v, h, 5, 7, 4, 7), "the open edge left of (5,7) stays open")
        assertEquals(2, edgeCountOf(v, h), "exactly the two walls that were set")
    }

    @Test
    fun outerBorderIsImplicitAndNeverAnArraySlot() {
        val layout = Layout(IntArray(CELLS), IntArray(CELLS), 0, 0, DIR_E, emptyList())
        assertFalse(layout.wallsPresent(), "the border is not stored as an edge (Arena.kt:20-21)")
        assertEquals(0, layout.edgeCount(), "an empty layout fills no edge slot (Arena.kt:20-21)")
        assertTrue(layout.edgeBlocked(0, 0, DIR_W), "west of column 0 is the implicit border")
        assertTrue(layout.edgeBlocked(0, 0, DIR_N), "north of row 0 is the implicit border")
        assertTrue(layout.edgeBlocked(GRID - 1, GRID - 1, DIR_E), "east of column ${GRID - 1} is the implicit border")
        assertTrue(layout.edgeBlocked(GRID - 1, GRID - 1, DIR_S), "south of row ${GRID - 1} is the implicit border")
        assertFalse(layout.edgeBlocked(0, 0, DIR_E), "an open in-grid direction is open")
        assertFalse(layout.edgeBlocked(0, 0, DIR_S), "an open in-grid direction is open")
        assertFalse(layout.vertEdge.any { it } || layout.horizEdge.any { it }, "no array slot was consumed by the border")
    }

    // ==================== 2. mission source: parse and canonical text ====================

    @Test
    fun wallBlocksParseFromTheFixtureMissionSource() {
        val source = fixtureSource()
        val layout = Layout.fromCanonicalText(source)
        assertTrue(source.contains(SECTION_WALL_VERT), "the fixture source declares a WALL_VERT block")
        assertTrue(source.contains(SECTION_WALL_HORIZ), "the fixture source declares a WALL_HORIZ block")
        val (vertRows, horizRows) = Layout.encodeEdges(layout)
        assertEquals(WALL_VERT_ROWS, vertRows.size, "WALL_VERT block is 20 rows (PLAN-EDGE-WALLS.md section 4)")
        assertEquals(WALL_VERT_ROWS, GRID, "WALL_VERT row count equals GRID")
        assertTrue(vertRows.all { it.length == VERT_EDGE_COLUMNS }, "every WALL_VERT row is 19 chars: $vertRows")
        assertEquals(WALL_HORIZ_ROWS, horizRows.size, "WALL_HORIZ block is 19 rows (PLAN-EDGE-WALLS.md section 4)")
        assertEquals(WALL_HORIZ_ROWS, GRID - 1, "WALL_HORIZ row count equals GRID-1")
        assertTrue(horizRows.all { it.length == GRID }, "every WALL_HORIZ row is 20 chars: $horizRows")
        assertEquals(FIXTURE_VERT_WALLS[2], wallsIn(vertRows[2]), "decoded WALL_VERT row 2 walls (row text '${vertRows[2]}')")
        assertEquals(FIXTURE_HORIZ_WALLS[1], wallsIn(horizRows[1]), "decoded WALL_HORIZ row 1 walls (row text '${horizRows[1]}')")
        assertEquals(FIXTURE_HORIZ_WALLS[2], wallsIn(horizRows[2]), "decoded WALL_HORIZ row 2 walls (row text '${horizRows[2]}')")
        for (y in 0 until WALL_VERT_ROWS) if (y != 2) assertEquals(emptySet<Int>(), wallsIn(vertRows[y]), "fixture WALL_VERT row $y declares no wall")
        for (y in 0 until WALL_HORIZ_ROWS) if (y != 1 && y != 2) assertEquals(emptySet<Int>(), wallsIn(horizRows[y]), "fixture WALL_HORIZ row $y declares no wall")
        assertTrue(layout.wallsPresent(), "the fixture layout carries walls")
        assertEquals(fixtureWallCount(), layout.edgeCount(), "wall count equals the fixture wall table")
        assertEquals(3, layout.edgeCount(), "fixture table: WALL_VERT row 2 char 5, WALL_HORIZ rows 1 and 2 char 2")
        assertTrue(layout.wallBetween(5, 2, 6, 2), "WALL_VERT row 2 char 5 is '#' so (5,2) is walled from (6,2)")
        assertTrue(layout.wallBetween(2, 1, 2, 2), "WALL_HORIZ row 1 char 2 is '#' so (2,1) is walled from (2,2)")
        assertTrue(layout.wallBetween(2, 2, 2, 3), "WALL_HORIZ row 2 char 2 is '#' so (2,2) is walled from (2,3)")
        assertFalse(layout.wallBetween(2, 2, 3, 2), "WALL_VERT row 2 char 2 is '.' so (2,2) stays open to (3,2)")
    }

    @Test
    fun aSourceWithoutWallBlocksParsesToTheSameLayoutAsBefore() {
        val plain = Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E)
        val explicitEmpty = Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E, emptyList(), emptyList(), emptyList())
        assertSameLayoutArrays(plain, explicitEmpty, "empty wall arguments vs the old decode call")
        assertFalse(plain.wallsPresent(), "no wall block means no wall (PLAN-EDGE-WALLS.md section 4)")
        assertFalse(explicitEmpty.wallsPresent(), "empty wall arguments mean no wall")
        assertEquals(0, plain.edgeCount(), "a wall-less layout has zero edges")
        assertFalse(plain.vertEdge.any { it }, "an absent WALL_VERT block is all open")
        assertFalse(plain.horizEdge.any { it }, "an absent WALL_HORIZ block is all open")
        val text = plain.toCanonicalText()
        assertFalse(text.contains(SECTION_WALL_VERT), "wall-less canonical text emits no WALL_VERT block")
        assertFalse(text.contains(SECTION_WALL_HORIZ), "wall-less canonical text emits no WALL_HORIZ block")
        assertSameLayoutArrays(plain, Layout.fromCanonicalText(text), "wall-less canonical text round trip")
    }

    @Test
    fun wallBlocksAreValidatedAsStrictlyAsTerrainRows() {
        val goodV = vertRowsOf(FIXTURE_VERT_WALLS)
        val goodH = horizRowsOf(FIXTURE_HORIZ_WALLS)

        val rowsError = runCatching {
            Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E, emptyList(), goodV.take(19), goodH)
        }.exceptionOrNull()
        assertNotNull(rowsError, "a WALL_VERT block with 19 rows must fail")
        assertTrue(rowsError!!.message!!.contains("WALL_VERT rows 19, expected 20"), rowsError.message!!)

        val badLength = goodV.toMutableList().also { it[3] = it[3].drop(1) }
        val lengthError = runCatching {
            Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E, emptyList(), badLength, goodH)
        }.exceptionOrNull()
        assertNotNull(lengthError, "a WALL_VERT row of 18 chars must fail")
        assertTrue(lengthError!!.message!!.contains("WALL_VERT row 3 has 18 chars, expected 19"), lengthError.message!!)

        val badGlyph = goodV.toMutableList().also { it[0] = "|" + it[0].drop(1) }
        val glyphError = runCatching {
            Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E, emptyList(), badGlyph, goodH)
        }.exceptionOrNull()
        assertNotNull(glyphError, "a WALL_VERT row using '|' must fail: the block alphabet is '#' and '.'")
        assertTrue(glyphError!!.message!!.contains("WALL_VERT row 0 char '|' at x=0 is not a known wall glyph"), glyphError.message!!)

        val horizRowsError = runCatching {
            Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E, emptyList(), goodV, goodH.take(18))
        }.exceptionOrNull()
        assertNotNull(horizRowsError, "a WALL_HORIZ block with 18 rows must fail")
        assertTrue(horizRowsError!!.message!!.contains("WALL_HORIZ rows 18, expected 19"), horizRowsError.message!!)

        // A legal one-block source: the block that is absent stays all open.
        val onlyVert = Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E, emptyList(), goodV, emptyList())
        assertEquals(FIXTURE_VERT_WALLS.values.sumOf { it.size }, onlyVert.edgeCount(), "a source with only WALL_VERT declares 1 wall")
        assertFalse(onlyVert.horizEdge.any { it }, "a missing WALL_HORIZ block means all open (PLAN-EDGE-WALLS.md section 4)")
        val onlyHoriz = Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E, emptyList(), emptyList(), goodH)
        assertEquals(FIXTURE_HORIZ_WALLS.values.sumOf { it.size }, onlyHoriz.edgeCount(), "a source with only WALL_HORIZ declares 2 walls")
        assertFalse(onlyHoriz.vertEdge.any { it }, "a missing WALL_VERT block means all open")
        assertFalse(onlyHoriz.wallBetween(0, 0, 0, 1), "WALL_HORIZ row 0 declares no wall in the fixture")
    }

    @Test
    fun canonicalTextEmitsBothWallBlocksOnlyWhenAWallExists() {
        val lines = fixtureLayout().toCanonicalText().lines()
        val headerAt = lines.indexOf("$CANONICAL_HEADER $GRID")
        val terrainAt = lines.indexOf(SECTION_TERRAIN)
        val fireAt = lines.indexOf(SECTION_FIRE)
        val wallVertAt = lines.indexOf(SECTION_WALL_VERT)
        val wallHorizAt = lines.indexOf(SECTION_WALL_HORIZ)
        val roverAt = lines.indexOfFirst { it.startsWith("$ITEM_ROVER ") }
        assertEquals(0, headerAt, "the text starts with the canonical header")
        assertEquals(1, terrainAt, "TERRAIN is the first section")
        assertEquals(terrainAt + 1 + GRID, fireAt, "FIRE starts right after the 20 terrain rows")
        assertEquals(fireAt + 1 + GRID, wallVertAt, "WALL_VERT starts right after the 20 fire rows")
        assertEquals(wallVertAt + 1 + WALL_VERT_ROWS, wallHorizAt, "WALL_HORIZ starts right after the 20 WALL_VERT rows")
        assertEquals(wallHorizAt + 1 + WALL_HORIZ_ROWS, roverAt, "the items start right after the 19 WALL_HORIZ rows")
        // toCanonicalText ends with one newline, so lines() reports one empty trailing element.
        assertEquals(lines.size - 2, roverAt, "the ROVER item is the last content line")

        val wallLess = Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E).toCanonicalText()
        assertFalse(wallLess.contains(SECTION_WALL_VERT), "no WALL_VERT block when no wall exists")
        assertFalse(wallLess.contains(SECTION_WALL_HORIZ), "no WALL_HORIZ block when no wall exists")
        assertEquals(
            roverAt - (1 + WALL_VERT_ROWS + 1 + WALL_HORIZ_ROWS),
            wallLess.lines().indexOfFirst { it.startsWith("$ITEM_ROVER ") },
            "a wall-less text is 41 lines shorter: two headers and their 39 rows",
        )
        assertSameLayoutArrays(Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E), Layout.fromCanonicalText(wallLess), "wall-less text round trip")
        assertEquals(wallLess, Layout.fromCanonicalText(wallLess).toCanonicalText(), "wall-less text is byte-identical after a round trip")
    }

    @Test
    fun fixtureMissionSourceRoundTripsThroughEncodeAndReparse() {
        val first = fixtureLayout()
        val text = first.toCanonicalText()
        val again = Layout.fromCanonicalText(text)
        assertSameLayoutArrays(first, again, "fixture wall-carrying source round trip")
        assertEquals(text, again.toCanonicalText(), "re-encoding the reparsed fixture yields the same bytes")
        assertEquals(first.edgeCount(), again.edgeCount(), "wall count survives the round trip")
        assertTrue(again.wallsPresent(), "the reparsed fixture still carries walls")
        assertTrue(
            first.wallVertRowMajor().contentEquals(again.wallVertRowMajor()),
            "every WALL_VERT edge round-trips (fixture row 2: '${vertRowsOf(FIXTURE_VERT_WALLS)[2]}')",
        )
        assertTrue(
            first.wallHorizRowMajor().contentEquals(again.wallHorizRowMajor()),
            "every WALL_HORIZ edge round-trips (fixture row 1: '${horizRowsOf(FIXTURE_HORIZ_WALLS)[1]}')",
        )
    }

    @Test
    fun canonicalTextRejectsAMalformedMissionSource() {
        val shortBlock = fixtureSource().lines().toMutableList()
        val header = shortBlock.indexOf(SECTION_WALL_HORIZ)
        shortBlock.removeAt(header + 18)                  // drop one WALL_HORIZ row
        val rowsError = runCatching { Layout.fromCanonicalText(shortBlock.joinToString("\n")) }.exceptionOrNull()
        assertNotNull(rowsError, "a WALL_HORIZ block with 18 rows must fail")
        assertTrue(rowsError!!.message!!.contains("WALL_HORIZ rows 18, expected 19"), rowsError.message!!)

        val unknown = fixtureSource() + "NOTES thin walls teach the lane\n"
        val unknownError = runCatching { Layout.fromCanonicalText(unknown) }.exceptionOrNull()
        assertNotNull(unknownError, "an unknown section or item must fail")
        assertTrue(unknownError!!.message!!.contains("unknown section or item: 'NOTES"), unknownError.message!!)

        val spawnOnExit = fixtureSource().replace("$ITEM_ROVER $SPAWN_X,$SPAWN_Y,$DIR_E", "$ITEM_ROVER $EXIT_X,$EXIT_Y,$DIR_E")
        val spawnError = runCatching { Layout.fromCanonicalText(spawnOnExit) }.exceptionOrNull()
        assertNotNull(spawnError, "a ROVER item that points at the exit cell must fail")
        assertTrue(spawnError!!.message!!.contains("rover spawn cell ($EXIT_X,$EXIT_Y) is not floor"), spawnError.message!!)

        val markedRows = fixtureTerrainSection.toMutableList()
        markedRows[SPAWN_Y] = markedRows[SPAWN_Y].take(SPAWN_X) + GLYPH_ROVER + markedRows[SPAWN_Y].drop(SPAWN_X + 1)
        val markerInTerrain = fixtureSource().replace(
            fixtureTerrainSection.joinToString("\n"),
            markedRows.joinToString("\n"),
        )
        val markerError = runCatching { Layout.fromCanonicalText(markerInTerrain) }.exceptionOrNull()
        assertNotNull(markerError, "an 'R' marker inside the TERRAIN section must fail")
        assertTrue(markerError!!.message!!.contains("char 'R' at x=$SPAWN_X is not a known glyph"), markerError.message!!)
    }

    // ==================== 3. gates ====================

    @Test
    fun blockedMoveAcrossAnEdgeWallIsASilentNoOp() {
        val layout = fixtureLayout()
        assertTrue(layout.wallBetween(5, 2, 6, 2), "fixture WALL_VERT row 2 char 5 is a wall")
        val blocked = worldOfFixture(layout, MOVE_LOOP)
        val open = worldOfFixture(fixtureLayout(vertWalls = withoutVert(FIXTURE_VERT_WALLS, 2, 5)), MOVE_LOOP)
        // MOVE costs two ticks and the JMP costs one, so one lane step per three ticks.
        repeat(8) { blocked.tickOnce(); open.tickOnce() }        // MOVE completes on ticks 2, 5, 8
        assertEquals(SPAWN_X + 3, blocked.rover.x, "three MOVEs reach (5,2)")
        assertEquals(SPAWN_Y, blocked.rover.y, "the lane stays on row 2")
        assertEquals(3, blocked.rover.distanceMoved, "three successful MOVEs so far")
        blocked.tickOnce(); open.tickOnce()                       // tick 9: the JMP
        blocked.tickOnce(); open.tickOnce()                       // tick 10: MOVE (1/2) against the edge wall
        assertEquals(5, blocked.rover.x, "the blocked MOVE did not move the rover")
        assertEquals(2, blocked.rover.y, "the blocked MOVE did not move the rover")
        blocked.tickOnce(); open.tickOnce()                       // tick 11: MOVE (2/2), the completion tick
        assertEquals("MOVE (2/2)", blocked.lastOp, "lastOp still records the attempted MOVE (World.kt:169)")
        assertEquals(11, blocked.tick, "the tick advanced anyway: MOVE still cost its two ticks (PLAN-EDGE-WALLS.md section 3 gate 1)")
        assertEquals(3, blocked.rover.distanceMoved, "a blocked MOVE adds no distance")
        assertEquals(0, blocked.rover.heat, "MOVE adds no heat")
        assertTrue(blocked.lastEvents.isEmpty(), "a blocked MOVE is silent: ${blocked.lastEvents}")
        assertEquals(SPAWN_X + 4, open.rover.x, "the same firmware without that wall reaches (6,2)")
        assertEquals(4, open.rover.distanceMoved, "the open lane completed four MOVEs")
        assertEquals(
            blocked.finalStateHash(),
            worldOfFixture(fixtureLayout(), MOVE_LOOP).also { w -> repeat(11) { w.tickOnce() } }.finalStateHash(),
            "the blocked run is reproducible from (layout, rules, seed, firmware)",
        )
        assertTrue(
            blocked.finalStateHash() != open.finalStateHash(),
            "removing the edge wall changes the state, so the gate really ran",
        )
    }

    @Test
    fun anOpenEdgeBetweenTwoWalledNeighboursStillMoves() {
        val layout = fixtureLayout()
        assertTrue(layout.edgeBlocked(SPAWN_X, SPAWN_Y, DIR_N), "fixture WALL_HORIZ row 1 char 2 seals north")
        assertTrue(layout.edgeBlocked(SPAWN_X, SPAWN_Y, DIR_S), "fixture WALL_HORIZ row 2 char 2 seals south")
        assertFalse(layout.edgeBlocked(SPAWN_X, SPAWN_Y, DIR_E), "fixture WALL_VERT row 2 char 2 is '.' so east is open")
        assertFalse(layout.edgeBlocked(SPAWN_X, SPAWN_Y, DIR_W), "no wall declares the west edge, so it is open")
        val w = worldOfFixture(layout, MOVE_LOOP)
        w.tickOnce(); w.tickOnce()
        assertEquals(SPAWN_X + 1, w.rover.x, "the open east edge is walkable while north and south are walled")
        assertEquals(SPAWN_Y, w.rover.y, "the rover stayed on row 2")
        assertEquals(1, w.rover.distanceMoved, "one successful MOVE")
    }

    @Test
    fun aPacketSpawnedThroughAnEdgeWallIsRefusedButTheShotStillCosts() {
        val sealed = fixtureLayout(vertWalls = eastOfSpawn)
        assertTrue(sealed.wallBetween(SPAWN_X, SPAWN_Y, SPAWN_X + 1, SPAWN_Y), "fixture variant adds WALL_VERT row 2 char 2")
        assertEquals(emptyList<String>(), sealed.validate(), "the variant stays a valid mission: the spawn cell keeps its west edge open")
        val w = worldOfFixture(sealed, SHOOT_ONCE)
        repeat(3) { w.tickOnce() }
        assertEquals(0, w.packetCount(), "the shot never materializes through an edge wall (World.kt:359-363)")
        assertEquals(90, w.rover.water, "the discharge still consumed 10 water")
        assertEquals(20, w.rover.heat, "the discharge still added 20 heat")
        assertEquals(1, w.rover.shots, "the shot still counted")
        assertEquals(3, w.tick, "the shot still occupied its three ticks")
        assertTrue(w.lastEvents.contains("PACKET BLOCKED EDGE ${SPAWN_X + 1},$SPAWN_Y"), "new event shape (PLAN-EDGE-WALLS.md section 3 gate 3): ${w.lastEvents}")
    }

    @Test
    fun aPacketIsAbsorbedAtAnEdgeWallAndCrossesTheSameEdgeWhenItIsOpen() {
        val walled = worldOfFixture(fixtureLayout(), SHOOT_ONCE)
        val open = worldOfFixture(fixtureLayout(vertWalls = withoutVert(FIXTURE_VERT_WALLS, 2, 5)), SHOOT_ONCE)
        repeat(3) { walled.tickOnce(); open.tickOnce() }
        assertTrue(walled.hasWaterPacketAt(3, 2), "the packet spawns in the cell in front of the rover")
        assertTrue(walled.lastEvents.contains("PACKET -> 3,2"), "the open facing edge spawned the packet: ${walled.lastEvents}")
        walled.tickOnce(); open.tickOnce()                        // tick 4: advance to (4,2)
        assertTrue(walled.hasWaterPacketAt(4, 2), "the packet crossed the open edge at WALL_VERT row 2 char 3")
        walled.tickOnce(); open.tickOnce()                        // tick 5: advance to (5,2)
        assertTrue(walled.hasWaterPacketAt(5, 2), "the packet reached the cell in front of the wall")
        walled.tickOnce(); open.tickOnce()                        // tick 6: the blocked step
        assertFalse(walled.hasWaterPacketAt(6, 2), "a packet never crosses a walled edge")
        assertEquals(0, walled.packetCount(), "the Arena absorbs a projectile at the edge it hits (World.kt:432)")
        assertTrue(walled.lastEvents.contains("PACKET SPENT EDGE 6,2"), "new event shape (PLAN-EDGE-WALLS.md section 3 gate 4): ${walled.lastEvents}")
        assertTrue(open.hasWaterPacketAt(6, 2), "the same packet reaches (6,2) with that wall absent")
        assertEquals(1, open.packetCount(), "the open lane keeps the packet alive")
        assertEquals(6, open.packetsOrdered().first().x, "the surviving packet sits on (6,2)")
    }

    @Test
    fun aheadReportsOneSentinelForAnEdgeWallAWallCellAndTheBorder() {
        val open = worldOfFixture(fixtureLayout())
        assertEquals(0, open.aheadBlocked(), "fixture WALL_VERT row 2 char 2 is '.' so the read ahead is open")
        assertEquals(0, open.sensor(FirelinePorts.AHEAD), "port 11 agrees with aheadBlocked()")

        val sealed = worldOfFixture(fixtureLayout(vertWalls = eastOfSpawn))
        assertEquals(TERRAIN_FLOOR, sealed.terrainAt(SPAWN_X + 1, SPAWN_Y), "the cell ahead is floor, only the edge is walled")
        assertEquals(1, sealed.aheadBlocked(), "AHEAD reads the wall sentinel across an edge wall (World.kt:288)")
        assertEquals(1, sealed.sensor(FirelinePorts.AHEAD), "port 11 reads the same sentinel")

        val border = FirelineWorld(descriptorOf(layout = layoutOf(openTerrainRows(GRID - 1, SPAWN_Y), dir = DIR_E)))
        assertEquals(1, border.aheadBlocked(), "the outer border already reads 1 today")

        val cellWall = FirelineWorld(
            descriptorOf(layout = layoutOf(editRows(openTerrainRows(SPAWN_X, SPAWN_Y), listOf(Triple(3, 2, GLYPH_WALL))))),
        )
        assertEquals(1, cellWall.aheadBlocked(), "a wall cell already reads 1 today")
        assertEquals(cellWall.aheadBlocked(), sealed.aheadBlocked(), "one consistent sentinel for a thin wall and a wall cell")
        assertEquals(border.aheadBlocked(), sealed.aheadBlocked(), "one consistent sentinel for a thin wall and the border")
    }

    // ==================== 4. validation and reachability ====================

    @Test
    fun validationRejectsAnExitSealedByEdgeWallsAndAcceptsItWithThatWallRemoved() {
        val sealedVert = withVert(withVert(FIXTURE_VERT_WALLS, EXIT_Y, EXIT_X - 1), EXIT_Y, EXIT_X)
        val sealedHoriz = withHoriz(withHoriz(FIXTURE_HORIZ_WALLS, EXIT_Y - 1, EXIT_X), EXIT_Y, EXIT_X)
        val sealed = fixtureLayout(vertWalls = sealedVert, horizWalls = sealedHoriz)
        assertTrue(sealed.wallsPresent(), "the sealed variant carries walls")
        assertEquals(
            fixtureWallCount(sealedVert, sealedHoriz), sealed.edgeCount(),
            "the sealed variant declares its walls (WALL_VERT row ${EXIT_Y} chars ${EXIT_X - 1} and $EXIT_X, WALL_HORIZ rows ${EXIT_Y - 1} and $EXIT_Y char $EXIT_X)",
        )
        val problems = sealed.validate()
        assertEquals(1, problems.size, "exactly one validation problem expected: $problems")
        assertTrue(
            problems.first().startsWith("exit ($EXIT_X,$EXIT_Y) unreachable from spawn"),
            "the exit keeps its existing message shape (Layout.validate): $problems",
        )
        assertTrue(
            problems.first().endsWith(" blocked by an edge wall"),
            "the new wording names the walls as the only cause: $problems",
        )
        assertEquals(
            emptyList<String>(),
            fixtureLayout(vertWalls = withoutVert(sealedVert, EXIT_Y, EXIT_X - 1), horizWalls = sealedHoriz).validate(),
            "the same source with the exit's west edge wall removed is valid",
        )
    }

    @Test
    fun aFullyEdgeSealedCellIsStillOccupiableAndStillReportsItsTerrain() {
        // A rover cell walled on all four edges in a source with no exit, civilian
        // or starting fire: validation has no unreachable target to report, so the
        // rover legally stands in a cell it can never leave.
        val sealed = Layout.decode(
            terrainRows = openTerrainRows(SPAWN_X, SPAWN_Y),
            fireRows = noFireRows(),
            roverDir = DIR_E,
            vertEdgeRows = vertRowsOf(mapOf(SPAWN_Y to setOf(SPAWN_X - 1, SPAWN_X))),
            horizEdgeRows = horizRowsOf(mapOf(SPAWN_Y - 1 to setOf(SPAWN_X), SPAWN_Y to setOf(SPAWN_X))),
        )
        assertEquals(emptyList<String>(), sealed.validate(), "walls gate movement, never occupancy")
        assertTrue(terrainPassable(sealed.terrainAt(SPAWN_X, SPAWN_Y)), "the sealed cell is still passable terrain (Arena.kt:68-71)")
        for (d in 0..3) assertTrue(sealed.edgeBlocked(SPAWN_X, SPAWN_Y, d), "every direction out of the cell is walled")
        assertEquals(4, sealed.edgeCount(), "the four edges around the spawn cell are walls")
        val w = worldOfFixture(sealed, MOVE_LOOP)
        repeat(4) { w.tickOnce() }                                // two blocked MOVE attempts
        assertEquals(SPAWN_X, w.rover.x, "the rover cannot leave a fully edge-sealed cell")
        assertEquals(SPAWN_Y, w.rover.y, "the rover cannot leave a fully edge-sealed cell")
        assertEquals(0, w.rover.distanceMoved, "no move succeeded")
        assertEquals(4, w.tick, "both MOVE attempts still paid their two ticks each")
        assertEquals(TERRAIN_FLOOR, w.sensor(FirelinePorts.TILE), "IN TILE still reports the cell's terrain id")
        assertEquals(1, w.aheadBlocked(), "AHEAD reports the wall sentinel")
    }

    // ==================== 5. walls are never a terrain property ====================

    @Test
    fun wallsNeverMakeACellNonFreeAndAreNeverDerivedFromATerrainId() {
        val fixture = fixtureLayout()
        val wallless = Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E)
        for (i in 0 until CELLS) {
            assertEquals(wallless.terrain[i], fixture.terrain[i], "cell ${indexX(i)},${indexY(i)} terrain changed by a wall")
            assertEquals(
                terrainPassable(wallless.terrain[i]), terrainPassable(fixture.terrain[i]),
                "cell ${indexX(i)},${indexY(i)} passability changed by a wall (Arena.kt:68-71)",
            )
        }
        // A walled edge between two FLOOR cells still blocks the step.
        assertEquals(TERRAIN_FLOOR, fixture.terrainAt(5, 2), "fixture terrain row 2 char 5 is '${fixtureTerrainSection[2][5]}' = floor")
        assertEquals(TERRAIN_FLOOR, fixture.terrainAt(6, 2), "fixture terrain row 2 char 6 is '${fixtureTerrainSection[2][6]}' = floor")
        assertTrue(fixture.wallBetween(5, 2, 6, 2), "WALL_VERT row 2 char 5 lies between them")
        assertTrue(fixture.stepBlocked(5, 2, 6, 2), "two floor cells separated by an edge wall cannot be stepped between")
        // Terrain decides occupancy: an open edge says nothing about the cell ahead,
        // and a wall cell stays impassable with every touching edge open.
        val terrainWall = layoutOf(editRows(openTerrainRows(SPAWN_X, SPAWN_Y), listOf(Triple(3, 2, GLYPH_WALL))))
        assertFalse(terrainWall.wallsPresent(), "this layout declares no edges")
        assertFalse(terrainWall.edgeBlocked(SPAWN_X, SPAWN_Y, DIR_E), "no edge wall between (2,2) and the wall cell")
        assertTrue(terrainWall.stepBlocked(SPAWN_X, SPAWN_Y, 3, 2), "the wall cell is blocked by terrain alone (Arena.kt:84-85)")
        assertFalse(terrainPassable(TERRAIN_WALL), "terrainPassable reads the terrain id only (Arena.kt:68-71)")
        assertEquals(TERRAIN_FLOOR, terrainWall.terrainAt(SPAWN_X, SPAWN_Y), "the cell the rover stands on is floor")
        // Walled edges around a floor cell never turn it into a wall cell.
        val walledAround = fixtureLayout(
            vertWalls = withVert(FIXTURE_VERT_WALLS, 2, 3),
            horizWalls = withHoriz(withHoriz(FIXTURE_HORIZ_WALLS, 1, 3), 2, 3),
        )
        assertEquals(TERRAIN_FLOOR, walledAround.terrainAt(3, 2), "(3,2) stays floor in the fixture terrain rows")
        assertFalse(walledAround.stepBlocked(SPAWN_X, SPAWN_Y, 3, 2), "its west edge is open in this variant, so the step is legal")
        assertTrue(walledAround.wallBetween(3, 2, 4, 2), "its east edge is walled in this variant")
        assertTrue(walledAround.wallBetween(3, 2, 3, 3), "its south edge is walled in this variant")
        assertEquals(TERRAIN_FLOOR, walledAround.terrainAt(4, 2), "three walled edges did not change any terrain id")
    }

    @Test
    fun wallArraysAreDefensiveCopiesOnLayoutAndWorld() {
        val layout = fixtureLayout()
        val v = layout.wallVertRowMajor()
        val h = layout.wallHorizRowMajor()
        assertEquals(VERT_EDGE_SLOTS, v.size, "the WALL_VERT copy has 380 slots")
        assertEquals(HORIZ_EDGE_SLOTS, h.size, "the WALL_HORIZ copy has 380 slots")
        v[0] = true
        h[0] = true
        assertFalse(layout.wallBetween(0, 0, 1, 0), "mutating the copy does not touch the layout")
        assertFalse(layout.wallBetween(0, 0, 0, 1), "mutating the copy does not touch the layout")
        assertEquals(fixtureWallCount(), layout.edgeCount(), "the layout still reports the fixture wall count")
        val w = worldOfFixture(layout)
        assertTrue(w.wallVertRowMajor().contentEquals(layout.wallVertRowMajor()), "the world copy equals the layout copy")
        assertTrue(w.wallHorizRowMajor().contentEquals(layout.wallHorizRowMajor()), "the world copy equals the layout copy")
        w.wallVertRowMajor()[0] = true
        assertFalse(layout.wallBetween(0, 0, 1, 0), "the world copy is a copy, not the layout array")
        val decoded = Layout.decode(fixtureTerrainRows, noFireRows(), DIR_E, emptyList(), vertRowsOf(FIXTURE_VERT_WALLS))
        assertEquals(FIXTURE_VERT_WALLS.values.sumOf { it.size }, decoded.edgeCount(), "a source carrying only WALL_VERT is legal")
        assertFalse(decoded.horizEdge.any { it }, "a missing WALL_HORIZ block means all open (PLAN-EDGE-WALLS.md section 4)")
    }

    // ==================== 6. nothing shipped changed ====================

    /**
     * Mission-content adoption, PLAN-RECONCILE-MAIN-01.md §10 step 5. Since
     * missions 01-05 are main's layouts, every authored mission carries thin
     * edges: main's measured 2, 25, 11, 18 and 40 edges for missions 01-05
     * (measured with the engine's own `Layout.edgeCount`) plus mission 06's
     * 13 wall-column edges (the R4 capstone redesign, GO AROUND: one vertical
     * run at x=9 rows 0..12, measured with the same `Layout.edgeCount`). The
     * isolation half of the gate survives: mission 06 is still the authored
     * mission named for its wall, and no mission validation depends on the
     * old wall-free layouts.
     */
    @Test
    fun shippedMissionLayoutsAllCarryThinEdgesAndMission06StaysTheTeachingCapstone() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), Missions.ALL.map { it.id }, "the six authored missions (Missions.ALL)")
        val adopted = mapOf(1 to 2, 2 to 25, 3 to 11, 4 to 18, 5 to 40) // main's measured edge counts, missions 01-05
        val verticals = mapOf(1 to 0, 2 to 0, 3 to 7, 4 to 0, 5 to 0)
        for ((id, edges) in adopted) {
            val m = assertNotNull(Missions.byId(id), "mission $id must exist")
            assertTrue(m.layout.wallsPresent(), "mission ${m.id} '${m.name}' carries main's thin edge walls")
            assertEquals(edges, m.layout.edgeCount(), "mission ${m.id} '${m.name}' carries main's $edges thin edges (Layout.edgeCount)")
            assertEquals(verticals.getValue(id), m.layout.vertEdge.count { it }, "mission ${m.id} WALL_VERT wall count")
            assertEquals(edges - verticals.getValue(id), m.layout.horizEdge.count { it }, "mission ${m.id} WALL_HORIZ wall count")
            assertEquals(emptyList<String>(), m.validate(), "mission ${m.id} '${m.name}' validates with its edge walls")
        }
        assertTrue(Missions.byId(1)!!.layout.wallBetween(5, 1, 5, 2), "mission 01 seals the fire lane: the thin edge south of (5,1)")
        assertTrue(Missions.byId(1)!!.layout.wallBetween(5, 2, 5, 3), "mission 01 seals the fire lane: the thin edge south of (5,2)")
        assertTrue(Missions.byId(2)!!.layout.wallBetween(0, 2, 0, 3) && Missions.byId(2)!!.layout.wallBetween(1, 2, 1, 3), "mission 02's row-2 thin wall starts west of the gate")
        assertFalse(Missions.byId(2)!!.layout.wallBetween(2, 2, 2, 3), "mission 02 keeps its one-cell gate at x=2")
        assertTrue(Missions.byId(2)!!.layout.wallBetween(3, 2, 3, 3), "mission 02's row-2 thin wall continues east of the gate")
        assertTrue(Missions.byId(2)!!.layout.wallBetween(9, 10, 9, 11), "mission 02 walls the pocket's south edge")
        assertTrue(Missions.byId(3)!!.layout.wallBetween(7, 5, 8, 5), "mission 03's vertical edge column at x=7 gates row 5")
        assertTrue(Missions.byId(4)!!.layout.wallBetween(8, 1, 8, 2) && Missions.byId(4)!!.layout.wallBetween(8, 2, 8, 3), "mission 04 seals the fuel lane north and south")
        assertTrue(Missions.byId(5)!!.layout.wallBetween(0, 2, 0, 3) && Missions.byId(5)!!.layout.wallBetween(19, 1, 19, 2), "mission 05 seals the burning corridor end to end")
        val thin = assertNotNull(Missions.byId(6), "mission 06 GO AROUND must exist")
        assertTrue(thin.layout.wallsPresent(), "mission 06 is the authored teaching mission that carries edge walls")
        assertEquals(13, thin.layout.edgeCount(), "mission 06 carries its 13-edge wall column (R4 capstone redesign, measured with Layout.edgeCount)")
        assertEquals(13, thin.layout.vertEdge.count { it }, "mission 06 WALL_VERT: the single column x=9, rows 0..12")
        assertEquals(0, thin.layout.horizEdge.count { it }, "mission 06 WALL_HORIZ: the wall is one vertical segment run")
        assertTrue(thin.layout.wallBetween(9, 10, 10, 10), "mission 06 gates row 10 between (9,10) and (10,10)")
        assertTrue(thin.layout.wallBetween(9, 12, 10, 12), "mission 06's wall reaches its lower end at row 12")
        assertFalse(thin.layout.wallBetween(9, 13, 10, 13), "mission 06 keeps row 13 open: the detour crosses below the wall's lower end")
        assertEquals(emptyList<String>(), thin.validate(), "mission 06 validates with its edges (the flood fill reaches the pocket fire through the open rows)")
        assertTrue(fixtureLayout().wallsPresent(), "the fixture is the layout that does carry walls")
        assertEquals(3, fixtureLayout().edgeCount(), "the fixture wall table declares 3 edges")
    }

    /**
     * Canonical text round trip per mission: since main's layouts were adopted,
     * every authored mission emits both WALL blocks and re-emits them
     * byte-identically (the engine always writes the two blocks, all-open rows
     * included - measured against the merged engine).
     */
    @Test
    fun missionSourcesRoundTripAndEveryMissionCarriesWallBlocks() {
        val adopted = mapOf(1 to 2, 2 to 25, 3 to 11, 4 to 18, 5 to 40) // main's measured edge counts, missions 01-05
        for (id in 1..6) {
            val m = assertNotNull(Missions.byId(id), "mission $id must exist")
            val text = m.layout.toCanonicalText()
            assertTrue(text.contains(SECTION_WALL_VERT), "mission ${m.id} text carries the WALL_VERT block")
            assertTrue(text.contains(SECTION_WALL_HORIZ), "mission ${m.id} text carries the WALL_HORIZ block")
            val reparsed = Layout.fromCanonicalText(text)
            assertSameLayoutArrays(m.layout, reparsed, "mission ${m.id} '${m.name}' canonical text")
            assertEquals(text, reparsed.toCanonicalText(), "mission ${m.id} '${m.name}' text is byte-identical after a round trip")
            assertTrue(reparsed.wallsPresent(), "mission ${m.id} reparses with its edges")
            assertEquals((adopted[id] ?: 13), reparsed.edgeCount(), "mission ${m.id} reparses with the same edge count")
        }
    }

    /**
     * Anchors re-recorded from the merged engine after the mission-content
     * adoption (2026-11-26, headless kotlinc twin MeasureR3): missions 01-05 now
     * ship main's thin-wall layouts, so these fingerprints moved again (the
     * canonical state stamps terrain plus both edge maps). The TICKS are
     * unchanged - `02-ref-final` at 102 and `01-never-final` at 60 are main's
     * shipped finish ticks (PLAN-RECONCILE-MAIN-01.md §8 decision 1). The
     * docs/web/fixtures TSV/JSON columns are re-cut at the anchor re-baseline
     * (engine v4); these assertions carry the same values.
     */
    @Test
    fun missionAnchorsMoveWithTheCanonicalEdgeMapsButTheirTicksDoNot() {
        val corridor = assertNotNull(Missions.byId(2), "mission 02 CORRIDOR must exist")
        val corridorDescriptor = corridor.descriptorForSource(corridor.referenceSource)

        val t12 = FirelineWorld(corridorDescriptor)
        repeat(12) { t12.tickOnce() }
        assertEquals(12, t12.tick, "anchor 02-ref-t12 tick")
        assertEquals("579cf583444d2790", t12.finalStateHash(), "main-layout re-record of label 02-ref-t12 (was 894f4fbeb771c162 on the pre-adoption layout)")

        val t46 = FirelineWorld(corridorDescriptor)
        repeat(46) { t46.tickOnce() }
        assertEquals(46, t46.tick, "anchor 02-ref-t46 tick")
        assertEquals("f188fe6c320394bc", t46.finalStateHash(), "main-layout re-record of label 02-ref-t46 (was 3c88e48d9e86ebf2 on the pre-adoption layout)")

        val t30 = FirelineWorld(corridorDescriptor)
        repeat(30) { t30.tickOnce() }
        assertEquals(30, t30.tick, "mission 02 CORRIDOR tick 30")
        assertEquals("aae3c24e24551f92", t30.finalStateHash(), "measured in this build from mission 02 referenceSource (was a4e289de3b43ffe4 on the pre-adoption layout)")

        val finished = FirelineRun.runWithGuard(corridor, corridorDescriptor)
        assertEquals(102, finished.tick, "anchor 02-ref-final tick")
        assertEquals(RunStatus.SUCCESS, finished.status, "mission 02 reference firmware still succeeds at main's tick 102")
        assertEquals("2b8f20db8081bf73", finished.finalStateHash, "main-layout re-record of label 02-ref-final and parity_matrix 2-reference final_hash (tick 102, was d604c2a00e2b5de5 on the pre-adoption layout)")

        val nozzle = assertNotNull(Missions.byId(1), "mission 01 NOZZLE CHECK must exist")
        val neverShoots = nozzle.failingSources.first { it.first == "never shoots" }
        val neverDescriptor = nozzle.descriptorForSource(neverShoots.second)

        val n30 = FirelineWorld(neverDescriptor)
        repeat(30) { n30.tickOnce() }
        assertEquals(30, n30.tick, "anchor 01-never-t30 tick")
        assertEquals("128031754936e24c", n30.finalStateHash(), "main-layout re-record of label 01-never-t30 (was 7a8795328485c85c on the pre-adoption layout)")

        val nFinal = FirelineRun.runWithGuard(nozzle, neverDescriptor)
        assertEquals(60, nFinal.tick, "anchor 01-never-final tick = timeLimit 60")
        assertEquals(FailureReason.TIME_EXPIRED, nFinal.failureReason, "mission 01 failing firmware fails for the documented reason")
        assertEquals("51fd5cb69657d680", nFinal.finalStateHash, "main-layout re-record of label 01-never-final and parity_matrix 1-failing final_hash (tick 60 = timeLimit, was c35cd239631ff200 on the pre-adoption layout)")
    }

    @Test
    fun canonicalStateCarriesBothEdgeMapsAndSeparatesWalllessFromWalled() {
        // The synthetic open field is the wall-free half of this gate now: every
        // authored mission carries thin edges since the main-layout adoption.
        val openState = worldOfFixture(layoutOf()).canonicalState()
        assertTrue(openState.contains("\nverticalEdges=" + "0".repeat(VERT_EDGE_SLOTS) + "\n"), "a wall-free layout stamps 380 open vertical edges")
        assertTrue(openState.contains("\nhorizontalEdges=" + "0".repeat(HORIZ_EDGE_SLOTS) + "\n"), "a wall-free layout stamps 380 open horizontal edges")
        assertTrue(openState.contains("terrain="), "canonicalState keeps its terrain line unchanged (PLAN-EDGE-WALLS.md section 4)")
        assertTrue(openState.contains("fire="), "canonicalState keeps its fire line unchanged")
        val m5 = assertNotNull(Missions.byId(5), "mission 05 HOT ZONE must exist")
        val hotState = FirelineWorld(m5.descriptorForSource(m5.referenceSource)).canonicalState()
        assertTrue(hotState.split("verticalEdges=")[1].take(VERT_EDGE_SLOTS).count { it == '1' } == 0, "mission 05 stamps no vertical edge (its walls are all horizontal)")
        assertTrue(hotState.split("horizontalEdges=")[1].take(HORIZ_EDGE_SLOTS).count { it == '1' } == 40, "mission 05 stamps exactly its 40 horizontal edges (the corridor seal, rows 1 and 2)")
        val thin = assertNotNull(Missions.byId(6), "mission 06 GO AROUND must exist")
        val thinState = FirelineWorld(thin.descriptorForSource(thin.referenceSource)).canonicalState()
        assertTrue(thinState.contains("\nverticalEdges="), "mission 06 stamps its vertical wall column")
        assertTrue(thinState.split("verticalEdges=")[1].take(VERT_EDGE_SLOTS).count { it == '1' } == 13, "mission 06 stamps exactly its 13 vertical edges (the single column x=9, rows 0..12)")
        assertTrue(thinState.split("horizontalEdges=")[1].take(HORIZ_EDGE_SLOTS).count { it == '1' } == 0, "mission 06 stamps no horizontal edge (its wall is one vertical run)")
        assertEquals(FIRELINE_ENGINE_VERSION, 2, "engine v2 stays the shipped version through this increment: canonicalState stamps it and MissionDescriptor defaults to it; the bump to 4 follows at the anchor re-baseline (PLAN-RECONCILE-MAIN-01.md §8)")
    }
}
