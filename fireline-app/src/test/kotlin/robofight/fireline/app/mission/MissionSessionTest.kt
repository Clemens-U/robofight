package robofight.fireline.app.mission

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.fireline.FirelinePorts
import robofight.fireline.FirelineRun
import robofight.fireline.Missions

/**
 * The MISSION session is the app-side twin of the headless runner (PLAN 9.5:
 * "STEP advances exactly one world tick, including multi-tick VM state";
 * FIRELINE-DESIGN 13.2 point 3: step-by-step world API == headless runner).
 * Everything asserted here is compared against `:fireline-engine`'s own output
 * or authored mission facts, never against app-computed values.
 */
class MissionSessionTest {

    private fun driveToFinish(session: MissionSession) {
        var guard = 0
        while (!session.finished && !session.guardReached && guard < 100_000) {
            session.step()
            guard++
        }
    }

    @Test
    fun stepByStepSessionEqualsRunWithGuardForEveryAuthoredMission() {
        for (mission in Missions.ALL) {
            val session = MissionSession(mission, "REFERENCE.ASM", mission.referenceSource)
            driveToFinish(session)
            val expected = FirelineRun.runWithGuard(mission, mission.descriptorForSource(mission.referenceSource))
            assertEquals(expected, session.result(), "mission ${mission.id} step-by-step result")
            assertEquals(expected.finalStateHash, session.summary().finalStateHash, "mission ${mission.id} hash")
            assertTrue(session.finished)
        }
    }

    @Test
    fun stepIsExactlyOneTickEvenMidMultiTickInstruction() {
        // CORRIDOR starts TURN R (1 tick) then MOVE (2 ticks: pending fires on
        // the second tick only).
        val mission = Missions.byId(2)!!
        val session = MissionSession(mission, "REFERENCE.ASM", mission.referenceSource)
        assertEquals(StepOutcome.ADVANCED, session.step())
        assertEquals(1, session.tick)

        assertEquals(StepOutcome.ADVANCED, session.step())
        assertEquals(2, session.tick)
        assertTrue(session.currentWorld.rover.vm.inProgress, "MOVE must still be in flight")
        assertEquals(2, session.currentWorld.rover.y, "the pending MOVE must not have landed yet")

        assertEquals(StepOutcome.ADVANCED, session.step())
        assertEquals(3, session.tick)
        assertFalse(session.currentWorld.rover.vm.inProgress)
        assertEquals(3, session.currentWorld.rover.y, "the MOVE landed on its final tick")
    }

    @Test
    fun resetRebuildsAFreshWorldFromTheSameDescriptor() {
        val mission = Missions.byId(1)!!
        val session = MissionSession(mission, "REFERENCE.ASM", mission.referenceSource)
        repeat(12) { session.step() }
        assertTrue(session.tick > 0)
        session.reset()
        assertEquals(0, session.tick)
        assertEquals(2, session.currentWorld.rover.x)
        assertEquals(2, session.currentWorld.rover.y)
        assertEquals(100, session.telemetry().water)
        assertEquals(emptyList<String>(), session.eventLog)
        assertFalse(session.guardReached)
        // A reset run replays deterministically to the same result.
        driveToFinish(session)
        val reference = FirelineRun.runWithGuard(mission, mission.descriptorForSource(mission.referenceSource))
        assertEquals(reference, session.result())
    }

    @Test
    fun telemetryReadsAuthoredValuesThroughEnginePortsOnly() {
        val nozzle = Missions.byId(1)!!
        val session = MissionSession(nozzle, "REFERENCE.ASM", nozzle.referenceSource)
        val t = session.telemetry()
        // Authored layout facts (Missions.kt): rover (2,2) facing E, one fire
        // cell (5,2) at level '2' = 50, no civilians, seed-fixed wind W x0.
        assertEquals(3, t.fireDist)
        assertEquals(50, t.fireLevel)
        assertEquals(0, t.civCount)
        assertEquals(100, t.water)
        assertEquals(100, t.hp)
        assertEquals(0, t.heat)
        assertEquals(0, t.shieldTicks)
        assertEquals("W x0", t.windLabel)
        // Engine semantics (FirelineWorld.missionBits): allRequiredRescued() is
        // vacuously true when the mission has no civilians, so bit 0x01 is set.
        assertEquals("$01", t.missionBitsHex)
        assertEquals(60, t.timeLimit)
        assertEquals(0, t.a)
        assertTrue(t.instructionLine > 0)

        session.step() // IN FIRE_LEVEL
        val after = session.telemetry()
        assertEquals(1, after.tick)
        assertEquals(50, session.currentWorld.sensor(FirelinePorts.FIRE_LEVEL))
        assertTrue(session.currentWorld.lastOp.startsWith("IN port"))
    }

    @Test
    fun eventLogKeepsEngineEventStrings() {
        val nozzle = Missions.byId(1)!!
        val session = MissionSession(nozzle, "REFERENCE.ASM", nozzle.referenceSource)
        driveToFinish(session)
        assertTrue(session.eventLog.last().contains("MISSION SUCCESS"))
        assertTrue(session.eventLog.any { it.contains("PACKET ->") })
        assertTrue(session.eventLog.any { it.contains("FIRE -") })
        // Newest-first for the panel and full-log overlay.
        assertEquals(session.eventLog.last(), session.fullLogNewestFirst().first())
    }
}
