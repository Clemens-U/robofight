package robofight.fireline

import robofight.vm.Env
import robofight.vm.Vm

/**
 * The FIRELINE rover: physical body state + the RF-8 [Env] boundary
 * (FIRELINE-DESIGN.md §3.5, §5).
 *
 * The rover owns only its own state; every world question (terrain, targets,
 * sensors, packets) is answered by [FirelineWorld], which owns the authoritative
 * tick order. `MOVE`/`SHOOT`/`SHIELD` side effects are invoked by the VM on the
 * instruction's FINAL tick (RF8-PLATFORM-SPEC.md §6), which is exactly step 6 of
 * the FIRELINE tick order (§8).
 */
class Rover(private val world: FirelineWorld, startingWater: Int) : Env {
    val vm = Vm()

    var x = 0; var y = 0; var facing = DIR_E
    var hp = MAX_HP
    var heat = 0

    /** Suppressant tank 0..100 (§3.5/§3.6). */
    var water = startingWater

    /**
     * Shield state is a DURATION counter, not a per-tick flag
     * (RF8-PLATFORM-SPEC.md §6.1): armed to [SHIELD_DURATION] when the
     * two-tick activation completes, decremented by the world only AFTER that
     * tick's world resolution — giving the approved five-tick window.
     */
    var shieldTicksRemaining = 0
    val shielded: Boolean get() = shieldTicksRemaining > 0

    /** Set by a heat action that completed this tick; consumed by the cooling gate. */
    var heatedThisTick = false

    /** Fractional overload damage pending, in exact milli-HP (see §5 heat rules). */
    var heatOverloadMilli = 0

    var destroyed = false

    // statistics for FirelineResult (§7.5)
    var shots = 0
    var shotsDry = 0
    var waterUsed = 0
    var shieldsRaised = 0
    var distanceMoved = 0

    fun place(x: Int, y: Int, dir: Int) {
        this.x = x; this.y = y; this.facing = dir and 3
    }

    // ---- Env: ports ----
    override fun portIn(port: Int): Int = world.sensor(port)

    /**
     * §6.2: `DIR` writes set facing to `A & 3`. FIRELINE v1 defines no
     * scenario-specific writable output port (FIRELINE-DESIGN.md §5), so every
     * other port — official or unknown — ignores the write without faulting
     * (RF8-PLATFORM-SPEC.md §4.2).
     */
    override fun portOut(port: Int, value: Int) {
        if (port == 1) facing = value and 3
    }

    /** Heat lockout pre-check for the VM's multi-tick heat ops (§6.1). */
    override fun heatLocked(op: Int): Boolean {
        val cost = if (op == 0) HEAT_SHOOT else HEAT_SHIELD
        return heat + cost > HEAT_MAX
    }

    // ---- Env: physical instructions ----
    override fun move() {
        if (destroyed) return
        val nx = x + dirDx(facing)
        val ny = y + dirDy(facing)
        // §3.5: a failed move is a silent no-op after its normal two-tick cost.
        // Walls and protected assets block; fire and civilians do not (§3.2/§3.4).
        if (!inBounds(nx, ny)) return
        if (world.layout.wallBetween(x, y, nx, ny)) return
        if (!terrainPassable(world.terrainAt(nx, ny))) return
        x = nx; y = ny
        distanceMoved++
        world.noteRoverMoved()
    }

    override fun turn(delta: Int) {
        facing = ((facing + delta) % 4 + 4) % 4
    }

    override fun shoot() {
        if (destroyed) return
        // §3.6: below 10 water nothing happens — no packet, no water removed,
        // no heat added — but the instruction still occupied its three ticks.
        if (water < WATER_PER_SHOT) {
            shotsDry++
            world.noteDryShot()
            return
        }
        water -= WATER_PER_SHOT
        waterUsed += WATER_PER_SHOT
        heat = (heat + HEAT_SHOOT).coerceAtMost(HEAT_MAX)
        heatedThisTick = true
        shots++
        world.spawnWaterPacket(this)
    }

    override fun shield() {
        if (destroyed) return
        // RF8-PLATFORM-SPEC.md §6.1 / FIRELINE-DESIGN.md §5: SHIELD is a
        // two-tick ACTIVATION; on completion the thermal shield is armed to
        // SHIELD_DURATION (five resolution ticks, re-arming resets rather than
        // extends) and adds 20 heat. The world decrements the duration only in
        // tick-order step 15, which yields exactly the five-tick window.
        heat = (heat + HEAT_SHIELD).coerceAtMost(HEAT_MAX)
        heatedThisTick = true
        shieldsRaised++
        shieldTicksRemaining = SHIELD_DURATION
        world.addEvent("SHIELD ACTIVE $SHIELD_DURATION")
    }

    // ---- Zero page (shared with Arena): world publishes the tick counter ----
    /** Publish the 24-bit tick counter little-endian at [robofight.isa.ISA.SYS_TICK]. */
    fun syncSysPage(tick: Int) {
        vm.mem[robofight.isa.ISA.SYS_TICK] = (tick and 0xFF).toByte()
        vm.mem[robofight.isa.ISA.SYS_TICK + 1] = ((tick shr 8) and 0xFF).toByte()
        vm.mem[robofight.isa.ISA.SYS_TICK + 2] = ((tick shr 16) and 0xFF).toByte()
    }

    val alive: Boolean get() = hp > 0 && !destroyed

    fun applyDamage(d: Int) {
        hp -= d
        if (hp <= 0) { hp = 0; destroyed = true }
    }
}
