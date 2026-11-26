package robofight.fireline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ported coverage from origin/main's `ThinWallsTest.kt` (`src/test/kotlin`),
 * absorbed into `commonTest` by the reconcile merge (PLAN-RECONCILE-MAIN-01.md
 * §8 decision 4: "EdgeWallsTest must be rewritten to the resolved rules and
 * absorb main's ThinWallsTest coverage; running both suites against different
 * rules is not an option").
 *
 * The cases ported here are the ones `EdgeWallsTest` does not already carry:
 * wallBetween for every cardinal orientation plus the blocked MOVE and AHEAD
 * sentinel, water refused at spawn and consumed in flight across both edge
 * orientations, the mission 01 firing lane refused at a thin edge, and the
 * spread guard that consumes the two RNG draws even when the wall blocks the
 * ignition. The main-side cases that this branch already covers with the
 * branch's own wording — defensive edge-array copies, canonical round trips,
 * sealed-exit validation, edge maps in the fingerprint — stay in
 * `EdgeWallsTest` and are not duplicated here.
 */
class ThinWallsTest {
    /** A wall-less open grid with thin walls on exactly the given cell pairs. */
    private fun edges(vararg pairs: Pair<Pair<Int, Int>, Pair<Int, Int>>): Layout {
        val v = BooleanArray(VERT_EDGE_SLOTS)
        val h = BooleanArray(HORIZ_EDGE_SLOTS)
        for ((a, b) in pairs) {
            if (a.second == b.second) v[a.second * VERT_EDGE_COLUMNS + minOf(a.first, b.first)] = true
            else h[minOf(a.second, b.second) * GRID + a.first] = true
        }
        val base = layoutOf()
        return Layout(base.terrain, base.fire, base.roverX, base.roverY, base.roverDir, base.civilians, v, h)
    }

    @Test
    fun everyDirectionBlocksMovementAndAheadWithoutOccupyingEitherCell() {
        for (d in 0..3) {
            val nx = 2 + dirDx(d)
            val ny = 2 + dirDy(d)
            val layout = edges((2 to 2) to (nx to ny))
            assertTrue(layout.wallBetween(2, 2, nx, ny), "wall between (2,2) and ($nx,$ny) facing $d")
            assertTrue(layout.wallBetween(nx, ny, 2, 2), "wallBetween is symmetric for facing $d")
            assertEquals(TERRAIN_FLOOR, layout.terrainAt(nx, ny), "the cell across the wall stays free terrain (facing $d)")
            val w = FirelineWorld(descriptorOf(layout = layout, firmwareSource = "MOVE\nWAIT"))
            w.rover.facing = d
            assertEquals(1, w.sensor(FirelinePorts.AHEAD), "AHEAD reports 1 through the thin edge (facing $d)")
            w.tickOnce()
            assertTrue(w.rover.vm.inProgress, "the MOVE is mid-execution on its first tick (facing $d)")
            w.tickOnce()
            assertEquals(2 to 2, w.rover.x to w.rover.y, "the blocked MOVE is a silent no-op (facing $d)")
            assertEquals(0, w.rover.distanceMoved, "the blocked MOVE adds no distance (facing $d)")
            w.rover.turn(1)
            assertEquals(0, w.sensor(FirelinePorts.AHEAD), "the unblocked facing reports open ground (facing $d)")
        }
        val open = worldOf(firmwareSource = "MOVE\nWAIT")
        repeat(2) { open.tickOnce() }
        assertEquals(3, open.rover.x, "without the wall the same firmware steps to (3,2)")
    }

    @Test
    fun waterIsRefusedAtSpawnAndConsumedInFlightAcrossBothOrientations() {
        for (d in 0..3) {
            val nx = 2 + dirDx(d)
            val ny = 2 + dirDy(d)

            // Wall on the spawn edge: the shot pays its ticks and water, no packet exists.
            val near = FirelineWorld(descriptorOf(edges((2 to 2) to (nx to ny)), firmwareSource = "SHOOT\nWAIT"))
            near.rover.facing = d
            repeat(3) { near.tickOnce() }
            assertEquals(0, near.packetsOrdered().size, "spawn refused across the walled edge (facing $d)")
            assertEquals(90, near.rover.water, "the refused shot still costs its water (facing $d)")
            assertTrue(near.lastEvents.any { it.startsWith("PACKET BLOCKED EDGE") }, "PACKET BLOCKED EDGE for facing $d: ${near.lastEvents}")

            // Wall one cell further: the packet spawns, travels, and dies AT the edge.
            val far = FirelineWorld(
                descriptorOf(edges((nx to ny) to (nx + dirDx(d) to ny + dirDy(d))), firmwareSource = "SHOOT\nWAIT"),
            )
            far.rover.facing = d
            repeat(3) { far.tickOnce() }
            assertEquals(1, far.packetsOrdered().size, "the packet spawned on the open side (facing $d)")
            far.tickOnce()
            assertTrue(far.packetsOrdered().isEmpty(), "the packet was consumed at the wall (facing $d)")
            assertTrue(far.lastEvents.any { it.startsWith("PACKET SPENT EDGE") }, "PACKET SPENT EDGE for facing $d: ${far.lastEvents}")
        }
    }

    /**
     * Main's mission-01 flavour of the spawn refusal: NOZZLE CHECK's lane
     * (rover (2,2) facing E, fire at (5,2)) with a thin wall on the rover's
     * east edge. The first SHOOT aims straight at that edge.
     */
    @Test
    fun mission01ShotAtAThinEdgeIsRefusedAtSpawn() {
        val nozzle = Missions.NOZZLE_CHECK
        val base = nozzle.layout
        assertEquals(DIR_E, base.roverDir, "mission 01 starts facing east down the nozzle lane")
        val v = BooleanArray(VERT_EDGE_SLOTS)
        v[SPAWN_ROW_FOR_EDGES * VERT_EDGE_COLUMNS + 2] = true // vertical wall right of (2,2)
        val layout = Layout(base.terrain, base.fire, base.roverX, base.roverY, base.roverDir, base.civilians, v)
        val w = FirelineWorld(
            descriptorOf(
                layout = layout,
                firmwareSource = "fire:  SHOOT\nloop:  WAIT\n       JMP    loop",
                seed = nozzle.seed,
                objectives = nozzle.objectives,
                timeLimit = nozzle.timeLimit,
                missionId = nozzle.id,
            ),
        )
        repeat(3) { w.tickOnce() } // SHOOT completes on tick 3 and would spawn at (3,2)
        assertEquals(0, w.packetsOrdered().size, "the shot aimed at the thin edge spawns no packet")
        assertEquals(90, w.rover.water, "the refused shot still costs 10 water")
        assertTrue(
            w.lastEvents.any { it.startsWith("PACKET BLOCKED EDGE 3,2") },
            "spawn refusal is reported at the walled target cell: ${w.lastEvents}",
        )
        // The burning target is untouched — the packet never crossed.
        assertTrue(w.fireAt(5, 2) > 0, "the fire down the lane is unaffected by the refused shot")
    }

    private companion object {
        const val SPAWN_ROW_FOR_EDGES = 2
    }

    @Test
    fun wallsBlockSpreadButKeepDrawCountAndTargetSensorsUnchanged() {
        val center = 10 to 10
        val enclosed = edges(*(0..3).map { center to (10 + dirDx(it) to 10 + dirDy(it)) }.toTypedArray())
        var openIgnitions = 0
        for (seed in 1..100) {
            val blocked = FirelineWorld(descriptorOf(layout = enclosed, seed = seed, windForce = 3, windDir = DIR_E))
            val open = worldOf(seed = seed, windForce = 3, windDir = DIR_E)
            blocked.setFire(10, 10, 100)
            open.setFire(10, 10, 100)
            assertEquals(open.sensor(FirelinePorts.FIRE_DIST), blocked.sensor(FirelinePorts.FIRE_DIST), "seed $seed: target sensors ignore edge walls")
            repeat(5) {
                blocked.tickOnce()
                open.tickOnce()
            }
            assertEquals(1, blocked.fireCellsBurning(), "seed $seed: the wall keeps every neighbour out (main's guard, PLAN-RECONCILE-MAIN-01.md §8)")
            assertEquals(open.spreadRng.state, blocked.spreadRng.state, "seed $seed: the wall never shifts the spread RNG stream (the guard sits after both draws)")
            if (open.fireCellsBurning() > 1) openIgnitions++
        }
        assertTrue(openIgnitions > 0, "the wall-less control actually ignites neighbours within 100 seeds, or the comparison proves nothing")
    }

    @Test
    fun aWalllessLayoutAndTheSameLayoutWithOneEdgeHashDifferentlyButDeterministically() {
        val a = FirelineWorld(descriptorOf(layout = edges((2 to 2) to (3 to 2), (5 to 4) to (5 to 5))))
        val b = FirelineWorld(descriptorOf(layout = edges((2 to 2) to (3 to 2), (5 to 4) to (5 to 5))))
        val open = worldOf()
        assertNotEquals(open.finalStateHash(), a.finalStateHash(), "edge maps are stamped into canonicalState")
        repeat(20) {
            a.tickOnce()
            b.tickOnce()
        }
        assertEquals(a.finalStateHash(), b.finalStateHash(), "the same walled layout replays byte-identically")
        assertTrue(a.canonicalState().contains("verticalEdges="), "canonicalState carries the vertical edge map (main's line 549)")
        assertTrue(a.canonicalState().contains("horizontalEdges="), "canonicalState carries the horizontal edge map (main's line 550)")
    }
}
