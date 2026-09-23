package robofight.isa

/**
 * RF-8 instruction set — shared by the assembler (encoding) and the VM (decoding).
 *
 * Every instruction is exactly 3 bytes:
 *
 *   byte 0          : opcode (0..255)
 *   bytes 1–2 (LE)  : 16-bit operand; interpretation depends on [OperandKind]
 *
 * Fixed-size instructions mean labels are trivial: byte offset of line i = i * 3,
 * so the assembler does not need to know instruction widths to place labels.
 *
 * Operand kinds (see [OperandKind] below):
 *   NONE   — no operand (operand field unused)
 *   IMM    — 8-bit immediate, bytes 1–2 hold value 0..255 in byte 1, byte 2 = 0
 *   REG    — operand low nibble = register id (0=A, 1=X, 2=Y)
 *   ABS    — 16-bit absolute memory address
 *   REL    — signed 8-bit relative offset (byte 1, two's complement); byte 2 = 0
 *   PORT   — 8-bit I/O port number (byte 1), byte 2 = 0
 *   IND    — indirect via X: memory[X] (operand field unused)
 */
object ISA {
    // ---- Operand kinds ----
    const val NONE = 0
    const val IMM = 1
    const val REG = 2
    const val ABS = 3
    const val REL = 4
    const val PORT = 5
    const val IND = 6

    // ---- I/O ports ($00C0+) ----
    val PORT_NAMES = linkedMapOf(
        "HP" to 0, "DIR" to 1, "RX" to 2, "RY" to 3, "DIST" to 4,
        "ANGLE" to 5, "FIRE" to 6, "SHIELD" to 7, "HEAT" to 8,
        "ENEMY_HP" to 9, "RAND" to 10, "AHEAD" to 11,
    )
    val PORT_NUMS = PORT_NAMES.values.toTypedArray()
    const val NUM_PORTS = 12

    // ---- Register ids (for REG operand) ----
    const val REG_A = 0
    const val REG_X = 1
    const val REG_Y = 2

    // ---- Memory map ----
    const val DATA_START = 0x0000
    const val DATA_END = 0x007F
    const val STACK_TOP = 0x00BF
    const val IO_BASE = 0x00C0
    const val PROG_BASE = 0x0100
    const val PROG_END = 0x03FF
    const val MEM_SIZE = 0x0400
    const val INSN_BYTES = 3

    // ---- Opcode table ----
    // op -> operand kind
    private val KIND = IntArray(256) { NONE }
    // op -> mnemonic (for listings / step log)
    private val NAME = Array(256) { "???" }
    private val DEFINED = BooleanArray(256)

    private fun def(code: Int, name: String, kind: Int) {
        KIND[code] = kind
        NAME[code] = name
        DEFINED[code] = true
    }

    init {
        // ---- MOV ----
        def(0x00, "MOV A,#n",  IMM)
        def(0x01, "MOV A,[a]", ABS)
        def(0x02, "MOV A,X",   REG)  // reg = X
        def(0x03, "MOV A,Y",   REG)  // reg = Y
        def(0x04, "MOV A,(X)", IND)
        def(0x05, "MOV [a],A", ABS)
        def(0x06, "MOV (X),A", IND)
        def(0x07, "MOV X,#n",  IMM)
        def(0x08, "MOV X,A",   REG)  // reg = A
        def(0x09, "MOV X,Y",   REG)  // reg = Y
        def(0x0A, "MOV Y,#n",  IMM)
        def(0x0B, "MOV Y,A",   REG)  // reg = A
        def(0x0C, "MOV Y,X",   REG)  // reg = X
        def(0x0D, "XCH A,X",   NONE)
        def(0x0E, "XCH A,Y",   NONE)
        def(0x0F, "XCH A,(X)", IND)
        def(0x10, "NOP",       NONE)

        // ---- ALU (A = A <op> operand); 5 operand variants each ----
        // variant offset: 0=#n 1=X 2=Y 3=(X) 4=[a]
        val aluKinds = intArrayOf(IMM, REG, REG, IND, ABS)
        for (v in 0..4) {
            val k = aluKinds[v]
            def(0x20 + v, "ADD  ${varName(v)}", k)
            def(0x30 + v, "SUB  ${varName(v)}", k)
            def(0x40 + v, "MUL  ${varName(v)}", k)
            def(0x50 + v, "DIV  ${varName(v)}", k)
            def(0x70 + v, "AND  ${varName(v)}", k)
            def(0x75 + v, "OR   ${varName(v)}", k)
            def(0x7A + v, "XOR  ${varName(v)}", k)
            def(0x82 + v, "CMP  ${varName(v)}", k)
        }
        def(0x60, "INC A", NONE); def(0x61, "INC X", NONE); def(0x62, "INC Y", NONE)
        def(0x63, "DEC A", NONE); def(0x64, "DEC X", NONE); def(0x65, "DEC Y", NONE)
        def(0x66, "NEG A", NONE); def(0x67, "NOT A", NONE)
        def(0x68, "TST A", NONE); def(0x69, "TST X", NONE); def(0x6A, "TST Y", NONE)

        // ---- jumps / branches ----
        def(0x90, "JMP",  ABS)
        def(0x91, "JZ",   REL); def(0x92, "JNZ", REL); def(0x93, "JC", REL)
        def(0x94, "JNC",  REL); def(0x95, "JN", REL); def(0x96, "JNN", REL)
        def(0x97, "JG",   REL); def(0x98, "JGE", REL); def(0x99, "JL", REL)
        def(0x9A, "JLE",  REL); def(0x9B, "JE", REL); def(0x9C, "JNE", REL)

        // ---- stack / subroutines ----
        def(0xA0, "PUSH #n", IMM)
        def(0xA1, "PUSH A",  NONE)
        def(0xA2, "PUSH X",  NONE)
        def(0xA3, "PUSH Y",  NONE)
        def(0xA4, "POP A",   NONE)
        def(0xA5, "POP X",   NONE)
        def(0xA6, "POP Y",   NONE)
        def(0xA7, "CALL",    ABS)
        def(0xA8, "RET",     NONE)

        // ---- robot I/O ----
        def(0xB0, "SHOOT",  NONE)
        def(0xB1, "TURN L", NONE)
        def(0xB2, "TURN R", NONE)
        def(0xB3, "TURN #n", IMM)
        def(0xB4, "MOVE",   NONE)
        def(0xB5, "SHIELD", NONE)
        def(0xB6, "WAIT",   NONE)
        def(0xB7, "IN",     PORT)
        def(0xB8, "OUT",    PORT)
    }

    private fun varName(v: Int) = when (v) {
        0 -> "#n"; 1 -> "X"; 2 -> "Y"; 3 -> "(X)"; else -> "[a]"
    }

    fun kind(opcode: Int) = KIND[opcode and 0xFF]
    fun name(opcode: Int) = NAME[opcode and 0xFF]
    fun defined(opcode: Int) = DEFINED[opcode and 0xFF]

    fun portNum(name: String): Int? {
        val n = name.trim().uppercase()
        return PORT_NAMES[n] ?: n.toIntOrNull()?.takeIf { it in 0 until NUM_PORTS }
    }
}
