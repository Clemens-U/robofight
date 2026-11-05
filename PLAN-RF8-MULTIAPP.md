# RF-8 Multi-App Migration Plan

> Status: **implementation-ready plan**
> Depends on: `RF8-PLATFORM-SPEC.md`, `FIRELINE-DESIGN.md`
> Starting point: one Android `:app` module with `free`/`pro` flavors and one
> pure-JVM `:engine` module that currently contains both RF-8 and Arena code.

This plan restructures the repository without maintaining a separate FIRELINE
branch. It is deliberately phased so each commit can build and test. A future
chat or agent should implement phases in order and stop at a failing gate rather
than carrying a broken intermediate state forward.

---

## 1. Target repository shape

The first stable multi-app layout is:

```text
robofight/
  rf8-core/                 pure JVM
    isa/
    assembler/
    disasm/
    vm/

  engine/                   pure JVM; Opcode Arena engine (name retained)
    challenge/
    world/

  fireline-engine/          pure JVM
    mission/
    world/
    ports/

  app/                      existing Opcode Arena Android application
    src/main/
    src/free/
    src/pro/

  fireline-app/             separate FIRELINE Android application
    src/main/

  RF8-PLATFORM-SPEC.md
  FIRELINE-DESIGN.md
  PLAN-RF8-MULTIAPP.md
```

Gradle dependencies:

```text
:rf8-core
    ^          ^
    |          |
:engine   :fireline-engine
    ^          ^
    |          |
  :app    :fireline-app
```

Retain the Gradle name `:engine` during this migration. Renaming it to
`:arena-engine` adds path churn without enabling FIRELINE. Comments and docs
should describe it as the Arena engine. A later rename is allowed only as a
separate mechanical change.

Do not create a shared Android UI module initially. The existing Activity is
Arena-specific and large; premature extraction would mix architectural work
with product design. FIRELINE should implement a small independent Activity
against the shared core and its own engine. Extract a `:rf8-android` library
later only after both apps contain proven, substantially identical code.

---

## 2. Product/build matrix

The controlled builds are:

| Gradle task | Product | Application ID |
|-------------|---------|----------------|
| `:app:assembleFreeDebug` | Opcode Arena | `robofight.android` |
| `:app:assembleProDebug` | Opcode Arena: The Circuit | `robofight.android.pro` |
| `:fireline-app:assembleDebug` | RF-8: FIRELINE | `robofight.fireline` |

FIRELINE v1 has no tier dimension. Do not generate a four-way
Arena/Fireline x free/pro flavor matrix. If FIRELINE later needs editions, that
decision belongs to its own app module and does not affect Arena's flavor
dimension.

Release signing MAY initially reuse the repository's conditional keystore
configuration, but FIRELINE must have an explicit release application ID,
version code/name, label, icon, manifest, privacy/store copy, and artifact name.

All products remain buildable from the same commit.

---

## 3. Change discipline

### 3.1 Branch policy

- Work on normal short-lived branches such as `codex/shield-duration`,
  `codex/rf8-core`, and `codex/fireline-mvp`.
- Merge each green phase to the shared main line.
- Never create a permanent branch containing the FIRELINE product.

### 3.2 Commit boundaries

Prefer these reviewable boundaries:

1. Shield behavior + Arena engine-version bump + docs/tests.
2. Port registry and numeric-port support + tests.
3. Create `:rf8-core` and move code without semantic changes.
4. Add headless `:fireline-engine` skeleton and tests.
5. Implement deterministic FIRELINE MVP world.
6. Add `:fireline-app` shell/editor/run UI.
7. Add packaging, store assets, and release tasks.

Do not combine module moves with behavior changes except for import/package
adjustments required to compile.

### 3.3 Dirty-worktree rule

Before implementation, inspect `git status`. Existing unrelated files belong
to the user. Do not reset, overwrite, stage, or reformat them. Restrict each
phase to the files listed in its scope plus necessary build imports.

---

## 4. Phase 0 — establish a green baseline

### Work

1. Record the current Java/Gradle/Android toolchain versions.
2. Run and record:

   ```text
   ./gradlew :engine:test
   ./gradlew :app:testFreeDebugUnitTest
   ./gradlew :app:testProDebugUnitTest
   ./gradlew :app:assembleFreeDebug
   ./gradlew :app:assembleProDebug
   ```

3. Save representative assembled-byte fixtures for all shipped preset firmware
   and `MYBOT` in a core test. These are migration parity fixtures, not new
   gameplay golden results.
4. Save a small `eng=1` Challenge Code fixture for the old-engine warning test.

### Gate

- Every available baseline task is green, or an existing failure is documented
  before touching production code.
- Preset firmware fixture hashes/bytes are committed.

---

## 5. Phase 1 — implement the approved shield

This phase changes Arena behavior before extraction so failures are easy to
localize in the familiar module.

### Required code changes

1. In `ISA.kt`, change `SHIELD` cost from three ticks to two.
2. Replace the transient `Bot.shielded` storage with a duration counter, e.g.
   `shieldTicksRemaining`. A derived `shielded` property may remain for
   renderer/caller compatibility.
3. Do not clear shield state at the start of each world tick.
4. On successful activation completion:
   - add `HEAT_SHIELD` once;
   - mark `heatedThisTick`;
   - set duration to five.
5. Resolve all projectiles against the active shield.
6. Decrement the duration after projectile/world resolution.
7. `IN SHIELD` returns one while the duration is positive.
8. Re-activation refreshes to five and does not stack.
9. Keep the existing heat-lock behavior: a locked activation is a one-tick
   no-op and can cool.
10. Change `ChallengeFormat.ENGINE_VERSION` from `1` to `2` in the same commit.
11. Update stale `eng=1` comments in `ChallengeRun`, `RunController`, and
    `MainActivity` to avoid claiming the current engine is version 1.
12. Update `MANUAL.md`, `DESIGN.md`, `README.md`, and relevant challenge rules.

### Required tests

- Exact seven-tick timeline from `RF8-PLATFORM-SPEC.md` section 6.1.
- A projectile on activation tick 1 damages the bot.
- A projectile on activation tick 2 is blocked.
- Projectiles on the four following ticks are blocked.
- A projectile on tick 7 damages the bot.
- Multiple projectiles in one active tick are all blocked.
- Code execution resumes on tick 3.
- `IN SHIELD` is 1 only for the active window.
- Refresh behavior and non-stacking duration.
- Heat is added once, equals +20, and cooling timing is correct.
- Lockout above 80 remains a one-tick no-op.
- Old `eng=1` code imports with `OLD ENGINE — REPLAY USES CURRENT RULES`.
- New codes emit `eng=2`.

### Gate

- All baseline tests pass with only the approved deterministic expectation
  changes.
- No opcode number or firmware byte fixture changes.

---

## 6. Phase 2 — make ports the extension mechanism

### Required code changes

1. Add a shared, data-driven port catalog matching
   `RF8-PLATFORM-SPEC.md` sections 4-5.
2. Preserve the current base numbers `0..11` exactly.
3. Add official symbols `PROFILE`, `PROFILE_REV`, `CAPS`, and the FIRELINE
   symbols `16..30` to the catalog.
4. Modify assembler numeric-port parsing to accept `0..255`, independent of
   whether the runtime implements the port.
5. Keep unknown symbolic names as assembler errors.
6. Make disassembly prefer catalog names and fall back safely to numeric names.
7. Ensure every `Env` implementation provides read-zero/write-ignore behavior
   for unimplemented numeric ports.
8. Arena SHOULD explicitly return `PROFILE=0`; its legacy behavior is also zero.
9. Do not implement FIRELINE world behavior in this phase.
10. Generate or validate the RF-8 cheat-sheet port table from the catalog where
    practical. If generation is deferred, add a parity test between the table
    and the hand-maintained cheat sheet.

### Required tests

Use the conformance list in `RF8-PLATFORM-SPEC.md` section 9. In addition:

- Assemble, disassemble, and reassemble every named port.
- Prove `IN 200` executes without fault in the Arena environment and yields 0.
- Prove `OUT 200,A` changes no world or VM state except `PC`/normal execution.
- Prove a pinned firmware block containing `IN 200` passes opcode decode scan.
- Prove a pinned block containing an undefined opcode still fails.

### Gate

- Existing firmware byte fixtures remain identical.
- Existing base-port tests remain identical.
- Both Android flavors still build.

---

## 7. Phase 3 — extract `:rf8-core`

### 7.1 Module creation

Add to `settings.gradle.kts`:

```kotlin
include(":rf8-core")
```

Create a pure Kotlin/JVM Gradle module using the same JVM target and Kotlin
toolchain as the current engine. It MUST have no Android, UI, filesystem,
network, or game-content dependency.

### 7.2 Move ownership

Move these packages from `:engine` to `:rf8-core`:

```text
robofight.isa
robofight.assembler
robofight.disasm
robofight.vm
```

Also move their focused unit tests and all new platform/port conformance tests.
Keep package names initially; changing packages and modules simultaneously
would obscure semantic parity. Package renaming, if desired, is later work.

Keep these in `:engine`:

```text
robofight.world
robofight.challenge
```

Add:

```kotlin
implementation(project(":rf8-core"))
```

to `engine/build.gradle.kts`. The existing Android `:app` continues to depend
only on `:engine`; it receives `:rf8-core` transitively.

### 7.3 VM/environment boundary

`Vm` currently dispatches physical instructions through `Env` methods such as
`move`, `shoot`, `shield`, `turn`, and `heatLocked`. Keep this ABI in the core
for the first extraction. Both Arena and FIRELINE implement the same body
operations, so redesigning it is unnecessary migration risk.

If later profiles need generalized actions, change the boundary in a dedicated
platform revision with conformance tests. Do not replace the methods with
reflection, callbacks keyed by strings, or Android dependencies.

### 7.4 Gate

- `:rf8-core:test` is green.
- `:engine:test` is green.
- Both Arena app flavor tests and assemblies are green.
- Preset firmware bytes are identical.
- Arena headless outcomes are identical to the end of phase 2.
- `rf8-core` contains no import from `robofight.world`, `robofight.challenge`,
  or Android packages.

---

## 8. Phase 4 — create `:fireline-engine`

### 8.1 Module

Add a pure Kotlin/JVM module:

```kotlin
include(":fireline-engine")
```

Dependencies:

```kotlin
implementation(project(":rf8-core"))
testImplementation(libs.junit.jupiter)
```

It MUST NOT depend on `:engine`. Sharing happens through `:rf8-core`, not by
making FIRELINE a special Arena mode.

### 8.2 Suggested packages/files

```text
fireline-engine/src/main/kotlin/robofight/fireline/
  FirelineRules.kt          constants and engine version
  FirelinePorts.kt          profile implementation/catalog parity
  Mission.kt                immutable mission descriptor/objectives
  Layout.kt                 terrain and authored layout decoding
  Rover.kt                  physical state + RF-8 Env
  FireCell.kt               dynamic fire state if not stored in arrays
  WaterPacket.kt            suppressant projectile
  FirelineWorld.kt          authoritative tick and resolution order
  FirelineRun.kt            descriptor -> world construction/run path
  FirelineResult.kt         deterministic outcome/statistics
  TextGrid.kt               headless ASCII renderer
```

Names may change, but ownership and separation should remain recognizable.

### 8.3 Implementation order

1. Static layout + rover + `MOVE`/`TURN`/base ports.
2. Water tank + `SHOOT` + water packets.
3. Fire intensity + extinguishing.
4. Approved shield behavior against fire exposure.
5. Civilians and contact rescue.
6. Exit and composable objectives.
7. Wind and deterministic fire spread with separate RNG streams.
8. Smoke and remaining FIRELINE ports.
9. Results, failure reasons, and headless runner.
10. Authored MVP missions.

Follow `FIRELINE-DESIGN.md` exactly for tick ordering, target selection,
sentinel values, damage, and mission completion.

### 8.4 Gate

- The engine runs to completion with no Android dependency.
- Repeating a mission with the same descriptor and firmware produces an
  identical result and final-state hash.
- `IN RAND` frequency does not alter environmental spread.
- Different seeds can alter spread but not authored static terrain.
- All port and world acceptance tests from `FIRELINE-DESIGN.md` pass.

---

## 9. Phase 5 — create `:fireline-app`

### 9.1 Android module

Add a separate `com.android.application` module with:

- namespace/application ID `robofight.fireline`;
- `minSdk`, `targetSdk`, and `compileSdk` aligned with `:app`;
- dependency on `:fireline-engine`;
- its own `AndroidManifest.xml`, resources, icon, label, version code/name,
  preferences, and database filename;
- no product flavors for v1;
- the same no-network/privacy posture as Arena unless explicitly reconsidered.

The module MUST be installable alongside both Arena packages.

### 9.2 Minimum screens

Implement three FIRELINE-owned surfaces:

1. **FILES** — create, edit, rename, duplicate, delete, import, and export RF-8
   source files.
2. **MISSION** — choose mission and firmware, then `RUN`, `STEP`, or `RESET`.
3. **RESULT** — outcome, failure reason, ticks, civilians, HP, water, and fire
   remaining, with a route back to the editor.

The run screen includes the grid, current/next RF-8 instruction, registers,
port telemetry, and mission status. There are no real-time movement controls.

### 9.3 Reuse policy

- Reuse the RF-8 core and FIRELINE engine through Gradle dependencies.
- Do not make `fireline-app` depend on the Arena `:app` module.
- Do not copy the 3,000-line Arena `MainActivity` and rename strings.
- Small neutral utilities may be copied initially only when extraction would
  broaden the phase; mark them with a follow-up issue.
- Extract shared Android code only when both apps have concrete identical
  behavior and tests. Good later candidates are source-file persistence,
  export ZIP primitives, terminal colors/fonts, and editor line mapping.
- Arena-specific challenge UI, bot slots, arena pack gating, and migration UX
  are never candidates for FIRELINE reuse.

### 9.4 Firmware interoperability

FIRELINE must accept plain `.asm` shared through Android's normal document/share
flow. It stores its own local copy; it does not reach into Arena's private app
data. Arena source containing FIRELINE symbols assembles because the official
port catalog is shared, even though those ports read zero there.

### 9.5 Gate

- Install Arena free, The Circuit, and FIRELINE side-by-side.
- Create and run firmware entirely offline.
- `STEP` advances exactly one world tick, including multi-tick VM state.
- Background/foreground and rotation do not silently start a second simulation
  or corrupt the saved source.
- A headless result and app-driven result match for the same mission tuple.
- Accessibility labels and small-screen layout are usable at the existing
  minimum supported API.

---

## 10. Phase 6 — packaging and release readiness

Before any FIRELINE store release:

1. Add dedicated store icon, feature graphic, screenshots, alt text, listing,
   privacy-policy coverage, and release notes.
2. Add release bundle tasks and verify signing/application ID.
3. Add FIRELINE to CI/build documentation without removing Arena tasks.
4. Run release-mode minification and verify no registry/serializer classes are
   stripped.
5. Verify the app requests no Internet permission, analytics, advertising ID,
   account, or unnecessary storage permission.
6. Add deterministic golden missions and a manual smoke-test checklist.
7. Decide whether mission sharing is in the release. If yes, create and lock a
   separate `FIRELINE-CODES.md`; do not improvise on Arena's `RF1` format.

---

## 11. Test/build matrix after migration

The normal pre-merge matrix is:

```text
./gradlew :rf8-core:test
./gradlew :engine:test
./gradlew :fireline-engine:test
./gradlew :app:testFreeDebugUnitTest
./gradlew :app:testProDebugUnitTest
./gradlew :fireline-app:testDebugUnitTest
./gradlew :app:assembleFreeDebug
./gradlew :app:assembleProDebug
./gradlew :fireline-app:assembleDebug
```

Release preparation additionally runs the three relevant bundle/release builds
and installation/signature checks.

---

## 12. Cross-phase acceptance checklist

The migration is complete only when all are true:

- [ ] One checkout builds all three installable products.
- [ ] No permanent FIRELINE branch exists.
- [ ] RF-8 opcode numbers and old firmware bytes are unchanged.
- [ ] Unknown ports read zero/write-ignore without faults.
- [ ] Official port names assemble in every product.
- [ ] Arena shield uses two-tick activation and five-tick duration.
- [ ] New Arena codes emit `eng=2`; old codes follow the documented skew path.
- [ ] RF-8 core has no world or Android dependency.
- [ ] Arena and FIRELINE engines are independently headless and deterministic.
- [ ] FIRELINE does not depend on Arena engine or app modules.
- [ ] Arena free/Pro behavior outside approved shield/port additions is intact.
- [ ] Firmware can be moved between apps as source without shared private data.
- [ ] FIRELINE missions cannot be mistaken for Arena `RF1` challenges.
- [ ] Documentation names one owner/spec for each shared rule.

---

## 13. Decisions deliberately deferred

These are not blockers for starting phases 0-5:

- FIRELINE paid/free editions.
- A shared Android UI library.
- FIRELINE mission-code wire format.
- Cloud sync, online leaderboards, or accounts.
- RF-8/64.
- New opcodes.
- Procedural mission generation beyond deterministic spread inside authored
  layouts.
- Renaming `:engine` to `:arena-engine`.
