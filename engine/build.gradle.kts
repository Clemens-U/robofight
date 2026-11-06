// Opcode Arena engine — pure JVM Kotlin library (no Android, no LibGDX).
//
// This is the Arena world/simulator layer: world rules and the 5 preset
// bots. The RF-8 machine layer (ISA, port catalog, assembler, disassembler,
// VM) moved to the pure module :rf8-core in Phase 3 (PLAN-RF8-MULTIAPP.md §7)
// and arrives here through `implementation(project(":rf8-core"))`. It is
// shared with the Android app (:app) via `implementation(project(":engine"))`.
//
// The self-test suites live in src/test/kotlin (Tests.kt et al.) and are run
// by `:engine:test` through the JUnit shim EngineTests.kt, so they show up in
// Android Studio's test runner. Demo.kt (headless runnable script) is also in
// the test source set — it shares the test classpath, not the library jar.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        // JVM 11 matches the verified offline build and Android's default
        // jvmTarget; pin Java to the same target so the two compile tasks agree.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    // `api`, not `implementation`: :engine's public API exposes :rf8-core
    // types — `robofight.world.Bot : robofight.vm.Env`, and World/Bot surface
    // Vm/ISA/Assembler types the app consumes directly (ArenaView.kt uses the
    // Env supertype, MainActivity imports robofight.vm). Kotlin/AGP compiles
    // types per-module, so `implementation` breaks :app's compile classpath.
    // :app still declares only :engine and receives :rf8-core transitively.
    api(project(":rf8-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
