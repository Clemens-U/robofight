package robofight.fireline.app.mission

/**
 * The grid legend of the MISSION run surface (DESIGN-FIRELINE.md 5.2), kept as
 * plain constants so the wording the app shows is assertable without an
 * Activity. MissionRunSurface renders [GLYPHS] as one dim line and
 * [thinWallEntryRow] as the thin-wall entry (PLAN-EDGE-WALLS.md step E3,
 * DESIGN-FIRELINE.md amendment A-6).
 *
 * The thin wall has no glyph on purpose: `TextGrid.glyphAt` stays the single
 * glyph authority (PLAN-EDGE-WALLS.md 3), so the legend shows the wall the way
 * the grid shows it, as a drawn segment, and never as a new symbol.
 */
object GridLegend {

    /** Glyph legend line, unchanged since milestone 5. */
    const val GLYPHS: String =
        "1-4 FIRE   R ROVER   * PACKET   C CIV   E EXIT   W REFILL   F FUEL   P ASSET   # WALL   . FLOOR"

    const val THIN_WALL_LABEL: String = "THIN WALL"

    /** PLAN-EDGE-WALLS.md 5: "a short thick segment plus 'wall, blocks the way'". */
    const val THIN_WALL_NOTE: String = "wall, blocks the way"

    const val THIN_WALL_ENTRY: String = "$THIN_WALL_LABEL   $THIN_WALL_NOTE"

    /** Width of the legend swatch, dp. */
    const val THIN_WALL_SWATCH_WIDTH_DP: Int = 24

    /**
     * Thickness of the legend swatch, dp. It is the drawn stroke width, not a
     * round number picked by taste: the reference cell is the DESIGN-FIRELINE.md
     * 3 phone cell, so the swatch is at least `cell * 0.14` (the
     * `EdgeWallGeometry` recipe) and at most 1 dp above it.
     */
    const val THIN_WALL_SWATCH_HEIGHT_DP: Int = 3

    /** DESIGN-FIRELINE.md 3: reference-phone grid cell, 19 dp. */
    const val REFERENCE_CELL_DP: Int = 19
}
