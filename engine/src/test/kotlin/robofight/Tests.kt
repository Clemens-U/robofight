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
    override fun heatLocked(op: Int) = false
    override fun shoot() {}
    override fun move() {}
    override fun shield() {}
    override fun turn(delta: Int) {}
}

fun main() {
    println("== Opcode Arena engine tests ==")

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

    // ---- execution monitor: source line map & current instruction ----
    run {
        println("\n[listing: lineOf & currentInsn]")
        // lineOf: instruction i maps to the source line that produced it,
        // skipping blanks/comments and counting label+instruction as one line.
        val r = assemble(
            ";\n" +                        // line 0 (comment)
            "start: MOV A,#5\n" +          // line 1 (label + insn)
            "ADD #1\n" +                   // line 2
            ";\n" +                        // line 3 (comment)
            "NOP\n"                        // line 4
        )
        check("lineOf.size == instructionCount (3)",
            r.ok && r.lineOf.size == 3 && r.instructionCount == 3,
            "count=${r.instructionCount} size=${r.lineOf.size}")
        check("lineOf maps each instruction to its source line",
            r.lineOf.contentEquals(intArrayOf(1, 2, 4)), "got ${r.lineOf.toList()}")

        // currentInsn: one index per single-tick op, -1 before the first step.
        val vm = Vm()
        val r2 = assemble("MOV A,#1\nMOV X,#2\nMOV Y,#3\n")
        vm.load(r2.code)
        check("currentInsn is -1 before the first step", vm.currentInsn == -1)
        vm.step(NullEnv)
        check("currentInsn == 0 after the first op", vm.currentInsn == 0, "got ${vm.currentInsn}")
        vm.step(NullEnv)
        vm.step(NullEnv)
        check("currentInsn == 2 after three ops", vm.currentInsn == 2, "got ${vm.currentInsn}")

        // currentInsn stays pinned on the SHOOT line for all 3 ticks of the op.
        val vm2 = Vm()
        vm2.load(assemble("SHOOT\nWAIT\n").code)
        vm2.step(NullEnv)
        check("SHOOT tick 1 -> currentInsn 0", vm2.currentInsn == 0, "got ${vm2.currentInsn}")
        vm2.step(NullEnv)
        vm2.step(NullEnv)
        check("SHOOT ticks 2-3 hold line 0 (multi-tick)", vm2.currentInsn == 0, "got ${vm2.currentInsn}")
        vm2.step(NullEnv)   // WAIT
        check("WAIT after SHOOT -> currentInsn 1", vm2.currentInsn == 1, "got ${vm2.currentInsn}")

        // currentInsn follows a forward branch to its TARGET instruction.
        val vm3 = Vm()
        val r3 = assemble("MOV A,#1\nJMP skip\nNOP\nskip: NOP\n")
        vm3.load(r3.code)
        vm3.step(NullEnv)   // MOV (insn 0)
        vm3.step(NullEnv)   // JMP skip (insn 1)
        vm3.step(NullEnv)   // lands on skip (insn 3)
        check("branch lands on its target instruction", vm3.currentInsn == 3, "got ${vm3.currentInsn}")
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
        repeat(3) { w.tickOnce() }   // SHOOT now occupies 3 ticks
        check("adjacent SHOOT is a hit (shield absorbs)", b.hp == MAX_HP && a.shots == 1,
            "b.hp=${b.hp} a.shots=${a.shots} tick=${w.tick}")

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
        repeat(3) { w2.tickOnce() }   // SHOOT now occupies 3 ticks
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
        // SHOOT occupies ticks 1–3 (shot leaves on tick 3), the projectile
        // then needs 2 more resolve steps to reach cell 4.
        repeat(5) { w4.tickOnce() }
        check("projectile flies & hits at range 4", b4.hp == 90, "b4.hp=${b4.hp} tick=${w4.tick}")

        // Heat is cumulative on a 0–100 scale: 5 SHOOTs reach the max, and
        // heat only cools on ticks where no SHOOT/SHIELD added heat.
        val w5 = World()
        val a5 = Bot("A", 'A', 2, 0, 0, DIR_E, "WAIT\n")
        val d5 = Bot("D", 'D', 4, 19, 0, DIR_W, "WAIT\n")
        w5.add(a5); w5.add(d5)
        repeat(5) { a5.shoot() }
        check("5 SHOOTs reach maximum heat", a5.heat == HEAT_MAX && a5.shots == 5,
            "heat=${a5.heat} shots=${a5.shots}")
        a5.shoot()                     // 100+20 > 100 → locked out
        check("SHOOT locked out at max heat", a5.shots == 5 && a5.heat == HEAT_MAX,
            "shots=${a5.shots} heat=${a5.heat}")
        repeat(10) { w5.tickOnce() }   // 10 idle ticks cool 2 each: 100 → 80
        a5.shoot()                     // 80+20 = 100 → works again
        check("SHOOT works again at exactly 80 heat", a5.shots == 6 && a5.heat == HEAT_MAX,
            "shots=${a5.shots} heat=${a5.heat}")

        // Heat cools 2 per idle tick — 100 → 0 in 50 idle ticks (5 s at 100 ms/tick)
        val w5b = World()
        val a5b = Bot("A", 'A', 2, 5, 0, DIR_E, "WAIT\n")
        val d5b = Bot("D", 'D', 4, 19, 0, DIR_W, "WAIT\n")
        w5b.add(a5b); w5b.add(d5b)
        a5b.heat = HEAT_MAX
        var coolTicks = 0
        while (a5b.heat > 0 && coolTicks < 200) { w5b.tickOnce(); coolTicks++ }
        check("heat decays 100→0 in 50 idle ticks", a5b.heat == 0 && coolTicks == 50,
            "heat=${a5b.heat} ticks=$coolTicks")

        // SHIELD piles heat the same way: 5 shields reach the cap, it
        // collapses on the ticks it would overflow, and works again at 80
        val w6 = World()
        val s = Bot("S", 'S', 4, 0, 0, DIR_S, "WAIT\n")
        val d6 = Bot("D", 'D', 4, 19, 0, DIR_W, "WAIT\n")
        w6.add(s); w6.add(d6)
        repeat(5) { s.shield() }
        check("5 SHIELDs reach maximum heat", s.heat == HEAT_MAX, "heat=${s.heat}")
        var collapses = 0
        repeat(9) {
            w6.tickOnce()   // idle tick cools 2
            s.shield()      // still > 80 → collapsed, no protection
            if (!s.shielded && s.heat > 80) collapses++
        }
        check("SHIELD collapses while heat > 80", collapses == 9,
            "collapses=$collapses heat=${s.heat}")
        w6.tickOnce()       // 82 → 80
        s.shield()          // 80+20 = 100 → works again
        check("SHIELD works again at exactly 80 heat", s.shielded && s.heat == HEAT_MAX,
            "shielded=${s.shielded} heat=${s.heat}")

        // A real firmware loop that fires 5 SHOOTs in a row reaches the
        // max (as the spec says), and the gun is locked out until the heat
        // cools back to 80.
        // The dummy sits off the firing line (19,19) so it never dies and
        // freezes the world mid-window.
        val w7 = World()
        val a7 = Bot("A", 'A', 2, 5, 0, DIR_E, """
            burst:  SHOOT
            SHOOT
            SHOOT
            SHOOT
            SHOOT
            JMP    burst
        """.trimIndent())
        val d7 = Bot("D", 'D', 4, 19, 19, DIR_N, "WAIT\n")
        w7.add(a7); w7.add(d7)
        var guard = 0
        while (a7.heat < HEAT_MAX && guard < 200) { w7.tickOnce(); guard++ }
        check("5 burst SHOOTs reach maximum heat (15 ticks, no cooling in between)",
            a7.heat == HEAT_MAX, "heat=${a7.heat} tick=${w7.tick}")
        val shotsAtMax = a7.shots
        repeat(15) { w7.tickOnce() }
        check("max heat locks the gun until it cools to 80",
            a7.shots - shotsAtMax == 1, "extra=${a7.shots - shotsAtMax}")

        // A normal firmware loop necessarily spends a tick on JMP between
        // shots. It must still accumulate heat and eventually take overheat
        // damage without tests manually calling shoot() or pinning heat.
        val w7b = World()
        val a7b = Bot("A", 'A', 2, 5, 0, DIR_E, """
            loop: SHOOT
            JMP loop
        """.trimIndent())
        val d7b = Bot("D", 'D', 4, 19, 19, DIR_N, "WAIT\n")
        w7b.add(a7b); w7b.add(d7b)
        repeat(72) { w7b.tickOnce() }
        // SHOOT/JMP now runs a 4-tick shot cycle (3 SHOOT + 1 JMP) with
        // lockouts at >80 heat: ~9 shots in 72 ticks, heat sits >80 from
        // tick 67 onward.
        check("SHOOT/JMP loop accumulates visible heat", a7b.heat > HEAT_DAMAGE_THRESHOLD,
            "heat=${a7b.heat} shots=${a7b.shots}")
        check("SHOOT/JMP loop takes organic overheat damage", a7b.hp < MAX_HP,
            "hp=${a7b.hp} heat=${a7b.heat}")

        // TURTLE has five control-flow instructions between heat actions.
        // Its rapid-fire and defensive loops must now build meaningful heat
        // rather than cooling away the entire action cost between uses.
        val w7c = World()
        val firingTurtle = Bot("T", 'T', 4, 5, 5, DIR_E, Presets.TURTLE)
        val adjacentDummy = Bot("D", 'D', 2, 6, 5, DIR_W, "WAIT\n")
        w7c.add(firingTurtle); w7c.add(adjacentDummy)
        repeat(80) { w7c.tickOnce() }   // TURTLE loop is ~5x slower in ticks
        check("TURTLE rapid fire accumulates heat", firingTurtle.heat > HEAT_DAMAGE_THRESHOLD,
            "heat=${firingTurtle.heat} shots=${firingTurtle.shots}")

        val w7d = World()
        val shieldingTurtle = Bot("T", 'T', 4, 0, 0, DIR_E, Presets.TURTLE)
        val distantDummy = Bot("D", 'D', 2, 19, 19, DIR_W, "WAIT\n")
        w7d.add(shieldingTurtle); w7d.add(distantDummy)
        repeat(80) { w7d.tickOnce() }   // TURTLE loop is ~5x slower in ticks
        check("TURTLE repeated shielding accumulates heat", shieldingTurtle.heat > HEAT_DAMAGE_THRESHOLD,
            "heat=${shieldingTurtle.heat}")

        // Heat above 80% damages the bot at 2 HP/sec. At the engine's
        // 10-ticks/sec model that is 0.2 HP/tick → 1 HP every 5 ticks.
        // Pin heat at 100 (re-set before each tick since WAIT would cool it)
        // so the rate is exact and deterministic.
        val w8 = World()
        val a8 = Bot("A", 'A', 2, 5, 5, DIR_E, "WAIT\n")
        val d8 = Bot("D", 'D', 4, 19, 0, DIR_W, "WAIT\n")
        w8.add(a8); w8.add(d8)
        a8.heat = HEAT_MAX
        repeat(5) { a8.heat = HEAT_MAX; w8.tickOnce() }
        check("overheat: 1 HP lost after 5 ticks at heat 100",
            a8.hp == MAX_HP - 1, "hp=${a8.hp} heat=${a8.heat}")
        repeat(5) { a8.heat = HEAT_MAX; w8.tickOnce() }
        check("overheat: 2 HP lost after 10 ticks (2 HP/sec at 10 ticks/sec)",
            a8.hp == MAX_HP - 2, "hp=${a8.hp} heat=${a8.heat}")

        // Heat at exactly 80 (the threshold) does NOT damage — "above 80%"
        // means strictly greater than 80.
        val w9 = World()
        val a9 = Bot("A", 'A', 2, 5, 5, DIR_E, "WAIT\n")
        val d9 = Bot("D", 'D', 4, 19, 0, DIR_W, "WAIT\n")
        w9.add(a9); w9.add(d9)
        a9.heat = HEAT_DAMAGE_THRESHOLD
        repeat(10) { w9.tickOnce() }
        check("heat at 80 (not above) → no damage",
            a9.hp == MAX_HP, "hp=${a9.hp} heat=${a9.heat}")

        // Heat just below 80 never damages — accumulator drains each tick.
        val w10 = World()
        val a10 = Bot("A", 'A', 2, 5, 5, DIR_E, "WAIT\n")
        val d10 = Bot("D", 'D', 4, 19, 0, DIR_W, "WAIT\n")
        w10.add(a10); w10.add(d10)
        a10.heat = HEAT_DAMAGE_THRESHOLD - 1
        repeat(10) { w10.tickOnce() }
        check("heat below 80 → no damage",
            a10.hp == MAX_HP, "hp=${a10.hp} heat=${a10.heat}")

        // Brief drops to the safe range stop new damage, but do not discard
        // already accumulated fractional exposure. Otherwise repeated short
        // heat spikes can avoid overheat damage forever.
        val w10b = World()
        val a10b = Bot("A", 'A', 2, 5, 5, DIR_E, "WAIT\n")
        val d10b = Bot("D", 'D', 4, 19, 0, DIR_W, "WAIT\n")
        w10b.add(a10b); w10b.add(d10b)
        repeat(4) { a10b.heat = HEAT_MAX; w10b.tickOnce() }
        a10b.heat = HEAT_DAMAGE_THRESHOLD + HEAT_COOL
        w10b.tickOnce()
        a10b.heat = HEAT_MAX
        w10b.tickOnce()
        check("fractional overheat survives a safe tick", a10b.hp == MAX_HP - 1,
            "hp=${a10b.hp} pending=${a10b.heatOverload}")

        // A bot can die from sustained overheating: 5 HP at 0.2 HP/tick
        // → death after 25 ticks (5 × 5).
        val w11 = World()
        val a11 = Bot("A", 'A', 2, 5, 5, DIR_E, "WAIT\n")
        val d11 = Bot("D", 'D', 4, 19, 0, DIR_W, "WAIT\n")
        w11.add(a11); w11.add(d11)
        a11.heat = HEAT_MAX
        a11.hp = 5
        var died11 = false
        repeat(30) {
            a11.heat = HEAT_MAX
            if (!w11.finished) {
                w11.tickOnce()
                if (!a11.alive) died11 = true
            }
        }
        check("overheat kills a bot at 5 HP after ~25 ticks",
            died11 && a11.hp == 0, "alive=${a11.alive} hp=${a11.hp}")
    }

    // ---- multi-tick ops (per-opcode tick cost) ----
    run {
        println("\n[multitick]")
        // Cost table: SHOOT/SHIELD = 3 ticks, MOVE = 2, everything else 1.
        check("ISA costs (SHOOT=3, SHIELD=3, MOVE=2, WAIT=1, IN=1)",
            robofight.isa.ISA.cost(0xB0) == 3 && robofight.isa.ISA.cost(0xB5) == 3 &&
                robofight.isa.ISA.cost(0xB4) == 2 && robofight.isa.ISA.cost(0xB6) == 1 &&
                robofight.isa.ISA.cost(0xB7) == 1)

        // Recording env: pure-VM view of when the deferred side effect lands.
        var shotTicks = ArrayList<Int>()
        var tickNo = 0
        val recEnv = object : Env {
            override fun portIn(port: Int) = 0
            override fun portOut(port: Int, value: Int) {}
            override fun heatLocked(op: Int) = false
            override fun shoot() { shotTicks.add(tickNo) }
            override fun move() {}
            override fun shield() {}
            override fun turn(delta: Int) {}
        }
        val rv = Vm()
        rv.load(assemble("SHOOT\nWAIT").code)
        repeat(4) { tickNo++; rv.step(recEnv) }
        check("SHOOT occupies 3 ticks, side effect on the final tick",
            shotTicks == listOf(3), "shots on ticks $shotTicks")
        check("VM idle after multi-tick op completes", !rv.inProgress && rv.lastOp == "WAIT")

        // 5 back-to-back SHOOTs (3-tick ops, nothing between them) must
        // reach exactly 100 heat with ZERO cooling in between — the burst
        // lockout invariant survives the new timing. Pass = 5×3 ticks;
        // the closing JMP (tick 16) is the first tick allowed to cool.
        val w = World()
        val a = Bot("A", 'A', 2, 0, 0, DIR_E, """
            burst: SHOOT
            SHOOT
            SHOOT
            SHOOT
            SHOOT
            JMP    burst
        """.trimIndent())
        val d = Bot("D", 'D', 4, 19, 19, DIR_N, "WAIT\n")
        w.add(a); w.add(d)
        var guard = 0
        while (a.heat < HEAT_MAX && guard < 100) { w.tickOnce(); guard++ }
        check("5 consecutive SHOOTs hit exactly 100 heat on tick 15",
            a.heat == HEAT_MAX && w.tick == 15 && a.shots == 5,
            "heat=${a.heat} tick=${w.tick} shots=${a.shots}")
        // Second pass: the gun is locked. Locked SHOOTs must collapse to
        // 1-tick no-ops that cool at the normal rate — if they occupied 3
        // ticks without cooling, heat could never drop back to 80 and the
        // 6th shot would never fire. Hand-traced: locked on 17–21 and 23–26
        // (9 ticks; 22 is the JMP), the 6th shot spans 27–29 (heat 78+20=98).
        var lockedCount = 0
        repeat(14) {
            w.tickOnce()
            if (a.vm.lastOp.contains("locked")) lockedCount++
        }
        check("locked SHOOTs are 1-tick no-ops: 9 locked, 6th shot on tick 29",
            lockedCount == 9 && a.shots == 6 && a.heat == 98 && w.tick == 29,
            "locked=$lockedCount shots=${a.shots} heat=${a.heat} tick=${w.tick}")

        // MOVE occupies 2 ticks: the chassis moves only on the final tick.
        val wm = World()
        val m = Bot("M", 'M', 2, 3, 0, DIR_E, "MOVE\nWAIT\nWAIT\n")
        val dm = Bot("D", 'D', 4, 19, 19, DIR_N, "WAIT\n")
        wm.add(m); wm.add(dm)
        wm.tickOnce()
        check("MOVE: position unchanged on first tick", m.px == 3, "px=${m.px}")
        wm.tickOnce()
        check("MOVE: position advances on second tick", m.px == 4, "px=${m.px}")
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

        // HUNTER vs adjacent WAIT bot: must fire, not oscillate.
        // Before the fix, AHEAD=1 (enemy adjacent) triggered retreat,
        // causing an infinite retreat→turn-back→retreat loop with 0 shots.
        val hw = World()
        val hh = Bot("H", 'H', 2, 5, 5, DIR_E, Presets.HUNTER)
        val hd = Bot("D", 'D', 4, 6, 5, DIR_W, "WAIT\n")
        hw.add(hh); hw.add(hd)
        repeat(20) { hw.tickOnce() }
        check("HUNTER fires at adjacent enemy (not retreat loop)",
            hh.shots > 0, "shots=${hh.shots} heat=${hh.heat}")
        check("adjacent enemy takes damage from HUNTER",
            hd.hp < MAX_HP, "hd.hp=${hd.hp}")
    }

    // ---- AHEAD sensor & RNG seeding ----
    run {
        println("\n[sensors: AHEAD & RAND]")
        val w = World()
        val a = Bot("A", 'A', 2, 0, 5, DIR_W, """
            WAIT
        """.trimIndent())
        val b = Bot("B", 'B', 4, 5, 5, DIR_W, """
            WAIT
        """.trimIndent())
        w.add(a); w.add(b)
        // A at x=0 facing W → cell ahead is (-1,5), off-grid → wall → AHEAD=1.
        // B at x=5 facing W → cell ahead is (4,5), free (A is at x=0) → 0.
        check("AHEAD=1 at wall", a.portIn(11) == 1, "got ${a.portIn(11)}")
        check("AHEAD=0 when cell ahead clear", b.portIn(11) == 0, "got ${b.portIn(11)}")
        b.px = 1
        check("AHEAD=1 when enemy ahead", b.portIn(11) == 1, "got ${b.portIn(11)}")

        // setSeed is deterministic; a different seed gives a different stream.
        val w1 = World(); w1.setSeed(42)
        val w2 = World(); w2.setSeed(42)
        val w3 = World(); w3.setSeed(43)
        val s1 = (1..3).map { w1.nextRand() }
        val s2 = (1..3).map { w2.nextRand() }
        val s3 = (1..3).map { w3.nextRand() }
        check("same seed → same RAND stream", s1 == s2, "$s1 vs $s2")
        check("different seed → different RAND stream", s1 != s3, "$s1 vs $s3")
        check("RAND in range 0..255", s1.all { it in 0..255 }, "$s1")

        // DIST = Manhattan distance to nearest enemy (0 = none).
        // Adjacent bots → 1; the port never returns 0 for a living enemy
        // at distance 1 (a common misconception — see HUNTER retreat bug).
        val wa = World()
        val ba = Bot("A", 'A', 2, 5, 5, DIR_E, "WAIT\n")
        val b1 = Bot("B", 'B', 4, 6, 5, DIR_W, "WAIT\n")   // adjacent (dist 1)
        val b2 = Bot("C", 'C', 6, 7, 5, DIR_W, "WAIT\n")   // 2 cells away (dist 2)
        wa.add(ba); wa.add(b1); wa.add(b2)
        check("DIST=1 for adjacent enemy", ba.portIn(4) == 1, "got ${ba.portIn(4)}")
        b1.hp = 0; b1.alive = false                    // kill b1 (nearest)
        check("DIST skips dead enemy, returns next nearest", ba.portIn(4) == 2, "got ${ba.portIn(4)}")

        // Single bot → no enemy → DIST=0.
        val wb = World()
        val solo = Bot("S", 'S', 2, 5, 5, DIR_E, "WAIT\n")
        wb.add(solo)
        check("DIST=0 when no enemy", solo.portIn(4) == 0, "got ${solo.portIn(4)}")
    }

    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}
