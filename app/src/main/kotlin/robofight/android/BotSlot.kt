package robofight.android

/**
 * A selectable opponent / player slot in the RUN screen.
 *
 * [isFile] distinguishes the two kinds of slots:
 *  - presets  (index 0..4): the five engine bots (HUNTER/TURTLE/SNAKE/CHAOS/WALKER);
 *    [firmware] holds their source directly and [isFile] is false.
 *  - user files (index 5+): MYBOT, BOT1, ... — [name] is the file name in the
 *    [robofight.android.BotFiles] store and the source is resolved at fight
 *    time, so the file always wins over any unsaved editor text.
 *
 * [color] is an index into [robofight.world.Palette.HEX] (16 entries).
 */
class BotSlot(
    val name: String,
    val glyph: Char,
    val color: Int,
    val firmware: String,
    val isFile: Boolean = false,
)
