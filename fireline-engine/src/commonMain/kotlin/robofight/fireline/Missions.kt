package robofight.fireline

/**
 * The six authored free missions (FIRELINE-DESIGN.md §9.1-§9.6).
 *
 * Layouts are ASCII cell arrays in version control (§9 "ASCII source
 * representation"): 20x20 rows, `.` floor, `#` wall, `F` fuel, `W` refill,
 * `E` exit, `P` protected asset, `R` rover spawn, `C` civilian. Fire rows use
 * the authored levels `1`=25, `2`=50, `3`=75, `4`=100. Mission 06 additionally
 * carries the two thin edge wall blocks of PLAN-EDGE-WALLS.md §4 (WALL_VERT
 * 20x19, WALL_HORIZ 19x20).
 *
 * Missions 01-05 are the layouts origin/main ships (PLAN-RECONCILE-MAIN-01.md
 * §8 decision 1 / §10 step 5): main revised all five MVP layouts to teach its
 * thin walls, so each one now carries thin edge walls — 2, 25, 11, 18 and 40
 * edges for missions 01 to 05 in order, measured with the engine's own
 * `Layout.edgeCount()` — instead of the thick wall rows the pre-merge layouts
 * used. Mission 06 carries the three edges of the pinned E2 lane fixture.
 *
 * Mission ID + name are immutable; changing a layout under an existing ID is a
 * content compatibility change (§9) and needs a new ID.
 *
 * Layouts are authored so that the deterministic spread rules can only reach
 * cells the reference solution is built to handle: burning cells are ringed by
 * walls/refill cells except on the line the rover shoots along. That keeps
 * `EXTINGUISH_ALL` missions solvable under the fixed 5-tick spread cadence
 * while still teaching the sensors the mission is named for.
 */
object Missions {

    private fun rows(vararg r: String): List<String> = r.map { it.padEnd(GRID, GLYPH_FLOOR) }

    /** All-dot fire map. */
    private fun emptyFire(): List<String> = List(GRID) { ".".repeat(GRID) }

    /** Fill selected (x,y) cells of an all-dot fire map. */
    private fun fireAt(edits: List<Triple<Int, Int, Char>>): List<String> {
        val grid = emptyFire().map { it.toCharArray() }
        for ((x, y, ch) in edits) grid[y][x] = ch
        return rows(*grid.map { it.concatToString() }.toTypedArray())
    }

    /** Set terrain glyphs at (x,y) on an all-floor field with the spawn marker. */
    private fun field(spawnX: Int, spawnY: Int, edits: List<Triple<Int, Int, Char>>): List<String> {
        val grid = (0 until GRID).map { CharArray(GRID) { GLYPH_FLOOR } }
        for ((x, y, ch) in edits) grid[y][x] = ch
        grid[spawnY][spawnX] = GLYPH_ROVER
        return rows(*grid.map { it.concatToString() }.toTypedArray())
    }

    /**
     * WALL_VERT rows (PLAN-EDGE-WALLS.md §4): `row to column` puts a thin wall on
     * the edge to the RIGHT of cell (column, row). Missing pairs stay open, so a
     * mission only names the edges it actually walls.
     */
    private fun vertEdgeRows(vararg walls: Pair<Int, Int>): List<String> =
        (0 until WALL_VERT_ROWS).map { y ->
            (0 until VERT_EDGE_COLUMNS).map { x ->
                if (walls.any { it.first == y && it.second == x }) EDGE_GLYPH_WALL else EDGE_GLYPH_OPEN
            }.joinToString("")
        }

    /** WALL_HORIZ rows: `row to column` puts a thin wall on the edge BELOW cell (column, row). */
    private fun horizEdgeRows(vararg walls: Pair<Int, Int>): List<String> =
        (0 until WALL_HORIZ_ROWS).map { y ->
            (0 until GRID).map { x ->
                if (walls.any { it.first == y && it.second == x }) EDGE_GLYPH_WALL else EDGE_GLYPH_OPEN
            }.joinToString("")
        }

    /** Main's segment authoring form (`verticalRows`): thin walls on the edges to
     *  the RIGHT of every cell `(x, y0..y1)`, as `vertEdgeRows` pairs. */
    private fun vertEdgeSegment(x: Int, y0: Int, y1: Int): List<Pair<Int, Int>> = (y0..y1).map { it to x }

    /** Main's segment authoring form (`horizontalRows`): thin walls on the edges
     *  BELOW every cell `(x0..x1, y)`, as `horizEdgeRows` pairs. */
    private fun horizEdgeSegment(y: Int, x0: Int, x1: Int): List<Pair<Int, Int>> = (x0..x1).map { y to it }

    /**
     * Shared reactive extinguishing pattern: shoot at the selected burning cell
     * while any fire remains, bounded so a broken layout can never loop forever.
     * `IN FIRE_LEVEL` + `JZ` is the FIRELINE-native "is anything still burning"
     * test (§6.3); the bounded chain keeps the program finite and deterministic.
     */
    private fun extinguishBlocks(shots: Int): String = buildString {
        var label = 1
        for (i in 0 until shots) {
            append("       IN     FIRE_LEVEL\n")
            append(if (i == 0) "       JNZ    s$label\n       JMP    fin\n" else "       JNZ    s$label\n       JMP    fin\n")
            append("s$label:   SHOOT\n")
            label++
        }
    }

    // ---- 9.1 ----
    // Main's seal for the firing lane: the wall cell east of the fire plus the
    // thin edges south of (5,1) and south of (5,2). The fire at (5,2) can only
    // ever be engaged from the west, down the lane the rover shoots along.
    val NOZZLE_CHECK = Mission(
        id = 1,
        name = "NOZZLE CHECK",
        briefing = "One stationary fire straight ahead. Read DIST/ANGLE, shoot, watch HEAT.",
        layout = Layout.decode(
            terrainRows = field(2, 2, listOf(Triple(6, 2, GLYPH_WALL))),
            fireRows = fireAt(listOf(Triple(5, 2, '2'))),
            roverDir = DIR_E,
            horizEdgeRows = horizEdgeRows(1 to 5, 2 to 5),
        ),
        seed = 1001,
        objectives = Objectives.EXTINGUISH_ALL,
        timeLimit = 60,
        startingWater = 100,
        windDir = DIR_W,
        windForce = 0,
        referenceSource = extinguishBlocks(8) + "fin:   WAIT\n       JMP    fin",
        failingSources = listOf(
            Triple("never shoots", "loop:  WAIT\n       JMP    loop", FailureReason.TIME_EXPIRED),
        ),
        maxTicks = 200,
    )

    // ---- 9.2 ----
    // Thin wall below row 2, with a gate at x=2; the naive east route hits
    // the solid obstacle at (3,2).
    // The rover drops through the gap at (2,3), walks east along row 10 to the
    // pocket approach at (8,10) — a refill firebreak the pocket fire can never
    // ignite — and shoots the pocket cell (9,10) directly (the packet spawns
    // on the burning cell). The pocket is ringed by thin walls on the north,
    // west-approach side and south and by the refill east of it, so the fixed
    // 5-tick spread cadence can never leave the pocket; the burning pocket is
    // also the barrier that makes the timed MOVE phase wait until the shots
    // actually cut the lane open, and the exit sits behind the dead pocket.
    val CORRIDOR = Mission(
        id = 2,
        name = "CORRIDOR",
        briefing = "The wall in front of you is not negotiable. Turn, walk, shoot what burns ahead, then reach the exit.",
        layout = Layout.decode(
            terrainRows = field(
                2, 2,
                listOf(Triple(3, 2, GLYPH_WALL)) +
                    listOf(
                        Triple(8, 10, GLYPH_REFILL),                           // approach side firebreak
                        Triple(10, 10, GLYPH_REFILL),                          // pocket east
                        Triple(11, 10, GLYPH_EXIT),                            // exit behind the pocket
                    ),
            ),
            fireRows = fireAt(listOf(Triple(9, 10, '1'))),
            roverDir = DIR_E,
            horizEdgeRows = horizEdgeRows(
                *(
                    horizEdgeSegment(2, 0, 1) + horizEdgeSegment(2, 3, 19) +      // one-cell gate at x=2
                        horizEdgeSegment(9, 8, 10) + horizEdgeSegment(10, 8, 10)  // thin pocket walls
                    ).toTypedArray(),
            ),
        ),
        seed = 2002,
        objectives = Objectives.EXTINGUISH_ALL or Objectives.REACH_EXIT,
        timeLimit = 120,
        startingWater = 100,
        windDir = DIR_W,
        windForce = 0,
        referenceSource =
            "       TURN   R\n" +                                       // E -> S: the wall ahead is not negotiable
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +   // drop through the gap at (2,3)
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +   // (2,2) -> (2,10)
                "       TURN   L\n" +                                   // S -> E along row 10
                "walk:  IN     FIRE_DIST\n" +
                "       JZ     fin\n" +                                 // no fire left: stop walking into the pocket
                "       CMP    #1\n" +
                "       JLE    ext\n" +
                "       MOVE\n" +                                       // approach (3,10)..(8,10); the pocket blocks the rest
                "       JMP    walk\n" +
                "ext:   IN     FIRE_LEVEL\n" +
                "       JZ     fin\n" +
                "       SHOOT\n" +                                      // packet lands on the pocket cell directly ahead
                "       JMP    ext\n" +
                "fin:   MOVE\n       MOVE\n       MOVE\n" +            // through the dead pocket to the exit
                "       WAIT\n       JMP    fin",
        failingSources = listOf(
            Triple("naive always forward", "loop:  MOVE\n       JMP    loop", FailureReason.TIME_EXPIRED),
        ),
        maxTicks = 300,
    )

    // ---- 9.3 ----
    // Main's thin walls shape the rescue route: a vertical edge column at x=7
    // for rows 3..9 and a horizontal edge row at y=9 for x=3..6 keep the rover
    // on the (8,2) -> (8,10) -> exit corridor instead of cutting corners.
    val FIRST_RESPONSE = Mission(
        id = 3,
        name = "FIRST RESPONSE",
        briefing = "Two civilians, small fires elsewhere. Contact rescues itself; use CIV_COUNT and the exit sensors.",
        layout = Layout.decode(
            terrainRows = field(
                2, 2,
                listOf(Triple(5, 2, GLYPH_CIVILIAN), Triple(8, 10, GLYPH_CIVILIAN), Triple(12, 10, GLYPH_EXIT)),
            ),
            fireRows = fireAt(listOf(Triple(12, 4, '1'), Triple(14, 14, '1'), Triple(3, 16, '1'))),
            roverDir = DIR_E,
            vertEdgeRows = vertEdgeRows(*vertEdgeSegment(7, 3, 9).toTypedArray()),
            horizEdgeRows = horizEdgeRows(*horizEdgeSegment(9, 3, 6).toTypedArray()),
        ),
        seed = 3003,
        objectives = Objectives.RESCUE_ALL or Objectives.REACH_EXIT,
        timeLimit = 120,
        startingWater = 100,
        windDir = DIR_W,
        windForce = 0,
        referenceSource =
            "       MOVE\n       MOVE\n       MOVE\n" +                       // (2,2) -> (5,2) civ 1
                "       MOVE\n       MOVE\n       MOVE\n" +                       // -> (8,2)
                "       TURN   R\n" +                                            // E -> S
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +         // (8,2) -> (8,6)
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +         // -> (8,10) civ 2
                "       TURN   L\n" +                                            // S -> E
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +         // -> (12,10) exit
                "       WAIT\n       JMP    fin\nfin:   WAIT\n       JMP    fin",
        failingSources = listOf(
            Triple("skips the second civilian",
                "       MOVE\n       MOVE\n       MOVE\n       TURN   R\n" +
                    "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                    "       TURN   L\n       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                    "loop:  WAIT\n       JMP    loop",
                FailureReason.TIME_EXPIRED),
        ),
        maxTicks = 300,
    )

    // ---- 9.4 ----
    // A reactive loop shoots roughly once per (instruction cycle) ticks; a
    // capped fuel cell (+10 every 5-tick spread window) outgrows bloated
    // loops, so this mission is built around the leanest possible one: a
    // PARKED bomber that never moves (FIRE_ANGLE + SHOOT, ~8 ticks per shot,
    // net -9 per cycle). A water packet lives 20 steps and passes through
    // extinguished cells, so the rover standing on the refill at (3,2) can
    // suppress every burning cell on row 2 without ever advancing.
    // (4,2)+(5,2) is the burning pair ON the wind axis (WIND_DIR=E, force 2
    // boosts the eastward attempts): the WEST cell (4,2) starts alight and is
    // ground first (nearest); the east cell (5,2) catches on a BOOSTED
    // eastward attempt, so exactly when it lights - and how hard the rover
    // has to grind it - depends on the wind force. Wind 2 vs wind 3 produce
    // different engagement lengths, which is the whole reason to read
    // WIND_DIR/WIND_FORCE. A westward relight mole (5,2)->(4,2) is cheap to
    // kill with the 6-tick shoot cadence (a fresh 25 dies in one packet).
    // The pair is sealed (refill west/east, walls north/south), so wind can
    // never push fire into open floor; (8,2) and (11,2) are sealed UNLIT
    // fuel singletons: fuel tiles the FIRE sensors report that must stay
    // cold. After the lane is cold the rover walks south and west to the
    // civilian, then south to the exit.
    val DOWNWIND = Mission(
        id = 4,
        name = "DOWNWIND",
        briefing = "Wind pushes fire into the fuel tiles. Check WIND_DIR/WIND_FORCE, hold the refill station, and drown the burning pair cell by cell.",
        layout = Layout.decode(
            terrainRows = field(
                2, 2,
                listOf(
                    Triple(3, 2, GLYPH_REFILL),
                    Triple(4, 2, GLYPH_FUEL), Triple(5, 2, GLYPH_FUEL),
                    Triple(6, 2, GLYPH_REFILL),
                    Triple(7, 2, GLYPH_REFILL), Triple(8, 2, GLYPH_FUEL), Triple(9, 2, GLYPH_REFILL),
                    Triple(10, 2, GLYPH_REFILL), Triple(11, 2, GLYPH_FUEL), Triple(12, 2, GLYPH_REFILL),
                    Triple(2, 6, GLYPH_CIVILIAN), Triple(2, 12, GLYPH_EXIT),
                ),
            ),
            fireRows = fireAt(listOf(Triple(4, 2, '1'))),
            roverDir = DIR_E,
            horizEdgeRows = horizEdgeRows(
                *(horizEdgeSegment(1, 4, 12) + horizEdgeSegment(2, 4, 12)).toTypedArray(),
            ),
        ),
        seed = 4004,
        objectives = Objectives.RESCUE_ALL or Objectives.EXTINGUISH_ALL or Objectives.REACH_EXIT,
        timeLimit = 500,
        startingWater = 100,
        windDir = DIR_E,
        windForce = 2,
        referenceSource =
            "       IN     WIND_DIR\n" +                                  // lesson: read the wind first
                "       IN     WIND_FORCE\n" +
                "       MOVE\n" +                                           // step onto the refill station and park
                "main:  IN     FIRE_LEVEL\n" +
                "       JZ     civ\n" +
                "       SHOOT\n" +                                        // parked on the refill: every burning
                "       JMP    main\n" +                                 // cell sits on this firing axis
                "civ:   IN     CIV_COUNT\n" +
                "       JZ     exit\n" +
                "       IN     CIV_DIST\n" +
                "       CMP    #1\n" +
                "       JLE    civface\n" +                               // close the last step facing the civilian,
                "       IN     CIV_ANGLE\n" +                             // never sideways into a diagonal approach
                "       JZ     walk\n" +
                "       TURN   R\n" +
                "       JMP    civ\n" +
                "civface: IN   CIV_ANGLE\n" +
                "       JZ     walk\n" +
                "       TURN   R\n" +
                "       JMP    civface\n" +
                "walk:  MOVE\n" +
                "       JMP    civ\n" +
                "exit:  IN     EXIT_DIST\n" +
                "       CMP    #1\n" +
                "       JLE    eface\n" +
                "       IN     EXIT_ANGLE\n" +
                "       JZ     ewalk\n" +
                "       TURN   R\n" +
                "       JMP    exit\n" +
                "eface: IN     EXIT_ANGLE\n" +
                "       JZ     ewalk\n" +
                "       TURN   R\n" +
                "       JMP    eface\n" +
                "ewalk: MOVE\n" +
                "       JMP    exit\n",
        failingSources = listOf(
            Triple("ignores the burning line", "loop:  TURN   R\n       MOVE\n       JMP    loop", FailureReason.TIME_EXPIRED),
        ),
        maxTicks = 700,
    )

    // ---- 9.5 ----
    val HOT_ZONE = Mission(
        id = 5,
        name = "HOT ZONE",
        briefing = "A burning corridor, a refill pocket, a civilian and an asset to protect. Cross it shielded: two ticks to raise, five ticks of protection.",
        layout = Layout.decode(
            terrainRows = field(
                1, 2,
                listOf(Triple(0, 2, GLYPH_WALL)) +
                    (2..8).map { Triple(it, 2, GLYPH_FLOOR) } +
                    listOf(Triple(9, 2, GLYPH_REFILL)) +
                    (10..15).map { Triple(it, 2, GLYPH_FLOOR) } +
                    listOf(Triple(16, 2, GLYPH_REFILL), Triple(17, 2, GLYPH_CIVILIAN),
                        Triple(18, 2, GLYPH_EXIT), Triple(5, 5, GLYPH_PROTECTED)),
            ),
            fireRows = fireAt(
                (2..8).map { Triple(it, 2, '4') } + (10..15).map { Triple(it, 2, '4') },
            ),
            roverDir = DIR_E,
            horizEdgeRows = horizEdgeRows(
                *(horizEdgeSegment(1, 0, 19) + horizEdgeSegment(2, 0, 19)).toTypedArray(),
            ),
        ),
        seed = 5005,
        objectives = Objectives.RESCUE_ALL or Objectives.PROTECT_ASSETS or Objectives.REACH_EXIT,
        timeLimit = 200,
        startingWater = 60,
        windDir = DIR_N,
        windForce = 0,
        referenceSource =
            "       SHIELD\n" +
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       SHIELD\n" +
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       SHIELD\n" +
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       SHIELD\n" +
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       WAIT\n" +                       // cooling stop on the refill pocket
                "       SHIELD\n" +
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       SHIELD\n" +
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       MOVE\n       MOVE\n" +         // civilian cell, then exit
                "       WAIT\n       JMP    fin\nfin:   WAIT\n       JMP    fin",
        failingSources = listOf(
            Triple("no shield, straight through",
                "loop:  MOVE\n       JMP    loop", FailureReason.ROVER_DESTROYED),
        ),
        maxTicks = 400,
    )

    // ---- 9.6 ----
    // The thin wall mission (PLAN-EDGE-WALLS.md §7 step E5). The layout carries
    // NO wall cells: the only walls are three thin edges, the same three the
    // pinned E2 fixture rows carry (`docs/web/fixtures/parity_matrix.tsv`
    // `wall-lane-move` / `wall-open-lane`: WALL_VERT row 2 char 5, the edge right
    // of (5,2), plus the spawn cell's north and south edges at WALL_HORIZ rows 1
    // and 2 char 2). The lane gate is what the mission is named for: it stops the
    // rover's straight MOVE, it stops a water packet at spawn and absorbs one in
    // flight, and AHEAD answers 1 across it, exactly like a wall cell would -
    // while fire spreads across it (decision DW1), so the burning lane cell
    // (6,2) lights the lane cell on the rover's side of the wall.
    //
    // Refill cells ring the lane: row 1 and row 3 for x=2..17, plus (4,2) and
    // (7,2) ON the lane. A refill cell never ignites, so the crossing can only
    // ever be (6,2) -> (5,2) and back: exactly the two cells the reference
    // solution drowns, one on each side of the wall, and row 3 is the safe
    // bypass lane the rover shoots the lane from. Seed 6027 is the seed whose
    // spread stream crosses the gate at tick 15 (a W attempt westward across the
    // walled edge) and never relights the source cell after it is drowned.
    val THIN_WALL = Mission(
        id = 6,
        name = "THIN WALL",
        briefing = "The wall here is only a line between two cells. It stops your MOVE, it stops your water, and it stops the fire too. Read AHEAD, turn into the refill lane, drown the burning cell from the bypass under it, then walk the bypass to the exit.",
        layout = Layout.decode(
            terrainRows = field(
                2, 2,
                (2..17).map { Triple(it, 1, GLYPH_REFILL) } +
                    (2..17).map { Triple(it, 3, GLYPH_REFILL) } +
                    listOf(
                        Triple(4, 2, GLYPH_REFILL),                            // lane firebreak west of the gate
                        Triple(7, 2, GLYPH_REFILL),                           // lane firebreak east of the fire
                        Triple(17, 2, GLYPH_EXIT),                            // exit at the end of the lane
                    ),
            ),
            fireRows = fireAt(listOf(Triple(6, 2, '1'))),
            roverDir = DIR_E,
            vertEdgeRows = vertEdgeRows(2 to 5),                              // the lane gate right of (5,2)
            horizEdgeRows = horizEdgeRows(1 to 2, 2 to 2),                    // the spawn cell cannot be left north or south
        ),
        seed = 6027,
        objectives = Objectives.EXTINGUISH_ALL or Objectives.REACH_EXIT,
        timeLimit = 120,
        startingWater = 100,
        windDir = DIR_W,
        windForce = 0,
        referenceSource =
            "       MOVE\n       MOVE\n       MOVE\n" +                    // (2,2) -> (5,2): the lane cell west of the thin wall
                "       IN     AHEAD\n" +                                // the thin wall answers 1, the same sentinel a wall cell gives (§6.2)
                "       JZ     straight\n" +                            // empty ahead: go straight. Here the lane is closed
                "       TURN   R\n" +                                  // E -> S: the wall is not negotiable, the bypass is
                "straight:\n       MOVE\n" +                            // (5,2) -> (5,3) onto the refill lane
                "       TURN   L\n       MOVE\n" +                     // S -> E, step to (6,3) under the burning cell
                "       TURN   L\n" +                                  // E -> N
                "       SHOOT\n       SHOOT\n" +                       // drown (6,2): the packet spawns on it, no edge to cross
                "       TURN   L\n       MOVE\n" +                     // N -> W, back to (5,3)
                "       TURN   R\n" +                                  // W -> N
                "       SHOOT\n       SHOOT\n" +                       // aimed at (5,2): under the merged rule the fire never crossed the gate, so these find no fire and expire at the boundary (06 is slated for redesign)
                "       TURN   R\n" +                                  // N -> E, walk the refill bypass east
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +   // (5,3) -> (17,3)
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       TURN   L\n" +                                  // E -> N
                "       MOVE\n" +                                      // (17,3) -> (17,2) the exit
                "       WAIT\n       JMP    fin\nfin:   WAIT\n       JMP    fin",
        failingSources = listOf(
            Triple("spray through the thin wall",
                "       MOVE\n       MOVE\n       MOVE\nloop:  SHOOT\n       JMP    loop",
                FailureReason.TIME_EXPIRED),
        ),
        maxTicks = 300,
    )

    val ALL: List<Mission> = listOf(NOZZLE_CHECK, CORRIDOR, FIRST_RESPONSE, DOWNWIND, HOT_ZONE, THIN_WALL)
        .map { it.requireValid() }

    fun byId(id: Int): Mission? = ALL.firstOrNull { it.id == id }

    /** Reference results for every authored mission (headless §13.2 path). */
    fun referenceResults(): List<FirelineResult> = ALL.map { FirelineRun.runWithGuard(it, it.descriptorForSource(it.referenceSource)) }
}
