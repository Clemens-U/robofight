package robofight.fireline.app.editor

import robofight.assembler.AsmResult
import robofight.assembler.assemble as rf8Assemble
import robofight.fireline.app.files.FirmwareSource

/** Assemble outcome in the shape the editor chrome displays. */
data class AssemblySummary(
    val ok: Boolean,
    val instructionCount: Int,
    val codeBytes: Int,
    /** (1-based source line, message) exactly as the assembler reported it. */
    val errors: List<Pair<Int, String>>,
)

/** Two-line file card: `42 B  18 i`, or `42 B  2 ERR` when it will not assemble. */
data class FileCardStats(val bytes: Int, val instructions: Int, val errorCount: Int) {
    fun format(): String =
        if (errorCount == 0) "%d B  %d i".format(bytes, instructions)
        else "%d B  %d ERR".format(bytes, errorCount)
}

/**
 * Editor mechanics that are pure string/number work, so the gutter, the cheat
 * sheet insert and the assemble readout are testable without a device.
 * The assembler itself is `:rf8-core`'s - the editor never re-implements it.
 */
object EditorModel {

    /** 4-digit zero-padded gutter (DESIGN-FIRELINE.md 6). */
    const val GUTTER_DIGITS = 4

    fun gutterLabels(lineCount: Int): List<String> =
        (1..maxOf(1, lineCount)).map { it.toString().padStart(GUTTER_DIGITS, '0') }

    /** Assemble [source] through the shared RF-8 assembler (never a local copy). */
    fun assembleSource(source: String): AssemblySummary {
        val result: AsmResult = rf8Assemble(source)
        return AssemblySummary(
            ok = result.ok,
            instructionCount = result.instructionCount,
            codeBytes = result.code.size,
            errors = result.errors.map { it.line to it.msg },
        )
    }

    /** Card numbers: byte size from storage rules, instruction count from the assembler. */
    fun cardStats(source: String): FileCardStats {
        val summary = assembleSource(source)
        return FileCardStats(FirmwareSource.byteCount(source), summary.instructionCount, summary.errors.size)
    }

    /** Offset of a 1-based source line inside [source] (0 when out of range). */
    fun lineStartOffset(source: String, lineOneBased: Int): Int {
        if (lineOneBased < 1) return 0
        var line = 1
        for (i in source.indices) {
            if (line == lineOneBased) return i
            if (source[i] == '\n') line++
        }
        return source.length
    }

    /**
     * Insert the port cheat sheet at [caret], returning the new source and the
     * caret position just after the block. No-op (same source, same caret) when
     * the block is already present.
     */
    fun insertCheatSheet(source: String, caret: Int): Pair<String, Int> {
        if (PortCheatSheet.isPresent(source)) return source to caret
        val block = PortCheatSheet.block()
        val at = caret.coerceIn(0, source.length)
        // Snap to the start of the caret's line so a block never splits a line.
        val lineStart = lineStartOffset(source, source.substring(0, at).count { it == '\n' } + 1)
        val updated = source.substring(0, lineStart) + block + "\n" + source.substring(lineStart)
        return updated to (lineStart + block.length + 1)
    }
}
