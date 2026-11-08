package robofight.fireline.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import robofight.fireline.CivilianStatus
import robofight.fireline.DIR_E
import robofight.fireline.DIR_N
import robofight.fireline.DIR_S
import robofight.fireline.DIR_W
import robofight.fireline.GRID
import robofight.fireline.TERRAIN_EXIT
import robofight.fireline.TERRAIN_FLOOR
import robofight.fireline.TERRAIN_FUEL
import robofight.fireline.TERRAIN_PROTECTED
import robofight.fireline.TERRAIN_REFILL
import robofight.fireline.TERRAIN_WALL
import robofight.fireline.TextGrid
import robofight.fireline.FirelineWorld
import robofight.fireline.SMOKE_MAX
import robofight.fireline.app.FirelineTheme

/**
 * Canvas renderer for the 20x20 FIRELINE grid (DESIGN-FIRELINE.md 4). The
 * house-style ArenaView is the rendering reference; this is FIRELINE's own
 * view and the world data reaches it only through public FirelineWorld
 * accessors, tick by tick.
 *
 * Glyph language (all state-carrying):
 *  - fire is always the digit 1-4 in the ramp color (amendment A-2), chosen
 *    through the engine's own TextGrid.fireGlyph so app and headless agree;
 *  - rover uses Arena's centered letter/direction pair on one baseline,
 *    inside a panel-2 identity tile;
 *  - packets are `*` from the current tick's packet list only - drawn where
 *    the engine says they are, never interpolated;
 *  - shield is a one-cell bracket ring while `shieldTicksRemaining > 0`, and
 *    the numeric SHIELD readout in the telemetry panel is its twin;
 *  - the selected fire target (FIRE_DIST's cell) gets a thin text-bright
 *    outline; no target means no outline.
 * Entity precedence matches TextGrid: rover, civilian, packet, fire, terrain.
 * Smoke is a dim panel-2 tint on otherwise empty floor cells only, capped at
 * 30% alpha (DESIGN 4). Nothing here animates rover, shield, packets or
 * civilians; the CRT overlay is default-off and absent (DESIGN 7).
 */
class FirelineGridView(
    context: Context,
    private val theme: FirelineTheme,
) : View(context) {

    var world: FirelineWorld? = null
        set(value) {
            field = value
            invalidate()
        }

    private val displayFace: Typeface = theme.displayFace

    private val fillPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyphPaint = Paint().apply { textAlign = Paint.Align.CENTER; typeface = displayFace }
    private val roverPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        typeface = displayFace
        color = theme.rover
    }

    private val firePaints = intArrayOf(theme.fire1, theme.fire2, theme.fire3, theme.fire4)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Square canvas: the 20x20 grid never stretches, so one cell is one
        // square and glyph positions stay honest at any window width.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = minOf(w, h)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val w = world
        if (w == null) return
        val cell = width.toFloat() / GRID

        fillPaint.color = theme.field
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        val target = w.selectedFire()
        val roverX = w.rover.x
        val roverY = w.rover.y

        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                drawCell(canvas, w, x, y, cell, roverX, roverY)
            }
        }

        if (target != null) {
            linePaint.color = theme.textBright
            linePaint.strokeWidth = 1f
            canvas.drawRect(
                target.x * cell + 1f, target.y * cell + 1f,
                (target.x + 1) * cell - 1f, (target.y + 1) * cell - 1f,
                linePaint,
            )
        }

        if (w.rover.shieldTicksRemaining > 0) drawShieldRing(canvas, roverX, roverY, cell)

        // Grid lines over the cells, under the frame border.
        linePaint.color = theme.gridline
        linePaint.strokeWidth = 1f
        for (i in 1 until GRID) {
            canvas.drawLine(i * cell, 0f, i * cell, height.toFloat(), linePaint)
            canvas.drawLine(0f, i * cell, width.toFloat(), i * cell, linePaint)
        }
        linePaint.color = theme.border
        linePaint.strokeWidth = 2f
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), linePaint)
    }

    private fun drawCell(canvas: Canvas, w: FirelineWorld, x: Int, y: Int, cell: Float, roverX: Int, roverY: Int) {
        val left = x * cell
        val top = y * cell
        val terrain = w.terrainAt(x, y)
        val fire = w.fireAt(x, y)
        val isRover = x == roverX && y == roverY
        val civilian = w.civilians.firstOrNull { it.status == CivilianStatus.WAITING && it.x == x && it.y == y }

        if (isRover) {
            fillPaint.color = theme.panel2 // identity tile (amendment A-5)
            canvas.drawRect(left, top, left + cell, top + cell, fillPaint)
        } else if (terrain == TERRAIN_WALL) {
            fillPaint.color = theme.wall
            canvas.drawRect(left + 1f, top + 1f, left + cell - 1f, top + cell - 1f, fillPaint)
        }

        val packetHere = w.hasWaterPacketAt(x, y)
        val occupied = isRover || civilian != null || packetHere || fire > 0 ||
            terrain in setOf(TERRAIN_EXIT, TERRAIN_REFILL, TERRAIN_FUEL, TERRAIN_PROTECTED, TERRAIN_WALL)

        if (!occupied && terrain == TERRAIN_FLOOR) {
            // Smoke tint: only cells that hold nothing, capped at 30% alpha.
            val smoke = w.smokeAt(x, y)
            if (smoke > 0) {
                val alpha = (smoke.toFloat() / SMOKE_MAX * 0.30f * 255f).toInt().coerceAtMost((0.30f * 255f).toInt())
                fillPaint.color = (theme.panel2 and 0x00FFFFFF) or (alpha shl 24)
                canvas.drawRect(left + 1f, top + 1f, left + cell - 1f, top + cell - 1f, fillPaint)
            }
        }

        val glyph = TextGrid.glyphAt(w, x, y)
        when {
            isRover -> {
                // Match Opcode Arena: the two-character marker is centered as
                // one unit, using the same pixel font, size and baseline.
                val heading = when (w.rover.facing and 3) {
                    DIR_N -> '^'; DIR_E -> '>'; DIR_S -> 'v'; DIR_W -> '<'; else -> '?'
                }
                roverPaint.textSize = cell * 0.52f
                canvas.drawText("${TextGrid.ROVER_GLYPH}$heading",
                    left + cell * 0.5f, top + cell * 0.69f, roverPaint)
            }
            civilian != null -> drawGlyph(canvas, glyphPaint.apply {
                color = theme.civilian
                textSize = cell * 0.62f
                typeface = displayFace
            }, cell, left, top, TextGrid.CIVILIAN_GLYPH.toString())
            packetHere -> drawGlyph(canvas, glyphPaint.apply {
                color = theme.water
                textSize = cell * 0.62f
                typeface = displayFace
            }, cell, left, top, TextGrid.PACKET_GLYPH.toString())
            glyph != null && fire > 0 -> {
                val level = (TextGrid.fireGlyph(fire)!! - '1').coerceIn(0, 3)
                drawGlyph(canvas, glyphPaint.apply {
                    color = firePaints[level]
                    textSize = cell * 0.62f
                    typeface = displayFace
                }, cell, left, top, glyph.toString())
            }
            terrain == TERRAIN_EXIT -> drawGlyph(canvas, glyphPaint.apply {
                color = theme.exit
                textSize = cell * 0.62f
                typeface = displayFace
            }, cell, left, top, "E")
            terrain == TERRAIN_REFILL -> drawGlyph(canvas, glyphPaint.apply {
                color = theme.refill
                textSize = cell * 0.62f
                typeface = displayFace
            }, cell, left, top, "W")
            terrain == TERRAIN_FUEL -> drawGlyph(canvas, glyphPaint.apply {
                color = theme.fuel
                textSize = cell * 0.62f
                typeface = displayFace
            }, cell, left, top, "F")
            terrain == TERRAIN_PROTECTED -> drawGlyph(canvas, glyphPaint.apply {
                color = theme.asset
                textSize = cell * 0.62f
                typeface = displayFace
            }, cell, left, top, "P")
            !occupied && terrain == TERRAIN_FLOOR -> drawGlyph(canvas, glyphPaint.apply {
                color = theme.empty
                textSize = cell * 0.42f
                typeface = displayFace
            }, cell, left, top, ".")
        }
    }

    private fun drawGlyph(canvas: Canvas, paint: Paint, cell: Float, left: Float, top: Float, text: String) {
        val baseline = top + cell / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, left + cell / 2f, baseline, paint)
    }

    private fun drawShieldRing(canvas: Canvas, x: Int, y: Int, cell: Float) {
        linePaint.color = theme.caret
        linePaint.strokeWidth = 2f
        val left = x * cell + 1f
        val top = y * cell + 1f
        val right = (x + 1) * cell - 1f
        val bottom = (y + 1) * cell - 1f
        val arm = cell * 0.30f
        // Four bracket corners: the one-cell ring, never a solid frame.
        canvas.drawLine(left, top, left + arm, top, linePaint)
        canvas.drawLine(left, top, left, top + arm, linePaint)
        canvas.drawLine(right - arm, top, right, top, linePaint)
        canvas.drawLine(right, top, right, top + arm, linePaint)
        canvas.drawLine(left, bottom, left + arm, bottom, linePaint)
        canvas.drawLine(left, bottom - arm, left, bottom, linePaint)
        canvas.drawLine(right - arm, bottom, right, bottom, linePaint)
        canvas.drawLine(right, bottom - arm, right, bottom, linePaint)
    }

}
