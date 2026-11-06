package robofight.fireline

/**
 * FIRELINE's separate deterministic RNG streams (FIRELINE-DESIGN.md §4.3,
 * RF8-PLATFORM-SPEC.md §7): one 31-bit LCG state per stream, derived from the
 * mission seed with a fixed documented salt.
 *
 * Three streams are mandated: sensor randomness (`IN RAND`), environmental fire
 * spread, and — if layout generation is ever added — a third one. Keeping them
 * separate is what makes a diagnostic `IN RAND` incapable of changing when or
 * where fire spreads (PLAN §8.4 gate).
 *
 * Generator (fixed; changing it bumps [FIRELINE_ENGINE_VERSION]):
 * ```text
 * next(state) = (state * 1103515245 + 12345) & 0x7FFFFFFF
 * byte(state) = (state >> 16) & 0xFF
 * ```
 * Salt derivation uses 32-bit two's-complement arithmetic; masking the exact
 * product to 31 bits is identical to wrapping to 32 bits first, so the Long
 * arithmetic below is bit-exact.
 */
class LcgStream(seedState: Int) {
    var state: Int = seedState and 0x7FFFFFFF
        private set

    /** Consume one draw and return its byte. */
    fun nextByte(): Int {
        state = nextOf(state)
        return (state shr 16) and 0xFF
    }

    companion object {
        const val MULTIPLIER = 1103515245L
        const val INCREMENT = 12345L
        const val STATE_MASK = 0x7FFFFFFF

        /** Fixed salts (§4.3): "RF8S" for sensors, "FIRE" for spread. */
        const val SENSOR_SALT = 0x52463853
        const val SPREAD_SALT = 0x46495245

        /** Third documented stream, reserved for procedural layout generation (§4.3/§15). */
        const val LAYOUT_SALT = 0x4C415954 // "LAYT"

        fun nextOf(state: Int): Int =
            ((state.toLong() and STATE_MASK.toLong()) * MULTIPLIER + INCREMENT and STATE_MASK.toLong()).toInt()

        fun byteOf(state: Int): Int = (state shr 16) and 0xFF

        fun forSensor(seed: Int) = LcgStream(seed xor SENSOR_SALT)
        fun forSpread(seed: Int) = LcgStream(seed xor SPREAD_SALT)
        fun forLayout(seed: Int) = LcgStream(seed xor LAYOUT_SALT)
    }
}
