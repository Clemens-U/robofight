package robofight.world

import robofight.assembler.assemble
import robofight.vm.Env
import robofight.vm.Vm

const val GRID = 20          // 20×20 arena
const val MAX_HP = 100
const val DAMAGE = 10
const val HEAT_MAX = 10       // heat cap — at the max, SHOOT & SHIELD are locked out
const val HEAT_COOL = 1       // heat cooled by this every HEAT_COOL_EVERY ticks
const val HEAT_COOL_EVERY = 2 // ticks per cool step — heat lingers, maxed robots sit out
const val HEAT_SHOOT = 2      // heat added by SHOOT (piles up over time)
const val HEAT_SHIELD = 2     // heat added by SHIELD
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
) : Env {
    val vm = Vm()
    var px = x; var py = y
    var facing = dir
    var hp = MAX_HP
    var heat = 0
    var shielded = false      // resets every tick before the bot acts
    var alive = true
    var shots = 0
    var hits = 0
    var dmgDealt = 0
    var dmgTaken = 0
    private var world: World? = null
    private val fw = firmware

    init { load(fw) }

    fun load(fw: String) {
        val r = assemble(fw)
        check(r.ok) { "[$name] firmware failed to assemble:\n" + r.errors.joinToString("\n") { "line ${it.line}: ${it.msg}" } }
        vm.load(r.code)
    }

    fun bind(w: World) { world = w }

    fun reset(x: Int, y: Int, dir: Int) {
        px = x; py = y; facing = dir
        hp = MAX_HP; heat = 0; shielded = false; alive = true
        shots = 0; hits = 0; dmgDealt = 0; dmgTaken = 0
        load(fw)
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

    override fun portOut(port: Int, value: Int) {
        if (port == 1) facing = value and 3
    }

    override fun shoot() {
        val w = world ?: return
        if (!alive) return
        // Heat is cumulative: every shot adds HEAT_SHOOT and it only cools
        // 1 per 2 ticks, so an active bot's heat piles up over the fight.
        // When a shot would push heat past the max, the gun is locked until
        // the heat has cooled enough to fire again.
        if (heat + HEAT_SHOOT > HEAT_MAX) return
        val (dx, dy) = delta(facing)
        w.spawnProjectile(this, px + dx, py + dy, facing)
        heat += HEAT_SHOOT
        shots++
    }

    override fun move() {
        val w = world ?: return
        if (!alive) return
        val (dx, dy) = delta(facing)
        val nx = px + dx; val ny = py + dy
        if (nx in 0 until GRID && ny in 0 until GRID && w.botAt(nx, ny) == null) { px = nx; py = ny }
    }

    override fun shield() {
        // Shielding strains the system too (+HEAT_SHIELD). When shielding
        // would push heat past the max, the shield collapses — no protection
        // — until the heat has been reduced enough to raise it again.
        if (heat + HEAT_SHIELD > HEAT_MAX) return
        shielded = true
        heat += HEAT_SHIELD
    }

    override fun turn(delta: Int) {
        facing = ((facing + delta) % 4 + 4) % 4
    }

    fun applyHit(d: Int) {
        hp -= d
        dmgTaken += d
        if (hp <= 0) { hp = 0; alive = false }
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
class World {
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
        return if (botAt(nx, ny) != null) 1 else 0
    }

    fun nextRand(): Int {
        rng = (rng * 1103515245 + 12345) and 0x7FFFFFFF
        return (rng shr 16) % 256
    }

    fun nearestEnemyDist(b: Bot): Int {
        val e = enemies(b).minByOrNull { manhattan(b, it) } ?: return 0
        return manhattan(b, e)
    }

    fun nearestEnemyHp(b: Bot): Int = enemies(b).minByOrNull { manhattan(b, it) }?.hp ?: 0

    /** 0..3 = how many steps clockwise to face the nearest enemy; 255 = none.
     *  Returns a clockwise DELTA from the bot's current facing (not the target
     *  direction itself) — firmware counts this many `TURN R` steps and tests
     *  `JZ` when it reads back 0. */
    fun neededTurn(b: Bot): Int {
        val e = enemies(b).minByOrNull { manhattan(b, it) } ?: return 255
        val target = turnToward(b, e)
        return (target - b.facing + 4) % 4
    }

    private fun enemies(b: Bot) = bots.filter { it.alive && it !== b }

    private fun manhattan(a: Bot, b: Bot) = kotlin.math.abs(a.px - b.px) + kotlin.math.abs(a.py - b.py)

    private fun turnToward(from: Bot, to: Bot): Int {
        val dx = to.px - from.px
        val dy = to.py - from.py
        if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) return if (dx >= 0) DIR_E else DIR_W
        return if (dy >= 0) DIR_S else DIR_N
    }

    fun spawnProjectile(owner: Bot, x: Int, y: Int, dir: Int) {
        if (x !in 0 until GRID || y !in 0 until GRID) return
        projectiles.add(Projectile(owner, x, y, dir, PROJ_LIFE))
    }

    /** One full turn: every bot runs one instruction, then the world resolves. */
    fun tickOnce() {
        if (finished) return
        tick++
        val log = ArrayList<String>()
        for (b in bots) {
            if (!b.alive) continue
            b.shielded = false
            if (tick % HEAT_COOL_EVERY == 0) b.heat = (b.heat - HEAT_COOL).coerceAtLeast(0)
            b.vm.step(b)
            if (b.vm.fault) { b.alive = false; b.hp = 0 }
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
                if (nx !in 0 until GRID || ny !in 0 until GRID) continue // hit wall
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
        if (living.size <= 1) { finished = true; if (living.size == 1) winner = living[0] }
    }

    fun finishByHp() {
        winner = bots.maxByOrNull { it.hp }?.takeIf { it.hp > 0 }
    }

    fun forceEnd() {
        finished = true
        if (winner == null) winner = bots.maxByOrNull { it.hp }?.takeIf { it.hp > 0 }
    }

    private fun checkWinner() {
        if (winner != null) return
        winner = bots.maxByOrNull { it.hp }?.takeIf { it.hp > 0 }
    }
}

fun delta(dir: Int): Pair<Int, Int> = when (dir) {
    DIR_W -> -1 to 0
    DIR_N -> 0 to -1
    DIR_E -> 1 to 0
    else -> 0 to 1
}
