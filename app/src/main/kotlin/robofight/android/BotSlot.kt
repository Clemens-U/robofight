package robofight.android

/**
 * A selectable opponent / player slot.
 * index 0..4  = the five engine presets (HUNTER/TURTLE/SNAKE/CHAOS/WALKER)
 * index 5     = MYBOT  — the user's editable firmware loaded from assets/MYBOT.asm
 */
class BotSlot(
    val name: String,
    val glyph: Char,
    val color: Int,        // index into robofight.world.Palette.HEX
    var firmware: String,
)
