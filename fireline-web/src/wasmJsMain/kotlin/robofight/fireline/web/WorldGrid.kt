package robofight.fireline.web

import robofight.fireline.GRID
import robofight.fireline.FirelineWorld
import robofight.fireline.TERRAIN_EXIT
import robofight.fireline.TERRAIN_FUEL
import robofight.fireline.TERRAIN_PROTECTED
import robofight.fireline.TERRAIN_REFILL
import robofight.fireline.TERRAIN_WALL
import robofight.fireline.TextGrid
import robofight.fireline.VERT_EDGE_COLUMNS
import robofight.fireline.WALL_HORIZ_ROWS
import robofight.fireline.WALL_VERT_ROWS
import robofight.isa.decPad

/**
 * One thin edge wall of a mission layout, as the engine's own `Layout` reports it
 * (`Layout.wallVertRowMajor()` / `wallHorizRowMajor()`, the renderer copies of
 * `PLAN-EDGE-WALLS.md` section 4). `vertical == true` is the edge to the RIGHT of cell
 * `(x, y)`; `vertical == false` is the edge BELOW `(x, y)`. Nothing here invents a
 * coordinate system: the same two arrays are what `docs/web/fixtures/SnapshotFixtures.kt`
 * records as `WALL_VERT` / `WALL_HORIZ` and what the Android grid strokes in step E3.
 */
data class EdgeWallSpec(val vertical: Boolean, val x: Int, val y: Int) {
    /** `v 5,2` / `h 2,1`, the key the browser overlay and the fixture checks both address. */
    val key: String get() = (if (vertical) "v " else "h ") + x + "," + y
}

/**
 * The 20x20 world grid and the symbol legend of the FIRELINE WEB CONSOLE
 * (`docs/FIRELINE-WEB.md` §15.2 item 2: the world column is `WIND`, the grid,
 * the legend, `EVENT LOG`; §15.4 puts the legend once, under the grid).
 *
 * One glyph authority, exactly as §13 demands of the two UIs: every visible
 * symbol comes from the engine's own `TextGrid` (`glyphAt` applies the layer
 * order rover > civilian > packet > fire > terrain, `fireGlyph` maps the live
 * intensity to the 1/2/3/4 display bands), and the terrain/fire reads behind the
 * colour classes are `FirelineWorld.terrainAt` / `fireAt`. This mirrors what the
 * shipped Android grid does in `FirelineGridView.drawCell` (`FirelineGridView.kt:128-205`,
 * same reads, same
 * branch order: rover, civilian, packet, fire level, then terrain), so browser
 * and app describe the same cell with the same symbol. Nothing here re-derives a
 * glyph, a layer precedence or an intensity band.
 *
 * Markup is copied from the frozen executable source of the layout,
 * `docs/web/gen_web_mockups.py` `grid_html()` / `LEGEND` (whose output is the
 * golden master `docs/web/fl-web-5-playable-landing.html`): the `corner` +
 * `ruler` label cells, `<span class="cell CLASS" STYLE>GLYPH</span>` cells, the
 * rover's `<i class="nose">caret</i>`, the idle-floor `font-size:8px;opacity:.75`
 * style, the burning-cell `font-weight:bold` style, and the legend items verbatim
 * (same class names, same wording, same HTML entities). `docs/web/verify_layout_v1.py`
 * counts `class="cell "` occurrences == 400 in the world column and checks the
 * wind/grid/legend/event-log order; `WorldGridTest` re-asserts both gates against
 * this renderer's own output.
 *
 * Geometry (§15.3 "21 rows x 20 columns of 15px cells (grid height 317px)" and
 * §15.5 "grid cells 11px, pane height `21 * 11 + 1`"): the pixel metrics live in
 * the frozen chrome CSS that `fireline-web/console.html` carries verbatim from
 * the golden master (`.grid{grid-template-columns:30px repeat(20,15px);
 * grid-auto-rows:15px}` plus its `max-width:900px` media query), so this object
 * states the same rules as arithmetic over `GRID` instead of magic numbers, and
 * `WorldGridTest` locks them to the §15.3 / §15.5 table values. The 21st row is
 * the ruler row `grid_html()` emits.
 */
object WorldGrid {

    /** 20x20 cells = 400 cell nodes (the §15.6 gate `verify_layout_v1.py` counts). */
    const val CELL_NODES: Int = GRID * GRID

    /** 21 grid rows: the `GRID` cell rows plus the one ruler row of `grid_html()`. */
    const val GRID_ROW_COUNT: Int = GRID + 1

    /** §15.3 desktop cell edge; §15.5 `<=900px` cell edge. */
    const val DESKTOP_CELL_PX: Int = 15
    const val NARROW_CELL_PX: Int = 11

    /** Grid tracks (W3 record 16, §15.3 `30px + 20x15px`): the label column is exactly
     *  2 cell tracks wide, so the grid is 2 + 20 = 22 cell tracks plus its 2px border. */
    const val LABEL_TRACK_CELLS: Int = 2
    const val GRID_TRACK_CELLS: Int = LABEL_TRACK_CELLS + GRID

    /** Widest cell the scaling may produce (34px == 2x the §15.3 desktop cell; the §15.5
     *  narrow floor is `NARROW_CELL_PX`). The scale never goes below that floor either. */
    const val CELL_MAX_PX: Int = 34

    /** The scaling rule: the grid must fit INSIDE its column at 22 cell tracks plus the
     *  2px border, so the cell edge is (column - 2) / 22, clamped to [11, 34]. Rounding
     *  is downward on purpose: a cell never rounds up out of the column. */
    fun cellSizePx(worldColumnPx: Int): Int {
        val fit = (worldColumnPx - 2) / GRID_TRACK_CELLS
        return fit.coerceIn(NARROW_CELL_PX, CELL_MAX_PX)
    }

    /**
     * E4 (15.8): the edge wall stroke, the Android recipe of `PLAN-EDGE-WALLS.md` §5 rounded to a
     * whole EVEN pixel count. `FirelineGridView` strokes `cell * 0.14` floored at 2px; the web
     * grid keeps that ratio and rounds it in two integer steps so no browser has to round a
     * fraction: half-up to a whole pixel (`(cell * 14 + 50) / 100`), then the nearest multiple of
     * 2 with a tie rounding up. The line straddles its cell border symmetrically instead of
     * half-covering the cell on one side: 2px up to a 17px cell (the 11px narrow floor, the
     * frozen 15px desktop cell) and 4px from an 18px cell on (the measured 21/24/27px cells, up to
     * the 34px desktop ceiling), clamped to
     * [WALL_STROKE_MIN_PX, WALL_STROKE_MAX_PX]. The `pointer: coarse` gate in
     * `fireline-web/console.html` raises the painted floor to 4px the way §15.4 and 15.2b raise
     * the touch targets back to 49dp.
     */
    const val WALL_STROKE_MIN_PX: Int = 2
    const val WALL_STROKE_MAX_PX: Int = 4

    fun wallStrokePx(cellPx: Int): Int {
        val ratioPx = (cellPx * 14 + 50) / 100
        val nearestEven = ((ratioPx + 1) / 2) * 2
        return nearestEven.coerceIn(WALL_STROKE_MIN_PX, WALL_STROKE_MAX_PX)
    }

    /**
     * E4 (15.8): the four cell-border anchors inside the grid's padding box, as arithmetic over the
     * measured cell (the same token `cellStyleAttr` writes). The 400 cell nodes fill the padding box
     * exactly (`x` from 0 to `GRID * cell`, `y` from 0 to `GRID * cell`); the ruler labels are
     * absolute children parked in the 2px padding margins, which is why the box is
     * `GRID_TRACK_CELLS * cell + 2` wide while a CONTENT column is `x * cell` (browser measurement,
     * `docs/EDGE-E4-RECORD.md` section 7: the rover at engine x 17 sits at padding left `1 + 17 *
     * cell`). Row 0 of the grid is the
     * ruler row, so cell row `y` starts one cell below the top edge. A wall is painted ON one of
     * these four borders and never inside a cell. `LABEL_TRACK_CELLS` belongs to the box arithmetic
     * in [cellSizePx], not to a content anchor; adding it here shifts every wall two cells right.
     */
    fun cellLeftBorderPx(x: Int, cellPx: Int): Int = x * cellPx
    fun cellRightBorderPx(x: Int, cellPx: Int): Int = cellLeftBorderPx(x + 1, cellPx)
    fun cellTopBorderPx(y: Int, cellPx: Int): Int = (y + 1) * cellPx
    fun cellBottomBorderPx(y: Int, cellPx: Int): Int = cellTopBorderPx(y + 1, cellPx)

    /**
     * E4 (15.8): the edge walls of the world's layout, straight from the engine's renderer copies
     * (`Layout.wallVertRowMajor()`, 20 rows x 19 columns; `Layout.wallHorizRowMajor()`, 19 rows x 20
     * columns), read in the same row-major order the fixture records (§11.4). Vertical first, then
     * horizontal, each row low to high: a mission with no walls returns an empty list and the grid
     * markup is byte-identical to the wall-free build.
     */
    fun edgeWalls(world: FirelineWorld): List<EdgeWallSpec> {
        val layout = world.layout
        val out = ArrayList<EdgeWallSpec>()
        val vert = layout.wallVertRowMajor()
        for (y in 0 until WALL_VERT_ROWS) {
            for (x in 0 until VERT_EDGE_COLUMNS) {
                if (vert[y * VERT_EDGE_COLUMNS + x]) out.add(EdgeWallSpec(true, x, y))
            }
        }
        val horiz = layout.wallHorizRowMajor()
        for (y in 0 until WALL_HORIZ_ROWS) {
            for (x in 0 until GRID) {
                if (horiz[y * GRID + x]) out.add(EdgeWallSpec(false, x, y))
            }
        }
        return out
    }

    /**
     * `cellStyleAttr(worldColumnPx)`: the inline `style` written on the PAIR element, cell edge in
     * raw px from the MEASURED column (W3 record 16). It carries `--cell`, `--grid-h` and (E4, 15.8)
     * `--wall-stroke`: the `.grid` CSS rule reads the cell token, the pair's `--pane-h` derives from
     * `--grid-h`, and the `.wallseg` overlay rules read both the cell token and the stroke token, so
     * grid tracks, grid box, rows, firmware pane height and the edge overlay all come from one
     * measured number. 15.9 (record 19) added a glyph scale token to this list and measured it away
     * again: the block is filled by `.cell.wall`'s own background, which needs no number
     * (`WALL_BLOCK_FILL_NOTE`).
     *
     * Label geometry measured in Chrome: the `y/x` corner and the row rulers are not grid tracks
     * (the renderer lays them out inside the label area), so `repeat(2, c) repeat(20, c)` computes
     * as 21 tracks, `2c + 20 * c`, in 21 rows of `c`, total `22c + 2` x `21c + 2` with the border.
     *
     * Why not a CSS expression: Chrome 142 re-resolves `var()`, `%`, `vw` and `cqw` inside a grid's
     * own track/box sizing against the grid's own box rather than the column it sits in. Measured at
     * viewport 1370: `--cell` computed 305.6px on the pair but 28.281px inside `.grid`'s tracks, and
     * `--cw` computed 613.422px on `.pairworld` but 626.4px inside `.grid`, i.e. a 748px track sum
     * inside a 611px box, 136px of cells drawn across the column edge into the firmware column
     * (the owner's screenshot). A plain token substitution has no box to re-resolve against.
     */
    fun cellStyleAttr(worldColumnPx: Int): String {
        val cell = cellSizePx(worldColumnPx)
        return "style=\"--cell:${cell}px;--grid-h:${GRID_ROW_COUNT * cell + 2}px;" +
            "--wall-stroke:${wallStrokePx(cell)}px;\""
    }

    /** §15.3 grid height lock, written as the rule: 21 rows x 15px + 1px border
     *  top and bottom = 317px (the Chrome measurement of this build agrees). */
    const val DESKTOP_GRID_HEIGHT_PX: Int = GRID_ROW_COUNT * DESKTOP_CELL_PX + 2

    /** §15.5 narrow variant of the same rule: 21 rows x 11px + 2px border. */
    const val NARROW_GRID_HEIGHT_PX: Int = GRID_ROW_COUNT * NARROW_CELL_PX + 2

    /** §15.3 firmware pane lock `21 * 15 + 1` = 316px (`ConsoleRender` writes it
     *  inline as `--src-height`); §15.5 narrow `21 * 11 + 1` = 232px (the chrome
     *  media query `calc(21 * 11px + 1px)` applies it at `<=900px`). Both are the
     *  same grid-row count times the cell edge, which is what "flush pairing"
     *  means: the grid and the pane stop at the same row count. */
    const val DESKTOP_PANE_HEIGHT_PX: Int = GRID_ROW_COUNT * DESKTOP_CELL_PX + 1
    const val NARROW_PANE_HEIGHT_PX: Int = GRID_ROW_COUNT * NARROW_CELL_PX + 1

    /** Styles `grid_html()` puts on the two cell kinds that need one. */
    const val IDLE_CELL_STYLE: String = "font-size:8px;opacity:.75"
    const val FIRE_CELL_STYLE: String = "font-weight:bold"

    /**
     * 15.9 (W3 record 19): the glyph a `TERRAIN_WALL` cell is DRAWN with. The engine's terrain
     *  glyph stays `TextGrid`'s `GLYPH_WALL` '#' (layout text, snapshot fixtures, the Android grid);
     *  the web grid paints the full block U+2588 so a wall cell reads as the solid block the legend's
     *  wall swatch already promises (`<span class="wall">&#x2588;</span>`) and as the Android cell
     *  paints it. One constant, used by the render layer, asserted against the legend item by
     *  `WorldGridTest` and against the live page by the browser measurement.
     */
    const val WALL_BLOCK_GLYPH: String = "\u2588"

    /**
     * 15.9 (W3 record 19): why the wall block is filled by CSS and not by a font size or a scale.
     *  Measured in Chrome with canvas metrics for `WALL_BLOCK_GLYPH` (U+2588) in the shell's font
     *  stack: the horizontal advance is 0.600 em at every size (14.40px at 24px, 15.60px at 26px),
     *  and the ink box covers only 81% of its own em (at 26px: ascent 22, descent 8, ink spanning
     *  4px..25px inside a 26px em). So a glyph can never reach a cell's edges: the old 14px block
     *  left 11.6px of column gaps between neighbouring wall cells.
     *
     *  The fill is therefore the cell's own paint, in `console.html`: `.cell.wall` backgrounds
     *  `var(--wall)` (the token the edge wall strokes use) across its whole border box, so two
     *  adjacent wall cells share their 1px border and a wall run reads as one continuous block;
     *  `WALL_BLOCK_GLYPH` stays in the DOM as the cell's text, hidden at 1px transparent, so the grid
     *  still renders exactly 400 text cells (§15.6). Measured live on mission 02 at --cell 26: wall
     *  cells 26x26 filled with `#8a6d5e`, and the corridor band is one contiguous run of 17 wall cells
     *  measuring 442.0x26.0 with every interior gap 0.00px (`WorldGridTest`, `verify_layout_v1.py` 15.9,
     *  `docs/WEB-W3-RECORD.md` 19.4).
     */
    const val WALL_BLOCK_FILL_NOTE: String = "cell background fills the block; the glyph marks the terrain"

    /** `grid_html()`: the whole grid, in row-major order (row 0 first, x 0..19).
     *  Cell node `y * GRID + x` is therefore the cell at coordinate (x, y), which
     *  is how `WorldGridTest` and the browser check address one cell.
     *
     *  E4 (15.8): the grid closes with the thin edge wall overlay of the world's layout
     *  (`wallSegmentsHtml`), after all 400 cells and before `</div>`: a wall-free mission (every
     *  mission but 06 today) emits an empty string, so its markup stays byte-identical.
     *  `worldColumnPx` is the measured column `Main.kt` relayout passes; a caller that has not
     *  measured passes nothing and gets the §15.5 floor cell. */
    fun gridHtml(world: FirelineWorld, worldColumnPx: Int = 0): String {
        val sb = StringBuilder("<div class=\"grid\">")
        sb.append("<span class=\"corner\"></span>")
        for (x in 0 until GRID) sb.append("<span class=\"ruler\">").append(decPad(x, 2)).append("</span>")
        val nose = noseCaret(TextGrid.dirName(world.rover.facing))
        for (y in 0 until GRID) {
            sb.append("<span class=\"ruler\">").append(decPad(y, 2)).append("</span>")
            for (x in 0 until GRID) sb.append(cellHtml(world, x, y, nose))
        }
        sb.append(wallSegmentsHtml(world, cellSizePx(worldColumnPx)))
        return sb.append("</div>").toString()
    }

    /**
     * E4 (15.8): the edge overlay markup, one absolutely positioned
     * `<span class="wallseg v|h" data-edge="v x,y">` per blocked edge of the layout. The inline
     * `left` / `top` are raw px anchors ON the cell border (never inside a cell), derived from the
     * same measured cell edge `cellStyleAttr` writes as `--cell`; the segment's length and stroke
     * are the CSS `.wallseg` rules in `fireline-web/console.html`, which read `--cell` (one cell
     * long, so a wall covers exactly one edge) and `--wall-stroke`. Nothing here changes a cell
     * node, the 400-cell gate or the box arithmetic `22 * --cell + 2` x `21 * --cell + 2`.
     */
    fun wallSegmentsHtml(world: FirelineWorld, cellPx: Int): String {
        val sb = StringBuilder()
        for (w in edgeWalls(world)) {
            val kind = if (w.vertical) "v" else "h"
            val left = if (w.vertical) cellRightBorderPx(w.x, cellPx) else cellLeftBorderPx(w.x, cellPx)
            val top = if (w.vertical) cellTopBorderPx(w.y, cellPx) else cellBottomBorderPx(w.y, cellPx)
            sb.append("<span class=\"wallseg ").append(kind)
                .append("\" data-edge=\"").append(w.key)
                .append("\" style=\"left:").append(left).append("px;top:").append(top).append("px\"></span>")
        }
        return sb.toString()
    }

    /** The symbol legend: the 10 items of `LEGEND` in `gen_web_mockups.py` byte-identical, plus the
     *  dated E4 thin-wall item of 15.8 (`PLAN-EDGE-WALLS.md` §5). Single occurrence rule §15.4: it
     *  lives here, under the grid, nowhere else. */
    fun legendHtml(): String = LEGEND_HTML

    /**
     * Class of one cell - the palette token key the frozen chrome CSS paints.
     * The class follows the glyph the engine produced, so the colour can never
     * disagree with the symbol (`FirelineGridView.drawCell` branches the same way).
     */
    fun cellClassFor(world: FirelineWorld, x: Int, y: Int): String {
        val glyph = TextGrid.glyphAt(world, x, y)
        val fireGlyph = TextGrid.fireGlyph(world.fireAt(x, y))
        return when {
            glyph == TextGrid.ROVER_GLYPH -> "rover"
            glyph == TextGrid.CIVILIAN_GLYPH -> "civ"
            glyph == TextGrid.PACKET_GLYPH -> "packet"
            fireGlyph != null && glyph == fireGlyph -> "fire" + fireGlyph
            else -> terrainClass(world.terrainAt(x, y))
        }
    }

    /** Terrain id -> the mockup's cell class (`glyph_for`'s terrain table). */
    private fun terrainClass(terrain: Int): String = when (terrain) {
        TERRAIN_WALL -> "wall"
        TERRAIN_FUEL -> "fuel"
        TERRAIN_REFILL -> "refill"
        TERRAIN_EXIT -> "exit"
        TERRAIN_PROTECTED -> "prot"
        else -> "empty"
    }

    private fun cellHtml(world: FirelineWorld, x: Int, y: Int, nose: String): String {
        val cssClass = cellClassFor(world, x, y)
        // 15.9 (W3 record 19): TERRAIN_WALL renders `WALL_BLOCK_GLYPH`, wrapped in an element so the
        // shell can stretch the block to the cell (a bare text node has no box to transform). Every
        // other cell keeps the engine glyph exactly as `TextGrid.glyphAt` produced it.
        val glyph = when (cssClass) {
            "wall" -> "<span>" + ConsoleRender.escapeHtml(WALL_BLOCK_GLYPH) + "</span>"
            else -> ConsoleRender.escapeHtml(TextGrid.glyphAt(world, x, y).toString())
        }
        val style = when {
            cssClass == "empty" -> " style=\"$IDLE_CELL_STYLE\""
            cssClass.startsWith("fire") -> " style=\"$FIRE_CELL_STYLE\""
            else -> ""
        }
        val dirClass = noseDirectionClass(TextGrid.dirName(world.rover.facing))
        val noseMarkup = if (cssClass == "rover") "<i class=\"nose $dirClass\">$nose</i>" else ""
        return "<span class=\"cell $cssClass\"$style>$glyph$noseMarkup</span>"
    }

    /**
     * 15.9 (W3 record 19): the facing direction named on the caret element, derived from the
     *  engine's own letter (`TextGrid.dirName`), so the shell can anchor the caret on the cell edge
     *  it points at instead of leaving it at the glyph's static position. `nose-<letter>`; an
     *  unknown letter yields the bare `nose` class and the caret renders unanchored rather than in
     *  the wrong place.
     */
    fun noseDirectionClass(dirName: String): String =
        if (dirName.length == 1 && dirName[0].lowercase() in "eswn") "nose-" + dirName.lowercase() else ""

    /**
     * Facing caret inside the rover cell: the four glyphs `grid_html()`'s
     * `dir_caret` table uses (U+25B8 U+25BE U+25C2 U+25B4), keyed by the
     * engine's own `TextGrid.dirName` letters. Public so `WorldGridTest` can
     * assert that the drawn caret follows `TelemetrySnapshot.roverFacing`.
     */
    fun noseCaret(dirName: String): String = when (dirName) {
        "E" -> "▸"
        "S" -> "▾"
        "W" -> "◂"
        "N" -> "▴"
        else -> ""
    }

    private const val LEGEND_HTML = """<div class="legend">
<b><span class="wall">&#x2588;</span> wall</b>
<b><span class="edgebar"></span> thin wall, blocks the way</b>
<b><span class="rover">R</span><span class="nose-txt">&#x25b8;</span> rover + facing</b>
<b><span class="fire1">1</span><span class="fire2">2</span><span class="fire3">3</span><span class="fire4">4</span> fire, engine display bands 1..24 / 25..49 / 50..74 / 75..100</b>
<b><span class="packet">*</span> water packet</b>
<b><span class="refill">W</span> refill</b>
<b><span class="exit">E</span> exit</b>
<b><span class="civ">C</span> civilian</b>
<b><span class="fuel">F</span> fuel</b>
<b><span class="prot">P</span> protected asset</b>
<b>layer order: rover &gt; civilian &gt; packet &gt; fire &gt; terrain</b>
</div>"""
}
