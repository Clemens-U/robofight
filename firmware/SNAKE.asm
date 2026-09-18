; ============================================================
;  ROBOFIGHT firmware — SNAKE
;  Strafe a few steps, flip 180 degrees, fire on the flip.
; ============================================================
      MOV    X, #3           ; steps before flipping
loop:  IN     DIST
      JZ     idle
      MOVE
      DEC    X
      JZ     flip
      JMP    loop
flip:  TURN   R
      TURN   R
      SHOOT
      MOV    X, #3
      JMP    loop
idle:  WAIT
      JMP    loop
