package robofight.fireline

import robofight.assembler.assemble

/**
 * Shared test support: author tiny deterministic missions without repeating the
 * decode/assemble/descriptor boilerplate in every suite. Plain JUnit 5 suites
 * use these; nothing here is an engine entry point (no check()/main()/
 * exitProcess facade — the :rf8-core test-idiom rule applies to this module).
 */

/** Firmware used when a test only needs the world to tick. */
internal const val IDLE_FIRMWARE = "loop:  WAIT\n       JMP    loop"

internal fun rowsOf(rows: List<CharArray>): List<String> = rows.map { String(it) }

/** 20x20 all-floor authoring rows with the rover spawn marker at (sx,sy). */
internal fun openTerrainRows(sx: Int = 2, sy: Int = 2): List<String> {
    val grid = (0 until GRID).map { CharArray(GRID) { GLYPH_FLOOR } }
    grid[sy][sx] = GLYPH_ROVER
    return rowsOf(grid)
}

/** Apply (x,y,glyph) edits to authoring rows. */
internal fun editRows(rows: List<String>, edits: List<Triple<Int, Int, Char>>): List<String> {
    val grid = rows.map { it.toCharArray() }
    for ((x, y, ch) in edits) grid[y][x] = ch
    return rowsOf(grid)
}

/** Empty fire map. */
internal fun noFireRows(): List<String> = (0 until GRID).map { ".".repeat(GRID) }

/** Fire map with (x,y,level glyph) edits: 1=25, 2=50, 3=75, 4=100. */
internal fun fireRows(edits: List<Triple<Int, Int, Char>>): List<String> =
    editRows(noFireRows(), edits)

internal fun layoutOf(
    terrain: List<String> = openTerrainRows(),
    fire: List<String> = noFireRows(),
    dir: Int = DIR_E,
    civilians: List<CivilianSpec> = emptyList(),
): Layout = Layout.decode(terrain, fire, dir, civilians)

/**
 * Mechanics unit tests need a world that does not end on the first tick, so the
 * default mission is a SURVIVE_UNTIL whose deadline is far beyond any unit test
 * instead of an objective that a fire-less test layout trivially satisfies.
 */
internal const val MECHANICS_OBJECTIVES = Objectives.SURVIVE_UNTIL

internal fun descriptorOf(
    layout: Layout,
    firmwareSource: String = IDLE_FIRMWARE,
    seed: Int = 1234,
    objectives: Int = MECHANICS_OBJECTIVES,
    timeLimit: Int? = null,
    surviveUntil: Int? = if (objectives and Objectives.SURVIVE_UNTIL != 0) MAX_TICKS else null,
    water: Int = DEFAULT_STARTING_WATER,
    windDir: Int = DIR_W,
    windForce: Int = 0,
    missionId: Int = 1,
): MissionDescriptor {
    val r = assemble(firmwareSource)
    check(r.ok) { "test firmware failed to assemble: ${r.errors.joinToString { it.msg }}" }
    return MissionDescriptor(
        missionId = missionId,
        seed = seed,
        objectives = objectives,
        timeLimit = timeLimit,
        surviveUntil = surviveUntil,
        startingWater = water,
        windDir = windDir,
        windForce = windForce,
        firmware = r.code,
        layout = layout,
    )
}

internal fun worldOf(
    terrain: List<String> = openTerrainRows(),
    fire: List<String> = noFireRows(),
    dir: Int = DIR_E,
    firmwareSource: String = IDLE_FIRMWARE,
    seed: Int = 1234,
    objectives: Int = MECHANICS_OBJECTIVES,
    timeLimit: Int? = null,
    surviveUntil: Int? = if (objectives and Objectives.SURVIVE_UNTIL != 0) MAX_TICKS else null,
    water: Int = DEFAULT_STARTING_WATER,
    windDir: Int = DIR_W,
    windForce: Int = 0,
    civilians: List<CivilianSpec> = emptyList(),
    missionId: Int = 1,
): FirelineWorld = FirelineWorld(
    descriptorOf(
        layoutOf(terrain, fire, dir, civilians), firmwareSource, seed, objectives,
        timeLimit, surviveUntil, water, windDir, windForce, missionId,
    )
)

/** Assemble a firmware source to its canonical image (for direct-binary tests). */
internal fun codeOf(source: String): ByteArray {
    val r = assemble(source)
    check(r.ok) { "test firmware failed to assemble: ${r.errors.joinToString { it.msg }}" }
    return r.code
}
