package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §8.3 step 5: civilians and contact rescue.
 *
 * Covers FIRELINE-DESIGN.md §3.4 (rescue on MOVE completion, civilians never
 * block movement or water, HP loss, LOST at zero HP) and §6.1/§6.3 civilian
 * target selection and the CIV_* ports.
 */
class Step5CivilianRescueTest {

    private val moveLoop = "loop:  MOVE\n       JMP    loop"
    private val idle = "loop:  NOP\n       JMP    loop"

    private fun civWorld(civilians: List<CivilianSpec>, firmwareSource: String = idle) = worldOf(
        firmwareSource = firmwareSource,
        civilians = civilians,
    )

    @Test
    fun rescueHappensWhenAMoveCompletesOnTheCivilianCell() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_CIVILIAN))),
            firmwareSource = moveLoop,
        )
        val civ = w.civilians.first()
        assertEquals(1, w.civiliansWaiting())

        w.tickOnce()                                   // MOVE 1/2: no interaction yet
        assertEquals(2, w.rover.x)
        assertEquals(CivilianStatus.WAITING, civ.status, "rescue waits for the completed move")

        w.tickOnce()                                   // MOVE completes onto the civilian
        assertEquals(3, w.rover.x)
        assertEquals(CivilianStatus.RESCUED, civ.status)
        assertEquals(1, w.civiliansRescued())
        assertEquals(0, w.civiliansWaiting())
        assertTrue(w.lastEvents.any { it.contains("RESCUED") }, w.lastEvents.toString())
    }

    @Test
    fun rescueNeedsNoInstructionBeyondMoveAndDoesNotChangeMoveTiming() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_CIVILIAN))),
            firmwareSource = moveLoop,
        )
        repeat(9) { w.tickOnce() }                     // three MOVEs (2 ticks) + three JMPs
        assertEquals(5, w.rover.x, "the civilian does not stall or add ticks")
        assertEquals(3, w.rover.distanceMoved)
        assertEquals(1, w.civiliansRescued())
        assertEquals(0, w.rover.shots)
    }

    @Test
    fun rescuedCivilianTakesNoFurtherDamage() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(3, 2, '4'))),      // 100 -> 4 HP/tick
            firmwareSource = moveLoop,
        )
        val civ = w.civilians.first()
        w.tickOnce()
        assertEquals(96, civ.hp, "a waiting civilian on fire burns")
        w.tickOnce()                                       // rover arrives, rescue in step 7
        assertEquals(CivilianStatus.RESCUED, civ.status)
        assertEquals(96, civ.hp)
        repeat(3) { w.tickOnce() }
        assertEquals(96, civ.hp, "a rescued civilian leaves the grid and takes no damage")
    }

    @Test
    fun civPortsReturnSentinelsWhenNoCivilianExists() {
        val w = worldOf(firmwareSource = "loop:  IN     ${FirelinePorts.CIV_COUNT}\n       JMP    loop")
        w.tickOnce()
        assertEquals(0, w.rover.vm.a, "CIV_COUNT is 0")
        assertNull(w.selectedCivilian())
        assertEquals(NO_TARGET_DIST, w.rover.portIn(FirelinePorts.CIV_DIST))
        assertEquals(NO_TARGET_ANGLE, w.rover.portIn(FirelinePorts.CIV_ANGLE))
    }

    @Test
    fun civPortsReportTheSelectedCivilian() {
        val w = civWorld(listOf(CivilianSpec(id = 1, x = 2, y = 5)))     // dist 3, straight south
        assertEquals(3, w.rover.portIn(FirelinePorts.CIV_DIST))
        assertEquals(1, w.rover.portIn(FirelinePorts.CIV_ANGLE), "clockwise delta from east to south")
        assertEquals(1, w.rover.portIn(FirelinePorts.CIV_COUNT))

        val viaVm = worldOf(
            firmwareSource = "loop:  IN     ${FirelinePorts.CIV_DIST}\n       JMP    loop",
            civilians = listOf(CivilianSpec(id = 1, x = 2, y = 5)),
        )
        viaVm.tickOnce()
        assertEquals(3, viaVm.rover.vm.a, "the civilian sensors sit on the shared port bus")
    }

    @Test
    fun selectionPrefersNearestThenLowestHp() {
        val near = civWorld(listOf(
            CivilianSpec(id = 1, x = 4, y = 2, hp = 100),      // dist 2
            CivilianSpec(id = 2, x = 6, y = 2, hp = 100),      // dist 4
        ))
        assertEquals(2, near.rover.portIn(FirelinePorts.CIV_DIST))
        assertEquals(1, near.selectedCivilian()!!.id, "nearest first")

        val hurt = civWorld(listOf(
            CivilianSpec(id = 1, x = 4, y = 2, hp = 100),      // dist 2
            CivilianSpec(id = 2, x = 3, y = 3, hp = 40),       // dist 2, lower HP
        ))
        assertEquals(2, hurt.selectedCivilian()!!.id, "equal distance: lowest HP wins")
        assertEquals(2, hurt.rover.portIn(FirelinePorts.CIV_DIST))
    }

    @Test
    fun selectionBreaksTiesByRowMajorPosition() {
        val w = civWorld(listOf(
            CivilianSpec(id = 1, x = 4, y = 2, hp = 100),      // dist 2, row 2
            CivilianSpec(id = 2, x = 2, y = 4, hp = 100),      // dist 2, row 4
            CivilianSpec(id = 3, x = 3, y = 3, hp = 100),      // dist 2, row 3
        ))
        assertEquals(1, w.selectedCivilian()!!.id, "row-major (y, then x) breaks the tie")
    }

    @Test
    fun rescuedAndLostCiviliansLeaveCountAndSelection() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(6, 6, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(6, 6, '4'))),          // 4 HP/tick -> dead at tick 25
            firmwareSource = idle,
        )
        assertEquals(1, w.rover.portIn(FirelinePorts.CIV_COUNT))
        repeat(25) { w.tickOnce() }
        assertEquals(CivilianStatus.LOST, w.civilians.first().status)
        assertEquals(0, w.rover.portIn(FirelinePorts.CIV_COUNT), "lost civilians are excluded")
        assertNull(w.selectedCivilian())
        assertEquals(NO_TARGET_ANGLE, w.rover.portIn(FirelinePorts.CIV_ANGLE))
    }

    @Test
    fun optionalCiviliansAreNotCountedOrSelectedButCanStillBeRescuedAndBurn() {
        val w = worldOf(
            firmwareSource = moveLoop,
            civilians = listOf(CivilianSpec(id = 7, x = 3, y = 2, hp = 60, required = false)),
        )
        assertEquals(0, w.rover.portIn(FirelinePorts.CIV_COUNT), "CIV_COUNT counts required civilians")
        assertNull(w.selectedCivilian())
        w.tickOnce(); w.tickOnce()
        assertEquals(CivilianStatus.RESCUED, w.civilians.first().status, "contact rescue still applies")
        assertEquals(1, w.civiliansRescued())
    }

    @Test
    fun civilianDamageAndLossUseTheSharedExposureFormula() {
        // Explicit civilian specs allow authored starting HP; the cell burns at 50.
        val w2 = worldOf(
            firmwareSource = idle,
            civilians = listOf(CivilianSpec(id = 1, x = 3, y = 2, hp = 3)),
            fire = fireRows(listOf(Triple(3, 2, '2'))),
        )
        val civ = w2.civilians.first()
        w2.tickOnce(); assertEquals(1, civ.hp, "ceil(50/25) = 2 HP per tick")
        w2.tickOnce()
        assertEquals(0, civ.hp)
        assertEquals(CivilianStatus.LOST, civ.status, "zero HP means LOST and removal")
        assertEquals(0, w2.civiliansWaiting())
        assertEquals(1, w2.civiliansLost())
        assertTrue(w2.lastEvents.any { it.contains("LOST BY FIRE") }, w2.lastEvents.toString())
        assertEquals(100, w2.rover.hp, "the rover never stood on the fire")
    }
}
