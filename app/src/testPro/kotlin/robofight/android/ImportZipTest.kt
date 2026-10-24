package robofight.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import robofight.assembler.assemble
import robofight.challenge.ChallengeCodeFileName
import robofight.challenge.ChallengeFormat
import robofight.world.SPAWN_SEPARATE
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P3 migration import ([ImportZip]) — pro flavor test run only (the import UX
 * ships exclusively in the Circuit). Builds a fixture `robofight-export`-style
 * ZIP in memory, runs it through [ImportZip.read], and asserts every entry
 * lands in the right store via [ImportZip.writeToDirs], plus the defensive
 * rules (§ zip-slip names, caps, strict UTF-8, §10 code validation).
 */
class ImportZipTest {

    private val firmwareText = "; MIGRATED BOT\nWAIT\nMOV A, #5\nSHOOT\n"

    private fun validCode(): String {
        val image = assemble("WAIT\nMOV A, #5\nSHOOT\n").code
        val desc = ChallengeFormat.FightDescriptor(
            eng = 1, layoutId = 0x00, slots = 0b01, seed = 7,
            mode = ChallengeFormat.MODE_KO, spawn = SPAWN_SEPARATE,
            hpA = 100, hpB = 100, result = null,
            fwA = ByteArray(0), fwB = image, srcA = null, srcB = null,
        )
        return ChallengeFormat.encode(desc)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return buf.toByteArray()
    }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun acceptedEntriesLandInTheirStores() {
        val zip = zipOf(
            "MYBOT" to utf8(firmwareText),
            "HUNTER.asm" to utf8(firmwareText),
            "CODE1.chc" to utf8(validCode()),
        )
        val report = ImportZip.read(zip)
        assertEquals(0, report.rejected.size, "rejected: ${report.rejected}")
        assertEquals(3, report.entries.size)

        val byName = report.entries.associate { it.name to it }
        assertFalse(byName.getValue("MYBOT").isCode)
        assertFalse(byName.getValue("HUNTER.asm").isCode)
        assertTrue(byName.getValue("CODE1.chc").isCode)
        assertEquals(firmwareText, byName.getValue("MYBOT").source)

        // Stored codes are canonicalized exactly like any other import:
        // parse → encode, and the stored text survives a re-parse unchanged.
        val stored = byName.getValue("CODE1.chc").source
        val reparsed = ChallengeFormat.parse(stored)
        assertTrue(reparsed is ChallengeFormat.ParseResult.Ok, "stored code must parse: $reparsed")
        assertEquals(
            stored,
            ChallengeFormat.encode((reparsed as ChallengeFormat.ParseResult.Ok).desc),
        )

        val dir = newTempDir()
        try {
            val written = ImportZip.writeToDirs(report, File(dir, "firmware"), File(dir, "codes"))
            assertEquals(3, written)
            assertEquals(firmwareText, File(dir, "firmware/MYBOT").readText())
            assertEquals(firmwareText, File(dir, "firmware/HUNTER.asm").readText())
            assertEquals(stored, File(dir, "codes/CODE1${ChallengeCodeFileName.EXT}").readText())
            // Second run must not clobber the first (collision-safe names).
            assertEquals(3, ImportZip.writeToDirs(report, File(dir, "firmware"), File(dir, "codes")))
            assertTrue(File(dir, "firmware/MYBOT@2").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun junkAndBadCodesAreRejected() {
        val zip = zipOf(
            "BAD.chc" to utf8("HELLO THERE"),
            "notes.txt" to byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte()), // not valid UTF-8
            "empty" to ByteArray(0),
        )
        val report = ImportZip.read(zip)
        assertTrue(report.entries.isEmpty(), "nothing here is store-worthy: ${report.entries}")
        assertEquals(3, report.rejected.size)
        assertTrue(report.rejected[0].startsWith("BAD.chc"))       // §10 parse rejection
        assertTrue(report.rejected[1].contains("NOT TEXT"))        // binary firmware junk
        assertTrue(report.rejected[2].contains("EMPTY"))
    }

    @Test
    fun pathsAndMacJunkNeverEscapeTheStoreName() {
        val code = validCode()
        val zip = zipOf(
            "evil/../../ESCAPED.chc" to utf8(code),   // zip-slip style path
            "sub/" to ByteArray(0),                   // directory entry
            "__MACOSX/._MYBOT" to utf8(firmwareText), // macOS resource fork junk
            "._MYBOT" to utf8(firmwareText),
        )
        val report = ImportZip.read(zip)
        assertEquals(1, report.entries.size, "report: ${report.entries} + ${report.rejected}")
        val entry = report.entries.single()
        assertTrue(entry.isCode)
        assertFalse(entry.name.contains('/'), "name must be a bare file name: ${entry.name}")
        assertFalse(entry.name.contains(".."))
        assertEquals("ESCAPED.chc", entry.name)
    }

    @Test
    fun oversizedEntriesAreRefused() {
        val fat = ByteArray(ImportZip.MAX_ENTRY_BYTES + 1024) // deflate makes this compressible
        val zip = zipOf("HUGE" to fat)
        val report = ImportZip.read(zip)
        assertTrue(report.entries.isEmpty())
        assertEquals(1, report.rejected.size)
        assertTrue(report.rejected.single().contains("LARGER"))
    }

    @Test
    fun nonZipInputFailsCleanly() {
        val report = ImportZip.read("this is not a zip archive at all".toByteArray())
        assertTrue(report.entries.isEmpty())
        assertNotNull(report.rejected.singleOrNull())
        assertTrue(report.rejected.single().startsWith("ARCHIVE"))
    }

    private fun newTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "rf-import-zip-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
