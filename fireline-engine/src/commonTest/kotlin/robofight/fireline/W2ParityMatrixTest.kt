package robofight.fireline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import robofight.assembler.assemble

/**
 * Gate G3 (docs/FIRELINE-WEB.md §4): the fingerprint matrix - every authored
 * mission x its reference firmware x its documented failing firmware, plus the
 * four E2 wall fixture layouts of `PLAN-EDGE-WALLS.md` §7 step E2 - must
 * reproduce the recorded tick, outcome, reason and fingerprint, and the runner
 * and step-by-step paths must agree, identically on `jvm` and `wasmJs`.
 *
 * The expectations live in [PARITY_FIXTURE_ROWS], generated from live engine
 * state by `docs/web/fixtures/ParityMatrix.kt`. Nothing here invents a number:
 * a mismatch is either an engine determinism defect or a container (wasm) defect.
 * Wall rows carry no authored mission, so [parityMissionOf] rebuilds them from
 * the pinned canonical layout text and the recorded rule columns.
 */
class W2ParityMatrixTest {

    /** Resolve the mission and firmware the row names, so a renamed variant fails loudly. */
    private fun missionOf(row: ParityFixtureRow): Mission = parityMissionOf(row)

    private fun sourceOf(row: ParityFixtureRow): String = paritySourceOf(row)

    private fun runOf(row: ParityFixtureRow): FirelineResult =
        FirelineRun.runWithGuard(missionOf(row), missionOf(row).descriptorForSource(sourceOf(row)))

    @Test
    fun everyMatrixRowReproducesItsRecordedOutcome() {
        assertEquals(
            PARITY_EXPECTED_ROWS, PARITY_FIXTURE_ROWS.size,
            "docs/web/fixtures/parity_matrix.tsv carries $PARITY_EXPECTED_MISSION_ROWS authored rows plus $PARITY_EXPECTED_WALL_ROWS E2 wall rows",
        )
        assertEquals(PARITY_EXPECTED_MISSION_ROWS, parityMissionRows().size, "authored mission rows (parity_matrix.tsv kind reference/failing)")
        assertEquals(PARITY_EXPECTED_WALL_ROWS, parityWallRows().size, "E2 wall fixture rows (parity_matrix.tsv kind wall/wall-open)")
        for (row in PARITY_FIXTURE_ROWS) {
            val result = runOf(row)
            assertEquals(row.finalTick, result.tick, "${row.scenario}: tick (parity_matrix.tsv row ${row.scenario})")
            assertEquals(RunStatus.valueOf(row.status), result.status, "${row.scenario}: status (parity_matrix.tsv row ${row.scenario})")
            assertEquals(
                row.failureReason.let { if (it == "-") null else it }?.let { FailureReason.valueOf(it) },
                result.failureReason,
                "${row.scenario}: failure reason (${result.render()})",
            )
            assertEquals(row.finalHash, result.finalStateHash, "${row.scenario}: final fingerprint (parity_matrix.tsv row ${row.scenario})")
            assertEquals(FIRELINE_ENGINE_VERSION, result.engineVersion, "${row.scenario}: engine version stamp")
        }
    }

    @Test
    fun everyMatrixRowAssemblesToTheRecordedImage() {
        for (row in PARITY_FIXTURE_ROWS) {
            val asm = assemble(sourceOf(row))
            assertTrue(asm.ok, "${row.scenario}: firmware must assemble: ${asm.errors.map { it.msg }}")
            assertEquals(row.asmBytes, asm.code.size, "${row.scenario}: program bytes (parity_matrix.tsv row ${row.scenario})")
            assertEquals(row.asmInsns, asm.code.size / 3, "${row.scenario}: instruction count (parity_matrix.tsv row ${row.scenario})")
        }
    }

    @Test
    fun runnerAndStepByStepAgreeOnEveryMatrixRow() {
        for (row in PARITY_FIXTURE_ROWS) {
            val m = missionOf(row)
            val descriptor = m.descriptorForSource(sourceOf(row))
            val stepped = FirelineRun.runStepByStep(descriptor, m.maxTicks)
            assertEquals(runOf(row), stepped, "${row.scenario}: runner vs step-by-step")
        }
    }

    @Test
    fun everyMatrixRowReproducesItsFixedTickProbeFingerprint() {
        for (row in PARITY_FIXTURE_ROWS) {
            val world = FirelineWorld(missionOf(row).descriptorForSource(sourceOf(row)))
            repeat(row.probeTick) { world.tickOnce() }
            assertEquals(row.probeTick, world.tick, "${row.scenario}: probe tick")
            assertEquals(row.probeHash, world.finalStateHash(), "${row.scenario}: probe fingerprint at tick ${row.probeTick} (parity_matrix.tsv row ${row.scenario})")
        }
    }

    /**
     * Recorded status must mean what the row's `kind` says it means. Authored
     * reference firmware succeeds and authored failing firmware fails; the three
     * `wall` layouts fail and the `wall-open` control of the same layout and
     * firmware succeeds (`PLAN-EDGE-WALLS.md` §12).
     */
    @Test
    fun failingRowsFailAndReferenceRowsSucceed() {
        for (row in PARITY_FIXTURE_ROWS) {
            val expected = when (row.kind) {
                "reference" -> RunStatus.SUCCESS
                "failing", "wall" -> RunStatus.FAILURE
                "wall-open" -> RunStatus.SUCCESS
                else -> error("unknown fixture kind ${row.kind} in parity_matrix.tsv row ${row.scenario}")
            }
            assertEquals(expected, RunStatus.valueOf(row.status), "${row.scenario}: recorded status kind (parity_matrix.tsv row ${row.scenario})")
            assertTrue(row.finalTick <= missionOf(row).maxTicks, "${row.scenario}: inside the guard")
        }
    }
}
