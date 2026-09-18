; ============================================================
;  ROBOFIGHT firmware — TURTLE
;  Shield up at all times; only fires when an enemy is adjacent.
; ============================================================
loop:  IN     DIST
      JZ     idle
      CMP    #1            ; adjacent?
      JNE    hold
      SHOOT
      JMP    loop
hold:  SHIELD
      JMP    loop
idle:  WAIT
      JMP    loop
