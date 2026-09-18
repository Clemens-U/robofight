; ============================================================
;  ROBOFIGHT firmware — WALKER
;  March straight; bursts a shot every few steps.
; ============================================================
      MOV    X, #3
loop:  MOVE
      DEC    X
      JZ     burst
      JMP    loop
burst: SHOOT
      MOV    X, #3
      JMP    loop
