package robofight.world

import robofight.world.Palette.RED
import robofight.world.Palette.GREEN
import robofight.world.Palette.CYAN
import robofight.world.Palette.PURPLE
import robofight.world.Palette.ORANGE

/** Canned firmware for the 5 starter bots. */
object Presets {
    val HUNTER = """
        ; HUNTER — face the nearest enemy (clockwise), step in, fire
top:    IN     DIST            ; nearest enemy distance -> A
        JZ     idle            ; nothing there? idle
        IN     ANGLE           ; A = steps clockwise until we face it
loop:   JZ     face            ; already facing?
        TURN   R               ; rotate one step clockwise
        DEC    A
        JMP    loop
face:   MOVE
        SHOOT
        JMP    top
idle:   WAIT
        JMP    top
""".trimIndent()

    val TURTLE = """
        ; TURTLE — shield up; only fires when an enemy is adjacent
loop:   IN     DIST
        JZ     idle
        CMP    #1            ; adjacent?
        JNE    hold
        SHOOT
        JMP    loop
hold:   SHIELD
        JMP    loop
idle:   WAIT
        JMP    loop
""".trimIndent()

    val SNAKE = """
        ; SNAKE — strafe a few steps, flip 180°, fire on the flip
        MOV    X, #3           ; steps before flipping
loop:   IN     DIST
        JZ     idle
        MOVE
        DEC    X
        JZ     flip
        JMP    loop
flip:   TURN   R
        TURN   R
        SHOOT
        MOV    X, #3
        JMP    loop
idle:   WAIT
        JMP    loop
""".trimIndent()

    val CHAOS = """
        ; CHAOS — pure noise: MOVE 50% · SHOOT 25% · TURN R 12.5% · SHIELD 12.5%
loop:   IN     RAND
        AND    #1
        JZ     b
        MOVE
        JMP    loop
b:      IN     RAND
        AND    #1
        JZ     c
        SHOOT
        JMP    loop
c:      IN     RAND
        AND    #1
        JZ     d
        TURN   R
        JMP    loop
d:      SHIELD
        JMP    loop
""".trimIndent()

    val WALKER = """
        ; WALKER — march straight; bursts a shot every few steps
        MOV    X, #3
loop:   MOVE
        DEC    X
        JZ     burst
        JMP    loop
burst:  SHOOT
        MOV    X, #3
        JMP    loop
""".trimIndent()

    data class PresetDef(val name: String, val glyph: Char, val color: Int, val x: Int, val y: Int, val firmware: String)

    fun all(): List<PresetDef> = listOf(
        PresetDef("HUNTER", 'H', RED, 10, 15, HUNTER),
        PresetDef("TURTLE", 'T', GREEN, 10, 4, TURTLE),
        PresetDef("SNAKE", 'S', CYAN, 4, 10, SNAKE),
        PresetDef("CHAOS", 'C', PURPLE, 16, 10, CHAOS),
        PresetDef("WALKER", 'W', ORANGE, 10, 10, WALKER),
    )
}
