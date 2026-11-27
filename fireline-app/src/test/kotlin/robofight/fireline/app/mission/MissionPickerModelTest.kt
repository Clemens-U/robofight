package robofight.fireline.app.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.FIRELINE_ENGINE_VERSION
import robofight.fireline.FailureReason
import robofight.fireline.Mission
import robofight.fireline.Missions
import robofight.fireline.RunStatus
import robofight.fireline.session.RunSummary

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

    // ---- catalog and unlock rule (PLAN-RECONCILE-MAIN-01.md 10 item 6) --------
    // Owner decision 3 shape (b): ids 01 to 03 are free, ids 04 to 06 unlock on a
    // recorded SUCCESS in the previous mission in catalog order. This replaces
    // the released line's fixed free trio (ids 01, 02 and 04), which no longer
    // exists on this line: no `availableMissions` list, no "free edition" guard.

    /** Same read the cards and the picker use: the stored best badge per mission. */
    private fun badgeOf(vararg summaries: RunSummary): (Mission) -> RunSummary? =
        { mission -> summaries.firstOrNull { it.missionId == mission.id } }

    @Test
    fun freeTrioIsOpenOnAFreshInstallAndGatedIdsStartLocked() {
        val cards = MissionPickerModel.cards { null }
        assertEquals(listOf(false, false, false, true, true, true), cards.map { it.locked })
        assertEquals(listOf(1, 2, 3), MissionPickerModel.unlockedMissions { null }.map { it.id })
        assertEquals(listOf(4, 5, 6), cards.filter { it.locked }.map { it.missionId })
        assertTrue(cards.take(3).all { it.lockLabel == null })
        assertTrue(cards.drop(3).all { it.lockLabel != null })
    }

    @Test
    fun gateForEveryIdIsTheCatalogPredecessor() {
        assertEquals(listOf(1, 2, 3), MissionPickerModel.FREE_MISSION_IDS)
        assertEquals(listOf(null, null, null), (1..3).map { MissionPickerModel.gateIdFor(it) })
        assertEquals(mapOf(4 to 3, 5 to 4, 6 to 5), (4..6).associateWith { MissionPickerModel.gateIdFor(it) })
    }

    @Test
    fun predecessorSuccessOpensItsMissionStepByStep() {
        val success3 = runSummarySample(RunStatus.SUCCESS, null, missionId = 3, tick = 77)
        val success4 = runSummarySample(RunStatus.SUCCESS, null, missionId = 4, tick = 300)
        val success5 = runSummarySample(RunStatus.SUCCESS, null, missionId = 5, tick = 180)

        assertEquals(listOf(1, 2, 3, 4), MissionPickerModel.unlockedMissions(badgeOf(success3)).map { it.id })
        assertEquals(listOf(1, 2, 3, 4, 5), MissionPickerModel.unlockedMissions(badgeOf(success3, success4)).map { it.id })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), MissionPickerModel.unlockedMissions(badgeOf(success3, success4, success5)).map { it.id })
        // A card unlocks on its own predecessor badge, not on a chain check: a
        // DOWNWIND success stored by the released free trio opens 05 here even
        // while 04 itself is still locked. Earned progress is never taken back.
        assertEquals(listOf(1, 2, 3, 5), MissionPickerModel.unlockedMissions(badgeOf(success4)).map { it.id })
        assertTrue(MissionPickerModel.isUnlocked(4, badgeOf(success3)))
        assertFalse(MissionPickerModel.isUnlocked(5, badgeOf(success3)))
    }

    @Test
    fun failureOrNoRunKeepsTheNextMissionLocked() {
        val failure3 = runSummarySample(RunStatus.FAILURE, FailureReason.TIME_EXPIRED, missionId = 3, tick = 120)
        assertEquals(listOf(1, 2, 3), MissionPickerModel.unlockedMissions(badgeOf(failure3)).map { it.id })
        assertFalse(MissionPickerModel.isUnlocked(4, badgeOf(failure3)))

        // Unlock consults the same version-scoped badge read the card badge and
        // the picker use (`BestBadgeStore.get(id, FIRELINE_ENGINE_VERSION)`), so
        // a SUCCESS stored in an older engine lineage keeps the card locked.
        val badges = BestBadgeStore(FakeKeyValues())
        badges.update(runSummarySample(RunStatus.SUCCESS, null, engineVersion = FIRELINE_ENGINE_VERSION - 1, missionId = 3, tick = 60))
        val shippedLineage: (Mission) -> RunSummary? = { badges.get(it.id, FIRELINE_ENGINE_VERSION) }
        assertFalse(MissionPickerModel.isUnlocked(4, shippedLineage))
        assertEquals(listOf(1, 2, 3), MissionPickerModel.unlockedMissions(shippedLineage).map { it.id })
        assertTrue(badges.update(runSummarySample(RunStatus.SUCCESS, null, missionId = 3, tick = 77)))
        assertTrue(MissionPickerModel.isUnlocked(4, shippedLineage))
        assertEquals(listOf(1, 2, 3, 4), MissionPickerModel.unlockedMissions(shippedLineage).map { it.id })
    }

    /** Minimal KeyValues fake so the badge lineage is testable here too. */
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
    fun lockedCardCopyNamesThePredecessorAndUsesNoDashCharacters() {
        assertEquals(
            "mission 04 is locked  //  a SUCCESS in 03 FIRST RESPONSE opens it",
            MissionPickerModel.lockLabel(4),
        )
        assertEquals(
            "mission 06 is locked  //  a SUCCESS in 05 HOT ZONE opens it",
            MissionPickerModel.lockLabel(6),
        )
        val labels = MissionPickerModel.cards { null }.mapNotNull { it.lockLabel } + MissionPickerModel.LINEUP_LABEL
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.none { it.contains('\u2014') || it.contains('\u2013') }, "no em or en dashes in card copy: $labels")
        assertEquals(
            "missions 01 to 03 are free  //  a SUCCESS in a mission opens the next one",
            MissionPickerModel.LINEUP_LABEL,
        )
        // Free cards carry no lock copy at all and never consult a gate.
        assertTrue(MissionPickerModel.isUnlocked(2) { null })
        assertTrue(MissionPickerModel.cards { null }.take(3).none { it.locked })
    }

    /**
     * The MISSION card copy for a mission with no recorded run states the engine
     * version it is looking for, read from `FIRELINE_ENGINE_VERSION`
     * (`MissionSurface.emptyRunLabel`, rendered at `MissionSurface.kt:84`).
     */
    @Test
    fun emptyCardCopyNamesTheShippedEngineVersion() {
        assertEquals("no run recorded yet for engine v$FIRELINE_ENGINE_VERSION", MissionSurface.emptyRunLabel)
        assertEquals("no run recorded yet for engine v4", MissionSurface.emptyRunLabel)
        val card = MissionPickerModel.cardFor(Missions.byId(2)!!, null)
        assertNull(card.badgeLabel)
    }
}
