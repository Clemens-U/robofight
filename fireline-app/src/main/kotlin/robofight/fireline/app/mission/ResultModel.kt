package robofight.fireline.app.mission

import robofight.fireline.FailureReason
import robofight.fireline.Missions
import robofight.fireline.Objectives

/**
 * RESULT formatting model (DESIGN-FIRELINE.md 5.3): pure text built from a
 * stored [RunSummary]. Every state value comes from the summary; the mission
 * name is read from `Missions.byId` (12.1: reconstruct context from the
 * mission tuple, never from cached world state). The failure chips are the
 * exact `FailureReason` enum names - the model adds no wording of its own.
 */
object ResultModel {

    /** Big banner: engine status, with the exact reason enum name on failure. */
    fun banner(summary: RunSummary): String =
        if (summary.success) "MISSION COMPLETE" else "FAILURE: ${summary.failureReason?.name ?: "?"}"

    /** `MISSION 04 / DOWNWIND   TICK 120/120` (name from the authored catalog). */
    fun missionLine(summary: RunSummary): String {
        val name = Missions.byId(summary.missionId)?.name ?: "UNKNOWN MISSION"
        val limit = summary.timeLimit?.toString() ?: "MAX"
        return "MISSION %02d / %s   TICK %d/%s".format(summary.missionId, name, summary.tick, limit)
    }

    /** Configured objectives with met state: `o` met, `x` unmet (5.3 legend). */
    fun objectiveChips(summary: RunSummary): List<Pair<String, Boolean>> =
        Objectives.ALL_FLAGS
            .filter { Objectives.has(summary.objectiveMask, it) }
            .map { Objectives.name(it) to Objectives.has(summary.objectiveMetMask, it) }

    /** Stat block lines, wireframe order. `shots`/`SHIELD` come from the
     *  documented RunSummary add-ons (exposed Rover state, not FirelineResult). */
    fun statLines(summary: RunSummary): List<String> = listOf(
        "ticks ${summary.tick}          water used ${summary.waterUsed}",
        "water left ${summary.waterRemaining}     shots ${summary.shots}",
        "HP ${summary.roverHp}   HEAT ${summary.roverHeat}   SHIELD ${summary.shieldTicksRemaining}",
        "civilians rescued ${summary.civiliansRescued}  waiting ${summary.civiliansWaiting}  lost ${summary.civiliansLost}",
        "burning cells ${summary.burningCells}  total intensity ${summary.fireIntensitySum}",
        "protected intact ${summary.protectedAssetsIntact}  lost ${summary.protectedAssetsLost}",
        "firmware ${summary.firmwareBytes} B / ${summary.firmwareInstructions} instructions",
        "fingerprint ${fingerprintShort(summary.finalStateHash)}",
    )

    /** Short form = top 32 bits of the 16-hex-digit FNV-1a 64 value (5.3). */
    fun fingerprintShort(finalStateHash: String): String = "$" + finalStateHash.take(FINGERPRINT_SHORT_HEX)

    /**
     * FIRMWARE_FAULT extra panel content (11.6): halt reason plus the last
     * executed instruction, both straight from the summary's engine strings.
     */
    fun haltLines(summary: RunSummary): List<String> =
        if (summary.failureReason == FailureReason.FIRMWARE_FAULT) {
            listOf(
                "HALT: ${summary.failureReason.name}",
                "last instruction: ${summary.lastOp}",
            )
        } else {
            emptyList()
        }

    /** WHY block header note; the lines themselves are the persisted tail. */
    fun whyLines(summary: RunSummary): List<String> = summary.lastEventTail

    private const val FINGERPRINT_SHORT_HEX = 8
}
