package robofight

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Runs the Opcode Arena engine self-test suites inside JUnit so the checks
 * appear in Android Studio's test panel and in `:engine:test`:
 *
 *  - Tests.kt — the original 20-check engine suite
 *  - ArenaTests.kt — the challenge-code arena suite (§12.1 geometry, §7.5
 *    walls, §6 timed/HP, §7.6 SCATTER, §4.3 binary path) including the
 *    full §12.3 fight matrix (250 headless fights)
 *  - RegistryGateTest.kt — the P1 two-app gate proof (locked preview for
 *    unregistered 0x10, playable after Arena.registerPro, hard id-range
 *    gate). Runs LAST: it mutates the Arena registry.
 *  - ProArenaNamesTest.kt — the P3 names-only table proof (pack names
 *    resolve while byId stays null). Runs BEFORE the gate test: the names
 *    proof needs an untouched registry.
 *
 * All suites are kept verbatim-style (global `check()` + `main()` +
 * shared `passed`/`failed` counters). Their entry points are invoked via
 * reflection: Tests.kt, Demo.kt, ArenaTests.kt, FightMatrix.kt,
 * ProArenaNamesTest.kt and RegistryGateTest.kt each declare top-level
 * `main()` in package `robofight`, so a direct facade reference does not
 * resolve cleanly under the Kotlin 2 K2 compiler.
 *
 * On success the suites print their PASS lines and leave the global
 * `failed` counter at 0; we assert that and re-emit the captured output
 * into the Gradle test log.
 */
class EngineTests {

    @Test
    fun engineSuite() {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            for (facadeName in listOf(
                "robofight.TestsKt", "robofight.ArenaTestsKt",
                "robofight.ProArenaNamesTestKt", "robofight.RegistryGateTestKt",
            )) {
                val facade = Class.forName(facadeName)
                facade.getMethod("main").invoke(null)
            }
        } finally {
            System.setOut(original)
        }
        val output = captured.toString()
        check(failed == 0) {
            "Opcode Arena engine suite failed ($failed failed, $passed passed):\n$output"
        }
        println(output)
    }
}
