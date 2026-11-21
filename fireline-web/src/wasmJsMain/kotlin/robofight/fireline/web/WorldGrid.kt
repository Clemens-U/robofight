package robofight.fireline.web

import robofight.fireline.FirelineWorld
import robofight.fireline.GRID
import robofight.fireline.TERRAIN_EXIT
import robofight.fireline.TERRAIN_FUEL
import robofight.fireline.TERRAIN_PROTECTED
import robofight.fireline.TERRAIN_REFILL
import robofight.fireline.TERRAIN_WALL
import robofight.fireline.TextGrid
import robofight.isa.decPad

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

    /** `grid_html()`: the whole grid, in row-major order (row 0 first, x 0..19).
     *  Cell node `y * GRID + x` is therefore the cell at coordinate (x, y), which
     *  is how `WorldGridTest` and the browser check address one cell. */
    fun gridHtml(world: FirelineWorld): String {
        val sb = StringBuilder("<div class=\"grid\">")
        sb.append("<span class=\"corner\"></span>")
        for (x in 0 until GRID) sb.append("<span class=\"ruler\">").append(decPad(x, 2)).append("</span>")
        val nose = noseCaret(TextGrid.dirName(world.rover.facing))
        for (y in 0 until GRID) {
            sb.append("<span class=\"ruler\">").append(decPad(y, 2)).append("</span>")
            for (x in 0 until GRID) sb.append(cellHtml(world, x, y, nose))
        }
        return sb.append("</div>").toString()
    }

    /** The symbol legend, byte-identical to `LEGEND` in `gen_web_mockups.py`
     *  (single occurrence rule §15.4: it lives here, under the grid, nowhere else). */
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
        val glyph = ConsoleRender.escapeHtml(TextGrid.glyphAt(world, x, y).toString())
        val style = when {
            cssClass == "empty" -> " style=\"$IDLE_CELL_STYLE\""
            cssClass.startsWith("fire") -> " style=\"$FIRE_CELL_STYLE\""
            else -> ""
        }
        val noseMarkup = if (cssClass == "rover") "<i class=\"nose\">$nose</i>" else ""
        return "<span class=\"cell $cssClass\"$style>$glyph$noseMarkup</span>"
    }

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
