package robofight.fireline.app.mission

import robofight.fireline.FailureReason
import robofight.fireline.FirelineResult
import robofight.fireline.FirelineWorld
import robofight.fireline.Objectives
import robofight.fireline.RunStatus

/**
 * Plain-data record of one finished run (FIRELINE-DESIGN.md 12.1: persist the
 * most recent result and the per-mission best result; never a mutable world).
 *
 * The scalar block mirrors every FirelineResult field of §7.5 one-for-one, so
 * a stored run can be compared with the engine's own §7.5 ranking through
 * [toFirelineResult]. The add-on block holds only values the engine exposes
 * but §7.5 does not carry (Rover statistics, the VM's last op, the per-tick
 * objective state, the firmware name and the newest event-log tail lines);
 * nothing here is computed a second time in the app.
 *
 * Storage is a flat `key=value` line format (see [encode]): engine strings
 * never contain newlines, and splitting each line at the FIRST `=` keeps
 * values that themselves contain `=` or `,` intact (event lines like
 * `FIRE IGNITES ... chance=42<64`). This keeps persistence dependency-free -
 * no JSON library, matching the curated offline egress of the module.
 */
data class RunSummary(
    // ---- FirelineResult §7.5 scalars, field for field ----
    val status: RunStatus,
    val failureReason: FailureReason?,
    val engineVersion: Int,
    val missionId: Int,
    val seed: Int,
    val tick: Int,
    val roverHp: Int,
    val roverHeat: Int,
    val waterRemaining: Int,
    val waterUsed: Int,
    val civiliansRescued: Int,
    val civiliansWaiting: Int,
    val civiliansLost: Int,
    val burningCells: Int,
    val fireIntensitySum: Int,
    val protectedAssetsIntact: Int,
    val protectedAssetsLost: Int,
    val firmwareBytes: Int,
    val firmwareInstructions: Int,
    val finalStateHash: String,
    // ---- exposed engine state that §7.5 does not carry ----
    /** Rover.shots / Rover.shotsDry (public Rover statistics, §3.6). */
    val shots: Int,
    val shotsDry: Int,
    /** Rover.shieldTicksRemaining at the finishing tick (telemetry readout). */
    val shieldTicksRemaining: Int,
    /** Vm.lastOp string at the finishing tick (FIRMWARE_FAULT halt reason, 11.6). */
    val lastOp: String,
    // ---- run context ----
    val objectiveMask: Int,
    /** Which configured objectives held at the finishing tick (chip met/unmet). */
    val objectiveMetMask: Int,
    val timeLimit: Int?,
    val firmwareName: String,
    /** Newest-first tail of the run's event log, each line `tick op | events`. */
    val lastEventTail: List<String>,
) {
    val success: Boolean get() = status == RunStatus.SUCCESS

    /** Rebuild a §7.5 result so ranking uses the engine comparator, not a copy. */
    fun toFirelineResult(): FirelineResult = FirelineResult(
        status = status,
        failureReason = failureReason,
        engineVersion = engineVersion,
        missionId = missionId,
        seed = seed,
        tick = tick,
        roverHp = roverHp,
        roverHeat = roverHeat,
        waterRemaining = waterRemaining,
        waterUsed = waterUsed,
        civiliansRescued = civiliansRescued,
        civiliansWaiting = civiliansWaiting,
        civiliansLost = civiliansLost,
        burningCells = burningCells,
        fireIntensitySum = fireIntensitySum,
        protectedAssetsIntact = protectedAssetsIntact,
        protectedAssetsLost = protectedAssetsLost,
        firmwareBytes = firmwareBytes,
        firmwareInstructions = firmwareInstructions,
        finalStateHash = finalStateHash,
        lastEvents = lastEventTail,
    )

    fun encode(): String = buildString {
        put("status", status.name)
        put("failure", failureReason?.name ?: "")
        put("engine", engineVersion.toString())
        put("mission", missionId.toString())
        put("seed", seed.toString())
        put("tick", tick.toString())
        put("hp", roverHp.toString())
        put("heat", roverHeat.toString())
        put("water_left", waterRemaining.toString())
        put("water_used", waterUsed.toString())
        put("civ_rescued", civiliansRescued.toString())
        put("civ_waiting", civiliansWaiting.toString())
        put("civ_lost", civiliansLost.toString())
        put("cells", burningCells.toString())
        put("intensity", fireIntensitySum.toString())
        put("assets_intact", protectedAssetsIntact.toString())
        put("assets_lost", protectedAssetsLost.toString())
        put("fw_bytes", firmwareBytes.toString())
        put("fw_insns", firmwareInstructions.toString())
        put("hash", finalStateHash)
        put("shots", shots.toString())
        put("shots_dry", shotsDry.toString())
        put("shield", shieldTicksRemaining.toString())
        put("last_op", lastOp)
        put("obj_mask", objectiveMask.toString())
        put("obj_met", objectiveMetMask.toString())
        put("time_limit", timeLimit?.toString() ?: "")
        put("fw_name", firmwareName)
        for (event in lastEventTail) put("event", event)
    }

    companion object {

        /** Snapshot the finished (or mid-run diagnostic) world into plain data. */
        fun from(world: FirelineWorld, firmwareName: String, lastEventTail: List<String>): RunSummary {
            val result = world.snapshot()
            val rover = world.rover
            return RunSummary(
                status = result.status,
                failureReason = result.failureReason,
                engineVersion = result.engineVersion,
                missionId = result.missionId,
                seed = result.seed,
                tick = result.tick,
                roverHp = result.roverHp,
                roverHeat = result.roverHeat,
                waterRemaining = result.waterRemaining,
                waterUsed = result.waterUsed,
                civiliansRescued = result.civiliansRescued,
                civiliansWaiting = result.civiliansWaiting,
                civiliansLost = result.civiliansLost,
                burningCells = result.burningCells,
                fireIntensitySum = result.fireIntensitySum,
                protectedAssetsIntact = result.protectedAssetsIntact,
                protectedAssetsLost = result.protectedAssetsLost,
                firmwareBytes = result.firmwareBytes,
                firmwareInstructions = result.firmwareInstructions,
                finalStateHash = result.finalStateHash,
                shots = rover.shots,
                shotsDry = rover.shotsDry,
                shieldTicksRemaining = rover.shieldTicksRemaining,
                lastOp = world.lastOp,
                objectiveMask = world.descriptor.objectives,
                objectiveMetMask = objectiveMetMask(world),
                timeLimit = world.descriptor.timeLimit,
                firmwareName = firmwareName,
                lastEventTail = lastEventTail,
            )
        }

        /**
         * Per-objective met state at the finishing tick, mirroring the exact
         * predicates of `FirelineWorld.objectivesSatisfied()` so the RESULT
         * chips cannot disagree with what the engine ended on.
         */
        fun objectiveMetMask(world: FirelineWorld): Int {
            val m = world.descriptor.objectives
            var met = 0
            if (Objectives.has(m, Objectives.EXTINGUISH_ALL) && world.fireAllOut())
                met = met or Objectives.EXTINGUISH_ALL
            if (Objectives.has(m, Objectives.RESCUE_ALL) && world.allRequiredRescued())
                met = met or Objectives.RESCUE_ALL
            if (Objectives.has(m, Objectives.REACH_EXIT) && world.roverOnExit())
                met = met or Objectives.REACH_EXIT
            if (Objectives.has(m, Objectives.SURVIVE_UNTIL) && world.survivalElapsed() && world.rover.alive)
                met = met or Objectives.SURVIVE_UNTIL
            if (Objectives.has(m, Objectives.PROTECT_ASSETS) && world.assetsIntactObjective())
                met = met or Objectives.PROTECT_ASSETS
            return met
        }

        /** Null for anything that is not a valid encoded summary. */
        fun decode(text: String): RunSummary? {
            val scalar = LinkedHashMap<String, String>()
            val events = ArrayList<String>()
            for (line in text.split('\n')) {
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                if (eq <= 0) return null
                val key = line.substring(0, eq)
                val value = line.substring(eq + 1)
                if (key == "event") events.add(value) else scalar[key] = value
            }
            val status = scalar["status"]?.let { name -> RunStatus.values().firstOrNull { it.name == name } } ?: return null
            val engine = scalar["engine"]?.toIntOrNull() ?: return null
            val missionId = scalar["mission"]?.toIntOrNull() ?: return null
            return RunSummary(
                status = status,
                failureReason = scalar["failure"]?.takeIf { it.isNotEmpty() }?.let { name -> FailureReason.values().firstOrNull { it.name == name } },
                engineVersion = engine,
                missionId = missionId,
                seed = scalar["seed"]?.toIntOrNull() ?: return null,
                tick = scalar["tick"]?.toIntOrNull() ?: return null,
                roverHp = scalar["hp"]?.toIntOrNull() ?: return null,
                roverHeat = scalar["heat"]?.toIntOrNull() ?: return null,
                waterRemaining = scalar["water_left"]?.toIntOrNull() ?: return null,
                waterUsed = scalar["water_used"]?.toIntOrNull() ?: return null,
                civiliansRescued = scalar["civ_rescued"]?.toIntOrNull() ?: return null,
                civiliansWaiting = scalar["civ_waiting"]?.toIntOrNull() ?: return null,
                civiliansLost = scalar["civ_lost"]?.toIntOrNull() ?: return null,
                burningCells = scalar["cells"]?.toIntOrNull() ?: return null,
                fireIntensitySum = scalar["intensity"]?.toIntOrNull() ?: return null,
                protectedAssetsIntact = scalar["assets_intact"]?.toIntOrNull() ?: return null,
                protectedAssetsLost = scalar["assets_lost"]?.toIntOrNull() ?: return null,
                firmwareBytes = scalar["fw_bytes"]?.toIntOrNull() ?: return null,
                firmwareInstructions = scalar["fw_insns"]?.toIntOrNull() ?: return null,
                finalStateHash = scalar["hash"] ?: return null,
                shots = scalar["shots"]?.toIntOrNull() ?: return null,
                shotsDry = scalar["shots_dry"]?.toIntOrNull() ?: return null,
                shieldTicksRemaining = scalar["shield"]?.toIntOrNull() ?: return null,
                lastOp = scalar["last_op"] ?: return null,
                objectiveMask = scalar["obj_mask"]?.toIntOrNull() ?: return null,
                objectiveMetMask = scalar["obj_met"]?.toIntOrNull() ?: return null,
                timeLimit = scalar["time_limit"]?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
                firmwareName = scalar["fw_name"] ?: return null,
                lastEventTail = events,
            )
        }
    }
}

private fun StringBuilder.put(key: String, value: String) {
    append(key).append('=').append(value).append('\n')
}
