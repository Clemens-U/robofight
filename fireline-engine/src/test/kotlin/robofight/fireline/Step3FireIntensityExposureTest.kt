package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §8.3 step 3: fire intensity semantics, extinguishing, and exposure damage.
 *
 * Covers FIRELINE-DESIGN.md §3.3 (intensity 0..100, fire does not block), §3.6
 * (25-point reduction, floor at zero), §4.5 (`damage = ceil(intensity / 25)`
 * applied after water resolution and before the five-tick spread step, civilians
 * always take it) and §8 tick order steps 8-10.
 */
class Step3FireIntensityExposureTest {

    private val idle = "loop:  NOP\n       JMP    loop"

    @Test
    fun exposureDamageIsCeilOfIntensityOverTwentyFive() {
        assertEquals(0, fireExposureDamage(0), "not burning means no damage")
        assertEquals(1, fireExposureDamage(1))
        assertEquals(1, fireExposureDamage(25))
        assertEquals(2, fireExposureDamage(26))
        assertEquals(2, fireExposureDamage(50))
        assertEquals(3, fireExposureDamage(51))
        assertEquals(3, fireExposureDamage(75))
        assertEquals(4, fireExposureDamage(76))
        assertEquals(4, fireExposureDamage(100), "1-4 HP per world tick")
    }

    @Test
    fun roverOnABurningCellLosesHpEveryTick() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_FUEL))),
            fire = fireRows(listOf(Triple(3, 2, '2'))),      // intensity 50 -> 2 HP/tick
            firmwareSource = "loop:  MOVE\n       JMP    loop",
        )
        w.tickOnce(); assertEquals(100, w.rover.hp, "still off the fire")
        w.tickOnce(); assertEquals(3, w.rover.x); assertEquals(98, w.rover.hp, "ceil(50/25) = 2")
        w.tickOnce()  // JMP keeps the rover on the burning cell
        assertEquals(96, w.rover.hp, "exposure damage applies every tick")
        assertTrue(w.lastEvents.any { it.startsWith("ROVER FIRE DAMAGE -2") }, w.lastEvents.toString())
    }

    @Test
    fun exposureDamageDoesNotReduceIntensity() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_FUEL))),
            fire = fireRows(listOf(Triple(3, 2, '4'))),      // intensity 100
            firmwareSource = "loop:  MOVE\n       JMP    loop",
        )
        repeat(2) { w.tickOnce() }                            // MOVE completes onto the fire
        assertEquals(3, w.rover.x)
        assertEquals(96, w.rover.hp, "ceil(100/25) = 4 HP")
        assertEquals(100, w.fireAt(3, 2), "exposure damage does not reduce intensity")
    }

    @Test
    fun aCellNeedsFourPacketsToExtinguishAFullyBurnedCell() {
        val w = worldOf(
            terrain = openTerrainRows(2, 2),
            fire = fireRows(listOf(Triple(4, 2, '4'))),      // intensity 100 at (4,2)
            firmwareSource = "loop:  SHOOT\n       NOP\n       JMP    loop",
        )
        // Firmware cycle: SHOOT(3 ticks), NOP(1), JMP(1) -> discharges complete at
        // ticks 3, 8, 13, 18; each packet reaches (4,2) one tick after creation.
        repeat(4) { w.tickOnce() }                            // packet A hits at tick 4
        assertEquals(75, w.fireAt(4, 2), "first contact removes 25")
        repeat(5) { w.tickOnce() }                            // packet B created t8, hits t9
        assertEquals(50, w.fireAt(4, 2))
        assertEquals(0, w.packetCount(), "contact consumes the packet")
        repeat(5) { w.tickOnce() }                            // packet C created t13, hits t14
        assertEquals(25, w.fireAt(4, 2))
        repeat(5) { w.tickOnce() }                            // packet D created t18, hits t19
        assertEquals(0, w.fireAt(4, 2), "four contacts x 25 clear a 100-intensity cell")
        assertEquals(0, w.fireCellsBurning())
        assertEquals(0, w.fireIntensitySum())
    }

    @Test
    fun intensityFloorsAtZeroAndClampsTo100() {
        val w = worldOf(
            terrain = openTerrainRows(2, 2),
            fire = fireRows(listOf(Triple(3, 2, '1'))),      // authored 25
            firmwareSource = "loop:  SHOOT\n       JMP    loop",
        )
        repeat(3) { w.tickOnce() }                            // packet spawns on the burning cell
        assertEquals(0, w.fireAt(3, 2), "25 - 25 floors at zero, never negative")
        assertEquals(0, w.fireCellsBurning())
        w.setFire(6, 6, 140)
        assertEquals(FIRE_MAX, w.fireAt(6, 6), "positive values clamp to 100")
        w.setFire(6, 6, -7)
        assertEquals(0, w.fireAt(6, 6), "negative values clamp to zero")
    }

    @Test
    fun exposureUsesTheFireStateAfterWaterResolution() {
        // §8 step 10 runs after §8 step 8: the tick a packet extinguishes a
        // civilian's cell, that civilian takes no exposure damage at all.
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(5, 2, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(5, 2, '1'))),      // authored level 1 = intensity 25
            firmwareSource = "loop:  SHOOT\n       JMP    loop",
        )
        val civ = w.civilians.first()
        repeat(4) { w.tickOnce() }                            // ticks 1-4: still burning
        assertEquals(96, civ.hp, "one HP per tick while burning")
        assertEquals(25, w.fireAt(5, 2))
        w.tickOnce()                                          // tick 5: packet hits (5,2)
        assertEquals(0, w.fireAt(5, 2), "extinguished during water resolution")
        assertEquals(96, civ.hp, "so this tick's exposure step sees zero fire")
    }

    @Test
    fun waitingCiviliansOnFireTakeDamageAndAreLostAtZeroHp() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(5, 5, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(5, 5, '4'))),      // intensity 100 -> 4 HP/tick
            firmwareSource = idle,
        )
        val civ = w.civilians.first()
        assertEquals(100, civ.hp)
        w.tickOnce()
        assertEquals(96, civ.hp, "civilians have no shield")
        repeat(24) { w.tickOnce() }                            // 25 ticks total -> 100 damage
        assertEquals(0, civ.hp)
        assertEquals(CivilianStatus.LOST, civ.status)
        assertEquals(0, w.civiliansWaiting(), "a lost civilian leaves the grid")
        assertTrue(w.lastEvents.any { it.contains("LOST BY FIRE") }, w.lastEvents.toString())
    }
}