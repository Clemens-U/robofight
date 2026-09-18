package robofight.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import robofight.world.Palette
import robofight.world.World

/**
 * Canvas renderer for the 20×20 arena.
 *
 * Draws the grid, bot glyphs (colored), direction indicators, and projectiles
 * using the engine's NES palette. The view is a square (width == height) and
 * each cell is width / 20.
 *
 * The view does not own the [World] — it reads from the [RunController] and
 * calls invalidate() each frame to trigger onDraw().
 */
class ArenaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val monospace: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    // --- paints ---
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0d0d14")
    }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#161622")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222233")
        strokeWidth = 1f
    }
    private val emptyDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2a2a3a")
        textSize = 6f
        textAlign = Paint.Align.CENTER
        typeface = monospace
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = monospace
    }
    private val projPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val projGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF00")
        textAlign = Paint.Align.CENTER
        typeface = monospace
        textSize = 14f
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334455")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val GRID = 20
    private val DIR_GLYPH = arrayOf('<', '^', '>', 'v')

    private var cellSize: Float = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force square: use the width spec, set height = width.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val height = w
        setMeasuredDimension(w, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        cellSize = w / GRID

        // background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // grid cells
        for (py in 0 until GRID) {
            for (px in 0 until GRID) {
                val left = px * cellSize
                val top = py * cellSize
                canvas.drawRect(left, top, left + cellSize, top + cellSize, cellPaint)
            }
        }

        // grid lines (subtle)
        for (i in 0..GRID) {
            val x = i * cellSize
            val y = i * cellSize
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val world = controller?.world ?: return

        // projectiles (under bots)
        for (p in world.projectiles) {
            val left = p.x * cellSize
            val top = p.y * cellSize
            // filled square (the * glyph)
            canvas.drawRect(left + cellSize * 0.25f, top + cellSize * 0.25f,
                left + cellSize * 0.75f, top + cellSize * 0.75f, projPaint)
        }

        // bots
        for (b in world.bots) {
            if (!b.alive) continue
            val left = b.px * cellSize
            val top = b.py * cellSize

            // bot body (colored rectangle)
            glyphPaint.color = Color.parseColor(Palette.HEX[b.color % 15])
            canvas.drawRect(left, top, left + cellSize, top + cellSize, glyphPaint)

            // direction nose (white, in the cell)
            val dirGlyph = DIR_GLYPH[b.facing % 4].toString()
            emptyDotPaint.textSize = cellSize * 0.6f
            emptyDotPaint.color = Color.WHITE
            canvas.drawText(dirGlyph, left + cellSize / 2f, top + cellSize * 0.65f, emptyDotPaint)
        }

        // border
        canvas.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, borderPaint)
    }

    // Set by MainActivity
    var controller: RunController? = null
        set(value) {
            field = value
            invalidate()
        }
}
