package robofight.challenge

import java.util.Locale

/**
 * File-style names for locally saved challenge codes.
 *
 * The wire format does not carry a file name; this helper only keeps the app's
 * local code library consistent. Names always expose the `.chc` suffix so a
 * saved challenge cannot be mistaken for firmware.
 */
object ChallengeCodeFileName {
    const val EXT = ".chc"
    const val MAX_LENGTH = 64

    /**
     * Trim a user-entered name and add/canonicalize `.chc`.
     * Returns null for an empty base name, path/control characters, or names
     * longer than [MAX_LENGTH]. Spaces, dots, dashes, and underscores are legal.
     */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val base = if (trimmed.endsWith(EXT, ignoreCase = true)) {
            trimmed.dropLast(EXT.length).trimEnd()
        } else {
            trimmed
        }
        if (base.isEmpty() || base.endsWith('.') || base.endsWith(' ')) return null
        if (base.any { it.code < 32 || it in INVALID_CHARS }) return null
        val result = base + EXT
        return result.takeIf { it.length <= MAX_LENGTH }
    }

    /** First free `CODE<n>.chc`, comparing names case-insensitively. */
    fun nextDefault(existing: Collection<String>): String {
        val used = existing.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        var n = 1
        while ("code$n$EXT" in used) n++
        return "CODE$n$EXT"
    }

    /**
     * Choose a collision-free `.chc` name for a pre-v4 database row.
     * A conflicting `DUEL.chc` makes legacy `DUEL` become `DUEL_2.chc`.
     */
    fun migratedLegacyName(name: String, usedNames: Collection<String>): String {
        val used = usedNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        val normalized = normalize(name) ?: normalize(FALLBACK)!!
        if (normalized.lowercase(Locale.ROOT) !in used) return normalized
        val base = normalized.dropLast(EXT.length)
        var n = 2
        var candidate: String
        do {
            candidate = "${base}_$n$EXT"
            n++
        } while (candidate.lowercase(Locale.ROOT) in used)
        return candidate
    }

    private const val FALLBACK = "CODE"
    private val INVALID_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
}
