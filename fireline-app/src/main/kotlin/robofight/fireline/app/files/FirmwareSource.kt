package robofight.fireline.app.files

/**
 * FIRELINE firmware source text rules (the storage half of PLAN-RF8-MULTIAPP.md
 * 9.2). This file is Android-free on purpose: it is the acceptance layer every
 * editor save, import and duplicate passes through, and it is exercised by the
 * plain-JVM unit tests.
 *
 * It validates the *document*, not the program: opcode legality, operands and
 * the 256-instruction program limit are the assembler's job (`robofight.rf8`
 * via `:rf8-core`), so a source that will not assemble is still a legal saved
 * document - the editor shows the line errors instead of refusing the save.
 */
object FirmwareSource {

    /** 768 program bytes / 3 bytes per instruction = 256 instructions; a
     *  1200-line ceiling comfortably covers labels, comments and blank lines. */
    const val MAX_SOURCE_LINES = 1200

    /** Character ceiling for a whole source document (UTF-8 encoded). */
    const val MAX_SOURCE_BYTES = 8192

    /**
     * Accept/reject reasons. Empty list means the source may be stored.
     * Rejects: empty document, non-ASCII, control characters other than LF and
     * tab, CR (normalized away by [normalize] before validation), oversized
     * documents.
     */
    fun validate(source: String): List<String> {
        val problems = ArrayList<String>()
        if (source.isBlank()) problems.add("source is empty")
        if (source.contains('\r')) problems.add("source contains carriage return characters")
        for (ch in source) {
            if (ch.code > 126) { problems.add("source contains non-ASCII character (U+${ch.code.toString(16).uppercase()}), FIRELINE sources are ASCII"); break }
        }
        for (ch in source) {
            if (ch.code < 32 && ch != '\n' && ch != '\t') {
                problems.add("source contains control character U+00${ch.code.toString(16).uppercase()}")
                break
            }
        }
        val lines = source.split('\n').size
        if (lines > MAX_SOURCE_LINES) problems.add("source has $lines lines, limit is $MAX_SOURCE_LINES")
        val bytes = source.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_SOURCE_BYTES) problems.add("source is $bytes bytes, limit is $MAX_SOURCE_BYTES")
        return problems
    }

    /** Normalize before storing: CRLF and lone CR become LF, tabs become spaces. */
    fun normalize(source: String): String =
        source.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")

    /**
     * Line count for a card: content lines only (blank lines are chrome, not
     * program), which is also what makes the gutter numbers match the editor.
     */
    fun lineCount(source: String): Int = normalize(source).count { it == '\n' } + 1

    /** Stored size of a source, exactly what a file card prints as `B`. */
    fun byteCount(source: String): Int = source.toByteArray(Charsets.UTF_8).size
}

/** One saved firmware document. [id] is store-assigned and never reused. */
data class FirmwareFile(
    val id: Long,
    val name: String,
    val source: String,
    val createdMillis: Long,
    val updatedMillis: Long,
) {
    val bytes: Int get() = FirmwareSource.byteCount(source)

    /** Name without the extension, for the editor title bar. */
    val title: String get() = FirmwareFileNames.baseName(name)
}

/** Result of a store mutation that had to pass validation first. */
sealed class SaveOutcome {
    /** Stored. [requestedName] is non-null when the name was renamed to stay unique. */
    data class Saved(val file: FirmwareFile, val requestedName: String? = null) : SaveOutcome()

    /** Refused; [problems] are display-ready reasons, never empty. */
    data class Rejected(val problems: List<String>) : SaveOutcome()
}

/**
 * The FIRELINE-private firmware store (PLAN-RF8-MULTIAPP.md 9.4: FIRELINE keeps
 * its own copy and never reaches into Arena's private data). Names are
 * case-insensitive and unique; the two implementations are the Android SQLite
 * store and [InMemoryFirmwareRepository] for tests and previews.
 */
interface FirmwareRepository {
    /** All files, case-insensitive name order. */
    fun list(): List<FirmwareFile>

    fun count(): Int

    /** Case-insensitive lookup; null when absent. */
    fun find(name: String): FirmwareFile?

    fun byId(id: Long): FirmwareFile?

    /** Create a file; the name is sanitized and made unique, the source validated. */
    fun save(name: String, source: String): SaveOutcome

    /** Replace the content of an existing file (name unchanged). */
    fun update(id: Long, source: String): SaveOutcome

    /** Rename an existing file to a sanitized, collision-free name. */
    fun rename(id: Long, newName: String): SaveOutcome

    /** Copy a file under a `BASE-COPY.ASM` name (unique-suffixed if needed). */
    fun duplicate(id: Long): SaveOutcome

    fun delete(id: Long): Boolean
}
