package robofight.fireline.app.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.FailureReason
import robofight.fireline.RunStatus

/** MISSION persistence rules on the pure KeyValues seam (no Android here). */
class RunStoresTest {

    private class FakeKeyValues : KeyValues {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun firmwareSelectionRoundtripsAndClears() {
        val selection = FirmwareSelection(FakeKeyValues())
        assertNull(selection.get())
        selection.set("CORRIDOR.ASM")
        assertEquals("CORRIDOR.ASM", selection.get())
        selection.set("HOTZ.ASM")
        assertEquals("HOTZ.ASM", selection.get())
        selection.clear()
        assertNull(selection.get())
        // The store uses the same key milestone 3 writes for the FILES `[>]`
        // selection, so both tabs read one value.
        assertEquals("firmware", FirmwareSelection.KEY)
    }

    @Test
    fun missionSelectionRoundtripsInts() {
        val selection = MissionSelection(FakeKeyValues())
        assertNull(selection.get())
        selection.set(5)
        assertEquals(5, selection.get())
        selection.clear()
        assertNull(selection.get())
    }

    @Test
    fun bestBadgeWritesFirstRunAndOnlyReplacedByBetterRank() {
        val store = BestBadgeStore(FakeKeyValues())
        val first = runSummarySample(tick = 120)
        assertTrue(store.update(first))
        assertEquals(first, store.get(4, 1))

        // §7.5 ranks failures by civilians and remaining fire intensity, not
        // by tick count: an equal-rank failure keeps the badge.
        assertFalse(store.update(runSummarySample(tick = 90)))
        val lessFire = runSummarySample(tick = 120, fireIntensitySum = 100)
        assertTrue(store.update(lessFire))
        assertEquals(100, store.get(4, 1)?.fireIntensitySum)

        // A success outranks a failure regardless of tick count.
        assertTrue(store.update(runSummarySample(RunStatus.SUCCESS, null, tick = 300)))
        assertEquals(RunStatus.SUCCESS, store.get(4, 1)?.status)
        // Among successes the engine prefers fewer ticks.
        assertTrue(store.update(runSummarySample(RunStatus.SUCCESS, null, tick = 200)))
        assertEquals(200, store.get(4, 1)?.tick)
    }

    @Test
    fun bestBadgesAreScopedToEngineVersionAndMission() {
        val store = BestBadgeStore(FakeKeyValues())
        store.update(runSummarySample(engineVersion = 1, missionId = 4))
        assertNotNull(store.get(4, 1))
        assertNull(store.get(4, 2))
        assertNull(store.get(2, 1))
        // A different engine version starts its own badge lineage.
        assertTrue(store.update(runSummarySample(engineVersion = 2, missionId = 4, tick = 500)))
        assertEquals(120, store.get(4, 1)?.tick)
        assertEquals(500, store.get(4, 2)?.tick)
    }

    @Test
    fun runRecordRoundtripsEverythingIncludingEventTail() {
        val store = RunRecordStore(FakeKeyValues())
        assertNull(store.get())
        val sample = runSummarySample(
            lastEventTail = listOf(
                "118 IN port18 | ",
                "112  | FIRE IGNITES 5,3 floor from 4,2 dir=E chance=42<64",
                "100 SHOOT (3/3) | PACKET -> 5,2",
            ),
        )
        store.put(sample)
        val decoded = store.get()
        assertNotNull(decoded)
        assertEquals(sample, decoded)
        // Values containing '=' or ',' survive the first-'=' split intact.
        assertEquals(
            "112  | FIRE IGNITES 5,3 floor from 4,2 dir=E chance=42<64",
            decoded?.lastEventTail?.get(1),
        )
        store.clear()
        assertNull(store.get())
    }

    @Test
    fun decodeRefusesGarbageAndKeepsNullOptionalFields() {
        assertNull(RunSummary.decode("not a summary"))
        assertNull(RunSummary.decode("status=SUCCESS\nmission=1"))
        val noFailureNoLimit = runSummarySample(RunStatus.SUCCESS, null, timeLimit = null)
        assertEquals(noFailureNoLimit, RunSummary.decode(noFailureNoLimit.encode()))
        val dry = runSummarySample(failureReason = FailureReason.TIME_EXPIRED)
        assertEquals(dry, RunSummary.decode(dry.encode()))
    }
}
