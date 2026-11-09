package robofight.fireline.app.mission

import robofight.fireline.FailureReason
import robofight.fireline.RunStatus

/**
 * Synthetic RunSummary builder for unit tests only (never shown as a real run;
 * real summaries come from `RunSummary.from(world, ...)`). The numbers mirror
 * the DESIGN-FIRELINE.md 5.3 wireframe run so formatting tests compare against
 * the documented layout.
 */
internal fun runSummarySample(
    status: RunStatus = RunStatus.FAILURE,
    failureReason: FailureReason? = FailureReason.TIME_EXPIRED,
    engineVersion: Int = 1,
    missionId: Int = 4,
    tick: Int = 120,
    seed: Int = 4004,
    roverHp: Int = 34,
    roverHeat: Int = 62,
    waterRemaining: Int = 40,
    waterUsed: Int = 60,
    civiliansRescued: Int = 1,
    civiliansWaiting: Int = 1,
    civiliansLost: Int = 0,
    burningCells: Int = 3,
    fireIntensitySum: Int = 125,
    protectedAssetsIntact: Int = 2,
    protectedAssetsLost: Int = 0,
    firmwareBytes: Int = 66,
    firmwareInstructions: Int = 22,
    finalStateHash: String = "7c31a90edeadbeef",
    shots: Int = 6,
    shotsDry: Int = 0,
    shieldTicksRemaining: Int = 0,
    lastOp: String = "JMP 268",
    objectiveMask: Int = 7,
    objectiveMetMask: Int = 1,
    timeLimit: Int? = 120,
    firmwareName: String = "DOWNWIND.ASM",
    lastEventTail: List<String> = listOf(
        "118 IN port18 | ",
        "115  | ROVER FIRE DAMAGE -8 (fire 50)",
    ),
): RunSummary = RunSummary(
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
    shots = shots,
    shotsDry = shotsDry,
    shieldTicksRemaining = shieldTicksRemaining,
    lastOp = lastOp,
    objectiveMask = objectiveMask,
    objectiveMetMask = objectiveMetMask,
    timeLimit = timeLimit,
    firmwareName = firmwareName,
    lastEventTail = lastEventTail,
)
