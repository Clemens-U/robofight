// RoboFight Android app — native Android front-end over the pure-Kotlin engine.
//
// AGP 9.0+ has built-in Kotlin, so only `com.android.application` is applied
// (org.jetbrains.kotlin.android is removed in AGP 9 and would fail the build).
// The engine is consumed as a plain JVM library dependency.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "robofight.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "robofight.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.3-m3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No proguard file — the app is tiny; keep it simple and correct.
        }
    }

    // Built-in Kotlin (AGP 9): configure the Kotlin compiler via the kotlin
    // extension instead of the removed kotlinOptions / jvmToolchain DSL.
}

kotlin {
    compilerOptions {
        // Match AGP's default Java jvmTarget (11) and the engine module.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":engine"))
}
