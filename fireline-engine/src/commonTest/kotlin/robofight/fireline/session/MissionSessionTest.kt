package robofight.fireline.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test
    fun sessionLogAndHexPaddingKeepTheirShape() {
        // The session layer renders its own text, so it was moved off
        // `String.format` (unavailable on wasmJs) onto `robofight.isa.decPad`/
        // `hexPad`, the same helpers the engine renderer uses (docs/WEB-W1-RECORD.md
        // 4: "output-identical formatting instead of platform conditionals"). This
        // pins the shape of those strings on BOTH targets: three zero-padded ticks,
        // the ` | ` separator the MISSION panel shows, and uppercase three-digit hex.
        val mission = Missions.byId(2)!!
        val session = MissionSession(mission, "REFERENCE.ASM", mission.referenceSource)
        repeat(3) { session.step() }
        assertEquals("001", session.eventLog[0].substringBefore(" "))
        assertEquals("003", session.eventLog[2].substringBefore(" "))
        assertTrue(session.eventLog[2].contains(" | "))
        val t = session.telemetry()
        assertTrue(Regex("^\\$[0-9A-F]{3}$").matches(t.pcHex))
        assertTrue(Regex("^\\$[0-9A-F]{3}$").matches(t.spHex))
        assertEquals(t.pcHex, t.pcHex.uppercase())
    }

    @Test
    fun telemetrySeparatesRoverPositionFromVmRegisters() {
        // The grid accessibility line used to read `TelemetrySnapshot.x/y`, which are the
        // VM X/Y registers, so it announced a rover that never moved. Mission 02 pins the
        // body position at the committed engine anchors (docs/web/fixtures/snapshots.json:
        // tick 12 x=2 y=7 dir=S, tick 46 x=6 y=10 dir=E).
        val mission = Missions.byId(2)!!
        val session = MissionSession(mission, "REFERENCE.ASM", mission.referenceSource)

        repeat(12) { session.step() }
        val t12 = session.telemetry()
        assertEquals(12, t12.tick)
        assertEquals(2, t12.roverX)
        assertEquals(7, t12.roverY)
        assertEquals("S", t12.roverFacing)

        while (session.tick < 46 && !session.finished) {
            session.step()
        }
        val t46 = session.telemetry()
        assertEquals(46, t46.tick)
        assertEquals(6, t46.roverX)
        assertEquals(10, t46.roverY)
        assertEquals("E", t46.roverFacing)

        // The rover fields are the engine body state; X/Y are the firmware's own registers.
        val rover = session.currentWorld.rover
        assertEquals(rover.x, t46.roverX)
        assertEquals(rover.y, t46.roverY)
        assertEquals(rover.vm.x, t46.x)
        assertEquals(rover.vm.y, t46.y)
    }
}
