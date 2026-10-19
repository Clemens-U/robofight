package robofight.world

import kotlin.math.abs
import robofight.assembler.assemble
import robofight.isa.ISA
import robofight.vm.Env
import robofight.vm.Vm

const val GRID = 20          // 20×20 arena
const val MAX_HP = 100
const val DAMAGE = 10
const val HEAT_MAX = 100     // heat cap (0–100) — 5 SHOOT/SHIELD reach the max
const val HEAT_COOL = 2      // heat dropped per cool tick; 100→0 in 50 cool ticks = 5 s at 100 ms/tick
const val HEAT_SHOOT = 20    // heat added by SHOOT
const val HEAT_SHIELD = 20   // heat added by SHIELD
const val HEAT_DAMAGE_THRESHOLD = 80   // heat (0–100) above which the bot takes self-damage
const val HEAT_DAMAGE_PER_SEC = 2      // HP lost per second while heat > HEAT_DAMAGE_THRESHOLD
const val TICKS_PER_SECOND = 10        // engine time model: 100 ms per tick (see §10 DESIGN.md)
// Ops lock out when heat + HEAT_* > HEAT_MAX, i.e. they work again exactly
// at 80 (80+20 = 100 is allowed, 82+20 > 100 is not).
// Heat above HEAT_DAMAGE_THRESHOLD damages the bot at HEAT_DAMAGE_PER_SEC
// (2 HP/sec → 0.2 HP/tick at 10 ticks/sec). Fractional damage accumulates
// in Bot.heatOverload and is applied as whole HP once it reaches 1.0.
const val MAX_TICKS = 1_000_000 // "endless" — cap is a safety net, not a game rule
const val PROJ_LIFE = 40

const val DIR_W = 0
const val DIR_N = 1
const val DIR_E = 2
const val DIR_S = 3

/** One combat robot: VM + physical state. Implements the VM's Env. */
class Bot(
    val name: String,
    val glyph: Char,
    val color: Int,           // index into palette
    x: Int,
    y: Int,
    dir: Int,
    firmware: String,
    hp: Int = MAX_HP,         // per-slot HP (challenge codes, §6)
) : Env {
    val vm = Vm()
    var px = x; var py = y
    var facing = dir
    var startHp = hp          // configured HP — restored by [reset]
        private set
    var hp = hp
    var heat = 0
    var heatedThisTick = false  // set by shoot()/shield(); consumed by the cool step
    var heatOverload = 0f       // fractional HP pending from overheating (applied as whole HP)
    var shielded = false      // resets every tick before the bot acts
    var alive = true
    var shots = 0
    var hits = 0
    var dmgDealt = 0
    var dmgTaken = 0
    private var world: World? = null
    private val fw = firmware
    private var fwBinary: ByteArray? = null   // set by [loadBinary] (challenge codes)

    init { load(fw) }

    fun load(fw: String) {
        val r = assemble(fw)
        check(r.ok) { "[$name] firmware failed to assemble:\n" + r.errors.joinToString("\n") { "line ${it.line}: ${it.msg}" } }
        fwBinary = null
        vm.load(r.code)
    }

    /**
     * Challenge-code path (§4.3): the canonical firmware is the assembler's
     * OUTPUT image — loaded straight into the VM, never re-assembled.
     * [Vm.load] enforces the 768-byte program limit.
     */
    fun loadBinary(image: ByteArray) {
        check(image.size % 3 == 0) { "[$name] firmware image must be a multiple of 3 bytes (got ${image.size})" }
        vm.load(image)
        fwBinary = image
    }

    fun bind(w: World) { world = w }

    fun reset(x: Int, y: Int, dir: Int, configuredHp: Int = startHp) {
        px = x; py = y; facing = dir
        startHp = configuredHp
        hp = configuredHp; heat = 0; heatedThisTick = false; heatOverload = 0f; shielded = false; alive = true
        shots = 0; hits = 0; dmgDealt = 0; dmgTaken = 0
        val binary = fwBinary
        if (binary != null) vm.load(binary) else load(fw)
    }

    // ---- Env (I/O) ----
    override fun portIn(port: Int): Int {
        val w = world ?: return 0
        return when (port) {
            0 -> hp
            1 -> facing
            2 -> px
            3 -> py
            4 -> w.nearestEnemyDist(this)
            5 -> w.neededTurn(this)
            6 -> 0
            7 -> if (shielded) 1 else 0
            8 -> heat
            9 -> w.nearestEnemyHp(this)
            10 -> w.nextRand()
            11 -> w.aheadBlocked(this)
            else -> 0
        }
    }

    /**
     * Publish the world's zero-page state into this bot's memory: the 24-bit
     * tick counter (little-endian) at [ISA.SYS_TICK]. The world calls this
     * every tick BEFORE the bots act, so a bot acting on tick N reads N.
     * The page is re-written every tick, so a firmware clobber self-heals.
     */
    fun syncSysPage(tick: Int) {
        vm.mem[ISA.SYS_TICK] = (tick and 0xFF).toByte()
        vm.mem[ISA.SYS_TICK + 1] = ((tick shr 8) and 0xFF).toByte()
        vm.mem[ISA.SYS_TICK + 2] = ((tick shr 16) and 0xFF).toByte()
    }

    override fun portOut(port: Int, value: Int) {
        if (port == 1) facing = value and 3
    }

    override fun shoot() {
        val w = world ?: return
        if (!alive) return
        // Heat is cumulative (0–100): every shot adds HEAT_SHOOT, so 5 shots
        // reach the max. The gun locks out while heat + HEAT_SHOOT > HEAT_MAX,
        // i.e. it works again exactly at 80. Heat only cools on ticks where
        // no SHOOT/SHIELD added heat.
        if (heat + HEAT_SHOOT > HEAT_MAX) return
        val (dx, dy) = delta(facing)
        w.spawnProjectile(this, px + dx, py + dy, facing)
        heat += HEAT_SHOOT
        heatedThisTick = true
        shots++
    }

    override fun move() {
        val w = world ?: return
        if (!alive) return
        val (dx, dy) = delta(facing)
        val nx = px + dx; val ny = py + dy
        // Edge walls and blocks stop MOVE as a silent no-op (§7.5), exactly like the arena edge.
        if (nx in 0 until GRID && ny in 0 until GRID && !w.layout.stepBlocked(px, py, nx, ny) && w.botAt(nx, ny) == null) { px = nx; py = ny }
    }

    override fun shield() {
        // Shielding strains the system too (+HEAT_SHIELD). When shielding
        // would push heat past the max, the shield collapses — no protection
        // — until the heat has cooled back to 80.
        if (heat + HEAT_SHIELD > HEAT_MAX) return
        shielded = true
        heat += HEAT_SHIELD
        heatedThisTick = true
    }

    /**
     * Lockout pre-check used by the VM for multi-tick heat ops: true when
     * [op] (0 = SHOOT, 1 = SHIELD) would be rejected right now.
     */
    override fun heatLocked(op: Int): Boolean {
        val cost = if (op == 0) HEAT_SHOOT else HEAT_SHIELD
        return heat + cost > HEAT_MAX
    }

    override fun turn(delta: Int) {
        facing = ((facing + delta) % 4 + 4) % 4
    }

    fun applyHit(d: Int) {
        hp -= d
        dmgTaken += d
        if (hp <= 0) { hp = 0; alive = false }
    }

    companion object {
        /**
         * Challenge-code path (§4.3): a bot running a pre-assembled binary
         * image — the assembler's output, loaded verbatim, never re-assembled.
         */
        fun fromBinary(
            name: String,
            glyph: Char,
            color: Int,
            x: Int,
            y: Int,
            dir: Int,
            image: ByteArray,
            hp: Int = MAX_HP,
        ): Bot {
            val b = Bot(name, glyph, color, x, y, dir, "", hp)
            b.loadBinary(image)
            return b
        }
    }
}

data class Projectile(val owner: Bot, var x: Int, var y: Int, var dir: Int, var life: Int)

/**
 * A projectile connection event, raised by [World] when a shot reaches an
 * enemy. The engine only reports the facts (who, where, damage, whether the
 * victim's shield absorbed it); the front-end decides how to present it —
 * spark particles, screen shake, sound. [blocked] is true when the victim's
 * SHIELD ate the hit (no HP lost); otherwise [damage] HP were removed.
 */
data class HitEvent(
    val victim: Bot,
    val attacker: Bot,
    val x: Int,
    val y: Int,
    val damage: Int,
    val blocked: Boolean,
)

/** The arena: grid, bots, projectiles, deterministic tick resolution. */
class World(val layout: Layout = Arena.OPEN) {
    val bots = ArrayList<Bot>()
    val projectiles = ArrayList<Projectile>()
    var tick = 0
        private set
    var finished = false
        private set
    var winner: Bot? = null
        private set
    var lastLine = ""
        private set

    /**
     * Timed mode (challenge codes, §6): when set, the fight ends at this tick
     * — highest HP wins, an exact tie is a DRAW ([winner] stays null).
     * Null = KO mode (the default).
     */
    var timeLimit: Int? = null

    /**
     * Optional presentation hook for hit effects. The engine is pure and
     * deterministic, so this stays null under the headless tests; an app
     * (e.g. the Android arena) sets it to render sparks / shake / play sound.
     * Invoked on the thread that calls [tickOnce], once per resolved shot.
     */
    var onHit: ((HitEvent) -> Unit)? = null
    private var rng = 12345

    fun setSeed(seed: Int) { rng = seed }

    fun add(bot: Bot) {
        bot.bind(this)
        bots.add(bot)
    }

    fun botAt(x: Int, y: Int): Bot? = bots.firstOrNull { it.alive && it.px == x && it.py == y }

    /** 1 if the cell directly in front of [b] is a wall or an enemy, else 0. */
    fun aheadBlocked(b: Bot): Int {
        val (dx, dy) = delta(b.facing)
        val nx = b.px + dx; val ny = b.py + dy
        if (nx !in 0 until GRID || ny !in 0 until GRID) return 1
        if (layout.stepBlocked(b.px, b.py, nx, ny)) return 1
        return if (botAt(nx, ny) != null) 1 else 0
    }

    fun nextRand(): Int {
        rng = (rng * 1103515245 + 12345) and 0x7FFFFFFF
        return (rng shr 16) % 256
    }

    /**
     * True if [from] can see [to]: they share a row or column and no obstacle
     * (edge wall or block) sits on the straight line between them. Bots never
     * block sight. This is the cover rule — a wall between two robots hides
     * them from each other's enemy sensors.
     */
    private fun lineOfSight(from: Bot, to: Bot): Boolean {
        if (from.px != to.px && from.py != to.py) return false
        val stepX = if (from.px == to.px) 0 else if (to.px > from.px) 1 else -1
        val stepY = if (from.py == to.py) 0 else if (to.py > from.py) 1 else -1
        var x = from.px
        var y = from.py
        while (x != to.px || y != to.py) {
            val nx = x + stepX
            val ny = y + stepY
            if (layout.stepBlocked(x, y, nx, ny)) return false   // wall or block in the way
            x = nx
            y = ny
        }
        return true
    }

    /** Living enemies [b] can see (clear line of sight), nearest first. */
    private fun visibleEnemies(b: Bot): List<Bot> =
        bots.filter { it.alive && it !== b && lineOfSight(b, it) }
            .sortedWith(compareBy({ abs(it.px - b.px) + abs(it.py - b.py) }, { it.py * GRID + it.px }))

    /**
     * DIST port: straight-line distance to the nearest enemy [b] can see, 0 when
     * none is in line of sight. A walled-off enemy reads as absent — cover
     * conceals, it does not merely add range. 8-bit: capped at 255.
     */
    fun nearestEnemyDist(b: Bot): Int {
        val nearest = visibleEnemies(b).firstOrNull() ?: return 0
        return (abs(nearest.px - b.px) + abs(nearest.py - b.py)).coerceAtMost(255)
    }

    /**
     * ENEMY_HP port: HP of the nearest enemy [b] can see; 255 when none is in
     * line of sight. 255 (not 0) is the "no enemy" sentinel so that a future
     * multi-bot arena can still read a visible, already-destroyed enemy's 0 HP.
     */
    fun nearestEnemyHp(b: Bot): Int = visibleEnemies(b).firstOrNull()?.hp ?: 255

    /**
     * ANGLE port: clockwise turns (0-3) from [b] to face the nearest enemy it
     * can see; 255 when none is in line of sight. Returns a clockwise DELTA
     * from the bot's current facing (not the target direction) — firmware
     * counts this many `TURN R` steps and tests `JZ` when it reads back 0.
     */
    fun neededTurn(b: Bot): Int {
        val e = visibleEnemies(b).firstOrNull() ?: return 255
        return (turnToward(b, e) - b.facing + 4) % 4
    }

    private fun turnToward(from: Bot, to: Bot): Int {
        val dx = to.px - from.px
        val dy = to.py - from.py
        if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) return if (dx >= 0) DIR_E else DIR_W
        return if (dy >= 0) DIR_S else DIR_N
    }

    fun spawnProjectile(owner: Bot, x: Int, y: Int, dir: Int) {
        if (x !in 0 until GRID || y !in 0 until GRID) return
        // A shot starts one cell in front of the shooter; it can't spawn through
        // an edge wall or into a block.
        if (layout.stepBlocked(owner.px, owner.py, x, y)) return
        projectiles.add(Projectile(owner, x, y, dir, PROJ_LIFE))
    }

    /** One full turn: every bot runs one instruction, then the world resolves. */
    fun tickOnce() {
        if (finished) return
        tick++
        // Zero-page (C64-style): the world owns $0000–$000F of each bot's data
        // RAM. Publish the current tick to every bot BEFORE it acts, so a bot
        // acting on tick N reads N.
        for (b in bots) b.syncSysPage(tick)
        val log = ArrayList<String>()
        for (b in bots) {
            if (!b.alive) continue
            b.shielded = false
            b.heatedThisTick = false
            b.vm.step(b)
            if (b.vm.fault) { b.alive = false; b.hp = 0 }
            // Cool only on ticks where the bot added no heat (no SHOOT/SHIELD
            // that ran) — an active bot stays hot, an idle one cools.
            // Cooling is ALSO suppressed while a multi-tick op is in flight
            // (vm.inProgress): its heat lands on the final tick, but the
            // whole op counts as one continuous action — this is what keeps
            // 5 back-to-back SHOOTs reaching exactly 100 heat with zero
            // cooling in between (burst lockout unchanged), and a locked
            // SHOOT (1-tick no-op) still cools normally.
            if (!b.heatedThisTick && !b.vm.inProgress) {
                b.heat = (b.heat - HEAT_COOL).coerceAtLeast(0)
            }
            // Heat above HEAT_DAMAGE_THRESHOLD burns the bot: 2 HP/sec
            // (engine model: 10 ticks/sec → 0.2 HP/tick). Fractional damage
            // accumulates in heatOverload and is applied as whole HP once
            // it reaches 1.0. Safe ticks add no damage, but must not erase
            // fractional exposure from earlier hot ticks.
            if (b.alive && b.heat > HEAT_DAMAGE_THRESHOLD) {
                b.heatOverload += HEAT_DAMAGE_PER_SEC.toFloat() / TICKS_PER_SECOND
                val dmg = b.heatOverload.toInt()
                if (dmg > 0) {
                    b.heatOverload -= dmg
                    b.applyHit(dmg)
                }
            }
            log.add("${b.name}[${b.vm.lastOp}]")
        }
        resolveProjectiles()
        lastLine = log.joinToString("  ·  ")
        checkFinish()
        if (tick >= MAX_TICKS) { finished = true; checkWinner() }
    }

    private fun targetAt(x: Int, y: Int, p: Projectile): Bot? =
        botAt(x, y)?.takeIf { it !== p.owner }

    private fun resolveProjectiles() {
        val kept = ArrayList<Projectile>()
        for (p in projectiles) {
            // 1) spawned on top of an enemy (adjacent shot) → hits immediately
            var target = targetAt(p.x, p.y, p)
            // 2) otherwise advance one cell; hit if an enemy is there, else keep flying
            if (target == null) {
                val (dx, dy) = delta(p.dir)
                val nx = p.x + dx; val ny = p.y + dy
                if (nx !in 0 until GRID || ny !in 0 until GRID) continue // hit arena edge
                if (layout.stepBlocked(p.x, p.y, nx, ny)) continue // absorbed by an edge wall or block (§7.5)
                target = targetAt(nx, ny, p)
                if (target == null) { p.x = nx; p.y = ny }
            }
            if (target != null) {
                // The hit lands on the victim's cell; report it before/after
                // mutating so a listener can read the fresh state.
                if (!target.shielded) {
                    target.applyHit(DAMAGE)
                    p.owner.hits++
                    p.owner.dmgDealt += DAMAGE
                    onHit?.invoke(HitEvent(target, p.owner, target.px, target.py, DAMAGE, false))
                } else {
                    onHit?.invoke(HitEvent(target, p.owner, target.px, target.py, 0, true))
                }
                continue // consumed
            }
            p.life--
            if (p.life > 0) kept.add(p)
        }
        projectiles.clear()
        projectiles.addAll(kept)
    }

    private fun checkFinish() {
        val living = bots.filter { it.alive }
        if (living.size <= 1) { finished = true; if (living.size == 1) winner = living[0]; return }
        // Timed mode (§6): at the limit the fight ends — highest HP wins, an
        // exact tie is a DRAW (winner stays null).
        val limit = timeLimit ?: return
        if (tick < limit) return
        finished = true
        val top = bots.maxOf { it.hp }
        val leaders = bots.filter { it.hp == top }
        if (leaders.size == 1) winner = leaders[0]
    }

    fun finishByHp() {
        winner = uniqueHpLeader()
    }

    fun forceEnd() {
        finished = true
        if (winner == null) winner = uniqueHpLeader()
    }

    private fun checkWinner() {
        if (winner != null) return
        winner = uniqueHpLeader()
    }

    /** Highest-HP bot, or null when nobody is alive or the top HP is tied. */
    private fun uniqueHpLeader(): Bot? {
        val top = bots.maxOfOrNull { it.hp } ?: return null
        if (top <= 0) return null
        return bots.singleOrNull { it.hp == top }
    }
}

fun delta(dir: Int): Pair<Int, Int> = when (dir) {
    DIR_W -> -1 to 0
    DIR_N -> 0 to -1
    DIR_E -> 1 to 0
    else -> 0 to 1
}
