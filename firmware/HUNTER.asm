; ============================================================
;  ROBOFIGHT firmware — HUNTER
;  Seek the nearest enemy, turn to face it, step in, fire.
; ============================================================
top:   IN     DIST            ; nearest enemy distance -> A
      JZ     idle            ; nothing there? idle
      IN     ANGLE           ; A = steps clockwise until we face it
loop:  JZ     face            ; already facing?
      TURN   R               ; rotate one step clockwise
      DEC    A
      JMP    loop
face:  MOVE                  ; step in
      SHOOT                 ; fire
      JMP    top
idle:  WAIT
      JMP    top
