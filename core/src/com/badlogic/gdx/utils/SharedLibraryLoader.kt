package com.badlogic.gdx.utils

import java.io.File
import java.net.URLClassLoader

/**
 * Shim for the missing com.badlogic.gdx.utils.SharedLibraryLoader (absent from
 * the gdx-1.14.2.jar in libs/). GdxNativesLoader / Lwjgl3NativesLoader call
 * `new SharedLibraryLoader().load("gdx")` / `.load("lwjgl")`.
 *
 * Strategy: extract EVERY native lib from the classpath jars (both flat and
 * nested windows/x64/org/lwjgl/... layouts) into one shared temp dir, then
 * System.load() the requested one from that dir. Loading from a shared dir lets
 * the Windows loader resolve inter-DLL dependencies (gdx64.dll -> lwjgl.dll)
 * from the same folder.
 */
class SharedLibraryLoader {
    private companion object {
        var DIR: File? = null
        // Lwjgl3Application reads this static (checks it's not MacOsX); set to Windows.
        @JvmStatic
        var os: Os = Os.Windows
    }

    fun load(library: String) {
        val dir = DIR ?: buildDir().also { DIR = it }
        val cl = classloader()
        val candidates = listOf(
            "${library}64.dll", "$library.dll",
            "lib${library}64.so", "lib$library.so",
            "lib$library.dylib", "lib${library}64.dylib",
        )
        for (c in candidates) {
            val f = File(dir, c)
            if (f.exists() && f.length() > 0) {
                try { System.load(f.absolutePath); return } catch (e: Throwable) { /* fallthrough */ }
            }
        }
        throw UnsatisfiedLinkError("SharedLibraryLoader: cannot load '$library' (tried ${candidates.joinToString()} in $dir)")
    }

    private fun classloader(): URLClassLoader {
        val cp = (System.getProperty("java.class.path") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }
        return URLClassLoader(cp.map { File(it).toURI().toURL() }.toTypedArray())
    }

    private fun buildDir(): File {
        val dir = File.createTempFile("rfgdx_natives_", "")
        dir.delete(); dir.mkdirs()
        val cl = classloader()
        val exts = setOf("dll", "so", "dylib")
        // collect every native path in the classpath
        val natives = mutableSetOf<String>()
        for (entry in (System.getProperty("java.class.path") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }) {
            val f = File(entry)
            if (!f.exists()) continue
            try {
                val zf = java.util.jar.JarFile(f)
                for (e in zf.entries()) {
                    val n = e.name
                    if (e.isDirectory) continue
                    val full = n.substringAfterLast('/')
                    if (exts.any { full.endsWith(it) }) natives.add(n)
                }
                zf.close()
            } catch (e: Exception) { /* not a jar */ }
        }
        for (n in natives) {
            val base = n.substringAfterLast('/')
            val target = File(dir, base)
            val url = cl.findResource(n) ?: continue
            try {
                url.openStream().use { ins -> target.outputStream().use { os -> ins.copyTo(os) } }
            } catch (e: Exception) { }
        }
        return dir
    }
}
