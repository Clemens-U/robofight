# RF-8 Platform Contract

> Status: **implementation specification**
> Decision date: 2026-11-05
> Applies to: Opcode Arena, Opcode Arena: The Circuit, and RF-8: FIRELINE
> Purpose: keep one compatible RF-8 computer while allowing multiple games to
> attach different bodies, sensors, and deterministic worlds.

This document is normative for shared RF-8 behavior. `DESIGN.md` remains the
source of truth for Opcode Arena as a game; `FIRELINE-DESIGN.md` is the source
of truth for FIRELINE. Where either game document describes the CPU, this
platform contract wins.

The keywords **MUST**, **MUST NOT**, **SHOULD**, and **MAY** have their usual
requirements meaning.

---

## 1. Locked decisions

1. RF-8 is a shared platform, not an Arena-only implementation detail.
2. The shipped opcode numbers, instruction width, operand encoding, registers,
   flags, and base memory window form **RF-8 ISA v1**.
3. ISA v1 opcodes are frozen. Scenario features MUST first be expressed as new
   ports, new values on existing ports, or world rules.
4. Named ports are assembler symbols only. Firmware contains the numeric port
   operand on the existing `IN` (`0xB7`) or `OUT` (`0xB8`) instruction.
5. The official port-number registry is global across RF-8 products. An
   official number MUST NOT mean different things in Arena and FIRELINE.
6. Unknown port reads return `0`; unknown port writes have no effect. They do
   not fault or halt the VM.
7. Unknown opcodes remain a hard VM fault and remain rejected by challenge-code
   decode scans. They MUST NOT silently become no-ops.
8. Opcode Arena and FIRELINE live in one repository and normal mainline. A
   permanent FIRELINE branch is forbidden. Short-lived feature branches are
   normal and merge back to main.
9. FIRELINE is a separate Android application and world engine. It is not an
   Opcode Arena product flavor.
10. Plain RF-8 firmware is portable. Complete world descriptors, replays, and
    challenge codes are profile-specific.

---

## 2. Compatibility vocabulary

The project MUST distinguish these kinds of compatibility:

| Term | Promise |
|------|---------|
| **Binary ISA compatibility** | Previously assembled three-byte RF-8 instructions decode with the same instruction boundaries and CPU meanings. |
| **Source compatibility** | A shared assembler recognizes all official port names, even when the current world does not implement the named peripheral. |
| **Peripheral compatibility** | Missing ports degrade to read-zero/write-ignore behavior. |
| **World compatibility** | A world profile preserves the deterministic meaning of its layout/rules/seed/firmware tuple for a stated world-engine version. |
| **Content compatibility** | A particular app owns and can render the referenced mission, arena, or content pack. |

The guarantees are intentionally asymmetric:

- Old firmware MUST continue to run on a newer RF-8 VM.
- FIRELINE firmware MUST remain valid RF-8 when loaded in Arena, but it is not
  promised to perform a useful task when its peripherals read zero.
- An Arena challenge is not a FIRELINE mission, even if both embed identical
  firmware bytes.
- Changing a deterministic world rule does not change the ISA version; it
  changes that world's engine-rules version.

---

## 3. RF-8 ISA v1 invariants

The following are frozen for ISA v1:

- Every instruction is exactly three bytes.
- Byte 0 is the opcode; bytes 1-2 are the little-endian operand field.
- Existing opcode numbers and operand kinds in `ISA.kt` do not move.
- Registers remain `A`, `X`, and `Y`, with the current `PC`, `SP`, and flag
  behavior.
- Program start remains `$0100` in the compatibility profile.
- `IN p` reads an 8-bit port into `A` and updates the normal `Z`/`N` flags.
- `OUT p,A` sends the current `A` value to a port and does not change `A`.
- Existing arithmetic, branch, stack, and data-movement semantics remain
  unchanged.

An opcode addition is not an ordinary feature. If a future CPU requirement
cannot be represented by ports or existing operations, it requires all of:

1. A separate RF-8 ISA revision document.
2. An append-only opcode allocation.
3. A loader/profile declaration that an old implementation can reject before
   execution.
4. Updates to assembler, disassembler, VM, firmware containers, code import,
   compatibility tests, and both apps.

RF-8/64 address expansion may justify such work, but FIRELINE v1 does not.

---

## 4. Port bus

### 4.1 Encoding and accepted source forms

The `PORT` operand is an unsigned byte. The shared assembler MUST accept:

```asm
IN   HEAT
IN   CIV_ANGLE
IN   20
OUT  31,A
```

Numeric ports `0..255` are encodable. Values outside that range are assembler
errors. An unknown symbolic name is an assembler error because it cannot be
encoded; an unknown numeric port is legal.

Official named allocations initially occupy `0..63`. Values `64..255` are
reserved for later RF-8 profiles and private experiments. Imported binaries
using an unimplemented value anywhere in `0..255` still receive the standard
read-zero/write-ignore behavior.

The existing `$00C0-$00FF` documentation describes the 64-port compatibility
window. `IN`/`OUT` are the normative peripheral access mechanism. Direct
`MOV` access to the backing VM byte array MUST NOT be presented as equivalent
to an `IN`/`OUT` side effect unless a later memory-bus specification explicitly
implements that mapping.

### 4.2 Unknown-port behavior

For every world environment:

```text
IN unknown       -> A = 0; Z = 1; N = 0; execution continues
OUT unknown,A    -> no state change; execution continues
```

Unknown accesses consume the normal one tick for `IN` or `OUT`. They MUST NOT
change heat, world RNG streams, mission state, or any other hidden state.

### 4.3 Global registry

The shared core owns a data-driven registry with at least:

```kotlin
data class PortDef(
    val number: Int,
    val name: String,
    val access: PortAccess,
    val profile: MachineProfile,
    val description: String,
)
```

The exact Kotlin API may differ, but it MUST provide:

- case-insensitive name-to-number lookup for assembly;
- number-to-definition lookup for documentation/disassembly;
- duplicate-number and duplicate-name validation at startup or in tests;
- a way to generate the per-app cheat sheet without maintaining a second
  hand-written mapping;
- all official symbols in every official app build.

Disassembly of an `IN` or `OUT` SHOULD show the registered symbolic name. If
none exists, it MUST show `port<number>` or the numeric value without failing.

---

## 5. Official port allocation

### 5.1 Base body ports (`0..11`)

These numbers already ship and remain fixed:

| # | Symbol | Arena meaning | Cross-profile rule |
|---:|--------|---------------|--------------------|
| 0 | `HP` | Robot HP | Current machine integrity, `0` means destroyed. |
| 1 | `DIR` | Facing; writable | `0=W 1=N 2=E 3=S`; a supported write changes facing. |
| 2 | `RX` | Grid column | Current X coordinate. |
| 3 | `RY` | Grid row | Current Y coordinate. |
| 4 | `DIST` | Nearest visible enemy distance | Profile's primary target distance; `0` means none. |
| 5 | `ANGLE` | Turn delta to enemy | Clockwise turn delta `0..3`; `255` means none. |
| 6 | `FIRE` | Reserved/read-zero | Remains reserved in ISA v1; use `SHOOT`. |
| 7 | `SHIELD` | Shield active | `1` while active, otherwise `0`. |
| 8 | `HEAT` | Current heat | Shared body heat on the `0..100` scale. |
| 9 | `ENEMY_HP` | Nearest enemy HP | Primary target strength; `255` means none. |
| 10 | `RAND` | Seeded random byte | Profile-owned deterministic sensor RNG. |
| 11 | `AHEAD` | Cell ahead blocked | `1` if forward movement is blocked, otherwise `0`. |

`DIST`, `ANGLE`, and `ENEMY_HP` deliberately describe a profile's **primary
target**. In FIRELINE the primary target is a burning cell. This lets simple
Arena firmware such as HUNTER perform a recognizable fallback behavior without
claiming that enemies and fires are the same world entity.

### 5.2 Discovery ports (`12..15`)

| # | Symbol | Meaning |
|---:|--------|---------|
| 12 | `PROFILE` | `0` Arena compatibility profile; `1` FIRELINE. Unknown on an old implementation therefore naturally reads as Arena/legacy `0`. |
| 13 | `PROFILE_REV` | Revision of the selected machine profile, starting at `1` for FIRELINE v1. Arena may return `0` until it explicitly adopts discovery. |
| 14 | `CAPS` | Profile-specific 8-bit capability field. A profile document must define its bits. |
| 15 | reserved | Reads zero; writes ignored. |

Portable firmware MAY branch on `PROFILE` or capability bits. It MUST still
handle zero as a valid legacy/no-capability response.

### 5.3 FIRELINE ports (`16..31`)

| # | Symbol | R/W | Meaning |
|---:|--------|-----|---------|
| 16 | `FIRE_DIST` | R | Manhattan distance to selected burning cell; `0` if none. |
| 17 | `FIRE_ANGLE` | R | Clockwise turn delta to selected burning cell; `255` if none. |
| 18 | `FIRE_LEVEL` | R | Selected fire intensity `1..100`; `0` if none. |
| 19 | `CIV_DIST` | R | Manhattan distance to selected unrescued civilian; `0` if none. |
| 20 | `CIV_ANGLE` | R | Clockwise turn delta to selected civilian; `255` if none. |
| 21 | `CIV_COUNT` | R | Required civilians still awaiting rescue. |
| 22 | `WATER` | R | Suppressant remaining, `0..100`. |
| 23 | `LOCAL_FIRE` | R | Fire intensity on the rover's current cell, `0..100`. |
| 24 | `SMOKE` | R | Derived local smoke density, `0..255`. |
| 25 | `WIND_DIR` | R | Wind direction using the shared direction encoding. |
| 26 | `WIND_FORCE` | R | Wind force `0..3`. |
| 27 | `EXIT_DIST` | R | Manhattan distance to the mission exit; `0` if absent or already on it. |
| 28 | `EXIT_ANGLE` | R | Clockwise turn delta to exit; `255` if no exit. |
| 29 | `TILE` | R | Current terrain identifier defined by the FIRELINE profile. |
| 30 | `MISSION` | R | FIRELINE mission-state bit field. |
| 31 | reserved | Reads zero; writes ignored. |

FIRELINE v1 `CAPS` bits are:

| Bit | Mask | Capability |
|----:|-----:|------------|
| 0 | `$01` | Fire target sensors (`16..18`) |
| 1 | `$02` | Civilian sensors (`19..21`) |
| 2 | `$04` | Water tank (`22`) |
| 3 | `$08` | Smoke sensor (`24`) |
| 4 | `$10` | Wind sensors (`25..26`) |
| 5 | `$20` | Exit sensors (`27..28`) |
| 6 | `$40` | Mission/tile sensors (`29..30`) |
| 7 | `$80` | Reserved; zero in v1 |

All FIRELINE v1 builds return `$7F` from `CAPS`.

---

## 6. Shared physical instructions and timing

The body operations keep their opcode numbers across profiles:

| Instruction | ISA v1 opcode | Shared timing contract |
|-------------|---------------|------------------------|
| `MOVE` | `0xB4` | Two ticks; movement is attempted on the final tick. |
| `SHOOT` | `0xB0` | Three ticks; the profile-specific projectile/tool action occurs on the final tick. |
| `SHIELD` | `0xB5` | Two activation ticks; activation completes on the final tick and protects five world-resolution ticks including that tick. |
| `TURN` | `0xB1..0xB3` | One tick. |
| `WAIT` | `0xB6` | One tick. |

The shared cooling gate remains tied to VM/action state: after the VM step,
cool by 2 only when the operation is no longer `inProgress` and the environment
did not set `heatedThisTick`. Consequently, continuation ticks of any multi-tick
physical instruction do not cool; a successful heat action also does not cool
on its completion tick. A final `MOVE` tick or a dry FIRELINE `SHOOT` completion
may cool because it adds no heat and the operation has completed. Active shield
duration by itself does not suppress cooling.

### 6.1 Approved shield semantics

The previous implementation occupied three ticks and protected only the final
tick. It is replaced by this contract:

1. `SHIELD` checks heat lockout when the instruction begins.
2. If locked, it is a one-tick no-op and normal cooling may occur.
3. Otherwise it occupies two ticks. No later instruction executes during those
   two activation ticks.
4. On the second tick it adds `20` heat and sets the shield duration to five.
5. Projectile/environment resolution then occurs while the shield is active.
6. The duration decrements **after** that tick's world resolution.
7. The next instruction begins on the following tick while four protected
   resolution windows remain.
8. Reissuing `SHIELD` performs the normal two-tick activation and refreshes the
   duration to five. Durations do not add or exceed five.
9. `IN SHIELD` returns `1` for the complete active window.
10. The shield blocks every shieldable hit/exposure during the window; it is
    not consumed by the first hit.

Timeline:

| Tick | CPU | Protected during resolution? | Remaining after resolution |
|-----:|-----|------------------------------|---------------------------:|
| 1 | `SHIELD (1/2)` | no | 0 |
| 2 | `SHIELD (2/2)` | yes | 4 |
| 3 | next instruction | yes | 3 |
| 4 | next instruction | yes | 2 |
| 5 | next instruction | yes | 1 |
| 6 | next instruction | yes | 0 |
| 7 | next instruction | no | 0 |

Arena's shield-rules change MUST bump `ChallengeFormat.ENGINE_VERSION` from 1
to 2 in the same implementation change. Old `eng=1` codes remain importable
under the existing warning/current-rules contract.

---

## 7. Determinism and random streams

Every world profile owns its deterministic engine version. ISA version and
world-engine version are separate.

- Arena continues to use `ChallengeFormat.ENGINE_VERSION`.
- FIRELINE owns an independent `FIRELINE_ENGINE_VERSION`, starting at 1.
- A deterministic change in one world does not bump the other world's version.
- A shared CPU semantic change normally requires bumps in every affected
  world's engine version.

FIRELINE MUST use separate seeded streams for:

1. `RAND` port reads;
2. environmental fire spread;
3. procedural layout generation, if added.

This prevents a diagnostic `IN RAND` from changing when or where fire spreads.
Each stream is derived deterministically from the mission seed and a fixed,
documented salt. Changing a generator, salt, draw order, or traversal order is
a FIRELINE engine-version bump.

---

## 8. Firmware and world containers

### 8.1 Portable firmware

The portable unit is either:

- UTF-8 RF-8 assembly source; or
- the canonical assembled instruction image, a multiple of three bytes.

Firmware containers MUST declare an ISA/profile requirement if a future format
needs more than ISA v1. FIRELINE v1 does not.

### 8.2 Profile-specific descriptors

Arena's `RF1-` Challenge Code remains Arena-specific. FIRELINE MUST NOT encode a
mission by pretending it is an Arena layout or reusing Arena result fields.

FIRELINE sharing, when implemented, uses a distinct prefix and descriptor
specified separately. Both formats MAY embed the same canonical firmware
bytes. An app that cannot run the other profile MAY still offer to extract and
save its firmware.

---

## 9. Required conformance tests

The shared core is not complete until automated tests prove:

1. Every existing opcode retains its number and operand kind.
2. Existing Arena firmware assembles byte-for-byte identically after module
   extraction.
3. Numeric ports `0`, `63`, `64`, and `255` assemble; `-1` and `256` fail.
4. Every official symbolic port assembles to its allocated number.
5. Port names and numbers are globally unique.
6. Unknown `IN` returns zero and sets `Z`; unknown `OUT` is side-effect free.
7. Unknown ports do not consume RNG or alter heat.
8. Unknown opcodes still fault and are rejected by pinned-code decode scans.
9. Arena and FIRELINE environments report their intended `PROFILE`, revision,
   and capability values.
10. The shield timeline matches section 6.1 exactly, including refresh, heat
    lockout, multiple hits in one tick, and `IN SHIELD`.

The module migration MUST run the existing engine and Android test suites
before and after every move. A move that changes assembled bytes or current
Arena results, other than the explicitly approved shield/engine-version change,
is a regression.

---

## 10. Explicit non-goals

- No runtime plugin system.
- No network service or account requirement.
- No cross-app shared database.
- No new FIRELINE-only opcodes.
- No attempt to make an Arena challenge executable as a FIRELINE mission.
- No silent interpretation of the same official port number in two unrelated
  ways.
- No RF-8/64 implementation as part of the FIRELINE v1 work.
