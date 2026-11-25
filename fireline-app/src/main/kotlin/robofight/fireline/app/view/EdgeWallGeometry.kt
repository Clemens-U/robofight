package robofight.fireline.app.view

import robofight.fireline.GRID
import robofight.fireline.HORIZ_EDGE_SLOTS
import robofight.fireline.VERT_EDGE_COLUMNS
import robofight.fireline.VERT_EDGE_SLOTS

/**
 * Thin edge wall geometry for the FIRELINE grid (PLAN-EDGE-WALLS.md step E3,
 * DESIGN-FIRELINE.md amendment A-6).
 *
 * The model is the shipped Arena's, not an invention. A thin wall is a line
 * segment on the border BETWEEN two 4-adjacent cells, never a filled cell
 * (`engine/src/main/kotlin/robofight/world/Arena.kt:15-18`, drawn at
 * `app/src/main/kotlin/robofight/android/ArenaView.kt:323-336`), and the
 * segment anchors here are the engine's own edge slots (`EdgeWalls.kt:59-63`):
 * a VERTICAL wall is the edge to the RIGHT of cell (col, row) and a HORIZONTAL
 * wall is the edge BELOW cell (col, row).
 *
 * Input is the pair of renderer copies `FirelineWorld.wallVertRowMajor()` and
 * `wallHorizRowMajor()` (`fireline-engine/src/commonMain/kotlin/robofight/
 * fireline/FirelineWorld.kt:124-125`). Nothing here touches terrain: PLAN-EDGE-
 * WALLS.md §4 forbids a view inferring an edge from a cell, and a file that
 * never reads a cell cannot break that rule.
 *
 * Pure lattice arithmetic, no Canvas and no Android types, so the exact mapping
 * the view draws with is unit-testable (`EdgeWallGeometryTest`).
 */

/** Which shared border a thin wall sits on (`Arena.kt:39-46`). */
enum class WallEdgeOrientation { VERTICAL, HORIZONTAL }

/**
 * One thin wall segment in lattice coordinates: the anchor cell plus the kind
 * of border it shares with its neighbour. `col`/`row` are legal edge-slot keys
 * (`vertEdgeInBounds` / `horizEdgeInBounds`), not cell guesses.
 */
data class WallEdgeSegment(val orientation: WallEdgeOrientation, val col: Int, val row: Int)

/** One segment resolved to canvas pixels for one cell size. */
data class WallEdgeLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

object EdgeWallGeometry {

    /** Arena stroke recipe: `cellSize * 0.14f` (`ArenaView.kt:323`). */
    const val STROKE_FACTOR: Float = 0.14f

    /** ... floored at 2 px so a thin wall survives a small cell (`ArenaView.kt:323`). */
    const val STROKE_FLOOR_PX: Float = 2f

    fun strokeWidthPx(cellPx: Float): Float = (cellPx * STROKE_FACTOR).coerceAtLeast(STROKE_FLOOR_PX)

    /**
     * Map the two row-major renderer copies to the segments to draw, in lattice
     * order (vertical edges row by row, then horizontal edges row by row). The
     * result size is the engine's own `Layout.edgeCount()` for the same layout,
     * which the test pins per mission.
     */
    fun segmentsOf(vertRowMajor: BooleanArray, horizRowMajor: BooleanArray): List<WallEdgeSegment> {
        require(vertRowMajor.size == VERT_EDGE_SLOTS) {
            "WALL_VERT renderer copy has ${vertRowMajor.size} slots, expected $VERT_EDGE_SLOTS"
        }
        require(horizRowMajor.size == HORIZ_EDGE_SLOTS) {
            "WALL_HORIZ renderer copy has ${horizRowMajor.size} slots, expected $HORIZ_EDGE_SLOTS"
        }
        val out = ArrayList<WallEdgeSegment>()
        for (row in 0 until GRID) {
            for (col in 0 until VERT_EDGE_COLUMNS) {
                if (vertRowMajor[row * VERT_EDGE_COLUMNS + col]) {
                    out.add(WallEdgeSegment(WallEdgeOrientation.VERTICAL, col, row))
                }
            }
        }
        for (row in 0 until GRID - 1) {
            for (col in 0 until GRID) {
                if (horizRowMajor[row * GRID + col]) {
                    out.add(WallEdgeSegment(WallEdgeOrientation.HORIZONTAL, col, row))
                }
            }
        }
        return out
    }

    /**
     * Canvas endpoints of one segment: centered on the lattice border the two
     * cells share (the border coordinate exactly, so the stroke straddles it
     * half into each cell) and running from one lattice intersection to the
     * next, the same placement `ArenaView.kt:325-336` uses. The two cell
     * interiors stay open, which is what makes a wall line read as something
     * other than a wall cell.
     */
    fun lineOf(segment: WallEdgeSegment, cellPx: Float): WallEdgeLine = when (segment.orientation) {
        WallEdgeOrientation.VERTICAL -> {
            val x = (segment.col + 1) * cellPx
            WallEdgeLine(x, segment.row * cellPx, x, (segment.row + 1) * cellPx)
        }
        WallEdgeOrientation.HORIZONTAL -> {
            val y = (segment.row + 1) * cellPx
            WallEdgeLine(segment.col * cellPx, y, (segment.col + 1) * cellPx, y)
        }
    }
}
