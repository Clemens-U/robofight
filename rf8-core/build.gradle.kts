// RF-8 core — pure Kotlin library (no Android, no UI, no filesystem, no
// network, no game-content dependency; PLAN-RF8-MULTIAPP.md §7).
//
// Owns the machine layer: ISA table + port catalog (robofight.isa), assembler
// (robofight.assembler), disassembler (robofight.disasm) and virtual machine
// (robofight.vm). The Arena :engine and :fireline-engine consume it; :app
// receives it transitively via :engine.
//
// W1 (docs/FIRELINE-WEB.md §9): Kotlin Multiplatform (jvm + wasmJs) so the SAME
// commonMain sources serve the JVM (Arena + FIRELINE Android apps, headless
// tests) and wasmJs (browser). Source sets moved main -> commonMain and test ->
// commonTest with no semantic change; the only code edits are output-identical
// formatting replacements (String.format -> hexPad/decPad in isa/TextFormat.kt,
// String(CharArray) -> concatToString) because the wasmJs stdlib does not expose
// those calls. Tests use kotlin.test so one suite runs on both targets.
//
// Toolchain unchanged: Kotlin 2.2.10 via the version catalog, jvmTarget 11 pinned
// on Kotlin so it agrees with AGP's Java 11 default (see robofight-android-build).

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    wasmJs {
        nodejs()
        // Executable binaries expose the .wasm + JS glue bundle that a browser
        // page loads; wasmJsTest runs the common suite on this target.
        binaries.executable()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
