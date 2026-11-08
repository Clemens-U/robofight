# RF-8: FIRELINE — Design Specification

> Status: **approved direction; implementation specification for MVP**
> Target: separate offline Android app plus pure-Kotlin headless engine
> Shared platform: `RF8-PLATFORM-SPEC.md`
> Repository migration: `PLAN-RF8-MULTIAPP.md`

RF-8: FIRELINE is a programming game about an autonomous emergency-response
rover. The player writes RF-8 firmware, sends the rover into a deterministic
burning environment, and watches the program navigate, suppress fire, rescue
civilians, and reach safety.

The skill is programming, not reflexes. There are no direct driving controls
while a mission runs.

---

## 1. Product identity

### 1.1 Fantasy

An RF-8 controller has been installed in a compact firefighting rover. Buildings,
industrial yards, and forest facilities are already burning. The rover receives
simple sensors rather than a map-solving AI. Its behavior comes entirely from
the player's firmware:

```text
SENSE -> PRIORITIZE -> NAVIGATE -> SUPPRESS -> RESCUE -> EXIT
```

The same little computer that drives an Arena robot now drives a different
machine. `MOVE`, `TURN`, `SHOOT`, `SHIELD`, registers, branches, memory, heat,
and timing remain recognizable, but combat is no longer the objective.

### 1.2 Positioning

FIRELINE is a sibling game, not an Arena expansion or paid content pack.

- Separate Android package and store listing.
- Same RF-8 ISA and firmware language.
- Separate deterministic world engine and mission format.
- Firmware source can move between products.
- Arena challenge codes and FIRELINE missions are not interchangeable.

### 1.3 Design pillars

1. **Programming is the action.** All meaningful decisions are expressed in
   RF-8 firmware.
2. **The environment is the opponent.** Fire propagation, limited water,
   paths, wind, civilians, and time create pressure without another combat bot.
3. **Determinism is visible.** A mission can be stepped, replayed, tested
   headlessly, and reconstructed from its descriptor.
4. **Old firmware does something legible.** Arena code using the primary-target
   ports can detect and approach fire, even if it cannot solve sophisticated
   missions.
5. **Small sensors create deep behavior.** The rover is not given a pathfinder.
   Players build navigation, prioritization, state, and recovery logic.
6. **Failure teaches.** Results explain whether the rover burned, a civilian
   was lost, time expired, water ran out, or the firmware faulted.

### 1.4 Anti-goals

- No direct touch steering during simulation.
- No reaction-time or rhythm mechanics.
- No hidden nondeterministic fire behavior.
- No new FIRELINE-only opcode.
- No realistic fire-science claim; rules are an understandable game model.
- No inventory crafting, character progression, gacha, ads, analytics, or
  online account in MVP.
- No requirement to defeat a hostile robot in MVP.
- No reuse of Arena `RF1` codes for missions.

---

## 2. MVP scope

MVP includes:

- one programmable rover;
- 20x20 authored mission layouts;
- floor, wall, fuel, refill, exit, and protected-objective terrain;
- dynamic fire intensity and deterministic spread;
- a water/suppressant tank and moving water packets;
- static civilians rescued by contact;
- wind direction and force;
- the approved two-tick/five-tick thermal shield;
- composable mission objectives and explicit failure reasons;
- at least five authored training missions;
- source editor/file storage;
- mission picker;
- `RUN`, `STEP`, `RESET`;
- live execution monitor and telemetry;
- headless tests and deterministic replay within the same engine version.

Not required for MVP:

- procedural layouts;
- mission sharing codes;
- moving civilians;
- doors, terminals, pumps, or output-port interactions;
- multiple rovers;
- destructible walls or structural collapse simulation;
- diagonal movement or diagonal projectiles;
- smoke obscuring sensors;
- campaign story presentation;
- Android UI code shared with Arena.

---

## 3. World representation

### 3.1 Grid and directions

- Grid size is `20x20`.
- Coordinates are zero-based: `(0,0)` is top-left.
- Direction encoding is shared with Arena:

  ```text
  0 = west
  1 = north
  2 = east
  3 = south
  ```

- Movement and water packets are four-directional.
- Distances exposed by target sensors are Manhattan distances.
- When an angle points to a target not on the same row/column, choose the
  horizontal axis when `abs(dx) >= abs(dy)`; otherwise choose the vertical
  axis. Return the clockwise delta from current facing.

### 3.2 Static terrain

Every cell has exactly one terrain kind:

| ID | Glyph | Name | Passable | Burnable | Effect |
|---:|:-----:|------|:--------:|:--------:|--------|
| 0 | `.` | floor | yes | yes | Normal cell; ignition threshold uses base chance. |
| 1 | `#` | wall | no | no | Blocks rover, water, and line drawing. |
| 2 | `F` | fuel | yes | yes | Easier to ignite; fire grows faster. |
| 3 | `W` | refill | yes | no | Restores 10 water after each resolution tick occupied, capped at 100. |
| 4 | `E` | exit | yes | yes | Used by `REACH_EXIT`; may burn but remains identifiable. |
| 5 | `P` | protected asset | no | yes | Used by `PROTECT_UNTIL`; mission fails if its fire intensity reaches 100. |

Terrain identifiers returned by `IN TILE` are the numeric IDs above. A civilian
does not change the terrain ID.

#### Thin edge walls (engine version 2, 2026-11-08)

Keep full-cell `#` blocks and add thin walls between adjacent cells, using
Arena's coordinate convention. An edge wall does not occupy either cell or
change its `TILE` value. The outer grid boundary remains implicit.

- Vertical edges lie RIGHT of `(x,y)`, `x=0..18`, `y=0..19`, stored row-major
  at `y*19+x` (380 booleans).
- Horizontal edges lie BELOW `(x,y)`, `x=0..19`, `y=0..18`, stored row-major
  at `y*20+x` (380 booleans).
- Edges block crossing in both directions: rover MOVE, initial water emission,
  in-flight packets, and fire ignition across that edge. A blocked MOVE retains
  its two-tick cost; a blocked shot still consumes its normal water/heat/ticks.
- `AHEAD` returns 1 across an edge. Target distance/angle sensors retain their
  geometric Manhattan/delta behavior, including targets behind walls. Smoke
  retains the existing local-neighbor formula; walls do not add smoke transport
  simulation or visibility occlusion.
- Spread still consumes two random bytes per burning snapshot cell BEFORE
  testing wall geometry. An edge prevents ignition, not growth of existing fire.
- Reachability validation follows passable cells through open edges, including
  gaps in wall segments. Constructor inputs and returned edge arrays are copied.
- Draw edges as thin lines on grid boundaries, distinct from solid block tiles.
  Headless output uses a 41x41 cell-and-gutter picture when thin walls exist.

`Layout.decode` accepts optional `verticalEdgeRows` (20 strings of 19 chars,
`.` open / `|` wall) and `horizontalEdgeRows` (19 strings of 20 chars,
`.` open / `-` wall). Both default to empty geometry. Encoding methods round-trip
these maps separately from the unchanged terrain/fire maps. For example a `|`
at vertical map row 2, column 2 separates cells `(2,2)` and `(3,2)`; a `-` at
horizontal row 2, column 2 separates `(2,2)` and `(2,3)`.

The canonical hash includes both 380-bit maps after terrain and before fire.
FIRELINE_ENGINE_VERSION is 2; ISA/profile port numbers and Arena are unchanged.
Version-1 stored results remain readable, but their fingerprints/best records
must not be compared as version-2 runs.

### 3.3 Dynamic cell state

Each cell additionally stores fire intensity `0..100`.

- Zero means not burning.
- Positive values are clamped to `1..100`.
- Authored starting intensity SHOULD use `25`, `50`, `75`, or `100`, but the
  engine supports every integer value.
- Fire does not make a passable cell impassable.
- Fire state is part of the deterministic world, not terrain identity.

Smoke is derived rather than independently simulated in MVP:

```text
SMOKE = min(255,
    LOCAL_FIRE +
    sum(fire intensity of west/north/east/south neighbors) / 2)
```

Integer division truncates. Off-grid and wall neighbors contribute zero.
Smoke is informational only in MVP: it does not change visibility, damage, or
movement.

### 3.4 Civilians

A civilian has:

- stable mission-local ID;
- cell position;
- HP `0..100`, starting at 100 unless authored otherwise;
- status `WAITING`, `RESCUED`, or `LOST`;
- `required` flag.

Civilians do not block movement or water. When the rover finishes a successful
`MOVE` on a `WAITING` civilian's cell, rescue happens automatically during the
same tick's interaction phase. The civilian becomes `RESCUED` and is removed
from the grid.

A waiting civilian on a burning cell takes the same environmental fire damage
formula as an unshielded rover. Civilians have no shield. At zero HP they become
`LOST` and are removed. Losing any required civilian immediately fails a
mission that requires `RESCUE_ALL`.

### 3.5 Rover

The rover starts with:

- HP: 100;
- heat: 0;
- water: mission-configured, default 100;
- shield duration: 0;
- facing and position from the mission descriptor;
- clean RF-8 VM state loaded with the selected firmware.

The rover blocks no other entity because MVP has only one rover. A failed move
is a silent no-op after its normal two-tick instruction cost.

### 3.6 Water packets

`SHOOT` represents the suppressant nozzle:

- instruction cost: three ticks;
- successful discharge consumes 10 water;
- successful discharge adds 20 heat;
- on the final instruction tick, a packet is created in the adjacent cell in
  front of the rover if that cell is in bounds and not blocked by a wall;
- creation checks that adjacent cell for fire immediately but does not also
  advance a second cell on the creation tick;
- on later ticks the packet advances one cell during water resolution;
- maximum life is 20 resolution ticks;
- a wall or boundary consumes the packet;
- the first burning cell contacted loses 25 intensity and consumes the packet;
- intensity at or below zero becomes zero;
- civilians, exits, refills, and the rover do not consume packets.

If water is below 10 when `SHOOT` completes, no packet is created, no water is
removed, and no heat is added. The instruction still occupies its three ticks.
Heat lockout is checked when `SHOOT` begins and retains the shared one-tick
locked-no-op behavior.

---

## 4. Fire model

### 4.1 Cadence

Fire growth/spread occurs every five world ticks, when `tick % 5 == 0`, after
rover action, water resolution, interactions, and current-tick exposure damage.

The spread step uses a snapshot of cells burning at the beginning of the spread
step, traversed in row-major order. A cell ignited during this spread step does
not itself grow or spread until a later spread step.

### 4.2 Growth

Each snapshot burning cell grows before attempting spread:

- floor, exit: `+5` intensity;
- fuel, protected asset: `+10` intensity;
- refill is non-burnable;
- maximum 100.

### 4.3 Separate deterministic RNG

FIRELINE uses separate 31-bit LCG states for sensor randomness and fire spread:

```text
next(state) = (state * 1103515245 + 12345) & 0x7FFFFFFF
byte(state) = (state >> 16) & 0xFF
```

Initial states are derived using 32-bit two's-complement arithmetic:

```text
sensorState = (seed xor 0x52463853) & 0x7FFFFFFF  // "RF8S"
spreadState = (seed xor 0x46495245) & 0x7FFFFFFF  // "FIRE"
```

`IN RAND` consumes only `sensorState`. Fire evolution consumes only
`spreadState`. Layout generation, if added later, must use a third documented
stream. Any change to generator constants, salts, draw count, or traversal
order bumps `FIRELINE_ENGINE_VERSION`.

### 4.4 Spread attempt

Each snapshot burning cell consumes exactly two spread bytes, even if all its
neighbors are blocked. This fixed draw count prevents geometry from shifting
the RNG stream unexpectedly.

1. Build this direction bag in order:

   ```text
   [W, N, E, S] + WIND_DIR repeated WIND_FORCE times
   ```

2. Select `bag[directionByte % bag.size]`.
3. Inspect that neighboring cell.
4. Determine ignition threshold:

   | Target terrain | Base threshold out of 256 |
   |----------------|--------------------------:|
   | floor / exit | 64 |
   | fuel / protected asset | 160 |
   | wall / refill / off-grid | 0 |

5. If selected direction equals `WIND_DIR`, add `16 * WIND_FORCE` to the
   threshold, capped at 255.
6. If the target is burnable, currently at intensity zero, and
   `chanceByte < threshold`, ignite it at intensity 25.

An already-burning target is unchanged by the spread attempt beyond its own
growth when visited in the snapshot.

### 4.5 Exposure damage

After rover/water actions but before the five-tick spread step, an entity on a
burning cell receives:

```text
damage = ceil(fireIntensity / 25) = (fireIntensity + 24) / 25
```

This yields 1-4 HP per world tick.

- An active rover shield blocks all fire exposure damage for that tick.
- It does not reduce the cell's intensity.
- Civilians always take exposure damage.
- Heat-overload damage is separate and not blocked by the shield.

---

## 5. RF-8 body semantics

FIRELINE implements `RF8-PLATFORM-SPEC.md` without new opcodes.

| Instruction | FIRELINE effect |
|-------------|-----------------|
| `MOVE` | Two ticks; attempt one forward cell on completion. Rescue by contact occurs afterward. |
| `TURN L/R/#n` | Change facing using shared encoding. |
| `SHOOT` | Three-tick suppressant discharge described in section 3.6. |
| `SHIELD` | Two-tick thermal-shield activation; five protected resolution ticks; +20 heat. |
| `WAIT` / `NOP` | No physical action. |
| `IN` / `OUT` | Shared port bus. FIRELINE v1 defines no scenario-specific writable output port. |

Heat behavior remains shared:

- maximum 100;
- successful `SHOOT` or `SHIELD` adds 20;
- continuation ticks of multi-tick physical actions suppress cooling;
- a successful heat action also suppresses cooling on its completion tick;
- otherwise heat cools by 2 per tick, so a final `MOVE` tick or dry `SHOOT`
  completion can cool;
- an action that would raise heat above 100 is locked;
- above 80, heat overload accumulates 2 HP/second at 10 ticks/second;
- overload damage is applied in whole HP with fractional accumulation.

The shield blocks environmental fire exposure, not heat-overload damage.

---

## 6. Port semantics

The official numbers are fixed by `RF8-PLATFORM-SPEC.md`. This section defines
FIRELINE values and target selection.

### 6.1 Target selection

Selected fire is the burning cell ordered by:

1. smallest Manhattan distance from rover;
2. highest fire intensity;
3. row-major position (`y`, then `x`).

Selected civilian is a required `WAITING` civilian ordered by:

1. smallest Manhattan distance;
2. lowest HP;
3. row-major position;
4. stable civilian ID.

Walls and smoke do not hide targets in MVP. These are facility beacon sensors,
not visual line-of-sight sensors.

### 6.2 Base ports

| # | Symbol | FIRELINE value |
|---:|--------|----------------|
| 0 | `HP` | Rover HP. |
| 1 | `DIR` | Facing; writes set facing to `A & 3`. |
| 2 | `RX` | Rover X. |
| 3 | `RY` | Rover Y. |
| 4 | `DIST` | Selected-fire distance, or 0 when no fire exists. |
| 5 | `ANGLE` | Selected-fire turn delta, or 255 when none. |
| 6 | `FIRE` | 0; reserved. |
| 7 | `SHIELD` | 1 while shield duration is positive, else 0. |
| 8 | `HEAT` | Rover heat. |
| 9 | `ENEMY_HP` | Selected fire intensity, or 255 when none. |
| 10 | `RAND` | Next byte from sensor RNG. |
| 11 | `AHEAD` | 1 for boundary/wall/protected asset ahead, otherwise 0. Civilians and fire do not block. |

This mapping lets a simple Arena hunter approach and spray the selected fire.

### 6.3 Discovery and FIRELINE ports

| # | Symbol | FIRELINE value |
|---:|--------|----------------|
| 12 | `PROFILE` | 1 |
| 13 | `PROFILE_REV` | 1 |
| 14 | `CAPS` | `$7F` |
| 16 | `FIRE_DIST` | Same target/distance as base `DIST`; 0 if none. |
| 17 | `FIRE_ANGLE` | Same as base `ANGLE`; 255 if none. |
| 18 | `FIRE_LEVEL` | Selected intensity; 0 if none. |
| 19 | `CIV_DIST` | Selected-civilian distance; 0 if none. |
| 20 | `CIV_ANGLE` | Selected-civilian turn delta; 255 if none. |
| 21 | `CIV_COUNT` | Number of required waiting civilians; rescued/lost excluded. |
| 22 | `WATER` | Rover water `0..100`. |
| 23 | `LOCAL_FIRE` | Intensity at rover position. |
| 24 | `SMOKE` | Derived value from section 3.3. |
| 25 | `WIND_DIR` | Mission wind direction. |
| 26 | `WIND_FORCE` | Mission wind force `0..3`. |
| 27 | `EXIT_DIST` | Distance to exit; 0 if no exit or currently on it. |
| 28 | `EXIT_ANGLE` | Turn delta to exit; 255 if no exit. |
| 29 | `TILE` | Terrain ID beneath rover. |
| 30 | `MISSION` | Bit field below. |

`MISSION` bits:

| Bit | Mask | Meaning while read |
|----:|-----:|--------------------|
| 0 | `$01` | All required civilians are rescued. |
| 1 | `$02` | All fire is extinguished. |
| 2 | `$04` | Rover is currently on the exit. |
| 3 | `$08` | Survival timer, if any, has elapsed. |
| 4 | `$10` | A required civilian has been lost. |
| 5 | `$20` | A protected asset has failed. |
| 6 | `$40` | Mission objectives are currently satisfied. |
| 7 | `$80` | Mission is finished. |

When a mission has no exit, `EXIT_DIST=0` and `EXIT_ANGLE=255`. Firmware should
use `PROFILE`/`CAPS` or mission knowledge rather than treating distance zero
alone as proof that an exit exists.

---

## 7. Mission descriptor and objectives

### 7.1 Deterministic tuple

A complete run is determined by:

```text
FIRELINE_ENGINE_VERSION
mission/layout definition
objective mask and rule values
seed
firmware binary
```

The headless construction path is the authority. The Android app must call the
same builder/runner; it may not reconstruct world rules independently.

### 7.2 Descriptor model

The pure engine should expose an immutable equivalent of:

```kotlin
data class MissionDescriptor(
    val engineVersion: Int,
    val missionId: Int,
    val seed: Int,
    val objectives: Int,
    val timeLimit: Int?,
    val surviveUntil: Int?,
    val startingWater: Int,
    val windDir: Int,
    val windForce: Int,
    val firmware: ByteArray,
)
```

Authored layout content supplies terrain, starting fires, rover spawn/facing,
civilians, exit, and protected assets. Validate all ranges before constructing
the world.

### 7.3 Objective mask

Composable objective flags:

| Objective | Meaning |
|-----------|---------|
| `EXTINGUISH_ALL` | No cell may remain at positive fire intensity. |
| `RESCUE_ALL` | Every required civilian must be rescued; losing one is immediate failure. |
| `REACH_EXIT` | Rover must occupy the exit after all other required completion conditions are true. |
| `SURVIVE_UNTIL` | World tick must reach `surviveUntil` with rover and protected assets intact. |
| `PROTECT_ASSETS` | No protected asset may reach fire intensity 100. |

At least one objective is required. `REACH_EXIT` may be combined with the
others; when combined, reaching the exit early does not complete the mission.

### 7.4 End conditions and precedence

Evaluate after all world resolution for the tick in this order:

1. Firmware/VM fault -> failure `FIRMWARE_FAULT`.
2. Rover HP zero -> failure `ROVER_DESTROYED`.
3. Required civilian lost while `RESCUE_ALL` -> failure `CIVILIAN_LOST`.
4. Protected asset failed while `PROTECT_ASSETS` -> failure `ASSET_LOST`.
5. All configured objectives satisfied -> success.
6. Hard `timeLimit` reached without completion -> failure `TIME_EXPIRED`.

Completing the objectives on the final allowed tick is therefore a success.

If multiple failures occur in one tick, the order above selects the reported
primary reason; the full final state remains available for analysis.

### 7.5 Results

`FirelineResult` records at least:

- status `SUCCESS` or `FAILURE`;
- failure reason, if any;
- final tick;
- rover HP and heat;
- water remaining and water used;
- civilians rescued, waiting, and lost;
- number of burning cells and sum of fire intensity;
- protected assets intact/lost;
- firmware byte and instruction counts;
- deterministic final-state hash for tests/replays.

MVP does not collapse these into an opaque global score. Mission comparison is:

1. success before failure;
2. more required civilians rescued;
3. fewer required civilians lost;
4. fewer remaining fire-intensity points;
5. fewer ticks for successful runs;
6. higher rover HP;
7. more water remaining.

Code-golf ranking by firmware size is a separate displayed category.

---

## 8. Authoritative tick order

One call to `FirelineWorld.tickOnce()` performs exactly:

1. If finished, return without mutation.
2. Increment world tick.
3. Publish the 24-bit tick counter to rover zero page.
4. Clear per-tick event lists and `heatedThisTick`.
5. Advance the RF-8 VM one tick. Multi-tick instructions remain highlighted.
6. Apply any physical side effect completing this tick (`MOVE`, `SHOOT`, or
   `SHIELD`).
7. Resolve contact interactions caused by movement: rescue civilian, note
   current exit/refill occupancy.
8. Resolve existing and newly created water packets. A newly created packet
   checks only its spawn cell this tick.
9. Apply refill effect (`+10` water, cap 100) if rover occupies `W`.
10. Apply fire exposure damage to rover and waiting civilians using the fire
    state after water resolution.
11. Apply heat cooling/overload rules. Cooling is suppressed for a heat action
    in progress or completed this tick; shield activity alone does not suppress
    cooling after activation.
12. If `tick % 5 == 0`, run growth and spread from a row-major snapshot.
13. Resolve any protected asset reaching intensity 100.
14. Evaluate mission failure/success in section 7.4 order.
15. Decrement active shield duration after all shieldable world resolution.
16. Publish the tick event log/result snapshot to observers.

The shield activates during step 6 and therefore protects step 10 on its second
activation tick. It is decremented only in step 15, producing exactly the
five-tick window in the shared platform specification.

Changing this order is a FIRELINE engine-version bump.

---

## 9. Authored MVP missions

Ship at least these five deterministic training missions. Exact wall maps are
content work, but each must pass the stated teaching and validation contract.

Engine-2 layout revision (2026-11-08), explicitly superseding the original
geometry under the same five mission IDs:

- NOZZLE CHECK: thin north/south fire-pocket edges, retaining the solid backstop.
- CORRIDOR: thin gated cross-map partition and pocket edges, retaining the solid
  obstacle immediately ahead of spawn. Gate `(2,2)->(2,3)` stays open.
- FIRST RESPONSE: thin room partitions beside the rescue route, with open ends
  preserving access to both civilians and the exit.
- DOWNWIND: thin north/south containment edges along the fuel lane; refill cells
  still bound its ends and the paired fuel cells can still ignite each other.
- HOT ZONE: thin continuous corridor edges above/below the burning lane, retaining
  the solid west stop. The civilian, refill pockets, exit, and protected asset
  remain authored at their existing positions.

All reference programs, mission seeds/objectives, and documented failing programs
retain their teaching contracts; determinism and seed-sweep tests cover the new
geometry. Existing user firmware remains source-compatible, though outcomes may
change with the versioned geometry.

### 9.1 `01 / NOZZLE CHECK`

- Objective: `EXTINGUISH_ALL`.
- Open layout, one stationary fire, full water, no civilians, no wind.
- Teaches `DIST`, `ANGLE`, `SHOOT`, and heat.
- Reference starter firmware must succeed.

### 9.2 `02 / CORRIDOR`

- Objectives: `EXTINGUISH_ALL | REACH_EXIT`.
- Walls force use of `AHEAD`, turning, and state.
- No spread until at least tick 25 or wind force zero with isolated fuel.
- A naive always-forward program must fail to finish.

### 9.3 `03 / FIRST RESPONSE`

- Objectives: `RESCUE_ALL | REACH_EXIT`.
- Two civilians, small fires, generous time.
- Teaches `CIV_COUNT`, `CIV_ANGLE`, automatic contact rescue, and exit sensors.

### 9.4 `04 / DOWNWIND`

- Objectives: `RESCUE_ALL | EXTINGUISH_ALL | REACH_EXIT`.
- Wind force 2 or 3 and fuel tiles make prioritization visible.
- At least one viable solution must use or benefit from `WIND_DIR`/`WIND_FORCE`.

### 9.5 `05 / HOT ZONE`

- Objectives: `RESCUE_ALL | PROTECT_ASSETS | REACH_EXIT`.
- Route crosses intense fire; thermal shield is required by the reference
  solution.
- Includes a refill station and starting water below 100.
- Teaches the two-tick activation delay and five-tick active window.

Every authored mission requires:

- immutable ID and name;
- ASCII source representation or explicit cell arrays in version control;
- validation: one rover spawn, valid exit when required, reachable passable
  regions, no civilian in a wall, rule ranges, and at least one objective;
- reference firmware that succeeds deterministically;
- at least one documented intentionally failing firmware;
- headless maximum-tick guard.

Changing an in-use mission layout under the same ID is an engine/content
compatibility change. Prefer allocating a new mission ID unless correcting a
release-blocking error with the appropriate version bump.

---

## 10. Starter firmware

The default source should be simple, readable, and deliberately incomplete for
later missions. A suitable behavioral outline is:

```asm
; RF-8: FIRELINE starter
; Approach the selected fire, avoid walls, spray when close.

main:  IN     FIRE_DIST
       JZ     done
       CMP    #3
       JLE    spray

       IN     AHEAD
       JZ     face
       TURN   R
       JMP    main

face:  IN     FIRE_ANGLE
       CMP    #255
       JE     main
       JZ     advance
       TURN   R
       JMP    main

advance:
       MOVE
       JMP    main

spray: IN     FIRE_ANGLE
       JZ     fire
       TURN   R
       JMP    spray

fire:  SHOOT
       JMP    main

done:  WAIT
       JMP    done
```

The shipped version must be assembled and exercised against mission 01 in a
test. The cheat sheet should label base ports separately from FIRELINE
peripherals and state the zero/255 no-target sentinels.

---

## 11. Android application design

### 11.1 Navigation

FIRELINE has three top-level tabs or equivalent destinations:

1. **FILES** — firmware library and editor.
2. **MISSION** — mission selection and simulation.
3. **RESULT** — latest outcome and analysis.

Do not expose Arena bot slots, paid Arena packs, SEND BACK, or Arena challenge
terminology.

### 11.2 Editor

Required operations:

- new, edit, save, rename, duplicate, delete;
- assemble with line-specific errors;
- import/export plain UTF-8 `.asm`;
- insert or view a generated RF-8/FIRELINE port cheat sheet;
- preserve unsaved text across normal Activity recreation;
- select the saved firmware used by the mission runner.

The database and preferences are FIRELINE-private. Cross-app transfer uses
explicit files/share intents, not private-data access.

### 11.3 Mission setup

Show:

- mission name and objectives;
- authored briefing;
- selected firmware;
- seed (fixed by authored mission by default, editable in sandbox mode later);
- wind, starting water, civilians, and time constraints;
- `RUN`, `STEP`, and `RESET`.

MVP does not need a complex mission-builder UI.

### 11.4 Run screen

The primary grid remains a chunky ASCII/text presentation. Suggested glyphs:

| Entity | Glyph/color |
|--------|-------------|
| Rover | `R` plus facing nose; phosphor green or white |
| Low/medium/high fire | `1`, `2`, `3`, `4` or graduated flame glyph/color |
| Water packet | `*` in cyan/blue |
| Civilian | `C` in white |
| Exit | `E` in bright green |
| Refill | `W` in cyan |
| Fuel | `F` in amber |
| Protected asset | `P` in violet/white |
| Wall | `#` or solid block |

Avoid relying on color alone; glyph/intensity must remain readable in
grayscale and for color-vision deficiencies.

Below or beside the grid show:

- current/next source line;
- `A X Y SP PC` and flags;
- `HP`, `HEAT`, `WATER`, `SHIELD`;
- selected fire distance/intensity;
- civilian count;
- tick, wind, and objective state;
- latest event, such as `FIRE +25`, `CIVILIAN RESCUED`, `SHIELD ACTIVE`, or
  `WATER EMPTY`.

Readability amendment (2026-11-08): instrument overview uses 11sp monospace,
event rows use 12sp, and tapping the instrument panel opens a scrollable 14sp
snapshot of registers, source context, and telemetry. Mission picker cards retain
compact typography with 11sp descriptions, 10sp metadata/badges, natural font
line spacing (no extra 5dp leading), and 2dp section gaps. The separate briefing
uses readable 14sp text. Run and result text also use natural font leading.
Result details use 13sp text with natural wrapping. Full run logs use 14sp.
Keep mission identity/goals, the entire 20x20 grid, and bottom controls/status
pinned. Reserve a separate EVENTS panel with four 12sp rows above the run
controls; instrument scrolling must never hide the event tail. Long event rows
may ellipsize, with the complete text available in the full log. Scale the square
grid to fit the available width and height; only the instrument panel scrolls
independently below it. Never crop grid
rows to make room for telemetry.

When a mission finishes, record its result but retain the final grid on MISSION.
RESULT is opened explicitly; switching back to MISSION restores the same paused
or finished session during the activity lifetime. LIST opens the mission picker.
This is an in-memory view, not persistence/restoration of mutable world state.

### 11.5 Simulation controls

- `RUN` advances on the app's normal timer until paused or finished.
- While RUN is active, `STEP` first pauses at the current tick without advancing
  or rebuilding the session. Preserve registers, PC, pending multi-tick
  instruction, world state, RNG, and event history. Subsequent paused `STEP`
  presses advance exactly one **world tick**, not one completed instruction.
- Opening the full event log or instrument inspector pauses the same session.
  Closing either leaves it paused. `RUN` explicitly resumes from that context.
- `RESET` reconstructs the initial world from descriptor and firmware.
- Editing firmware never mutates a run already in progress; restart to load it.
- No screen tap directly moves, turns, sprays, or shields the rover.

### 11.6 Results and explanation

Display success/failure prominently, then:

- primary failure reason;
- ticks;
- civilians rescued/lost;
- remaining fires and total intensity;
- rover HP/heat;
- water used/remaining;
- firmware instructions/bytes;
- actions: `RETRY`, `EDIT FIRMWARE`, `MISSION LIST`.

For firmware faults, include the VM's halt reason and last executed instruction.

---

## 12. Persistence and sharing

### 12.1 Local data

Persist:

- source files and names;
- selected firmware and mission;
- authored-mission best result per engine version;
- most recent result;
- editor/UI preferences.

Do not persist a mutable world as the authoritative replay. Reconstruct from
the mission tuple and rerun. A UI snapshot may be cached only as disposable
presentation state.

### 12.2 Firmware export

Plain `.asm` is the first interoperability format. UTF-8, comments, and line
endings should round-trip. Binary export may be added later but must include
enough metadata to identify RF-8 ISA compatibility.

### 12.3 Mission codes

Mission-code sharing is post-MVP unless explicitly pulled forward. Before
implementation, create `FIRELINE-CODES.md` with a locked prefix and wire format.
Requirements:

- distinct from `RF1-`, suggested prefix `FL1-`;
- carries FIRELINE format and engine versions;
- identifies mission/layout, objectives/rules, seed, and canonical firmware;
- records FIRELINE result fields rather than Arena winner/HP fields;
- CRC/integrity and input-size limits;
- unknown opcode rejection and unknown-port tolerance;
- no network dependency.

---

## 13. Determinism and test requirements

### 13.1 Unit tests

At minimum:

- every terrain passability/burnability rule;
- movement bounds and wall blocking;
- water consumption, heat, packet timing, collision, life, and intensity
  reduction;
- empty-water behavior;
- shield activation/duration/damage blocking/overload behavior;
- fire growth and spread thresholds at wind forces 0 and 3;
- exact RNG stream separation;
- smoke formula;
- civilian rescue and loss;
- refill timing and cap;
- each port including no-target sentinels and tie-breaking;
- objective combinations and end-condition precedence;
- VM fault handling;
- tick-order regression tests.

### 13.2 Determinism tests

For every authored mission:

1. Run reference firmware twice from fresh worlds; compare result and final
   state hash.
2. Run once with extra unused `IN RAND` calls; prove fire-spread history is
   unchanged when physical actions/timing otherwise match.
3. Run through the direct headless runner and the same step-by-step world API;
   compare final state.
4. Record a golden event trace for a short fixed number of ticks.
5. Sweep representative seeds and assert termination under the maximum guard.

The final-state hash input must be canonical and ordered: engine version, tick,
rover state, row-major terrain, vertical edges, horizontal edges, fire arrays,
ordered civilians, ordered water
packets, objective state, and both RNG states. Do not use JVM object hash codes.

### 13.3 Integration tests

- Assemble FIRELINE symbols through `:rf8-core`.
- Run assembled bytes in `:fireline-engine` without source.
- Load Arena starter firmware and verify it performs valid execution without
  unsupported-opcode faults.
- Load FIRELINE firmware in Arena and verify unsupported ports return zero
  without faults.
- Verify all three Android application IDs can coexist.
- Verify app `STEP` results equal a direct headless tick.

### 13.4 Performance/safety limits

- Hard run cap: 1,000,000 ticks, reported as `TIME_EXPIRED`/safety termination.
- Firmware size remains the RF-8 compatibility limit until RF-8/64 ships.
- Mission layout remains exactly 400 cells.
- Water packets have a fixed maximum life and bounded list.
- Fire processing is row-major over the fixed grid; no unbounded queues.
- Import parsers must impose explicit source/code length limits before
  allocation.

---

## 14. MVP acceptance criteria

FIRELINE MVP is functionally complete when:

- [ ] It builds as `:fireline-app` and installs beside both Arena apps.
- [ ] It uses `:rf8-core` and `:fireline-engine`, not Arena's world engine.
- [ ] No new opcode exists.
- [ ] Numeric and named FIRELINE ports assemble through the shared assembler.
- [ ] Unknown ports degrade according to the shared platform contract.
- [ ] The rover can move, turn, spray, shield, rescue, refill, and exit.
- [ ] Fire grows and spreads deterministically with wind.
- [ ] Sensor RNG cannot perturb spread RNG.
- [ ] Shield timing matches the approved two/five contract.
- [ ] Five authored missions and their reference firmware pass headlessly.
- [ ] `RUN`, `STEP`, and `RESET` use the same authoritative world path.
- [ ] Results explain success or the selected failure reason.
- [ ] Firmware can be exported/imported as `.asm` offline.
- [ ] No Internet permission, account, ads, or analytics are required.
- [ ] All tests in sections 13 and the shared platform spec are green.

---

## 15. Post-MVP directions

### Deferred usability follow-ups (evaluation 2026-11-08)

The following remain deferred; the current startup and failure-result behavior
is intentionally unchanged:

- First-run onboarding: provide an optional commented starter program and a short
  edit -> step -> observe -> improve introduction. Preserve offline use and
  never replace user firmware. Evaluate the experience with an empty library.
- Failure diagnostics: supplement the authoritative failure reason and event
  tail with a concise, evidence-based explanation of earlier contributing events
  (for example repeated shots blocked by a wall before TIME_EXPIRED). Derive
  explanations from recorded run evidence; never invent a cause or change engine
  outcomes. Keep the full trace available for inspection.

### Other candidates

Candidates, not commitments:

- gates, terminals, pumps, and official writable FIRELINE ports;
- multiple spray modes selected through an output port;
- moving civilians or convoy escort;
- destructible/flammable barriers;
- structural-collapse countdowns;
- procedural missions with a dedicated RNG stream;
- mission/challenge codes and asynchronous sharing;
- code golf, water-efficiency, and local ladders;
- campaign chapters and narrative briefings;
- shared Android editor/storage library after both apps prove the abstraction;
- RF-8/64 as a platform-wide revision, never a FIRELINE-only CPU advantage.
