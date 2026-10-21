package robofight

import org.junit.jupiter.api.Test
import robofight.challenge.ChallengeCodeFileName

class ChallengeCodeFileNameTest {
    @Test
    fun normalizesVisibleExtension() {
        check(ChallengeCodeFileName.normalize("  My Duel  ") == "My Duel.chc")
        check(ChallengeCodeFileName.normalize("My Duel.CHC") == "My Duel.chc")
        check(ChallengeCodeFileName.normalize("a.chc.chc") == "a.chc.chc")
    }

    @Test
    fun rejectsInvalidFileNames() {
        check(ChallengeCodeFileName.normalize("") == null)
        check(ChallengeCodeFileName.normalize(".chc") == null)
        check(ChallengeCodeFileName.normalize("bad/name") == null)
        check(ChallengeCodeFileName.normalize("bad?.chc") == null)
        check(ChallengeCodeFileName.normalize("x".repeat(61)) == null)
    }

    @Test
    fun choosesNextDefaultCaseInsensitively() {
        check(ChallengeCodeFileName.nextDefault(listOf("CODE1.chc", "code2.CHC")) == "CODE3.chc")
    }

    @Test
    fun migratesLegacyNamesWithoutOverwriting() {
        check(ChallengeCodeFileName.migratedLegacyName("DUEL", emptyList()) == "DUEL.chc")
        check(
            ChallengeCodeFileName.migratedLegacyName("DUEL", listOf("duel.CHC", "DUEL_2.chc")) ==
                "DUEL_3.chc"
        )
    }
}
