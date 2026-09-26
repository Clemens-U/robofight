package robofight.world

/** Run a fight to completion (or maxTicks) and produce stats. */
object Simulator {
    fun runFight(
        bots: List<Bot>,
        maxTicks: Int = MAX_TICKS,
        onTick: ((World, Int) -> Unit)? = null,
    ): World {
        val w = World()
        bots.forEach { w.add(it) }
        while (!w.finished && w.tick < maxTicks) {
            w.tickOnce()
            onTick?.invoke(w, w.tick)
        }
        // If the tick limit (not a KO) ended the fight, resolve by HP —
        // "highest HP after N ticks" (DESIGN.md §10). Without this, finished
        // stays false and winner stays null, which is indistinguishable from
        // a stalemate and breaks callers that check w.finished.
        if (!w.finished) w.forceEnd()
        return w
    }

    fun stats(w: World): String {
        val sb = StringBuilder()
        sb.append("tick=${w.tick}")
        sb.append("  winner=").append(w.winner?.name ?: "DRAW")
        for (b in w.bots) {
            sb.append("  |  ").append(b.name)
                .append(" hp=").append(b.hp)
                .append(" shots=").append(b.shots)
                .append(" hits=").append(b.hits)
                .append(" dealt=").append(b.dmgDealt)
                .append(" taken=").append(b.dmgTaken)
        }
        return sb.toString()
    }
}
