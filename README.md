# RoboFight

> **An 8-bit combat-programming game for Android.** You are a combat-programmer:
> you write *firmware* in a tiny assembly language for a robot on a 20×20 grid,
> then pit it against preset bots and watch your **code** fight — one instruction
> per tick, rendered in chunky ASCII.
>
> **The skill is programming, not reflexes.** Read the sensor ports, branch, fire,
> manage heat. That's the whole game, and that's the fun.

```
+------------------------------+
|  ROBOFIGHT        RF-8 ONLINE|
+------------------------------+
|                              |
|       . . . . . . . .        |
|       . . H > * * * . T < .  |
|       . . . . . . . .        |
|                              |
+------------------------------+
| 1  top:   IN     DIST        |
| 2         JZ     idle        |
| 3         IN     ANGLE       |
| 4         TURN   R          |
| 5         MOVE              |
| 6         SHOOT             |
| 7         JMP    top        |
+------------------------------+
| T:14  HUNTER HP90 SH4 HIT2   |
+------------------------------+
```

RoboFight is a small, self-contained game. There is no sprite pipeline, no asset
factory, no network — just a **pure-Kotlin CPU** (the "RF-8"), a deterministic
turn-based world, and a native Android front-end that draws the arena as a grid of
glyphs and gives you a green-screen terminal to write and run firmware.

---

## What's here (v0.3)

- **RF-8 CPU** — a small 8-bit-class machine: 3 registers (`A`, `X`, `Y`), a
  stack, ~40 mnemonics, and robot state exposed as **memory-mapped I/O ports**
  (`HP`, `DIR`, `DIST`, `ANGLE`, `FIRE`, `HEAT`, …) — like a real embedded target.
- **Assembler with real errors** — `line 5: unknown opcode 'SHOOTX'`,
  `line 9: undefined label 'idle'`, `immediate 300 out of range 0..255`.
- **Deterministic arena** — 20×20 grid, one instruction per alive bot per tick,
  projectiles at 1 cell/tick, heat-based anti-spam, shield, KO or best-HP-after-200.
- **5 preset bots** with distinct personalities — `HUNTER`, `TURTLE`, `SNAKE`,
  `CHAOS`, `WALKER` — plus **`MYBOT`**, your code.
- **Two modes** in the Android app:
  - **SHELL** — a file terminal (`DIR` / `EDIT` / `NEW` / `DEL`) + a full-height
    line-based editor, all kept above the Android soft keyboard.
  - **RUN** — bot selection, `RUN` / `STEP` / `RESET`, and live telemetry.
- **A 20-check self-test** of the engine, run headlessly by `:engine:test`.
- **Offline build path** — a script that assembles, dexes, aligns, and signs the
  APK without touching the network (see *Build*).

The game brain (ISA, assembler, VM, world, simulator, presets) is **pure Kotlin
with zero Android or framework dependencies**, so it is unit-testable and can run
headless on the JVM — the Android app is just a front-end on top.

---

## Quick start

**Play it.** Install the debug/release APK on an Android device (API 24+):
it starts in the shell with `MYBOT` already loaded. Tap the file to open the
editor, edit if you like (or don't), then tap **RUN** — set one slot to `MYBOT`
so your code is actually in the fight — and press **RUN** again. Watch your
assembly come alive. `STEP` advances one tick; `RESET` clears the board.

**Run the engine headlessly.** The test source set contains a runnable demo that
plays a fight and prints the ASCII arena. It's wired to the `:engine` test classpath:

```bash
./gradlew :engine:test          # runs the 20-check self-test + demo
```

> Full user manual: **[MANUAL.md](MANUAL.md)**. The complete RF-8 language cheat
> sheet, combat rules, and troubleshooting all live there.

---

## Repository layout

```
robofight/
  app/                  native Android front-end (Kotlin)
    src/main/kotlin/robofight/android/
      MainActivity.kt     single-activity shell, SHELL/RUN modes
      RunController.kt    fight state machine over the engine
      ArenaView.kt        the 20×20 glyph arena renderer
      BotFiles.kt         firmware file store (SQLite)
      Firmware.kt         assembler wrapper + error surfacing
      BotSlot.kt          A/B bot selection (presets + files)
    src/main/assets/      MYBOT.asm starter + Press Start 2P font
  engine/               PURE Kotlin — the game brain, no Android
    src/main/kotlin/robofight/
      isa/        ISA.kt      opcodes, encodings
      assembler/  Assembler.kt
      vm/         Vm.kt       the CPU
      world/      World.kt    grid, bots, projectiles
                Simulator.kt  per-tick step
                TextGrid.kt   shared ASCII renderer
                Presets.kt    the 5 bots
    src/test/kotlin/    Tests.kt (self-test) + Demo.kt (headless fight)
  firmware/             the 5 presets + MYBOT, as .asm sources
  build-android.sh      offline aapt2→kotlinc→d8→apksigner pipeline
  DESIGN.md             locked design spec (the source of truth)
  MANUAL.md             user manual (v1)
  PLAN-M3-ANDROID.md    the plan that took us to native Android
```

Dependency graph: `:app → :engine`. That's the whole app.

---

## Build

**Standard (Android Studio / Gradle).** This is the path we want you to use.
`settings.gradle.kts` points at `google()` + `mavenCentral()`. Open the repo in
Android Studio, or:

```bash
./gradlew :engine:test        # engine self-test
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # release APK (sign with your own key)
```

Toolchain: **AGP 9.4.1** (with built-in Kotlin), **Kotlin 2.2.10**,
`compileSdk 37`, `minSdk 24`, `targetSdk 34`. The engine is a plain
`kotlin("jvm")` library; the app is a `com.android.application` module.

**Offline / air-gapped.** `build-android.sh` builds and signs `robofight.apk`
without any network access, driving the SDK's native tools directly
(`aapt2 → kotlinc → jar → d8 → inject → zipalign → apksigner`). It's useful when
Maven/AGP can't be reached. Note it is **host-specific** (it references the
original author's JDK, `kotlinc`, SDK, and Python paths at the top of the file) —
treat it as a recipe, adjust the `JAVA_HOME` / `KOTLINC_JAR` / `SDK` block to
your machine, or use Gradle above if you have connectivity.

---

## The RF-8 language (60-second tour)

Every line is `[label:]  MNEMONIC  [operand]`; `;` starts a comment. The classic
`HUNTER` bot is the whole idea in a few lines:

```asm
; face the nearest enemy, step in, fire
top:   IN     DIST        ; distance to nearest enemy -> A
       JZ     idle        ; none? go idle
       IN     ANGLE       ; how many cw turns to face it
loop:  JZ     face        ; already facing?
       TURN   R           ; one step clockwise
       DEC    A
       JMP    loop
face:  MOVE              ; step toward the enemy
       SHOOT             ; fire (if heat allows)
       JMP    top
idle:  WAIT
       JMP    top
```

Read it as: *every tick — if there's an enemy, rotate to face it, walk one cell,
shoot.* Sense (`IN`) → decide (`JZ`/`CMP`) → act (`MOVE`/`SHOOT`).

Key facts:

| | |
|---|---|
| **Registers** | `A` (accumulator), `X` (index/scratch), `Y` (scratch) |
| **Sensors** (`IN`/`OUT`) | `HP` `DIR` `RX` `RY` `DIST` `ANGLE` `ENEMY_HP` `HEAT` `SHIELD` `RAND` `FIRE` `AHEAD` |
| **Robot** | `MOVE` `TURN L`/`TURN R`/`TURN #n` `SHOOT` `SHIELD` `WAIT` `NOP` |
| **Branch** | `JMP` `JZ` `JNZ` `JC` `JNC` `JN` `JNN` `JG` `JGE` `JL` `JLE` `JE` `JNE` |
| **Subroutines** | `CALL` `RET`, with a stack (`PUSH`/`POP`) |
| **Combat** | 100 HP, 10 dmg/hit, heat cap 5 (≈1 shot/5 ticks), shield, win by KO or HP @ tick 200 |

`ANGLE` is the star sensor: it reads back a *delta* (0–3) — `0` = already facing the
enemy, `N` = turn right `N` steps to face it, `255` = no enemy. `IN ANGLE` → `JZ` if
already facing, else count down `TURN R` steps. That's how `HUNTER` aims. The full
cheat sheet, error formats, and worked examples are in **[MANUAL.md §7–§9](MANUAL.md)**.

---

## How this was built (the honest version)

**RoboFight was fully vibe-coded / agentic-coded.** It was built conversationally
with an AI coding agent: a person set the direction and the design spec, and the
agent drove the implementation — writing the ISA, the VM, the world model, the
Android UI, the tests, and even the offline build pipeline — and iterated against
real test runs and build output until things actually worked.

We're being straight about it for a couple of reasons:

- **It's a feature, not a caveat.** The whole point of the game is that
  *programming* is the fun loop — and this repo is a live example of code
  produced the same way, in the open, with the design docs and the messy build
  history all committed so you can see how it got here.
- **It's meant to be forked and shaped.** If you'd steer it somewhere — a new
  preset, a step-debugger, hot-seat PvP, a different CPU, a port — that's exactly
  the kind of thing this setup is good at. The cleanest path into the codebase is
  `engine/`, which has no framework dependencies and a test harness you can run in
  seconds.

The design spec ([DESIGN.md](DESIGN.md)) was written *first*, as the locked source
of truth, and the agent worked to it. Where the implementation and the design
diverge (the shipped RF-8 is a trimmed, simpler ISA than the original 7-register
draft), the code and [MANUAL.md](MANUAL.md) are what you should trust.

---

## Contributing

The bar is low on purpose. This is a small, readable codebase and there's a lot of
room.

- **Good first issues:** a new preset bot (`engine/world/Presets.kt` + a `firmware/*.asm`),
  a new sensor or instruction, or a bug in the assembler's error messages.
- **Larger, planned:** step-debugger (single-step, watch registers/memory, breakpoints),
  campaign-vs-AI difficulty ladder, PvP hot-seat, tournaments, chiptune audio + CRT overlay.
- **Rules of the road:**
  - Keep `engine/` dependency-free (no Android, no LibGDX). That's the whole reason
    the game brain is testable headlessly.
  - The combat model must stay **deterministic** (one instruction per alive bot, fixed
    order). No hidden randomness in the core step.
  - Add a check to `engine/src/test/kotlin/robofight/Tests.kt` for anything you touch,
    and make sure `./gradlew :engine:test` is green.
  - The 16-color palette and glyph set live in [DESIGN.md §3](DESIGN.md) — keep the
    aesthetic if you're touching rendering.

PRs, forks, and "I built a bot and it's weird but cool" are all welcome.

---

## Docs

| File | What it is |
|------|-----------|
| **[DESIGN.md](DESIGN.md)** | The locked design spec — fantasy, CPU, combat model, palette, milestones. Source of truth. |
| **[MANUAL.md](MANUAL.md)** | User manual — the two modes, the shell, the RF-8 language, combat rules, troubleshooting. |
| **[PLAN-M3-ANDROID.md](PLAN-M3-ANDROID.md)** | The plan that moved us from LibGDX to a pure native-Android app. |
| **[IDEA.md](IDEA.md)** | The one-line seed it all started from. |

---

## License & status

**Status:** pre-1.0 (v0.3), actively in flux, and explicitly a learning/experiment
project. No warranty, and the API is allowed to move.

**License:** [MIT](LICENSE) — short and permissive. Use it, fork it, ship it; just
keep the copyright notice in the source.
