// RF-8: FIRELINE world engine — pure Kotlin library (FIRELINE-DESIGN.md;
// PLAN-RF8-MULTIAPP.md §8.1).
//
// Owns the FIRELINE world profile: static layout decoding, rover body state,
// water packets, dynamic fire + deterministic spread, civilians, objectives,
// results and the headless run path. Consumes the machine layer (ISA, port
// catalog, assembler, VM) through :rf8-core ONLY — it MUST NOT depend on
// :engine or any Android module: sharing happens through the shared core, not
// by making FIRELINE an Arena mode. No Android, filesystem or network deps.
//
// W1 (docs/FIRELINE-WEB.md §9): Kotlin Multiplatform (jvm + wasmJs) with the
// engine in commonMain, so the browser build and the Android build run the
// identical engine code. Formatting replacements are output-identical (see
// rf8-core/build.gradle.kts). wasmJsMain holds the W1 hello-wasm spike only —
// it is not engine behaviour and never reaches the Android targets.

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
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":rf8-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
