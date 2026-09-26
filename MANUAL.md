# OPCODE ARENA — Manual (v1, first edition)

> **The game in one line:** you are a combat-programmer. You write *firmware* in a
> tiny assembly language for a robot on a 20×20 grid, then pit it against a preset
> bot (or your second bot) and watch your code fight, one instruction per tick.
> The skill is **programming, not reflexes** — read the sensor ports, branch, fire,
> manage heat. Everything renders in chunky 8-bit ASCII.

This manual covers the **native Android app** (the `app/` module) and the **RF-8
language** it runs.

---

## 1. The two modes

```
+--------------------------------------+
|  OPCODE ARENA█           RF-8 / ONLINE|
+--------------------------------------+
|       [ SHELL ]     [ RUN ]           |  <- mode switch
+--------------------------------------+
|  SHELL                                |  RUN
|  DIR | HELP | EDIT                    |  20×20 arena
|                                      |  A HUNTER >
|  RF:\> EDIT MYBOT                    |  B TURTLE >
|  ; edit RF-8 firmware                |  RUN STEP RESET
+--------------------------------------+
|  SYS READY / TICK + BOT TELEMETRY     |  <- status line
+--------------------------------------+
```

The app has two deliberately separate modes with the same green-screen terminal
design:

- **SHELL** is the firmware workspace. Manage saved programs in the command terminal,
  then open one in the full-height editor. The command line and editor remain above
  the Android keyboard while you type. The block cursor in the title and the native
  text cursor blink like an old terminal.
- **RUN** dedicates the screen to the arena, bot selection, fight controls, and live
  telemetry. Entering RUN dismisses the keyboard. A running fight pauses when you
  return to SHELL and resumes when you come back.

---

## 2. Quick start (first fight in 30 seconds)

1. **Open the app.** It starts in the **shell** with `MYBOT` already loaded.
2. Tap `MYBOT` in the `DIR` listing (or **EDIT**) to open it in the editor. Edit the
   firmware, then tap **SAVE**. You can also leave the starter code unchanged.
3. Tap **FIGHT >** or the top **[ RUN ]** mode tab. In RUN mode, tap the A or B bot
   selector until one slot is `MYBOT` (otherwise it is preset-vs-preset).
4. Tap **RUN**. The arena advances continuously; the bottom telemetry updates. When a bot
   hits 0 HP (or 200 ticks pass), the winner is shown.
5. Tap **`STEP`** to advance one tick at a time, or **`RESET`** to clear.

> **The *file* is what runs, not the editor.** On `RUN`, each slot reads its firmware
> from the file store — for `MYBOT` that's the saved `MYBOT` file. Always **SAVE** after
> editing, otherwise the fight uses the last saved version.

---

## 3. The file shell

A small terminal for managing firmware. Type a command in the bottom box, tap **ENTER**;
or use the quick buttons **DIR** / **HELP**.

| Command | Aliases | Effect |
|---------|---------|--------|
| `DIR`   | `D`, `LS` | List all saved firmware (name, bytes, modified). **Tap a name to open it** in the editor. |
| `EDIT <name>` | `E`, `OPEN` | Load `MYBOT`, `BOT1`, … into the editor. |
| `NEW <name>`  | `N` | Create + open a new file, pre-filled with the RF-8 cheat sheet as comments. Auto-named `BOT1`, `BOT2`, … |
| `DEL <name>`  | `RM` | Delete a file. |
| `RUN`   | `FIGHT` | Switch to RUN mode and start a fight. |
| `CLEAR` | `CLS` | Clear the terminal output. |
| `HELP` | `?` | Show this command list. |

Notes:
- Names are **case-insensitive** (`hunter` = `HUNTER`).
- Opening a different file while you have unsaved edits **auto-saves** the current
  file first ("saved MYBOT (unsaved changes kept)") — you won't lose work.
- Files live in the app's local SQLite store; they persist across launches.

### Editor buttons
| Button | Effect |
|--------|--------|
| **`SAVE`**  | Save the editor text to the current file. |
| **`NEW`**   | Create a new auto-named file (cheat-sheet template) and open it. |
| **`TERM`**  | Go back to the file terminal. |
| **`FIGHT >`** | Switch to RUN mode and start the fight. |

---

## 4. The bots you can fight

Slots **A** and **B** each cycle through the whole roster (tap to advance): the five
engine presets first, then **one slot per firmware file** in the store (`MYBOT`,
`HUNTER.asm`, …, `BOT1`, …).

| Bot | Glyph | Color | Personality |
|-----|-------|-------|-------------|
| **HUNTER** | `H` | red | Faces nearest enemy, advances, fires. Aggressive. |
| **TURTLE** | `T` | green | Shields up; only fires when you're right on it. Defensive. |
| **SNAKE** | `S` | cyan | Strafes in short bursts, fires on the flip. |
| **CHAOS** | `C` | pink | Random move/shoot/turn/shield each tick. Unpredictable. |
| **WALKER** | `W` | orange | Marches straight, bursts a shot every few steps. Baseline. |
| **MYBOT** | `M` | yellow | **Your code** — the saved `MYBOT` file. |
| `*.asm` files | first letter | stable per name | Any firmware you created in the shell. |

- The presets are **also stored as files** (`HUNTER.asm` … `WALKER.asm`) on first
  launch — open one, edit, `SAVE`, and your version is what the arena uses from then on.
- Set **A=MYBOT, B=HUNTER** for the classic "my bot vs a preset" match, or pick any
  two files for a custom duel. Deleting a file removes its slot automatically.

---

## 5. Reading the arena

Each cell is a glyph; a bot is drawn as **its letter + a facing arrow**:

| Glyph | Meaning |
|-------|---------|
| `H>`, `T<`, `S^`, `Cv` | a bot; the arrow is its facing (E, W, N, S) |
| `*` | a live projectile, moving 1 cell/tick along the shooter's facing |
| `.` | empty ground |

Bots are colored per the table above. When a bot dies it disappears.

---

## 6. How a fight works (combat rules)

- **Grid:** 20×20, coordinates 0–19. A bot occupies one cell and faces one of four
  ways (N/E/S/W).
- **One instruction per tick:** each tick, every *alive* bot executes exactly one
  instruction (deterministic order), then the world resolves — projectiles advance,
  hit, or hit a wall.
- **Health:** every bot starts at **100 HP**. A hit does **10 damage** → 10 clean
  hits to KO.
- **Heat (anti-spam):** heat is **cumulative**, measured 0–100. `SHOOT` and
  `SHIELD` each add **+10** — 10 of either op maxes you out. Heat cools by
  **2 per tick**, but only on ticks where you didn't shoot or shield (at
  100 ms/tick that's 100 % → 0 % in 5 s). When an action would push heat past
  the max it is **locked out** — with +10/op, both work again exactly at
  **90**. Sustained full fire settles at 10 shots / 15 ticks.
- **Overheat damage:** heat above **80** (80%) burns **2 HP per second**.
  The damage accumulates fractionally (0.2 HP/tick at the engine's 10 ticks/sec)
  and is applied as whole HP, so staying hot is lethal — shut down, idle, and
  let heat cool below 80 to stop taking damage.
- **Shield:** `SHIELD` protects a bot from one hit *that tick* and **adds heat
  too**. It resets every tick, so it only matters the instant you raise it.
  When heat is too high to shield, the shield **collapses** — no protection —
  and `SHOOT` is locked out in the same way: the robot simply waits until the
  heat has dropped and it can act again.
- **Winning:** first to KO the opponent, or **highest HP after 200 ticks** (tie = draw).
- **Projectiles** live up to 40 ticks; they vanish off-grid or on a hit.

### The stats bar
`T:12  HUNTER: HP80 SH3 HIT2 D20   TURTLE: HP90 SH5 HIT1 D10`
→ tick, then per bot: **HP** left, **SH**ots fired, **HIT**s landed, **D**amage dealt.

---

## 7. The RF-8 language

A tiny 8-bit machine: **3 registers** (`A`, `X`, `Y`), a **stack**, **memory-mapped
I/O ports**, and one instruction per tick. Every line is `[label:]  MNEMONIC  [operand]`;
`;` starts a comment. Numbers are 0–255 unless noted.

### Registers & memory
| Name | Role |
|------|------|
| `A` | accumulator — most results land here |
| `X` | index / pointer / scratch |
| `Y` | scratch |
| `(X)` | indirect — reads memory at address `X` |
| `[n]` | absolute memory address (`MOV` data 0–255; ALU/jumps 0–1023) |

### I/O ports (read with `IN`, write with `OUT`)
| Port | R/W | Meaning |
|------|-----|---------|
| `HP` | R | your current HP |
| `DIR` | R/W | your facing: **0=W 1=N 2=E 3=S** |
| `RX` / `RY` | R | your grid column / row |
| `DIST` | R | distance to nearest enemy (**0 = none**) |
| `ANGLE` | R | **how many steps clockwise** to face the nearest enemy (`0` = already facing, `255` = no enemy) |
| `ENEMY_HP` | R | nearest enemy's HP |
| `HEAT` | R | your current heat (0–100; +10 per SHOOT/SHIELD, −2 per idle tick; at the max = action locked out until 90; **above 80 = 2 HP/sec self-damage**) |
| `SHIELD` | R | 1 if shielded this tick, else 0 |
| `RAND` | R | random byte 0–255 |
| `AHEAD` | R | 1 if a wall or enemy blocks the cell directly in front of you, 0 if clear |
| `FIRE` | W | (write 1 to shoot — `SHOOT` is the alias) |

> **`ANGLE` is the key sensor.** It reads back a *delta* (0–3): `0` means you're
> already facing the nearest enemy, `N` means turn right `N` times to face it,
> and `255` means there's no enemy. That's how HUNTER aims: `IN ANGLE` →
> `JZ` already facing → else `TURN R` / `DEC` / loop until it reads `0`.

### Instructions (cheat sheet)
| Group | Mnemonics |
|-------|-----------|
| **Robot** | `SHOOT` · `MOVE` · `TURN L` / `TURN R` / `TURN #n` · `SHIELD` · `WAIT` · `NOP` |
| **Sensors** | `IN <port>` (loads port into `A`) · `OUT <port>, A` (writes `A` to a port) |
| **Move data** | `MOV A,#n` `MOV A,X` `MOV A,Y` `MOV A,(X)` `MOV A,[n]` · `MOV X,Y/#n` `MOV Y,X/#n` · `MOV [n],A` `MOV (X),A` · `XCH A,X/Y/(X)` |
| **Math** (`A=A op …`) | `ADD` `SUB` `MUL` `DIV` `AND` `OR` `XOR` `CMP` — operand `#n` `X` `Y` `(X)` `[n]` |
| **Increment** | `INC A/X/Y` · `DEC A/X/Y` · `TST A/X/Y` · `NEG A` · `NOT A` |
| **Branch** | `JMP label` · `JZ` `JNZ` · `JC` `JNC` · `JN` `JNN` · `JG` `JGE` `JL` `JLE` · `JE` `JNE` |
| **Subroutines** | `CALL label` · `RET` |
| **Stack** | `PUSH #n/A/X/Y` · `POP A/X/Y` |

Branch targets must be **within ±127 instructions** of the branch. `JZ`/`JE` fire when
the last result was zero (e.g. `IN DIST` → `JZ idle` means "no enemy, go idle").

---

## 8. A worked example — HUNTER

```
; HUNTER — face the nearest enemy, step in, fire
top:    IN     DIST            ; nearest enemy distance -> A
        JZ     idle            ; none? go idle
        IN     ANGLE           ; how many cw turns to face it
loop:   JZ     face            ; already facing?
        TURN   R               ; one step clockwise
        DEC    A
        JMP    loop
face:   MOVE                  ; step toward the enemy
        SHOOT                 ; fire (if heat allows)
        JMP    top
idle:   WAIT
        JMP    top
```

Read it as: *every tick — if there's an enemy, rotate to face it, walk one cell, shoot.*
That's the whole "nerd loop": sense (`IN`), decide (`JZ`/`CMP`), act (`MOVE`/`SHOOT`).

**Try it yourself:** open `MYBOT`, keep the strafe skeleton, and add a `CMP`/`SHOOT`
when `DIST` is small — or make it shield before shooting. `STEP` through to see each
instruction the bot executes (the last op shows in the engine trace).

---

## 9. Assembler errors

`RUN` assembles before it plays. On a bad line the fight **won't start** and the stats
bar shows the first error, e.g.:

```
line 5: unknown opcode 'SHOOTX'
line 9: undefined label or address 'idle'
line 3: immediate 300 out of range 0..255
```

Fix the highlighted line and `RUN` again.

---

## 10. Tips & troubleshooting

- **Nothing happens on RUN?** You probably have A and B on two *presets* — set one slot
  to `MYBOT` so your code is in the fight.
- **`no file open — press NEW first`?** You hit `SAVE` with no file selected; run `NEW`
  (or `EDIT MYBOT`) first.
- **My bot never fires.** Check `HEAT` — if `> 0`, `SHOOT` is ignored. Space your shots.
- **My bot walks into a wall / a bot.** `MOVE` simply doesn't move if the target cell is
  off-grid or occupied — it's not an error, just a no-op that tick.
- **It draws after 200 ticks** → highest HP wins; equal HP → `DRAW`.
- Want a fresh bot? `NEW BOT2`, code it, `SAVE`, then `EDIT BOT2` any time to reload it.

---

*Built on the pure-Kotlin `engine/` (RF-8 ISA + VM + world) — see `DESIGN.md` for the
locked spec and `firmware/*.asm` for the full preset sources.*
