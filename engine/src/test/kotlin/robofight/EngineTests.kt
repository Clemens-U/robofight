package robofight

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Runs the RoboFight engine self-test suite (Tests.kt) inside JUnit so the 20
 * checks appear in Android Studio's test panel and in `:engine:test`.
 *
 * Tests.kt is kept verbatim (global `check()` + `main()` + `passed`/`failed`).
 * The suite entry point is invoked via reflection: both Tests.kt and Demo.kt
 * declare top-level `main()` in package `robofight`, so a direct `TestsKt.main()`
 * facade reference does not resolve cleanly under the Kotlin 2 K2 compiler.
 * Reflection resolves the same class at runtime with no compile-time ambiguity.
 *
 * On success the suite prints 20 PASS lines and sets the global `failed` counter
 * to 0; we assert that and re-emit the captured output into the Gradle test log.
 */
class EngineTests {

    @Test
    fun engineSuite() {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true))
        try {
            val facade = Class.forName("robofight.TestsKt")
            val main = facade.getMethod("main")
            main.invoke(null)
        } finally {
            System.setOut(original)
        }
        val output = captured.toString()
        check(failed == 0) {
            "RoboFight engine suite failed ($failed failed, $passed passed):\n$output"
        }
        println(output)
    }
}
