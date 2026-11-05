package robofight

import robofight.assembler.assemble
import robofight.world.Presets
import java.io.File

/**
 * Phase 0 migration-parity fixtures (PLAN-RF8-MULTIAPP.md §4).
 *
 * Pinned assembled-byte fixtures for ALL shipped preset firmware and MYBOT,
 * in both shipping families:
 *
 *  - ENGINE: the `Presets.kt` source strings (the seed source the Gradle-built
 *    APK and every headless run actually use) and the app asset
 *    `app/src/main/assets/MYBOT.asm` (the seeded MYBOT).
 *  - SEED: the repo `firmware/<NAME>.asm` files (offline-build asset mirror and
 *    export mirror; note APEX ships only in this family).
 *
 * These are migration parity fixtures, not gameplay goldens: they prove the
 * assembler output for existing firmware stays byte-identical across module
 * moves (RF8-PLATFORM-SPEC.md §9.2). A diff here without an assembler change
 * is a regression.
 *
 * Baseline notes (recorded 2026-11-05, pre-shield-phase):
 *  - `firmware/HUNTER.asm`, `firmware/TURTLE.asm`, `firmware/SNAKE.asm` differ
 *    from the `Presets.kt` fallbacks (the .asm seeds are the newer variants);
 *    `firmware/MYBOT.asm` differs from the seeded app asset. Both families are
 *    pinned as-is; the divergence is pre-existing and documented, not asserted
 *    equal.
 *  - `app/src/main/assets/MYBOT.asm` is byte-identical to `firmware/SNAKE.asm`.
 */

private fun hexOf(b: ByteArray): String =
    b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Assembled bytes of the repo `firmware/<NAME>.asm` seed files. */
private val SEED_GOLDEN = linkedMapOf(
    "APEX" to "b20000b40000b10000b70100080000b70500210000b80100b704009121008201009b0f00b00000b70b00920900b40000900901b00000b20000b40000900901b60000900901",
    "CHAOS" to "b70a00700100910600b40000900001b70a00700100910600b00000900001b70a00700100910600b20000900001b50000900001",
    "HUNTER" to "b70400913900080000b70500910900b20000630000900c01b70b00910f000200008201009c0f00b00000900001b40000b00000900001b20000b20000900001b60000900001",
    "MYBOT" to "b704000b0000b20000b40000b70400840000980c00b20000b20000b40000b40000b70100080000b70500210000b80100b704009121008201009b0f00b00000b70b00920900b40000902101b00000b20000b40000902101b60000902101",
    "SNAKE" to "070300b70400912100b70b00920c00b40000640000910300900301b20000b20000b00000070300900301b60000900301",
    "TURTLE" to "b704009112008201009c0600b00000900001b50000900001b60000900001",
    "WALKER" to "070300b70b00920c00b40000640000910900900301b20000900301b00000070300900301",
)

/** Assembled bytes of the engine preset sources (what the Gradle APK seeds). */
private val ENGINE_GOLDEN = linkedMapOf(
    "HUNTER" to "b70400913900080000b70500910900b20000630000900c01b70b00910f000200008201009c0f00b00000900001b40000b00000900001b20000b20000900001b70b00910600b20000903f01b40000900001",
    "TURTLE" to "b704009112008201009c0600b00000900001b50000900001b70b00910600b20000901801b40000900001",
    "SNAKE" to "070300b70400912100b70b00920c00b40000640000910300900301b20000b20000b00000070300900301b70b00910600b20000902a01b40000900301",
    "CHAOS" to "b70a00700100910600b40000900001b70a00700100910600b00000900001b70a00700100910600b20000900001b50000900001",
    "WALKER" to "070300b70b00920c00b40000640000910900900301b20000900301b00000070300900301",
)

/** Assembled bytes of `app/src/main/assets/MYBOT.asm` (the seeded MYBOT). */
private val ASSET_MYBOT_GOLDEN =
    "070300b70400912100b70b00920c00b40000640000910300900301b20000b20000b00000070300900301b60000900301"

/**
 * Locate the repo root by walking up from the current directory: tests run
 * with cwd = repo root (kotlinc standalone) or cwd = engine/ (Gradle).
 */
private fun findRepoRoot(): File? {
    var dir: File? = File("").absoluteFile
    for (hop in 0 until 5) {
        val d = dir ?: return null
        if (File(d, "settings.gradle.kts").isFile && File(d, "firmware").isDirectory) return d
        dir = d.parentFile
    }
    return null
}

fun main() {
    println("== firmware byte fixtures ==")

    // ---- engine-owned sources (no file access needed) ----
    for (pd in Presets.all()) {
        val golden = ENGINE_GOLDEN[pd.name]
        val r = assemble(pd.firmware)
        check("ENGINE preset ${pd.name} assembles", r.ok, r.errors.toString())
        check("ENGINE preset ${pd.name} bytes pinned", r.ok && hexOf(r.code) == golden,
            "got ${if (r.ok) hexOf(r.code) else "(assemble failed)"}")
    }

    // ---- repo seed files ----
    val root = findRepoRoot()
    check("repo root found for seed-file fixtures", root != null)
    if (root != null) {
        for ((name, golden) in SEED_GOLDEN) {
            val file = File(root, "firmware/$name.asm")
            if (!file.isFile) { check("SEED firmware/$name.asm present", false, "missing"); continue }
            val r = assemble(file.readText())
            check("SEED firmware/$name.asm assembles", r.ok, r.errors.toString())
            check("SEED firmware/$name.asm bytes pinned", r.ok && hexOf(r.code) == golden,
                "got ${if (r.ok) hexOf(r.code) else "(assemble failed)"}")
        }
        val asset = File(root, "app/src/main/assets/MYBOT.asm")
        check("asset MYBOT.asm present", asset.isFile, "missing")
        if (asset.isFile) {
            val r = assemble(asset.readText())
            check("asset MYBOT.asm bytes pinned", r.ok && hexOf(r.code) == ASSET_MYBOT_GOLDEN,
                "got ${if (r.ok) hexOf(r.code) else "(assemble failed)"}")
        }
    }
}
