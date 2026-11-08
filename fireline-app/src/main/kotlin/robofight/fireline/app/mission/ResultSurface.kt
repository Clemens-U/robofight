package robofight.fireline.app.mission

import android.app.Activity
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import robofight.fireline.app.FirelineTheme

/**
 * RESULT surface (milestone 6; DESIGN-FIRELINE.md 5.3): big banner
 * (MISSION COMPLETE or FAILURE with the exact reason chip), the mission id /
 * name / tick line, objective met chips, the full stat block, the WHY block
 * with the persisted last-events tail (tapping it opens the scrollable log
 * overlay, same affordance as on MISSION), and the RETRY / EDIT FIRMWARE /
 * MISSION LIST routes. For FIRMWARE_FAULT the halt reason and last executed
 * instruction are added (11.6).
 *
 * Everything renders from the stored RunSummary (plain data, FIRELINE-DESIGN
 * 12.1); the surface never touches world state and cannot resurrect one.
 */
class ResultSurface(
    private val activity: Activity,
    private val theme: FirelineTheme,
    private val summary: RunSummary,
    private val onRetry: () -> Unit,
    private val onEditFirmware: () -> Unit,
    private val onMissionList: () -> Unit,
) {
    private val logOverlay = ScrollView(activity)
    private val logColumn = column(matchParent, wrapContent)

    val root: View

    init {
        val container = android.widget.FrameLayout(activity)
        val column = column(matchParent, wrapContent)
        container.addView(column)

        val banner = text(12, theme.textBright, ResultModel.banner(summary), theme.displayFace).apply {
            background = theme.panelDrawable(
                fill = if (summary.success) theme.panel else theme.runFill,
                stroke = if (summary.success) theme.caret else theme.runFill,
            )
            setPadding(theme.dp(10), theme.dp(12), theme.dp(10), theme.dp(12))
            setLineSpacing(theme.chromeLineSpacingPx().toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
        }
        column.addView(banner)
        // Mission line and objective chips were flush under the banner; every
        // chrome block now gets its own vertical gap (milestone 8).
        column.addView(text(8, theme.text, ResultModel.missionLine(summary), theme.workFace).apply {
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
        })

        val chips = row(matchParent, wrapContent).apply {
            setPadding(0, theme.dp(7), 0, theme.dp(7))
        }
        chips.addView(text(7, theme.textDim, "objectives:", theme.displayFace))
        for ((name, met) in ResultModel.objectiveChips(summary)) {
            chips.addView(text(
                7, if (met) theme.caret else theme.runFill,
                "${if (met) "o" else "x"} $name",
                theme.displayFace,
            ).apply { setPadding(theme.dp(5), 0, theme.dp(5), 0) })
        }
        column.addView(chips)

        val stats = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(8))
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
        }
        addStackedRows(stats, ResultModel.statLines(summary), theme.text)
        addStackedRows(stats, ResultModel.haltLines(summary), theme.textBright)
        column.addView(stats)

        val why = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(8))
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
            contentDescription = "WHY  //  last events, tap for the log tail"
        }
        why.addView(text(7, theme.textBright, "WHY: last events  //  tap for log tail", theme.displayFace))
        val tail = ResultModel.whyLines(summary)
        if (tail.isEmpty()) why.addView(text(7, theme.textDim, "no events recorded", theme.workFace))
        addStackedRows(why, tail.take(WHY_VISIBLE), theme.text)
        val openLog = { showLogOverlay(tail) }
        why.setOnClickListener { openLog() }
        column.addView(why)

        val actions = row(matchParent, wrapContent).apply { setPadding(0, theme.dp(10), 0, theme.dp(10)) }
        actions.addView(button("RETRY", theme.runFill, theme.runText, onRetry))
        actions.addView(button("EDIT FIRMWARE", theme.secondaryFill, theme.secondaryText, onEditFirmware))
        actions.addView(spacer())
        actions.addView(button("MISSION LIST", theme.secondaryFill, theme.secondaryText, onMissionList))
        column.addView(actions)

        buildLogOverlay()
        container.addView(logOverlay)

        root = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            addView(container)
        }
    }

    private fun buildLogOverlay() {
        logOverlay.visibility = View.GONE
        logOverlay.setBackgroundColor(theme.field)
        logOverlay.isVerticalScrollBarEnabled = true
        val body = column(matchParent, wrapContent).apply {
            setPadding(theme.dp(10), theme.dp(8), theme.dp(10), theme.dp(8))
        }
        val head = row(matchParent, wrapContent)
        head.addView(text(8, theme.textBright, "RUN EVENT LOG TAIL  //  newest first", theme.displayFace))
        head.addView(spacer())
        head.addView(button("CLOSE", theme.secondaryFill, theme.secondaryText) {
            logOverlay.visibility = View.GONE
        })
        body.addView(head)
        body.addView(logColumn)
        logOverlay.addView(body)
    }

    private fun showLogOverlay(tail: List<String>) {
        logColumn.removeAllViews()
        if (tail.isEmpty()) logColumn.addView(text(7, theme.textDim, "no events recorded", theme.workFace))
        addStackedRows(logColumn, tail, theme.text)
        logOverlay.visibility = View.VISIBLE
    }

    // ---- view builders (house style) --------------------------------------------

    /**
     * Stat / WHY rows are separate TextViews, so the vertical breathing room
     * between them has to be an explicit row margin (milestone 8: the rows
     * used to touch). Row metrics stay untouched - only the gap changes.
     */
    private fun addStackedRows(target: LinearLayout, lines: List<String>, color: Int) {
        for (line in lines) {
            val row = text(7, color, line, theme.workFace)
            row.layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.dp(STAT_ROW_GAP_DP)
            }
            target.addView(row)
        }
    }

    private fun button(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        text(8, textColor, label, theme.displayFace).apply {
            background = theme.panelDrawable(fill = fill, stroke = fill)
            setPadding(theme.dp(8), theme.dp(6), theme.dp(8), theme.dp(6))
            gravity = Gravity.CENTER
            contentDescription = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
        }

    private fun text(sizeSp: Int, color: Int, value: String = "", face: Typeface = theme.displayFace): TextView =
        TextView(activity).apply {
            text = value
            setTextColor(color)
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            includeFontPadding = false
            setLineSpacing(theme.chromeLineSpacingPx().toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
        }

    private fun row(width: Int, height: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun column(width: Int, height: Int) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun spacer(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    companion object {
        private const val WHY_VISIBLE = 4
        private const val STAT_ROW_GAP_DP = 4
        private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
