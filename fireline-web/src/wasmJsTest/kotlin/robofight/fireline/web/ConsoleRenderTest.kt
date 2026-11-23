package robofight.fireline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import robofight.fireline.RunStatus

/**
 * Headless gate for the console SHELL layer: `ConsoleRender.renderConsoleHtml`
 * is a pure function, so every layout and readout assertion below runs in Node
 * against real engine output - no DOM, no browser, no npm.
 *
 * Fixture provenance (the same committed sources as `ConsoleModelTest`):
 *  - `docs/web/fixtures/parity_matrix.json` / `.tsv`: the five `N-reference`
 *    best-run anchors (`$245faa6d`, `$d3ea4711`, `$01663660`, `$acf46562`,
 *    `$ba8e443d`) and the `1-failing` `never shoots` anchor
 *    (tick 60, TIME_EXPIRED, `7cbfe49993c17318`).
 *  - `docs/web/fixtures/snapshots.json`: `02-ref-final` (tick 102, `MOVE (2/2)`,
 *    `d3ea47119fe3b101`), `02-ref-t46` (tick 46, `JMP 286`, `A 4`, PC `$11E`).
 *  - `docs/WEB-W1-RECORD.md` §9 `STEP x 30` row: `TICK 30 / 120`, `A 6`,
 *    `PC $12D`, `last: MOVE (1/2)`, `FIRE_DIST 6`, `FIRE_LEVEL 55`. Tick 30 is
 *    pinned by that committed device row, not by `snapshots.json`
 *    (docs/WEB-W3-RECORD.md §6 correction 1). The row's `TICK 30 / 120` is the
 *    Android MISSION wording: the frozen web panel carries the same tick as
 *    `tick n/limit` on the objectives line plus the `TICK` cell of RF-8 STATE,
 *    because §15.2 item 2 has no kv row in the world column - the grid stands
 *    there now (`WorldGridTest`).
 *  - Layout gates: `docs/FIRELINE-WEB.md` §15.2 / §15.4 / §15.7 and
 *    `docs/web/verify_layout_v1.py` (band cell lists, single-occurrence rule,
 *    one source pane, no VITALS inside TELEMETRY, house no-em/en-dash copy rule).
 *
 * Every expected value in this file is a committed anchor or a value read back
 * from `ConsoleModel` / `ConsoleRun`; none of them is produced by the render
 * layer itself, which is the point of the split.
 */
class ConsoleRenderTest {

    private val model = ConsoleModel()

    /** Strip tags and collapse whitespace, so readout assertions can name the
     *  label+value pairs the console shows (`FIRE_DIST 6`) instead of pinning
     *  markup bytes. Raw-markup assertions below pin the band cell shape too. */
    private fun plainText(html: String): String =
        html.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Band markup of one `<div class="fields">` block, in document order. */
    private fun bandCells(html: String, bandIndex: Int): List<Pair<String, String>> {
        val block = html.split("<div class=\"fields\">")[bandIndex + 1]
            .split("</div></div>")[0]
        return Regex("<span>([^<]+)</span><b>([^<]*)</b>").findAll(block)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()
    }

    /** §15.2 items 3 and 4: the frozen cell lists, and nothing else, per band. */
    private fun renderedBands(html: String): Pair<List<String>, List<String>> =
        bandCells(html, 0).map { it.first } to bandCells(html, 1).map { it.first }

    /**
     * Fixture: `parity_matrix.json` rows `1-reference` .. `6-reference`
     * (final_hash), asserted in live wasm by `ConsoleModelTest`. The picker the
     * shell renders must carry all six cards and all six anchors.
     */
    @Test
    fun renderedPickerCarriesAllSixMissionCardsWithTheirCommittedBestRunAnchors() {
        val html = ConsoleRender.renderConsoleHtml(model, null)
        val text = plainText(html)

        for (row in model.pickerRows()) {
            assertTrue(html.contains("<article class=\"mcard\" id=\"fl-card-" + row.id + "\">"),
                "card markup for ${row.missionLabel} (Missions.ALL order)")
            assertTrue(text.contains(row.missionLabel), "card title ${row.missionLabel} (MissionRow.missionLabel)")
            assertTrue(text.contains("seed " + row.seed), "card seed ${row.seed} (snapshots ${row.id}-* seed)")
            assertTrue(text.contains("limit " + row.timeLimit + " ticks"),
                "card limit ${row.timeLimit} (snapshots time_limit)")
            assertTrue(text.contains(row.bestRun.fingerprintHex),
                "card best-run fingerprint ${row.bestRun.fingerprintHex} (parity_matrix ${row.id}-reference)")
            assertTrue(text.contains("OPEN IN CONSOLE"), "card action label (golden master choose_calls_html)")
        }

        // The six committed engine-v2 anchors, spelled out rather than derived.
        val anchors = listOf("\$245faa6d", "\$d3ea4711", "\$01663660", "\$acf46562", "\$ba8e443d", "\$61fe3d4c")
        anchors.forEach { anchor ->
            assertTrue(text.contains(anchor), "picker carries $anchor (parity_matrix 1..6-reference)")
        }
        assertEquals(6, Regex("<article class=\"mcard\"").findAll(html).toList().size, "six cards, no more")
        assertTrue(html.contains("FL:&gt; SELECT A CALL"), "picker prompt wording (§15.2 item 7, golden master)")
    }

    /** §15.2 items 3-4 frozen cell lists, copied from `verify_layout_v1.py`. */
    @Test
    fun renderedBandsMatchTheFrozenCellListsAndTheirMarkupShape() {
        val run = model.openMission(2)
        val html = ConsoleRender.renderConsoleHtml(model, run)
        val (stateCells, telemetryCells) = renderedBands(html)

        assertEquals(ConsoleRender.STATE_BAND_CELLS, stateCells, "RF-8 STATE cell list (verify_layout_v1 VM_CELLS)")
        assertEquals(ConsoleRender.TELEMETRY_BAND_CELLS, telemetryCells, "TELEMETRY cell list (verify_layout_v1 TELEMETRY_CELLS)")
        assertEquals(12, stateCells.size, "12 STATE cells (§15.2 item 3)")
        assertEquals(12, telemetryCells.size, "12 TELEMETRY cells (§15.2 item 4)")

        assertTrue(html.contains("<div class=\"fcell\"><span>A</span><b>0</b></div>"),
            "band cell shape `<span>LABEL</span><b>VALUE</b>` at tick 0 (verify_layout_v1 band regex)")
        assertTrue(html.contains("<h4>RF-8 STATE (REGISTERS, FLAGS, RUN IDENTITY)</h4>"), "STATE band title (golden master)")
        assertTrue(html.contains("<h4>TELEMETRY (FIRELINE READOUT PORTS)</h4>"), "TELEMETRY band title (golden master)")
        assertFalse(Regex("<span>PORT \\d").containsMatchIn(html), "no PORT-number prefixes in a band (§15.2 item 4)")
        assertFalse(html.contains("<span>VITALS"), "no VITALS row inside TELEMETRY (§15.7)")
    }

    /**
     * Fixture: `docs/WEB-W1-RECORD.md` §9 `STEP x 30` row (`TICK 30 / 120`,
     * `A 6`, `PC $12D`, `last: MOVE (1/2)`, `FIRE_DIST 6`, `FIRE_LEVEL 55`),
     * reproduced live in wasm by `ConsoleModelTest.mission02Tick30StopMatchesTheCommittedStepByStepState`.
     */
    @Test
    fun mission02RenderAtTick30ShowsTheCommittedStepByStepReadout() {
        val run = model.openMission(2)
        repeat(30) { run.step() }
        val html = ConsoleRender.renderConsoleHtml(model, run)
        val text = plainText(html)

        assertTrue(text.contains("MISSION 02 / CORRIDOR // TICK 30"),
            "prompt line shows the live tick (§15.2 item 1)")
        assertTrue(text.contains("tick 30/120"),
            "objectives line carries the live tick in the golden master's own wording (`tick n/limit`, " +
                "gen_web_mockups objectives_html); the world column's `TICK n / limit` kv row was the shell " +
                "placeholder for the grid and §15.2 item 2 has no such row - the grid replaced it")
        assertTrue(text.contains("A 6"), "RF-8 STATE A at tick 30 (WEB-W1-RECORD §9: A 6)")
        assertTrue(text.contains("PC $12D"), "RF-8 STATE PC at tick 30 (WEB-W1-RECORD §9: PC $12D)")
        assertTrue(text.contains("MOVE (1/2)"), "LAST OP at tick 30 (WEB-W1-RECORD §9: last: MOVE (1/2))")
        assertTrue(text.contains("FIRE_DIST 6"), "TELEMETRY FIRE_DIST at tick 30 (WEB-W1-RECORD §9)")
        assertTrue(text.contains("FIRE_LEVEL 55"), "TELEMETRY FIRE_LEVEL at tick 30 (WEB-W1-RECORD §9)")

        // Same values pinned as raw band cells, so the shape is asserted too.
        assertTrue(html.contains("<div class=\"fcell\"><span>A</span><b>6</b></div>"), "A cell (snapshots 02-* register form)")
        assertTrue(html.contains("<div class=\"fcell\"><span>PC</span><b>\$12D</b></div>"), "PC cell (TelemetrySnapshot.pcHex)")
        assertTrue(html.contains("<div class=\"fcell\"><span>LAST OP</span><b>MOVE (1/2)</b></div>"), "LAST OP cell (snapshots 02-ref-t12 vm.last_op shape)")
        assertTrue(html.contains("<div class=\"fcell\"><span>FIRE_DIST</span><b>6</b></div>"), "FIRE_DIST cell (WEB-W1-RECORD §9)")
        assertTrue(html.contains("<div class=\"fcell\"><span>FIRE_LEVEL</span><b>55</b></div>"), "FIRE_LEVEL cell (WEB-W1-RECORD §9)")
        assertTrue(html.contains("<div class=\"fcell\"><span>SP</span><b>\$0BF</b></div>"), "SP cell (snapshots 02-* vm.registers.sp = 191)")
        assertTrue(html.contains("<div class=\"fcell\"><span>OP TICKS LEFT</span><b>1</b></div>"),
            "MOVE (1/2) is still in flight at tick 30 (Vm.lastOp position, not the ISA cost table)")
        assertTrue(html.contains("<div class=\"fcell\"><span>TICK</span><b>30</b></div>"), "TICK cell")
        assertTrue(html.contains("<div class=\"grid\">"), "world column renders the 20x20 grid in place of the old kv readout (§15.2 item 2)")
        assertTrue(html.indexOf("class=\"legend\"") < html.indexOf("<h4 style=\"margin-top:.7rem\">EVENT LOG"),
            "the symbol legend sits between grid and EVENT LOG (§15.2 item 2)")
        assertFalse(text.contains("MISSION SUCCESS"), "nothing finished at tick 30 (snapshots 02-ref-t46 is mid-run)")

        // Control case: at fixture-pinned tick 46 the op in flight is `JMP 286`,
        // a single-tick op, so OP TICKS LEFT is 0 (`snapshots 02-ref-t46`).
        val at46 = model.openMission(2).apply { repeat(46) { step() } }
        assertTrue(plainText(ConsoleRender.renderConsoleHtml(model, at46)).contains("OP TICKS LEFT 0"),
            "no multi-tick op in flight at tick 46 (snapshots 02-ref-t46 vm.last_op = JMP 286)")
    }

    /**
     * Fixture: `snapshots.json 02-ref-final` / `parity_matrix 2-reference`:
     * tick 102, SUCCESS, `MOVE (2/2)`, `$d3ea4711`, log tail
     * `102 MOVE (2/2) | MISSION SUCCESS`.
     */
    @Test
    fun finishedMission02RenderCarriesTheCommittedFinishAnchor() {
        val run = model.openMission(2)
        run.run()
        val html = ConsoleRender.renderConsoleHtml(model, run)
        val text = plainText(html)

        assertEquals(102, run.tick, "run driven to the committed finish (parity_matrix 2-reference)")
        assertTrue(text.contains("MISSION 02 / CORRIDOR // TICK 102"), "prompt line at the finish")
        assertTrue(html.contains("102 MOVE (2/2) | MISSION SUCCESS"),
            "EVENT LOG line verbatim from MissionSession.eventLog (snapshots 02-ref-final recent_events)")
        assertTrue(html.contains("fingerprint \$d3ea4711"),
            "status line fingerprint (snapshots 02-ref-final d3ea47119fe3b101)")
        assertTrue(html.contains("<span class=\"ok\">EXTINGUISH_ALL done</span>"), "objective chip done (ResultModel.objectiveChips)")
        assertTrue(html.contains("<span class=\"ok\">REACH_EXIT done</span>"), "objective chip done (snapshots 02-ref-final objectives)")
        assertTrue(text.contains("tick 102/120"), "objline tick chip (snapshots 02-ref-t12 time_limit 120)")
        assertTrue(html.contains("<div class=\"fcell\"><span>LAST OP</span><b>MOVE (2/2)</b></div>"), "LAST OP at the finish (snapshots 02-ref-final vm.last_op)")
        assertTrue(html.contains("<div class=\"fcell\"><span>FIRE_DIST</span><b>0</b></div>"), "FIRE_DIST 0 at the finish (snapshots 02-ref-final ports)")
        assertEquals(RunStatus.SUCCESS, run.status(), "engine status at the finish")
    }

    /**
     * Fixture: `snapshots.json 01-never-final` / `parity_matrix 1-failing`
     * (`never shoots`): tick 60, TIME_EXPIRED, `JMP 256`, `$7cbfe499`.
     */
    @Test
    fun mission01NeverShootsRenderCarriesTimeExpiredAndItsAnchor() {
        val run = model.openFailingFirmware(1, "never shoots")
        run.run()
        val html = ConsoleRender.renderConsoleHtml(model, run)
        val text = plainText(html)

        assertTrue(text.contains("TIME_EXPIRED"), "failure reason name reaches the page (parity_matrix 1-failing)")
        assertTrue(html.contains("060 JMP 256 | MISSION FAILURE TIME_EXPIRED"),
            "EVENT LOG line verbatim (snapshots 01-never-final recent_events)")
        assertTrue(html.contains("<div class=\"fcell\"><span>LAST OP</span><b>JMP 256</b></div>"), "LAST OP cell (snapshots 01-never-final vm.last_op)")
        assertTrue(html.contains("fingerprint \$7cbfe499"), "status line fingerprint (snapshots 01-never-final 7cbfe49993c17318)")
        assertTrue(html.contains("<span class=\"bad\">EXTINGUISH_ALL failed</span>"), "objective chip failed on FAILURE (objectives_html shape)")
        assertTrue(text.contains("tick 60/60"), "objline tick chip at the time limit (snapshots 01-never-* time_limit 60)")
        assertTrue(html.contains("MISSION 01://NOZZLE CHECK"),
            "titlebar wording (console_pair `MISSION %02d://%s`, gen_web_mockups.py line 675)")
        assertTrue(html.contains("never shoots.ASM"), "firmware name of the authored failing variant (openFailingFirmware)")
    }

    /**
     * The firmware pane renders user-supplied text. A `<script>` tag inside a
     * firmware comment line must come out as inert text and must not appear raw
     * anywhere in the shell's HTML.
     */
    @Test
    fun firmwareSourceTextIsEscapedSoItCannotBreakThePage() {
        val hostile = "; <script>alert(1)</script>\nloop:  WAIT\nJMP    loop"
        val run = model.openMission(1, firmwareSource = hostile, firmwareName = "HOSTILE.ASM")
        val html = ConsoleRender.renderConsoleHtml(model, run)

        assertFalse(html.contains("<script"), "raw `<script` is gone from the rendered shell (escapeHtml)")
        assertFalse(html.contains("alert(1)</script>"), "no closed tag smuggled through (escapeHtml)")
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"),
            "the line renders as escaped text inside the source pane")
        assertTrue(html.contains("<pre class=\"source\" id=\"fl-source\" style=\"--src-height:316px\">"),
            "single source pane with the §15.3 height lock (21 * 15 + 1)")
        assertTrue(html.contains("<span><u>01</u> ; &lt;script&gt;alert(1)&lt;/script&gt;</span>"),
            "line number, then the escaped source text (source_pane shape)")
        assertTrue(html.contains("HOSTILE.ASM"), "pane heading carries the firmware name")
    }

    /** §15.4 / §15.7 gates, re-run against the shell's own output. */
    @Test
    fun singleOccurrenceAndCopyGatesHoldForTheRenderedShell() {
        val run = model.openMission(2)
        repeat(30) { run.step() }
        val html = ConsoleRender.renderConsoleHtml(model, run)

        assertEquals(1, Regex(">RUN<").findAll(html).toList().size, "RUN appears once (§15.4 single control block)")
        assertEquals(1, Regex(">STEP<").findAll(html).toList().size, "STEP appears once (§15.4)")
        assertEquals(1, Regex(">RESET<").findAll(html).toList().size, "RESET appears once (§15.4)")
        assertEquals(1, Regex("<pre class=\"source\"").findAll(html).toList().size, "one source pane, none nested (§15.7)")
        assertEquals(1, Regex("fingerprint").findAll(html).toList().size, "one state fingerprint, on the status line (§15.4)")
        assertEquals(2, Regex("<div class=\"fields\">").findAll(html).toList().size, "two readout bands only (§15.2 items 3-4)")
        // §15.2a shell deviation (W3 record 11): the STATE band is the firmware column's
        // band, the status line is the world column's grid footer, and the state band is
        // the last thing in the firmware column, under the assembler note.
        val firmStart = html.indexOf("<div class=\"pairfirm\">")
        val stateBandAt = html.indexOf("<div class=\"fields\"><h4>" + ConsoleRender.STATE_BAND_TITLE)
        val telemetryAt = html.indexOf("<div class=\"fields\"><h4>" + ConsoleRender.TELEMETRY_BAND_TITLE)
        val firmCol = html.substring(firmStart, telemetryAt)
        val worldCol = html.substringAfter("<div class=\"pairworld\">").substringBefore("<div class=\"pairfirm\">")
        assertTrue(worldCol.contains("class=\"statusbar\""), "status line inside the world column")
        assertTrue(worldCol.indexOf("class=\"statusbar\"") > worldCol.indexOf("class=\"grid\""),
            "status line sits after the grid inside the world column (15.2a 2)")
        assertTrue(worldCol.indexOf("class=\"statusbar\"") < worldCol.indexOf("class=\"legend\""),
            "status line sits before the symbol legend (15.2a 2)")
        assertTrue(stateBandAt in firmStart until telemetryAt, "RF-8 STATE band inside the firmware column (15.2a 1)")
        assertTrue(stateBandAt > html.indexOf("<div class=\"consolecontrols\""),
            "state band sits under the control block (15.2a 1)")
        assertTrue(stateBandAt > html.indexOf("id=\"fl-asmnote\""),
            "state band sits under the assembler note (15.2a 1)")
        assertFalse(html.contains("<div class=\"fields linkblock\">"),
            "LINK block rendered although nothing is shared")
        assertFalse(html.contains("<a class=\"btn"), "no anchor run controls: the shell uses real buttons (§15.4 labels unchanged)")
        assertEquals(3, Regex("<button type=\"button\" class=\"btn ").findAll(html).toList().size, "RUN/STEP/RESET are three buttons")
        assertFalse(Regex("[\u2014\u2013]").containsMatchIn(html), "no em dash or en dash in console copy (verify_layout_v1 copy rule)")
        assertEquals(1, Regex("VITALS").findAll(html).toList().size, "vitals appear once, on the status line (§15.4)")
    }

    /** With nothing open, the shell renders only the picker (§15.2 item 7). */
    @Test
    fun renderWithNoRunOpenShowsThePickerAndNoRunSurface() {
        val html = ConsoleRender.renderConsoleHtml(model, null)
        assertFalse(html.contains("class=\"console\""), "no monitor panel before a mission is opened")
        assertFalse(html.contains("class=\"fields\""), "no readout bands before a mission is opened")
        assertFalse(html.contains(">RUN<"), "no run controls before a mission is opened")
        assertTrue(html.contains("FL:&gt; SELECT A CALL"), "picker prompt present")
        assertEquals(6, Regex("<article class=\"mcard\"").findAll(html).toList().size, "six cards")
    }
}
