package robofight.fireline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import robofight.fireline.FailureReason

/**
 * Headless gate for the browser firmware library codec (`WebLibrary`) and the
 * editor draft path (`ConsoleModel.draftFirmware`), the W3 half that carries
 * docs/FIRELINE-WEB.md §8 storage (namespaced key, only firmware + selection)
 * and §12a D4 (editor with line-level assembler errors). The `localStorage`
 * read/write itself lives in `Main.kt` (DOM interop); everything that decides
 * what a stored or typed firmware MEANS is this pure layer, asserted here.
 */
class W3LibraryDraftTest {

    private val model = ConsoleModel()

    @Test
    fun storageKeyIsTheNamespacedKeyOfPlanSection8() {
        assertEquals("fireline.web.v1", WebLibrary.STORAGE_KEY)
    }

    @Test
    fun storeNamesFollowTheAndroidStoreContract() {
        // Gate G5 imports exactly these names: uppercase, one .ASM extension.
        assertEquals("MY-BOT.ASM", WebLibrary.normalizeName("  my-bot  "))
        assertEquals("CORRIDOR-REF.ASM", WebLibrary.normalizeName("corridor-ref.asm"))
        assertEquals("CORRIDOR-REF.ASM", WebLibrary.normalizeName("CORRIDOR-REF.ASM"))
        assertNull(WebLibrary.normalizeName(""))
        assertNull(WebLibrary.normalizeName(".ASM"))
        assertNull(WebLibrary.normalizeName("lower case with spaces"))
        assertNull(WebLibrary.normalizeName("WEIRD*NAME"))
    }

    @Test
    fun libraryCodecRoundTripsArbitrarySourcesAndSelection() {
        val store = WebLibrary.Store(
            entries = listOf(
                WebLibrary.Entry("MY-LOOP.ASM", "loop:  WAIT\n       JMP    loop"),
                WebLibrary.Entry("TRICKY.ASM", "line with \\ backslash\nand a tab\tinside"),
            ),
            selectedName = "TRICKY.ASM",
        )
        val decoded = WebLibrary.decode(WebLibrary.encode(store))
        assertEquals(store, decoded)
    }

    @Test
    fun foreignSiteDataDecodesToEmptyAndNeverCrashes() {
        assertEquals(WebLibrary.EMPTY, WebLibrary.decode(null))
        assertEquals(WebLibrary.EMPTY, WebLibrary.decode(""))
        assertEquals(WebLibrary.EMPTY, WebLibrary.decode("someone.else's format\nX\tY"))
        assertEquals(WebLibrary.EMPTY, WebLibrary.decode("fireline.web.v1\nBAD NAME\tsource"))
    }

    @Test
    fun saveReplaceRemoveAndSelectionTrackEachOther() {
        var store = WebLibrary.EMPTY
        store = WebLibrary.saved(store, "A.ASM", "MOVE")
        store = WebLibrary.saved(store, "B.ASM", "TURN L")
        assertEquals(listOf("A.ASM", "B.ASM"), store.entries.map { it.name })
        store = WebLibrary.saved(store, "A.ASM", "SHOOT") // replace, not duplicate
        assertEquals(2, store.entries.size)
        assertEquals("SHOOT", WebLibrary.find(store, "A.ASM")!!.source)
        assertEquals("A.ASM", store.selectedName)
        store = WebLibrary.removed(store, "A.ASM")
        assertNull(store.selectedName)
        store = WebLibrary.withSelected(store, "B.ASM")
        assertEquals("TURN L", WebLibrary.find(store, "B.ASM")!!.source)
    }

    @Test
    fun editorDraftWithLineLevelAssemblerErrors() {
        val draft = model.draftFirmware(2, "BROKEN.ASM", "MOVE\nTHIST NOT REAL\nWAIT")
        assertIs<ConsoleModel.DraftResult.Rejected>(draft)
        assertTrue(draft.problems.any { it.startsWith("line 2:") }, draft.problems.toString())
    }

    @Test
    fun editorDraftRefusesBlankAndNonAsciiSources() {
        assertIs<ConsoleModel.DraftResult.Rejected>(model.draftFirmware(2, "EMPTY.ASM", "   "))
        val umlaut = model.draftFirmware(2, "HOSTILE.ASM", "MOVE  # über")
        assertIs<ConsoleModel.DraftResult.Rejected>(umlaut)
        assertTrue(umlaut.problems.any { it.contains("non-ASCII") }, umlaut.problems.toString())
    }

    @Test
    fun editorDraftOpensACustomFirmwareAndRunsItToTheDocumentedFailure() {
        // The mission 01 authored failing variant, typed exactly as a player
        // would (source copied from the `1-failing-never-shoots` row of
        // `docs/web/fixtures/permalink_sources.tsv`, i.e. live engine text);
        // parity_matrix.tsv row 1-failing pins TIME_EXPIRED at tick 60 with
        // final hash 7cbfe49993c17318 (engine v2).
        val source = "loop:  WAIT\n       JMP    loop"
        val draft = model.draftFirmware(1, "NEVER.ASM", source)
        assertIs<ConsoleModel.DraftResult.Accepted>(draft)
        val outcome = draft.run.run()
        assertEquals(robofight.fireline.session.StepOutcome.FINISHED, outcome)
        assertEquals(FailureReason.TIME_EXPIRED, draft.run.failureReason())
        assertEquals("7cbfe49993c17318", draft.run.fingerprint())
        assertEquals(60, draft.run.tick)
    }
}
