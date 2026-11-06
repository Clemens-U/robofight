package robofight.fireline

import kotlin.math.abs

/**
 * FIRELINE world constants (FIRELINE-DESIGN.md §2-§5, RF8-PLATFORM-SPEC.md §5-§6).
 *
 * Everything numeric the FIRELINE world rules depend on lives here so a rule
 * change is visible as a constant change and can be paired with the required
 * [FIRELINE_ENGINE_VERSION] bump. Terrain ids/glyphs and their passability
 * tables belong to [Layout] (static authored content); per-cell fire rules
 * belong to [FireCell] (dynamic state).
 */

/**
 * Deterministic world-engine version (RF8-PLATFORM-SPEC.md §7,
 * FIRELINE-DESIGN.md §4.3/§8). Bump on ANY change to the tick order, fire
 * growth/spread rules, RNG generator/salts/draw order/traversal, port
 * semantics or shield timing — never silently.
 */
const val FIRELINE_ENGINE_VERSION = 1

// ---- Grid and directions (FIRELINE-DESIGN.md §3.1) ----
/** Mission layout is exactly 20x20 (§2, §13.4). */
const val GRID = 20
const val CELLS = GRID * GRID

/** Shared direction encoding: 0 = west, 1 = north, 2 = east, 3 = south. */
const val DIR_W = 0
const val DIR_N = 1
const val DIR_E = 2
const val DIR_S = 3

fun dirDx(dir: Int): Int = when (dir and 3) { DIR_W -> -1; DIR_E -> 1; else -> 0 }
fun dirDy(dir: Int): Int = when (dir and 3) { DIR_N -> -1; DIR_S -> 1; else -> 0 }

/** Sensor distances are Manhattan distances (§3.1). */
fun manhattan(x0: Int, y0: Int, x1: Int, y1: Int): Int = abs(x1 - x0) + abs(y1 - y0)

/**
 * Clockwise turn delta from [facing] toward [tx],[ty] (§3.1): when the target
 * is not on the same row/column the horizontal axis wins if abs(dx) >= abs(dy),
 * otherwise the vertical axis. A zero-distance target resolves to east.
 */
fun turnDeltaTo(facing: Int, x0: Int, y0: Int, tx: Int, ty: Int): Int {
    val dx = tx - x0
    val dy = ty - y0
    val want = if (abs(dx) >= abs(dy)) { if (dx >= 0) DIR_E else DIR_W }
    else { if (dy >= 0) DIR_S else DIR_N }
    return (want - (facing and 3) + 4) % 4
}

// ---- Rover body (§3.5, shared with Arena via RF8-PLATFORM-SPEC §6) ----
const val MAX_HP = 100
const val HEAT_MAX = 100
const val HEAT_COOL = 2                 // cooled per tick when nothing added heat
const val HEAT_SHOOT = 20               // heat added by a successful SHOOT
const val HEAT_SHIELD = 20              // heat added by SHIELD completion
const val HEAT_DAMAGE_THRESHOLD = 80    // heat above this burns the rover
const val HEAT_DAMAGE_PER_SEC = 2       // HP/sec while overheating
const val TICKS_PER_SECOND = 10         // 10 ticks/sec -> 0.2 HP/tick overload
/** Overload accumulates in exact milli-HP so the hash never depends on float formatting. */
const val HEAT_OVERLOAD_PER_TICK_MILLI = HEAT_DAMAGE_PER_SEC * 1000 / TICKS_PER_SECOND

/** SHIELD protects five world resolutions (RF8-PLATFORM-SPEC.md §6.1). */
const val SHIELD_DURATION = 5

const val DEFAULT_STARTING_WATER = 100

// ---- Water / suppressant (§3.6) ----
const val WATER_PER_SHOT = 10           // consumed by a successful SHOOT
const val WATER_REFILL = 10             // per resolution tick on a refill cell
const val WATER_MAX = 100
const val PACKET_LIFE = 20              // max 20 water-resolution steps
const val PACKET_FIRE_REDUCE = 25       // intensity removed from the first burning cell hit

// ---- Fire model (§4) ----
const val FIRE_MAX = 100
const val FIRE_SPREAD_PERIOD = 5        // growth + spread when tick % 5 == 0
const val IGNITION_INTENSITY = 25       // a newly ignited cell starts here
/** Preferred authored starting intensities (§3.3); the engine accepts every integer 0..100. */
val FIRE_LEVELS = intArrayOf(25, 50, 75, 100)

// ---- Mission limits (§13.4) ----
/** Hard run cap; reaching it reports TIME_EXPIRED as a safety termination. */
const val MAX_TICKS = 1_000_000

// ---- Sensor sentinels (§6.2/§6.3) ----
const val NO_TARGET_DIST = 0
const val NO_TARGET_ANGLE = 255
const val NO_TARGET_LEVEL = 0
const val NO_TARGET_ENEMY_HP = 255

// ---- Mission port bit field (§6.3) ----
const val MISSION_BIT_CIVILIANS_RESCUED = 0x01
const val MISSION_BIT_FIRE_OUT          = 0x02
const val MISSION_BIT_ON_EXIT           = 0x04
const val MISSION_BIT_SURVIVED          = 0x08
const val MISSION_BIT_CIVILIAN_LOST     = 0x10
const val MISSION_BIT_ASSET_LOST        = 0x20
const val MISSION_BIT_OBJECTIVES_MET    = 0x40
const val MISSION_BIT_FINISHED          = 0x80
