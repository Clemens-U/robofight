// Opcode Arena engine — pure JVM Kotlin library (no Android, no LibGDX).
//
// This is the combat-programming core: ISA, assembler, VM, world/simulator,
// and the 5 preset bots. It is shared with the Android app (:app) via
// `implementation(project(":engine"))`.
//
// The 20-check self-test lives in src/test/kotlin (Tests.kt) and is run by
// `:engine:test` through the JUnit shim EngineTests.kt, so it shows up in
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
