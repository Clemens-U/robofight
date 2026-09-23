; ROBOFIGHT MYBOT — your bot. Edit it,
; then hit RUN.
; (RF-8 cheat sheet at the bottom.)
;
; AHEAD is the wall sensor: IN AHEAD -> A = 1 when a wall or an
; enemy blocks the cell directly in front of you, 0 when it's
; clear. Check it before every MOVE so you steer around walls
; instead of stalling against them.

      MOV    X, #3           ; strafe length
loop:  IN     DIST           ; 0 = no enemy in range
      JZ     idle
      IN     AHEAD           ; wall / enemy straight ahead?
      JNZ    turnaround      ; blocked -> flip now
      MOVE
      DEC    X
      JZ     turnaround
      JMP    loop
turnaround:
      TURN   R
      TURN   R               ; 180 degrees, off the wall
      SHOOT
      MOV    X, #3
      JMP    loop
idle:  WAIT
      JMP    loop

; ================================
; RF-8 cheat sheet
; ================================
; MOV  A,#n / A,X / A,Y / A,(X) /
;      A,[n]                    load
; MOV  X,Y,#n                   regs
; MOV  [n],A / (X),A            memory
; ADD  #n|X|Y|(X)|[n]  A=A+op
; SUB MUL DIV AND OR XOR CMP
; INC|DEC|TST  A|X|Y
; JZ JNZ JC JNC JN JNN JG JGE
; JL JLE JE JNE   branch to label
; PUSH|POP  #n|A|X|Y
; CALL label   RET
; SHOOT MOVE TURN L|R|#n
; SHIELD WAIT
; IN   DIST|ANGLE|HP|ENEMY_HP|
;      RAND|X|Y|DIR|HEAT|AHEAD
; OUT  DIR, A
; Ports:
;   DIST   dist to enemy (0=none)
;   ANGLE  cw turns to face it
;   AHEAD  1 = wall/enemy blocks the cell ahead, 0 = clear
;   RAND   random byte 0..255
; ================================
