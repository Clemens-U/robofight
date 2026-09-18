package robofight.desktop

import robofight.world.*
import java.io.File
import kotlin.math.max

/**
 * RoboFight desktop runner (M2).
 *
 * Usage:
 *   robofight HUNTER TURTLE                ; 1v1 between two presets
 *   robofight MYBOT.asm CHAOS              ; your bot vs a preset
 *   robofight HUNTER TURTLE --live --speed 120
 *   robofight HUNTER TURTLE --step         ; pause after every tick
 *   robofight --melee                      ; all five presets
 *
 * Options:
 *   --ticks N     max ticks (default 200)
 *   --speed MS    delay per tick in live mode (default 100)
 *   --live        animate the arena in place (clears screen each tick)
 *   --step        wait for ENTER after each tick ('q' quits, 'x' skips 10)
 *   --melee       run all five presets at once (ignores bot args)
 *   --seed N      RNG seed (default 12345) — deterministic replays
 *   --log FILE    write the full fight trace (one line per tick) to FILE
 *   --help
 */
data class FightConfig(
    var a: String = "HUNTER",
    var b: String = "TURTLE",
    var maxTicks: Int = MAX_TICKS,
    var speedMs: Int = 100,
    var live: Boolean = false,
    var step: Boolean = false,
    var melee: Boolean = false,
    var seed: Int = 12345,
    var logFile: String? = null,
)

val PRESET_NAMES = listOf("HUNTER", "TURTLE", "SNAKE", "CHAOS", "WALKER")

fun parseArgs(args: Array<String>): FightConfig {
    val c = FightConfig()
    val positional = ArrayList<String>()
    var i = 0
    fun die(msg: String): Nothing {
        println("error: $msg")
        println()
        printHelp()
        kotlin.system.exitProcess(2)
    }
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--help" || a == "-h" -> { printHelp(); kotlin.system.exitProcess(0) }
            a == "--ticks" -> { i++; if (i >= args.size) die("--ticks needs a value"); c.maxTicks = args[i].toIntOrNull() ?: die("bad ticks '${args[i]}'") }
            a == "--speed" -> { i++; if (i >= args.size) die("--speed needs a value"); c.speedMs = args[i].toIntOrNull() ?: die("bad speed '${args[i]}'") }
            a == "--live" -> c.live = true
            a == "--step" -> c.step = true
            a == "--melee" -> c.melee = true
            a == "--seed" -> { i++; if (i >= args.size) die("--seed needs a value"); c.seed = args[i].toIntOrNull() ?: die("bad seed '${args[i]}'") }
            a == "--log" -> { i++; if (i >= args.size) die("--log needs a value"); c.logFile = args[i] }
            a.startsWith("--") -> die("unknown option '$a'")
            else -> {
                if (positional.size >= 2) die("too many bots (max 2; use --melee for more)")
                positional.add(a)
            }
        }
        i++
    }
    if (!c.melee) {
        if (positional.size == 1) { c.a = positional[0]; c.b = "HUNTER" }
        if (positional.size == 2) { c.a = positional[0]; c.b = positional[1] }
    }
    return c
}

fun printHelp() = println(
"""
ROBOFIGHT — terminal arena
usage: robofight BOT_A BOT_B [options]

  BOT = preset name (HUNTER TURTLE SNAKE CHAOS WALKER) or path to a .asm file
options:
  --ticks N   max ticks (default 200)
  --speed MS  delay per tick in live mode (default 100)
  --live      animate the arena in place
  --step      wait for ENTER after each tick (q = quit, x = skip 10)
  --melee     all five presets at once
  --seed N    RNG seed (default 12345)
  --log FILE  write the full fight trace
  --help
""")

private fun makeBot(spec: String, x: Int, y: Int, dir: Int, colorIdx: Int): Bot {
    val up = spec.uppercase()
    val preset = Presets.all().firstOrNull { it.name == up }
    return if (preset != null) {
        Bot(preset.name, preset.glyph, colorIdx, x, y, dir, preset.firmware)
    } else {
        val f = File(spec)
        if (!f.isFile) throw IllegalArgumentException("no preset '$spec' and no file: $spec")
        Bot(f.nameWithoutExtension, f.nameWithoutExtension.first().uppercaseChar(), colorIdx, x, y, dir, f.readText())
    }
}

fun run(cfg: FightConfig) {
    val log = cfg.logFile?.let { File(it).bufferedWriter() }
    fun trace(line: String) { log?.append(line); log?.append('\n') }

    val world = World().apply { setSeed(cfg.seed) }
    val bots = if (cfg.melee) {
        Presets.all().mapIndexed { i, p ->
            world.add(makeBot(p.name, p.x, p.y, 1, i % 5))
        }
        world.bots
    } else {
        val a = makeBot(cfg.a, 10, 15, 1, 2)
        val b = makeBot(cfg.b, 10, 4, 3, 4)
        world.add(a); world.add(b)
        listOf(a, b)
    }
    trace("=== RoboFight ===  bots=${bots.joinToString { it.name }}  seed=${cfg.seed}  maxTicks=${cfg.maxTicks}")
    frame(world, "start")

    val escClear = "\u001b[H\u001b[2J"
    while (!world.finished) {
        world.tickOnce()
        if (world.tick > cfg.maxTicks) { world.forceEnd(); break }
        trace("tick=${world.tick}  ${world.lastLine}")
        if (cfg.live) print(escClear)
        frame(world, "tick ${world.tick}")
        if (cfg.step) {
            print("  [enter=step · x=+10 · q=quit] > ")
            val ch = (readLine()?.trim()?.firstOrNull() ?: '\n').code
            if (ch == 'q'.code) { world.forceEnd(); break }
            if (ch == 'x'.code) {
                repeat(10) { if (!world.finished) { world.tickOnce(); trace("tick=${world.tick}  ${world.lastLine}") } }
                if (cfg.live) print(escClear)
                frame(world, "tick ${world.tick}")
            }
        }
        if (cfg.live && cfg.speedMs > 0) Thread.sleep(cfg.speedMs.toLong())
    }

    log?.flush(); log?.close()
    println()
    println("  ── RESULT ──")
    val winner = world.winner
    println("  winner: ${winner?.name ?: "DRAW"}   ticks: ${world.tick}")
    println()
    for (b in world.bots.sortedByDescending { it.hp }) {
        val mark = if (b === winner) "★" else " "
        println("  ${mark} ${b.name.padEnd(8)} HP ${b.hp.toString().padStart(3)}   " +
                "shots ${b.shots.toString().padStart(3)}   hits ${b.hits.toString().padStart(3)}   " +
                "dealt ${b.dmgDealt.toString().padStart(3)}   taken ${b.dmgTaken.toString().padStart(3)}")
    }
    if (cfg.logFile != null) println("\n  trace written to: ${cfg.logFile}")
}

private fun frame(world: World, title: String) {
    println("  ── $title " + "─".repeat(max(1, 30 - title.length)))
    TextGrid.render(world).trimEnd().lines().forEach { println("  $it") }
    for (b in world.bots) {
        val bar = bar(b.hp, MAX_HP)
        println("  ${b.name.padEnd(8)} ${bar} ${b.hp.toString().padStart(3)}")
    }
}

private fun bar(value: Int, max: Int): String {
    val w = 10
    val filled = (value * w + max / 2) / max
    return "[" + "#".repeat(filled) + "-".repeat(w - filled) + "]"
}

fun main(args: Array<String>) {
    try {
        run(parseArgs(args))
    } catch (e: Exception) {
        println("error: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
