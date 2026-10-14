# CHALLENGE-CODES — Specification (v1)

> Status: **locked spec (v1)**. The single source of truth for challenge codes:
> the wire format, the free arena registry, the rule knobs, and the free/Pro
> gating contract. The app implementation (encoder + parser + challenge screen)
> must conform to this document; the acceptance criteria in §12 define "done".
>
> A **challenge code** is a short, copy-pasteable text string that fully
> describes a fight: arena, seed, rules, and the firmware(s). It is the
> mechanism for three things: **replays** ("watch this fight exactly"),
> **challenges** (*"can you write a firmware that beats mine in this arena?"*),
> and **response chains** (win → generate the counter-challenge → repeat).

---

## 1. Principles

1. **Mechanism is free; content is paid.** No one is ever blocked from
   creating, sharing, importing, or *previewing* a code. An entitlement gate
   only decides whether *playback* of Pro content starts and which arena
   entries appear in the Pro builder menu (§9).
2. **Self-contained.** A code contains everything needed to reproduce the
   fight: arena id, seed, rule knobs, firmware binaries, and the recorded
   result. No server, no content pack, no DRM, no network call.
3. **Deterministic.** A code is a complete fight state. Same code + same
   engine ⇒ same fight, every time. Firmware *binaries* are canonical for
   execution; embedded *source* is display-only (§4.3).
4. **Append-only.** The v1 free format never changes. New free arenas get
   new low ids; Pro content gets the `0x10` range; new deterministic behavior
   bumps `engine_version`; new mode classes wait for v2 (§13). Free codes
   must parse and play on any free build, forever.

---

## 2. Code modes (the slot mask)

One byte, `slots`, selects the mode and which firmware slots are **pinned**
(carry their binary in the code) versus **open** (a hole the recipient fills
with their own bot):

| `slots` | Mode | A | B | Meaning |
|---------|------|---|---|---------|
| `0b00` | **REPLAY** | pinned | pinned | "Watch this fight exactly as it happened." |
| `0b01` | **CHALLENGE** | **open** | pinned | "Can you beat my bot (B) in this arena?" — the canonical challenge. |
| `0b10` | **CHALLENGE** | pinned | **open** | Same, mirrored. Parseable by all builds; the v1 builder never emits it. |
| `0b11` | **OPEN DUEL** | open | open | Arena + rules fixed by the code; the recipient fills *both* slots from their own store. |

Conventions:

- The builder always emits `0b01` — the sender's bot sits in **B**, the
  recipient is **A** ("you are A"). The importer handles all four values.
- `0b11` is *not* a survival mode. When an environment-opponent survival
  mode ("true gauntlet") ships, it lands as a v2 mode class (§13) and
  **never reuses `0b11`**, which means "open duel" forever.

**Validity rule (enforced at parse):** a slot that is open **must** carry a
firmware length of `0`; a slot that is pinned **must** carry a length in
`3..768` that is a multiple of 3. Any mismatch is `CODE MALFORMED — SLOTS`
(§10).

---

## 3. Text form

```
RF1-<base32>
```

- Prefix: literal `RF1-` (app id + format version 1).
- Payload: **base32, RFC 4648**, uppercase alphabet `A–Z 2–7`, **no padding**.
- Emit: groups of 4 characters separated by `-` for readability
  (`RF1-MRTH-4T2G-…`).
- Parse: strip the prefix, then delete **every character outside** the
  base32 alphabet (hyphens, spaces, newlines, lower-case is *not* accepted —
  a lower-case letter is an alphabet violation and parses to
  `NOT AN RF CODE`), then decode. Trailing bits < 5 are padding and must be
  zero.
- Size limit: normalized input longer than **10,000 characters** is rejected
  as `CODE TOO LONG` before decoding. (A full-payload code is ≈ 9 KB of
  text; a typical challenge is ≈ 400–600 characters.)

---

## 4. Payload format (v1)

The base32-decoded payload is a little-endian byte stream of **ordered
fields**. Fixed prefix (12 bytes), optional result, two firmware blocks,
two optional source blocks, then the checksum.

### 4.1 Field table

| # | Field | Size | Values / notes |
|---|-------|------|----------------|
| 1 | `fmt` | 1 B | `1`. (Duplicated by the `RF1` prefix; the byte exists so raw payloads can be inspected.) |
| 2 | `eng` | 1 B | **engine version** of the build that produced the code. Starts at `1` (§5). |
| 3 | `layout` | 1 B | Arena id from the registry (§7). |
| 4 | `slots` | 1 B | Mode mask, §2. Only `0b00..0b11`; higher values are `CODE MALFORMED — SLOTS`. |
| 5 | `flags` | 1 B | bit 0 = result present · bit 1 = source A present · bit 2 = source B present · bits 3–7 reserved, must be `0`. |
| 6 | `seed` | 4 B | Fight seed, little-endian. Drives the RNG port and SCATTER spawns (§7.6). |
| 7 | `mode` | 1 B | Index into: `0`=KO · `1`=100 · `2`=150 · `3`=200 · `4`=300 ticks (§6). |
| 8 | `spawn` | 1 B | `0`=SEPARATE · `1`=CLASH · `2`=SCATTER (§7.6). |
| 9 | `hp` | 1 B | Bits 0–1 = slot A, bits 2–3 = slot B, each `0..3` → `[50, 100, 150, 200]`. Bits 4–7 must be `0`. |
| 10 | `result` | 5 B | **Only if** `flags` bit 0. §4.2. |
| 11 | `fwA_len` | 2 B | Length of firmware A in bytes. `0` ⇔ A is open; else a multiple of 3, ≤ 768. |
| 12 | `fwA` | n B | Firmware A binary (assembled image, §4.3). |
| 13 | `fwB_len` | 2 B | As `fwA_len`, for B. |
| 14 | `fwB` | m B | Firmware B binary. |
| 15 | `srcA` | 2 B + k B | **Only if** `flags` bit 1: 2-byte LE length + UTF-8 source text, length ≤ 2048. Display-only. |
| 16 | `srcB` | 2 B + l B | As `srcA`, for B. |
| 17 | `crc` | 2 B | CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`, no reflection, no xor-out) over **all preceding payload bytes**. |

Minimum payload size: **18 bytes** (12 + 2 + 2 + 2, no optional fields).

### 4.2 Result field (5 bytes)

| Field | Size | Values |
|-------|------|--------|
| `winner` | 1 B | `0`=A · `1`=B · `2`=DRAW · `0xFF`=NONE (author never ran it). |
| `ticks` | 3 B | LE; ticks at fight end (≤ 16,777,215; the 1,000,000-tick safety cap fits). |
| `end_hp` | 1 B | HP of the *losing* side at fight end (`0..200`). For DRAW: slot A's HP. For NONE: `0`. |

Semantics: the result describes **the fight as the code's author saw it, in
this code's slot orientation**. When a response code is generated the app
remaps so that **the result of a response code always describes a fight in
which the pinned slot won** (§8). A REPLAY code carries the author's actual
outcome (winner may be A, B, or DRAW).

### 4.3 Firmware encoding

- **Canonical = binary.** The stored firmware is the assembler's output
  image — 3 bytes per instruction, starting at instruction 0 (what the VM
  loads at `$0100`, trimmed; the loader NOP-pads to 768). Execution never
  re-assembles from a code.
- **Display = source (optional).** If the author's source text is ≤ 2048
  bytes, the encoder embeds it (flags bit 1/2) purely for display. Rules:
  - The embedded source is **never executed** and is never assumed to
    match the binary (it is decorative; the binary is truth).
  - If the author's source is > 2048 bytes, the encoder omits the flag and
    the block entirely.
  - A receiver with no embedded source shows a **disassembly** of the
    binary instead (labels absent — acceptable; the display path, not the
    execution path).
- **Decode scan (import-time integrity):** the importer dry-decodes every
  3-byte triple of each *pinned* firmware against the current build's ISA
  table. Any unknown opcode ⇒ reject `NEW OPCODES — UPDATE APP`. (In
  practice the `eng` check in §5 catches version skew first; the scan is
  the final gate that no executable payload reaches the VM unvalidated.)

---

## 5. Determinism & the `eng` (engine version) contract

`eng` is an integer constant in the app source (`ChallengeFormat.ENGINE_VERSION`),
starting at `1`. It is **not** the app's version number.

**Bump `eng` if and only if** a given `(layout, rules, seed, firmware)`
tuple could produce a different fight result:

- changes to the combat model (damage, heat, shields, win conditions);
- changes to world resolution (projectiles, tick order, spawn rules);
- changes to ISA *semantics* (an opcode's effect, flag behavior, I/O ports);
- changes to the RNG stream or its seeding;
- a fix to an **in-use** layout generator (a new id is content — it needs no
  bump because no old code references it; a changed generator does, because
  old codes do).

**Do not bump** for: UI, editor, shell, renderer, audio, new free or Pro
arenas (new ids), new named modes (display-only), new error strings.

**Skew behavior at import:**

| Condition | Behavior |
|-----------|----------|
| `eng` > app's | Reject: `MADE WITH NEWER ENGINE — UPDATE APP`. |
| `eng` = app's | Play. Deterministic, exact. |
| `eng` < app's | **Play, with warning** `OLD ENGINE — REPLAY USES CURRENT RULES`. The fight runs under current semantics; the embedded `result` is always shown *as recorded by the author* and is never re-verified. |

**Release checklist item (CI/manual):** *"Does this release change any
deterministic behavior? If yes, bump `ENGINE_VERSION` and say so in the
changelog."*

---

## 6. Rule knobs (the free dials)

Everything below is selectable by **any** user, free or Pro, per challenge.

| Knob | Field | Free options |
|------|-------|--------------|
| **Mode** | `mode` | KO (run to a KO), or timed: 100 / 150 / 200 / 300 ticks (highest HP wins; tie = DRAW) |
| **Spawn** | `spawn` | SEPARATE · CLASH · SCATTER (§7.6) |
| **HP** | `hp` | 50 / 100 / 150 / 200 — **independent per slot** (asymmetric challenges are legal and intended) |
| **Arena** | `layout` | the five free arenas, §7 |
| **Seed** | `seed` | any 32-bit value |
| **Opponent(s)** | `fwA` / `fwB` | any preset or saved firmware (pinned slots); any local bot (open slots) |

**Fixed in v1** (not encodable; they live in the engine, and changing them
is an `eng` bump, never a code field): shot damage 10, heat +20 / −2 / cap
100 / lockout at 80 / burn 2 HP/s above 80, projectile 1 cell/tick,
projectile lifetime 40 ticks, 2 bots, 1,000,000-tick safety cap.

### 6.1 Named challenge modes (display layer)

Common knob combinations get names. Names are **UI-only**: codes always store
the explicit knob values, never a name.

| Name | Mode | Spawn | HP (A/B) | Pitch |
|------|------|-------|----------|-------|
| **DUEL** | KO | SEPARATE | 100/100 | The fair fight. |
| **MELEE** | KO | CLASH | 100/100 | Knives out, center of the room. |
| **SIEGE** | 300 ticks | SEPARATE | 200/200 | War of attrition — heat management wins. |
| **GAMBIT** | KO | SEPARATE | 50/150 | The taunt: the *pinned* (sender's) bot fights over-equipped; the recipient's bot is at 50. |

The builder offers the four names plus a **CUSTOM** row exposing the dials.

---

## 7. Arena registry

### 7.1 Layout id allocation

| Range | Assignment |
|-------|------------|
| `0x00–0x04` | Free arenas defined in this spec (§7.2). |
| `0x05–0x0F` | **Reserved for future free arenas.** A new free arena is a new id + generator + registry entry; no format change. |
| `0x10–0x1F` | **Pro arena pack (future).** Gated per §9. v1 defines none of these. |
| `0x20–0xFF` | Reserved for v2 layout classes (e.g. procedural arenas with parameters). |

### 7.2 Invariants (every arena, free or Pro)

1. **Fully connected:** a 4-neighbor walk from the perimeter reaches every
   free cell (no sealed pockets, no islands).
2. **No dead ends:** no free cell has exactly one free neighbor. Rationale:
   bots see only one cell ahead (`AHEAD`); a dead end soft-locks simple
   presets into fights that run to the 1,000,000-tick cap — a UX failure.
   (Pro arenas may later relax this *only* with hazard/exit mechanics that
   resolve the stall; the invariant is re-proven per arena either way.)
3. **Point-symmetric** about the board center (9.5, 9.5) — SEPARATE/CLASH
   spawns are therefore fair by construction.
4. **Spawns defined** (§7.3) and all on free cells; CLASH spawns are
   adjacent (Manhattan distance 1).
5. **Corridors ≥ 2 cells wide** in the free set (checked per arena below).
6. Every arena ships with its **acceptance matrix result** (§12.3) — an
   arena that fails the matrix does not ship, free or Pro.

### 7.3 Free arena definitions

The **coordinate rules are the implementation source of truth**; the ASCII
renderings are generated from them and verified (§12.1). Grid: 20×20,
`x` = column 0–19 (left→right), `y` = row 0–19 (top→bottom). `#` = wall.

#### `0x00` OPEN — 0 walls

No walls. The baseline: pure aiming, spacing, and heat management.

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
....................
```

Spawns: SEPARATE `(1,1)`/`(18,18)` · CLASH `(9,10)`/`(10,10)`.

#### `0x01` BLOCKS — 16 walls

Four 2×2 wall blocks, top-left corners at `(6,6)`, `(12,6)`, `(6,12)`,
`(12,12)`. The first "real" arena: cover and flanking; teaches `AHEAD`
and pathing around obstacles.

```
....................
....................
....................
....................
....................
....................
......##....##......
......##....##......
....................
....................
....................
....................
......##....##......
......##....##......
....................
....................
....................
....................
....................
....................
```

Spawns: SEPARATE `(1,1)`/`(18,18)` · CLASH `(9,9)`/`(10,9)`.

#### `0x02` RING — 20 walls

Perimeter of the rectangle `x 6..13 × y 6..13` (an 8×8 square ring) with a
2-wide gate at the middle of each side: top/bottom at `x ∈ {9,10}`,
left/right at `y ∈ {9,10}`. Interior is a 6×6 chamber. Bullets die on the
ring, so fights collapse into gate duels; center or perimeter is the
opening decision.

```
....................
....................
....................
....................
....................
....................
......###..###......
......#......#......
......#......#......
....................
....................
......#......#......
......#......#......
......###..###......
....................
....................
....................
....................
....................
....................
```

Spawns: SEPARATE `(1,1)`/`(18,18)` · CLASH `(9,9)`/`(10,9)` (inside the ring).

#### `0x03` CROSS — 28 walls

A plus-shaped cover: vertical bar `x ∈ {9,10}, y 6..13` plus horizontal bar
`y ∈ {9,10}, x 6..13`. Direct corner-to-corner fire lines are blocked;
shooting means committing to one of the four lanes around the arms.

```
....................
....................
....................
....................
....................
....................
.........##.........
.........##.........
.........##.........
......########......
......########......
.........##.........
.........##.........
.........##.........
....................
....................
....................
....................
....................
....................
```

Spawns: SEPARATE `(1,1)`/`(18,18)` · CLASH `(8,7)`/`(8,8)`.

#### `0x04` MAZE — 108 walls

Composite: (a) outer perimeter ring `x 1..18 × y 1..18` with 2-wide gates at
the middle of each side (`x ∈ {9,10}` top/bottom, `y ∈ {9,10}` left/right);
(b) solid 4×4 center block `x 8..11 × y 8..11`; (c) horizontal walls at
`y = 4` and `y = 15`, `x 5..14`, each with a 2-wide gap at `x ∈ {9,10}`;
(d) vertical walls at `x = 4` and `x = 15`, `y 5..14`, each with a 2-wide
gap at `y ∈ {9,10}`. Loop topology throughout — no dead ends, no sealed
pockets. The prestige arena: navigation and ambush; the simple presets
suffer here, by design.

```
....................
.########..########.
.#................#.
.#................#.
.#...####..####...#.
.#..#..........#..#.
.#..#..........#..#.
.#..#..........#..#.
.#..#...####...#..#.
........####........
........####........
.#..#...####...#..#.
.#..#..........#..#.
.#..#..........#..#.
.#..#..........#..#.
.#...####..####...#.
.#................#.
.#................#.
.########..########.
....................
```

Spawns: SEPARATE `(2,2)`/`(17,17)` · CLASH `(7,9)`/`(7,10)`.

### 7.4 Why fixed patterns (and what Pro adds)

Free arenas are **learnable**: the same walls every time, so players build
per-arena strategies and a shared vocabulary ("he's untouchable in BLOCKS").
Pro (future) adds *procedural* arenas — seed-derived, never the same twice —
as layout ids in the `0x10` range (v2 will carry their parameter bytes,
§13). The free/paid line is **learnable vs. unrepeatable**, which writes its
own store copy.

### 7.5 Wall rendering

Walls render as `█` in the arena view (DESIGN.md §3.1) and as `#` in the
spec/ASCII context. Wall cells block `MOVE` (silent no-op, as today), stop
projectiles (vanish), and register in `AHEAD`.

### 7.6 Spawn rules

- **SEPARATE / CLASH:** fixed points per arena (§7.3). Slot A takes the first
  point, slot B the second.
- **SCATTER:** each bot draws a uniformly random free cell from the fight
  seed; if the two are closer than Manhattan distance 6, redraw (up to 8
  tries, then accept). Fully deterministic from `seed`.

---

## 8. Results & the response flow

A **response** is the social loop: winning a challenge generates the
counter-challenge.

1. Recipient imports a CHALLENGE (canonical orientation: A open, B pinned)
   and picks their own bot (preset or stored firmware) for slot A.
2. They play. A **win** (KO, or timed with strictly higher HP) unlocks the
   **SEND BACK** action. (A DRAW or loss: no response — the chain waits.)
3. The app generates the **response code**: same `layout`, `seed`, `mode`,
   `spawn`, `hp`; `slots = 0b01`; **B = the recipient's winning firmware**
   (binary + source if ≤ 2048 B); A left open; `result` = the recorded
   outcome, remapped so the **pinned slot (B) is the winner** in the new
   orientation, with `ticks` and the loser's `end_hp` from the actual fight.
4. The original sender imports it, fills slot A with a *new* firmware, and
   the chain continues. Depth is unbounded; every hop is independently
   verifiable by anyone who replays any code in the chain.

Notes:

- `winner` is always **relative to the code's own slots**, never to people —
  the remap in step 3 is the only bookkeeping.
- A challenge may also be created **cold** (never played): `flags` bit 0
  off, or `winner = 0xFF`. The recipient's win (or loss) is the first
  recorded result for that arena+rules combo in their local history.
- Local history (per-code outcomes, per-arena win counts) is an **app
  feature, outside this format**.
- The existing `EXPORT` shell command (firmware ZIPs) is orthogonal: a
  challenge code embeds firmware inline and produces no files.

---

## 9. Free / Pro gating

### 9.1 v1 gate model: **soft gate**

Both free and Pro builds ship the **full layout generator registry** (free +
Pro). Pro arena *generators are code, not downloadable assets*, and the
content set is small enough that shipping them in the free build is
harmless. The entitlement then gates exactly two things:

1. **Playback** of a fight whose `layout` is a Pro id — the free build
   renders the arena preview and the opponent's source, and locks RUN with
   `ARENA NEEDS PRO — UNLOCK TO PLAY`.
2. The **builder menu**: Pro arena entries (and, later, Pro-only knobs) are
   hidden/locked in the free build, so a free build can never *create* a Pro
   code.

**Migration note:** if the Pro content grows beyond "a few generators"
(e.g. a large arena pack), move to a hard gate (Pro generators stripped
from the free build; Pro-layout codes show a placeholder preview instead of
the rendered arena). The **format is unaffected** — `layout` is an id into a
registry either way.

### 9.2 Import decision table

| Code contains… | Free build | Pro build |
|----------------|-----------|-----------|
| Free layout + v1 rules | Full: play, respond, re-share. | Full. |
| Pro layout (`0x10+`) | Arena preview + opponent source; playback locked: `ARENA NEEDS PRO — UNLOCK TO PLAY`. No response possible (can't play). | Full. |
| Unknown layout id | Reject: `UNKNOWN ARENA — NEWER VERSION`. | Same (a Pro build can be older than a free one's registry). |
| `fmt` or `eng` newer than the app | Reject (§5). | Same. |
| `eng` older than the app | Play with warning (§5). | Same. |
| Corrupt / malformed | Reject with the matching §10 string. | Same. |

A free build can never produce a code it cannot itself play (its builder
only offers free arenas and v1 knobs).

### 9.3 What is never gated

Creating, sharing (share sheet / clipboard / QR), importing, parsing,
previewing, disassembling, and the local history of any code — free or Pro —
always works. Entitlement checks happen **at playback and in the builder
menu**, never at parse time; a store hiccup or offline mode can therefore
never corrupt, lose, or block a code.

---

## 10. Error strings

Exact strings, terminal voice. One per failure; the first failing check in
§11 wins.

| Condition | String |
|-----------|--------|
| Missing prefix, or non-alphabet character after normalization | `NOT AN RF CODE` |
| Normalized length > 10,000 | `CODE TOO LONG` |
| base32 decode failure, or payload < 18 bytes | `CODE MALFORMED — LENGTH` |
| CRC mismatch | `CODE CORRUPT — CHECK THE COPY` |
| `fmt` > app's format version | `MADE WITH NEWER VERSION — UPDATE APP` |
| `eng` > app's engine version | `MADE WITH NEWER ENGINE — UPDATE APP` |
| `eng` < app's engine version | *(warning, playable)* `OLD ENGINE — REPLAY USES CURRENT RULES` |
| `layout` not in this build's registry | `UNKNOWN ARENA — NEWER VERSION` |
| `layout` is a Pro id, build is free | `ARENA NEEDS PRO — UNLOCK TO PLAY` |
| `slots` > `0b11`, or slots/firmware-length mismatch | `CODE MALFORMED — SLOTS` |
| Pinned `fw` length not a multiple of 3, or > 768 | `CODE MALFORMED — SIZE` |
| `mode` > 4, or `spawn` > 2, or reserved hp bits set | `CODE MALFORMED — RULES` |
| Reserved `flags` bits (3–7) set | `CODE MALFORMED — FLAGS` |
| `result.winner` not in {0, 1, 2, 0xFF} | `CODE MALFORMED — RESULT` |
| Embedded source length > 2048 | `CODE MALFORMED — SOURCE` |
| Decode scan finds an unknown opcode | `NEW OPCODES — UPDATE APP` |

---

## 11. Validation (parse order)

The importer runs, in order — first failure produces its string:

1. **Prefix:** input must start with `RF1-`; normalize (delete
   non-alphabet characters from the remainder).
2. **Length:** normalized length ≤ 10,000.
3. **Decode:** base32 → bytes; trailing partial group must be zero bits;
   payload ≥ 18 bytes.
4. **CRC:** `crc` over all preceding bytes.
5. **`fmt`:** must be ≤ the app's format version (and = 1 for v1 builds).
6. **`eng`:** §5 skew rules.
7. **`layout`:** registry lookup; entitlement check for Pro ids (§9.2).
8. **`slots`:** range + the open/pinned ↔ `fw_len` consistency rule (§2).
9. **Firmware lengths:** multiples of 3, ≤ 768, `0` only for open slots.
10. **Rules:** `mode` ≤ 4, `spawn` ≤ 2, hp reserved bits clear.
11. **`flags`:** reserved bits clear; declared blocks must be present.
12. **`result`:** present iff flag set; `winner` in {0, 1, 2, 0xFF}.
13. **Source blocks:** present iff flag set; length ≤ 2048.
14. **Decode scan:** dry-decode every pinned firmware (§4.3).
15. **Done:** build the fight descriptor (arena, rules, seeds, firmwares,
    result) for the challenge screen.

---

## 12. Acceptance criteria (definition of done)

### 12.1 Layout properties — verified at spec time

Each arena in §7.3 was checked programmatically on 2026-10-12: row/width
integrity (20×20), point symmetry, full connectivity / no sealed pockets
(BFS from the perimeter), zero dead-end cells, and spawn cells free with
CLASH adjacency. **All five pass.** Wall counts: OPEN 0 · BLOCKS 16 · RING
20 · CROSS 28 · MAZE 108. The same checks run in CI against the engine's
layout generators (engine support for walls is the implementation
dependency, §12.3).

### 12.2 Format tests (headless)

- **Round-trip:** for a corpus of generated codes (every mode × every dial
  combo × sampled firmwares), `parse(encode(x)) == x` and
  `encode(parse(s)) == s` byte-for-byte (optional fields preserved or
  absent, never altered).
- **Determinism:** the same code, simulated twice (and after
  encode→decode→re-encode), produces identical tick-by-tick worlds and
  identical outcomes.
- **Fuzz:** 10,000 random single-/multi-byte mutations of valid codes must
  each land in either a successful parse or a **defined** §10 string —
  zero crashes, zero silent misplays.
- **Size:** a max-payload code (both firmwares 768 B, both sources 2048 B,
  result present) encodes within the 10,000-character text limit.

### 12.3 Fight matrix (per arena, free and Pro)

The 5-preset × 5-preset matrix (25 ordered pairs) in both DUEL and MELEE —
the 25-pair × 2-mode matrix on each arena — 50 fights per arena, 250 total —
each must
**resolve** (KO or timed end, never the 1,000,000-tick cap) within a
**2,000-tick budget**. An arena that fails is fixed or cut. This matrix is
the enforcement of the §7.2 invariants against *actual* preset behavior;
§12.1 proves the geometry, §12.3 proves the gameplay. **Dependency:** the
engine must first support wall cells in `World.kt`/`TextGrid.kt` (movement
block, projectile absorption, `AHEAD`, rendering) — that change and this
matrix ship together.

### 12.4 Process

- The `eng` bump checklist item (§5) is a release-gate question, answered
  in the changelog of every release.
- Any change to this spec is a **spec PR**: field tables, strings, and the
  acceptance criteria change together; "v1" is only relabeled by adding a
  v2 section (§13), never by editing v1.

---

## 13. Future extensions (append-only rules)

| Future item | How it lands |
|-------------|--------------|
| New **free** arena | New `layout` id `0x05+`, generator + registry entry + §12.3 matrix. No format change. |
| **Pro arena pack** | `layout` ids `0x10+`, §9 gating. v1 codes are unaffected. |
| **Procedural** (seed-derived) arenas | v2 layout class: `fmt = 2` carries layout parameter bytes after the fixed prefix. |
| **Custom round lengths** (raw tick values) | v2 `mode` encoding; the v1 table stays reserved. |
| **Second weapon / new rule knobs** | v2 rule bytes + `eng` bump (new deterministic behavior). |
| **4-bot / tournament codes** | v2 slot scheme (N slots, generalized mask). |
| **Survival ("true gauntlet")** | v2 mode class. `0b11` remains OPEN DUEL permanently — never reused. |
| Audio, CRT, cosmetics | Never in a code. |

The invariant behind every row: **a v1 code keeps meaning exactly what this
document says it means, on every future free build, forever.**
