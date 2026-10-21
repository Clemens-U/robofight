# RoboFight R3 build handover (Stage 4 → release `0.6-codes`, versionCode 7)

**Mission.** Build RoboFight **R3 — "full v1"**: the challenge **builder** (make a
challenge from named modes + dials + pinned opponent), the **SEND BACK** response flow
(spec §8: winning firmware pinned in slot B, open slot A, recorded result remapped so
the pinned slot is the winner), **share** (`ACTION_SEND` text/plain + copy to
clipboard), **local history** (per-code P/W/L/D + per-arena win counts), and the version
bump to `versionCode 7` / `0.5-codes` → `0.6-codes`. Stages 1–3 (arena, codec, import)
are already implemented; R3 is the last release on the v1 track.

**Repo.** `C:\Users\Clemens\Documents\github\robofight` — Android, **pure Kotlin, NO
LibGDX, NO Gradle at build time**. `engine/` = pure JVM (ISA/assembler/VM/world +
`robofight/challenge/ChallengeFormat.kt` + `robofight/disasm/Disasm.kt`). `app/` = pure
Android (programmatic UI only).

**Authoritative docs (read these first):**
- `PLAN-CHALLENGE-CODES.md` — implementation plan. **Stage 4 = R3** (spec at lines
  244–275). Also the "Locked v1 decisions" section near the top.
- `CHALLENGE-CODES.md` — locked format spec (fields §4, modes §6, response §8, display
  policy §4.1, error strings §10, validation §11).
- `HANDOVER-R2.md` — prior release context (import/preview/playback/save).
- `MOCKUP-CHALLENGE-R2.html` — visual concept; R3 builder/replay follow the same
  phosphor/terminal look, `Press Start 2P`, and reuse `ArenaView`.

---

## STATUS — 2026-10-20 (R3 code complete; headless ALL GREEN; device smoke PASSING through response flow)

**Code + verification done, NOT yet committed** (working tree, per working agreement):
- `app/build.gradle.kts` + `app/src/main/AndroidManifest.xml` — version bump to
  `versionCode 7` / `0.6-codes`.
- `app/.../MainActivity.kt` (+505) — builder screen, SEND BACK flow,
  `shareCode` (chooser "SHARE CHALLENGE CODE"), `copyCode` (clipboard), history row.
- `app/.../BotFiles.kt` (+85) — history persistence: per-code records + per-arena
  win counts, loaded/saved with the `codes` table.
- **NEW** `engine/.../challenge/ChallengeResponse.kt` (90) — `build()` (pin winning
  firmware in B, open A, same rules, result remapped so the pinned slot wins),
  `isPlayerWin()`. Codec constant is `ChallengeFormat.MAX_SRC` (2048) — **not**
  `MAX_SOURCE` (early subagent hit that compile error; fixed).
- **NEW** `engine/src/test/kotlin/robofight/ResponseChainTest.kt` — two-hop
  response-chain probes.
- **NEW** `run-headless-tests.sh` — the headless test runner (compile engine+tests,
  run all suites).

**Headless — ALL SUITES GREEN, 293 passing lines, 0 failures**
(`build-headless-r3.log`):
- Codec `ChallengeFormat` suite: **123 passed, 0 failed**.
- **`ResponseChainTest: 6/6 PASS`** — `coldChallengeRoundTrips`,
  `hop1IsDeterministicPlayerWin`, `responseRemapsResultAndRoundTrips`,
  `hop2IsDeterministicPlayerWin`, `secondResponseBuilds`,
  `noResponseWithoutPlayerWin`. Full chain across CROSS (SNAKE↔WALKER) and MAZE
  (HUNTER vs WALKER, SNAKE↔CHAOS) arenas.
- Arena: 250 deterministic fights (KO=176, timed-out=74, all under the 2000-tick
  budget). Presets all assemble: HUNTER 27 inst (81 B), TURTLE 14, SNAKE 20, CHAOS 17,
  WALKER 12.

**APK.** `opcode-arena.apk` (v7 / `0.6-codes`, 3.3 MB, signed v2+v3) builds offline via
`bash build-android.sh` (last build log: `build-apk-r3b.log`, includes the banner fix
below). Installed on `emulator-5554`.

**Device smoke — PASSING on emulator-5554** (this session):
1. **Launch** — banner `OPCODE ARENA OS 0.6 // RF-8 COMBAT SYSTEM`.
   (Banner was **stale at `OS 0.5`** — hardcoded in `MainActivity.kt:754`; fixed and
   rebuilt during the smoke. If you bump the version again, remember this line.)
2. **`[ CODES ]` tab** — paste box + `PASTE / IMPORT / CLEAR / BUILD` row.
3. **Builder** (`BUILD`) — named-mode row (DUEL/MELEE/SIEGE/GAMBIT/CUSTOM), dials
   (MODE, SPAWN, HP A/B, ARENA), seed field + `RND`, `OPPONENT` picker,
   `SRC <n> B ≤ 2048 // EMBEDS SOURCE` note, action row
   `BUILD CODE / SHARE / COPY / < CODES`.
4. **Dials** — cycled ARENA → MAZE (4 taps) and OPPONENT → WALKER (4 taps); labels
   update live. Slot order = presets in `Presets.all()` order (HUNTER, TURTLE, SNAKE,
   CHAOS, WALKER) **then** user files in name order.
5. **BUILD CODE** — emits a canonical `RF1-…` code and returns to the CODES screen:
   `CODE OK // CHALLENGE`, `B ▸ HIDDEN · 36 B` (WALKER 12 inst × 3 B — correct),
   open-slot picker `A HUNTER >`.
6. **RUN** — deterministic duel, result `WINNER: HUNTER` (KO, B HP 0) — matches the
   headless expectation exactly.
7. **SEND BACK unlocked** after the win (hidden until a player win — spec §8).
8. **Local history row** — `CODE 1P 1W 0L 0D // ARENAS MAZE 1/1`.
9. **SEND BACK** — counter-challenge generated: `RECORDED: B WINS · T+835 · HP 0`
   (T+835 = the probe's exact tick count), `B ▸ HIDDEN · 81 B` (HUNTER 27 × 3 —
   correct), rules preserved (KO · SEPARATE · 100/100, MAZE), slot A open.
10. **Share sheet fires** — `ACTION_SEND` chooser came to focus
    (`com.android.intentresolver.ChooserActivityLauncher`); "straight to share" holds.
11. **After dismissing the chooser** the counter-challenge stays imported (new
    `RF1-…` code in the box, `CODE OK // CHALLENGE`) — ready for a second player.

**Remaining smoke (optional, small):**
- `COPY` → paste box `PASTE` re-import round-trip (clipboard path).
- `SAVE` the counter-challenge to a `.chc`.
- Cycle the open-slot A picker to a different bot and confirm a second SEND BACK
  embeds *that* firmware (headless `hop2IsDeterministicPlayerWin` covers the logic).
- SEND BACK absent on a loss/draw fight (headless `noResponseWithoutPlayerWin`
  covers the logic).
- Named modes DUEL/MELEE/SIEGE/GAMBIT (only CUSTOM dials were exercised on-device).

**Out of scope (deferred, by design):**
- **QR** — no offline ZXing available; a self-contained QR encoder is a follow-up, not
  a v1 gate.
- **Stage 5 gating scaffolding** (Pro track) — untouched; no release yet.

---

## Pitfalls / environment notes (learned this session)
- **`python3` is NOT on PATH** on this host (Windows Store stub). Use `python`
  (3.14.7) or the agent's `execute_code` tool for XML/JSON parsing.
- **`com.example.myapplication`** — a stale ~3-month-old dev build of this app was
  installed on emulator-5554 and **contaminated `uiautomator` dumps** (it was the
  source of the earlier subagent's false "missing action row" investigation — there
  was no UI bug; the action row renders fine at y≈1355). It has been uninstalled.
- `HUNTER.asm` (1448 B file in DIR) ≠ `HUNTER` preset source (1061 B) — they are two
  separate builder slots; not a bug.
- R2 note still applies: the ADB `am start --es` harness cannot deliver string extras
  on this build, so the inbound share-receive path can't be ADB-driven — but the
  outbound chooser is verified, and manual paste/import is the proven re-entry path.
- Build recipe: see the `robofight-android-build` skill and `HANDOVER-R2.md`;
  `bash build-android.sh` from repo root; `run-headless-tests.sh` for the suites.
  Pass `C:/` native paths to native tools (never `/c/` MSYS paths).

## For the next session (in order)
1. Optional: finish the remaining device smoke bullets above.
2. Write nothing new — this file is the handover. Suggested commit message:
   `feat(android): R3 — challenge builder, SEND BACK response, share/copy, history (0.6-codes)`.
   Stage: new `ChallengeResponse.kt`, `ResponseChainTest.kt`, `run-headless-tests.sh`.
3. QR encoder (self-contained) and Stage 5 gating are the next work items.
