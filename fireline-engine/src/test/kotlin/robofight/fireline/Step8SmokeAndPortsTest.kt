package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.vm.Vm

/**
 * §8.3 step 8: derived smoke + the full FIRELINE port bus.
 *
 * Covers FIRELINE-DESIGN.md §3.3 (SMOKE formula, informational only), §5
 * (`DIR` writes), §6.2/§6.3 (base + peripheral values, sentinels, MISSION bits)
 * and RF8-PLATFORM-SPEC.md §5.2/§5.3 (PROFILE=1, PROFILE_REV=1, CAPS=0x7F,
 * reserved 15/31 and unknown ports read zero and ignore writes).
 */
class Step8SmokeAndPortsTest {

    private val idle = "loop:  WAIT\n       JMP    loop"

    private fun vmZ() = Vm.FLAG_Z

    // ---- §3.3 smoke ----
    @Test
    fun smokeIsLocalFirePlusHalfTheNeighbourSum() {
        // Rover spawns on (5,5); W=50, N=25, E=none, S=100 -> 0 + 175/2 = 87.
        val w = worldOf(
            terrain = openTerrainRows(5, 5),
            fire = fireRows(listOf(Triple(4, 5, '2'), Triple(5, 4, '1'), Triple(5, 6, '4'))),
        )
        assertEquals(87, w.smokeAt(5, 5), "integer division truncates 87.5 to 87")
        assertEquals(87, w.sensor(FirelinePorts.SMOKE), "port 24 is the smoke at the rover cell")
        assertEquals(50, w.smokeAt(4, 5), "50 local, all four neighbours of this cell are empty")
    }

    @Test
    fun smokeClampsAt255() {
        val ring = listOf(Triple(4, 5, '4'), Triple(6, 5, '4'), Triple(5, 4, '4'), Triple(5, 6, '4'))
        val w = worldOf(
            terrain = openTerrainRows(5, 5),
            fire = fireRows(ring + listOf(Triple(5, 5, '4'))),
        )
        assertEquals(100, w.fireAt(5, 5))
        assertEquals(SMOKE_MAX, w.smokeAt(5, 5), "100 + 400/2 = 300 -> min(255)")
        assertEquals(SMOKE_MAX, w.sensor(FirelinePorts.SMOKE))
    }

    @Test
    fun offGridAndWallNeighboursContributeZero() {
        val w = worldOf(
            terrain = openTerrainRows(0, 0),
            fire = fireRows(listOf(Triple(1, 0, '2'), Triple(0, 1, '1'))),
        )
        assertEquals(37, w.smokeAt(0, 0), "only E and S exist off the corner: 75/2 = 37")
        val walled = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_WALL))),
            fire = fireRows(listOf(Triple(4, 2, '2'))),
        )
        assertEquals(0, walled.smokeAt(2, 2), "the wall neighbour (and the empty cells) contribute zero")
        assertEquals(50, walled.smokeAt(4, 2), "the burning cell keeps its own 50; its wall neighbour adds 0")
    }

    @Test
    fun smokeNeverHidesTargetsBlocksMovementOrPackets() {
        val w = worldOf(
            terrain = openTerrainRows(2, 2),
            fire = fireRows(listOf(Triple(2, 3, '4'), Triple(1, 2, '4'), Triple(3, 2, '4'), Triple(2, 1, '4'))),
            firmwareSource = "loop:  MOVE\n       JMP    loop",
            dir = DIR_S,
        )
        assertEquals(200, w.smokeAt(2, 2), "0 local + 400/2 from the burning ring")
        val target = w.selectedFire()
        assertNotNull(target, "smoke does not hide the beacon target (§3.3)")
        assertEquals(1, w.sensor(FirelinePorts.DIST))
        assertEquals(100, w.sensor(FirelinePorts.FIRE_LEVEL))
        assertEquals(0, w.sensor(FirelinePorts.AHEAD), "a smoking, burning floor cell stays passable")
        repeat(2) { w.tickOnce() }                             // MOVE completes on tick 2
        assertEquals(3, w.rover.y, "smoke and fire never block movement")
        assertEquals(100, w.smokeAt(2, 3), "its new cell: 100 local + zero neighbours")
        assertEquals(200, w.smokeAt(2, 2), "the cell it left still derives 400/2 smoke")
    }

    // ---- §5.2/§5.3 discovery conformance ----
    @Test
    fun discoveryPortsAnswerOneOneAndCaps7f() {
        val w = worldOf(firmwareSource = idle)
        assertEquals(1, w.sensor(FirelinePorts.PROFILE_PORT), "PROFILE=1 (§5.2)")
        assertEquals(1, w.sensor(FirelinePorts.PROFILE_REV_PORT), "PROFILE_REV=1 for FIRELINE v1")
        assertEquals(0x7F, w.sensor(FirelinePorts.CAPS_PORT), "every FIRELINE v1 build returns 0x7F (§5.3)")
        assertEquals(FirelinePorts.PROFILE, w.sensor(FirelinePorts.PROFILE_PORT))
        assertEquals(0, FirelinePorts.CAPS and FirelinePorts.CAP_RESERVED, "bit 7 stays zero in v1")
        assertEquals(0x7F, FirelinePorts.CAPS)
    }

    @Test
    fun discoveryValuesComeThroughTheVmPortBus() {
        val w = worldOf(firmwareSource = "loop:  IN     PROFILE\n       IN     PROFILE_REV\n       IN     CAPS\n       JMP    loop")
        w.tickOnce()
        assertEquals(1, w.rover.vm.a)
        w.tickOnce()
        assertEquals(1, w.rover.vm.a)
        w.tickOnce()
        assertEquals(0x7F, w.rover.vm.a, "IN CAPS through the assembled bus")
        assertEquals(0, w.rover.vm.flags and vmZ())
        assertFalse(w.rover.vm.fault)
    }

    // ---- §5.3 peripheral coverage ----
    @Test
    fun everyOfficialFirelinePortAnswersItsDocumentedValue() {
        // Rover spawns on (2,2) facing E. burning fuel at (3,2), civilian at
        // (2,6), exit at (8,2), wind E force 2.
        val w = worldOf(
            terrain = editRows(
                openTerrainRows(2, 2),
                listOf(Triple(8, 2, GLYPH_EXIT), Triple(2, 6, GLYPH_CIVILIAN), Triple(3, 2, GLYPH_FUEL)),
            ),
            fire = fireRows(listOf(Triple(3, 2, '3'))),
            windDir = DIR_E,
            windForce = 2,
        )
        assertEquals(1, w.sensor(FirelinePorts.FIRE_DIST))
        assertEquals(0, w.sensor(FirelinePorts.FIRE_ANGLE), "target is due east, facing east")
        assertEquals(75, w.sensor(FirelinePorts.FIRE_LEVEL))
        assertEquals(1, w.sensor(FirelinePorts.DIST), "DIST mirrors the primary target (§5.1)")
        assertEquals(75, w.sensor(FirelinePorts.ENEMY_HP), "ENEMY_HP mirrors the selected intensity")
        assertEquals(4, w.sensor(FirelinePorts.CIV_DIST))
        assertEquals(1, w.sensor(FirelinePorts.CIV_ANGLE), "civilian is due south of an east-facing rover")
        assertEquals(1, w.sensor(FirelinePorts.CIV_COUNT))
        assertEquals(DEFAULT_STARTING_WATER, w.sensor(FirelinePorts.WATER))
        assertEquals(0, w.sensor(FirelinePorts.LOCAL_FIRE), "the rover's own cell is not burning")
        assertEquals(37, w.sensor(FirelinePorts.SMOKE), "0 local + 75/2 from the burning fuel cell")
        assertEquals(DIR_E, w.sensor(FirelinePorts.WIND_DIR))
        assertEquals(2, w.sensor(FirelinePorts.WIND_FORCE))
        assertEquals(6, w.sensor(FirelinePorts.EXIT_DIST))
        assertEquals(0, w.sensor(FirelinePorts.EXIT_ANGLE), "the exit is due east")
        assertEquals(TERRAIN_FLOOR, w.sensor(FirelinePorts.TILE))
        assertEquals(0, w.sensor(FirelinePorts.MISSION), "nothing achieved on tick 0")
        // Every official number 16..30 has an explicit expectation here, so an
        // unimplemented peripheral (which falls through to the read-zero default)
        // cannot hide behind a legitimately zero reading.
        val expected = mapOf(
            FirelinePorts.FIRE_DIST to 1, FirelinePorts.FIRE_ANGLE to 0, FirelinePorts.FIRE_LEVEL to 75,
            FirelinePorts.CIV_DIST to 4, FirelinePorts.CIV_ANGLE to 1, FirelinePorts.CIV_COUNT to 1,
            FirelinePorts.WATER to DEFAULT_STARTING_WATER, FirelinePorts.LOCAL_FIRE to 0,
            FirelinePorts.SMOKE to 37, FirelinePorts.WIND_DIR to DIR_E, FirelinePorts.WIND_FORCE to 2,
            FirelinePorts.EXIT_DIST to 6, FirelinePorts.EXIT_ANGLE to 0,
            FirelinePorts.TILE to TERRAIN_FLOOR, FirelinePorts.MISSION to 0,
        )
        assertEquals(FirelinePorts.FIRELINE_PORT_NUMBERS.toSet(), expected.keys, "port coverage gap")
        for ((port, want) in expected.toList().sortedBy { it.first }) assertEquals(want, w.sensor(port), "port $port")
    }

    @Test
    fun noTargetSentinelsMatchTheProfileTable() {
        val w = worldOf(fire = noFireRows(), terrain = openTerrainRows())
        assertEquals(0, w.sensor(FirelinePorts.DIST))
        assertEquals(255, w.sensor(FirelinePorts.ANGLE))
        assertEquals(255, w.sensor(FirelinePorts.ENEMY_HP))
        assertEquals(0, w.sensor(FirelinePorts.FIRE_DIST))
        assertEquals(255, w.sensor(FirelinePorts.FIRE_ANGLE))
        assertEquals(0, w.sensor(FirelinePorts.FIRE_LEVEL))
        assertEquals(0, w.sensor(FirelinePorts.CIV_DIST))
        assertEquals(255, w.sensor(FirelinePorts.CIV_ANGLE))
        assertEquals(0, w.sensor(FirelinePorts.CIV_COUNT))
        assertEquals(0, w.sensor(FirelinePorts.EXIT_DIST), "no exit -> distance 0 (§6.3)")
        assertEquals(255, w.sensor(FirelinePorts.EXIT_ANGLE), "no exit -> 255 (§6.3)")
    }

    // ---- reserved / unknown ports ----
    @Test
    fun reservedAndUnknownPortsReadZeroAndIgnoreWrites() {
        val w = worldOf(fire = fireRows(listOf(Triple(5, 5, '2'))))
        for (p in intArrayOf(15, 31, 32, 42, 200, 255)) {
            assertEquals(0, w.sensor(p), "port $p must read zero (§4.2)")
        }
        val waterBefore = w.rover.water
        val heatBefore = w.rover.heat
        val fireBefore = w.fireIntensitySum()
        for (p in intArrayOf(15, 31, 22, 24, 42, 200, 255)) w.rover.portOut(p, 99)
        assertEquals(waterBefore, w.rover.water, "writes to a read-only port are ignored")
        assertEquals(heatBefore, w.rover.heat)
        assertEquals(fireBefore, w.fireIntensitySum())
        assertFalse(w.rover.vm.fault, "an ignored write must not fault the VM")

        val viaVm = worldOf(firmwareSource = "loop:  MOV A,#9\n       IN     200\n       OUT    200, A\n       JMP    loop")
        viaVm.tickOnce()
        viaVm.tickOnce()
        assertEquals(0, viaVm.rover.vm.a, "IN 200 reads zero")
        assertEquals(2, viaVm.rover.vm.flags and vmZ(), "a zero read sets Z")
        viaVm.tickOnce()
        assertFalse(viaVm.rover.vm.fault)
    }

    // ---- §5/§6.2 DIR write semantics ----
    @Test
    fun dirWriteSetsFacingAndSensorsFollowIt() {
        val w = worldOf(
            fire = fireRows(listOf(Triple(5, 2, '1'))),
            firmwareSource = "loop:  MOV A,#1\n       OUT    DIR, A\n       WAIT\n       JMP    loop",
        )
        assertEquals(DIR_E, w.rover.facing)
        repeat(2) { w.tickOnce() }                                // MOV + OUT
        assertEquals(DIR_N, w.rover.facing, "a supported DIR write changes facing (§5.1)")
        assertEquals(DIR_N, w.sensor(FirelinePorts.DIR))
        assertEquals(1, w.sensor(FirelinePorts.ANGLE), "clockwise delta from N to an east target is 1")
        assertEquals(1, w.sensor(FirelinePorts.FIRE_ANGLE))
        w.rover.portOut(FirelinePorts.DIR, 7)
        assertEquals(DIR_S, w.rover.facing, "writes mask to A & 3 (§5 table)")
        w.rover.portOut(FirelinePorts.DIR, 2)
        assertEquals(DIR_E, w.rover.facing)
        assertEquals(0, w.sensor(FirelinePorts.ANGLE), "facing east, the target is straight ahead")
    }

    @Test
    fun turnInstructionsAndDirWritesShareOneFacing() {
        val w = worldOf(firmwareSource = "loop:  TURN L\n       JMP    loop")
        repeat(2) { w.tickOnce() }
        assertEquals(DIR_N, w.rover.facing, "TURN L is one step counter-clockwise")
        w.rover.portOut(FirelinePorts.DIR, 2)
        assertEquals(DIR_E, w.rover.facing, "DIR writes and TURN share the same facing")
    }

    // ---- catalog / cheat sheet ----
    @Test
    fun profileAndSharedCatalogStayInParity() {
        assertEquals(emptyList<String>(), FirelinePorts.checkCatalogParity())
    }

    @Test
    fun cheatSheetListsEveryImplementedPortWithSentinels() {
        val sheet = FirelinePorts.cheatSheet()
        for (p in FirelinePorts.FIRELINE_PORT_NUMBERS) {
            val symbol = robofight.isa.PortCatalog.definition(p)!!.name
            assertTrue(sheet.contains(symbol), "cheat sheet is missing $symbol (port $p)")
        }
        assertTrue(sheet.contains("255"), "sentinels must be stated")
        assertTrue(sheet.contains("0x7f") || sheet.contains("0x7F"), "CAPS must be stated")
        assertFalse(sheet.contains("reserved write"), "FIRELINE v1 has no writable peripheral")
    }
}
