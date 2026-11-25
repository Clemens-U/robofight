package robofight.fireline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import robofight.fireline.GRID
import robofight.fireline.TERRAIN_EXIT
import robofight.fireline.TERRAIN_FLOOR
import robofight.fireline.TERRAIN_REFILL
import robofight.fireline.TERRAIN_WALL
import robofight.fireline.TextGrid
import robofight.fireline.terrainGlyph

/**
 * Headless gate for the world column of §15.2 item 2 (`WIND`, the 20x20 grid,
 * the symbol legend, `EVENT LOG`) as `WorldGrid` renders it inside
 * `ConsoleRender.renderConsoleHtml`.
 *
 * Fixture provenance:
 *  - `docs/web/verify_layout_v1.py`: "15.6 grid renders 400 cells"
 *    (`class="cell "` occurrences == 400 in the world column), "15.2 world column
 *    order wind/grid/legend/event log", "15.4 no legend inside the firmware column".
 *  - `docs/web/gen_web_mockups.py` `grid_html()` / `LEGEND`: the cell, ruler,
 *    corner, nose and legend markup these tests compare against byte-for-byte.
 *  - `docs/FIRELINE-WEB.md` §15.3 ("world grid | 21 rows x 20 columns of 15px
 *    cells (grid height 317px)"), §15.5 (`<=900px`: cells 11px, pane
 *    `21 * 11 + 1`), §14.1 grid parity ("mockup 2 renders 400 cells, 26 walls,
 *    2 refills, 1 exit, 1 rover and 1 burning cell, matching the engine's
 *    tick-46 terrain exactly") and §14.1's fire rule ("the mockups use the
 *    engine's own live-intensity display mapping from `TextGrid.fireGlyph`").
 *  - `fireline-engine/src/commonTest/.../Step1LayoutRoverPortsTest.kt`: the
 *    decode anchors that terrain ids, terrain glyphs and authored fire levels
 *    come from the mission layout (`Layout.decode` -> `terrainAt` /
 *    `terrainGlyph` / `fireAt`). Every coordinate asserted below is one of
 *    `Missions.CORRIDOR`'s authored layout rows, read back through
 *    `FirelineWorld.terrainAt`, never a bare number the test invented.
 *  - `rf8-core` `ISA.cost`: TURN and bookkeeping ops cost 1 world tick, MOVE
 *    costs 2 and its world side effect fires on the op's FINAL tick. Mission 02's
 *    authored reference source opens `TURN   R`, so STEP 1 changes the rover's
 *    facing in place and STEP 3 walks it from the spawn (2,2) through the
 *    authored wall gap at (2,3).
 *
 * As in `ConsoleRenderTest`, no expected value here is produced by the render
 * layer: terrain, fire, rover position and facing come from `FirelineWorld` /
 * `TelemetrySnapshot`, and glyph expectations are `TextGrid`'s own functions
 * evaluated at the same coordinates.
 */
class WorldGridTest {

    private val model = ConsoleModel()

    /** One rendered cell node: its palette class, its inline attributes, its
     *  inner markup (the rover's `<i class="nose">` caret stays in it). */
    private data class CellNode(val cssClass: String, val attrs: String, val inner: String) {
        val glyph: String get() = inner.substringBefore("<")
        val nose: String get() = inner.substringAfter("<i class=\"nose\">", "").substringBefore("<")
    }

    private val cellRegex = Regex("<span class=\"cell ([^\"]+)\"([^>]*)>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)

    private fun gridBlock(html: String): String =
        html.substringAfter("<div class=\"grid\">").substringBefore("</div>")

    /** Cell nodes in document order; `grid_html()` writes row-major, so node
     *  `y * GRID + x` is the cell at coordinate (x, y). */
    private fun cells(html: String): List<CellNode> =
        cellRegex.findAll(gridBlock(html)).map {
            CellNode(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }.toList()

    private fun cellAt(html: String, x: Int, y: Int): CellNode = cells(html)[y * GRID + x]

    /** One `wallSegmentsHtml()` overlay child: its key plus the raw px anchor it is placed at. */
    private data class Seg(val kind: String, val key: String, val left: Int, val top: Int)

    private val segRegex = Regex(
        "<span class=\"wallseg ([vh])\" data-edge=\"([^\"]+)\" style=\"left:(\\d+)px;top:(\\d+)px\"></span>",
    )

    private fun segMarkup(markup: String): List<Seg> =
        segRegex.findAll(markup).map { Seg(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt(), it.groupValues[4].toInt()) }.toList()

    /** The world column only, isolated the way `verify_layout_v1.py` isolates it. */
    private fun worldColumn(html: String): String =
        html.substringAfter("<div class=\"pair").substringBefore("<div class=\"pairfirm")

    /** Mission 02 / CORRIDOR with the authored reference firmware: the state
     *  §15.6 names for layout acceptance. */
    private fun corridor(): ConsoleRun = model.openMission(2)

    /** Count cells of one palette class in the world column. */
    private fun countClass(html: String, cssClass: String): Int =
        cells(html).count { it.cssClass == cssClass }

    /**
     * §15.2 item 2 / `verify_layout_v1.py` "15.2 world column order
     * wind/grid/legend/event log": the markers sit in the world column, in that
     * order, ahead of the firmware column, and the legend appears once. The log
     * slot is empty since W3 record 11 f and the pacing row left the column in 11 g,
     * so the world column is wind/grid/status line/legend and its height is the grid's.
     */
    @Test
    fun worldColumnRendersWindThenGridThenStatusLineThenLegend() {
        val html = ConsoleRender.renderConsoleHtml(model, corridor())
        val world = worldColumn(html)
        val firm = html.substringAfter("<div class=\"pairfirm\">")

        val markers = listOf("class=\"wind\"", "<div class=\"grid", "class=\"statusbar\"", "class=\"legend\"")
        val positions = markers.map { world.indexOf(it) }
        positions.forEachIndexed { index, position ->
            assertTrue(position >= 0, "${markers[index]} present in the world column (§15.2 item 2)")
        }
        assertEquals(positions.sorted(), positions, "world column order wind/grid/status line/legend, got $positions (§15.2 item 2, 15.2a 2)")
        assertTrue(world.indexOf("class=\"legend\"") == world.lastIndexOf("class=\"legend\"") &&
            world.indexOf("class=\"legend\"") > world.indexOf("class=\"statusbar\""),
            "the legend is the last thing in the world column (11 g)")
        assertEquals(-1, world.indexOf("<div class=\"pacing\""), "pacing row no longer inside the world column (11 g)")
        assertTrue(html.indexOf("class=\"wind\"") < html.indexOf("<div class=\"grid"),
            "WIND line sits above the grid (console_pair markup)")

        assertEquals(1, Regex("class=\"legend\"").findAll(html).toList().size, "symbol legend appears once (§15.4)")
        assertEquals(-1, firm.indexOf("class=\"legend\""), "no legend inside the firmware column (verify_layout_v1 §15.4 check)")
        assertEquals(1, Regex("<div class=\"grid\"[^>]*>").findAll(html).toList().size, "one world grid (W3 record 16 adds the inline scaling style, so the open tag is matched with its attributes)")
    }

    /**
     * §15.6 "grid renders 400 cells" (the exact `verify_layout_v1.py` check:
     * `class="cell "` occurrences == 400 in the world column), the `grid_html()`
     * label row (corner + 20 column rulers + 20 row rulers), and the §15.3 /
     * §15.5 geometry rules as arithmetic over `GRID`.
     */
    @Test
    fun gridRendersExactlyFourHundredCellNodesInTheFrozenGeometry() {
        val html = ConsoleRender.renderConsoleHtml(model, corridor())
        val world = worldColumn(html)

        assertEquals(400, Regex("class=\"cell ").findAll(world).toList().size,
            "400 cell nodes in the world column (verify_layout_v1 '15.6 grid renders 400 cells')")
        assertEquals(GRID * GRID, cells(html).size, "GRID * GRID = 400 cell nodes (FirelineRules.GRID = 20)")
        assertEquals(1, Regex("class=\"corner\"").findAll(world).toList().size, "one corner label (grid_html)")
        assertEquals(GRID * 2, Regex("class=\"ruler\"").findAll(world).toList().size, "20 column rulers + 20 row rulers (grid_html)")
        assertEquals(0, Regex("class=\"cell \"").findAll(world).toList().size, "no cell without a palette class")

        // §15.3 "21 rows x 20 columns of 15px cells (grid height 317px)" and
        // §15.5 "`<=900px`: grid cells 11px, pane height 21 * 11 + 1".
        assertEquals(GRID + 1, WorldGrid.GRID_ROW_COUNT, "21 grid rows: 20 cell rows plus the ruler row")
        assertEquals(WorldGrid.GRID_ROW_COUNT * WorldGrid.DESKTOP_CELL_PX + 2, WorldGrid.DESKTOP_GRID_HEIGHT_PX,
            "grid height written as the rule (21 rows x 15px + 2px border), not a magic number")
        assertEquals(317, WorldGrid.DESKTOP_GRID_HEIGHT_PX, "grid height lock 317px (§15.3 table)")
        assertEquals(WorldGrid.GRID_ROW_COUNT * WorldGrid.DESKTOP_CELL_PX + 1, WorldGrid.DESKTOP_PANE_HEIGHT_PX,
            "firmware pane lock 21 * 15 + 1 = 316px (§15.3)")
        assertEquals(WorldGrid.GRID_ROW_COUNT * WorldGrid.NARROW_CELL_PX + 1, WorldGrid.NARROW_PANE_HEIGHT_PX,
            "narrow pane lock written as the rule (21 rows x 11px + 1)")
        assertEquals(21 * 11 + 1, WorldGrid.NARROW_PANE_HEIGHT_PX, "narrow pane lock 232px (§15.5)")
        assertEquals(WorldGrid.GRID_ROW_COUNT * WorldGrid.NARROW_CELL_PX + 2, WorldGrid.NARROW_GRID_HEIGHT_PX,
            "narrow grid height 21 rows x 11px + 2px border = 233px (§15.5)")
        // Flush pairing means the grid and the pane share the same 21-row grid.
        assertEquals(WorldGrid.DESKTOP_GRID_HEIGHT_PX - WorldGrid.DESKTOP_PANE_HEIGHT_PX,
            WorldGrid.NARROW_GRID_HEIGHT_PX - WorldGrid.NARROW_PANE_HEIGHT_PX,
            "grid and pane differ by the same 1px border allowance at both widths (§15.3 flush pairing)")
    }

    /**
     * The rover cell is the cell the engine says it is: node
     * `telemetry().roverY * GRID + roverX` carries `TextGrid.ROVER_GLYPH` with
     * the mockup's `rover` class, and its `<i class="nose">` caret is the caret
     * of `TelemetrySnapshot.roverFacing`.
     */
    @Test
    fun roverCellSitsAtTheTelemetryCoordinateAndCarriesTheRoverGlyph() {
        val run = corridor()
        val html = ConsoleRender.renderConsoleHtml(model, run)
        val t = run.telemetry()
        val world = run.currentWorld
        val index = t.roverY * GRID + t.roverX
        val cell = cells(html)[index]

        assertEquals(GRID * GRID, cells(html).size, "grid addresses 400 cells before picking the rover out")
        assertEquals("rover", cell.cssClass, "cell ${t.roverX},${t.roverY} is the rover cell (TelemetrySnapshot.roverX/roverY)")
        assertEquals(TextGrid.ROVER_GLYPH.toString(), cell.glyph, "rover cell carries TextGrid.ROVER_GLYPH (TextGrid.glyphAt rover layer)")
        assertEquals(world.rover.x, t.roverX, "telemetry position matches world.rover (MissionSession.currentWorld)")
        assertEquals(world.rover.y, t.roverY, "telemetry position matches world.rover (MissionSession.currentWorld)")
        assertEquals(TextGrid.ROVER_GLYPH, TextGrid.glyphAt(world, t.roverX, t.roverY),
            "TextGrid.glyphAt puts the rover above every other layer at its own coordinate")
        assertEquals(WorldGrid.noseCaret(t.roverFacing), cell.nose,
            "the drawn caret is the caret of the engine's own facing letter (TelemetrySnapshot.roverFacing)")
        assertEquals(1, countClass(html, "rover"), "exactly one rover cell (one rover in the world)")
    }

    /**
     * STEP moves the grid with the world. Mission 02's authored reference source
     * opens `TURN   R` (1 tick per `ISA.cost`), so STEP 1 turns the rover in
     * place; MOVE costs 2 ticks and its world effect fires on the final tick, so
     * STEP 3 walks it from the spawn (2,2) through the authored gap at (2,3).
     * After each STEP the rover cell is found again at the coordinate the engine
     * reports, and the cell it left is no longer a rover cell.
     */
    @Test
    fun stepsMoveTheRoverCellToTheCoordinateTheEngineReports() {
        val run = corridor()
        val before = run.telemetry()
        val beforeHtml = ConsoleRender.renderConsoleHtml(model, run)
        val beforeIndex = before.roverY * GRID + before.roverX
        assertEquals(run.currentWorld.rover.x, before.roverX, "spawn x from the engine (Missions.CORRIDOR layout rover spawn)")
        assertEquals(run.currentWorld.rover.y, before.roverY, "spawn y from the engine (Missions.CORRIDOR layout rover spawn)")

        // STEP 1: the authored TURN. Rover stays put, its facing and caret change.
        run.step()
        val turned = run.telemetry()
        val turnedHtml = ConsoleRender.renderConsoleHtml(model, run)
        val turnedIndex = turned.roverY * GRID + turned.roverX
        assertEquals(beforeIndex, turnedIndex, "a TURN leaves the rover in the same cell (authored first op of CORRIDOR-REF)")
        assertNotEquals(before.roverFacing, turned.roverFacing, "TURN R changed the engine's facing letter (Vm.lastOp TURN)")
        assertEquals("rover", cells(turnedHtml)[turnedIndex].cssClass, "rover cell still at the telemetry coordinate after TURN")
        assertEquals(WorldGrid.noseCaret(turned.roverFacing), cells(turnedHtml)[turnedIndex].nose,
            "the rover caret changed with the engine's facing (grid_html dir_caret table)")

        // STEP 2 and 3: MOVE (1/2) then MOVE (2/2), the tick whose world effect fires.
        run.step()
        val midMove = run.telemetry()
        assertTrue(run.lastEvent().startsWith("MOVE"), "MOVE in flight after the second STEP (Vm.lastOp)")
        assertTrue(midMove.executing, "MOVE occupies two world ticks (ISA MOVE cost 2)")

        run.step()
        val after = run.telemetry()
        val afterHtml = ConsoleRender.renderConsoleHtml(model, run)
        val afterIndex = after.roverY * GRID + after.roverX
        assertEquals("MOVE (2/2)", run.lastEvent(), "the MOVE completed on its final tick (Vm.lastOp)")

        assertNotEquals(beforeIndex, afterIndex, "the rover cell index changed when the engine moved the rover")
        assertEquals(after.roverY * GRID + after.roverX, afterIndex, "rover cell index equals the new telemetry coordinate")
        assertEquals("rover", cells(afterHtml)[afterIndex].cssClass, "the grid rover sits at the new telemetry coordinate")
        assertEquals(TextGrid.ROVER_GLYPH.toString(), cells(afterHtml)[afterIndex].glyph, "the moved cell still carries TextGrid.ROVER_GLYPH")
        assertNotEquals("rover", cells(afterHtml)[beforeIndex].cssClass, "the cell the rover left is no longer a rover cell")
        assertEquals(afterIndex - GRID, beforeIndex, "the engine moved the rover one row south through the authored gap (wallRow y=3 gap at x=2)")
        assertEquals(after.roverX, before.roverX, "MOVE south kept the column (TextGrid.dirName after TURN R)")
        assertEquals(GRID * GRID, cells(afterHtml).size, "still 400 cells after the move (verify_layout_v1 400-cell gate)")
    }

    /**
     * Terrain cells: for authored layout coordinates of `Missions.CORRIDOR`, the
     * engine's own `terrainAt` read decides both the palette class and the glyph,
     * and the rendered glyph equals `TextGrid.glyphAt` at the same coordinate
     * (the `Step1LayoutRoverPortsTest` discipline: layout cells come from the
     * mission layout, never from invented numbers).
     */
    @Test
    fun terrainCellsRenderTheClassAndGlyphThatTextGridProduces() {
        val run = corridor()
        val html = ConsoleRender.renderConsoleHtml(model, run)
        val world = run.currentWorld

        // Authored layout coordinates of Missions.CORRIDOR (terrainRows).
        val wallAt = Pair(3, 2)       // the wall directly ahead of the spawn
        val pocketWallAt = Pair(9, 9)  // pocket north wall
        val refillAt = Pair(8, 10)     // approach-side firebreak
        val exitAt = Pair(11, 10)      // exit behind the pocket

        assertEquals(TERRAIN_WALL, world.terrainAt(wallAt.first, wallAt.second), "3,2 is TERRAIN_WALL in the authored layout (Missions.CORRIDOR terrainRows)")
        assertEquals(TERRAIN_WALL, world.terrainAt(pocketWallAt.first, pocketWallAt.second), "9,9 is TERRAIN_WALL in the authored layout (pocket north)")
        assertEquals(TERRAIN_REFILL, world.terrainAt(refillAt.first, refillAt.second), "8,10 is TERRAIN_REFILL in the authored layout (pocket approach firebreak)")
        assertEquals(TERRAIN_EXIT, world.terrainAt(exitAt.first, exitAt.second), "11,10 is TERRAIN_EXIT in the authored layout (exit behind the pocket)")

        for ((x, y) in listOf(wallAt, pocketWallAt, refillAt, exitAt)) {
            val cell = cellAt(html, x, y)
            val expectedGlyph = TextGrid.glyphAt(world, x, y)
            assertEquals(expectedGlyph.toString(), cell.glyph,
                "cell $x,$y renders TextGrid.glyphAt (terrain ${world.terrainAt(x, y)})")
            assertEquals(terrainGlyph(world.terrainAt(x, y)).toString(), cell.glyph,
                "a cell nothing stands on renders the terrain glyph of its own terrain id")
        }
        assertEquals("wall", cellAt(html, wallAt.first, wallAt.second).cssClass, "TERRAIN_WALL renders the mockup's wall class (glyph_for terrain table)")
        assertEquals("wall", cellAt(html, pocketWallAt.first, pocketWallAt.second).cssClass, "TERRAIN_WALL renders the mockup's wall class")
        assertEquals("refill", cellAt(html, refillAt.first, refillAt.second).cssClass, "TERRAIN_REFILL renders the mockup's refill class")
        assertEquals("exit", cellAt(html, exitAt.first, exitAt.second).cssClass, "TERRAIN_EXIT renders the mockup's exit class")
        assertEquals("#", cellAt(html, wallAt.first, wallAt.second).glyph, "wall glyph is TextGrid's terrain glyph for TERRAIN_WALL")
        assertEquals("W", cellAt(html, refillAt.first, refillAt.second).glyph, "refill glyph is TextGrid's terrain glyph for TERRAIN_REFILL")
        assertEquals("E", cellAt(html, exitAt.first, exitAt.second).glyph, "exit glyph is TextGrid's terrain glyph for TERRAIN_EXIT")

        // A floor cell with nothing on it: the mockup's `empty` class and its
        // idle style (`grid_html` puts `font-size:8px;opacity:.75` on it).
        val floor = (0 until GRID).flatMap { y -> (0 until GRID).map { x -> x to y } }
            .first { (x, y) -> world.terrainAt(x, y) == TERRAIN_FLOOR && TextGrid.glyphAt(world, x, y) == terrainGlyph(TERRAIN_FLOOR) }
        val floorCell = cellAt(html, floor.first, floor.second)
        assertEquals("empty", floorCell.cssClass, "bare TERRAIN_FLOOR renders the mockup's empty class at ${floor.first},${floor.second}")
        assertEquals(".", floorCell.glyph, "floor glyph is TextGrid.glyphAt for TERRAIN_FLOOR")
        assertTrue(floorCell.attrs.contains(WorldGrid.IDLE_CELL_STYLE), "idle floor cell carries the mockup's idle style (grid_html)")

        // Class/glyph agreement for all 400 cells, cell by cell, engine-read.
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val cell = cellAt(html, x, y)
            assertEquals(TextGrid.glyphAt(world, x, y).toString(), cell.glyph, "glyph of cell $x,$y equals TextGrid.glyphAt(world,$x,$y)")
            assertEquals(WorldGrid.cellClassFor(world, x, y), cell.cssClass, "class of cell $x,$y follows that same glyph")
        }
    }

    /**
     * §14.1 grid parity for mission 02: the engine's tick-46 terrain renders
     * 26 walls, 2 refills, 1 exit, 1 rover and 1 burning cell (terrain is
     * immutable for the whole run, `Layout` doc), and every burning cell renders
     * the glyph `TextGrid.fireGlyph` produces for its own intensity.
     */
    @Test
    fun burningCellsRenderTheFireGlyphForTheirOwnIntensity() {
        val run = corridor()
        val world = run.currentWorld

        assertEquals(25, world.fireAt(9, 10), "authored pocket fire level '1' decodes to intensity 25 (Missions.CORRIDOR fireRows, Step1LayoutRoverPortsTest decode anchor)")
        assertEquals('2', TextGrid.fireGlyph(world.fireAt(9, 10))!!, "intensity 25 displays as fire glyph 2 (TextGrid.fireGlyph band 25..49, §14.1)")

        val tick0Html = ConsoleRender.renderConsoleHtml(model, run)
        val tick0Cell = cellAt(tick0Html, 9, 10)
        assertEquals("fire2", tick0Cell.cssClass, "the burning pocket cell carries the fire2 palette class (gen_web_mockups glyph_for fire table)")
        assertEquals(TextGrid.fireGlyph(world.fireAt(9, 10)).toString(), tick0Cell.glyph, "the burning cell renders TextGrid.fireGlyph for its own intensity")
        assertTrue(tick0Cell.attrs.contains(WorldGrid.FIRE_CELL_STYLE), "burning cell carries the mockup's font-weight:bold style (grid_html)")
        assertEquals(1, countClass(tick0Html, "fire2"), "one burning cell at tick 0 (Missions.CORRIDOR fireRows)")
        assertEquals(1, world.fireCellsBurning(), "the engine reports the same burning-cell count the grid renders")

        // Frozen anchor state: mission 02 / CORRIDOR at tick 46 (§15.6, §14.1,
        // `snapshots.json 02-ref-t46`: rover 6,10 facing E, fire cell (9,10) at
        // intensity 70).
        val at46 = model.openMission(2).apply { repeat(46) { step() } }
        val html46 = ConsoleRender.renderConsoleHtml(model, at46)
        val world46 = at46.currentWorld
        val t46 = at46.telemetry()
        assertEquals(46, at46.tick, "the frozen tick-46 fixture state (snapshots 02-ref-t46)")
        assertEquals(6, t46.roverX, "rover x at tick 46 (snapshots 02-ref-t46 rover x)")
        assertEquals(10, t46.roverY, "rover y at tick 46 (snapshots 02-ref-t46 rover y)")
        assertEquals("E", t46.roverFacing, "rover facing at tick 46 (snapshots 02-ref-t46 rover dir)")
        assertEquals(70, world46.fireAt(9, 10), "pocket fire intensity at tick 46 (snapshots 02-ref-t46 fire_cells intensity 70)")

        assertEquals(26, countClass(html46, "wall"), "26 wall cells at tick 46 (docs/FIRELINE-WEB.md §14.1 grid parity)")
        assertEquals(2, countClass(html46, "refill"), "2 refill cells at tick 46 (§14.1 grid parity)")
        assertEquals(1, countClass(html46, "exit"), "1 exit cell at tick 46 (§14.1 grid parity)")
        assertEquals(1, countClass(html46, "rover"), "1 rover cell at tick 46 (§14.1 grid parity)")
        val fireCells46 = cells(html46).filter { it.cssClass.startsWith("fire") }
        assertEquals(1, fireCells46.size, "1 burning cell at tick 46 (§14.1 grid parity: the pocket fire)")
        assertEquals("3", fireCells46[0].glyph, "the tick-46 burning cell renders glyph 3 for intensity 70 (TextGrid.fireGlyph band 50..74, §14.1 live-intensity rule)")
        assertEquals("fire3", fireCells46[0].cssClass, "and the fire3 palette class the mockup paints for that band (glyph_for fire table)")
        assertEquals("rover", cellAt(html46, t46.roverX, t46.roverY).cssClass, "the rover cell is the fixture's 6,10 cell (snapshots 02-ref-t46 rover)")
        assertEquals(world46.fireCellsBurning(), Regex("class=\"cell fire").findAll(worldColumn(html46)).toList().size,
            "rendered fire cell count equals FirelineWorld.fireCellsBurning()")
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val intensity = world46.fireAt(x, y)
            val glyph = TextGrid.fireGlyph(intensity) ?: continue
            val cell = cellAt(html46, x, y)
            assertEquals(glyph.toString(), cell.glyph, "burning cell $x,$y (intensity $intensity) renders TextGrid.fireGlyph")
            assertEquals("fire" + glyph, cell.cssClass, "burning cell $x,$y renders the fire class of its own band")
        }
    }

    /**
     * §15.4: the symbol legend appears once, under the grid and above the EVENT
     * LOG, with the mockup's own items, wording, HTML entities and class names,
     * plus the dated E4 thin-wall item of 15.8 (`PLAN-EDGE-WALLS.md` §5).
     */
    @Test
    fun legendAppearsOnceWithTheMockupsItemsAndClasses() {
        val html = ConsoleRender.renderConsoleHtml(model, corridor())
        val world = worldColumn(html)
        val legend = world.substringAfter("<div class=\"legend\">").substringBefore("</div>")

        assertEquals(1, Regex("<div class=\"legend\">").findAll(html).toList().size, "one legend block on the page (§15.4)")
        assertTrue(world.indexOf("<div class=\"legend\">") > world.indexOf("<div class=\"grid"), "legend sits under the grid (§15.4 'under the grid, in the world column')")
        assertTrue(world.trimEnd().endsWith("</div>") && world.substringBeforeLast("</div>").indexOf("<div class=\"legend\">") > world.indexOf("<div class=\"grid"), "legend is the world column's last block (W3 record 11 g)")
        val bandBlocks = html.split("<div class=\"fields\">").drop(1)
        assertEquals(2, bandBlocks.size, "two readout bands (verify_layout_v1)")
        bandBlocks.forEach { band ->
            assertTrue(!band.substringBefore("</div></div>").contains("class=\"legend\""),
                "no symbol legend inside a readout band (§15.7)")
        }

        val items = listOf(
            "<b><span class=\"wall\">&#x2588;</span> wall</b>",
            "<b><span class=\"edgebar\"></span> thin wall, blocks the way</b>",
            "<b><span class=\"rover\">R</span><span class=\"nose-txt\">&#x25b8;</span> rover + facing</b>",
            "<b><span class=\"fire1\">1</span><span class=\"fire2\">2</span><span class=\"fire3\">3</span><span class=\"fire4\">4</span> fire, engine display bands 1..24 / 25..49 / 50..74 / 75..100</b>",
            "<b><span class=\"packet\">*</span> water packet</b>",
            "<b><span class=\"refill\">W</span> refill</b>",
            "<b><span class=\"exit\">E</span> exit</b>",
            "<b><span class=\"civ\">C</span> civilian</b>",
            "<b><span class=\"fuel\">F</span> fuel</b>",
            "<b><span class=\"prot\">P</span> protected asset</b>",
            "<b>layer order: rover &gt; civilian &gt; packet &gt; fire &gt; terrain</b>",
        )
        items.forEach { item ->
            assertTrue(legend.contains(item), "legend item '$item' byte-identical to LEGEND in gen_web_mockups.py")
        }
        assertEquals(items.size, Regex("<b>").findAll(legend).toList().size, "eleven legend items, no more (10 of LEGEND in gen_web_mockups.py + the 15.8 thin wall)")
        val expectedLegend = "<div class=\"legend\">\n" + items.joinToString("\n") + "\n</div>"
        assertEquals(expectedLegend, WorldGrid.legendHtml(), "the whole legend block is byte-identical to LEGEND in gen_web_mockups.py")
        assertTrue(html.contains(expectedLegend), "the shell renders that legend block verbatim, in the world column")
        assertTrue(legend.contains("1..24 / 25..49 / 50..74 / 75..100"),
            "legend states the engine's live-intensity display bands so the fire glyphs are not misread (§14.1, §15.4)")
    }

    /** The grid tracks the run: finished mission 02 parks the rover on the exit
     *  cell it rendered while walking, and the grid never grows a second body. */
    @Test
    fun finishedMission02GridShowsTheRoverOnTheExitCell() {
        val run = corridor()
        run.run()
        val html = ConsoleRender.renderConsoleHtml(model, run)
        val t = run.telemetry()
        val world = run.currentWorld

        assertEquals(400, cells(html).size, "400 cells at the finish too (verify_layout_v1 400-cell gate)")
        assertEquals("rover", cellAt(html, t.roverX, t.roverY).cssClass, "rover cell at the finished telemetry coordinate")
        assertTrue(world.roverOnExit(), "the engine finished mission 02 with the rover on TERRAIN_EXIT (FirelineWorld.roverOnExit)")
        assertEquals(TERRAIN_EXIT, world.terrainAt(t.roverX, t.roverY), "that cell's terrain is TERRAIN_EXIT (world.terrainAt)")
        assertEquals(1, countClass(html, "rover"), "one rover cell (TextGrid layer order: rover above exit)")
        assertEquals(TextGrid.ROVER_GLYPH, TextGrid.glyphAt(world, t.roverX, t.roverY), "TextGrid puts the rover above the exit glyph on that cell")
    }

    /**
     * W3 record 16: the world grid scaled itself with `--cell: max(320px, calc((var(--cw) - 19.2px) / 2))`
     * written on the PAIR element, and Chrome 142 re-resolved that expression inside the grid's own
     * track sizing against the grid's own box. Measured at a 1370px viewport: `--cell` 305.6px on
     * `.pair`, 28.281px inside `.grid`, a 748.6px track sum inside a 611.4px box, i.e. cells that ran
     * 136px past the world column into the firmware column (the owner's screenshot).
     *
     * The scaling is therefore arithmetic in the render layer: `WorldGrid.cellSizePx` derives the
     * cell edge from the measured world column, and `WorldGrid.cellStyleAttr` writes `--cell` and
     * `--grid-h` as one raw-px inline style on the pair element; the CSS `.grid` rule builds its
     * tracks, rows and box from that same token. The gate here is the arithmetic plus the shape of
     * that style; the browser gate (docs/WEB-W3-RECORD.md record 16) measures the result.
     */
    @Test
    fun worldGridScalesItsCellsInsideTheWorldColumnAtEveryWidth() {
        assertEquals(22, WorldGrid.GRID_TRACK_CELLS, "22 cell tracks: the 2-track label column plus 20 cell tracks")
        assertEquals(14, WorldGrid.cellSizePx(320), "a 320px column fits 14px cells ((320 - 2) / 22): the §15.5 floor is not what a narrow column forces")
        assertEquals(27, WorldGrid.cellSizePx(613), "the 1370px viewport's measured 613px world column -> 27px cells, the §15.3 15px rule scaled by 1.8")
        assertEquals(34, WorldGrid.cellSizePx(2000), "the scale stops at the 34px ceiling")
        assertEquals(11, WorldGrid.cellSizePx(0), "an unmeasured column falls back to the floor, never to zero")
        assertEquals(11, WorldGrid.cellSizePx(264), "the 11px floor only binds below 266px of column (22 * 11 + 2 = 244px box, 264 + 2 = 266)")

        val style = WorldGrid.cellStyleAttr(320)
        assertTrue(style == "style=\"--cell:14px;--grid-h:296px;--wall-stroke:2px;\"",
            "the scaling hands over exactly one measured cell plus the paired grid height: ${style}")
        assertTrue(WorldGrid.GRID_TRACK_CELLS * WorldGrid.cellSizePx(320) + 2 == 310,
            "the CSS box term 22 * --cell + 2 is 310px at a 320px column, inside the column")
        assertTrue(WorldGrid.GRID_ROW_COUNT * WorldGrid.cellSizePx(320) + 2 == 296,
            "the CSS row term 21 * --cell + 2 is 296px, the same cell as --grid-h")
        for (column in listOf(320, 480, 613, 900, 1200)) {
            val cell = WorldGrid.cellSizePx(column)
            assertTrue(WorldGrid.NARROW_CELL_PX <= cell && cell <= WorldGrid.CELL_MAX_PX, "cell stays in [11, 34] at $column")
            assertTrue(WorldGrid.GRID_TRACK_CELLS * cell + 2 <= column, "the grid box ($cell px cells) fits inside the $column px column")
            assertTrue(WorldGrid.GRID_ROW_COUNT * cell + 2 <= 716, "the grid box height stays inside the ceiling case (21 * 34 + 2 = 716px), so the scale can never make a taller grid than the ceiling allows")
        }

        val run = corridor()
        val html = ConsoleRender.renderConsoleHtml(model, run, worldColumnPx = 613)
        assertTrue(html.contains("<div class=\"pair\" style=\"--cell:27px;--grid-h:569px;--wall-stroke:4px;\">"),
            "the measured cell is inline on the pair element, the grid reads it through CSS")
        assertTrue(html.contains("<div class=\"grid\">"), "the grid div itself carries no inline style (CSS reads --cell)")
        assertEquals(400, cells(html).size, "the scaling touches geometry only: 400 cells as always (verify_layout_v1 400-cell gate)")
    }

    /**
     * E4 (docs/FIRELINE-WEB.md 15.8, PLAN-EDGE-WALLS.md step E4): the thin edge
     * wall overlay. The acceptance claim: web and Android describe the same edges
     * for the same mission, each wall is a line ON a cell border and never a cell,
     * and a wall-free mission's grid markup stays byte-identical to the wall-free
     * build. Edge data comes only from the engine's renderer copies
     * (`Layout.wallVertRowMajor` / `wallHorizRowMajor`), the same arrays the
     * Android `EdgeWallGeometry` strokes with and the fixture records as
     * `WALL_VERT` / `WALL_HORIZ`.
     */
    @Test
    fun thinWallStrokeIsTheAndroidRatioRoundedToWholeEvenPixels() {
        assertEquals(2, WorldGrid.wallStrokePx(11), "the 11px narrow floor: 11 * 0.14 = 1.54 -> 2px")
        assertEquals(2, WorldGrid.wallStrokePx(15), "the frozen 15px desktop cell: 2.1 -> 2 -> 2px")
        assertEquals(2, WorldGrid.wallStrokePx(17), "a 17px cell: 2.38 -> 2 -> 2px")
        assertEquals(4, WorldGrid.wallStrokePx(18), "an 18px cell: 2.52 -> 3 -> nearest even rounds the tie up to 4px")
        assertEquals(4, WorldGrid.wallStrokePx(21), "a 21px tablet cell: 2.94 -> 3 -> 4px")
        assertEquals(4, WorldGrid.wallStrokePx(24), "a 24px tablet cell: 3.36 -> 3 -> 4px")
        assertEquals(4, WorldGrid.wallStrokePx(27), "the measured 613px column's 27px cell: 3.78 -> 4 -> 4px")
        assertEquals(4, WorldGrid.wallStrokePx(28), "the 28px cell: 3.92 -> 4 -> 4px")
        assertEquals(4, WorldGrid.wallStrokePx(34), "the 34px ceiling: 4.76 -> 4px, never above WALL_STROKE_MAX_PX")
        assertTrue(WorldGrid.wallStrokePx(0) >= WorldGrid.WALL_STROKE_MIN_PX, "never thinner than the floor")
        for (cell in WorldGrid.NARROW_CELL_PX..WorldGrid.CELL_MAX_PX) {
            val stroke = WorldGrid.wallStrokePx(cell)
            assertTrue(stroke % 2 == 0, "stroke $stroke at cell $cell is even, so the line straddles its border symmetrically")
            assertTrue(WorldGrid.WALL_STROKE_MIN_PX <= stroke && stroke <= WorldGrid.WALL_STROKE_MAX_PX, "stroke stays in [2, 4] at cell $cell")
            assertTrue(stroke < cell / 2, "the stroke stays far under half a cell: the two cell interiors it separates stay open")
        }
    }

    /**
     * The web overlay and the Android segments describe the SAME edges for
     * mission 06: the lane gate (the edge right of (5,2), authored `WALL_VERT`
     * row 2 char 5) plus the spawn cell's north and south edges (`WALL_HORIZ`
     * rows 1 and 2 char 2), the same three slots `EdgeWallGeometryTest` pins on
     * the Android side and `parity_matrix.tsv` counts as `wall_count` 3.
     */
    @Test
    fun thinWallOverlayDrawsExactlyTheEnginesEdgeSlotsForMission06() {
        val run = model.openMission(6)
        val world = run.currentWorld
        assertEquals(3, world.layout.edgeCount(), "mission 06 carries three edge walls (parity_matrix 6-reference wall_count)")
        val walls = WorldGrid.edgeWalls(world)
        assertEquals(
            listOf(
                EdgeWallSpec(true, 5, 2),   // v 5,2  lane gate, right border of (5,2)
                EdgeWallSpec(false, 2, 1),  // h 2,1  spawn cell's north edge
                EdgeWallSpec(false, 2, 2),  // h 2,2  spawn cell's south edge
            ),
            walls,
            "vertical edges row by row first, then horizontal, exactly the two renderer copies' row-major order",
        )
        assertEquals(3, walls.map { it.key }.distinct().size, "three distinct edge keys")

        val html = ConsoleRender.renderConsoleHtml(model, run, worldColumnPx = 320)
        val segs = Regex("<span class=\"wallseg ([vh])\" data-edge=\"([^\"]+)\" style=\"left:(\\d+)px;top:(\\d+)px\"></span>")
            .findAll(html).toList()
        assertEquals(3, segs.size, "one wallseg per engine edge (mission 06)")
        assertEquals(listOf("v 5,2", "h 2,1", "h 2,2"), segs.map { it.groupValues[2] }, "overlay keys equal the engine slots")
        val cell = WorldGrid.cellSizePx(320) // 14
        // v 5,2 sits ON the right border of (5,2): x = (5 + 1) * cell, spanning one cell of y from
        // the top border of row 2 (the ruler row is the extra row).
        assertEquals((5 + 1) * cell, segs[0].groupValues[3].toInt(), "vertical anchor is cellRightBorderPx(5), on the border, not in a cell")
        assertEquals((2 + 1) * cell, segs[0].groupValues[4].toInt(), "vertical anchor is cellTopBorderPx(2)")
        // h 2,1 / h 2,2 start ON the left border of column 2 and sit ON the bottom borders of
        // cells (2,1) / (2,2): cellBottomBorderPx(y) = (y + 2) * cell (the ruler row is the extra row).
        assertEquals(2 * cell, segs[1].groupValues[3].toInt(), "horizontal anchor is cellLeftBorderPx(2)")
        assertEquals(2 * cell, segs[2].groupValues[3].toInt(), "horizontal anchor is cellLeftBorderPx(2)")
        assertEquals((1 + 2) * cell, segs[1].groupValues[4].toInt(), "horizontal anchor is cellBottomBorderPx(1)")
        assertEquals((2 + 2) * cell, segs[2].groupValues[4].toInt(), "horizontal anchor is cellBottomBorderPx(2)")
        assertEquals(400, cells(html).size, "the overlay adds no cell node: the 400-cell gate still holds with walls present")
        assertEquals(0, Regex("class=\"cell [^\"]*wallseg").findAll(html).toList().size, "no wall is a cell class")
        assertTrue(html.contains("--wall-stroke:${WorldGrid.wallStrokePx(cell)}px"), "the pair hands over the stroke token the .wallseg CSS reads")
    }

    /**
     * Wall-free missions render no overlay at all, so every existing anchor of
     * the wall-free build (cell counts, markup) stays byte-identical for the
     * five missions that carry no edges.
     */
    @Test
    fun wallFreeMissionsRenderTheByteIdenticalWallFreeGrid() {
        for (missionId in 1..5) {
            val run = model.openMission(missionId)
            val world = run.currentWorld
            assertTrue(world.layout.wallVertRowMajor().none { it } && world.layout.wallHorizRowMajor().none { it },
                "mission $missionId declares no edges (PLAN-EDGE-WALLS.md DW4: no retrofit)")
            assertEquals(0, WorldGrid.edgeWalls(world).size, "mission $missionId has no overlay")
            assertEquals("", WorldGrid.wallSegmentsHtml(world, 14), "mission $missionId emits an empty overlay string")
            val html = ConsoleRender.renderConsoleHtml(model, run, worldColumnPx = 613)
            assertEquals(0, Regex("wallseg").findAll(html).toList().size, "no wallseg markup for mission $missionId")
            assertEquals(400, cells(html).size, "mission $missionId keeps the frozen 400-cell grid")
        }
    }

    /**
     * E4 (15.8): the anchor arithmetic is the cell geometry, asserted against the cell nodes
     * themselves and at both ends of the allowed cell range (the 11px narrow floor, the 34px
     * desktop ceiling, plus a measured mid value). A vertical anchor must equal the inline left of
     * the cell to its right and the inline left plus one cell of the cell to its left; a horizontal
     * anchor must equal the inline top of the cell below it. This is what the browser measurement
     * of `docs/EDGE-E4-RECORD.md` section 7 reads back in px: the 400 cells fill the grid's padding
     * box, so a content column is `x * cell` and the ruler labels live in the padding margins.
     */
    @Test
    fun thinWallAnchorsLandOnTheBorderTheTwoCellsShare() {
        val run = model.openMission(6)
        val world = run.currentWorld
        for (cell in listOf(WorldGrid.NARROW_CELL_PX, 21, WorldGrid.CELL_MAX_PX)) {
            val segs = segMarkup(WorldGrid.wallSegmentsHtml(world, cell))
            assertEquals(3, segs.size, "mission 06 has three edges at cell $cell")
            val (v, h1, h2) = segs
            assertEquals("v 5,2", v.key)
            assertEquals(6 * cell, v.left, "v 5,2 left is the right border of (5,2) = the left border of (6,2)")
            assertEquals(3 * cell, v.top, "v 5,2 top is the top border of row 2 (ruler row is the extra row)")
            assertEquals("h 2,1", h1.key)
            assertEquals(2 * cell, h1.left, "h 2,1 left is the left border of column 2")
            assertEquals(3 * cell, h1.top, "h 2,1 top is the bottom border of row 1")
            assertEquals("h 2,2", h2.key)
            assertEquals(2 * cell, h2.left, "h 2,2 left is the left border of column 2")
            assertEquals(4 * cell, h2.top, "h 2,2 top is the bottom border of row 2")
            // The same numbers as cell boxes: cell (6,2) starts where the vertical bar is, cell
            // (2,3) starts where h 2,2 is. `--cell` is written inline by cellStyleAttr(worldColumnPx),
            // so a chosen cell edge is the column (cell * GRID_TRACK_CELLS + 2) that fits it.
            val columnPx = cell * WorldGrid.GRID_TRACK_CELLS + 2
            val html = ConsoleRender.renderConsoleHtml(model, run, worldColumnPx = columnPx)
            val styleOf = Regex("style=\"--cell:(\\d+)px").findAll(html).first().groupValues[1].toInt()
            assertEquals(cell, styleOf, "the pair hands over the chosen cell token")
            assertTrue(cellAt(html, 6, 2).cssClass != "wall" && cellAt(html, 5, 2).cssClass != "wall",
                "the vertical bar straddles two cells that are not wall cells (mission 06 has no wall cell)")
            assertTrue(cellAt(html, 2, 2).cssClass != "wall" && cellAt(html, 2, 3).cssClass != "wall",
                "h 2,2 straddles two cells that are not wall cells")
            assertEquals(0, Regex("class=\"cell wall\"").findAll(html).toList().size,
                "mission 06 draws no wall cell next to its bars (E2 pin: 0 TERRAIN_WALL cells)")
            assertEquals(0, Regex("class=\"cell [^\"]*wallseg").findAll(html).toList().size, "no bar is a cell")
        }
    }
}
