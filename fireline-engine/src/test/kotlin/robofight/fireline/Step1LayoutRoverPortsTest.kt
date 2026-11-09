package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.isa.ISA

/**
 * §8.3 step 1: static layout + rover + MOVE/TURN + base ports 0..11.
 *
 * Covers FIRELINE-DESIGN.md §3.1-§3.2 (grid, directions, terrain rules),
 * §3.5 (rover body, silent failed move), §5 (MOVE/TURN semantics), §6.1 (target
 * selection), §6.2 (base ports + no-target sentinels), §3.1's angle axis rule,
 * and the shared-platform discovery-port values for this profile.
 */
class Step1LayoutRoverPortsTest {

    // ---- §3.2 terrain rules ----
    @Test
    fun terrainIdsAndRulesMatchSection32() {
        assertEquals(0, TERRAIN_FLOOR); assertEquals(1, TERRAIN_WALL); assertEquals(2, TERRAIN_FUEL)
        assertEquals(3, TERRAIN_REFILL); assertEquals(4, TERRAIN_EXIT); assertEquals(5, TERRAIN_PROTECTED)

        assertTrue(terrainPassable(TERRAIN_FLOOR))
        assertTrue(terrainPassable(TERRAIN_FUEL))
        assertTrue(terrainPassable(TERRAIN_REFILL))
        assertTrue(terrainPassable(TERRAIN_EXIT))
        assertFalse(terrainPassable(TERRAIN_WALL))
        assertFalse(terrainPassable(TERRAIN_PROTECTED))   // impassable but burnable

        assertTrue(terrainBurnable(TERRAIN_FLOOR))
        assertTrue(terrainBurnable(TERRAIN_FUEL))
        assertTrue(terrainBurnable(TERRAIN_EXIT))
        assertTrue(terrainBurnable(TERRAIN_PROTECTED))
        assertFalse(terrainBurnable(TERRAIN_WALL))
        assertFalse(terrainBurnable(TERRAIN_REFILL))

        assertEquals('.', terrainGlyph(TERRAIN_FLOOR))
        assertEquals('#', terrainGlyph(TERRAIN_WALL))
        assertEquals('F', terrainGlyph(TERRAIN_FUEL))
        assertEquals('W', terrainGlyph(TERRAIN_REFILL))
        assertEquals('E', terrainGlyph(TERRAIN_EXIT))
        assertEquals('P', terrainGlyph(TERRAIN_PROTECTED))
    }

    // ---- authored layout decoding ----
    @Test
    fun decodeMapsGlyphsFireLevelsAndMarkers() {
        val terrain = openTerrainRows(3, 4)
        val edited = editRows(terrain, listOf(Triple(5, 4, GLYPH_WALL), Triple(6, 4, GLYPH_FUEL), Triple(7, 4, GLYPH_REFILL), Triple(8, 4, GLYPH_EXIT), Triple(9, 4, GLYPH_PROTECTED), Triple(10, 4, GLYPH_CIVILIAN)))
        val fires = fireRows(listOf(Triple(1, 0, '1'), Triple(2, 0, '2'), Triple(3, 0, '3'), Triple(4, 0, '4')))
        val layout = Layout.decode(edited, fires, DIR_N)

        assertEquals(TERRAIN_FLOOR, layout.terrainAt(3, 4))   // 'R' is floor under the spawn
        assertEquals(TERRAIN_WALL, layout.terrainAt(5, 4))
        assertEquals(TERRAIN_FUEL, layout.terrainAt(6, 4))
        assertEquals(TERRAIN_REFILL, layout.terrainAt(7, 4))
        assertEquals(TERRAIN_EXIT, layout.terrainAt(8, 4))
        assertEquals(TERRAIN_PROTECTED, layout.terrainAt(9, 4))
        assertEquals(TERRAIN_FLOOR, layout.terrainAt(10, 4))  // a civilian does not change terrain id

        assertEquals(25, layout.fireAt(1, 0))
        assertEquals(50, layout.fireAt(2, 0))
        assertEquals(75, layout.fireAt(3, 0))
        assertEquals(100, layout.fireAt(4, 0))
        assertEquals(0, layout.fireAt(5, 0))

        assertEquals(3, layout.roverX); assertEquals(4, layout.roverY); assertEquals(DIR_N, layout.roverDir)
        assertEquals(1, layout.civilians.size)
        assertEquals(CivilianSpec(1, 10, 4, MAX_HP, true), layout.civilians[0])
        assertTrue(layout.validate().isEmpty())
    }

    @Test
    fun decodeRejectsBadAuthoring() {
        // wrong row length
        assertThrows(IllegalArgumentException::class.java) {
            Layout.decode(listOf("..................."), noFireRows().let { it }, DIR_E)
        }
        // unknown glyph
        assertThrows(IllegalArgumentException::class.java) {
            Layout.decode(editRows(openTerrainRows(), listOf(Triple(5, 2, '?'))), noFireRows(), DIR_E)
        }
        // two spawns / no spawn
        assertThrows(IllegalArgumentException::class.java) {
            Layout.decode(
                editRows(openTerrainRows(), listOf(Triple(7, 7, GLYPH_ROVER))),
                noFireRows(), DIR_E,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val noSpawn = editRows(openTerrainRows(), listOf(Triple(2, 2, GLYPH_FLOOR)))
            Layout.decode(noSpawn, noFireRows(), DIR_E)
        }
    }

    @Test
    fun layoutValidationRejectsImpossibleMissions() {
        // civilian standing in a wall
        val civInWall = layoutOf(
            editRows(openTerrainRows(), listOf(Triple(9, 9, GLYPH_WALL))),
            noFireRows(),
            civilians = listOf(CivilianSpec(1, 9, 9)),
        )
        assertTrue(civInWall.validate().any { it.contains("impassable") })

        // starting fire on non-burnable terrain
        val fireOnRefill = layoutOf(openTerrainRows(), fireRows(listOf(Triple(6, 6, '2'))))
        val withRefill = Layout.decode(
            editRows(openTerrainRows(), listOf(Triple(6, 6, GLYPH_REFILL))),
            fireRows(listOf(Triple(6, 6, '2'))), DIR_E,
        )
        assertTrue(fireOnRefill.validate().isEmpty())
        assertTrue(withRefill.validate().any { it.contains("not burnable") })

        // exit sealed off behind walls -> unreachable
        val sealed = editRows(openTerrainRows(), buildList {
            for (y in 0 until GRID) for (x in 0 until GRID) if (x == 10) add(Triple(x, y, GLYPH_WALL))
            add(Triple(15, 15, GLYPH_EXIT))
        })
        val sealedLayout = Layout.decode(sealed, noFireRows(), DIR_E)
        assertTrue(sealedLayout.validate().any { it.contains("unreachable") })
    }

    // ---- MOVE / TURN timing and blocking ----
    @Test
    fun moveOccupiesTwoTicksAndMovesOnTheSecond() {
        val w = worldOf(firmwareSource = "loop:  MOVE\n       JMP    loop")
        assertEquals(2, ISA.cost(0xB4), "MOVE occupies two ticks (platform spec §6)")
        w.tickOnce()
        assertEquals(2, ISA.cost(0xB4))
        assertTrue(w.rover.vm.inProgress, "MOVE tick 1 must still be in progress")
        assertEquals(2, w.rover.x); assertEquals(2, w.rover.y)
        w.tickOnce()
        assertFalse(w.rover.vm.inProgress)
        assertEquals(3, w.rover.x, "MOVE completes on its final tick")
        // JMP occupies the third tick, so a MOVE loop advances every 3 ticks
        w.tickOnce()
        assertEquals(3, w.rover.x)
        w.tickOnce()
        assertEquals(3, w.rover.x)
        w.tickOnce()
        assertEquals(4, w.rover.x)
    }

    @Test
    fun moveIsBlockedByWallBoundaryAndProtectedAsset() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_WALL))),
            firmwareSource = "loop:  MOVE\n       JMP    loop",
        )
        repeat(6) { w.tickOnce() }
        assertEquals(2, w.rover.x); assertEquals(2, w.rover.y)
        assertEquals(0, w.rover.distanceMoved, "a failed move is a silent no-op (§3.5)")

        val edge = worldOf(
            terrain = openTerrainRows(0, 10),
            firmwareSource = "loop:  MOVE\n       JMP    loop",
            dir = DIR_W,
        )
        repeat(6) { edge.tickOnce() }
        assertEquals(0, edge.rover.x); assertEquals(10, edge.rover.y)

        val asset = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_PROTECTED))),
            firmwareSource = "loop:  MOVE\n       JMP    loop",
        )
        repeat(6) { asset.tickOnce() }
        assertEquals(2, asset.rover.x, "protected assets block movement")
    }

    @Test
    fun fireAndCiviliansDoNotBlockMovement() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_CIVILIAN))),
            fire = fireRows(listOf(Triple(3, 2, '4'))),
            firmwareSource = "loop:  MOVE\n       JMP    loop",
        )
        repeat(4) { w.tickOnce() }
        assertEquals(3, w.rover.x, "fire does not make a passable cell impassable (§3.3)")
        assertEquals(0, w.aheadBlocked())
    }

    @Test
    fun turnLeftRightAndImmediateUseTheSharedEncoding() {
        // TURN costs one tick, so a TURN/JMP loop turns on every second tick.
        val w = worldOf(firmwareSource = "loop:  TURN   R\n       JMP    loop")
        assertEquals(DIR_E, w.rover.facing)
        w.tickOnce(); assertEquals(DIR_S, w.rover.facing)     // tick 1: TURN R
        w.tickOnce(); assertEquals(DIR_S, w.rover.facing)     // tick 2: JMP
        w.tickOnce(); assertEquals(DIR_W, w.rover.facing)     // tick 3: TURN R

        val l = worldOf(firmwareSource = "loop:  TURN   L\n       JMP    loop")
        l.tickOnce(); assertEquals(DIR_N, l.rover.facing)

        val imm = worldOf(firmwareSource = "loop:  TURN   #2\n       JMP    loop")
        imm.tickOnce(); assertEquals(DIR_W, imm.rover.facing)
    }

    @Test
    fun dirWriteSetsFacingMaskedToTwoBits() {
        val w = worldOf(firmwareSource = "loop:  MOV    A,#6\n       OUT    DIR, A\n       JMP    loop")
        w.tickOnce()          // MOV
        w.tickOnce()          // OUT DIR
        assertEquals(6, w.rover.vm.a)
        assertEquals(DIR_E, w.rover.facing, "A & 3 == 2 (§6.2)")
    }

    @Test
    fun nonDirWritesAreIgnoredWithoutFault() {
        val before = w0()
        val hpBefore = before.rover.hp
        val w = worldOf(firmwareSource = "loop:  MOV    A,#77\n       OUT    HEAT, A\n       OUT    200, A\n       JMP    loop")
        repeat(4) { w.tickOnce() }
        assertEquals(0, w.rover.heat, "FIRELINE v1 has no writable output port besides DIR")
        assertEquals(0, w.sensor(FirelinePorts.HEAT))
        assertEquals(hpBefore, w.rover.hp)
    }

    private fun w0() = worldOf()

    // ---- §6.2 base ports ----
    @Test
    fun basePortsReportRoverBodyState() {
        val w = worldOf(terrain = openTerrainRows(4, 5), dir = DIR_S, water = 60)
        assertEquals(MAX_HP, w.sensor(FirelinePorts.HP))
        assertEquals(DIR_S, w.sensor(FirelinePorts.DIR))
        assertEquals(4, w.sensor(FirelinePorts.RX))
        assertEquals(5, w.sensor(FirelinePorts.RY))
        assertEquals(0, w.sensor(FirelinePorts.FIRE), "FIRE stays reserved/read-zero (§5.1)")
        assertEquals(0, w.sensor(FirelinePorts.SHIELD))
        assertEquals(0, w.sensor(FirelinePorts.HEAT))
        assertEquals(60, w.sensor(FirelinePorts.WATER))
        assertEquals(TERRAIN_FLOOR, w.sensor(FirelinePorts.TILE))
        assertEquals(0, w.sensor(FirelinePorts.LOCAL_FIRE))
        assertEquals(0, w.sensor(15), "reserved port 15 reads zero")
        assertEquals(0, w.sensor(31), "reserved port 31 reads zero")
        assertEquals(0, w.sensor(255), "unknown port reads zero (§4.2)")
    }

    @Test
    fun inPortExecutesThroughTheVmAndSetsFlags() {
        val w = worldOf(firmwareSource = "loop:  IN     HP\n       JMP    loop")
        w.tickOnce()
        assertEquals(MAX_HP, w.rover.vm.a)
        assertEquals(0, w.rover.vm.flags and VmZFlag(), "non-zero read clears Z")
        val z = worldOf(firmwareSource = "loop:  IN     200\n       JMP    loop")
        z.tickOnce()
        assertEquals(0, z.rover.vm.a)
        assertEquals(2, z.rover.vm.flags and VmZFlag(), "unknown IN returns 0 and sets Z (§4.2)")
    }

    private fun VmZFlag() = robofight.vm.Vm.FLAG_Z

    // ---- §6.1 primary target (selected fire) ----
    @Test
    fun primaryTargetPortsMirrorTheFirePortsAndSentinels() {
        val w = worldOf(
            terrain = openTerrainRows(2, 2),
            fire = fireRows(listOf(Triple(2, 5, '2'))),   // (2,5) intensity 50, 3 cells south
            dir = DIR_S,
        )
        val t = w.selectedFire()
        assertNotNull(t)
        assertEquals(2, t!!.x); assertEquals(5, t.y); assertEquals(50, t.intensity); assertEquals(3, t.dist)

        assertEquals(3, w.sensor(FirelinePorts.DIST))
        assertEquals(0, w.sensor(FirelinePorts.ANGLE), "already facing south toward it")
        assertEquals(50, w.sensor(FirelinePorts.ENEMY_HP))
        assertEquals(3, w.sensor(FirelinePorts.FIRE_DIST))
        assertEquals(0, w.sensor(FirelinePorts.FIRE_ANGLE))
        assertEquals(50, w.sensor(FirelinePorts.FIRE_LEVEL))

        val none = worldOf()
        assertNull(none.selectedFire())
        assertEquals(NO_TARGET_DIST, none.sensor(FirelinePorts.DIST))
        assertEquals(NO_TARGET_DIST, none.sensor(FirelinePorts.FIRE_DIST))
        assertEquals(NO_TARGET_ANGLE, none.sensor(FirelinePorts.ANGLE))
        assertEquals(NO_TARGET_ANGLE, none.sensor(FirelinePorts.FIRE_ANGLE))
        assertEquals(NO_TARGET_ENEMY_HP, none.sensor(FirelinePorts.ENEMY_HP))
        assertEquals(NO_TARGET_LEVEL, none.sensor(FirelinePorts.FIRE_LEVEL))
    }

    @Test
    fun selectedFireOrdersByDistanceThenIntensityThenRowMajor() {
        // same distance, higher intensity wins
        val byIntensity = worldOf(fire = fireRows(listOf(Triple(4, 2, '1'), Triple(0, 2, '4'))))
        val t1 = byIntensity.selectedFire()!!
        assertEquals(0, t1.x); assertEquals(2, t1.y); assertEquals(100, t1.intensity)

        // same distance AND same intensity -> row-major: smaller y first, then smaller x
        val sameRow = worldOf(fire = fireRows(listOf(Triple(4, 2, '2'), Triple(0, 2, '2'))))
        val t2 = sameRow.selectedFire()!!
        assertEquals(0, t2.x); assertEquals(2, t2.y)
        val sameColumn = worldOf(fire = fireRows(listOf(Triple(2, 4, '2'), Triple(2, 0, '2'))))
        val t3 = sameColumn.selectedFire()!!
        assertEquals(2, t3.x); assertEquals(0, t3.y, "row-major tie-break prefers the earlier row")

        // nearest wins even when a hotter fire exists elsewhere
        val byDistance = worldOf(fire = fireRows(listOf(Triple(3, 2, '1'), Triple(10, 2, '4'))))
        val t4 = byDistance.selectedFire()!!
        assertEquals(3, t4.x); assertEquals(2, t4.y)
    }

    @Test
    fun anglePicksTheHorizontalAxisWhenAbsDxIsAtLeastAbsDy() {
        // rover (2,2) facing north, target (4,4): dx=2 dy=2 -> horizontal axis wins
        // -> face east; clockwise delta from north is 1
        val w = worldOf(terrain = openTerrainRows(2, 2), fire = fireRows(listOf(Triple(4, 4, '2'))), dir = DIR_N)
        assertEquals(1, w.sensor(FirelinePorts.ANGLE))
        // a target closer vertically takes the vertical axis instead
        val v = worldOf(terrain = openTerrainRows(2, 2), fire = fireRows(listOf(Triple(4, 6, '2'))), dir = DIR_N)
        assertEquals(2, v.sensor(FirelinePorts.ANGLE), "dy>dx -> vertical axis -> south = 2 clockwise steps")
    }

    @Test
    fun angleSentinelAndDeltaMatchSharedDirectionEncoding() {
        val w = worldOf(terrain = openTerrainRows(2, 2), fire = fireRows(listOf(Triple(4, 4, '2'))), dir = DIR_N)
        val t = w.selectedFire()!!
        assertEquals(4, t.dist)
        assertEquals(1, w.sensor(FirelinePorts.ANGLE), "N facing, horizontal axis wins -> east = 1 clockwise step")
        val facingEast = worldOf(terrain = openTerrainRows(2, 2), fire = fireRows(listOf(Triple(4, 4, '2'))), dir = DIR_E)
        assertEquals(0, facingEast.sensor(FirelinePorts.ANGLE))
        val vertical = worldOf(terrain = openTerrainRows(2, 2), fire = fireRows(listOf(Triple(2, 5, '2'))), dir = DIR_E)
        assertEquals(1, vertical.sensor(FirelinePorts.ANGLE), "E facing, target due south -> 1 clockwise step")
    }

    @Test
    fun aheadReportsBoundaryWallAndProtectedAssetOnly() {
        val w = worldOf(terrain = openTerrainRows(2, 2), dir = DIR_E)
        assertEquals(0, w.sensor(FirelinePorts.AHEAD))
        val wall = worldOf(terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_WALL))), dir = DIR_E)
        assertEquals(1, wall.sensor(FirelinePorts.AHEAD))
        val asset = worldOf(terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_PROTECTED))), dir = DIR_E)
        assertEquals(1, asset.sensor(FirelinePorts.AHEAD))
        val edge = worldOf(terrain = openTerrainRows(19, 2), dir = DIR_E)
        assertEquals(1, edge.sensor(FirelinePorts.AHEAD))
        val fuel = worldOf(terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_FUEL))), dir = DIR_E)
        assertEquals(0, fuel.sensor(FirelinePorts.AHEAD))
    }

    // ---- §4.3 RNG streams ----
    @Test
    fun randPortIsSeededDeterministicPerStream() {
        val one = worldOf(seed = 42)
        val drawn = List(8) { one.sensor(FirelinePorts.RAND) }
        val two = worldOf(seed = 42)
        val repeated = List(8) { two.sensor(FirelinePorts.RAND) }
        val other = worldOf(seed = 43)
        val otherSeed = List(8) { other.sensor(FirelinePorts.RAND) }

        assertEquals(drawn, repeated, "same seed must produce the same sensor stream")
        assertTrue(drawn.any { it != 0 }, "sensor stream must actually produce bytes")
        assertTrue(drawn.distinct().size > 1, "the stream must advance, not repeat one byte")
        assertTrue(drawn != otherSeed, "different seeds must give different sensor streams")
    }

    @Test
    fun randDrawsDoNotTouchTheSpreadStream() {
        val w = worldOf(seed = 7)
        val spreadBefore = w.spreadRng.state
        val drawn = List(20) { w.sensor(FirelinePorts.RAND) }
        assertEquals(20, drawn.size)
        assertEquals(spreadBefore, w.spreadRng.state, "IN RAND must not advance the spread stream (§4.3)")
        assertEquals(LcgStream.forSpread(7).state, w.spreadRng.state)
        assertTrue(w.sensorRng.state != LcgStream.forSensor(7).state, "the sensor stream did advance")
    }

    @Test
    fun generatorConstantsMatchPlatformDocument() {
        val stream = LcgStream.forSensor(0)
        val expected = (0L xor LcgStream.SENSOR_SALT.toLong()) and LcgStream.STATE_MASK.toLong()
        assertEquals(expected.toInt(), stream.state)
        val spread = LcgStream.forSpread(0)
        assertEquals((0L xor LcgStream.SPREAD_SALT.toLong()).toInt(), spread.state)
        // next state + byte derivation
        val s = LcgStream(1)
        val nextState = (1L * LcgStream.MULTIPLIER + LcgStream.INCREMENT) and LcgStream.STATE_MASK.toLong()
        assertEquals(nextState.toInt(), LcgStream.nextOf(1))
        assertEquals(((nextState shr 16) and 0xFF).toInt(), LcgStream.byteOf(LcgStream.nextOf(1)))
    }

    // ---- discovery ports for this profile ----
    @Test
    fun discoveryPortsAnnounceTheFirelineProfile() {
        val w = worldOf()
        assertEquals(1, w.sensor(FirelinePorts.PROFILE_PORT))
        assertEquals(1, w.sensor(FirelinePorts.PROFILE_REV_PORT))
        assertEquals(0x7F, w.sensor(FirelinePorts.CAPS_PORT))
        assertTrue(FirelinePorts.checkCatalogParity().isEmpty(),
            "profile/catalog parity: ${FirelinePorts.checkCatalogParity()}")
    }

    // ---- zero page + rendering ----
    @Test
    fun tickCounterIsPublishedToTheZeroPageBeforeTheRoverActs() {
        val w = worldOf(firmwareSource = "loop:  MOV    A,[0]\n       JMP    loop")
        repeat(301) { w.tickOnce() }
        assertEquals(301, w.tick)
        assertEquals(301 and 0xFF, w.rover.vm.mem[ISA.SYS_TICK].toInt() and 0xFF)
        assertEquals(1, w.rover.vm.mem[ISA.SYS_TICK + 1].toInt() and 0xFF)
        assertEquals(0, w.rover.vm.mem[ISA.SYS_TICK + 2].toInt() and 0xFF)
        // 301 ticks of a MOV/JMP loop: odd ticks execute the MOV, so A holds tick 301
        assertEquals(45, w.rover.vm.a, "the rover reads its own tick from the zero page")
    }

    @Test
    fun textGridRendersTerrainFireCiviliansAndRover() {
        val w = worldOf(
            terrain = editRows(openTerrainRows(2, 2), listOf(
                Triple(4, 2, GLYPH_WALL), Triple(5, 2, GLYPH_FUEL), Triple(6, 2, GLYPH_EXIT),
                Triple(7, 2, GLYPH_REFILL), Triple(8, 2, GLYPH_PROTECTED), Triple(9, 2, GLYPH_CIVILIAN),
            )),
            fire = fireRows(listOf(Triple(3, 2, '2'))),
        )
        assertEquals(TextGrid.ROVER_GLYPH, TextGrid.glyphAt(w, 2, 2))
        assertEquals('3', TextGrid.glyphAt(w, 3, 2))          // intensity 50 -> glyph '3' (50..74)
        assertEquals('#', TextGrid.glyphAt(w, 4, 2))
        assertEquals('F', TextGrid.glyphAt(w, 5, 2))
        assertEquals('E', TextGrid.glyphAt(w, 6, 2))
        assertEquals('W', TextGrid.glyphAt(w, 7, 2))
        assertEquals('P', TextGrid.glyphAt(w, 8, 2))
        assertEquals('C', TextGrid.glyphAt(w, 9, 2))
        assertEquals('.', TextGrid.glyphAt(w, 0, 0))
        assertEquals('1', TextGrid.fireGlyph(24))
        assertEquals('2', TextGrid.fireGlyph(25))
        assertEquals('4', TextGrid.fireGlyph(100))
        assertNull(TextGrid.fireGlyph(0))

        val grid = w.render()
        assertTrue(grid.contains("FIRELINE v$FIRELINE_ENGINE_VERSION"), grid.lines().first())
        assertTrue(grid.contains("rover 2,2 dir=E"), grid)
        assertTrue(grid.contains("fire cells=1"), grid)
        // 20 rendered grid rows of exactly 21 chars (row label + 20 cells)
        val body = grid.lines().filter { it.length == 21 && !it.contains('+') }
        assertEquals(GRID, body.size)
    }
}
