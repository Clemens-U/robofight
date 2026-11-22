package robofight.fireline.web

import robofight.fireline.FailureReason
import robofight.fireline.FirelineResult
import robofight.fireline.FirelinePorts
import robofight.fireline.FirelineRun
import robofight.fireline.FirelineWorld
import robofight.fireline.Mission
import robofight.fireline.Missions
import robofight.fireline.Objectives
import robofight.fireline.RunStatus
import robofight.fireline.TextGrid
import robofight.fireline.session.MissionSession
import robofight.fireline.session.ResultModel
import robofight.fireline.session.StepOutcome
import robofight.fireline.session.TelemetrySnapshot
import robofight.isa.decPad

/**
 * The presentation-free data layer of the FIRELINE WEB CONSOLE
 * (docs/FIRELINE-WEB.md §13, frozen layout §15). Two things live here:
 *
 * 1. [MissionRow] picker rows for `Missions.ALL` - the `FL:> SELECT A CALL`
 *    cards of §15.2 item 7, plus the engine-v2 best-run label the cards carry;
 * 2. [ConsoleRun], a thin driver over the shared `MissionSession`
 *    (`:fireline-engine` commonMain) that names the state the run surface, the
 *    RF-8 STATE band and the TELEMETRY band render.
 *
 * There is no DOM, HTML or JS here on purpose: the browser shell renders these
 * values and never derives any of them. Like the Android MISSION screen, this
 * layer only ever asks the engine (`MissionSession`, `ResultModel`,
 * `FirelineRun`) - which is what §13 names as the drift risk between the two UIs.
 *
 * Every number this layer can produce is engine output; the committed fixtures
 * under `docs/web/fixtures/` are the source of truth for what the engine must
 * output (`ConsoleModelTest` asserts exactly that).
 */
class ConsoleModel(val missions: List<Mission> = Missions.ALL) {

    /** Best-run labels are engine runs; computed once per mission and cached. */
    private val bestRuns = HashMap<Int, BestRunLabel>()

    /** The mission-picker cards, in authored order (`Missions.ALL`). */
    fun pickerRows(): List<MissionRow> = missions.map { rowOf(it) }

    /** One picker card by mission id, or null when the id is not authored. */
    fun row(missionId: Int): MissionRow? = missions.firstOrNull { it.id == missionId }?.let { rowOf(it) }

    /** Labels of the documented failing firmware variants of a mission (§9 authored facts). */
    fun firmwareLabels(missionId: Int): List<String> =
        missions.firstOrNull { it.id == missionId }?.failingSources?.map { it.first } ?: emptyList()

    /**
     * Open a mission in the console. Default firmware is the mission's authored
     * reference source; the name is console-formatted (`MISSION 02 / CORRIDOR`,
     * Mission.toString) so the shell and the app show the same mission label.
     */
    fun openMission(missionId: Int, firmwareSource: String = referenceSource(missionId), firmwareName: String = "REFERENCE.ASM"): ConsoleRun {
        val mission = missions.firstOrNull { it.id == missionId }
            ?: throw IllegalArgumentException("no authored mission with id $missionId")
        return ConsoleRun(mission, MissionSession(mission, firmwareName, firmwareSource))
    }

    /**
     * The editor path (docs/FIRELINE-WEB.md §12a D4 "editor with line-level
     * assembler errors"): validate a typed source through the web input gate
     * (`WebInputLimits`, 1200 lines / 8192 bytes / non-ASCII refusals) and the
     * RF-8 assembler, and open the mission only when both are clean. A refused
     * draft carries the exact `AsmError` line numbers so the console can name
     * the line it failed on instead of failing the whole page.
     */
    fun draftFirmware(missionId: Int, firmwareName: String, firmwareSource: String): DraftResult {
        val mission = missions.firstOrNull { it.id == missionId }
            ?: return DraftResult.Rejected(listOf("no authored mission with id $missionId"))
        val problems = WebLibrary.sourceProblems(firmwareSource)
        if (problems.isNotEmpty()) return DraftResult.Rejected(problems)
        return DraftResult.Accepted(ConsoleRun(mission, MissionSession(mission, firmwareName, firmwareSource)))
    }

    /** Outcome of [draftFirmware]: an openable run, or the refusal reasons. */
    sealed class DraftResult {
        data class Accepted(val run: ConsoleRun) : DraftResult()
        data class Rejected(val problems: List<String>) : DraftResult()
    }

    /** Open a mission with one of its documented failing firmware variants. */
    fun openFailingFirmware(missionId: Int, label: String): ConsoleRun {
        val mission = missions.firstOrNull { it.id == missionId }
            ?: throw IllegalArgumentException("no authored mission with id $missionId")
        val entry = mission.failingSources.firstOrNull { it.first == label }
            ?: throw IllegalArgumentException("mission ${mission.id} has no failing firmware '$label'")
        return ConsoleRun(mission, MissionSession(mission, label + ".ASM", entry.second))
    }

    private fun referenceSource(missionId: Int): String =
        missions.firstOrNull { it.id == missionId }?.referenceSource
            ?: throw IllegalArgumentException("no authored mission with id $missionId")

    private fun rowOf(mission: Mission): MissionRow = MissionRow(
        id = mission.id,
        title = mission.name,
        briefing = mission.briefing,
        objectiveLabels = Objectives.ALL_FLAGS
            .filter { Objectives.has(mission.objectives, it) }
            .map { Objectives.name(it) },
        seed = mission.seed,
        timeLimit = mission.timeLimit,
        tickLimit = mission.maxTicks,
        windLabel = TextGrid.dirName(mission.windDir),
        windForce = mission.windForce,
        startingWater = mission.startingWater,
        bestRun = bestRunOf(mission),
    )

    /**
     * Engine-v2 best run of the authored reference firmware: the headless
     * authority path (`FirelineRun.runWithGuard`), formatted from its own result -
     * the same values pinned in `docs/web/fixtures/parity_matrix.tsv` /
     * `parity_matrix.json`.
     */
    fun bestRunOf(mission: Mission): BestRunLabel = bestRuns.getOrPut(mission.id) {
        val result = FirelineRun.runWithGuard(mission, mission.descriptorForSource(mission.referenceSource))
        BestRunLabel(
            engineVersion = result.engineVersion,
            status = result.status,
            tick = result.tick,
            timeLimit = mission.timeLimit,
            fingerprintHex = ResultModel.fingerprintShort(result.finalStateHash),
            firmwareBytes = result.firmwareBytes,
            firmwareInstructions = result.firmwareInstructions,
        )
    }

    companion object {
        /** Console formatting rule shared with `MissionSession`'s own log lines. */
        const val LOG_SEQ_WIDTH = 3

        /** `030 MOVE (1/2) | ` - the golden-trace line shape (`decPad(tick,3) + ' ' + rest`). */
        fun logLine(seq: Int, text: String): String = decPad(seq, LOG_SEQ_WIDTH) + " " + text

        /** Same line built from the engine's own op text and event list. */
        fun logLine(seq: Int, op: String, events: List<String>): String =
            logLine(seq, op + " | " + events.joinToString(" · "))
    }
}

/**
 * One `FL:> SELECT A CALL` mission card (§15.2 item 7). Field names mirror the
 * committed fixture keys (`docs/web/fixtures/snapshots.json`) so a fixture value
 * and a rendered card can be compared without renaming anything:
 * `time_limit` -> [timeLimit], `tick_limit` -> [tickLimit], `wind_dir` ->
 * [windLabel], `wind_force` -> [windForce], `objectives_readable` ->
 * [objectiveLabels].
 */
data class MissionRow(
    val id: Int,
    /** Authored mission name, e.g. `CORRIDOR`. */
    val title: String,
    /** Authored briefing line shown on the card. */
    val briefing: String,
    /** Objective labels in engine order (`EXTINGUISH_ALL`, `REACH_EXIT`). */
    val objectiveLabels: List<String>,
    val seed: Int,
    /** Mission time limit shown as `TICK n / limit` (null when unbounded). */
    val timeLimit: Int?,
    /** Headless tick guard of this mission (fixture key `tick_limit`). */
    val tickLimit: Int,
    /** Compass letter of the authored wind direction. */
    val windLabel: String,
    val windForce: Int,
    val startingWater: Int,
    /** Engine-v2 best run of the authored reference firmware. */
    val bestRun: BestRunLabel,
) {
    /** `MISSION 02 / CORRIDOR` - the prompt/titlebar wording of §15.2 item 1
     *  (`MISSION nn / NAME`), the same wording `ResultModel.missionLine` puts in
     *  the RESULT header, so the picker card and a result card agree. */
    val missionLabel: String get() = "MISSION " + decPad(id, 2) + " / " + title
}

/** Engine-v2 best-run label carried by a picker card. */
data class BestRunLabel(
    val engineVersion: Int,
    val status: RunStatus,
    val tick: Int,
    val timeLimit: Int?,
    val fingerprintHex: String,
    val firmwareBytes: Int,
    val firmwareInstructions: Int,
) {
    /** Card line, e.g. `engine v2  SUCCESS tick 102/120  $d3ea4711  75 B / 25 instructions`. */
    val label: String
        get() = "engine v$engineVersion  ${status.name} tick $tick/${timeLimit?.toString() ?: "MAX"}  " +
            "$fingerprintHex  $firmwareBytes B / $firmwareInstructions instructions"
}

/**
 * Driver for one console run. It owns nothing: the world lives in
 * [MissionSession] (shared with the Android MISSION screen), and every accessor
 * below either forwards to it or formats its output.
 *
 * Method names are the console's own vocabulary for the frozen layout
 * (§15.2 items 2-5): [status], [tick], [lastEvent], [nextEventPreview],
 * [firmwareBytes], [firmwareInstructions], [fingerprint], [fingerprintHex],
 * [telemetry], [snapshot], [step], [run], [reset], [logTail], [events].
 */
class ConsoleRun(val mission: Mission, val session: MissionSession) {

    val missionId: Int = mission.id
    val firmwareName: String = session.firmwareName

    /** `MISSION 02 / CORRIDOR` for the prompt line of §15.2 item 1. */
    val missionLabel: String get() = "MISSION " + mission

    val tick: Int get() = session.tick
    val tickLimit: Int get() = mission.maxTicks
    val finished: Boolean get() = session.finished
    val guardReached: Boolean get() = session.guardReached

    /** Engine status, null while the run is still stepping. */
    fun status(): RunStatus? = session.status

    /** Exact `FailureReason` enum name on failure (no console wording added). */
    fun failureReason(): FailureReason? = session.failureReason

    /** Last executed op text, e.g. `MOVE (2/2)` / `JMP 256` (RF-8 STATE `LAST OP`). */
    fun lastEvent(): String = session.telemetry().lastOp

    /**
     * The `next:` / `exec:` preview the app's MISSION surface shows next to the
     * firmware pane (`MissionRunSurface`), built from the same telemetry fields
     * so the two containers cannot drift in wording.
     */
    fun nextEventPreview(): String {
        val t = session.telemetry()
        return (if (t.executing) "exec: " else "next: ") + t.instructionText +
            (if (t.instructionLine > 0) "   @line " + decPad(t.instructionLine, 4) else "")
    }

    /** One tick's readout, straight from the engine's accessors. */
    fun telemetry(): TelemetrySnapshot = session.telemetry()

    /**
     * Read handle on the world the session owns (`MissionSession.currentWorld`),
     * for `WorldGrid`: terrain, fire, rover position and facing, civilians and
     * packets are read through `FirelineWorld` / `TextGrid` exactly as the
     * Android `FirelineGridView` reads them. The render layer never copies or
     * mutates it.
     */
    val currentWorld: FirelineWorld get() = session.currentWorld

    /** Full engine snapshot (mid-run diagnostic, post-finish authoritative). */
    fun snapshot(): FirelineResult = session.result()

    /** Full 16-hex-digit FNV-1a 64 state fingerprint. */
    fun fingerprint(): String = session.result().finalStateHash

    /** Short form shown on the status line: `$` + top 32 bits (`$d3ea4711`). */
    fun fingerprintHex(): String = ResultModel.fingerprintShort(fingerprint())

    fun firmwareBytes(): Int = session.result().firmwareBytes
    fun firmwareInstructions(): Int = session.result().firmwareInstructions

    /**
     * The firmware text the source pane renders, verbatim from the session's own
     * source string (`MissionSession.firmwareSource`). The shell never rewrites
     * a line: escaping happens in `ConsoleRender`, formatting never reassembly.
     */
    fun sourceLines(): List<String> = session.firmwareSource.lines()

    /**
     * The 12 TELEMETRY cells of §15.2 item 4 in the frozen order, each value a
     * `FirelineWorld.sensor` read of the matching `FirelinePorts` port. WIND_DIR
     * and WIND_FORCE are the raw port numbers, which is what the frozen golden
     * master shows (the mission's authored wind letters belong to the `WIND`
     * line of the world column, not to this band).
     */
    fun readoutPorts(): List<Pair<String, Int>> = listOf(
        "FIRE_DIST" to sensor(FirelinePorts.FIRE_DIST),
        "FIRE_ANGLE" to sensor(FirelinePorts.FIRE_ANGLE),
        "FIRE_LEVEL" to sensor(FirelinePorts.FIRE_LEVEL),
        "LOCAL_FIRE" to sensor(FirelinePorts.LOCAL_FIRE),
        "SMOKE" to sensor(FirelinePorts.SMOKE),
        "CIV_DIST" to sensor(FirelinePorts.CIV_DIST),
        "CIV_ANGLE" to sensor(FirelinePorts.CIV_ANGLE),
        "CIV_COUNT" to sensor(FirelinePorts.CIV_COUNT),
        "EXIT_DIST" to sensor(FirelinePorts.EXIT_DIST),
        "EXIT_ANGLE" to sensor(FirelinePorts.EXIT_ANGLE),
        "WIND_DIR" to sensor(FirelinePorts.WIND_DIR),
        "WIND_FORCE" to sensor(FirelinePorts.WIND_FORCE),
    )

    private fun sensor(port: Int): Int = session.currentWorld.sensor(port)

    /** Objective chips (`EXTINGUISH_ALL` met or not): `ResultModel.objectiveChips`
     *  over this session's own `RunSummary`, so the console and the RESULT panel
     *  cannot disagree about what held. */
    fun objectiveChips(): List<Pair<String, Boolean>> = ResultModel.objectiveChips(session.summary())

    /**
     * `RUN STATS` rows of §15.2 item 6 in the mockup's row order. Every row is a
     * public engine field: rover counters (`Rover.shots / shotsDry / distanceMoved
     * / shieldsRaised / waterUsed`) plus `FirelineResult` fields. The mockup's
     * `SHIELD ACTIVATIONS` is `Rover.shieldsRaised` (activations, not the
     * remaining shield ticks).
     */
    fun runStats(): List<Pair<String, String>> {
        val rover = session.currentWorld.rover
        val result = session.result()
        return listOf(
            "SHOOTS FIRED" to rover.shots.toString(),
            "LOCKED-OUT SHOTS" to rover.shotsDry.toString(),
            "MOVES" to rover.distanceMoved.toString(),
            "SHIELD ACTIVATIONS" to rover.shieldsRaised.toString(),
            "WATER USED" to rover.waterUsed.toString(),
            "BURNING CELLS" to result.burningCells.toString(),
            "CIVILIANS RESCUED / WAITING / LOST" to
                result.civiliansRescued.toString() + " / " + result.civiliansWaiting + " / " + result.civiliansLost,
            "TOTAL FIRE INTENSITY" to result.fireIntensitySum.toString(),
        )
    }

    /**
     * `OP TICKS LEFT` cell of §15.2 item 3. `Vm.ticksLeft` is private, but the
     * engine prints the position itself in `Vm.lastOp` (`MOVE (1/2)` = tick 1 of
     * a 2-tick op, and `TelemetrySnapshot.executing` is `Vm.inProgress`), so the
     * cell is `(total - step)` read off the engine's own op text - never
     * re-derived from the ISA cost table. 0 whenever no op is in flight.
     */
    fun opTicksLeft(): Int {
        val t = session.telemetry()
        if (!t.executing) return 0
        val match = OP_TICK_POSITION_REGEX.find(t.lastOp) ?: return 0
        val step = match.groupValues[1].toInt()
        val total = match.groupValues[2].toInt()
        return (total - step).coerceAtLeast(0)
    }

    /** `VM FAULT` cell: the VM's own fault flag (`Vm.fault`, public). */
    fun vmFault(): Boolean = session.currentWorld.rover.vm.fault

    /** Newest-first event strings of the current tick (EVENT LOG head). */
    fun events(maxLines: Int = MissionSession.EVENT_TAIL): List<String> = session.currentEvents(maxLines)

    /** Newest-first per-tick log lines (§15.2 item 2 `EVENT LOG`). */
    fun logTail(count: Int = MissionSession.EVENT_TAIL): List<String> = session.fullLogNewestFirst().take(count)

    /** One STEP press: exactly one world tick, multi-tick ops included. */
    fun step(): StepOutcome = session.step()

    /**
     * RUN: STEP repeatedly until the world finishes or the mission tick guard is
     * reached, never past it. STEP is the authority (FIRELINE-DESIGN 13.2), so
     * RUN is defined as repeated STEP and reaches the same state the headless
     * runner does - asserted against `FirelineRun.runWithGuard` in `ConsoleModelTest`.
     */
    fun run(): StepOutcome {
        var outcome = StepOutcome.ALREADY_FINISHED
        while (!session.finished && !session.guardReached) {
            outcome = session.step()
            if (outcome == StepOutcome.GUARD_STOPPED) break
        }
        return outcome
    }

    /** RESET: fresh world from the same descriptor, log and guard cleared. */
    fun reset() {
        session.reset()
    }

    companion object {
        /** `(1/2)` tail of the engine's multi-tick op text (`Vm.lastOp`). */
        private val OP_TICK_POSITION_REGEX = Regex("\\((\\d+)/(\\d+)\\)$")

        /** `030 MOVE (1/2) | ` - shared golden-trace formatting rule. */
        fun logLine(seq: Int, text: String): String = ConsoleModel.logLine(seq, text)

        /** Same line from the engine's op text plus its event list. */
        fun logLine(seq: Int, op: String, events: List<String>): String = ConsoleModel.logLine(seq, op, events)
    }
}
