package robofight.fireline

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThinWallsTest {
    private fun edges(vararg pairs: Pair<Pair<Int, Int>, Pair<Int, Int>>): Layout {
        val v = BooleanArray(GRID * (GRID - 1))
        val h = BooleanArray(GRID * (GRID - 1))
        for ((a, b) in pairs) {
            if (a.second == b.second) v[a.second * (GRID - 1) + minOf(a.first, b.first)] = true
            else h[minOf(a.second, b.second) * GRID + a.first] = true
        }
        val base = layoutOf()
        return Layout(base.terrain, base.fire, base.roverX, base.roverY, base.roverDir, base.civilians, v, h)
    }

    @Test
    fun everyDirectionBlocksMovementAndAheadWithoutOccupyingEitherCell() {
        for (d in 0..3) {
            val nx = 2 + dirDx(d); val ny = 2 + dirDy(d)
            val layout = edges((2 to 2) to (nx to ny))
            assertTrue(layout.wallBetween(2, 2, nx, ny))
            assertTrue(layout.wallBetween(nx, ny, 2, 2))
            assertEquals(TERRAIN_FLOOR, layout.terrainAt(nx, ny))
            val w = FirelineWorld(descriptorOf(layout, "MOVE\nWAIT"))
            w.rover.facing = d
            assertEquals(1, w.sensor(FirelinePorts.AHEAD))
            w.tickOnce()
            assertTrue(w.rover.vm.inProgress)
            w.tickOnce()
            assertEquals(2 to 2, w.rover.x to w.rover.y)
            w.rover.turn(1)
            assertEquals(0, w.sensor(FirelinePorts.AHEAD))
        }
        val open = worldOf(firmwareSource = "MOVE\nWAIT")
        repeat(2) { open.tickOnce() }
        assertEquals(3, open.rover.x)
    }

    @Test
    fun waterIsConsumedAtSpawnAndInFlightAcrossBothOrientations() {
        for (d in 0..3) {
            val nx = 2 + dirDx(d); val ny = 2 + dirDy(d)
            val near = FirelineWorld(descriptorOf(edges((2 to 2) to (nx to ny)), "SHOOT\nWAIT"))
            near.rover.facing = d
            repeat(3) { near.tickOnce() }
            assertEquals(0, near.packetsOrdered().size)
            assertEquals(90, near.rover.water)
            assertTrue(near.lastEvents.any { it.startsWith("PACKET BLOCKED EDGE") })

            val far = FirelineWorld(descriptorOf(edges((nx to ny) to (nx + dirDx(d) to ny + dirDy(d))), "SHOOT\nWAIT"))
            far.rover.facing = d
            repeat(3) { far.tickOnce() }
            assertEquals(1, far.packetsOrdered().size)
            far.tickOnce()
            assertTrue(far.packetsOrdered().isEmpty())
            assertTrue(far.lastEvents.any { it.startsWith("PACKET SPENT EDGE") })
        }
    }

    @Test
    fun wallsBlockSpreadButKeepDrawCountAndTargetSensorsUnchanged() {
        val center = 10 to 10
        val enclosed = edges(*(0..3).map { center to (10 + dirDx(it) to 10 + dirDy(it)) }.toTypedArray())
        var openIgnitions = 0
        for (seed in 1..100) {
            val blocked = FirelineWorld(descriptorOf(enclosed, seed = seed, windForce = 3, windDir = DIR_E))
            val open = worldOf(seed = seed, windForce = 3, windDir = DIR_E)
            blocked.setFire(10, 10, 100)
            open.setFire(10, 10, 100)
            assertEquals(open.sensor(FirelinePorts.FIRE_DIST), blocked.sensor(FirelinePorts.FIRE_DIST))
            repeat(5) { blocked.tickOnce(); open.tickOnce() }
            assertEquals(1, blocked.fireCellsBurning())
            assertEquals(open.spreadRng.state, blocked.spreadRng.state)
            if (open.fireCellsBurning() > 1) openIgnitions++
        }
        assertTrue(openIgnitions > 0)
    }

    @Test
    fun edgeMapsRoundTripAndAreDefensivelyCopied() {
        val v = MutableList(GRID) { ".".repeat(GRID - 1) }
        val h = MutableList(GRID - 1) { ".".repeat(GRID) }
        v[2] = "..|" + ".".repeat(16)
        h[4] = ".....-" + ".".repeat(14)
        val layout = Layout.decode(openTerrainRows(), noFireRows(), verticalEdgeRows = v, horizontalEdgeRows = h)
        assertEquals(v, Layout.encodeVerticalEdges(layout))
        assertEquals(h, Layout.encodeHorizontalEdges(layout))
        assertTrue(layout.wallBetween(2, 2, 3, 2))
        assertTrue(layout.wallBetween(5, 4, 5, 5))
        assertFalse(layout.wallBetween(-1, 2, 0, 2))
        assertFalse(layout.wallBetween(19, 2, 20, 2))
        assertFalse(layout.wallBetween(2, 2, 3, 3))
        val copy = layout.verticalEdgesRowMajor()
        copy.fill(false)
        assertTrue(layout.verticalEdgeAt(2, 2))
        val input = BooleanArray(380)
        val frozen = Layout(IntArray(CELLS), IntArray(CELLS), 2, 2, DIR_E, emptyList(), input)
        input[40] = true
        assertFalse(frozen.hasEdgeWalls())
        assertThrows(IllegalArgumentException::class.java) {
            Layout.decode(openTerrainRows(), noFireRows(), verticalEdgeRows = listOf("bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Layout.decode(openTerrainRows(), noFireRows(), verticalEdgeRows = List(GRID) { "x".repeat(19) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            Layout(IntArray(CELLS), IntArray(CELLS), 2, 2, DIR_E, emptyList(), BooleanArray(1))
        }
    }

    @Test
    fun reachabilityHonorsWallsAndStillFindsGaps() {
        val base = layoutOf(terrain = editRows(openTerrainRows(), listOf(Triple(4, 2, GLYPH_EXIT))))
        val v = BooleanArray(380)
        for (y in 0 until GRID) v[y * 19 + 2] = true
        val sealed = Layout(base.terrain, base.fire, 2, 2, DIR_E, emptyList(), v)
        assertTrue(sealed.validate().any { it.contains("exit (4,2) unreachable") })
        v[19 * 19 + 2] = false
        val gap = Layout(base.terrain, base.fire, 2, 2, DIR_E, emptyList(), v)
        assertTrue(gap.validate().isEmpty())
    }

    @Test
    fun geometryIsVisibleHeadlesslyAndIncludedInDeterministicHash() {
        val a = FirelineWorld(descriptorOf(edges((2 to 2) to (3 to 2), (5 to 4) to (5 to 5))))
        val b = FirelineWorld(descriptorOf(edges((2 to 2) to (3 to 2), (5 to 4) to (5 to 5))))
        val open = worldOf()
        assertNotEquals(open.finalStateHash(), a.finalStateHash())
        assertTrue(a.render().contains("R|"))
        assertTrue(a.render().contains('-'))
        repeat(20) { a.tickOnce(); b.tickOnce() }
        assertEquals(a.finalStateHash(), b.finalStateHash())
    }

    @Test
    fun allAuthoredMissionsUseThinWallsWithoutChangingTheirGridSize() {
        assertTrue(FIRELINE_ENGINE_VERSION >= 2)
        for (mission in Missions.ALL) {
            assertTrue(mission.layout.hasEdgeWalls(), mission.name)
            assertEquals(CELLS, mission.layout.terrain.size)
            assertTrue(mission.layout.validate().isEmpty(), mission.name)
            val decoded = Layout.decode(
                openTerrainRows(), noFireRows(),
                verticalEdgeRows = Layout.encodeVerticalEdges(mission.layout),
                horizontalEdgeRows = Layout.encodeHorizontalEdges(mission.layout),
            )
            assertArrayEquals(mission.layout.verticalEdgesRowMajor(), decoded.verticalEdgesRowMajor())
            assertArrayEquals(mission.layout.horizontalEdgesRowMajor(), decoded.horizontalEdgesRowMajor())
        }
    }
}
