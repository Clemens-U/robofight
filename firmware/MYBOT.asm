; ============================================================
;  ROBOFIGHT firmware — MYBOT (your bot, edit me!)
;
;  RF-8 cheat sheet
;  ----------------
;  MOV  A,#n / A,X / A,Y / A,(X) / A,[n]      load
;  MOV  X,Y,#n ...                             store to registers
;  MOV  [n],A / (X),A                          store to memory
;  ADD  #n|X|Y|(X)|[n]        (A = A + operand)
;  SUB  MUL  DIV  AND  OR  XOR  CMP           same operand forms
;  INC  A|X|Y      DEC  A|X|Y      TST  A|X|Y
;  JZ/JNZ/JC/JNC/JN/JNN/JG/JGE/JL/JLE/JE/JNE   branch to label
;  PUSH #n|A|X|Y   POP  A|X|Y   CALL label   RET
;  SHOOT  MOVE  TURN L|R|#n  SHIELD  WAIT
;  IN   DIST|ANGLE|HP|ENEMY_HP|RAND|X|Y|DIR|HEAT   (read port -> A)
;  OUT  DIR, A    (write A to DIR)
;
;  Ports you'll use most:
;    DIST     distance to nearest enemy (0 = none)
;    ANGLE    steps clockwise needed to face nearest enemy
;    RAND     a random byte (0..255)
; ============================================================

; TODO: make it do something scary.

      MOV    X, #3           ; strafe length
loop:  IN     DIST
      JZ     idle
      MOVE
      DEC    X
      JZ     turnaround
      JMP    loop
turnaround:
      TURN   R
      TURN   R
      SHOOT
      MOV    X, #3
      JMP    loop
idle:  WAIT
      JMP    loop
