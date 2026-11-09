package robofight.fireline

/**
 * Deterministic outcome + statistics (FIRELINE-DESIGN.md §7.4/§7.5).
 *
 * [FirelineWorld.snapshot] is what fills these fields; MVP deliberately does
 * not collapse them into an opaque score — mission comparison uses the ordered
 * [compareTo] rules of §7.5 and code-golf by firmware size stays separate.
 */

enum class RunStatus { SUCCESS, FAILURE }

/** Primary failure reason; §7.4 fixes the precedence when several happen at once. */
enum class FailureReason {
    /** The VM faulted (undefined opcode, ran off the end of the program). */
    FIRMWARE_FAULT,

    /** Rover HP reached zero (fire exposure or heat overload). */
    ROVER_DESTROYED,

    /** A required civilian reached 0 HP while RESCUE_ALL was configured. */
    CIVILIAN_LOST,

    /** A protected asset reached fire intensity 100 while PROTECT_ASSETS was configured. */
    ASSET_LOST,

    /** Hard timeLimit reached without completion (also the 1,000,000-tick safety cap). */
    TIME_EXPIRED,
}

data class FirelineResult(
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
    /** Canonical ordered final-state fingerprint (§13.2) — replay/test anchor. */
    val finalStateHash: String,
    /** Event lines of the last processed tick (telemetry/debug aid). */
    val lastEvents: List<String>,
) {
    val success: Boolean get() = status == RunStatus.SUCCESS

    /**
     * §7.5 mission comparison: success before failure, more required civilians
     * rescued, fewer required civilians lost, fewer remaining fire-intensity
     * points, fewer ticks for successful runs, higher rover HP, more water
     * remaining. Ordering is best-first.
     */
    fun compareToRank(other: FirelineResult): Int {
        if (status != other.status) return if (success) -1 else 1
        if (civiliansRescued != other.civiliansRescued) return other.civiliansRescued - civiliansRescued
        if (civiliansLost != other.civiliansLost) return civiliansLost - other.civiliansLost
        if (fireIntensitySum != other.fireIntensitySum) return fireIntensitySum - other.fireIntensitySum
        if (success) {
            if (tick != other.tick) return tick - other.tick
            if (roverHp != other.roverHp) return other.roverHp - roverHp
            if (waterRemaining != other.waterRemaining) return other.waterRemaining - waterRemaining
        }
        return 0
    }

    fun render(): String = buildString {
        append("mission %02d seed=%d -> ".format(missionId, seed))
        if (success) append("SUCCESS") else append("FAILURE (${failureReason?.name ?: "?"})")
        append(" at tick $tick\n")
        append("rover: hp=$roverHp heat=$roverHeat water=$waterRemaining used=$waterUsed\n")
        append("civilians: rescued=$civiliansRescued waiting=$civiliansWaiting lost=$civiliansLost\n")
        append("fire: cells=$burningCells intensity=$fireIntensitySum  assets: intact=$protectedAssetsIntact lost=$protectedAssetsLost\n")
        append("firmware: $firmwareBytes bytes / $firmwareInstructions instructions\n")
        append("hash: $finalStateHash")
    }
}
