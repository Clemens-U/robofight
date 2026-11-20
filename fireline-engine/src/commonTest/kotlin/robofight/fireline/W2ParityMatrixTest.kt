package robofight.fireline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import robofight.assembler.assemble

/**
 * Gate G3 (docs/FIRELINE-WEB.md §4): the fingerprint matrix - every authored
 * mission x its reference firmware x its documented failing firmware - must
 * reproduce the recorded tick, outcome, reason and fingerprint, and the runner
 * and step-by-step paths must agree, identically on `jvm` and `wasmJs`.
 *
 * The expectations live in [PARITY_FIXTURE_ROWS], generated from live engine
 * state by `docs/web/fixtures/ParityMatrix.kt`. Nothing here invents a number:
 * a mismatch is either an engine determinism defect or a container (wasm) defect.
 */
class W2ParityMatrixTest {

    private fun missionOf(id: Int): Mission =
        assertNotNull(Missions.byId(id), "fixture names mission $id which does not exist")

    /** Resolve the firmware the row names, so a renamed variant fails loudly. */
    private fun sourceOf(row: ParityFixtureRow): String {
        val m = missionOf(row.missionId)
        if (row.kind == "reference") return m.referenceSource
        val found = m.failingSources.firstOrNull { it.first == row.firmwareLabel }
        assertNotNull(found, "mission ${m.id} does not carry a failing firmware labelled ${row.firmwareLabel}")
        return found.second
    }

    private fun runOf(row: ParityFixtureRow): FirelineResult {
        val m = missionOf(row.missionId)
        return FirelineRun.runWithGuard(m, m.descriptorForSource(sourceOf(row)))
    }

    @Test
    fun everyMatrixRowReproducesItsRecordedOutcome() {
        assertEquals(10, PARITY_FIXTURE_ROWS.size, "5 missions x (reference + failing) = 10 rows")
        for (row in PARITY_FIXTURE_ROWS) {
            val result = runOf(row)
            assertEquals(row.finalTick, result.tick, "${row.scenario}: tick")
            assertEquals(RunStatus.valueOf(row.status), result.status, "${row.scenario}: status")
            assertEquals(
                row.failureReason.let { if (it == "-") null else it }?.let { FailureReason.valueOf(it) },
                result.failureReason,
                "${row.scenario}: failure reason (${result.render()})",
            )
            assertEquals(row.finalHash, result.finalStateHash, "${row.scenario}: final fingerprint")
            assertEquals(FIRELINE_ENGINE_VERSION, result.engineVersion, "${row.scenario}: engine version stamp")
        }
    }

    @Test
    fun everyMatrixRowAssemblesToTheRecordedImage() {
        for (row in PARITY_FIXTURE_ROWS) {
            val asm = assemble(sourceOf(row))
            assertTrue(asm.ok, "${row.scenario}: firmware must assemble: ${asm.errors.map { it.msg }}")
            assertEquals(row.asmBytes, asm.code.size, "${row.scenario}: program bytes")
            assertEquals(row.asmInsns, asm.code.size / 3, "${row.scenario}: instruction count")
        }
    }

    @Test
    fun runnerAndStepByStepAgreeOnEveryMatrixRow() {
        for (row in PARITY_FIXTURE_ROWS) {
            val m = missionOf(row.missionId)
            val descriptor = m.descriptorForSource(sourceOf(row))
            val stepped = FirelineRun.runStepByStep(descriptor, m.maxTicks)
            assertEquals(runOf(row), stepped, "${row.scenario}: runner vs step-by-step")
        }
    }

    @Test
    fun everyMatrixRowReproducesItsFixedTickProbeFingerprint() {
        for (row in PARITY_FIXTURE_ROWS) {
            val world = FirelineWorld(missionOf(row.missionId).descriptorForSource(sourceOf(row)))
            repeat(row.probeTick) { world.tickOnce() }
            assertEquals(row.probeTick, world.tick, "${row.scenario}: probe tick")
            assertEquals(row.probeHash, world.finalStateHash(), "${row.scenario}: probe fingerprint at tick ${row.probeTick}")
        }
    }

    @Test
    fun failingRowsFailAndReferenceRowsSucceed() {
        for (row in PARITY_FIXTURE_ROWS) {
            val expected = if (row.kind == "reference") RunStatus.SUCCESS else RunStatus.FAILURE
            assertEquals(expected, RunStatus.valueOf(row.status), "${row.scenario}: recorded status kind")
            assertTrue(row.finalTick <= missionOf(row.missionId).maxTicks, "${row.scenario}: inside the guard")
        }
    }
}
