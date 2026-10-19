package robofight.disasm

import robofight.isa.ISA

/**
 * Display-only disassembler (R2 Phase 1): firmware image bytes → instruction
 * listing, via the existing ISA tables (ISA.defined()/ISA.name()).
 *
 * Built + tested now (§4.1 display path, round-trip test), NOT surfaced
 * in-app in v1 — the firmware is hidden (PLAN v1 decision: identity +
 * size only, no source/reveal). Pure JVM.
 *
 * An image is a sequence of 3-byte instructions (opcode, operand LE). The
 * listing is LOSSY BY DESIGN for display purposes (register names, port
 * names, hex addresses), so the round-trip test re-encodes each [Insn] back
 * to its 3 bytes and compares against the original image.
 */
object Disasm {

    /** One disassembled instruction at byte [offset]; raw operand bytes kept,
     *  so [encode] is a structurally exact inverse of [disassemble]. */
    data class Insn(
        val offset: Int,
        val opcode: Int,
        val b1: Int,          // operand byte 1 (LE low)
        val b2: Int,          // operand byte 2 (LE high)
    ) {
        /** ISA table name for the opcode — "???" when undefined. */
        val mnemonic: String get() = ISA.name(opcode)

        /** First word of the mnemonic. */
        val verb: String get() = mnemonic.substringBefore(' ')

        /** Rendered operand ("" when the kind has none). */
        val operand: String get() = operandText(opcode, b1, b2)

        /** Concrete listing text: the mnemonic with its #n/[a] placeholder
         *  filled in (or the operand appended when the mnemonic carries none). */
        val text: String get() = render(opcode, b1, b2)

        /** Re-encode to exactly the original 3 bytes (round-trip property). */
        fun encode(): ByteArray = byteArrayOf(opcode.toByte(), b1.toByte(), b2.toByte())
    }

    /**
     * Disassemble a firmware image (must be a multiple of 3 bytes — the
     * codec guarantees this for pinned blocks; the assembler output always
     * is). Undefined opcodes are listed as `???` (never thrown — the
     * decoder scan in the codec rejects them on import; this is display).
     */
    fun disassemble(image: ByteArray): List<Insn> {
        require(image.size % 3 == 0) { "firmware image must be a multiple of 3 bytes (got ${image.size})" }
        val out = ArrayList<Insn>(image.size / 3)
        for (off in image.indices step 3) {
            out.add(
                Insn(
                    off,
                    image[off].toInt() and 0xFF,
                    image[off + 1].toInt() and 0xFF,
                    image[off + 2].toInt() and 0xFF,
                )
            )
        }
        return out
    }

    /** Terminal-voice listing: `0000  WAIT` / `0003  MOV A,#5` / `0006  SHOOT`. */
    fun listing(image: ByteArray): String =
        disassemble(image).joinToString("\n") { i -> "%04X  %s".format(i.offset, i.text) }

    /**
     * Concrete listing text for one instruction: the ISA mnemonic is a
     * template with placeholders (`#n`, `[a]`); substitute them with the
     * rendered operand, or append the operand when the mnemonic carries no
     * placeholder (JMP/CALL/branch/PORT forms).
     */
    private fun render(opcode: Int, b1: Int, b2: Int): String {
        val mn = ISA.name(opcode)
        return when (ISA.kind(opcode)) {
            ISA.NONE, ISA.IND, ISA.REG -> mn // already concrete ("WAIT", "MOV A,X")
            ISA.IMM -> {
                val v = "#$b1"
                if (mn.endsWith("#n")) mn.dropLast(2) + v else "$mn $v"
            }
            ISA.ABS -> {
                val v = "0x%04X".format(b1 or (b2 shl 8))
                if (mn.contains("[a]")) mn.replace("[a]", v) else "$mn $v"
            }
            ISA.REL -> {
                val v = if (b1 and 0x80 != 0) b1 - 256 else b1
                "$mn ${if (v >= 0) "+" else ""}$v"
            }
            ISA.PORT -> "$mn ${portName(b1)}"
            else -> mn
        }
    }

    /** Render the operand per the ISA kind table (Int kind constants). */
    fun operandText(opcode: Int, b1: Int, b2: Int): String = when (ISA.kind(opcode)) {
        ISA.NONE, ISA.IND -> ""
        ISA.IMM -> "#$b1"
        ISA.REG -> when (b1) {
            0 -> "A"
            1 -> "X"
            2 -> "Y"
            else -> "R$b1"
        }
        ISA.ABS -> "0x%04X".format(b1 or (b2 shl 8))
        ISA.REL -> {
            val v = if (b1 and 0x80 != 0) b1 - 256 else b1
            "$v"
        }
        ISA.PORT -> portName(b1)
        else -> ""
    }

    /** Port display name by number (PORT_NAMES is name → number). */
    private fun portName(num: Int): String =
        ISA.PORT_NAMES.entries.firstOrNull { it.value == num }?.key ?: "PORT$num"
}
