package robofight.fireline

/**
 * Headless ASCII renderer for the FIRELINE grid (FIRELINE-DESIGN.md §11.4).
 *
 * Glyphs stay readable in grayscale — fire intensity is carried by the glyph
 * itself (`1..4` flame levels), never by colour alone, which is what the design
 * doc requires for colour-vision accessibility. Entity precedence over a cell's
 * terrain glyph: rover, civilian, water packet, fire level, terrain.
 */
object TextGrid {
    const val ROVER_GLYPH = 'R'
    const val CIVILIAN_GLYPH = 'C'
    const val PACKET_GLYPH = '*'

    /** Fire glyph: 1 = 1..24, 2 = 25..49, 3 = 50..74, 4 = 75..100. */
    fun fireGlyph(intensity: Int): Char? = when {
        intensity <= 0 -> null
        intensity < 25 -> '1'
        intensity < 50 -> '2'
        intensity < 75 -> '3'
        else -> '4'
    }

    fun glyphAt(world: FirelineWorld, x: Int, y: Int): Char {
        if (world.rover.x == x && world.rover.y == y) return ROVER_GLYPH
        if (world.civilians.any { it.onGrid && it.x == x && it.y == y }) return CIVILIAN_GLYPH
        if (world.hasWaterPacketAt(x, y)) return PACKET_GLYPH
        return fireGlyph(world.fireAt(x, y)) ?: terrainGlyph(world.terrainAt(x, y))
    }

    fun render(world: FirelineWorld): String = buildString {
        append(header(world)).append('\n')
        if (world.layout.hasEdgeWalls()) {
            append(renderWithEdges(world)).append('\n')
            append(footer(world))
            return@buildString
        }
        append(' ')
        for (x in 0 until GRID) append(if (x % 5 == 0) '+' else ' ')   // column ruler (never a fire glyph)
        append('\n')
        for (y in 0 until GRID) {
            append(if (y % 5 == 0) (y / 5).toString() else ' ')
            for (x in 0 until GRID) append(glyphAt(world, x, y))
            append('\n')
        }
        append(footer(world))
    }.trimEnd()

    /** Arena-style 41x41 debug view: cells occupy odd coordinates, edges the gutters. */
    private fun renderWithEdges(world: FirelineWorld): String {
        val size = GRID * 2 + 1
        val picture = Array(size) { CharArray(size) { ' ' } }
        for (y in 0 until GRID) for (x in 0 until GRID) {
            picture[y * 2 + 1][x * 2 + 1] = glyphAt(world, x, y)
            if (world.layout.verticalEdgeAt(x, y)) picture[y * 2 + 1][x * 2 + 2] = '|'
            if (world.layout.horizontalEdgeAt(x, y)) picture[y * 2 + 2][x * 2 + 1] = '-'
        }
        return picture.joinToString("\n") { it.concatToString() }
    }

    fun header(world: FirelineWorld): String {
        val status = when {
            world.status == RunStatus.SUCCESS -> "SUCCESS"
            world.status == RunStatus.FAILURE -> "FAILURE ${world.failureReason?.name ?: "?"}"
            else -> "RUNNING"
        }
        return "FIRELINE v$FIRELINE_ENGINE_VERSION mission=${world.descriptor.missionId} " +
            "seed=${world.descriptor.seed} tick=${world.tick} $status"
    }

    private fun footer(world: FirelineWorld): String = buildString {
        val r = world.rover
        append("rover ${r.x},${r.y} dir=${dirName(r.facing)} hp=${r.hp} heat=${r.heat} water=${r.water}")
        if (r.shieldTicksRemaining > 0) append(" shield=${r.shieldTicksRemaining}")
        append("  fire cells=${world.fireCellsBurning()} intensity=${world.fireIntensitySum()}")
        append("  civ waiting=${world.waitingCount()} rescued=${world.rescuedCount()} lost=${world.lostCount()}")
        val lastOp = world.lastOp
        if (lastOp.isNotBlank()) append("\nlast: $lastOp")
        if (world.lastEvents.isNotEmpty()) append("\nevents: ${world.lastEvents.joinToString(" | ")}")
    }

    fun dirName(dir: Int): String = when (dir and 3) {
        DIR_W -> "W"; DIR_N -> "N"; DIR_E -> "E"; else -> "S"
    }
}
