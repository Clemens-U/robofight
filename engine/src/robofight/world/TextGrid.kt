package robofight.world

/**
 * 16-color NES-style palette (hex) + per-cell rendering of the 20×20 grid.
 * The same renderer powers the terminal demo and (later) the LibGDX canvas.
 */
object Palette {
    const val BLACK = 0
    const val WHITE = 1
    const val RED = 2
    const val LIGHT_RED = 3
    const val GREEN = 4
    const val LIGHT_GREEN = 5
    const val CYAN = 6
    const val BLUE = 7
    const val BROWN = 8
    const val ORANGE = 9
    const val PINK = 10
    const val PURPLE = 11
    const val YELLOW = 12
    const val GRAY = 13
    const val DARK_GRAY = 14

    val HEX = listOf(
        "#000000", "#FCFCFC", "#BC0000", "#FC9838",
        "#00A800", "#58D854", "#00E8D8", "#7878F8",
        "#A88400", "#F8D878", "#D878F8", "#98F898",
        "#F8B800", "#ACB0A0", "#747C74",
    )

    fun hex(c: Int) = HEX[c]
}

object TextGrid {
    private val DIR_GLYPH = arrayOf('<', '^', '>', 'v')
    private val BOT_GLYPH = arrayOf('#', 'H', 'T', 'S', 'C')

    /** One arena frame as a plain-text grid (for terminal / tests). */
    fun render(world: World): String {
        val sb = StringBuilder()
        for (py in 0 until GRID) {
            for (px in 0 until GRID) {
                val bot = world.botAt(px, py)
                if (bot != null) {
                    sb.append(bot.glyph)
                    sb.append(DIR_GLYPH[bot.facing % 4])
                } else {
                    val proj = world.projectiles.firstOrNull { it.x == px && it.y == py }
                    sb.append(if (proj != null) '*' else '.')
                }
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** One arena frame as an ANSI-colored string (2-char cells). */
    fun renderAnsi(world: World): String {
        val codes = intArrayOf(
            30, 37, 31, 38, 9, 46, 45, 44, 33, 208, 205, 46, 226, 250, 30,
        )
        fun c(code: Int, s: String) = "\u001b[${code}m$s\u001b[0m"
        val sb = StringBuilder()
        for (py in 0 until GRID) {
            for (px in 0 until GRID) {
                val bot = world.botAt(px, py)
                if (bot != null) {
                    sb.append(c(codes[bot.color], "%c%c".format(bot.glyph, DIR_GLYPH[bot.facing % 4])))
                } else {
                    val proj = world.projectiles.firstOrNull { it.x == px && it.y == py }
                    sb.append(if (proj != null) c(226, "* ") else "  ")
                }
            }
            sb.append('\n')
        }
        sb.append("\u001b[0m")
        return sb.toString()
    }
}
