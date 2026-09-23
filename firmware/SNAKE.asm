; ============================================================
;  OPCODE ARENA firmware — SNAKE
;  Strafe a few steps, flip 180 degrees, fire on the flip.
;  AHEAD senses the wall ahead: when it's blocked, flip now
;  (turning also away from a wall) rather than pushing into it.
; ============================================================
      MOV    X, #3           ; steps before flipping
loop:  IN     DIST
      JZ     idle
      IN     AHEAD           ; wall / enemy straight ahead?
      JNZ    flip            ; blocked -> flip now
      MOVE
      DEC    X
      JZ     flip
      JMP    loop
flip:  TURN   R
      TURN   R               ; 180 degrees, off the wall
      SHOOT
      MOV    X, #3
      JMP    loop
idle:  WAIT
      JMP    loop
