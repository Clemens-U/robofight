// RF-8 core — pure Kotlin/JVM library (no Android, no UI, no filesystem,
// no network, no game-content dependency; PLAN-RF8-MULTIAPP.md §7).
//
// Owns the machine layer: ISA table + port catalog (robofight.isa),
// assembler (robofight.assembler), disassembler (robofight.disasm) and
// virtual machine (robofight.vm). The Arena :engine and the future
// :fireline-engine consume it through this module; :app receives it
// transitively via :engine.
//
// Toolchain mirrors engine/build.gradle.kts: Kotlin 2.2.10 via the version
// catalog, jvmTarget 11 pinned on Kotlin AND Java so both compile tasks
// agree (AGP 9 default Java target is 11; see robofight-android-build).
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
