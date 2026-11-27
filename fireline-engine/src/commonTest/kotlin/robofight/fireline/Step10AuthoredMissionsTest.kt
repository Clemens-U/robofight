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
            listOf("NOZZLE CHECK", "CORRIDOR", "FIRST RESPONSE", "DOWNWIND", "HOT ZONE", "GO AROUND"),
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
        assertTrue(m.layout.terrain.count { it == TERRAIN_WALL } == 1, "main's corridor keeps one solid obstacle at (3,2)")
        assertEquals(25, m.layout.edgeCount(), "main's corridor is shaped by 25 thin edges (row-2 line with a gate at x=2, plus the pocket walls)")
        assertTrue(m.layout.wallBetween(2, 2, 2, 3).not(), "the drop through the gate at x=2 is open")
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
     * Mission 06 (FIRELINE-DESIGN.md §9.6, PLAN-EDGE-WALLS.md §13, resolved by
     * PLAN-RECONCILE-MAIN-01.md §8): the facts a thin wall teaches. (1) It is not
     * a cell: the cell behind it stays free terrain and a straight MOVE across
     * the walled edge simply does not happen. (2) Under the merged rules it gates
     * water AND fire: every packet the documented failing variant fires at the
     * wall column is refused at the edge, and the pocket fire never crosses it —
     * so the spray-only bot survives behind the wall and simply runs out of
     * time (TIME_EXPIRED at the 90-tick limit). The reference variant walks the
     * open rows below the wall's lower end and drowns the pocket cell from the
     * refill firing stand (R4 capstone redesign, GO AROUND; measured anchors
     * from the scratch twin MeasureR5: reference SUCCESS at tick 62, failing
     * TIME_EXPIRED at tick 90).
     */
    @Test
    fun thinWallGatesMovementWaterAndFire() {
        val m = Missions.GO_AROUND
        assertEquals(6, m.id)
        assertEquals(Objectives.EXTINGUISH_ALL or Objectives.REACH_EXIT, m.objectives)
        assertEquals(90, m.timeLimit, "headroom for the detour, tight enough that the spray-only bot runs out of time")
        assertEquals(0, m.windForce, "the crossing here is the wall's, not the wind's")
        assertEquals(3, m.layout.terrain.count { it == TERRAIN_WALL }, "three terrain wall cells seal the pocket shell")
        assertEquals(13, m.layout.edgeCount(), "one vertical wall run at x=9, rows 0..12")
        assertTrue(m.layout.wallBetween(9, 10, 10, 10), "the wall gates row 10 between (9,10) and (10,10)")
        assertFalse(m.layout.wallBetween(9, 13, 10, 13), "the bypass south of the wall's lower end is open")
        assertEquals(TERRAIN_FLOOR, m.layout.terrainAt(10, 10), "the cell behind the thin wall stays free terrain")
        assertTrue(terrainPassable(m.layout.terrainAt(10, 10)), "a thin wall never makes a cell non-free (DW1/§3)")
        assertEquals(1, m.layout.fire.count { it > 0 }, "one burning pocket cell beyond the wall")
        assertEquals(50, m.layout.fireAt(10, 13), "the pocket cell is authored in the 26..50 band so 2..4 packets drown it")

        val naive = FirelineWorld(
            m.descriptorForSource("       MOVE\n       MOVE\n       MOVE\n       MOVE\n       MOVE\n       MOVE\n       MOVE\n       MOVE\n       WAIT\n       JMP    fin\nfin:   WAIT\n       JMP    fin"),
        )
        naive.runUntilFinished(m.maxTicks)
        assertEquals(9, naive.rover.x, "the always-forward bot stops on the west side of the wall")
        assertEquals(10, naive.rover.y)
        assertEquals(7, naive.rover.distanceMoved, "the blocked MOVE pays its ticks without stepping")

        val failing = FirelineWorld(m.descriptorForSource(m.failingSources[0].second))
        var blockedShots = 0
        var crossedEdge = false
        val ignitePattern = Regex("IGNITES (\\d+),(\\d+).*from (\\d+),(\\d+)")
        failing.onTick = { w ->
            for (e in w.lastEvents) {
                if (e.startsWith("PACKET BLOCKED EDGE")) blockedShots++
                val ign = ignitePattern.find(e)
                if (ign != null) {
                    val (tx, ty, fx, fy) = ign.destructured
                    if (m.layout.wallBetween(fx.toInt(), fy.toInt(), tx.toInt(), ty.toInt())) crossedEdge = true
                }
            }
        }
        failing.runUntilFinished(m.maxTicks)
        assertTrue(blockedShots >= 3, "packets fired straight at a thin wall are refused at the edge (measured: $blockedShots)")
        assertFalse(crossedEdge, "the merged rule (PLAN-RECONCILE-MAIN-01.md §8) blocks fire spread across a walled edge: the pocket fire never crosses x=9")
        assertEquals(FailureReason.TIME_EXPIRED, failing.failureReason,
            "the fire stays sealed in the pocket, the rover survives behind the wall, and the spray-only bot simply runs out of time (measured: hp ${failing.rover.hp} at tick ${failing.tick})")
        assertTrue(failing.rover.hp > 0, "the wall that stops the fire also keeps the rover alive")
        assertEquals(90, failing.tick, "control anchor 6-failing-t90 tick")
        assertEquals("ba00d941d5cfce57", failing.finalStateHash(), "measured control anchor (MeasureR5): 6-failing final hash at tick 90")

        val reference = referenceOf(m)
        assertEquals(RunStatus.SUCCESS, reference.status, reference.render())
        assertEquals(0, reference.burningCells, "the pocket cell beyond the wall is drowned from the refill firing stand")
        assertEquals(0, reference.fireIntensitySum)
        assertEquals(62, reference.tick, "reference anchor 6-reference-final tick (MeasureR5), inside the 90-tick limit")
        assertEquals("78ab6090ddbe444a", reference.finalStateHash, "reference anchor 6-reference final hash (MeasureR5)")
        assertTrue(reference.tick <= m.timeLimit!!, "reference ${reference.tick} ticks inside the limit ${m.timeLimit}")
        assertTrue(reference.firmwareBytes in 75..111, "firmware size in the range of missions 01-05: ${reference.firmwareBytes}")
        assertTrue(reference.firmwareInstructions in 25..37, "instruction count in the range of missions 01-05: ${reference.firmwareInstructions}")

        val refWorld = FirelineWorld(m.descriptorForSource(m.referenceSource))
        var refIgnites = 0
        var refCrossedEdge = 0
        var refExitIgnites = 0
        refWorld.onTick = { w ->
            for (e in w.lastEvents) {
                val ign = ignitePattern.find(e)
                if (ign != null) {
                    refIgnites++
                    val (tx, ty, fx, fy) = ign.destructured
                    if (m.layout.wallBetween(fx.toInt(), fy.toInt(), tx.toInt(), ty.toInt())) refCrossedEdge++
                    if (m.layout.terrainAt(tx.toInt(), ty.toInt()) == TERRAIN_EXIT) refExitIgnites++
                }
            }
        }
        refWorld.runUntilFinished(m.maxTicks)
        assertEquals(0, refIgnites, "the reference run sees no ignition at all: the sealed pocket cannot crawl anywhere")
        assertEquals(0, refCrossedEdge, "no ignition ever lands on a cell whose shared edge with a burning cell is a mission thin wall (measured: 0)")
        assertEquals(0, refExitIgnites, "the exit cell never ignites during the reference run")
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
