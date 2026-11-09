package robofight.fireline.app.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source validation and repository CRUD through the in-memory fake (the same
 * rules the SQLite store runs). Pure JVM: no Android classes are touched here.
 */
class FirmwareStoreRulesTest {

    // ---- document acceptance ---------------------------------------------------

    @Test
    fun sourceValidationAcceptsAndRejects() {
        assertEquals(emptyList<String>(), FirmwareSource.validate("MOV A,#1\n"))
        assertEquals(emptyList<String>(), FirmwareSource.validate("loop:  IN FIRE_LEVEL ; comment\n       JMP loop"))
        assertTrue(FirmwareSource.validate("").any { it.contains("empty") })
        assertTrue(FirmwareSource.validate("   ").any { it.contains("empty") })
        assertTrue(FirmwareSource.validate("MOV A,#1\r\n").any { it.contains("carriage return") })
        assertTrue(FirmwareSource.validate("IN FIRE_LEVEL ; Wärme").any { it.contains("non-ASCII") })
        assertTrue(FirmwareSource.validate("MOV\u0007A").any { it.contains("control character") })
        val tooManyLines = "1\n".repeat(FirmwareSource.MAX_SOURCE_LINES + 1)
        assertTrue(FirmwareSource.validate(tooManyLines).any { it.contains("lines") })
        val tooBig = "A".repeat(FirmwareSource.MAX_SOURCE_BYTES + 1)
        assertTrue(FirmwareSource.validate(tooBig).any { it.contains("bytes") })
    }

    @Test
    fun normalizeFlattensLineEndingsAndTabs() {
        assertEquals("a\nb\nc    d", FirmwareSource.normalize("a\r\nb\rc\td"))
        assertEquals(2, FirmwareSource.lineCount("MOV A,#1\nWAIT"))
        assertEquals(3, FirmwareSource.lineCount("MOV A,#1\nWAIT\n"))
        assertEquals(1, FirmwareSource.lineCount(""))
        assertEquals(14, FirmwareSource.byteCount("IN FIRE_LEVEL\n"))
        assertEquals(9, FirmwareSource.byteCount("IN FÄLL\n"))
    }

    // ---- repository CRUD via the fake -------------------------------------------

    private class Clock {
        var now = 1_000L
        operator fun invoke(): Long = now
    }

    @Test
    fun saveStoresValidatedUppercasedFiles() {
        val clock = Clock()
        val repo = InMemoryFirmwareRepository({ clock() })
        val outcome = repo.save("tanker.asm", "MOV A,#1\nWAIT\n")
        assertTrue(outcome is SaveOutcome.Saved)
        val saved = (outcome as SaveOutcome.Saved).file
        assertEquals("TANKER.ASM", saved.name)
        assertEquals(14, saved.bytes)
        assertEquals(1_000L, saved.createdMillis)
        assertEquals(1_000L, saved.updatedMillis)
        assertEquals("TANKER", saved.title)
        assertEquals(1, repo.count())
        assertNotNull(repo.find("tanker.asm"))   // case-insensitive lookup
        assertNotNull(repo.find("TANKER.ASM"))
        assertNull(repo.find("missing.asm"))
    }

    @Test
    fun saveRejectsEmptySourcesAndUnusableNames() {
        val repo = InMemoryFirmwareRepository()
        val empty = repo.save("TANKER.ASM", "")
        assertTrue(empty is SaveOutcome.Rejected)
        assertTrue((empty as SaveOutcome.Rejected).problems.isNotEmpty())
        assertEquals(0, repo.count())
        val noName = repo.save("....", "MOV A,#1")
        assertTrue(noName is SaveOutcome.Rejected)
        assertTrue((noName as SaveOutcome.Rejected).problems.first().contains("file name"))
        assertEquals(0, repo.count())
    }

    @Test
    fun saveSanitizesImportedNamesAndRenamesCollisions() {
        val repo = InMemoryFirmwareRepository()
        val first = repo.save("../evil/name.asm", "MOV A,#1") as SaveOutcome.Saved
        assertEquals("EVIL-NAME.ASM", first.file.name)
        assertNull(first.requestedName)
        val again = repo.save("evil-name", "WAIT") as SaveOutcome.Saved
        assertEquals("EVIL-NAME_2.ASM", again.file.name)
        assertEquals("EVIL-NAME.ASM", again.requestedName)
        val third = repo.save("evil-name.asm", "WAIT") as SaveOutcome.Saved
        assertEquals("EVIL-NAME_3.ASM", third.file.name)
        assertEquals(3, repo.count())
    }

    @Test
    fun updateKeepsIdentityAndTimestamps() {
        val clock = Clock()
        val repo = InMemoryFirmwareRepository({ clock() })
        val saved = (repo.save("TANKER.ASM", "MOV A,#1\n") as SaveOutcome.Saved).file
        clock.now = 2_000L
        val updated = (repo.update(saved.id, "IN FIRE_LEVEL\nJZ idle\n") as SaveOutcome.Saved).file
        assertEquals(saved.id, updated.id)
        assertEquals("TANKER.ASM", updated.name)
        assertEquals(1_000L, updated.createdMillis)
        assertEquals(2_000L, updated.updatedMillis)
        assertEquals("IN FIRE_LEVEL\nJZ idle\n", repo.byId(saved.id)?.source)
        val missing = repo.update(999L, "WAIT")
        assertTrue(missing is SaveOutcome.Rejected)
        val badSource = repo.update(saved.id, "")
        assertTrue(badSource is SaveOutcome.Rejected)
        assertEquals("IN FIRE_LEVEL\nJZ idle\n", repo.byId(saved.id)?.source)   // rejection left the last accepted content untouched
    }

    @Test
    fun renameIsCaseInsensitiveAndCollisionSafe() {
        val repo = InMemoryFirmwareRepository()
        val a = (repo.save("TANKER.ASM", "MOV A,#1") as SaveOutcome.Saved).file
        val b = (repo.save("PUMP.ASM", "MOV A,#2") as SaveOutcome.Saved).file
        val renamed = repo.rename(a.id, "pump") as SaveOutcome.Saved
        assertEquals("PUMP_2.ASM", renamed.file.name)
        assertEquals("PUMP.ASM", renamed.requestedName)
        val sameName = repo.rename(b.id, "PUMP.ASM") as SaveOutcome.Saved
        assertNull(sameName.requestedName)   // renaming to its own name is not a collision
        assertEquals(2, repo.count())
        val missing = repo.rename(999L, "X")
        assertTrue(missing is SaveOutcome.Rejected)
    }

    @Test
    fun duplicateCopiesUnderACopyName() {
        val repo = InMemoryFirmwareRepository()
        val original = (repo.save("TANKER.ASM", "MOV A,#1\n") as SaveOutcome.Saved).file
        val copy = repo.duplicate(original.id) as SaveOutcome.Saved
        assertEquals("TANKER-COPY.ASM", copy.file.name)
        assertEquals(original.source, copy.file.source)
        val copy2 = repo.duplicate(original.id) as SaveOutcome.Saved
        assertEquals("TANKER-COPY_2.ASM", copy2.file.name)
        assertEquals(3, repo.count())
        assertTrue(repo.duplicate(999L) is SaveOutcome.Rejected)
    }

    @Test
    fun listIsNameOrderedAndDeleteIsFinal() {
        val repo = InMemoryFirmwareRepository()
        repo.save("ZEBRA", "MOV A,#1")
        repo.save("alpha.asm", "MOV A,#2")
        repo.save("middle", "MOV A,#3")
        assertEquals(listOf("ALPHA.ASM", "MIDDLE.ASM", "ZEBRA.ASM"), repo.list().map { it.name })
        val zebra = repo.find("ZEBRA.ASM")!!
        assertTrue(repo.delete(zebra.id))
        assertEquals(2, repo.count())
        assertTrue(!repo.delete(zebra.id))
        assertTrue(repo.list().none { it.name == "ZEBRA.ASM" })
    }

    @Test
    fun tabsAreNormalizedOnSave() {
        val repo = InMemoryFirmwareRepository()
        val saved = (repo.save("TAB", "MOV\tA,#1") as SaveOutcome.Saved).file
        assertEquals("MOV    A,#1", saved.source)
    }

    @Test
    fun starterSeedsOnlyAnEmptyLibrary() {
        val empty = InMemoryFirmwareRepository()
        val starter = StarterFirmware.seedIfEmpty(empty)
        assertNotNull(starter)
        assertEquals(StarterFirmware.NAME, starter?.name)
        assertEquals(StarterFirmware.SOURCE, starter?.source)
        assertEquals(1, empty.count())

        // A second launch must not duplicate it, and an existing library is
        // never modified by the first-use helper.
        assertNull(StarterFirmware.seedIfEmpty(empty))
        val existing = InMemoryFirmwareRepository()
        existing.save("MY-CODE", "WAIT\n")
        assertNull(StarterFirmware.seedIfEmpty(existing))
        assertEquals(listOf("MY-CODE.ASM"), existing.list().map { it.name })
    }
}
