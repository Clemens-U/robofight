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
     * PLAN-RECONCILE-MAIN-01.md §8): the facts a thin wall teaches, on this
     * level's own geometry. (1) It is not a cell: the cell behind it stays free
     * terrain and a straight MOVE across the walled edge simply does not happen.
     * (2) It gates water: every packet the documented failing variant fires at
     * the wall column is refused at the edge (measured: 9 refusals). (3) It gates
     * fire, and that half is a measured comparison instead of a seal nothing can
     * challenge: the burning pocket cell (10,10) sits ON a walled row with open
     * passable floor (9,10) directly west of it, the seed-6027 stream aims W at
     * (9,10) on tick 15 with chance 37 (below the 64 floor threshold), the walled
     * run records 0 cross-wall ignitions, and the same layout with its 13 wall
     * segments removed ignites (9,10) on tick 15, crosses back into the pocket on
     * tick 55 and burns 8 cells - which turns the reference firmware from SUCCESS
     * at tick 49 into TIME_EXPIRED at tick 90 and the spray bot from hp 92 into
     * hp 0 (ROVER_DESTROYED at tick 59). Anchors measured with the scratch twin
     * MeasureR6 (GO AROUND capstone, R4 layout + R5 fire measurement).
     */
    @Test
    fun thinWallGatesMovementWaterAndFire() {
        val m = Missions.GO_AROUND
        assertEquals(6, m.id)
        assertEquals(Objectives.EXTINGUISH_ALL or Objectives.REACH_EXIT, m.objectives)
        assertEquals(90, m.timeLimit, "headroom for the detour, tight enough that the spray-only bot runs out of time")
        assertEquals(0, m.windForce, "the crossing here is the wall's, not the wind's")
        assertEquals(2, m.layout.terrain.count { it == TERRAIN_WALL }, "two terrain wall cells seal the pocket shell north and east of the fire")
        assertEquals(13, m.layout.edgeCount(), "one vertical wall run at x=9, rows 0..12")
        assertTrue(m.layout.wallBetween(9, 10, 10, 10), "the wall gates row 10 between (9,10) and (10,10)")
        assertFalse(m.layout.wallBetween(9, 13, 10, 13), "the bypass south of the wall's lower end is open")
        assertEquals(TERRAIN_FLOOR, m.layout.terrainAt(10, 10), "the cell behind the thin wall stays free terrain")
        assertTrue(terrainPassable(m.layout.terrainAt(10, 10)), "a thin wall never makes a cell non-free (DW1/§3)")
        assertEquals(1, m.layout.fire.count { it > 0 }, "one burning pocket cell beyond the wall")
        assertEquals(50, m.layout.fireAt(10, 10), "the pocket cell is authored in the 26..50 band so 3..4 packets drown it")
        // The fire half is load bearing only because the burning cell sits ON a
        // walled row: its western neighbour is open passable floor across this
        // mission's own thin wall, and the other three faces never ignite.
        assertTrue(m.layout.wallBetween(9, 10, 10, 10), "the burning cell (10,10) shares its west edge with a thin wall")
        assertEquals(TERRAIN_FLOOR, m.layout.terrainAt(9, 10), "the burning cell's western neighbour (9,10) is open passable floor")
        assertTrue(terrainPassable(m.layout.terrainAt(9, 10)), "fire could walk into the west half of the field if the thin wall were not there")
        assertEquals(TERRAIN_WALL, m.layout.terrainAt(10, 9), "the pocket is sealed north by a terrain wall cell")
        assertEquals(TERRAIN_WALL, m.layout.terrainAt(11, 10), "the pocket is sealed east by a terrain wall cell between the fire and the exit")
        assertEquals(TERRAIN_REFILL, m.layout.terrainAt(10, 11), "the pocket's south face is the refill firing stand: refill cells never ignite")
        assertEquals(TERRAIN_EXIT, m.layout.terrainAt(12, 10), "the exit glyph stays east of the burning cell inside the pocket")
        assertEquals(8, (2..9).count { m.layout.terrainAt(it, 13) == TERRAIN_REFILL }, "the west refill lane is the 8 cells (2..9,13) on the open row 13 below the wall")
        assertEquals(9, m.layout.terrain.count { it == TERRAIN_REFILL }, "measured: 9 refill cells in total, the lane plus the firing stand (10,11)")

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
        val blockedLines = ArrayList<String>()
        val ignitePattern = Regex("IGNITES (\\d+),(\\d+).*from (\\d+),(\\d+)")
        failing.onTick = { w ->
            for (e in w.lastEvents) {
                if (e.startsWith("PACKET BLOCKED EDGE")) {
                    blockedShots++
                    blockedLines.add("t${w.tick} $e")
                }
                val ign = ignitePattern.find(e)
                if (ign != null) {
                    val (tx, ty, fx, fy) = ign.destructured
                    if (m.layout.wallBetween(fx.toInt(), fy.toInt(), tx.toInt(), ty.toInt())) crossedEdge = true
                }
            }
        }
        failing.runUntilFinished(m.maxTicks)
        assertTrue(blockedShots >= 3, "packets fired straight at a thin wall are refused at the edge (measured: $blockedShots)")
        assertEquals(9, blockedShots, "measured control anchor: 9 PACKET BLOCKED EDGE refusals from (9,10) toward 10,10")
        assertEquals(
            listOf("t17 PACKET BLOCKED EDGE 10,10", "t21 PACKET BLOCKED EDGE 10,10"),
            blockedLines.take(2),
            "two exact control event lines, measured at ticks 17 and 21",
        )
        assertFalse(crossedEdge, "the merged rule (PLAN-RECONCILE-MAIN-01.md §8) blocks fire spread across a walled edge: the pocket fire never crosses x=9 in this run")
        assertEquals(FailureReason.TIME_EXPIRED, failing.failureReason,
            "the fire stays behind the wall, the rover survives behind it, and the spray-only bot simply runs out of time (measured: hp ${failing.rover.hp} at tick ${failing.tick})")
        assertTrue(failing.rover.hp > 0, "the wall that stops the fire also keeps the rover alive (measured hp 92; without the wall the same bot dies at tick 59)")
        assertEquals(90, failing.tick, "control anchor 6-failing-t90 tick")
        assertEquals("55d1dabb5ff672d3", failing.finalStateHash(), "engine v4 re-record of the control anchor 6-failing final hash at tick 90 (was a01a2344948c78b5 under engine v2; parity_matrix 6-failing)")

        val reference = referenceOf(m)
        assertEquals(RunStatus.SUCCESS, reference.status, reference.render())
        assertEquals(0, reference.burningCells, "the pocket cell beyond the wall is drowned from the refill firing stand")
        assertEquals(0, reference.fireIntensitySum)
        assertEquals(49, reference.tick, "reference anchor 6-reference-final tick (MeasureR6), inside the 90-tick limit")
        assertEquals("3a958e3503fe0741", reference.finalStateHash, "engine v4 re-record of the reference anchor 6-reference final hash (was 1b8b91c5bd8ff47f under engine v2; parity_matrix 6-reference)")
        assertTrue(reference.tick <= m.timeLimit!!, "reference ${reference.tick} ticks inside the limit ${m.timeLimit}")
        assertTrue(m.timeLimit!! - reference.tick >= 20, "measured headroom: ${m.timeLimit!! - reference.tick} ticks between the reference finish and the limit")
        assertTrue(reference.firmwareBytes in 75..111, "firmware size in the range of missions 01-05: ${reference.firmwareBytes}")
        assertTrue(reference.firmwareInstructions in 25..37, "instruction count in the range of missions 01-05: ${reference.firmwareInstructions}")
        val refProbe = FirelineWorld(m.descriptorForSource(m.referenceSource))
        repeat(12) { refProbe.tickOnce() }
        assertEquals("108606110907a1bd", refProbe.finalStateHash(), "engine v4 re-record of the reference anchor 6-reference probe hash at tick 12 (was 085d4da41e356ccf under engine v2; parity_matrix 6-reference probe)")

        val refWorld = FirelineWorld(m.descriptorForSource(m.referenceSource))
        var refIgnites = 0
        var refCrossedEdge = 0
        var refExitIgnites = 0
        var refPackets = 0
        var refArrivalTick = -1
        var refMaxHeat = 0
        refWorld.onTick = { w ->
            if (refArrivalTick < 0 && w.rover.x == 10 && w.rover.y == 11) refArrivalTick = w.tick
            if (w.rover.heat > refMaxHeat) refMaxHeat = w.rover.heat
            for (e in w.lastEvents) {
                if (e.startsWith("FIRE -")) refPackets++
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
        assertEquals(29, refArrivalTick, "measured: the rover reaches its firing stand (10,11) on tick 29, after walking around the wall's lower end")
        assertEquals(4, refPackets, "measured: 4 FIRE packets drown the pocket cell (limit 5)")
        assertTrue(refPackets <= 5, "the reference solution stays within the 5-packet budget (measured: $refPackets)")
        assertTrue(refWorld.rover.hp > 0, "measured: the reference rover finishes at hp ${refWorld.rover.hp}")
        assertTrue(refMaxHeat + HEAT_SHOOT <= HEAT_MAX, "no SHOOT of the reference run is heat-locked (measured heat peak $refMaxHeat)")
        assertEquals(0, refIgnites, "the walled reference run sees no ignition at all: the pocket's only open face is the walled one")
        assertEquals(0, refCrossedEdge, "cross-wall ignition count in the walled run: no ignition ever lands on a cell whose shared edge with a burning cell is a mission thin wall")
        assertEquals(0, refExitIgnites, "the exit cell never ignites during the reference run")

        /**
         * The edge-open counterfactual: the same layout (same terrain, same fire,
         * same seed, same reference firmware) with this mission's 13 thin wall
         * segments removed. This is what makes the fire half load bearing - the
         * zero above is challenged on the level's own geometry instead of being
         * guaranteed by a pocket that shares no walled edge with anything.
         */
        val openMission = Mission(
            id = m.id, name = m.name, briefing = m.briefing,
            layout = Layout(
                m.layout.terrain.copyOf(), m.layout.fire.copyOf(),
                m.layout.roverX, m.layout.roverY, m.layout.roverDir, m.layout.civilians,
            ),
            seed = m.seed, objectives = m.objectives, timeLimit = m.timeLimit,
            startingWater = m.startingWater, windDir = m.windDir, windForce = m.windForce,
            referenceSource = m.referenceSource, failingSources = m.failingSources, maxTicks = m.maxTicks,
        )
        assertEquals(0, openMission.layout.edgeCount(), "the counterfactual is the same layout with all 13 thin wall segments removed")
        assertEquals(emptyList<String>(), openMission.validate(), "the wall-free layout still validates: the wall, not geometry, is what separates the fire from the west half")
        val openWorld = FirelineWorld(openMission.descriptorForSource(openMission.referenceSource))
        val openIgnites = ArrayList<String>()
        val openCrossColumn = ArrayList<String>()
        val openWestIgnites = ArrayList<String>()
        var openExitIgnites = 0
        openWorld.onTick = { w ->
            for (e in w.lastEvents) {
                val ign = ignitePattern.find(e)
                if (ign != null) {
                    val (tx, ty, fx, fy) = ign.destructured
                    val line = "t${w.tick} $e"
                    openIgnites.add(line)
                    // West/east crossing of the former wall column x = 9 (the
                    // border between (9,y) and (10,y) in the mission layout).
                    if ((fx.toInt() >= 10 && tx.toInt() <= 9) || (fx.toInt() <= 9 && tx.toInt() >= 10)) openCrossColumn.add(line)
                    if (tx.toInt() <= 8) openWestIgnites.add(line)
                    if (w.layout.terrainAt(tx.toInt(), ty.toInt()) == TERRAIN_EXIT) openExitIgnites++
                }
            }
        }
        openWorld.runUntilFinished(m.maxTicks)
        assertTrue(openCrossColumn.isNotEmpty(), "without the wall the spread stream crosses the former wall column (measured: ${openCrossColumn.size} crossings)")
        assertEquals(
            listOf(
                "t15 FIRE IGNITES 9,10 floor from 10,10 dir=W chance=37<64",
                "t55 FIRE IGNITES 10,10 floor from 9,10 dir=E chance=31<64",
            ),
            openCrossColumn,
            "exact cross-column IGNITES lines of the wall-free run: the tick-15 west draw the thin wall refuses, and the tick-55 re-entry into the pocket",
        )
        assertEquals(listOf(
            "t40 FIRE IGNITES 8,10 floor from 9,10 dir=W chance=15<64",
            "t65 FIRE IGNITES 8,9 floor from 8,10 dir=N chance=27<64",
            "t80 FIRE IGNITES 8,11 floor from 8,10 dir=S chance=18<64",
            "t85 FIRE IGNITES 8,12 floor from 8,11 dir=S chance=37<64",
        ), openWestIgnites, "measured ignites west of the wall column: the fire walks into the rover's west half down to (8,12), directly north of the refill lane cell (8,13)")
        assertEquals(8, openIgnites.size, "measured: 8 ignites in the wall-free run against 0 in the walled run")
        assertEquals(0, openExitIgnites, "the exit cell never ignites in either run")
        assertEquals(FailureReason.TIME_EXPIRED, openWorld.failureReason, "the same reference firmware misses EXTINGUISH_ALL once the fire crosses the wall line")
        assertEquals(90, openWorld.tick, "measured wall-free reference finish: tick 90 against tick 49 behind the wall")
        assertEquals(8, openWorld.snapshot().burningCells, "measured: 8 cells still burn against 0 behind the wall")
        assertEquals(40, openWorld.snapshot().waterUsed, "the wall-free run spends the same 40 water on 4 packets and still cannot drown everything")
        assertEquals("1f60ddf4db958199", openWorld.finalStateHash(), "engine v4 re-record of the wall-free counterfactual final hash (was ec033d1efa5fddff under engine v2; scratch twin MeasureR8)")

        val openNaive = FirelineWorld(openMission.descriptorForSource(openMission.failingSources[0].second))
        openNaive.runUntilFinished(m.maxTicks)
        assertEquals(FailureReason.ROVER_DESTROYED, openNaive.failureReason, "the spray bot stands on (9,10): the thin wall is the only reason it survives")
        assertEquals(59, openNaive.tick, "measured wall-free spray-bot finish: ROVER_DESTROYED at tick 59")
        assertEquals(0, openNaive.rover.hp, "measured: hp 0 without the wall against hp 92 behind it")
        assertEquals("2e7446f859fd1abe", openNaive.finalStateHash(), "engine v4 re-record of the wall-free spray-bot final hash (was f01e72b53ef2ed3c under engine v2; scratch twin MeasureR8)")
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
