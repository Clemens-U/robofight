# HANDOVER: FIRELINE app visual design (brainstorm + design doc)

Purpose of the new session: brainstorm and produce the VISUAL DESIGN of the
upcoming FIRELINE Android app (`:fireline-app`, Phase 5 of the RF-8 multi-app
platform). This is a design session, not an implementation session. Deliver a
design document plus mockups, not code. Do not push anything. Do not touch app
modules.

## 1. Where the project stands (verify with git at session start)

- Repo: `C:\Users\Clemens\Documents\github\robofight` (Windows, git-bash shell;
  pass native `C:/` forward-slash paths to native tools like git/gradle).
- `main` contains Phases 1-3 (platform spec, port catalog, pure `:rf8-core`).
- Phase 4 (`:fireline-engine`, the headless FIRELINE simulation engine + five
  authored missions, 127 tests green) is DONE on local branch
  `codex/fireline-engine` (tip `34b9fdd`, 10 milestone commits) but is NOT yet
  pushed or merged. That is intentional; leave it.
- Phase 5 will branch later and create `:fireline-app` as a NEW Android app
  module (`robofight.fireline`), independent from the Arena `:app`.

## 2. Read first (grounding, in this order)

1. `PLAN-RF8-MULTIAPP.md` §9 (Phase 5 scope: FILES / MISSION / RESULT screens,
   reuse policy, interoperability, gate). §8's DONE note records engine facts.
2. `FIRELINE-DESIGN.md` §11 "Android application design": already-binding
   decisions (see §4 below), especially 11.1 navigation, 11.2 editor,
   11.3 mission setup, 11.4 run screen (glyph/color table), 11.5 controls,
   11.6 results. Also §9 (the five missions' themes), §12 (persistence/sharing).
3. `DESIGN.md` (Arena's locked spec) for the house visual language: chunky
   8-bit text-grid presentation, near-black field (#161718 launcher background),
   pixel typeface.
4. `README.md`, `STORE-LISTINGS.md` briefly for brand tone ("Opcode Arena"
   family, "Designed by a human. Built with coding agents.").
5. `app/src/main/kotlin/robofight/android/ArenaView.kt` (how Arena actually
   renders its TextGrid on a Canvas: pixel typeface, glyph paints per cell,
   solid blocks for walls) as the visual reference implementation to diverge
   from or match.

## 3. Existing visual facts to build on (do not reinvent blindly)

- Both games share one RF-8 instruction set; FIRELINE ports 16-30 already have
  engine-side telemetry (fire dist/level, wind, civ count, water, heat, smoke).
- The engine emits a canonical event stream (`FIRE +25`, `CIVILIAN RESCUED`,
  `SHIELD ACTIVE`, `WATER EMPTY`, ...) that the run screen should feature.
- Engine mission set (ids 1-5): NOZZLE CHECK, CORRIDOR, FIRST RESPONSE,
  DOWNWIND, HOT ZONE. Mission feel ranges from single-cell drill to a
  shielded corridor crossing. Visual identity should carry "industrial
  firefighting terminal", not fantasy/firefighter imagery.
- FIRELINE world entities already have suggested glyphs/roles from
  FIRELINE-DESIGN §11.4: `R` rover + facing nose (phosphor green/white),
  fire intensity glyphs `1..4` (graduated flame color), `*` water packet
  (cyan), `C` civilian (white), `E` exit (bright green), `W` refill (cyan),
  `F` fuel (amber), `P` protected asset (violet/white), `#`/solid wall.
- Hard accessibility rule from the design doc: never rely on color alone;
  glyph and intensity must stay readable in grayscale and for color-vision
  deficiencies. Any proposed palette must pass this test explicitly.
- No touch-to-control: the only interactions are editing code, choosing a
  mission/firmware, and RUN / STEP / RESET. The design brief is about
  legibility, telemetry, and vibe, not action-game feedback loops.

## 4. What is already DECIDED (constraints, not open questions)

- Separate app, must install side-by-side with both Arena packages.
- Three owned surfaces only: FILES, MISSION, RESULT.
- Primary grid stays a "chunky ASCII/text presentation" (11.4).
- STEP = exactly one world tick including multi-tick VM state (visual state
  must be able to represent in-progress MOVE/SHOOT/SHIELD).
- Firmware files are plain `.asm`, shared via Android document/share flows.
- No dependency on Arena `:app`; do not copy the Arena MainActivity.
- No product flavors for v1; offline/no-network posture.

## 5. What to brainstorm (the actual open design space)

Produce a design brief and then a concrete proposal covering:

1. **Brand identity**: FIRELINE is a codename. Explore working names/tag
   treatment, app icon concept (e.g. `R` + flame glyph on near-black),
   launcher label options, and how FIRELINE visually sits "between" the two
   Arena packages: same family DNA, clearly different job.
2. **Palette**: build a full token palette on the near-black #161718 base:
   fire ramp (intensity 1-4, amber to red-white), water cyan, phosphor green
   for rover/success, amber fuel, violet protected, plus UI chrome (panel
   borders, selection, disabled states). Provide grayscale fallback mapping
   and a CVD-safe verification table. Consider whether FIRELINE leans warmer
   (fire) than Arena's phosphor-green field as the differentiator.
3. **Typography**: pixel typeface choice for grid cells vs readable mono for
   the editor and telemetry (maybe two faces: display vs work). Cell density:
   how big must a 20x20 grid + telemetry be on a medium phone (test target:
   emulator-5554 Medium Phone)? Line height, glyph size ratios (Arena uses
   ~0.5-0.75 of cell size), labels above cells for entities with variants.
4. **Grid rendering language**: how fire reads visually (glyph digit + color
   ramp? animated flicker acceptable since it is cosmetic, must not imply
   state), how a facing rover nose reads, how water packets read in flight,
   smoke as dimming/texture (engine smoke is informational; visual smoke must
   never hide entities), shield as a state ring/overlay, heat shown without
   clutter.
5. **Screen layouts** (sketch all three, portrait first, note small-screen
   behavior):
   - FILES: list/cards of .asm sources, import/export affordances.
   - MISSION: mission picker (five authored missions for v1; think ahead for
     mission codes from §12.3), firmware picker, RUN/STEP/RESET control bar,
     grid + telemetry panel + event ticker arrangement.
   - RESULT: outcome banner (SUCCESS/FAILURE reason), the §7.5 stat block
     (ticks, HP, water, civilians, burning cells, hash short form), "why did
     I fail" affordance (last events), routes back to editor/retry.
6. **Editor treatment**: line numbers, PC highlight, mnemonic coloring that
   degrades gracefully in grayscale, disasm/error presentation.
7. **Motion**: what may animate (flame flicker, packet travel already tick-
   driven, event ticker) vs what must be static (grid state fidelity; no
   animation may imply uncomputed state). Respect "background/rotation must
   not corrupt the sim" in visual choices (no animation-driven state).
8. **Accessibility & density**: font scaling, grayscale mode, CVD palette
   check, contrast ratios on near-black, target sizes for RUN/STEP/RESET.
9. **Icon & store presence**: launcher icon, screenshot composition for a
   later store listing (keep the honest "designed by a human" tone).

## 6. Deliverables of the new session

- `DESIGN-FIRELINE.md` at repo root: locked decisions + rationale, palette
  tokens (hex table), type specs, per-screen ASCII/annotated wireframes,
  glyph table honoring §11.4 with any refinements flagged as amendments to
  FIRELINE-DESIGN.md (do not silently contradict it; propose amendments
  explicitly), open questions left for Phase 5 implementation.
- At least 2 DIFFERENT visual mockups (self-contained HTML/CSS files, dark
  theme built from the proposed tokens), saved under `docs/mockups/` and
  delivered so they can be judged on-screen. The mockups must present
  genuinely distinct visual directions (for example: a phosphor-terminal
  look close to Arena's house style vs. a warmer industrial/firefighting
  treatment), each showing the MISSION run screen with grid, telemetry and
  event ticker, so the final identity is chosen by comparison, not by
  default. A third variant is welcome if it clarifies the decision.
- Commit nothing to `main`; a local commit on a `docs/`-only branch is fine
  if the user asks. NO PUSH.

## 7. Ground rules for the new session

- Respond in English. No em dashes or en dashes in any user-facing/published
  copy (rewrite with commas, colons, periods).
- Design proposals must respect §4 constraints; when an idea collides with
  FIRELINE-DESIGN.md or PLAN §9, say so and propose an amendment rather than
  drifting.
- Keep context lean: read the listed docs selectively (sections, not whole
  files where possible); mockup HTML in small self-contained files.
- Useful local assets: `robofight-android-ui` skill (emulator checks),
  `emulator-5554` (Medium Phone) if running, `architecture-diagram` or
  `p5js` skills only if they genuinely help; plain HTML/CSS mockups are the
  expected medium.
- Facts about the engine (ports, telemetry values, events, mission list) are
  authoritative from `FIRELINE-DESIGN.md` and `fireline-engine/` sources, not
  from imagination; verify a value before drawing it into UI.

## 8. Suggested opening line for the new chat

"Read HANDOVER-FIRELINE-VISUAL.md in C:\Users\Clemens\Documents\github\robofight
and run the design brainstorm it describes. Start with the brand identity and
palette section, show me options before committing to DESIGN-FIRELINE.md."
