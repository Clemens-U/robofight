// RF-8: FIRELINE WEB CONSOLE data layer (docs/FIRELINE-WEB.md §13 architecture,
// §15 frozen v1 layout, §9 W3).
//
// Presentation-free Kotlin only: the mission-picker rows for `Missions.ALL` and a
// `MissionSession` driver for the run surface. No DOM, no HTML, no JS interop, no
// npm dependencies, no Android: the browser shell (later in W3) renders these
// values, it never computes them. Everything here is read from the shared session
// layer in `:fireline-engine` commonMain, so the web console and the Android
// MISSION screen drive the identical engine (§13 "two UIs drifting" mitigation).
//
// wasmJs target configured exactly as in docs/WEB-W1-RECORD.md §4 (`nodejs()` so
// `wasmJsTest` runs through `wasmJsNodeTest` instead of reporting NO-SOURCE; the
// toolchain download-off wiring lives in gradle/wasm-toolchain.gradle and
// kotlin.js.yarn=false in gradle.properties). No `jvm` target on purpose:
// `:fireline-engine` has one because the Android modules consume it through jvm,
// and nothing consumes this module from the Android side.

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    wasmJs {
        nodejs()
        binaries.library()
    }

    sourceSets {
        commonMain.dependencies {
            // The shared session layer plus the RF-8 machine layer's public formatting
            // helpers (hexPad/decPad, public since W1 exactly so module boundaries
            // reuse them instead of retyping formatting).
            implementation(project(":fireline-engine"))
            implementation(project(":rf8-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
