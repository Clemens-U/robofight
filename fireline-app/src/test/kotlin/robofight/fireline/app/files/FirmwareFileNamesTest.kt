package robofight.fireline.app.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Name rules (FirmwareFileNames): accept/reject cases, path-separator safety,
 * the duplicate rename scheme, and the length ceiling. Pure JVM, no Android.
 */
class FirmwareFileNamesTest {

    @Test
    fun sanitizeAddsTheExtensionAndUppercases() {
        assertEquals("TANKER.ASM", FirmwareFileNames.sanitize("tanker"))
        assertEquals("TANKER.ASM", FirmwareFileNames.sanitize("  tanker.asm  "))
        assertEquals("TANKER.ASM", FirmwareFileNames.sanitize("TANKER.ASM"))
        assertEquals("TANKER-ASM.ASM", FirmwareFileNames.sanitize("TANKER ASM"))
    }

    @Test
    fun sanitizeNeutralizesPathAndShellCharacters() {
        assertEquals("EVIL-NAME.ASM", FirmwareFileNames.sanitize("../evil/name.asm"))
        assertEquals("BAD-NAME.ASM", FirmwareFileNames.sanitize("bad/name"))
        assertEquals("BAD-NAME.ASM", FirmwareFileNames.sanitize("bad\\name"))
        assertEquals("BAD-NAME.ASM", FirmwareFileNames.sanitize("bad:name"))
        assertEquals("QUOTE-X.ASM", FirmwareFileNames.sanitize("quote\"x"))
        assertEquals("PIPE-X.ASM", FirmwareFileNames.sanitize("pipe|x"))
        assertEquals("STAR-X-Y.ASM", FirmwareFileNames.sanitize("star*x?y"))
    }

    @Test
    fun sanitizeHandlesNonAsciiAndDots() {
        assertEquals("BER.ASM", FirmwareFileNames.sanitize("über"))
        assertEquals("NAME-TXT.ASM", FirmwareFileNames.sanitize("name.txt"))
        // A single trailing .asm is stripped; a second one is part of the base.
        assertEquals("TANKER-ASM.ASM", FirmwareFileNames.sanitize("tanker.asm.asm"))
        assertEquals("SPACED-NAME.ASM", FirmwareFileNames.sanitize("  spaced  name  "))
    }

    @Test
    fun sanitizeRejectsNamesWithNothingUsable() {
        assertNull(FirmwareFileNames.sanitize(""))
        assertNull(FirmwareFileNames.sanitize("   "))
        assertNull(FirmwareFileNames.sanitize("...."))
        assertNull(FirmwareFileNames.sanitize("///"))
    }

    @Test
    fun sanitizeTruncatesAndStaysIdempotent() {
        val long = FirmwareFileNames.sanitize("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123")!!
        assertEquals(
            FirmwareFileNames.MAX_BASE_NAME_LENGTH,
            long.removeSuffix(FirmwareFileNames.STORE_EXT).length,
        )
        assertEquals(long, FirmwareFileNames.sanitize(long))
        // Truncation that lands on a separator is trimmed again, not left dangling.
        val trimmed = FirmwareFileNames.sanitize("ABCDEFGHIJKLMNOPQRSTUVW-XZ")!!
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVW.ASM", trimmed)
        assertFalse(trimmed.removeSuffix(FirmwareFileNames.STORE_EXT).endsWith("-"))
    }

    @Test
    fun validationAcceptsCanonicalNamesOnly() {
        assertTrue(FirmwareFileNames.isValid("TANKER.ASM"))
        // Case-insensitive store: a lowercase canonical name is legal input.
        assertTrue(FirmwareFileNames.isValid("tanker.asm"))
        assertTrue(FirmwareFileNames.isValid("TANKER_2.ASM"))
        assertFalse(FirmwareFileNames.isValid("bad name"))
        assertFalse(FirmwareFileNames.isValid("tanker.txt"))
        assertFalse(FirmwareFileNames.isValid("A"))
        assertFalse(FirmwareFileNames.isValid(""))
    }

    @Test
    fun baseNameDropsTheExtension() {
        assertEquals("TANKER", FirmwareFileNames.baseName("TANKER.ASM"))
        assertEquals("TANKER", FirmwareFileNames.baseName("tanker.asm"))
        assertEquals("NOEXT", FirmwareFileNames.baseName("NOEXT"))
    }

    @Test
    fun duplicateNamingScheme() {
        assertEquals("TANKER.ASM", FirmwareFileNames.uniqueName("TANKER.ASM", emptySet()))
        assertEquals("TANKER_2.ASM", FirmwareFileNames.uniqueName("TANKER.ASM", setOf("TANKER.ASM")))
        assertEquals(
            "TANKER_3.ASM",
            FirmwareFileNames.uniqueName("TANKER.ASM", setOf("TANKER.ASM", "TANKER_2.ASM")),
        )
        // Collision detection is case-insensitive.
        assertEquals("TANKER_2.ASM", FirmwareFileNames.uniqueName("TANKER.ASM", setOf("tanker.asm")))
        assertEquals("TANKER-COPY.ASM", FirmwareFileNames.duplicateName("TANKER.ASM"))
    }

    @Test
    fun uniqueNameKeepsMaximalBaseBounded() {
        val maximal = "ABCDEFGHIJKLMNOPQRSTUVWX" + FirmwareFileNames.STORE_EXT
        val next = FirmwareFileNames.uniqueName(maximal, setOf(maximal))
        assertEquals("ABCDEFGHIJKLMNOPQRSTUV_2" + FirmwareFileNames.STORE_EXT, next)
        assertTrue(next.length <= FirmwareFileNames.MAX_NAME_LENGTH)
        // The renamed candidate does not collide with the maximal original.
        assertEquals(next, FirmwareFileNames.uniqueName(maximal, setOf(maximal, "ABCDEFGHIJKLMNOPQRSTUV" + FirmwareFileNames.STORE_EXT)))
    }
}
