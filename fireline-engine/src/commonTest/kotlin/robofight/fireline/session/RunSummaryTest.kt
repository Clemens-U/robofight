package robofight.fireline.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `RunSummary.encode()` always ends with a newline. Android's preferences writer
 * pretty-prints such a value and appends the next element's indentation *inside*
 * the stored string, so the app reads back a trailing whitespace-only line after
 * `key=value` content. That padding is storage, not data: decode must survive it,
 * because the alternative (null) silently wipes the RESULT screen and every
 * mission best badge after a relaunch (seen on device in the post-W3.1 check).
 * Garbage still returns null.
 */
class RunSummaryTest {

    private val sample = runSummarySample()

    @Test
    fun preferencesIndentAfterTheTrailingNewlineStillDecodes() {
        val stored = sample.encode() + "    "
        val decoded = RunSummary.decode(stored)
        assertNotNull(decoded)
        assertEquals(sample, decoded)
    }

    @Test
    fun blankAndIndentedLinesBetweenRecordsAreSkipped() {
        val stored = sample.encode() + "\n   \n"
        assertEquals(sample, RunSummary.decode(stored))
    }

    @Test
    fun aLineThatIsNotKeyValueIsStillRejected() {
        assertNull(RunSummary.decode(sample.encode() + "best badge\n"))
        assertNull(RunSummary.decode("   status=SUCCESS"))
    }
}
