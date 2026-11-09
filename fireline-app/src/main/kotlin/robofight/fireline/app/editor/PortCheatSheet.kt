package robofight.fireline.app.editor

import robofight.fireline.FirelinePorts

/**
 * The insertable `; FIRELINE PORTS` comment block (DESIGN-FIRELINE.md 6,
 * FIRELINE-DESIGN.md 6.3/10). Everything below the header comes from the
 * engine's own cheat sheet - `FirelinePorts.cheatSheet()` builds it from the
 * shared port catalog in `:rf8-core` - so a port rename in the engine lands in
 * the editor for free and the app never re-states a port meaning by hand.
 *
 * Every line is an RF-8 comment (';'), which is what lets the block travel
 * inside a plain `.asm` document through import/export.
 */
object PortCheatSheet {

    const val HEADER = "; FIRELINE PORTS"

    /** Comment-per-line block, insertable anywhere in a source. */
    fun block(): String =
        (listOf(HEADER) + FirelinePorts.cheatSheet().split('\n').map { line ->
            if (line.isBlank()) ";" else "; $line"
        }).joinToString("\n")

    /** Guard so repeated taps do not stack duplicate blocks in one file. */
    fun isPresent(source: String): Boolean = source.lineSequence().any { it.trim() == HEADER }
}
