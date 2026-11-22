package robofight.fireline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import robofight.isa.decPad

/**
 * §8.3 step 10 + FIRELINE-DESIGN.md §9/§13.2 acceptance tests for the six
 * authored free missions: validation contract, deterministic reference firmware,
 * documented failing firmware, and the §13.2 determinism battery per mission.
 */
class Step10AuthoredMissionsTest {

    private fun referenceOf(m: Mission) = FirelineRun.runWithGuard(m, m.descriptorForSource(m.referenceSource))

    @Test
    fun allSixMissionsExistWithImmutableIdsAndBriefings() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), Missions.ALL.map { it.id })
        assertEquals(
            listOf("NOZZLE CHECK", "CORRIDOR", "FIRST RESPONSE", "DOWNWIND", "HOT ZONE", "THIN WALL"),
            Missions.ALL.map { it.name },
        )
        for (m in Missions.ALL) {
            assertTrue(m.briefing.isNotBlank(), "mission $m has no briefing")
            assertEquals(emptyList<String>(), m.validate(), "mission $m is invalid")
            assertNotNull(Missions.byId(m.id))
            assertTrue(m.maxTicks > 0, "every authored mission needs a headless guard (§9)")
        }
    }

    @Test
    fun referenceFirmwareSucceedsDeterministicallyOnEveryMission() {
        for (m in Missions.ALL) {
            val a = referenceOf(m)
            val b = referenceOf(m)
            assertEquals(RunStatus.SUCCESS, a.status, "${m.name}: ${a.render()}")
            assertEquals(null, a.failureReason)
            assertEquals(a, b, "${m.name} is not deterministic")
            assertEquals(a.finalStateHash, b.finalStateHash)
            assertTrue(a.tick <= m.maxTicks, "${m.name} ran ${a.tick} ticks > guard ${m.maxTicks}")
        }
    }

    @Test
    fun headlessRunnerAndStepByStepApiAgreeOnEveryMission() {
        for (m in Missions.ALL) {
            val descriptor = m.descriptorForSource(m.referenceSource)
            assertEquals(
                FirelineRun.runWithGuard(m, descriptor),
                FirelineRun.runStepByStep(descriptor, m.maxTicks),
                "${m.name}: runner vs world API",
            )
        }
    }

    @Test
    fun documentedFailingFirmwareFailsForTheDocumentedReason() {
        for (m in Missions.ALL) {
            assertTrue(m.failingSources.isNotEmpty(), "${m.name} needs a documented failing variant (§9)")
            for ((label, source, expected) in m.failingSources) {
                val result = FirelineRun.runWithGuard(m, m.descriptorForSource(source))
                assertEquals(RunStatus.FAILURE, result.status, "$label unexpectedly succeeded")
                assertEquals(expected, result.failureReason, "$label failed for the wrong reason: ${result.render()}")
            }
        }
    }

    // ---- §9 per-mission teaching contract ----
    @Test
    fun nozzleCheckTeachesFireSensorsWithoutCiviliansOrWind() {
        val m = Missions.NOZZLE_CHECK
        assertEquals(Objectives.EXTINGUISH_ALL, m.objectives)
        assertEquals(100, m.startingWater, "full water")
        assertEquals(0, m.windForce)
        assertEquals(0, m.layout.civilians.size, "no civilians")
        assertEquals(1, m.layout.fire.count { it > 0 }, "one stationary fire")
        val w = FirelineWorld(m.descriptorForSource(m.referenceSource))
        w.runUntilFinished(m.maxTicks)
        assertEquals(0, w.fireCellsBurning())
        assertTrue(w.rover.shots >= 2, "the reference solution shoots")
        assertTrue(w.rover.heat <= 80, "a nozzle check must not bake the rover: heat ${w.rover.heat}")
    }

    @Test
    fun corridorForcesTurningAndBlocksNaiveForwardMovement() {
        val m = Missions.CORRIDOR
        assertTrue(Objectives.has(m.objectives, Objectives.EXTINGUISH_ALL))
        assertTrue(Objectives.has(m.objectives, Objectives.REACH_EXIT))
        assertTrue(m.layout.terrain.count { it == TERRAIN_WALL } > 20, "walls shape the corridor")
        assertTrue(m.layout.exitCells().size == 1)
        assertEquals(0, m.windForce, "isolated fires, no wind")
        val naive = FirelineWorld(m.descriptorForSource("loop:  MOVE\n       JMP    loop"))
        naive.runUntilFinished(m.maxTicks)
        assertEquals(2, naive.rover.x, "the wall at (3,2) stops the always-forward bot where it started")
        assertEquals(2, naive.rover.y)
        val reference = referenceOf(m)
        assertTrue(reference.tick > 20, "the corridor route is a real walk, not a straight line")
    }

    @Test
    fun firstResponseRescuesTwoCiviliansByContactAndReachesTheExit() {
        val m = Missions.FIRST_RESPONSE
        assertEquals(Objectives.RESCUE_ALL or Objectives.REACH_EXIT, m.objectives)
        assertEquals(2, m.layout.civilians.size)
        assertTrue(m.timeLimit!! >= 100, "generous time")
        val w = FirelineWorld(m.descriptorForSource(m.referenceSource))
        w.runUntilFinished(m.maxTicks)
        assertEquals(2, w.civiliansRescued(), "contact rescue is automatic")
        assertEquals(0, w.civiliansLost())
        assertEquals(0, w.civiliansWaiting())
        assertTrue(w.roverOnExit())
        assertEquals(0, w.rover.shots, "this mission does not need the nozzle")
    }

    @Test
    fun downwindUsesWindSensorsAndFuelTiles() {
        val m = Missions.DOWNWIND
        assertEquals(Objectives.RESCUE_ALL or Objectives.EXTINGUISH_ALL or Objectives.REACH_EXIT, m.objectives)
        assertTrue(m.windForce >= 2, "wind force 2 or 3 (§9.4)")
        assertEquals(DIR_E, m.windDir)
        assertTrue(m.layout.terrain.count { it == TERRAIN_FUEL } >= 4, "fuel tiles make prioritization visible")
        val descriptor = m.descriptorForSource(m.referenceSource)
        val w = FirelineWorld(descriptor)
        w.runUntilFinished(m.maxTicks)
        assertEquals(RunStatus.SUCCESS, w.status, w.render())
        assertEquals(0, w.fireCellsBurning(), "the burning line is out")
        assertEquals(1, w.civiliansRescued())
        // The spread the reference solution had to handle came from the wind axis:
        // the same layout at wind force 3 must produce a DIFFERENT spread history,
        // which is what makes the wind sensors worth reading.
        val windier = Mission(
            id = m.id, name = m.name, briefing = m.briefing, layout = m.layout, seed = m.seed,
            objectives = m.objectives, timeLimit = m.timeLimit, surviveUntil = m.surviveUntil,
            startingWater = m.startingWater, windDir = m.windDir, windForce = 3,
            referenceSource = m.referenceSource, maxTicks = m.maxTicks,
        )
        val other = referenceOf(windier)
        assertEquals(other.civiliansRescued, 1)
        assertTrue(other.finalStateHash != referenceOf(m).finalStateHash,
            "wind force 2 vs 3 must not produce the same final fire state")
    }

    @Test
    fun hotZoneRequiresShieldingAndCarriesRefillAndAssetRules() {
        val m = Missions.HOT_ZONE
        assertEquals(Objectives.RESCUE_ALL or Objectives.PROTECT_ASSETS or Objectives.REACH_EXIT, m.objectives)
        assertTrue(m.startingWater < 100, "starting water below 100 (§9.5)")
        assertTrue(m.layout.terrain.count { it == TERRAIN_REFILL } >= 1, "refill station")
        assertTrue(m.layout.protectedCells().size >= 1, "protected asset")
        val reference = referenceOf(m)
        assertEquals(RunStatus.SUCCESS, reference.status, reference.render())
        assertTrue(reference.roverHp < 100, "crossing hot fire is not free")
        val w = FirelineWorld(m.descriptorForSource(m.referenceSource))
        w.runUntilFinished(m.maxTicks)
        assertTrue(w.rover.shieldsRaised >= 3, "the reference solution uses the thermal shield")
        assertEquals(1, w.assetsIntact(), "the protected asset survived")
        assertTrue(w.rover.water > 0, "the refill pocket refilled the tank")

        val unshielded = FirelineWorld(m.descriptorForSource("loop:  MOVE\n       JMP    loop"))
        unshielded.runUntilFinished(m.maxTicks)
        assertEquals(RunStatus.FAILURE, unshielded.status)
        assertEquals(FailureReason.ROVER_DESTROYED, unshielded.failureReason,
            "without the shield the corridor is lethal, which is the lesson")
    }

    /**
     * Mission 06 (FIRELINE-DESIGN.md §9.6, PLAN-EDGE-WALLS.md §13): the two facts
     * a thin wall teaches. (1) It is not a cell: the cell behind it stays free
     * terrain and a straight MOVE across the walled edge simply does not happen.
     * (2) It gates water but not fire: every packet the documented failing variant
     * fires at the lane is absorbed at the edge, while the fire crosses the very
     * same edge and kills the rover standing behind it.
     */
    @Test
    fun thinWallGatesMovementAndWaterButNotFire() {
        val m = Missions.THIN_WALL
        assertEquals(6, m.id)
        assertEquals(Objectives.EXTINGUISH_ALL or Objectives.REACH_EXIT, m.objectives)
        assertEquals(120, m.timeLimit, "same limit as mission 02: the route is a short walk")
        assertEquals(0, m.windForce, "the crossing here is the wall's, not the wind's")
        assertEquals(0, m.layout.terrain.count { it == TERRAIN_WALL }, "the mission carries no wall cells at all")
        assertEquals(3, m.layout.edgeCount(), "the three edges the E2 lane fixtures carry (parity_matrix.tsv wall-lane-move)")
        assertTrue(m.layout.wallBetween(5, 2, 6, 2), "the lane gate is the edge between (5,2) and (6,2)")
        assertFalse(m.layout.wallBetween(5, 2, 5, 3), "the bypass south of the lane is open")
        assertEquals(TERRAIN_FLOOR, m.layout.terrainAt(6, 2), "the cell behind the thin wall stays free terrain")
        assertTrue(terrainPassable(m.layout.terrainAt(6, 2)), "a thin wall never makes a cell non-free (DW1/§3)")
        assertEquals(1, m.layout.fire.count { it > 0 }, "one burning lane cell beyond the gate")

        val naive = FirelineWorld(
            m.descriptorForSource("       MOVE\n       MOVE\n       MOVE\n       MOVE\n       WAIT\n       JMP    fin\nfin:   WAIT\n       JMP    fin"),
        )
        naive.runUntilFinished(m.maxTicks)
        assertEquals(5, naive.rover.x, "the always-forward bot stops on the west side of the gate")
        assertEquals(2, naive.rover.y)
        assertEquals(3, naive.rover.distanceMoved, "the blocked MOVE pays its ticks without stepping")

        val failing = FirelineWorld(m.descriptorForSource(m.failingSources[0].second))
        var blockedShots = 0
        var crossedEdge = false
        failing.onTick = { w ->
            for (e in w.lastEvents) {
                if (e.startsWith("PACKET BLOCKED EDGE")) blockedShots++
                if (e.contains("IGNITES 5,2") && e.contains("from 6,2")) crossedEdge = true
            }
        }
        failing.runUntilFinished(m.maxTicks)
        assertTrue(blockedShots >= 3, "packets fired straight at a thin wall are absorbed at the edge ($blockedShots)")
        assertTrue(crossedEdge, "fire spread from (6,2) westward across the walled edge into (5,2) (DW1)")
        assertEquals(FailureReason.ROVER_DESTROYED, failing.failureReason,
            "the fire crossed the wall the water could not cross, which is the lesson")

        val reference = referenceOf(m)
        assertEquals(RunStatus.SUCCESS, reference.status, reference.render())
        assertEquals(0, reference.burningCells, "both lane cells, one on each side of the wall, are drowned")
        assertEquals(0, reference.fireIntensitySum)
        assertTrue(reference.tick <= m.timeLimit!!, "reference ${reference.tick} ticks inside the limit ${m.timeLimit}")
        assertTrue(reference.firmwareBytes in 75..111, "firmware size in the range of missions 01-05: ${reference.firmwareBytes}")
        assertTrue(reference.firmwareInstructions in 25..37, "instruction count in the range of missions 01-05: ${reference.firmwareInstructions}")
    }

    // ---- §13.2 point 5 for authored content ----
    @Test
    fun authoredMissionsTerminateUnderTheirGuardAcrossASeedSweep() {
        for (m in Missions.ALL) {
            val results = FirelineRun.sweep(m, seeds = listOf(m.seed, m.seed + 1, m.seed + 7, m.seed + 101, m.seed + 999))
            assertEquals(5, results.size)
            for (r in results) {
                assertTrue(r.tick <= m.maxTicks, "${m.name} seed sweep exceeded the guard: ${r.tick}")
                assertNotNull(r.failureReason ?: RunStatus.SUCCESS)
            }
        }
    }

    @Test
    fun missionCodesAndResultsRenderStably() {
        val results = Missions.referenceResults()
        assertEquals(6, results.size, "one reference result per authored mission (Missions.ALL)")
        for ((m, r) in Missions.ALL.zip(results)) {
            val text = FirelineRun.report(r)
            assertTrue(text.contains("mission " + decPad(m.id, 2)), text)
            assertTrue(text.contains("hash: "), text)
            assertTrue(text.contains("SUCCESS"), "${m.name}: $text")
        }
    }
}
