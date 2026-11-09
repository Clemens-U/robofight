package robofight.fireline.app.editor

import robofight.assembler.MNEMONICS
import robofight.isa.MachineProfile
import robofight.isa.PortCatalog

/** Highlight classes from DESIGN-FIRELINE.md 6 (they stay distinct in grayscale). */
enum class TokenClass { MNEMONIC, PORT, NUMBER, LABEL, COMMENT, TEXT }

/** One styled run of source text: half-open [start, end) into the original line. */
data class Token(val start: Int, val end: Int, val text: String, val klass: TokenClass)

/**
 * Line tokenizer for the editor's four coloring classes. It is a *display*
 * tokenizer only - the assembler in `:rf8-core` remains the sole authority on
 * what is legal RF-8 - and it borrows the assembler's own mnemonic set and the
 * shared port catalog instead of keeping a third list here.
 *
 * PORT covers the FIRELINE peripheral symbols (catalog profile FIRELINE,
 * numbers 16..30); shared body ports and registers render as TEXT.
 */
object SourceHighlight {

    private val firelinePortSymbols: Set<String> =
        PortCatalog.namedPorts()
            .filter { it.profile == MachineProfile.FIRELINE }
            .map { it.name.uppercase() }
            .toSet()

    /** Public for tests/diagnostics: exactly the symbols colored as PORT. */
    fun portSymbols(): Set<String> = firelinePortSymbols

    private val tokenPattern = Regex("[A-Za-z_][A-Za-z0-9_]*:|#?\\d+|[A-Za-z_][A-Za-z0-9_]*")

    fun tokens(line: String): List<Token> {
        val out = ArrayList<Token>()
        val commentAt = line.indexOf(';')
        val codePart = if (commentAt >= 0) line.substring(0, commentAt) else line
        for (match in tokenPattern.findAll(codePart)) {
            val text = match.value
            val klass = when {
                text.endsWith(":") -> TokenClass.LABEL
                text.uppercase() in MNEMONICS -> TokenClass.MNEMONIC
                text.uppercase() in firelinePortSymbols -> TokenClass.PORT
                text.startsWith("#") || text.all { it.isDigit() } -> TokenClass.NUMBER
                else -> TokenClass.TEXT
            }
            out.add(Token(match.range.first, match.range.last + 1, text, klass))
        }
        if (commentAt >= 0) {
            out.add(Token(commentAt, line.length, line.substring(commentAt), TokenClass.COMMENT))
        }
        return out
    }
}
