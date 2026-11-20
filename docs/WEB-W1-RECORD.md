# WEB-W1 record: the pure-Kotlin engine as Kotlin/wasmJs (branch `codex/web-w1-kmp-wasm`)

Phase W1 of `docs/FIRELINE-WEB.md` §9. Run 2026-11-20 on branch `codex/web-w1-kmp-wasm`,
branched from `2828fcb` (`codex/fireline-app`, the frozen layout + committed fixtures).

W1 gate as written: "artifacts reachable on this machine, both test tasks green, wasm output
loadable in a browser." **All three parts are met.** The kill/branch decision is: keep
Option A (Kotlin Multiplatform -> wasmJs), proceed to W2.

## 1. What changed in the repo

| change | where |
|---|---|
| Source sets moved `src/main -> src/commonMain`, `src/test -> src/commonTest` | `:rf8-core`, `:fireline-engine` |
| Modules converted from `kotlin("jvm")` to Kotlin Multiplatform with `jvm` + `wasmJs` targets | `rf8-core/build.gradle.kts`, `fireline-engine/build.gradle.kts` |
| New formatting helpers `hexPad`, `decPad` (public: `internal` is module-scoped and `:fireline-engine` needs them across the module boundary) | `rf8-core/src/commonMain/kotlin/robofight/isa/TextFormat.kt` |
| Nine output-identical formatting replacements (the wasmJs stdlib does not expose `String.format` or `String(CharArray)`) | see section 2 |
| Test suites moved from JUnit Jupiter to `kotlin.test` (11 files) so one suite runs on both targets; six `assertThrows(X::class.java)` sites became `assertFailsWith<X> { }` | `:rf8-core` and `:fireline-engine` commonTest |
| Wasm-only spike entry point (never reaches the Android targets) | `fireline-engine/src/wasmJsMain/kotlin/robofight/fireline/W1HelloWasm.kt` |
| Wasm toolchain wiring (see section 4) | `gradle/wasm-toolchain.gradle`, root `build.gradle.kts`, `gradle.properties` |
| `kotlin-test` + `kotlin-multiplatform` added to the version catalog | `gradle/libs.versions.toml` |
| npm lock store kept for reproducible wasm dependency resolution | `kotlin-js-store/wasm/package-lock.json` |

`settings.gradle.kts` is **unchanged**: `RepositoriesMode.FAIL_ON_PROJECT_REPOS` still holds
after the toolchain fix, verified by a full run (it had to be relaxed while the toolchain was
still trying to download node, and the relaxation was reverted once the downloads were off).
`:engine`, `:app`, `:fireline-app` are untouched: they consume the same `:rf8-core` /
`:fireline-engine` code through the `jvm` target.

## 2. Formatting replacements (the whole port cost)

Main sources (nine sites, all display or debug strings, none arithmetic/RNG/hash):

| file | site | replacement |
|---|---|---|
| `rf8-core` `disasm/Disasm.kt` | 3 | `String.format("%04X", ...)` -> `hexPad(value, 4)` |
| `rf8-core` `vm/Vm.kt` | 1 | `"%02X".format(op)` -> `hexPad(op, 2)` |
| `fireline-engine` `FirelineResult.kt` | 1 | `"%02d".format(missionId)` -> `decPad(missionId, 2)` |
| `fireline-engine` `FirelineRun.kt` | 1 | `"%03d".format(world.tick)` -> `decPad(world.tick, 3)` |
| `fireline-engine` `Mission.kt` | 1 | `"%02d".format(id)` -> `decPad(id, 2)` |
| `fireline-engine` `Missions.kt` | 2 | `String(charArray)` -> `charArray.concatToString()` |

Test sources (three more sites, found when the wasm target compiled the suites; same rule):

| file | site | replacement |
|---|---|---|
| `rf8-core` `Rf8CoreConformanceTest.kt` | 1 | `"%02X:%s:%d".format(...)` -> `hexPad(it, 2) + ":" + ...` |
| `fireline-engine` `Step10AuthoredMissionsTest.kt` | 1 | `"mission %02d".format(m.id)` -> `"mission " + decPad(m.id, 2)` |
| `fireline-engine` `TestSupport.kt` | 1 | `String(it)` -> `it.concatToString()` |

The wasm target rejects `String(CharArray)` as a deprecated *error*, not a warning: any new
code in `commonMain`/`commonTest` must use `concatToString()`.

## 3. Gate results (measured, `w1-gates-final.log`)

| task | target | suites | tests | failures | errors |
|---|---|---|---|---|---|
| `:rf8-core:jvmTest` | JVM | 1 | 15 | 0 | 0 |
| `:rf8-core:wasmJsTest` (results dir `wasmJsNodeTest`) | wasmJs/Node | 1 | 15 | 0 | 0 |
| `:fireline-engine:jvmTest` | JVM | 10 | 127 | 0 | 0 |
| `:fireline-engine:wasmJsTest` (results dir `wasmJsNodeTest`) | wasmJs/Node | 10 | 127 | 0 | 0 |
| `:engine:test` | JVM | 6 | 15 | 0 | 0 |
| `:app:testFreeDebugUnitTest` | JVM | 1 | 3 | 0 | 0 |
| `:app:testProDebugUnitTest` | JVM | 3 | 9 | 0 | 0 |
| `:fireline-app:testDebugUnitTest` | JVM | 7 | 51 | 0 | 0 |

Identical test counts on both targets (15 / 127): the same suites really run in wasm, not a
skipped source set. Kotlin writes wasm test results to `build/test-results/wasmJsNodeTest/`,
not `wasmJsTest/`; a `NO-SOURCE` on `wasmJsTest` means the `wasmJs { nodejs() }` runner is not
configured.

`BUILD SUCCESSFUL` for the combined run of all eight tasks plus `:fireline-engine:wasmJsNodeDevelopmentRun`.

## 4. Wasm toolchain wiring (what it took, and why)

The engine compiles to wasm out of the box. The toolchain plumbing was the work. Observed
failure modes, in order, and the fix that settled each:

1. `wasmJsTest` reported `NO-SOURCE` (tests silently skipped). Fix: `wasmJs { nodejs() }` in
   the target block; results then land in `wasmJsNodeTest`.
2. `Could not find org.nodejs:node:22.0.0`: the wasm Node setup resolves its own Node
   distribution from `https://nodejs.org/dist`, which the curated Maven mirror on this machine
   does not serve, and `FAIL_ON_PROJECT_REPOS` rejected the project repository the plugin adds.
3. `Could not find com.yarnpkg:yarn` (same shape, package manager).
4. Extension names (`kotlinWasmNodeJs`, `kotlinWasmNodeJsSpec`, `kotlinWasmYarnSpec`, ...) come
   from an internal platform disambiguator and are not public API: matching them from the
   Kotlin DSL is brittle, and `extensions.withType(...)` in a `.kts` script does not compile the
   way the Groovy docs suggest.

Settled configuration:

- `gradle/wasm-toolchain.gradle` (Groovy, applied from the root script) turns `download` off on
  `kotlinWasmNodeJsSpec` and `kotlinWasmYarnSpec` for every project. It runs both per-project
  `afterEvaluate` and a `gradle.projectsEvaluated` pass, because the root project's wasm toolchain
  extensions only appear while a subproject is evaluated (KGP applies its root plugins lazily).
  Logged line: `W1 wasm toolchain: download disabled for <project>/<extension>`.
- `gradle.properties`: `kotlin.js.yarn=false`. KGP's `NodeJsRootPluginApplier` applies the yarn
  plugin only when that property is true, so npm (bundled with the Node.js on PATH) is used
  instead and no yarn artifact is ever resolved.
- Consequence: the toolchain uses the Node.js already installed (v22.23.2 here, npm 10.9.8), and
  `RepositoriesMode.FAIL_ON_PROJECT_REPOS` could be restored to its original strict value.
- npm registry egress works on this machine (`kotlin-js-store/wasm/package-lock.json` resolved
  from `registry.npmjs.org`); the block was specifically on Maven-hosted node/yarn/binaryen
  artifacts, not on npm.

Still blocked on this host: `kotlinWasmBinaryenSetup` cannot fetch `com.github.webassembly:binaryen:123`
from the curated mirror, so the two `...Optimize` tasks fail. Consequence: the browser bundle is
the unoptimized wasm (1,233,651 bytes for the engine executable, 1,655,891 for the test
executable). Either enable server-side gzip (see `docs/DEPLOY-IIS-WASM.md` §3) or build the
optimized variant on a machine that can reach that artifact.

## 5. Determinism anchors reproduced in wasm

From `:fireline-engine:wasmJsNodeDevelopmentRun` (Node) and the same bundle in a browser
(Edge/Chromium 157):

```
OK   assembler                 errors=0 bytes=75 insns=25
OK   disasm listing            0000  TURN R
OK   tick                      tick=12
OK   rover at tick 12          x=2 y=7 dir=3
OK   last op                   lastOp=MOVE (1/2)
OK   ports                     FIRE_DIST=10 FIRE_LEVEL=35
OK   mid-run fingerprint       hash=113c566635ef3e13
OK   final tick/status         tick=102 status=SUCCESS
OK   final fingerprint         hash=47e28d84c5d61220
OK   runner == stepByStep      hash=47e28d84c5d61220
OK   failure anchor            tick=60 reason=TIME_EXPIRED hash=a64c17a9d85a6f75
W1 WASM RESULT: ALL ANCHORS OK
```

Every value is a committed anchor, not a new number: tick-12 state and `$113c566635ef3e13`
from `docs/web/fixtures/snapshots.json`, the mission 02 finish at tick 102 with
`47e28d84c5d61220` from the Milestone 7 record (`$47e28d84`), and the mission 01
`never shoots` anchor (TIME_EXPIRED at tick 60). `runner == stepByStep` also confirms that the
`RUN` path and the one-tick-at-a-time `STEP` path agree in wasm, which is what the console needs.

## 6. Browser proof

Harness: `docs/web/w1/` (`index.html` + `serve_local.py`), artifacts copied from
`fireline-engine/build/compileSync/wasmJs/main/developmentExecutable/kotlin/`.

- Positive: with `.wasm` served as `application/wasm`, the page prints `ALL ANCHORS OK`, loaded
  through `WebAssembly.instantiateStreaming(fetch(...))` in the glue.
- Negative control: the same files served with `.wasm` as `application/octet-stream` fail with
  `TypeError: Failed to execute 'compile' on 'WebAssembly': Incorrect response MIME type.
  Expected 'application/wasm'.` This is the empirical basis for the mandatory `.wasm` MIME map
  in `docs/DEPLOY-IIS-WASM.md` §1.
- A `.mjs` entry glue also needs a JavaScript MIME type (strict module-script checking): served as
  `text/plain` it is refused. Pinned to `text/javascript` in the harness server; check the same
  mapping on the production host.

Artifact inventory (dev build, unoptimized, 2026-11-20):

| file | bytes | md5 |
|---|---|---|
| `opcode-arena-fireline-engine.wasm` | 1,233,651 | `57943a28adf302f9a885c2aef57072f6` |
| `opcode-arena-fireline-engine.uninstantiated.mjs` | 12,193 | `b184266995fd5fb0393a286541514e3a` |
| `opcode-arena-fireline-engine.mjs` | 217 | `7bb63631774f4401f183c82475192520` |
| `custom-formatters.js` | 5,150 | `c51ada01a5a6f3f57b38d7a93b0a9eb1` |
| `opcode-arena-fireline-engine.wasm.map` | 54,779 | `f453e3a52b6aac02dc3ba1726c75bcba` |

The `.wasm.map` is a debug source map and stays out of any deploy.

## 7. Reproduce

```bash
export JAVA_HOME='C:/Program Files/Android/Android Studio/jbr'
./gradlew --console=plain \
  :rf8-core:jvmTest :fireline-engine:jvmTest \
  :rf8-core:wasmJsTest :fireline-engine:wasmJsTest \
  :fireline-engine:wasmJsNodeDevelopmentRun \
  :engine:test :app:testFreeDebugUnitTest :app:testProDebugUnitTest :fireline-app:testDebugUnitTest
```

## 8. What W1 does not prove

- Arithmetic parity is only proven for the paths these suites and anchors exercise (mission 01
  and 02 reference + failing firmware). The full five-mission fingerprint matrix and the
  `Long`/`Int` audit across wasm remain W2.
- No console UI: `W1HelloWasm.kt` is a spike entry point in `wasmJsMain`, scheduled for
  replacement by the `:fireline-web` shell in W3.
- Production artifact size and the optimized build are unresolved on this host (binaryen).
- The Android side was re-verified beyond unit tests: `:fireline-app:assembleDebug` and
  `:app:assembleFreeDebug` both build after the KMP switch (1,271,667 and 1,651,397 byte
  debug APKs, 2026-11-20). An on-device UI run after the switch belongs in the W2 sweep.

## 9. On-device regression after the KMP switch (2026-11-20, emulator-5554)

Both debug APKs reinstalled (`adb install -r -d`) and run on the Medium_Phone AVD.

FIRELINE (`robofight.fireline`, mission 02 CORRIDOR with `CORRIDOR-REF.ASM`):

| on-device step | screen readout | matches |
|---|---|---|
| STEP x 12 | `TICK 12 / 120`, `last: MOVE (1/2)`, `FIRE_DIST 10`, `FIRE_LEVEL 35` | fixtures tick-12 |
| STEP x 30 | `TICK 30 / 120`, `A 6`, `PC $12D`, `last: MOVE (1/2)`, `FIRE_DIST 6`, `FIRE_LEVEL 55` | Milestone 7 headless twin |
| RUN to completion | `MISSION COMPLETE`, `MISSION 02 / CORRIDOR TICK 102/120`, `HP 100 HEAT 64`, `firmware 75 B / 25 instructions`, `fingerprint $47e28d84` | engine anchor `47e28d84c5d61220` |
| RETRY + STEP x 102 | same result screen, `fingerprint $47e28d84` | RUN path == STEP path on device |

Opcode Arena free (`robofight.android`): fight ran to completion on the same build, status line
`WINNER: HUNTER`, HUD `HP 100 SH 12 / HIT 9 DMG 90 / HEAT 040`.

### Gate fix found by this sweep

A `--rerun-tasks` run failed in `:kotlinWasmStorePackageLock`: "Lock file was changed. Run the
`kotlinWasmUpgradePackageLock` task". Cause: forcing `kotlinWasmNpmInstall` to re-run regenerates
`package-lock.json`, and with no npm dependencies in this build the regenerated file differs only
cosmetically from the committed lock, which KGP reports as FAIL by default. Fix:
`gradle/wasm-toolchain.gradle` now sets the wasm npm extension's `packageLockMismatchReport` to
`WARNING` (log line `W1 wasm toolchain: packageLockMismatchReport = WARNING`), so the committed
lock stays byte-identical (`git status` clean after a rerun) while a real dependency change still
shows up. Verified: `--rerun-tasks` over all eight test tasks is `BUILD SUCCESSFUL` with every
test task executed (no UP-TO-DATE) and 362 tests total, zero failures.

### Defect found on device (open, not yet fixed)

`fireline-app/.../mission/MissionRunSurface.kt:327` builds the grid's accessibility description as
`... rover x <t.x> y <t.y> ...`, but `TelemetrySnapshot.x/y` are the **VM X/Y registers**
(`MissionSession.kt:117-118`, `x = vm.x, y = vm.y`), not the rover's world position. On mission 02
the description therefore reads `rover x 0 y 0` for the whole run while the rover is actually
moving on the grid (the visible telemetry and every engine value are correct). A screen reader
hears a wrong rover position and never hears the right one. Fix shape: add `roverX/roverY`
(`rover.x`, `rover.y`) to `TelemetrySnapshot` and use those in the description, keeping the RF-8
band's X/Y registers as they are.
