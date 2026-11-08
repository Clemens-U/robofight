package robofight.fireline.app.mission

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import robofight.fireline.app.FirelineTheme
import robofight.fireline.app.view.FirelineGridView
import robofight.fireline.app.view.HeatBarView

/**
 * MISSION run surface (milestone 5; DESIGN-FIRELINE.md 5.2, PLAN 9.2/9.5):
 * mission header card, live grid panel with glyph legend, the two-column
 * instrument row (RF-8 registers + current instruction with 2 context lines |
 * telemetry ports), the newest-first event panel (tap opens the full log as a
 * scrollable overlay, no separate tab), and the pinned RUN / STEP / RESET bar
 * at 48dp equal thirds (bar 52dp so each button keeps >= 48dp after the bar's
 * top padding - the milestone 7 accessibility measurement).
 *
 * Run control semantics (PLAN 9.5 gate items):
 *  - STEP is exactly one `MissionSession.step()` == one `FirelineWorld.tickOnce()`,
 *    including ticks in the middle of a multi-tick VM instruction;
 *  - RUN is a tick timer (Handler, ~3 ticks/s) that only calls `step()`; when
 *    the world finishes it stops itself and hands the RunSummary upward;
 *  - RESET rebuilds a fresh world from the same mission tuple (no hidden state).
 * Nothing animates except the permitted tick pacing: glyph state is whatever
 * the current tick computed (DESIGN 7).
 */
class MissionRunSurface(
    private val activity: Activity,
    private val theme: FirelineTheme,
    private val session: MissionSession,
    private val onRunFinished: (RunSummary) -> Unit,
) {
    private val mission = session.mission

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val gridView = FirelineGridView(activity, theme).apply {
        contentDescription = "MISSION GRID"
    }
    private val heatBar = HeatBarView(activity, theme)

    private val tickLine = text(14, theme.text, face = theme.workFace)
    private val statusLine = text(14, theme.textDim, face = theme.workFace)
    private val vmRegisters = text(7, theme.text, "", theme.workFace)
    private val vmFlags = text(7, theme.textDim, "", theme.workFace)
    private val vmLastOp = text(7, theme.text, "", theme.workFace)
    private val vmInstruction = text(7, theme.textBright, "", theme.workFace)
    private val contextLabels = List(CONTEXT_SLOTS) { text(7, theme.textDim, "", theme.workFace) }
    private val telemetryLabels = LinkedHashMap<String, TextView>()
    private val eventLabels = List(EVENT_SLOTS) { text(7, theme.text, "", theme.workFace) }
    private val logColumn = column(matchParent, wrapContent)
    private val logOverlay = ScrollView(activity)

    val root: View

    init {
        val container = android.widget.FrameLayout(activity)
        val main = column(matchParent, matchParent)
        container.addView(main)

        val card = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(8))
        }
        card.addView(text(
            10, theme.textBright,
            "MISSION %02d / %s".format(mission.id, mission.name),
            theme.displayFace,
        ))
        val firmwareRow = row(matchParent, wrapContent)
        firmwareRow.addView(text(12, theme.text, session.firmwareName, theme.workFace),
            LinearLayout.LayoutParams(0, wrapContent, 1f))
        val details = column(matchParent, wrapContent).apply {
            setPadding(theme.dp(12), theme.dp(8), theme.dp(12), theme.dp(8))
        }
        details.addView(text(
            12, theme.text,
            "${MissionPickerModel.seedLabel(mission)}\nWIND ${MissionPickerModel.windLabel(mission)}  |  WATER ${mission.startingWater}  |  ${MissionPickerModel.timeLimitLabel(mission)}",
            theme.workFace,
        ))
        details.addView(text(14, theme.textBright, mission.briefing, theme.workFace).apply {
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.dp(8)
                bottomMargin = theme.dp(8)
            }
        })
        firmwareRow.addView(text(12, theme.exit, "BRIEFING +", theme.workFace).apply {
            setPadding(theme.dp(8), theme.dp(4), 0, theme.dp(4))
            contentDescription = "Show mission briefing and objectives"
            setOnClickListener {
                (details.parent as? ViewGroup)?.removeView(details)
                android.app.AlertDialog.Builder(activity)
                    .setTitle("MISSION %02d / %s".format(mission.id, mission.name))
                    .setView(ScrollView(activity).apply { addView(details) })
                    .setPositiveButton("CLOSE", null)
                    .show()
            }
        })
        card.addView(firmwareRow)
        card.addView(chipRow(MissionPickerModel.objectiveChips(mission)))
        main.addView(card)
        val runStatus = row(matchParent, wrapContent).apply {
            setBackgroundColor(theme.panel)
            setPadding(theme.dp(8), theme.dp(4), theme.dp(8), theme.dp(4))
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.dp(6)
            }
        }
        runStatus.addView(tickLine, LinearLayout.LayoutParams(0, wrapContent, 1f))
        runStatus.addView(statusLine)

        val scroller = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(matchParent, 0, 1f)
        }
        val content = column(matchParent, wrapContent)
        // Mission identity and goals remain pinned above the scrolling instruments.

        val gridPanel = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(4), theme.dp(4), theme.dp(4), theme.dp(4))
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
        }
        gridView.layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
        gridPanel.addView(gridView)
        gridPanel.addView(text(LEGEND_SP, theme.textDim, LEGEND, theme.workFace))
        content.addView(gridPanel)

        content.addView(instrumentRow())
        content.addView(eventPanel())
        scroller.addView(content)
        main.addView(scroller)

        main.addView(controlBar())
        main.addView(runStatus)

        buildLogOverlay()
        container.addView(logOverlay)

        root = container
        refresh()
    }

    /** Stops the RUN timer; MainActivity calls it whenever the surface leaves. */
    fun stopRun() {
        running = false
        handler.removeCallbacks(ticker)
    }

    // ---- run control -------------------------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            stepOnce()
            if (running) handler.postDelayed(this, MissionSession.RUN_TICK_MS)
        }
    }

    private fun stepOnce() {
        when (session.step()) {
            StepOutcome.FINISHED -> {
                running = false
                handler.removeCallbacks(ticker)
                refresh()
                onRunFinished(session.summary())
            }
            StepOutcome.GUARD_STOPPED -> {
                running = false
                handler.removeCallbacks(ticker)
                statusLine.text = "TICK GUARD ${mission.maxTicks} REACHED  //  RESET or EDIT FIRMWARE"
                refresh()
            }
            else -> refresh()
        }
    }

    private fun controlBar(): View {
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(matchParent, theme.dp(CONTROL_BAR_DP))
            setPadding(0, theme.dp(4), 0, 0)
        }
        bar.addView(controlButton("RUN", theme.runFill, theme.runText) {
            if (session.finished || running) return@controlButton
            running = true
            ticker.run()
        })
        bar.addView(View(activity), LinearLayout.LayoutParams(theme.dp(6), 1))
        bar.addView(controlButton("STEP", theme.secondaryFill, theme.secondaryText) {
            if (running || session.finished) return@controlButton
            stepOnce()
        })
        bar.addView(View(activity), LinearLayout.LayoutParams(theme.dp(6), 1))
        bar.addView(controlButton("RESET", theme.secondaryFill, theme.secondaryText) {
            stopRun()
            session.reset()
            refresh()
        })
        return bar
    }

    private fun controlButton(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        text(8, textColor, label, theme.displayFace).apply {
            background = theme.panelDrawable(fill = fill, stroke = fill)
            gravity = Gravity.CENTER
            contentDescription = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, matchParent, 1f)
        }

    // ---- instrument row ----------------------------------------------------------

    /**
     * One framed panel around both instrument columns (milestone 9): a single
     * `panelDrawable` border encloses the RF-8 column and the TELEMETRY column,
     * separated by a 1dp vertical rule in the border token color. The row is a
     * horizontal LinearLayout, so the panel stretches to the taller column and
     * the MATCH_PARENT-height children share the same panel top/bottom; both
     * column bodies are top-weighted, so the RF-8 / TELEMETRY headings sit on
     * the same baseline. The columns carry no frames of their own (the old
     * double-border look came from each column having its own panelDrawable).
     */
    private fun instrumentRow(): View {
        val panel = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
        }
        val row = row(matchParent, wrapContent)
        panel.addView(row)

        val vm = column(0, matchParent).apply {
            setPadding(theme.dp(8), theme.dp(8), theme.dp(7), theme.dp(8))
            layoutParams = LinearLayout.LayoutParams(0, matchParent, 1f)
        }
        vm.addView(text(7, theme.textBright, "RF-8", theme.displayFace))
        vm.addView(vmRegisters)
        vm.addView(vmFlags)
        vm.addView(vmLastOp)
        vm.addView(vmInstruction)
        contextLabels.forEach { vm.addView(it) }

        val separator = View(activity).apply {
            setBackgroundColor(theme.border)
            layoutParams = LinearLayout.LayoutParams(theme.dp(1), matchParent)
        }

        val tele = column(0, matchParent).apply {
            setPadding(theme.dp(7), theme.dp(8), theme.dp(8), theme.dp(8))
            layoutParams = LinearLayout.LayoutParams(0, matchParent, 1f)
        }
        tele.addView(text(7, theme.textBright, "TELEMETRY", theme.displayFace))
        for (name in TELEMETRY_ORDER) {
            val label = text(7, theme.text, "$name --", theme.workFace)
            telemetryLabels[name] = label
            tele.addView(label)
        }
        heatBar.layoutParams = LinearLayout.LayoutParams(matchParent, theme.dp(HEAT_BAR_DP)).apply {
            topMargin = theme.dp(2)
            bottomMargin = theme.dp(2)
        }
        tele.addView(heatBar)

        row.addView(vm)
        row.addView(separator)
        row.addView(tele)
        return panel
    }

    private fun eventPanel(): View {
        val panel = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(8), theme.dp(8), theme.dp(8), theme.dp(8))
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
            contentDescription = "EVENTS  //  tap for full log"
        }
        panel.addView(text(7, theme.textBright, "EVENTS  //  tap for full log", theme.displayFace))
        // Event rows are separate TextViews: give each one its own gap so the
        // log tail stops reading as one dense block (milestone 8).
        eventLabels.forEach { label ->
            label.layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = theme.dp(4)
            }
            panel.addView(label)
        }
        val open = { showLogOverlay() }
        panel.setOnClickListener { open() }
        eventLabels.forEach { it.setOnClickListener { open() } }
        return panel
    }

    // ---- full-log overlay (DESIGN 5.2: tap the event panel, no separate tab) ----

    private fun buildLogOverlay() {
        logOverlay.visibility = View.GONE
        logOverlay.setBackgroundColor(theme.field)
        logOverlay.isVerticalScrollBarEnabled = true
        val body = column(matchParent, wrapContent).apply {
            setPadding(theme.dp(10), theme.dp(10), theme.dp(10), theme.dp(10))
        }
        val head = row(matchParent, wrapContent)
        head.addView(text(8, theme.textBright, "FULL EVENT LOG  //  newest first", theme.displayFace))
        head.addView(spacer())
        head.addView(button("CLOSE", theme.secondaryFill, theme.secondaryText) {
            logOverlay.visibility = View.GONE
        })
        body.addView(head)
        logColumn.layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
        body.addView(logColumn)
        logOverlay.addView(body)
    }

    private fun showLogOverlay() {
        logColumn.removeAllViews()
        val lines = session.fullLogNewestFirst()
        if (lines.isEmpty()) logColumn.addView(text(7, theme.textDim, "no events yet", theme.workFace))
        for (line in lines) {
            logColumn.addView(text(7, theme.text, line, theme.workFace).apply {
                layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                    topMargin = theme.dp(4)
                }
            })
        }
        logOverlay.visibility = View.VISIBLE
    }

    // ---- refresh ------------------------------------------------------------------

    private fun refresh() {
        val t = session.telemetry()
        gridView.world = session.currentWorld
        // Accessibility: the canvas grid carries a concise per-refresh state
        // line so the run surface is usable with touch explored off (9.5 gate).
        gridView.contentDescription =
            "MISSION GRID " + (if (session.finished) "finished" else "running") +
                " mission " + "%02d".format(mission.id) +
                " tick " + t.tick + " of " + t.timeLimit +
                " rover x " + t.x + " y " + t.y +
                " hp " + t.hp + " heat " + t.heat + " water " + t.water +
                " fire distance " + t.fireDist + " civilians " + t.civCount
        tickLine.text = "TICK ${t.tick} / ${t.timeLimit}"
        tickLine.contentDescription = tickLine.text
        statusLine.text = when {
            session.status == robofight.fireline.RunStatus.SUCCESS ->
                "MISSION SUCCESS  TICK ${t.tick}"
            session.failureReason != null ->
                "MISSION FAILURE ${session.failureReason?.name}  TICK ${t.tick}"
            session.status == robofight.fireline.RunStatus.FAILURE ->
                "MISSION FAILURE  TICK ${t.tick}"
            session.guardReached -> "TICK GUARD REACHED"
            else -> if (running) "RUNNING" else "PAUSED"
        }
        statusLine.contentDescription = statusLine.text
        statusLine.setTextColor(
            if (session.status != null) theme.textBright else theme.textDim,
        )

        vmRegisters.text = "A ${t.a}   X ${t.x}   Y ${t.y}   SP ${t.spHex}   PC ${t.pcHex}"
        vmFlags.text = "FLAGS  ${flag(t.flagC, "C")}  ${flag(t.flagZ, "Z")}  ${flag(t.flagN, "N")}  ${flag(t.flagV, "V")}"
        vmLastOp.text = "last: ${t.lastOp}"
        vmInstruction.text = (if (t.executing) "exec: " else "next: ") +
            t.instructionText + (if (t.instructionLine > 0) "   @line ${t.instructionLine}" else "")
        for (i in contextLabels.indices) {
            val entry = t.contextLines.getOrNull(i)
            contextLabels[i].text = entry?.let { (line, source) ->
                (if (line == t.instructionLine) ">" else " ") + "%04d  $source".format(line)
            } ?: ""
        }

        telemetryLabels.getValue("FIRE_DIST").text = "FIRE_DIST  ${t.fireDist}"
        telemetryLabels.getValue("FIRE_LEVEL").text = "FIRE_LEVEL  ${t.fireLevel}"
        telemetryLabels.getValue("CIV_COUNT").text = "CIV_COUNT  ${t.civCount}"
        telemetryLabels.getValue("WATER").text = "WATER  ${t.water} / ${MissionSession.WATER_LIMIT}"
        telemetryLabels.getValue("HP").text = "HP  ${t.hp} / ${MissionSession.HP_LIMIT}"
        telemetryLabels.getValue("HEAT").text = "HEAT  ${t.heat} / ${MissionSession.HEAT_LIMIT}  //  OVERLOAD > ${MissionSession.HEAT_THRESHOLD}"
        telemetryLabels.getValue("SHIELD").text = "SHIELD  ${t.shieldTicks}"
        telemetryLabels.getValue("WIND").text = "WIND  ${t.windLabel}"
        telemetryLabels.getValue("MISSION").text = "MISSION  ${t.missionBitsHex}"
        heatBar.value = t.heat
        heatBar.max = MissionSession.HEAT_LIMIT
        heatBar.threshold = MissionSession.HEAT_THRESHOLD

        val events = session.currentEvents(EVENT_SLOTS)
        for (i in eventLabels.indices) {
            eventLabels[i].text = events.getOrNull(i) ?: ""
            eventLabels[i].setTextColor(if (i == 0 && events.isNotEmpty()) theme.textBright else theme.text)
        }
    }

    private fun flag(on: Boolean, name: String): String = if (on) "$name!" else name.lowercase()

    // ---- view builders (house style) --------------------------------------------

    private fun button(label: String, fill: Int, textColor: Int, onClick: () -> Unit): TextView =
        text(8, textColor, label, theme.displayFace).apply {
            background = theme.panelDrawable(fill = fill, stroke = fill)
            setPadding(theme.dp(8), theme.dp(5), theme.dp(8), theme.dp(5))
            gravity = Gravity.CENTER
            contentDescription = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
        }

    private fun chipRow(chips: List<String>): View {
        val row = row(matchParent, wrapContent).apply { setPadding(0, theme.dp(4), 0, theme.dp(4)) }
        for (chip in chips) {
            row.addView(text(12, theme.text, chip.replace('_', ' '), theme.workFace).apply {
                background = theme.panelDrawable(fill = theme.panel2, stroke = theme.border)
                setPadding(theme.dp(4), theme.dp(2), theme.dp(4), theme.dp(2))
                contentDescription = chip
            })
        }
        return row
    }

    private fun text(sizeSp: Int, color: Int, value: String = "", face: Typeface = theme.displayFace): TextView =
        TextView(activity).apply {
            text = value
            setTextColor(color)
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            includeFontPadding = false
            // Stacked telemetry / status lines need explicit leading (font
            // padding is off); wrapped lines only - single rows are unaffected.
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
        private const val CONTROL_BAR_DP = 52
        private const val HEAT_BAR_DP = 10
        private const val EVENT_SLOTS = 4
        private const val CONTEXT_SLOTS = 5
        private const val LEGEND_SP = 6
        private const val LEGEND =
            "1-4 FIRE   R ROVER   * PACKET   C CIV   E EXIT   W REFILL   F FUEL   P ASSET   # WALL   . FLOOR"
        private val TELEMETRY_ORDER =
            listOf("FIRE_DIST", "FIRE_LEVEL", "CIV_COUNT", "WATER", "HP", "HEAT", "SHIELD", "WIND", "MISSION")
        private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
