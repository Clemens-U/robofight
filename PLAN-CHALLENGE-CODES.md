# PLAN — Challenge Codes (implementation of CHALLENGE-CODES.md v1)

> Spec: `CHALLENGE-CODES.md` (locked v1). This plan splits the work into five
> stages so that **stage 1 is a releasable new version of the existing free
> app** (arena) and ships *before* any challenge-code work is finished.
> Stages 3 and 4 each end with another store release; stage 2 and 5 are
> headless/infra work with no release of their own.

## Release order

| Release | versionCode | Content | Stage |
|---------|-------------|---------|-------|
| R1 — "arena"    | 5 (`0.4-arena`)  | 5 free arenas + wall engine | 1 |
| R2 — "import"   | 6 (`0.5-codes`)  | import + preview + deterministic playback of codes | 3 |
| R3 — "full v1"  | 7 (`0.6-codes`)  | builder + response flow + share + history | 4 |
| — (no release)  | — | gating scaffolding, release process, docs | 5 |

Current state: `versionCode = 4`, `versionName = "0.3-m3"` (`app/build.gradle.kts`).

## Why this split

- Every code embeds an arena id, and codes with an unknown layout are rejected
  (§9.2). A challenge-code app *cannot play any code* before walls + the five
  free arenas exist — so the arena is the hard dependency and a natural first
  release on its own.
- All **deterministic** engine changes (walls, per-slot HP, timed mode, SCATTER,
  binary firmware loading) land in **stage 1, in one batch**. `eng = 1` is
  minted at that release; from then on no engine change may sneak in without the
  §5 release-gate question. This avoids re-verifying determinism mid-project.
- The codec (stage 2) is pure-JVM and fully testable headless (§12.2) before a
  line of UI exists, so the R2 UI wires against a verified parser.

---

## Stage 1 — Arena + engine foundation → **release R1**

Goal: ship the five free arenas as a normal app update. Mint `eng = 1`.

### 1.0 Commit the spec
`CHALLENGE-CODES.md` is untracked; `DESIGN.md`/`README.md` already reference it.
Commit spec + this plan first.

### 1.1 Engine (pure Kotlin, `engine/`)
All additions here are deterministic and part of the `eng = 1` contract.

- **New `robofight/world/Arena.kt`**
  - `data class Layout(id: Int, name: String, walls: BooleanArray /*20×20*/, separate: Pair<Cell,Cell>, clash: Pair<Cell,Cell>)`
  - Five generators from the §7.3 **coordinate rules** (the rules are the
    implementation source of truth; the ASCII renderings are verified by test).
  - `object Arena { val registry: Map<Int, Layout>; fun byId(id: Int): Layout? }`
    — ids `0x00`–`0x04` only. `0x05–0x0F` reserved (absent = future free),
    `0x10+` = Pro range (absent in v1 ⇒ `UNKNOWN ARENA` per §9.2).
- **`World.kt` changes**
  - `World(layout: Layout = Arena.OPEN)`.
  - `Bot.move()`: block on wall cells (silent no-op, same as today's edge rule).
  - `spawnProjectile()`: never spawns on a wall cell.
  - `resolveProjectiles()`: projectile vanishes on a wall cell (today: only the
    grid edge absorbs).
  - `aheadBlocked()`: returns 1 for wall cells too (spec §7.5: walls register in
    `AHEAD`).
  - **Per-slot HP:** `Bot(..., hp: Int = MAX_HP)` constructor param + `reset(hp)`;
    values come from the `[50, 100, 150, 200]` table. Default 100 keeps all
    existing call sites (free play) unchanged.
  - **Timed mode:** `World.timeLimit: Int?` — `tickOnce()` ends the fight at the
    limit and resolves by HP with **tie = DRAW** (winner stays null). The
    1,000,000-tick safety cap path is untouched (endless fights keep their
    current semantics).
  - **SCATTER spawn:** engine function (deterministic, uses the world LCG via
    `nextRand()`): A draws a uniform free cell, B draws one; if Manhattan < 6,
    redraw the pair, up to 8 tries, then accept. Exact draw order documented in
    the code comment — it is part of the determinism contract.
  - **Binary firmware:** `Bot.fromBinary(name, glyph, color, x, y, dir, bytes, hp)`
    — loads the pre-assembled image straight into `vm.load(bytes)` (same
    path/trim/NOP-pad as `Bot.load(source)`). Skips the assembler; the binary is
    canonical (§4.3).
- **`Simulator.kt`:** `runFight(..., timeLimit: Int? = null)` passthrough;
  `stats()` reports `DRAW` on tie.

### 1.2 Engine tests (`engine/src/test/`, Tests.kt pattern + JUnit wrapper)
- **§12.1 geometry per arena:** 20×20 row/width integrity; wall counts
  (OPEN 0 · BLOCKS 16 · RING 20 · CROSS 28 · MAZE 108); point symmetry about
  (9.5, 9.5); BFS full connectivity from the perimeter (no sealed pockets);
  zero dead-end cells; spawn cells free; CLASH pairs adjacent (Manhattan 1).
- **Wall behavior:** MOVE blocked at a wall (silent), projectile absorption,
  `AHEAD = 1` at a wall; OPEN-arena regression (all 20 existing tests pass
  unchanged — OPEN must be behaviorally identical to today's free play).
- **Timed mode:** fight ends exactly at the limit, highest-HP win, tie = DRAW.
- **SCATTER:** same seed ⇒ identical spawns; pair ≥ 6 apart (when a redraw
  succeeds); both cells free.
- **Per-slot HP:** respected at start, shown at end.
- **Binary loading:** `assemble(src)` bytes ≡ source-loaded bot, tick-identical
  fight.
- **Fight matrix §12.3** (`FightMatrix.kt`, headless `main` + wired into the
  suite): 5 presets × 5 presets (25 ordered pairs) × {DUEL = KO/SEPARATE/100-100,
  MELEE = KO/CLASH/100-100} × each arena = **250 fights per arena, 2,000-tick
  budget, must resolve (never the 1M cap)**. Prints a per-arena pass/fail
  report. A failing arena is fixed or cut per spec — preset tuning is allowed
  (content, not spec) and needs no `eng` bump.

### 1.3 App (`app/`)
- RUN screen: arena picker row (OPEN/BLOCKS/RING/CROSS/MAZE, styled like the
  bot picker), default OPEN.
- `RunController.start()`: pass the selected layout into `World`; free-play
  spawns stay random but now pick **free cells** (skip walls) — existing
  non-deterministic behavior, unchanged for OPEN.
- `ArenaView.onDraw()`: render wall cells (solid filled block, DESIGN.md §3.1
  `█`) under projectiles/bots.
- `versionCode 4 → 5`, `versionName "0.4-arena"`; changelog: five arenas.

### 1.4 Verification + release gate
- Headless: full engine suite green via kotlinc (offline recipe,
  `build-android.sh` conventions); matrix 1,250/1,250 resolved.
- APK: `bash build-android.sh`, signed v2+v3, verified.
- **Device smoke (manual, no emulator available):** each arena renders with
  correct wall geometry; a free-play fight in MAZE runs; OPEN arena plays
  identically to 0.3-m3.
- **Store:** upload R1 to the existing free listing. *First release ships here —
  challenge-code work continues without waiting for the store.*

---

## Stage 2 — Codec (headless, no release)

Goal: the complete §4/§10/§11 wire format, verified to §12.2 before any UI.

### 2.1 New package `robofight/challenge/` in `engine/` (pure JVM)
- **`ChallengeFormat.kt`**
  - `const val FORMAT_VERSION = 1`, `const val ENGINE_VERSION = 1` (§5 — an
    integer constant, *not* the app version).
  - `data class FightDescriptor(...)` — every §4 field: layout, slots, flags,
    seed, mode, spawn, hp, result? (winner/ticks/endHp), fwA/fwB binaries,
    srcA/srcB?, eng.
  - `encode(desc): String` — `RF1-` + RFC 4648 base32 (uppercase, **no
    padding**), grouped in 4 with `-`.
  - `parse(text): ParseResult` — `Ok(desc, warning: String?) | Err(string)`
    with the **exact §10 strings**; runs the §11 checks **in order**, first
    failure wins.
  - base32 encoder/decoder from scratch (no offline lib): emit no padding;
    parse strips the prefix, then deletes every non-alphabet char — lowercase is
    an alphabet violation ⇒ `NOT AN RF CODE`; trailing partial group must be
    zero bits.
  - CRC-16/CCITT-FALSE from scratch (poly `0x1021`, init `0xFFFF`, no
    reflection/xor-out) over all preceding payload bytes.
  - Firmware blocks: pinned ⇒ multiple of 3, ≤ 768; open ⇒ 0; source blocks
    ≤ 2048 UTF-8 only when the flag is set; reserved `flags` bits must be 0.
  - **Decode scan:** dry-decode every triple of each pinned firmware with the
    existing `ISA.defined(opcode)` (already public) ⇒ `NEW OPCODES — UPDATE APP`.
- **`robofight/disasm/Disasm.kt`** (pure, display-only path):
  `disassemble(bytes): String` — 3-byte triples → mnemonic via the existing
  `ISA.name`/`ISA.kind` tables. Verified by round-trip: disassembling the
  assembler output of all 5 presets reproduces the same instruction sequence.

### 2.2 Tests (§12.2)
- **Round-trip corpus:** every slots value (4) × mode 0..4 × spawn 0..2 × all 16
  hp combos × 5 layouts × result absent/4 winners/NONE × sampled firmwares
  (open = 0; pinned = 3, 150, 768 B; sources absent/short/max 2048 B):
  `parse(encode(x)) == x` and `encode(parse(s)) == s` **byte-for-byte**.
- **Determinism:** same descriptor simulated twice (and after
  encode→decode→re-encode) ⇒ identical tick-by-tick worlds and outcomes.
- **Fuzz:** 10,000 random single-/multi-byte mutations of valid codes ⇒ either a
  successful parse or a **defined** §10 string; zero crashes, zero silent
  misplays.
- **Size:** max-payload code (both fw 768 B, both sources 2048 B, result
  present) ≤ 10,000 normalized chars.
- **Error strings:** one test per §10 row, including prefix/alphabet,
  `CODE TOO LONG`, both MALFORMED variants, FLAGS, RULES, SOURCE, RESULT,
  `MADE WITH NEWER VERSION/ENGINE`, `UNKNOWN ARENA`, `OLD ENGINE` (playable
  warning path).
- **CRC vector:** `"123456789"` → `0x29B1` (guards the from-scratch CRC).

Verification: headless kotlinc run, same as stage 1. No APK changes.

---

## Stage 3 — Import + playback → **release R2**

Goal: the user can paste/receive a code and play it exactly.

### 3.1 App
- **New `AppMode.CHALLENGE`** (third tab beside SHELL/RUN, programmatic UI like
  the existing screens):
  - Paste box + PASTE-from-clipboard; import runs `ChallengeFormat.parse`.
    Errors show the exact §10 string, terminal-voiced.
  - **Challenge screen:** arena preview (static grid: walls + spawn markers),
    rules line (mode / spawn / HP A–B / seed), mode label (REPLAY · CHALLENGE ·
    OPEN DUEL), opponent panel (embedded source if present, else the
    disassembly — §4.3), recorded-result badge ("as the author recorded it" —
    never re-verified).
  - **eng skew:** `OLD ENGINE — REPLAY USES CURRENT RULES` = warning, playable;
    newer eng/fmt = blocked with the §10 string.
  - **Open slots:** pick a local bot per open slot (5 presets or BotFiles) →
    RUN. REPLAY: play as-is. OPEN DUEL: pick both.
- **`RunController.startDeterministic(desc, openFirmware)`:** builds `World`
  from the descriptor — layout (SEPARATE/CLASH fixed spawns, SCATTER via the
  engine function), per-slot HP, code seed, `timeLimit` for timed modes, binary
  firmwares via `Bot.fromBinary` (pinned) / assembled local firmware (open).
  Free-play `start()` untouched.
- **Result capture:** on finish, store the actual outcome (winner relative to
  the code's slots, ticks, loser `end_hp`) in activity-local state — input for
  the stage-4 response flow and history.
- **Manifest:** `MainActivity` gains an `intent-filter` for
  `android.intent.action.SEND` (`text/plain`) so shared codes open the app;
  the extra is parsed through the same importer (same §10 handling).
- `versionCode 5 → 6`, `versionName "0.5-codes"`.

### 3.2 Verification
- Headless: codec suite still green.
- **Known-code vector:** commit a handful of reference codes in the repo
  (one per slots mode + one timed + one with result). Device smoke: import
  each, play, and check **tick count + winner + final HP match the headless
  simulation of the same code** (determinism across encode/decode, spec §3).
- **Store:** upload R2.

---

## Stage 4 — Builder + response + share → **release R3 (full v1)**

Goal: create, win, and send back — the complete §6/§8 loop.

### 4.1 App
- **Builder** (from the challenge screen): named rows DUEL / MELEE / SIEGE /
  GAMBIT (§6.1) + CUSTOM exposing the dials (mode, spawn, HP A/B, arena,
  random seed). Opponent picker (preset/file) for the pinned slot; embeds the
  source when ≤ 2,048 B. Output is always `slots = 0b01`.
  - Cold challenge (never played: `flags` bit 0 off / `winner = 0xFF`).
  - Or "record from the fight you just played" (winner/ticks/`end_hp` from the
    stage-3 capture).
- **Response flow (§8):** after a **win** in an imported CHALLENGE (KO, or timed
  with strictly higher HP) the SEND BACK action unlocks → generates the
  counter-challenge (same rules; B = the recipient's winning firmware + source
  if ≤ 2,048 B; A open; `result` remapped so the **pinned slot is the winner**)
  → straight to share. DRAW/loss: no response.
- **Share:** `ACTION_SEND` text/plain + clipboard copy. **QR is deferred** (no
  offline ZXing on the curated Maven mirror; a self-contained QR encoder is a
  follow-up — the mechanism is never gated, §9.3, so this doesn't break v1).
- **Local history** (BotFiles-style store): per-code outcomes, per-arena win
  counts; shown on the challenge screen / in the shell. App feature, outside
  the format (§8).
- `versionCode 6 → 7`, `versionName "0.6-codes"` — full v1 acceptance: §12.1–12.4
  all green.

### 4.2 Verification + release
- Headless: full suite + one scripted response chain (A wins → response code →
  B wins → second response code; every hop replays deterministically).
- Device smoke: build → share → import on a second device (or clear-data
  reimport), respond, verify the remapped result badge.
- **Store:** upload R3 — the v1 challenge-code feature is complete.

---

## Stage 5 — Gating scaffolding + release process (no release)

- **Entitlement stub:** `Entitlements.isPro(): Boolean` (v1: constant false —
  no billing lib exists offline; Play Billing lands when Pro content is real).
  Gate points per §9.1 are wired but inert: playback of a Pro layout id
  (locked RUN + `ARENA NEEDS PRO — UNLOCK TO PLAY`) and hidden/locked Pro
  entries in the builder menu. v1 registry holds only free ids, so `0x10+`
  currently resolves to `UNKNOWN ARENA — NEWER VERSION` per §9.2.
- **Import decision table tests** (§9.2): simulate free/Pro build × code type
  (free layout, Pro id, unknown id, newer eng/fmt, old eng, malformed) and
  assert the exact behavior/string per cell.
- **Release gate (from now on):** every changelog answers §5 — *"Does this
  release change deterministic behavior? If yes, bump `ENGINE_VERSION` and say
  so."*
- **Docs:** MANUAL.md chapter (challenge codes: modes, dials, response loop),
  README table row, DESIGN.md roadmap line → done.
- **Migration note for the record:** if Pro grows beyond a few generators,
  switch to the hard gate (§9.1) — format unaffected.

---

## Risks & mitigations

| Risk | Mitigation |
|------|------------|
| MAZE fails the §12.3 matrix (simple presets are *designed* to suffer, but must still resolve ≤ 2,000 ticks in DUEL/MELEE) | Matrix runs in stage 1, before R1 ships; failing arena is fixed or cut (spec); preset tweaks are content, no `eng` bump |
| From-scratch base32/CRC (no offline libs) | Test vectors (`0x29B1`) + 10k-mutation fuzz gate |
| Disassembler is a new artifact | Round-trip test vs all 5 presets; display-only, never on the execution path |
| SCATTER draw-order ambiguity | Fixed order documented in code comment + determinism test |
| No emulator (no system image) | Device smoke on the phone is an explicit release gate in every stage; headless determinism check (R2 vector codes) covers the engine side |
| `MainActivity.kt` already 1,403 lines of programmatic UI | New screens follow the existing convention; challenge UI stays in one screen-builder per stage |
| OneDrive reparse-point landmines on new files | Watch `build-android.sh` for "not a regular file"; fix with the cp/rm/mv dance if hit |

## Out of scope for v1 (spec §13)
Pro arena content, procedural arenas (`fmt = 2`), custom round lengths, second
weapon / new knobs, 4-bot codes, survival mode, QR (deferred), hard-gate
migration. The append-only invariant: a v1 code keeps meaning exactly what the
spec says, on every future free build, forever.
