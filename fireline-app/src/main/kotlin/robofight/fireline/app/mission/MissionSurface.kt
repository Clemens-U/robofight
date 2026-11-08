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
import robofight.fireline.Missions
import robofight.fireline.FIRELINE_ENGINE_VERSION
import robofight.fireline.app.FirelineTheme
import robofight.fireline.app.editor.EditorModel
import robofight.fireline.app.files.FirmwareFile
import robofight.fireline.app.files.FirmwareRepository

/**
 * MISSION surface, picker (milestones 4-5; DESIGN-FIRELINE.md 5.2).
 *
 * Empty state: the five authored mission cards (ids 01-05, engine briefing,
 * objective chips, tick limit, wind, water, read-only seed, best-result badge
 * per engine version) above the firmware picker (the FIRELINE file store's
 * saved sources, `[>]` selects the mission firmware exactly like FILES does).
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
    private val onSelectFirmware: (FirmwareFile) -> Unit,
) {

    /** The MISSION tab's empty state: mission cards + firmware picker. */
    fun picker(attention: String? = null): View {
        val column = column(matchParent, wrapContent)
        if (attention != null) column.addView(text(8, theme.textBright, attention))
        val notice = text(8, theme.textDim)
        notice.text = selection.get()
            ?.let { "mission firmware: $it" }
            ?: "no mission firmware selected  //  pick one below or in FILES"
        column.addView(notice)
        for (mission in Missions.ALL) {
            column.addView(missionCard(mission, MissionPickerModel.cardFor(mission, bestStore.get(mission.id, FIRELINE_ENGINE_VERSION))))
        }
        column.addView(firmwarePicker())
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
        head.addView(spacer())
        card.badgeLabel?.let { head.addView(text(7, theme.caret, it, theme.displayFace)) }
        panel.addView(head)

        panel.addView(gapped(7, theme.text, card.briefing, theme.workFace))
        panel.addView(chipRow(card.objectiveChips))
        panel.addView(gapped(
            7, theme.textDim,
            "${card.seedLabel}  //  ${card.timeLimitLabel}  //  WIND ${card.windLabel}  //  WATER ${card.startingWater}",
            theme.workFace,
        ))
        if (card.badgeLabel == null) panel.addView(gapped(7, theme.textDim, "no run recorded yet for engine v$FIRELINE_ENGINE_VERSION", theme.workFace))

        val actions = row(matchParent, wrapContent).apply { setPadding(0, theme.dp(7), 0, 0) }
        val notice = text(7, theme.textDim).apply { visibility = View.GONE }
        actions.addView(button("OPEN RUN SETUP", theme.runFill, theme.runText, {
            val firmware = selection.get()
            if (firmware == null || store.find(firmware) == null) {
                notice.text = "select mission firmware first  //  pick one in the picker below"
                notice.visibility = View.VISIBLE
            } else {
                onOpenRun(mission, firmware)
            }
        }))
        panel.addView(actions)
        panel.addView(notice)
        return panel
    }

    private fun firmwarePicker(): View {
        val section = column(matchParent, wrapContent).apply { setPadding(0, theme.dp(12), 0, 0) }
        section.addView(text(8, theme.textBright, "MISSION FIRMWARE", theme.displayFace))
        val files = store.list()
        if (files.isEmpty()) {
            section.addView(text(7, theme.textDim, "no firmware files yet  //  NEW FILE or IMPORT .asm in FILES", theme.workFace))
            return section
        }
        val selected = selection.get()
        for (file in files) {
            val card = row(matchParent, wrapContent).apply {
                background = theme.panelDrawable()
                setPadding(theme.dp(8), theme.dp(7), theme.dp(8), theme.dp(7))
                layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                    topMargin = theme.dp(6)
                }
            }
            card.addView(text(8, theme.text, file.name, theme.workFace))
            card.addView(spacer())
            card.addView(text(7, theme.textDim, EditorModel.cardStats(file.source).format(), theme.workFace))
            if (file.name.equals(selected, ignoreCase = true)) {
                card.addView(text(7, theme.caret, "SELECTED", theme.displayFace))
            }
            card.addView(button("[>]", theme.secondaryFill, theme.secondaryText, { onSelectFirmware(file) }))
            section.addView(card)
        }
        return section
    }

    private fun chipRow(chips: List<String>): View {
        val row = row(matchParent, wrapContent).apply { setPadding(0, theme.dp(5), 0, theme.dp(5)) }
        for (chip in chips) {
            val view = text(6, theme.text, chip, theme.displayFace).apply {
                background = theme.panelDrawable(fill = theme.panel2, stroke = theme.border)
                setPadding(theme.dp(5), theme.dp(3), theme.dp(5), theme.dp(3))
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

    /** Stacked chrome line with its own top gap (milestone 8 line spacing). */
    private fun gapped(sizeSp: Int, color: Int, value: String, face: Typeface): TextView =
        text(sizeSp, color, value, face).apply {
        layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            topMargin = theme.dp(5)
        }
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
        private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
