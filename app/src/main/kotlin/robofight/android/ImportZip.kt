package robofight.android

import robofight.challenge.ChallengeCodeFileName
import robofight.challenge.ChallengeFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * ZIP import twin of [FirmwareExport] (P3 cross-app UX, PLAN-PRO §6):
 * reads a `robofight-export-<stamp>.zip` back into the app — firmware
 * entries land in the firmware store, `.chc` entries land in the saved-code
 * store flagged read-only (imported from the other app).
 *
 * Pure JVM on purpose (no Android types): the classification/validation is
 * the part worth testing, so `:app:testProDebugUnitTest` builds a fixture
 * ZIP, runs [read], and asserts every entry lands in the right store via
 * [writeToDirs]. [MainActivity] owns the SAF picker and the real stores.
 *
 * Validation rules (defensive — the file comes from outside the app):
 *  - entry names are reduced to their last path segment (no zip-slip paths);
 *  - macOS junk (`__MACOSX`, `._*`) and directory entries are skipped;
 *  - names are sanitized through [FirmwareExport.safeName];
 *  - `.chc` entries must normalize through [ChallengeCodeFileName] and parse
 *    through [ChallengeFormat.parse] — they are stored CANONICALIZED
 *    (parse → encode), identical to any other import;
 *  - anything else is firmware text (strict UTF-8);
 *  - hard caps: 200 entries, 1 MiB per entry, 8 MiB total.
 */
object ImportZip {

    const val MAX_ENTRIES = 200
    const val MAX_ENTRY_BYTES = 1 shl 20          // 1 MiB per entry
    const val MAX_TOTAL_BYTES = 8 shl 20          // 8 MiB per archive

    /** One accepted archive entry: store-ready name + text. */
    data class Entry(val name: String, val isCode: Boolean, val source: String)

    /** Accepted entries plus one human-readable line per rejected/skipped entry. */
    data class Report(val entries: List<Entry>, val rejected: List<String>)

    /** Read + validate a ZIP's bytes into store-ready entries. Never throws. */
    fun read(zipBytes: ByteArray): Report {
        val entries = ArrayList<Entry>()
        val rejected = ArrayList<String>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val raw = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    if (entry.isDirectory || raw.isEmpty() ||
                        raw.startsWith("._") || raw.startsWith("__MACOSX")
                    ) continue
                    val safe = FirmwareExport.safeName(raw)
                    if (entries.size >= MAX_ENTRIES) {
                        rejected.add("$safe // TOO MANY ENTRIES (MAX $MAX_ENTRIES)")
                        continue
                    }
                    val bytes = readCapped(zip)
                    if (bytes == null) {
                        rejected.add("$safe // ENTRY LARGER THAN $MAX_ENTRY_BYTES BYTES")
                        continue
                    }
                    if (bytes.isEmpty()) {
                        rejected.add("$safe // EMPTY")
                        continue
                    }
                    total += bytes.size
                    if (total > MAX_TOTAL_BYTES) {
                        rejected.add("$safe // ARCHIVE LARGER THAN $MAX_TOTAL_BYTES BYTES")
                        continue
                    }
                    val text = bytes.decodeStrictUtf8()
                    if (text == null) {
                        rejected.add("$safe // NOT TEXT")
                        continue
                    }
                    if (safe.endsWith(ChallengeCodeFileName.EXT, ignoreCase = true)) {
                        val name = ChallengeCodeFileName.normalize(safe)
                        if (name == null) {
                            rejected.add("$safe // BAD CODE NAME")
                            continue
                        }
                        when (val r = ChallengeFormat.parse(text.trim())) {
                            is ChallengeFormat.ParseResult.Ok ->
                                entries.add(Entry(name, true, ChallengeFormat.encode(r.desc)))
                            is ChallengeFormat.ParseResult.Err ->
                                rejected.add("$safe // ${r.message}")
                        }
                    } else {
                        entries.add(Entry(safe, false, text))
                    }
                }
            }
        } catch (e: Exception) {
            rejected.add("ARCHIVE // NOT A READABLE ZIP (${e.message ?: "IO"})")
        }
        // A stream that parsed cleanly but held nothing usable (garbage input
        // reaches EOF without ever seeing a local header) still deserves a
        // reason line — the terminal echoes every rejected entry.
        if (entries.isEmpty() && rejected.isEmpty())
            rejected.add("ARCHIVE // NOTHING READABLE IN IT")
        return Report(entries, rejected)
    }

    /**
     * Write a report's entries into two directories — firmware into
     * [firmwareDir], codes into [codesDir] (name collision → `_2`, `_3`, …).
     * Returns the number of files written. This is the fallback/test sink;
     * [MainActivity] writes accepted entries straight into the [BotFiles]
     * store instead, with the codes flagged imported.
     */
    fun writeToDirs(report: Report, firmwareDir: File, codesDir: File): Int {
        firmwareDir.mkdirs()
        codesDir.mkdirs()
        var written = 0
        for (e in report.entries) {
            val dir = if (e.isCode) codesDir else firmwareDir
            var name = e.name
            var n = 2
            while (File(dir, name).exists()) {
                val base = if (e.isCode) name.dropLast(ChallengeCodeFileName.EXT.length) else name
                name = "$base@$n" + if (e.isCode) ChallengeCodeFileName.EXT else ""
                n++
            }
            FileOutputStream(File(dir, name)).use { it.write(e.source.toByteArray(Charsets.UTF_8)) }
            written++
        }
        return written
    }

    /** Read up to MAX_ENTRY_BYTES; null when the entry exceeds the cap. */
    private fun readCapped(zip: ZipInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_ENTRY_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Strict UTF-8 decode (same re-encode proof the codec parser uses); null on binary. */
    private fun ByteArray.decodeStrictUtf8(): String? = try {
        String(this, Charsets.UTF_8).let {
            if (it.toByteArray().contentEquals(this)) it else null
        }
    } catch (_: Exception) {
        null
    }
}
