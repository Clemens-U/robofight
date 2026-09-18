package robofight

import robofight.assembler.assemble
import robofight.vm.Env
import robofight.vm.Vm
import robofight.world.*

var passed = 0
var failed = 0

fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { passed++; println("  PASS  $name") }
    else { failed++; println("  FAIL  $name  $detail") }
}

/** Null Env for pure-VM tests (all ports read 0, no side effects). */
object NullEnv : Env {
    override fun portIn(port: Int) = 0
    override fun portOut(port: Int, value: Int) {}
    override fun shoot() {}
    override fun move() {}
    override fun shield() {}
    override fun turn(delta: Int) {}
}

fun main() {
    println("== RoboFight engine tests ==")

    // ---- assembler ----
    run {
        println("\n[assembler]")
        val r1 = assemble("MOV A,#5\nADD #1")
        check("simple program assembles", r1.ok, r1.errors.toString())
        check("two 3-byte instructions", r1.code.size == 6, "got ${r1.code.size}")

        val r2 = assemble("FOOBAR #1")
        check("unknown opcode rejected", !r2.ok && r2.errors.any { "unknown opcode" in it.msg })

        val r3 = assemble("MOV A,#999")
        check("immediate out of range rejected", !r3.ok)

        val r4 = assemble("loop: JMP loop\ndup: NOP\ndup: NOP")
        check("duplicate label rejected", !r4.ok)

        val r5 = assemble("JZ nowhere")
        check("undefined label rejected", !r5.ok)

        val r6 = assemble("; comment only\n\n   ")
        check("comment/blank only -> 0 instructions", r6.ok && r6.instructionCount == 0)
    }

    // ---- VM ----
    run {
        println("\n[vm]")
        val vm = Vm()
        val r = assemble("""
            MOV A, #10
            MOV X, #3
            ADD #5        ; A = 15
            MOV Y, A      ; Y = 15
            MOV X, #0
            MOV (X), A    ; mem[0] = 15
            MOV X, #0
            MOV A, (X)    ; A = 15
            CMP #15
            JZ  ok        ; taken
            ADD #100      ; (skipped)
            ok: MOV A, #99
            PUSH A
            PUSH X
            POP  X
            POP  A
            CALL sub
            JMP  end
            sub: ADD #1    ; 99 + 1 = 100
            RET
            end: NOP
        """.trimIndent())
        check("test program assembles", r.ok, r.errors.toString())
        vm.load(r.code)
        repeat(18) { vm.step(NullEnv) }
        check("A == 100 after ADD/RET", vm.a == 100, "A=${vm.a} op=${vm.lastOp}")
        check("mem[0] == 15 via (X)", vm.mem[0].toInt() and 0xFF == 15)
    }

    run {
        println("\n[vm: branches & flags]")
        val vm = Vm()
        val r = assemble("""
            MOV A, #3
            CMP #5
            JG  no
            JGE no
            JL  yes
            JMP no
            yes: MOV A, #1
            no: NOP
        """.trimIndent())
        vm.load(r.code)
        repeat(7) { vm.step(NullEnv) }
        check("JL taken for 3 < 5", vm.a == 1, "A=${vm.a}")

        val vm2 = Vm()
        val r2 = assemble("""
            MOV A, #250
            ADD #10        ; wraps -> 4, Z clear
            TST A
            JZ  no
            JNZ  yes
            yes: NOP
            no: NOP
        """.trimIndent())
        vm2.load(r2.code)
        repeat(6) { vm2.step(NullEnv) }
        check("8-bit wrap 250+10 == 4", vm2.a == 4, "A=${vm2.a}")
    }

    run {
        println("\n[vm: stack]")
        val vm = Vm()
        val r = assemble("""
            MOV A, #11
            MOV X, #22
            MOV Y, #33
            PUSH A
            PUSH X
            PUSH Y
            POP  A
            POP  X
            POP  Y
            NOP
        """.trimIndent())
        vm.load(r.code)
        repeat(10) { vm.step(NullEnv) }
        // stack is LIFO: Y was pushed last, so it pops into A first
        check("PUSH/POP round-trips (LIFO)", vm.a == 33 && vm.x == 22 && vm.y == 11,
            "A=${vm.a} X=${vm.x} Y=${vm.y}")
    }

    // ---- world ----
    run {
        println("\n[world]")
        val w = World()
        val a = Bot("A", 'A', 2, 5, 5, DIR_E, """
            SHOOT
            WAIT
        """.trimIndent())
        val b = Bot("B", 'B', 4, 6, 5, DIR_W, """
            SHIELD
            WAIT
        """.trimIndent())
        w.add(a); w.add(b)
        w.tickOnce()
        check("adjacent SHOOT is a hit (shield absorbs)", b.hp == MAX_HP && a.shots == 1,
            "b.hp=${b.hp} a.shots=${a.shots}")

        val w2 = World()
        val a2 = Bot("A", 'A', 2, 5, 5, DIR_E, """
            SHOOT
            WAIT
        """.trimIndent())
        val b2 = Bot("B", 'B', 4, 6, 5, DIR_W, """
            WAIT
            WAIT
        """.trimIndent())
        w2.add(a2); w2.add(b2)
        w2.tickOnce()
        check("adjacent SHOOT deals 10 damage", b2.hp == 90 && a2.hits == 1, "b2.hp=${b2.hp}")

        val w3 = World()
        val a3 = Bot("A", 'A', 2, 0, 0, DIR_W, """
            MOVE
            WAIT
        """.trimIndent())
        w3.add(a3)
        w3.tickOnce()
        check("wall blocks MOVE", a3.px == 0 && a3.py == 0, "pos=${a3.px},${a3.py}")

        val w4 = World()
        val a4 = Bot("A", 'A', 2, 0, 0, DIR_E, """
            SHOOT
            WAIT
        """.trimIndent())
        val b4 = Bot("B", 'B', 4, 4, 0, DIR_W, """
            WAIT
            WAIT
        """.trimIndent())
        w4.add(a4); w4.add(b4)
        repeat(3) { w4.tickOnce() }   // projectile takes 3 ticks to travel 4 cells
        check("projectile flies & hits at range 4", b4.hp == 90, "b4.hp=${b4.hp}")
    }

    // ---- full fights ----
    run {
        println("\n[fights]")
        val h = Bot("HUNTER", 'H', 2, 10, 15, DIR_N, Presets.HUNTER)
        val t = Bot("TURTLE", 'T', 4, 10, 5, DIR_S, Presets.TURTLE)
        val w = Simulator.runFight(listOf(h, t), maxTicks = 200)
        check("HUNTER vs TURTLE finishes with a winner", w.finished && w.winner != null,
            "finished=${w.finished} winner=${w.winner?.name}")
        check("fight produced stats", h.dmgDealt + t.dmgDealt > 0 || w.winner == t,
            "hDealt=${h.dmgDealt} tDealt=${t.dmgDealt}")
        println("  → " + Simulator.stats(w))

        val bots = Presets.all().map {
            Bot(it.name, it.glyph, it.color, it.x, it.y, DIR_N, it.firmware)
        }
        val w2 = Simulator.runFight(bots, maxTicks = 200)
        check("5-bot melee finishes", w2.finished, "finished=${w2.finished}")
        println("  → " + Simulator.stats(w2))
        println("  → final arena:")
        println(TextGrid.render(w2).lines().joinToString("\n  ") { "  $it" })
    }

    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}
