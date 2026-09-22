package robofight.android

import robofight.assembler.assemble
import robofight.world.Bot
import robofight.world.World

/**
 * Drives one fight: assembles both bots, builds a [World], and advances it a
 * fixed number of ticks per render frame so the arena animates smoothly.
 *
 * All engine state lives on the UI thread (one tick batch per frame, ~60fps),
 * so no synchronization is needed — this is the only object that touches [world].
 */
class RunController {

    var world: World? = null
        private set
    var status: String = "edit firmware, then RUN"
        private set
    var errorLine: Int = -1          // 1-based line in the failing source
        private set
    var errorCount: Int = 0
        private set
    var errorMsg: String = ""
        private set
    var errorBot: String = ""        // which side's firmware failed ("" = none)
        private set

    /** Called by the view each frame after ticking (to refresh stats / status bar). */
    var onFrame: (() -> Unit)? = null

    /**
     * Start a fresh fight between [a] and [b].
     *
     * [sourceFor] resolves each slot's firmware (the activity reads the
     * BotFiles store for file slots and the preset constant for presets).
     * Both sides are validated with assemble() first so a bad line on either
     * bot surfaces before any tick runs. Returns true if the fight started,
     * false on an assembler error (status + errorLine + errorBot then describe
     * the failure).
     */
    fun start(a: BotSlot, b: BotSlot, sourceFor: (BotSlot) -> String): Boolean {
        val first = listOf(a, b).firstNotNullOfOrNull { slot ->
            val res = assemble(sourceFor(slot))
            if (!res.ok) slot to res.errors.first() else null
        }
        if (first != null) {
            val (slot, err) = first
            errorLine = err.line
            errorBot = slot.name
            errorCount = 1
            errorMsg = err.msg
            status = "${slot.name} line ${err.line}: ${err.msg}"
            onFrame?.invoke()
            return false
        }
        errorLine = -1
        errorCount = 0
        errorMsg = ""
        errorBot = ""
        status = "running…"

        val w = World()
        w.add(Bot(a.name, a.glyph, a.color, 10, 15, 2, sourceFor(a)))
        w.add(Bot(b.name, b.glyph, b.color, 10, 4, 3, sourceFor(b)))
        world = w
        onFrame?.invoke()
        return true
    }

    /**
     * Advance up to [n] ticks. Returns the number actually advanced.
     * Caps at the world's finished state so we never over-tick.
     */
    fun step(n: Int): Int {
        val w = world ?: return 0
        var advanced = 0
        var guard = 0
        while (advanced < n && !w.finished && guard < 256) {
            w.tickOnce()
            advanced++
            guard++
        }
        if (w.finished) {
            status = w.winner?.let { "WINNER: ${it.name}" } ?: "DRAW"
        }
        return advanced
    }

    /** Reset to the idle "press RUN" state (clears the arena). */
    fun reset() {
        world = null
        status = "edit firmware, then RUN"
        errorLine = -1
        errorCount = 0
        errorMsg = ""
        errorBot = ""
        onFrame?.invoke()
    }
}
