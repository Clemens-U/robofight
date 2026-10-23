package robofight.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * JUnit wrapper so `:app:testProDebugUnitTest` (pro flavor test run) executes
 * the full Arena Pack 1 suite — registration gate, §7.2 seed-corpus sweep,
 * §12.3 fight matrix, end-to-end seeded ChallengeRun — inside Gradle.
 * The suite itself lives in [ProArenaPackTest.kt] (same flavor test source
 * set) and prints every check; this wrapper only asserts the fail count.
 * Compiled ONLY for pro variants: the free flavor has no pack to verify.
 */
class ProArenaPackSuiteTest {

    @Test
    fun arenaPack1SuiteIsGreen() {
        val (passed, failed) = runArenaPackSuite()
        assertEquals(0, failed, "Arena Pack 1 suite had $failed failing checks (of ${passed + failed})")
    }
}
