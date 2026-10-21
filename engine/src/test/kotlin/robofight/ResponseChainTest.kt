package robofight

import org.junit.jupiter.api.Test
import robofight.assembler.assemble
import robofight.challenge.ChallengeFormat
import robofight.challenge.ChallengeFormat.FightDescriptor
import robofight.challenge.ChallengeFormat.ParseResult
import robofight.challenge.ChallengeResponse
import robofight.challenge.ChallengeRun
import robofight.world.Arena
import robofight.world.Bot
import robofight.world.Presets
import robofight.world.SPAWN_SEPARATE

/**
 * Response chain (PLAN-CHALLENGE-CODES.md §4.2): A wins → response code →
 * B wins → second response code; every hop replays deterministically.
 *
 * Fixture (probe-verified 2026-10-20): MAZE / KO (mode 0) / SEPARATE /
 * seed 0x0BADF00D / HP 100 vs 100.
 *   hop 1: HUNTER (open A) kills pinned WALKER  — 835 ticks, end_hp 0
 *   hop 2: WALKER (open A) kills pinned HUNTER  — 862 ticks, end_hp 0
 *
 * The response builder is codec-level bookkeeping (no simulation), so this
 * test also pins the §8 remap: in the counter-challenge the PINNED slot (B)
 * is the recorded winner, with ticks/end_hp taken from the real fight.
 */
class ResponseChainTest {

    companion object {
        const val SEED = 0x0BADF00D
        const val HOP1_TICKS = 835
        const val HOP2_TICKS = 862

        val HUNTER_FW: ByteArray = assemble(Presets.HUNTER).code
        val WALKER_FW: ByteArray = assemble(Presets.WALKER).code

        /** Original challenge: A open, B = pinned WALKER, cold (no result). */
        val COLD: FightDescriptor = FightDescriptor(
            eng = ChallengeFormat.ENGINE_VERSION,
            layoutId = Arena.MAZE.id,
            slots = 0b01,
            seed = SEED,
            mode = 0,
            spawn = SPAWN_SEPARATE,
            hpA = 100,
            hpB = 100,
            result = null,
            fwA = ByteArray(0),
            fwB = WALKER_FW,
            srcA = null,
            srcB = Presets.WALKER,
        )

        val HUNTER_BOT = Bot("HUNTER", 'H', 2, 0, 0, 0, Presets.HUNTER)
        val WALKER_BOT = Bot("WALKER", 'W', 4, 0, 0, 0, Presets.WALKER)
    }

    /** A cold challenge round-trips through encode/parse, result still absent. */
    @Test
    fun coldChallengeRoundTrips() {
        val code = ChallengeFormat.encode(COLD)
        check(code.startsWith("RF1-")) { "code must start with RF1- (got $code)" }
        val res = ChallengeFormat.parse(code)
        check(res is ParseResult.Ok) { "parse failed: $res" }
        val d = res.desc
        check(d.slots == 0b01)
        check(d.layoutId == Arena.MAZE.id)
        check(d.seed == SEED)
        check(d.mode == 0 && d.spawn == SPAWN_SEPARATE)
        check(d.hpA == 100 && d.hpB == 100)
        check(d.result == null) { "cold code must carry no result" }
        check(d.fwA.isEmpty() && d.srcA == null)
        check(d.fwB.contentEquals(WALKER_FW))
        check(d.srcB == Presets.WALKER)
    }

    /** Hop 1 replays deterministically: open HUNTER beats pinned WALKER. */
    @Test
    fun hop1IsDeterministicPlayerWin() {
        val o1 = ChallengeRun.run(COLD, HUNTER_BOT, null)
        check(o1.winnerSlot == 0) { "hop1: HUNTER must win (winnerSlot=${o1.winnerSlot})" }
        check(o1.ticks == HOP1_TICKS) { "hop1 ticks ${o1.ticks} != $HOP1_TICKS" }
        check(o1.loserEndHp == 0)
        check(ChallengeResponse.isPlayerWin(COLD, o1))
        // replay — byte-identical outcome
        val o1b = ChallengeRun.run(COLD, HUNTER_BOT, null)
        check(o1b.winnerSlot == o1.winnerSlot && o1b.ticks == o1.ticks && o1b.loserEndHp == o1.loserEndHp)
    }

    /**
     * The response remaps the recorded result (pinned B = winner), pins the
     * winning firmware, and round-trips through the codec.
     */
    @Test
    fun responseRemapsResultAndRoundTrips() {
        val o1 = ChallengeRun.run(COLD, HUNTER_BOT, null)
        val resp = ChallengeResponse.build(COLD, HUNTER_FW, Presets.HUNTER, o1)

        check(resp.slots == 0b01) { "response must be canonical slots=0b01" }
        check(resp.layoutId == COLD.layoutId && resp.seed == COLD.seed)
        check(resp.mode == COLD.mode && resp.spawn == COLD.spawn)
        check(resp.hpA == COLD.hpA && resp.hpB == COLD.hpB) { "dials must travel unchanged" }
        check(resp.fwA.isEmpty() && resp.srcA == null) { "open slot A must be empty" }
        check(resp.fwB.contentEquals(HUNTER_FW)) { "pinned B must be the winning firmware" }
        check(resp.srcB == Presets.HUNTER) { "source embeds when ≤ 2048 B" }
        check(resp.result?.winner == ChallengeFormat.WIN_B) { "pinned slot must be the recorded winner" }
        check(resp.result?.ticks == HOP1_TICKS)
        check(resp.result?.endHp == 0)

        val code = ChallengeFormat.encode(resp)
        check(code.startsWith("RF1-"))
        val res = ChallengeFormat.parse(code)
        check(res is ParseResult.Ok) { "response code must parse: $res" }
        val d = res.desc
        check(d.slots == 0b01 && d.layoutId == Arena.MAZE.id && d.seed == SEED)
        check(d.result?.winner == ChallengeFormat.WIN_B)
        check(d.result?.ticks == HOP1_TICKS)
        check(d.fwB.contentEquals(HUNTER_FW))
        check(d.srcB == Presets.HUNTER)
    }

    /** Hop 2 replays deterministically: open WALKER beats the pinned HUNTER. */
    @Test
    fun hop2IsDeterministicPlayerWin() {
        val resp = ChallengeResponse.build(
            COLD, HUNTER_FW, Presets.HUNTER, ChallengeRun.run(COLD, HUNTER_BOT, null),
        )
        val o2 = ChallengeRun.run(resp, WALKER_BOT, null)
        check(o2.winnerSlot == 0) { "hop2: WALKER (open) must win (winnerSlot=${o2.winnerSlot})" }
        check(o2.ticks == HOP2_TICKS) { "hop2 ticks ${o2.ticks} != $HOP2_TICKS" }
        check(o2.loserEndHp == 0)
        check(ChallengeResponse.isPlayerWin(resp, o2))
        val o2b = ChallengeRun.run(resp, WALKER_BOT, null)
        check(o2b.winnerSlot == o2.winnerSlot && o2b.ticks == o2.ticks && o2b.loserEndHp == o2.loserEndHp)
    }

    /** The second response builds and encodes from the hop-2 win. */
    @Test
    fun secondResponseBuilds() {
        val resp1 = ChallengeResponse.build(
            COLD, HUNTER_FW, Presets.HUNTER, ChallengeRun.run(COLD, HUNTER_BOT, null),
        )
        val o2 = ChallengeRun.run(resp1, WALKER_BOT, null)
        val resp2 = ChallengeResponse.build(resp1, WALKER_FW, Presets.WALKER, o2)

        check(resp2.fwB.contentEquals(WALKER_FW)) { "second response pins the hop-2 winner" }
        check(resp2.srcB == Presets.WALKER)
        check(resp2.result?.winner == ChallengeFormat.WIN_B)
        check(resp2.result?.ticks == HOP2_TICKS)

        val code = ChallengeFormat.encode(resp2)
        val res = ChallengeFormat.parse(code)
        check(res is ParseResult.Ok) { "second response code must parse: $res" }
        check(res.desc.fwB.contentEquals(WALKER_FW))
    }

    /** §8: a loss or draw produces NO response. */
    @Test
    fun noResponseWithoutPlayerWin() {
        val o1 = ChallengeRun.run(COLD, HUNTER_BOT, null)
        // Simulated LOSS: the pinned side won.
        val loss = ChallengeRun.Outcome(winnerSlot = 1, ticks = 10, loserEndHp = 50)
        check(!ChallengeResponse.isPlayerWin(COLD, loss))
        check(!ChallengeResponse.isPlayerWin(COLD, ChallengeRun.Outcome(winnerSlot = 2, ticks = 10, loserEndHp = 0)))
        val ex = runCatching { ChallengeResponse.build(COLD, HUNTER_FW, Presets.HUNTER, loss) }
        check(ex.isFailure) { "loss must not generate a response" }
        check(o1.winnerSlot == 0) { "sanity: the real hop-1 outcome is the player win" }
    }
}

/**
 * Headless entry point (run-headless-tests.sh): runs every case in
 * [ResponseChainTest]. JUnit runs the same methods when a runner is available.
 */
fun main() {
    val t = ResponseChainTest()
    t.coldChallengeRoundTrips(); println("PASS coldChallengeRoundTrips")
    t.hop1IsDeterministicPlayerWin(); println("PASS hop1IsDeterministicPlayerWin")
    t.responseRemapsResultAndRoundTrips(); println("PASS responseRemapsResultAndRoundTrips")
    t.hop2IsDeterministicPlayerWin(); println("PASS hop2IsDeterministicPlayerWin")
    t.secondResponseBuilds(); println("PASS secondResponseBuilds")
    t.noResponseWithoutPlayerWin(); println("PASS noResponseWithoutPlayerWin")
    println("ResponseChainTest: 6/6 PASS")
}
