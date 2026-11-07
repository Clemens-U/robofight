package robofight.fireline.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import robofight.fireline.FIRELINE_ENGINE_VERSION
import robofight.fireline.GRID
import robofight.fireline.Mission
import robofight.fireline.Missions
import robofight.fireline.R

/**
 * FIRELINE MainActivity (PLAN-RF8-MULTIAPP.md 9.2): programmatic views only,
 * no XML layouts and no androidx, same house style as the Arena app.
 *
 * Milestone 1 delivers the chrome: the wordmark strip, the tagline caption,
 * the FILES | MISSION | RESULT tab order and proof that the FIRELINE engine is
 * linked. The FILES surface and the editor land in milestone 3; MISSION and
 * RESULT render as pending tabs until the run surfaces arrive.
 *
 * Motion rule (DESIGN-FIRELINE.md 7): nothing here animates. FIRELINE v1 ships
 * the CRT scanline overlay default-off, so no overlay view exists in this
 * skeleton.
 */
class MainActivity : Activity() {

    private lateinit var theme: FirelineTheme

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = FirelineTheme(this)
        setContentView(buildChrome())
    }

    // ---- chrome -----------------------------------------------------------------

    private fun buildChrome(): View {
        val root = column(matchParent, matchParent).apply {
            setBackgroundColor(theme.field)
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
        }
        root.addView(wordmarkStrip())
        root.addView(theme.rule())
        root.addView(tabStrip())
        root.addView(engineProof())
        return root
    }

    /** `OPCODE FIRELINE            RF-8` plus the tagline caption strip. */
    private fun wordmarkStrip(): View {
        val strip = row(matchParent, wrapContent)
        strip.addView(label("OPCODE FIRELINE", 12, theme.textBright))
        strip.addView(spacer())
        strip.addView(label("RF-8", 10, theme.textDim))
        val block = column(matchParent, wrapContent)
        block.addView(strip)
        block.addView(label(getString(R.string.tagline), 8, theme.textDim))
        return block
    }

    /** Tab order is FILES | MISSION | RESULT (FIRELINE-DESIGN 11.1). */
    private fun tabStrip(): View {
        val strip = row(matchParent, wrapContent).apply {
            setPadding(0, theme.dp(8), 0, theme.dp(8))
        }
        strip.addView(tabButton("FILES", selected = true))
        strip.addView(tabButton("MISSION", selected = false))
        strip.addView(tabButton("RESULT", selected = false))
        return strip
    }

    private fun tabButton(name: String, selected: Boolean): View =
        label(
            if (selected) name else "$name  pending",
            8,
            if (selected) theme.runText else theme.textDim,
        ).apply {
            background = theme.panelDrawable(
                fill = if (selected) theme.runFill else theme.secondaryFill,
                stroke = if (selected) theme.runFill else theme.border,
            )
            setPadding(theme.dp(10), theme.dp(6), theme.dp(10), theme.dp(6))
            gravity = Gravity.CENTER
        }

    /** Proof the FIRELINE engine is linked: version plus the five authored missions. */
    private fun engineProof(): View {
        val card = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(10), theme.dp(10), theme.dp(10), theme.dp(10))
        }
        card.addView(label("FILES", 12, theme.textBright))
        card.addView(label(
            "engine v$FIRELINE_ENGINE_VERSION, ${Missions.ALL.size} authored missions",
            8, theme.textDim,
        ))
        for (mission in Missions.ALL) card.addView(label(missionSkeletonLine(mission), 7, theme.text))
        card.addView(label("firmware storage and editor arrive with milestones 2 and 3", 7, theme.textDim))
        return card
    }

    /**
     * Skeleton-only line: grid size plus civilian count read straight from the
     * mission layout, no invented strings. Milestone 3 replaces this with the
     * real `.asm` file cards.
     */
    private fun missionSkeletonLine(mission: Mission): String =
        "%02d  %-16s grid %dx%d, civilians %d".format(
            mission.id, mission.name, GRID, GRID, mission.layout.civilians.size,
        )

    // ---- view builders (house style) --------------------------------------------

    private fun label(textValue: String, sizeSp: Int, color: Int): TextView =
        TextView(this).apply {
            text = textValue
            setTextColor(color)
            typeface = displayFace
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            includeFontPadding = false
        }

    private fun row(width: Int, height: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun column(width: Int, height: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private val displayFace: Typeface get() = theme.displayFace

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
}
