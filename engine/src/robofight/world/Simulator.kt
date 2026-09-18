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
