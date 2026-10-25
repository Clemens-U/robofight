# Store Listings — Opcode Arena (free) / Opcode Arena: The Circuit (paid)

Play Console listing copy per PLAN-PRO §1.2 / §7. Two distinct listings from
one codebase (product flavors `free` / `pro`; same HEAD, tags `free-0.7`,
`pro-1.0`). Matching upload-ready artwork lives in `assets/store/release/`.

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

The five arenas are the training floors — just the beginning.
A new arena every fight in Opcode Arena: The Circuit.
```

### Category / content rating
- Category: Game (Puzzle / Strategy)
- Content rating: Everyone

---

## 2. Opcode Arena: The Circuit (paid; internal codename `pro`)

- **Package:** `robofight.android.pro` (flavor `pro`; `applicationIdSuffix .pro`,
  independent paid-app version sequence)
- **App name:** `Opcode Arena: The Circuit` (25 chars ≤ 30 cap)
- **Release tag / version:** `pro-1.0` (versionCode 1, versionName `1.0`)

### Short description (≤ 80 chars)
> Everything in Opcode Arena — plus a new arena every fight.

### Full description (skeleton)
```
Five arenas train you. The Circuit is where training begins for real.

Every challenge code carries a seed, and a seed builds a hall: Circuit
fights run in arenas generated deterministically for that fight.
And the Circuit goes deeper than arenas. More is coming below them.

The complete game: firmware editor, deterministic fights, challenge codes.
Plus Circuit Arena Packs:

- Arena Pack 1: two procedural, seed-derived arena families (GEN-MAZE,
  GEN-ISLES — every code's seed generates the map) and four curated
  arenas (0x12–0x15)
- Challenge codes from PRO arenas run fully in PRO; free-app players can
  import them as locked previews and see the arena they are missing.

€1.99 one-time. No ads. No subscriptions. No in-app purchases. No accounts.

Five arenas free. A new arena every fight — in The Circuit.
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

- Shared icon composition, distinct tier color: free uses
  `assets/store/opcode-arena-icon-base-512.png`; The Circuit uses
  `assets/store/opcode-arena-icon-circuit-512.png`, with only the robot changed
  to amber and the surrounding arena kept green. Android Pro launcher assets
  live in `app/src/pro/res` as a flavor overlay; shared/free resources remain
  unchanged.
- Release-ready screenshot sets are in `assets/store/release/free` and
  `assets/store/release/circuit`. The Circuit set is captured from the Pro
  flavor and shows its amber identity, migration path, Arena Pack 1,
  procedural combat, and challenge builder.

## 5. Release / tagging convention

- Single repo, single `main` branch; both apps built from the same HEAD
  (CI: `.github/workflows/ci.yml` builds engine tests, both flavor unit tests,
  both debug APKs, and both unsigned release bundles).
- Tags: `free-0.7`, `pro-1.0` (flavor-prefixed). Store uploads:
  `app/build/outputs/bundle/freeRelease/app-free-release.aab` → free listing;
  `app/build/outputs/bundle/proRelease/app-pro-release.aab` → paid listing.
- Wire format is shared and frozen: `ENGINE_VERSION = 1` in both variants
  (asserted per flavor by `TierParityTest`).
