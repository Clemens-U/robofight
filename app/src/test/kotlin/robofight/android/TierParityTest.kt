package robofight.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import robofight.challenge.ChallengeFormat

/**
 * Variant parity gate (PLAN-PRO §6 CI matrix): runs under BOTH
 * testFreeDebugUnitTest and testProDebugUnitTest from the same source,
 * asserting (1) the wire-format ENGINE_VERSION is identical (== 1) in every
 * variant, and (2) the compile-time tier gate: BuildConfig.PRO_BUILD is true
 * exactly for the pro flavor, false for free. The FLAVOR constant is
 * generated per variant by the product-flavor setup itself.
 */
class TierParityTest {

    @Test
    fun engineVersionIsOneInEveryVariant() {
        assertEquals(1, ChallengeFormat.ENGINE_VERSION)
    }

    @Test
    fun proBuildFlagMatchesFlavor() {
        val expected = BuildConfig.FLAVOR == "pro"
        assertEquals(expected, BuildConfig.PRO_BUILD, "PRO_BUILD must match flavor '${BuildConfig.FLAVOR}'")
    }

    @Test
    fun applicationIdMatchesFlavor() {
        val expected = if (BuildConfig.FLAVOR == "pro") "robofight.android.pro" else "robofight.android"
        assertEquals(expected, BuildConfig.APPLICATION_ID)
    }
}
