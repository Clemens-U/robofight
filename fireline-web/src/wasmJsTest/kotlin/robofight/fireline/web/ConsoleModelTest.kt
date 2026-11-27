package robofight.fireline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import robofight.fireline.FailureReason
import robofight.fireline.FirelineRun
import robofight.fireline.Missions
import robofight.fireline.RunStatus
import robofight.fireline.session.StepOutcome

/**
 * Headless gate for the `:fireline-web` console data layer: every expected value
 * below is read from the committed fixtures under `docs/web/fixtures/` and
 * compared against a live `MissionSession` run inside wasmJs. Fixture provenance
 * is named on each assertion.
 *
 * Sources of truth (committed on this branch):
 *  - `parity_matrix.json` / `parity_matrix.tsv` - 16 rows: 12 authored mission
 *    rows (6 missions x reference + failing firmware) plus the four E2 wall
 *    fixture rows of `PLAN-EDGE-WALLS.md` §7 step E2, which no authored mission
 *    ships and which `:fireline-engine` asserts instead of this console gate.
 *    Used here for the mission rows: final_tick, status,
 *    failure_reason, final_hash, asm_bytes, asm_insns.
 *  - `parity_traces.json` / `parity_traces.txt` - golden trace lines 001..012
 *    per scenario (probe tick 12), shape `NNN <op> | <events>`.
 *  - `snapshots.json` - 8 live-engine snapshots with `vm.registers`, `vm.pc`,
 *    `vm.pc_hex`, `vm.last_op`, `ports`, `stats`, `fingerprint`, `time_limit`,
 *    `tick_limit`, `seed`, `wind_dir`, `wind_force`, `objectives_readable`.
 *    Labels used here: `02-ref-t12`, `02-ref-t46`, `02-ref-final`,
 *    `01-never-t30`, `01-never-final`.
 *  - `docs/WEB-W1-RECORD.md` §9 - the on-device STEP x 30 readout for mission 02
 *    (`TICK 30 / 120`, `A 6`, `PC $12D`, `last: MOVE (1/2)`, `FIRE_DIST 6`,
 *    `FIRE_LEVEL 55`), the committed twin of the tick-30 console state.
 */
class ConsoleModelTest {

    private val model = ConsoleModel()

    private fun driveToFinish(run: ConsoleRun) {
        var guard = 0
        while (!run.finished && !run.guardReached && guard < 100_000) {
            run.step()
            guard++
        }
    }

    /**
     * Fixture: `parity_matrix.json` rows `1-reference`, `2-reference`,
     * `3-reference`, `4-reference`, `5-reference`, `6-reference` (final_tick /
     * status / final_hash / asm_bytes / asm_insns), stamped `# engine v4` in
     * `parity_matrix.tsv`. The picker must show the engine-v4 best run for all
     * six authored missions (D3: every mission runs on the shipped engine).
     */
    @Test
    fun pickerRowsCarryTheCommittedEngineV4BestRunForAllSixMissions() {
        val rows = model.pickerRows()
        assertEquals(6, rows.size, "authored mission count (Missions.ALL)")
        assertEquals(listOf(1, 2, 3, 4, 5, 6), rows.map { it.id }, "picker order")

        // scenario      final_tick status   final_hash(8) bytes insns
        val expected = listOf(
            Triple(17, "aa3d630d", 102 to 34),   // 1-reference  aa3d630d5d30d9ce
            Triple(102, "5cd373d7", 75 to 25),  // 2-reference  5cd373d7f4a15c81
            Triple(38, "a62d2c59", 72 to 24),   // 3-reference  a62d2c5976fa1887
            Triple(147, "40990811", 105 to 35), // 4-reference  40990811d10e1466
            Triple(45, "e6b157b1", 111 to 37), // 5-reference  e6b157b12efba137
            Triple(49, "3a958e35", 87 to 29), // 6-reference  3a958e3503fe0741
        )
        rows.forEachIndexed { index, row ->
            val (tick, hashShort, firmware) = expected[index]
            assertEquals(RunStatus.SUCCESS, row.bestRun.status, "row ${row.id} status (parity_matrix ${row.id}-reference)")
            assertEquals(tick, row.bestRun.tick, "row ${row.id} final_tick (parity_matrix ${row.id}-reference)")
            assertEquals("$" + hashShort, row.bestRun.fingerprintHex, "row ${row.id} final_hash (parity_matrix ${row.id}-reference)")
            assertEquals(firmware.first, row.bestRun.firmwareBytes, "row ${row.id} asm_bytes")
            assertEquals(firmware.second, row.bestRun.firmwareInstructions, "row ${row.id} asm_insns")
            assertEquals(4, row.bestRun.engineVersion, "row ${row.id} engine version (parity_matrix.tsv '# engine v4')")
        }
    }

    /**
     * Fixture: `snapshots.json` `02-ref-t12` / `02-ref-final` (seed 2002,
     * time_limit 120, tick_limit 300, wind_dir W, wind_force 0, rover.water 100,
     * objectives_readable [EXTINGUISH_ALL, REACH_EXIT]) and `01-never-*`
     * (seed 1001, time_limit 60, tick_limit 200).
     */
    @Test
    fun pickerRowsCarryAuthoredMissionHeaderFacts() {
        val m2 = model.row(2)!!
        assertEquals("MISSION 02 / CORRIDOR", m2.missionLabel, "mission label")
        assertEquals("CORRIDOR", m2.title, "title (parity_matrix 2-reference name)")
        assertEquals(listOf("EXTINGUISH_ALL", "REACH_EXIT"), m2.objectiveLabels, "objectives (snapshots 02-ref-t12 objectives_readable)")
        assertEquals(2002, m2.seed, "seed (snapshots 02-ref-t12 seed)")
        assertEquals(120, m2.timeLimit!!, "time_limit (snapshots 02-ref-t12)")
        assertEquals(300, m2.tickLimit, "tick_limit (snapshots 02-ref-t12)")
        assertEquals("W", m2.windLabel, "wind_dir (snapshots 02-ref-t12)")
        assertEquals(0, m2.windForce, "wind_force (snapshots 02-ref-t12)")
        assertEquals(100, m2.startingWater, "rover.water (snapshots 02-ref-t12)")

        val m1 = model.row(1)!!
        assertEquals("MISSION 01 / NOZZLE CHECK", m1.missionLabel, "mission label")
        assertEquals(listOf("EXTINGUISH_ALL"), m1.objectiveLabels, "objectives (snapshots 01-never-t30 objectives_readable)")
        assertEquals(1001, m1.seed, "seed (snapshots 01-never-t30 seed)")
        assertEquals(60, m1.timeLimit!!, "time_limit (snapshots 01-never-t30)")
        assertEquals(200, m1.tickLimit, "tick_limit (snapshots 01-never-t30)")

        val m6 = model.row(6)!!
        assertEquals("MISSION 06 / GO AROUND", m6.missionLabel, "mission label")
        assertEquals("GO AROUND", m6.title, "title (parity_matrix 6-reference name)")
        assertEquals(listOf("EXTINGUISH_ALL", "REACH_EXIT"), m6.objectiveLabels,
            "objectives_readable (parity_matrix 6-reference objectives 5)")
        assertEquals(6027, m6.seed, "seed (parity_matrix 6-reference seed)")
        assertEquals(90, m6.timeLimit!!, "time_limit (parity_matrix 6-reference time_limit)")
        assertEquals(300, m6.tickLimit, "max_ticks (parity_matrix 6-reference max_ticks)")
        assertEquals("W", m6.windLabel, "wind_dir (parity_matrix 6-reference wind_dir 0 = DIR_W)")
        assertEquals(0, m6.windForce, "wind_force (parity_matrix 6-reference wind_force)")
        assertEquals(100, m6.startingWater, "starting_water (parity_matrix 6-reference starting_water)")

        assertNull(model.row(7), "an id that is not authored has no card")
    }

    /**
     * Fixture: `snapshots.json` `02-ref-final` and `parity_matrix.json`
     * `2-reference`: tick 102, SUCCESS, `vm.last_op = MOVE (2/2)`,
     * `ports.FIRE_DIST = 0`, fingerprint `5cd373d7f4a15c81`, 75 bytes / 25
     * instructions. Driven through the console RUN (repeated STEP).
     */
    @Test
    fun mission02ReferenceRunFinishesAtTick102WithTheCommittedAnchor() {
        val run = model.openMission(2)
        assertEquals(StepOutcome.FINISHED, run.run(), "RUN ends FINISHED")
        assertEquals(102, run.tick, "final_tick (parity_matrix 2-reference)")
        assertEquals(RunStatus.SUCCESS, run.status(), "status (snapshots 02-ref-final status)")
        assertTrue(run.tick < run.tickLimit, "the mission finishes on its objectives, before the tick guard")
        assertEquals("MOVE (2/2)", run.lastEvent(), "last_op (snapshots 02-ref-final vm.last_op)")
        assertEquals("5cd373d7f4a15c81", run.fingerprint(), "fingerprint (snapshots 02-ref-final)")
        assertEquals("\$5cd373d7", run.fingerprintHex(), "short fingerprint on the status line")
        assertEquals(0, run.telemetry().fireDist, "FIRE_DIST at the finish (snapshots 02-ref-final ports)")
        assertEquals(0, run.telemetry().fireLevel, "FIRE_LEVEL at the finish (snapshots 02-ref-final ports)")
        assertEquals(75, run.firmwareBytes(), "firmware_bytes (snapshots 02-ref-final)")
        assertEquals(25, run.firmwareInstructions(), "instruction_count (snapshots 02-ref-final vm)")
        assertEquals(listOf("MISSION SUCCESS"), run.events(1), "last tick events (snapshots 02-ref-final recent_events)")
        assertEquals("102 MOVE (2/2) | MISSION SUCCESS", run.logTail(1).first(), "golden-trace tail")
        assertTrue(run.finished)
        assertFalse(run.guardReached, "102 < tick_limit 300: no guard stop")
    }

    /**
     * Fixture: `snapshots.json` `01-never-final` and `parity_matrix.json`
     * `1-failing` (`never shoots`): tick 60 = time_limit, FAILURE /
     * TIME_EXPIRED, `vm.last_op = JMP 256`, fingerprint `82a952554d427d7a`,
     * 6 bytes / 2 instructions.
     */
    @Test
    fun mission01NeverShootsFailsWithTimeExpiredAtTick60() {
        assertEquals(listOf("never shoots"), model.firmwareLabels(1), "authored failing firmware labels")
        val run = model.openFailingFirmware(1, "never shoots")
        assertEquals(StepOutcome.FINISHED, run.run(), "the world ends on the time limit, not the guard")
        assertEquals(60, run.tick, "final_tick (parity_matrix 1-failing)")
        assertEquals(RunStatus.FAILURE, run.status(), "status (snapshots 01-never-final)")
        assertEquals(FailureReason.TIME_EXPIRED, run.failureReason(), "failure_reason (snapshots 01-never-final)")
        assertEquals("JMP 256", run.lastEvent(), "last_op (snapshots 01-never-final vm.last_op)")
        assertEquals("82a952554d427d7a", run.fingerprint(), "fingerprint (snapshots 01-never-final)")
        assertEquals("\$82a95255", run.fingerprintHex(), "short fingerprint on the status line")
        assertEquals(6, run.firmwareBytes(), "firmware_bytes (snapshots 01-never-final)")
        assertEquals(2, run.firmwareInstructions(), "instruction_count (snapshots 01-never-final vm)")
        assertEquals(0, run.telemetry().a, "vm A (snapshots 01-never-final vm.registers.a)")
        assertEquals("$0BF", run.telemetry().spHex, "vm SP (snapshots 01-never-final vm.registers.sp = 191)")
        assertEquals("$100", run.telemetry().pcHex, "vm PC (snapshots 01-never-final vm.pc_hex)")
        assertEquals(listOf("MISSION FAILURE TIME_EXPIRED"), run.events(1), "last tick events (snapshots 01-never-final recent_events)")
    }

    /**
     * Mid-run stop at tick 30 on mission 02 - the console twin of the committed
     * on-device STEP x 30 readout (`docs/WEB-W1-RECORD.md` §9: `TICK 30 / 120`,
     * `A 6`, `PC $12D`, `last: MOVE (1/2)`, `FIRE_DIST 6`, `FIRE_LEVEL 55`), and
     * of the `parity_traces.txt` `2-reference` alternation
     * (`002 MOVE (1/2)`, `003 MOVE (2/2)`, ...) that the trace fixtures pin up to
     * the probe tick 12.
     */
    @Test
    fun mission02Tick30StopMatchesTheCommittedStepByStepState() {
        val run = model.openMission(2)
        repeat(30) { assertEquals(StepOutcome.ADVANCED, run.step(), "step to tick 30") }
        val t = run.telemetry()
        assertEquals(30, run.tick, "tick 30")
        assertNull(run.status(), "still running at tick 30")
        assertEquals("MOVE (1/2)", run.lastEvent(), "last_op at tick 30 (WEB-W1-RECORD §9 STEP x 30)")
        assertEquals(6, t.a, "vm A at tick 30 (WEB-W1-RECORD §9 STEP x 30: A 6)")
        assertEquals(191, parseHex(t.spHex), "vm SP at tick 30 = $0BF (fixture SP is 191 at 02-ref-t12/t46/final)")
        assertEquals(301, parseHex(t.pcHex), "vm PC at tick 30 = $12D (WEB-W1-RECORD §9 STEP x 30)")
        assertEquals(6, t.fireDist, "FIRE_DIST at tick 30 (WEB-W1-RECORD §9 STEP x 30)")
        assertEquals(55, t.fireLevel, "FIRE_LEVEL at tick 30 (WEB-W1-RECORD §9 STEP x 30)")
        assertEquals("030 MOVE (1/2) | ", ConsoleModel.logLine(30, "MOVE (1/2) | "), "log line shape")
        assertEquals("030 MOVE (1/2) | ", ConsoleModel.logLine(30, run.lastEvent(), run.events()), "log line from engine op + events")
        assertEquals("030 MOVE (1/2) | ", run.logTail(1).first(), "the engine's own tick-30 log line")
        assertEquals("\$7776f745", run.fingerprintHex(), "mid-run fingerprint is live engine output, not a fixture")
        assertEquals("exec: MOVE   @line 0015", run.nextEventPreview(), "multi-tick MOVE still in flight at tick 30")
    }

    /**
     * Fixture: `snapshots.json` `02-ref-t46` - tick 46, `vm.last_op = JMP 286`,
     * `a = 4`, `sp = 191` (`$0BF`), `pc = 286` (`$11E`),
     * `ports.FIRE_DIST = 3`, `ports.FIRE_LEVEL = 70`,
     * fingerprint `9b6e65741beac0ea`. This is the fixture-pinned mid-run register
     * state (the fixtures pin ticks 12/46/final, not 30).
     */
    @Test
    fun mission02Tick46StopMatchesFixtureRegisters() {
        val run = model.openMission(2)
        repeat(46) { run.step() }
        val t = run.telemetry()
        assertEquals(46, run.tick, "tick 46")
        assertEquals("JMP 286", run.lastEvent(), "last_op (snapshots 02-ref-t46 vm.last_op)")
        assertEquals(4, t.a, "vm A (snapshots 02-ref-t46 vm.registers.a)")
        assertEquals(191, parseHex(t.spHex), "vm SP (snapshots 02-ref-t46 vm.registers.sp)")
        assertEquals(286, parseHex(t.pcHex), "vm PC (snapshots 02-ref-t46 vm.pc)")
        assertEquals("$11E", t.pcHex, "vm PC hex (snapshots 02-ref-t46 vm.pc_hex)")
        assertEquals(3, t.fireDist, "FIRE_DIST (snapshots 02-ref-t46 ports)")
        assertEquals(70, t.fireLevel, "FIRE_LEVEL (snapshots 02-ref-t46 ports)")
        assertEquals("5409bb0e6a3f057a", run.fingerprint(), "fingerprint (snapshots 02-ref-t46)")

        // `02-ref-t12` / `01-never-t30` fingerprints pin the same run mid-way.
        val t12 = model.openMission(2).apply { repeat(12) { step() } }
        assertEquals("698f7ba3f6e52462", t12.fingerprint(), "fingerprint (snapshots 02-ref-t12)")
        assertEquals("MOVE (1/2)", t12.lastEvent(), "last_op (snapshots 02-ref-t12, parity_traces 2-reference 012)")
        val never30 = model.openFailingFirmware(1, "never shoots").apply { repeat(30) { step() } }
        assertEquals("b35796f9ce76dae6", never30.fingerprint(), "fingerprint (snapshots 01-never-t30)")
        assertEquals("JMP 256", never30.lastEvent(), "last_op (snapshots 01-never-t30)")
        assertEquals(3, never30.telemetry().fireDist, "FIRE_DIST (snapshots 01-never-t30 ports)")
        assertEquals(80, never30.telemetry().fireLevel, "FIRE_LEVEL (snapshots 01-never-t30 ports)")
    }

    /**
     * Gate G1 shape: the console's RUN (repeated STEP on the shared
     * `MissionSession`) equals the headless authority `FirelineRun.runWithGuard`
     * for every authored mission and every documented failing firmware, and the
     * failure reasons match the authored expectations.
     */
    @Test
    fun consoleRunEqualsHeadlessAuthorityForEveryAuthoredFirmware() {
        for (mission in Missions.ALL) {
            val reference = model.openMission(mission.id)
            reference.run()
            assertEquals(
                FirelineRun.runWithGuard(mission, mission.descriptorForSource(mission.referenceSource)),
                reference.snapshot(),
                "mission ${mission.id} reference RUN == runWithGuard",
            )
            for ((label, source, reason) in mission.failingSources) {
                val failing = model.openFailingFirmware(mission.id, label)
                failing.run()
                assertEquals(
                    FirelineRun.runWithGuard(mission, mission.descriptorForSource(source)),
                    failing.snapshot(),
                    "mission ${mission.id} '$label' RUN == runWithGuard",
                )
                assertEquals(reason, failing.failureReason(), "mission ${mission.id} '$label' authored reason")
            }
        }
    }

    /** `RESET` rebuilds from the same descriptor: the replay ends at the same anchor. */
    @Test
    fun resetReplaysToTheSameCommittedAnchor() {
        val run = model.openMission(2)
        repeat(30) { run.step() }
        run.reset()
        assertEquals(0, run.tick, "tick after RESET")
        assertEquals("", run.logTail(1).joinToString(""), "log cleared by RESET")
        run.run()
        assertEquals(102, run.tick, "replayed final_tick (parity_matrix 2-reference)")
        assertEquals("\$5cd373d7", run.fingerprintHex(), "replayed fingerprint")
    }

    /** Owner rule 2026-11-25: any firmware loads into any mission. `draftFirmware` checks the
     *  source through `WebInputLimits` and the real assembler and nothing mission-local, so every
     *  authored firmware must open in every authored mission (mission 06's thin-wall program in
     *  mission 02 and mission 02's corridor program in mission 06 both included). */
    @Test
    fun everyAuthoredFirmwareDraftsIntoEveryAuthoredMission() {
        for (sourceMission in Missions.ALL) {
            for (targetMission in Missions.ALL) {
                val draft = model.draftFirmware(
                    targetMission.id,
                    sourceMission.id.toString(),
                    sourceMission.referenceSource,
                )
                assertTrue(
                    draft is ConsoleModel.DraftResult.Accepted,
                    "mission ${sourceMission.id} firmware refused on mission ${targetMission.id}: " +
                        if (draft is ConsoleModel.DraftResult.Rejected) draft.problems.joinToString(" | ") else "rejected",
                )
                val run = (draft as ConsoleModel.DraftResult.Accepted).run
                assertEquals(targetMission.id, run.missionId, "draft opened in the wrong mission")
                assertEquals(sourceMission.referenceSource, run.session.firmwareSource, "source altered")
            }
        }
    }

    /** `$0BF`-style telemetry hex -> decimal, so assertions can name fixture decimals. */
    private fun parseHex(value: String): Int {
        val digits = value.removePrefix("$").removePrefix("0x")
        var result = 0
        for (ch in digits) {
            val d = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'f' -> ch - 'a' + 10
                in 'A'..'F' -> ch - 'A' + 10
                else -> throw IllegalArgumentException("not a hex digit: $ch in $value")
            }
            result = result * 16 + d
        }
        return result
    }
}
