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
 * used. Mission 06 carries the 13 edges of its single wall column (measured
 * with the same `Layout.edgeCount`).
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
    // GO AROUND, the thin-wall capstone (PLAN-RECONCILE-MAIN-01.md §10 step R4,
    // fire half measured in step R5). One vertical thin wall:
    // vertEdgeSegment(9, 0, 12), 13 edges on the border between (9,y) and (10,y)
    // for rows 0..12. The rover starts west at (2,10) facing east: its straight
    // MOVE stops at the walled edge, every straight SHOOT is refused there
    // ("PACKET BLOCKED EDGE", the Arena never spawns a packet through a thin
    // wall), and AHEAD answers 1 across it exactly like a wall cell would. The
    // only west->east crossings are the open rows 13..19 below the wall's lower
    // end.
    //
    // The pocket east of the wall holds exactly ONE burning cell (10,10) at
    // authored level 2 (50 intensity, in the 26..50 band) and no fuel cells, and
    // that cell sits ON a walled row: its western neighbour (9,10) is open
    // passable floor behind the thin wall, so the wall is the only thing between
    // this fire and the west half of the field. Its other three faces are the
    // terrain walls (10,9) north and (11,10) east plus the refill cell (10,11)
    // south (walls and refill cells never ignite); that refill cell is also the
    // firing stand. The rover walks around the lower end of the wall along the
    // open row 13, comes up column 10 to (10,11) and drowns the pocket cell from
    // there (measured: four packets, arrival tick 29), then walks to the exit
    // (12,10) on the pocket row east of the burning cell.
    //
    // Fire load bearing, measured with the scratch twin (MeasureR6, seed 6027):
    // the spread stream aims W at (9,10) on tick 15 with chance 37, below the 64
    // floor threshold, and the thin wall is what refuses it - cross-wall
    // ignitions in the walled run: 0. In the edge-open counterfactual (same
    // layout with the 13 wall segments removed, same seed, same reference
    // firmware) that draw ignites (9,10) at tick 15, the fire crawls west to
    // (8,12) above the refill lane and back east into the pocket at tick 55, the
    // same firmware misses EXTINGUISH_ALL and ends TIME_EXPIRED at tick 90 with
    // 8 cells burning instead of SUCCESS at tick 49, and the spray bot dies
    // (ROVER_DESTROYED at tick 59, hp 0) on the cell the wall keeps safe.
    val GO_AROUND = Mission(
        id = 6,
        name = "GO AROUND",
        briefing = "A thin wall on your starting row stops MOVE, water and fire. The burning cell right behind it cannot cross: walk around the lower end of the wall, drown it from the refill cell south of the pocket, then reach the exit east of the pocket.",
        layout = Layout.decode(
            terrainRows = field(
                2, 10,
                listOf(
                    Triple(10, 9, GLYPH_WALL),                            // pocket shell, north of the fire
                    Triple(11, 10, GLYPH_WALL),                          // pocket shell, east of the fire, seals the exit side
                    Triple(10, 11, GLYPH_REFILL),                        // pocket shell, south of the fire: the refill firing stand
                    Triple(12, 10, GLYPH_EXIT),                          // exit, east of the burning cell on the pocket row
                ) + (2..9).map { Triple(it, 13, GLYPH_REFILL) },        // west refill lane on the open row below the wall
            ),
            fireRows = fireAt(listOf(Triple(10, 10, '2'))),              // the single pocket cell ON the walled row 10, level 2 = 50
            roverDir = DIR_E,
            vertEdgeRows = vertEdgeRows(*vertEdgeSegment(9, 0, 12).toTypedArray()),
        ),
        seed = 6027,
        objectives = Objectives.EXTINGUISH_ALL or Objectives.REACH_EXIT,
        timeLimit = 90,
        startingWater = 100,
        windDir = DIR_W,
        windForce = 0,
        referenceSource =
            "       TURN   R\n" +                                       // E -> S: the thin wall ahead is not negotiable
                "       MOVE\n       MOVE\n       MOVE\n" +             // (2,10) -> (2,13), down past the wall's lower end
                "       TURN   L\n" +                                   // S -> E
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" + // (2,13) -> (10,13), along the open row under the wall
                "       TURN   L\n" +                                   // E -> N
                "       MOVE\n       MOVE\n" +                          // (10,13) -> (10,11), the refill firing stand (tick 29)
                "       SHOOT\n       SHOOT\n       SHOOT\n       SHOOT\n" +   // 4 x 25 drown the grown pocket cell (measured)
                "       TURN   R\n" +                                   // N -> E
                "       MOVE\n       MOVE\n" +                          // (10,11) -> (12,11), south of the pocket shell
                "       TURN   L\n" +                                   // E -> N
                "       MOVE\n" +                                       // (12,11) -> (12,10), the exit on the pocket row
                "       WAIT\n       JMP    fin\nfin:   WAIT\n       JMP    fin",
        failingSources = listOf(
            Triple("spray straight at the wall",
                // The naive bot walks to the thin wall and sprays east. Every SHOOT is refused at
                // the walled edge ("PACKET BLOCKED EDGE", PLAN-EDGE-WALLS.md §3.6; measured: 9
                // refusals from (9,10) toward 10,10); it never drowns (10,10) and never leaves the
                // wall, so it runs out of time. Its AHEAD answers 1 across the edge, exactly like a
                // wall cell would. Behind the wall it survives at hp 92 (TIME_EXPIRED at tick 90);
                // in the edge-open counterfactual the same bot dies on (9,10) at tick 59.
                "       MOVE\n       MOVE\n       MOVE\n       MOVE\n" +
                "       MOVE\n       MOVE\n       MOVE\n" +             // (2,10) -> (9,10), stopped by the walled edge
                "loop:  SHOOT\n       JMP    loop",
                FailureReason.TIME_EXPIRED),
        ),
        maxTicks = 300,
    )

    val ALL: List<Mission> = listOf(NOZZLE_CHECK, CORRIDOR, FIRST_RESPONSE, DOWNWIND, HOT_ZONE, GO_AROUND)
        .map { it.requireValid() }

    fun byId(id: Int): Mission? = ALL.firstOrNull { it.id == id }

    /** Reference results for every authored mission (headless §13.2 path). */
    fun referenceResults(): List<FirelineResult> = ALL.map { FirelineRun.runWithGuard(it, it.descriptorForSource(it.referenceSource)) }
}
