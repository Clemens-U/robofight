# OPCODE ARENA — Manual (v1, first edition)

> **The game in one line:** you are a combat-programmer. You write *firmware* in a
> tiny assembly language for a robot on a 20×20 grid, then pit it against a preset
> bot (or your second bot) and watch your code fight, tick by tick.
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
|                                      |  [A code window][B code window]
|  RF:\> EDIT MYBOT                    |  A HUNTER >        B TURTLE >
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
- **RUN** dedicates the screen to the arena, the **execution monitor** (live code
  view of both bots, §6.1), bot selection, fight controls, and live telemetry.
  Entering RUN dismisses the keyboard. A running fight pauses when you return to
  SHELL and resumes when you come back.

---

## 2. Quick start (first fight in 30 seconds)

1. **Open the app.** It starts in the **shell** with `MYBOT` already loaded.
2. Tap `MYBOT` in the `DIR` listing (or **EDIT**) to open it in the editor. Edit the
   firmware, then tap **SAVE**. You can also leave the starter code unchanged.
3. Tap **FIGHT >** or the top **[ RUN ]** mode tab. In RUN mode, tap the A or B bot
   selector until one slot is `MYBOT` (otherwise it is preset-vs-preset).
4. Tap **RUN**. The arena advances continuously; the per-bot readouts update. When a bot
   hits 0 HP the winner is shown (fights run to a KO — see §6).
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
| `EXPORT` / `EXPORT ALL` | `FULL` | ZIP-archive **all** firmware. The standard folder picker opens; the `robofight-export-<date>.zip` is written into the folder you pick (any folder on the device — no permissions needed). On devices without a documents UI (plain AOSP emulators) it falls back to the app's own `Android/data/robofight.android/files/` folder and says so in the terminal. |
| `EXPORT <name>` | — | Same picker, but writes **one** firmware file (e.g. `EXPORT HUNTER.asm`). |
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
- **Instruction cost (ticks):** the engine advances in *ticks*. Each tick, every
  *alive* bot steps its program, but an instruction is not always one tick — the
  robot's physical actions are slower than bookkeeping: `SHOOT` **3 ticks**, `SHIELD`
  **3 ticks**, `MOVE` **2 ticks**, and **every other instruction 1 tick**. While a
  multi-tick op is in flight the bot executes nothing else — it *holds* on that line
  for the whole duration (no decode, no faults, no further steps), and its world
  effect (the projectile, the step, the shield) fires on the op's **final tick**.
  After every bot has stepped, the world resolves — projectiles advance one cell,
  hit, or die on a wall. (A `SHIELD` still covers exactly one world-resolve; a
  `SHOOT`'s projectile spawns on the shot's final tick.)
- **Health:** every bot starts at **100 HP**. A hit does **10 damage** → 10 clean
  hits to KO.
- **Heat (anti-spam):** heat is **cumulative**, measured 0–100. `SHOOT` and
  `SHIELD` each add **+20** — 5 of either op maxes you out. Heat cools by
  **2 per tick**, but only on ticks where you didn't shoot or shield (at
  100 ms/tick that's 100 % → 0 % in 5 s). When an action would push heat past
  the max it is **locked out** — with +20/op, both work again exactly at
  **80**.
- **Overheat damage:** heat above **80** (80%) burns **2 HP per second**.
  The damage accumulates fractionally (0.2 HP/tick at the engine's 10 ticks/sec)
  and is applied as whole HP. Safe ticks stop adding damage without erasing
  earlier fractional exposure, so repeated short spikes cannot bypass the
  penalty. Staying hot is lethal — shut down, idle, and let heat cool below 80.
- **Shield:** `SHIELD` protects a bot from one hit *that tick* and **adds heat
  too**. It resets every tick, so it only matters the instant you raise it.
  When heat is too high to shield, the shield **collapses** — no protection —
  and `SHOOT` is locked out in the same way: the robot simply waits until the
  heat has dropped and it can act again.
- **Winning:** first bot to KO the other (HP → 0) wins. Fights run to a KO —
  there is no round-length tiebreak. The engine has a 1,000,000-tick safety cap
  (a runaway-loop guard, not a game rule); if it is ever reached, the higher-HP
  bot is declared the winner, and a simultaneous double-KO is a `DRAW`.
- **Projectiles** live up to 40 ticks; they vanish off-grid or on a hit.

### The stats bar
Each combatant has its own readout block under its slot button:

```
HP 100   SH  0
HIT  0  DMG   0
HEAT  40 ####------
```

→ **HP** left · **SH**ots fired · **HIT**s landed · **DMG** damage dealt ·
**HEAT** (0–100) with a 10-cell bar — a cell is a bright `#` when heat is
≥ (cell+1)·10, so 40 heat fills exactly the first four cells. Before a fight and
after a KO the block dims to `HP ---` placeholders with an all-dim bar. The status
line above the arena reads `T+ 42  // EXECUTING`, and swaps the state for
`// WINNER: HUNTER` (or `// DRAW`) once the fight ends.

### 6.1 The execution monitor

Directly under the arena, between it and the A/B slot buttons, sits the **execution
monitor**: two side-by-side windows, one per combatant, showing the bot's *original
firmware source* with a marker on the line being executed. It updates every tick.

```
+----------------------------------+----------------------------------+
| A ▸ HUNTER                       | B ▸ TURTLE                       |
|       SHOOT                      |       SHOOT                      |
| > advance:MOVE                   | > hold:  SHIELD                  |
|       SHOOT                      |       JMP loop                   |
| retreat:TURN R                   | idle:   WAIT                     |
|       TURN R                     |       JMP loop                   |
|                                  |                                  |
| A00 X14 Y00 SP0BF PC133 Z        | A10 X00 Y00 SP0BF PC115          |
+----------------------------------+----------------------------------+
```

- **Window:** 6 source lines — 2 before the current line, the current line, and
  3 ahead. The window slides as the program runs; near the end it clamps at the
  last line (a bot that runs off the end of its program — the memory tail is NOPs —
  keeps the marker on the last line with an `…END` tag).
- **Current opcode:** the executing line is bright and prefixed with `>`; lines
  already passed are dimmed. Multi-tick ops (`SHOOT`, `MOVE`, `SHIELD`) hold the
  marker on their line for the whole operation, and a jump/branch moves it to the
  target's line.
- **Register line** (pinned to the pane's bottom): `A## X## Y##` (8-bit hex),
  `SP### PC###` (stack pointer / program counter, 10-bit hex), and the lit flag
  letters — `C` (carry), `Z` (zero), `N` (negative). Watch `PC` walk as you
  `STEP`; it's the fastest way to see where your code actually is.
- A KO'd bot's pane switches to `KO — destroyed`.
- Lines are left-aligned and clipped at the pane's right edge — long comments are
  cut, never wrapped.

---

## 7. The RF-8 language

A tiny 8-bit machine: **3 registers** (`A`, `X`, `Y`), a **stack**, **memory-mapped
I/O ports**, and a small instruction set. Every instruction is exactly **3 bytes**
(`opcode` + a 16-bit operand field, little-endian), which is why labels are easy to
place — the address of instruction *i* is `$0100 + i·3`.

A source line is `[label:]  MNEMONIC  [operand]`; `;` starts a comment. Numbers are
0–255 unless noted. The machine clock is the *tick*: an instruction occupies as many
ticks as its **cost** (most are 1; `SHOOT`/`SHIELD` are 3, `MOVE` is 2 — see §6).

### 7.1 Registers & flags
| Reg | Size | Role |
|-----|------|------|
| `A` | 8-bit | accumulator — most ALU results land here |
| `X` | 8-bit | index / pointer / scratch |
| `Y` | 8-bit | scratch |
| `SP` | 10-bit | stack pointer, starts `$00BF`, grows **down** |
| `PC` | 10-bit | program counter, starts `$0100` |
| flags | — | `C` (carry), `Z` (zero), `N` (negative) — the register line shows all three |

`Z` is set when a result is 0; `N` when its high bit (bit 7) is set (negative in
two's complement); these are set by the ALU, `INC`/`DEC`/`TST`, `CMP`, and `POP A`.
`CMP` sets the flags on `A − B` *without* changing `A`. (The `C` carry bit is defined
but not yet set by the ALU, so `JC`/`JNC` read as "never / always".)

### 7.2 Memory map (1 KB, `$0000–$03FF`)
| Range | What |
|-------|------|
| `$0000–$007F` | data RAM (128 bytes) — `MOV A,[n]` / `MOV [n],A` use an 8-bit `n` here |
| `$0080–$00BF` | stack (grows down from `$00BF`) |
| `$00C0–$00FF` | I/O ports (routed through the robot / world) |
| `$0100–$03FF` | program (up to 256 instructions) |

Operand forms, wherever they appear:
- `#n` — 8-bit immediate, 0–255
- `A` `X` `Y` — registers
- `(X)` — **indirect**: memory at address `X` (data space only, 0–255)
- `[n]` / `label` — absolute address. For `MOV A,[n]` / `MOV [n],A` this is 8-bit
  (0–255, data RAM); for ALU `…,[n]` and `JMP`/`CALL` it's 10-bit (0–1023, program space)

### 7.3 I/O ports
Read with `IN <port>` (result → `A`), write with `OUT <port>, A` (writes `A`). Only
`DIR` is writable (it sets your facing directly); the rest are sensors.

| Port | # | R/W | Meaning |
|------|---|-----|---------|
| `HP` | 0 | R | your current HP (0–100) |
| `DIR` | 1 | R/W | your facing: **0=W 1=N 2=E 3=S**. Writing it sets your facing directly. |
| `RX` | 2 | R | your grid column (0–19) |
| `RY` | 3 | R | your grid row (0–19) |
| `DIST` | 4 | R | Manhattan distance to nearest enemy (**0 = none in range**) |
| `ANGLE` | 5 | R | **clockwise steps** to face nearest enemy (`0` = already facing, `255` = none) |
| `FIRE` | 6 | — | reads back 0; use `SHOOT` instead |
| `SHIELD` | 7 | R | 1 if you shielded this tick, else 0 |
| `HEAT` | 8 | R | your current heat (0–100; +20 per SHOOT/SHIELD, −2 per idle tick, **above 80 = 2 HP/sec self-damage**) |
| `ENEMY_HP` | 9 | R | nearest enemy's HP (0 if none) |
| `RAND` | 10 | R | random byte 0–255 (per-fight seeded) |
| `AHEAD` | 11 | R | 1 if a wall or enemy blocks the cell straight ahead, 0 if clear |

> **`ANGLE` is the aiming sensor.** It's a *delta* (0–3), not an absolute
> direction: `0` = already facing the nearest enemy, `n` = turn right `n` times
> to face it, `255` = no enemy. That's how HUNTER aims: `IN ANGLE` → `JZ` to skip
> if already facing → else `TURN R` / `DEC` / loop until it reads `0`.

### 7.4 Robot ops (what the body does)
These drive the robot and carry the real tick costs (§6). A heat op that is
**locked out** (heat too high) collapses to a **1-tick no-op** instead of animating
— so a bot spamming a jammed gun still cools and re-arms in ~10 ticks.

| Op | Cost | Effect |
|----|------|--------|
| `SHOOT` | 3 | spawn a projectile one cell ahead, along your facing (fires on the op's final tick). +20 heat. Locked out while `heat + 20 > 100` — it only fires again exactly at 80. |
| `MOVE` | 2 | step one cell toward your facing. A wall or an occupied cell = a silent no-op (not an error). |
| `TURN L` | 1 | rotate 90° counter-clockwise |
| `TURN R` | 1 | rotate 90° clockwise |
| `TURN #n` | 1 | rotate `n` steps clockwise, mod 4 — e.g. `TURN #2` = 180° |
| `SHIELD` | 3 | raise your shield for the next world-resolve (absorbs one hit). +20 heat. Collapses (no protection) when locked out. |
| `WAIT` | 1 | do nothing this tick (idle; heat cools) |
| `NOP` | 1 | do nothing — same as `WAIT`, for padding / timing |

### 7.5 Data movement (`MOV` / `XCH`)
| Mnemonic | Effect |
|----------|--------|
| `MOV A,#n` | load immediate → `A` (sets Z/N) |
| `MOV A,X` / `MOV A,Y` | register → `A` (sets Z/N) |
| `MOV A,(X)` | memory[X] → `A`, indirect (sets Z/N) |
| `MOV A,[n]` | memory[n] → `A`, `n` 0–255 (sets Z/N) |
| `MOV [n],A` | `A` → memory[n], `n` 0–255 |
| `MOV (X),A` | `A` → memory[X], indirect |
| `MOV X,A` / `MOV X,Y` / `MOV X,#n` | set `X` |
| `MOV Y,A` / `MOV Y,X` / `MOV Y,#n` | set `Y` |
| `XCH A,X` / `XCH A,Y` / `XCH A,(X)` | swap `A` with `X` / `Y` / memory[X] |

The `A`-destined loads set the Z/N flags; the `X`/`Y`/store forms and `XCH` do not.
Every `MOV`/`XCH`/`NOP` costs **1 tick**.

### 7.6 Arithmetic & logic
The `ADD`–`CMP` group takes one operand in any form `#n` / `X` / `Y` / `(X)` / `[n]`,
computes into `A`, and sets the Z/N flags.

| Mnemonic | Effect |
|----------|--------|
| `ADD` | `A = (A + op) & FF` |
| `SUB` | `A = (A − op) & FF` (wraps at 0) |
| `MUL` | `A = (A × op) & FF` (low byte) |
| `DIV` | `A = A ÷ op` (integer; `op = 0` yields 0, not a fault) |
| `AND` | `A = A & op` |
| `OR` | `A = A \| op` |
| `XOR` | `A = A ^ op` |
| `CMP` | sets flags from `A − op`, **`A` unchanged** (remembers `op` for `JG`/`JGE`/`JL`/`JLE`) |

Single-register / `A`-only ops:

| Mnemonic | Effect |
|----------|--------|
| `INC A` / `INC X` / `INC Y` | +1, wraps 255→0 (Z/N set on `A`) |
| `DEC A` / `DEC X` / `DEC Y` | −1, wraps 0→255 (Z/N set on `A`) |
| `NEG A` | `A = (−A) & FF` (Z/N set) |
| `NOT A` | `A = ~A & FF` (bitwise invert, Z/N set) |
| `TST A` / `TST X` / `TST Y` | write nothing — just set Z/N from that register |

All cost **1 tick**.

### 7.7 Branches
`JMP` / `CALL` take a **10-bit absolute** address (0–1023, a label or number). The
conditional jumps take a **relative** target — the label must be **within ±127
instructions** of the *next* instruction (the assembler enforces the range).

| Mnemonic | Jumps when |
|----------|-----------|
| `JMP label` | always (unconditional) |
| `JZ` / `JE` | Z set — last result was zero (`IN DIST` → `JZ idle` = "no enemy, go idle") |
| `JNZ` / `JNE` | Z clear |
| `JC` / `JNC` | carry set / clear *(carry is never set by the ALU yet, so JNC is "always")* |
| `JN` / `JNN` | N set (negative) / N clear (non-negative) |
| `JG` | signed `A > B` (after a `CMP`) |
| `JGE` | signed `A >= B` |
| `JL` | signed `A < B` |
| `JLE` | signed `A <= B` |

The signed comparisons (`JG`/`JGE`/`JL`/`JLE`) compare `A` against the operand stored
by the most recent `CMP`, read as **signed** bytes (so `JG` distinguishes 250 < 100
from 250 > 100). All branches cost **1 tick**.

### 7.8 Subroutines & stack
| Mnemonic | Effect |
|----------|--------|
| `CALL label` | push the return address (next instruction), jump to `label` |
| `RET` | pop the return address into `PC` |
| `PUSH #n` / `PUSH A` / `PUSH X` / `PUSH Y` | store the value at `SP`, then decrement `SP` |
| `POP A` / `POP X` / `POP Y` | increment `SP`, load `mem[SP]` into the register (`POP A` sets Z/N) |

The stack lives at `$080–$0BF` (64 bytes) and grows down; a `CALL` pushes two bytes
(low then high), so a few dozen `PUSH`es and roughly 30 sub-routine levels fit before
it would collide with the data RAM. All of these cost **1 tick**.

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
when `DIST` is small — or make it shield before shooting. In RUN mode, **`STEP`**
through and watch the execution monitor: the `>` marker walks down your source and
the register line shows `A`/`X`/`Y`, `SP`, `PC` and the flags after every instruction.

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
- **My bot never fires.** Check `HEAT` — if it's above **80**, `SHOOT` is locked out.
  Space your shots so heat cools back to 80 (2 per idle tick) between bursts.
- **My bot walks into a wall / a bot.** `MOVE` simply doesn't move if the target cell is
  off-grid or occupied — it's not an error, just a no-op that tick. Use the `AHEAD`
  sensor to steer around walls (see `WALKER.asm` / `SNAKE.asm`).
- **It self-destructs with no opponent nearby.** That's **overheat** — heat above 80
  burns 2 HP/sec. Don't hold the trigger; idle to let it cool.
- **A fight that never KOs** runs to the 1,000,000-tick safety cap, after which the
  higher-HP bot wins (equal → `DRAW`) — effectively endless, so a real match is decided
  by a KO.
- Want a fresh bot? `NEW BOT2`, code it, `SAVE`, then `EDIT BOT2` any time to reload it.

---

*Built on the pure-Kotlin `engine/` (RF-8 ISA + VM + world) — see `DESIGN.md` for the
locked spec and `firmware/*.asm` for the full preset sources.*
