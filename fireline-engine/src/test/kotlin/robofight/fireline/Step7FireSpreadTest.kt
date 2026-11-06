package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §8.3 step 7: wind + deterministic fire growth/spread with separate RNG streams.
 *
 * Covers FIRELINE-DESIGN.md §4.1 (five-tick cadence, row-major snapshot, new
 * fires wait for a later step), §4.2 (growth table + 100 cap), §4.3 (stream
 * separation), §4.4 (fixed two-byte draw count, direction bag, thresholds, wind
 * bonus, ignition at 25) and the PLAN §8.4 gates on RAND independence and
 * seed-vs-terrain.
 */
class Step7FireSpreadTest {

    private val idle = "loop:  WAIT\n       JMP    loop"

    private fun burning(
        fire: List<String>,
        terrain: List<String> = openTerrainRows(),
        seed: Int = 4242,
        windDir: Int = DIR_E,
        windForce: Int = 0,
        firmwareSource: String = idle,
    ) = worldOf(
        terrain = terrain,
        fire = fire,
        seed = seed,
        windDir = windDir,
        windForce = windForce,
        firmwareSource = firmwareSource,
    )

    // ---- §4.1 cadence + §4.2 growth ----
    @Test
    fun fireGrowsOnlyOnSpreadTicks() {
        val w = burning(fireRows(listOf(Triple(10, 10, '1'))))     // floor, intensity 25
        repeat(4) { w.tickOnce() }
        assertEquals(25, w.fireAt(10, 10), "no growth before the five-tick step")
        w.tickOnce()                                              // tick 5: tick % 5 == 0
        assertEquals(30, w.fireAt(10, 10), "floor grows +5")
        repeat(4) { w.tickOnce() }
        assertEquals(30, w.fireAt(10, 10))
        w.tickOnce()                                              // tick 10
        assertEquals(35, w.fireAt(10, 10))
    }

    @Test
    fun growthRatesFollowTheTerrainTableAndCapAt100() {
        val terrain = editRows(
            openTerrainRows(),
            listOf(
                Triple(5, 5, GLYPH_FLOOR),       // control floor
                Triple(6, 5, GLYPH_FUEL),
                Triple(7, 5, GLYPH_EXIT),
                Triple(8, 5, GLYPH_PROTECTED),
                Triple(9, 5, GLYPH_REFILL),      // non-burnable: never authored burning
                Triple(10, 5, GLYPH_FUEL),
            ),
        )
        val fire = fireRows(
            listOf(Triple(5, 5, '1'), Triple(6, 5, '1'), Triple(7, 5, '1'),
                Triple(8, 5, '1'), Triple(10, 5, '4')),
        )
        val w = burning(fire = fire, terrain = terrain)
        repeat(5) { w.tickOnce() }                                // tick 5 is the spread step
        assertEquals(30, w.fireAt(5, 5), "floor +5")
        assertEquals(35, w.fireAt(6, 5), "fuel +10")
        assertEquals(30, w.fireAt(7, 5), "exit +5 and it still burns")
        assertEquals(35, w.fireAt(8, 5), "protected asset +10")
        assertEquals(0, w.fireAt(9, 5), "refill never burns")
        assertEquals(FIRE_MAX, w.fireAt(10, 5), "growth is clamped at 100")
        repeat(40) { w.tickOnce() }
        assertEquals(FIRE_MAX, w.fireAt(10, 5), "clamped forever")
        assertEquals(1, w.assetsLost(), "the protected asset reached intensity 100")
    }

    @Test
    fun aNewlyIgnitedCellWaitsForALaterSpreadStep() {
        val fuelRing = listOf(Triple(9, 10, GLYPH_FUEL), Triple(11, 10, GLYPH_FUEL),
            Triple(10, 9, GLYPH_FUEL), Triple(10, 11, GLYPH_FUEL))
        val w = burning(
            fire = fireRows(listOf(Triple(10, 10, '4'))),
            terrain = editRows(openTerrainRows(), fuelRing),
            seed = 7,
        )
        var ignitedAt = -1
        var intensityAtIgnition = -1
        while (ignitedAt < 0 && w.tick < 60) {
            val before = w.fireCellsBurning()
            w.tickOnce()
            if (w.fireCellsBurning() > before) {
                ignitedAt = w.tick
                intensityAtIgnition = w.fireIntensitySum() - w.fireAt(10, 10)
            }
        }
        assertTrue(ignitedAt > 0, "seed 7 must ignite something within 60 ticks")
        assertEquals(IGNITION_INTENSITY, intensityAtIgnition, "a new cell starts at 25")
        assertTrue(ignitedAt % 5 == 0, "ignition only happens on a spread step")
        // the next spread step is the first time the new cell grows
        val atIgnition = w.fireIntensitySum()
        repeat(4) { w.tickOnce() }
        assertEquals(atIgnition, w.fireIntensitySum(), "it does not grow in its own ignition step")
        w.tickOnce()
        assertTrue(w.fireIntensitySum() > atIgnition, "it grows at the next step")
    }

    // ---- §4.3 stream separation ----
    @Test
    fun diagnosticRandReadsCannotChangeFireSpread() {
        val layoutFire = listOf(Triple(4, 2, '4'), Triple(9, 6, '2'))
        val a = burning(
            fire = fireRows(layoutFire), seed = 99,
            firmwareSource = "loop:  SHOOT\n       WAIT\n       WAIT\n       JMP    loop",
        )
        val b = burning(
            fire = fireRows(layoutFire), seed = 99,
            firmwareSource = "loop:  SHOOT\n       IN     ${FirelinePorts.RAND}\n       IN     ${FirelinePorts.RAND}\n       JMP    loop",
        )
        val historyA = ArrayList<String>()
        val historyB = ArrayList<String>()
        repeat(60) {
            a.tickOnce(); b.tickOnce()
            historyA.add("${a.tick}|${a.fireRowMajor().joinToString(",")}")
            historyB.add("${b.tick}|${b.fireRowMajor().joinToString(",")}")
        }
        assertEquals(historyB, historyA, "identical physical timeline must give identical fire history")
        assertEquals(a.spreadRng.state, b.spreadRng.state, "the spread stream must be untouched by IN RAND")
        assertNotEquals(a.sensorRng.state, b.sensorRng.state, "the sensor stream did consume draws")
        assertEquals(a.rover.shots, b.rover.shots)
    }

    @Test
    fun spreadConsumesExactlyTwoBytesPerSnapshotCell() {
        val w = burning(fire = fireRows(listOf(Triple(10, 10, '2'))), seed = 5150)
        var visits = 0
        repeat(40) {
            if ((w.tick + 1) % FIRE_SPREAD_PERIOD == 0) visits += w.fireCellsBurning()
            w.tickOnce()
        }
        var expected = LcgStream.forSpread(5150).state
        repeat(2 * visits) { expected = LcgStream.nextOf(expected) }
        assertEquals(expected, w.spreadRng.state,
            "the stream advanced exactly 2 bytes per snapshot cell, never per neighbour")
    }

    // ---- determinism / seeds ----
    @Test
    fun spreadIsIdenticalForRepeatedRunsWithTheSameSeed() {
        val fire = fireRows(listOf(Triple(10, 10, '4'), Triple(11, 10, '2')))
        val terrain = editRows(openTerrainRows(10, 12), List(8) { Triple(6 + it, 6, GLYPH_FUEL) })
        val a = burning(fire = fire, terrain = terrain, seed = 20251106)
        val b = burning(fire = fire, terrain = terrain, seed = 20251106)
        repeat(50) { a.tickOnce(); b.tickOnce() }
        assertContentEquals(a, b)
        assertEquals(a.spreadRng.state, b.spreadRng.state)
    }

    @Test
    fun differentSeedsChangeSpreadButNeverChangeAuthoredTerrain() {
        val fire = fireRows(listOf(Triple(10, 10, '4')))
        val terrain = editRows(
            openTerrainRows(10, 12),
            List(12) { Triple(4 + it, 4 + (it % 4), GLYPH_FUEL) },
        )
        val terrains = HashSet<String>()
        val fireStates = HashSet<String>()
        for (seed in 1..8) {
            val w = burning(fire = fire, terrain = terrain, seed = seed)
            repeat(40) { w.tickOnce() }
            terrains.add(w.terrainRowMajor().joinToString(","))
            fireStates.add(w.fireRowMajor().joinToString(","))
        }
        assertEquals(1, terrains.size, "authored static terrain must not depend on the seed (§8.4)")
        assertTrue(fireStates.size > 1, "seeds must be able to alter spread, got $fireStates")
    }

    // ---- §4.4 wind ----
    @Test
    fun directionBagRepeatsTheWindDirectionByForce() {
        assertEquals(listOf(DIR_W, DIR_N, DIR_E, DIR_S), spreadDirectionBag(DIR_E, 0).toList())
        assertEquals(listOf(DIR_W, DIR_N, DIR_E, DIR_S, DIR_N), spreadDirectionBag(DIR_N, 1).toList())
        assertEquals(listOf(DIR_W, DIR_N, DIR_E, DIR_S, DIR_W, DIR_W, DIR_W), spreadDirectionBag(DIR_W, 3).toList())
    }

    @Test
    fun thresholdsMatchTheSpecTableAtWindForceZeroAndThree() {
        assertEquals(64, spreadThresholdFor(TERRAIN_FLOOR, DIR_N, DIR_E, 0))
        assertEquals(64, spreadThresholdFor(TERRAIN_EXIT, DIR_N, DIR_E, 0))
        assertEquals(160, spreadThresholdFor(TERRAIN_FUEL, DIR_N, DIR_E, 0))
        assertEquals(160, spreadThresholdFor(TERRAIN_PROTECTED, DIR_N, DIR_E, 0))
        assertEquals(0, spreadThresholdFor(TERRAIN_WALL, DIR_E, DIR_E, 3))
        assertEquals(0, spreadThresholdFor(TERRAIN_REFILL, DIR_E, DIR_E, 3))

        assertEquals(64 + 16, spreadThresholdFor(TERRAIN_FLOOR, DIR_E, DIR_E, 1))
        assertEquals(64 + 48, spreadThresholdFor(TERRAIN_FLOOR, DIR_E, DIR_E, 3))
        assertEquals(160 + 48, spreadThresholdFor(TERRAIN_FUEL, DIR_E, DIR_E, 3))
        assertEquals(160, spreadThresholdFor(TERRAIN_FUEL, DIR_N, DIR_E, 3), "off-axis gets no bonus")
        // The cap clause is exercised with an out-of-range force on purpose; a
        // mission descriptor rejects windForce > 3 (§7.2), the table itself caps.
        assertEquals(WIND_THRESHOLD_CAP, spreadThresholdFor(TERRAIN_FUEL, DIR_E, DIR_E, 6), "capped at 255")
    }

    @Test
    fun windMovesIgnitionsDownwind() {
        // Same burning core and same seeds, wind force 3 east vs west: the east
        // side must take more fire than the west side across the whole sweep.
        var eastSide = 0
        var westSide = 0
        for (seed in 1..20) {
            val w = burning(
                fire = fireRows(listOf(Triple(10, 10, '4'))),
                terrain = editRows(
                    openTerrainRows(2, 2),
                    List(9) { Triple(6 + it, 6, GLYPH_FUEL) } + List(9) { Triple(6 + it, 14, GLYPH_FUEL) },
                ),
                seed = seed,
                windDir = DIR_E,
                windForce = 3,
            )
            repeat(100) { w.tickOnce() }
            for (y in 0 until GRID) for (x in 0 until GRID) {
                if (w.fireAt(x, y) <= 0 || (x == 10 && y == 10)) continue
                if (x > 10) eastSide++ else if (x < 10) westSide++
            }
        }
        assertTrue(eastSide > westSide, "wind force 3 east must burn more on the east side ($eastSide vs $westSide)")
    }

    @Test
    fun wallsRefillAndBoundaryNeverIgnite() {
        val terrain = editRows(
            openTerrainRows(),
            listOf(
                Triple(9, 10, GLYPH_WALL), Triple(11, 10, GLYPH_WALL),
                Triple(10, 9, GLYPH_REFILL), Triple(10, 11, GLYPH_WALL),
                Triple(0, 10, GLYPH_WALL),       // boundary neighbour is off-grid, not a cell
            ),
        )
        val w = burning(fire = fireRows(listOf(Triple(10, 10, '4'))), terrain = terrain, seed = 3)
        repeat(100) { w.tickOnce() }
        assertEquals(0, w.fireAt(9, 10), "a wall never ignites")
        assertEquals(0, w.fireAt(11, 10), "a wall never ignites")
        assertEquals(0, w.fireAt(10, 9), "refill is non-burnable")
        assertEquals(0, w.fireAt(10, 11), "a wall never ignites")
        assertTrue(w.fireCellsBurning() >= 1)
    }

    // ---- helpers ----
    private fun assertContentEquals(a: FirelineWorld, b: FirelineWorld) {
        assertEquals(a.tick, b.tick)
        assertEquals(a.fireRowMajor().toList(), b.fireRowMajor().toList())
        assertEquals(a.terrainRowMajor().toList(), b.terrainRowMajor().toList())
        assertEquals(a.rover.x, b.rover.x)
        assertEquals(a.rover.y, b.rover.y)
        assertEquals(a.rover.hp, b.rover.hp)
        assertEquals(a.civilians.map { it.status to it.hp }, b.civilians.map { it.status to it.hp })
        assertEquals(a.packetsSnapshot().map { it.toString() }, b.packetsSnapshot().map { it.toString() })
    }
}
