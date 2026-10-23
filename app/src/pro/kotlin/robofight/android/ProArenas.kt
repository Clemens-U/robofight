package robofight.android

import robofight.world.Arena

/**
 * PRO flavor: registers this app's arena pack into the engine registry at
 * startup. Registered layouts are playable by id; ids 0x10–0x1F that are NOT
 * registered stay importable-but-locked (ChallengeFormat §11.7), exactly like
 * in the free app.
 *
 * Hard gate: only Arena.registerPro may add layouts, and it enforces the
 * reserved Pro id range 0x10–0x1F + uniqueness (engine/src/main/.../Arena.kt).
 *
 * Idempotent: onCreate can run again after configuration changes; the registry
 * refuses duplicate ids, so registration happens once per process.
 */
object ProArenas {
    private var registered = false

    fun registerAll() {
        if (registered) return
        registered = true
        // P2 Arena Pack 1 registers here (0x10 GEN-MAZE, 0x11 GEN-ISLES,
        // 0x12–0x15 curated), each via Arena.registerPro(...).
        // P1 ships the Pro app with the five free arenas only — the pack
        // arrives in P2; the gate plumbing (this file + registerPro +
        // RegistryGateTest) is what P1 delivers.
    }

    /** Helper for P2 packs: build a Layout in the Pro range and register it. */
    fun register(id: Int, name: String, layout: () -> robofight.world.Layout) {
        require(id in 0x10..0x1F) { "pro id 0x${id.toString(16)} out of range" }
        Arena.registerPro(layout().copy(id = id, name = name))
    }
}
