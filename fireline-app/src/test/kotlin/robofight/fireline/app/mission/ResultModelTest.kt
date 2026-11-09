package robofight.fireline.app.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.FailureReason
import robofight.fireline.FirelineRun
import robofight.fireline.Missions
import robofight.fireline.RunStatus

/**
 * RESULT formatting rules (DESIGN-FIRELINE.md 5.3) as pure text tests: the
 * banner, the failure chips (exact `FailureReason` enum names), the stat
 * block layout, and the fingerprint short form ($hex8 = top 32 bits of the
 * 64-bit final-state hash).
 */
class ResultModelTest {

    private fun sample(): RunSummary = runSummarySample(status = RunStatus.SUCCESS, failureReason = null)

    @Test
    fun successBannerCarriesTheMissionIdNameAndTick() {
        assertEquals("MISSION COMPLETE", ResultModel.banner(sample()))
        assertEquals("MISSION 04 / DOWNWIND   TICK 120/120", ResultModel.missionLine(sample()))
    }

    @Test
    fun failureBannerPrintsTheExactReasonEnumName() {
        for (reason in FailureReason.entries) {
            val summary = ResultModelTestHelpers.failureSample(reason)
            assertEquals("FAILURE: ${reason.name}", ResultModel.banner(summary))
        }
        // Engine authority: drive the mission 2 reference program tick-by-tick
        // and print exactly whatever status the engine reports - the banner
        // never paraphrases or invents a reason.
        val mission2 = Missions.byId(2)!!
        val engineResult = FirelineRun.run(mission2)
        val fromEngine = MissionSession(mission2, "REFERENCE.ASM", mission2.referenceSource)
        var guard = 0
        while (!fromEngine.finished && guard < 10_000) {
            fromEngine.step()
            guard++
        }
        val expectedBanner = if (engineResult.success) {
            "MISSION COMPLETE"
        } else {
            "FAILURE: ${engineResult.failureReason!!.name}"
        }
        assertEquals(expectedBanner, ResultModel.banner(fromEngine.summary()))
    }

    @Test
    fun missionLineFallsBackWhenTheCatalogNoLongerCarriesTheId() {
        val summary = sample().copy(missionId = 99)
        assertEquals("MISSION 99 / UNKNOWN MISSION   TICK 120/120", ResultModel.missionLine(summary))
    }

    @Test
    fun objectiveChipsListConfiguredObjectivesWithMetState() {
        // objectiveMask 7 = EXTINGUISH_ALL | RESCUE_ALL | REACH_EXIT; met 1 = extinguish only.
        val chips = ResultModel.objectiveChips(sample())
        assertEquals(
            listOf(
                "EXTINGUISH_ALL" to true,
                "RESCUE_ALL" to false,
                "REACH_EXIT" to false,
            ),
            chips,
        )
    }

    @Test
    fun statBlockFollowsTheWireframeLayout() {
        assertEquals(
            listOf(
                "ticks 120          water used 60",
                "water left 40     shots 6",
                "HP 34   HEAT 62   SHIELD 0",
                "civilians rescued 1  waiting 1  lost 0",
                "burning cells 3  total intensity 125",
                "protected intact 2  lost 0",
                "firmware 66 B / 22 instructions",
                "fingerprint ${ResultModel.fingerprintShort(sample().finalStateHash)}",
            ),
            ResultModel.statLines(sample()),
        )
    }

    @Test
    fun fingerprintShortFormIsTop32BitsOfTheEngineHash() {
        assertEquals("$7c31a90e", ResultModel.fingerprintShort("7c31a90edeadbeef"))
        val engineHash = FirelineRun.run(Missions.byId(1)!!).finalStateHash
        assertEquals(16, engineHash.length)
        assertEquals("$" + engineHash.substring(0, 8), ResultModel.fingerprintShort(engineHash))
    }

    @Test
    fun firmwareFaultAddsHaltReasonAndLastInstruction() {
        val fault = ResultModelTestHelpers.failureSample(FailureReason.FIRMWARE_FAULT)
        val halt = ResultModel.haltLines(fault)
        assertEquals(2, halt.size)
        assertEquals("HALT: FIRMWARE_FAULT", halt[0])
        assertEquals("last instruction: IN port30", halt[1])
        assertTrue(ResultModel.haltLines(sample()).isEmpty())
    }

    @Test
    fun whyBlockUsesOnlyThePersistedEventTail() {
        assertEquals(sample().lastEventTail, ResultModel.whyLines(sample()))
        assertTrue(ResultModel.whyLines(sample().copy(lastEventTail = emptyList())).isEmpty())
    }
}

/** Fixtures that force each failure reason without contriving engine behavior. */
private object ResultModelTestHelpers {
    fun failureSample(reason: FailureReason) = runSummarySample(
        status = RunStatus.FAILURE,
        failureReason = reason,
        lastOp = "IN port30",
    )
}
