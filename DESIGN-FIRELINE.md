# FIRELINE Visual Design (Phase 5 design doc, draft 1)

Status: design session output 2026-11-07, finalized (all open questions
resolved, see 11). Brand decisions are locked by user choice (see 1). Visual
identity LOCKED: direction F "Firecall Warm" (user selection 2026-11-07, after
the W/F side-by-side comparison in
`docs/mockups/fireline-mission-WF-compare.html`). W "Warm Industrial" was the
runner-up; P "Phosphor Terminal" was rejected as too similar to Arena. The
candidate token sets stay in this doc as reference.
Nothing in this file is committed to `main`.
This doc governs the visual layer only; all simulation facts are owned by
`FIRELINE-DESIGN.md` and `fireline-engine/` sources.

Sources used: FIRELINE-DESIGN.md sections 1, 6.3, 7.5, 9, 11, 12;
PLAN-RF8-MULTIAPP.md sections 8.4, 9; DESIGN.md section 3 (house style);
`app/src/main/kotlin/robofight/android/ArenaView.kt` (render ratios and real
hex values); `STORE-LISTINGS.md` section 4 (icon lineage);
`fireline-engine/src/main/kotlin/robofight/fireline/Missions.kt` and
`FirelineRules.kt` (real layouts and constants).

## 0. Ground facts this design builds on (verified, not invented)

- Grid is exactly 20x20 (400 cells); terrain glyphs in authored layouts:
  `.` floor, `#` wall, `F` fuel, `W` refill, `E` exit, `P` asset, `R` spawn,
  `C` civilian; fire authored levels `1..4` = intensities 25/50/75/100.
- Engine constants used in the mockups: `WATER_PER_SHOT=10`, `WATER_REFILL=10`,
  `WATER_MAX=100`, `HEAT_SHOOT=20`, `HEAT_COOL=2`, `PACKET_FIRE_REDUCE=25`,
  `SHIELD_DURATION=5`, `FIRE_SPREAD_PERIOD=5`.
- Canonical event strings (exact from engine): `FIRE -25 at 9,10 -> 0`,
  `PACKET -> 9,10`, `REFILL +10`, `PACKET SPENT WALL 12,10`,
  `CIVILIAN #3 RESCUED at 4,7`, `SHIELD ACTIVE 5`, `WATER EMPTY`,
  `ROVER FIRE DAMAGE -8 (fire 50)`, `ROVER OVERLOAD -2`, `CIVILIAN #2 LOST BY FIRE`.
- Failure reasons for the RESULT banner: `FIRMWARE_FAULT`, `ROVER_DESTROYED`,
  `CIVILIAN_LOST`, `ASSET_LOST`, `TIME_EXPIRED` (7.4 precedence order).
- Mission 02 / CORRIDOR real parameters: seed 2002, wind W force 0, starting
  water 100, timeLimit 120, objectives EXTINGUISH_ALL | REACH_EXIT, pocket fire
  at (9,10) level 1, refills at (8,10) and (10,10), exit at (11,10), wall line
  row 3 with the single gap at (2,3), blocking wall at (3,2).
- Arena render ratios (ArenaView.kt): glyph textSize 0.5x cell (bots 0.75x),
  entity labels 0.26x drawn above the cell, empty dot 0.38x, launcher background
  `#161718` (colors.xml), Arena field `#000502`, Arena idle green `#34ec5b`.

## 1. Brand identity (LOCKED by user selection 2026-11-07)

- Store name: **Opcode FIRELINE**. Rationale: keeps the "Opcode" family DNA
  while avoiding the `Opcode Arena: X` pattern that The Circuit already owns;
  FIRELINE is a sibling game (FIRELINE-DESIGN 1.2), not a content pack.
- Launcher label: **FIRELINE** (fits short-label budgets in every launcher).
- Tag line: **"The same little computer. A different job."** Used in the app
  header caption strip, store full description opening, and feature graphic.
- Honest tone line continues unchanged: "Designed by a human. Built with
  coding agents." (same as Arena listings).
- App icon concept (chosen: "Operator", retuned 2026-11-07 to the locked F
  palette): near-black `#161718` field, a single turnout-yellow `R` (`#ffd23f`)
  with a bright safety-green facing caret (`#34ec5b`), one level-2 fire digit
  (`#f2921e`) beside it, thin baseline rule in the border red-brown `#63392f`.
  Family sibling of the Arena robot icon (same composition discipline as
  STORE-LISTINGS 4, different glyph, different job).
- Internal codename `FIRELINE`, package `robofight.fireline` (already decided
  in PLAN 9.1; untouched here).
- Visual identity decision log: P rejected (too close to Arena); W preferred
  initially; F "Firecall Warm" requested for comparison and chosen as the
  final identity (2026-11-07).

## 2. Palette strategy

Shared semantic rules (apply to both candidate directions):

1. Glyph shape is the primary differentiator, color is secondary. Every entity
   keeps its distinct glyph (`R 1 2 3 4 * C E W F P #`), so a fully grayscale
   display is still a fully readable game. Color only accelerates recognition.
2. The fire intensity ramp must be monotone in luminance, both raw and under
   protanopia/deuteranopia simulation, so ordering of `1<2<3<4` survives every
   accessibility mode. Direction P ramps hot=darker, direction W ramps
   hot=brighter (white-hot); both orders are monotone.
3. Non-text information tokens must clear WCAG 3.0 contrast against both the
   field and the panel; body text tokens clear 4.5. Verified below.
4. Green never means "fire", warm never means "machine". In P the field is
   green-chromed and warm only inside the fire ramp; in W the chrome is warm
   neutral and green appears only on the rover nose, exit, and success states;
   in F (locked) the chrome is warm cream and green appears only on the facing
   caret and the exit success accents.

### 2.1 Direction P "Phosphor Terminal" (house-first; REJECTED, too close to Arena)

Arena continuity is maximal: phosphor chrome, warm fire ramp as the
differentiator, steel-gray walls to signal "industrial site" instead of Arena
green blocks.

| token | hex | role |
|---|---|---|
| field | `#161718` | launcher background, app field |
| panel | `#1b2021` | cards, telemetry panel |
| panel-2 | `#202627` | grid backdrop, highlighted rows |
| border | `#2e3739` | panel borders |
| gridline | `#232b2d` | cell borders |
| text | `#34ec5b` | chrome, system text, rover (Arena idle green) |
| text-bright | `#eaff93` | emphasized values, success, facing caret |
| text-dim | `#2f9e46` | secondary labels, keys |
| empty | `#3a4a42` | empty-cell dot (decorative only) |
| wall | `#6f767a` | solid wall block, steel gray |
| fire 1-4 | `#f8d878` `#f8b800` `#fc9838` `#fc0040` | amber to NES red |
| water / packet | `#00e8d8` | cyan |
| refill | `#00e8d8` | cyan, `W` glyph |
| civilian | `#fcfcfc` | white |
| exit | `#00e43c` | bright green |
| fuel | `#a07818` | dark-shifted olive amber (amendment A-3) |
| protected | `#fc00bc` | NES pink violet |
| RUN button | `#34ec5b` fill, `#0c1310` text | primary control |
| secondary button | `#232a2c` fill, `#cfe8d4` text | STEP / RESET |

### 2.2 Direction W "Warm Industrial" (scene-first; runner-up)

Instrument-white chrome on ash-warm panels; the field reads as a burning
industrial site; green is rationed to the rover nose, exit, and success
accents. Largest visual distance from Arena.

| token | hex | role |
|---|---|---|
| field | `#161718` | same family base |
| panel | `#211d1a` | ash-warm cards |
| panel-2 | `#26211d` | grid backdrop |
| border | `#453d35` | warm borders |
| gridline | `#332d27` | cell borders |
| text | `#e8e2d9` | instrument white chrome |
| text-bright | `#ffffff` | emphasized values |
| text-dim | `#9a8f80` | secondary labels |
| empty | `#4a443c` | empty-cell dot (decorative) |
| wall | `#7b6e5f` | warm gray solid block |
| fire 1-4 | `#7d6429` `#b06e17` `#dd5c22` `#f7ecc4` | ember to white-hot |
| water / packet | `#35d6d6` | cyan |
| refill | `#35d6d6` | cyan |
| civilian | `#f2efe9` | warm white |
| exit | `#34ec5b` | reserved green accent |
| rover | `#fcfcfc` | instrument white (allowed by 11.4) |
| facing caret / success | `#34ec5b` | reserved green accent |
| fuel | `#8d7a22` | dark olive amber (A-3) |
| protected | `#c77dff` | violet |
| RUN button | `#e8b04c` fill, `#141010` text | the one warm accent |
| secondary button | `#2c2622` fill, `#e0d7cb` text | STEP / RESET |

### 2.2b Direction F "Firecall Warm" (LOCKED, chosen 2026-11-07)

Same terminal structure and same token roles as W, but the color language is
turnout-gear firefighter: warm cream chrome, turnout-yellow rover, a fire ramp
running yellow to red, and a fire-engine red RUN button. This keeps the
"industrial firefighting terminal" pillar (no emblem or clip-art imagery; the
firefighter read is carried purely by palette).

| token | hex | role |
|---|---|---|
| field | `#161718` | same family base |
| panel | `#241a17` | ember-brown cards |
| panel-2 | `#2a1e1a` | grid backdrop |
| border | `#63392f` | red-brown borders |
| gridline | `#3a2822` | cell borders |
| text | `#f2d9b0` | warm cream chrome |
| text-bright | `#fff4dc` | emphasized values |
| text-dim | `#a87f5e` | secondary labels |
| empty | `#4a382e` | empty-cell dot (decorative) |
| wall | `#8a6d5e` | warm tan-gray solid block |
| rover | `#ffd23f` | turnout yellow |
| facing caret / success | `#34ec5b` | reserved safety green |
| fire 1-4 | `#ffb02e` `#f2921e` `#e96a2a` `#e0303c` | gold to fire-engine red |
| water / packet | `#35d6d6` | cyan |
| refill | `#35d6d6` | cyan |
| civilian | `#fff1e0` | warm white |
| exit | `#00e43c` | bright green |
| fuel | `#8d6a1e` | dark olive amber (A-3) |
| protected | `#c77dff` | violet |
| RUN button | `#e63946` fill, `#120a0a` text | fire-engine red primary |
| secondary button | `#2a1e1a` fill, `#f2d9b0` text | STEP / RESET |

### 2.3 Accessibility verification (computed, 2026-11-07)

Method: WCAG relative luminance for contrast; standard 3x3 Brettel-style
matrices for protanopia (prot) and deuteranopia (deut); "gray" = luminance x 255.
Rule: non-text >= 3.0 on both backgrounds, body text >= 4.5, ramp monotone in
all three columns.

Direction P (contrast on field / on panel; gray raw / prot / deut):

| token | cr field | cr panel | gray | prot | deut |
|---|---|---|---|---|---|
| text | 11.36 | 10.43 | 157 | 56 | 41 |
| text-bright | 16.43 | 15.08 | 232 | 218 | 215 |
| text-dim | 5.21 | 4.78 | 65 | 28 | 22 |
| wall | 3.89 | 3.57 | 45 | 43 | 43 |
| fire 1 | 12.89 | 11.83 | 180 | 198 | 207 |
| fire 2 | 10.13 | 9.29 | 138 | 170 | 184 |
| fire 3 | 8.28 | 7.60 | 111 | 151 | 171 |
| fire 4 | 4.46 | 4.09 | 54 | 66 | 99 |
| water | 11.57 | 10.61 | 160 | 43 | 29 |
| civ | 17.50 | 16.05 | 248 | 248 | 248 |
| exit | 10.40 | 9.54 | 142 | 30 | 18 |
| fuel | 4.44 | 4.07 | 53 | 65 | 70 |
| protected | 5.01 | 4.60 | 62 | 74 | 103 |

Direction W:

| token | cr field | cr panel | gray | prot | deut |
|---|---|---|---|---|---|
| text | 13.94 | 12.99 | 195 | 199 | 200 |
| text-bright | 17.95 | 16.73 | 255 | 255 | 255 |
| text-dim | 5.65 | 5.27 | 72 | 75 | 77 |
| wall | 3.62 | 3.38 | 41 | 44 | 46 |
| fire 1 | 3.19 | 2.97 | 35 | 40 | 43 |
| fire 2 | 4.35 | 4.05 | 52 | 70 | 78 |
| fire 3 | 4.81 | 4.48 | 59 | 89 | 109 |
| fire 4 | 15.17 | 14.14 | 214 | 221 | 224 |
| water | 10.04 | 9.35 | 137 | 59 | 45 |
| civ | 15.64 | 14.58 | 221 | 223 | 223 |
| exit | 11.36 | 10.59 | 157 | 56 | 41 |
| fuel | 4.22 | 3.93 | 50 | 56 | 58 |
| protected | 6.67 | 6.22 | 87 | 110 | 116 |

Direction F:

| token | cr field | cr panel | gray | prot | deut |
|---|---|---|---|---|---|
| text | 13.10 | 12.41 | 183 | 197 | 204 |
| text-bright | 16.43 | 15.57 | 232 | 240 | 243 |
| text-dim | 5.01 | 4.75 | 62 | 74 | 80 |
| wall | 3.79 | 3.59 | 44 | 51 | 54 |
| rover | 12.43 | 11.77 | 173 | 198 | 210 |
| caret | 11.36 | 10.76 | 157 | 56 | 41 |
| fire 1 | 9.83 | 9.31 | 134 | 171 | 189 |
| fire 2 | 7.61 | 7.21 | 101 | 138 | 156 |
| fire 3 | 5.61 | 5.31 | 71 | 106 | 127 |
| fire 4 | 3.98 | 3.77 | 47 | 71 | 94 |
| water | 10.04 | 9.51 | 137 | 59 | 45 |
| civ | 16.16 | 15.31 | 228 | 238 | 242 |
| exit | 10.40 | 9.85 | 142 | 30 | 18 |
| fuel | 3.60 | 3.41 | 41 | 49 | 53 |
| protected | 6.67 | 6.32 | 87 | 110 | 116 |

Fires ramp monotonicity: P raw 180>138>111>54, prot 198>170>151>66, deut
monotone. W raw 35<52<59<214, prot 40<70<89<221, deut 43<78<109<224. F raw
134>101>71>47, prot 171>138>106>71, deut 189>156>127>94. All pass.

Findings and mitigations (residual risks accepted with glyph-first rule):

- F rover yellow vs fire 1 gold: raw gray 173 vs 134, prot 198 vs 171; the
  gap is moderate and the `R` vs `1` glyph shapes carry the separation.
- F fuel vs fire 4: raw gray 41 vs 47 (nearly equal), but prot 49 vs 71 and
  deut 53 vs 94 separate them; glyph `F` vs digit carries the rest.
- F's first ramp draft failed the 3.0 gate at the hot end (dark reds on dark
  panels); the retune to gold-led `#ffb02e` with hot end `#e0303c` fixed it,
  and rover yellow was kept above the ramp in luminance on purpose.

- P rover green under CVD becomes dark yellow (56/41); adjacent to a level-4
  red cell (prot 66) the luminance gap is only 10. Mitigation A-6: rover cells
  always carry the panel-2 tile, a bold glyph, and the facing caret, so the
  rover is shape-identified regardless of color.
  (Dated correction 2026-11-25: this mitigation is the numbered entry A-5 in
  section 9 below. The number A-6 is the thin edge wall amendment applied in
  step E3, `PLAN-EDGE-WALLS.md` section 7. Annotated, not rewritten.)
- W fuel gray 50 nearly matches W fire 2 gray 52. Mitigation: `F` glyph vs
  digit glyphs, plus static terrain context (fuel never flickers, fire does).
- W exit and facing caret share one token; separation is by glyph and position.
  Optional tuning: W exit -> `#00e43c` (prot 30 vs caret 56) if the user
  chooses W. Flagged, not applied.
- P exit and water both darken strongly under CVD; glyphs `E` vs `W` vs `*`
  carry the distinction.

Grayscale mode: one toggle; every token maps to its raw gray column, all rules
above remain satisfied by glyph shape. The gray columns above are the exact
grayscale palette.

## 3. Typography (two faces: display vs work)

- Display face: Press Start 2P (OFL, already bundled by Arena; same asset
  family). Used for: wordmark, tabs, mission id line, grid glyphs, control
  button labels, RESULT banner. Never for body-size reading.
- Work face: **JetBrains Mono (OFL; LOCKED 2026-11-07, open question 7)**.
  Rationale: highest x-height of the candidates for small phone screens,
  dotted zero cleanly separates `0` from `O` in telemetry and literals, and it
  is designed for code reading. Fallback if asset metrics misfit: IBM Plex
  Mono. Used for: editor body, telemetry values, events, briefings, file
  cards. Decision: two faces, because grid legibility and code editing want
  different metrics; the pairing is new relative to Arena's single-face setup
  and is flagged as a design amendment A-4 to the house language (nothing in
  FIRELINE-DESIGN forbids it).
- Grid metrics (mirror verified ArenaView ratios): cell glyphs 0.55x cell
  (mockups use fixed 11px in 18px preview cells), rover glyph 0.75x bold,
  empty dot 0.38x, labels above cell 0.26x.
- Density, Medium Phone target (emulator-5554 class: 1080x2400 at 420dpi =
  411x914 dp): cell 19dp -> grid 380dp wide, fits with panel borders; glyph
  height approx 10.5dp = 31px. Small phones (320dp wide): cell 15dp -> 300dp
  grid, glyph 8.2dp = 24px, still readable; below that enable horizontal grid
  scroll rather than shrinking glyphs under 7dp.
- Editor: work face 13sp minimum, line height 1.5, line numbers 11sp dim.
- Control targets: RUN / STEP / RESET at >= 48dp height, >= 100dp wide,
  equal thirds of the bar.

## 4. Grid rendering language

- Fire: always digit glyphs 1-4 in the ramp color (amendment A-2; the "or
  graduated flame glyph" option in 11.4 is rejected for determinism and
  grayscale reasons). Intensity changes are visible in the digit itself, the
  most honest presentation of a simulation value.
- Optional cosmetic flicker on fire glyphs only (see section 7).
- Rover: `R` on a panel-2 tile, bold, plus facing caret. Amendment A-1: 11.4
  shows the nose in the facing cell when empty (Arena convention); when the
  facing cell is occupied (wall, refill, another entity) draw a small caret on
  the rover cell edge in the nose color instead of hiding the facing state.
- Water packets: `*` in water color, drawn only for packets in the current
  tick list; travel is tick driven, one cell per tick, never interpolated as
  motion (a packet in STEP mode teleports per tick, which is honest).
- Smoke: engine SMOKE is informational. Visual smoke is a dim tint behind the
  cell glyph at max 30% alpha on panel-2, never over a glyph, and never for
  rover, civilian, exit, refill, asset, or fuel cells. A cell may dim only when
  it holds nothing.
- Shield: a one-cell ring (thin bracket corners around the rover cell) shown
  only while SHIELD duration > 0, in the success/nose color; plus a numeric
  SHIELD readout so the ring is never the only carrier.
- Heat: no per-cell heat visuals on the grid; heat lives in telemetry as a bar
  with the overload threshold marked, avoiding clutter.
- Selection/target: the currently selected fire target (the cell the FIRE_DIST
  port points at) gets a 1px text-bright outline while any target exists;
  zero-target sentinel states render as no outline and `0` readouts.

## 5. Screen layouts (wireframes)

Portrait-first for all three surfaces; tab order FILES | MISSION | RESULT per
11.1. Small-screen rules: panels stack, controls stay pinned at the bottom,
grid always renders full width and scrolls only if below the 15dp cell floor.

### 5.1 FILES

```
+--------------------------------------+
| OPCODE FIRELINE                 RF-8 |
| FILES    (MISSION)    RESULT         |
| [ NEW FILE ]                         |
| +----------------------------------+ |
| | TANKER.ASM            42 B  18 i | |  i = instruction count
| | edited 2026-11-07   [EDIT] [>]   | |  [>] = select as mission firmware
| +----------------------------------+ |
| | CORRIDOR-REF.ASM      31 B  14 i | |
| | ...                 [EDIT] [>]   | |
| +----------------------------------+ |
| ...                                  |
| IMPORT .asm            EXPORT .asm   |  Android document/share intents only
+--------------------------------------+
```

Editor is entered from [EDIT] and is a full-screen surface of FILES (it is not
a fourth tab): line-number gutter, PC highlight, assemble errors listed with
line numbers, port cheat sheet insertable as a comment block (11.2).

### 5.2 MISSION

Layout order top to bottom: wordmark strip with tagline; tabs; mission header
card (id/name, firmware, seed, wind, briefing, objective state chips, tick /
timeLimit); grid panel with glyph legend; two-column instrument row (left: RF-8
state A X Y SP PC flags plus current source line with 2 lines of context;
right: telemetry ports: FIRE_DIST, FIRE_LEVEL, CIV_COUNT, WATER/HP/HEAT,
SHIELD, WIND, MISSION bit field hex); event panel (newest first, up to 4
visible; resolved 2026-11-07, open question 4: tapping the event panel opens
the full event log as a scrollable overlay on the MISSION surface, no separate
tab, and RESULT's WHY block links to the same log); pinned control bar RUN /
STEP / RESET.

Seed display: the seed line in the mission header card is read-only in v1
(authored missions pin their seed). Resolved 2026-11-07 (open question 6):
when sandbox mode arrives, seed editing lands inline in this same mission
header card, where the seed's meaning is visible, not in Settings.

Mission picker is the empty state of MISSION: five cards (ids 01-05) with
name, briefing line, objective chips, best result badge per engine version.
Resolved 2026-11-07 (open question 5): no `FL1-` mission-code affordance in
v1 UI at all, not even a disabled card; mission codes are post-MVP and their
wire format is not yet specified (12.3 requires a locked `FIRELINE-CODES.md`
first), so a stub would advertise an undefined feature. The MISSION empty
state shows only the five authored missions plus the firmware picker.

The mockups `docs/mockups/fireline-mission-P.html` and
`fireline-mission-W.html` render this surface at the 426px column width for
both candidate palettes, from the verified CORRIDOR tick-46 snapshot (see 8).

### 5.3 RESULT

```
+--------------------------------------+
| FAILURE: TIME_EXPIRED                |   big banner + reason chip
| MISSION 04 / DOWNWIND   tick 120/120|
| objectives: EXTINGUISH x  EXIT o    |   x unmet, o met
+--------------------------------------+
| ticks        120      water used  60|
| water left    40      shots       6 |
| HP 34   HEAT 62   SHIELD 0          |
| civilians rescued 1  waiting 1  lost 0|
| burning cells 3  total intensity 125|
| protected intact 2  lost 0          |
| firmware 47 B / 22 instructions     |
| fingerprint $7c31a90e (short form)  |   top 32 bits of the 64-bit value
+--------------------------------------+
| WHY: last events                     |
| 118 ROVER FIRE DAMAGE -8 (fire 50)  |
| 115 PACKET EXPIRED 12,7             |
| 112 CIVILIAN #2 LOST BY FIRE        |
+--------------------------------------+
| [ RETRY ]  [ EDIT FIRMWARE ]        |
| [ MISSION LIST ]                    |
+--------------------------------------+
```

Success variant swaps the banner (success color, `MISSION COMPLETE`, tick
count) and keeps the same stat block so runs are comparable. For
`FIRMWARE_FAULT` the panel adds halt reason and last executed instruction
(11.6). The last-events block is the "why did I fail" affordance; no hidden
analysis is added.

## 6. Editor treatment

- Line numbers: 4-digit zero-padded gutter, text-dim.
- PC highlight: full-width panel-2 row with a leading caret in the nose/accent
  color; during multi-tick instructions the highlight stays on the executing
  line (matches "STEP shows in-progress instruction" from the locked set).
- Mnemonic coloring classes: mnemonics in text color, FIRELINE port symbols in
  exit color, numeric literals in text-bright, comments in text-dim. All four
  classes stay distinct in grayscale via weight and position, not hue only.
- Assemble errors: red-accent line markers plus a list with line numbers,
  clickable to jump. Disassembly view: hex bytes + assembled mnemonics in the
  same editor chrome.
- Cheat sheet insert: a comment block listing ports 16-30 with meanings (from
  6.3), inserted as `; FIRELINE PORTS` so it travels inside the `.asm` file.

## 7. Motion rules

Allowed (purely cosmetic, never implying uncomputed state):
- fire glyph flicker: opacity 0.9-1.0, 0.6-1.2s random cycles; flicker remains
  in grayscale mode; disabled entirely when the user turns animations off (Android reduce-motion).
- event panel entry: newest event highlights and slides in on new tick.
- RUN pacing: a tick counter pulse; RUN itself is tick-timer driven.

Forbidden:
- any animation on rover, shield, packets, civilians (their state is computed;
  interpolating would imply sub-tick state);
- packet travel animation faster than one cell per tick;
- idle breathing/pulsing on any entity;
- background art that changes with real time rather than world tick.
Consistent with the locked rule that rotation/background must not corrupt the
sim: no visual loop may feed state back into the world.

CRT overlay (resolved 2026-11-07, open question 3): FIRELINE v1 ships the same
optional scanline overlay Arena ships (`Scanlines` view in Arena
MainActivity: non-interactive, faint dark scanlines, never intercepts focus or
touches). Settings toggle, **default off**. It is pure presentation above the
grid and chrome: it must never alter glyph colors used for state reading, must
stay off the editor text surface, and must not affect layout metrics or the
sim in any way. Off by default because FIRELINE's differentiation is its warm
palette and dense telemetry, not CRT nostalgia.

## 8. Mockups (deliverable of this session)

- `docs/mockups/fireline-mission-P.html` direction P, MISSION run screen.
- `docs/mockups/fireline-mission-W.html` direction W, identical layout,
  identical snapshot, different identity.
- `docs/mockups/fireline-mission-F.html` direction F, same snapshot.
- `docs/mockups/fireline-mission-WF-compare.html` W and F side by side in one
  frame for the final identity decision.
- Both show MISSION 02 / CORRIDOR tick 46 (post-shot, pocket extinguished,
  rover parked on the (8,10) refill cell, `MISSION=$02` because the
  extinguished bit is set and exit is not reached), exact briefing text from
  Missions.kt, real event strings, and values consistent with the section 0
  constants. Register/PC line numbers are illustrative; entity positions,
  terrain, ports, and limits are real.
- `docs/mockups/gen_mockups.py` regenerates both files; palettes live in one
  dict each so re-judging a tuned palette costs one run.

## 9. Amendments proposed to FIRELINE-DESIGN.md 11.4 (explicit, not applied)

- A-1: facing caret drawn on the rover cell edge when the facing cell is
  occupied (extends the "nose in facing cell" convention, which cannot render
  against walls/refills/entities).
- A-2: fire glyphs are always digits 1-4; the graduated-flame-glyph alternative
  is dropped for determinism and grayscale readability.
- A-3: fuel amber is dark-shifted olive amber in both directions so it never
  collides with the low end of the fire ramp; glyph `F` unchanged.
- A-4: two-face typography (Press Start 2P display + IBM Plex Mono work)
  introduced; 11.4 is silent on faces, DESIGN.md house style keeps one face,
  so this is flagged rather than assumed.
- A-5: rover identity tile (panel-2 background + bold glyph) as the grayscale
  and CVD backstop.
- A-6 (APPLIED 2026-11-25, steps E3/E4, `docs/EDGE-E3-RECORD.md` and
  `docs/EDGE-E4-RECORD.md`): the thin edge wall is a border segment, not a
  glyph. A blocked edge is painted as a short thick line from one lattice
  intersection to the next, in the existing wall token, over the open cell
  borders: Android `strokeWidth = cell * 0.14` floored at 2 px (the Arena
  `EdgeWalls`/`ArenaView` recipe, `view/EdgeWallGeometry.kt`), web the same
  ratio rounded to whole even pixels with a 4 px touch floor (`.wallseg`). A
  filled wall cell (`#`) and a wall line between two open cells are two visibly
  different things; no new glyph enters the grid language and `TextGrid.glyphAt`
  stays the single glyph authority. Both grids add the legend entry
  `THIN WALL   wall, blocks the way` with a short bar key.
No amendments change simulation behavior, ports, or objectives.

## 10. Icon and store presence (for Phase 6, prepared here)

- Launcher icon: "Operator" concept from section 1; adaptive icon layers:
  foreground R + caret + fire digit, background `#161718`.
- Screenshot composition (4-5 shots): 1 MISSION grid mid-CORRIDOR with the
  event ticker mid-FIRE readout; 2 editor with a visible IN FIRE_LEVEL loop;
  3 a RESULT failure screen showing the explanation value proposition;
  4 HOT ZONE shield crossing; 5 FILES with imported `.asm`. All captured from
  emulator-5554 in the final locked palette, not from mockups.
- Listing tone: same honest voice as STORE-LISTINGS, short description under
  80 chars built around "program a firefighting rover"; keep the family line
  and the "Designed by a human. Built with coding agents." disclosure.

## 11. Open questions — ALL RESOLVED 2026-11-07

1. RESOLVED 2026-11-07: final identity is direction F "Firecall Warm" (user
   choice). P rejected for Arena similarity, W kept as runner-up reference.
2. RESOLVED 2026-11-07: the W-specific retune is moot. F needs no palette
   retune; its one residual (fuel vs fire-4 equal gray) is covered by glyph
   shape plus the prot/deut separation shown in 2.3.
3. RESOLVED 2026-11-07: CRT overlay ships in FIRELINE v1 as an optional
   settings toggle, default off (see section 7 constraints).
4. RESOLVED 2026-11-07: tap the event panel to open the full event log
   overlay; no long-press, no static-only ticker (see 5.2).
5. RESOLVED 2026-11-07: `FL1-` mission-code affordance omitted entirely from
   v1 UI; not even a disabled placeholder (see 5.2).
6. RESOLVED 2026-11-07: sandbox seed editing will live inline in the MISSION
   header card; v1 shows the seed read-only (see 5.2).
7. RESOLVED 2026-11-07: work face is JetBrains Mono, IBM Plex Mono as
   fallback (see section 3).

Nothing remains open for Phase 5 implementation from the visual design side.
