package robofight

import robofight.assembler.assemble
import robofight.challenge.ChallengeFormat
import robofight.challenge.ChallengeFormat.FightDescriptor
import robofight.challenge.ChallengeFormat.ParseResult
import robofight.challenge.ChallengeFormat.Result
import robofight.challenge.ChallengeRun
import robofight.disasm.Disasm
import robofight.isa.ISA
import robofight.world.Arena
import robofight.world.Bot
import robofight.world.Presets
import kotlin.random.Random

/**
 * Challenge-code codec tests (CHALLENGE-CODES.md §12.2 — headless format
 * matrix) plus the §10 error-string table (verbatim), §9 gating, and the
 * disassembler round-trip. Shares the global check()/passed/failed of
 * Tests.kt; run as `robofight.CodecTestsKt`.
 */
fun main() {
    println("== Challenge codec tests (§12.2) ==")
    testCrc16()
    testBase32()
    testErrorStrings()
    testRoundTripCorpus()
    testMaxSize()
    testGating()
    testFuzz()
    testDeterminism()
    testDisasmRoundTrip()
    testEncoderInvariants()
    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}

// =====================================================================
//  helpers
// =====================================================================

/** Encode a raw payload byte stream into an ungrouped RF1- code. */
private fun rawCode(payload: ByteArray): String =
    ChallengeFormat.PREFIX + ChallengeFormat.base32Encode(payload)

/**
 * Build a structurally complete v1 payload with the given field values
 * (everything else default-valid), CRC included. The test mutates exactly
 * one field per case, so the FIRST failing §11 check is the one under test.
 */
private fun makePayload(
    fmt: Int = 1,
    eng: Int = 1,
    layout: Int = 0,
    slots: Int = 3,
    flags: Int = 0,
    seed: Int = 0x1234_5678,
    mode: Int = 0,
    spawn: Int = 0,
    hp: Int = 0,            // bits0–1 = A idx, bits2–3 = B idx
    resultWinner: Int? = null, // set -> flags bit0 must be set by caller
    resultTicks: Int = 77,
    resultEndHp: Int = 0,
    fwA: ByteArray = ByteArray(0),
    fwB: ByteArray = ByteArray(0),
    srcA: ByteArray? = null,
    srcB: ByteArray? = null,
): ByteArray {
    val out = ArrayList<Byte>()
    out.add(fmt.toByte()); out.add(eng.toByte()); out.add(layout.toByte())
    out.add(slots.toByte()); out.add(flags.toByte())
    out.add((seed and 0xFF).toByte()); out.add(((seed shr 8) and 0xFF).toByte())
    out.add(((seed shr 16) and 0xFF).toByte()); out.add(((seed shr 24) and 0xFF).toByte())
    out.add(mode.toByte()); out.add(spawn.toByte()); out.add(hp.toByte())
    if (resultWinner != null) {
        out.add(resultWinner.toByte())
        out.add((resultTicks and 0xFF).toByte()); out.add(((resultTicks shr 8) and 0xFF).toByte())
        out.add(((resultTicks shr 16) and 0xFF).toByte())
        out.add(resultEndHp.toByte())
    }
    out.add((fwA.size and 0xFF).toByte()); out.add(((fwA.size shr 8) and 0xFF).toByte())
    for (b in fwA) out.add(b)
    out.add((fwB.size and 0xFF).toByte()); out.add(((fwB.size shr 8) and 0xFF).toByte())
    for (b in fwB) out.add(b)
    if (srcA != null) { out.add((srcA.size and 0xFF).toByte()); out.add(((srcA.size shr 8) and 0xFF).toByte()); for (b in srcA) out.add(b) }
    if (srcB != null) { out.add((srcB.size and 0xFF).toByte()); out.add(((srcB.size shr 8) and 0xFF).toByte()); for (b in srcB) out.add(b) }
    val body = out.toByteArray()
    val crc = ChallengeFormat.crc16(body)
    out.add((crc and 0xFF).toByte()); out.add(((crc shr 8) and 0xFF).toByte())
    return out.toByteArray()
}

/** A small valid pinned firmware: 8 instructions, all defined. */
private val WAIT = 0xB6
private fun validFw(nInstr: Int): ByteArray =
    ByteArray(nInstr * 3) { i -> if (i % 3 == 0) WAIT.toByte() else 0 }

/** A descriptor that encode() will accept (pinned slots, valid dials). */
private fun desc(
    layoutId: Int = 0,
    slots: Int = 0,
    seed: Int = 0x1234_5678,
    mode: Int = 0,
    spawn: Int = 0,
    hpA: Int = 100,
    hpB: Int = 100,
    result: Result? = null,
    srcA: String? = null,
    srcB: String? = null,
): FightDescriptor {
    val aOpen = slots and 0x01 != 0
    val bOpen = slots and 0x02 != 0
    return FightDescriptor(
        eng = 1, layoutId = layoutId, slots = slots, seed = seed, mode = mode,
        spawn = spawn, hpA = hpA, hpB = hpB, result = result,
        fwA = if (aOpen) ByteArray(0) else validFw(8),
        fwB = if (bOpen) ByteArray(0) else validFw(8),
        srcA = srcA, srcB = srcB,
    )
}

// =====================================================================
//  CRC-16 / base32
// =====================================================================

private fun testCrc16() {
    println("\n[crc16: CCITT-FALSE]")
    // The §4.1 field-17 check value (guarded by this test, per the codec spec).
    check("CRC-16/CCITT-FALSE(\"123456789\") == 0x29B1",
        ChallengeFormat.crc16("123456789".toByteArray()) == 0x29B1,
        "got 0x${ChallengeFormat.crc16("123456789".toByteArray()).toString(16)}")
    check("CRC of empty input == 0xFFFF (init)", ChallengeFormat.crc16(ByteArray(0)) == 0xFFFF)
    check("CRC of zero bytes == 0x29B1 is NOT the zero vector",
        ChallengeFormat.crc16(ByteArray(9)) != 0x29B1)

    // The stored CRC of an encoded code must equal the CRC over the body.
    val code = ChallengeFormat.encode(desc())
    val b32 = code.removePrefix(ChallengeFormat.PREFIX).filter { it in 'A'..'Z' || it in '2'..'7' }
    val payload = ChallengeFormat.base32Decode(b32)!!
    val stored = (payload[payload.size - 2].toInt() and 0xFF) or ((payload[payload.size - 1].toInt() and 0xFF) shl 8)
    check("stored CRC matches recomputed CRC", stored == ChallengeFormat.crc16(payload, 0, payload.size - 2))
}

private fun testBase32() {
    println("\n[base32: RFC 4648, no padding]")
    // Standard RFC 4648 examples (padding stripped).
    check("b32('f') == MY", ChallengeFormat.base32Encode("f".toByteArray()) == "MY")
    check("b32('fo') == MZXQ", ChallengeFormat.base32Encode("fo".toByteArray()) == "MZXQ")
    check("b32('foo') == MZXW6", ChallengeFormat.base32Encode("foo".toByteArray()) == "MZXW6")
    check("b32('foobar') == MZXW6YTBOI", ChallengeFormat.base32Encode("foobar".toByteArray()) == "MZXW6YTBOI")
    check("b32('') == ''", ChallengeFormat.base32Encode(ByteArray(0)) == "")

    // Round-trip on deterministic pseudo-random buffers of every size 0..63.
    val rnd = Random(20261016)
    var rtOk = true; var bad = -1
    for (n in 0..63) {
        val b = ByteArray(n) { rnd.nextInt(256).toByte() }
        val s = ChallengeFormat.base32Encode(b)
        val d = ChallengeFormat.base32Decode(s) ?: run { rtOk = false; bad = n; break }
        if (!d.contentEquals(b)) { rtOk = false; bad = n; break }
    }
    check("b32 round-trips every size 0..63", rtOk, "failed at n=$bad")

    // Non-zero trailing bits must be rejected (§11.3). Hand-crafted: "ZZZZ"
    // = 11001×4 → 2 data bytes + 4 non-zero padding bits. (The encoder's own
    // output always zero-pads, so this can only be triggered by paste corruption.)
    check("b32 decode rejects non-zero trailing bits",
        ChallengeFormat.base32Decode("ZZZZ") == null)
    // Zero trailing bits (0x00 0x00) decode fine.
    check("b32 decode accepts zero trailing bits",
        (ChallengeFormat.base32Decode(ChallengeFormat.base32Encode(byteArrayOf(0, 0))) ?: ByteArray(-1)).contentEquals(byteArrayOf(0, 0)))

    // Grouping: emit is 4-char blocks; parse strips the separators.
    val code = ChallengeFormat.encode(desc())
    check("emitted code is grouped in 4s (RF1-XXXX-XXXX-…)",
        code.matches(Regex("RF1-[A-Z2-7]{4}(-[A-Z2-7]{1,4})*")), code.take(32))
    val ungrouped = ChallengeFormat.PREFIX + code.removePrefix(ChallengeFormat.PREFIX).replace("-", "")
    check("parse tolerates the grouping (== ungrouped parse)",
        sameOutcome(ChallengeFormat.parse(code), ChallengeFormat.parse(ungrouped)))
    val noisy = code.replaceRange(8, 9, " ") + "\n" // a stray space + newline
    check("parse strips stray spaces/newlines",
        sameOutcome(ChallengeFormat.parse(code), ChallengeFormat.parse(noisy)))
}

/** Two parse outcomes are the same kind with the same string/descriptor. */
private fun sameOutcome(a: ParseResult, b: ParseResult): Boolean = when {
    a is ParseResult.Err && b is ParseResult.Err -> a.message == b.message
    a is ParseResult.Ok && b is ParseResult.Ok -> a.desc == b.desc && a.warning == b.warning && a.lock == b.lock
    else -> false
}

// =====================================================================
//  §10 error strings — each condition, verbatim
// =====================================================================

private fun testErrorStrings() {
    println("\n[§10: error strings (verbatim)]")
    fun errOf(code: String): String? = (ChallengeFormat.parse(code) as? ParseResult.Err)?.message

    // NOT AN RF CODE — missing prefix / lowercase after normalization
    check("no prefix -> NOT AN RF CODE", errOf("GARBAGE-AAAA") == ChallengeFormat.E_NOT_RF)
    check("empty -> NOT AN RF CODE", errOf("") == ChallengeFormat.E_NOT_RF)
    check("wrong prefix -> NOT AN RF CODE", errOf("RF2-AAAAAAAA") == ChallengeFormat.E_NOT_RF)
    check("lowercase letter -> NOT AN RF CODE", errOf("RF1-AAAAaAAA") == ChallengeFormat.E_NOT_RF)
    check("prefix only, no payload -> NOT AN RF CODE is wrong; it's LENGTH",
        errOf("RF1-") == ChallengeFormat.E_LENGTH,
        "got ${errOf("RF1-")}")

    // CODE TOO LONG — normalized length > 10,000
    check(">10,000 normalized chars -> CODE TOO LONG",
        errOf(ChallengeFormat.PREFIX + "A".repeat(10_001)) == ChallengeFormat.E_TOO_LONG)
    check("exactly 10,000 normalized chars passes the length gate",
        errOf(ChallengeFormat.PREFIX + "A".repeat(10_000)) != ChallengeFormat.E_TOO_LONG)

    // CODE MALFORMED — LENGTH: base32 trailing bits / payload < 18
    check("payload < 18 bytes -> CODE MALFORMED — LENGTH",
        errOf(rawCode(ByteArray(10))) == ChallengeFormat.E_LENGTH)
    // Non-zero trailing bits: hand-crafted "ZZZZ" = 11001×4 → 2 data bytes
    // + 4 non-zero padding bits → base32 decode fails → LENGTH.
    check("non-zero trailing bits -> CODE MALFORMED — LENGTH",
        errOf(ChallengeFormat.PREFIX + "ZZZZ") == ChallengeFormat.E_LENGTH)

    // CODE CORRUPT — CHECK THE COPY: flip one body byte (CRC region protected)
    val good = ChallengeFormat.parse(rawCode(makePayload())) as ParseResult.Ok
    val payload = makePayload()
    val flipped = payload.copyOf(); flipped[5] = (flipped[5].toInt() xor 0x40).toByte() // seed byte
    check("flipped seed byte -> CODE CORRUPT — CHECK THE COPY",
        errOf(rawCode(flipped)) == ChallengeFormat.E_CORRUPT)
    val flippedCrc = payload.copyOf(); flippedCrc[payload.size - 1] = (flippedCrc[payload.size - 1].toInt() xor 0x01).toByte()
    check("flipped CRC byte -> CODE CORRUPT — CHECK THE COPY",
        errOf(rawCode(flippedCrc)) == ChallengeFormat.E_CORRUPT)
    check("valid baseline parses (sanity)", good.desc.layoutId == 0)

    // MADE WITH NEWER VERSION — UPDATE APP: fmt > 1
    check("fmt=2 -> MADE WITH NEWER VERSION — UPDATE APP",
        errOf(rawCode(makePayload(fmt = 2))) == ChallengeFormat.E_NEWER_FMT)
    // MADE WITH NEWER ENGINE — UPDATE APP: eng > 1
    check("eng=2 -> MADE WITH NEWER ENGINE — UPDATE APP",
        errOf(rawCode(makePayload(eng = 2))) == ChallengeFormat.E_NEWER_ENG)
    // eng < app -> playable with the exact warning
    val old = ChallengeFormat.parse(rawCode(makePayload(eng = 0)))
    check("eng=0 -> Ok with OLD ENGINE warning",
        old is ParseResult.Ok && old.warning == ChallengeFormat.W_OLD_ENG && old.lock == null)

    // layout: unknown free-reserved id vs Pro range
    check("layout=0x05 (reserved free) -> UNKNOWN ARENA — NEWER VERSION",
        errOf(rawCode(makePayload(layout = 0x05))) == ChallengeFormat.E_UNKNOWN_ARENA)
    check("layout=0x20 (v2 range) -> UNKNOWN ARENA — NEWER VERSION",
        errOf(rawCode(makePayload(layout = 0x20))) == ChallengeFormat.E_UNKNOWN_ARENA)
    val pro = ChallengeFormat.parse(rawCode(makePayload(layout = 0x10)))
    check("layout=0x10 (Pro) -> Ok with ARENA NEEDS PRO lock",
        pro is ParseResult.Ok && pro.lock == ChallengeFormat.E_PRO_ARENA)

    // CODE MALFORMED — SLOTS: range + open/pinned ↔ fw_len mismatch
    check("slots=0b100 -> CODE MALFORMED — SLOTS",
        errOf(rawCode(makePayload(slots = 4, fwA = validFw(2), fwB = validFw(2)))) == ChallengeFormat.E_SLOTS)
    check("pinned slot with fw_len 0 -> CODE MALFORMED — SLOTS",
        errOf(rawCode(makePayload(slots = 0, fwA = ByteArray(0), fwB = validFw(2)))) == ChallengeFormat.E_SLOTS)
    check("open slot (0b01) with non-empty fwA -> CODE MALFORMED — SLOTS",
        errOf(rawCode(makePayload(slots = 1, fwA = validFw(2), fwB = validFw(2)))) == ChallengeFormat.E_SLOTS)
    check("open slot (0b10) with non-empty fwB -> CODE MALFORMED — SLOTS",
        errOf(rawCode(makePayload(slots = 2, fwA = validFw(2), fwB = validFw(2)))) == ChallengeFormat.E_SLOTS)

    // CODE MALFORMED — SIZE: pinned fw not multiple of 3 / > 768
    check("fw len 4 (not %3) -> CODE MALFORMED — SIZE",
        errOf(rawCode(makePayload(slots = 0, fwA = validFw(2).copyOf(4), fwB = validFw(2)))) == ChallengeFormat.E_SIZE)
    check("fw len 771 (>768) -> CODE MALFORMED — SIZE",
        errOf(rawCode(makePayload(slots = 0, fwA = validFw(771 / 3), fwB = validFw(2)))) == ChallengeFormat.E_SIZE)

    // CODE MALFORMED — RULES: mode > 4, spawn > 2, hp reserved bits
    check("mode=5 -> CODE MALFORMED — RULES",
        errOf(rawCode(makePayload(mode = 5))) == ChallengeFormat.E_RULES)
    check("spawn=3 -> CODE MALFORMED — RULES",
        errOf(rawCode(makePayload(spawn = 3))) == ChallengeFormat.E_RULES)
    check("hp reserved bit 4 set -> CODE MALFORMED — RULES",
        errOf(rawCode(makePayload(hp = 0x10))) == ChallengeFormat.E_RULES)
    check("hp reserved bits 7..4 all set -> CODE MALFORMED — RULES",
        errOf(rawCode(makePayload(hp = 0xF0))) == ChallengeFormat.E_RULES)
    check("hp B=200 (bits2–3=11) is NOT a rules violation",
        ChallengeFormat.parse(rawCode(makePayload(hp = 0x0C))) is ParseResult.Ok)

    // CODE MALFORMED — FLAGS: reserved bits 3–7
    check("flags bit3 set -> CODE MALFORMED — FLAGS",
        errOf(rawCode(makePayload(flags = 0x08))) == ChallengeFormat.E_FLAGS)
    check("flags 0xF8 -> CODE MALFORMED — FLAGS",
        errOf(rawCode(makePayload(flags = 0xF8))) == ChallengeFormat.E_FLAGS)

    // CODE MALFORMED — RESULT: winner not in {0,1,2,0xFF} / end_hp > 200
    check("result.winner=5 -> CODE MALFORMED — RESULT",
        errOf(rawCode(makePayload(flags = 0x01, resultWinner = 5))) == ChallengeFormat.E_RESULT)
    check("result.winner=0xFF (NONE) is legal",
        ChallengeFormat.parse(rawCode(makePayload(flags = 0x01, resultWinner = 0xFF))) is ParseResult.Ok)
    check("result.end_hp=201 -> CODE MALFORMED — RESULT",
        errOf(rawCode(makePayload(flags = 0x01, resultWinner = 0, resultEndHp = 201))) == ChallengeFormat.E_RESULT)

    // CODE MALFORMED — SOURCE: embedded source > 2048 / invalid UTF-8
    check("srcA len 2049 -> CODE MALFORMED — SOURCE",
        errOf(rawCode(makePayload(flags = 0x02, srcA = ByteArray(2049) { 'a'.code.toByte() }))) == ChallengeFormat.E_SOURCE)
    check("srcB len 2049 -> CODE MALFORMED — SOURCE",
        errOf(rawCode(makePayload(flags = 0x04, srcB = ByteArray(2049) { 'b'.code.toByte() }))) == ChallengeFormat.E_SOURCE)
    check("srcA invalid UTF-8 -> CODE MALFORMED — SOURCE",
        errOf(rawCode(makePayload(flags = 0x02, srcA = byteArrayOf(0xFF.toByte(), 0xFE.toByte())))) == ChallengeFormat.E_SOURCE)

    // NEW OPCODES — UPDATE APP: decode scan finds an undefined opcode
    val undef = (0..255).first { !ISA.defined(it) }
    val badFw = ByteArray(6) { i -> if (i == 0) undef.toByte() else (if (i % 3 == 0) WAIT.toByte() else 0) }
    check("pinned fw with undefined opcode 0x${undef.toString(16)} -> NEW OPCODES — UPDATE APP",
        errOf(rawCode(makePayload(slots = 0, fwA = badFw, fwB = validFw(2)))) == ChallengeFormat.E_OPCODES)
}

// =====================================================================
//  §12.2 round-trip corpus: every arena × slots × mode × spawn × hp dials
// =====================================================================

private fun testRoundTripCorpus() {
    println("\n[§12.2 round-trip corpus]")
    val arenas = Arena.all().map { it.id }          // the five free arenas (0x00–0x04)
    val slots = listOf(0b00, 0b01, 0b10, 0b11)
    val modes = listOf(0, 1, 2, 3, 4)
    val spawns = listOf(0, 1, 2)
    val hps = listOf(50, 100, 150, 200)
    val seeds = listOf(0, 1, 0xDEAD_BEEF.toInt(), 0x8000_0000.toInt(), 0xFFFF_FFFF.toInt())
    val rnd = Random(77)

    var n = 0
    var descMismatch = -1
    var byteMismatch = -1
    var parseFail = ""
    outer@ for (aid in arenas) for (sl in slots) for (md in modes) for (sp in spawns)
        for (ha in hps) for (hb in hps) {
            for (sd in seeds) {
                val d = desc(layoutId = aid, slots = sl, seed = sd, mode = md, spawn = sp, hpA = ha, hpB = hb)
                val s = ChallengeFormat.encode(d)
                val p = ChallengeFormat.parse(s)
                if (p !is ParseResult.Ok) { parseFail = s.take(24); descMismatch = n; break@outer }
                if (p.desc != d) { descMismatch = n; break@outer }
                if (ChallengeFormat.encode(p.desc) != s) { byteMismatch = n; break@outer }
                n++
            }
        }
    check("corpus: ${arenas.size} arenas × 4 slots × 5 modes × 3 spawns × 16 hp × 5 seeds = $n round-trips",
        n == arenas.size * slots.size * modes.size * spawns.size * hps.size * hps.size * seeds.size,
        "got $n")
    check("corpus: parse(encode(x)) == x (descriptor identity)", descMismatch < 0, "first at #${descMismatch} $parseFail")
    check("corpus: encode(parse(s)) == s (byte-for-byte)", byteMismatch < 0, "first at #${byteMismatch}")

    // With result + both sources present (optional fields preserved, never altered).
    var optOk = true
    var optDetail = ""
    for (sd in seeds) {
        val d = desc(
            layoutId = 3, slots = 0b01, seed = sd, mode = 2, spawn = 2, hpA = 50, hpB = 200,
            result = Result(1, 123_456, 42), srcA = "loop: WAIT\nJMP loop\n", srcB = "SHOOT\nWAIT\n",
        )
        val s = ChallengeFormat.encode(d)
        val p = ChallengeFormat.parse(s)
        if (p !is ParseResult.Ok || p.desc != d || ChallengeFormat.encode(p.desc) != s) {
            optOk = false; optDetail = "seed=$sd ${p::class.simpleName}"
        }
    }
    check("optional fields (result + srcA + srcB) round-trip verbatim", optOk, optDetail)

    // Sampled real firmware: every preset in every pinned-slot arrangement.
    var fwOk = true; var fwDetail = ""
    for (pdef in Presets.all()) {
        val fw = assemble(pdef.firmware)
        if (!fw.ok) { fwOk = false; fwDetail = pdef.name; continue }
        for (sl in listOf(0b00, 0b01, 0b10, 0b11)) {
            val d = desc(
                layoutId = 1, slots = sl, seed = 42, mode = 0, spawn = 0, hpA = 100, hpB = 100,
            )
            val withFw = d.copy(
                fwA = if (d.slotAOpen) ByteArray(0) else fw.code,
                fwB = if (d.slotBOpen) ByteArray(0) else fw.code,
            )
            val s = ChallengeFormat.encode(withFw)
            val p = ChallengeFormat.parse(s)
            if (p !is ParseResult.Ok || p.desc.fwA.contentEquals(if (p.desc.slotAOpen) ByteArray(0) else fw.code).not()) {
                fwOk = false; fwDetail = "${pdef.name}/slots=$sl"; break
            }
            if (ChallengeFormat.encode(p.desc) != s) { fwOk = false; fwDetail = "bytes ${pdef.name}"; break }
        }
    }
    check("preset firmware round-trips in all 4 slot arrangements (5 presets)", fwOk, fwDetail)
}

// =====================================================================
//  §12.2 size: max-payload code within the 10,000-char limit
// =====================================================================

private fun testMaxSize() {
    println("\n[§12.2 size: max payload]")
    val maxD = desc(
        layoutId = 4, slots = 0b00, seed = 0xCAFE_BABE.toInt(), mode = 4, spawn = 2,
        hpA = 200, hpB = 200,
        result = Result(0, 1_000_000, 200),
        srcA = "A".repeat(2048), srcB = "B".repeat(2048),
    ).copy(fwA = validFw(768 / 3), fwB = validFw(768 / 3))
    val s = ChallengeFormat.encode(maxD)
    val normalized = s.filter { it in 'A'..'Z' || it in '2'..'7' }
    check("max payload (2×768B fw + 2×2048B src + result) <= 10,000 normalized chars",
        normalized.length <= ChallengeFormat.MAX_TEXT, "got ${normalized.length}")
    val p = ChallengeFormat.parse(s)
    check("max code parses back identical", p is ParseResult.Ok && p.desc == maxD)
    check("max code re-encodes byte-for-byte", p is ParseResult.Ok && ChallengeFormat.encode(p.desc) == s)
    println("  → max code: ${s.length} chars grouped, ${normalized.length} normalized")
}

// =====================================================================
//  §9 gating + §5 skew (decision-table rows)
// =====================================================================

private fun testGating() {
    println("\n[§9 gating / §5 eng skew]")
    for (aid in listOf(0x00, 0x04)) {
        val p = ChallengeFormat.parse(rawCode(makePayload(layout = aid)))
        check("free layout 0x${aid.toString(16)}: full (no lock, no warning)",
            p is ParseResult.Ok && p.lock == null && p.warning == null)
    }
    for (aid in listOf(0x10, 0x1F)) {
        val p = ChallengeFormat.parse(rawCode(makePayload(layout = aid)))
        check("Pro layout 0x${aid.toString(16)}: importable, RUN locked",
            p is ParseResult.Ok && p.lock == ChallengeFormat.E_PRO_ARENA && p.warning == null)
    }
    for (aid in listOf(0x05, 0x0F, 0x20, 0xFF)) {
        val p = ChallengeFormat.parse(rawCode(makePayload(layout = aid)))
        check("unknown layout 0x${aid.toString(16)}: rejected",
            p is ParseResult.Err && p.message == ChallengeFormat.E_UNKNOWN_ARENA)
    }
    val eq = ChallengeFormat.parse(rawCode(makePayload(eng = 1)))
    check("eng == app: play, exact (no warning)",
        eq is ParseResult.Ok && eq.warning == null)
}

// =====================================================================
//  §12.2 fuzz: 10,000 byte mutations + 1,000 text mutations
// =====================================================================

private fun testFuzz() {
    println("\n[§12.2 fuzz: 10,000 mutations]")
    val bases = ArrayList<String>()
    for (aid in listOf(0, 2, 4)) for (sl in listOf(0b00, 0b01, 0b11)) for (md in listOf(0, 2, 4))
        bases.add(ChallengeFormat.encode(desc(layoutId = aid, slots = sl, mode = md,
            spawn = if (md == 4) 2 else 0, hpA = 150, hpB = 100,
            result = if (md == 0) null else Result(1, 500, 10),
            srcA = if (aid == 2) "loop: WAIT\nJMP loop\n" else null)))
    val rnd = Random(20261016)
    var okCount = 0
    var definedRejects = 0
    var undefined = ""
    var crashed = ""

    // ---- 10,000 payload-byte mutations (single- and multi-byte) ----
    repeat(10_000) { i ->
        val base = bases[i % bases.size]
        val payload = ChallengeFormat.base32Decode(base.removePrefix(ChallengeFormat.PREFIX).filter { it in 'A'..'Z' || it in '2'..'7' })!!
        val m = payload.copyOf()
        val nMut = if (i % 5 == 0) rnd.nextInt(2, 4) else 1   // 20% multi-byte (2–3)
        repeat(nMut) { m[rnd.nextInt(m.size)] = rnd.nextInt(256).toByte() }
        val code = ChallengeFormat.PREFIX + ChallengeFormat.base32Encode(m)
        try {
            when (val r = ChallengeFormat.parse(code)) {
                is ParseResult.Ok -> okCount++
                is ParseResult.Err -> {
                    if (r.message in ChallengeFormat.ALL_ERRORS) definedRejects++
                    else undefined = "i=$i -> '${r.message}'"
                }
            }
        } catch (e: Exception) { crashed = "i=$i ${e::class.simpleName}: ${e.message}" }
    }
    check("fuzz bytes: zero crashes", crashed == "", crashed)
    check("fuzz bytes: every rejection is a defined §10 string", undefined == "", undefined)
    println("  → byte fuzz: $okCount parsed OK, $definedRejects defined rejections (of 10,000)")

    // ---- 1,000 text-form mutations (paste corruption) ----
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    var tokOk = 0; var tokDef = 0; var tokBad = ""; var tokCrash = ""
    repeat(1_000) { i ->
        val base = bases[(i * 7) % bases.size]
        val sb = StringBuilder(base)
        when (i % 5) {
            0 -> { // replace a char after the prefix
                val at = 4 + rnd.nextInt(sb.length - 4)
                sb[at] = if (i % 10 == 0) 'a' else alphabet[rnd.nextInt(alphabet.length)]
            }
            1 -> if (sb.length > 6) sb.deleteCharAt(4 + rnd.nextInt(sb.length - 4))
            2 -> { // insert junk
                val at = 4 + rnd.nextInt(sb.length - 3)
                sb.insert(at, if (i % 2 == 0) " " else alphabet[rnd.nextInt(alphabet.length)])
            }
            3 -> if (sb.length > 8) sb.delete(4, 4 + rnd.nextInt(sb.length - 4)) // chunk delete
            4 -> sb.append(alphabet[rnd.nextInt(alphabet.length)]) // trailing garbage
        }
        try {
            when (val r = ChallengeFormat.parse(sb.toString())) {
                is ParseResult.Ok -> tokOk++
                is ParseResult.Err -> if (r.message in ChallengeFormat.ALL_ERRORS) tokDef++ else { tokBad = "i=$i -> '${r.message}'" }
            }
        } catch (e: Exception) { tokCrash = "i=$i ${e::class.simpleName}" }
    }
    check("fuzz text: zero crashes", tokCrash == "", tokCrash)
    check("fuzz text: every rejection is a defined §10 string", tokBad == "", tokBad)
    println("  → text fuzz: $tokOk parsed OK, $tokDef defined rejections (of 1,000)")
}

// =====================================================================
//  §12.2 determinism: identical code → identical result (headless)
// =====================================================================

private fun testDeterminism() {
    println("\n[§12.2 determinism: code → identical result]")
    // A REPLAY code: HUNTER (A) vs TURTLE (B), OPEN arena, timed-200, SEPARATE.
    // (timed mode: HUNTER vs TURTLE is a known stalemate pair — it reaches the
    // time limit without a KO, so KO mode would run to the 1M cap; the time
    // limit guarantees a bounded, deterministic outcome.)
    val hunter = assemble(Presets.HUNTER)
    val turtle = assemble(Presets.TURTLE)
    check("preset firmware assembles", hunter.ok && turtle.ok,
        hunter.errors.toString() + turtle.errors.toString())
    val d = desc(
        layoutId = Arena.OPEN.id, slots = 0b00, seed = 0x0BAD_F00D.toInt(),
        mode = 3, spawn = 0, hpA = 100, hpB = 100,
    ).copy(fwA = hunter.code, fwB = turtle.code)
    val s1 = ChallengeFormat.encode(d)
    val parsed = ChallengeFormat.parse(s1) as ParseResult.Ok
    val d2 = parsed.desc

    val r1 = ChallengeRun.run(d, null, null)
    val r2 = ChallengeRun.run(d2, null, null)
    val s3 = ChallengeFormat.encode(d2)
    check("encode(parse(encode(x))) == encode(x) (re-encode stability)", s3 == s1)
    val r3 = ChallengeRun.run((ChallengeFormat.parse(s3) as ParseResult.Ok).desc, null, null)
    check("determinism: 3 runs (direct, parsed, re-encoded) identical",
        r1 == r2 && r2 == r3, "r1=$r1 r2=$r2 r3=$r3")
    check("fight resolves well under the 1,000,000-tick cap (< 2,000 budget)",
        r1.ticks in 1..2_000, "ticks=${r1.ticks}")
    check("winner is a slot (0 or 1) or DRAW (2)", r1.winnerSlot in 0..2)
    check("loser end HP in 0..200", r1.loserEndHp in 0..200, "hp=${r1.loserEndHp}")
    println("  → HUNTER vs TURTLE (OPEN, KO, SEPARATE, seed 0x0BADF00D): $r1")

    // Timed mode determinism + the exact §6 tick table (1=100,2=150,3=200,4=300).
    for (md in listOf(1, 2, 3, 4)) {
        val t = ChallengeFormat.encode(desc(mode = md)).let { (ChallengeFormat.parse(it) as ParseResult.Ok).desc }
        check("mode $md → timeLimit ${listOf(100, 150, 200, 300)[md - 1]}", t.timeLimit == listOf(100, 150, 200, 300)[md - 1],
            "got ${t.timeLimit}")
    }

    // SCATTER determinism: the spawn draws consume the seeded LCG — same code,
    // same cells, same outcome, run twice.
    val sd = desc(layoutId = Arena.MAZE.id, slots = 0b00, seed = 0x5EED_5EED.toInt(), mode = 0, spawn = 2)
    val rs1 = ChallengeRun.run(sd, null, null)
    val rs2 = ChallengeRun.run(sd, null, null)
    check("SCATTER determinism: identical outcomes across runs", rs1 == rs2, "rs1=$rs1 rs2=$rs2")
    check("SCATTER fight resolves (< 2,000 budget)", rs1.ticks in 1..2_000, "ticks=${rs1.ticks}")
    println("  → MAZE SCATTER KO (seed 0x5EED5EED): $rs1")

    // Per-slot HP is honored: A at 50 vs B at 200, same firmware both — the
    // HP dial, not the firmware, decides who is the paper tiger.
    val weakA = desc(layoutId = Arena.OPEN.id, slots = 0b00, seed = 1, mode = 3, spawn = 0, hpA = 50, hpB = 200)
        .copy(fwA = hunter.code, fwB = turtle.code)
    val ro = ChallengeRun.run(weakA, null, null)
    check("asymmetric HP runs to a resolution", ro.ticks in 1..2_000, "ticks=${ro.ticks}")
}

// =====================================================================
//  disassembler round-trip (display-only; §4.1 path + test matrix)
// =====================================================================

private fun testDisasmRoundTrip() {
    println("\n[disasm round-trip: all 5 presets]")
    for (pdef in Presets.all()) {
        val asm = assemble(pdef.firmware)
        check("${pdef.name}: assembles", asm.ok, asm.errors.toString())
        if (!asm.ok) continue
        val insns = Disasm.disassemble(asm.code)
        check("${pdef.name}: instruction count ${asm.instructionCount}", insns.size == asm.instructionCount)
        var reEnc = true; var undefMn = -1
        for ((i, insn) in insns.withIndex()) {
            val slice = asm.code.copyOfRange(i * 3, i * 3 + 3)
            if (!insn.encode().contentEquals(slice)) { reEnc = false; break }
            if (insn.mnemonic == "???") undefMn = i
        }
        check("${pdef.name}: every instruction re-encodes to its original 3 bytes", reEnc)
        check("${pdef.name}: no undefined opcodes (ISA tables)", undefMn < 0, "first at #$undefMn")
    }
    // Listing shape: offset + verb + operand, one line per instruction.
    val one = assemble("WAIT\nMOV A,#5\nSHOOT\n")
    val listing = Disasm.listing(one.code)
    val lines = listing.lines()
    check("listing: 3 lines for 3 instructions", lines.size == 3, "got ${lines.size}")
    check("listing: offsets 0000/0003/0006", lines[0].startsWith("0000") && lines[1].startsWith("0003") && lines[2].startsWith("0006"),
        listing)
    check("listing: WAIT renders with no operand", lines[0].contains("WAIT"))
    check("listing: MOV A,#5 renders the immediate", lines[1].contains("MOV") && lines[1].contains("#5"))
    check("listing: SHOOT renders the verb", lines[2].contains("SHOOT"))
}

// =====================================================================
//  encoder invariants (the creation path never emits an unparseable code)
// =====================================================================

private fun testEncoderInvariants() {
    println("\n[encoder invariants]")
    fun throws(f: () -> Unit): Boolean = try { f(); false } catch (e: IllegalArgumentException) { true }
    check("open slot with non-empty fw rejected",
        throws { ChallengeFormat.encode(desc(slots = 0b01).copy(fwA = validFw(2))) })
    check("pinned slot with empty fw rejected",
        throws { ChallengeFormat.encode(desc(slots = 0b00).copy(fwA = ByteArray(0))) })
    check("fw len not %3 rejected",
        throws { ChallengeFormat.encode(desc(slots = 0b00).copy(fwA = validFw(2).copyOf(4))) })
    check("fw len > 768 rejected",
        throws { ChallengeFormat.encode(desc(slots = 0b00).copy(fwA = validFw(257))) })
    check("hp outside {50,100,150,200} rejected",
        throws { ChallengeFormat.encode(desc(hpA = 75)) })
    check("mode out of range rejected", throws { ChallengeFormat.encode(desc(mode = 5)) })
    check("spawn out of range rejected", throws { ChallengeFormat.encode(desc(spawn = 3)) })
    check("result.winner out of set rejected",
        throws { ChallengeFormat.encode(desc(result = Result(5, 10, 0))) })
    check("result.end_hp > 200 rejected",
        throws { ChallengeFormat.encode(desc(result = Result(0, 10, 201))) })
    check("unknown opcode in pinned fw rejected",
        throws {
            val undef = (0..255).first { !ISA.defined(it) }
            val fw = ByteArray(6) { i -> if (i == 0) undef.toByte() else (if (i % 3 == 0) WAIT.toByte() else 0) }
            ChallengeFormat.encode(desc(slots = 0b00).copy(fwA = fw))
        })
    check("src > 2048 bytes rejected",
        throws { ChallengeFormat.encode(desc(srcA = "A".repeat(2049))) })
}
