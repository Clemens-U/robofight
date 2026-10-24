package robofight.challenge

import robofight.isa.ISA
import robofight.world.Arena
import kotlin.math.ceil

/**
 * Challenge-code wire format v1 — the complete implementation of
 * CHALLENGE-CODES.md §3–§5, §10, §11 (locked spec, source of truth).
 *
 * A **challenge code** is `RF1-<base32>` (RFC 4648, no padding, grouped in
 * 4s on emit). The payload is a little-endian byte stream:
 *
 *   0  fmt      1B   = 1
 *   1  eng      1B   engine version of the producing build (§5)
 *   2  layout   1B   arena registry id (§7)
 *   3  slots    1B   mode mask (§2): bit0 = A open, bit1 = B open
 *   4  flags    1B   bit0 result · bit1 srcA · bit2 srcB · bits3–7 = 0
 *   5  seed     4B   LE fight seed
 *   9  mode     1B   0=KO 1=100 2=150 3=200 4=300 (§6)
 *   10 spawn    1B   0=SEPARATE 1=CLASH 2=SCATTER (§7.6)
 *   11 hp       1B   bits0–1 = A, bits2–3 = B → [50,100,150,200]; bits4–7 = 0
 *   12 [result] 5B   only if flags bit0: winner 1B, ticks 3B LE, end_hp 1B
 *   …  [fwA_len 2B LE + fwA][fwB_len 2B LE + fwB]
 *   …  [srcA_len 2B LE + srcA] (flags bit1) [srcB_len 2B LE + srcB] (bit2)
 *   …  crc      2B   CRC-16/CCITT-FALSE over all preceding payload bytes
 *
 * Minimum payload: 18 bytes. The encoder emits only codes that the parser
 * will accept (same invariants, plus the §4.3 decode scan); the parser
 * enforces §11 in order and reports the exact §10 strings.
 *
 * Pure JVM — no Android, no I/O.
 */
object ChallengeFormat {

    // ---- versions (§5) -------------------------------------------------
    /** Wire format version. The `RF1` prefix carries the same number. */
    const val FORMAT_VERSION = 1
    /**
     * Engine version (§5): bumped if and only if a given
     * (layout, rules, seed, firmware) tuple could produce a different fight.
     * NOT the app's version number.
     */
    const val ENGINE_VERSION = 1

    const val PREFIX = "RF1-"
    /** Normalized (non-alphabet stripped) input limit (§3). */
    const val MAX_TEXT = 10_000
    /** Max pinned firmware image bytes (§2/§4.1: multiple of 3, ≤ 768). */
    const val MAX_FW = 768
    /** Max embedded source bytes (§4.3). */
    const val MAX_SRC = 2048

    // ---- result constants (§4.2) ----------------------------------------
    const val WIN_A = 0
    const val WIN_B = 1
    const val WIN_DRAW = 2
    const val WIN_NONE = 0xFF // author never ran it

    // ---- mode constants (§6) --------------------------------------------
    const val MODE_KO = 0
    const val MODE_100 = 1
    const val MODE_150 = 2
    const val MODE_200 = 3
    const val MODE_300 = 4

    // ---- slot mask values (§2) -------------------------------------------
    const val SLOTS_REPLAY = 0b00 // A pinned, B pinned
    const val SLOTS_CHALLENGE = 0b01 // A open, B pinned (canonical challenge)
    const val SLOTS_CHALLENGE_MIRRORED = 0b10 // A pinned, B open
    const val SLOTS_OPEN_DUEL = 0b11 // both open

    // ---- exact §10 error strings (verbatim, terminal voice) ---------------
    const val E_NOT_RF = "NOT AN RF CODE"
    const val E_TOO_LONG = "CODE TOO LONG"
    const val E_LENGTH = "CODE MALFORMED — LENGTH"
    const val E_CORRUPT = "CODE CORRUPT — CHECK THE COPY"
    const val E_NEWER_FMT = "MADE WITH NEWER VERSION — UPDATE APP"
    const val E_NEWER_ENG = "MADE WITH NEWER ENGINE — UPDATE APP"
    const val W_OLD_ENG = "OLD ENGINE — REPLAY USES CURRENT RULES" // warning, playable
    const val E_UNKNOWN_ARENA = "UNKNOWN ARENA — NEWER VERSION"
    const val E_PRO_ARENA = "ARENA NEEDS THE CIRCUIT — PLAY IT THERE" // A1 (2026-10-23); copy rev 2026-10-24: on-screen never says "pro app" — the paid product is THE CIRCUIT
    const val E_SLOTS = "CODE MALFORMED — SLOTS"
    const val E_SIZE = "CODE MALFORMED — SIZE"
    const val E_RULES = "CODE MALFORMED — RULES"
    const val E_FLAGS = "CODE MALFORMED — FLAGS"
    const val E_RESULT = "CODE MALFORMED — RESULT"
    const val E_SOURCE = "CODE MALFORMED — SOURCE"
    const val E_OPCODES = "NEW OPCODES — UPDATE APP"

    /** Every defined §10 string — the fuzz gate asserts rejections land here. */
    val ALL_ERRORS = setOf(
        E_NOT_RF, E_TOO_LONG, E_LENGTH, E_CORRUPT, E_NEWER_FMT, E_NEWER_ENG,
        W_OLD_ENG, E_UNKNOWN_ARENA, E_PRO_ARENA, E_SLOTS, E_SIZE, E_RULES,
        E_FLAGS, E_RESULT, E_SOURCE, E_OPCODES,
    )

    private val HP_TABLE = intArrayOf(50, 100, 150, 200)

    /** The recorded outcome of a fight (§4.2), in the code's slot orientation. */
    data class Result(
        /** 0=A · 1=B · 2=DRAW · 0xFF=NONE (author never ran it). */
        val winner: Int,
        /** Ticks at fight end (≤ 16,777,215). */
        val ticks: Int,
        /** HP of the losing side at fight end (0..200); DRAW → slot A's HP; NONE → 0. */
        val endHp: Int,
    )

    /**
     * A complete fight as described by a code (§4 fields). Value semantics:
     * firmware blocks compare by content. `eng` is the engine version of the
     * build that PRODUCED the code (1 for this build); `layoutId` indexes the
     * §7 registry. Open slots carry an empty firmware block.
     */
    class FightDescriptor(
        val eng: Int,
        val layoutId: Int,
        val slots: Int,
        val seed: Int,
        val mode: Int,
        val spawn: Int,
        val hpA: Int,
        val hpB: Int,
        val result: Result?,
        val fwA: ByteArray,
        val fwB: ByteArray,
        val srcA: String?,
        val srcB: String?,
    ) {
        val slotAOpen: Boolean get() = slots and 0x01 != 0
        val slotBOpen: Boolean get() = slots and 0x02 != 0

        /** REPLAY · CHALLENGE · OPEN DUEL, from the slot mask (§2). */
        val codeMode: String get() = when (slots) {
            SLOTS_REPLAY -> "REPLAY"
            SLOTS_OPEN_DUEL -> "OPEN DUEL"
            else -> "CHALLENGE"
        }

        /** Timed-mode tick limit, or null for KO (§6: 1=100 · 2=150 · 3=200 · 4=300). */
        val timeLimit: Int? get() = when (mode) {
            MODE_KO -> null
            MODE_100 -> 100
            MODE_150 -> 150
            MODE_200 -> 200
            else -> 300
        }

        /** Human label for the mode dial: KO / 100 / 150 / 200 / 300. */
        val modeLabel: String get() = when (mode) {
            MODE_KO -> "KO"
            MODE_100 -> "100"
            MODE_150 -> "150"
            MODE_200 -> "200"
            else -> "300"
        }

        val spawnLabel: String get() = when (spawn) {
            0 -> "SEPARATE"
            1 -> "CLASH"
            else -> "SCATTER"
        }

        fun copy(
            eng: Int = this.eng,
            layoutId: Int = this.layoutId,
            slots: Int = this.slots,
            seed: Int = this.seed,
            mode: Int = this.mode,
            spawn: Int = this.spawn,
            hpA: Int = this.hpA,
            hpB: Int = this.hpB,
            result: Result? = this.result,
            fwA: ByteArray = this.fwA,
            fwB: ByteArray = this.fwB,
            srcA: String? = this.srcA,
            srcB: String? = this.srcB,
        ) = FightDescriptor(eng, layoutId, slots, seed, mode, spawn, hpA, hpB, result, fwA, fwB, srcA, srcB)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FightDescriptor) return false
            return eng == other.eng && layoutId == other.layoutId && slots == other.slots &&
                seed == other.seed && mode == other.mode && spawn == other.spawn &&
                hpA == other.hpA && hpB == other.hpB && result == other.result &&
                fwA.contentEquals(other.fwA) && fwB.contentEquals(other.fwB) &&
                srcA == other.srcA && srcB == other.srcB
        }

        override fun hashCode(): Int {
            var h = eng
            h = 31 * h + layoutId
            h = 31 * h + slots
            h = 31 * h + seed
            h = 31 * h + mode
            h = 31 * h + spawn
            h = 31 * h + hpA
            h = 31 * h + hpB
            h = 31 * h + (result?.hashCode() ?: 0)
            h = 31 * h + fwA.contentHashCode()
            h = 31 * h + fwB.contentHashCode()
            h = 31 * h + (srcA?.hashCode() ?: 0)
            h = 31 * h + (srcB?.hashCode() ?: 0)
            return h
        }

        override fun toString(): String =
            "FightDescriptor(eng=$eng layout=0x${layoutId.toString(16).uppercase()} slots=0b${"%4s".format(slots.toString(2))}" +
                " seed=${seed.toUInt()} mode=$modeLabel spawn=$spawnLabel hp=${hpA}/${hpB}" +
                (result?.let { " result=(w=${it.winner},t=${it.ticks},hp=${it.endHp})" } ?: "") +
                " fwA=${fwA.size}B fwB=${fwB.size}B" +
                (srcA?.let { " srcA=${srcA.length}B" } ?: "") +
                (srcB?.let { " srcB=${srcB.length}B" } ?: "")
    }

    /**
     * §11 outcome: [Ok] carries the descriptor plus an optional playable
     * warning (`eng` < app's engine, §5) and an optional playback lock
     * (Pro layout id in a free build, §9.1/§9.2 — import succeeds, RUN is
     * locked with [E_PRO_ARENA]). [Err] carries the exact §10 string.
     */
    sealed class ParseResult {
        class Ok(val desc: FightDescriptor, val warning: String?, val lock: String?) : ParseResult()
        class Err(val message: String) : ParseResult()
    }

    // =====================================================================
    //  ENCODE
    // =====================================================================

    /**
     * Canonical encoding: `RF1-` + base32 (uppercase, no padding, groups of
     * 4 separated by `-`). The encoder enforces the same invariants the
     * parser enforces, so a valid [FightDescriptor] always produces a code
     * this build can parse back verbatim.
     *
     * @throws IllegalArgumentException when the descriptor is structurally
     * invalid (open/pinned mismatch, bad lengths, unknown opcode in a
     * pinned block, source > [MAX_SRC] bytes, …).
     */
    fun encode(d: FightDescriptor): String {
        // Same structural invariants as §2/§4/§10 (the encoder never emits
        // a code it would reject on import).
        require(d.slots in 0..3) { "slots must be 0b00..0b11 (got ${d.slots})" }
        require(d.mode in 0..4) { "mode out of range 0..4 (got ${d.mode})" }
        require(d.spawn in 0..2) { "spawn out of range 0..2 (got ${d.spawn})" }
        require(d.hpA in HP_TABLE) { "hpA must be 50/100/150/200 (got ${d.hpA})" }
        require(d.hpB in HP_TABLE) { "hpB must be 50/100/150/200 (got ${d.hpB})" }
        require(d.eng in 0..255) { "eng out of range (got ${d.eng})" }
        require(d.layoutId in 0..0xFF) { "layout out of range (got ${d.layoutId})" }
        if (d.result != null) {
            val r = d.result
            require(r.winner == WIN_A || r.winner == WIN_B || r.winner == WIN_DRAW || r.winner == WIN_NONE) {
                "result.winner must be 0/1/2/0xFF (got ${r.winner})"
            }
            require(r.ticks in 0..0xFFFFFF) { "result.ticks out of range 0..16777215" }
            require(r.endHp in 0..200) { "result.endHp out of range 0..200 (got ${r.endHp})" }
        }
        requireFw(d.fwA, "A", d.slotAOpen)
        requireFw(d.fwB, "B", d.slotBOpen)
        require(d.srcA == null || d.srcA.toByteArray().size <= MAX_SRC) { "srcA > $MAX_SRC bytes" }
        require(d.srcB == null || d.srcB.toByteArray().size <= MAX_SRC) { "srcB > $MAX_SRC bytes" }
        // §4.3 decode scan: a pinned block may only contain defined opcodes —
        // the encoder is the creation path, it never emits an unexecutable image.
        for ((label, fw, isOpen) in listOf(Triple("A", d.fwA, d.slotAOpen), Triple("B", d.fwB, d.slotBOpen))) {
            if (!isOpen) {
                for (off in fw.indices step 3) {
                    require(ISA.defined(fw[off].toInt() and 0xFF)) {
                        "firmware $label has an unknown opcode at byte $off (0x${(fw[off].toInt() and 0xFF).toString(16)})"
                    }
                }
            }
        }

        val flags =
            (if (d.result != null) 0x01 else 0) or
            (if (d.srcA != null) 0x02 else 0) or
            (if (d.srcB != null) 0x04 else 0)

        val out = ArrayList<Byte>(12 + 6 + d.fwA.size + d.fwB.size + 4 +
            (d.srcA?.toByteArray()?.size ?: 0) + (d.srcB?.toByteArray()?.size ?: 0))
        out.add(FORMAT_VERSION.toByte())
        out.add(d.eng.toByte())
        out.add(d.layoutId.toByte())
        out.add(d.slots.toByte())
        out.add(flags.toByte())
        out.add((d.seed and 0xFF).toByte())
        out.add(((d.seed shr 8) and 0xFF).toByte())
        out.add(((d.seed shr 16) and 0xFF).toByte())
        out.add(((d.seed shr 24) and 0xFF).toByte())
        out.add(d.mode.toByte())
        out.add(d.spawn.toByte())
        out.add((HP_TABLE.indexOf(d.hpA) or (HP_TABLE.indexOf(d.hpB) shl 2)).toByte())
        d.result?.let { r ->
            out.add(r.winner.toByte())
            out.add((r.ticks and 0xFF).toByte())
            out.add(((r.ticks shr 8) and 0xFF).toByte())
            out.add(((r.ticks shr 16) and 0xFF).toByte())
            out.add(r.endHp.toByte())
        }
        out.addU16(d.fwA.size); out.addAll(d.fwA)
        out.addU16(d.fwB.size); out.addAll(d.fwB)
        d.srcA?.let { s -> val b = s.toByteArray(); out.addU16(b.size); out.addAll(b) }
        d.srcB?.let { s -> val b = s.toByteArray(); out.addU16(b.size); out.addAll(b) }

        val body = out.toByteArray()
        val crc = crc16(body)
        out.add((crc and 0xFF).toByte())
        out.add(((crc shr 8) and 0xFF).toByte())

        return PREFIX + group(base32Encode(out.toByteArray()))
    }

    private fun requireFw(fw: ByteArray, slot: String, open: Boolean) {
        val n = fw.size
        if (open) require(n == 0) { "open slot $slot must carry an empty firmware block (got $n B)" }
        else {
            require(n > 0) { "pinned slot $slot must carry firmware (got 0 B)" }
            require(n % 3 == 0) { "pinned slot $slot must be a multiple of 3 bytes (got $n)" }
            require(n <= MAX_FW) { "pinned slot $slot exceeds $MAX_FW bytes (got $n)" }
        }
    }

    // =====================================================================
    //  PARSE (§11 order — the first failing check wins, §10 strings)
    // =====================================================================

    /**
     * Import path: normalize, decode, validate (§11 in order), build the
     * descriptor. Never throws for malformed input — every failure is a
     * [ParseResult.Err] with the exact §10 string.
     *
     * The input must START with `RF1-` (the app trims pasted text before
     * calling). After the prefix: every non-alphabet character is deleted
     * EXCEPT lower-case letters, which are an alphabet violation
     * ([E_NOT_RF], §3).
     */
    fun parse(text: String): ParseResult {
        // ---- §11.1 prefix ------------------------------------------------
        if (!text.startsWith(PREFIX)) return ParseResult.Err(E_NOT_RF)
        // ---- normalize (§3): delete non-alphabet; lowercase = violation ---
        val norm = StringBuilder()
        for (i in 4 until text.length) {
            val ch = text[i]
            when {
                ch in 'A'..'Z' || ch in '2'..'7' -> norm.append(ch)
                ch in 'a'..'z' -> return ParseResult.Err(E_NOT_RF)
                else -> Unit // hyphens, spaces, newlines, … deleted
            }
        }
        // ---- §11.2 length -------------------------------------------------
        if (norm.length > MAX_TEXT) return ParseResult.Err(E_TOO_LONG)
        // ---- §11.3 decode: base32 → bytes; trailing bits must be zero -----
        val payload = base32Decode(norm.toString()) ?: return ParseResult.Err(E_LENGTH)
        if (payload.size < 18) return ParseResult.Err(E_LENGTH)
        // ---- §11.4 CRC over all preceding bytes ---------------------------
        val crcStored = (payload[payload.size - 2].toInt() and 0xFF) or
            ((payload[payload.size - 1].toInt() and 0xFF) shl 8)
        if (crc16(payload, 0, payload.size - 2) != crcStored) return ParseResult.Err(E_CORRUPT)

        // ---- structural read (bounds-checked; shortness = E_LENGTH at §11.11)
        var short = false
        fun u8(p: Int): Int = if (p < payload.size) payload[p].toInt() and 0xFF else 0
        fun u16le(p: Int): Int {
            if (p + 1 >= payload.size) { short = true; return 0 }
            return u8(p) or (u8(p + 1) shl 8)
        }
        fun take(p: Int, n: Int): ByteArray {
            if (p + n > payload.size) { short = true; return ByteArray(0) }
            return payload.copyOfRange(p, p + n)
        }

        val fmt = u8(0)
        val eng = u8(1)
        val layoutId = u8(2)
        val slots = u8(3)
        val flags = u8(4)
        val seed = u8(5) or (u8(6) shl 8) or (u8(7) shl 16) or (u8(8) shl 24)
        val mode = u8(9)
        val spawn = u8(10)
        val hpByte = u8(11)
        var p = 12
        var result: Result? = null
        if (flags and 0x01 != 0) {
            val blk = take(p, 5)
            if (blk.isEmpty()) short = true
            else result = Result(u8(p), u8(p + 1) or (u8(p + 2) shl 8) or (u8(p + 3) shl 16), u8(p + 4))
            p += 5
        }
        val fwA_len = u16le(p); p += 2
        val fwA = take(p, fwA_len); p += fwA_len
        val fwB_len = u16le(p); p += 2
        val fwB = take(p, fwB_len); p += fwB_len
        var srcA: String? = null
        var srcAFail = false
        if (flags and 0x02 != 0) {
            val n = u16le(p); p += 2
            val b = take(p, n); p += n
            if (b.isEmpty()) short = true
            else { srcA = b.decodeStrictUtf8(); if (srcA == null) srcAFail = true }
        }
        var srcB: String? = null
        var srcBFail = false
        if (flags and 0x04 != 0) {
            val n = u16le(p); p += 2
            val b = take(p, n); p += n
            if (b.isEmpty()) short = true
            else { srcB = b.decodeStrictUtf8(); if (srcB == null) srcBFail = true }
        }
        // exactly the 2-byte crc must remain after the last block
        if (payload.size - p != 2) short = true

        // ---- §11.5 fmt ----------------------------------------------------
        if (fmt > FORMAT_VERSION) return ParseResult.Err(E_NEWER_FMT)
        if (fmt < FORMAT_VERSION) return ParseResult.Err(E_LENGTH) // not a v1 payload
        // ---- §11.6 eng skew (§5) ------------------------------------------
        var warning: String? = null
        if (eng > ENGINE_VERSION) return ParseResult.Err(E_NEWER_ENG)
        if (eng < ENGINE_VERSION) warning = W_OLD_ENG
        // ---- §11.7 layout registry + Pro entitlement (§9.2) ----------------
        var lock: String? = null
        if (Arena.byId(layoutId) == null) {
            if (layoutId in 0x10..0x1F) lock = E_PRO_ARENA // Pro range: importable, RUN locked
            else return ParseResult.Err(E_UNKNOWN_ARENA)
        }
        // ---- §11.8 slots: range + open/pinned ↔ fw_len consistency (§2) ----
        if (slots > 0b11) return ParseResult.Err(E_SLOTS)
        val aOpen = slots and 0x01 != 0
        val bOpen = slots and 0x02 != 0
        if ((aOpen && fwA.size != 0) || (!aOpen && fwA.size == 0)) return ParseResult.Err(E_SLOTS)
        if ((bOpen && fwB.size != 0) || (!bOpen && fwB.size == 0)) return ParseResult.Err(E_SLOTS)
        // ---- §11.9 firmware lengths: multiple of 3, ≤ 768 ------------------
        if ((fwA.size % 3 != 0 || fwA.size > MAX_FW) && !aOpen) return ParseResult.Err(E_SIZE)
        if ((fwB.size % 3 != 0 || fwB.size > MAX_FW) && !bOpen) return ParseResult.Err(E_SIZE)
        // ---- §11.10 rules: mode ≤ 4, spawn ≤ 2, hp reserved bits clear -----
        if (mode > MODE_300) return ParseResult.Err(E_RULES)
        if (spawn > 2) return ParseResult.Err(E_RULES)
        if (hpByte and 0xF0 != 0) return ParseResult.Err(E_RULES) // reserved bits 4–7 (§4.1)
        // ---- §11.11 flags reserved bits + declared blocks present ----------
        if (flags and 0xF8 != 0) return ParseResult.Err(E_FLAGS)
        if (short) return ParseResult.Err(E_LENGTH)
        // ---- §11.12 result: winner in {0,1,2,0xFF}; end_hp 0..200 ----------
        result?.let { r ->
            if (r.winner != WIN_A && r.winner != WIN_B && r.winner != WIN_DRAW && r.winner != WIN_NONE)
                return ParseResult.Err(E_RESULT)
            if (r.endHp > 200) return ParseResult.Err(E_RESULT)
        }
        // ---- §11.13 source blocks: ≤ 2048 bytes, valid UTF-8 ---------------
        if (srcAFail || srcA != null && srcA.toByteArray().size > MAX_SRC) return ParseResult.Err(E_SOURCE)
        if (srcBFail || srcB != null && srcB.toByteArray().size > MAX_SRC) return ParseResult.Err(E_SOURCE)

        // ---- §11.14 decode scan: pinned firmware, every triple (§4.3) ------
        for ((label, fw, isOpen) in listOf(Triple("A", fwA, aOpen), Triple("B", fwB, bOpen))) {
            if (isOpen) continue
            for (off in fw.indices step 3) {
                if (!ISA.defined(fw[off].toInt() and 0xFF)) return ParseResult.Err(E_OPCODES)
            }
        }

        // ---- §11.15 done: build the descriptor -----------------------------
        val desc = FightDescriptor(
            eng = eng,
            layoutId = layoutId,
            slots = slots,
            seed = seed,
            mode = mode,
            spawn = spawn,
            hpA = HP_TABLE[hpByte and 3],
            hpB = HP_TABLE[(hpByte shr 2) and 3],
            result = result,
            fwA = fwA,
            fwB = fwB,
            srcA = srcA,
            srcB = srcB,
        )
        return ParseResult.Ok(desc, warning, lock)
    }

    /** Strict UTF-8 decode — null on malformed sequences (§11.13 → E_SOURCE). */
    private fun ByteArray.decodeStrictUtf8(): String? = try {
        String(this, Charsets.UTF_8).let {
            // String(bytes) is lenient; re-encode and compare to prove validity.
            if (it.toByteArray().contentEquals(this)) it else null
        }
    } catch (_: Exception) { null }

    // =====================================================================
    //  base32 (RFC 4648, no padding) + CRC-16/CCITT-FALSE — from scratch
    // =====================================================================

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val B32_INDEX: IntArray = IntArray(128) { -1 }.also { t ->
        for (i in B32.indices) t[B32[i].code] = i
    }

    /** RFC 4648 base32, uppercase, NO padding (the encoder's output). */
    fun base32Encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buf = 0
        var bits = 0
        for (b in data) {
            buf = (buf shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buf shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    /**
     * RFC 4648 base32 decode. [s] must contain only alphabet characters
     * (the caller normalizes). Returns null on an invalid character or when
     * the trailing partial group (< 5 bits) is non-zero (§11.3).
     */
    fun base32Decode(s: String): ByteArray? {
        val out = ArrayList<Byte>((s.length * 5 + 7) / 8)
        var buf = 0
        var bits = 0
        for (ch in s) {
            val v = if (ch.code < 128) B32_INDEX[ch.code] else -1
            if (v < 0) return null
            buf = (buf shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add(((buf shr (bits - 8)) and 0xFF).toByte())
                bits -= 8
            }
        }
        // trailing partial group: the low `bits` (0..4) bits must be zero
        if (bits > 0 && buf and ((1 shl bits) - 1) != 0) return null
        return out.toByteArray()
    }

    /**
     * CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no xor-out
     * (§4.1 field 17). Check value: "123456789" → 0x29B1 (guarded by test).
     */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    // =====================================================================
    //  helpers
    // =====================================================================

    /** Group a base32 string in 4-char blocks separated by `-` (§3 emit). */
    fun group(s: String): String {
        if (s.length <= 4) return s
        val sb = StringBuilder(s.length + s.length / 4)
        for (i in s.indices step 4) {
            if (i > 0) sb.append('-')
            sb.append(s, i, minOf(i + 4, s.length))
        }
        return sb.toString()
    }

    private fun ArrayList<Byte>.addU16(n: Int) {
        add((n and 0xFF).toByte())
        add(((n shr 8) and 0xFF).toByte())
    }

    private fun ArrayList<Byte>.addAll(b: ByteArray) { for (x in b) add(x) }
}
