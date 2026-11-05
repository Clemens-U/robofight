package robofight

import robofight.assembler.assemble
import robofight.challenge.ChallengeFormat

/**
 * Phase 0 challenge-code fixture (PLAN-RF8-MULTIAPP.md §4).
 *
 * A small canonical `eng=1` challenge code saved verbatim as a migration
 * fixture. Baseline (engine version 1): it must parse Ok with NO warning,
 * because eng=1 is the current engine.
 *
 * After the approved shield change bumps ENGINE_VERSION to 2 (Phase 1), this
 * same frozen code becomes an old-engine import and must parse Ok with the
 * §10 warning `OLD ENGINE — REPLAY USES CURRENT RULES` (RF8-PLATFORM-SPEC.md
 * §6.1). That flip is the approved deterministic expectation change; any other
 * behavior change on this fixture is a regression.
 *
 * Fixture was produced by `ChallengeFormat.encode` with a pinned WAIT-loop
 * firmware (OPEN layout, CHALLENGE slots, KO mode, seed 0x1234, 100/100 HP).
 */

private const val LEGACY_ENG1_CODE =
    "RF1-AEAQ-AAIE-GQJA-AAAA-AACQ-AAAG-AC3A-AAEQ-AAAR-IADM-N5XX-AORA-K5AU-SVAK-JJGV-AIDM-N5XX-ACQP-CU"

private fun normalize(text: String): String = text.filter { it != '-' && !it.isWhitespace() }.uppercase()

fun main() {
    println("== eng=1 challenge code fixture ==")

    val r = ChallengeFormat.parse(LEGACY_ENG1_CODE)
    check("fixture parses Ok", r is ChallengeFormat.ParseResult.Ok, r.toString())
    if (r is ChallengeFormat.ParseResult.Ok) {
        check("fixture carries eng=1", r.desc.eng == 1, "eng=${r.desc.eng}")
        check("fixture playable (no lock)", r.lock == null, "lock=${r.lock}")
        check("fixture imports with the old-engine warning (eng=2 bump)",
            r.warning == ChallengeFormat.W_OLD_ENG, "warning=${r.warning}")
        val re = ChallengeFormat.parse(ChallengeFormat.encode(r.desc))
        check("fixture round-trips through encode",
            re is ChallengeFormat.ParseResult.Ok && normalize(ChallengeFormat.encode(r.desc)) == normalize(LEGACY_ENG1_CODE),
            "re=${re}")
    }

    // Codes this build emits carry this build's engine version, whatever it is.
    val loop = "loop: WAIT\nJMP loop\n"
    val fw = assemble(loop).code
    val fresh = ChallengeFormat.encode(
        ChallengeFormat.FightDescriptor(
            eng = ChallengeFormat.ENGINE_VERSION, layoutId = 0x00, slots = 0b01,
            seed = 0x1234, mode = 0, spawn = 0, hpA = 100, hpB = 100,
            result = null, fwA = ByteArray(0), fwB = fw, srcA = null, srcB = loop,
        )
    )
    val freshParsed = ChallengeFormat.parse(fresh)
    check("fresh code is stamped with the current ENGINE_VERSION",
        freshParsed is ChallengeFormat.ParseResult.Ok &&
            freshParsed.desc.eng == ChallengeFormat.ENGINE_VERSION && freshParsed.warning == null,
        "ENGINE_VERSION=${ChallengeFormat.ENGINE_VERSION} parsed=$freshParsed")
}
