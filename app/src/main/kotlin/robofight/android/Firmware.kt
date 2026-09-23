package robofight.android

/**
 * Firmware file templates shared by the editor and the file shell.
 *
 * A freshly created (empty) bot ships with this header + the RF-8 cheat
 * sheet as comments, so the code is ready to type into and every mnemonic
 * is one screen away. Comments are ignored by the assembler.
 */
object Firmware {

    /** One-line header for a brand-new user bot. */
    private const val NEW_HEADER =
        "; OPCODE ARENA BOT — your firmware. Edit it,\n" +
        "; then hit SAVE and pick this file in RUN mode.\n" +
        "; (RF-8 cheat sheet below.)\n"

    /**
     * RF-8 cheat sheet as assembler comments — the canonical copy, used for
     * every new firmware file (see [newTemplate]).
     */
    val CHEAT_SHEET: String = """
; ================================
; RF-8 cheat sheet
; ================================
; MOV  A,#n / A,X / A,Y / A,(X) /
;      A,[n]                    load
; MOV  X,Y,#n                   regs
; MOV  [n],A / (X),A            memory
; ADD  #n|X|Y|(X)|[n]  A=A+op
; SUB MUL DIV AND OR XOR CMP
; INC|DEC|TST  A|X|Y
; JZ JNZ JC JNC JN JNN JG JGE
; JL JLE JE JNE   branch to label
; PUSH|POP  #n|A|X|Y
; CALL label   RET
; SHOOT MOVE TURN L|R|#n
; SHIELD WAIT
; IN   DIST|ANGLE|HP|ENEMY_HP|
;      RAND|X|Y|DIR|HEAT|AHEAD
; OUT  DIR, A
; Ports:
;   DIST   dist to enemy (0=none)
;   ANGLE  cw turns to face it
;   RAND   random byte 0..255
;   AHEAD  1 if wall/enemy blocks
;          the cell in front, else 0
; =================================
""".trimIndent()

    /**
     * Source written into a new (empty) firmware file: a short header plus
     * the cheat sheet, all as comments. The assembler sees an empty program.
     */
    val NEW_TEMPLATE: String = NEW_HEADER + CHEAT_SHEET
}
