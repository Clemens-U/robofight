// Opcode Arena Android app — native Android front-end over the pure-Kotlin engine.
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
        targetSdk = 36
        // Free-app transfer hotfix: valid export filename/MIME metadata.
        versionCode = 9
        versionName = "0.7.1"
    }

    // CI-safe signing: the release keystore files exist only on the local
    // signing machine (git-ignored). Without them the release buildType
    // falls back to AGP's default signing instead of crashing at
    // configuration time. Local behavior with the keystore present is
    // unchanged.
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // P1 build split (PLAN-PRO §6): one source tree -> free + Pro APKs.
    // The compile-time tier gate is the flavor source sets: src/pro/kotlin
    // (real ProArenas + Pro packs) is compiled ONLY into the pro variants;
    // src/free/kotlin provides the no-op twin, so shared code needs no
    // BuildConfig branch. PRO_BUILD stays available for diagnostics/UI.
    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "tier"
    productFlavors {
        create("free") {
            dimension = "tier"
            // applicationId stays robofight.android (unchanged listing).
            manifestPlaceholders["appLabel"] = "Opcode Arena"
            buildConfigField("boolean", "PRO_BUILD", "false")
        }
        create("pro") {
            dimension = "tier"
            applicationIdSuffix = ".pro"
            // Paid-app transfer hotfix: export metadata + tolerant ZIP picker.
            versionCode = 2
            versionName = "1.0.1"
            manifestPlaceholders["appLabel"] = "Opcode Arena: The Circuit"
            buildConfigField("boolean", "PRO_BUILD", "true")
        }
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
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
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
