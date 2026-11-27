package robofight.fireline.app.mission

import robofight.fireline.Mission
import robofight.fireline.Missions
import robofight.fireline.Objectives
import robofight.fireline.TextGrid
import robofight.fireline.session.RunSummary

/**
 * One authored mission rendered as a picker card (DESIGN-FIRELINE.md 5.2):
 * the MISSION empty state shows exactly the six cards of Missions.ALL, ids
 * zero-padded 01-06, with the engine's own briefing text, objective chips,
 * time limit, wind, starting water and the read-only seed. No `FL1-` mission
 * code affordance exists in v1 (DESIGN 5.2, open question 5), so the picker
 * has no stub cards.
 *
 * A gated card that is not open yet is still a real card: `locked` is true and
 * `lockLabel` carries the copy that replaces the OPEN MISSION action.
 *
 * Every field here is a read of the engine's authored data; the model invents
 * no copy and no numbers.
 */
data class MissionCard(
    /** Zero-padded mission id label ("01".."06", DESIGN 5.2). */
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
    /** True when this card is gated by progress and no SUCCESS opened it yet. */
    val locked: Boolean = false,
    /** Copy shown instead of the OPEN MISSION action; null when the card is open. */
    val lockLabel: String? = null,
)

object MissionPickerModel {

    /** Ids every install can open without any recorded progress (owner decision 3:
     *  three missions free, the rest unlocked by progress, no billing). */
    val FREE_MISSION_IDS: List<Int> = listOf(1, 2, 3)

    /**
     * The mission whose recorded SUCCESS opens `missionId`: the predecessor in
     * catalog order (4 opened by 3, 5 by 4, 6 by 5). Null for the free trio.
     */
    fun gateIdFor(missionId: Int): Int? =
        if (missionId in FREE_MISSION_IDS) null else missionId - 1

    /**
     * Unlock rule (PLAN-RECONCILE-MAIN-01.md 10 item 6): ids 1 to 3 are always
     * open, ids 4 to 6 open only when the previous mission in catalog order has
     * a recorded SUCCESS.
     *
     * `bestOf` is the same best-badge read the card badge shows, i.e.
     * `BestBadgeStore.get(missionId, engineVersion)` from `RunStores.kt`, so a
     * card's lock state and its badge state can never disagree. A stored FAILURE
     * or no stored run keeps the card locked; badges are engine-version scoped,
     * so the caller supplies the shipped engine version here.
     */
    fun isUnlocked(missionId: Int, bestOf: (Mission) -> RunSummary?): Boolean {
        val gateId = gateIdFor(missionId) ?: return true
        val gate = Missions.byId(gateId) ?: return false
        return bestOf(gate)?.success == true
    }

    /** The cards the player can open right now, in catalog order. */
    fun unlockedMissions(bestOf: (Mission) -> RunSummary?): List<Mission> =
        Missions.ALL.filter { isUnlocked(it.id, bestOf) }

    /**
     * Card copy for a gated mission that is not open yet. Plain console voice,
     * no dash characters: it names the mission and the SUCCESS it needs.
     */
    fun lockLabel(missionId: Int): String {
        val gate = gateIdFor(missionId)?.let(Missions::byId) ?: return "mission ${"%02d".format(missionId)} is locked"
        return "mission ${"%02d".format(missionId)} is locked  //  a SUCCESS in ${"%02d".format(gate.id)} ${gate.name} opens it"
    }

    /** Free-trio copy above the cards: states the rule the cards follow. */
    const val LINEUP_LABEL: String = "missions 01 to 03 are free  //  a SUCCESS in a mission opens the next one"

    /** The six authored missions in catalog order, each with its best badge and lock state. */
    fun cards(bestOf: (Mission) -> RunSummary?): List<MissionCard> =
        Missions.ALL.map { mission ->
            val unlocked = isUnlocked(mission.id, bestOf)
            cardFor(mission, bestOf(mission)).copy(
                locked = !unlocked,
                lockLabel = if (unlocked) null else lockLabel(mission.id),
            )
        }

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
