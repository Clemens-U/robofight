package robofight.fireline.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import robofight.fireline.R

/**
 * FIRELINE chrome (DESIGN-FIRELINE.md 2.2b + 3): the LOCKED direction F
 * "Firecall Warm" tokens are declared once in res/values/colors.xml and read
 * here, so no Kotlin file carries a hand-copied hex literal.
 *
 * Two faces by design (amendment A-4): Press Start 2P for display chrome
 * (wordmark, tabs, button labels) and a monospace work face for code and
 * telemetry. The work face arrives with the editor (milestone 3).
 */
class FirelineTheme(private val context: Context) {

    val field = color(R.color.fireline_field)
    val panel = color(R.color.fireline_panel)
    val panel2 = color(R.color.fireline_panel2)
    val border = color(R.color.fireline_border)
    val gridline = color(R.color.fireline_gridline)

    val text = color(R.color.fireline_text)
    val textBright = color(R.color.fireline_text_bright)
    val textDim = color(R.color.fireline_text_dim)

    val rover = color(R.color.fireline_rover)
    val caret = color(R.color.fireline_caret)

    val runFill = color(R.color.fireline_run_fill)
    val runText = color(R.color.fireline_run_text)
    val secondaryFill = color(R.color.fireline_secondary_fill)
    val secondaryText = color(R.color.fireline_secondary_text)

    /** Display face: wordmark, tabs, control labels (never body-size reading). */
    val displayFace: Typeface by lazy {
        Typeface.createFromAsset(context.assets, "fonts/PressStart2P-Regular.ttf")
    }

    fun color(id: Int): Int = context.resources.getColor(id)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Card fill with a border, the house pattern for panel chrome. */
    fun panelDrawable(fill: Int = panel, stroke: Int = border, radiusDp: Int = 2): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** Faint horizontal rule in the border color (wordmark underline, section rules). */
    fun rule(heightDp: Int = 1, colorOverride: Int = border): android.view.View =
        android.view.View(context).apply {
            setBackgroundColor(colorOverride)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp),
            )
        }

}
