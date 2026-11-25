# Opcode FIRELINE in the Browser (web console concept, draft 1)

Status: concept, mockups and the W1/W2 build phases, 2026-11-19 to 2026-11-20.
Nothing here is committed to `main` yet: the build work lives on
`codex/web-w1-kmp-wasm` and `codex/web-w2-parity`.
**The layout is frozen for v1 (§15, owner decision 2026-11-19) and all build
decisions D1-D6 are DECIDED (§12a; D2-D5 decided 2026-11-20, §12b is empty). W1
and W2 are DONE, recorded in `docs/WEB-W1-RECORD.md` and `docs/WEB-W2-RECORD.md`.
W3 (`codex/web-w3-console`) is DONE and its gate set G1-G6 is satisfied, recorded in
`docs/WEB-W3-RECORD.md` (section 10 is the gate close-out; the matrix stands at 534 tests, zero
failures). **W4 (`codex/web-w4-integration`, landing page and hosting) is the next phase and is not
started: it needs an explicit go (D5 authorises W4 only on the owner's word).** This doc proposes scope, architecture
and integration. It does not change any locked spec; where it needs one, it
files a numbered amendment (see §11).

Product facts are owned by `FIRELINE-DESIGN.md` and `fireline-engine/`.
Visual facts are owned by `DESIGN-FIRELINE.md` (locked direction F "Firecall
Warm") and by the live landing page `https://opcode-fireline.appsystems.at/`.

## 0. Ground facts this concept builds on (verified this session)

- Module shape is already web-ready. `rf8-core` and `fireline-engine` are pure
  Kotlin/JVM libraries with no Android, filesystem or network dependency
  (`rf8-core/build.gradle.kts` header, `fireline-engine/build.gradle.kts`
  header: "No Android, filesystem or network deps").
- JVM-coupling scan of the main source sets found exactly one import outside
  `kotlin.*`: `java.util.Locale` in
  `engine/src/main/kotlin/robofight/challenge/ChallengeCodeFileName.kt`
  (Arena challenge codes, not needed by FIRELINE). `robofight.world` / Arena
  `:engine` is not needed by FIRELINE at all.
- Engine main code size: about 5,220 lines across `rf8-core` + `fireline-engine`
  (11 files in `fireline-engine/src/main`). Small enough that a port audit is
  feasible, but the plan below ports instead of reimplementing.
- Determinism is a shipped, cross-process contract already: the Milestone 7
  gate compared 30 `STEP` presses on the emulator against a headless kotlinc
  twin and matched (`TICK 30`, last MOVE (1/2), `A 6`, `PC $12D`, `FIRE_DIST 6`,
  `FIRE_LEVEL 55`), and mission 02 finished on both paths at tick 102 with
  fingerprint `$47e28d84`. Canonical hash inputs are already specified
  (`FIRELINE-DESIGN.md` 13.2: engine version, tick, rover state, row-major
  terrain/fire arrays, ordered civilians, ordered packets, objective state, both
  RNG states; no JVM object hash codes).
- Safety limits are already web-friendly: 1,000,000 tick hard cap
  (`FirelineRules.kt` `MAX_TICKS`), fixed 400-cell layout, bounded packet list,
  row-major fire processing, no unbounded queues (`FIRELINE-DESIGN.md` 13.4).
- Landing page tokens (read from the live page, 2026-11-19):
  `--field:#100b09 --screen:#16100e --panel:#211512 --line:#6c372c
  --text:#f2d9b0 --bright:#fff4dc --dim:#b38362 --yellow:#ffd23f
  --orange:#f2921e --red:#e63946 --cyan:#35d6d6 --green:#34ec5b`,
  body font `"Courier New",Courier,monospace`, fixed scanline overlay,
  bracketed nav `[HOME] [MISSION] ...`, hash-routing panels
  (`#home #mission #calls #tools #privacy`) driven by `/site.js`,
  `--cols:82rem`, and a console component
  (`.console > .titlebar + .consolebody > pre.map + pre.source + .telemetry`)
  that already shows a map, a source listing with the executed line marked, and
  a telemetry strip.
- Landing page copy already promises the exact loop the web console delivers:
  "SOURCE EDITOR + ASSEMBLER", "SINGLE-STEP DEBUGGER", "EVENT LOG",
  "REPEATABLE RUNS", and the hero command `FL:> PROGRAM THE RESPONSE`.
- Five authored missions exist with real parameters (`Missions.kt`):
  01 NOZZLE CHECK (seed 1001, limit 60, wind W 0, `EXTINGUISH_ALL`),
  02 CORRIDOR (seed 2002, limit 120, wind W 0, `EXTINGUISH_ALL|REACH_EXIT`),
  03 FIRST RESPONSE (seed 3003, limit 120, `RESCUE_ALL|REACH_EXIT`),
  04 DOWNWIND (seed 4004, limit 500, wind E 2, `RESCUE_ALL|EXTINGUISH_ALL|REACH_EXIT`),
  05 HOT ZONE (seed 5005, limit 200, starting water 60, wind N 0,
  `RESCUE_ALL|PROTECT_ASSETS|REACH_EXIT`). Each carries `referenceSource` and
  `failingSources`, which is exactly what a web tutorial needs.
- Starter firmware contract exists (`FIRELINE-DESIGN.md` 10) and must be
  assembled and exercised against mission 01 in a test.
- **W1 pre-probe (2026-11-19, throwaway scratch project outside the repo; the
  repo itself was not modified).** A scratch Kotlin Multiplatform spike pointed
  `wasmJs` at the real `:rf8-core` + `:fireline-engine` main sources to test
  exactly the §3.1 toolchain check:
  - Egress is green. `org.jetbrains.kotlin.multiplatform` plugin marker,
    `kotlin-stdlib-wasm-js` 2.2.10 and `kotlin-test-wasm-js` all arrive
    (HTTP 200); `kotlin-stdlib-wasm-js-2.2.10.klib` landed in the `~/.gradle`
    cache during the probe, and KGP 2.2.10 was already cached from the
    Android build.
  - The toolchain runs. The wasmJs target configures and `compileKotlinWasmJs`
    compiles the real engine sources (KGP 2.2 requires
    `wasmJs { binaries.executable() }` to expose the executable tasks).
  - The port audit is bounded: exactly nine wasm-hostile call sites in both
    main source sets, all display or debug strings. `String.format` (the wasm
    stdlib does not expose common `String.format`): `rf8-core` `Disasm.kt`
    (3 sites), `Vm.kt` (1), `fireline` `FirelineResult.kt`, `FirelineRun.kt`,
    `Mission.kt` (1 each). `String(CharArray)` (rejected by wasm):
    `fireline/Missions.kt` (2 sites), identical-output replacement
    `concatToString()`. No arithmetic, RNG, collection or hash site failed.
    The audit result is nine output-identical mechanical edits, not a port.
  - `node` v22.23.2 exists on this machine, so later `wasmJsTest` runs can use
    it instead of downloading KGP's own node distribution.
  - Hosting ground fact: the landing page host runs Windows Server 2016 with
    IIS (owner statement 2026-11-19); the consequences are recorded in §7.

## 1. What the web build actually is (positioning)

Three candidate framings, and only one is coherent with the product:

| framing | consequence | verdict |
|---|---|---|
| demo / teaser | toy firmware, canned runs, no real assembler | Rejects the product promise ("programming is the action"). A demo teaches nothing and converts nobody. |
| companion tool | editor only, runs happen on Android | Useful but half the loop; a browser that cannot run is a text box. |
| **FIRELINE as a second platform** | same engine, same missions, same determinism contract, same firmware files; the container differs | **Recommended.** Consistent with the multi-app discipline (one code dialect, engines in lockstep) and with the landing page, which already describes tools rather than a store page. |

Consequences of the recommended framing, stated up front because they are the
cost of the decision:

1. The web build must be *verifiably* the same simulation, not a lookalike.
   Every web run shows its fingerprint and can be replayed on Android.
2. The web build cannot claim "no network access". The honest claim is a two
   state model (§8): the page is fetched over HTTPS, and after load the
   simulation, storage and privacy posture are identical to Android.
3. The web build is not a funnel with accounts, ladders or telemetry. It is the
   same game in another container, which is also why it is allowed to exist
   next to a Play listing that promises "FREE. OFFLINE."

Working name: **Opcode FIRELINE WEB CONSOLE** (short forms `FIRELINE WEB`,
`FLW`). It deliberately does not create a new brand: it is the same product on
a second platform. Decision to lock: D1 (§12).

## 2. Scope gates for web v1

Web v1 = the full authoring loop, in the browser, for the shipped missions.

In v1:

- mission picker with all five authored missions (name, briefing, objectives,
  seed, wind, starting water, time limit, mission-local best result);
- firmware library in browser storage: new, edit, save, rename, duplicate,
  delete, select-for-run (mirrors `FIRELINE-DESIGN.md` 11.2 operations);
- source editor with line-level assembler errors from the real `:rf8-core`
  assembler (line number + message, before deployment);
- deploy, `RUN`, `STEP` (one world tick), `RESET`, run speed control, pause;
- live run screen: 20x20 text grid with the locked glyph/color language, RF-8
  state (A X Y SP PC, flags, current source line, in-progress multi-tick op),
  telemetry (HP, HEAT, WATER, SHIELD, FIRE_*, CIV_*, EXIT_*, WIND_*, MISSION),
  event log, objective progress and tick/time-limit readout;
- result screen with the `§7.5` explanation fields and `RETRY`,
  `EDIT FIRMWARE`, `MISSION LIST`;
- `.asm` import/export as UTF-8 files (download / file picker), so a firmware
  written on the web opens in the Android app and vice versa;
- run fingerprint display (`$xxxxxxxx`) plus a copyable permalink that encodes
  mission + firmware + seed so a run can be reproduced by anyone who opens the
  link;
- the RF-8/FIRELINE port cheat sheet, separated into base ports and FIRELINE
  peripherals with the 0/255 no-target sentinels;
- works offline after first load (service worker), keyboard-only operation,
  reduced-motion behavior, and grid state exposed as accessible text.

Explicitly out for v1 (post-v1 candidates in §10): accounts, cloud saves,
leaderboards, ladders, code golf, user-authored missions, mission codes shared
between players, sandbox seed editing, procedural layouts, campaign narrative.

Never on web (product posture, not scheduling): accounts or logins, ads,
analytics, third-party tracking, server-side execution of user firmware,
server-side storage of user firmware.

## 3. Architecture candidates

| option | what it is | pros | costs | verdict |
|---|---|---|---|---|
| **A. Port the engine: Kotlin Multiplatform -> wasmJs (+ js fallback)** | `:rf8-core` and `:fireline-engine` move their shared source into `commonMain`; a new `:fireline-web` module provides the wasm binary and a thin Kotlin/JS UI shell | One engine, one source of truth. Determinism is inherited, not hoped for. Existing 127 `:fireline-engine` tests + 15 `:rf8-core` tests run on the wasm target unchanged. Android builds untouched. | Gradle/Kotlin/Wasm toolchain must be reachable from this machine (Maven egress is a curated mirror; see the `robofight-android-build` skill). Kotlin/Wasm output is a few MB before gzip. UI code is new regardless. | **Recommended.** |
| **B. Reimplement in TypeScript** | hand-written JS assembler/VM/world | No new toolchain; small bundle; idiomatic web tooling | Two simulations that must agree tick for tick, forever. Directly contradicts the house rule that mechanics stay in lockstep, and multiplies the determinism test matrix. | Reject as primary. Keep only as an escape hatch if the wasm toolchain turns out to be unreachable in this environment. |
| **C. Server-run engine (API)** | browser sends firmware, server runs it, streams ticks | Tiny client, no porting | Breaks the offline/privacy pillar, needs servers and abuse controls, makes latency the core interaction, kills "run it on your machine, verify it yourself" | Reject. |

### 3.1 Recommended shape (Option A)

```
rf8-core/        commonMain  (ISA, Ports, Assembler, Disasm, Vm)      [source move, no semantic change]
fireline-engine/ commonMain  (Rules, Layout, Mission(s), World, Rover,
                              WaterPacket, Ports, Rng, Run, Result)   [source move, no semantic change]
fireline-web/    wasmJsMain  (run driver + DOM bridge)
                 commonTest  (existing suites run on jvmTest + wasmJsTest)
app/, fireline-app/           unchanged, keep jvmMain/androidMain source dirs
```

- This is a module-shape change of exactly the kind already done for Phase 3
  (`:rf8-core` extraction): a move with zero semantic change, gated by the same
  test matrices.
- The Android apps stay on the JVM/Android target. Nothing about the Play
  releases changes except that they now build from `jvmMain`/`androidMain`.
- `commonMain` discipline (the port audit): no `java.*`, no
  `kotlin.jvm.*`, no `System.*`, no filesystem, no `exitProcess` in main code.
  Current state is already compliant; the only known offender in the repo is
  `java.util.Locale` in the Arena challenge-code filename helper, which lives
  in `:engine` and is out of scope for FIRELINE web.
- Byte-level determinism audit (the real risk list): JS/wasm number semantics
  are IEEE-754 doubles, and Kotlin/JS and Kotlin/Wasm differ in how `Long` is
  backed. The engine's determinism-critical arithmetic is integer, including
  the FNV-1a hash (`FIRELINE_ENGINE_VERSION`, `FNV_OFFSET_BASIS`,
  `FNV_PRIME` in `FirelineRules.kt`) and the RNG streams in `FirelineRng.kt`.
  Required mitigation: (a) no floating point anywhere in world rules or RNG;
  (b) explicit 32-bit/64-bit semantics using `Int` where the algorithm is 32
  bit and a documented `Long` helper that stays exact below 2^53, or a small
  expect/actual helper if wasmJs proves `Long` exact (it uses native i64; the
  js fallback is the risky target); (c) pin expected fingerprints in golden
  tests, so any drift fails a build instead of shipping.
- Run driver contract: the web UI may only call the same three primitives the
  Android app uses (`RUN` = repeated tick, `STEP` = one authoritative tick,
  `RESET` = rebuild from descriptor + firmware). No second simulation path,
  no client-side interpolation that could diverge from the authoritative world
  (`FIRELINE-DESIGN.md` 11.5).
- Bundle plan: single page, no framework, no bundler required. Ship
  `fireline-web.js` + `fireline-web.wasm` (~a few MB, gzip-friendly), the
  mission data as generated JSON or as compiled constants, and one stylesheet
  derived from the landing page tokens. No third-party JS at all.
- Toolchain reality check: this is the one item that can block the project.
  Kotlin Multiplatform wasm needs the Kotlin plugin, the wasm toolchain and the
  `kotlin-test` artifacts from Maven. The repo's egress is a curated mirror, so
  W1 (see §9) is a spike whose only job is to prove the artifacts arrive on
  this machine and that the wasm test task runs. If it does not, the fallback
  order is: (1) vendor the toolchain from an internet-connected machine,
  (2) js target via IR, (3) Option B, explicitly documented as a divergence
  risk with a parity harness.
  **Update 2026-11-19:** the §0 pre-probe already cleared this check on this
  machine (the artifacts arrived and the wasm compile ran against the real
  engine sources). W1 stays a branch, but its gate softens from a kill/branch
  spike to a confirmation spike; the fallback order above remains documented
  as insurance and is currently unexercised.

## 4. Verification strategy (why this one is trustworthy)

The web build is accepted as the same game only if it passes parity gates:

| gate | what must match | where it comes from |
|---|---|---|
| G1 unit parity | all `:rf8-core` and `:fireline-engine` suites green on `jvmTest` AND on `wasmJsTest` | existing 15 + 127 tests, no new expectations |
| G2 golden traces | fixed-tick event traces per authored mission (`13.2` item 4) identical on both targets | recorded fixtures checked into `fireline-engine/src/test` |
| G3 fingerprint matrix | final-state fingerprint per mission x per reference firmware x per failing firmware identical on both targets | the `referenceSource` / `failingSources` already in `Missions.kt` |
| G4 cross-container | the emulator `STEP` sequence from Milestone 7 reproduces on web: same tick-30 field values and mission 02 finish at tick 102 with fingerprint `$47e28d84` | existing acceptance record |
| G5 file interop | a `.asm` written in the browser imports into `:fireline-app` and reproduces the same fingerprint, and vice versa | `FIRELINE-DESIGN.md` 12.2 plain `.asm` contract |
| G6 limits | 1,000,000 tick cap, layout size, source size limits enforced in the browser before allocation (`13.4`) | engine limits + web input parser limits |

G2/G3 are the load-bearing ones: they convert "the same engine" from an
architectural claim into a checked fact that survives future engine bumps,
because every fingerprint fixture stamps the engine version.

## 5. What the browser adds (only where it is genuinely better)

1. Zero-install onboarding. The landing page hero command
   `FL:> PROGRAM THE RESPONSE` currently points at a mission feed panel. With a
   web console it can point at a live mission. This is the single biggest
   marketing lever: the store listing says "FREE. OFFLINE. THREE MISSIONS." and
   the site can then say "and you can try it here, in 30 seconds, without
   installing".
2. Copy/paste and keyboard-first editing. Assembly is text. The Android editor
   is a phone; the browser is the natural place to write 60 lines, select,
   paste, and diff. Two containers, different strengths, same firmware files.
3. Instant shareable runs. A permalink carrying mission + firmware + seed makes
   "here is my solution, reproduce it" possible without accounts or servers.
   This is a strictly local-URL feature (see §8.3) and a natural precursor to
   the post-MVP `FL1-` mission codes (`12.3`).
4. Cross-checkability as a feature. Showing the fingerprint and the "verify on
   Android" affordance turns determinism from a spec sentence into something a
   player can demonstrate.
5. Devlog/teaching surface. Mission briefings plus reference firmware can be
   presented as annotated walkthroughs next to the runnable mission.

What the browser does not add and must not pretend to add: better simulation
(no), richer graphics (no, the text grid is the identity), multiplayer (no).

## 6. Web v1 surface and how it maps to the shipped app

| web panel | Android equivalent | notes |
|---|---|---|
| `#play` (landing page) | none (new) | invite + mission picker + starter firmware, one click to a running mission |
| `MISSION` | MISSION tab | mission card, objectives, seed/wind/water/limit readout, `RUN` `STEP` `RESET` |
| `FIRMWARE` | FILES tab | library, editor, assembler errors, cheat sheet, import/export |
| `RUN` | MISSION run screen | grid, RF-8 state, telemetry, events, objectives, tick readout |
| `RESULT` | RESULT tab | result fields, failure reason, `RETRY` / `EDIT FIRMWARE` / `MISSION LIST`, fingerprint |
| `PRIVACY` | privacy policy page | rewritten web wording (§8), same no-tracking posture |

Navigation follows the shipped app's three destinations (FILES / MISSION /
RESULT) plus a landing-page entry, so a player who later installs the app is
not learning a second product. The web console keeps the same rule that no
screen tap can move, turn, spray or shield the rover (`11.5`).

## 7. Integration with the landing page

The landing page is already a terminal SPA with hash-routed panels. The web
console slots into that pattern instead of being bolted beside it.

1. **Nav**: add one bracketed entry after `TOOLS`:
   `[HOME] [MISSION] [THREE CALLS] [TOOLS] [PLAY] [PRIVACY] [ARENA]`.
2. **Entry points** (progressive, low risk):
   - hero command `FL:> PROGRAM THE RESPONSE` becomes the primary path into
     `#play` (today it is a link to the mission feed panel; the feed panel
     keeps its role as the non-interactive explanation);
   - the `MISSION` panel console gains a `OPEN THIS IN THE CONSOLE` command
     under the telemetry strip;
   - the `CTA` block changes from "PREPARING FOR GOOGLE PLAY" to a two-line
     status: the Play status plus `PLAY IN YOUR BROWSER // NO INSTALL`.
3. **Panel design**: the `#play` panel reuses `.console`, `.titlebar`,
   `.consolebody`, `.status`, `.command` and the existing token set, so the
   playable panel is visually indistinguishable from the illustrative console
   that already sits in the `MISSION` panel. The transition from "look at a
   monitor" to "operate the monitor" is the entire pitch, and it should be
   seamless.
4. **Placement**: recommended v1 target is the existing domain under `/play/`
   (option B below), with the landing page embedding it in a panel whose
   console region is progressively replaced by the live app once the wasm
   binary has loaded. That keeps the landing page lightweight and indexable
   (no multi-MB payload for a reader) while still being one page for the
   player.

5. **Panel composition: FROZEN, see §15.** Summary of what the iteration
   converged on (the full normative text and the numbers are in §15): the `#play`
   panel is a landing page wrapped around the paired run surface, not a mission
   feed with a play button bolted on.

Hosting options (decision D2, §12):

| option | url | pros | costs |
|---|---|---|---|
| A: separate subdomain | `play.opcode-fireline.appsystems.at` | clean isolation, independent cache, no risk to the landing page | extra DNS/host, second cookie-less host to document |
| **B: same domain, path** | `opcode-fireline.appsystems.at/play/` | one host, one privacy story, `#play` nav can link there directly, sharing links look like the product | deploy step must not disturb the static landing page |
| C: embedded in the landing page itself | `.../#play` with the wasm loaded on demand | most seamless | landing page becomes an app shell, slower first paint, harder to keep the static page reviewable |

**DECIDED 2026-11-20 (D2): B for the app**, with `#play` on the landing page as
the invite and a progressive link into `/play/` (i.e. B now, C later if wanted).
A subdomain is rejected for v1; C stays available after W4 (see §12a).

Hosting facts (owner statement 2026-11-19): `opcode-fireline.appsystems.at` is
served by IIS on Windows Server 2016. This constrains the deploy task, not the
architecture:

- IIS does not serve `.wasm` unless the MIME type is registered, so the deploy
  adds `<staticContent><mimeMap fileExtension=".wasm"
  mimeType="application/wasm"/></staticContent>` (per-directory `web.config`
  or server configuration). Without it the wasm fetch fails with 404.3 and
  `WebAssembly.instantiateStreaming` never runs. This is the only mandatory
  server-side change.
- IIS dynamic compression provides gzip; brotli requires a third-party module.
  gzip is sufficient for the few-MB wasm/JS bundle (§13 size budget).
- Deployment is a static file copy into `/play/`, which matches option B's
  cost line: no server-side runtime, no node, no build step on the server,
  landing page untouched.
- HTTPS is already the access model (§8); the secure context required by the
  service worker rides on it.

## 8. Privacy model for the web build

The shipped privacy posture is a product pillar and the web build must not
silently erode it. Three states, stated plainly:

1. **Load**: the browser fetches the page, the stylesheet, and the wasm engine
   from `opcode-fireline.appsystems.at` over HTTPS. This is a normal static
   page fetch. No accounts, no cookies required, no third-party requests, no
   analytics, no fingerprinting scripts, no fonts from third parties (the page
   already uses system `Courier New` / monospace). Server access logs exist for
   any website and must be disclosed rather than denied.
2. **Play**: after load, everything runs locally in the browser tab. No
   network request is made to simulate, and the page works with networking
   disabled (service worker / `Cache-Control`, installable as a PWA if wanted
   later). User firmware never leaves the browser unless the player explicitly
   downloads a `.asm` file or copies a share link.
3. **Storage**: browser `localStorage` under a namespaced key
   (`fireline.web.v1`), holding only: firmware sources and names, selected
   mission/firmware, last result, per-mission best result per engine version,
   editor preferences. No server-side copy. Clearing site data or the in-page
   `DELETE ALL LOCAL DATA` action removes it. Export/import via files, mirroring
   the Android SAF story.

Share links: encode mission id, seed override, and firmware source in the URL
**fragment** (`#run=...`), not the query string. Fragments are not sent to the
server, so they never appear in access logs. Compression: gzip-then-base64url
with a short versioned prefix (`FLW1-`), with the same kind of length and
unknown-opcode limits planned for `FL1-` codes (`12.3`). The link is a
reproduction recipe, not a saved game: opening it re-runs the simulation from
the descriptor.

Copy rules (to stay honest):

- Allowed: "runs entirely in your browser", "no account, no ads, no analytics,
  no tracking", "your firmware stays on your device unless you export it",
  "the page is downloaded once from our site".
- Not allowed: "offline app", "no network access", "no data leaves your
  device" (untrue at load, and share links knowingly leave by user action).

Amendment A-8 in §11 covers the wording split between the Android and web
privacy pages.

## 9. Phased plan (W0..W4), one short-lived branch each

Follows the house pattern (`PLAN-RF8-MULTIAPP.md` 3.1): each phase is a
`codex/<topic>` branch, headless gate first, then the Gradle matrix, then a
status block in the plan.

- **W0, concept and mockups** (this document + `docs/web/mockups/`).
  Gate: user decision on D1-D4 (§12).
- **W1, toolchain spike** (`codex/web-w1-kmp-wasm`).
  Add KMP targets for `:rf8-core` and `:fireline-engine` with `commonMain`
  moves only (zero semantic change), run existing suites on `jvmTest` and
  `wasmJsTest`, and produce a hello-wasm page assembled from the real
  assembler. Gate: artifacts reachable on this machine, both test tasks green,
  wasm output loadable in a browser. Kill/branch decision recorded here.
  **DONE 2026-11-20, gate met, decision: keep Option A and go to W2.** Record:
  `docs/WEB-W1-RECORD.md` (15 + 127 tests identical on jvm and wasmJs, the
  committed mission 02 anchors reproduced in wasm, browser load proof plus the
  wrong-MIME negative control in `docs/web/w1/`). The engine port cost was
  twelve output-identical formatting sites (nine in main sources, three in
  suites); the toolchain cost was `gradle/wasm-toolchain.gradle` plus
  `kotlin.js.yarn=false` in `gradle.properties`.
- **W2, determinism parity** (`codex/web-w2-parity`).
  Golden trace + fingerprint fixtures for all five missions (reference and
  failing firmware), arithmetic audit of `Long`/`Int` semantics on wasmJs and
  js, and the `MAX_TICKS` / source-size limit enforcement on the web input
  path. Gate: G1-G3, G6 green on both targets; fingerprint matrix checked in.
  **DONE 2026-11-20, gate met.** Record: `docs/WEB-W2-RECORD.md`. The matrix was
  10 rows (5 missions x reference + failing firmware) plus 120 golden trace
  lines at that step (16 rows and 192 trace lines since mission 06,
  `docs/web/fixtures/README.md`), generated from live engine state by `docs/web/fixtures/ParityMatrix.kt`
  and pinned by `parity_fixture_gen.py`; 29 arithmetic vectors and the
  `WebInputLimits` refusals (1200 lines / 8192 bytes / `MAX_TICKS`) are asserted
  by 19 new `commonTest` tests that run identically on `jvmTest` and
  `wasmJsTest` (146 engine tests per target, 392 tests green across the
  close-out matrix). `js` stays a documented fallback: the pinned suites are in
  `commonTest`, so adding a js target asserts it with no new test code.
- **W3, web console v1** (`codex/web-w3-console`).
  `:fireline-web` shell: mission picker, editor with line-level assembler
  errors, grid/telemetry/event renderer, `RUN`/`STEP`/`RESET`, result screen,
  `.asm` import/export, `localStorage` library, cheat sheet. Gate: G4 emulator
  sequence reproduced on web (tick 30 field values, mission 02 finish at tick
  102, fingerprint `$47e28d84`), G5 roundtrip with `:fireline-app`, accessibility
  and reduced-motion pass, 48dp-equivalent touch targets on touch screens.
  **Scope decided 2026-11-20 (D3, D4): all five authored missions, and the full
  loop including `.asm` import/export and `#run=` share permalinks. Gate G5 is
  therefore part of the W3 gate, not deferred to W4.** **DONE 2026-11-22, gates
  G1-G6 satisfied (see `docs/WEB-W3-RECORD.md` §10): the console drives the shared
  `MissionSession`, all five authored missions run in the browser, the tick 12/30/46
  bands and the mission 02 finish at tick 102 match the committed fixtures field for
  field in both containers, and `.ASM` bytes round-trip app -> browser -> app with one
  shared fingerprint. Under engine v2 the fingerprint anchor is `$d3ea4711`
  (`parity_matrix.json` `2-reference`); the `$47e28d84` above was the engine v1 value.**
- **W4, landing page integration** (`codex/web-w4-integration`).
  `#play` panel, nav entry, hero command rewiring, `/play/` deploy task,
  share permalinks, web privacy page. Gate: landing page static rendering
  unchanged for non-JS readers and for crawlers, Lighthouse-style performance
  budget, no third-party requests introduced. **Hosting decided 2026-11-20 (D2):
  option B, `opcode-fireline.appsystems.at/play/`, with `#play` on the landing
  page as the invite and a progressive link into `/play/`.**
- **W5+ (optional)**: permalink -> `FL1-` mission codes, walkthrough content,
  sandbox seed editing, PWA install. Only after W4 is stable.

Estimated effort shape: W1 and W2 are the risky engineering (toolchain,
arithmetic, fixtures). W3 is bulk UI work that no existing module constrains.
W4 is small. A rough read: W1 a session or two, W2 a session, W3 the largest
(single biggest chunk, likely several sessions), W4 a session.

## 10. Post-v1 candidates (not commitments)

- `FL1-` mission codes and cross-platform challenge runs (pulls `12.3` forward
  because the browser is where sharing naturally happens).
- Sandbox mode with seed editing inline in the mission header (already resolved
  as a UI decision in the visual design, unresolved as a feature).
- Walkthrough mode: mission briefing, reference firmware, annotated tick trace
  (the reference sources already in `Missions.kt` make this cheap).
- Local-only ladders and code golf (fewest instructions / least water per
  mission), stored locally, exported by the player. No leaderboard server
  unless the product posture changes deliberately.
- Shared editor/storage library between `:fireline-app` and `:app`
  (`15` last bullet) once both prove the abstraction.
- Keyboard/ergonomics features unique to web: line numbers, jump to
  assembler error, firmware diff between two saves.

## 11. Numbered amendments proposed (nothing applied yet)

- **A-7 to `FIRELINE-DESIGN.md` §11**: add §11.7 "Web console surface"
  describing the panel mapping in §6 and the rule that the web build may only
  use the `RUN`/`STEP`/`RESET` primitives of the authoritative world path.
- **A-8 to `FIRELINE-DESIGN.md` §12.1**: persistence gains a container column:
  Android app-private storage vs browser `localStorage` under
  `fireline.web.v1`, same field set, same "no mutable world as authoritative
  replay" rule.
- **A-9 to `FIRELINE-DESIGN.md` §14**: acceptance list gains a web gate
  (G1-G6 of §4) that does not alter the Android gates.
- **A-10 to `PLAN-RF8-MULTIAPP.md`**: add Phase 7 "Browser target (W0..W4)"
  with the W1 kill criterion (toolchain unreachable -> stop and re-decide, do
  not fork the engine) and the rule that `commonMain` moves carry zero semantic
  change and must keep byte-level fixture `FirmwareFixturesTest` untouched.
- **A-11 to `DESIGN-FIRELINE.md` §2.2b**: add web token bindings for the
  landing page palette (see §7.1 of this doc and the mockups), including the
  wall token (`#8a6d5e` from direction F) which the landing page does not yet
  carry, and confirm the landing page's `--screen:#16100e` as the field for web
  panels (the landing page base is already the family base shifted warm).
- **A-12 to `STORE-LISTINGS.md`**: note that the web console exists, so the
  store copy's "OFFLINE" claim is scoped to the Android app and mirrored by the
  web copy rules in §8.
- **A-13 to `DESIGN-FIRELINE.md`**: once the web build is scheduled, carry the
  frozen v1 layout (§15) into the visual spec as a new section: the paired run
  surface, the two full-width readout bands, the single control block, the
  single-occurrence rule and the flush-pairing rule. Until then §15 is the only
  home of these rules and the mockup is their reference rendering.

## 12. Decisions

### 12a. Decided

- **D6 layout (DECIDED 2026-11-19)**: the layout in §15 is frozen for v1. The
  golden master is `docs/web/fl-web-5-playable-landing.html`; changes need a
  numbered amendment.
- **D1 name (DECIDED 2026-11-19, follows the frozen mockup)**: `FIRELINE WEB
  CONSOLE`, with the panel label `FL:> LIVE CONSOLE // RUNS IN YOUR BROWSER`.
  `Opcode FIRELINE ONLINE` stays rejected because "online" fights the product
  language.
- **D2 hosting (DECIDED 2026-11-20): option B, same domain under `/play/`.**
  `opcode-fireline.appsystems.at/play/` carries the console as a static copy,
  `web.config` in that directory registers the `.wasm` MIME type, and `#play` on
  the landing page stays a progressive invite (a link plus the frozen panel) that
  hands off to `/play/` once the engine loads. Consequences: one host, one
  privacy story, share links read as the product, gzip only (IIS 2016, no brotli
  module), and the W4 gate is about not disturbing the static landing page rather
  than about running an app inside it. Option A (subdomain) is rejected: a second
  host buys isolation this project does not need and doubles the privacy copy.
  Option C (engine embedded in the landing page) is deferred, not forbidden: it
  becomes possible after W4 if the first-paint and reviewability gates can still
  be met.
- **D3 v1 mission set (DECIDED 2026-11-20): all five authored missions.**
  They already carry reference and failing firmware, all ten combinations are
  pinned in the W2 fixture matrix (`docs/web/fixtures/parity_matrix.tsv`), and
  `Missions.ALL` is compiled into the wasm binary, so five missions cost the same
  bytes as two. The web console and the Android app therefore present the same
  five, which removes the content-drift risk in §13. Missions 04 and 05 stay in
  v1 because they exercise the console rather than just pass: the 147-tick wind
  solve under a 500-tick guard, and the destructive-fire failure path.
  - **Note 2026-11-22 (engine v2, `PLAN-EDGE-WALLS.md` §13):** the authored catalog is six
    missions since `06 THIN WALL` was added, the first authored mission that carries thin edge
    walls. The decision's logic is unchanged and its wording above is kept as decided: the
    console enumerates `Missions.ALL`, so both surfaces present the same missions, and the
    fixture matrix now pins all twelve mission combinations (16 rows, `docs/web/fixtures/README.md`).
- **D4 v1 depth (DECIDED 2026-11-20): the full loop.** Editor with line-level
  assembler errors, run surface, `.asm` import and export, `localStorage`
  library, cheat sheet, and `#run=` share permalinks (fragment-only, `FLW1-`
  prefix, reproduction recipe not save game, §8). Import/export is load-bearing:
  it is gate G5 (browser `.asm` -> `:fireline-app` with identical fingerprint, and
  the reverse), so **G5 moves into the W3 gate**. If W3 effort pressure appears,
  cut permalinks first; do not cut import/export, because that is both the
  cross-container proof and the mitigation for the `localStorage` data-loss risk.
- **D5 engine route and W3 go (DECIDED 2026-11-20).** Option A (Kotlin
  Multiplatform -> wasmJs) is confirmed on this machine: W1 proved the real engine
  compiles to wasmJs and both test targets run the same suites, W2 proved
  cross-target arithmetic parity (`docs/WEB-W1-RECORD.md`, `docs/WEB-W2-RECORD.md`).
  `js` stays a documented fallback, and Option B (separate JS engine) stays
  rejected. The owner's D2/D3/D4 scope decisions of 2026-11-20 authorize W3; W4
  proceeds only after the W3 gate is met. The remaining unknown is effort, not
  architecture.

### 12b. Open

None. D1-D6 are decided (see 12a). What is still unknown is W3 effort (the shell
is the largest single piece of the web route) and whether W4 is scheduled after
the W3 gate.

## 13. Risks

| risk | impact | mitigation |
|---|---|---|
| KMP/wasm artifacts unreachable through this machine's curated Maven egress | W1 blocked | pre-probed 2026-11-19 (§0) and confirmed in W1: the engine compiles to wasm and both test targets run. What actually had to be worked around was the toolchain's own artifact downloads (node dist, yarn, binaryen), fixed for node and yarn in `gradle/wasm-toolchain.gradle` + `kotlin.js.yarn=false`; binaryen is still unreachable, so the `...Optimize` wasm tasks do not run on this machine (see `docs/WEB-W1-RECORD.md` §4). Fallback order (vendored toolchain, js target, Option B) kept as insurance |
| `Long` / integer arithmetic drift on wasmJs or js | silent determinism divergence, worst failure mode of the project | no floats in rules/RNG, arithmetic audit in W2, pinned fingerprints (G2/G3), keep wasmJs primary (native i64) and js only as fallback. **Audited 2026-11-20 (W2, `docs/WEB-W2-RECORD.md` §5): 0 `Float`/`Double`/`Math`/`kotlin.random` sites in engine `commonMain`, all 64-bit work is the LCG step and the FNV loop, and 29 pinned vectors plus the 10-row matrix and its 120 pinned trace lines are identical on `jvm` and `wasmJs` (19 W2 tests)** |
| Two UIs drifting from each other over time | player confusion, double maintenance | panel mapping in §6, shared copy, shared cheat sheet text, one mission data source generated once |
| Landing page turns into an app shell | slower first paint, worse crawlability of a currently clean static page | `/play/` path for the app, static landing page untouched except nav + copy, wasm loaded only on `#play` |
| Big initial download (few MB wasm) | cold-start friction on mobile | gzip/brotli, long cache, size budget gate in W4, plain HTML mission picker before the engine arrives |
| IIS on Windows Server 2016 serves `.wasm` as unknown MIME (404.3) | the web console never boots in the browser | `web.config` MIME map as a deploy task (§7 hosting facts); W4 gate HEADs the wasm URL and requires `application/wasm` |
| Users lose browser firmware when clearing site data | real data loss | prominent export affordance in the editor, `localStorage` quota check, export reminder in the result screen |
| Scope creep toward accounts/ladders | breaks the privacy pillar the product is built on | §2 "never on web" list treated as a spec gate, not a preference |

## 14. Mockups

Generated by `docs/web/gen_web_mockups.py` (one run regenerates all five). The
palette is the live landing page token set plus locked direction F tokens
(`DESIGN-FIRELINE.md` 2.2b) for the world layer; every world state, register
value, event string and fingerprint in the mockups is read from headless engine
snapshots, not written by hand. **Mockup 5 is the frozen golden master for the
v1 layout (§15).**

- `docs/web/fl-web-1-play-panel.html` - landing page `#play` panel with the new
  nav entry, mission picker, starter firmware and a live mission monitor at
  engine tick 15 (water packet in flight), at the landing page's 82rem column.
- `docs/web/fl-web-2-console-midrun.html` - full web console running
  mission 02 / CORRIDOR at engine tick 46, with editor, grid, RF-8 state,
  telemetry, run stats, event log, controls and the run link.
- `docs/web/fl-web-3-result-failure.html` - RESULT panel for a real
  failing run (`TIME_EXPIRED`) with the `7.5` explanation fields.
- `docs/web/fl-web-4-mobile.html` - same console collapsed to a phone
  width, to check ordering and touch control size.
- `docs/web/fl-web-5-playable-landing.html` - **combination of web-1 and web-2**:
  the landing page's framing (hero, release status, choose-a-call cards, status
  strip, privacy block) carrying web-2's live monitor, with the world and the
  firmware kept side by side as the primary surface. Controls sit under the
  firmware column, telemetry is a horizontal strip under the world column.

Preferred composition (owner decision 2026-11-19, after reviewing web-1 and
web-2): the **paired run surface** is the product's core layout. The 20x20 world
on the left and the firmware you wrote on the right, at the same height, so the
player reads cause and effect across one gutter. Everything else (mission picker,
telemetry, event log, result fields) is secondary chrome around that pair. The
generator enforces this with one shared `console_pair()` builder, used by both
mockup 2 and mockup 5, so the pairing cannot drift between mockups.

### 14.1 How the mockups were verified

Record status 2026-11-21: the fingerprints in this section, and the `$47e28d84` values cited
in §2, §4 gate G4 and §9 W3, were measured under engine v1. `FIRELINE_ENGINE_VERSION` is 2
(`FirelineRules.kt:21`) since the edge-wall step E1b (`PLAN-EDGE-WALLS.md` section 11), which
moved every state fingerprint. The measurements below are kept as recorded; the current values
are in `docs/web/fixtures/parity_matrix.tsv` and `docs/web/fixtures/snapshots.json`. §13 to §15
are the frozen layout spec and were not touched.

- Snapshot harness: `docs/web/fixtures/SnapshotFixtures.kt` (plus
  `CrossCheck.kt`, `verify_snapshots.py`, `verify_crosscheck.py`, `README.md`
  with the exact kotlinc/JBR commands). It compiles `:rf8-core` +
  `:fireline-engine` main sources headlessly and reads state after exactly N
  `tickOnce()` calls (one call = one STEP). Fingerprints come from
  `FirelineWorld.finalStateHash()`, the same function the Step9/Step10
  determinism tests assert.
- Cross-check: every final state was produced twice, once through the headless
  runner and once step by step (`runner==stepByStep:true`), and two generator
  runs produced byte-identical fixture JSON.
- Cross-container check: mission 02 with the reference firmware finishes at
  tick 102 with fingerprint `47e28d84c5d61220`, matching the Milestone 7
  acceptance record `$47e28d84` measured on `emulator-5554` (`PLAN-RF8-MULTIAPP.md`
  9.6). The engine therefore produced the mockup's world on the same path that
  the Android app used.
- Fixture results: mission 01 reference firmware succeeds at tick 17
  (`198645dc2e7e9649`, 102 B / 34 instructions); mission 01 with the documented
  `never shoots` firmware fails at tick 60 = timeLimit (`a64c17a9d85a6f75`,
  fire spread to 2 cells, total intensity 145).
- Grid parity: mockup 2 renders 400 cells, 26 walls, 2 refills, 1 exit, 1 rover
  and 1 burning cell, matching the engine's tick-46 terrain exactly (a rover or
  fire replaces its cell's glyph, per the documented layer order).
- Fire glyph semantics: the mockups use the engine's own live-intensity display
  mapping from `TextGrid.fireGlyph` (`1`=1..24, `2`=25..49, `3`=50..74,
  `4`=75..100), not the authoring level mapping used in layout source rows
  (`1`=25 ... `4`=100). Exact intensities per burning cell stay available in the
  fixture (`fire_cells`, `fire_intensity_total`); the mockup legend states the
  bands so a reader is not misled.
- Rendering: each mockup was measured in a real browser at 1312px and 420px CSS
  width: no horizontal overflow, grid 332px (15px cells) desktop and 246px
  (11px cells) phone, RUN/STEP/RESET buttons 74-90 x 49px (above the 48dp
  minimum already enforced on Android). In mockups 2 and 5 the world column and
  the firmware column measure the same top offset (635px at 1312px width), which
  is what "side by side" means here, and the pair stacks only below 900px.
- Palette: audited against the house gates (text >= 4.5, non-text >= 3.0 on both
  field and panel, fire ramp monotone under protanopia/deuteranopia) with
  `palette_audit.py`; results in 14.2.

### 14.2 Palette audit (computed 2026-11-19, `palette_audit.py`)

Backgrounds audited: landing page `--field #100b09`, `--screen #16100e`,
`--panel #211512`, grid backdrop `#2a1e1a` (direction F `panel-2`).

Gate 1, text tokens (WCAG body text >= 4.5):

| token | hex | #100b09 | #16100e | #211512 | #2a1e1a | verdict |
|---|---|---|---|---|---|---|
| chrome text | `#f2d9b0` | 14.27 | 13.75 | 12.98 | 11.80 | pass |
| emphasized value | `#fff4dc` | 17.90 | 17.25 | 16.28 | 14.80 | pass |
| secondary label | `#b38362` | 5.90 | 5.69 | 5.37 | 4.88 | pass |
| eyebrow small caps | `#f2921e` | 8.29 | 7.99 | 7.54 | 6.86 | pass |
| RUN label on RUN fill | `#120a0a` on `#e63946` | 4.69 | | | | pass |

Gate 2, world tokens (non-text >= 3.0 on both console field and grid backdrop):

| token | hex | #16100e | #2a1e1a | verdict |
|---|---|---|---|---|
| rover | `#ffd23f` | 13.05 | 11.19 | pass |
| water / refill | `#35d6d6` | 10.53 | 9.04 | pass |
| exit / facing caret | `#34ec5b` | 11.93 | 10.23 | pass |
| wall | `#8a6d5e` | 3.97 | 3.41 | pass |
| fuel | `#8d6a1e` | 3.78 | 3.24 | pass |
| civilian | `#fff1e0` | 16.97 | 14.56 | pass |
| protected asset | `#c77dff` | 7.00 | 6.01 | pass |
| fire 1 / 2 / 3 / 4 | `#ffb02e` `#f2921e` `#e96a2a` `#e0303c` | 10.32 / 7.99 / 5.89 / 4.18 | 8.85 / 6.86 / 5.05 / 3.59 | pass |
| empty-cell dot | `#4a382e` | 1.70 | 1.46 | decorative, exempt |

Gate 3, fire ramp monotonicity: raw grays `134, 101, 71, 47`, protanopia
`171, 138, 106, 71`, deuteranopia `189, 156, 127, 94`. All three monotone
decreasing, so `1 < 2 < 3 < 4` ordering survives every simulation.

Findings and decisions:

1. The only failing token is the empty-cell dot. It stays decorative (house
   convention: glyph shape carries meaning, the dot is texture), matching
   `DESIGN-FIRELINE.md` 2.2b. A verified retune exists if a literal gate is ever
   required: `#896755` (3.71 / 3.18), but it nearly collides with the wall token
   `#8a6d5e`, which would weaken the wall read. Decision: keep `#4a382e` and the
   exemption, do not brighten.
2. Tightest text case: the secondary label `#b38362` at 4.88 on the grid backdrop.
   Keep secondary labels on panels, not on the grid backdrop, if any background
   is darkened later.
3. `RUN` label/fill pair passes at 4.69 with no headroom. Do not lighten
   `#e63946` for hover/active states without re-auditing.
4. Tooling note: the skill's `scripts/palette_audit.py` had a syntax error on
   disk (missing closing parens) and was repaired during this audit, so the gate
   is runnable again for future mockup work.

## 15. FROZEN LAYOUT SPEC v1 (owner decision 2026-11-19)

Status: **FROZEN**. This section is normative for the web v1 layout. Changing
anything here requires a numbered amendment (§11) that states what changed and
why; silent divergence between a build and this section counts as a defect.

### 15.1 Canonical artifacts

| role | path |
|---|---|
| visual golden master (what v1 must look like) | `docs/web/fl-web-5-playable-landing.html` |
| executable layout source (CSS and structure) | `docs/web/gen_web_mockups.py` (`console_pair`, `source_pane`, `run_controls`, `fields_band`, `telemetry_fields_html`, `vm_fields_html`, `statusbar`, `m5_combined`, `CSS`) |
| data those mockups are drawn from | `docs/web/fixtures/snapshots.json` (headless engine snapshots) |

Regenerate with `python docs/web/gen_web_mockups.py`. The mockup and the
generator must be updated together; a design change that lands in one but not
the other is not a change, it is drift.

### 15.2 Region order (top to bottom, `#play` panel)

1. `FL:> LIVE MONITOR // MISSION nn / NAME // TICK n` prompt.
2. **Live monitor** (`.console` > `.consolebody`): titlebar (`MISSION nn//NAME`,
   `T+0000nnnn`) (shell since 15.2a item 11: no titlebar), mission header (name, firmware name
   and config, seed), the objectives line, then the **paired run surface** (`.pair`):
   - **left, world column**: `WIND`, the 20x20 grid, the symbol legend, `EVENT LOG`.
   - **right, firmware column**: `FIRMWARE: NAME.ASM` source pane, the single
     control block (see 15.2 item 2 and 15.4), assembler note.
3. **RF-8 STATE band** (full width). Cells: `A`, `X`, `Y`, `SP`, `PC` (with its
   label), `LAST OP`, `FLAGS Z`, `FLAGS N`, `FLAGS C`, `OP TICKS LEFT`,
   `VM FAULT`, `TICK`. 12 cells.
4. **TELEMETRY band** (full width). Cells are the engine readout port names,
   plain, in this order: `FIRE_DIST`, `FIRE_ANGLE`, `FIRE_LEVEL`, `LOCAL_FIRE`,
   `SMOKE`, `CIV_DIST`, `CIV_ANGLE`, `CIV_COUNT`, `EXIT_DIST`, `EXIT_ANGLE`,
   `WIND_DIR`, `WIND_FORCE`. 12 cells. No `PORT 16`-style prefixes, no base
   vitals cells, no `MISSION` objective-bits cell.
5. **Status line** (read-only, directly below TELEMETRY): left `VITALS HP /
   WATER / HEAT / SHIELD`, right `STATE tick n fingerprint $hex`.
6. `WHAT THE PORTS MEAN` and `RUN STATS` (two columns, label-first tables).
7. `FL:> SELECT A CALL` mission cards.
8. `FL:> RELEASE STATUS` block. (console shell: removed 2026-11-24, see 15.2b 1; the 15.1
   golden master keeps it.)
9. Four-cell strip: `ENGINE`, `DETERMINISM`, `STORAGE`, `NETWORK`.
10. `PRIVATE BY DESIGN, ALSO IN THE BROWSER` block, then the footer with the
    token chips and the engine-snapshot provenance line.

Above the monitor sits the hero (eyebrow, `PROGRAM THE RESPONSE`, lede, current
call prompt, three commands that are not run commands).

### 15.2a Dated shell deviation (2026-11-23, owner-directed, `docs/WEB-W3-RECORD.md` 11)

The live browser console (`fireline-web/console.html`, wasm shell) moves or removes regions and
re-anchors the two run columns. The golden master, `console_pair()` / `statusbar()` / `run_controls()` /
`events_html()` of `docs/web/gen_web_mockups.py`, and the mockups below were NOT moved by
this change, so §15.6 item 1 is met by the mockups and NOT by the console shell until the
master and the generator are amended in step (a documented open item, not silent drift):

1. **RF-8 STATE band**: no longer a full-width band; it renders inside the firmware column,
   directly under the source pane, controls and assembler note, at that column's width
   (`minmax(320px,1.1fr)`), flush left and right with the firmware field.
2. **Status line**: no longer directly below TELEMETRY; it renders inside the world column,
   directly below the 20x20 grid (before the symbol legend), at that column's width
   (`minmax(0,1fr)`). The 15.3 lock `TELEMETRY to status line: 13px gap` does not apply to
   the shell; the shell lock is `grid bottom to status line: same column, 0.6rem gap`.
3. **Share `LINK`** (with `COPY` and `DOWNLOAD .ASM`): no longer a line of the control block;
   it renders as its own full-width block (`.fields.linkblock`) directly below the TELEMETRY
   band, and only while a run state is shared. 15.4's single-occurrence rule keeps holding
   (one `LINK`, one `COPY`, one `DOWNLOAD .ASM` on the page), only its "only place" column
   changes for this row.
4. **`next:` / `exec:` preview line**: removed from the shell entirely. The instruction in
   flight is already carried by the `>` marker in the firmware pane, the `LAST OP` and
   `OP TICKS LEFT` cells of the STATE band and the event line on the prompt row, so the
   console shows no `next:` text at all.
5. **Assembler note**: last in the firmware column, under the RF-8 STATE band, instead of
   directly under the control block. It stays the one `0 errors` / editor-error line of §12a
   and keeps its id.
6. **EVENT LOG**: the log section is gone from the shell, together with its heading and its
   `<ul>` element. The `SPEED 1 / 5 / 20 / 50 ticks per second` choices and
   `PANIC uncapped, no speed limit` (the §15.4 controls with the master's wording) survive in
   the pacing row described in item 8. Recorded consequence: the failure reason name
   (`TIME_EXPIRED`) was previously visible on screen only as the last log line; the page now
   shows failure through the `failed` objective chips, while the reason stays available in the
   model and in the share link.
7. **Equal half-width columns, anchored on the grid square**: the pair grid is
   `minmax(0,1fr) minmax(0,1fr)` with the 1.2rem gap, so each column is half of the console
   body's content width minus half the gap, and the grid fills its whole column. Everything
   else derives from that one anchor (`console.html` custom properties):
   `--col = (100vw - 74.56px - 19.2px) / 2` (74.56px is the body margin, page padding, console
   border and consolebody padding chain, 19.2px is the 1.2rem gap), `--cell = (col - 5px) / 21`
   clamped to 11..34px, `--grid-h = 21 * --cell + 2px`.
7a. **Row alignment**: the RF-8 STATE band's top edge equals the VITALS/STATE status line's top
   edge in the two-column layout. The pane is sized for it, `--pane-h = max(180px, --grid-h -
   --row-offset)` with `--row-offset: 84px` (measured 2026-11-23; it was 100px while the pacing
   row was two lines tall at 56px, see item 9 and `docs/WEB-W3-RECORD.md` 14). The world side's
   allowance is the 25px the firmware column's heading adds at the top of the other column plus
   that same 84px: the status line sits 109px below the grid's bottom edge (12.8px margin, 17px
   heading, 8px heading margin, minus the 8px that collapses against the status line's margin),
   and under the pane the firmware column carries 84px of its own chrome (collapsed 4px gap +
   34px run row + 8px gap + one 28px pacing line + 8px pacing margin, collapsed against the
   band's own margin). Measured at viewport 1370: grid 613x635, pane 551, status line top and
   STATE band top at the same y (delta 0px). Consequences, recorded rather than hidden: the firmware column ends
   ~123px lower than the world column, because the world column's last block is the 58px symbol
   legend while the firmware column's last block is the 56px assembler note plus the 123px of
   chrome the run row and pacing row add above the band; and in EDIT mode the firmware column
   carries 56px more chrome above the band (the editor name row), so the band sits 56px lower
   there. The frozen 15.3 ratio survives as the 21-cell structure; its absolute 15px cell and
   316px pane no longer apply to the shell. Collapsed mode (pair grid to one column under 900px)
   keeps 11px cells, the 233px pane floor and does not honour the anchor.
8. **Pacing row without a box**: `SPEED` and `PANIC` render as a plain row (`.pacing`) directly
   under the run row inside the firmware column, with no panel border and no panel background,
   and with smaller buttons: speed/PANIC chips 28px tall, run buttons 34px minimum on desktop.
   The 49dp gate keeps holding for touch pointers through the `pointer: coarse` block.
9. **Pacing rows, each on one line** (owner rules 2026-11-23, replacing 15.4's slash-list
   rendering for the shell): `DIV.pacing` holds two nowrap flex rows. Row 1
   `SPEED [1] [5] [20] [50] ticks per second` followed by the slack filler
   (`#fl-pacing-fill`);   row 2 (class `inforow pacingpanic`, separated by
   `--gap-related`) `PANIC` + `uncapped, no speed limit` + the toggle. Neither row wraps at any
   width the layout is measurable at (586px, Chrome's minimum window on this host, up to 1370px):
   labels, unit and hint are rigid (`white-space:nowrap`, `flex:0 0 auto`), the four choice cells
   are equal-width button cells (`flex:1 1 42px`, clamped 30..64px; 26px minimum below 700px), and
   the selected cell keeps the master's `SELECTED` wording in the markup and in
   `aria-pressed="true"` while the yellow fill is what paints the selection (`clip-path` hides the
   wording). Below 600px the PANIC hint text is dropped so a row's rigid cells cannot overflow.
10. **Vertical rhythm**: the shell's block gaps come from two tokens, `--gap-major: 12.8px`
    (between blocks) and `--gap-related: 8px` (inside a block or against a neighbour that
    belongs to it), so the measured gap between any two shell blocks is 13px or 8px.
11. **No titlebar**: the console renders no `.titlebar` (owner request 2026-11-23). 15.2 item 2's
    `MISSION nn//NAME` / `T+0000nnnn` header row is gone from the shell because both facts already
    render below it (`mhead` mission label, objline `tick n/limit` chip, status line `TICK n`); the
    master keeps that wording in `console_pair` and the mockups are unchanged. Consequently
    `--row-offset` was re-measured from 84px to 120px (see item 7a and `docs/WEB-W3-RECORD.md` 15).

The 15.7 rejection of "a second run/status bar under the monitor" is untouched: the status
line still occurs exactly once and stays read-only.

### 15.2b Dated shell deviation (2026-11-24, owner-directed, `docs/WEB-W3-RECORD.md` 17)

Owner review of the live console page (screenshot of the FIRMWARE LIBRARY section):

1. **No RELEASE STATUS block**: the console shell renders no `FL:> RELEASE STATUS` cta block
   (15.2 item 8 removed for the shell only). The release framing lives in the store listings and
   in the 15.1 golden master; the status4 strip, privacy block and footer keep their 15.2 slots.
2. **Compact library controls**: the firmware library chips (`SAVE CURRENT`, `IMPORT .ASM`,
   `DELETE LIBRARY`) are 30px chips at 12px font, the library entry buttons (`LOAD` / `DELETE`)
   are 26px at 12px font, and the selects are 12px, matching the console control scale. The
   49dp `pointer:coarse` gate covers all of them. The native file button (which renders in the
   OS language, e.g. "Datei auswählen") is visually hidden but focusable; the shell chip
   `IMPORT .ASM` on the label opens the picker.

### 15.3 Binding numeric rules (desktop, measured 1312px viewport)

| rule | value |
|---|---|
| page column / content width | 1276px / 1236px |
| pair grid | `minmax(0,1fr)` + `minmax(320px,1.1fr)`, 1.2rem gap (shell since 15.2a 7: two `minmax(0,1fr)` columns) |
| world grid | 21 rows x 20 columns of 15px cells (grid height 317px) (shell since 15.2a 7: half the body content width; since W3 record 16 the cell scales with the column as `cell = clamp((columnPx - 2) / 22, 11, 34)` written inline on the pair element (`--cell`, `--grid-h`), so the grid box `22 * cell + 2` x `21 * cell + 2` is the same term as its tracks and always fits inside its own column) |
| firmware pane height | 316px (`21 * 15 + 1`), scrolls internally (shell since 15.2a 7: `21 * --cell + 2px`, the same ratio at the anchor width) |
| flush pairing | grid bottom and pane bottom at the same y (measured delta 0px) |
| readout bands | full content width, one wrapping row of 12 cells, 132px tall each |
| band cell | flex basis 118px, padding 3px/6px, 4px gap, label 9.5px above value 13px |
| run buttons | 74-80 x 49px minimum height (house 48dp gate) (shell since 15.2a 8: 34px on desktop, 49px under `pointer: coarse`; pacing chips 28px / 49px) |
| TELEMETRY to status line | 13px gap, no element between them |
| no horizontal overflow | `scrollWidth == clientWidth` at 1312px and at 420px |

### 15.4 Single-occurrence rule

Each of these appears exactly once on the page:

| item | only place |
|---|---|
| `RUN` / `STEP` / `RESET`, `SPEED 1/5/20/50`, `PANIC`, share `LINK` with `COPY` and `DOWNLOAD .ASM` | the control block under the firmware pane (console shell since 15.2a: its control block carries `RUN`/`STEP`/`RESET` only; `SPEED` and `PANIC` moved to the pacing row in the firmware column, rendered as one nowrap line with four equal choice cells per 15.2a item 9, `LINK`/`COPY`/`DOWNLOAD .ASM` to the full-width block under TELEMETRY, each still exactly once) |
| symbol legend (wall, rover + facing, fire display bands 1..24 / 25..49 / 50..74 / 75..100, packet, refill, exit, civilian, fuel, protected asset, layer order) | under the grid, in the world column |
| state fingerprint | the status line |
| `HP` / `WATER` / `HEAT` / `SHIELD` | the status line (and as registers only, never as a `VITALS` row in TELEMETRY) |

There is no second run bar anywhere on the page; the status line is read-only.

### 15.5 Breakpoints

- `>900px`: pair side by side (this is the reference layout).
- `<=900px`: everything stacks to one column, grid cells 11px, pane height
  `21 * 11 + 1`px, bands keep full width and wrap.
- `<=640px`: band cells flex basis 100px.
- `<=900px` also collapses `cols`, `privacy` and the status line to one column,
  and the four-cell strip to two.

### 15.6 Layout acceptance for v1

Static gates are machine-checked: `python docs/web/verify_layout_v1.py` (checks
items 1, 3 and 4 below plus the copy rule against the golden master; exit code
0 means all pass). Geometry gates (2 and 5) are measured in a browser at a 1312px
and a 420px viewport with the method in §14.1.

A build matches this spec when, on the golden master's mission 02 / CORRIDOR /
tick 46 state with `CORRIDOR-REF.ASM`:

1. region order and band cell lists match 15.2 exactly;
2. the numeric locks in 15.3 hold within 2px at a 1312px viewport;
3. the single-occurrence table in 15.4 holds (grep or DOM query for duplicates);
4. exactly one element on the page has a vertical scrollbar and it is the
   firmware pane (no nested `pre.source`, which renders two scrollbars);
5. no horizontal overflow at 1312px and 420px, and no run control below 49px;
6. the palette passes the gates recorded in §14.2 (text >= 4.5, non-text >= 3.0,
   fire ramp monotone under protanopia and deuteranopia), with the documented
   empty-cell-dot exemption;
7. the console shell's CSS keeps the 15.2a items 7 and 8 (equal half columns, grid square
   at half the body width, pane height `21 * --cell + 2px`, unboxed pacing row, 49px touch
   override), checked by `shell_css_gate_for_15_2a()` of the same script.

### 15.7 Rejected designs (do not re-propose without an amendment)

- A horizontal strip of values without labels under the grid: the values lose
  their labels and cannot be read as a runout.
- A paragraph-style "what the ports mean" block: definitions must be label-first
  rows.
- A second run/status bar under the monitor: it duplicates the control block.
- A `VITALS` row inside TELEMETRY, a `MISSION` cell inside TELEMETRY, the symbol
  legend inside a readout band, and a `STATE FINGERPRINT` cell inside RF-8 STATE:
  all duplicate content that already has a home.
- Wrapping the source pane in a second `pre`: nested scrollable boxes.

### 15.8 Dated layout amendment (2026-11-25, step E4, `docs/EDGE-E4-RECORD.md`)

The grid gains a **thin edge wall** overlay: a bar painted ON a cell border, never a
cell, never a glyph (`PLAN-EDGE-WALLS.md` §3, §5; `DESIGN-FIRELINE.md` amendment A-6).
The rules are render-layer arithmetic over the measured cell token of 15.3, so the box
model of 15.3 does not move and the 400-cell gate is untouched:

- The engine's renderer copies are the only source: `wallVertRowMajor()` (20 x 19) then
  `wallHorizRowMajor()` (19 x 20), read row-major, vertical before horizontal. A
  wall-free mission emits no overlay node and its grid markup is byte-identical to the
  wall-free build.
- Overlay nodes are `<span class="wallseg v|h" data-edge="v5,2">` written after the 400
  cells and before `</div>`, positioned inline with `left`/`top` px from the pair's
  `--cell` (`cellLeftBorderPx`/`cellTopBorderPx` arithmetic), `--wall-stroke` from the
  same token. They add no grid track and no cell node: `grid-template-columns: repeat(22,
  var(--cell, 26px))` and the `22 * --cell + 2` x `21 * --cell + 2` box stay as written
  in 15.3.
- Stroke width: `cell * 0.14` rounded in two integer steps (half-up to a whole pixel,
  then nearest even, tie up), clamped to 2..4 px, handed to the overlay as the pair's
  inline `--wall-stroke`. A `pointer: coarse` media rule raises the painted floor to
  4 px for touch, the way 15.4 and 15.2b raise touch targets to 49dp:
  `.pair{--wall-stroke-touch:4px}` plus `.wallseg{--wall-stroke:var(--wall-stroke-touch,
  var(--wall-stroke))}`. The bump cannot be written on `.wallseg` reading its own
  `--wall-stroke`: a rule that references the custom property it declares is invalid at
  computed-value time, and the bar would keep the 2 px desktop width (measured,
  `EDGE-E4-RECORD.md` deviation 2). The legend bar follows the Android
  `EdgeWallGeometry` ratio under the same floor,
  `.legend .edgebar{height:max(4px, calc(var(--cell, 11px) * 0.185))}`. The web ratios
  match the Android recipe; the rounding differs because CSS cannot stroke a fraction
  (`EDGE-E4-RECORD.md` deviation 1).
- Legend: the block under the grid gains an 11th item, a drawn `<span class="edgebar">`
  plus `thin wall, blocks the way`, single occurrence (15.4) inside the grid region only.
- The overlay adds nothing to the readouts: telemetry and the runout keep their v1 rows
  (`PLAN-EDGE-WALLS.md` §7 E4 does not ask for a wall counter), and the layout's own
  identity (`layoutDigest`, `layoutVersion`, `edgeCount`, `wallVertRowMajor`/
  `wallHorizRowMajor`) stays engine-side, where the fixtures read it.

## 16. What would make this worth doing

Honest assessment: the web build is not required for the game to exist, and it
is the largest new surface in the roadmap. Its value is concentrated in one
place, the moment before installation. A person who writes 20 lines of RF-8 in
a browser tab, watches a rover fail to shoot the fire, and fixes it is already
playing the game; the Play listing then becomes the second step rather than the
first leap. If that onboarding value is the goal, the scope above is the minimum
credible version. If the goal is instead a broader distribution platform
(accounts, sharing, ladders), that is a different product decision and should be
taken deliberately, because it changes the privacy pillar the family is built on.
