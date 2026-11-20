package robofight.fireline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import robofight.isa.decPad

/**
 * Gate G2 (docs/FIRELINE-WEB.md §4): golden event traces, FIRELINE-DESIGN.md
 * §13.2 item 4. The first 12 ticks of every matrix scenario - tick number, the
 * engine's own `lastOp` rendering and its `lastEvents` - are pinned verbatim,
 * so a rendering or tick-order change is caught the same way on `jvm` and
 * `wasmJs`.
 *
 * The pinned traces come from `FirelineRun.eventTrace` as recorded by
 * `docs/web/fixtures/ParityMatrix.kt` (see [PARITY_GOLDEN_TRACES]);
 * `parity_traces.txt` in the docs fixtures is the same text for humans.
 */
class W2GoldenTraceTest {

    /** FNV-1a 64 over a string, with the engine's own FNV parameters: the same
     *  fingerprint family `FirelineWorld.finalStateHash()` uses, re-implemented
     *  here so the test itself exercises 64-bit wrap arithmetic. */
    private fun fnvHex(text: String): String {
        var h = FNV_OFFSET_BASIS
        for (c in text) h = (h xor c.code.toLong()) * FNV_PRIME
        return h.toULong().toString(16).padStart(16, '0')
    }

    private fun sourceOf(row: ParityFixtureRow): String {
        val m = assertNotNull(Missions.byId(row.missionId), "fixture mission ${row.missionId} missing")
        if (row.kind == "reference") return m.referenceSource
        val found = m.failingSources.firstOrNull { it.first == row.firmwareLabel }
        return assertNotNull(found, "failing firmware ${row.firmwareLabel} missing on mission ${m.id}").second
    }

    @Test
    fun goldenTraceMatchesForEveryScenario() {
        assertEquals(PARITY_FIXTURE_ROWS.size, PARITY_GOLDEN_TRACES.size, "one trace per matrix row")
        for (row in PARITY_FIXTURE_ROWS) {
            val m = assertNotNull(Missions.byId(row.missionId))
            val trace = FirelineRun.eventTrace(m.descriptorForSource(sourceOf(row)), row.probeTick)
            val pinned = assertNotNull(PARITY_GOLDEN_TRACES[row.scenario], "no pinned trace for ${row.scenario}")
            assertEquals(pinned.size, trace.size, "${row.scenario}: trace length")
            for (i in pinned.indices) {
                assertEquals(pinned[i], trace[i], "${row.scenario}: trace line ${i + 1}")
            }
        }
    }

    @Test
    fun traceHashMatchesForEveryScenario() {
        for (row in PARITY_FIXTURE_ROWS) {
            val pinned = assertNotNull(PARITY_GOLDEN_TRACES[row.scenario])
            assertEquals(row.traceHash, fnvHex(pinned.joinToString("\n")), "${row.scenario}: pinned trace hash")
            val m = assertNotNull(Missions.byId(row.missionId))
            val live = FirelineRun.eventTrace(m.descriptorForSource(sourceOf(row)), row.probeTick)
            assertEquals(row.traceHash, fnvHex(live.joinToString("\n")), "${row.scenario}: live trace hash")
        }
    }

    @Test
    fun traceLinesCarryTickAndEngineOpRendering() {
        // House format (FirelineRun.eventTrace): "<tick padded 3> <lastOp> | <events joined>".
        for ((scenario, trace) in PARITY_GOLDEN_TRACES) {
            for ((index, line) in trace.withIndex()) {
                assertEquals(decPad(index + 1, 3), line.substring(0, 3), "$scenario: line ${index + 1} tick prefix")
                assertEquals(' ', line[3], "$scenario: line ${index + 1} separator after tick")
                assertTrue(line.contains(" | "), "$scenario: line ${index + 1} lacks the op/event separator")
            }
        }
    }
}
