package robofight

import robofight.world.Arena

/**
 * Pro arena NAMES table proof (P3 cross-app UX, PLAN-PRO §6 P3).
 *
 * The free app may name a locked Arena Pack 1 arena in a preview without
 * owning its geometry. Proves the names-only split:
 *  (a) Arena.proArenaNames resolves exactly the six pack ids 0x10–0x15 to
 *      the names the pro flavor's ProArenaPack registers, in id order;
 *  (b) ids outside the table (free ids, 0x16–0x1F, out-of-range) resolve
 *      to null — the table carries no geometry and no tier leak;
 *  (c) the shared registry is untouched: Arena.byId stays null for every
 *      pack id, so the hard gate (registerPro/byId) is unchanged.
 *
 * Uses the shared check()/passed/failed counters of Tests.kt. Runs BEFORE
 * RegistryGateTestKt in the EngineTests shim: that suite mutates the
 * registry (registers 0x10/0x11 stubs) and must stay last.
 * Run standalone as `robofight.ProArenaNamesTestKt`.
 */
fun main() {
    println("\n== Pro arena names tests (P3 names-only table) ==")

    val expected = mapOf(
        0x10 to "GEN-MAZE",
        0x11 to "GEN-ISLES",
        0x12 to "COLISEUM",
        0x13 to "PINWHEEL",
        0x14 to "TWIN WALLS",
        0x15 to "LABYRINTH",
    )
    for ((id, name) in expected) {
        val got = Arena.proArenaNames(id)
        check("0x${id.toString(16).uppercase()} names '$name'", got == name, "got '$got'")
        check("0x${id.toString(16).uppercase()} stays UNREGISTERED (gate intact)",
            Arena.byId(id) == null, "byId returned '${Arena.byId(id)?.name}'")
    }

    check("free ids carry no pro name", listOf(0x00, 0x01, 0x02, 0x03, 0x04).all { Arena.proArenaNames(it) == null })
    check("reserved-but-unnamed 0x16..0x1F resolve null", (0x16..0x1F).all { Arena.proArenaNames(it) == null })
    check("out-of-range ids resolve null", listOf(-1, 0x05, 0x0F, 0x20, 0xFF).all { Arena.proArenaNames(it) == null })
    check("registry still the free five", Arena.all().size == 5, "got ${Arena.all().size}")

    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}
