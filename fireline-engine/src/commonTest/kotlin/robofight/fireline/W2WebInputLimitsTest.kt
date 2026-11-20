package robofight.fireline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate G6 (docs/FIRELINE-WEB.md §4): the limits a browser container must enforce
 * BEFORE it allocates anything - source size, source line count, and the tick
 * budget - plus the rule that an assembler refusal is reported instead of a run.
 *
 * The interesting case is not "too big for the assembler" (the assembler would
 * catch that too), it is "legal program, over the document ceiling": that has to
 * be refused by [WebInputLimits] before the assembler or a world is ever touched,
 * because the web path takes untrusted text from a textarea.
 */
class W2WebInputLimitsTest {

    private val mission = Missions.NOZZLE_CHECK

    @Test
    fun ceilingsMatchTheEngineContract() {
        assertEquals(1200, WebInputLimits.MAX_SOURCE_LINES)
        assertEquals(8192, WebInputLimits.MAX_SOURCE_BYTES)
        assertEquals(MAX_TICKS, WebInputLimits.MAX_REQUEST_TICKS, "the web cap is the engine cap")
        assertEquals(1_000_000, MAX_TICKS, "13.4 tick cap")
    }

    @Test
    fun referenceFirmwareIsAcceptedWithTheMissionGuardAsTickBudget() {
        val request = WebInputLimits.prepare(mission, mission.referenceSource)
        val accepted = assertNotNull(request.acceptedOrNull(), request.problems.joinToString("; "))
        assertEquals(mission.maxTicks, accepted.maxTicks)
        assertEquals(FIRELINE_ENGINE_VERSION, accepted.descriptor.engineVersion)
        assertEquals(mission.id, accepted.descriptor.missionId)
        assertEquals(sourceBytesOf(mission.referenceSource), accepted.sourceBytes)
        assertEquals(mission.referenceSource.split('\n').size, accepted.sourceLines)
        assertEquals(0, request.problems.size)
    }

    @Test
    fun oversizedButAssemblerLegalSourceIsRefusedBeforeAssembly() {
        // One legal instruction behind a comment long enough to break the byte
        // ceiling: the assembler would happily accept this program, the input
        // gate must refuse it before assembling or building a world.
        val source = ";" + "x".repeat(WebInputLimits.MAX_SOURCE_BYTES) + "\n       WAIT"
        val problems = WebInputLimits.sourceProblems(source)
        assertTrue(problems.any { it.contains("limit is ${WebInputLimits.MAX_SOURCE_BYTES}") },
            "byte ceiling not reported: $problems")
        val request = WebInputLimits.prepare(mission, source)
        assertNull(request.acceptedOrNull(), "oversized source must not reach the assembler: $problems")
        assertTrue(request is RunRequest.Rejected)
    }

    @Test
    fun tooManyLinesIsRefused() {
        val source = "       WAIT\n".repeat(WebInputLimits.MAX_SOURCE_LINES + 1)
        val request = WebInputLimits.prepare(mission, source)
        assertNull(request.acceptedOrNull())
        assertTrue(request.problems.any { it.contains("lines, limit is") }, request.problems.toString())
    }

    @Test
    fun emptyNonAsciiAndControlCharactersAreRefused() {
        assertTrue(WebInputLimits.sourceProblems("").isNotEmpty())
        assertTrue(WebInputLimits.sourceProblems("   \n  ").isNotEmpty(), "blank document is not a program")
        assertTrue(WebInputLimits.sourceProblems("       WÄIT").any { it.contains("non-ASCII") })
        assertTrue(WebInputLimits.sourceProblems("       WAIT\u0000").any { it.contains("control character") })
        assertTrue(WebInputLimits.sourceProblems("       WAIT\r\n").any { it.contains("carriage return") })
    }

    @Test
    fun tickBudgetIsCappedAtTheEngineLimitAndClampedToTheMissionGuard() {
        assertEquals(
            listOf("requested tick budget ${MAX_TICKS + 1} exceeds the engine cap $MAX_TICKS"),
            WebInputLimits.tickProblems(MAX_TICKS + 1),
        )
        assertTrue(WebInputLimits.tickProblems(0).isNotEmpty())
        assertTrue(WebInputLimits.tickProblems(-5).isNotEmpty())

        val smaller = assertNotNull(WebInputLimits.prepare(mission, mission.referenceSource, 50).acceptedOrNull())
        assertEquals(50, smaller.maxTicks, "a caller may ask for fewer ticks")

        val bigger = assertNotNull(WebInputLimits.prepare(mission, mission.referenceSource, 900_000).acceptedOrNull())
        assertEquals(mission.maxTicks, bigger.maxTicks, "the mission guard stays the effective budget")
    }

    @Test
    fun rejectedTickBudgetRefusesBeforeAssembly() {
        val request = WebInputLimits.prepare(mission, mission.referenceSource, MAX_TICKS + 1)
        assertNull(request.acceptedOrNull())
        assertTrue(request.problems.any { it.contains("engine cap") }, request.problems.toString())
    }

    @Test
    fun assemblerErrorsBecomeProblemsInsteadOfARun() {
        val request = WebInputLimits.prepare(mission, "       FROBNICATE   ZORK")
        assertNull(request.acceptedOrNull())
        assertTrue(request.problems.any { it.startsWith("line 1:") }, request.problems.toString())
    }

    @Test
    fun recordedLimitProbeMatchesThisTargetsBehavior() {
        // The recorded docs-side probe (limit_probe.tsv) and the live gate must agree.
        val recorded = assertNotNull(PARITY_LIMIT_PROBE["prepare_oversized"])
        val live = WebInputLimits.prepare(mission, "       WAIT\n".repeat(WebInputLimits.MAX_SOURCE_LINES + 5))
        assertTrue(recorded.contains("rejected before assembly"), recorded)
        assertNull(live.acceptedOrNull(), "live gate accepted what the recorded probe rejected")
        val legal = assertNotNull(PARITY_LIMIT_PROBE["prepare_legal"])
        assertTrue(legal.contains("accepted maxTicks=${mission.maxTicks}"), legal)
        assertNotNull(WebInputLimits.prepare(mission, mission.referenceSource).acceptedOrNull())
    }

    private fun sourceBytesOf(source: String): Int = source.encodeToByteArray().size
}
