# PLAN-PRO — Roadmap to the Paid Tier

> Status: **plan** (decisions locked 2026-10-22). Defines the free/paid split,
> the two-app architecture, the Pro v1 scope, and the milestone order.
> Related specs: `CHALLENGE-CODES.md` (v1, locked — §9 gating is amended by
> §4 below), `DESIGN.md`, `GAMEPLAY-IDEAS.md`.

---

## 0. Locked decisions

| Area | Decision |
|------|----------|
| Free tier | **Frozen at the 0.6-codes feature set.** No new arenas, modes, or content ships to the free app. Maintenance continues: bug fixes, performance, editor/QoL, and shared engine mechanics. |
| Paid tier | **A separate app with its own store entry** — store name **"Opcode Arena: The Circuit"** (locked 2026-10-23; internal flavor/codename stays `pro`). No in-app purchase, no Play Billing integration, no server, no accounts: the Play purchase *is* the license. Positioning (owner direction, 2026-10-23): the paid version **slowly moves away from a battle-only focus and targets fight / collect / riddles content** — reflected in UI copy (no "COMBAT" wording in pro strings) and in the §7 saga direction. |
| Lore | The story lives **only in the paid app and its listing**. Free app: lore-neutral (max one cross-sell line in the free *listing*, nothing in the free APK). The escape saga ("The Circuit" grows into it) is **post-P4** — see GAMEPLAY-IDEAS §7. |
| Repo & branches | **Single repo, single `main` branch for both apps.** One source tree, two build variants, two store entries; each APK cut is tagged (e.g. `free-0.7`, `pro-1.0`). Branch-per-app is excluded — it *is* the update-drift risk §7 guards against. |
| Price | **€1.99 launch price**, as a Play price step. Price follows content: step up (€3.99, then €4.99) when campaign/survival land. Buyers at any price keep all future updates (grandfathered), which doubles as the early-bird incentive. |
| Mechanics | **Shared in both apps.** One engine, one ISA, one physics, one `eng` version. Codes interoperate between the apps. The paid app differentiates **only** through content and modes. |
| Pro v1 scope | **Arena pack only** — procedural (seed-derived) arenas + curated arenas at `layout` ids `0x10+`. No challenge-code wire change. Traps, hazards, campaign, survival are later phases (§7). |

---

## 1. Two-app architecture

### 1.1 One repo, one branch, two builds

Single repo, **single `main` branch** (locked, §0): both APKs are cut from
the same HEAD, distinguished only by the build variant and release tags.
`engine/` stays shared and dependency-free. The `app/` module
builds twice from the same sources:

- **Free build** (`app`, current): strips the Pro layout-generator registry
  entries (§4.2). Everything else identical.
- **Pro build** (`app` with the full registry): free content + Pro arenas.

Mechanically: a compile-time `PRO_BUILD` flag (or two thin application
modules sharing all sources). Both variants are produced by the same Gradle
project and the same offline `build-android.sh` recipe (two APK outputs).

**Hard rules:**

1. **One release train.** Every engine/ISA/`eng` change ships to *both*
   listings in the same release. Divergent `eng` values between the apps
   break cross-app code interoperability — CI asserts the apps'
   `ChallengeFormat.ENGINE_VERSION` are equal.
2. **Shared mechanics only.** The Pro build may gate *content registry
   entries*, never a sensor, opcode, knob, or physics rule. A bot written in
   the free app runs identically in the Pro app, and vice versa.

### 1.2 Store & cross-sell

- Listings: **Opcode Arena** (free) → **Opcode Arena: The Circuit** (€1.99).
  Distinct names, distinct icons (Circuit sigil vs plain opcode mark), shared
  screenshots lineage. `Opcode Arena PRO` remains the internal codename.
- Cross-sell in the free app is *tasteful and non-modal*: one permanent
  discreet entry (e.g. a `PRO` line on the CODES screen footer) plus a
  context link when a Pro-layout code is previewed (§4.3). No ads, no nag
  dialogs — consistent with the repo's tone.
- No upsell flow, no restore-purchase UI: a Play install of the paid app is
  the entitlement.

### 1.3 Free → Pro migration

Upgrading users keep their work via the existing export path:

1. Free app: `EXPORT` → the ZIP with firmware (+ saved codes, `.chc`).
2. Pro app: import the ZIP (firmware store + read-only code store).

Build a first-run "MIGRATE FROM FREE" hint in the Pro app pointing at this
path. No cloud, no accounts.

---

## 2. What stays free (forever)

- The entire challenge-code mechanism: build, share, import, preview,
  playback, SEND BACK chains, local history — for all free↔free content.
- The five free arenas (`0x00–0x04`), all v1 dials and named modes.
- All current and future *mechanics*: sensors, ISA, combat rules, RNG.
- Bug fixes, performance, editor/QoL, execution-monitor improvements.

The `0x05–0x0F` "future free arenas" registry range stays **reserved but
unused** (community/fork headroom, per CHALLENGE-CODES §7.1); the official
roadmap puts new content at `0x10+`.

---

## 3. Shared-mechanics policy (the interoperability contract)

Because both apps share one dialect:

- A free-authored code plays in the Pro app unchanged.
- A Pro-authored code (Pro arena) *previews* in the free app (§4.3) but
  cannot be answered there — by design, Pro challenges live in the Pro
  ecosystem; free↔free challenges never touch Pro content.
- Mechanics updates (e.g. the **THREAT sensor** from GAMEPLAY-IDEAS #1)
  ship **free to both apps**: they raise the ceiling for everyone and keep
  every old code replayable in every app, forever. `eng` bumps per
  CHALLENGE-CODES §5, applied to both listings in lockstep.

---

## 4. Amendment to CHALLENGE-CODES §9 (two-app gating model)

Wire format: **unchanged** — `layout` ids `0x10–0x1F` remain the Pro range,
v1 codes keep their meaning. Only the *gating model* changes, which is the
migration path the spec already anticipated (§9.1 migration note). This
section is a spec-amendment checklist for the P0 PR (§6):

1. **§9.1 soft gate → replaced by the two-app model.** Entitlement checks
   disappear; the *build variant* is the gate. The free build does not
   contain Pro generators (hard gate).
2. **§9.2 import table**, revised rows:
   - Pro layout (`0x10+`) + free build → **preview from metadata only**:
     arena name, rule knobs, pinned opponent firmware/source (all carried in
     the code), with the arena itself rendered as a labelled placeholder
     ("PRO ARENA — preview unavailable"), plus a Play link to the Pro app.
   - Pro layout + Pro build → full playback and response.
3. **§10 error string**: `ARENA NEEDS PRO — UNLOCK TO PLAY` becomes
   `ARENA NEEDS PRO — PLAY IN THE PRO APP`.
4. **§9.3 "never gated" list stands** with one clarification: parsing,
   previewing metadata, disassembling, and local history are always free;
   *playback* of `0x10+` content only happens in the Pro app.
5. **§13 clarification** (see §5.2): Pro v1 procedural arenas are
   **seed-derived** (`geometry = f(layout_id, fight_seed)`), so they need no
   layout parameter bytes and stay fully v1. The "v2 layout class with
   parameter bytes" row remains reserved for future *custom-parameter*
   arenas.

---

## 5. Pro v1: Arena Pack 1

### 5.1 Content

- **2 procedural generator families** registered at `0x10` and `0x11`
  (working names `GEN-MAZE`, `GEN-ISLES`): geometry derived deterministically
  from `(id, seed)` — the same fight seed reproduces the same arena.
- **3–4 curated arenas** at `0x12–0x15`: hand-tuned loop arenas, denser and
  meaner than the free five (MAZE remains the free ceiling; the Pro curated
  set goes beyond it).
- Builder menu (Pro build only) shows a `PRO ARENAS` section; free builder
  unchanged.

### 5.2 Procedural generation rules (determinism + invariants)

- Pure function: `generate(id, seed) -> Layout`. No clock, no hidden RNG.
- Deterministic repair: if a generated layout fails the §7.2 invariants,
  re-derive with a fixed seed-remap sequence until valid (still a pure
  function of `(id, seed)`).
- Symmetry by construction: generate one half of the board, mirror point-wise.
- Connectivity/no-dead-ends by construction: loop-based carving (nested
  rings, gated corridors ≥ 2 wide — no recursive dead-end maze algorithms).
- **The no-dead-end invariant holds for every Pro v1 arena.** Traps are
  deliberately *out* of scope: a dead-end arena authored in KO mode could
  stall to the 1M-tick cap, and the format cannot constrain which modes an
  arena is played in. Trap content ships only once a hazard/exit mechanic
  exists that resolves stalls (Pro v2, §7).
- Every generator and curated arena passes the §12.1 property checks and the
  §12.3 fight matrix (250 fights per arena, ≤ 2,000-tick budget) in CI.

### 5.3 Explicitly out of Pro v1

Traps/hazards, weapons, tournament, campaign, survival, procedural *params*
(v2 format). Each has a reserved landing spot in GAMEPLAY-IDEAS and
CHALLENGE-CODES §13 — nothing here forecloses them.

---

## 6. Milestones

| # | Milestone | Deliverables |
|---|-----------|--------------|
| **P0** | Spec amendment ✅ (2026-10-23) | CHALLENGE-CODES §9/§10 two-app gating revision + §13 seed-derived note — applied as **amendment A1** (§7.1/§7.4/§9/§10/§11/§13 + amendment log; v1 wire tables untouched). |
| **P1** | Build split ✅ (2026-10-23) | One source → free + Pro APKs (`app-free-debug.apk` `robofight.android` / `app-pro-debug.apk` `robofight.android.pro`); tier gate = flavor source sets (`src/free` no-op vs `src/pro` `ProArenas`) + `Arena.registerPro` (0x10–0x1F hard range); gate proof `RegistryGateTest` in `:engine:test`; CI (`.github/workflows/ci.yml`) builds both variants + `TierParityTest` asserts `ENGINE_VERSION`/`PRO_BUILD` per flavor; `STORE-LISTINGS.md` stubs. |
| **P2** | Arena Pack 1 ✅ (2026-10-23) | `GEN-MAZE`, `GEN-ISLES` generators + curated `COLISEUM`/`PINWHEEL`/`TWIN WALLS`/`LABYRINTH` (`0x12–0x15`); pack suite `ProArenaPackTest` 40/40 (invariants + seed diversity ≥971/1000) via `:app:testProDebugUnitTest` + CI; Pro picker = header cycle chip `ARENA PACK 1: <name> >` (variant B — no extra rows, seed-derived previews), on-device verified; free flavor unchanged. |
| **P3** | Cross-app UX ✅ (2026-10-24) | Free app: locked pro-code preview — arena name via engine display-only `Arena.proArenaNames` (0x10–0x15), placeholder `PRO ARENA — PREVIEW UNAVAILABLE`, pinned opponent firmware as `Disasm` listing (locked codes only; free↔free secrecy unchanged), `PLAY IN THE CIRCUIT >` link (market + https fallback). Pro app: `IMPORT` ZIP via `ImportZip` (zip-slip + size caps, strict UTF-8), read-only imported-codes store (db v5 `imported` flag), one-time `MIGRATE FROM FREE` SHELL card; free `EXPORT` now carries saved `.chc` codes. Tests: `ProArenaNamesTest` (17 checks), `ImportZipTest` (5). On-device UI check pending device. |
| **P4** | Launch | Free **0.7** (maintenance) + **The Circuit 1.0 at €1.99** on both listings. Store copy hook: *"Five arenas free. A new arena every fight — in The Circuit."* |

Post-launch roadmap (order, not scope-locked): the shared **"Walls Down"
mechanics pack** (THREAT sensor + exit/gate/item/hazard tiles — free to both
apps, `eng` bump) → challenge-codes **v2** (gauntlet/score semantics, custom
round lengths, slot scheme, layout params) → tournament mode → trap arenas →
the paid **saga chapters** (the escape story under The Circuit — see
GAMEPLAY-IDEAS §7; campaign + survival waves land as its chapters) → price
step €3.99 → €4.99. Nothing in this roadmap touches P2–P4 scope.

---

## 7. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Update drift between the two apps breaks code compatibility. | One release train, shared sources, CI equality check on `ENGINE_VERSION` (§1.1). |
| Free tier perceived as "dead". | Mechanics/QoL updates keep shipping to both apps with a visible shared changelog; the PRO line in the free app is discreet, not nagging. |
| €1.99 reads as low-value. | Price follows content: launch price is honest for an arena pack; planned steps with grandfathering make early purchase the best deal. |
| Two similar listings flagged by Play. | Distinct names/icons/short descriptions ("free version" vs "full version + arena pack"); common, policy-compliant pattern. |
| Paid app piracy (no server, no DRM). | Play install is the license; acceptable at this scale — no extra DRM; the product's value is ongoing content + chain ecosystem, not a one-time asset. |
| Pro-only content fragments the challenge community. | Free↔free loop untouched and complete; Pro challenges preview-link in the free app act as advertisement, not taunt (preview shows the fight setup, invites the answer in PRO). |

---

## 8. Success measures (first 90 days post-P4)

- Free→PRO conversion rate (Play Console funnel: listing views → installs
  of PRO; cross-link taps from the free app if measurable).
- Challenge chains per week (free tier health — the funnel's engine).
- PRO retention: fights per week in the Pro app, arena-pack utilization
  spread (are the procedural arenas actually played?).
- Review sentiment on the free listing (the cross-sell must not sour it).
