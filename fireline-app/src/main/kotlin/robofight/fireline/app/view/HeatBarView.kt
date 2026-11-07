package robofight.fireline.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import robofight.fireline.app.FirelineTheme

/**
 * Horizontal heat bar with the overload threshold marked (DESIGN-FIRELINE.md
 * 4: heat lives in telemetry, never as per-cell grid visuals). The fill turns
 * the run color once heat exceeds the engine's HEAT_DAMAGE_THRESHOLD; the
 * threshold tick is always drawn so "how close am I" is readable before the
 * bar changes color.
 */
class HeatBarView(
    context: Context,
    private val theme: FirelineTheme,
) : View(context) {

    var value: Int = 0
        set(v) {
            field = v
            invalidate()
        }
    var max: Int = 100
        set(v) {
            field = v
            invalidate()
        }
    var threshold: Int = 80
        set(v) {
            field = v
            invalidate()
        }

    private val fillPaint = Paint()
    private val framePaint = Paint().apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint().apply { style = Paint.Style.STROKE }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        fillPaint.color = theme.panel2
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        if (max > 0 && value > 0) {
            val over = value > threshold
            fillPaint.color = if (over) theme.runFill else theme.fire2
            canvas.drawRect(1f, 1f, w * (value.coerceAtMost(max) / max), h - 1f, fillPaint)
        }

        if (max > 0) {
            tickPaint.color = theme.textBright
            tickPaint.strokeWidth = 2f
            val x = w * (threshold / max.toFloat())
            canvas.drawLine(x, 0f, x, h, tickPaint)
        }

        framePaint.color = theme.border
        framePaint.strokeWidth = 1f
        canvas.drawRect(0f, 0f, w, h, framePaint)
    }
}
