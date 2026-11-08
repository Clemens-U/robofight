package robofight.fireline.app.files

import java.util.Locale

/**
 * FIRELINE file-name rules for RF-8 source files (PLAN-RF8-MULTIAPP.md 9.2/9.4:
 * FILES stores plain `.asm` documents).
 *
 * The rules are deliberately strict and Android-free so they are testable on a
 * plain JVM and identical on every device: names are ASCII, one extension, no
 * path separators, no Windows-hostile trailing dots or spaces. `sanitize` is
 * total (any input maps to a legal name or to null) and idempotent, so an
 * imported file name can never smuggle a path into the private store.
 */
object FirmwareFileNames {

    /** Lowercase on import, uppercase in the store and on every card. */
    const val EXT = ".asm"

    /** How the store and every card spell the extension. */
    const val STORE_EXT = ".ASM"

    /** Card titles stay readable on a phone: the base part is capped. */
    const val MAX_BASE_NAME_LENGTH = 24

    /** Hard bound including the extension and any duplicate suffix. */
    const val MAX_NAME_LENGTH = MAX_BASE_NAME_LENGTH + STORE_EXT.length + 4

    /** Guard against a pathological `taken` set rather than looping forever. */
    const val MAX_UNIQUE_ATTEMPTS = 999

    /** Legal characters in the base part: A-Z 0-9 dash and underscore. */
    private fun legalBase(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_'

    /**
     * Map any user/imported name to a legal `BASE.ASM` name, or null when
     * nothing usable survives. Rules, in order:
     *  1. drop a single trailing `.asm` (any case) so re-imported names do not
     *     grow a double extension;
     *  2. replace every character outside the legal base set with `-`
     *     (path separators, quotes, spaces, dots, non-ASCII all collapse here);
     *  3. collapse runs of `-` and trim leading/trailing `-`, `.` and space;
     *  4. truncate the base to [MAX_BASE_NAME_LENGTH];
     *  5. uppercase the base and append [STORE_EXT].
     */
    fun sanitize(raw: String): String? {
        var base = raw.trim()
        if (base.endsWith(EXT, ignoreCase = true)) base = base.substring(0, base.length - EXT.length)
        val sb = StringBuilder(base.length + 4)
        for (ch in base) sb.append(if (legalBase(ch.uppercaseChar()) && ch.code < 128) ch.uppercaseChar() else '-')
        // Collapse '-' runs, then trim hostile edges.
        var out = sb.toString().replace(Regex("-{2,}"), "-").trim('-', '.', ' ')
        if (out.length > MAX_BASE_NAME_LENGTH) out = out.substring(0, MAX_BASE_NAME_LENGTH).trim('-', '.', ' ')
        if (out.isEmpty()) return null
        return out + STORE_EXT
    }

    /**
     * True when [name] is legal: it is exactly what [sanitize] would produce,
     * ignoring case (the store is case-insensitive, cards display uppercase).
     */
    fun isValid(name: String): Boolean = sanitize(name) == name.uppercase(Locale.US)

    /** `TANKER.ASM` -> `TANKER` (the label shown on an editor title bar). */
    fun baseName(name: String): String {
        val trimmed = if (name.endsWith(EXT, ignoreCase = true)) name.substring(0, name.length - EXT.length) else name
        return trimmed.uppercase(Locale.US)
    }

    /**
     * Duplicate-naming scheme: the first collision becomes `BASE_2.ASM`, the
     * next `BASE_3.ASM`, ... (case-insensitive against [taken]). The base is
     * trimmed to [MAX_BASE_NAME_LENGTH] minus the suffix length first, so a
     * maximal base plus a suffix can never collide with its own truncation and
     * the whole name stays inside [MAX_NAME_LENGTH]. Candidates carry [STORE_EXT].
     */
    fun uniqueName(name: String, taken: Set<String>): String {
        val takenUpper = taken.map { it.uppercase(Locale.US) }.toSet()
        if (!takenUpper.contains(name.uppercase(Locale.US))) return name
        val base = baseName(name)
        var attempt = 2
        while (attempt <= MAX_UNIQUE_ATTEMPTS) {
            val suffix = "_$attempt"
            val maxBase = MAX_BASE_NAME_LENGTH - suffix.length
            val trimmed = if (base.length > maxBase) base.substring(0, maxBase).trim('-', '_') else base
            val candidate = trimmed + suffix + STORE_EXT
            if (!takenUpper.contains(candidate.uppercase(Locale.US))) return candidate
            attempt++
        }
        // Unreachable in practice; keep the return total rather than throwing.
        return base + "_" + attempt + STORE_EXT
    }

    /** Suggested name for the FILES "duplicate" affordance. */
    fun duplicateName(name: String): String = baseName(name) + "-COPY" + STORE_EXT

    /**
     * Name offered to SAF `ACTION_CREATE_DOCUMENT` (Intent.EXTRA_TITLE).
     * DocumentsUI only keeps a title's extension when it already matches the
     * requested MIME type, so a bare `TANKER.ASM` under `text/plain` silently
     * landed as `TANKER.ASM.txt` on device (milestone 7 gate finding). The
     * export intent pairs this exact `BASE.ASM` title with the generic
     * `application/octet-stream` type, which makes the picker keep the title
     * verbatim; the store name always carries [STORE_EXT], so the roundtrip
     * file is byte-identically named.
     */
    fun exportFileName(name: String): String = baseName(name) + STORE_EXT
}
