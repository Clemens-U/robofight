package robofight.fireline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Gate for the W3 render surface of the §12a D4 full loop: the pacing row
 * (`SPEED 1/5/20/50`, `PANIC`), the share block (`LINK` + `COPY` + `DOWNLOAD .ASM`
 * as its own full-width block under TELEMETRY, §15.2a), the editor (textarea
 * replacing the source pane, line-level assembler errors) and the §8 library block.
 *
 * What these tests protect is the §15.4 single-occurrence rule and §15.6 item 4
 * (exactly one scrollable box): every control the frozen layout puts in the one
 * control block must appear exactly once in the rendered shell, and the editor
 * must replace the source pane rather than sit next to it. The strings asserted
 * here are the golden master's own wording (`run_controls()` of
 * `docs/web/gen_web_mockups.py`).
 */
class W3RenderTest {

    private val model = ConsoleModel()

    private fun render(state: ConsoleUiState = ConsoleUiState.DEFAULT, missionId: Int = 2): String =
        ConsoleRender.renderConsoleHtml(model, model.openMission(missionId), state)

    private fun countOf(haystack: String, needle: String): Int {
        var index = 0
        var hits = 0
        while (true) {
            index = haystack.indexOf(needle, index)
            if (index < 0) return hits
            hits++
            index += needle.length
        }
    }

    @Test
    fun runControlsAppearExactlyOnceInTheControlBlock() {
        val html = render()
        for (id in listOf(ConsoleRender.ID_RUN, ConsoleRender.ID_STEP, ConsoleRender.ID_RESET)) {
            assertEquals(1, countOf(html, "id=\"$id\""), "duplicate control id $id")
        }
        // The control block is the firmware column's; the pacing row is a plain row, not
        // a control block, and carries no run row (11 f/g).
        val firmRegion = html.substringAfter("<div class=\"pairfirm\">")
        assertEquals(1, countOf(firmRegion, "<div class=\"consolecontrols\""), "second control block")
        // §15.4: no second run bar anywhere on the page.
        assertEquals(1, countOf(html, "<div class=\"runrow\""), "second run row")
    }

    /** §15.2a (W3 record 11 a with 11 e): the RF-8 STATE band belongs to the firmware
     *  column, directly under the controls and above the assembler note, at that
     *  column's width; the shell keeps exactly two readout bands. */
    @Test
    fun stateBandMovesIntoTheFirmwareColumnUnderTheControls() {
        val html = render()
        assertEquals(2, countOf(html, "<div class=\"fields\">"), "the shell still renders two readout bands")
        val firmStart = html.indexOf("<div class=\"pairfirm\">")
        val stateBandAt = html.indexOf("<div class=\"fields\"><h4>" + ConsoleRender.STATE_BAND_TITLE)
        val telemetryAt = html.indexOf("<div class=\"fields\"><h4>" + ConsoleRender.TELEMETRY_BAND_TITLE)
        val firmCol = html.substring(firmStart, telemetryAt)
        val worldCol = html.substringAfter("<div class=\"pairworld\">").substringBefore("<div class=\"pairfirm\">")
        assertTrue(firmStart in 0 until stateBandAt, "no STATE band inside the firmware column")
        assertTrue(stateBandAt < telemetryAt, "TELEMETRY must follow the firmware column's band")
        assertTrue(stateBandAt > html.indexOf("<div class=\"consolecontrols\""), "STATE band above the controls")
        assertTrue(html.indexOf("id=\"${ConsoleRender.ID_ASSEMBLER_NOTE}\"") > stateBandAt,
            "the assembler note sits under the STATE band (11 e)")
        assertTrue(firmCol.contains(ConsoleRender.STATE_BAND_TITLE), "STATE band missing from the firmware column region")
        assertTrue(!worldCol.contains(ConsoleRender.STATE_BAND_TITLE), "STATE band rendered in the world column")
    }

    /** W3 record 11 d, f and g: the `next:`/`exec:` preview line is gone, the EVENT LOG
     *  list is gone, and the `SPEED`/`PANIC` row is a plain unboxed row of the firmware
     *  column under the run row, still inside the shell's two readout bands. */
    @Test
    fun nextPreviewAndEventLogAreGoneAndPacingIsAPlainRowInTheFirmwareColumn() {
        val html = render()
        assertEquals(-1, html.indexOf("id=\"fl-next\""), "the next:/exec: preview line still renders")
        assertEquals(-1, html.indexOf("<ul class=\"events\""), "the EVENT LOG list still renders")
        assertEquals(-1, html.indexOf(">EVENT LOG<"), "the EVENT LOG heading still renders")
        assertEquals(1, countOf(html, "<div class=\"pacing\">"), "the pacing row must render exactly once")
        assertEquals(2, countOf(html, "<div class=\"fields\">"), "the pacing row is not a readout band")
        val firm = html.substringAfter("<div class=\"pairfirm\">")
        val controlsAt = firm.indexOf("<div class=\"consolecontrols\"")
        val pacingAt = firm.indexOf("<div class=\"pacing\">")
        val stateBandAt = firm.indexOf("<div class=\"fields\"><h4>" + ConsoleRender.STATE_BAND_TITLE)
        assertTrue(pacingAt >= 0, "pacing row not inside the firmware column (11 g)")
        assertTrue(pacingAt > controlsAt, "the pacing row sits under the run row")
        assertTrue(pacingAt < stateBandAt, "the pacing row sits above the RF-8 STATE band")
        assertEquals(-1, html.indexOf("<div class=\"fields pacing\">"), "the bordered pacing block is gone (11 g)")
        assertEquals(1, countOf(html, "<b class=\"speedlabel\">SPEED</b>"), "speed wording")
        assertEquals(1, countOf(html, ">ticks per second<"), "speed unit wording (14)")
        assertEquals(1, countOf(html, "<b class=\"paniclabel\">PANIC</b>"), "panic label")
        assertEquals(1, countOf(html, "<span class=\"panichint\">uncapped, no speed limit</span>"),
            "panic wording of 15.4 kept in the hint cell")
        assertEquals(1, countOf(html, "id=\"${ConsoleRender.ID_PANIC}\""), "panic button")
        // W3 records 14 and 15: two nowrap lines. Line 1 carries the four choice cells in fixed
        // order plus the filler cell, line 2 is its own row and carries only the PANIC controls.
        assertEquals(1, countOf(html, "<div class=\"inforow\">"), "the SPEED line")
        assertEquals(1, countOf(html, "<div class=\"inforow pacingpanic\">"), "the PANIC line")
        assertEquals(1, countOf(html, "<span class=\"gap fill\" id=\"${ConsoleRender.ID_PACING_FILL}\">"),
            "filler cell")
        assertEquals(4, countOf(html, "id=\"${ConsoleRender.ID_SPEED_PREFIX}"), "speed cells")
        val rowStart = html.indexOf("<div class=\"pacing\"><div class=\"inforow\">")
        val fillAt = html.indexOf("<span class=\"gap fill\"")
        val row = html.substring(rowStart, fillAt)
        var at = -1
        for (token in listOf(
                "<b class=\"speedlabel\">SPEED</b>",
                "id=\"${ConsoleRender.speedButtonId(1)}\"",
                "id=\"${ConsoleRender.speedButtonId(5)}\"",
                "id=\"${ConsoleRender.speedButtonId(20)}\"",
                "id=\"${ConsoleRender.speedButtonId(50)}\"",
                "id=\"${ConsoleRender.ID_SPEED_UNIT}\"")) {
            val found = row.indexOf(token)
            assertTrue(found > at, "SPEED line order broken at $token: $row")
            at = found
        }
        assertEquals(-1, row.indexOf(ConsoleRender.ID_PANIC), "the PANIC controls are not on the SPEED line")
        val panicRow = html.substringAfter("<div class=\"inforow pacingpanic\">")
        assertEquals(-1, panicRow.indexOf(ConsoleRender.ID_SPEED_PREFIX), "the SPEED choices are not on the PANIC line")
    }

    @Test
    fun speedRowCarriesTheGoldenMasterChoicesAndOneSelectedChip() {
        val html = render()
        assertTrue(html.contains("<b class=\"speedlabel\">SPEED</b>"), html)
        // W3 record 14: the chosen cell is a button cell again (so all four are equal width and
        // clickable) and carries the master's SELECTED wording plus aria-pressed="true".
        assertTrue(
            html.contains(
                "<button type=\"button\" class=\"chip btn on\" id=\"${ConsoleRender.speedButtonId(5)}\" " +
                    "aria-pressed=\"true\">5<small>SELECTED</small></button>",
            ),
            html,
        )
        assertEquals(1, countOf(html, "SELECTED</small>"), "speed chips render more than one selection")
        assertEquals(1, countOf(html, "aria-pressed=\"true\""), "selection is not also marked elsewhere")
        for (speed in listOf(1, 20, 50)) {
            assertTrue(html.contains("id=\"${ConsoleRender.speedButtonId(speed)}\""), "speed $speed not selectable")
        }
        // The selected choice is the only cell carrying the selection mark, and the id match is
        // anchored on the attribute value because `speedButtonId(5)` is a prefix of
        // `speedButtonId(50)`.
        assertEquals(1, countOf(html, "id=\"${ConsoleRender.speedButtonId(5)}\" aria-pressed=\"true\""),
            "the selection mark appears more than once")
    }

    @Test
    fun panicRowCarriesTheGoldenMasterWordingAndToggles() {
        val html = render()
        val infoRow = html.substringAfter("<div class=\"inforow pacingpanic\">").substringBefore("<div class=\"flags")
        assertTrue(infoRow.contains("<b class=\"paniclabel\">PANIC</b>"), "A label: $infoRow")
        assertTrue(infoRow.contains("<span class=\"panichint\">uncapped, no speed limit</span>"), "A wording: $infoRow")
        assertTrue(infoRow.contains("id=\"${ConsoleRender.ID_PANIC}\""), "B button: $infoRow")
        assertTrue(infoRow.contains(">PANIC</button>"), "C off label: $infoRow")
        val on = render(ConsoleUiState(panic = true))
        val onRow = on.substringAfter("<div class=\"inforow\">").substringBefore("<div class=\"flags")
        val onTail = onRow.substring(onRow.indexOf("uncapped"))
        val onTailSafe = onTail.replace("<", "[<").replace(">", ">]")
        assertTrue(onTail.contains(ConsoleRender.panicButtonId(true)), "D on id: $onTailSafe")
        assertTrue(onTail.contains(">PANIC ON</button>"), "E on label: $onTailSafe")
        // While PANIC is on, no speed chip claims to be selected.
        assertFalse(onRow.contains("SELECTED</small>"), "F selection: $onRow")
        assertFalse(onRow.contains("aria-pressed=\"true\""), "F selection mark while PANIC is on: $onRow")
    }

    @Test
    fun shareRowCarriesLinkCopyAndDownloadOnce() {
        val run = model.openMission(2, model.missions.first { it.id == 2 }.referenceSource, "CORRIDOR-REF.ASM")
        val state = ConsoleUiState(shareLink = Permalink.fragmentFor(run.mission, run.firmwareName, run.session.firmwareSource))
        val html = ConsoleRender.renderConsoleHtml(model, run, state)
        assertTrue(html.contains("<span class=\"link\"><b>LINK</b> <code>#run=FLW1-"), html)
        assertEquals(1, countOf(html, ConsoleRender.ID_COPY), "duplicate COPY")
        assertEquals(1, countOf(html, ConsoleRender.ID_DOWNLOAD), "duplicate DOWNLOAD")
        assertTrue(html.contains("DOWNLOAD .ASM"), html)
        // §8: the payload is a fragment, never a query string.
        assertFalse(html.contains("?run="), html)
    }

    /** §15.2a (W3 record 11 c): LINK is a full-width block of its own under
     *  TELEMETRY, not a line of the control block, and it still occurs once. */
    @Test
    fun shareLinkRendersAsItsOwnFullWidthBlockUnderTelemetry() {
        val run = model.openMission(2, model.missions.first { it.id == 2 }.referenceSource, "CORRIDOR-REF.ASM")
        val state = ConsoleUiState(shareLink = Permalink.fragmentFor(run.mission, run.firmwareName, run.session.firmwareSource))
        val html = ConsoleRender.renderConsoleHtml(model, run, state)
        val console = html.substringAfter("<div class=\"console\"").substringBefore("<div class=\"fields\">")
        assertFalse(console.contains("<span class=\"link\">"), "LINK still inside the monitor panel / control block")
        val telemetry = html.indexOf("<h4>" + ConsoleRender.TELEMETRY_BAND_TITLE + "</h4>")
        val link = html.indexOf("<div class=\"fields linkblock\">")
        assertTrue(telemetry >= 0 && link > telemetry, "LINK block sits after the TELEMETRY band")
        assertTrue(html.indexOf("<div class=\"cols\">") > link, "LINK block sits before the explanation tables")
        assertEquals(1, countOf(html, "<div class=\"fields linkblock\">"), "duplicate LINK block")
        assertEquals(1, countOf(html, "<span class=\"link\">"), "duplicate share row")
        // Nothing shared: no block, no empty band.
        assertFalse(render().contains("linkblock"), "LINK block rendered with no share link")
    }

    @Test
    fun editorReplacesTheSourcePaneInsteadOfAddingASecondScrollableBox() {
        val state = ConsoleUiState(editDraft = EditDraft("loop:  WAIT\n       JMP    loop", "MY-LOOP.ASM"))
        val html = render(state)
        assertTrue(html.contains("<textarea class=\"source editor\" id=\"${ConsoleRender.ID_EDITOR_SOURCE}\""), html)
        assertFalse(html.contains("<pre class=\"source\""), "editor left the frozen pane on screen: two scroll boxes")
        assertTrue(html.contains("id=\"${ConsoleRender.ID_APPLY}\""), html)
        assertTrue(html.contains("id=\"${ConsoleRender.ID_EDITOR_CANCEL}\""), html)
        assertFalse(html.contains("id=\"${ConsoleRender.ID_EDIT}\""), "EDIT and APPLY both offered")
        // §15.3 frozen pane height is inherited by the editor.
        assertEquals(-1, html.indexOf("--src-height:"),
            "the shell carries no inline pane height; console.html sizes it from the grid anchor (11 g)")
    }

    @Test
    fun editorShowsLineLevelAssemblerErrorsFromTheRealAssembler() {
        val draft = EditDraft("loop:  WAIT\n       JMP    ", "BAD.ASM")
        val problems = WebLibrary.sourceProblems(draft.source)
        assertTrue(problems.isNotEmpty(), "the assembler must refuse this draft")
        val state = ConsoleUiState(editDraft = draft.copy(problems = problems))
        val html = render(state)
        assertTrue(html.contains("class=\"flags asmerr\""), html)
        assertTrue(html.contains("line "), problems.toString())
        assertTrue(html.contains("id=\"${ConsoleRender.ID_ASSEMBLER_NOTE}\""), html)
    }

    @Test
    fun cleanDraftInvitesApplyInsteadOfNamingALine() {
        val source = model.missions.first { it.id == 2 }.referenceSource
        assertTrue(WebLibrary.sourceProblems(source).isEmpty())
        val html = render(ConsoleUiState(editDraft = EditDraft(source, "CORRIDOR-REF.ASM")))
        assertTrue(html.contains("editor: the draft assembles, APPLY opens it as a run."), html)
    }

    @Test
    fun libraryBlockListsStoredFirmwaresWithLoadAndDelete() {
        val store = WebLibrary.saved(WebLibrary.EMPTY, "MY-LOOP.ASM", "loop:  WAIT\n       JMP    loop")
        val html = render(ConsoleUiState(library = store))
        assertTrue(html.contains("FL:&gt; FIRMWARE LIBRARY"), html)
        assertTrue(html.contains("MY-LOOP.ASM"), html)
        assertTrue(html.contains("id=\"${ConsoleRender.libraryLoadButtonId("MY-LOOP.ASM")}\""), html)
        assertTrue(html.contains("id=\"${ConsoleRender.libraryDeleteButtonId("MY-LOOP.ASM")}\""), html)
        // The empty state names the §8 key the store lives under.
        val empty = render(ConsoleUiState(library = WebLibrary.EMPTY))
        assertTrue(empty.contains("empty. the library is browser `localStorage` under `${WebLibrary.STORAGE_KEY}`"), empty)
    }

    /** §15.2b item 3 (W3 record 18): ONE firmware target select replaces the former `into` and
     *  `load into` pair, offers every authored mission, and its button labels carry the target. */
    @Test
    fun singleFirmwareTargetSelectOffersEveryAuthoredMission() {
        val html = render()
        assertTrue(html.contains("type=\"file\" id=\"${ConsoleRender.ID_IMPORT_FILE}\""), html)
        assertTrue(html.contains("accept=\".asm,.txt\""), html)
        assertEquals(1, countOf(html, "<select id="), "expected exactly one firmware target select")
        assertTrue(html.contains("<select id=\"${ConsoleRender.ID_FIRMWARE_TARGET}\""), html)
        assertTrue(
            html.contains("<div class=\"librarytarget\">${ConsoleRender.FIRMWARE_TARGET_LABEL}<select id=\"${ConsoleRender.ID_FIRMWARE_TARGET}\""),
            html,
        )
        for (row in model.pickerRows()) {
            assertTrue(html.contains("<option value=\"${row.id}\""), "mission ${row.id} not selectable as target")
        }
        assertEquals(1, countOf(html, " selected>"), "the single select carries exactly one selected option")
        assertTrue(html.contains("<option value=\"2\" selected>02 CORRIDOR</option>"), html)
        val withSix = ConsoleUiState.DEFAULT.copy(firmwareMissionId = 6)
        assertTrue(render(withSix).contains("<option value=\"6\" selected>06 THIN WALL</option>"), "target 06 not selected")
    }

    /** W3 record 18: the LOAD and SAVE CURRENT labels name the mission they act on, so the target
     *  is visible at the button and not only in the select. */
    @Test
    fun libraryButtonsNameTheMissionTheyActOn() {
        val store = WebLibrary.saved(WebLibrary.EMPTY, "MY-LOOP.ASM", "loop:  WAIT\n       JMP    loop")
        val html = render(ConsoleUiState(library = store, firmwareMissionId = 6))
        assertTrue(html.contains(">LOAD 06</button>"), html)
        val runHtml = ConsoleRender.renderConsoleHtml(
            model,
            model.openMission(6),
            ConsoleUiState(library = store, firmwareMissionId = 6),
        )
        assertTrue(runHtml.contains(">SAVE CURRENT INTO 06</button>"), runHtml)
    }

    /** W3 record 18: every mission the console can open becomes the firmware target, including the
     *  one a share link carries. */
    @Test
    fun everyOpenMissionSelectsItselfAsFirmwareTarget() {
        for (row in model.pickerRows()) {
            val html = render(ConsoleUiState.DEFAULT.copy(firmwareMissionId = row.id), missionId = row.id)
            assertTrue(
                html.contains("<option value=\"${row.id}\" selected>"),
                "mission ${row.id} not the selected target",
            )
        }
    }

    @Test
    fun hostileFirmwareTextNeverEscapesTheRenderedPane() {
        // Hostile text goes through the EDITOR path (the same path a typed or
        // imported firmware takes), never through `openMission`: an assembling-
        // refused source must not reach a session at all.
        val hostile = "loop:  WAIT ; <script>alert(1)</script> \"quoted\"\n       JMP    loop"
        val html = render(
            ConsoleUiState(
                editDraft = EditDraft(hostile, "HOSTILE.ASM", WebLibrary.sourceProblems(hostile)),
                shareLink = "#run=FLW1-test",
            ),
        )
        assertFalse(html.contains("<script>alert"), html)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html)
        assertTrue(html.contains("&quot;quoted&quot;"), html)
        // The hostile source still round-trips losslessly through the share codec.
        val mission = model.missions.first { it.id == 2 }
        val fragment = Permalink.fragmentFor(mission, "HOSTILE.ASM", hostile)
        val decoded = Permalink.decode(fragment, WebLibrary.authoredMissionIds(model.missions))
        assertIs<Permalink.Decoded.Ok>(decoded, "hostile source must still decode")
        assertEquals(hostile, decoded.firmwareSource)
        assertEquals("HOSTILE.ASM", decoded.firmwareName)
    }
}
