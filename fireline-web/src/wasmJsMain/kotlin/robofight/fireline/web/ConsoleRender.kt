package robofight.fireline.web

import robofight.fireline.RunStatus
import robofight.isa.decPad

/**
 * The pure render layer of the FIRELINE WEB CONSOLE: `ConsoleModel` plus an
 * optional [ConsoleRun] in, one HTML fragment out. No DOM, no JS interop, no
 * state of its own - which is exactly what makes the shell assertable from
 * `wasmJsTest` in Node (no browser needed) and what keeps the §13 drift risk at
 * zero: this layer only formats values that `ConsoleModel`, `ConsoleRun` or the
 * engine's `TelemetrySnapshot` produced. It computes no mission or run value.
 *
 * Wording and structure are copied from the frozen v1 layout, not invented:
 * `docs/FIRELINE-WEB.md` §15.2 (region order), §15.3 (numeric locks), §15.4
 * (single-occurrence rule) and the golden master
 * `docs/web/fl-web-5-playable-landing.html`, whose markup comes from
 * `docs/web/gen_web_mockups.py` (`console_pair`, `mission_header`,
 * `objectives_html`, `source_pane`, `run_controls`, `fields_band`,
 * `vm_fields_html`, `telemetry_fields_html`, `statusbar`, `kv_rows`,
 * `ports_meaning_html`, `stats_html`, `choose_calls_html`). The two band cell
 * lists below are byte-equal to `VM_CELLS` / `TELEMETRY_CELLS` in
 * `docs/web/verify_layout_v1.py`, and `ConsoleRenderTest` re-asserts them.
 *
 * `TICK n / limit`, `ROVER at x n y n facing L` and the `next:` / `exec:`
 * preview reuse the wording of the shipped Android MISSION surface
 * (`MissionRunSurface.updateReadouts`) so the two containers describe the same
 * `MissionSession` state with the same words.
 *
 * Deliberate omissions of this shell step (recorded in docs/WEB-W3-RECORD.md):
 * the 20x20 world grid and the symbol legend (their glyph and layer rules are
 * §15.3/§15.6 geometry acceptance, and re-implementing them here would be a
 * second glyph authority); the `SPEED`/`PANIC` row, the share `LINK`, `COPY`
 * and `DOWNLOAD .ASM` (no pacing timer and no `localStorage`/`#run=` permalink
 * until the next W3 step); the hero, `FL:> RELEASE STATUS`, the four-cell strip,
 * the privacy block and the footer chips (§15.2 items 8-10 are landing-page
 * framing, not the console panel).
 */
object ConsoleRender {

    // ---- element ids the wasm entry point (`Main.kt`) binds handlers to ----
    const val ID_CONSOLE = "fl-console"
    const val ID_EVENTS = "fl-events"
    const val ID_SOURCE = "fl-source"
    const val ID_NEXT = "fl-next"
    const val ID_RUN = "fl-btn-run"
    const val ID_STEP = "fl-btn-step"
    const val ID_RESET = "fl-btn-reset"
    const val ID_MISSIONS = "fl-missions"

    fun cardId(missionId: Int): String = "fl-card-" + missionId
    fun openCardButtonId(missionId: Int): String = "fl-card-open-" + missionId

    /** §15.3: firmware pane height `21 * 15 + 1` = 316px (written as the rule). */
    const val SOURCE_PANE_HEIGHT_PX = 21 * 15 + 1

    /** Visible EVENT LOG window. The engine's own tail bound is
     *  `MissionSession.EVENT_TAIL` (24 lines); the console shows the newest 8. */
    const val EVENT_LOG_LINES = 8

    /** §15.2 item 3 frozen cell list (`verify_layout_v1.py` VM_CELLS). */
    val STATE_BAND_CELLS = listOf(
        "A", "X", "Y", "SP", "PC", "LAST OP", "FLAGS Z", "FLAGS N", "FLAGS C",
        "OP TICKS LEFT", "VM FAULT", "TICK",
    )

    /** §15.2 item 4 frozen cell list (`verify_layout_v1.py` TELEMETRY_CELLS). */
    val TELEMETRY_BAND_CELLS = listOf(
        "FIRE_DIST", "FIRE_ANGLE", "FIRE_LEVEL", "LOCAL_FIRE", "SMOKE",
        "CIV_DIST", "CIV_ANGLE", "CIV_COUNT", "EXIT_DIST", "EXIT_ANGLE",
        "WIND_DIR", "WIND_FORCE",
    )

    /** Band titles exactly as the golden master renders their `<h4>`. */
    const val STATE_BAND_TITLE = "RF-8 STATE (REGISTERS, FLAGS, RUN IDENTITY)"
    const val TELEMETRY_BAND_TITLE = "TELEMETRY (FIRELINE READOUT PORTS)"

    /** `FL:&gt; SELECT A CALL` - the picker prompt of §15.2 item 7. */
    const val PICKER_PROMPT = "FL:&gt; SELECT A CALL"

    // ------------------------------------------------------------- entry point
    /**
     * The whole console shell: monitor panel, both readout bands, the status
     * line, the two explanation tables and the mission picker. With `run == null`
     * only the picker renders (nothing is open yet), which is the same
     * `FL:&gt; SELECT A CALL` prompt the frozen layout uses for the picker.
     */
    fun renderConsoleHtml(model: ConsoleModel, run: ConsoleRun?): String {
        val out = StringBuilder()
        if (run != null) {
            out.append(promptLine(run))
            out.append(consolePanel(model, run))
            out.append(fieldsBand(STATE_BAND_TITLE, stateBandCells(run)))
            out.append(fieldsBand(TELEMETRY_BAND_TITLE, telemetryBandCells(run)))
            out.append(statusLine(run))
            out.append(explanationColumns(run))
        }
        out.append(missionPicker(model))
        return out.toString()
    }

    /**
     * HTML escaping for text the console did not author - the firmware source is
     * user-supplied code, and a `<script>` in a comment line must render as
     * text. Same rule as `escape()` in `docs/web/gen_web_mockups.py`: `&` first,
     * then `<`, `>`, `"`.
     */
    fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // -------------------------------------------------------------- §15.2 item 1
    /** `FL:> LIVE MONITOR // MISSION 02 / CORRIDOR // TICK 46` (mission label and
     *  tick both from `ConsoleRun`). */
    private fun promptLine(run: ConsoleRun): String =
        div("prompt", ID_PROMPT_ATTR, "FL:&gt; LIVE MONITOR // " + escapeHtml(run.missionLabel) + " // TICK " + run.tick)

    private const val ID_PROMPT_ATTR = "fl-prompt"

    // -------------------------------------------------------------- §15.2 item 2
    private fun consolePanel(model: ConsoleModel, run: ConsoleRun): String {
        val row = model.row(run.missionId)
            ?: error("console run for mission ${run.missionId} has no picker row")
        val sb = StringBuilder()
        sb.append("<div class=\"console\" id=\"").append(ID_CONSOLE).append("\">")
        sb.append("<div class=\"titlebar\"><span>")
            .append(escapeHtml("MISSION " + decPad(row.id, 2) + "://" + row.title)).append("</span>")
            .append("<span>T+0000").append(decPad(run.tick, 4)).append("</span></div>")
        sb.append("<div class=\"consolebody\">")
        sb.append(missionHeader(row, run.firmwareName))
        sb.append(objectivesLine(run, row))
        sb.append("<div class=\"pair\">")
        sb.append(worldColumn(run, row))
        sb.append(firmwareColumn(run))
        sb.append("</div>")
        sb.append("</div></div>")
        return sb.toString()
    }

    /** Field order copied from `mission_header()`, including its two seed
     *  positions (`firmware  seed  WIND  seed  limit`) - the master's own shape,
     *  kept because §15.1 makes the master the visual truth. */
    private fun missionHeader(row: MissionRow, firmwareName: String): String =
        "<div class=\"mhead\">\n <div><span class=\"mid\">" + escapeHtml(row.missionLabel) + "</span>" +
            "<div class=\"brief\">" + escapeHtml(row.briefing) + "</div></div>\n <div class=\"mparams\">" +
            escapeHtml(firmwareName) + "&nbsp;&nbsp;" + row.seed + "&nbsp;&nbsp;" +
            escapeHtml(row.windLabel) + " " + row.windForce + "&nbsp;&nbsp;seed " + row.seed +
            "&nbsp;&nbsp;" + (row.timeLimit?.toString() ?: "MAX") + "</div>\n</div>"

    /** Objective chips (`NAME done` / `NAME failed` / `NAME pending`) from
     *  `ConsoleRun.objectiveChips()` (i.e. `ResultModel.objectiveChips` over the
     *  session's own summary) plus the engine's own tick/time-limit chip. */
    private fun objectivesLine(run: ConsoleRun, row: MissionRow): String {
        val failure = run.status() == RunStatus.FAILURE
        val chips = run.objectiveChips().map { (label, met) ->
            val state = if (met) " done" else if (failure) " failed" else " pending"
            val cls = if (met) "ok" else if (failure) "bad" else "pend"
            "<span class=\"" + cls + "\">" + escapeHtml(label) + state + "</span>"
        }
        val tickChip = "<span class=\"pend\">tick " + run.tick + "/" + (row.timeLimit?.toString() ?: "MAX") + "</span>"
        return "<div class=\"objline\">" + (chips + tickChip).joinToString("&nbsp;&nbsp;") + "</div>"
    }

    /** World column: `WIND` line (authored mission values from `MissionRow`), a
     *  `kv` readout of the engine's own tick and rover state in place of the
     *  grid, then the EVENT LOG. */
    private fun worldColumn(run: ConsoleRun, row: MissionRow): String {
        val t = run.telemetry()
        return "<div class=\"pairworld\">" +
            "<div class=\"wind\">WIND " + escapeHtml(row.windLabel) + "&nbsp;&nbsp;FORCE " + row.windForce + "</div>" +
            kvTable(
                listOf(
                    "TICK" to (t.tick.toString() + " / " + t.timeLimit),
                    "ROVER" to ("at x " + t.roverX + " y " + t.roverY + " facing " + t.roverFacing),
                ),
            ) +
            "<h4 style=\"margin-top:.7rem\">EVENT LOG</h4>" +
            "<ul class=\"events\" id=\"" + ID_EVENTS + "\">" + eventLog(run) + "</ul>" +
            "</div>"
    }

    /**
     * `NNN <op> | <events>` lines exactly as the engine wrote them
     * (`MissionSession.eventLog` via `ConsoleRun.logTail`), newest first, newest
     * item carrying the `newest` class. The mockup splits the tick number into a
     * `<u>` column; the console renders the engine's line verbatim so the log
     * text on screen is byte-identical to the log text the app prints.
     */
    private fun eventLog(run: ConsoleRun): String {
        val lines = run.logTail(EVENT_LOG_LINES)
        // `events_html` fallback: `<li class="newest"><u>tick</u>no event on this tick</li>`
        if (lines.isEmpty()) return "<li class=\"newest\"><u>" + decPad(run.tick, 3) + "</u>no event on this tick</li>"
        return lines.mapIndexed { index, line ->
            "<li class=\"" + (if (index == 0) "newest" else "") + "\">" + escapeHtml(line) + "</li>"
        }.joinToString("")
    }

    /** Firmware column: heading, the single `pre.source` pane (one scrollable
     *  box, §15.7), the `next:`/`exec:` preview, the one control block, and the
     *  assembler note. */
    private fun firmwareColumn(run: ConsoleRun): String {
        val lines = run.sourceLines()
        return "<div class=\"pairfirm\"><h4>FIRMWARE: " + escapeHtml(run.firmwareName) + "</h4>" +
            sourcePane(lines, run.telemetry().instructionLine) +
            "<div class=\"flags\" id=\"" + ID_NEXT + "\">" + escapeHtml(run.nextEventPreview()) + "</div>" +
            "<div class=\"consolecontrols\"><div class=\"runrow\">" +
            "<button type=\"button\" class=\"btn run\" id=\"" + ID_RUN + "\">RUN</button>" +
            "<button type=\"button\" class=\"btn step\" id=\"" + ID_STEP + "\">STEP</button>" +
            "<button type=\"button\" class=\"btn reset\" id=\"" + ID_RESET + "\">RESET</button>" +
            "</div></div>" +
            // "0 errors" is a fact, not a claim: `Mission.descriptorForSource`
            // refuses a source that does not assemble (`check(r.ok)`), so a run
            // that reached this renderer assembled cleanly.
            "<div class=\"flags asmnote\">assembler: 0 errors, " + run.firmwareBytes() + " bytes, " +
            lines.size + " source lines. the &gt; marker is the instruction the execution monitor is on; " +
            "the source pane scrolls while it runs. cheat sheet: base ports 0-11 | FIRELINE ports 16-30 | " +
            "no-target sentinels DIST 0 / ANGLE 255 / LEVEL 0</div>" +
            "</div>"
    }

    /** One `<pre class="source">`, one `<span>` per source line, `<u>` line
     *  numbers, `> ` marker plus `now` class on the line the monitor is on
     *  (`TelemetrySnapshot.instructionLine`). Every source line goes through
     *  [escapeHtml]: this is where a hostile firmware line could otherwise
     *  break the page. */
    private fun sourcePane(sourceLines: List<String>, currentLine: Int): String {
        val sb = StringBuilder("<pre class=\"source\" id=\"").append(ID_SOURCE)
            .append("\" style=\"--src-height:").append(SOURCE_PANE_HEIGHT_PX).append("px\">")
        sourceLines.forEachIndexed { index, line ->
            val lineNo = index + 1
            val now = currentLine > 0 && lineNo == currentLine
            sb.append("<span").append(if (now) " class=\"now\"" else "").append("><u>")
                .append(decPad(lineNo, 2)).append("</u> ")
                .append(if (now) "&gt;" else "")
                .append(escapeHtml(line)).append("</span>")
        }
        return sb.append("</pre>").toString()
    }

    // -------------------------------------------------------------- §15.2 items 3-4
    private fun stateBandCells(run: ConsoleRun): List<Pair<String, String>> {
        val t = run.telemetry()
        return listOf(
            "A" to t.a.toString(),
            "X" to t.x.toString(),
            "Y" to t.y.toString(),
            "SP" to t.spHex,
            "PC" to t.pcHex,
            "LAST OP" to run.lastEvent(),
            "FLAGS Z" to flag(t.flagZ),
            "FLAGS N" to flag(t.flagN),
            "FLAGS C" to flag(t.flagC),
            "OP TICKS LEFT" to run.opTicksLeft().toString(),
            "VM FAULT" to (if (run.vmFault()) "yes" else "no"),
            "TICK" to run.tick.toString(),
        )
    }

    private fun telemetryBandCells(run: ConsoleRun): List<Pair<String, String>> =
        run.readoutPorts().map { (label, value) -> label to value.toString() }

    private fun flag(set: Boolean): String = if (set) "1" else "0"

    private fun fieldsBand(title: String, cells: List<Pair<String, String>>): String =
        "<div class=\"fields\"><h4>" + escapeHtml(title) + "</h4><div class=\"frow\">" +
            cells.joinToString("") { (label, value) ->
                "<div class=\"fcell\"><span>" + escapeHtml(label) + "</span><b>" + escapeHtml(value) + "</b></div>"
            } +
            "</div></div>"

    // -------------------------------------------------------------- §15.2 items 5-6
    /** Read-only status line, the single place the state fingerprint and the
     *  four vitals appear (§15.4). */
    private fun statusLine(run: ConsoleRun): String {
        val t = run.telemetry()
        return "<div class=\"statusbar\">\n <div class=\"vitals\"><b>VITALS</b> HP " + t.hp +
            "&nbsp;&nbsp;WATER " + t.water + "&nbsp;&nbsp;HEAT " + t.heat +
            "&nbsp;&nbsp;SHIELD " + t.shieldTicks + "</div>\n <div class=\"finger\"><b>STATE</b> tick " +
            run.tick + "&nbsp;&nbsp;fingerprint " + escapeHtml(run.fingerprintHex()) + "</div>\n</div>"
    }

    private fun explanationColumns(run: ConsoleRun): String =
        "<div class=\"cols\"><div class=\"col\"><h4>WHAT THE PORTS MEAN</h4>" + kvTable(PORTS_MEANING) +
            "</div><div class=\"col\"><h4>RUN STATS</h4>" + kvTable(run.runStats()) + "</div></div>"

    /** Port semantics copied from `ports_meaning_html()` (static text, no run
     *  value in it). */
    private val PORTS_MEANING = listOf(
        "16 FIRE_DIST" to "cells to the nearest fire along the heading, 0 when nothing burns in range",
        "17 FIRE_ANGLE" to "fire offset in 10-degree steps -180..180, 255 when outside the +-90 cone",
        "18 FIRE_LEVEL" to "intensity of the fire at your current heading, 0 when nothing burns",
        "19 TARGET_KIND" to "1 FIRE, 2 CIVILIAN, 3 REFILL, 4 EXIT",
        "20 TARGET_DIST" to "cells to that target",
        "21 TARGET_ANGLE" to "its offset in 10-degree steps",
        "22 TARGET_LEVEL" to "its level: fire intensity, civilian health, refill amount",
        "24 EXIT_DIST" to "cells to the exit, through walls",
        "25 EXIT_ANGLE" to "direction to the exit in 10-degree steps",
        "26 WIND_DIR" to "0-7 compass direction the wind blows toward",
        "27 WIND_FORCE" to "0-4, how hard the wind pushes fire and smoke",
        "28 CIV_COUNT" to "civilians still waiting in the building",
        "29 CIV_ANGLE" to "direction to the nearest waiting civilian",
        "30 MISSION" to "objective bits: fire out, civilians, water, exit",
    )

    // -------------------------------------------------------------- §15.2 item 7
    private fun missionPicker(model: ConsoleModel): String =
        "<div class=\"prompt\" style=\"margin-top:1.6rem\">" + PICKER_PROMPT + "</div>" +
            "<div class=\"missions\" id=\"" + ID_MISSIONS + "\">" +
            model.pickerRows().joinToString("") { missionCard(it) } + "</div>"

    /** One card. `h3`, the briefing paragraph and the `meta2` fact line are the
     *  master's shape (`choose_calls_html()`); the second `meta2` line is the
     *  engine-v1 best-run label the console cards carry (`BestRunLabel.label`). */
    private fun missionCard(row: MissionRow): String =
        "<article class=\"mcard\" id=\"" + cardId(row.id) + "\">" +
            "<h3>" + escapeHtml(row.missionLabel) + "</h3><p>" + escapeHtml(row.briefing) + "</p>" +
            "<div class=\"meta2\">seed " + row.seed + "&nbsp;&nbsp;WIND " + escapeHtml(row.windLabel) + " " +
            row.windForce + "&nbsp;&nbsp;WATER " + row.startingWater + "&nbsp;&nbsp;limit " +
            (row.timeLimit ?: row.tickLimit) + " ticks</div>" +
            "<div class=\"meta2\">" + escapeHtml(row.bestRun.label) + "</div>" +
            "<button type=\"button\" class=\"mini\" id=\"" + openCardButtonId(row.id) + "\">OPEN IN CONSOLE</button>" +
            "</article>"

    // ------------------------------------------------------------------- shared
    /** `kv_rows()` of the generator: label-first rows, value column right-aligned. */
    private fun kvTable(pairs: List<Pair<String, String>>): String =
        "<table class=\"kv\">" + pairs.joinToString("") { (key, value) ->
            "<tr><td class=\"k\">" + escapeHtml(key) + "</td><td class=\"v\">" + escapeHtml(value) + "</td></tr>"
        } + "</table>"

    private fun div(cssClass: String, id: String, content: String): String =
        "<div class=\"" + cssClass + "\" id=\"" + id + "\">" + content + "</div>"
}
