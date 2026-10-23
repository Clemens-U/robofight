# Store Listings — Opcode Arena (free) / Opcode Arena PRO

Play Console listing stubs per PLAN-PRO §1.2 / §7. Two distinct listings from
one codebase (product flavors `free` / `pro`; same HEAD, tags `free-0.7`,
`pro-1.0`). Paste-ready skeleton; a human fills the final marketing polish.

---

## 1. Opcode Arena (free)

- **Package:** `robofight.android` (unchanged listing; the existing 0.6-codes
  app continues on this package)
- **App name:** `Opcode Arena`
- **Release tag / version:** `free-0.7` (versionCode 7, versionName `0.7`)

### Short description (≤ 80 chars)
> Write 8-bit bot firmware. Fight in five arenas. Share fight codes.

### Full description (skeleton — feature set frozen at 0.6-codes + challenge codes loop)
```
Program bots in an 8-bit firmware language and fight them in a 20×20 arena.

- Editor + assembler for the RF-8 firmware (3-byte instructions, 28 opcodes)
- Five built-in arenas: OPEN, BLOCKS, RING, CROSS, MAZE
- Deterministic fights: same code, same fight — down to the last tick
- Challenge codes: copy a fight as an RF1- code, paste it, run the exact
  same fight on another phone
- Replay codes carry the outcome: beat a friend's code and prove it
- 5 preset bots (HUNTER, TURTLE, …) plus your own
- No ads. No accounts. No tracking.

A new arena every fight — in Opcode Arena PRO.
```

### Category / content rating
- Category: Game (Puzzle / Strategy)
- Content rating: Everyone

---

## 2. Opcode Arena PRO

- **Package:** `robofight.android.pro` (flavor `pro`; `applicationIdSuffix .pro`,
  versionName suffix `-pro`)
- **App name:** `Opcode Arena PRO`
- **Release tag / version:** `pro-1.0`

### Short description (≤ 80 chars)
> Everything in Opcode Arena — plus a new arena every fight.

### Full description (skeleton)
```
Everything in Opcode Arena, plus the arenas.

The complete game: firmware editor, deterministic fights, challenge codes.
Plus PRO Arena Packs:

- Arena Pack 1: two procedural, seed-derived arena families (GEN-MAZE,
  GEN-ISLES — every code's seed generates the map) and four curated
  arenas (0x12–0x15)
- Challenge codes from PRO arenas run fully in PRO; free-app players can
  import them as locked previews and see the arena they are missing.

€1.99 one-time. No ads. No subscriptions. No in-app purchases. No accounts.

Five arenas free. A new arena every fight — PRO.
```

### Pricing
- €1.99 one-time purchase (paid app, no IAP). Price point per PLAN-PRO §1.2.

---

## 3. Distinct-listing compliance (Google Play "free version" policy)

- The free app ships **only** the five free generators and **no** Pro
  generators (hard compile-time gate: flavor source sets — the free APK
  contains no Pro arena code at all; Pro ids 0x10–0x1F parse as
  importable-but-locked previews: "ARENA NEEDS PRO — PLAY IN THE PRO APP").
- The PRO listing must NOT describe itself as a mere unlock: it is the
  **full version + Arena Packs**, with its own store copy, icon lineage, and
  feature claims (procedural arenas the free app can never run).
- Both listings cross-reference each other ("free version" / "full version")
  in the full descriptions to satisfy the distinct-apps policy.

## 4. Icon / screenshots lineage

- Shared icon lineage: same `ic_launcher` family both flavors (store
  distinctness comes from name + screenshots). If Play review asks, fork the
  PRO icon in `app/src/pro/res` (flavor res overlay — no shared changes).
- Screenshot sets: free set shows the five arenas; PRO set adds Arena Pack 1
  shots (procedural GEN-MAZE/GEN-ISLES previews) once P2 lands.

## 5. Release / tagging convention

- Single repo, single `main` branch; both APKs built from the same HEAD
  (CI: `.github/workflows/ci.yml` builds engine tests + both flavor unit
  tests + `assembleFreeDebug` + `assembleProDebug` and asserts both APKs).
- Tags: `free-0.7`, `pro-1.0` (flavor-prefixed). Store uploads:
  `app/build/outputs/apk/free/release/…` → free listing;
  `app/build/outputs/apk/pro/release/…` → PRO listing.
- Wire format is shared and frozen: `ENGINE_VERSION = 1` in both variants
  (asserted per flavor by `TierParityTest`).
