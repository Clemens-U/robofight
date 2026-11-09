// FIRELINE Android app (PLAN-RF8-MULTIAPP.md §9.1).
//
// Separate installable package from Opcode Arena: namespace/applicationId
// robofight.fireline, its own label, icon, database filename and versioning.
// No product flavors for v1, no permissions (offline posture), and it depends
// on the pure-JVM FIRELINE engine only - never on the Arena :app module.
//
// AGP 9.0+ has built-in Kotlin, so only `com.android.application` is applied
// (org.jetbrains.kotlin.android is removed in AGP 9 and would fail the build).
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "robofight.fireline"
    compileSdk = 37

    defaultConfig {
        applicationId = "robofight.fireline"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.1"
    }

    // Use the same local upload key as the Arena apps. The key and password
    // files are git-ignored and exist only on the signing machine; CI and
    // source-only checkouts can still configure an unsigned release build.
    val releaseKeystore = rootProject.file("robofight.keystore")
    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                val keystorePasswordFile = rootProject.file("keystore.pass")
                storeFile = releaseKeystore
                storePassword = keystorePasswordFile.readText().trim()
                keyAlias = "robofight"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        // AGP's default Java target is 11; pin Kotlin to the same target so
        // the Java and Kotlin compile tasks agree (see robofight-android-build).
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":fireline-engine"))
    // The machine layer is an explicit dependency, not a transitive one:
    // :fireline-engine declares :rf8-core as `implementation`, and the editor
    // calls the assembler directly for per-line error reporting.
    implementation(project(":rf8-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
