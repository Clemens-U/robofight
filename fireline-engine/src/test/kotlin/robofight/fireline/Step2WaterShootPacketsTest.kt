package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.isa.ISA

/**
 * §8.3 step 2: water tank + SHOOT + water packets.
 *
 * Covers FIRELINE-DESIGN.md §3.6 (nozzle cost, water/heat accounting, packet
 * creation timing, advance, life, wall/boundary consumption, intensity
 * reduction, non-consuming entities), §3.2 (refill +10/tick capped at 100) and
 * the shared heat model of §5 / RF8-PLATFORM-SPEC.md §6 (cooling gate,
 * lockout, overload accumulation).
 */
class Step2WaterShootPacketsTest {

    private val shootLoop = "loop:  SHOOT\n       JMP    loop"

    @Test
    fun shootOccupiesThreeTicksAndCreatesThePacketOnTheLast() {
        val w = worldOf(firmwareSource = shootLoop, water = 100)
        assertEquals(3, ISA.cost(0xB0), "SHOOT is a three-tick instruction (§5)")

        w.tickOnce()   // SHOOT 1/3
        assertTrue(w.rover.vm.inProgress)
        assertEquals(0, w.packetCount()); assertEquals(100, w.rover.water)

        w.tickOnce()   // SHOOT 2/3 — continuation tick, still nothing
        assertTrue(w.rover.vm.inProgress)
        assertEquals(0, w.packetCount()); assertEquals(100, w.rover.water)

        w.tickOnce()   // SHOOT 3/3 — completion: water, heat and the packet
        assertFalse(w.rover.vm.inProgress)
        assertEquals(1, w.packetCount())
        assertEquals(90, w.rover.water, "a successful discharge consumes 10 water")
        assertEquals(20, w.rover.heat, "a successful discharge adds 20 heat")
        val p = w.packetsSnapshot().first()
        assertEquals(3, p.x); assertEquals(2, p.y); assertEquals(DIR_E, p.dir)
        assertEquals(3, w.tick, "packet is created on the final instruction tick")
    }

    @Test
    fun packetDoesNotAdvanceOnItsCreationTick() {
        val w = worldOf(firmwareSource = shootLoop, water = 100)
        repeat(3) { w.tickOnce() }
        assertEquals(3, w.packetsSnapshot().first().x)
        w.tickOnce()   // tick 4: first advance
        assertEquals(4, w.packetsSnapshot().first().x)
        w.tickOnce()   // tick 5: JMP tick, packet still advances one cell
        assertEquals(5, w.packetsSnapshot().first().x)
    }

    @Test
    fun packetReducesTheFirstBurningCellItContactsAndIsConsumed() {
        // Target at (4,2) ringed by non-burnable cells so growth/spread cannot
        // interfere with the contact accounting.
        val w = worldOf(
            terrain = editRows(
                openTerrainRows(2, 2),
                listOf(Triple(3, 2, GLYPH_REFILL), Triple(5, 2, GLYPH_WALL),
                    Triple(4, 1, GLYPH_WALL), Triple(4, 3, GLYPH_WALL)),
            ),
            fire = fireRows(listOf(Triple(4, 2, '2'))),      // intensity 50 at (4,2)
            firmwareSource = shootLoop,
        )
        repeat(3) { w.tickOnce() }                            // packet spawns on the refill cell
        assertEquals(50, w.fireAt(4, 2), "a refill cell does not stop the packet")
        w.tickOnce()                                          // tick 4: contact
        assertEquals(25, w.fireAt(4, 2), "the contacted cell loses 25 intensity")
        assertEquals(0, w.packetCount(), "and the packet is consumed")

        repeat(3) { w.tickOnce() }                            // ticks 5-7: spread tick + new SHOOT
        val before = w.fireAt(4, 2)
        assertEquals(30, before, "the surviving fire grew +5 on the tick-5 spread step")
        w.tickOnce()                                          // tick 8: second contact
        assertEquals(before - PACKET_FIRE_REDUCE, w.fireAt(4, 2), "each contact removes exactly 25")
        assertEquals(0, w.packetCount())
    }

    @Test
    fun packetCreatedOnABurningCellChecksItsSpawnCellImmediately() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_FUEL))),
            fire = fireRows(listOf(Triple(3, 2, '1'))),      // burning cell right in front
            firmwareSource = shootLoop,
        )
        repeat(3) { w.tickOnce() }
        assertEquals(0, w.fireAt(3, 2), "creation checks the adjacent cell at once")
        assertEquals(0, w.packetCount(), "and consumes the packet")
    }

    @Test
    fun wallAndBoundaryConsumeAPacket() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(4, 2, GLYPH_WALL))),
            firmwareSource = shootLoop,
        )
        repeat(3) { w.tickOnce() }
        assertEquals(1, w.packetCount())
        w.tickOnce()
        assertEquals(0, w.packetCount(), "a wall consumes the packet")
        assertTrue(w.lastEvents.any { it.startsWith("PACKET SPENT WALL") }, w.lastEvents.toString())

        val edge = worldOf(
            terrain = openTerrainRows(18, 2),
            firmwareSource = shootLoop,
            dir = DIR_E,
        )
        repeat(3) { edge.tickOnce() }
        assertEquals(1, edge.packetCount())          // packet at (19,2)
        edge.tickOnce()
        assertEquals(0, edge.packetCount(), "the grid boundary consumes the packet")
    }

    @Test
    fun shootingIntoTheBoundaryCreatesNoPacketButStillCostsWaterAndTicks() {
        val w = worldOf(terrain = openTerrainRows(19, 2), firmwareSource = shootLoop, dir = DIR_E)
        repeat(3) { w.tickOnce() }
        assertEquals(0, w.packetCount())
        assertEquals(90, w.rover.water, "the nozzle still discharged")
        assertEquals(1, w.rover.shots)
        assertTrue(w.lastEvents.any { it.contains("PACKET BLOCKED BOUNDARY") }, w.lastEvents.toString())
    }

    @Test
    fun injectedPacketExpiresAfterItsRemainingResolutionSteps() {
        // Injected at a fixed tick so the life counter, not the grid edge, is
        // what ends the packet: it is injected facing a wall-free pocket cell
        // and driven without advancing by using a zero-distance spawn check.
        val w = worldOf(terrain = openTerrainRows(10, 10))
        w.tickOnce()
        val spawnedAt = w.tick
        w.injectPacket(WaterPacket(10, 10, DIR_N, ticksLeft = 3, spawnTick = spawnedAt))
        assertEquals(1, w.packetCount())
        w.tickOnce(); assertEquals(1, w.packetCount())     // advance to (10,9)
        assertEquals(10, w.packetsSnapshot().first().x); assertEquals(9, w.packetsSnapshot().first().y)
        w.tickOnce(); assertEquals(1, w.packetCount())     // advance to (10,8)
        w.tickOnce(); assertEquals(0, w.packetCount(), "expired after its last resolution tick")
        assertTrue(w.lastEvents.any { it.startsWith("PACKET EXPIRED") }, w.lastEvents.toString())
    }

    @Test
    fun shootHitsCellsOneThroughFiveButCannotReachCellSixInAnyDirection() {
        assertEquals(5, WATER_RANGE)
        for (dir in 0..3) for (distance in listOf(1, 5, 6)) {
            val tx = 10 + dirDx(dir) * distance
            val ty = 10 + dirDy(dir) * distance
            // Refills surrounding the target cannot ignite, so a nearer fire
            // cannot intercept the packet and mask an off-by-one range error.
            val terrain = editRows(List(GRID) { "W".repeat(GRID) }, listOf(
                Triple(10, 10, GLYPH_ROVER), Triple(tx, ty, GLYPH_FLOOR),
            ))
            val w = worldOf(terrain = terrain, fire = fireRows(listOf(Triple(tx, ty, '4'))),
                dir = dir, firmwareSource = "SHOOT\nidle: WAIT\nJMP idle")
            repeat(2) { w.tickOnce() }
            assertEquals(0, w.packetCount(), "no packet before SHOOT completes")
            w.tickOnce()
            assertEquals(90, w.rover.water)
            assertEquals(20, w.rover.heat)
            assertEquals(1, w.rover.shots)
            if (distance > 1) {
                assertEquals(10 + dirDx(dir), w.packetsSnapshot().single().x)
                assertEquals(10 + dirDy(dir), w.packetsSnapshot().single().y)
            }
            repeat(minOf(distance, WATER_RANGE) - 1) { w.tickOnce() }
            assertEquals(if (distance <= WATER_RANGE) 75 else 100, w.fireAt(tx, ty),
                "direction $dir, distance $distance")
            assertEquals(0, w.packetCount(), "packet is consumed or expires at cell 5")
            if (distance == 6) {
                assertTrue(w.lastEvents.any { it.startsWith("PACKET EXPIRED") })
                w.tickOnce()
                assertEquals(100, w.fireAt(tx, ty), "no deferred hit at distance 6")
            }
        }
    }

    @Test
    fun civiliansExitsAndRefillsDoNotConsumePackets() {
        val w = worldOf(
            terrain = editRows(
                openTerrainRows(2, 2),
                listOf(Triple(3, 2, GLYPH_CIVILIAN), Triple(4, 2, GLYPH_EXIT), Triple(5, 2, GLYPH_REFILL)),
            ),
            fire = fireRows(listOf(Triple(6, 2, '1'))),
            firmwareSource = shootLoop,
        )
        repeat(5) { w.tickOnce() }        // ticks 1-5: packet travels, spread tick grows the fire
        val before = w.fireAt(6, 2)
        assertEquals(30, before, "25 authored + 5 growth on the tick-5 spread step")
        w.tickOnce()                        // tick 6: the packet reaches (6,2)
        assertEquals(before - PACKET_FIRE_REDUCE, w.fireAt(6, 2))
        assertEquals(0, w.packetCount())
        assertEquals(1, w.civilians.count { it.status == CivilianStatus.WAITING }, "a civilian does not block water")
    }

    @Test
    fun roverEnteringAPacketCellDoesNotConsumeIt() {
        val w = worldOf(terrain = openTerrainRows(2, 2), firmwareSource = "loop:  SHOOT\n       MOVE\n       JMP    loop")
        repeat(4) { w.tickOnce() }        // SHOOT 1..3, MOVE 1/2
        assertEquals(1, w.packetCount())
        w.tickOnce()                       // MOVE completes into the packet cell
        assertEquals(3, w.rover.x)
        assertEquals(1, w.packetCount(), "the rover does not consume a packet")
        assertEquals(1, w.rover.shots)
    }

    // ---- water accounting ----
    @Test
    fun dryShootConsumesTicksButNoWaterHeatOrPacket() {
        val w = worldOf(firmwareSource = shootLoop, water = 5)
        repeat(3) { w.tickOnce() }
        assertEquals(0, w.packetCount(), "no packet when water is below 10")
        assertEquals(5, w.rover.water, "no water is removed")
        assertEquals(0, w.rover.heat, "no heat is added")
        assertEquals(1, w.rover.shotsDry)
        assertEquals(0, w.rover.shots)
        assertTrue(w.lastEvents.contains("WATER EMPTY"), w.lastEvents.toString())
    }

    @Test
    fun waterNeverExceedsTheCapAndRefillAddsTenPerOccupiedTick() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_REFILL))),
            firmwareSource = "loop:  MOVE\n       NOP\n       JMP    loop",
            water = 40,
        )
        w.tickOnce(); assertEquals(40, w.rover.water, "not on the refill cell yet")
        w.tickOnce(); assertEquals(50, w.rover.water, "+10 on the tick it enters the refill cell")
        w.tickOnce(); assertEquals(60, w.rover.water)
        w.tickOnce(); assertEquals(70, w.rover.water, "refill applies to every occupied resolution tick")
        w.tickOnce(); assertEquals(80, w.rover.water)
        w.tickOnce(); assertEquals(80, w.rover.water, "leaving the cell stops the refill")
    }

    @Test
    fun refillRecoversWaterAfterAnEmptyTank() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_REFILL))),
            firmwareSource = "loop:  MOVE\n       JMP    loop",
            water = 0,
        )
        w.tickOnce()
        assertEquals(0, w.rover.water, "not on the refill cell yet")
        w.tickOnce()
        assertEquals(10, w.rover.water, "one occupied tick restores 10 water")
        assertEquals(0, w.rover.heat)
        assertFalse(w.rover.heatLocked(0), "the nozzle is usable again after one refill tick")
        w.tickOnce()
        assertEquals(20, w.rover.water, "refill applies to every occupied resolution tick")
    }

    // ---- heat ----
    @Test
    fun coolingIsSuppressedForTheWholeMultiTickActionButNotAfterIt() {
        val w = worldOf(firmwareSource = shootLoop, water = 100)
        repeat(3) { w.tickOnce() }        // SHOOT completes
        assertEquals(20, w.rover.heat, "completion tick does not cool")
        w.tickOnce()                       // JMP: idle tick cools
        assertEquals(18, w.rover.heat)
        // continuation ticks of an in-flight SHOOT also suppress cooling
        w.tickOnce(); w.tickOnce()        // SHOOT 1/3, 2/3 with heat 18
        assertEquals(18, w.rover.heat, "continuation ticks suppress cooling")
        w.tickOnce()                       // completion adds 20
        assertEquals(38, w.rover.heat)
    }

    @Test
    fun moveCompletionTickCoolsBecauseItAddsNoHeat() {
        val w = worldOf(firmwareSource = "loop:  MOVE\n       MOVE\n       JMP    loop", water = 100)
        repeat(4) { w.tickOnce() }         // two MOVEs complete (2 ticks each)
        assertEquals(4, w.rover.x)
        assertEquals(0, w.rover.heat)
    }

    @Test
    fun heatLockoutCollapsesShootToOneTickNoOpAndCoolsNormally() {
        val locked = worldOf(firmwareSource = "loop:  SHOOT", water = 100)
        locked.rover.heat = 81                       // 81 + 20 > 100 -> locked out
        locked.tickOnce()
        assertTrue(locked.lastOp.contains("locked"), locked.lastOp)
        assertFalse(locked.rover.vm.inProgress, "a locked SHOOT collapses to one tick (§6.1)")
        assertEquals(0, locked.rover.shots, "a locked SHOOT fires nothing")
        assertEquals(100, locked.rover.water, "and consumes nothing")
        assertEquals(79, locked.rover.heat, "a locked SHOOT cools normally")

        val allowed = worldOf(firmwareSource = "loop:  SHOOT", water = 100)
        allowed.rover.heat = 80                      // 80 + 20 == 100 -> allowed
        repeat(3) { allowed.tickOnce() }
        assertEquals(1, allowed.rover.shots)
        assertEquals(90, allowed.rover.water)
        assertEquals(100, allowed.rover.heat)

        val hot = worldOf(firmwareSource = "loop:  SHOOT\n       JMP    loop", water = 100)
        hot.rover.heat = 100
        hot.tickOnce()
        assertTrue(hot.lastOp.contains("locked"), hot.lastOp)
        assertEquals(98, hot.rover.heat)
        assertEquals(0, hot.rover.shots)
    }

    @Test
    fun heatOverloadBurnsHpFractionallyAndAppliesWholeHp() {
        val w = worldOf(firmwareSource = "loop:  NOP\n       JMP    loop", water = 0)
        w.rover.heat = 92                       // above 80: 2 HP/sec = 0.2 HP/tick
        val hpBefore = w.rover.hp
        repeat(4) { w.tickOnce() }              // 4 hot ticks = 0.8 HP pending
        assertEquals(800, w.rover.heatOverloadMilli, "fractional damage accumulates exactly")
        assertEquals(hpBefore, w.rover.hp, "no whole HP yet")
        w.tickOnce()                            // 5th hot tick -> 1 whole HP
        assertEquals(hpBefore - 1, w.rover.hp)
        assertEquals(0, w.rover.heatOverloadMilli)
        assertTrue(w.lastEvents.any { it == "ROVER OVERLOAD -1" }, w.lastEvents.toString())
        assertEquals(82, w.rover.heat, "cooling continues while overheating")
    }
}
