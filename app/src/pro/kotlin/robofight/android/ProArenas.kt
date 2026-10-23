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
        // P2 Arena Pack 1 (PLAN-PRO §5): seed-derived family entries
        // (0x10 GEN-MAZE, 0x11 GEN-ISLES — preview instances carrying their
        // per-seed generators) + curated layouts 0x12–0x15, each registered
        // via Arena.registerPro (hard id gate 0x10–0x1F). See ProArenaPack.
        for (preview in ProArenaPack.previews()) Arena.registerPro(preview)
    }
}
