package robofight.fireline

import robofight.isa.MachineProfile
import robofight.isa.PortAccess
import robofight.isa.PortCatalog

/**
 * FIRELINE machine profile implementation + catalog parity
 * (RF8-PLATFORM-SPEC.md §5.2/§5.3, FIRELINE-DESIGN.md §6).
 *
 * The numbers themselves are owned by the shared [PortCatalog] in `:rf8-core`
 * (one global registry, §1.5); this file owns only what the FIRELINE profile
 * ANSWERS on those numbers and the parity check that keeps the profile and the
 * shared catalog from drifting apart.
 */
object FirelinePorts {
    // ---- Discovery (§5.2): FIRELINE is profile 1 ----
    const val PROFILE = 1
    const val PROFILE_REV = 1

    /** v1 capability field: bits 0..6 set, bit 7 reserved zero (§5.3). */
    const val CAPS = 0x7F

    const val CAP_FIRE_SENSORS = 0x01   // 16..18
    const val CAP_CIVILIAN_SENSORS = 0x02 // 19..21
    const val CAP_WATER_TANK = 0x04     // 22
    const val CAP_SMOKE = 0x08          // 24
    const val CAP_WIND = 0x10           // 25..26
    const val CAP_EXIT = 0x20           // 27..28
    const val CAP_MISSION_TILE = 0x40   // 29..30
    const val CAP_RESERVED = 0x80       // zero in v1

    // ---- Base body ports (§5.1, frozen) ----
    const val HP = 0
    const val DIR = 1
    const val RX = 2
    const val RY = 3
    const val DIST = 4
    const val ANGLE = 5
    const val FIRE = 6            // reserved, reads zero
    const val SHIELD = 7
    const val HEAT = 8
    const val ENEMY_HP = 9
    const val RAND = 10
    const val AHEAD = 11

    const val PROFILE_PORT = 12
    const val PROFILE_REV_PORT = 13
    const val CAPS_PORT = 14

    // ---- FIRELINE ports (§5.3) ----
    const val FIRE_DIST = 16
    const val FIRE_ANGLE = 17
    const val FIRE_LEVEL = 18
    const val CIV_DIST = 19
    const val CIV_ANGLE = 20
    const val CIV_COUNT = 21
    const val WATER = 22
    const val LOCAL_FIRE = 23
    const val SMOKE = 24
    const val WIND_DIR = 25
    const val WIND_FORCE = 26
    const val EXIT_DIST = 27
    const val EXIT_ANGLE = 28
    const val TILE = 29
    const val MISSION = 30

    /** Numbers 15 and 31 are reserved: no symbol, read zero, writes ignored. */
    val RESERVED_PORTS = intArrayOf(15, 31)

    /** Every official FIRELINE-profile port number 16..30 (reserved ones excluded). */
    val FIRELINE_PORT_NUMBERS = (16..30).toList()

    /**
     * Profile/catalog parity check: each FIRELINE port number carries its
     * official symbol in the shared catalog, is read-only, and is owned by the
     * FIRELINE profile; the base body ports stay SHARED and `DIR` remains the
     * only writable official port. Empty list = consistent.
     */
    fun checkCatalogParity(): List<String> {
        val problems = ArrayList<String>()
        val expected = mapOf(
            FIRE_DIST to "FIRE_DIST", FIRE_ANGLE to "FIRE_ANGLE", FIRE_LEVEL to "FIRE_LEVEL",
            CIV_DIST to "CIV_DIST", CIV_ANGLE to "CIV_ANGLE", CIV_COUNT to "CIV_COUNT",
            WATER to "WATER", LOCAL_FIRE to "LOCAL_FIRE", SMOKE to "SMOKE",
            WIND_DIR to "WIND_DIR", WIND_FORCE to "WIND_FORCE",
            EXIT_DIST to "EXIT_DIST", EXIT_ANGLE to "EXIT_ANGLE",
            TILE to "TILE", MISSION to "MISSION",
        )
        for ((number, name) in expected) {
            val def = PortCatalog.definition(number)
            if (def == null) { problems.add("catalog has no definition for port $number ('$name')"); continue }
            if (def.name != name) problems.add("port $number is '${def.name}' in the catalog, expected '$name'")
            if (def.profile != MachineProfile.FIRELINE) problems.add("port $number '${def.name}' profile is ${def.profile}, expected FIRELINE")
            if (def.access != PortAccess.READ) problems.add("port $number '${def.name}' must be read-only in FIRELINE v1")
        }
        for (number in RESERVED_PORTS) {
            val def = PortCatalog.definition(number)
            if (def != null) problems.add("reserved port $number must not carry a symbol (found '${def.name}')")
        }
        val dir = PortCatalog.definition(DIR)
        if (dir == null || dir.access != PortAccess.READ_WRITE) problems.add("DIR (1) must remain the writable shared port")
        for (number in FIRELINE_PORT_NUMBERS) {
            val def = PortCatalog.definition(number)
            if (def != null && def.access == PortAccess.READ_WRITE) problems.add("FIRELINE port $number must not be writable in v1")
        }
        return problems
    }

    /**
     * Cheat-sheet rows for the FIRELINE app (base body ports separated from
     * FIRELINE peripherals, sentinels stated — FIRELINE-DESIGN.md §10), derived
     * from the shared catalog rather than a second hand-written mapping.
     */
    fun cheatSheet(): String = buildString {
        append("FIRELINE ports (profile ${PROFILE} rev ${PROFILE_REV}, CAPS 0x${CAPS.toString(16)})\n\n")
        append("Base body ports:\n")
        for (p in PortCatalog.namedPorts().filter { it.number < PortCatalog.BASE_PORTS || it.number in 12..14 })
            append("  ${p.name.padEnd(12)} ${p.number.toString().padStart(2)}  ${p.description}\n")
        append("\nFIRELINE peripherals:\n")
        for (p in PortCatalog.namedPorts().filter { it.profile == MachineProfile.FIRELINE })
            append("  ${p.name.padEnd(12)} ${p.number.toString().padStart(2)}  ${p.description}\n")
        append("\nNo target sentinels: DIST/FIRE_DIST/EXIT_DIST/CIV_DIST = 0, ANGLE/FIRE_ANGLE/CIV_ANGLE/EXIT_ANGLE = 255,\n")
        append("ENEMY_HP = 255, FIRE_LEVEL = 0. Unknown ports read 0 and ignore writes.\n")
    }.trimEnd()
}
