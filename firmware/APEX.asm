; ============================================================
;  OPCODE ARENA firmware — APEX
;
;  A general-purpose duelist built for unknown opponents:
;    * opens with a perpendicular dodge to leave the spawn line
;    * aims in constant time by adding ANGLE to DIR
;    * fires while closing so aligned targets cannot camp or flee
;    * at point-blank range, fires and immediately sidesteps
;
;  Literal victory against every possible firmware is impossible:
;  an identical opponent can force a symmetric draw. APEX instead
;  optimizes for broad, adversarial performance without depending
;  on the behavior of any bundled preset.
; ============================================================

; Both robots initially face each other on the dominant axis. Step
; off that firing line before doing any slower sensing or shooting.
        TURN   R
        MOVE
        TURN   L

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
