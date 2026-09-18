# RoboFight — Design Document (v0)

> Status: **design phase** (no code yet). This is the source of truth for what we build.
> Target: Android (primary) + desktop (dev/test), **8-bit retro, ASCII text-grid**, combat-programming.
> Engine: **LibGDX 1.14.2**, Kotlin.

---

## 1. Fantasy

You are a **combat-programmer**. You write *firmware* for a robot in a small
assembly language, send it into the arena, and watch your code come alive to
fight AI (or a friend). The skill is **programming**, not reflexes. The "nerdy"
loop *is* the game: read sensor data, branch, fire, manage heat — all on a
1 KB machine rendered in chunky 8-bit ASCII.

## 2. Decisions log (locked)

| Area | Decision |
|------|----------|
| Aesthetic | **8-bit retro** — low-res, 16-color palette, chiptune |
| **Bot art** | **ASCII / text-grid** — bots, bullets & arena are glyphs in a cell grid (no sprite pipeline) |
| Arena | **Grid, turn-based** — 1 instruction/tick, deterministic |
| Grid | **20×20**, 4-way facing (N/E/S/W) |
| CPU | **RF-8** — full mini-CPU: 7 registers, stack, subroutines, memory-mapped I/O |
| Shooting | **Moving projectile**, 1 cell/tick |
| Screen | **240×240** square, nearest-neighbor upscaled |
| Editor UX | **Line-based assembly** (monospace, labels, syntax-highlighted); preview on **RUN** (not live) |
| Screen layout | **Arena on top · Editor below · Stats bar** (§13) |
| First mode | **Sandbox / editor-first** |
| v1 scope | **Trim** — editor + assembler + arena + presets (step-debugger deferred) |
| Modules | `engine` (pure Kotlin) · `core` (LibGDX) · `android` · `desktop` |

## 3. 8-bit / ASCII identity

- **Text-grid arena**: the 20×20 board is a grid of single-character cells,
  each rendered as a fixed-size box and nearest-neighbor upscaled to fill
  240×240. Same renderer drives the desktop console *and* the in-game arena.
- **16-color NES palette** for glyph + background colors (table below).
- **Bitmap font** (Press Start 2P, OFL) for the editor, menus, and stats.
- **CRT overlay** (optional toggle): scanlines + faint chromatic aberration.
- **Chiptune audio**: square/triangle SFX (shoot, hit, KO, win) + a simple loop.

### 3.1 ASCII glyph set

| Thing | Glyph | Notes |
|-------|-------|-------|
| Empty cell | `·` (or ` `) | faint color |
| Bot body | preset letter: `H` `T` `S` `C` `W` | color-coded per preset |
| Bot facing ("nose") | `^` `>` `v` `<` | rendered in the cell the bot faces into (if empty) |
| Projectile | `*` | bright, moves 1 cell/tick along its axis |
| Wall / boundary | `█` (or `#`) | arena edges |
| Hit flash | `!` | one-tick flash on a struck cell |
| Shield up | `O` | one-tick ring on a bot that raised shield |

### 3.2 NES 16-color palette

| # | Hex | Name | # | Hex | Name |
|---|-----|------|---|-----|------|
| 0 | `#7C7C7C` | white | 8 | `#7CB87C` | green |
| 1 | `#0000FC` | blue | 9 | `#00E43C` | bright green |
| 2 | `#007CFC` | sky | 10 | `#00F878` | lime |
| 3 | `#00FFFF` | cyan | 11 | `#FCFCFC` | white 2 |
| 4 | `#FC00BC` | pink | 12 | `#B8B8B8` | gray |
| 5 | `#FC0040` | red | 13 | `#585858` | dark gray |
| 6 | `#F87858` | orange | 14 | `#5858FC` | navy |
| 7 | `#F8B0A0` | peach | 15 | `#58F898` | mint |

**Preset colors**: HUNTER `red` · TURTLE `green` · SNAKE `cyan` · CHAOS `pink` · WALKER `orange`.

### 3.3 Sample arena (tick 12)

A 20×20 board, cropped to the action (H = HUNTER at col 4, T = TURTLE at col 13;
H fired a `*` that has traveled east; T is facing west to intercept):

```
....................
....................
....................
....................
....................
....................
....................
....................
....................
....H>...*...T<....
....................
....................
....................
....................
....................
....................
....................
....................
....................
....................
```

## 4. Core loop

**CODE → SIMULATE (run) → FIGHT → SCORE → PROGRESS**

1. **Code** the bot in an in-game editor (ISA, registers, I/O ports).
2. **Simulate** in the sandbox arena — run the fight tick by tick.
3. **Fight** — bot vs bot; KO or highest HP after *N* ticks wins.
4. **Score** — ticks, shots, hits, damage dealt/taken.
5. **Progress** (later) — campaign AI ladder, bot roster, tournaments.

## 5. The CPU — RF-8

An 8-bit-class machine. **1 KB** of memory, 7 registers, robot state exposed as
**memory-mapped I/O ports** — like a real embedded system.

### 5.1 Registers (7)

| Reg | Size | Purpose |
|-----|------|---------|
| `A`  | 8-bit  | accumulator |
| `X`  | 8-bit  | index / pointer / scratch |
| `Y`  | 8-bit  | index / scratch |
| `SP` | 10-bit | stack pointer (grows down from `$00BF`) |
| `PC` | 10-bit | program counter (starts `$0100`) |
| `P`  | 8-bit  | flags `C Z V N H I` |

### 5.2 Memory map (1024 bytes, `$0000–$03FF`)

| Range | What it is |
|-------|-----------|
| `$0000–$007F` | data RAM (128 bytes) |
| `$0080–$00BF` | stack (grows down from `$00BF`) |
| `$00C0–$00FF` | **I/O ports** — robot state & world |
| `$0100–$03FF` | program (768 bytes) |

### 5.3 I/O ports (`$00C0`+)

| Addr | Name | R/W | Meaning |
|------|------|-----|---------|
| `$00C0` | `HP` | R/W | self HP |
| `$00C1` | `DIR` | R/W | facing: 0=N 1=E 2=S 3=W |
| `$00C2` | `X` | R | grid column |
| `$00C3` | `Y` | R | grid row |
| `$00C4` | `DIST` | R | nearest enemy distance (0 = none) |
| `$00C5` | `ANGLE` | R | facing to nearest enemy (0–3, 255 = none) |
| `$00C6` | `FIRE` | W | write 1 → shoot (SHOOT alias) |
| `$00C7` | `SHIELD` | R/W | shield active this tick |
| `$00C8` | `HEAT` | R | heat level |
| `$00C9` | `ENEMY_HP` | R | nearest enemy HP |
| `$00CA` | `ENEMY_X` | R | nearest enemy column |
| `$00CB` | `ENEMY_Y` | R | nearest enemy row |
| `$00CC` | `TICK` | R | global tick counter |

### 5.4 Instruction set (~40 mnemonics, 16 distinct verbs)

Encoding: **1-byte** (no/register operand), **2-byte** (immediate or relative
branch), **3-byte** (absolute 10-bit address). `nnnn` = absolute address,
`(X)` = zero-page indexed by `X`.

| Family | Mnemonics |
|--------|-----------|
| **No-op / robot** | `NOP` `WAIT` `MOVE` `SHOOT` `SHIELD` `TURNL` `TURNR` |
| **Stack / subr.** | `RET` `PUSH A` `PUSH X` `PUSH Y` `PUSH #n` `POP A` `POP X` `POP Y` |
| **Transfer** | `MOV A,#n / X / Y / nnnn / (X)` · `MOV nnnn,A` · `MOV X,Y` `MOV Y,X` `MOV X,#n` `MOV Y,#n` · `XCH A,X` `XCH A,Y` `XCH A,(X)` |
| **Arith** (A=result) | `ADD` `SUB` `MUL` `DIV` `INC A` `DEC A` `NEG A` `NOT A` |
| **Logic** (A=result) | `AND` `OR` `XOR` `TST A` |
| **Compare** (flags) | `CMP A,#n / X / Y / nnnn / (X)` |
| **Branch** (rel.) | `JMP nnnn` `JZ/JNZ` `JC/JNC` `JN/JNN` `JG/JGE/JL/JLE` (aliases `JE/JNE`) |
| **Subroutine** | `CALL nnnn` |
| **I/O** | `IN nnnn` (A = MEM[n]) `OUT nnnn,A` (MEM[n] = A) |

### 5.5 Sample firmware — `HUNTER`

```
        ; firmware v1 — HUNTER
loop:   IN    DIST            ; nearest enemy distance -> A
        JZ    idle            ; nothing there? idle
        IN    ANGLE           ; face the target
        OUT   DIR, A
        MOVE                  ; step forward
        SHOOT                 ; fire
        JMP   loop
idle:   WAIT
        JMP   loop
```

## 6. Combat model

- **20×20 grid**, indices 0–19; each bot occupies one cell; 4-way facing.
- **Per-tick order**: (1) each *alive* bot executes **exactly one instruction**
  (bot-index order, deterministic); (2) **world resolves** — projectiles advance
  1 cell, collisions, off-grid vanish, damage applied.
- `SHOOT` spawns a **projectile** moving 1 cell/tick along the bot's `DIR`.
- **Projectile hit**: reaching an enemy bot cell deals **10 HP**, projectile
  vanishes. Off-grid → vanishes.
- **Anti-spam**: `SHOOT` raises `HEAT`; overheated → shot disabled until `HEAT`
  decays. (Default: heat cap 5, decays 1/tick.)
- **SHIELD** absorbs one hit that tick (no damage).
- **Win**: KO (HP ≤ 0) first, or **highest HP after N ticks** (default 200).
  Tie → draw.

## 7. Modes

**v1 — Sandbox / editor** (build & verify first):

- Assembly **editor** — monospace, line numbers, highlight mnemonics/labels/numbers.
- **Assembler** with real errors:
  `line 12: unknown opcode 'SHOOTX'`, `line 7: label 'loop' not found`, `operand out of range`.
- **Sandbox arena** — drop your bot + ghost bot(s), run the fight, watch it tick.
- **Preset firmware** — `HUNTER`, `TURTLE`, `SNAKE`, `CHAOS`, `WALKER`.
- **Stats panel** — ticks, shots, hits, damage dealt/taken.

**Later (roadmap)**: step-debugger (single-step, watch regs/memory, breakpoints) ·
**Campaign vs AI** (difficulty ladder) · **PvP hot-seat** · **Tournaments** ·
**Progression** (unlock parts/weapons).

## 8. Architecture

Standard LibGDX multi-module. **The whole game brain lives in `engine/`**
(pure Kotlin, no LibGDX) so it is unit-testable and verifiable **headlessly**,
independent of any UI or device. The **ASCII text-grid renderer also lives in
`engine/`** so the desktop console and the LibGDX arena share it.

```
robofight/
  settings.gradle.kts
  build.gradle.kts            (root, version catalog)
  gradle/libs.versions.toml
  engine/                     pure Kotlin + JUnit5
                              → ISA, Assembler, VM, World, Simulator,
                                TextGrid renderer, presets
  core/                       LibGDX 1.14.2 + engine
                              → EditorScreen, ArenaScreen (upscaled TextGrid),
                                rendering, audio
  android/                    AndroidApplication + core
  desktop/                    Lwjgl3Application + core  (dev/test on PC,
                              also hosts the ASCII console runner)
```

Dependency graph: `android → core → engine`, `desktop → core → engine`.

## 9. Roster (5 presets)

| Bot | Letter | Color | Personality |
|-----|--------|-------|-------------|
| **HUNTER** | `H` | red | Seeks nearest enemy, faces it, advances, fires. Aggressive. |
| **TURTLE** | `T` | green | Shields up, retreats, pops a shot only when flushed. Defensive. |
| **SNAKE** | `S` | cyan | Orbits the arena edge, fires when aligned. Trickster. |
| **CHAOS** | `C` | pink | Pseudo-random turns + random shots. Unpredictable. |
| **WALKER** | `W` | orange | Simplest: walks in a line, shoots on a fixed cadence. Baseline. |

### Sample preset firmware

```
; TURTLE — defensive
loop:   SHIELD
        IN    DIST
        JZ    wait
        CMP   A, #2
        JGE   wait
        SHOOT
wait:   WAIT
        JMP   loop
```

```
; SNAKE — orbit + fire
loop:   IN    DIST
        JZ    spin
        IN    ANGLE
        OUT   DIR, A
        TURNR
        SHOOT
        JMP   loop
spin:   TURNL
        JMP   loop
```

```
; CHAOS — random
loop:   MOV   A, #0
        CALL  rnd
        CMP   A, #2
        JGE   shot
        TURNR
        JMP   loop
shot:   SHOOT
        TURNL
        JMP   loop
rnd:    MOV   A, #1
        RET
```

## 10. Tuning knobs (defaults adopted — tweak later)

| Knob | Default | Note |
|------|---------|------|
| Round length `N` (ticks) | 200 | higher = longer battles |
| Shot damage | 10 | HP 100 → 10 clean hits to KO |
| Heat cap / decay | 5 / 1 per tick | anti-spam |
| Start HP | 100 | per bot |
| Max bots in arena | 4 | 2 in v1, 4 possible |
| Projectile speed | 1 cell/tick | fixed |
| Persistence | JSON | firmware + stats, local |

## 11. Milestones

- **M0** — design lock (this document) ✅
- **M1** ✅ DONE — `engine/`: ISA + assembler + VM + world + simulator + presets +
  TextGrid renderer + 5 presets + unit tests (20/20 passing) + headless demo
  (`robofight.DemoKt`) that plays a fight and prints the ASCII arena.
- **M2** ✅ DONE — `desktop/` CLI runner (`robofight.desktop.MainKt`): two bots by
  preset name **or** `.asm` file, live/step modes, `--melee`, `--seed`, `--log`
  trace. Plus `firmware/*.asm` (5 presets + editable `MYBOT.asm`). Verified:
  file-vs-preset, melee, and trace-log all run; 20/20 tests still green.
- **M3** — `core/` + `android/`: 8-bit **upscaled text-grid arena**, line-based
  editor screen, preset picker, stats panel.
- **M4** — Campaign vs AI, bot roster, progression.
- **M5** — Step-debugger, PvP hot-seat, tournaments.
- **M6** — Chiptune audio, CRT overlay, icon, store-ready packaging.

## 12. Open questions (design) — RESOLVED

All locked:
1. ✅ In-game layout — **arena on top · editor below · stats bar** (§13).
2. ✅ Roster — **5 presets** as listed (§9).
3. ✅ Editor feel — **preview on RUN** (not live); syntax highlighting as proposed.

**M0 + M1 + M2 are complete.** The engine core is built, compiled, and verified;
the desktop runner is playable from the shell. Next: **M3** — `core/` + `android/`:
the 8-bit upscaled text-grid arena, line-based editor, and preset picker in LibGDX.

## 13. In-game screen layout (locked)

240×240, **arena on top · editor below · stats bar**.

```
+-------------------------------+
|  ROBOFIGHT   [RUN][STEP][QUIT]|   <- top bar (bitmap font)
+-------------------------------+
|  . . . . . . . . . . . . . . |
|  . . . H > * * * . . . T < . |   <- ARENA (upscaled TextGrid, ~50%)
|  . . . . . . . . . . . . . . |
|  . . . . . . . . . . . . . . |
+-------------------------------+
|  1  loop:   IN  DIST          |
|  2         JZ  idle          |   <- EDITOR (line-based, highlighted, ~40%)
|  3         IN  ANGLE         |
|  4         OUT DIR, A        |
|  5         MOVE              |
|  6         SHOOT             |
|  7         JMP loop          |
|  8  idle:   WAIT             |
+-------------------------------+
|  T:12 H:14  SH:3 HIT:2  D:20 |   <- STATS bar
+-------------------------------+
```

- **Buttons**: **RUN** (assemble + play), **STEP** (single tick), **QUIT** (to menu).
- **Editor previews on RUN** (not live while typing) — the arena stays stable until
  you commit; the editor then highlights the failing line with the assembler's
  message on error.
