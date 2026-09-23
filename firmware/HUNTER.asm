; ============================================================
;  ROBOFIGHT firmware — HUNTER
;  Seek the nearest enemy, turn to face it, step in and fire.
;  Uses AHEAD to sense the wall (or an enemy) ahead and back off
;  instead of marching into it.
; ============================================================
top:   IN     DIST            ; nearest enemy distance -> A
      JZ     idle            ; nothing there? idle
      IN     ANGLE           ; A = steps clockwise until we face it
loop:  JZ     face            ; already facing?
      TURN   R               ; rotate one step clockwise
      DEC    A
      JMP    loop
face:  IN     AHEAD           ; wall / enemy straight ahead?
      JNZ    retreat         ; blocked -> back off
      MOVE                  ; step in
      SHOOT                 ; fire
      JMP    top
retreat: TURN   R
      TURN   R               ; pivot away from the wall
      JMP    top
idle:  WAIT
      JMP    top
