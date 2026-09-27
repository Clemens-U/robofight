package robofight.android

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports firmware files out of the [BotFiles] store.
 *
 * The destination is chosen by the user at export time via the standard
 * Android folder picker (SAF, `ACTION_OPEN_DOCUMENT_TREE`) — any folder the
 * user can reach, no extra permissions, no androidx. This object only builds
 * the bytes and file names; [MainActivity] owns the picker and writes into
 * the chosen folder.
 *
 * The ZIP is written with a plain [ZipOutputStream] — no extra dependencies,
 * which the offline toolchain needs. Entries are STORED (uncompressed) with a
 * fixed DOS timestamp, so re-running the export produces identical bytes.
 */
object FirmwareExport {

    /** MIME type for the all-firmware ZIP. */
    const val MIME_ZIP = "application/zip"

    /** MIME type for a single firmware source file. */
    const val MIME_SOURCE = "text/plain"

    /** `yyyyMMdd-HHmm` stamp for export file names (device local time). */
    fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

    /** File name for the all-firmware archive. */
    fun zipName(): String = "robofight-export-${stamp()}.zip"

    /** File-system-safe version of a stored firmware name (no path chars). */
    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "FILE" }

    /**
     * Builds the ZIP bytes for [files] (stored name -> source). Entry names
     * mirror the firmware names (`MYBOT`, `HUNTER.asm`, …) so the archive can
     * be re-imported by matching file names.
     */
    fun zipBytes(files: Map<String, String>): ByteArray {
        require(files.isNotEmpty()) { "no files to export" }
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            val used = mutableSetOf<String>()
            for ((name, source) in files) {
                var entryName = safeName(name)
                var dup = 1
                while (!used.add(entryName)) entryName = "${safeName(name)}_${dup++}"
                val bytes = source.toByteArray(Charsets.UTF_8)
                // A STORED entry must carry its size and CRC in the header
                // (ZipOutputStream.putNextEntry rejects it otherwise).
                val crcValue = CRC32().apply { update(bytes) }.value
                zip.putNextEntry(ZipEntry(entryName).apply {
                    time = FIXED_ZIP_TIME
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = crcValue
                })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buf.toByteArray()
    }

    // 2026-01-01 00:00 in DOS format — deterministic archive bytes.
    private const val FIXED_ZIP_TIME = 0x5C200000L
}
