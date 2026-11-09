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
import robofight.fireline.Mission
import robofight.fireline.FIRELINE_ENGINE_VERSION
import robofight.fireline.app.FirelineTheme
import robofight.fireline.app.files.FirmwareRepository

/**
 * MISSION surface, picker (milestones 4-5; DESIGN-FIRELINE.md 5.2).
 *
 * Empty state: the three free mission cards (ids 01, 02, 04, engine briefing,
 * objective chips, tick limit, wind, water and read-only seed). Firmware is
 * selected in FILES; this surface only displays the active filename.
 *
 * Choosing mission + firmware hands the pair to MainActivity, which opens the
 * live MissionRunSurface (milestone 5): header card, grid, telemetry, event
 * panel and the RUN / STEP / RESET bar.
 */
class MissionSurface(
    private val activity: Activity,
    private val theme: FirelineTheme,
    private val store: FirmwareRepository,
    private val selection: FirmwareSelection,
    private val bestStore: BestBadgeStore,
    private val onOpenRun: (Mission, String) -> Unit,
) {

    /** Mission cards and the active firmware filename; selection lives in FILES. */
    fun picker(attention: String? = null): View {
        val column = column(matchParent, wrapContent)
        if (attention != null) column.addView(text(8, theme.textBright, attention))
        val notice = text(8, theme.textDim)
        notice.text = selection.get()
            ?.let { "mission firmware: $it" }
            ?: "no mission firmware selected  //  select a file in FILES"
        column.addView(notice)
        for (mission in MissionPickerModel.availableMissions) {
            column.addView(missionCard(mission, MissionPickerModel.cardFor(mission, bestStore.get(mission.id, FIRELINE_ENGINE_VERSION))))
        }
        return ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            addView(column)
        }
    }

    // ---- cards ------------------------------------------------------------------

    private fun missionCard(mission: Mission, card: MissionCard): View {
        val panel = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(8))
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
        }
        val head = row(matchParent, wrapContent)
        head.addView(text(9, theme.textBright, "${card.idLabel}  ${card.name}", theme.displayFace))
        panel.addView(head)

        panel.addView(gapped(11, theme.text, card.briefing, theme.workFace))
        panel.addView(chipRow(card.objectiveChips))
        panel.addView(gapped(
            10, theme.textDim,
            "${card.seedLabel}  //  ${card.timeLimitLabel}  //  WIND ${card.windLabel}  //  WATER ${card.startingWater}",
            theme.workFace,
        ))
        if (card.badgeLabel == null) panel.addView(gapped(10, theme.textDim, "no run recorded yet for engine v$FIRELINE_ENGINE_VERSION", theme.workFace))

        val actions = row(matchParent, wrapContent).apply { setPadding(0, theme.dp(4), 0, 0) }
        val notice = text(12, theme.textDim, face = theme.workFace).apply { visibility = View.GONE }
        actions.addView(button("OPEN MISSION", theme.runFill, theme.runText, {
            val firmware = selection.get()
            if (firmware == null || store.find(firmware) == null) {
                notice.text = "select firmware in FILES, then open a mission"
                notice.visibility = View.VISIBLE
            } else {
                onOpenRun(mission, firmware)
            }
        }))
        panel.addView(actions)
        panel.addView(notice)
        return panel
    }

    private fun chipRow(chips: List<String>): View {
        val row = row(matchParent, wrapContent).apply { setPadding(0, theme.dp(3), 0, theme.dp(1)) }
        for (chip in chips) {
            val view = text(10, theme.text, chip.replace('_', ' '), theme.workFace).apply {
                background = theme.panelDrawable(fill = theme.panel2, stroke = theme.border)
                setPadding(theme.dp(4), theme.dp(2), theme.dp(4), theme.dp(2))
                contentDescription = chip
            }
            row.addView(view)
        }
        return row
    }

    // ---- view builders (house style) --------------------------------------------

    private fun button(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        text(8, textColor, label, theme.displayFace).apply {
            background = theme.panelDrawable(fill = fill, stroke = fill)
            setPadding(theme.dp(8), theme.dp(6), theme.dp(8), theme.dp(6))
            gravity = Gravity.CENTER
            contentDescription = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
        }

    /** Small section gap; wrapped body lines use the font's natural leading. */
    private fun gapped(sizeSp: Int, color: Int, value: String, face: Typeface): TextView =
        text(sizeSp, color, value, face).apply {
        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            topMargin = theme.dp(2)
        }
    }

    private fun text(sizeSp: Int, color: Int, value: String = "", face: Typeface = theme.displayFace): TextView =
        TextView(activity).apply {
            text = value
            setTextColor(color)
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            includeFontPadding = false
            setLineSpacing(0f, 1f)
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

    companion object {
        private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
