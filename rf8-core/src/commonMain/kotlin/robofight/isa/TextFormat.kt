package robofight.isa

/*
 * Output-identical integer formatting helpers (W1, docs/FIRELINE-WEB.md §9).
 *
 * The wasmJs stdlib does not expose the JVM `String.format` used by the display
 * and debug strings of this module. These helpers reproduce the exact previous
 * output (upper-case hex, zero-padded, non-negative values only) so that the
 * disassembler listing, VM fault strings and run traces stay byte-identical across
 * targets: the determinism gates (G1-G3) compare engine output, not formatting
 * calls. Common code only: no JVM, no regex, no java.util dependency.
 *
 * Public, not internal: `:fireline-engine` consumes them across the module
 * boundary (Kotlin `internal` is module-scoped, so a file-internal helper is
 * invisible to the consuming module on every target).
 */

public fun hexPad(value: Int, digits: Int): String =
    value.toInt().toUInt().toString(16).uppercase().padStart(digits, '0')

public fun decPad(value: Int, digits: Int): String =
    value.toString().padStart(digits, '0')
