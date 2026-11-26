package robofight.fireline.app.view

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.HORIZ_EDGE_SLOTS
import robofight.fireline.Layout
import robofight.fireline.Missions
import robofight.fireline.GRID
import robofight.fireline.TERRAIN_FLOOR
import robofight.fireline.TERRAIN_WALL
import robofight.fireline.VERT_EDGE_SLOTS

/**
 * The Android thin-wall renderer mapping (PLAN-EDGE-WALLS.md step E3,
 * DESIGN-FIRELINE.md amendment A-6). These tests pin the exact arithmetic the
 * grid strokes with, on the mission that is named after its wall (06 THIN WALL,
 * `fireline-engine/src/commonMain/kotlin/robofight/fireline/Missions.kt:363`).
 *
 * The acceptance claim they exist to prove: a thin wall reaches the canvas as
 * line segments that come only from the engine's edge arrays, never as a filled
 * cell and never as a glyph derived from a cell.
 */
class EdgeWallGeometryTest {

    private val cell = 20f

    @Test
    fun mission06MapsToExactlyTheThreeEdgeSlotsTheEngineCarries() {
        val layout = Missions.byId(6)!!.layout
        assertEquals(3, layout.edgeCount())
        val segments = EdgeWallGeometry.segmentsOf(layout.wallVertRowMajor(), layout.wallHorizRowMajor())
        assertEquals(
            listOf(
                // WALL_VERT row 2 char 5: the lane gate, the edge right of (5,2).
                WallEdgeSegment(WallEdgeOrientation.VERTICAL, 5, 2),
                // WALL_HORIZ rows 1 and 2 char 2: the spawn cell's north and south edges.
                WallEdgeSegment(WallEdgeOrientation.HORIZONTAL, 2, 1),
                WallEdgeSegment(WallEdgeOrientation.HORIZONTAL, 2, 2),
            ),
            segments,
        )
    }

    @Test
    fun mission06HasNoWallCellSoItsWallsCanOnlyComeFromTheEdgeArrays() {
        val layout = Missions.byId(6)!!.layout
        assertEquals(0, layout.terrainRowMajor().count { it == TERRAIN_WALL })
        assertTrue(layout.wallsPresent())
        // The gate cells on both sides of the thin wall are ordinary floor cells.
        assertEquals(TERRAIN_FLOOR, layout.terrainAt(5, 2))
        assertEquals(TERRAIN_FLOOR, layout.terrainAt(6, 2))
        assertTrue(EdgeWallGeometry.segmentsOf(layout.wallVertRowMajor(), layout.wallHorizRowMajor()).isNotEmpty())
    }

    @Test
    fun everySegmentSitsOnTheBorderItsTwoCellsShare() {
        val layout = Missions.byId(6)!!.layout
        val segments = EdgeWallGeometry.segmentsOf(layout.wallVertRowMajor(), layout.wallHorizRowMajor())
        for (segment in segments) {
            val line = EdgeWallGeometry.lineOf(segment, cell)
            // Endpoints land exactly on lattice intersections, never inside a cell.
            assertEquals(0f, line.x1 % cell)
            assertEquals(0f, line.y1 % cell)
            assertEquals(0f, line.x2 % cell)
            assertEquals(0f, line.y2 % cell)
            when (segment.orientation) {
                WallEdgeOrientation.VERTICAL -> {
                    // The border coordinate between column `col` and `col + 1`.
                    assertEquals((segment.col + 1) * cell, line.x1)
                    assertEquals(line.x1, line.x2)
                    assertEquals(segment.row * cell, line.y1)
                    assertEquals(segment.row * cell + cell, line.y2)
                }
                WallEdgeOrientation.HORIZONTAL -> {
                    assertEquals((segment.row + 1) * cell, line.y1)
                    assertEquals(line.y1, line.y2)
                    assertEquals(segment.col * cell, line.x1)
                    assertEquals(segment.col * cell + cell, line.x2)
                }
            }
        }
        // Concrete gate wall: x = 6 * 20 = 120, y from 2 * 20 to 3 * 20, i.e. the
        // right border of cell (5,2) and the left border of cell (6,2).
        val gate = EdgeWallGeometry.lineOf(WallEdgeSegment(WallEdgeOrientation.VERTICAL, 5, 2), cell)
        assertEquals(120f, gate.x1)
        assertEquals(120f, gate.x2)
        assertEquals(40f, gate.y1)
        assertEquals(60f, gate.y2)
    }

    @Test
    fun aSegmentIsOneCellSideLongAndNeverFillsACell() {
        val layout = Missions.byId(6)!!.layout
        for (segment in EdgeWallGeometry.segmentsOf(layout.wallVertRowMajor(), layout.wallHorizRowMajor())) {
            val line = EdgeWallGeometry.lineOf(segment, cell)
            val length = if (line.x1 == line.x2) line.y2 - line.y1 else line.x2 - line.x1
            assertEquals(cell, length)
            // The stroke is a fraction of a cell, so the two cell interiors it
            // separates stay open: a line reads as a wall, not as a wall cell.
            val stroke = EdgeWallGeometry.strokeWidthPx(cell)
            assertTrue(stroke < cell / 2f)
        }
    }

    @Test
    fun strokeWidthFollowsTheArenaRecipeAndFloorsAtTwoPixels() {
        assertEquals(14f, EdgeWallGeometry.strokeWidthPx(100f), 1e-4f)
        assertEquals(2.8f, EdgeWallGeometry.strokeWidthPx(20f), 1e-4f)
        assertEquals(EdgeWallGeometry.STROKE_FLOOR_PX, EdgeWallGeometry.strokeWidthPx(10f), 1e-4f)
        assertEquals(2f, EdgeWallGeometry.strokeWidthPx(0f), 1e-4f)
    }

    /**
     * Since the mission-content adoption (PLAN-RECONCILE-MAIN-01.md §10 step 5)
     * every authored mission carries thin edges: main's 2, 25, 11, 18 and 40
     * for missions 01-05 plus mission 06's 3. The renderer must show each of
     * them as edge segments straight from the engine arrays - and the half that
     * proves the renderer never invents walls still holds: the segment sets are
     * exactly the engine edge sets, cell for cell.
     */
    @Test
    fun adoptedMissionsDrawExactlyTheirEngineEdgeSegments() {
        val adoptedEdges = mapOf(1 to 2, 2 to 25, 3 to 11, 4 to 18, 5 to 40, 6 to 3)
        for (mission in Missions.ALL) {
            val segments = EdgeWallGeometry.segmentsOf(
                mission.layout.wallVertRowMajor(),
                mission.layout.wallHorizRowMajor(),
            )
            val expected = adoptedEdges.getValue(mission.id)
            assertEquals(expected, segments.size, "mission ${mission.id} drew ${segments.size} segments, expected $expected")
            assertTrue(mission.layout.wallsPresent(), "mission ${mission.id} carries thin edges")
        }
        // Mission 01's two edges concretely: WALL_HORIZ rows 1 and 2 char 5, the
        // fire-lane seal south of (5,1) and (5,2); its only vertical column is open.
        val m1 = Missions.byId(1)!!.layout
        assertEquals(
            listOf(
                WallEdgeSegment(WallEdgeOrientation.HORIZONTAL, 5, 1),
                WallEdgeSegment(WallEdgeOrientation.HORIZONTAL, 5, 2),
            ),
            EdgeWallGeometry.segmentsOf(m1.wallVertRowMajor(), m1.wallHorizRowMajor()),
        )
    }

    @Test
    fun aFilledWallCellProducesNoSegment() {
        // PLAN-EDGE-WALLS.md 4: no view may infer an edge from a cell. A layout
        // with a real wall cell and no wall blocks must render zero segments; the
        // cell itself is filled by the view's terrain branch, not here.
        val wallCellX = 12
        val terrainRows = (0 until GRID).map { y ->
            if (y == 2) "..R" + ".".repeat(wallCellX - 3) + "#" + ".".repeat(GRID - wallCellX - 1)
            else ".".repeat(GRID)
        }
        val layout = Layout.decode(terrainRows, List(GRID) { ".".repeat(GRID) })
        assertEquals(TERRAIN_WALL, layout.terrainAt(wallCellX, 2))
        assertEquals(0, layout.edgeCount())
        assertTrue(EdgeWallGeometry.segmentsOf(layout.wallVertRowMajor(), layout.wallHorizRowMajor()).isEmpty())
    }

    @Test
    fun rendererCopiesOfTheWrongSizeAreRefused() {
        val goodVert = Missions.byId(6)!!.layout.wallVertRowMajor()
        val goodHoriz = Missions.byId(6)!!.layout.wallHorizRowMajor()
        assertEquals(VERT_EDGE_SLOTS, goodVert.size)
        assertEquals(HORIZ_EDGE_SLOTS, goodHoriz.size)
        assertThrows(IllegalArgumentException::class.java) {
            EdgeWallGeometry.segmentsOf(BooleanArray(VERT_EDGE_SLOTS - 1), goodHoriz)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EdgeWallGeometry.segmentsOf(goodVert, BooleanArray(HORIZ_EDGE_SLOTS + 1))
        }
    }

    @Test
    fun segmentCountIsTheEngineEdgeCountForEveryAuthoredMission() {
        var total = 0
        for (mission in Missions.ALL) {
            val count = EdgeWallGeometry.segmentsOf(
                mission.layout.wallVertRowMajor(),
                mission.layout.wallHorizRowMajor(),
            ).size
            assertEquals(mission.layout.edgeCount(), count, "mission ${mission.id}")
            total += count
        }
        // 2 + 25 + 11 + 18 + 40 for main's adopted missions 01-05, plus mission 06's 3.
        assertEquals(99, total)
    }
}
