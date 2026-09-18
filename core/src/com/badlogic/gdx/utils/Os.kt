package com.badlogic.gdx.utils

/**
 * Shim for com.badlogic.gdx.utils.Os (absent from the gdx-1.14.2.jar in libs/).
 * Lwjgl3Application compares SharedLibraryLoader.os against Os.MacOsX.
 * Mirrors the real gdx enum's constant set.
 */
enum class Os {
    Windows, MacOsX, Linux, Android, Ios, J2ME, Unknown
}
