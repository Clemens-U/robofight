package robofight.fireline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * §8.3 step 9: results, failure reasons, deterministic outcome + final-state
 * hash, headless runner (FIRELINE-DESIGN.md §7.4/§7.5, §13.2, §13.4).
 */
class Step9ResultsDeterminismTest {

    /** Golden trace filled from the first recorded run (§13.2 point 4). */
private val GOLDEN_TRACE =
        "[001 SHOOT (1/3) | , 002 SHOOT (2/3) | , 003 SHOOT (3/3) | PACKET -> 3,2, " +
        "004 JMP 256 | , 005 SHOOT (1/3) | , 006 SHOOT (2/3) | FIRE -25 at 6,2 -> 35]"

    private val idle = "loop:  WAIT\n       JMP    loop"
    private val shootLoop = "loop:  SHOOT\n       JMP    loop"

    /** Small deterministic mission used as the determinism anchor. */
    private fun anchorDescriptor(seed: Int = 20261106) = descriptorOf(
        layoutOf(
            terrain = editRows(
                openTerrainRows(2, 2),
                listOf(Triple(6, 2, GLYPH_FUEL), Triple(6, 6, GLYPH_CIVILIAN), Triple(9, 9, GLYPH_EXIT)),
            ),
            fire = fireRows(listOf(Triple(6, 2, '2'), Triple(7, 3, '1'))),
        ),
        firmwareSource = shootLoop,
        seed = seed,
        objectives = Objectives.SURVIVE_UNTIL,
        surviveUntil = 10,
        windDir = DIR_E,
        windForce = 1,
    )

    // ---- §13.2 point 1: repeat runs ----
    @Test
    fun identicalDescriptorAndFirmwareGiveIdenticalResultsAndHashes() {
        val a = FirelineRun.run(anchorDescriptor())
        val b = FirelineRun.run(anchorDescriptor())
        assertEquals(a, b, "the whole result record must be identical")
        assertEquals(a.finalStateHash, b.finalStateHash)
        assertEquals(16, a.finalStateHash.length, "FNV-1a 64-bit rendered as 16 hex digits")
        assertTrue(a.finalStateHash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(RunStatus.SUCCESS, a.status, "SURVIVE_UNTIL 10 completes on tick 10")
        assertEquals(10, a.tick)
        assertNull(a.failureReason)
    }

    @Test
    fun runnerPathAndStepByStepWorldApiAgree() {
        val d = anchorDescriptor()
        assertEquals(FirelineRun.run(d), FirelineRun.runStepByStep(d), "§13.2 point 3")
        assertEquals(FirelineRun.run(d).finalStateHash, FirelineRun.runStepByStep(d).finalStateHash)
        assertTrue(FirelineRun.runsAreIdentical(d, repetitions = 4))
    }

    @Test
    fun observersAndRenderCallsDoNotPerturbTheRun() {
        val d = anchorDescriptor()
        val watched = FirelineWorld(d)
        var seen = 0
        // Only non-consuming telemetry: render + state sensors (a hook that drew
        // IN RAND would legitimately move the sensor stream, which §4.3 covers).
        watched.onTick = { w -> seen++; w.render(); w.sensor(FirelinePorts.HP); w.smokeAt(w.rover.x, w.rover.y) }
        watched.runUntilFinished()
        val plain = FirelineRun.run(d)
        assertEquals(plain, watched.snapshot(), "telemetry hooks must not change the simulation")
        assertTrue(seen > 0)
    }

    // ---- §13.2 hash input coverage ----
    @Test
    fun canonicalStateCoversEveryMandatedComponent() {
        val w = FirelineWorld(anchorDescriptor())
        repeat(7) { w.tickOnce() }
        val text = w.canonicalState()
        val lines = text.split('\n')
        assertEquals(
            listOf("engine", "tick", "rover", "terrain", "verticalEdges", "horizontalEdges", "fire", "civilians", "packets", "objective", "rng"),
            lines.filter { it.isNotEmpty() }.map { it.substringBefore('=') },
            "§13.2 fixes the components and their order",
        )
        assertEquals(400, lines[3].substringAfter('=').split(',').size, "terrain is row-major over 400 cells")
        assertEquals(380, lines[4].substringAfter('=').length)
        assertEquals(380, lines[5].substringAfter('=').length)
        assertTrue(lines[6].split(',').size == 400, "fire array is row-major over 400 cells")
        assertTrue(text.contains("rng=") && text.contains(","), "both RNG states are in the fingerprint")
        assertTrue(text.contains("engine=${FIRELINE_ENGINE_VERSION}"))
        // No Java identity hash codes anywhere (nothing looks like `Class@1a2b3c4d`).
        assertTrue(!Regex("@[0-9a-fA-F]{6,}").containsMatchIn(text), text)
    }

    @Test
    fun hashChangesWhenAnyMandatedComponentChanges() {
        val base = FirelineRun.run(anchorDescriptor())
        val otherSeed = FirelineRun.run(anchorDescriptor(seed = 7))
        val otherFirmware = FirelineRun.run(
            anchorDescriptor().let { d ->
                MissionDescriptor(
                    engineVersion = d.engineVersion, missionId = d.missionId, seed = d.seed,
                    objectives = d.objectives, timeLimit = d.timeLimit, surviveUntil = d.surviveUntil,
                    startingWater = d.startingWater, windDir = d.windDir, windForce = d.windForce,
                    firmware = codeOf(idle), layout = d.layout,
                )
            },
        )
        assertNotEquals(base.finalStateHash, otherSeed.finalStateHash, "spread/RNG state differs")
        assertNotEquals(base.finalStateHash, otherFirmware.finalStateHash, "rover state differs")
        val early = FirelineWorld(anchorDescriptor())
        repeat(5) { early.tickOnce() }
        val h5 = early.finalStateHash()
        early.tickOnce()
        assertNotEquals(h5, early.finalStateHash(), "tick alone changes the fingerprint")
    }

    // ---- §7.4/§7.5 outcome + failure reasons ----
    @Test
    fun hardTimeLimitReportsTimeExpired() {
        val d = descriptorOf(
            layoutOf(), firmwareSource = idle, seed = 5,
            objectives = Objectives.SURVIVE_UNTIL, surviveUntil = 500, timeLimit = 12,
        )
        val r = FirelineRun.run(d)
        assertEquals(RunStatus.FAILURE, r.status)
        assertEquals(FailureReason.TIME_EXPIRED, r.failureReason)
        assertEquals(12, r.tick)
    }

    @Test
    fun objectivesOnTheFinalAllowedTickAreASuccess() {
        val d = descriptorOf(
            layoutOf(terrain = editRows(openTerrainRows(2, 2), listOf(Triple(3, 2, GLYPH_EXIT)))),
            firmwareSource = "loop:  MOVE\n       JMP    loop",
            seed = 5, objectives = Objectives.REACH_EXIT, timeLimit = 2,
        )
        val r = FirelineRun.run(d)
        assertEquals(RunStatus.SUCCESS, r.status, "completion on the last allowed tick wins over TIME_EXPIRED (§7.4)")
        assertEquals(2, r.tick, "one MOVE completes onto the adjacent exit on tick 2")
    }

    @Test
    fun roverDestroyedRunReportsTheFullFinalState() {
        val d = descriptorOf(
            layoutOf(terrain = openTerrainRows(2, 2), fire = fireRows(listOf(Triple(2, 2, '4')))),
            firmwareSource = idle, seed = 5,
            objectives = Objectives.SURVIVE_UNTIL, surviveUntil = 1000,
        )
        val r = FirelineRun.run(d)
        assertEquals(RunStatus.FAILURE, r.status)
        assertEquals(FailureReason.ROVER_DESTROYED, r.failureReason)
        assertEquals(0, r.roverHp)
        assertEquals(25, r.tick, "100 intensity -> 4 HP per tick -> destroyed on tick 25")
        assertTrue(r.fireIntensitySum >= 100, "the authored cell plus whatever spread produced")
        assertTrue(r.burningCells >= 1)
        assertEquals(100, r.waterRemaining)
        assertEquals(0, r.waterUsed)
    }

    @Test
    fun midRunSnapshotIsMarkedUnfinishedRatherThanAnOutcome() {
        val w = FirelineWorld(anchorDescriptor())
        repeat(3) { w.tickOnce() }
        val mid = w.snapshot()
        assertEquals(RunStatus.FAILURE, mid.status)
        assertNull(mid.failureReason, "no §7.4 reason has fired yet")
        assertEquals(3, mid.tick)
    }

    // ---- §7.5 comparison ----
    @Test
    fun missionComparisonFollowsTheDocumentedOrder() {
        val success = FirelineRun.run(anchorDescriptor())
        val failure = FirelineRun.run(descriptorOf(
            layoutOf(), firmwareSource = idle, seed = 5,
            objectives = Objectives.SURVIVE_UNTIL, surviveUntil = 500, timeLimit = 9,
        ))
        assertTrue(success.compareToRank(failure) < 0, "success before failure")
        assertTrue(failure.compareToRank(success) > 0)

        val later = FirelineWorld(anchorDescriptor()).let { w ->
            w.runUntilFinished()
            // same run, one extra tick of state for the tie-breaker comparison
            FirelineResult(
                status = RunStatus.SUCCESS, failureReason = null, engineVersion = w.descriptor.engineVersion,
                missionId = w.descriptor.missionId, seed = w.descriptor.seed, tick = w.tick + 1,
                roverHp = w.rover.hp, roverHeat = w.rover.heat, waterRemaining = w.rover.water,
                waterUsed = w.rover.waterUsed, civiliansRescued = w.civiliansRescued(),
                civiliansWaiting = w.civiliansWaiting(), civiliansLost = w.civiliansLost(),
                burningCells = w.fireCellsBurning(), fireIntensitySum = w.fireIntensitySum(),
                protectedAssetsIntact = w.assetsIntact(), protectedAssetsLost = w.assetsLost(),
                firmwareBytes = 9, firmwareInstructions = 3,
                finalStateHash = "0000000000000000", lastEvents = emptyList(),
            )
        }
        assertTrue(success.compareToRank(later) < 0, "fewer ticks is better for successful runs")
        assertEquals(0, success.compareToRank(success.copy()), "identical records rank equal")
    }

    // ---- §13.2 point 4/5 ----
    @Test
    fun goldenEventTraceOfAShortRun() {
        val trace = FirelineRun.eventTrace(anchorDescriptor(), 6)
        assertEquals(GOLDEN_TRACE, trace.toString())
    }

    @Test
    fun seedSweepTerminatesUnderTheGuard() {
        val results = FirelineRun.sweep(
            Mission(
                id = 90, name = "SWEEP PROBE", briefing = "termination sweep",
                layout = layoutOf(
                    terrain = editRows(openTerrainRows(2, 2), listOf(Triple(6, 6, GLYPH_FUEL))),
                    fire = fireRows(listOf(Triple(6, 6, '3'))),
                ),
                seed = 1, objectives = Objectives.EXTINGUISH_ALL, timeLimit = 4_000,
                referenceSource = "loop:  SHOOT\n       JMP    loop",
                maxTicks = 4_000,
            ),
            seeds = (1..12).toList(),
        )
        assertEquals(12, results.size)
        for (r in results) {
            assertTrue(r.tick <= 4_000, "run exceeded the guard: ${r.tick}")
            assertTrue(r.finishedInGuard())
        }
    }

    /** A run that ended by itself (not by the tick guard) has a §7.4 status. */
    private fun FirelineResult.finishedInGuard(): Boolean =
        status == RunStatus.SUCCESS || failureReason == FailureReason.TIME_EXPIRED ||
            failureReason == FailureReason.ROVER_DESTROYED || failureReason == FailureReason.FIRMWARE_FAULT
}
