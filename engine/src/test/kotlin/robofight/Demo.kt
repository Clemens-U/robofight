package robofight

import robofight.world.*

/**
 * Headless demo: plays HUNTER vs TURTLE (and a 5-bot melee) and prints the
 * ASCII arena at key moments. This is the terminal version of the in-game view.
 */
fun main() {
    fun banner(t: String) {
        val w = 46
        println()
        println("+" + "=".repeat(w - 2) + "+")
        println("|" + t.centered(w - 2) + "|")
        println("+" + "=".repeat(w - 2) + "+")
    }
    fun arena(w: World, title: String) {
        println("  $title")
        TextGrid.render(w).trimEnd().lines().forEach { println("  $it") }
    }

    banner("OPCODE ARENA · HUNTER vs TURTLE")

    val h = Bot("HUNTER", 'H', 2, 10, 15, 1, Presets.HUNTER)
    val t = Bot("TURTLE", 'T', 4, 10, 5, 3, Presets.TURTLE)
    val w = World(); w.add(h); w.add(t)

    arena(w, "— start —")
    while (!w.finished && w.tick < 40) w.tickOnce()
    arena(w, "— after tick ${w.tick} —")

    while (!w.finished) w.tickOnce()
    arena(w, "— final —")
    println("  " + Simulator.stats(w))

    banner("OPCODE ARENA · 5-BOT MELEE")
    val bots = Presets.all().map { Bot(it.name, it.glyph, it.color, it.x, it.y, 1, it.firmware) }
    val w2 = World(); bots.forEach { w2.add(it) }
    while (!w2.finished) w2.tickOnce()
    arena(w2, "— final —")
    println("  " + Simulator.stats(w2))
}

private fun String.centered(width: Int): String {
    val pad = (width - length).coerceAtLeast(0)
    return " ".repeat(pad / 2) + this + " ".repeat(pad - pad / 2)
}
