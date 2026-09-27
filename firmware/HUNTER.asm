; ============================================================
;  ROBOFIGHT firmware — HUNTER
;  Seek the nearest enemy, turn to face it, step in and fire.
;  Uses AHEAD to sense the wall (or an enemy) ahead and back off
;  instead of marching into it. When an enemy is adjacent (DIST=1)
;  the HUNTER can't MOVE into its cell, so instead of retreating it
;  fires at point-blank range. Only retreats when the blocked cell
;  is a wall (DIST > 1).
; ============================================================
top:   IN     DIST            ; nearest enemy distance -> A
      JZ     idle            ; nothing there? idle
      MOV    X, A            ; save DIST (IN AHEAD overwrites A)
      IN     ANGLE           ; A = steps clockwise until we face it
loop:  JZ     face            ; already facing?
      TURN   R               ; rotate one step clockwise
      DEC    A
      JMP    loop
face:  IN     AHEAD           ; wall / enemy straight ahead?
      JZ     advance         ; clear -> step in
      MOV    A, X            ; restore DIST
      CMP    #1              ; enemy adjacent (DIST=1)?
      JNE    retreat         ; no -> wall -> back off
      SHOOT                  ; yes -> fire at point-blank range
      JMP    top
advance: MOVE                ; step in
      SHOOT                 ; fire
      JMP    top
retreat: TURN   R
      TURN   R               ; pivot away from the wall
      JMP    top
idle:  WAIT
      JMP    top
