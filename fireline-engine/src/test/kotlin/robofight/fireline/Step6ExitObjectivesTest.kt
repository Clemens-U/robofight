package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §8.3 step 6: exit, composable objectives (§7.3), end-condition precedence
 * (§7.4) and the exit / MISSION ports (§6.3).
 */
class Step6ExitObjectivesTest {

    private val moveLoop = "loop:  MOVE\n       JMP    loop"
    private val idle = "loop:  NOP\n       JMP    loop"

    private fun exitAt(x: Int, y: Int) =
        editRows(openTerrainRows(2, 2), listOf(Triple(x, y, GLYPH_EXIT)))

    @Test
    fun reachingTheExitCompletesAReachExitMission() {
        val w = worldOf(
            terrain = exitAt(5, 2),
            firmwareSource = moveLoop,
            objectives = Objectives.REACH_EXIT,
        )
        repeat(7) { w.tickOnce() }
        assertFalse(w.finished, "the rover reaches (4,2) but not the exit yet")
        w.tickOnce()                                       // MOVE completes on (5,2)
        assertTrue(w.finished)
        assertEquals(8, w.tick)
        assertEquals(RunStatus.SUCCESS, w.status)
        assertNull(w.failureReason)
        assertTrue(w.roverOnExit())
    }

    @Test
    fun reachingTheExitEarlyDoesNotCompleteACombinedMission() {
        val w = worldOf(
            terrain = editRows(
                openTerrainRows(2, 2),
                listOf(Triple(3, 2, GLYPH_FUEL), Triple(4, 2, GLYPH_EXIT)),
            ),
            fire = fireRows(listOf(Triple(3, 2, '1'))),
            firmwareSource = "work:  SHOOT\n        MOVE\n        MOVE\n        JMP     work",
            objectives = Objectives.REACH_EXIT or Objectives.EXTINGUISH_ALL,
        )
        repeat(3) { w.tickOnce() }                          // SHOOT completes, cell (3,2) out
        assertEquals(0, w.fireAt(3, 2))
        assertFalse(w.finished, "the rover is nowhere near the exit")
        assertEquals(0, w.rover.portIn(FirelinePorts.MISSION) and MISSION_BIT_ON_EXIT)
        assertEquals(0, w.rover.portIn(FirelinePorts.MISSION) and MISSION_BIT_OBJECTIVES_MET)

        repeat(4) { w.tickOnce() }                          // ticks 4-7: two MOVEs
        assertEquals(4, w.rover.x)
        assertTrue(w.finished, "exit + extinguished on the same tick")
        assertEquals(7, w.tick)
        assertEquals(RunStatus.SUCCESS, w.status)
    }

    @Test
    fun missionPortReportsTheObjectiveBitField() {
        val w = worldOf(
            terrain = exitAt(5, 2),
            fire = fireRows(listOf(Triple(9, 9, '1'))),
            firmwareSource = "loop:  IN     ${FirelinePorts.MISSION}\n       JMP    loop",
            objectives = Objectives.REACH_EXIT or Objectives.EXTINGUISH_ALL,
            timeLimit = 100,
        )
        w.tickOnce()
        assertEquals(0, w.rover.vm.a and MISSION_BIT_ON_EXIT, "not on the exit")
        assertEquals(0, w.rover.vm.a and MISSION_BIT_FIRE_OUT, "a cell is still burning")
        assertEquals(0, w.rover.vm.a and MISSION_BIT_FINISHED)
        assertEquals(MISSION_BIT_CIVILIANS_RESCUED, w.rover.vm.a and MISSION_BIT_CIVILIANS_RESCUED,
            "no required civilians means the civilian condition is trivially true")

        w.runUntilFinished(200)
        assertEquals(RunStatus.FAILURE, w.status, "the firmware never reaches the exit")
        assertEquals(FailureReason.TIME_EXPIRED, w.failureReason)
        val bits = w.missionBits()
        assertEquals(MISSION_BIT_FINISHED, bits and MISSION_BIT_FINISHED)
        assertEquals(0, bits and MISSION_BIT_OBJECTIVES_MET)
        assertEquals(0, bits and MISSION_BIT_FIRE_OUT, "the IN loop never extinguishes the fire")
    }

    @Test
    fun exitSensorsReportDistanceAndAngleOrSentinels() {
        val w = worldOf(
            terrain = exitAt(2, 5),
            firmwareSource = idle,
            objectives = Objectives.REACH_EXIT,
        )
        assertEquals(3, w.rover.portIn(FirelinePorts.EXIT_DIST))
        assertEquals(1, w.rover.portIn(FirelinePorts.EXIT_ANGLE), "clockwise delta east -> south")

        val onExit = worldOf(terrain = exitAt(3, 2), firmwareSource = moveLoop, objectives = Objectives.REACH_EXIT)
        repeat(2) { onExit.tickOnce() }                            // MOVE completes onto the exit
        assertEquals(3, onExit.rover.x)
        assertEquals(0, onExit.rover.portIn(FirelinePorts.EXIT_DIST), "already on the exit")

        val noExit = worldOf(firmwareSource = idle, objectives = Objectives.EXTINGUISH_ALL)
        assertEquals(NO_TARGET_DIST, noExit.rover.portIn(FirelinePorts.EXIT_DIST))
        assertEquals(NO_TARGET_ANGLE, noExit.rover.portIn(FirelinePorts.EXIT_ANGLE))
    }

    @Test
    fun reachExitObjectiveRequiresAnExitCell() {
        val problems = descriptorOf(layoutOf(), firmwareSource = idle, objectives = Objectives.REACH_EXIT).validate()
        assertTrue(problems.any { it.contains("REACH_EXIT objective requires an exit cell") }, problems.toString())
        assertThrows(IllegalArgumentException::class.java) {
            worldOf(firmwareSource = idle, objectives = Objectives.REACH_EXIT)
        }
    }

    @Test
    fun roverDestructionBeatsCivilianLossWhenBothHappenOnOneTick() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(10, 10, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(2, 2, '4'), Triple(10, 10, '4'))),   // 4 HP/tick each
            firmwareSource = idle,
            objectives = Objectives.RESCUE_ALL,
        )
        repeat(24) { w.tickOnce() }
        assertEquals(4, w.rover.hp, "both are burning down together")
        assertFalse(w.finished)
        w.tickOnce()                                       // tick 25: rover and civilian both hit 0
        assertTrue(w.finished)
        assertEquals(RunStatus.FAILURE, w.status)
        assertEquals(FailureReason.ROVER_DESTROYED, w.failureReason, "§7.4 order 2 before 3")
        assertEquals(1, w.civiliansLost(), "the full final state remains available")
    }

    @Test
    fun firmwareFaultBeatsRoverDestruction() {
        // The program region is zero-padded with defined MOVs, so a fault needs a
        // firmware binary containing an undefined opcode (the import path can ship
        // one even though the assembler cannot emit it).
        val layout = layoutOf(fire = fireRows(listOf(Triple(2, 2, '4'))))  // 4 HP/tick on spawn
        val undefinedOpcodeImage = byteArrayOf(0xB9.toByte(), 0, 0)
        val w = FirelineWorld(
            MissionDescriptor(
                missionId = 1, seed = 1234,
                objectives = Objectives.EXTINGUISH_ALL,
                firmware = undefinedOpcodeImage, layout = layout,
            ),
        )
        w.rover.hp = 4
        w.tickOnce()                                     // fault AND rover hits 0 on one tick
        assertTrue(w.rover.vm.fault, w.lastOp)
        assertTrue(w.lastOp.contains("undefined opcode"), w.lastOp)
        assertEquals(0, w.rover.hp)
        assertTrue(w.finished)
        assertEquals(FailureReason.FIRMWARE_FAULT, w.failureReason, "§7.4 order 1 beats order 2")
    }

    @Test
    fun aMissionWithoutFirmwareIsRejectedBeforeTheWorldStarts() {
        val problems = MissionDescriptor(
            missionId = 1, seed = 1, objectives = Objectives.EXTINGUISH_ALL,
            firmware = ByteArray(0), layout = layoutOf(),
        ).validate()
        assertTrue(problems.any { it.contains("firmware is empty") }, problems.toString())
    }

    @Test
    fun requiredCivilianLossFailsImmediatelyOnlyWhenRescueAllIsConfigured() {
        val strict = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(10, 10, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(10, 10, '4'))),
            firmwareSource = idle,
            objectives = Objectives.RESCUE_ALL,
        )
        repeat(24) { strict.tickOnce() }
        assertFalse(strict.finished)
        strict.tickOnce()
        assertEquals(FailureReason.CIVILIAN_LOST, strict.failureReason)
        assertEquals(25, strict.tick)

        val lenient = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(10, 10, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(10, 10, '4'))),
            firmwareSource = idle,
            objectives = Objectives.EXTINGUISH_ALL,
            timeLimit = 30,
        )
        lenient.runUntilFinished()
        assertEquals(1, lenient.civiliansLost())
        assertEquals(FailureReason.TIME_EXPIRED, lenient.failureReason,
            "no RESCUE_ALL objective means a lost civilian is not a failure")
    }

    @Test
    fun protectedAssetFailureFailsOnlyWhenProtectAssetsIsConfigured() {
        val terrain = editRows(openTerrainRows(2, 2), listOf(Triple(6, 2, GLYPH_PROTECTED)))
        val fire = fireRows(listOf(Triple(6, 2, '4')))     // asset already at 100

        val protecting = worldOf(
            terrain = terrain, fire = fire, firmwareSource = idle,
            objectives = Objectives.PROTECT_ASSETS,
        )
        protecting.tickOnce()
        assertTrue(protecting.finished)
        assertEquals(1, protecting.assetsLost())
        assertEquals(FailureReason.ASSET_LOST, protecting.failureReason)
        assertEquals(0, protecting.assetsIntact())

        val ignoring = worldOf(
            terrain = terrain, fire = fire, firmwareSource = idle,
            objectives = Objectives.EXTINGUISH_ALL, timeLimit = 5,
        )
        ignoring.runUntilFinished()
        assertEquals(1, ignoring.assetsLost(), "the asset still fails")
        assertEquals(FailureReason.TIME_EXPIRED, ignoring.failureReason,
            "but only PROTECT_ASSETS turns it into a mission failure")
    }

    @Test
    fun timeLimitExpiresUnlessTheObjectivesCompleteOnTheLastAllowedTick() {
        val lastTick = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_FUEL))),
            fire = fireRows(listOf(Triple(3, 2, '1'))),
            firmwareSource = "once:  SHOOT",
            objectives = Objectives.EXTINGUISH_ALL,
            timeLimit = 3,
        )
        lastTick.runUntilFinished()
        assertEquals(3, lastTick.tick)
        assertEquals(RunStatus.SUCCESS, lastTick.status, "success on the final allowed tick (§7.4)")

        val tooSlow = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_FUEL))),
            fire = fireRows(listOf(Triple(3, 2, '1'))),
            firmwareSource = "once:  SHOOT",
            objectives = Objectives.EXTINGUISH_ALL,
            timeLimit = 2,
        )
        tooSlow.runUntilFinished()
        assertEquals(2, tooSlow.tick)
        assertEquals(FailureReason.TIME_EXPIRED, tooSlow.failureReason)
        assertEquals(25, tooSlow.fireAt(3, 2))
    }

    @Test
    fun surviveUntilCompletesWhenRoverAndAssetsAreStillIntact() {
        val w = worldOf(
            firmwareSource = idle,
            objectives = Objectives.SURVIVE_UNTIL,
            surviveUntil = 6,
        )
        repeat(5) { w.tickOnce() }
        assertFalse(w.finished, "the survival timer has not elapsed")
        assertEquals(0, w.rover.portIn(FirelinePorts.MISSION) and MISSION_BIT_SURVIVED)
        w.tickOnce()
        assertEquals(6, w.tick)
        assertEquals(RunStatus.SUCCESS, w.status)
        assertEquals(MISSION_BIT_SURVIVED, w.missionBits() and MISSION_BIT_SURVIVED)

        val doomed = worldOf(
            fire = fireRows(listOf(Triple(2, 2, '4'))),
            firmwareSource = idle,
            objectives = Objectives.SURVIVE_UNTIL,
            surviveUntil = 6,
        )
        doomed.rover.hp = 8
        doomed.runUntilFinished()
        assertEquals(2, doomed.tick)
        assertEquals(FailureReason.ROVER_DESTROYED, doomed.failureReason)
    }

    @Test
    fun aFinishedWorldStopsMutating() {
        val w = worldOf(terrain = exitAt(3, 2), firmwareSource = moveLoop, objectives = Objectives.REACH_EXIT)
        repeat(2) { w.tickOnce() }                            // MOVE completes onto the exit
        assertTrue(w.finished)
        val tick = w.tick
        val hp = w.rover.hp
        repeat(3) { w.tickOnce() }
        assertEquals(tick, w.tick, "§8 step 1: a finished world returns without mutation")
        assertEquals(hp, w.rover.hp)
        assertEquals(MISSION_BIT_FINISHED, w.rover.portIn(FirelinePorts.MISSION) and MISSION_BIT_FINISHED)
    }
}
