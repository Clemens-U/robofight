package robofight.assembler

import robofight.isa.ISA

data class AsmError(val line: Int, val msg: String)

data class AsmResult(
    val ok: Boolean,
    val code: ByteArray,
    val errors: List<AsmError>,
    val instructionCount: Int,
)

/**
 * Two-pass line assembler for RF-8.
 *
 *   loop:  MOV  A, #5        ; comment after ';'
 *          ADD  #1
 *          JZ     idle
 *   idle:  WAIT
 *
 * Grammar per line:  [label:]  MNEMONIC  [operand]
 *
 * Operand forms:
 *   #n         immediate 0..255
 *   A X Y      registers
 *   (X)        indirect via X  (memory[X])
 *   label|nnnn absolute address (jumps/ALU: 10-bit 0..1023; MOV data: 0..255)
 *   port name  HP DIR RX RY DIST ANGLE FIRE SHIELD HEAT ENEMY_HP RAND
 *
 * Every instruction is exactly 3 bytes and lives in program space
 * ($100+), so the address of instruction i is ISA.PROG_BASE + i * 3.
 * The first pass only needs to count instructions to place labels.
 */
fun assemble(src: String): AsmResult {
    val lines = src.split("\n")
    val errors = ArrayList<AsmError>()
    val labels = HashMap<String, Int>()

    fun addErr(line: Int, msg: String) {
        if (errors.size < 8) errors.add(AsmError(line, msg))
    }

    // ---- pass 1: count instructions, collect labels (program addresses) ----
    var insnIndex = 0
    for ((i, raw) in lines.withIndex()) {
        val (label, body) = splitLabel(raw)
        if (label != null) {
            val addr = ISA.PROG_BASE + insnIndex * ISA.INSN_BYTES
            if (labels.put(label.uppercase(), addr) != null) addErr(i + 1, "duplicate label '$label'")
        }
        if (body.isEmpty()) continue
        if (mnemonicOf(body) != null) insnIndex++
    }

    // ---- pass 2: encode ----
    val out = ArrayList<Byte>()
    for ((i, raw) in lines.withIndex()) {
        val lineNo = i + 1
        val (_, body) = splitLabel(raw)
        if (body.isEmpty()) continue
        val parts = body.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        val mn = parts[0].uppercase()
        if (mn !in MNEMONICS) { addErr(lineNo, "unknown opcode '$mn'"); continue }
        encode(mn, parts.drop(1), out, labels) { msg -> addErr(lineNo, msg) }
    }

    if (errors.isNotEmpty()) return AsmResult(false, out.toByteArray(), errors, insnIndex)
    return AsmResult(true, out.toByteArray(), emptyList(), insnIndex)
}

val MNEMONICS = setOf(
    "MOV", "NOP", "XCH",
    "ADD", "SUB", "MUL", "DIV", "AND", "OR", "XOR", "CMP",
    "INC", "DEC", "TST", "NEG", "NOT",
    "JMP", "JZ", "JNZ", "JC", "JNC", "JN", "JNN", "JG", "JGE", "JL", "JLE", "JE", "JNE",
    "PUSH", "POP", "CALL", "RET",
    "SHOOT", "TURN", "MOVE", "SHIELD", "WAIT", "IN", "OUT",
)

private fun mnemonicOf(body: String): String? =
    body.split(Regex("[\\s,]+")).firstOrNull()?.trim()?.uppercase()?.takeIf { it in MNEMONICS }

private fun encode(
    mn: String,
    args: List<String>,
    out: ArrayList<Byte>,
    labels: Map<String, Int>,
    err: (String) -> Unit,
) {
    fun emit(opcode: Int, b1: Int = 0, b2: Int = 0) {
        out.add(opcode.toByte()); out.add(b1.toByte()); out.add(b2.toByte())
    }
    fun imm(s: String): Int? {
        val n = s.removePrefix("#").trim().toIntOrNull() ?: run { err("bad immediate '$s'"); return null }
        if (n !in 0..255) { err("immediate $n out of range 0..255"); return null }
        return n
    }
    /** 10-bit absolute address (jumps / ALU), 0..1023 */
    fun addr(s: String): Int? {
        val a = labels[s.uppercase()] ?: s.trim().toIntOrNull()
            ?: run { err("undefined label or address '$s'"); return null }
        if (a !in 0..0x3FF) { err("address $a out of range 0..1023"); return null }
        return a
    }
    /** 8-bit data-RAM address (MOV), 0..255 */
    fun addr8(s: String): Int? {
        val a = labels[s.uppercase()] ?: s.trim().toIntOrNull()
            ?: run { err("undefined label or address '$s'"); return null }
        if (a !in 0..255) { err("MOV data address $a must be 0..255"); return null }
        return a
    }
    fun port(s: String): Int? = ISA.portNum(s) ?: run { err("unknown port '$s'"); null }

    when (mn) {
        "NOP" -> if (args.isEmpty()) emit(0x10) else err("NOP takes no operand")

        "MOV" -> {
            if (args.size != 2) { err("MOV needs two operands (e.g. 'MOV A,#5')"); return }
            val (d, s) = args[0] to args[1]
            when {
                d == "A" -> when {
                    s.startsWith('#') -> { val v = imm(s); if (v == null) return; emit(0x00, v) }
                    s == "X" -> emit(0x02)
                    s == "Y" -> emit(0x03)
                    s == "(X)" -> emit(0x04)
                    else -> { val a = addr8(s); if (a == null) return; emit(0x01, a) }
                }
                d == "X" -> when {
                    s == "A" -> emit(0x08)
                    s == "Y" -> emit(0x09)
                    s.startsWith('#') -> { val v = imm(s); if (v == null) return; emit(0x07, v) }
                    else -> err("MOV X: source must be A, Y or #n")
                }
                d == "Y" -> when {
                    s == "A" -> emit(0x0B)
                    s == "X" -> emit(0x0C)
                    s.startsWith('#') -> { val v = imm(s); if (v == null) return; emit(0x0A, v) }
                    else -> err("MOV Y: source must be A, X or #n")
                }
                d == "(X)" -> if (s == "A") emit(0x06) else err("MOV (X),A is the only indirect store")
                else -> if (s == "A") { val a = addr8(d); if (a == null) return; emit(0x05, a) }
                         else err("expected 'MOV <addr>,A'")
            }
        }

        "ADD", "SUB", "MUL", "DIV", "AND", "OR", "XOR", "CMP" -> {
            if (args.size != 1) { err("$mn needs exactly one operand (A is the base)"); return }
            val v = args[0]
            val base = when (mn) {
                "ADD" -> 0x20; "SUB" -> 0x30; "MUL" -> 0x40; "DIV" -> 0x50
                "AND" -> 0x70; "OR" -> 0x75; "XOR" -> 0x7A; else -> 0x82
            }
            when {
                v.startsWith('#') -> { val n = imm(v); if (n == null) return; emit(base, n) }
                v == "X" -> emit(base + 1)
                v == "Y" -> emit(base + 2)
                v == "(X)" -> emit(base + 3)
                else -> { val a = addr(v); if (a == null) return; emit(base + 4, a and 0xFF, (a shr 8) and 0xFF) }
            }
        }

        "INC", "DEC", "TST" -> {
            if (args.size != 1) { err("$mn needs A, X or Y"); return }
            val r = when (args[0]) { "A" -> 0; "X" -> 1; "Y" -> 2; else -> -1 }
            if (r < 0) { err("$mn needs A, X or Y"); return }
            val base = when (mn) { "INC" -> 0x60; "DEC" -> 0x63; else -> 0x68 }
            emit(base + r)
        }

        "PUSH" -> {
            if (args.size != 1) { err("PUSH needs #n, A, X or Y"); return }
            val v = args[0]
            when {
                v.startsWith('#') -> { val n = imm(v); if (n == null) return; emit(0xA0, n) }
                v == "A" -> emit(0xA1)
                v == "X" -> emit(0xA2)
                v == "Y" -> emit(0xA3)
                else -> err("PUSH needs #n, A, X or Y")
            }
        }
        "POP" -> {
            if (args.size != 1) { err("POP needs A, X or Y"); return }
            val r = when (args[0]) { "A" -> 0; "X" -> 1; "Y" -> 2; else -> -1 }
            if (r < 0) { err("POP needs A, X or Y"); return }
            emit(0xA4 + r)
        }

        "JMP", "CALL" -> {
            if (args.size != 1) { err("$mn needs a label or address"); return }
            val a = addr(args[0]); if (a == null) return
            emit(if (mn == "JMP") 0x90 else 0xA7, a and 0xFF, (a shr 8) and 0xFF)
        }

        "JZ", "JNZ", "JC", "JNC", "JN", "JNN", "JG", "JGE", "JL", "JLE", "JE", "JNE" -> {
            if (args.size != 1) { err("$mn needs a label or address"); return }
            val a = addr(args[0]); if (a == null) return
            val code1 = when (mn) {
                "JZ" -> 0x91; "JNZ" -> 0x92; "JC" -> 0x93; "JNC" -> 0x94
                "JN" -> 0x95; "JNN" -> 0x96; "JG" -> 0x97; "JGE" -> 0x98
                "JL" -> 0x99; "JLE" -> 0x9A; "JE" -> 0x9B; else -> 0x9C
            }
            // relative offset from the ABSOLUTE address of the next instruction
            val nextAbs = ISA.PROG_BASE + out.size + ISA.INSN_BYTES
            val off = a - nextAbs
            if (off !in -128..127) { err("$mn target $a out of ±127 range (from $nextAbs)"); return }
            emit(code1, off and 0xFF)
        }

        "IN" -> {
            if (args.size != 1) { err("IN needs a port"); return }
            val p = port(args[0]); if (p == null) return
            emit(0xB7, p)
        }
        "OUT" -> {
            if (args.size != 2) { err("OUT needs port and A (e.g. 'OUT DIR, A')"); return }
            val p = port(args[0]); if (p == null) return
            if (args[1] != "A") { err("OUT writes A to the port"); return }
            emit(0xB8, p)
        }
        "TURN" -> {
            if (args.size != 1) { err("TURN needs L, R or #n"); return }
            when (args[0]) {
                "L" -> emit(0xB1)
                "R" -> emit(0xB2)
                else -> { val n = imm(args[0]); if (n == null) return; emit(0xB3, n) }
            }
        }
        "XCH" -> {
            if (args.size != 2 || args[0] != "A") { err("forms: XCH A,X · XCH A,Y · XCH A,(X)"); return }
            when (args[1]) {
                "X" -> emit(0x0D)
                "Y" -> emit(0x0E)
                "(X)" -> emit(0x0F)
                else -> err("XCH A: second operand must be X, Y or (X)")
            }
        }
        "RET" -> if (args.isEmpty()) emit(0xA8) else err("RET takes no operand")
        "SHOOT" -> if (args.isEmpty()) emit(0xB0) else err("SHOOT takes no operand")
        "MOVE" -> if (args.isEmpty()) emit(0xB4) else err("MOVE takes no operand")
        "SHIELD" -> if (args.isEmpty()) emit(0xB5) else err("SHIELD takes no operand")
        "WAIT" -> if (args.isEmpty()) emit(0xB6) else err("WAIT takes no operand")
        "NEG" -> if (args.isEmpty()) emit(0x66) else err("NEG takes no operand (operates on A)")
        "NOT" -> if (args.isEmpty()) emit(0x67) else err("NOT takes no operand (operates on A)")
    }
}

/** Splits `label: body` when the part before ':' is a bare identifier. */
private fun splitLabel(raw: String): Pair<String?, String> {
    val noComment = raw.substringBefore(';').trim()
    if (noComment.isEmpty()) return null to ""
    val colon = noComment.indexOf(':')
    if (colon > 0) {
        val pre = noComment.substring(0, colon).trim()
        if (pre.isNotEmpty() && pre.all { it.isLetterOrDigit() || it == '_' }) {
            return pre to noComment.substring(colon + 1).trim()
        }
    }
    return null to noComment
}
