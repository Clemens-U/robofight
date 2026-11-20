package robofight.isa

/**
 * RF-8 official port catalog (RF8-PLATFORM-SPEC.md §4.3, §5;
 * PLAN-RF8-MULTIAPP.md §6 Phase 2).
 *
 * The single, data-driven source of truth for named ports across all RF-8
 * products (Opcode Arena, The Circuit, FIRELINE). Rules the catalog encodes:
 *
 *  - Base body ports keep their shipped numbers 0..11 exactly (§5.1).
 *  - Official named allocations occupy 0..63; 64..255 are reserved for
 *    later profiles and private experiments (§4.1).
 *  - Assembler name lookup is case-insensitive; unknown SYMBOLS stay
 *    assembler errors, while any numeric port 0..255 is encodable (§4.1).
 *  - Ports an environment does not implement degrade to read-zero /
 *    write-ignore without faulting (§4.2) — enforced by the Env impls,
 *    not by the catalog.
 *  - Official port numbers are globally unique across profiles (§1.5).
 *
 * Reserved numbers 15 and 31 (§5.2, §5.3) carry NO symbol: they are legal
 * numeric ports that simply read zero / ignore writes, so they are absent
 * from the named list on purpose. Disassembly falls back to the numeric
 * form for them (and any unnamed number).
 */

/** Port access kind (documentation, cheat-sheet generation). */
enum class PortAccess { READ, READ_WRITE }

/** Which machine profile owns the port's real behavior (§5). SHARED ports
 *  behave the same in every profile; profile ports read zero elsewhere. */
enum class MachineProfile { SHARED, ARENA, FIRELINE }

data class PortDef(
    val number: Int,
    val name: String,
    val access: PortAccess,
    val profile: MachineProfile,
    val description: String,
)

object PortCatalog {
    /** Any unsigned byte is a legal numeric port operand (§4.1). */
    const val NUMERIC_MIN = 0
    const val NUMERIC_MAX = 255

    /** Named allocations initially occupy 0..63 (§4.1). */
    const val OFFICIAL_MAX = 63

    /** Base body ports shipped in ISA v1 — numbers frozen (§5.1). */
    const val BASE_PORTS = 12

    val ports: List<PortDef> = listOf(
        // ---- §5.1 base body ports (0..11) — frozen ----
        PortDef(0,  "HP",         PortAccess.READ,       MachineProfile.SHARED,   "current integrity; 0 = destroyed"),
        PortDef(1,  "DIR",        PortAccess.READ_WRITE, MachineProfile.SHARED,   "facing 0=W 1=N 2=E 3=S; writing sets facing"),
        PortDef(2,  "RX",         PortAccess.READ,       MachineProfile.SHARED,   "grid column"),
        PortDef(3,  "RY",         PortAccess.READ,       MachineProfile.SHARED,   "grid row"),
        PortDef(4,  "DIST",       PortAccess.READ,       MachineProfile.SHARED,   "primary-target distance; 0 = none"),
        PortDef(5,  "ANGLE",      PortAccess.READ,       MachineProfile.SHARED,   "clockwise turn delta to primary target; 255 = none"),
        PortDef(6,  "FIRE",       PortAccess.READ,       MachineProfile.SHARED,   "reserved in ISA v1; read-zero; use SHOOT"),
        PortDef(7,  "SHIELD",     PortAccess.READ,       MachineProfile.SHARED,   "1 while the shield window is active"),
        PortDef(8,  "HEAT",       PortAccess.READ,       MachineProfile.SHARED,   "body heat 0..100"),
        PortDef(9,  "ENEMY_HP",   PortAccess.READ,       MachineProfile.SHARED,   "primary-target strength; 255 = none"),
        PortDef(10, "RAND",       PortAccess.READ,       MachineProfile.SHARED,   "profile-owned deterministic sensor RNG"),
        PortDef(11, "AHEAD",      PortAccess.READ,       MachineProfile.SHARED,   "1 if forward movement is blocked"),

        // ---- §5.2 discovery ports (12..14) ----
        PortDef(12, "PROFILE",     PortAccess.READ, MachineProfile.SHARED, "machine profile id: 0 = Arena compatibility, 1 = FIRELINE"),
        PortDef(13, "PROFILE_REV", PortAccess.READ, MachineProfile.SHARED, "profile revision (Arena 0 until it adopts discovery; FIRELINE 1)"),
        PortDef(14, "CAPS",        PortAccess.READ, MachineProfile.SHARED, "profile-specific 8-bit capability field"),

        // ---- §5.3 FIRELINE ports (16..30); read-zero in Arena v1 ----
        PortDef(16, "FIRE_DIST",  PortAccess.READ, MachineProfile.FIRELINE, "Manhattan distance to selected burning cell; 0 = none"),
        PortDef(17, "FIRE_ANGLE", PortAccess.READ, MachineProfile.FIRELINE, "clockwise turn delta to selected burning cell; 255 = none"),
        PortDef(18, "FIRE_LEVEL", PortAccess.READ, MachineProfile.FIRELINE, "selected fire intensity 1..100; 0 = none"),
        PortDef(19, "CIV_DIST",   PortAccess.READ, MachineProfile.FIRELINE, "Manhattan distance to selected unrescued civilian; 0 = none"),
        PortDef(20, "CIV_ANGLE",  PortAccess.READ, MachineProfile.FIRELINE, "clockwise turn delta to selected civilian; 255 = none"),
        PortDef(21, "CIV_COUNT",  PortAccess.READ, MachineProfile.FIRELINE, "required civilians still awaiting rescue"),
        PortDef(22, "WATER",      PortAccess.READ, MachineProfile.FIRELINE, "suppressant remaining 0..100"),
        PortDef(23, "LOCAL_FIRE", PortAccess.READ, MachineProfile.FIRELINE, "fire intensity on the rover's current cell 0..100"),
        PortDef(24, "SMOKE",      PortAccess.READ, MachineProfile.FIRELINE, "derived local smoke density 0..255"),
        PortDef(25, "WIND_DIR",   PortAccess.READ, MachineProfile.FIRELINE, "wind direction (shared direction encoding)"),
        PortDef(26, "WIND_FORCE", PortAccess.READ, MachineProfile.FIRELINE, "wind force 0..3"),
        PortDef(27, "EXIT_DIST",  PortAccess.READ, MachineProfile.FIRELINE, "Manhattan distance to mission exit; 0 = absent or on it"),
        PortDef(28, "EXIT_ANGLE", PortAccess.READ, MachineProfile.FIRELINE, "clockwise turn delta to exit; 255 = no exit"),
        PortDef(29, "TILE",       PortAccess.READ, MachineProfile.FIRELINE, "current terrain id (FIRELINE profile)"),
        PortDef(30, "MISSION",    PortAccess.READ, MachineProfile.FIRELINE, "FIRELINE mission-state bit field"),
    )

    private val byName: Map<String, PortDef> = ports.associateBy { it.name.uppercase() }
    private val byNumber: Map<Int, PortDef> = ports.associateBy { it.number }

    /** Case-insensitive symbol lookup for the assembler. */
    fun number(name: String): Int? = byName[name.trim().uppercase()]?.number

    /** Number lookup for documentation / disassembly. */
    fun definition(number: Int): PortDef? = byNumber[number]

    /** Display name for a port number (null = no official symbol). */
    fun displayName(number: Int): String? = byNumber[number]?.name

    /** All named official ports, in number order. */
    fun namedPorts(): List<PortDef> = ports

    /** Base body ports (0..11) as the legacy name→number view. */
    fun baseNameToNumber(): Map<String, Int> =
        ports.filter { it.number < BASE_PORTS }.associate { it.name to it.number }

    /**
     * Startup/test integrity check (§4.3): numbers and names globally
     * unique, numbers inside the official allocation window, names are
     * assembler-encodable identifiers. Empty list = catalog is valid.
     */
    fun checkIntegrity(): List<String> {
        val problems = ArrayList<String>()
        ports.groupBy { it.number }.forEach { (num, defs) ->
            if (defs.size > 1) problems.add("duplicate port number $num: ${defs.map { it.name }}")
        }
        ports.groupBy { it.name.uppercase() }.forEach { (name, defs) ->
            if (defs.size > 1) problems.add("duplicate port name '$name'")
        }
        for (p in ports) {
            if (p.number !in NUMERIC_MIN until OFFICIAL_MAX)
                problems.add("port ${p.name} number ${p.number} outside official range 0..$OFFICIAL_MAX")
            if (!p.name.matches(Regex("[A-Z][A-Z0-9_]*")))
                problems.add("port name '${p.name}' is not an encodable identifier")
        }
        return problems
    }

    /**
     * Generate the per-app cheat-sheet markdown table (port | number |
     * access | meaning) from the catalog so no second hand-written mapping
     * is needed (§4.3). MANUAL.md §8.3 stays hand-typed for terminal voice;
     * PortConformanceTest asserts its parity against this catalog.
     */
    fun markdownTable(): String = buildString {
        append("| Port | # | R/W | Meaning |\n")
        append("|------|---|-----|---------|\n")
        for (p in ports) {
            val rw = when (p.access) { PortAccess.READ -> "R"; PortAccess.READ_WRITE -> "R/W" }
            append("| `${p.name}` | ${p.number} | $rw | ${p.description} |\n")
        }
    }.trimEnd()
}
