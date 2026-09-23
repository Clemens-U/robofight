; ============================================================
;  ROBOFIGHT firmware — WALKER
;  March straight and burst a shot every few steps. When AHEAD
;  reports the wall (or an enemy) straight ahead, turn to keep
;  moving instead of stalling against it.
; ============================================================
      MOV    X, #3
loop:  IN     AHEAD           ; wall / enemy straight ahead?
      JNZ     turnaway
      MOVE
      DEC    X
      JZ     burst
      JMP    loop
turnaway: TURN   R           ; off the wall
      JMP    loop
burst:  SHOOT
      MOV    X, #3
      JMP    loop
