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
 * `TICK n / limit` and `ROVER at x n y n facing L` reuse the wording of the shipped
 * Android MISSION surface (`MissionRunSurface.updateReadouts`) so the two containers
 * describe the same `MissionSession` state with the same words. The `next:` / `exec:`
 * preview of that surface is not rendered here: the shell dropped it (W3 record 11 d),
 * the same fact is carried by the `>` marker and the `LAST OP` / `OP TICKS LEFT` cells.
 *
 * Deliberate omissions of this shell step (recorded in docs/WEB-W3-RECORD.md):
 * the hero, `FL:> RELEASE STATUS`, the four-cell strip,
 * the privacy block and the footer chips (§15.2 items 8-10 are landing-page
 * framing, not the console panel). The 20x20 world grid and its symbol legend
 * are NOT omissions any more: `WorldGrid` renders them from the engine's own
 * `TextGrid` / `FirelineWorld` reads, which is also why `ConsoleRender` never
 * derives a glyph itself. The `kv` readout that stood in place of the grid in
 * the first shell step is gone: `TICK` already appears on the prompt line, the
 * titlebar, the objectives line and the RF-8 STATE band, and the rover position
 * now appears in the grid itself plus the `X`/`Y` cells. The `SPEED`/`PANIC`
 * row, the share `LINK` with `COPY` and `DOWNLOAD .ASM`, the editor and the
 * `localStorage` firmware library are no longer omissions either: they are the
 * §12a D4 "full loop" and render from [ConsoleUiState] (owned by `Main.kt`),
 * still without deriving any value here.
 */
object ConsoleRender {

    // ---- element ids the wasm entry point (`Main.kt`) binds handlers to ----
    const val ID_CONSOLE = "fl-console"
    const val ID_SOURCE = "fl-source"
    const val ID_RUN = "fl-btn-run"
    const val ID_STEP = "fl-btn-step"
    const val ID_RESET = "fl-btn-reset"
    const val ID_MISSIONS = "fl-missions"

    // ---- ids added with the §12a D4 full loop (pacing, share, editor, library) ----
    const val ID_STATUS = "fl-status"
    const val ID_ASSEMBLER_NOTE = "fl-asmnote"
    const val ID_SPEED_PREFIX = "fl-btn-speed-"
    const val ID_SPEED_UNIT = "fl-speed-unit"
    const val ID_PACING_FILL = "fl-pacing-fill"
    const val ID_PANIC = "fl-btn-panic"
    const val ID_PANIC_ON = "fl-btn-panic-on"
    const val ID_COPY = "fl-btn-copy"
    const val ID_DOWNLOAD = "fl-btn-download"
    const val ID_EDIT = "fl-btn-edit"
    const val ID_APPLY = "fl-btn-apply"
    const val ID_EDITOR_CANCEL = "fl-btn-editor-cancel"
    const val ID_EDITOR_SOURCE = "fl-editor-source"
    const val ID_EDITOR_NAME = "fl-editor-name"
    const val ID_SAVE = "fl-btn-save"
    const val ID_IMPORT_FILE = "fl-import-file"
    const val ID_CLEAR_LIBRARY = "fl-btn-clear-library"
    const val ID_IMPORT_MISSION = "fl-import-mission"
    const val ID_LIBRARY_TARGET = "fl-library-target"
    const val ID_LIBRARY_LIST = "fl-library-list"

    fun speedButtonId(ticksPerSecond: Int): String = ID_SPEED_PREFIX + ticksPerSecond

    /** The PANIC button names its state in its id, so a test (and the click
     *  wiring) can tell the two renderings apart without parsing the label. */
    fun panicButtonId(on: Boolean): String = if (on) ID_PANIC_ON else ID_PANIC
    fun panicLabel(on: Boolean): String = if (on) "PANIC ON" else PANIC_LABEL
    fun libraryLoadButtonId(name: String): String = "fl-btn-library-load-" + name
    fun libraryDeleteButtonId(name: String): String = "fl-btn-library-delete-" + name

    /** The `SPEED 1 / 5 / 20 / 50 ticks per second` choices of §15.4, in the
     *  frozen order. Pacing never changes engine behaviour: it only changes how
     *  often `ConsoleRun.step()` is called. */
    val SPEED_CHOICES = listOf(1, 5, 20, 50)
    const val DEFAULT_SPEED = 5

    /** `PANIC uncapped, no speed limit` of §15.4. */
    const val PANIC_LABEL = "PANIC"

    /** Which mission an IMPORT / LOAD targets when the console has not been told
     *  otherwise: the console's startup mission (2 / CORRIDOR), the mission the
     *  frozen golden master is drawn from. */
    const val DEFAULT_LIBRARY_MISSION_ID = 2

    fun cardId(missionId: Int): String = "fl-card-" + missionId
    fun openCardButtonId(missionId: Int): String = "fl-card-open-" + missionId

    /** §15.3: firmware pane height `21 * 15 + 1` = 316px (written as the rule). */
    const val SOURCE_PANE_HEIGHT_PX = 21 * 15 + 1

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
     * The whole console shell: monitor panel (with the RF-8 STATE band inside the
     * firmware column and the status line inside the world column), the TELEMETRY
     * band, the full-width LINK block, the two explanation tables and the mission
     * picker. With `run == null` only the picker renders (nothing is open yet),
     * which is the same `FL:&gt; SELECT A CALL` prompt the frozen layout uses for
     * the picker. Region order is §15.2 as amended by `docs/WEB-W3-RECORD.md` 11
     * (state band under the firmware pane, status line under the grid, LINK as its
     * own full-width block).
     */
    fun renderConsoleHtml(
        model: ConsoleModel,
        run: ConsoleRun?,
        state: ConsoleUiState = ConsoleUiState.DEFAULT,
    ): String {
        val out = StringBuilder()
        if (run != null) {
            out.append(promptLine(run))
            out.append(consolePanel(model, run, state))
            out.append(fieldsBand(TELEMETRY_BAND_TITLE, telemetryBandCells(run)))
            out.append(linkBlock(state))
            out.append(explanationColumns(run))
        }
        out.append(missionPicker(model))
        out.append(firmwareLibraryBlock(model, run, state))
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
    private fun consolePanel(model: ConsoleModel, run: ConsoleRun, state: ConsoleUiState): String {
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
        sb.append(worldColumn(run, row, state))
        sb.append(firmwareColumn(run, state))
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

    /** World column of §15.2 item 2 in the frozen order: the `WIND` line
     *  (authored mission values from `MissionRow`), the 20x20 world grid, the
     *  read-only status line directly under the grid (amendment, W3 record 11 b),
     *  the symbol legend, then the pacing block (`SPEED` / `PANIC`, amendment 11 f,
     *  the bordered block that replaced the EVENT LOG). Every cell glyph comes from
     *  `TextGrid.glyphAt` over the session's own world (`WorldGrid`). */
    private fun worldColumn(run: ConsoleRun, row: MissionRow, state: ConsoleUiState): String {
        // Statement form for the same reason as `firmwareColumn`: this chain's tail
        // carried `statusLine(run)` and `legendHtml()` and dropped them when it was
        // one long `+` chain.
        val sb = StringBuilder("<div class=\"pairworld\">")
        sb.append("<div class=\"wind\">WIND ").append(escapeHtml(row.windLabel))
            .append("&nbsp;&nbsp;FORCE ").append(row.windForce).append("</div>")
        sb.append(WorldGrid.gridHtml(run.currentWorld))
        sb.append(statusLine(run))
        sb.append(WorldGrid.legendHtml())
        // The EVENT LOG section is gone (amendment, W3 record 11 f). The world column is
        // wind, grid, status line and legend only, so the column height is the grid's.
        return sb.append("</div>").toString()
    }

    /**
     * The pacing row: the `SPEED 1 / 5 / 20 / 50 ticks per second` choices and the
     * `PANIC uncapped, no speed limit` toggle, the same controls and wording §15.4
     * defines. It has no readout band around it (W3 record 11 g) and it renders as
     * ONE grid line in the fixed order the owner asked for (W3 record 14):
     * `SPEED [1] [5] [20] [50] ticks per second ... PANIC uncapped ... [PANIC]`, the
     * grid cells alternating with two inert gap cells (`.gap`, one of them the 1fr
     * filler `ID_PACING_FILL`) so the line's width is fixed by CSS and a choice can
     * never be pushed to a second line. `ID_SPEED_UNIT` marks the unit label;
     * neither the gap cells nor the unit label is a control.
     */
    private fun pacingBlock(state: ConsoleUiState): String {
        val sb = StringBuilder("<div class=\"pacing\"><div class=\"inforow\">")
        sb.append("<b class=\"speedlabel\">SPEED</b>")
        for (speed in SPEED_CHOICES) {
            sb.append(speedChip(speed, state))
        }
        sb.append("<span class=\"speedunit\" id=\"").append(ID_SPEED_UNIT)
            .append("\">ticks per second</span>")
        sb.append("<span class=\"gap\"></span>")
        sb.append("<b class=\"paniclabel\">").append(PANIC_LABEL).append("</b>")
        sb.append("<span class=\"panichint\">uncapped, no speed limit</span>")
        sb.append(button(panicButtonId(state.panic), panicLabel(state.panic), active = state.panic))
        sb.append("<span class=\"gap fill\" id=\"").append(ID_PACING_FILL).append("\"></span>")
        sb.append("</div>")
        return sb.append("</div>").toString()
    }

    /** Firmware column: heading, the single `pre.source` pane (one scrollable
     *  box, §15.7), the one control block, the RF-8 STATE band at the column's own
     *  width, and the assembler note last (amendments, W3 record 11 a/d/e): the
     *  `next:`/`exec:` preview line is gone, the state readout sits flush with the
     *  firmware field it belongs to, and the assembler verdict reads as the tail of
     *  the whole column.
     *
     *  In edit mode the source pane is replaced by one `<textarea>` of the same
     *  frozen height, so the page still has exactly one scrollable box (§15.6
     *  item 4): the editor is the box, the pane is not rendered at the same
     *  time. */
    private fun firmwareColumn(run: ConsoleRun, state: ConsoleUiState): String {
        val lines = run.sourceLines()
        val pane = if (state.editDraft == null) {
            sourcePane(lines, run.telemetry().instructionLine)
        } else {
            editorPane(state.editDraft.source)
        }
        // Statement form with explicit appends, not one long `+` chain: the W3
        // close-out proved Kotlin 2.3.20's wasm backend folds a `+` chain whose tail
        // carries function calls (here: `fieldsBand(...)`) and silently drops those
        // operands from the emitted output. The same class as the truncated speed chip.
        val sb = StringBuilder("<div class=\"pairfirm\">")
        sb.append("<h4>FIRMWARE: ").append(escapeHtml(run.firmwareName)).append("</h4>")
        sb.append(pane)
        sb.append(controlBlock(state))
        // SPEED / PANIC as a plain row directly under the run row (W3 record 11 g): the
        // controls sit together in the firmware column, the row carries no panel box.
        sb.append(pacingBlock(state))
        sb.append(fieldsBand(STATE_BAND_TITLE, stateBandCells(run)))
        sb.append(assemblerNote(state))
        return sb.append("</div>").toString()
    }

    /** The editor surface (§12a D4): the draft source in one textarea, the
     *  store name next to it, APPLY / CANCEL under them. Line numbers are not
     *  shown while editing; they belong to the read-only pane, and APPLY returns
     *  to it. */
    private fun editorPane(draftSource: String): String =
        "<textarea class=\"source editor\" id=\"" + ID_EDITOR_SOURCE +
            "\" spellcheck=\"false\" autocomplete=\"off\" autocapitalize=\"off\" wrap=\"off\">" +
            escapeHtml(draftSource) + "</textarea>" +
            "<div class=\"editorname\"><label for=\"" + ID_EDITOR_NAME + "\">store name</label>" +
            "<input id=\"" + ID_EDITOR_NAME + "\" type=\"text\" spellcheck=\"false\" autocomplete=\"off\"" +
            " placeholder=\"NAME.ASM\"></div>"

    /** The control block, the one place §15.4 allows for `RUN` / `STEP` / `RESET`.
     *  Structure and wording are `run_controls()` of `docs/web/gen_web_mockups.py`:
     *  a `runrow` of the three commands. The editor's APPLY / CANCEL sit in the
     *  same block so no second control row appears. Two rows left this block: the
     *  share `LINK` (amendment 11 c, now the full-width block under TELEMETRY) and
     *  the `SPEED` / `PANIC` pacing row (amendment 11 f, now the pacing block in
     *  the world column). Each still occurs exactly once on the page. */
    private fun controlBlock(state: ConsoleUiState): String {
        val sb = StringBuilder("<div class=\"consolecontrols\"><div class=\"runrow\">")
        sb.append("<button type=\"button\" class=\"btn run\" id=\"").append(ID_RUN).append("\">RUN</button>")
        sb.append("<button type=\"button\" class=\"btn step\" id=\"").append(ID_STEP).append("\">STEP</button>")
        sb.append("<button type=\"button\" class=\"btn reset\" id=\"").append(ID_RESET).append("\">RESET</button>")
        if (state.editDraft == null) {
            sb.append(button(ID_EDIT, "EDIT"))
        } else {
            sb.append(button(ID_APPLY, "APPLY")).append(button(ID_EDITOR_CANCEL, "CANCEL"))
        }
        sb.append("</div>")
        sb.append(statusNote(state))
        return sb.append("</div>").toString()
    }

    /** `<span><b>SPEED</b> ... <span class="chip on">5 SELECTED</span> ...` of the
     *  golden master, with each choice clickable: the selected one keeps the
     *  master's `chip on` look, the others render as `.chip.btn` buttons. */
    private fun speedChip(ticksPerSecond: Int, state: ConsoleUiState): String =
        if (ticksPerSecond == state.speed && !state.panic) {
            // The chosen cell keeps the master's `SELECTED` wording (§15.4) in the markup, plus
            // the same state as an aria-pressed attribute, but the word itself is not painted:
            // the yellow fill marks the choice, and the four cells stay exactly equal width so
            // the row can never wrap. W3 record 14.
            "<button type=\"button\" class=\"chip btn on\" id=\"" + speedButtonId(ticksPerSecond) +
                "\" aria-pressed=\"true\">" + ticksPerSecond + "<small>SELECTED</small></button>"
        } else {
            "<button type=\"button\" class=\"chip btn\" id=\"" + speedButtonId(ticksPerSecond) +
                "\" aria-pressed=\"false\">" + ticksPerSecond + "</button>"
        }

    /** `LINK <code>#run=FLW1-…</code> COPY DOWNLOAD .ASM` of §15.4. The link text
     *  is this exact console state (§8: mission, seed override, firmware name and
     *  source), and it lives in the URL fragment only. */
    private fun shareRow(state: ConsoleUiState): String =
        if (state.shareLink == null) {
            ""
        } else {
            "<span class=\"link\"><b>LINK</b> <code>" + escapeHtml(state.shareLink) + "</code>" +
                button(ID_COPY, "COPY") + button(ID_DOWNLOAD, "DOWNLOAD .ASM") + "</span>"
        }

    /** The share row as its own full-width block below the TELEMETRY band
     *  (amendment, W3 record 11 c): the permalink is a 500+ character payload, and
     *  as a control-block line it was squeezed into the firmware column. Empty
     *  while no run state is shared, so no empty band appears. */
    private fun linkBlock(state: ConsoleUiState): String {
        val row = shareRow(state)
        if (row.isEmpty()) return ""
        return "<div class=\"fields linkblock\">" + row + "</div>"
    }

    /** Assembler note: the clean `0 errors` fact of a run that reached here, or
     *  the editor's line-level refusal (§12a D4). */
    private fun assemblerNote(state: ConsoleUiState): String {
        val draft = state.editDraft
        if (draft == null) {
            return draftNote(state.asmNote)
        }
        if (draft.problems.isEmpty()) {
            return "<div class=\"flags asmerr\" id=\"" + ID_ASSEMBLER_NOTE + "\">editor: the draft assembles," +
                " APPLY opens it as a run.</div>"
        }
        return "<div class=\"flags asmerr\" id=\"" + ID_ASSEMBLER_NOTE + "\">assembler: " +
            draft.problems.joinToString(" | ") { escapeHtml(it) } + "</div>"
    }

    private fun draftNote(asmNote: String): String =
        "<div class=\"flags asmnote\" id=\"" + ID_ASSEMBLER_NOTE + "\">" + asmNote + "</div>"

    /** One-line feedback for COPY / SAVE / IMPORT / library actions. It never
     *  carries firmware content, only what the console did. */
    private fun statusNote(state: ConsoleUiState): String =
        if (state.statusLine.isEmpty()) "" else div("flags flstatus", ID_STATUS, escapeHtml(state.statusLine))

    private fun button(id: String, label: String, active: Boolean = false): String {
        // Written as a branch, not as `a + if (c) x else y + b`: the W3 test run
        // showed Kotlin 2.3.20's wasm backend folding that one long `+` chain
        // wrong for the `active` case (the tag came out truncated after the
        // class attribute, no `id`, no label).
        val cls = if (active) "chip btn on" else "chip btn"
        return "<button type=\"button\" class=\"" + cls + "\" id=\"" + id + "\">" + label + "</button>"
    }

    /** One `<pre class="source">`, one `<span>` per source line, `<u>` line
     *  numbers, `> ` marker plus `now` class on the line the monitor is on
     *  (`TelemetrySnapshot.instructionLine`). Every source line goes through
     *  [escapeHtml]: this is where a hostile firmware line could otherwise
     *  break the page. */
    private fun sourcePane(sourceLines: List<String>, currentLine: Int): String {
        val sb = StringBuilder("<pre class=\"source\" id=\"").append(ID_SOURCE)
            // No inline pane height: the shell sizes the pane from the grid anchor
            // (console.html --pane-h, W3 record 11 g). SOURCE_PANE_HEIGHT_PX stays the
            // frozen 15px-ratio reference the mockups use.
            .append("\">")
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
     *  engine-v2 best-run label the console cards carry (`BestRunLabel.label`). */
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

    /** The golden master's assembler note (`run_controls` / the `asmnote` div of
     *  `console_pair`), split so the console can print its own engine numbers
     *  (`firmwareBytes`, source line count, "0 errors") without rewriting the
     *  frozen cheat-sheet wording. The `>` and `|` characters are stored as
     *  rendered HTML because they are part of the frozen wording. */
    const val DEFAULT_ASSEMBLER_NOTE_TAIL =
        "the &gt; marker is the instruction the execution monitor is on; the source pane scrolls while it " +
            "runs. cheat sheet: base ports 0-11 | FIRELINE ports 16-30 | " +
            "no-target sentinels DIST 0 / ANGLE 255 / LEVEL 0"

    /** The note exactly as the frozen golden master prints it (mission 02 /
     *  CORRIDOR reference firmware: 75 bytes, 25 source lines). */
    const val DEFAULT_ASSEMBLER_NOTE =
        "assembler: 0 errors, 75 bytes, 25 source lines. " + DEFAULT_ASSEMBLER_NOTE_TAIL

    // ---------------------------------------------------------- §8 firmware library

    /**
     * The `localStorage` library block (§8 "Storage", §12a D4): stored firmwares
     * with LOAD / DELETE, SAVE CURRENT, IMPORT .ASM, the mission an imported or
     *  stored firmware is loaded into, and DELETE LIBRARY (the §8 in-page
     *  removal of the whole store). It renders below the mission picker: §15.2
     *  freezes the ten regions of the monitor panel, and the library is a console
     *  tool, not one of those regions, so it sits in the tools tail with the
     *  picker rather than being inserted into the frozen order.
     */
    private fun firmwareLibraryBlock(model: ConsoleModel, run: ConsoleRun?, state: ConsoleUiState): String {
        val sb = StringBuilder()
        sb.append("<div class=\"prompt\" style=\"margin-top:1.6rem\">FL:&gt; FIRMWARE LIBRARY</div>")
        sb.append("<div class=\"librarycontrols\">")
        if (run != null) {
            sb.append("<button type=\"button\" class=\"chip btn\" id=\"").append(ID_SAVE).append("\">SAVE CURRENT</button>")
        }
        sb.append("<label class=\"importlabel\">IMPORT .ASM")
        sb.append("<input type=\"file\" id=\"").append(ID_IMPORT_FILE).append("\" accept=\".asm,.txt\">")
        sb.append("</label>")
        sb.append("<label class=\"importlabel\">into")
        sb.append("<select id=\"").append(ID_IMPORT_MISSION).append("\">")
        model.pickerRows().forEach { row ->
            val selected = if (row.id == state.importMissionId) " selected" else ""
            sb.append("<option value=\"").append(row.id).append("\"").append(selected).append(">")
                .append(decPad(row.id, 2)).append(" ").append(escapeHtml(row.title)).append("</option>")
        }
        sb.append("</select></label>")
        sb.append("<button type=\"button\" class=\"chip btn\" id=\"").append(ID_CLEAR_LIBRARY).append("\">DELETE LIBRARY</button>")
        sb.append("</div>")
        sb.append("<div class=\"librarytarget\">load into")
        // aria-label because the visible "load into" text sits outside the control, so a
        // screen reader reading the select alone would only hear the mission options.
        sb.append("<select id=\"").append(ID_LIBRARY_TARGET).append("\" aria-label=\"load library entry into mission\">")
        model.pickerRows().forEach { row ->
            val selected = if (row.id == state.libraryTargetMissionId) " selected" else ""
            sb.append("<option value=\"").append(row.id).append("\"").append(selected).append(">")
                .append(decPad(row.id, 2)).append(" ").append(escapeHtml(row.title)).append("</option>")
        }
        sb.append("</select></div>")
        sb.append("<div class=\"library\" id=\"").append(ID_LIBRARY_LIST).append("\">")
        val entries = state.library.entries
        if (entries.isEmpty()) {
            sb.append("<div class=\"flags\">empty. the library is browser `localStorage` under `")
                .append(WebLibrary.STORAGE_KEY)
                .append("`: nothing leaves the browser, and clearing site data removes it (§8).</div>")
        } else {
            entries.forEach { entry ->
                sb.append("<div class=\"libentry\">")
                sb.append("<span class=\"libname\">").append(escapeHtml(entry.name)).append("</span>")
                sb.append("<span class=\"libmeta\">").append(entry.source.lines().size).append(" source lines")
                    .append(if (entry.name == state.library.selectedName) "  selected" else "").append("</span>")
                sb.append("<button type=\"button\" class=\"mini\" id=\"").append(libraryLoadButtonId(entry.name)).append("\">LOAD</button>")
                sb.append("<button type=\"button\" class=\"mini\" id=\"").append(libraryDeleteButtonId(entry.name)).append("\">DELETE</button>")
                sb.append("</div>")
            }
        }
        return sb.append("</div>").toString()
    }
}

/**
 * The shell's own state, passed into the render layer so `ConsoleRender` can
 * draw the pacing row, the share row, the editor and the library without owning
 * or deriving anything: `Main.kt` owns these values, the render layer formats
 * them (docs/FIRELINE-WEB.md §13). [asmNote] is stored as rendered HTML for the
 * same reason the golden master stores its cheat sheet that way.
 */
data class ConsoleUiState(
    val speed: Int = ConsoleRender.DEFAULT_SPEED,
    val panic: Boolean = false,
    /** The `#run=FLW1-…` fragment of the currently open run (§8), null while no
     *  run is open or the codec refused to encode it. */
    val shareLink: String? = null,
    /** The open editor draft, null when the read-only source pane renders. */
    val editDraft: EditDraft? = null,
    /** The decoded `localStorage` store (§8); never trusted, always re-checked. */
    val library: WebLibrary.Store = WebLibrary.EMPTY,
    /** Mission a LOAD button opens its firmware in. */
    val libraryTargetMissionId: Int = ConsoleRender.DEFAULT_LIBRARY_MISSION_ID,
    /** Mission an IMPORT .ASM is checked and opened in. */
    val importMissionId: Int = ConsoleRender.DEFAULT_LIBRARY_MISSION_ID,
    /** One-line feedback about the last COPY / SAVE / IMPORT / library action. */
    val statusLine: String = "",
    val asmNote: String = ConsoleRender.DEFAULT_ASSEMBLER_NOTE,
) {
    companion object {
        val DEFAULT = ConsoleUiState()
    }
}

/** A firmware being edited: what is typed, the store name typed with it, and the
 *  engine's own refusals (`WebInputLimits` first, then the RF-8 assembler with
 *  its `line n:` prefixes, docs/FIRELINE-WEB.md §12a D4). */
data class EditDraft(
    val source: String,
    val name: String,
    val problems: List<String> = emptyList(),
)
