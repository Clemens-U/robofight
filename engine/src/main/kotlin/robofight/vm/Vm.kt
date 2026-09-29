package robofight.vm

import robofight.isa.ISA

/**
 * RF-8 virtual machine — pure state, one instruction per tick.
 *
 * Memory map (single 10-bit address space, 3-byte fixed instructions):
 *   $000–$07F  data RAM
 *   $080–$0BF  stack (SP starts at $0BF, grows down)
 *   $0C0–$0FF  I/O ports (routed through [Env])
 *   $100–$3FF  program (up to 256 instructions)
 *
 * Registers: A, X, Y (8-bit) · SP, PC (10-bit) · flags C Z N V
 */
class Vm {
    val mem = ByteArray(1 shl 10)

    var a = 0; private set
    var x = 0; private set
    var y = 0; private set
    var sp = 0
        private set
    var pc = 0
        private set
    var flags = 0
        private set
    var lastOp = "(loaded)"
        private set
    var fault = false
        private set

    /**
     * Multi-tick execution: an op with [ISA.cost] > 1 occupies several
     * [step] calls. The env side effect is deferred and fires only on the
     * op's FINAL tick; the world reads [inProgress] to suppress heat
     * cooling for the op's whole duration. [load] clears the state.
     */
    var inProgress = false
        private set
    /**
     * Index (0-based, in program order) of the instruction CURRENTLY executing.
     * Held for the whole duration of a multi-tick op, so the arena's execution
     * monitor can pin the highlight on the right source line while a SHOOT/MOVE
     * runs. Captured at decode time (before the PC advances), so branches and
     * JMPs land on their target instruction. -1 before the first step.
     */
    var currentInsn = -1
        private set
    private var pending: ((Env) -> Unit)? = null
    private var ticksLeft = 0
    private var pendingTotal = 0
    private var currentOp = 0

    /** value the last CMP compared against (for JG/JGE/JL/JLE) */
    private var lastB = 0

    fun load(code: ByteArray) {
        check(code.size <= (ISA.PROG_END - ISA.PROG_BASE + 1)) {
            "program too large: ${code.size} bytes (max ${ISA.PROG_END - ISA.PROG_BASE + 1})"
        }
        mem.fill(0)
        a = 0; x = 0; y = 0
        sp = ISA.STACK_TOP
        pc = ISA.PROG_BASE
        flags = 0
        lastB = 0
        fault = false
        lastOp = "(loaded)"
        inProgress = false
        currentInsn = -1
        pending = null
        ticksLeft = 0
        code.copyInto(mem, ISA.PROG_BASE)
    }

    /** Execute exactly one instruction (one clock tick). */
    fun step(env: Env) {
        fault = false
        // Multi-tick op in flight: no decode, no fault possible mid-op.
        // The deferred side effect fires on the final tick.
        if (inProgress) {
            ticksLeft--
            if (ticksLeft > 0) {
                lastOp = "${ISA.name(currentOp)} (${pendingTotal - ticksLeft}/${pendingTotal})"
            } else {
                inProgress = false
                pending?.invoke(env)
                pending = null
                lastOp = "${ISA.name(currentOp)} (${pendingTotal}/${pendingTotal})"
            }
            return
        }
        val op = mem[pc].toInt() and 0xFF
        val opnd = mem[pc + 1].toInt() and 0xFF
        val opndHi = mem[pc + 2].toInt() and 0xFF
        // Instruction currently executing = the one at the pre-advance PC, in
        // program order. Captured before the branch/JMP mutates pc, and left
        // untouched by the multi-tick early-return above, so a long SHOOT/MOVE
        // keeps its line highlighted for the whole op.
        currentInsn = (pc - ISA.PROG_BASE) / ISA.INSN_BYTES
        pc += ISA.INSN_BYTES
        if (pc > ISA.PROG_END) pc = ISA.PROG_END

        when {
            op in 0x00..0x0F -> mov(op, opnd)
            aluFamily(op) != -1 -> alu(op, opnd, opndHi)
            op in 0x60..0x6A -> misc(op)
            op == 0x90 -> { pc = ((opndHi and 0x0F) shl 8) or (opnd and 0xFF); lastOp = "JMP $pc" }
            op in 0x91..0x9C -> {
                val off = mem[pc - ISA.INSN_BYTES + 1].toByte().toInt()
                if (cond(op)) { pc = (pc + off) and 0x3FF; lastOp = "${ISA.name(op)} → $pc" }
                else lastOp = "${ISA.name(op)} (skip)"
            }
            op in 0xA0..0xA8 -> stackOrSub(op, opnd, opndHi)
            op in 0xB0..0xB8 -> robotCosted(op, opnd, env)
            else -> { fault = true; lastOp = "HALT — undefined opcode 0x%02X".format(op) }
        }
    }

    private fun mov(op: Int, opnd: Int) {
        when (op) {
            0x00 -> { a = opnd; setFlags(a); lastOp = "MOV A,#$opnd" }
            0x01 -> { a = mem[opnd].toInt() and 0xFF; setFlags(a); lastOp = "MOV A,[$opnd]" }
            0x02 -> { a = x; setFlags(a); lastOp = "MOV A,X" }
            0x03 -> { a = y; setFlags(a); lastOp = "MOV A,Y" }
            0x04 -> { a = mem[x].toInt() and 0xFF; setFlags(a); lastOp = "MOV A,(X)" }
            0x05 -> { mem[opnd] = a.toByte(); lastOp = "MOV [$opnd],A" }
            0x06 -> { mem[x] = a.toByte(); lastOp = "MOV (X),A" }
            0x07 -> { x = opnd; lastOp = "MOV X,#$opnd" }
            0x08 -> { x = a; lastOp = "MOV X,A" }
            0x09 -> { x = y; lastOp = "MOV X,Y" }
            0x0A -> { y = opnd; lastOp = "MOV Y,#$opnd" }
            0x0B -> { y = a; lastOp = "MOV Y,A" }
            0x0C -> { y = x; lastOp = "MOV Y,X" }
            0x0D -> { val t = a; a = x; x = t; lastOp = "XCH A,X" }
            0x0E -> { val t = a; a = y; y = t; lastOp = "XCH A,Y" }
            0x0F -> { val t = mem[x].toInt() and 0xFF; mem[x] = a.toByte(); a = t; lastOp = "XCH A,(X)" }
            0x10 -> lastOp = "NOP"
        }
    }

    private fun aluFamily(op: Int): Int = when (op) {
        in 0x20..0x24 -> 0
        in 0x30..0x34 -> 1
        in 0x40..0x44 -> 2
        in 0x50..0x54 -> 3
        in 0x70..0x74 -> 4
        in 0x75..0x79 -> 5
        in 0x7A..0x7E -> 6
        in 0x82..0x86 -> 7
        else -> -1
    }

    /** operand variant (0 #n, 1 X, 2 Y, 3 (X), 4 [a]) — blocks are NOT 5-aligned,
     *  so subtract the family base instead of using op % 5 */
    private fun aluVariant(op: Int): Int {
        val base = when (aluFamily(op)) {
            0 -> 0x20; 1 -> 0x30; 2 -> 0x40; 3 -> 0x50
            4 -> 0x70; 5 -> 0x75; 6 -> 0x7A; else -> 0x82
        }
        return op - base
    }

    private fun alu(op: Int, opnd: Int, opndHi: Int) {
        val fam = aluFamily(op)
        val v = when (aluVariant(op)) {
            0 -> opnd
            1 -> x
            2 -> y
            3 -> mem[x].toInt() and 0xFF
            else -> mem[(opndHi shl 8) or opnd].toInt() and 0xFF   // [a]: read memory
        }
        when (fam) {
            0 -> { a = (a + v) and 0xFF; setFlags(a) }
            1 -> { a = (a - v) and 0xFF; setFlags(a) }
            2 -> { a = (a * v) and 0xFF; setFlags(a) }
            3 -> { a = if (v == 0) 0 else a / v; setFlags(a) }
            4 -> { a = a and v; setFlags(a) }
            5 -> { a = a or v; setFlags(a) }
            6 -> { a = a xor v; setFlags(a) }
            7 -> { lastB = v; val d = a - v; setFlags(d) }
        }
        lastOp = "${ISA.name(op)} $v"
    }

    private fun misc(op: Int) {
        when (op) {
            0x60 -> { a = (a + 1) and 0xFF; setFlags(a); lastOp = "INC A" }
            0x61 -> { x = (x + 1) and 0xFF; lastOp = "INC X" }
            0x62 -> { y = (y + 1) and 0xFF; lastOp = "INC Y" }
            0x63 -> { a = (a - 1) and 0xFF; setFlags(a); lastOp = "DEC A" }
            0x64 -> { x = (x - 1) and 0xFF; lastOp = "DEC X" }
            0x65 -> { y = (y - 1) and 0xFF; lastOp = "DEC Y" }
            0x66 -> { a = (-a) and 0xFF; setFlags(a); lastOp = "NEG A" }
            0x67 -> { a = a.inv() and 0xFF; setFlags(a); lastOp = "NOT A" }
            0x68 -> { setFlags(a); lastOp = "TST A" }
            0x69 -> { setFlags(x); lastOp = "TST X" }
            0x6A -> { setFlags(y); lastOp = "TST Y" }
        }
    }

    private fun cond(op: Int): Boolean {
        val sA = a.toByte().toInt()
        val sB = lastB.toByte().toInt()
        return when (op) {
            0x91, 0x9B -> flags and FLAG_Z != 0
            0x92, 0x9C -> flags and FLAG_Z == 0
            0x93 -> flags and FLAG_C != 0
            0x94 -> flags and FLAG_C == 0
            0x95 -> flags and FLAG_N != 0
            0x96 -> flags and FLAG_N == 0
            0x97 -> sA > sB
            0x98 -> sA >= sB
            0x99 -> sA < sB
            0x9A -> sA <= sB
            else -> false
        }
    }

    private fun stackOrSub(op: Int, opnd: Int, opndHi: Int) {
        when (op) {
            0xA0 -> { mem[sp] = opnd.toByte(); sp = (sp - 1) and 0x3FF; lastOp = "PUSH #$opnd" }
            0xA1 -> { mem[sp] = a.toByte(); sp = (sp - 1) and 0x3FF; lastOp = "PUSH A" }
            0xA2 -> { mem[sp] = x.toByte(); sp = (sp - 1) and 0x3FF; lastOp = "PUSH X" }
            0xA3 -> { mem[sp] = y.toByte(); sp = (sp - 1) and 0x3FF; lastOp = "PUSH Y" }
            0xA4 -> { sp = (sp + 1) and 0x3FF; a = mem[sp].toInt() and 0xFF; setFlags(a); lastOp = "POP A" }
            0xA5 -> { sp = (sp + 1) and 0x3FF; x = mem[sp].toInt() and 0xFF; lastOp = "POP X" }
            0xA6 -> { sp = (sp + 1) and 0x3FF; y = mem[sp].toInt() and 0xFF; lastOp = "POP Y" }
            0xA7 -> {
                mem[sp] = (pc and 0xFF).toByte(); sp = (sp - 1) and 0x3FF
                mem[sp] = ((pc shr 8) and 0xFF).toByte(); sp = (sp - 1) and 0x3FF
                pc = ((opndHi and 0x0F) shl 8) or (opnd and 0xFF)
                lastOp = "CALL $pc"
            }
            0xA8 -> {
                sp = (sp + 1) and 0x3FF
                val hi = mem[sp].toInt() and 0x0F   // CALL stored hi at the lower SP
                sp = (sp + 1) and 0x3FF
                val lo = mem[sp].toInt() and 0xFF
                pc = (hi shl 8) or lo
                lastOp = "RET → $pc"
            }
        }
    }

    private fun robot(op: Int, opnd: Int, env: Env) {
        when (op) {
            0xB0 -> { env.shoot(); lastOp = "SHOOT" }
            0xB1 -> { env.turn(-1); lastOp = "TURN L" }
            0xB2 -> { env.turn(1); lastOp = "TURN R" }
            0xB3 -> { env.turn(opnd); lastOp = "TURN #$opnd" }
            0xB4 -> { env.move(); lastOp = "MOVE" }
            0xB5 -> { env.shield(); lastOp = "SHIELD" }
            0xB6 -> lastOp = "WAIT"
            0xB7 -> { a = env.portIn(opnd); setFlags(a); lastOp = "IN port$opnd" }
            0xB8 -> { env.portOut(opnd, a); lastOp = "OUT port$opnd" }
        }
    }

    /**
     * Robot op dispatch with multi-tick costing. Ops whose [ISA.cost] > 1
     * defer their env side effect and occupy several [step] calls; the
     * effect (and its heat/flag updates) lands on the final tick only.
     *
     * Locked-out pre-check: a heat op (SHOOT/SHIELD) first asks the env
     * whether it is locked out RIGHT NOW. If so, it collapses to a 1-tick
     * no-op — the bot cools at the normal rate and re-arms in ~10 ticks,
     * instead of being frozen mid-anim (which would never cool and the gun
     * would stay jammed forever in a SHOOT loop).
     */
    private fun robotCosted(op: Int, opnd: Int, env: Env) {
        val cost = ISA.cost(op)
        if (cost <= 1) { robot(op, opnd, env); return }
        if ((op == 0xB0 || op == 0xB5) && env.heatLocked(if (op == 0xB0) 0 else 1)) {
            lastOp = "${ISA.name(op)} (locked)"
            return
        }
        inProgress = true
        currentOp = op
        pendingTotal = cost
        ticksLeft = cost - 1    // decode tick + (cost-1) continuations = cost ticks
        pending = when (op) {
            0xB0 -> { e -> e.shoot() }
            0xB4 -> { e -> e.move() }
            0xB5 -> { e -> e.shield() }
            else -> null
        }
        lastOp = "${ISA.name(op)} (1/$cost)"
    }

    companion object {
        const val FLAG_C = 0x01
        const val FLAG_Z = 0x02
        const val FLAG_N = 0x04
        const val FLAG_V = 0x08
    }

    private fun setFlags(v: Int) {
        var f = 0
        if (v == 0) f = f or FLAG_Z
        if (v and 0x80 != 0) f = f or FLAG_N
        flags = f
    }
}

/** I/O boundary between the VM and the world. */
interface Env {
    fun portIn(port: Int): Int
    fun portOut(port: Int, value: Int)
    /**
     * True if a heat-consuming op would be locked out RIGHT NOW.
     * [op]: 0 = SHOOT, 1 = SHIELD. Multi-tick heat ops pre-check this on
     * their first tick: a locked op collapses to a 1-tick no-op so the
     * bot cools at the normal rate instead of being frozen mid-anim.
     */
    fun heatLocked(op: Int): Boolean
    fun shoot()
    fun move()
    fun shield()
    fun turn(delta: Int)
}
