# GAMEPLAY-IDEAS — Ideas Backlog

> Status: **ideas backlog**, not a spec. Candidate features and modes with
> why they'd be fun, rough impact, and where they'd land. Nothing here is a
> commitment — graduation path is *idea → DESIGN.md §7 roadmap → spec (if it
> touches the wire format) → implementation with tests*.
>
> Grounded in the shipped state (v0.6-codes): challenge codes, builder, SEND
> BACK responses and local challenge history are **done** (locked spec:
> `CHALLENGE-CODES.md`); the engine ships five arenas with two terrain kinds
> — **blocks** (cell obstacles) and **edge walls** (line segments between
> cells) — see `engine/.../world/Arena.kt`.

---

## 0. Ground rules these ideas must respect

Inherited from DESIGN.md and CHALLENGE-CODES.md — an idea that breaks one of
these is not an idea, it's a redesign:

1. **Programming is the skill.** No reflex mechanics, no twitch, no timing
   minigames. Depth comes from sensors, branching, and heat economy.
2. **Determinism is sacred.** Every feature must be expressible as
   `(layout, rules, seed, firmware)` and replayable headlessly in `engine/`.
   Anything that a challenge code cannot capture is out of the core game.
3. **Mechanism free, content paid** (CHALLENGE-CODES §1, §9). New mechanics
   ship free; new arenas/opponents/weapons-as-content can be Pro.
4. **New deterministic semantics bump `eng`** (§5) and re-run the §12.3
   fight matrix for every affected arena.

---

## 1. Senses & knowledge

**THREAT — incoming-projectile sensor.** Highest-value idea in this document.
Today a bot cannot see a bullet coming: `SHIELD` is guesswork and kiting is
impossible. A port like *"nearest projectile heading toward me within n
cells"* (or a per-axis danger byte) opens the entire **dodge genre**:
timed shields, shoot-and-turn, bait-and-dodge duels. Cheap in the engine —
projectiles already exist in `World`; it is a ray scan.
*Impact: XL · Effort: S/M · New port ⇒ `eng` bump.*

**`ENEMY_DIR` — nearest enemy's facing.** Predict the enemy's firing line a
tick before the shot exists; pairs with THREAT for positioning play.
*Impact: M · Effort: S.*

**Directional scanning.** `DIST` only knows the nearest enemy, `AHEAD` only
knows one cell. Per-direction reads (nearest wall/enemy distance in each of
the four facings, or a "wall ahead at distance n" sensor) turn MAZE play
from wall-bouncing into real navigation. *Impact: M · Effort: M.*

**Considered:** a "last hit taken" port (who shot me, from where). Deferred —
THREAT + ENEMY_DIR cover most of it with less ISA bloat.

---

## 2. Terrain as gameplay

The two-terrain model (blocks + edge walls) is already general enough to
carry these — they are new *cell/edge behaviors*, not new geometry systems.

**Destructible blocks.** Blocks with 2–3 HP that projectiles chew through.
Fights reshape the arena; MAZE becomes survivable for aggressive bots.
*Impact: M · Effort: S/M.*

**Hazard & item tiles.** Spike tiles (damage on entry), **coolant vents**
(step on → heat drops fast — turns heat economy into a *positioning* game),
repair cells (one-time +HP). Cheap, and they make arena choice matter
between fights. *Impact: M · Effort: M.*

**Trap arenas (Pro pack).** §7.2's no-dead-end invariant can be relaxed for
Pro content *when a hazard/exit mechanic resolves the stall* — a dead end
with a coolant vent is a trap puzzle, not a soft-lock.

**Ice / conveyor edges** (a step slides one extra cell). Flavor, lower
priority; every movement twist makes preset behavior less predictable.

---

## 3. Weapons & heat economy

Every weapon is a **new rule knob** — it lands as a v2 code field (§13) and
an `eng` bump, not as a new ISA instruction.

**Slug.** Slower projectile (moves 1 cell every 2 ticks), 25 damage, +40
heat. Anti-spam counterweight to fast shots; punishes prediction-free
spamming. *Impact: M · Effort: M.*

**Spread.** Three-projectile fan (forward ± diagonal), 5 damage each, +40
heat. Arena control at close range; pairs with MELEE mode. *Impact: M ·
Effort: M (diagonal projectiles touch the 1-cell/tick resolution model —
design carefully).*

**Considered and shelved:** ammo economy (finite clips). Rejected — it adds
bookkeeping to the firmware without adding *decisions about the world*;
heat already is the ammo. Charged shots: deferred (multi-tick model gets
muddy fast).

---

## 4. Modes

**Weekly gauntlet.** The retention idea. Every player in a week fights the
*same* fight: `seed = week number`, fixed layout + named-mode knobs. No
server, no accounts — the weekly fight **is** a challenge code (codes already
encode everything); results propagate as SEND BACK codes and saved-code
history. Community rankings without the internet. *Impact: L · Effort: M
(mostly UI: a "this week's gauntlet" entry + local board).*

**Code golf.** Win a fixed challenge with firmware ≤ N instructions. The
format already carries `fw_len` (CHALLENGE-CODES §4.1) — a "golf" variant is
just a validator plus a badge. Rewards elegant code, which is the whole
thesis of the game. *Impact: M · Effort: S.*

**Score attack / fastest KO.** Fixed arena+seed; `result.ticks` is already in
the code — a personal/board best-time is one local query. *Impact: M ·
Effort: S/M.*

**Campaign vs AI (DESIGN M4).** Difficulty ladder where each unlock is the
opponent's **firmware for study** — the fantasy is reverse-engineering the
ladder. Natural synergy: ship each campaign opponent *as a challenge code*,
so campaign fights are shareable, verifiable, and buildable. *Impact: L ·
Effort: L.*

**Survival waves — "true gauntlet".** Environment opponents in waves. Lands
as the **v2 mode class** reserved in CHALLENGE-CODES §13; `0b11` stays OPEN
DUEL forever. *Impact: M · Effort: L.*

**Tournaments + 4-bot FFA.** Round-robin over saved bots, headless at high
speed, standings table. DESIGN already budgets 4 bots; the engine needs a
4-slot sim pass (bot order, nearest-enemy targeting already handles N bots).
*Impact: M · Effort: L.*

**Hot-seat PvP (DESIGN M5).** Same device, two editors, then watch. Fine to
defer — async challenge chains already deliver the social bragging.

---

## 5. Scoring, analysis & meta

**Efficiency score.** One number per fight: KO ticks, shots, hits, peak heat
combined (e.g. `damage / (ticks + 5·shots + peak_heat)`). Turns binary wins
into a code-review moment; feeds boards for golf/score-attack modes.
*Impact: M · Effort: S/M (display-only unless it enters code results).*

**Post-fight analysis: "why it ended."** KO vs overheat vs timeout, damage
dealt vs taken, max-heat spikes, wasted shots. The stats exist during play;
a summary screen is cheap and it's where players learn. *Impact: M · Effort:
S/M.*

**Local ladders.** Per-layout and per-saved-bot win/loss ratings across all
played codes (per-code history already ships in 0.6-codes; this generalizes
it). *Impact: M · Effort: S/M.*

**Step-debugger completion (DESIGN M5).** Memory peek (zero page + data RAM)
and line breakpoints in the execution monitor. Closes the *step, watch, fix*
loop the monitor started. *Impact: M · Effort: M.*

**Fault panel.** Surface runtime faults (stack underflow, bad `CALL`, writes
to read-only ports) as a terminal-style `FAULT` readout instead of silent
no-ops — crashing should be *legible*. *Impact: S/M · Effort: M.*

**Replay scrubber.** A code is a complete fight state; add a tick slider on
finished challenge fights (jump to tick N, watch the monitor at that state).
Mostly a Simulator re-run + UI. *Impact: M · Effort: M.*

---

## 6. Social (building on shipped codes)

**Chain bookkeeping.** SEND BACK ships; add chain depth to saved codes'
local metadata (not the wire format), a "longest chain per layout" local
board, and a badge when a challenge survives N answers. *Effort: S.*

**Challenge packs.** Bundle several codes as one share (gauntlet of five
arenas, a mini-campaign, a weekly set). Wire question: pack container
format — new spec section, v2-adjacent. *Effort: M.*

**Weekly gauntlet** (see §4) is the flagship of this section: async
community play with zero network.

---

## 7. The Circuit Saga — narrative spine (locked direction, post-P4)

> Locked 2026-10-23 with PLAN-PRO §0/§6. This is the story the **paid app**
> ("Opcode Arena: The Circuit") carries. The free app stays lore-neutral:
> max one cross-sell line in the free *listing*, nothing in the free APK.
>
> **Content pillars (paid app):** the Circuit slowly moves away from a
> battle-only focus toward **fight / collect / riddles** — fights stay the
> core loop, but UI copy, content, and later modes serve collecting (bots,
> arenas, codes) and firmware-solved riddles, not just combat.

**The reframe.** The five free arenas are training floors of a facility that
runs fights to test firmware it doesn't own. The bot's code *is* its mind.
Below the training floors lie maintenance levels, vents, locked doors — and
beyond them, the **outer world**. The free game is what the facility shows;
The Circuit is what happens when a bot stops fighting and starts looking for
the way out.

**Existing backlog items are chapters of this story:** hazard/item tiles
(§2), trap arenas gated on an exit mechanic (§2), survival/"true gauntlet"
and campaign (§4). The saga gives them an order and a name, not new scope.

**Contract (unchanged ground rules):**
- Shared *mechanics* — `EXIT` tiles, gates/pressure plates, item tiles
  (vent/repair/spikes, §2), puzzle terminals/ports — ship **free to both
  apps** with an `eng` bump (the "Walls Down" pack, PLAN-PRO §6). Everyone
  can author escape arenas in free-app codes.
- Paid *content* is the saga: authored puzzle arenas, campaign chapters,
  outer-world packs — needing the reserved **codes v2** semantics (§4,
  §13): gauntlet result fields (progress/score), not `winner/ticks/end_hp`.
- Riddles are solved **through firmware only** (principle 1): glyph
  terminals read via sensor ports, gates opening on a correctly computed
  port value, plate sequences derivable from the **seed** ("the key is in
  the seed"), memory carried across visits. No minigames, no hidden state;
  every puzzle headless-testable as `(layout, rules, seed, firmware)`.
- Teaser ceiling: one line ("the training floors are only the beginning"
  class of copy) in the free *store listing*; story content post-P4 only.

**New mechanics this adds to the backlog:** `EXIT` tile + gates/plates
(stall-resolution un-blocker), puzzle terminal (port-per-glyph sensor),
answer-gate port (write correct value → opens). All M effort individually;
together they are the saga foundation and land with codes v2.

---

## 8. Priorities

| Rank | Idea | Impact | Effort | Notes |
|------|------|--------|--------|-------|
| 1 | THREAT sensor | XL | S/M | Unlocks dodge/kiting — single biggest gameplay ceiling raiser |
| 2 | Hazard/item tiles (vent first) | M | M | Makes terrain matter between fights; coolant vent pairs with THREAT meta |
| 3 | Code golf + score attack | M | S | Uses fields the format already stores |
| 4 | Post-fight analysis + efficiency score | M | S/M | Pure app layer; high learning value |
| 5 | Weekly gauntlet | L | M | Retention engine; zero network; codes do the work |
| 6 | Destructible blocks | M | S/M | Arena reshaping; MAZE relief |
| 7 | Local ladders | M | S/M | Natural extension of shipped history |
| 8 | Slug / spread weapons | M | M | v2 rule knobs + `eng` bump — schedule with the next format work |
| 9 | Memory peek + breakpoints, fault panel | M | M | Completes the debugging loop (M5) |
| 10 | Campaign (as code-pack) | L | L | Biggest content play; leans on packs (§6) |
| 11 | Replay scrubber | M | M | Needs per-tick state re-run |
| 12 | Tournament / 4-bot FFA | M | L | Engine sim work first |
| 13 | Survival waves | M | L | Reserved as v2 mode class (§13) |
| 14 | Procedural arenas | — | M | **Pro content** (CHALLENGE-CODES §7.4) — paid pillar, not a free idea |
| 15 | Escape mechanics (exit/gates/puzzle ports) | L | M | "Walls Down" pack — saga foundation (§7); schedule with codes v2 |

---

## 9. Anti-goals — considered and rejected on principle

- **Real-time networked PvP.** Async challenge chains + weekly gauntlets give
  the same bragging rights, verifiably, with no server, no accounts, no
  matchmaking. Determinism is the moat; don't drain it.
- **Hidden randomness in the fight core.** The only RNG is the seeded `RAND`
  port and SCATTER. Any unseeded RNG silently breaks every existing code.
- **Ammo economy.** Bookkeeping without decisions (heat already is the ammo
  budget).
- **Reflex mechanics, twitch timing.** Violates principle 1 outright.
- **Gating mechanics or physics behind Pro.** CHALLENGE-CODES §9: the free
  tier owns the mechanism; only content is paid.
- **Sprite/3D rendering.** The text grid *is* the game's identity (DESIGN
  §3); chiptune and CRT (M6) polish it rather than replace it.

---

## 10. How an idea graduates

1. Prove it **headlessly first** in `engine/` (World/Simulator change +
   tests) — if it can't run in the test harness, it isn't a gameplay feature.
2. If it touches deterministic semantics → **`eng` bump** + changelog
   (CHALLENGE-CODES §5) + **§12.3 matrix** on every affected arena.
3. If it touches codes/rules → **spec change or v2 section** (§13), never an
   edit to v1.
4. Then, and only then, UI work in `app/`.
