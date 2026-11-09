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
const val FIRELINE_ENGINE_VERSION = 3

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
const val WATER_RANGE = 5               // adjacent cell is distance 1; last reachable cell is 5
const val PACKET_LIFE = WATER_RANGE     // one resolution per cell, including the spawn cell
const val PACKET_FIRE_REDUCE = 25       // intensity removed from the first burning cell hit

// ---- Fire model (§4) ----
const val FIRE_MAX = 100
const val FIRE_SPREAD_PERIOD = 5        // growth + spread when tick % 5 == 0
const val IGNITION_INTENSITY = 25       // a newly ignited cell starts here
/** Preferred authored starting intensities (§3.3); the engine accepts every integer 0..100. */
val FIRE_LEVELS = intArrayOf(25, 50, 75, 100)

// ---- §13.2 final-state fingerprint ----
/** FNV-1a 64-bit parameters (offset basis 0xCBF29CE484222325 as a signed Long). */
const val FNV_OFFSET_BASIS: Long = -3750763034362895579
const val FNV_PRIME: Long = 1099511628211

// ---- §3.3 derived smoke ----
const val SMOKE_MAX = 255

/** Orthogonal neighbour offsets in W, N, E, S order (same order the SMOKE formula names). */
val NEIGHBOUR_OFFSETS = listOf(-1 to 0, 0 to -1, 1 to 0, 0 to 1)

// ---- §4.2/§4.4 growth + spread table (fixed; changing these bumps the engine version) ----
const val GROWTH_FLOOR_EXIT = 5           // floor / exit grow +5 per spread step
const val GROWTH_FUEL_PROTECTED = 10      // fuel / protected asset grow +10
const val IGNITE_THRESHOLD_FLOOR_EXIT = 64
const val IGNITE_THRESHOLD_FUEL_PROTECTED = 160
const val IGNITE_THRESHOLD_NONE = 0       // wall / refill / off-grid never ignite
const val WIND_THRESHOLD_BONUS = 16       // +16 * WIND_FORCE on the wind axis
const val WIND_THRESHOLD_CAP = 255        // threshold cap out of 256

/** §4.2: growth applied to a snapshot burning cell per spread step. */
fun fireGrowthFor(terrain: Int): Int = when (terrain) {
    TERRAIN_FLOOR, TERRAIN_EXIT -> GROWTH_FLOOR_EXIT
    TERRAIN_FUEL, TERRAIN_PROTECTED -> GROWTH_FUEL_PROTECTED
    else -> 0                             // wall and refill never burn
}

/**
 * §4.4 step 1: the direction bag for one burning cell, built in order:
 * `[W, N, E, S] + WIND_DIR repeated WIND_FORCE times`.
 */
fun spreadDirectionBag(windDir: Int, windForce: Int): IntArray {
    val bag = IntArray(4 + windForce)
    bag[0] = DIR_W; bag[1] = DIR_N; bag[2] = DIR_E; bag[3] = DIR_S
    for (i in 0 until windForce) bag[4 + i] = windDir and 3
    return bag
}

/**
 * §4.4 steps 4-5: ignition threshold out of 256 for the selected target terrain,
 * including the wind bonus when the selected direction is the wind direction.
 */
fun spreadThresholdFor(targetTerrain: Int, chosenDir: Int, windDir: Int, windForce: Int): Int {
    val base = when (targetTerrain) {
        TERRAIN_FLOOR, TERRAIN_EXIT -> IGNITE_THRESHOLD_FLOOR_EXIT
        TERRAIN_FUEL, TERRAIN_PROTECTED -> IGNITE_THRESHOLD_FUEL_PROTECTED
        else -> IGNITE_THRESHOLD_NONE     // wall / refill / off-grid
    }
    if (base == IGNITE_THRESHOLD_NONE) return IGNITE_THRESHOLD_NONE
    val withWind = if (chosenDir == (windDir and 3)) base + WIND_THRESHOLD_BONUS * windForce else base
    return withWind.coerceAtMost(WIND_THRESHOLD_CAP)
}

/**
 * §4.5 exposure damage: `ceil(fireIntensity / 25)`, i.e. 1-4 HP per world tick.
 * Zero intensity means no damage.
 */
fun fireExposureDamage(intensity: Int): Int =
    if (intensity <= 0) 0 else (intensity.coerceAtMost(FIRE_MAX) + 24) / 25

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
