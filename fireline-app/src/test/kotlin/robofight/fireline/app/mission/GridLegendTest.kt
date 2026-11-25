package robofight.fireline.app.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.app.view.EdgeWallGeometry

/**
 * The MISSION grid legend (DESIGN-FIRELINE.md 5.2) and its thin-wall entry
 * (PLAN-EDGE-WALLS.md step E3, DESIGN-FIRELINE.md amendment A-6). The glyph
 * line must stay exactly what milestone 5 shipped, and the thin wall must be
 * introduced as a drawn segment plus wording, never as a 12th glyph.
 */
class GridLegendTest {

    @Test
    fun glyphLegendLineIsUnchangedAndCarriesNoThinWallGlyph() {
        assertEquals(
            "1-4 FIRE   R ROVER   * PACKET   C CIV   E EXIT   W REFILL   F FUEL   P ASSET   # WALL   . FLOOR",
            GridLegend.GLYPHS,
        )
        // Edges are drawn, never glyphed (PLAN-EDGE-WALLS.md 3): the thin wall
        // gets no character in the glyph line.
        assertFalse(GridLegend.GLYPHS.contains("THIN"))
        assertTrue(GridLegend.GLYPHS.contains("# WALL"))
    }

    @Test
    fun thinWallEntryUsesThePlannedWording() {
        assertEquals("THIN WALL   wall, blocks the way", GridLegend.THIN_WALL_ENTRY)
        assertEquals("wall, blocks the way", GridLegend.THIN_WALL_NOTE)
        assertEquals("THIN WALL", GridLegend.THIN_WALL_LABEL)
    }

    @Test
    fun thinWallSwatchMatchesTheStrokeTheGridDraws() {
        val stroke = EdgeWallGeometry.strokeWidthPx(GridLegend.REFERENCE_CELL_DP.toFloat())
        val height = GridLegend.THIN_WALL_SWATCH_HEIGHT_DP.toFloat()
        assertTrue(height >= stroke, "swatch $height dp under the drawn stroke $stroke dp")
        assertTrue(height <= stroke + 1f, "swatch $height dp over the drawn stroke $stroke dp")
        // A segment, not a dot: longer than it is thick.
        assertTrue(GridLegend.THIN_WALL_SWATCH_WIDTH_DP > GridLegend.THIN_WALL_SWATCH_HEIGHT_DP)
    }
}
