package robofight.fireline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Gate for the W3 render surface of the §12a D4 full loop: the pacing row
 * (`SPEED 1/5/20/50`, `PANIC`), the share row (`LINK` + `COPY` + `DOWNLOAD .ASM`),
 * the editor (textarea replacing the source pane, line-level assembler errors)
 * and the §8 library block.
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
        assertEquals(1, countOf(html, "<div class=\"consolecontrols\">"), "second control block")
        // §15.4: no second run bar anywhere on the page.
        assertEquals(1, countOf(html, "<div class=\"runrow\">"), "second run row")
    }

    @Test
    fun speedRowCarriesTheGoldenMasterChoicesAndOneSelectedChip() {
        val html = render()
        assertTrue(html.contains("<b>SPEED</b> 1 / 5 / 20 / 50 ticks per second"), html)
        assertTrue(html.contains("<span class=\"chip on\">5 SELECTED</span>"), html)
        assertEquals(1, countOf(html, "SELECTED</span>"), "speed chips render more than one selection")
        for (speed in listOf(1, 20, 50)) {
            assertTrue(html.contains("id=\"${ConsoleRender.speedButtonId(speed)}\""), "speed $speed not selectable")
        }
        // The selected choice renders as the master's `chip on` span, not a button:
        // `speedButtonId(5)` is a prefix of `speedButtonId(50)`, so assert the exact
        // attribute value rather than a substring.
        assertFalse(Regex("id=\"${ConsoleRender.speedButtonId(5)}\"").containsMatchIn(html), html)
    }

    @Test
    fun panicRowCarriesTheGoldenMasterWordingAndToggles() {
        val html = render()
        val infoRow = html.substringAfter("<div class=\"inforow\">").substringBefore("<div class=\"flags")
        assertTrue(infoRow.contains("<b>PANIC</b> uncapped, no speed limit"), "A wording: $infoRow")
        assertTrue(infoRow.contains("id=\"${ConsoleRender.ID_PANIC}\""), "B button: $infoRow")
        assertTrue(infoRow.contains(">PANIC</button>"), "C off label: $infoRow")
        val on = render(ConsoleUiState(panic = true))
        val onRow = on.substringAfter("<div class=\"inforow\">").substringBefore("<div class=\"flags")
        val onTail = onRow.substring(onRow.indexOf("uncapped"))
        val onTailSafe = onTail.replace("<", "[<").replace(">", ">]")
        assertTrue(onTail.contains(ConsoleRender.panicButtonId(true)), "D on id: $onTailSafe")
        assertTrue(onTail.contains(">PANIC ON</button>"), "E on label: $onTailSafe")
        // While PANIC is on, no speed chip claims to be selected.
        assertFalse(onRow.contains("SELECTED</span>"), "F selection: $onRow")
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
        assertTrue(html.contains("--src-height:316px"), html)
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

    @Test
    fun importAndLibraryTargetSelectsOfferEveryAuthoredMission() {
        val html = render()
        assertTrue(html.contains("type=\"file\" id=\"${ConsoleRender.ID_IMPORT_FILE}\""), html)
        assertTrue(html.contains("accept=\".asm,.txt\""), html)
        for (row in model.pickerRows()) {
            assertTrue(html.contains("<option value=\"${row.id}\""), "mission ${row.id} not selectable for import")
        }
        assertEquals(2, countOf(html, "<select id="), "expected exactly the import and load target selects")
        assertEquals(2, countOf(html, " selected>"), "exactly one selected option per select")
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
