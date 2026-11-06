// RF-8: FIRELINE world engine — pure Kotlin/JVM library
// (FIRELINE-DESIGN.md; PLAN-RF8-MULTIAPP.md §8.1).
//
// Owns the FIRELINE world profile: static layout decoding, rover body state,
// water packets, dynamic fire + deterministic spread, civilians, objectives,
// results and the headless run path. Consumes the machine layer (ISA, port
// catalog, assembler, VM) through :rf8-core ONLY — it MUST NOT depend on
// :engine or any Android module: sharing happens through the shared core, not
// by making FIRELINE an Arena mode. No Android, filesystem or network deps.
//
// Toolchain mirrors rf8-core/build.gradle.kts: Kotlin 2.2.10 via the version
// catalog, jvmTarget 11 pinned on Kotlin AND Java so both compile tasks agree.
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
    implementation(project(":rf8-core"))
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
