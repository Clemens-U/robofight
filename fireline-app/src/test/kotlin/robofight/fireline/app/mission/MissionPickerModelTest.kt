package robofight.fireline.app.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.FIRELINE_ENGINE_VERSION
import robofight.fireline.FailureReason
import robofight.fireline.Missions
import robofight.fireline.RunStatus

/**
 * The MISSION picker model: every card field must be the engine's authored
 * value (ids, names, verbatim briefings, objective names, limits, wind, water,
 * seeds), and the badge text must use the engine's enum names.
 */
class MissionPickerModelTest {

    @Test
    fun freeCatalogContainsOnlyMissionsOneTwoAndFourWithStableIds() {
        val cards = MissionPickerModel.cards { null }
        assertEquals(listOf("01", "02", "04"), cards.map { it.idLabel })
        assertEquals(
            listOf("NOZZLE CHECK", "CORRIDOR", "DOWNWIND"),
            cards.map { it.name },
        )
        assertEquals(listOf(1, 2, 4), cards.map { it.missionId })
        assertNull(MissionPickerModel.availableById(3))
        assertNull(MissionPickerModel.availableById(5))
        assertEquals(Missions.DOWNWIND, MissionPickerModel.availableById(4))
    }

    @Test
    fun briefingsAreVerbatimEngineText() {
        val cards = MissionPickerModel.cards { null }
        assertEquals(MissionPickerModel.availableMissions.map { it.briefing }, cards.map { it.briefing })
        assertEquals(
            "One stationary fire straight ahead. Read DIST/ANGLE, shoot, watch HEAT.",
            cards.first().briefing,
        )
    }

    @Test
    fun objectiveChipsUseEngineNamesInFlagOrder() {
        val cards = MissionPickerModel.cards { null }
        assertEquals(listOf("EXTINGUISH_ALL"), cards[0].objectiveChips)
        assertEquals(listOf("EXTINGUISH_ALL", "REACH_EXIT"), cards[1].objectiveChips)
        assertEquals(listOf("EXTINGUISH_ALL", "RESCUE_ALL", "REACH_EXIT"), cards[2].objectiveChips)
    }

    @Test
    fun rulesMatchTheAuthoredMissionNumbers() {
        val cards = MissionPickerModel.cards { null }
        assertEquals(listOf(60, 120, 500), cards.map { it.timeLimit })
        assertEquals(listOf(1001, 2002, 4004), cards.map { it.seedLabel.removePrefix("SEED ").removeSuffix(" (FIXED)").toInt() })
        assertEquals(listOf("W x0", "W x0", "E x2"), cards.map { it.windLabel })
        assertEquals(listOf(100, 100, 100), cards.map { it.startingWater })
        assertEquals(listOf("LIMIT 60 TICKS", "LIMIT 120 TICKS"), cards.take(2).map { it.timeLimitLabel })
        // The seed line is read-only display only; no editor affordance exists in v1.
        assertTrue(cards.all { it.seedLabel.contains("(FIXED)") })
    }

    @Test
    fun badgeTextUsesEngineEnumNames() {
        val success = runSummarySample(RunStatus.SUCCESS, null, tick = 34, engineVersion = FIRELINE_ENGINE_VERSION)
        val failure = runSummarySample(RunStatus.FAILURE, FailureReason.CIVILIAN_LOST, tick = 112)
        assertEquals("BEST: SUCCESS  TICK 34", MissionPickerModel.badgeLabel(success))
        assertEquals("BEST: FAILURE CIVILIAN_LOST  TICK 112", MissionPickerModel.badgeLabel(failure))
        assertNull(MissionPickerModel.cardFor(Missions.byId(2)!!, null).badgeLabel)
        assertEquals(FIRELINE_ENGINE_VERSION, success.engineVersion)
    }
}
