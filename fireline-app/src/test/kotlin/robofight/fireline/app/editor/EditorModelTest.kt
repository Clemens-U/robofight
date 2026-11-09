package robofight.fireline.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.Missions
import robofight.fireline.app.files.StarterFirmware

/**
 * Editor mechanics: gutter, assemble readout, cheat-sheet insertion, card
 * stats and the highlight classes. Engine facts come from `:rf8-core` and
 * `:fireline-engine` (the authored missions' own reference firmware).
 */
class EditorModelTest {

    private val nozzleCheck = Missions.byId(1)!!

    @Test
    fun gutterIsFourDigitZeroPadded() {
        assertEquals(listOf("0001", "0002", "0003"), EditorModel.gutterLabels(3))
        assertEquals(listOf("0001"), EditorModel.gutterLabels(0))
        assertEquals(4, EditorModel.gutterLabels(1).single().length)
        assertEquals(4, EditorModel.gutterLabels(1200).last().length)
    }

    @Test
    fun assembleReadoutUsesTheSharedAssembler() {
        // Real authored reference firmware from the engine assembles cleanly.
        val summary = EditorModel.assembleSource(nozzleCheck.referenceSource)
        assertTrue(summary.ok)
        assertTrue(summary.instructionCount > 0)
        assertEquals(summary.instructionCount * 3, summary.codeBytes)
        assertEquals(emptyList<Pair<Int, String>>(), summary.errors)
    }

    @Test
    fun firstUseStarterAssemblesAndCompletesNozzleCheck() {
        val summary = EditorModel.assembleSource(StarterFirmware.SOURCE)
        assertTrue(summary.ok)
        val result = robofight.fireline.FirelineRun.runWithGuard(
            nozzleCheck,
            nozzleCheck.descriptorForSource(StarterFirmware.SOURCE),
        )
        assertTrue(result.success)
    }

    @Test
    fun assembleErrorsCarryTheirSourceLine() {
        val summary = EditorModel.assembleSource("MOV A,#1\n\nBOGUS A\n")
        assertFalse(summary.ok)
        assertTrue(summary.errors.any { it.first == 3 && it.second.contains("unknown opcode") })
        // A comment-only line is not an instruction: only the bad line reports.
        assertEquals(1, summary.errors.size)
        val badImmediate = EditorModel.assembleSource("       MOV A,#999")
        assertFalse(badImmediate.ok)
        assertEquals(1, badImmediate.errors.first().first)
    }

    @Test
    fun cardStatsMatchTheWireframeShape() {
        val stats = EditorModel.cardStats("IN FIRE_LEVEL\nJZ fin\nSHOOT\nfin: WAIT\n")
        assertEquals(37, stats.bytes)
        assertEquals(4, stats.instructions)
        assertEquals("37 B  4 i", stats.format())
        val broken = EditorModel.cardStats("NOPE\n")
        assertEquals(0, broken.instructions)
        assertEquals(1, broken.errorCount)
        assertTrue(broken.format().contains("ERR"))
    }

    @Test
    fun lineOffsetsJumpToTheReportedLine() {
        val source = "MOV A,#1\nIN FIRE_LEVEL\nJZ fin\n"
        assertEquals(0, EditorModel.lineStartOffset(source, 1))
        assertEquals(9, EditorModel.lineStartOffset(source, 2))
        assertEquals(23, EditorModel.lineStartOffset(source, 3))
        assertEquals(source.length, EditorModel.lineStartOffset(source, 9))
        assertEquals(0, EditorModel.lineStartOffset(source, 0))
    }

    @Test
    fun cheatSheetIsEngineDerivedAndSingle() {
        val block = PortCheatSheet.block()
        assertTrue(block.startsWith(PortCheatSheet.HEADER))
        assertTrue(block.lines().all { it.startsWith(";") })
        // Every official FIRELINE port symbol appears, taken from the catalog.
        for (symbol in SourceHighlight.portSymbols()) assertTrue(block.contains(symbol), "missing $symbol")
        assertTrue(block.lines().any { it.contains("FIRE_DIST") && it.contains("16") })
        assertFalse(PortCheatSheet.isPresent("MOV A,#1\n"))
        assertTrue(PortCheatSheet.isPresent(block))

        val source = "loop:  IN FIRE_LEVEL\n       JZ loop\n"
        val (inserted, caret) = EditorModel.insertCheatSheet(source, 6)
        assertTrue(inserted.startsWith(PortCheatSheet.HEADER))   // snapped to line 0 start
        assertTrue(inserted.endsWith(source))
        assertEquals(block.length + 1, caret)
        // Second insert is a no-op: no stacked blocks.
        val (again, sameCaret) = EditorModel.insertCheatSheet(inserted, caret)
        assertEquals(inserted, again)
        assertEquals(caret, sameCaret)
    }

    @Test
    fun highlightClassesFollowTheDesignRules() {
        val instruction = SourceHighlight.tokens("IN     FIRE_LEVEL")
        assertEquals(TokenClass.MNEMONIC, instruction[0].klass)
        assertEquals(TokenClass.PORT, instruction[1].klass)
        assertEquals("FIRE_LEVEL", instruction[1].text)

        val literal = SourceHighlight.tokens("       MOV A,#25 ; fill A")
        assertEquals(TokenClass.MNEMONIC, literal[0].klass)
        assertEquals(TokenClass.TEXT, literal[1].klass)        // body register, not a peripheral
        assertEquals(TokenClass.NUMBER, literal[2].klass)
        assertEquals(TokenClass.COMMENT, literal[3].klass)

        val labelled = SourceHighlight.tokens("fin:   WAIT")
        assertEquals(TokenClass.LABEL, labelled[0].klass)
        assertEquals(TokenClass.MNEMONIC, labelled[1].klass)

        // The mission's own firmware: the sensor symbol in its first instruction
        // is highlighted as a FIRELINE peripheral, and authored firmware carries
        // no comments, so no COMMENT class appears.
        val firstLine = nozzleCheck.referenceSource.lines().first()
        assertTrue(SourceHighlight.tokens(firstLine).any { it.klass == TokenClass.PORT && it.text == "FIRE_LEVEL" })
        assertTrue(nozzleCheck.referenceSource.lines().none { SourceHighlight.tokens(it).any { t -> t.klass == TokenClass.COMMENT } })
        assertTrue(SourceHighlight.portSymbols().contains("MISSION"))
        assertFalse(SourceHighlight.portSymbols().contains("HP"))
    }
}
