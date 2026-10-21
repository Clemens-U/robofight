package robofight.challenge

import robofight.challenge.ChallengeFormat.FightDescriptor
import robofight.challenge.ChallengeFormat.Result

/**
 * Response flow (CHALLENGE-CODES.md §8): winning a CHALLENGE code generates
 * the counter-challenge. This is pure codec-level bookkeeping — it only
 * constructs descriptors, it never touches fight simulation, so it cannot
 * change deterministic behavior (no `eng` bump).
 *
 * Canonical orientation: the human player sits in the OPEN slot (A for
 * 0b01, B for the mirrored 0b10). A win (KO, or timed with strictly higher
 * HP) unlocks the response; a DRAW or loss does not (§8: "the chain waits").
 *
 * The response always comes out canonical (`slots = 0b01`: A open, B
 * pinned) with B = the player's winning firmware (binary, plus the source
 * when ≤ 2048 B) and `result` = the recorded outcome REMAPPED so the
 * PINNED slot (B) is the winner in the new orientation, taking `ticks` and
 * the loser's `end_hp` from the actual fight.
 */
object ChallengeResponse {

    /**
     * The slot the human player occupies in a CHALLENGE code, or null when
     * [desc] is not a challenge (REPLAY / OPEN DUEL — both or neither slot
     * open, no single player).
     */
    fun playerSlot(desc: FightDescriptor): Int? = when (desc.slots) {
        0b01 -> 0
        0b10 -> 1
        else -> null
    }

    /**
     * True iff [outcome] is a player WIN in this challenge: the winner is
     * the player's own slot (KO, or the unique HP leader at the time
     * limit). DRAW ([ChallengeRun.Outcome.winnerSlot] == 2) and loss are
     * false — no response is generated.
     */
    fun isPlayerWin(desc: FightDescriptor, outcome: ChallengeRun.Outcome): Boolean {
        val slot = playerSlot(desc) ?: return false
        return outcome.winnerSlot == slot
    }

    /**
     * Build the response code (§8 step 3).
     *
     * Same `layout`, `seed`, `mode`, `spawn`, `hp` (slot values unchanged —
     * the taunt travels: the winner becomes the pinned bot), `slots = 0b01`,
     * B = [userFw] (the player's winning firmware image) with [userSrc]
     * embedded when it fits the 2048-byte display cap, and the remapped
     * recorded result.
     *
     * @param userFw the canonical firmware image that WON (assembler output,
     *   1..768 bytes, 3 per instruction).
     * @param userSrc the firmware source, or null for binary-only; sources
     *   over 2048 UTF-8 bytes are dropped (the binary stays).
     * @throws IllegalArgumentException when [desc] is not a CHALLENGE or
     *   [outcome] is not a player win (draw/loss → no response, §8).
     */
    fun build(
        desc: FightDescriptor,
        userFw: ByteArray,
        userSrc: String?,
        outcome: ChallengeRun.Outcome,
    ): FightDescriptor {
        val slot = playerSlot(desc)
        require(slot != null) { "response is only for CHALLENGE codes (slots=${desc.slots})" }
        require(outcome.winnerSlot == slot) {
            "no response without a player win (winnerSlot=${outcome.winnerSlot}, playerSlot=$slot)"
        }
        require(userFw.size in 1..ChallengeFormat.MAX_FW) {
            "pinned firmware must be 1..${ChallengeFormat.MAX_FW} bytes (got ${userFw.size})"
        }
        return desc.copy(
            slots = 0b01,
            eng = ChallengeFormat.ENGINE_VERSION,
            result = ChallengeFormat.Result(
                winner = ChallengeFormat.WIN_B,
                ticks = outcome.ticks,
                endHp = outcome.loserEndHp,
            ),
            fwA = ByteArray(0),
            srcA = null,
            fwB = userFw,
            srcB = userSrc?.takeIf { it.toByteArray().size <= ChallengeFormat.MAX_SRC },
        )
    }
}
