package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.isa.ISA

/**
 * §8.3 step 4: approved SHIELD semantics against fire exposure.
 *
 * Contract under test (RF8-PLATFORM-SPEC.md §6.1 + FIRELINE-DESIGN.md §5, §4.5,
 * §8 steps 6/10/11/15): SHIELD is a two-tick activation, arms a five-tick
 * duration, protects exposure damage on its own activation tick, is decremented
 * only after all shieldable world resolution, does not reduce fire intensity,
 * does not protect civilians, does not block heat-overload damage, and re-arming
 * resets the window.
 */
class Step4ShieldExposureTest {

    /** One SHIELD, then six idle ticks: the whole window is observable. */
    private val shieldThenIdle =
        "shield: SHIELD\n         NOP\n         NOP\n         NOP\n         NOP\n         NOP\n         JMP     shield"

    private val idle = "loop:  NOP\n       JMP    loop"

    /** Rover spawned on a 25-intensity cell: 1 exposure HP per tick. */
    private fun burningSpawn(firmwareSource: String) = worldOf(
        fire = fireRows(listOf(Triple(2, 2, '1'))),
        firmwareSource = firmwareSource,
    )

    @Test
    fun shieldIsATwoTickActivationAndIsNotActiveBeforeItCompletes() {
        assertEquals(2, ISA.cost(0xB5), "SHIELD is a two-tick activation (spec §6.1)")
        val w = burningSpawn(shieldThenIdle)
        w.tickOnce()                       // activation tick 1/2
        assertTrue(w.rover.vm.inProgress, "SHIELD stays highlighted across its activation")
        assertEquals(0, w.rover.shieldTicksRemaining, "no protection during activation")
        assertEquals(99, w.rover.hp, "tick 1 exposure damage is NOT blocked")
        assertEquals(0, w.rover.heat, "heat is added on completion, not at start")
        w.tickOnce()                       // activation tick 2/2 completes
        assertEquals(4, w.rover.shieldTicksRemaining, "armed to 5 for its own tick, decremented at step 15")
        assertEquals(20, w.rover.heat, "a completed SHIELD adds 20 heat")
        assertEquals(99, w.rover.hp, "and protects exposure on its own completion tick")
        assertTrue(w.lastEvents.any { it.startsWith("SHIELD BLOCKS FIRE") }, w.lastEvents.toString())
        assertEquals(1, w.rover.shieldsRaised)
    }

    @Test
    fun shieldWindowCoversExactlyFiveResolutionTicks() {
        val w = burningSpawn(shieldThenIdle)
        val protectedTicks = mutableListOf<Int>()
        val damagedTicks = mutableListOf<Int>()
        var previousHp = w.rover.hp
        repeat(8) {                                        // tick 8 begins a new activation
            w.tickOnce()
            if (w.lastEvents.any { it.startsWith("SHIELD BLOCKS FIRE") }) protectedTicks.add(w.tick)
            if (w.rover.hp < previousHp) damagedTicks.add(w.tick)
            previousHp = w.rover.hp
        }
        assertEquals(listOf(2, 3, 4, 5, 6), protectedTicks, "activation tick + four later ticks")
        assertEquals(listOf(1, 7, 8), damagedTicks, "damage resumes the tick after the window")
        assertEquals(95, w.rover.hp,
            "1 HP at tick 1 (intensity 25) + 2 HP each at ticks 7 and 8 (intensity 30 after the tick-5 growth)")
    }

    @Test
    fun durationCountsDownOnlyAfterWorldResolution() {
        val w = burningSpawn(shieldThenIdle)
        repeat(2) { w.tickOnce() }
        assertEquals(4, w.rover.shieldTicksRemaining, "armed to 5, decremented at step 15")
        w.tickOnce(); assertEquals(3, w.rover.shieldTicksRemaining)
        w.tickOnce(); assertEquals(2, w.rover.shieldTicksRemaining)
        w.tickOnce(); assertEquals(1, w.rover.shieldTicksRemaining)
        w.tickOnce(); assertEquals(0, w.rover.shieldTicksRemaining)
        assertFalse(w.rover.shielded)
        assertEquals(0, w.rover.portIn(FirelinePorts.SHIELD))
    }

    @Test
    fun shieldPortReportsTheActiveWindow() {
        val w = burningSpawn(shieldThenIdle)
        assertEquals(0, w.rover.portIn(FirelinePorts.SHIELD))
        repeat(2) { w.tickOnce() }
        assertEquals(1, w.rover.portIn(FirelinePorts.SHIELD))
        repeat(3) { w.tickOnce() }                         // ticks 3,4,5 still protected
        assertEquals(1, w.rover.portIn(FirelinePorts.SHIELD))
        w.tickOnce()                                       // tick 6 is the last protected tick
        assertEquals(0, w.rover.portIn(FirelinePorts.SHIELD), "the window has expired")
    }

    @Test
    fun shieldBlocksExposureButDoesNotChangeFireIntensity() {
        val w = burningSpawn(idle)
        w.rover.shieldTicksRemaining = SHIELD_DURATION
        repeat(3) { w.tickOnce() }
        assertEquals(100, w.rover.hp, "no exposure damage while shielded")
        assertEquals(25, w.fireAt(2, 2), "the shield does not reduce the cell's intensity")
        assertTrue(w.lastEvents.any { it.contains("SHIELD BLOCKS FIRE") }, w.lastEvents.toString())
    }

    @Test
    fun shieldDoesNotBlockHeatOverloadDamage() {
        val w = burningSpawn(idle)
        w.rover.shieldTicksRemaining = SHIELD_DURATION
        w.rover.heat = 92
        repeat(4) { w.tickOnce() }
        assertEquals(100, w.rover.hp, "0.8 HP is still fractional")
        assertEquals(800, w.rover.heatOverloadMilli)
        w.tickOnce()
        assertEquals(99, w.rover.hp, "overload damage is not shielded (§4.5)")
        assertTrue(w.lastEvents.any { it == "ROVER OVERLOAD -1" }, w.lastEvents.toString())
        assertFalse(w.lastEvents.any { it.startsWith("ROVER FIRE DAMAGE") }, w.lastEvents.toString())
    }

    @Test
    fun coolingContinuesWhileTheShieldWindowIsOnlyActive() {
        val w = burningSpawn(shieldThenIdle)
        repeat(2) { w.tickOnce() }                         // SHIELD completion: heat 20
        assertEquals(99, w.rover.hp)
        repeat(3) { w.tickOnce() }                         // three idle protected ticks
        assertEquals(14, w.rover.heat, "shield activity alone does not suppress cooling (8 step 11)")
    }

    @Test
    fun shieldNeverProtectsCivilians() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(6, 6, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(2, 2, '1'), Triple(6, 6, '1'))),
            firmwareSource = idle,
        )
        w.rover.shieldTicksRemaining = SHIELD_DURATION
        val civ = w.civilians.first()
        repeat(3) { w.tickOnce() }
        assertEquals(100, w.rover.hp, "rover shielded")
        assertEquals(97, civ.hp, "civilians have no shield")
    }

    @Test
    fun shieldIsLockedOutAboveEightyHeatAndCollapsesToOneTick() {
        val w = burningSpawn(shieldThenIdle)
        w.rover.heat = 81                                // 81 + 20 > 100 -> locked
        w.tickOnce()
        assertTrue(w.lastOp.contains("locked"), w.lastOp)
        assertFalse(w.rover.vm.inProgress, "a locked SHIELD does not occupy two ticks")
        assertEquals(0, w.rover.shieldTicksRemaining, "and arms nothing")
        assertEquals(0, w.rover.shieldsRaised)
        assertEquals(79, w.rover.heat, "a locked SHIELD cools normally")
        assertEquals(99, w.rover.hp, "so its exposure damage is unshielded")
    }

    @Test
    fun rearmingResetsTheWindowInsteadOfExtendingIt() {
        val w = worldOf(
            fire = fireRows(listOf(Triple(2, 2, '1'))),
            firmwareSource = "s1:    SHIELD\n         NOP\n         NOP\n         SHIELD\n         JMP     s1",
        )
        repeat(2) { w.tickOnce() }                        // armed: 5 -> 4
        assertEquals(4, w.rover.shieldTicksRemaining)
        repeat(2) { w.tickOnce() }                        // ticks 3,4: 4 -> 3 -> 2
        assertEquals(2, w.rover.shieldTicksRemaining)
        w.tickOnce()                                      // tick 5: SHIELD 1/2
        assertTrue(w.rover.vm.inProgress)
        w.tickOnce()                                      // tick 6: re-armed to 5, then decremented
        assertEquals(4, w.rover.shieldTicksRemaining, "reset to 5, not 2 + 5")
        assertEquals(2, w.rover.shieldsRaised)
    }

    @Test
    fun shieldDoesNotReduceMoveOrShootTiming() {
        // The shield window must not stall the VM: instructions keep their cost.
        val w = worldOf(
            fire = noFireRows(),
            firmwareSource = "s:     SHIELD\n         MOVE\n         MOVE\n         JMP      s",
        )
        repeat(2) { w.tickOnce() }                        // SHIELD completes (5 -> 4)
        assertEquals(4, w.rover.shieldTicksRemaining)
        w.tickOnce(); assertTrue(w.rover.vm.inProgress); assertEquals(2, w.rover.x)
        w.tickOnce(); assertEquals(3, w.rover.x, "MOVE still costs exactly two ticks")
    }
}
