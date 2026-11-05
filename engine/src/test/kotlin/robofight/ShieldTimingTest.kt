package robofight

import robofight.assembler.assemble
import robofight.vm.Env
import robofight.vm.Vm
import robofight.world.*

/**
 * Shield timing suite for the approved contract in RF8-PLATFORM-SPEC.md §6.1
 * (PLAN-RF8-MULTIAPP.md §5 required tests):
 *
 *   SHIELD occupies TWO activation ticks; the completion tick (tick 2) arms a
 *   five-tick protected window that is decremented only AFTER that tick's
 *   world resolution; code execution resumes on tick 3; re-activation
 *   refreshes the window to five (never stacks); heat is added once (+20) at
 *   completion and cooling runs normally during the window; a locked-out
 *   SHIELD stays a one-tick no-op that cools; IN SHIELD is 1 exactly for the
 *   active window.
 *
 * Hits are driven by spawning projectiles directly onto the victim's cell
 * right before the tick whose resolution should resolve them, so arrival
 * ticks are exact. The firing bot sits far away and only WAITs, so it never
 * interferes and keeps the world from auto-ending.
 */

private object RecordingEnv : Env {
    val shieldTicks = ArrayList<Int>()
    var tickNo = 0
    override fun portIn(port: Int) = 0
    override fun portOut(port: Int, value: Int) {}
    override fun heatLocked(op: Int) = false
    override fun shoot() {}
    override fun move() {}
    override fun shield() { shieldTicks.add(tickNo) }
    override fun turn(delta: Int) {}
}

fun main() {
    println("== shield timing (RF8-PLATFORM-SPEC §6.1) ==")

    // ---- VM level: two activation ticks, side effect on the final tick ----
    val rv = Vm()
    rv.load(assemble("SHIELD\nWAIT").code)
    RecordingEnv.shieldTicks.clear()
    repeat(3) { RecordingEnv.tickNo++; rv.step(RecordingEnv) }
    check("SHIELD occupies 2 ticks, side effect on the final tick",
        RecordingEnv.shieldTicks == listOf(2), "shield fired on ticks ${RecordingEnv.shieldTicks}")
    check("the instruction after SHIELD executes on tick 3", rv.lastOp == "WAIT", "lastOp=${rv.lastOp}")

    // ---- exact seven-tick timeline with a hit resolved on every tick ----
    run {
        val w = World()
        val owner = Bot("O", 'O', 2, 19, 19, DIR_W, "loop: WAIT\nJMP loop\n")
        val victim = Bot("V", 'V', 4, 5, 5, DIR_E, "SHIELD\nMOV A,#7\nloop: WAIT\nJMP loop\n")
        w.add(owner); w.add(victim)
        fun hitOnThisTick() { w.spawnProjectile(owner, 5, 5, DIR_E) }

        val portShieldReads = ArrayList<Int>()   // IN SHIELD as firmware sees it, ticks 1..7
        val durAfter = ArrayList<Int>()
        val hpAfter = ArrayList<Int>()
        for (tick in 1..7) {
            portShieldReads.add(victim.portIn(7))
            hitOnThisTick()
            if (tick == 3) hitOnThisTick()       // two projectiles on one active tick
            w.tickOnce()
            durAfter.add(victim.shieldTicksRemaining)
            hpAfter.add(victim.hp)
        }
        check("tick 1 (activation tick 1): projectile damages the bot",
            hpAfter[0] == MAX_HP - DAMAGE, "hp=${hpAfter[0]} tick=${w.tick}")
        check("tick 1: no protected window yet", durAfter[0] == 0, "dur=${durAfter[0]}")
        check("tick 2 (activation completion): projectile blocked",
            hpAfter[1] == MAX_HP - DAMAGE, "hp=${hpAfter[1]}")
        check("tick 2: four protected resolutions remain after resolution",
            durAfter[1] == 4, "dur=${durAfter[1]}")
        check("code execution resumes on tick 3 (MOV runs)", victim.vm.a == 7, "A=${victim.vm.a}")
        check("ticks 3-6: all projectiles blocked (incl. two on tick 3)",
            hpAfter.subList(2, 6) == listOf(MAX_HP - DAMAGE, MAX_HP - DAMAGE, MAX_HP - DAMAGE, MAX_HP - DAMAGE),
            "hp=$hpAfter")
        check("tick 6: last protected resolution, none remain after",
            durAfter[5] == 0, "dur=${durAfter[5]}")
        check("tick 7: projectile damages the bot again",
            hpAfter[6] == MAX_HP - 2 * DAMAGE, "hp=${hpAfter[6]}")
        check("IN SHIELD is 1 exactly for the firmware-visible active window",
            portShieldReads == listOf(0, 0, 1, 1, 1, 1, 0), "reads=$portShieldReads")
    }

    // ---- heat: +20 once at completion, cooling runs during the window ----
    run {
        val w = World()
        val s = Bot("S", 'S', 4, 0, 0, DIR_S, "SHIELD\nloop: WAIT\nJMP loop\n")
        val d = Bot("D", 'D', 2, 19, 0, DIR_W, "loop: WAIT\nJMP loop\n")
        w.add(s); w.add(d)
        repeat(1) { w.tickOnce() }   // tick 1: decode tick, no heat yet
        check("activation tick 1: no heat added", s.heat == 0, "heat=${s.heat}")
        w.tickOnce()                 // tick 2: completion tick adds heat once
        check("completion tick adds exactly +20 heat", s.heat == HEAT_SHIELD, "heat=${s.heat}")
        repeat(5) { w.tickOnce() }   // ticks 3..7: window alone does not suppress cooling
        check("shield window alone does not suppress cooling (5 cool ticks)",
            s.heat == HEAT_SHIELD - 5 * HEAT_COOL, "heat=${s.heat}")
    }

    // ---- re-activation refreshes to five and does not stack ----
    run {
        val w = World()
        val owner = Bot("O", 'O', 2, 19, 19, DIR_W, "loop: WAIT\nJMP loop\n")
        val s = Bot("S", 'S', 4, 5, 5, DIR_E, "SHIELD\nWAIT\nSHIELD\nloop: WAIT\nJMP loop\n")
        w.add(owner); w.add(s)
        w.tickOnce()   // tick 1: SHIELD (1/2)
        w.tickOnce()   // tick 2: completes → window 5, decremented to 4
        check("first activation arms window 5 (4 remain after tick 2)",
            s.shieldTicksRemaining == 4, "dur=${s.shieldTicksRemaining}")
        w.tickOnce()   // tick 3: WAIT (3 remain)
        w.tickOnce()   // tick 4: SHIELD (1/2) (2 remain)
        w.spawnProjectile(owner, 5, 5, DIR_E)
        w.tickOnce()   // tick 5: re-activation completes while window still active
        check("re-activation refreshes to five instead of stacking",
            s.shieldTicksRemaining == SHIELD_DURATION - 1, "dur=${s.shieldTicksRemaining}")
        check("re-activation adds its own +20 heat (only the WAIT tick in between cooled)",
            s.heat == 2 * HEAT_SHIELD - HEAT_COOL, "heat=${s.heat}")
        check("hit during re-activation tick blocked", s.hp == MAX_HP, "hp=${s.hp}")
    }

    // ---- locked-out SHIELD: one-tick no-op that cools normally ----
    run {
        val w = World()
        val s = Bot("S", 'S', 4, 0, 0, DIR_S, "SHIELD\nMOV A,#9\nloop: WAIT\nJMP loop\n")
        val d = Bot("D", 'D', 2, 19, 0, DIR_W, "loop: WAIT\nJMP loop\n")
        w.add(s); w.add(d)
        s.heat = 82   // 82 + 20 > 100 → locked out at activation start
        w.tickOnce()  // tick 1: locked collapse
        check("locked SHIELD is a one-tick no-op (next instruction runs on tick 2)",
            !s.vm.inProgress && s.shieldTicksRemaining == 0,
            "inProgress=${s.vm.inProgress} dur=${s.shieldTicksRemaining}")
        check("locked SHIELD cools normally", s.heat == 80, "heat=${s.heat}")
        w.tickOnce()  // tick 2: MOV runs → collapse really was 1 tick
        check("collapsed SHIELD occupies exactly one tick", s.vm.a == 9, "A=${s.vm.a}")
    }
}
