package robofight.android

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
 * Draws the fight as a terminal-style character grid. The view stays square
 * within whatever space RUN mode gives it, including landscape screens.
 *
 * The view does not own the [World] — it reads from the [RunController] and
 * calls invalidate() each frame to trigger onDraw().
 */
class ArenaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val pixelTypeface: Typeface =
        Typeface.createFromAsset(context.assets, "fonts/PressStart2P-Regular.ttf")

    // --- paints ---
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#000502")
    }
    private val cellPaint = Paint().apply {
        color = Color.parseColor("#020c05")
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#0b2b14")
        strokeWidth = 1f
    }
    private val emptyDotPaint = Paint().apply {
        color = Color.parseColor("#174525")
        textSize = 6f
        textAlign = Paint.Align.CENTER
        typeface = pixelTypeface
    }
    private val glyphPaint = Paint().apply {
        textAlign = Paint.Align.CENTER
        typeface = pixelTypeface
    }
    private val projGlyphPaint = Paint().apply {
        color = Color.parseColor("#eaff93")
        textAlign = Paint.Align.CENTER
        typeface = pixelTypeface
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34ec5b")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val GRID = 20
    private val DIR_GLYPH = arrayOf('<', '^', '>', 'v')

    private var cellSize: Float = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            maxWidth == 0 -> maxHeight
            maxHeight == 0 -> maxWidth
            else -> minOf(maxWidth, maxHeight)
        }
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        cellSize = w / GRID

        // background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Character cells and the faint idle glyph that makes the arena read
        // like a real text-mode display instead of a modern game board.
        emptyDotPaint.textSize = cellSize * 0.38f
        for (py in 0 until GRID) {
            for (px in 0 until GRID) {
                val left = px * cellSize
                val top = py * cellSize
                canvas.drawRect(left, top, left + cellSize, top + cellSize, cellPaint)
                canvas.drawText("·", left + cellSize / 2f, top + cellSize * 0.64f, emptyDotPaint)
            }
        }

        // grid lines (subtle)
        for (i in 0..GRID) {
            val x = i * cellSize
            val y = i * cellSize
            canvas.drawLine(x, 0f, x, h, gridPaint)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val world = controller?.world
        if (world == null) {
            glyphPaint.color = Color.parseColor("#34ec5b")
            glyphPaint.textSize = cellSize * 0.75f
            canvas.drawText("SYSTEM IDLE", w / 2f, h / 2f, glyphPaint)
            canvas.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, borderPaint)
            return
        }

        // projectiles (under bots)
        for (p in world.projectiles) {
            val left = p.x * cellSize
            val top = p.y * cellSize
            projGlyphPaint.textSize = cellSize * 0.72f
            canvas.drawText("*", left + cellSize / 2f, top + cellSize * 0.72f, projGlyphPaint)
        }

        // bots
        for (b in world.bots) {
            if (!b.alive) continue
            val left = b.px * cellSize
            val top = b.py * cellSize

            glyphPaint.color = Color.parseColor(Palette.HEX[b.color % 15])
            glyphPaint.textSize = cellSize * 0.52f
            canvas.drawText(
                "${b.glyph}${DIR_GLYPH[b.facing % 4]}",
                left + cellSize / 2f,
                top + cellSize * 0.69f,
                glyphPaint,
            )
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
