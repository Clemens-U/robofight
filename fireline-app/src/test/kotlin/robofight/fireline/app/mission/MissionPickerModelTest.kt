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
    fun catalogIsTheSixAuthoredMissionsInOrder() {
        val cards = MissionPickerModel.cards { null }
        assertEquals(listOf("01", "02", "03", "04", "05", "06"), cards.map { it.idLabel })
        assertEquals(
            listOf("NOZZLE CHECK", "CORRIDOR", "FIRST RESPONSE", "DOWNWIND", "HOT ZONE", "GO AROUND"),
            cards.map { it.name },
        )
        assertEquals(listOf(1, 2, 3, 4, 5, 6), cards.map { it.missionId })
    }

    @Test
    fun briefingsAreVerbatimEngineText() {
        val cards = MissionPickerModel.cards { null }
        assertEquals(Missions.ALL.map { it.briefing }, cards.map { it.briefing })
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
        assertEquals(listOf("RESCUE_ALL", "REACH_EXIT"), cards[2].objectiveChips)
        assertEquals(listOf("EXTINGUISH_ALL", "RESCUE_ALL", "REACH_EXIT"), cards[3].objectiveChips)
        assertEquals(listOf("RESCUE_ALL", "REACH_EXIT", "PROTECT_ASSETS"), cards[4].objectiveChips)
        // Mission 06 objectives 5 = EXTINGUISH_ALL (1) | REACH_EXIT (4)
        // (docs/web/fixtures/parity_matrix.tsv row 6-reference).
        assertEquals(listOf("EXTINGUISH_ALL", "REACH_EXIT"), cards[5].objectiveChips)
    }

    @Test
    fun rulesMatchTheAuthoredMissionNumbers() {
        val cards = MissionPickerModel.cards { null }
        // time_limit / seed / wind_dir x wind_force / starting_water per
        // docs/web/fixtures/parity_matrix.tsv rows 1..6-reference.
        assertEquals(listOf(60, 120, 120, 500, 200, 90), cards.map { it.timeLimit })
        assertEquals(listOf(1001, 2002, 3003, 4004, 5005, 6027), cards.map { it.seedLabel.removePrefix("SEED ").removeSuffix(" (FIXED)").toInt() })
        assertEquals(listOf("W x0", "W x0", "W x0", "E x2", "N x0", "W x0"), cards.map { it.windLabel })
        assertEquals(listOf(100, 100, 100, 100, 60, 100), cards.map { it.startingWater })
        assertEquals(listOf("LIMIT 60 TICKS", "LIMIT 120 TICKS"), cards.take(2).map { it.timeLimitLabel })
        // The seed line is read-only display only; no editor affordance exists in v1.
        assertTrue(cards.all { it.seedLabel.contains("(FIXED)") })
    }

    @Test
    fun badgeTextUsesEngineEnumNames() {
        val success = runSummarySample(RunStatus.SUCCESS, null, tick = 34)
        val failure = runSummarySample(RunStatus.FAILURE, FailureReason.CIVILIAN_LOST, tick = 112)
        assertEquals("BEST: SUCCESS  TICK 34", MissionPickerModel.badgeLabel(success))
        assertEquals("BEST: FAILURE CIVILIAN_LOST  TICK 112", MissionPickerModel.badgeLabel(failure))
        assertNull(MissionPickerModel.cardFor(Missions.byId(2)!!, null).badgeLabel)
        assertEquals(FIRELINE_ENGINE_VERSION, success.engineVersion)
    }

    /**
     * The MISSION card copy for a mission with no recorded run states the engine
     * version it is looking for, read from `FIRELINE_ENGINE_VERSION`
     * (`MissionSurface.emptyRunLabel`, rendered at `MissionSurface.kt:84`).
     */
    @Test
    fun emptyCardCopyNamesTheShippedEngineVersion() {
        assertEquals("no run recorded yet for engine v$FIRELINE_ENGINE_VERSION", MissionSurface.emptyRunLabel)
        assertEquals("no run recorded yet for engine v2", MissionSurface.emptyRunLabel)
        val card = MissionPickerModel.cardFor(Missions.byId(2)!!, null)
        assertNull(card.badgeLabel)
    }
}
