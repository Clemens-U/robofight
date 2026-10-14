; ============================================================
;  OPCODE ARENA firmware — HAREHUNTER
;
;  A counter-duelist tuned for ANGSTHASE:
;    * finds the safe perpendicular dodge direction at spawn
;    * aims in constant time by adding ANGLE to DIR
;    * fires while closing so aligned targets cannot camp or flee
;    * at point-blank range, fires and immediately sidesteps
; ============================================================

; Both robots initially face each other on the dominant axis. Test
; a perpendicular step: if DIST shrank, that step went toward the
; enemy's firing row/column, so cross back and dodge the other way.
        IN     DIST
        MOV    Y, A
        TURN   R
        MOVE
        IN     DIST
        CMP    Y
        JGE    loop
        TURN   R
        TURN   R
        MOVE
        MOVE

; ANGLE is a clockwise delta, while DIR is the absolute direction.
; Adding them and writing the low two bits through OUT DIR turns to
; the current enemy direction in a fixed five instructions.
loop:   IN     DIR
        MOV    X, A
        IN     ANGLE
        ADD    X
        OUT    DIR, A

        IN     DIST
        JZ     idle
        CMP    #1
        JE     point

; Keep pressure on distant and evasive opponents. The sensing,
; movement, and loop-control ticks cool enough heat between shots
; to avoid a self-destructive locked-gun loop.
        SHOOT
        IN     AHEAD
        JNZ    side
        MOVE
        JMP    loop

; An adjacent shot hits immediately. Rotate and move afterward so
; return fire aimed at the old square is likely to miss.
point:  SHOOT
side:   TURN   R
        MOVE
        JMP    loop

idle:   WAIT
        JMP    loop
