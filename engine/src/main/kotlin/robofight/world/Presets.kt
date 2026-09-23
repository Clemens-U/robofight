package robofight.world

import robofight.world.Palette.RED
import robofight.world.Palette.GREEN
import robofight.world.Palette.CYAN
import robofight.world.Palette.PURPLE
import robofight.world.Palette.ORANGE

/** Canned firmware for the 5 starter bots. */
object Presets {
    val HUNTER = """
        ; HUNTER — face the nearest enemy,
        ; step in, fire; back off at a wall
top:    IN     DIST            ; nearest enemy
        JZ     idle            ; none? idle
        IN     ANGLE           ; turns to face
loop:   JZ     face
        TURN   R               ; one step cw
        DEC    A
        JMP    loop
face:   IN     AHEAD           ; wall/enemy ahead?
        JNZ    retreat
        MOVE
        SHOOT
        JMP    top
retreat:TURN   R
        TURN   R               ; pivot off the wall
        JMP    top
idle:   WAIT
        JMP    top
""".trimIndent()

    val TURTLE = """
        ; TURTLE — shield up; fires only
        ; when an enemy is adjacent
loop:   IN     DIST
        JZ     idle
        CMP    #1              ; adjacent?
        JNE    hold
        SHOOT
        JMP    loop
hold:   SHIELD
        JMP    loop
idle:   WAIT
        JMP    loop
""".trimIndent()

    val SNAKE = """
        ; SNAKE — strafe 3 steps, flip,
        ; fire on the flip; flip early at a wall
        MOV    X, #3
loop:   IN     DIST
        JZ     idle
        IN     AHEAD           ; wall/enemy ahead?
        JNZ    flip
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
        ; CHAOS — random: move/shoot/
        ; turn/shield
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
        ; WALKER — march straight,
        ; burst a shot every 3 steps;
        ; turn off the wall
        MOV    X, #3
loop:   IN     AHEAD           ; wall/enemy ahead?
        JNZ    turnaway
        MOVE
        DEC    X
        JZ     burst
        JMP    loop
turnaway:TURN   R
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
