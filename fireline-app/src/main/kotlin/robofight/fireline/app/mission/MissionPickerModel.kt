package robofight.fireline.app.mission

import robofight.fireline.Mission
import robofight.fireline.Missions
import robofight.fireline.Objectives
import robofight.fireline.TextGrid

/**
 * One authored mission rendered as a picker card (DESIGN-FIRELINE.md 5.2):
 * the free MISSION picker shows ids 01, 02 and 04, with the engine's own
 * briefing text, objective chips,
 * time limit, wind, starting water and the read-only seed. No `FL1-` mission
 * code affordance exists in v1 (DESIGN 5.2, open question 5), so the picker
 * has no stub cards.
 *
 * Every field here is a read of the engine's authored data; the model invents
 * no copy and no numbers.
 */
data class MissionCard(
    /** Zero-padded mission id label ("01".."05", DESIGN 5.2). */
    val idLabel: String,
    val missionId: Int,
    val name: String,
    /** Verbatim `Mission.briefing` line (DESIGN 8: "exact briefing text from Missions.kt"). */
    val briefing: String,
    /** Objective names in engine flag order (Objectives.name, e.g. "EXTINGUISH_ALL"). */
    val objectiveChips: List<String>,
    val timeLimitLabel: String,
    /** Raw authored time limit (null = no hard limit), same value as the label. */
    val timeLimit: Int?,
    val windLabel: String,
    val startingWater: Int,
    /** Read-only in v1: authored missions pin their seed (DESIGN 5.2). */
    val seedLabel: String,
    /** Best-result badge for the current engine version, null when never run. */
    val badgeLabel: String?,
)

object MissionPickerModel {

    /** Free edition content; retired IDs stay stable in the engine catalog. */
    val availableMissions: List<Mission> = listOf(Missions.NOZZLE_CHECK, Missions.CORRIDOR, Missions.DOWNWIND)

    fun availableById(id: Int): Mission? = availableMissions.firstOrNull { it.id == id }

    /** The three free missions in catalog order, each with its best badge. */
    fun cards(bestOf: (Mission) -> RunSummary?): List<MissionCard> =
        availableMissions.map { cardFor(it, bestOf(it)) }

    fun cardFor(mission: Mission, best: RunSummary?): MissionCard = MissionCard(
        idLabel = "%02d".format(mission.id),
        missionId = mission.id,
        name = mission.name,
        briefing = mission.briefing,
        objectiveChips = objectiveChips(mission),
        timeLimitLabel = timeLimitLabel(mission),
        timeLimit = mission.timeLimit,
        windLabel = windLabel(mission),
        startingWater = mission.startingWater,
        seedLabel = seedLabel(mission),
        badgeLabel = best?.let(::badgeLabel),
    )

    /** Chips use the engine's objective names verbatim, in Objectives.ALL_FLAGS order. */
    fun objectiveChips(mission: Mission): List<String> =
        Objectives.ALL_FLAGS.filter { Objectives.has(mission.objectives, it) }.map { Objectives.name(it) }

    /** Engine direction letter + force, e.g. "W x0" / "E x2" (TextGrid.dirName). */
    fun windLabel(mission: Mission): String =
        "${TextGrid.dirName(mission.windDir)} x${mission.windForce}"

    fun timeLimitLabel(mission: Mission): String =
        mission.timeLimit?.let { "LIMIT $it TICKS" } ?: "LIMIT NONE"

    /** Seed is displayed read-only in v1; sandbox seed editing is post-MVP. */
    fun seedLabel(mission: Mission): String = "SEED ${mission.seed} (FIXED)"

    /** Badge states the outcome exactly: enum names from RunStatus / FailureReason. */
    fun badgeLabel(best: RunSummary): String =
        if (best.success) "BEST: SUCCESS  TICK ${best.tick}"
        else "BEST: FAILURE ${best.failureReason?.name ?: "?"}  TICK ${best.tick}"
}
