package robofight.fireline

/**
 * Headless mission runner (§8.3 step 9, FIRELINE-DESIGN.md §13.2/§13.4).
 *
 * Every path here goes through the SAME authority — `FirelineWorld.tickOnce()` —
 * so the runner result, a hand-driven step-by-step world session and the app's
 * STEP control must agree exactly (§13.2 point 3). No filesystem, network or
 * Android access: the runner only assembles the state machine and renders text.
 */
object FirelineRun {

    /**
     * Run one mission descriptor to its §7.4 end condition (or the §13.4 safety
     * cap) and return the deterministic result, including the final-state hash.
     */
    fun run(descriptor: MissionDescriptor): FirelineResult {
        val world = FirelineWorld(descriptor)
        world.runUntilFinished()
        return world.snapshot()
    }

    /** Run one authored mission with its reference firmware (§9). */
    fun run(mission: Mission): FirelineResult =
        runWithGuard(mission, mission.descriptorForSource(mission.referenceSource))

    /** Mission rules decide the guard, so a stalled firmware cannot outrun it. */
    fun runWithGuard(mission: Mission, descriptor: MissionDescriptor): FirelineResult {
        val world = FirelineWorld(descriptor)
        world.runUntilFinished(mission.maxTicks)
        return world.snapshot()
    }

    /**
     * Identical run driven through the step-by-step world API instead of the
     * convenience loop (§13.2 point 3 comparison path).
     */
    fun runStepByStep(descriptor: MissionDescriptor, maxTicks: Int = MAX_TICKS): FirelineResult {
        val world = FirelineWorld(descriptor)
        while (!world.finished && world.tick < maxTicks) world.tickOnce()
        return world.snapshot()
    }

    /**
     * Determinism probe: fresh worlds of the same descriptor+firmware must
     * produce identical results AND identical final-state hashes (§13.2 point 1).
     */
    fun runsAreIdentical(descriptor: MissionDescriptor, repetitions: Int = 2, maxTicks: Int = MAX_TICKS): Boolean {
        val first = runStepByStep(descriptor, maxTicks)
        repeat(repetitions - 1) {
            val next = run(descriptor)
            if (next != first || next.finalStateHash != first.finalStateHash) return false
        }
        return true
    }

    /** Sweep seeds and confirm every run terminates inside the guard (§13.2 point 5). */
    fun sweep(mission: Mission, seeds: List<Int>, maxTicks: Int = mission.maxTicks): List<FirelineResult> =
        seeds.map { seed ->
            val descriptor = MissionDescriptor(
                engineVersion = FIRELINE_ENGINE_VERSION,
                missionId = mission.id,
                seed = seed,
                objectives = mission.objectives,
                timeLimit = mission.timeLimit,
                surviveUntil = mission.surviveUntil,
                startingWater = mission.startingWater,
                windDir = mission.windDir,
                windForce = mission.windForce,
                firmware = codeOf(mission.referenceSource),
                layout = mission.layout,
            )
            val world = FirelineWorld(descriptor)
            world.runUntilFinished(maxTicks)
            require(world.finished) { "mission $mission seed $seed did not terminate within $maxTicks ticks" }
            world.snapshot()
        }

    /**
     * Golden event trace: the event lines of the first [ticks] ticks of a run
     * (§13.2 point 4). Recorded once, then asserted by tests.
     */
    fun eventTrace(descriptor: MissionDescriptor, ticks: Int): List<String> {
        val world = FirelineWorld(descriptor)
        val trace = ArrayList<String>()
        repeat(ticks) {
            world.tickOnce()
            trace.add("%03d %s | %s".format(
                world.tick, world.lastOp, world.lastEvents.joinToString(" · "),
            ))
        }
        return trace
    }

    private fun codeOf(source: String): ByteArray {
        val r = robofight.assembler.assemble(source)
        check(r.ok) { "firmware failed to assemble:\n" + r.errors.joinToString("\n") { it.msg } }
        return r.code
    }

    /** Text report of a finished run (§11.6 shape, headless form). */
    fun report(result: FirelineResult): String = result.render()
}
