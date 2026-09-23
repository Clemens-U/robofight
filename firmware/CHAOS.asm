; ============================================================
;  OPCODE ARENA firmware — CHAOS
;  Pure noise: MOVE 50% | SHOOT 25% | TURN R 12.5% | SHIELD 12.5%
; ============================================================
loop:  IN     RAND
      AND    #1
      JZ     b
      MOVE
      JMP    loop
b:     IN     RAND
      AND    #1
      JZ     c
      SHOOT
      JMP    loop
c:     IN     RAND
      AND    #1
      JZ     d
      TURN   R
      JMP    loop
d:     SHIELD
      JMP    loop
