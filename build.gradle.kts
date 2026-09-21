// Root build script — declares plugins used by subprojects (applied `false` here).
// AGP 9.0+ has built-in Kotlin, so the app module does NOT apply
// org.jetbrains.kotlin.android. The engine module is a plain kotlin("jvm")
// library, which is the standard way to share Kotlin code with an Android app.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
