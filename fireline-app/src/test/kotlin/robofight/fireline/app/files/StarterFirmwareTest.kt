package robofight.fireline.app.files

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.FirelineRun
import robofight.fireline.Missions

/**
 * First-use starter firmware (released line, commit e4e3bcd): a brand-new
 * FIRELINE library gets exactly one runnable file, and an existing library is
 * never touched. The source also has to assemble and complete mission 01,
 * because it is what a new player sees first.
 */
class StarterFirmwareTest {

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

    @Test
    fun starterAssemblesAndCompletesNozzleCheck() {
        val nozzleCheck = Missions.byId(1)!!
        val assembly = robofight.fireline.app.editor.EditorModel.assembleSource(StarterFirmware.SOURCE)
        assertTrue(assembly.ok, "the shipped starter must assemble: ${assembly.errors}")
        val result = FirelineRun.runWithGuard(nozzleCheck, nozzleCheck.descriptorForSource(StarterFirmware.SOURCE))
        assertTrue(result.success, "the shipped starter must finish mission 01: ${result.failureReason}")
    }
}
